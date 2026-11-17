package com.emirchat.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The codec, the rate conversion, and how a call settles on μ-law or Opus with each kind of peer. */
class OpusTest {
    private static final int RATE = (int) AudioDevice.SAMPLE_RATE;

    /** 20 ms of a tone at {@code hz}, {@code rate} samples a second, starting {@code at} samples in. */
    static byte[] tone(double hz, int rate, int at, double amplitude) {
        short[] s = new short[rate / 50];
        for (int i = 0; i < s.length; i++) s[i] = Pcm.clip(Math.sin(2 * Math.PI * hz * (at + i) / rate) * amplitude);
        return Pcm.bytes(s, s.length);
    }

    /** Average absolute sample value. */
    static double level(byte[] pcm) {
        double sum = 0;
        short[] s = Pcm.samples(pcm);
        for (short v : s) sum += Math.abs(v);
        return sum / s.length;
    }

    // ------------------------------------------------------------------ rate conversion

    @Test
    void speechPassesTheConversionBothWays() {
        Resample down = Resample.down();
        Resample up = Resample.up();
        byte[] last = null;
        for (int f = 0; f < 10; f++) {
            byte[] eight = down.process(tone(1000, RATE, f * RATE / 50, 10000));
            assertEquals(160 * 2, eight.length, "20 ms at 8 kHz");
            last = up.process(eight);
            assertEquals(320 * 2, last.length, "and back to 20 ms at 16 kHz");
        }
        double expected = level(tone(1000, RATE, 0, 10000));
        assertTrue(Math.abs(level(last) - expected) < 0.1 * expected, "a 1 kHz tone keeps its level: " + level(last));
    }

    @Test
    void whatEightKilohertzCannotCarryIsFilteredOutNotFoldedBack() {
        Resample down = Resample.down();
        byte[] eight = null;
        for (int f = 0; f < 10; f++) eight = down.process(tone(6000, RATE, f * RATE / 50, 10000));
        // without the filter a 6 kHz tone would come out of 8 kHz audio as a loud 2 kHz one
        assertTrue(level(eight) < 0.05 * 6366, "6 kHz is removed: " + level(eight));
    }

    @Test
    void frameEdgesDoNotClick() {
        Resample up = Resample.up();
        short[] all = new short[0];
        for (int f = 0; f < 6; f++) {
            short[] s = Pcm.samples(up.process(tone(400, 8000, f * 160, 10000)));
            short[] joined = java.util.Arrays.copyOf(all, all.length + s.length);
            System.arraycopy(s, 0, joined, all.length, s.length);
            all = joined;
        }
        // a 400 Hz tone at 16 kHz moves at most about 2π·400/16000·10000 ≈ 1570 per sample
        for (int i = 400; i < all.length; i++) assertTrue(Math.abs(all[i] - all[i - 1]) < 1800, "jump at " + i);
    }

    // ------------------------------------------------------------------ codec

    @Test
    void opusCarriesSpeechInAFewDozenBytes() {
        OpusCodec.Encoder encoder = new OpusCodec.Encoder();
        OpusCodec.Decoder decoder = new OpusCodec.Decoder();
        int total = 0;
        byte[] out = null;
        for (int f = 0; f < 50; f++) {
            byte[] packet = encoder.encode(tone(440, RATE, f * RATE / 50, 8000));
            total += packet.length;
            out = decoder.decode(packet);
            assertNotNull(out);
            assertEquals(AudioDevice.FRAME_BYTES, out.length);
        }
        assertTrue(total / 50 < 120, "about 24 kbit/s, not μ-law's 64: " + total / 50 + " bytes a frame");
        assertTrue(level(out) > 0.75 * level(tone(440, RATE, 0, 8000)), "the tone comes through: " + level(out));
    }

    @Test
    void damagedOrHostilePacketsAreSilenceNotACrash() {
        OpusCodec.Decoder decoder = new OpusCodec.Decoder();
        assertNull(decoder.decode(new byte[0]));
        assertNull(decoder.decode(new byte[OpusCodec.MAX_PACKET + 1]));
        Random random = new Random(7);
        for (int i = 0; i < 2000; i++) {
            byte[] junk = new byte[1 + random.nextInt(OpusCodec.MAX_PACKET)];
            random.nextBytes(junk);
            byte[] pcm = decoder.decode(junk); // null or some samples; never an exception
            if (pcm != null) assertEquals(0, pcm.length % 2);
        }
        assertNotNull(decoder.decode(new OpusCodec.Encoder().encode(new byte[AudioDevice.FRAME_BYTES])), "and it still works");
    }

