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
    private final Clock clock;
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
        this.clock = clock;
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
        return login(rawUsername, password, null, identityKey, address);
    }

    /**
     * As {@link #login(String, String, byte[], String)}, with two-step login: an account that has it
     * asks a new device (not the one it is bound to) for a code from the authenticator app, or one
     * of its recovery codes. The code is asked for only after the password was right.
     */
    LoginResult login(String rawUsername, String password, String code, byte[] identityKey, String address)
            throws ApiException {
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
        Database.User user = found.get();
        boolean newDevice = !Arrays.equals(user.identityKey(), identityKey);
        if (newDevice) secondStep(user, code, username, address);
        perUsername.reset(username);
        if (newDevice) {
            try {
                db.updateIdentityKey(user.id(), identityKey);
            } catch (SQLException e) {
                throw new ApiException(ErrorCode.INTERNAL, "Server storage error.");
            }
        }
        return new LoginResult(user, newDevice);
    }

    /** Checks the second step of a login from a new device, if the account has it turned on. */
    private void secondStep(Database.User user, String code, String username, String address) throws ApiException {
        Optional<Database.TwoFactor> tf;
        try {
            tf = db.twoFactor(user.id());
        } catch (SQLException e) {
            throw new ApiException(ErrorCode.INTERNAL, "Server storage error.");
        }
        if (tf.isEmpty() || !tf.get().enabled()) return;
        if (code == null || code.isBlank()) {
            throw new ApiException(ErrorCode.CODE_REQUIRED, "Enter the code from your authenticator app.");
        }
        try {
            if (Totp.looksLikeRecoveryCode(code)) {
                if (db.useRecoveryCode(user.id(), Totp.recoveryHash(code))) return;
            } else {
                long step = Totp.verify(tf.get().secret(), code, clock.millis(), tf.get().lastStep());
                if (step >= 0) {
                    db.setTwoFactorStep(user.id(), step); // the same code never works twice
                    return;
                }
            }
        } catch (SQLException e) {
            throw new ApiException(ErrorCode.INTERNAL, "Server storage error.");
        }
        perUsername.recordFailure(username); // codes are guessed like passwords: the same limits
        perAddress.recordFailure(address);
        throw new ApiException(ErrorCode.BAD_CODE, "That code is not right. Check the time on your phone.");
    }

    /** Starts turning two-step login on: a new secret for the authenticator app (not active until confirmed). */
    byte[] setUpTwoFactor(Database.User user) throws ApiException {
        try {
            Optional<Database.TwoFactor> tf = db.twoFactor(user.id());
            if (tf.isPresent() && tf.get().enabled()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Two-step login is already on.");
            }
            byte[] secret = Totp.newSecret();
            db.setPendingTwoFactor(user.id(), secret);
            return secret;
        } catch (SQLException e) {
            throw new ApiException(ErrorCode.INTERNAL, "Server storage error.");
        }
    }

    /** Turns it on with the first code from the app. @return recovery codes, shown once */
    java.util.List<String> enableTwoFactor(Database.User user, String code) throws ApiException {
        try {
            Optional<Database.TwoFactor> tf = db.twoFactor(user.id());
            if (tf.isEmpty() || tf.get().enabled()) throw new ApiException(ErrorCode.INVALID_REQUEST, "Start again.");
            long step = Totp.verify(tf.get().secret(), code, clock.millis(), -1);
            if (step < 0) throw new ApiException(ErrorCode.BAD_CODE, "That code is not right. Check the time on your phone.");
            java.util.List<String> codes = Totp.newRecoveryCodes();
            db.enableTwoFactor(user.id(), step, codes.stream().map(Totp::recoveryHash).toList());
            return codes;
        } catch (SQLException e) {
            throw new ApiException(ErrorCode.INTERNAL, "Server storage error.");
        }
    }

    /** Turns it off; needs the password again, so an unlocked computer left alone is not enough. */
    void disableTwoFactor(Database.User user, String password) throws ApiException {
        checkPassword(user, password);
        try {
            db.disableTwoFactor(user.id());
        } catch (SQLException e) {
            throw new ApiException(ErrorCode.INTERNAL, "Server storage error.");
        }
    }

    /** @return {on, recovery codes left} */
    int[] twoFactorState(Database.User user) throws ApiException {
        try {
            Optional<Database.TwoFactor> tf = db.twoFactor(user.id());
            return tf.isPresent() && tf.get().enabled() ? new int[] {1, tf.get().recoveryLeft()} : new int[] {0, 0};
        } catch (SQLException e) {
            throw new ApiException(ErrorCode.INTERNAL, "Server storage error.");
        }
    }

    void changePassword(Database.User user, String oldPassword, String newPassword) throws ApiException {
        validatePassword(user.username(), newPassword);
        checkPassword(user, oldPassword);
        try {
            db.updatePassword(user.id(), hashed(() -> hasher.hash(newPassword)));
        } catch (SQLException e) {
            throw new ApiException(ErrorCode.INTERNAL, "Server storage error.");
        }
    }

    /** The current password, checked against the stored hash (the logged-in record may be older). */
    private void checkPassword(Database.User user, String password) throws ApiException {
        if (perUsername.isBlocked(user.username())) throw tooManyAttempts();
        Database.User current;
        try {
            current = db.findUserById(user.id()).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND, "Gone."));
        } catch (SQLException e) {
            throw new ApiException(ErrorCode.INTERNAL, "Server storage error.");
        }
        if (!hashed(() -> hasher.verify(password, current.password()))) {
            perUsername.recordFailure(user.username());
            throw new ApiException(ErrorCode.BAD_CREDENTIALS, "The password is not right.");
        }
    }

    boolean deviceExists(Database.User user, int deviceId) throws java.sql.SQLException {
        return db.deviceExists(user.id(), deviceId);
    }

    void deviceSeen(Database.User user, int deviceId, String name) throws java.sql.SQLException {
        db.deviceSeen(user.id(), deviceId, name.isBlank() ? "" : name.strip(), clock.millis());
    }

    java.util.List<Database.Device> devices(Database.User user) throws java.sql.SQLException {
        return db.devices(user.id());
    }

    void removeDevice(Database.User user, int deviceId) throws java.sql.SQLException {
        db.removeDevice(user.id(), deviceId);
    }

    /**
     * Checks a computer that asks to be linked to the account (3.6): the password and, when two-step
     * login is on, the code, under the same limits as a login. Nothing is bound to its key.
     */
    Database.User checkForLink(String rawUsername, String password, String code, String address) throws ApiException {
        String username = normalize(rawUsername);
        if (perAddress.isBlocked(address) || perUsername.isBlocked(username)) throw tooManyAttempts();
        Optional<Database.User> found;
        try {
            found = USERNAME.matcher(username).matches() ? db.findUser(username) : Optional.empty();
        } catch (SQLException e) {
            throw new ApiException(ErrorCode.INTERNAL, "Server storage error.");
        }
        Optional<Database.User> account = found;
        boolean ok = hashed(() -> {
            if (account.isPresent()) return hasher.verify(password, account.get().password());
            hasher.verifyDummy(password);
            return false;
        });
        if (!ok) {
            if (found.isPresent()) perUsername.recordFailure(username);
            perAddress.recordFailure(address);
            throw new ApiException(ErrorCode.BAD_CREDENTIALS, GENERIC_LOGIN_ERROR);
        }
        secondStep(found.get(), code, username, address);
        return found.get();
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
