package com.emirchat.server;

import com.emirchat.api.Packet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Which users are online, and their live connections (for pushes). */
final class Presence {
    private final Map<Long, Set<ClientConnection>> online = new HashMap<>();

    /** @return true if the user was offline before this connection */
    synchronized boolean add(long userId, ClientConnection connection) {
        Set<ClientConnection> set = online.computeIfAbsent(userId, k -> new LinkedHashSet<>());
        boolean wasOffline = set.isEmpty();
        set.add(connection);
        return wasOffline;
    }

    /** @return true if the user has no connections left */
    synchronized boolean remove(long userId, ClientConnection connection) {
        Set<ClientConnection> set = online.get(userId);
        if (set == null) return false;
        set.remove(connection);
        if (set.isEmpty()) {
            online.remove(userId);
            return true;
        }
        return false;
    }

    synchronized boolean isOnline(long userId) {
        return online.containsKey(userId);
    }

    /** Queues a push to every live connection of the user; never blocks. */
    void push(long userId, Packet packet) {
        List<ClientConnection> targets;
        synchronized (this) {
            Set<ClientConnection> set = online.get(userId);
            if (set == null) return;
            targets = List.copyOf(set);
        }
        for (ClientConnection c : targets) c.push(packet);
    }

    /** Like {@link #push}, but dropped rather than queued when a device is behind (live audio). */
    void pushDroppable(long userId, Packet packet) {
        List<ClientConnection> targets;
        synchronized (this) {
            Set<ClientConnection> set = online.get(userId);
            if (set == null) return;
            targets = List.copyOf(set);
        }
        for (ClientConnection c : targets) c.pushDroppable(packet);
    }
}
