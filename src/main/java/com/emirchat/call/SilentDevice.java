package com.emirchat.call;

import java.util.function.Consumer;

/**
 * No microphone and no speaker: calls connect and stay silent. Used when started with
 * {@code -Dsecurechat.audio=none} (screenshots and demos that must not open the real microphone).
 * Like a microphone in a quiet room it still delivers a frame of silence every 20 ms, so a call
 * behaves as a real one (the two sides learn what the other plays, video can start).
 */
public final class SilentDevice implements AudioDevice {
    private volatile Thread ticker;

    @Override
    public synchronized void start(Consumer<byte[]> captured) {
        Thread t = Thread.ofPlatform().daemon().name("silence").start(() -> {
            long next = System.nanoTime();
            try {
                while (ticker == Thread.currentThread()) {
                    captured.accept(new byte[FRAME_BYTES]);
                    next += 20_000_000L;
                    long wait = next - System.nanoTime();
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ticker = t;
    }

    @Override public void play(byte[] frame) {}

    @Override
    public synchronized void stop() {
        Thread t = ticker;
        ticker = null;
        if (t != null) t.interrupt();
    }
}
