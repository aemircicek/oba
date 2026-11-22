package com.emirchat.e2e;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;

/**
 * Owner-only, crash-safe storage for key and session files; encrypted under the account's
 * password once its {@link Vault} is unlocked.
 */
final class SecureFiles {
    private SecureFiles() {}

    /** @return the file's content, or null if it does not exist */
    static byte[] read(Path file) throws IOException {
        return Files.exists(file) ? Vault.open(file, Files.readAllBytes(file)) : null;
    }

    /** Writes to a temporary file and renames it, so a crash never leaves a half-written key file. */
    static void writeAtomically(Path file, byte[] content) throws IOException {
        byte[] data = Vault.seal(file, content);
        Path dir = file.toAbsolutePath().getParent();
        boolean posix = dir.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (!Files.isDirectory(dir)) {
            if (posix) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)));
            } else {
                Files.createDirectories(dir);
            }
        }
        Path tmp = posix
                ? Files.createTempFile(dir, ".tmp-", ".bin", PosixFilePermissions.asFileAttribute(EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)))
                : Files.createTempFile(dir, ".tmp-", ".bin");
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
