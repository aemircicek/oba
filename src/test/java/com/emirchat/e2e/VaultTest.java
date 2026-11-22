package com.emirchat.e2e;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.crypto.Identity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An account's keys and sessions on disk, under its password. */
class VaultTest {
    @TempDir Path dir;

    @AfterEach
    void lock() {
        Vault.lock(dir.resolve("emir"));
    }

    @Test
    void filesAreEncryptedAndReadBack() throws Exception {
        Path account = dir.resolve("emir");
        Vault.unlock(account, "correct horse");
        Path file = account.resolve("e2e/sessions/efe.bin");
        byte[] secret = "ratchet state: root key 0123".getBytes(StandardCharsets.UTF_8);
        SecureFiles.writeAtomically(file, secret);
        byte[] onDisk = Files.readAllBytes(file);
        assertTrue(Vault.encrypted(onDisk));
        assertFalse(new String(onDisk, StandardCharsets.ISO_8859_1).contains("ratchet"));
        assertArrayEquals(secret, SecureFiles.read(file));
    }

    @Test
    void aLockedOrWrongPasswordVaultOpensNothing() throws Exception {
        Path account = dir.resolve("emir");
        Vault.unlock(account, "correct horse");
        Path file = account.resolve("e2e/device-keys.bin");
        SecureFiles.writeAtomically(file, new byte[] {1, 2, 3});
        Vault.lock(account);
        assertThrows(java.io.IOException.class, () -> SecureFiles.read(file), "locked: no key");
        assertThrows(Vault.WrongPasswordException.class, () -> Vault.unlock(account, "wrong"));
        Vault.unlock(account, "correct horse");
        assertArrayEquals(new byte[] {1, 2, 3}, SecureFiles.read(file));
    }

    @Test
    void filesCannotBeSwapped() throws Exception {
        Path account = dir.resolve("emir");
        Vault.unlock(account, "pw");
        Path efe = account.resolve("e2e/sessions/efe.bin");
        Path ayse = account.resolve("e2e/sessions/ayse.bin");
        SecureFiles.writeAtomically(efe, new byte[] {1});
        SecureFiles.writeAtomically(ayse, new byte[] {2});
        Files.copy(efe, ayse, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertThrows(java.io.IOException.class, () -> SecureFiles.read(ayse), "efe's session under ayse's name");
    }

    @Test
    void plainFilesFromOlderVersionsAreEncryptedOnUnlock() throws Exception {
        Path account = dir.resolve("emir");
        Path file = account.resolve("e2e/groups/7.bin");
        SecureFiles.writeAtomically(file, new byte[] {9, 9}); // no vault yet: as 3.5 wrote it
        assertFalse(Vault.encrypted(Files.readAllBytes(file)));
        Vault.unlock(account, "pw");
        assertTrue(Vault.encrypted(Files.readAllBytes(file)));
        assertArrayEquals(new byte[] {9, 9}, SecureFiles.read(file));
    }

    @Test
    void aNewPasswordRewrapsTheSameKey() throws Exception {
        Path account = dir.resolve("emir");
        Vault.unlock(account, "old");
        Path file = account.resolve("e2e/x.bin");
        SecureFiles.writeAtomically(file, new byte[] {5});
        Vault.rewrap(account, "old", "new");
        Vault.lock(account);
        assertThrows(Vault.WrongPasswordException.class, () -> Vault.unlock(account, "old"));
        Vault.unlock(account, "new");
        assertArrayEquals(new byte[] {5}, SecureFiles.read(file));
    }

    @Test
    void aMessengerWorksThroughTheVault() throws Exception {
        Path account = dir.resolve("emir");
        Vault.unlock(account, "pw");
        Identity id = Identity.generate();
        Messenger m = Messenger.open(account.resolve("e2e"), id);
        m.newOneTimePrekeys(3);
        byte[] keys = Files.readAllBytes(account.resolve("e2e/device-keys.bin"));
        assertTrue(Vault.encrypted(keys), "prekeys encrypted at rest");
        Messenger again = Messenger.open(account.resolve("e2e"), id);
        assertTrue(again.oneTimePrekeyCount() >= 3);
    }
}
