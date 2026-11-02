package com.emirchat.e2e;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encryption of file attachments, as in Signal: every file gets a fresh random AES-256 key and is
 * encrypted once with AES-256-GCM. Only the ciphertext is uploaded to the server; the key and the
 * SHA-256 digest of the ciphertext travel inside an end-to-end encrypted message. The receiver
 * checks the digest before decrypting, so the server cannot substitute another blob, and GCM
 * authenticates the content under the key.
 *
 * <pre>
 *   ciphertext = nonce (12) ‖ AES-256-GCM(key, nonce, aad = "secure-chat attachment v1", file)
 *   digest     = SHA-256(ciphertext)
 * </pre>
 */
public final class Attachments {
    public static final int KEY_LENGTH = 32;
    public static final int DIGEST_LENGTH = 32;
    public static final int ID_LENGTH = 16;
    public static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;
    /** Bytes the ciphertext adds to the file: nonce and authentication tag. */
    public static final int OVERHEAD = NONCE_LENGTH + TAG_BITS / 8;
    public static final long MAX_CIPHERTEXT_BYTES = MAX_FILE_BYTES + OVERHEAD;
    private static final byte[] AAD = "secure-chat attachment v1".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** An encrypted file ready for upload. */
    public record Sealed(byte[] key, byte[] ciphertext, byte[] digest) {}

    private Attachments() {}

    public static Sealed seal(byte[] file) {
        if (file.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Files can be at most " + (MAX_FILE_BYTES / (1024 * 1024)) + " MB");
        }
        byte[] key = new byte[KEY_LENGTH];
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(key);
        RANDOM.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(AAD);
            byte[] body = c.doFinal(file);
            byte[] ciphertext = ByteBuffer.allocate(NONCE_LENGTH + body.length).put(nonce).put(body).array();
            return new Sealed(key, ciphertext, sha256(ciphertext));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable", e);
        }
    }

    /**
     * Checks the digest, then decrypts.
     *
     * @throws SecurityException if the blob is not the one the sender described, or was modified
     */
    public static byte[] open(byte[] ciphertext, byte[] key, byte[] digest) {
        if (key.length != KEY_LENGTH || digest.length != DIGEST_LENGTH) throw new SecurityException("Malformed attachment key");
        if (ciphertext.length < OVERHEAD || ciphertext.length > MAX_CIPHERTEXT_BYTES) {
            throw new SecurityException("Attachment has an invalid size");
        }
        if (!MessageDigest.isEqual(sha256(ciphertext), digest)) {
            throw new SecurityException("Attachment does not match its digest (replaced or corrupted)");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, ciphertext, 0, NONCE_LENGTH));
            c.updateAAD(AAD);
            return c.doFinal(ciphertext, NONCE_LENGTH, ciphertext.length - NONCE_LENGTH);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new SecurityException("Attachment failed authentication");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable", e);
        }
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lowercase hex, used for attachment ids in file names and logs. */
    public static String hex(byte[] id) {
        StringBuilder sb = new StringBuilder(id.length * 2);
        for (byte b : id) sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        return sb.toString();
    }
}
