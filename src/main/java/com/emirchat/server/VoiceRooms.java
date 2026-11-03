package com.emirchat.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who is in which voice room, in memory only (rooms vanish with the server). One room per
 * connection. The server only relays the participants' encrypted audio; it never holds keys.
 */
final class VoiceRooms {
    /** Most people in one room (each one's audio goes to everyone else). */
    static final int MAX_PARTICIPANTS = 12;

    private record Seat(Database.User user, ClientConnection connection) {}

    private final Map<Long, LinkedHashMap<ClientConnection, Seat>> rooms = new HashMap<>();
    private final Map<ClientConnection, Long> roomOf = new HashMap<>();

    /**
     * Puts the connection in the room of {@code groupId}.
     *
     * @return the room it left to do so, or 0
     * @throws com.emirchat.api.ApiException if the room is full
     */
    synchronized long join(long groupId, Database.User user, ClientConnection connection) throws com.emirchat.api.ApiException {
        Long current = roomOf.get(connection);
        if (current != null && current == groupId) return 0;
        LinkedHashMap<ClientConnection, Seat> room = rooms.computeIfAbsent(groupId, k -> new LinkedHashMap<>());
        if (room.size() >= MAX_PARTICIPANTS) {
            if (room.isEmpty()) rooms.remove(groupId);
            throw new com.emirchat.api.ApiException(com.emirchat.api.ErrorCode.LIMIT_REACHED, "The voice room is full.");
        }
        long left = current == null ? 0 : removeLocked(connection);
        room.put(connection, new Seat(user, connection));
        roomOf.put(connection, groupId);
        return left;
    }

    /** @return the room the connection left, or 0 if it was in none */
    synchronized long leave(ClientConnection connection) {
        return removeLocked(connection);
    }

    private long removeLocked(ClientConnection connection) {
        Long groupId = roomOf.remove(connection);
        if (groupId == null) return 0;
        LinkedHashMap<ClientConnection, Seat> room = rooms.get(groupId);
        if (room != null) {
            room.remove(connection);
            if (room.isEmpty()) rooms.remove(groupId);
        }
        return groupId;
    }

    /** The room the connection is in, or 0. */
    synchronized long roomOf(ClientConnection connection) {
        return roomOf.getOrDefault(connection, 0L);
    }

    /** Usernames in the room, each once (someone on two devices is still one person). */
    synchronized List<String> participants(long groupId) {
        LinkedHashMap<ClientConnection, Seat> room = rooms.get(groupId);
        if (room == null) return List.of();
        List<String> names = new ArrayList<>();
        for (Seat s : room.values()) if (!names.contains(s.user().username())) names.add(s.user().username());
        return names;
    }

    /** Everyone else's connections in the room. */
    synchronized List<ClientConnection> others(long groupId, ClientConnection me) {
        LinkedHashMap<ClientConnection, Seat> room = rooms.get(groupId);
        if (room == null) return List.of();
        List<ClientConnection> out = new ArrayList<>();
        for (ClientConnection c : room.keySet()) if (c != me) out.add(c);
        return out;
    }
}
