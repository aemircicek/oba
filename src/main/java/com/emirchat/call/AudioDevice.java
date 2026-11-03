package com.emirchat.call;

import java.util.function.Consumer;

/** Microphone and speaker for a call: 8 kHz, 16-bit, mono, little-endian, in 20 ms frames. */
public interface AudioDevice {
    float SAMPLE_RATE = 8000f;
    /** 20 ms of 16-bit samples at 8 kHz. */
    int FRAME_BYTES = 320;

    /**
     * Starts capturing and playing.
     *
     * @param captured receives each 20 ms frame from the microphone (on a capture thread)
     * @throws IllegalStateException if there is no usable microphone or speaker
     */
    void start(Consumer<byte[]> captured);

    /** Queues a frame for the speaker; never blocks (late frames are dropped). */
    void play(byte[] frame);

    void stop();

    /** True if {@link #playStereo} reaches two speakers (voice rooms place people left and right). */
    default boolean stereo() {
        return false;
    }

    /** Queues a stereo frame (16-bit little-endian, left and right interleaved, 20 ms). */
    default void playStereo(byte[] frame) {
        byte[] mono = new byte[frame.length / 2];
        for (int i = 0; i + 3 < frame.length; i += 4) {
            int l = (short) ((frame[i] & 0xff) | (frame[i + 1] << 8));
            int r = (short) ((frame[i + 2] & 0xff) | (frame[i + 3] << 8));
            int m = (l + r) / 2;
            mono[i / 2] = (byte) m;
            mono[i / 2 + 1] = (byte) (m >> 8);
        }
        play(mono);
    }
}
