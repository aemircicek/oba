package com.emirchat.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.emirchat.video.VideoFrame;
import com.emirchat.video.VideoSource;
import java.security.KeyPair;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class CallVideoTest {
    /** A camera that records whether anyone opened it. */
    private static final class Camera implements VideoSource {
        volatile boolean opened;

        @Override public boolean screen() { return false; }

        @Override public int fps() { return 15; }

        @Override public void start(Consumer<VideoFrame> frames) { opened = true; }

        @Override public void stop() { opened = false; }
    }

    @Test
    void aCallThatHasEndedNeverOpensTheCamera() {
        KeyPair a = CallCrypto.newCallKey();
        KeyPair b = CallCrypto.newCallKey();
        CallVideo video = new CallVideo(CallCrypto.deriveVideo(new byte[CallCrypto.CALL_ID_LENGTH], a,
                com.emirchat.crypto.X25519.encode(b.getPublic()), true), sealed -> {});
        video.close(); // the other side hung up while the camera button was being pressed
        Camera camera = new Camera();
        assertThrows(IllegalStateException.class, () -> video.start(camera));
        assertFalse(camera.opened, "the camera was never opened");
        assertFalse(video.sending());
    }

    @Test
    void endingTheCallClosesTheCamera() {
        KeyPair a = CallCrypto.newCallKey();
        KeyPair b = CallCrypto.newCallKey();
        CallVideo video = new CallVideo(CallCrypto.deriveVideo(new byte[CallCrypto.CALL_ID_LENGTH], a,
                com.emirchat.crypto.X25519.encode(b.getPublic()), true), sealed -> {});
        Camera camera = new Camera();
        video.start(camera);
        video.close();
        assertFalse(camera.opened, "closed with the call");
        assertFalse(video.sending());
    }

    /** Two ends of a call joined by a link that loses every third packet (or none). */
    private static double qualityAfter(boolean lossy, long millis) throws Exception {
        KeyPair a = CallCrypto.newCallKey();
        KeyPair b = CallCrypto.newCallKey();
        byte[] callId = new byte[CallCrypto.CALL_ID_LENGTH];
        CallVideo[] ends = new CallVideo[2];
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        ends[0] = new CallVideo(CallCrypto.deriveVideo(callId, a, com.emirchat.crypto.X25519.encode(b.getPublic()), true),
                sealed -> {
                    // only the video going from 0 to 1 is lossy; reports going back always arrive
                    if (!lossy || n.incrementAndGet() % 3 != 0) ends[1].receive(sealed);
                });
        ends[1] = new CallVideo(CallCrypto.deriveVideo(callId, b, com.emirchat.crypto.X25519.encode(a.getPublic()), false),
                sealed -> ends[0].receive(sealed));
        try {
            ends[0].start(new com.emirchat.video.PatternSource(320, 240, 15, false));
            Thread.sleep(millis);
            return ends[0].quality();
        } finally {
            ends[0].close();
            ends[1].close();
        }
    }

    @Test
    void aLossyLinkMakesTheSenderUseLess() throws Exception {
        double q = qualityAfter(true, 5_000);
        assertTrue(q < 1.0, "quality stayed at " + q);
    }

    @Test
    void aCleanLinkKeepsTheFullRate() throws Exception {
        assertEquals(1.0, qualityAfter(false, 3_500));
    }
}
