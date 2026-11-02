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
}
