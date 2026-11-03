package com.emirchat.call;

import java.util.function.DoubleConsumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/** Plays one voice message at a time through the default speaker. */
public final class VoicePlayer {
    private static final AudioFormat FORMAT = new AudioFormat(AudioDevice.SAMPLE_RATE, 16, 1, true, false);
    private static VoicePlayer current;

    private final byte[] pcm;
    private volatile boolean running;
    private volatile int position; // byte offset into pcm
    private Thread thread;

    public VoicePlayer(byte[] pcm) {
        this.pcm = pcm;
    }

    /**
     * Plays from {@code fraction} (0-1) of the way in; any other playing message stops first.
     *
     * @param progress receives the position (0-1) as it plays, on the player thread
     * @param done     runs when playback reaches the end (not when stopped)
     */
    public void play(double fraction, DoubleConsumer progress, Runnable done) {
        synchronized (VoicePlayer.class) {
            if (current != null && current != this) current.stop();
            current = this;
        }
        stop();
        position = (int) (Math.max(0, Math.min(1, fraction)) * pcm.length) & ~1;
        SourceDataLine line;
        try {
            line = AudioSystem.getSourceDataLine(FORMAT);
            line.open(FORMAT, AudioDevice.FRAME_BYTES * 8);
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            throw new IllegalStateException("No speaker available: " + e.getMessage(), e);
        }
        running = true;
        line.start();
        thread = Thread.ofPlatform().daemon().name("voice-player").start(() -> {
            try {
                while (running && position < pcm.length) {
                    int n = Math.min(AudioDevice.FRAME_BYTES * 2, pcm.length - position);
                    line.write(pcm, position, n);
                    position += n;
                    progress.accept((double) position / pcm.length);
                }
                if (running) {
                    line.drain();
                    running = false;
                    position = 0;
                    done.run();
                }
            } finally {
                line.stop();
                line.close();
            }
        });
    }

    public boolean playing() {
        return running;
    }

    /** Pauses; {@link #fraction} remembers where. */
    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public double fraction() {
        return pcm.length == 0 ? 0 : (double) position / pcm.length;
    }
}
