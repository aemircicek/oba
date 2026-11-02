package com.emirchat.call;

import java.util.function.Consumer;

/**
 * No microphone and no speaker: calls connect and stay silent. Used when started with
 * {@code -Dsecurechat.audio=none} (screenshots and demos that must not open the real microphone).
 */
public final class SilentDevice implements AudioDevice {
    @Override public void start(Consumer<byte[]> captured) {}

    @Override public void play(byte[] frame) {}

    @Override public void stop() {}
}
