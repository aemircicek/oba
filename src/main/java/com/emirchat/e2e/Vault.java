package com.emirchat.e2e;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts an account's key and session files at rest under its password (3.6).
 *
 * <p>A random data key encrypts every file written through {@link SecureFiles} below the
 * account's folder (AES-256-GCM, the file's path inside the folder as associated data, so files
 * cannot be swapped for one another). The data key itself is kept in {@code vault.key}, wrapped
 * by a key derived from the password (PBKDF2-HMAC-SHA256), so changing the password only rewraps
 * it. A copied disk without the password shows no ratchet state, prekeys or group keys.
 *
 * <pre>
 *   vault.key = "OBAVK1" ‖ u32 iterations ‖ salt(16) ‖ nonce(12) ‖ GCM(KEK, data key)
 *   a file    = "OBV1" ‖ nonce(12) ‖ GCM(data key, content; AAD = path inside the account folder)
 * </pre>
 *
 * Files written by older versions (no "OBV1") are still read, and encrypted when the vault is
 * first unlocked.
 */
public final class Vault {
    static final byte[] FILE_MAGIC = {'O', 'B', 'V', '1'};
    private static final byte[] KEY_MAGIC = "OBAVK1".getBytes(StandardCharsets.US_ASCII);
    static final String KEY_FILE = "vault.key";
    /** Like the history's: about half a second on a laptop. Tests lower it with -Doba.vault.iterations. */
    static final int DEFAULT_ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Unlocked account folders and their data keys. */
    private static final Map<Path, SecretKey> OPEN = new ConcurrentHashMap<>();

    private Vault() {}

    /** The password does not open this computer's copy of the account's keys. */
    public static final class WrongPasswordException extends IOException {
        private static final long serialVersionUID = 1L;

        WrongPasswordException() {
            super("This computer's copy of the account is locked with another password");
        }
    }

    /**
     * Opens (or, the first time, creates) the vault of an account folder, encrypts any files an
     * older version left in plain form, and from now on encrypts what is written there.
     */
    public static void unlock(Path accountDir, String password) throws IOException {
        Path root = accountDir.toAbsolutePath().normalize();
        Path keyFile = root.resolve(KEY_FILE);
        SecretKey dataKey;
        if (Files.exists(keyFile)) {
            dataKey = unwrap(Files.readAllBytes(keyFile), password);
        } else {
            byte[] raw = new byte[32];
            RANDOM.nextBytes(raw);
            dataKey = new SecretKeySpec(raw, "AES");
            Arrays.fill(raw, (byte) 0);
            SecureFiles.writeAtomically(keyFile, wrap(dataKey, password, iterations()));
        }
        OPEN.put(root, dataKey);
        migrate(root);
    }

    /** Wraps the data key under a new password (after the password changed on the server). */
    public static void rewrap(Path accountDir, String oldPassword, String newPassword) throws IOException {
        Path root = accountDir.toAbsolutePath().normalize();
        Path keyFile = root.resolve(KEY_FILE);
        if (!Files.exists(keyFile)) return;
        SecretKey dataKey = unwrap(Files.readAllBytes(keyFile), oldPassword);
        SecureFiles.writeAtomically(keyFile, wrap(dataKey, newPassword, iterations()));
    }

    /** Writes a small secret file below an unlocked account folder (encrypted like the keys). */
    public static void write(Path file, byte[] content) throws IOException {
        SecureFiles.writeAtomically(file, content);
    }

    /** Reads such a file, or null if there is none. */
    public static byte[] read(Path file) throws IOException {
        return SecureFiles.read(file);
    }

    /** Forgets the data key (logging out). Files stay encrypted on disk. */
    public static void lock(Path accountDir) {
        OPEN.remove(accountDir.toAbsolutePath().normalize());
    }

    /** True if files below {@code accountDir} are encrypted on writing. */
    public static boolean isUnlocked(Path accountDir) {
        return OPEN.containsKey(accountDir.toAbsolutePath().normalize());
    }

    // ------------------------------------------------------------------ used by SecureFiles

    /** The unlocked vault a file belongs to: {root, key}, or null. */
    private static Map.Entry<Path, SecretKey> owner(Path file) {
        Path f = file.toAbsolutePath().normalize();
        Map.Entry<Path, SecretKey> best = null;
        for (Map.Entry<Path, SecretKey> e : OPEN.entrySet()) {
            if (f.startsWith(e.getKey()) && (best == null || e.getKey().getNameCount() > best.getKey().getNameCount())) best = e;
        }
        return best;
    }

