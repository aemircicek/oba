package com.emirchat.api;

import com.emirchat.protocol.Wire;
import java.net.ProtocolException;

/**
 * Messages between client and server, sent inside the encrypted channel.
 *
 * <p>Every frame is {@code u8 type ‖ i64 requestId ‖ fields}. Requests carry a non-zero id that
 * the response echoes; server-initiated pushes use id 0. Decoding is strict: unknown types,
 * oversized fields and trailing bytes are rejected.
 */
public sealed interface Packet {
    int MAX_USERNAME_BYTES = 20;
    int MAX_PASSWORD_BYTES = 256;
    int MAX_MESSAGE_BYTES = 512;

    // ------------------------------------------------------------- requests (client -> server)

    record Register(String username, String password) implements Packet {
        @Override public String toString() { return "Register[" + username + "]"; } // never log passwords
    }

    record Login(String username, String password) implements Packet {
        @Override public String toString() { return "Login[" + username + "]"; }
    }

    record Ping() implements Packet {}

    // ------------------------------------------------------------- responses (server -> client)

    record Ok() implements Packet {}

    record Error(ErrorCode code, String message) implements Packet {}

    /** @param newDevice true if this login moved the account to a new device key */
    record LoggedIn(String username, boolean newDevice) implements Packet {}

    record Pong() implements Packet {}

    /** A packet together with the request id it answers (or 0 for a push). */
    record Frame(long requestId, Packet packet) {}

    // ------------------------------------------------------------- codec

    static byte[] encode(long requestId, Packet packet) {
        Wire.Writer w = new Wire.Writer();
        switch (packet) {
            case Register r -> w.u8(1).i64(requestId).string(r.username()).string(r.password());
            case Login l -> w.u8(2).i64(requestId).string(l.username()).string(l.password());
            case Ping p -> w.u8(3).i64(requestId);
            case Ok o -> w.u8(100).i64(requestId);
            case Error e -> w.u8(101).i64(requestId).u8(e.code().code).string(truncate(e.message()));
            case LoggedIn l -> w.u8(102).i64(requestId).string(l.username()).u8(l.newDevice() ? 1 : 0);
            case Pong p -> w.u8(103).i64(requestId);
        }
        return w.toByteArray();
    }

    static Frame decode(byte[] data) throws ProtocolException {
        Wire.Reader r = new Wire.Reader(data);
        int type = r.u8();
        long id = r.i64();
        Packet packet = switch (type) {
            case 1 -> new Register(r.string(MAX_USERNAME_BYTES), r.string(MAX_PASSWORD_BYTES));
            case 2 -> new Login(r.string(MAX_USERNAME_BYTES), r.string(MAX_PASSWORD_BYTES));
            case 3 -> new Ping();
            case 100 -> new Ok();
            case 101 -> new Error(ErrorCode.of(r.u8()), r.string(MAX_MESSAGE_BYTES));
            case 102 -> new LoggedIn(r.string(MAX_USERNAME_BYTES), bool(r.u8()));
            case 103 -> new Pong();
            default -> throw new ProtocolException("Unknown packet type " + type);
        };
        r.expectEnd();
        return new Frame(id, packet);
    }

    private static boolean bool(int v) throws ProtocolException {
        if (v > 1) throw new ProtocolException("Invalid boolean");
        return v == 1;
    }

    private static String truncate(String s) {
        String safe = s == null ? "" : s;
        while (safe.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            safe = safe.substring(0, safe.length() - 1);
        }
        return safe;
    }
}
