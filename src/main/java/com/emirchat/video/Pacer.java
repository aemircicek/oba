package com.emirchat.video;

/**
 * Spreads video packets evenly over time. The relay forwards at most 100 call packets a second
 * from one device (audio takes 50 of them), so video gets a steady share and never a burst that
 * would make the relay drop audio. A frame that would wait too long is better dropped, and the
 * caller then starts again from a key frame.
 */
public final class Pacer {
    private final double perSecond;
    private final int maxWaiting;
    private long nextAt;

    /**
     * @param perSecond  packets a second
     * @param maxWaiting packets that may be waiting at most (beyond it a frame is refused)
     */
    public Pacer(double perSecond, int maxWaiting) {
        this.perSecond = perSecond;
        this.maxWaiting = maxWaiting;
    }

    /**
     * Books the sending times of {@code packets} packets.
     *
     * @param now the current time in nanoseconds
     * @return the delay in nanoseconds before each of them, or null if they would make too many
     *         wait (send nothing then). With nothing waiting, a frame is always taken, however
     *         large: refusing it would refuse every key frame larger than the limit for ever.
     */
    public synchronized long[] book(int packets, long now) {
        long gap = (long) (1e9 / perSecond);
        long start = Math.max(now, nextAt);
        long waiting = (start - now) / gap;
        if (waiting > 0 && waiting + packets > maxWaiting) return null;
        long[] delays = new long[packets];
        for (int i = 0; i < packets; i++) delays[i] = start + i * gap - now;
        nextAt = start + packets * gap;
        return delays;
    }

    /** Forgets what is booked (the video stopped). */
    public synchronized void reset() {
        nextAt = 0;
    }
}
