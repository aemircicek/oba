package com.emirchat.call;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class VoiceCryptoTest {
    private static final byte[] AUDIO = "160 bytes of μ-law speech, more or less".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void roundTripAndKeyEncoding() {
        VoiceCrypto.Key key = VoiceCrypto.newKey();
        VoiceCrypto.Key copy = VoiceCrypto.decodeKey(VoiceCrypto.encodeKey(key));
        assertEquals(key.id(), copy.id());
        assertArrayEquals(key.secret(), copy.secret());
        VoiceCrypto.Sender efe = new VoiceCrypto.Sender(key);
        VoiceCrypto.Receiver emir = new VoiceCrypto.Receiver();
        emir.accept("efe", copy);
        assertArrayEquals(AUDIO, emir.open(7, "efe", efe.seal(7, "efe", AUDIO)));
        assertArrayEquals(AUDIO, emir.open(7, "efe", efe.seal(7, "efe", AUDIO)));
    }

    @Test
    void replayRewindTamperingAndImpersonationFail() {
        VoiceCrypto.Key key = VoiceCrypto.newKey();
        VoiceCrypto.Sender efe = new VoiceCrypto.Sender(key);
        VoiceCrypto.Receiver emir = new VoiceCrypto.Receiver();
        emir.accept("efe", key);
        byte[] first = efe.seal(7, "efe", AUDIO);
        byte[] second = efe.seal(7, "efe", AUDIO);
        emir.open(7, "efe", second);
        assertThrows(SecurityException.class, () -> emir.open(7, "efe", second), "replay");
        assertThrows(SecurityException.class, () -> emir.open(7, "efe", first), "older packet");
        byte[] third = efe.seal(7, "efe", AUDIO);
        third[third.length - 1] ^= 1;
        assertThrows(SecurityException.class, () -> emir.open(7, "efe", third), "altered");
        emir.accept("ayse", key); // even with the same key, a packet cannot be passed off as someone else's
        assertThrows(SecurityException.class, () -> emir.open(7, "ayse", efe.seal(7, "efe", AUDIO)));
        assertThrows(SecurityException.class, () -> emir.open(8, "efe", efe.seal(7, "efe", AUDIO)), "other room");
        assertThrows(SecurityException.class, () -> emir.open(7, "stranger", efe.seal(7, "stranger", AUDIO)), "no key");
        assertThrows(SecurityException.class, () -> emir.open(7, "efe", new byte[5]));
        assertThrows(SecurityException.class, () -> VoiceCrypto.decodeKey("not base64 at all!"));
    }

    @Test
    void oldKeysExpireAfterRotations() {
        VoiceCrypto.Receiver emir = new VoiceCrypto.Receiver();
        VoiceCrypto.Key first = VoiceCrypto.newKey();
        VoiceCrypto.Sender old = new VoiceCrypto.Sender(first);
        emir.accept("efe", first);
        byte[] late = old.seal(1, "efe", AUDIO);
        for (int i = 0; i < 3; i++) emir.accept("efe", VoiceCrypto.newKey());
        assertThrows(SecurityException.class, () -> emir.open(1, "efe", late), "only the latest keys are kept");
        emir.clear();
        assertEquals(false, emir.hasKey("efe"));
    }

    @Test
    void mixingClips() {
        byte[] out = VoiceChannels.mixPcm(new int[] {40000, -40000, 100});
        assertEquals(Short.MAX_VALUE, (short) ((out[0] & 0xff) | (out[1] << 8)));
        assertEquals(Short.MIN_VALUE, (short) ((out[2] & 0xff) | (out[3] << 8)));
        assertEquals(100, (short) ((out[4] & 0xff) | (out[5] << 8)));
    }
}
