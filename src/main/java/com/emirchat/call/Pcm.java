package com.emirchat.call;

/** 16-bit little-endian signed samples, as Java Sound and the network carry them, to and from numbers. */
final class Pcm {
    private Pcm() {}

    static short[] samples(byte[] pcm) {
        short[] out = new short[pcm.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
        return out;
    }

    static byte[] bytes(short[] samples, int count) {
        byte[] out = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            out[2 * i] = (byte) samples[i];
            out[2 * i + 1] = (byte) (samples[i] >> 8);
        }
        return out;
    }

    static short clip(double v) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v)));
    }
}
