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
    int MAX_ENVELOPE_BYTES = 40_000;
    int MAX_PREKEYS_PER_UPLOAD = 100;
    int MAX_ACKS = 500;
    int MAX_GROUP_NAME_BYTES = 64;
    int MAX_GROUP_MEMBERS = 100;
    /** Attachment upload and download chunk size (well under the 64 KiB frame limit). */
    int MAX_CHUNK_BYTES = 32 * 1024;
    int ATTACHMENT_ID_BYTES = com.emirchat.e2e.Attachments.ID_LENGTH;

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

    /** Publishes this device's X3DH identity and signed prekey (signatures are checked by the server). */
    record UploadKeys(byte[] dhIdentity, byte[] dhIdentitySignature, int signedPrekeyId, byte[] signedPrekey,
                      byte[] signedPrekeySignature) implements Packet {}

    record Prekey(int id, byte[] key) {}

    record UploadPrekeys(List<Prekey> prekeys) implements Packet {
        public UploadPrekeys { prekeys = List.copyOf(prekeys); }
    }

    record KeyStatus() implements Packet {}

    record FetchBundle(String username) implements Packet {}

    /** An end-to-end encrypted message; the server only sees the recipient and an opaque body. */
    record SendEnvelope(String recipient, byte[] body) implements Packet {}

    /** Asks the server to push every queued envelope; answered with PendingCount. */
    record FetchPending() implements Packet {}

    record CreateGroup(String name, List<String> members) implements Packet {
        public CreateGroup { members = List.copyOf(members); }
    }

    record AddGroupMember(long groupId, String username) implements Packet {}

    record RemoveGroupMember(long groupId, String username) implements Packet {}

    record LeaveGroup(long groupId) implements Packet {}

    record ListGroups() implements Packet {}

    /** One body for every member; the server fans it out (members other than the sender). */
    record SendGroupEnvelope(long groupId, byte[] body) implements Packet {}

    record AckEnvelopes(List<Long> ids) implements Packet {
        public AckEnvelopes { ids = List.copyOf(ids); }
    }

    /**
     * Reserves space for an encrypted attachment. Exactly one audience: a recipient (direct
     * message, groupId 0) or a group (recipient empty). Only that audience may download it.
     *
     * @param size the exact size of the encrypted blob
     */
    record UploadStart(long size, String recipient, long groupId) implements Packet {}

    /** Appends to an upload; {@code offset} must equal the bytes received so far. */
    record UploadChunk(byte[] attachmentId, long offset, byte[] data) implements Packet {
        public UploadChunk {
            attachmentId = attachmentId.clone();
            data = data.clone();
        }

        @Override public byte[] attachmentId() { return attachmentId.clone(); }

        @Override public byte[] data() { return data.clone(); }

        @Override public boolean equals(Object o) {
            return o instanceof UploadChunk u && java.util.Arrays.equals(u.attachmentId, attachmentId)
                    && u.offset == offset && java.util.Arrays.equals(u.data, data);
        }

        @Override public int hashCode() { return java.util.Arrays.hashCode(attachmentId) * 31 + Long.hashCode(offset); }

        @Override public String toString() { return "UploadChunk[" + offset + "+" + data.length + "]"; }
    }

    /** Completes an upload once every byte arrived; only then can it be downloaded. */
    record UploadFinish(byte[] attachmentId) implements Packet {
        public UploadFinish { attachmentId = attachmentId.clone(); }

        @Override public byte[] attachmentId() { return attachmentId.clone(); }

        @Override public boolean equals(Object o) {
            return o instanceof UploadFinish u && java.util.Arrays.equals(u.attachmentId, attachmentId);
        }

        @Override public int hashCode() { return java.util.Arrays.hashCode(attachmentId); }
    }

    /** Reads up to {@link #MAX_CHUNK_BYTES} of a completed attachment, starting at {@code offset}. */
    record FetchAttachment(byte[] attachmentId, long offset) implements Packet {
        public FetchAttachment { attachmentId = attachmentId.clone(); }

        @Override public byte[] attachmentId() { return attachmentId.clone(); }

        @Override public boolean equals(Object o) {
            return o instanceof FetchAttachment f && java.util.Arrays.equals(f.attachmentId, attachmentId) && f.offset == offset;
        }

        @Override public int hashCode() { return java.util.Arrays.hashCode(attachmentId) * 31 + Long.hashCode(offset); }
    }

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

    /** @param signedPrekeyId -1 if this device has not published keys yet */
    record KeyStatusResult(boolean hasKeys, int signedPrekeyId, int oneTimePrekeys) implements Packet {}

    record Bundle(com.emirchat.e2e.PrekeyBundle bundle) implements Packet {}

    record EnvelopeAccepted(long id, long serverTime) implements Packet {}

    record PendingCount(int count) implements Packet {}

    record Member(String username, byte[] identityKey) {
        public Member { identityKey = identityKey.clone(); }

        @Override public byte[] identityKey() { return identityKey.clone(); }

        @Override public boolean equals(Object o) {
            return o instanceof Member m && m.username.equals(username) && java.util.Arrays.equals(m.identityKey, identityKey);
        }

        @Override public int hashCode() { return username.hashCode(); }
    }

    record GroupInfo(long id, String name, String owner, List<Member> members) implements Packet {
        public GroupInfo { members = List.copyOf(members); }

        public boolean hasMember(String username) {
            return members.stream().anyMatch(m -> m.username().equals(username));
        }
    }

    record GroupList(List<GroupInfo> groups) implements Packet {
        public GroupList { groups = List.copyOf(groups); }
    }

    /** @param attachmentId random and unguessable, chosen by the server */
    record UploadStarted(byte[] attachmentId) implements Packet {
        public UploadStarted { attachmentId = attachmentId.clone(); }

        @Override public byte[] attachmentId() { return attachmentId.clone(); }

        @Override public boolean equals(Object o) {
            return o instanceof UploadStarted u && java.util.Arrays.equals(u.attachmentId, attachmentId);
        }

        @Override public int hashCode() { return java.util.Arrays.hashCode(attachmentId); }
    }

    /** @param totalSize size of the whole encrypted blob */
    record AttachmentChunk(long totalSize, byte[] data) implements Packet {
        public AttachmentChunk { data = data.clone(); }

        @Override public byte[] data() { return data.clone(); }

        @Override public boolean equals(Object o) {
            return o instanceof AttachmentChunk c && c.totalSize == totalSize && java.util.Arrays.equals(c.data, data);
        }

        @Override public int hashCode() { return Long.hashCode(totalSize); }

        @Override public String toString() { return "AttachmentChunk[" + data.length + " of " + totalSize + "]"; }
    }

    // ------------------------------------------------------------- pushes (server -> client, id 0)

    /** Membership or name changed (or we were added). */
    record GroupUpdated(GroupInfo group) implements Packet {}

    /** We are no longer in the group (removed, or we left). */
    record GroupRemoved(long groupId) implements Packet {}

    /** @param groupId 0 for a direct message */
    record Envelope(long id, String sender, long groupId, long sentAt, byte[] body) {}

    record EnvelopeReceived(Envelope envelope) implements Packet {}

    /** The recipient's device received (and acknowledged) one of our envelopes. */
    record EnvelopeDelivered(long id) implements Packet {}

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
            case UploadKeys k -> w.u8(20).i64(requestId).bytes(k.dhIdentity()).bytes(k.dhIdentitySignature())
                    .i64(k.signedPrekeyId()).bytes(k.signedPrekey()).bytes(k.signedPrekeySignature());
            case UploadPrekeys u -> {
                w.u8(21).i64(requestId).u16(u.prekeys().size());
                for (Prekey p : u.prekeys()) w.i64(p.id()).bytes(p.key());
            }
            case KeyStatus k -> w.u8(22).i64(requestId);
            case FetchBundle f -> w.u8(23).i64(requestId).string(f.username());
            case SendEnvelope s -> w.u8(24).i64(requestId).string(s.recipient()).bytes(s.body());
            case FetchPending f -> w.u8(25).i64(requestId);
            case CreateGroup c -> {
                w.u8(30).i64(requestId).string(c.name());
                writeNames(w, c.members());
            }
            case AddGroupMember a -> w.u8(31).i64(requestId).i64(a.groupId()).string(a.username());
            case RemoveGroupMember r -> w.u8(32).i64(requestId).i64(r.groupId()).string(r.username());
            case LeaveGroup l -> w.u8(33).i64(requestId).i64(l.groupId());
            case ListGroups l -> w.u8(34).i64(requestId);
            case SendGroupEnvelope s -> w.u8(35).i64(requestId).i64(s.groupId()).bytes(s.body());
            case GroupInfo g -> writeGroup(w.u8(116).i64(requestId), g);
            case GroupList l -> {
                w.u8(117).i64(requestId).u16(l.groups().size());
                for (GroupInfo g : l.groups()) writeGroup(w, g);
            }
            case GroupUpdated g -> writeGroup(w.u8(127).i64(requestId), g.group());
            case GroupRemoved g -> w.u8(128).i64(requestId).i64(g.groupId());
            case AckEnvelopes a -> {
                w.u8(26).i64(requestId).u16(a.ids().size());
                for (long id : a.ids()) w.i64(id);
            }
            case UploadStart u -> w.u8(40).i64(requestId).i64(u.size()).string(u.recipient()).i64(u.groupId());
            case UploadChunk u -> w.u8(41).i64(requestId).bytes(u.attachmentId()).i64(u.offset()).bytes(u.data());
            case UploadFinish u -> w.u8(42).i64(requestId).bytes(u.attachmentId());
            case FetchAttachment f -> w.u8(43).i64(requestId).bytes(f.attachmentId()).i64(f.offset());
            case UploadStarted u -> w.u8(118).i64(requestId).bytes(u.attachmentId());
            case AttachmentChunk c -> w.u8(119).i64(requestId).i64(c.totalSize()).bytes(c.data());
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
            case KeyStatusResult k -> w.u8(112).i64(requestId).u8(k.hasKeys() ? 1 : 0).i64(k.signedPrekeyId()).i64(k.oneTimePrekeys());
            case Bundle b -> {
                var x = b.bundle();
                w.u8(113).i64(requestId).bytes(x.identityKey()).bytes(x.dhIdentity()).bytes(x.dhIdentitySignature())
                        .i64(x.signedPrekeyId()).bytes(x.signedPrekey()).bytes(x.signedPrekeySignature())
                        .i64(x.oneTimePrekeyId()).bytes(x.oneTimePrekey());
            }
            case EnvelopeAccepted e -> w.u8(114).i64(requestId).i64(e.id()).i64(e.serverTime());
            case PendingCount p -> w.u8(115).i64(requestId).i64(p.count());
            case EnvelopeReceived e -> w.u8(125).i64(requestId).i64(e.envelope().id()).string(e.envelope().sender())
                    .i64(e.envelope().groupId()).i64(e.envelope().sentAt()).bytes(e.envelope().body());
            case EnvelopeDelivered d -> w.u8(126).i64(requestId).i64(d.id());
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
            case 20 -> new UploadKeys(r.exactBytes(X25519_KEY), r.exactBytes(SIGNATURE), (int) r.i64(),
                    r.exactBytes(X25519_KEY), r.exactBytes(SIGNATURE));
            case 21 -> {
                int n = r.count(MAX_PREKEYS_PER_UPLOAD);
                List<Prekey> keys = new ArrayList<>(n);
                for (int i = 0; i < n; i++) keys.add(new Prekey((int) r.i64(), r.exactBytes(X25519_KEY)));
                yield new UploadPrekeys(keys);
            }
            case 22 -> new KeyStatus();
            case 23 -> new FetchBundle(r.string(MAX_USERNAME_BYTES));
            case 24 -> new SendEnvelope(r.string(MAX_USERNAME_BYTES), r.bytes(MAX_ENVELOPE_BYTES));
            case 25 -> new FetchPending();
            case 30 -> new CreateGroup(r.string(MAX_GROUP_NAME_BYTES), readNames(r));
            case 31 -> new AddGroupMember(r.i64(), r.string(MAX_USERNAME_BYTES));
            case 32 -> new RemoveGroupMember(r.i64(), r.string(MAX_USERNAME_BYTES));
            case 33 -> new LeaveGroup(r.i64());
            case 34 -> new ListGroups();
            case 35 -> new SendGroupEnvelope(r.i64(), r.bytes(MAX_ENVELOPE_BYTES));
            case 116 -> readGroup(r);
            case 117 -> {
                int n = r.count(MAX_LIST);
                List<GroupInfo> groups = new ArrayList<>(n);
                for (int i = 0; i < n; i++) groups.add(readGroup(r));
                yield new GroupList(groups);
            }
            case 127 -> new GroupUpdated(readGroup(r));
            case 128 -> new GroupRemoved(r.i64());
            case 26 -> {
                int n = r.count(MAX_ACKS);
                List<Long> ids = new ArrayList<>(n);
                for (int i = 0; i < n; i++) ids.add(r.i64());
                yield new AckEnvelopes(ids);
            }
            case 40 -> new UploadStart(r.i64(), r.string(MAX_USERNAME_BYTES), r.i64());
            case 41 -> new UploadChunk(r.exactBytes(ATTACHMENT_ID_BYTES), r.i64(), r.bytes(MAX_CHUNK_BYTES));
            case 42 -> new UploadFinish(r.exactBytes(ATTACHMENT_ID_BYTES));
            case 43 -> new FetchAttachment(r.exactBytes(ATTACHMENT_ID_BYTES), r.i64());
            case 118 -> new UploadStarted(r.exactBytes(ATTACHMENT_ID_BYTES));
            case 119 -> new AttachmentChunk(r.i64(), r.bytes(MAX_CHUNK_BYTES));
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
            case 112 -> new KeyStatusResult(bool(r.u8()), (int) r.i64(), (int) r.i64());
            case 113 -> new Bundle(new com.emirchat.e2e.PrekeyBundle(r.exactBytes(Identity.ENCODED_PUBLIC_KEY_LENGTH),
                    r.exactBytes(X25519_KEY), r.exactBytes(SIGNATURE), (int) r.i64(), r.exactBytes(X25519_KEY),
                    r.exactBytes(SIGNATURE), (int) r.i64(), r.bytes(X25519_KEY)));
            case 114 -> new EnvelopeAccepted(r.i64(), r.i64());
            case 115 -> new PendingCount((int) r.i64());
            case 125 -> new EnvelopeReceived(new Envelope(r.i64(), r.string(MAX_USERNAME_BYTES), r.i64(), r.i64(),
                    r.bytes(MAX_ENVELOPE_BYTES)));
            case 126 -> new EnvelopeDelivered(r.i64());
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

    int X25519_KEY = 44;
    int SIGNATURE = 64;

    private static Wire.Writer writeGroup(Wire.Writer w, GroupInfo g) {
        w.i64(g.id()).string(g.name()).string(g.owner()).u16(g.members().size());
        for (Member m : g.members()) w.string(m.username()).bytes(m.identityKey());
        return w;
    }

    private static GroupInfo readGroup(Wire.Reader r) throws ProtocolException {
        long id = r.i64();
        String name = r.string(MAX_GROUP_NAME_BYTES);
        String owner = r.string(MAX_USERNAME_BYTES);
        int n = r.count(MAX_GROUP_MEMBERS);
        List<Member> members = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            members.add(new Member(r.string(MAX_USERNAME_BYTES), r.exactBytes(Identity.ENCODED_PUBLIC_KEY_LENGTH)));
        }
        return new GroupInfo(id, name, owner, members);
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
