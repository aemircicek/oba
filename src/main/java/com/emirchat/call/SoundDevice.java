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
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
    /** The speaker plays in stereo: mono frames go to both sides, voice rooms place people left and right. */
    private static final AudioFormat SPEAKER = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
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
            microphone = AudioSystem.getTargetDataLine(FORMAT);
            microphone.open(FORMAT, FRAME_BYTES * 4);
            speaker = AudioSystem.getSourceDataLine(SPEAKER);
            speaker.open(SPEAKER, FRAME_BYTES * 16);
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            closeLines();
            throw new IllegalStateException("No microphone or speaker available: " + e.getMessage(), e);
        }
        running = true;
        microphone.start();
        speaker.start();
        TargetDataLine mic = microphone;
        captureThread = Thread.ofPlatform().daemon().name("call-capture").start(() -> {
            byte[] frame = new byte[FRAME_BYTES];
            while (running) {
                int n = 0;
                while (n < FRAME_BYTES && running) n += mic.read(frame, n, FRAME_BYTES - n);
                if (running) captured.accept(frame.clone());
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
