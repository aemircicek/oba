package com.emirchat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupTest {
    private static final char[] PASS = "doğru at pil zımba".toCharArray();

    @TempDir Path dir;

    private Path home() throws IOException {
        Path home = dir.resolve("home");
        Files.createDirectories(home.resolve("accounts/srv_5056/emir/e2e"));
        Files.createDirectories(home.resolve("accounts/srv_5056/emir/attachments"));
        Files.writeString(home.resolve("identity.key"), "private key bytes");
        Files.writeString(home.resolve("known_servers"), "srv:5056 abcd");
        Files.writeString(home.resolve("accounts/srv_5056/emir/e2e/device-keys.bin"), "e2e keys");
        Files.writeString(home.resolve("accounts/srv_5056/emir/attachments/0011"), "cached blob");
        Files.writeString(home.resolve("accounts/srv_5056/emir/history.db-wal"), "live wal");
        return home;
    }

    @Test
    void backupAndRestoreRoundTrip() throws Exception {
        Path home = home();
        Path db = home.resolve("accounts/srv_5056/emir/history.db");
        Path backup = dir.resolve("b.scbackup");
        try (History h = History.open(db, "account password", 1000)) {
            h.add("dm:efe", "efe", "yedekteki mesaj", 1, false, History.RECEIVED, 1);
            Backup.create(home, backup, PASS, Map.of(db, h), 1000); // the history is open while backing up
        }
        String raw = new String(Files.readAllBytes(backup), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("private key") || raw.contains("identity.key"), "names and contents are encrypted");

        Path restored = dir.resolve("new-computer");
        assertNull(Backup.restore(backup, PASS, restored), "nothing to move aside in an empty folder");
        assertEquals("private key bytes", Files.readString(restored.resolve("identity.key")));
        assertEquals("e2e keys", Files.readString(restored.resolve("accounts/srv_5056/emir/e2e/device-keys.bin")));
        assertFalse(Files.exists(restored.resolve("accounts/srv_5056/emir/attachments")), "the cache is not backed up");
        assertFalse(Files.exists(restored.resolve("accounts/srv_5056/emir/history.db-wal")));
        try (History h = History.open(restored.resolve("accounts/srv_5056/emir/history.db"), "account password", 1000)) {
            assertEquals("yedekteki mesaj", h.load("dm:efe", 1).get(0).text());
        }
    }

    @Test
    void wrongPassphraseOrTamperingIsDetected() throws Exception {
        Path backup = dir.resolve("b.scbackup");
        Backup.create(home(), backup, PASS, Map.of(), 1000);
        assertThrows(SecurityException.class, () -> Backup.restore(backup, "yanlış parola!!".toCharArray(), dir.resolve("x")));
        byte[] bytes = Files.readAllBytes(backup);
        bytes[bytes.length - 20] ^= 1;
        Files.write(backup, bytes);
        assertThrows(SecurityException.class, () -> Backup.restore(backup, PASS, dir.resolve("y")));
        byte[] header = Files.readAllBytes(backup);
        header[10] ^= 1; // the iteration count is authenticated too
        Files.write(backup, header);
        assertThrows(Exception.class, () -> Backup.restore(backup, PASS, dir.resolve("z")));
    }

    @Test
    void existingDataIsMovedAsideNotDeleted() throws Exception {
        Path home = home();
        Path backup = dir.resolve("b.scbackup");
        Backup.create(home, backup, PASS, Map.of(), 1000);
        Files.writeString(home.resolve("identity.key"), "a newer key on this computer");
        Path aside = Backup.restore(backup, PASS, home);
        assertNotNull(aside);
        assertEquals("a newer key on this computer", Files.readString(aside.resolve("identity.key")));
        assertEquals("private key bytes", Files.readString(home.resolve("identity.key")));
    }

    @Test
    void entriesCannotEscapeTheRestoreFolder() throws Exception {
        Path root = dir.resolve("r").toAbsolutePath();
        assertEquals(root.resolve("a/b"), Backup.safeTarget(root, "a/b"));
        for (String evil : new String[] {"../outside", "a/../../outside", "/etc/passwd", "\\windows\\x", "C:\\x", "", "."}) {
            assertThrows(IOException.class, () -> Backup.safeTarget(root, evil), evil);
        }
    }

    @Test
    void inputsAreChecked() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> Backup.create(home(), dir.resolve("b"), "short".toCharArray(), Map.of()));
        Path notABackup = dir.resolve("photo.scbackup");
        Files.writeString(notABackup, "just some text that is long enough to have a header and more");
        IOException e = assertThrows(IOException.class, () -> Backup.restore(notABackup, PASS, dir.resolve("q")));
        assertTrue(e.getMessage().contains("not a secure-chat backup"));
    }

    @Test
    void theSuggestedNameNamesTheAccount() {
        String name = Backup.suggestedName("emir");
        assertTrue(name.startsWith("secure-chat-emir-") && name.endsWith(".scbackup"));
    }
}
