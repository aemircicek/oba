package com.emirchat.video;

import java.util.function.Consumer;

/** Where the pictures we send come from: the camera, the screen, or a test pattern. */
public interface VideoSource {
    /** What it is, for the other side: a screen is shown whole, a camera may be cropped to fit. */
    boolean screen();

    /** The most frames a second it delivers. */
    int fps();

    /**
     * Starts delivering pictures (on its own thread), already at the size to send.
     *
     * @throws IllegalStateException if there is no camera, or access was refused
     */
    void start(Consumer<VideoFrame> frames);

    void stop();
}
