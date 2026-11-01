package com.emirchat.api;

import com.emirchat.crypto.Identity;
import com.emirchat.protocol.Wire;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Messages between client and server, sent inside the encrypted channel.
 *
 * <p>Every frame is {@code u8 type ‖ i64 requestId ‖ fields}. Requests carry a non-zero id that
 * the response echoes; server-initiated pushes use id 0. Decoding is strict: unknown types,
 * oversized fields, oversized lists and trailing bytes are rejected.
 */
public sealed interface Packet {
    int MAX_USERNAME_BYTES = 20;
    int MAX_PASSWORD_BYTES = 256;
    int MAX_MESSAGE_BYTES = 512;
    int MAX_LIST = 1000;

    /** A friend as the server describes it. */
    record Friend(String username, boolean online, byte[] identityKey) {
        public Friend {
            identityKey = identityKey.clone();
        }

        @Override public byte[] identityKey() { return identityKey.clone(); }

        public String fingerprint() { return Identity.fingerprint(identityKey); }

        @Override public boolean equals(Object o) {
            return o instanceof Friend f && f.username.equals(username) && f.online == online
                    && java.util.Arrays.equals(f.identityKey, identityKey);
        }

        @Override public int hashCode() { return username.hashCode(); }

        @Override public String toString() { return "Friend[" + username + (online ? ", online" : "") + "]"; }
    }

    // ------------------------------------------------------------- requests (client -> server)

    record Register(String username, String password) implements Packet {
        @Override public String toString() { return "Register[" + username + "]"; } // never log passwords
    }

    record Login(String username, String password) implements Packet {
        @Override public String toString() { return "Login[" + username + "]"; }
    }

    record Ping() implements Packet {}

    record SendFriendRequest(String username) implements Packet {}

    record RespondFriendRequest(String username, boolean accept) implements Packet {}

    record RemoveFriend(String username) implements Packet {}

    record ListFriends() implements Packet {}

    // ------------------------------------------------------------- responses (server -> client)

    record Ok() implements Packet {}

    record Error(ErrorCode code, String message) implements Packet {}

    /** @param newDevice true if this login moved the account to a new device key */
    record LoggedIn(String username, boolean newDevice) implements Packet {}

    record Pong() implements Packet {}

    /** Answer to SendFriendRequest: either a pending request, or friends right away (they had asked us). */
    record FriendRequestResult(boolean nowFriends) implements Packet {}

    record FriendList(List<Friend> friends, List<String> incoming, List<String> outgoing) implements Packet {
        public FriendList {
            friends = List.copyOf(friends);
            incoming = List.copyOf(incoming);
            outgoing = List.copyOf(outgoing);
        }
    }

    // ------------------------------------------------------------- pushes (server -> client, id 0)

    record FriendRequestReceived(String from) implements Packet {}

    record FriendAdded(Friend friend) implements Packet {}

    record FriendRemoved(String username) implements Packet {}

    record PresenceChanged(String username, boolean online) implements Packet {}

    /** A friend logged in from a new device: their identity key changed. */
    record IdentityKeyChanged(String username, byte[] identityKey) implements Packet {
        public IdentityKeyChanged {
            identityKey = identityKey.clone();
        }

        @Override public byte[] identityKey() { return identityKey.clone(); }

        @Override public boolean equals(Object o) {
            return o instanceof IdentityKeyChanged k && k.username.equals(username)
                    && java.util.Arrays.equals(k.identityKey, identityKey);
        }

        @Override public int hashCode() { return username.hashCode(); }
    }

    /** A packet together with the request id it answers (or 0 for a push). */
    record Frame(long requestId, Packet packet) {}

    // ------------------------------------------------------------- codec

