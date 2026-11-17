package com.emirchat.call;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

/** The computer's default microphone and speaker, through Java Sound. */
public final class SoundDevice implements AudioDevice {
    /**
     * The rate the lines are opened at: {@link #SAMPLE_RATE} where the computer offers it, else
     * the 8 kHz that every sound system has, converted here (the rest of the app always sees
     * {@link #SAMPLE_RATE}).
     */
    private static final float FALLBACK_RATE = 8000f;
    private float lineRate;
    private Resample micUp;
    private Resample speakerLeft;
    private Resample speakerRight;
    /** At most this many frames wait for the speaker (100 ms); older ones are dropped to keep the delay low. */
    private static final int MAX_QUEUED = 5;

    private final BlockingQueue<byte[]> speakerQueue = new ArrayBlockingQueue<>(MAX_QUEUED);
    private volatile boolean running;
    private TargetDataLine microphone;
    private SourceDataLine speaker;
    private Thread captureThread;
    private Thread playThread;

    @Override
    public synchronized void start(Consumer<byte[]> captured) {
        try {
            openLines(SAMPLE_RATE);
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException first) {
            closeLines();
            try {
                openLines(FALLBACK_RATE);
            } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
                closeLines();
                throw new IllegalStateException("No microphone or speaker available: " + e.getMessage(), e);
            }
        }
        boolean converting = lineRate != SAMPLE_RATE;
        micUp = converting ? Resample.up() : null;
        speakerLeft = converting ? Resample.down() : null;
        speakerRight = converting ? Resample.down() : null;
        int lineFrame = (int) (lineRate / 50) * 2;
        running = true;
        microphone.start();
        speaker.start();
        TargetDataLine mic = microphone;
        captureThread = Thread.ofPlatform().daemon().name("call-capture").start(() -> {
            byte[] frame = new byte[lineFrame];
            while (running) {
                int n = 0;
                while (n < lineFrame && running) n += mic.read(frame, n, lineFrame - n);
                if (!running) break;
                Resample up = micUp;
                captured.accept(up == null ? frame.clone() : up.process(frame));
            }
        });
        SourceDataLine out = speaker;
        playThread = Thread.ofPlatform().daemon().name("call-play").start(() -> {
            try {
                while (running) {
                    byte[] frame = speakerQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (frame != null) out.write(frame, 0, frame.length);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override
    public void play(byte[] frame) {
        byte[] both = new byte[frame.length * 2];
        for (int i = 0; i + 1 < frame.length; i += 2) {
            both[2 * i] = frame[i];
            both[2 * i + 1] = frame[i + 1];
            both[2 * i + 2] = frame[i];
            both[2 * i + 3] = frame[i + 1];
        }
        playStereo(both);
    }

    @Override
    public boolean stereo() {
        return true;
    }

    @Override
    public void playStereo(byte[] frame) {
        if (speakerLeft != null) frame = toLineRate(frame);
        while (!speakerQueue.offer(frame)) speakerQueue.poll(); // drop the oldest: stay close to real time
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (captureThread != null) captureThread.interrupt();
        if (playThread != null) playThread.interrupt();
        closeLines();
        speakerQueue.clear();
    }

    private void openLines(float rate) throws LineUnavailableException {
        AudioFormat mono = new AudioFormat(rate, 16, 1, true, false);
        // the speaker plays in stereo: mono frames go to both sides, voice rooms place people left and right
        AudioFormat stereo = new AudioFormat(rate, 16, 2, true, false);
        int frame = (int) (rate / 50) * 2;
        microphone = AudioSystem.getTargetDataLine(mono);
        microphone.open(mono, frame * 4);
        speaker = AudioSystem.getSourceDataLine(stereo);
        speaker.open(stereo, frame * 16);
        lineRate = rate;
    }

    /** Stereo frame at {@link #SAMPLE_RATE} to the rate the speaker was opened at (each side on its own). */
    private byte[] toLineRate(byte[] stereo) {
        int samples = stereo.length / 4;
        byte[] left = new byte[samples * 2];
        byte[] right = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            left[2 * i] = stereo[4 * i];
            left[2 * i + 1] = stereo[4 * i + 1];
            right[2 * i] = stereo[4 * i + 2];
            right[2 * i + 1] = stereo[4 * i + 3];
        }
        byte[] l = speakerLeft.process(left);
        byte[] r = speakerRight.process(right);
        byte[] out = new byte[l.length * 2];
        for (int i = 0; i < l.length / 2; i++) {
            out[4 * i] = l[2 * i];
            out[4 * i + 1] = l[2 * i + 1];
            out[4 * i + 2] = r[2 * i];
            out[4 * i + 3] = r[2 * i + 1];
        }
        return out;
    }

    private void closeLines() {
        if (microphone != null) {
            microphone.stop();
            microphone.close();
            microphone = null;
        }
        if (speaker != null) {
            speaker.stop();
            speaker.close();
            speaker = null;
        }
    }
}
