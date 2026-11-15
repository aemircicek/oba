package com.emirchat.server;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Registration and login rules. */
final class AccountService {
    static final Pattern USERNAME = Pattern.compile("[a-z0-9_.]{3,20}");
    static final int MIN_PASSWORD = 8;
    static final int MAX_PASSWORD = 128;
    /** Registrations (that reach the password hash) per address and hour. */
    static final int REGISTRATIONS_PER_HOUR = 10;
    private static final String GENERIC_LOGIN_ERROR = "Wrong username or password.";

    record LoginResult(Database.User user, boolean newDevice) {}

    private final Database db;
    private final PasswordHasher hasher;
    private final RateLimiter perUsername;
    private final RateLimiter perAddress;
    private final RateLimiter registrations;
    /**
     * Password hashing is deliberately slow, and a flood of logins or registrations must not take
     * every processor: at most this many hashes run at once and the rest wait their turn (the
     * server keeps answering everyone who is already logged in).
     */
    private final Semaphore hashing = new Semaphore(Math.max(1, Runtime.getRuntime().availableProcessors() / 2), true);

    AccountService(Database db, int iterations, Clock clock) {
        this.db = db;
        this.hasher = new PasswordHasher(iterations);
        this.perUsername = new RateLimiter(5, Duration.ofMinutes(15), clock);
        this.perAddress = new RateLimiter(20, Duration.ofMinutes(15), clock);
        this.registrations = new RateLimiter(REGISTRATIONS_PER_HOUR, Duration.ofHours(1), clock);
    }

    static String normalize(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    Database.User register(String rawUsername, String password, byte[] identityKey) throws ApiException {
        return register(rawUsername, password, identityKey, null);
    }

    /**
     * @param address the limit key of the client's address, or null for a client that is not
     *                limited (this computer itself)
     */
    Database.User register(String rawUsername, String password, byte[] identityKey, String address) throws ApiException {
        String username = normalize(rawUsername);
        if (!USERNAME.matcher(username).matches()) {
            throw new ApiException(ErrorCode.INVALID_USERNAME,
                    "Usernames are 3-20 characters: lowercase letters, digits, '_' and '.'.");
        }
        validatePassword(username, password);
        if (address != null) {
            if (registrations.isBlocked(address)) {
                throw new ApiException(ErrorCode.RATE_LIMITED, "Too many new accounts from your network. Try again in an hour.");
            }
            registrations.recordFailure(address); // counts attempts: each one costs a password hash
        }
        try {
            if (db.findUser(username).isPresent()) { // before hashing: a taken name costs nothing
                throw new ApiException(ErrorCode.USERNAME_TAKEN, "That username is already taken.");
            }
            PasswordHasher.Hash hash = hashed(() -> hasher.hash(password));
            long id = db.createUser(username, hash, identityKey);
            return new Database.User(id, username, null, identityKey);
        } catch (Database.UsernameTakenException e) {
            throw new ApiException(ErrorCode.USERNAME_TAKEN, "That username is already taken.");
        } catch (SQLException e) {
            throw new ApiException(ErrorCode.INTERNAL, "Server storage error.");
        }
    }

    /**
     * Checks the password, then binds the account to the device key that authenticated this
     * connection. A different key means the user logged in from a new device.
     */
    LoginResult login(String rawUsername, String password, byte[] identityKey, String address) throws ApiException {
        String username = normalize(rawUsername);
        boolean wellFormed = USERNAME.matcher(username).matches();
        if (perAddress.isBlocked(address)) throw tooManyAttempts();
        Optional<Database.User> found;
        try {
            found = wellFormed ? db.findUser(username) : Optional.empty();
        } catch (SQLException e) {
            throw new ApiException(ErrorCode.INTERNAL, "Server storage error.");
        }
        // The lock on a username stops password guessing, but anyone could also use it to keep the
        // real owner out by failing on purpose. The owner's own device proved its key in the
        // handshake, which a guesser cannot do, so it is not locked out (the address limit still applies).
        boolean ownDevice = found.isPresent() && Arrays.equals(found.get().identityKey(), identityKey);
        if (!ownDevice && perUsername.isBlocked(username)) throw tooManyAttempts();
        Optional<Database.User> account = found;
        boolean ok = hashed(() -> {
            if (account.isPresent()) return hasher.verify(password, account.get().password());
            hasher.verifyDummy(password); // same cost as a real check: no username enumeration via timing
            return false;
        });
        if (!ok) {
            if (wellFormed) perUsername.recordFailure(username); // other strings can never be accounts
            perAddress.recordFailure(address);
            throw new ApiException(ErrorCode.BAD_CREDENTIALS, GENERIC_LOGIN_ERROR);
        }
        perUsername.reset(username);
        Database.User user = found.get();
        boolean newDevice = !Arrays.equals(user.identityKey(), identityKey);
        if (newDevice) {
            try {
                db.updateIdentityKey(user.id(), identityKey);
            } catch (SQLException e) {
                throw new ApiException(ErrorCode.INTERNAL, "Server storage error.");
            }
        }
        return new LoginResult(user, newDevice);
    }

    private static ApiException tooManyAttempts() {
        return new ApiException(ErrorCode.RATE_LIMITED, "Too many failed attempts. Try again in 15 minutes.");
    }

    /** Runs a password hash when one of the few hashing slots is free. */
    private <T> T hashed(java.util.function.Supplier<T> work) throws ApiException {
        try {
            if (!hashing.tryAcquire(20, TimeUnit.SECONDS)) {
                throw new ApiException(ErrorCode.RATE_LIMITED, "The server is busy. Try again in a moment.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.INTERNAL, "The server is shutting down.");
        }
        try {
            return work.get();
        } finally {
            hashing.release();
        }
    }

    private static void validatePassword(String username, String password) throws ApiException {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new ApiException(ErrorCode.WEAK_PASSWORD, "Use at least " + MIN_PASSWORD + " characters.");
        }
        if (password.length() > MAX_PASSWORD) {
            throw new ApiException(ErrorCode.WEAK_PASSWORD, "Use at most " + MAX_PASSWORD + " characters.");
        }
        if (password.toLowerCase(Locale.ROOT).contains(username)) {
            throw new ApiException(ErrorCode.WEAK_PASSWORD, "The password must not contain your username.");
        }
    }
}