    static byte[] encode(long requestId, Packet packet) {
        Wire.Writer w = new Wire.Writer();
        switch (packet) {
            case Register r -> w.u8(1).i64(requestId).string(r.username()).string(r.password());
            case Login l -> w.u8(2).i64(requestId).string(l.username()).string(l.password());
            case Ping p -> w.u8(3).i64(requestId);
            case SendFriendRequest s -> w.u8(10).i64(requestId).string(s.username());
            case RespondFriendRequest r -> w.u8(11).i64(requestId).string(r.username()).u8(r.accept() ? 1 : 0);
            case RemoveFriend r -> w.u8(12).i64(requestId).string(r.username());
            case ListFriends l -> w.u8(13).i64(requestId);
            case Ok o -> w.u8(100).i64(requestId);
            case Error e -> w.u8(101).i64(requestId).u8(e.code().code).string(truncate(e.message()));
            case LoggedIn l -> w.u8(102).i64(requestId).string(l.username()).u8(l.newDevice() ? 1 : 0);
            case Pong p -> w.u8(103).i64(requestId);
            case FriendRequestResult f -> w.u8(110).i64(requestId).u8(f.nowFriends() ? 1 : 0);
            case FriendList f -> {
                w.u8(111).i64(requestId).u16(f.friends().size());
                for (Friend fr : f.friends()) writeFriend(w, fr);
                writeNames(w, f.incoming());
                writeNames(w, f.outgoing());
            }
            case FriendRequestReceived f -> w.u8(120).i64(requestId).string(f.from());
            case FriendAdded f -> writeFriend(w.u8(121).i64(requestId), f.friend());
            case FriendRemoved f -> w.u8(122).i64(requestId).string(f.username());
            case PresenceChanged p -> w.u8(123).i64(requestId).string(p.username()).u8(p.online() ? 1 : 0);
            case IdentityKeyChanged k -> w.u8(124).i64(requestId).string(k.username()).bytes(k.identityKey());
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
            case 10 -> new SendFriendRequest(r.string(MAX_USERNAME_BYTES));
            case 11 -> new RespondFriendRequest(r.string(MAX_USERNAME_BYTES), bool(r.u8()));
            case 12 -> new RemoveFriend(r.string(MAX_USERNAME_BYTES));
            case 13 -> new ListFriends();
            case 100 -> new Ok();
            case 101 -> new Error(ErrorCode.of(r.u8()), r.string(MAX_MESSAGE_BYTES));
            case 102 -> new LoggedIn(r.string(MAX_USERNAME_BYTES), bool(r.u8()));
            case 103 -> new Pong();
            case 110 -> new FriendRequestResult(bool(r.u8()));
            case 111 -> {
                int n = r.count(MAX_LIST);
                List<Friend> friends = new ArrayList<>(n);
                for (int i = 0; i < n; i++) friends.add(readFriend(r));
                yield new FriendList(friends, readNames(r), readNames(r));
            }
            case 120 -> new FriendRequestReceived(r.string(MAX_USERNAME_BYTES));
            case 121 -> new FriendAdded(readFriend(r));
            case 122 -> new FriendRemoved(r.string(MAX_USERNAME_BYTES));
            case 123 -> new PresenceChanged(r.string(MAX_USERNAME_BYTES), bool(r.u8()));
            case 124 -> new IdentityKeyChanged(r.string(MAX_USERNAME_BYTES), r.exactBytes(Identity.ENCODED_PUBLIC_KEY_LENGTH));
            default -> throw new ProtocolException("Unknown packet type " + type);
        };
        r.expectEnd();
        return new Frame(id, packet);
    }

    private static void writeFriend(Wire.Writer w, Friend f) {
        w.string(f.username()).u8(f.online() ? 1 : 0).bytes(f.identityKey());
    }

    private static Friend readFriend(Wire.Reader r) throws ProtocolException {
        return new Friend(r.string(MAX_USERNAME_BYTES), bool(r.u8()), r.exactBytes(Identity.ENCODED_PUBLIC_KEY_LENGTH));
    }

    private static void writeNames(Wire.Writer w, List<String> names) {
        w.u16(names.size());
        for (String n : names) w.string(n);
    }

    private static List<String> readNames(Wire.Reader r) throws ProtocolException {
        int n = r.count(MAX_LIST);
        List<String> names = new ArrayList<>(n);
        for (int i = 0; i < n; i++) names.add(r.string(MAX_USERNAME_BYTES));
        return names;
    }

    private static boolean bool(int v) throws ProtocolException {
        if (v > 1) throw new ProtocolException("Invalid boolean");
        return v == 1;
    }

    private static String truncate(String s) {
        String safe = s == null ? "" : s;
        while (safe.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            safe = safe.substring(0, safe.length() - 1);
        }
        return safe;
    }
}
