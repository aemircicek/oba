package com.emirchat.video;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class VideoTest {
    /** A moving test picture: a gradient with a bright square that travels with {@code t}. */
    static VideoFrame pattern(int w, int h, int t) {
        byte[] p = new byte[VideoFrame.size(w, h)];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean square = Math.abs(x - (t * 7) % w) < 24 && Math.abs(y - h / 2) < 24;
                p[y * w + x] = (byte) (square ? 235 : 16 + (x + y) * 160 / (w + h));
            }
        }
        for (int i = w * h; i < p.length; i++) p[i] = (byte) 128;
        return new VideoFrame(w, h, p);
    }

    /** Average absolute difference of the luma planes. */
    static double lumaError(VideoFrame a, VideoFrame b) {
        long sum = 0;
        int n = a.width() * a.height();
        for (int i = 0; i < n; i++) sum += Math.abs((a.i420()[i] & 0xff) - (b.i420()[i] & 0xff));
        return (double) sum / n;
    }

    // ------------------------------------------------------------------ sizes

    @Test
    void picturesKeepTheirShapeAndShrinkOnlyToFit() {
        assertArrayEquals(new int[] {480, 360}, VideoFrame.fit(640, 480, 480, 360));
        assertArrayEquals(new int[] {480, 270}, VideoFrame.fit(1920, 1080, 480, 360));
        assertArrayEquals(new int[] {320, 240}, VideoFrame.fit(320, 240, 480, 360), "never larger");
        assertArrayEquals(new int[] {1280, 800}, VideoFrame.fit(2880, 1800, 1280, 800));
        int[] odd = VideoFrame.fit(333, 211, 480, 360);
        assertEquals(0, odd[0] % 2);
        assertEquals(0, odd[1] % 2);
        assertThrows(IllegalArgumentException.class, () -> new VideoFrame(3, 2, new byte[9]));
    }

    // ------------------------------------------------------------------ packets

    @Test
    void framesSplitIntoRelaySizedChunksAndComeBackWhole() {
        byte[] big = new byte[3000];
        new Random(1).nextBytes(big);
        List<byte[]> chunks = VideoPackets.split(0, big, VideoPackets.FLAG_KEY);
        assertEquals(4, chunks.size());
        for (byte[] c : chunks) assertTrue(c.length <= VideoPackets.CHUNK_DATA + 8);
        VideoPackets.Assembler a = new VideoPackets.Assembler();
        List<VideoPackets.Frame> out = new ArrayList<>();
        for (byte[] c : chunks) out.addAll(a.add(c));
        assertEquals(1, out.size());
        assertArrayEquals(big, out.get(0).data());
        assertTrue(out.get(0).key());
        assertFalse(a.needKeyFrame());
    }

    @Test
    void nothingIsShownBeforeTheFirstKeyFrame() {
        VideoPackets.Assembler a = new VideoPackets.Assembler();
        assertTrue(a.add(VideoPackets.split(5, new byte[10], 0).get(0)).isEmpty(), "a delta frame needs what came before");
        assertTrue(a.needKeyFrame());
        assertEquals(1, a.add(VideoPackets.split(6, new byte[10], VideoPackets.FLAG_KEY).get(0)).size());
        assertEquals(1, a.add(VideoPackets.split(7, new byte[10], 0).get(0)).size(), "and builds on it after");
    }

    @Test
    void aLostChunkHoldsEverythingBackUntilAKeyFrame() {
        VideoPackets.Assembler a = new VideoPackets.Assembler();
        a.add(VideoPackets.split(0, new byte[10], VideoPackets.FLAG_KEY).get(0));
        List<byte[]> lost = VideoPackets.split(1, new byte[2000], 0);
        a.add(lost.get(0)); // the second chunk never comes
        for (int f = 2; f < 8; f++) assertTrue(a.add(VideoPackets.split(f, new byte[10], 0).get(0)).isEmpty(), "frame " + f);
        assertTrue(a.needKeyFrame(), "the sender is asked for a fresh start");
        assertFalse(a.needKeyFrame(), "once");
        List<VideoPackets.Frame> fresh = a.add(VideoPackets.split(8, new byte[10], VideoPackets.FLAG_KEY).get(0));
        assertEquals(1, fresh.size());
        assertEquals(8, fresh.get(0).number());
    }

    @Test
    void oldRepeatedAndMalformedChunksAreIgnored() {
        VideoPackets.Assembler a = new VideoPackets.Assembler();
        byte[] key = VideoPackets.split(3, new byte[10], VideoPackets.FLAG_KEY).get(0);
        assertEquals(1, a.add(key).size());
        assertTrue(a.add(key).isEmpty(), "a repeat");
        assertTrue(a.add(VideoPackets.split(2, new byte[10], VideoPackets.FLAG_KEY).get(0)).isEmpty(), "older");
        assertTrue(a.add(new byte[3]).isEmpty());
        byte[] bad = key.clone();
        bad[5] = 9; // index 9 of 1
        assertTrue(a.add(bad).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> VideoPackets.split(0, new byte[VideoPackets.CHUNK_DATA * 256], 0));
    }

    @Test
    void thePacerSpreadsPacketsAndRefusesWhatWouldWaitTooLong() {
        Pacer p = new Pacer(40, 40); // 25 ms apart, at most a second waiting
        long[] first = p.book(3, 0);
        assertArrayEquals(new long[] {0, 25_000_000, 50_000_000}, first);
        long[] next = p.book(2, 10_000_000);
        assertEquals(65_000_000, next[0], "after the ones already booked");
        assertNull(p.book(40, 10_000_000), "a frame of 40 packets would make more than 40 wait");
        p.reset();
        assertNotNull(p.book(40, 0));
        p.reset();
        assertEquals(100, p.book(100, 0).length, "with nothing waiting even a frame over the limit goes (a large key frame)");
        assertNull(p.book(1, 0), "but nothing more until it is out");
    }

    // ------------------------------------------------------------------ codec and conversion

    @Test
    void vp8CarriesAMovingPictureInAFewKilobytesAFrame() {
        int w = 480, h = 270;
        try (Vp8.Encoder encoder = new Vp8.Encoder(w, h, 15, 280_000); Vp8.Decoder decoder = new Vp8.Decoder()) {
            int bytes = 0;
            VideoFrame last = null;
            VideoFrame sent = null;
            for (int t = 0; t < 30; t++) {
                sent = pattern(w, h, t);
                Vp8.Packet packet = encoder.encode(sent);
                if (packet == null) continue;
                if (t == 0) assertTrue(packet.key(), "the first frame stands on its own");
                bytes += packet.data().length;
                last = decoder.decode(packet.data());
                assertNotNull(last, "frame " + t);
            }
            assertEquals(w, last.width());
            assertEquals(h, last.height());
            assertTrue(lumaError(sent, last) < 12, "the picture comes through: error " + lumaError(sent, last));
            assertTrue(bytes / 30 < 280_000 / 8 / 15 * 3, "near the bit rate: " + bytes / 30 + " bytes a frame");

            encoder.keyFrame();
            assertTrue(encoder.encode(pattern(w, h, 31)).key(), "a key frame when asked for one");
        }
    }

    @Test
    void theDecoderSurvivesGarbage() {
        try (Vp8.Decoder decoder = new Vp8.Decoder(); Vp8.Encoder encoder = new Vp8.Encoder(64, 48, 15, 100_000)) {
            Random random = new Random(3);
            for (int i = 0; i < 500; i++) {
                byte[] junk = new byte[1 + random.nextInt(3000)];
                random.nextBytes(junk);
                decoder.decode(junk); // null or a picture; never a crash
            }
            assertNull(decoder.decode(new byte[0]));
            assertNotNull(decoder.decode(encoder.encode(pattern(64, 48, 0)).data()), "and still decodes real frames");
        }
    }

    @Test
    void picturesConvertBothWays() {
        try (Scaler scaler = new Scaler()) {
            VideoFrame original = pattern(64, 48, 2);
            byte[] bgra = scaler.toBgra(original);
            assertEquals(64 * 48 * 4, bgra.length);
            assertEquals((byte) 255, bgra[3], "opaque");
            VideoFrame back = scaler.bgraToI420(bgra, 64, 48, 64, 48);
            assertTrue(lumaError(original, back) < 4, "error " + lumaError(original, back));
            VideoFrame half = scaler.bgraToI420(bgra, 64, 48, 32, 24);
            assertEquals(32, half.width());
            assertEquals(24, half.height());
        }
    }
}
