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
        Deque<Long> times = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        prune(times);
        times.addLast(clock.millis());
    }

    synchronized void reset(String key) {
        failures.remove(key);
    }

    private void prune(Deque<Long> times) {
        long cutoff = clock.millis() - window.toMillis();
        while (!times.isEmpty() && times.peekFirst() <= cutoff) times.removeFirst();
    }
}
