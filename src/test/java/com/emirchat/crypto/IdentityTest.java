package com.emirchat.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdentityTest {
    private static boolean posix(Path p) {
        return p.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static String permissions(Path p) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
    }

    @Test
    void aNewKeyFolderAndKeyAreForTheOwnerOnly(@TempDir Path dir) throws Exception {
        Path home = dir.resolve("home");
        Identity created = Identity.loadOrCreate(home);
        assertEquals(created.fingerprint(), Identity.loadOrCreate(home).fingerprint(), "the same key is loaded again");
        if (!posix(home)) return;
        assertEquals("rwx------", permissions(home));
        assertEquals("rw-------", permissions(home.resolve("identity.key")));
    }

    @Test
    void aFolderThatWasOpenToOthersIsClosed(@TempDir Path dir) throws Exception {
        // what happened before: something else (the log, the lock file) created the data folder
        // first, with the system's default permissions, and the keys went into a readable folder
        Path home = Files.createDirectories(dir.resolve("home"));
        if (!posix(home)) return;
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwxr-xr-x"));
        Identity.loadOrCreate(home);
        assertEquals("rwx------", permissions(home));
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwxrwxrwx"));
        Identity.loadOrCreate(home); // and again for an existing identity
        assertEquals("rwx------", permissions(home));
    }

    @Test
    void makePrivateCreatesMissingFolders(@TempDir Path dir) throws Exception {
        Path nested = dir.resolve("a/b");
        Identity.makePrivate(nested);
        assertTrue(Files.isDirectory(nested));
        if (posix(nested)) assertEquals("rwx------", permissions(nested));
    }
}
