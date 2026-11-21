package com.emirchat.video;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The real capture paths on whatever system runs it (CI runs it on Windows, macOS and Linux):
 * {@code mvn test -Dtest=NativeCaptureTest -Doba.native=true}. The camera part runs only where
 * {@code OBA_EXPECT_CAMERA=1} says a camera is there (on Linux CI: a v4l2loopback device fed by
 * FFmpeg); elsewhere it checks that a missing camera is reported, not crashed on.
 */
@EnabledIfSystemProperty(named = "oba.native", matches = "true")
class NativeCaptureTest {
    private static final boolean CAMERA = "1".equals(System.getenv("OBA_EXPECT_CAMERA"));

    @Test
    void ffmpegLoadsAndVp8RoundTripsHere() {
        assertTrue(Ffmpeg.available(), "FFmpeg's native libraries load on " + System.getProperty("os.name"));
        try (Vp8.Encoder enc = new Vp8.Encoder(480, 360, 15, 300_000); Vp8.Decoder dec = new Vp8.Decoder()) {
            VideoFrame last = null;
            VideoFrame sent = null;
            for (int t = 0; t < 10; t++) {
                sent = VideoTest.pattern(480, 360, t);
                Vp8.Packet p = enc.encode(sent);
                if (p != null) last = dec.decode(p.data());
            }
            assertNotNull(last, "decoded a frame");
            assertTrue(VideoTest.lumaError(sent, last) < 30, "the picture survives");
        }
    }

    @Test
    void theCameraDeliversFramesOrSaysWhyNot() throws Exception {
        CameraSource camera = new CameraSource();
        BlockingQueue<VideoFrame> frames = new LinkedBlockingQueue<>();
        try {
            camera.start(frames::add);
        } catch (IllegalStateException e) {
            if (CAMERA) throw new AssertionError("expected a camera here", e);
            System.out.println("camera: not available here (" + e.getMessage() + "), reported cleanly");
            return;
        }
        try {
            VideoFrame f = frames.poll(10, TimeUnit.SECONDS);
            if (CAMERA) assertNotNull(f, "the camera delivered a frame");
            if (f != null) {
                System.out.println("camera: " + f.width() + "x" + f.height());
                assertTrue(f.width() <= CameraSource.MAX_WIDTH && f.height() <= CameraSource.MAX_HEIGHT);
            }
        } finally {
            camera.stop();
        }
    }

    @Test
    void theScreenIsCapturedAndEncoded() throws Exception {
        assumeTrue(!GraphicsEnvironment.isHeadless(), "no screen on this machine");
        ScreenSource screen = new ScreenSource();
        BlockingQueue<VideoFrame> frames = new LinkedBlockingQueue<>();
        screen.start(frames::add);
        try {
            VideoFrame f = frames.poll(10, TimeUnit.SECONDS);
            assertNotNull(f, "a screen frame arrived");
            System.out.println("screen: " + f.width() + "x" + f.height());
            try (Vp8.Encoder enc = new Vp8.Encoder(f.width(), f.height(), ScreenSource.FPS, 300_000)) {
                enc.keyFrame();
                assertNotNull(enc.encode(f), "the screen frame encodes");
            }
        } finally {
            screen.stop();
        }
    }
}
