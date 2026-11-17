package com.emirchat.server;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** Sliding-window limit on failed attempts per key (a username or an IP address). */
final class RateLimiter {
    private final int maxFailures;
    private final Duration window;
    private final Clock clock;
    /** Above this many keys, expired ones are swept out before a new key is added. */
    static final int SWEEP_AT = 10_000;
    private final Map<String, Deque<Long>> failures = new HashMap<>();

    RateLimiter(int maxFailures, Duration window, Clock clock) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
    }

    synchronized boolean isBlocked(String key) {
        Deque<Long> times = failures.get(key);
        if (times == null) return false;
        prune(times);
        if (times.isEmpty()) {
            failures.remove(key); // keep the map from growing with stale keys
            return false;
        }
        return times.size() >= maxFailures;
    }

    synchronized void recordFailure(String key) {
        if (failures.size() >= SWEEP_AT && !failures.containsKey(key)) sweep();
        Deque<Long> times = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        prune(times);
        times.addLast(clock.millis());
        // Only the newest maxFailures entries decide whether a key is blocked: keep no more.
        while (times.size() > maxFailures) times.removeFirst();
    }

    /** Drops keys whose entries have all expired, so the map cannot grow without limit. */
    private void sweep() {
        failures.values().removeIf(times -> {
            prune(times);
            return times.isEmpty();
        });
    }

    /** How many keys are tracked (for tests). */
    synchronized int size() {
        return failures.size();
    }

    synchronized void reset(String key) {
        failures.remove(key);
    }

    private void prune(Deque<Long> times) {
        long cutoff = clock.millis() - window.toMillis();
        while (!times.isEmpty() && times.peekFirst() <= cutoff) times.removeFirst();
    }
}
