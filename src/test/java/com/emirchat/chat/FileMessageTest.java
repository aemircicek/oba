package com.emirchat.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ProtocolException;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class FileMessageTest {
    private static FileMessage file(String name, String mime, long size) {
        byte[] id = new byte[16];
        byte[] key = new byte[32];
        byte[] digest = new byte[32];
        Arrays.fill(key, (byte) 0x5a);
        return new FileMessage(id, key, digest, size, name, mime, 1_700_000_000_000L);
    }

    @Test
    void roundTrip() throws ProtocolException {
        FileMessage f = file("paftalar.pdf", "application/pdf", 12_345);
        assertEquals(f, FileMessage.decode(f.encode()));
    }

    @Test
    void decodingIsStrict() {
        byte[] ok = file("a.txt", "text/plain", 1).encode();
        assertThrows(ProtocolException.class, () -> FileMessage.decode(Arrays.copyOf(ok, ok.length + 1)));
        assertThrows(ProtocolException.class, () -> FileMessage.decode(Arrays.copyOf(ok, ok.length - 1)));
        byte[] badVersion = ok.clone();
        badVersion[0] = 9;
        assertThrows(ProtocolException.class, () -> FileMessage.decode(badVersion));
        assertThrows(IllegalArgumentException.class, () -> file("big", "", 26L * 1024 * 1024));
        assertThrows(IllegalArgumentException.class, () -> file("neg", "", -1));
    }

    @Test
    void namesCannotEscapeTheDownloadFolder() {
        assertEquals("passwd", FileMessage.safeName("../../etc/passwd"));
        assertEquals("evil.exe", FileMessage.safeName("C:\\Users\\efe\\evil.exe"));
        assertEquals("bashrc", FileMessage.safeName(".bashrc"));
        assertEquals("file", FileMessage.safeName(".."));
        assertEquals("file", FileMessage.safeName(""));
        assertEquals("file", FileMessage.safeName(null));
        assertEquals("a_b_c.txt", FileMessage.safeName("a<b>c.txt"));
    }

    @Test
    void controlAndBidiCharactersAreNeutralised() {
        // U+202E (right-to-left override) would display "invoice_exe.txt" for "invoice_txt.exe"
        String sneaky = "invoice_\u202Etxt.exe";
        String safe = FileMessage.safeName(sneaky);
        assertFalse(safe.contains("\u202E"));
        assertTrue(safe.endsWith(".exe"));
        assertFalse(FileMessage.safeName("a\u0000b\nc").chars().anyMatch(Character::isISOControl));
    }

    @Test
    void longNamesAreTruncatedToTheByteLimit() {
        FileMessage f = file("ğ".repeat(300) + ".txt", "", 1);
        assertTrue(f.name().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= FileMessage.MAX_NAME_BYTES);
    }

    @Test
    void onlyRealImagesArePreviewed() {
        assertTrue(file("foto.jpg", "image/jpeg", 1000).isPreviewableImage());
        assertFalse(file("foto.jpg", "application/octet-stream", 1000).isPreviewableImage());
        assertFalse(file("foto.svg", "image/svg+xml", 1000).isPreviewableImage());
        assertFalse(file("huge.png", "image/png", 11L * 1024 * 1024).isPreviewableImage());
    }

    @Test
    void toStringNeverShowsTheKey() {
        String text = file("x.txt", "", 1).toString();
        assertFalse(text.toLowerCase().contains(HexFormat.of().formatHex(new byte[] {0x5a, 0x5a})));
        assertFalse(text.contains("key"));
    }
}
