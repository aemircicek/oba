package com.emirchat.call;

/**
 * G.711 μ-law: 16-bit audio samples squeezed into 8 bits on a logarithmic scale (the codec of
 * telephone networks). Halves the data with little audible loss for speech, and needs nothing
 * outside the JDK.
 */
public final class Mulaw {
    private static final int BIAS = 0x84;
    private static final int CLIP = 32635;

    private Mulaw() {}

    /** @param pcm 16-bit little-endian signed samples */
    public static byte[] encode(byte[] pcm) {
        byte[] out = new byte[pcm.length / 2];
        for (int i = 0; i < out.length; i++) {
            int sample = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
            out[i] = encodeSample(sample);
        }
        return out;
    }

    /** @return 16-bit little-endian signed samples */
    public static byte[] decode(byte[] ulaw) {
        byte[] out = new byte[ulaw.length * 2];
        for (int i = 0; i < ulaw.length; i++) {
            int sample = decodeSample(ulaw[i]);
            out[2 * i] = (byte) sample;
            out[2 * i + 1] = (byte) (sample >> 8);
        }
        return out;
    }

    static byte encodeSample(int sample) {
        int sign = (sample >> 8) & 0x80;
        if (sign != 0) sample = -sample;
        if (sample > CLIP) sample = CLIP;
        sample += BIAS;
        int exponent = 7;
        for (int mask = 0x4000; (sample & mask) == 0 && exponent > 0; mask >>= 1) exponent--;
        int mantissa = (sample >> (exponent + 3)) & 0x0f;
        return (byte) ~(sign | (exponent << 4) | mantissa);
    }

    static int decodeSample(byte encoded) {
        int u = ~encoded & 0xff;
        int sign = u & 0x80;
        int exponent = (u >> 4) & 0x07;
        int mantissa = u & 0x0f;
        int sample = (((mantissa << 3) + BIAS) << exponent) - BIAS;
        return sign != 0 ? -sample : sample;
    }
}
