package com.emirchat.video;

/**
 * How much of its room the video may use, decided from what the receiver reports: a step down
 * at once when packets are being lost, a step up slowly while nothing is (additive increase,
 * multiplicative decrease, as congestion control does). Each step is a share of the full bit
 * rate; on the lowest steps every other picture is skipped too, so the ones sent stay sharp.
 *
 * <p>A change of step means a new encoder (and a key frame), so steps change at most every
 * {@link #MIN_CHANGE_MS} going down and {@link #UP_AFTER_MS} going up.
 */
public final class VideoRate {
    /** Shares of the full bit rate. */
    static final double[] STEPS = {1.0, 0.7, 0.5, 0.35, 0.25};
    /** Loss above this (per mille) is congestion: step down. */
    static final int LOSS_DOWN = 80;
    /** Below this for a while: try a step up. */
    static final int LOSS_UP = 15;
    static final long MIN_CHANGE_MS = 2_000;
    static final long UP_AFTER_MS = 6_000;

    private int step;
    private long changedAt = Long.MIN_VALUE / 2;
    private long cleanSince = Long.MIN_VALUE / 2;

    /** The share of the full bit rate to use now. */
    public synchronized double share() {
        return STEPS[step];
    }

    /** True on the lowest steps: send every other picture. */
    public synchronized boolean halfFrameRate() {
        return step >= 3;
    }

    /** 0 (best) to {@code STEPS.length - 1}. */
    public synchronized int step() {
        return step;
    }

    /**
     * A receiver's report: {@code lossPerMille} of the packets of the last second did not arrive.
     *
     * @return true if the step changed (the encoder must be rebuilt)
     */
    public synchronized boolean report(int lossPerMille, long nowMs) {
        if (lossPerMille > LOSS_DOWN) {
            cleanSince = nowMs;
            return down(nowMs, lossPerMille > 300 ? 2 : 1);
        }
        if (lossPerMille > LOSS_UP) {
            cleanSince = nowMs; // some loss: hold
            return false;
        }
        if (step > 0 && nowMs - cleanSince >= UP_AFTER_MS && nowMs - changedAt >= UP_AFTER_MS) {
            step--;
            changedAt = nowMs;
            cleanSince = nowMs;
            return true;
        }
        return false;
    }

    /** Our own pacer had to drop a picture: there is not room for this rate. */
    public synchronized boolean localDrop(long nowMs) {
        cleanSince = nowMs;
        return down(nowMs, 1);
    }

    /** Back to the top (a new path, a new source). */
    public synchronized void reset() {
        step = 0;
        changedAt = Long.MIN_VALUE / 2;
        cleanSince = Long.MIN_VALUE / 2;
    }

    private boolean down(long nowMs, int by) {
        if (step >= STEPS.length - 1 || nowMs - changedAt < MIN_CHANGE_MS) return false;
        step = Math.min(STEPS.length - 1, step + by);
        changedAt = nowMs;
        return true;
    }

    /**
     * What a receiver counts: of the chunks of the frames that are settled (complete, or given up
     * because newer frames came), how many arrived; a frame number skipped entirely counts as one
     * chunk lost.
     */
    public static final class Loss {
        private long got;
        private long expected;
        private int highestFrame = -1;
        private final java.util.Map<Integer, int[]> frames = new java.util.HashMap<>(); // frame → {count, seen}

        /** A chunk of {@code frame} (one of {@code count}) arrived. */
        public synchronized void chunk(int frame, int count) {
            if (frames.size() > 64) frames.clear(); // nonsense numbers: start counting again
            int[] f = frames.computeIfAbsent(frame, k -> new int[] {Math.max(1, count), 0});
            f[1]++;
            if (frame > highestFrame) {
                if (highestFrame >= 0 && frame > highestFrame + 1) expected += Math.min(30, frame - highestFrame - 1);
                highestFrame = frame;
            }
            settle();
        }

        private void settle() {
            for (var it = frames.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                int[] f = e.getValue();
                if (f[1] >= f[0] || e.getKey() < highestFrame - 2) {
                    expected += f[0];
                    got += Math.min(f[1], f[0]);
                    it.remove();
                }
            }
        }

        /** Loss per mille since the last call, or -1 if nothing was settled (no video arriving). */
        public synchronized int take() {
            settle();
            if (expected == 0) return -1;
            int loss = (int) Math.max(0, Math.min(1000, (expected - got) * 1000 / expected));
            got = 0;
            expected = 0;
            return loss;
        }

        public synchronized void reset() {
            got = 0;
            expected = 0;
            highestFrame = -1;
            frames.clear();
        }
    }
}
