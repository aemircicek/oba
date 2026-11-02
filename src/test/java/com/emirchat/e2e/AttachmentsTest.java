package com.emirchat.e2e;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AttachmentsTest {
    private static final byte[] FILE = "plans for MobilCAD, version 3".getBytes(StandardCharsets.UTF_8);

    @Test
    void sealedFileOpensWithItsKeyAndDigest() {
        Attachments.Sealed s = Attachments.seal(FILE);
        assertEquals(FILE.length + Attachments.OVERHEAD, s.ciphertext().length);
        assertArrayEquals(FILE, Attachments.open(s.ciphertext(), s.key(), s.digest()));
    }

    @Test
    void ciphertextDoesNotContainThePlaintext() {
        Attachments.Sealed s = Attachments.seal(FILE);
        assertFalse(new String(s.ciphertext(), StandardCharsets.ISO_8859_1).contains("MobilCAD"));
    }

    @Test
    void everyFileGetsAFreshKey() {
        Attachments.Sealed a = Attachments.seal(FILE);
        Attachments.Sealed b = Attachments.seal(FILE);
        assertFalse(Arrays.equals(a.key(), b.key()));
        assertFalse(Arrays.equals(a.ciphertext(), b.ciphertext()));
    }

    @Test
    void aSwappedBlobIsRejectedByTheDigest() {
        Attachments.Sealed real = Attachments.seal(FILE);
        Attachments.Sealed other = Attachments.seal("something else entirely".getBytes(StandardCharsets.UTF_8));
        assertThrows(SecurityException.class, () -> Attachments.open(other.ciphertext(), real.key(), real.digest()));
    }

    @Test
    void tamperingIsRejectedEvenWithAMatchingDigest() {
        Attachments.Sealed s = Attachments.seal(FILE);
        byte[] tampered = s.ciphertext();
        tampered[tampered.length - 1] ^= 1;
        assertThrows(SecurityException.class, () -> Attachments.open(tampered, s.key(), s.digest()));
        // an attacker who also recomputes the digest still fails GCM authentication
        assertThrows(SecurityException.class, () -> Attachments.open(tampered, s.key(), Attachments.sha256(tampered)));
    }

    @Test
    void aWrongKeyIsRejected() {
        Attachments.Sealed s = Attachments.seal(FILE);
        byte[] wrongKey = s.key();
        wrongKey[0] ^= 1;
        assertThrows(SecurityException.class, () -> Attachments.open(s.ciphertext(), wrongKey, s.digest()));
    }

    @Test
    void sizeLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> Attachments.seal(new byte[(int) Attachments.MAX_FILE_BYTES + 1]));
        assertThrows(SecurityException.class, () -> Attachments.open(new byte[3], new byte[32], new byte[32]));
        Attachments.Sealed empty = Attachments.seal(new byte[0]);
        assertEquals(0, Attachments.open(empty.ciphertext(), empty.key(), empty.digest()).length);
    }
}
