package com.emirchat.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VideoRateTest {
    @Test
    void stepsDownFastAndUpSlowly() {
        VideoRate r = new VideoRate();
        assertEquals(1.0, r.share());
        assertTrue(r.report(120, 0), "12 % lost: down");
        assertEquals(0.7, r.share());
        assertFalse(r.report(200, 500), "not twice within two seconds");
        assertTrue(r.report(400, 2_500), "heavy loss: two steps");
        assertEquals(0.35, r.share());
        assertTrue(r.halfFrameRate());
        assertFalse(r.report(0, 3_000), "a clean second is not enough");
        assertFalse(r.report(40, 6_000), "some loss holds the step");
        assertFalse(r.report(0, 11_000));
        assertTrue(r.report(0, 12_100), "six clean seconds: one step up");
        assertEquals(0.5, r.share());
        assertFalse(r.halfFrameRate());
        r.reset();
        assertEquals(1.0, r.share());
    }

    @Test
    void neverBelowTheLastStep() {
        VideoRate r = new VideoRate();
        for (int i = 0; i < 10; i++) r.report(900, i * 3_000L);
        assertEquals(0.25, r.share());
        assertFalse(r.localDrop(100_000));
    }

    @Test
    void countsLostChunksAndSkippedFrames() {
        VideoRate.Loss loss = new VideoRate.Loss();
        assertEquals(-1, loss.take(), "nothing arrived");
        for (int f = 0; f < 10; f++) for (int i = 0; i < 4; i++) loss.chunk(f, 4);
        assertEquals(0, loss.take());
        // frame 10: 2 of 4 chunks; frames 11 and 12 never arrive; 13..15 whole
        loss.chunk(10, 4);
        loss.chunk(10, 4);
        for (int f = 13; f < 16; f++) for (int i = 0; i < 4; i++) loss.chunk(f, 4);
        int l = loss.take();
        // settled: frame 10 (2 of 4), the two skipped frames (one chunk each), 13..15 (12 of 12): 4 lost of 18
        assertEquals(4 * 1000 / 18, l);
    }

    @Test
    void reportsRoundTrip() {
        assertEquals(237, VideoPackets.reportedLoss(VideoPackets.report(237)));
        assertEquals(1000, VideoPackets.reportedLoss(VideoPackets.report(5000)));
        assertEquals(-1, VideoPackets.reportedLoss(new byte[] {VideoPackets.KEYFRAME_PLEASE}));
    }
}
