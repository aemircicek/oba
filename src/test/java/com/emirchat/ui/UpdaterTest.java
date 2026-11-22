package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** The updater installs only what the release key signed. */
class UpdaterTest {
    private static final String NAME = "Oba-3.6.0-macos-arm64.dmg";

    @Test
    void acceptsTheListedHashUnderAValidSignature() throws Exception {
        KeyPair keys = keys();
        byte[] installer = "the installer".getBytes(StandardCharsets.UTF_8);
        byte[] sums = sums(installer);
        byte[] hash = Updater.expectedHash(sums, sign(keys, sums), keys.getPublic(), NAME);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(installer), hash);
    }

    @Test
    void refusesAChangedListAnotherKeyAndUnlistedFiles() throws Exception {
        KeyPair keys = keys();
        byte[] sums = sums("x".getBytes(StandardCharsets.UTF_8));
        byte[] sig = sign(keys, sums);
        byte[] changed = sums.clone();
        changed[0] = (byte) (changed[0] == 'a' ? 'b' : 'a');
        assertThrows(java.io.IOException.class, () -> Updater.expectedHash(changed, sig, keys.getPublic(), NAME));
        assertThrows(java.io.IOException.class, () -> Updater.expectedHash(sums, sig, keys().getPublic(), NAME));
        assertThrows(java.io.IOException.class,
                () -> Updater.expectedHash(sums, sig, keys.getPublic(), "Oba-3.6.0-linux-x64.deb"));
    }

    @Test
    void theBuiltInKeyParses() {
        assertEquals("EdDSA", Updater.publicKey(Updater.RELEASE_KEY).getAlgorithm());
    }

    @Test
    void knowsWhichCopiesCanReplaceThemselves() {
        assertEquals(Updater.Kind.MANUAL, Updater.kind(null, "Mac OS X", "aarch64"), "a jar");
        assertEquals(Updater.Kind.MAC_APP, Updater.kind("/Applications/Oba.app/Contents/MacOS/Oba", "Mac OS X", "aarch64"));
        assertEquals(Updater.Kind.MANUAL, Updater.kind("/Applications/Oba.app/Contents/MacOS/Oba", "Mac OS X", "x86_64"),
                "no Intel build to update to");
        assertEquals(Updater.Kind.WINDOWS, Updater.kind("C:\\Users\\emir\\AppData\\Local\\Oba\\Oba.exe", "Windows 11", "amd64"));
        assertEquals(Updater.Kind.MANUAL, Updater.kind("D:\\tools\\Oba\\Oba.exe", "Windows 11", "amd64"), "portable zip");
        assertEquals(Updater.Kind.DEB, Updater.kind("/opt/oba/bin/Oba", "Linux", "amd64"));
        assertEquals("Oba-3.6.0-windows-x64.exe", Updater.Kind.WINDOWS.assetName("3.6.0"));
        assertEquals(Path.of("/Applications/Oba.app"), Updater.macBundle("/Applications/Oba.app/Contents/MacOS/Oba").orElseThrow());
    }

    @Test
    void neverDownloadsAnOlderOrTheSameVersion() {
        Exception e = assertThrows(java.io.IOException.class,
                () -> Updater.download("0.0.1", Updater.Kind.MAC_APP, Path.of("unused"), p -> {}));
        assertTrue(e.getMessage().contains("Not a newer version") || e.getMessage().contains("by hand"), e.getMessage());
    }

    @Test
    void quotesPathsForTheShell() {
        assertEquals("'/Users/a b/it'\\''s'", Updater.quote("/Users/a b/it's"));
    }

    private static byte[] sums(byte[] installer) throws Exception {
        String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(installer));
        return (hex + "  " + NAME + "\n" + "0".repeat(64) + "  Oba-3.6.0-windows-x64.exe\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sign(KeyPair keys, byte[] data) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(keys.getPrivate());
        s.update(data);
        return s.sign();
    }

    private static KeyPair keys() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @Test
    void verifiesWhatOpensslSigns() throws Exception {
        // the release workflow signs with "openssl pkeyutl -sign -rawin" and uploads base64: same bytes as Java's Ed25519
        KeyPair keys = keys();
        byte[] sums = sums(new byte[] {1});
        String uploaded = Base64.getEncoder().encodeToString(sign(keys, sums));
        Updater.expectedHash(sums, Base64.getDecoder().decode(uploaded.strip()), keys.getPublic(), NAME);
    }
}
