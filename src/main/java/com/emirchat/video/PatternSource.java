package com.emirchat.video;

import java.util.function.Consumer;

/**
 * A moving test picture instead of a camera: for tests and screenshots, which must not open the
 * real camera (started with {@code -Dsecurechat.video=pattern}).
 */
public final class PatternSource implements VideoSource {
    private final int width;
    private final int height;
    private final int fps;
    private final boolean screen;
    private volatile boolean running;
    private Thread thread;

    public PatternSource(int width, int height, int fps, boolean screen) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.screen = screen;
    }

    @Override public boolean screen() { return screen; }

    @Override public int fps() { return fps; }

    /** Frame {@code t} of the pattern: warm stripes of sky and sand and a bright disc that moves across. */
    public static VideoFrame frame(int w, int h, int t) {
        byte[] p = new byte[VideoFrame.size(w, h)];
        int cx = (t * 6) % w;
        int cy = h / 2 + (int) (Math.sin(t / 8.0) * h / 6);
        int r = Math.max(8, h / 8);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx = x - cx, dy = y - cy;
                boolean sun = dx * dx + dy * dy < r * r;
                p[y * w + x] = (byte) (sun ? 230 : y < h * 2 / 3 ? 40 + y * 60 / h : 140 + (x / 16 % 2) * 20);
            }
        }
        int u = w * h, v = u + (w / 2) * (h / 2);
        for (int y = 0; y < h / 2; y++) {
            for (int x = 0; x < w / 2; x++) {
                boolean sky = y < h / 3;
                p[u + y * (w / 2) + x] = (byte) (sky ? 150 : 100);
                p[v + y * (w / 2) + x] = (byte) (sky ? 110 : 160);
            }
        }
        return new VideoFrame(w, h, p);
    }

    @Override
    public synchronized void start(Consumer<VideoFrame> frames) {
        running = true;
        thread = Thread.ofPlatform().daemon().name("pattern").start(() -> {
            int t = 0;
            try {
                while (running) {
                    frames.accept(frame(width, height, t++));
                    Thread.sleep(1000 / fps);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override
    public synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
    }
}