    // ------------------------------------------------------------------ calls between versions

    private record Pair(Calls.Link caller, Calls.Link callee, CallCrypto oldCallee, CallCrypto oldCaller) {}

    private static Pair pair() {
        byte[] id = new byte[CallCrypto.CALL_ID_LENGTH];
        new SecureRandom().nextBytes(id);
        KeyPair a = CallCrypto.newCallKey();
        KeyPair b = CallCrypto.newCallKey();
        byte[] pa = com.emirchat.crypto.X25519.encode(a.getPublic());
        byte[] pb = com.emirchat.crypto.X25519.encode(b.getPublic());
        return new Pair(
                new Calls.Link(CallCrypto.derive(id, a, pb, true), CallCrypto.deriveOpus(id, a, pb, true)),
                new Calls.Link(CallCrypto.derive(id, b, pa, false), CallCrypto.deriveOpus(id, b, pa, false)),
                CallCrypto.derive(id, b, pa, false),  // the callee as a version before Opus sees it
                CallCrypto.derive(id, a, pb, true));  // the caller as a version before Opus sees it
    }

    @Test
    void twoCurrentVersionsMoveToOpusAfterTheFirstFrame() {
        Pair p = pair();
        List<byte[]> first = p.caller().send(tone(500, RATE, 0, 8000));
        assertEquals(2, first.size(), "μ-law, and the note that we play Opus");
        assertEquals(AudioDevice.FRAME_BYTES, p.callee().receive(first.get(0)).length);
        assertNull(p.callee().receive(first.get(1)), "the note itself is not sound");
        assertTrue(p.callee().peerOpus);

        List<byte[]> reply = p.callee().send(tone(500, RATE, 0, 8000));
        assertEquals(1, reply.size(), "the callee answers in Opus straight away");
        assertEquals(AudioDevice.FRAME_BYTES, p.caller().receive(reply.get(0)).length);
        assertTrue(p.caller().peerOpus);
        List<byte[]> next = p.caller().send(tone(500, RATE, 320, 8000));
        assertEquals(1, next.size());
        assertTrue(next.get(0).length < 8 + 16 + 1 + 120, "an Opus packet, much smaller than μ-law's 160 bytes");
    }

    @Test
    void videoPacketsAreNoiseToAnOlderVersionAndNeverPlayedAsSound() {
        byte[] id = new byte[CallCrypto.CALL_ID_LENGTH];
        new SecureRandom().nextBytes(id);
        KeyPair a = CallCrypto.newCallKey();
        KeyPair b = CallCrypto.newCallKey();
        byte[] pa = com.emirchat.crypto.X25519.encode(a.getPublic());
        byte[] pb = com.emirchat.crypto.X25519.encode(b.getPublic());
        CallCrypto video = CallCrypto.deriveVideo(id, a, pb, true);
        byte[] packet = video.seal(com.emirchat.video.VideoPackets.split(0, new byte[500], 1).get(0));
        assertNull(CallCrypto.derive(id, b, pa, false).open(packet), "a version before video cannot open it: it drops it");
        Calls.Link current = new Calls.Link(CallCrypto.derive(id, b, pa, false), CallCrypto.deriveOpus(id, b, pa, false));
        assertNull(current.receive(packet), "and a current version never takes it for audio");
    }

    @Test
    void anOlderVersionOnlyEverGetsMuLawItCanPlay() {
        Pair p = pair();
        int heard = 0;
        for (int f = 0; f < 200; f++) {
            for (byte[] packet : p.caller().send(tone(500, RATE, f * 320, 8000))) {
                byte[] ulaw = p.oldCallee().open(packet);
                if (ulaw == null) continue; // the Opus note: it cannot authenticate it, so it drops it
                assertEquals(160, ulaw.length, "20 ms of 8 kHz μ-law, as before");
                heard++;
            }
        }
        assertEquals(200, heard, "every frame reached the older version");
        assertTrue(!p.caller().peerOpus, "and we never switched");

        // and what the older version sends is played, at our rate
        byte[] pcm = p.caller().receive(p.oldCaller().seal(Mulaw.encode(tone(500, 8000, 0, 8000))));
        assertNull(pcm, "sealed by the wrong side: refused");
        byte[] fromOld = p.callee().receive(p.oldCaller().seal(Mulaw.encode(tone(500, 8000, 0, 8000))));
        assertNotNull(fromOld);
        assertEquals(AudioDevice.FRAME_BYTES, fromOld.length);
    }
}
