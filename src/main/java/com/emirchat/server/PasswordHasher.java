package com.emirchat.server;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password storage with PBKDF2-HMAC-SHA256 (OWASP 2023: at least 600,000 iterations) and a
 * random 16-byte salt per user. The iteration count is stored with each hash so it can be raised
 * later without breaking existing accounts.
 */
final class PasswordHasher {
    static final int DEFAULT_ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    record Hash(byte[] hash, byte[] salt, int iterations) {}

    private final int iterations;
    private final Hash dummy;

    PasswordHasher(int iterations) {
        if (iterations < 1) throw new IllegalArgumentException("iterations");
        this.iterations = iterations;
        this.dummy = hash("dummy password used to equalise timing");
    }

    Hash hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return new Hash(derive(password, salt, iterations), salt, iterations);
    }

    boolean verify(String password, Hash stored) {
        return MessageDigest.isEqual(derive(password, stored.salt(), stored.iterations()), stored.hash());
    }

    /**
     * Burns the same CPU time as a real verification. Used for unknown usernames so response
     * timing does not reveal which accounts exist.
     */
    void verifyDummy(String password) {
        verify(password, dummy);
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}
