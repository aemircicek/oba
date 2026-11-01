package com.emirchat.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.emirchat.crypto.Identity;
import java.net.ProtocolException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatTest {
    @Test
    void messageRoundTrip() throws ProtocolException {
        ChatMessage m = ChatMessage.text("Selam Efe, MobilCAD'e bakalım mı?");
        assertEquals(m, ChatMessage.decode(m.encode()));
    }

    @Test
    void rejectsTrailingGarbageAndUnknownTypes() {
        byte[] encoded = ChatMessage.text("x").encode();
        byte[] longer = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(ProtocolException.class, () -> ChatMessage.decode(longer));
        encoded[0] = 99;
        assertThrows(ProtocolException.class, () -> ChatMessage.decode(encoded));
    }

    @Test
    void rejectsOversizedText() {
        assertThrows(IllegalArgumentException.class, () -> ChatMessage.text("x".repeat(ChatMessage.MAX_TEXT_BYTES + 1)));
    }

    @Test
    void sanitizerNeutralizesTerminalEscapes() {
        String attack = "hi\u001b[2J\u001b]0;pwned\u0007\r\nfake: prompt‮gnp.exe";
        String safe = TerminalSanitizer.sanitize(attack);
        assertEquals(-1, safe.indexOf('\u001b'));
        assertEquals(-1, safe.indexOf('\n'));
        assertEquals(-1, safe.indexOf('‮'));
        assertEquals("Türkçe ğüşıöç 👋", TerminalSanitizer.sanitize("Türkçe ğüşıöç 👋"));
    }

    @Test
    void identityPersistsWithOwnerOnlyPermissions(@TempDir Path dir) throws Exception {
        Path home = dir.resolve("home");
        Identity first = Identity.loadOrCreate(home);
        Identity second = Identity.loadOrCreate(home);
        assertEquals(first.fingerprint(), second.fingerprint());
        if (home.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(home.resolve("identity.key"))));
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(home)));
        }
    }
}
