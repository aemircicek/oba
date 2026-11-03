package com.emirchat.call;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

/** Records a voice message from the default microphone (8 kHz, 16-bit, mono). */
public final class VoiceRecorder {
    private static final AudioFormat FORMAT = new AudioFormat(AudioDevice.SAMPLE_RATE, 16, 1, true, false);
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private volatile boolean running;
    private TargetDataLine line;
    private Thread thread;

    /**
     * Starts recording.
     *
     * @param level receives the loudness (0-1) of every 20 ms, on the recording thread
     * @param full  runs once if the maximum length is reached (the recording then stops by itself)
     * @throws IllegalStateException if there is no usable microphone (or access was refused)
     */
    public synchronized void start(Consumer<Float> level, Runnable full) {
        try {
            line = AudioSystem.getTargetDataLine(FORMAT);
            line.open(FORMAT, AudioDevice.FRAME_BYTES * 4);
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            line = null;
            throw new IllegalStateException("No microphone available: " + e.getMessage(), e);
        }
        running = true;
        line.start();
        TargetDataLine mic = line;
        long maxBytes = (long) VoiceNotes.MAX_SECONDS * (long) AudioDevice.SAMPLE_RATE * 2;
        thread = Thread.ofPlatform().daemon().name("voice-recorder").start(() -> {
            byte[] frame = new byte[AudioDevice.FRAME_BYTES];
            while (running) {
                int n = mic.read(frame, 0, frame.length);
                if (n <= 0) continue;
                synchronized (pcm) {
                    pcm.write(frame, 0, n);
                    if (pcm.size() >= maxBytes) {
                        running = false;
                        full.run();
                    }
                }
                level.accept(VoiceNotes.level(java.util.Arrays.copyOf(frame, n)));
            }
        });
    }

    /** Stops and returns what was recorded (16-bit PCM). */
    public synchronized byte[] stop() {
        running = false;
        if (line != null) {
            line.stop();
            line.close();
            line = null;
        }
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (pcm) {
            return pcm.toByteArray();
        }
    }
}
