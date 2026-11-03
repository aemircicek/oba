package com.emirchat.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypted backups of everything in the app's key directory: the device identity, pinned
 * servers and contacts, end-to-end sessions and keys, and the (already encrypted) message history.
 * Cached attachment copies are left out; they can be downloaded again while the server keeps them.
 *
 * <pre>
 *   file = "SCBACKUP1" ‖ u32 iterations ‖ salt (16) ‖ nonce (12) ‖ AES-256-GCM(key, zip)
 *   key  = PBKDF2-HMAC-SHA256(passphrase, salt, iterations)
 *   aad  = everything before the ciphertext
 * </pre>
 *
 * <p>Restoring a backup on a new computer moves the same identity there. Only one device can use
 * an account at a time, so the old one must not be used afterwards.
 */
public final class Backup {
    public static final int MIN_PASSPHRASE = 10;
    static final int ITERATIONS = 600_000;
    private static final byte[] MAGIC = "SCBACKUP1".getBytes(StandardCharsets.US_ASCII);
    private static final long MAX_BACKUP_BYTES = 512L * 1024 * 1024;
    private static final long MAX_UNPACKED_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_ENTRIES = 100_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Backup() {}

    /**
     * @param openHistories histories that are open right now (their file → the open handle); a
     *                      consistent copy is taken through SQLite instead of copying a live file
     */
    public static void create(Path home, Path out, char[] passphrase, Map<Path, History> openHistories) throws IOException {
        create(home, out, passphrase, openHistories, ITERATIONS);
    }

    static void create(Path home, Path out, char[] passphrase, Map<Path, History> openHistories, int iterations)
            throws IOException {
        checkPassphrase(passphrase);
        Path root = home.toAbsolutePath().normalize();
        Map<Path, History> open = new java.util.HashMap<>();
        openHistories.forEach((p, h) -> open.put(p.toAbsolutePath().normalize(), h));
        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipped); Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                if (!Files.isRegularFile(file) || skip(root.relativize(file))) continue;
                String name = root.relativize(file).toString().replace('\\', '/');
                byte[] data;
                History h = open.get(file);
                if (h != null) {
                    Path tmp = Files.createTempFile("history", ".db");
                    Files.delete(tmp); // VACUUM INTO needs a new file
                    try {
                        h.copyTo(tmp);
                        data = Files.readAllBytes(tmp);
                    } finally {
                        Files.deleteIfExists(tmp);
                    }
                } else {
                    data = Files.readAllBytes(file);
                }
                zip.putNextEntry(new ZipEntry(name));
                zip.write(data);
                zip.closeEntry();
            }
        }
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(nonce);
        byte[] header = ByteBuffer.allocate(MAGIC.length + 4 + 16 + 12).put(MAGIC).putInt(iterations).put(salt).put(nonce).array();
        byte[] ciphertext = gcm(Cipher.ENCRYPT_MODE, derive(passphrase, salt, iterations), nonce, header, zipped.toByteArray());
        Path tmp = out.toAbsolutePath().resolveSibling(out.getFileName() + ".tmp");
        Files.write(tmp, ByteBuffer.allocate(header.length + ciphertext.length).put(header).put(ciphertext).array());
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Temporary SQLite files and the attachment cache are not worth backing up. */
    private static boolean skip(Path relative) {
        String name = relative.getFileName().toString();
        if (name.endsWith("-wal") || name.endsWith("-shm") || name.endsWith(".tmp")) return true;
        for (Path part : relative) if (part.toString().equals("attachments")) return true;
        return false;
    }

    /**
     * Restores a backup into {@code home}. If {@code home} already has content, it is moved aside
     * to a sibling folder first (never deleted), whose path is returned.
     *
     * @throws SecurityException if the passphrase is wrong or the file was modified
     */
    public static Path restore(Path in, char[] passphrase, Path home) throws IOException {
        if (Files.size(in) > MAX_BACKUP_BYTES) throw new IOException("That file is too large to be a backup.");
        byte[] file = Files.readAllBytes(in);
        int headerLength = MAGIC.length + 4 + 16 + 12;
        if (file.length < headerLength + 16 || !Arrays.equals(Arrays.copyOf(file, MAGIC.length), MAGIC)) {
            throw new IOException("That is not an Oba (or Scorpion) backup.");
        }
        ByteBuffer b = ByteBuffer.wrap(file);
        b.position(MAGIC.length);
        int iterations = b.getInt();
        if (iterations < 1_000 || iterations > 10_000_000) throw new IOException("Unsupported backup settings.");
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        b.get(salt).get(nonce);
        byte[] header = Arrays.copyOf(file, headerLength);
        byte[] zipped = gcm(Cipher.DECRYPT_MODE, derive(passphrase, salt, iterations), nonce, header,
                Arrays.copyOfRange(file, headerLength, file.length));

        Path root = home.toAbsolutePath().normalize();
        Path movedAside = null;
        if (Files.exists(root) && !isEmpty(root)) {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            movedAside = root.resolveSibling(root.getFileName() + ".before-restore-" + stamp);
            Files.move(root, movedAside);
        }
        Files.createDirectories(root);
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipped))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("Backup has too many files.");
                Path target = safeTarget(root, e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                byte[] data = zip.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, MAX_UNPACKED_BYTES - total + 1));
                total += data.length;
                if (total > MAX_UNPACKED_BYTES) throw new IOException("Backup unpacks to too much data.");
                Files.createDirectories(target.getParent());
                Files.write(target, data);
                ownerOnly(target);
            }
        }
        return movedAside;
    }

    /** Rejects entries that would land outside the restore folder ("zip slip"). */
    static Path safeTarget(Path root, String entryName) throws IOException {
        if (entryName.isEmpty() || entryName.startsWith("/") || entryName.startsWith("\\") || entryName.contains(":")) {
            throw new IOException("Backup contains an unsafe path: " + entryName);
        }
        Path target = root.resolve(entryName).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IOException("Backup contains an unsafe path: " + entryName);
        }
        return target;
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }

    private static void ownerOnly(Path file) {
        try {
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException ignored) {
            // best effort, as for the original files
        }
    }

    static void checkPassphrase(char[] passphrase) {
        if (passphrase == null || passphrase.length < MIN_PASSPHRASE) {
            throw new IllegalArgumentException("Use a backup passphrase of at least " + MIN_PASSPHRASE + " characters.");
        }
    }

    private static byte[] derive(char[] passphrase, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] gcm(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] data) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            c.updateAAD(aad);
            return c.doFinal(data);
        } catch (AEADBadTagException e) {
            throw new SecurityException("Wrong passphrase, or the backup file was changed.");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** Suggested file name for a backup. */
    public static String suggestedName(String user) {
        return "oba-" + user + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".scbackup";
    }
}
