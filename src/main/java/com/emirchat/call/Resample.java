package com.emirchat.call;

/**
 * Converts speech between 16 kHz (what Oba records and plays) and 8 kHz (the μ-law rate that
 * versions before Opus send and expect). Both directions use the same low-pass filter, so
 * nothing above 3.6 kHz folds back into what is heard, and keep their state from one 20 ms
 * frame to the next, so frame edges do not click. One instance per stream.
 */
final class Resample {
    /** Windowed-sinc low-pass at 3.6 kHz for 16 kHz audio (Hamming window). */
    private static final double[] TAPS = taps(31, 3600.0 / 16000);

    private final double[] history = new double[TAPS.length - 1];
    private final double gain;
    private final boolean down;

    private Resample(boolean down) {
        this.down = down;
        this.gain = down ? 1 : 2; // stuffing zeros halves the level; the filter takes it back
    }

    /** 16 kHz to 8 kHz. */
    static Resample down() {
        return new Resample(true);
    }

    /** 8 kHz to 16 kHz. */
    static Resample up() {
        return new Resample(false);
    }

    /** @param pcm one frame of 16-bit little-endian samples at the input rate */
    byte[] process(byte[] pcm) {
        short[] in = Pcm.samples(pcm);
        double[] x;
        if (down) {
            x = new double[in.length];
            for (int i = 0; i < in.length; i++) x[i] = in[i];
        } else {
            x = new double[in.length * 2]; // every other sample is zero
            for (int i = 0; i < in.length; i++) x[2 * i] = in[i];
        }
        int step = down ? 2 : 1;
        short[] out = new short[x.length / step];
        int h = history.length;
        for (int o = 0; o < out.length; o++) {
            int n = o * step; // index in x of the newest sample under the filter
            double acc = 0;
            for (int k = 0; k < TAPS.length; k++) {
                int j = n - k;
                acc += TAPS[k] * (j >= 0 ? x[j] : history[h + j]);
            }
            out[o] = Pcm.clip(acc * gain);
        }
        // keep the last samples for the next frame
        if (x.length >= h) {
            System.arraycopy(x, x.length - h, history, 0, h);
        } else {
            System.arraycopy(history, x.length, history, 0, h - x.length);
            System.arraycopy(x, 0, history, h - x.length, x.length);
        }
        return Pcm.bytes(out, out.length);
    }

    private static double[] taps(int n, double cutoff) {
        double[] t = new double[n];
        double sum = 0;
        int mid = n / 2;
        for (int i = 0; i < n; i++) {
            int k = i - mid;
            double sinc = k == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * k) / (Math.PI * k);
            double window = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (n - 1));
            t[i] = sinc * window;
            sum += t[i];
        }
        for (int i = 0; i < n; i++) t[i] /= sum; // unity gain for steady sound
        return t;
    }
}