    /** True if the file would be written encrypted (or is encrypted on disk and needs a key). */
    static boolean encrypted(byte[] data) {
        return data.length >= FILE_MAGIC.length && Arrays.equals(Arrays.copyOf(data, FILE_MAGIC.length), FILE_MAGIC);
    }

    /** What to write for {@code content} at {@code file}: encrypted when its folder's vault is open. */
    static byte[] seal(Path file, byte[] content) throws IOException {
        if (file.getFileName().toString().equals(KEY_FILE)) return content;
        Map.Entry<Path, SecretKey> owner = owner(file);
        if (owner == null) return content;
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, owner.getValue(), new GCMParameterSpec(128, nonce));
            c.updateAAD(aad(owner.getKey(), file));
            byte[] ct = c.doFinal(content);
            return ByteBuffer.allocate(FILE_MAGIC.length + 12 + ct.length).put(FILE_MAGIC).put(nonce).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not encrypt " + file.getFileName(), e);
        }
    }

    /** The content of what was read from {@code file}; plain data from older versions passes through. */
    static byte[] open(Path file, byte[] data) throws IOException {
        if (!encrypted(data)) return data;
        Map.Entry<Path, SecretKey> owner = owner(file);
        if (owner == null) throw new IOException("The account's keys are locked: log in first");
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, owner.getValue(), new GCMParameterSpec(128, data, FILE_MAGIC.length, 12));
            c.updateAAD(aad(owner.getKey(), file));
            return c.doFinal(data, FILE_MAGIC.length + 12, data.length - FILE_MAGIC.length - 12);
        } catch (GeneralSecurityException e) {
            throw new IOException("A key file was changed or damaged: " + file.getFileName(), e);
        }
    }

    private static byte[] aad(Path root, Path file) {
        String inside = root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return inside.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ the key file

    static int iterations() {
        return Math.max(1_000, Integer.getInteger("oba.vault.iterations", DEFAULT_ITERATIONS));
    }

    private static byte[] wrap(SecretKey dataKey, String password, int iterations) throws IOException {
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, kek(password, salt, iterations), new GCMParameterSpec(128, nonce));
            c.updateAAD(KEY_MAGIC);
            byte[] ct = c.doFinal(dataKey.getEncoded());
            return ByteBuffer.allocate(KEY_MAGIC.length + 4 + 16 + 12 + ct.length).put(KEY_MAGIC).putInt(iterations)
                    .put(salt).put(nonce).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not lock the account's keys", e);
        }
    }

    private static SecretKey unwrap(byte[] file, String password) throws IOException {
        if (file.length < KEY_MAGIC.length + 4 + 16 + 12 + 16
                || !Arrays.equals(Arrays.copyOf(file, KEY_MAGIC.length), KEY_MAGIC)) {
            throw new IOException("The account's key file is damaged");
        }
        ByteBuffer b = ByteBuffer.wrap(file, KEY_MAGIC.length, file.length - KEY_MAGIC.length);
        int iterations = b.getInt();
        if (iterations < 1_000 || iterations > 50_000_000) throw new IOException("The account's key file is damaged");
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        b.get(salt);
        b.get(nonce);
        byte[] ct = new byte[b.remaining()];
        b.get(ct);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, kek(password, salt, iterations), new GCMParameterSpec(128, nonce));
            c.updateAAD(KEY_MAGIC);
            byte[] raw = c.doFinal(ct);
            SecretKey key = new SecretKeySpec(raw, "AES");
            Arrays.fill(raw, (byte) 0);
            return key;
        } catch (javax.crypto.AEADBadTagException e) {
            throw new WrongPasswordException();
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not open the account's keys", e);
        }
    }

    private static SecretKey kek(String password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            SecretKey k = new SecretKeySpec(raw, "AES");
            Arrays.fill(raw, (byte) 0);
            return k;
        } finally {
            spec.clearPassword();
        }
    }

    /** Encrypts the key and session files an older version wrote in plain form. */
    private static void migrate(Path root) throws IOException {
        Path e2e = root.resolve("e2e");
        if (!Files.isDirectory(e2e)) return;
        try (var walk = Files.walk(e2e)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString();
                if (name.startsWith(".tmp-")) continue;
                byte[] data = Files.readAllBytes(f);
                if (!encrypted(data)) SecureFiles.writeAtomically(f, data);
            }
        }
    }
}
