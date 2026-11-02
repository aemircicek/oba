package com.emirchat.call;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CallCryptoTest {
    private static final byte[] CALL = new byte[16];

    private static CallCrypto[] pair() {
        KeyPair a = CallCrypto.newCallKey();
        KeyPair b = CallCrypto.newCallKey();
        return new CallCrypto[] {
                CallCrypto.derive(CALL, a, com.emirchat.crypto.X25519.encode(b.getPublic()), true),
                CallCrypto.derive(CALL, b, com.emirchat.crypto.X25519.encode(a.getPublic()), false)};
    }

    @Test
    void audioFlowsBothWays() {
        CallCrypto[] p = pair();
        byte[] hello = "merhaba".getBytes();
        assertArrayEquals(hello, p[1].open(p[0].seal(hello)));
        assertArrayEquals(hello, p[0].open(p[1].seal(hello)));
    }

    @Test
    void eachDirectionHasItsOwnKey() {
        CallCrypto[] p = pair();
        byte[] sent = p[0].seal(new byte[20]);
        assertNull(p[0].open(sent), "a packet cannot be reflected back to its sender");
    }

    @Test
    void replaysOldAndForgedPacketsAreDropped() {
        CallCrypto[] p = pair();
        byte[] first = p[0].seal(new byte[] {1});
        byte[] second = p[0].seal(new byte[] {2});
        assertArrayEquals(new byte[] {2}, p[1].open(second));
        assertNull(p[1].open(first), "older than the last one: too late or replayed");
        assertNull(p[1].open(second), "the same packet twice");
        byte[] third = p[0].seal(new byte[] {3});
        third[third.length - 1] ^= 1;
        assertNull(p[1].open(third), "modified by the relay");
        assertArrayEquals(new byte[] {4}, p[1].open(p[0].seal(new byte[] {4})), "the call goes on");
    }

    @Test
    void anotherCallIdGivesOtherKeys() {
        KeyPair a = CallCrypto.newCallKey();
        KeyPair b = CallCrypto.newCallKey();
        byte[] otherCall = new byte[16];
        otherCall[0] = 1;
        CallCrypto sender = CallCrypto.derive(CALL, a, com.emirchat.crypto.X25519.encode(b.getPublic()), true);
        CallCrypto wrong = CallCrypto.derive(otherCall, b, com.emirchat.crypto.X25519.encode(a.getPublic()), false);
        assertNull(wrong.open(sender.seal(new byte[8])));
    }

    @Test
    void mulawKeepsSpeechCloseAndHalvesTheSize() {
        byte[] pcm = new byte[640];
        for (int i = 0; i < 320; i++) {
            short s = (short) (Math.sin(i / 8.0) * 12_000);
            pcm[2 * i] = (byte) s;
            pcm[2 * i + 1] = (byte) (s >> 8);
        }
        byte[] ulaw = Mulaw.encode(pcm);
        assertEquals(320, ulaw.length);
        byte[] back = Mulaw.decode(ulaw);
        for (int i = 0; i < 320; i++) {
            int original = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
            int decoded = (short) ((back[2 * i] & 0xff) | (back[2 * i + 1] << 8));
            assertTrue(Math.abs(original - decoded) <= Math.max(16, Math.abs(original) / 16), original + " vs " + decoded);
        }
        assertEquals(0, Mulaw.decodeSample(Mulaw.encodeSample(0)));
        assertFalse(Arrays.equals(pcm, back), "lossy, as expected");
    }
}
