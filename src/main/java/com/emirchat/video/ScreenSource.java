package com.emirchat.video;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.function.Consumer;

/**
 * The main screen, through Java's own screen capture. A few frames a second at a size where text
 * stays readable: screens change little, so most frames cost almost nothing to send.
 * (macOS asks the user once for permission to record the screen; without it only the desktop
 * picture is captured.)
 */
public final class ScreenSource implements VideoSource {
    public static final int FPS = 5;
    public static final int MAX_WIDTH = 1280;
    public static final int MAX_HEIGHT = 800;

    private volatile boolean running;
    private Thread thread;

    @Override public boolean screen() { return true; }

    @Override public int fps() { return FPS; }

    @Override
    public synchronized void start(Consumer<VideoFrame> frames) {
        Ffmpeg.load();
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("No screen to share");
        Robot robot;
        Rectangle area;
        try {
            robot = new Robot();
            area = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .getDefaultConfiguration().getBounds();
        } catch (java.awt.AWTException | SecurityException e) {
            throw new IllegalStateException("The screen cannot be captured: " + e.getMessage(), e);
        }
        running = true;
        thread = Thread.ofPlatform().daemon().name("screen").start(() -> {
            long gap = 1000 / FPS;
            try (Scaler scaler = new Scaler()) {
                while (running) {
                    long started = System.currentTimeMillis();
                    BufferedImage shot = robot.createScreenCapture(area);
                    BufferedImage rgb = shot.getType() == BufferedImage.TYPE_INT_RGB ? shot : copy(shot);
                    int w = rgb.getWidth() & ~1;
                    int h = rgb.getHeight() & ~1;
                    int[] px = ((DataBufferInt) rgb.getRaster().getDataBuffer()).getData();
                    byte[] bgra = new byte[w * h * 4];
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            int v = px[y * rgb.getWidth() + x];
                            int o = (y * w + x) * 4;
                            bgra[o] = (byte) v;
                            bgra[o + 1] = (byte) (v >> 8);
                            bgra[o + 2] = (byte) (v >> 16);
                            bgra[o + 3] = (byte) 255;
                        }
                    }
                    int[] size = VideoFrame.fit(w, h, MAX_WIDTH, MAX_HEIGHT);
                    frames.accept(scaler.bgraToI420(bgra, w, h, size[0], size[1]));
                    Thread.sleep(Math.max(1, gap - (System.currentTimeMillis() - started)));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                // capture failed: stop sharing
            } finally {
                running = false;
            }
        });
    }

    private static BufferedImage copy(BufferedImage image) {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(image, 0, 0, null);
        return rgb;
    }

    @Override
    public synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
