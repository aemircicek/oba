package com.emirchat.server;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Registration and login rules. */
final class AccountService {
    static final Pattern USERNAME = Pattern.compile("[a-z0-9_.]{3,20}");
    static final int MIN_PASSWORD = 8;
    static final int MAX_PASSWORD = 128;
    private static final String GENERIC_LOGIN_ERROR = "Wrong username or password.";

    record LoginResult(Database.User user, boolean newDevice) {}

    private final Database db;
    private final PasswordHasher hasher;
    private final RateLimiter perUsername;
    private final RateLimiter perAddress;

    AccountService(Database db, int iterations, Clock clock) {
        this.db = db;
        this.hasher = new PasswordHasher(iterations);
        this.perUsername = new RateLimiter(5, Duration.ofMinutes(15), clock);
        this.perAddress = new RateLimiter(20, Duration.ofMinutes(15), clock);
    }

    static String normalize(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    Database.User register(String rawUsername, String password, byte[] identityKey) throws ApiException {
        String username = normalize(rawUsername);
        if (!USERNAME.matcher(username).matches()) {
            throw new ApiException(ErrorCode.INVALID_USERNAME,
                    "Usernames are 3-20 characters: lowercase letters, digits, '_' and '.'.");
        }
        validatePassword(username, password);
        try {
            long id = db.createUser(username, hasher.hash(password), identityKey);
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
        if (perUsername.isBlocked(username) || perAddress.isBlocked(address)) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Too many failed attempts. Try again in 15 minutes.");
        }
        Optional<Database.User> found;
        try {
            found = USERNAME.matcher(username).matches() ? db.findUser(username) : Optional.empty();
        } catch (SQLException e) {
            throw new ApiException(ErrorCode.INTERNAL, "Server storage error.");
        }
        boolean ok;
        if (found.isPresent()) {
            ok = hasher.verify(password, found.get().password());
        } else {
            hasher.verifyDummy(password); // same cost as a real check: no username enumeration via timing
            ok = false;
        }
        if (!ok) {
            perUsername.recordFailure(username);
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
