package com.emirchat.client;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
import com.emirchat.chat.ChatMessage;
import com.emirchat.chat.FileMessage;
import com.emirchat.crypto.Identity;
import com.emirchat.e2e.Attachments;
import com.emirchat.e2e.GroupNames;
import com.emirchat.e2e.Messenger;
import com.emirchat.protocol.Wire;
import com.emirchat.trust.KeyChangedException;
import com.emirchat.trust.KnownPeers;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A logged-in account: server connection + end-to-end encryption + friend key pinning.
 *
 * <p>Trust model: the server reports each friend's identity key, but the client pins it the first
 * time (trust on first use) and refuses to encrypt to, or accept messages from, a different key
 * until the user re-verifies it with {@link #trustNewKey}. The server alone can therefore never
 * slip in a key of its own.
 */
public final class ChatClient implements AutoCloseable {
    static final int PREKEY_BATCH = 50;
    static final int PREKEY_LOW_WATER = 20;
    private static final int CONTENT_CHAT = 1;
    private static final int CONTENT_SENDER_KEY = 2;
    private static final int CONTENT_FILE = 3;
    /** Sets the conversation's disappearing-message timer: i64 seconds (0 = off). */
    private static final int CONTENT_TIMER = 4;
    /** Wraps a chat or file message that disappears: i64 seconds ‖ u8 inner type ‖ bytes inner payload. */
    private static final int CONTENT_EXPIRING = 5;
    /** Hands out the group's name key (admin only): see {@link GroupNames}. */
    private static final int CONTENT_GROUP_NAME_KEY = 6;
    /**
     * A chat or file message with an id that later actions can refer to:
     * u8 1 ‖ bytes id (16) ‖ i64 expireSeconds ‖ bytes replyTo (0 or 16) ‖ u8 kind ‖ bytes body.
     */
    private static final int CONTENT_MESSAGE = 7;
    /** Refers to an earlier message: u8 action ‖ bytes target (0 or 16) ‖ string value. */
    private static final int CONTENT_ACTION = 8;
    /** Call signalling: u8 kind ‖ 16-byte call id ‖ bytes call key (offer and answer). */
    private static final int CONTENT_CALL = 9;
    /**
     * 3.6, only between our own devices: something we sent from another of our computers
     * ({@code string peer ‖ u8 content type ‖ bytes payload}), so every computer shows the chat.
     */
    private static final int CONTENT_SYNC = 10;
    /**
     * 3.6: another content, padded with zeros to the next power of two (at least 256 bytes), so the
     * server cannot tell messages apart by their length ({@code bytes inner ‖ bytes zeros}).
     */
    private static final int CONTENT_PADDED = 11;
    private static final int KIND_TEXT = 1;
    private static final int KIND_FILE = 2;
    /** 3.4: a poll (see {@link com.emirchat.chat.Poll}). */
    private static final int KIND_POLL = 3;
    /** 3.4: a text inside the thread of the message it replies to. */
    private static final int KIND_THREAD = 4;
    /** An amber message (3.6): hidden until its date. */
    private static final int KIND_CAPSULE = 5;
    public static final int MESSAGE_ID_BYTES = 16;
    public static final int MAX_ACTION_VALUE_BYTES = ChatMessage.MAX_TEXT_BYTES;
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    /** What an action does to its target message. */
    public enum ActionKind {
        /** value: an emoji, or empty to take the reaction back */
        REACT(1),
        /** value: the new text (only the author may edit) */
        EDIT(2),
        /** removes the message for everyone (only the author may delete) */
        DELETE(3),
        /** direct messages: everything up to and including the target was read */
        READ(4),
        /** value "1" while typing, "0" when stopped; no target */
        TYPING(5),
        /** value "1" pins the target for everyone in the chat, "0" unpins it */
        PIN(6),
        /** value: the chat's new topic (empty clears it); no target */
        TOPIC(7),
        /** value: the chosen answer's index in the target poll, or empty to take the vote back */
        VOTE(8),
        /** groups only: the sender's current voice room key (see VoiceCrypto); no target, never shown */
        VOICE_KEY(9),
        /** direct messages only: the sender's time zone (an IANA id such as Europe/Istanbul); no target */
        CLOCK(10),
        /** direct messages only: a scorpion walks over and stings the composer (3.6); no target, value empty */
        NUDGE(11),
        /** direct messages only (3.6): the sender's access key for sealed messages, base64; no target, never shown */
        ACCESS_KEY(12);

        final int code;

        ActionKind(int code) {
            this.code = code;
        }

        static ActionKind of(int code) throws ProtocolException {
            for (ActionKind k : values()) if (k.code == code) return k;
            throw new ProtocolException("Unknown action " + code);
        }

        /** The kind, or null for one from a newer version. */
        static ActionKind orNull(int code) {
            for (ActionKind k : values()) if (k.code == code) return k;
            return null;
        }
    }

    /** A chat: a direct message with a user, or a group. */
    public record Chat(String user, long groupId) {
        public static Chat dm(String user) { return new Chat(user, 0); }

        public static Chat group(long groupId) { return new Chat(null, groupId); }

        public boolean isGroup() { return groupId != 0; }
    }

    /**
     * A message as received.
     *
     * @param messageId identifies it in later actions (16 bytes; random for messages from older clients)
     * @param text      a text message, or null
     * @param file      a file message, or null
     * @param replyTo   the id of the message this answers, or empty
     */
    public record Incoming(String from, long groupId, byte[] messageId, ChatMessage text, FileMessage file,
                           long expireSeconds, byte[] replyTo, long envelopeId, com.emirchat.chat.Poll poll, boolean thread,
                           com.emirchat.chat.Capsule capsule) {
        public Incoming(String from, long groupId, byte[] messageId, ChatMessage text, FileMessage file,
                        long expireSeconds, byte[] replyTo, long envelopeId) {
            this(from, groupId, messageId, text, file, expireSeconds, replyTo, envelopeId, null, false, null);
        }

        public Incoming(String from, long groupId, byte[] messageId, ChatMessage text, FileMessage file,
                        long expireSeconds, byte[] replyTo, long envelopeId, com.emirchat.chat.Poll poll, boolean thread) {
            this(from, groupId, messageId, text, file, expireSeconds, replyTo, envelopeId, poll, thread, null);
        }
    }

    /** A reaction, edit, deletion, read receipt or typing notice from {@code from}. */
    public record Action(String from, long groupId, ActionKind kind, byte[] target, String value) {}

    /** What a send produced: the message id, the envelope id (direct messages) and anyone skipped. */
    public record Sent(byte[] messageId, long envelopeId, List<String> skipped, FileMessage file) {}
    /** Shown for a group whose name key has not arrived yet. */
    public static final String ENCRYPTED_GROUP_NAME = "Encrypted group";
    /** Longest disappearing-message timer accepted (four weeks). */
    public static final long MAX_TIMER_SECONDS = 28L * 24 * 3600;
    private static final int MAX_CONTENT_BYTES = ChatMessage.MAX_TEXT_BYTES + 256;
    /** Padding never needs more than the content it pads. */
    private static final int MAX_PADDING = 64 * 1024 - 1;
    /** Upload/download requests kept in flight at once (the server answers them in order). */
    private static final int TRANSFER_WINDOW = 8;

    /** Everything the UI needs to hear about. All callbacks run on the client's inbox thread. */
    public interface Listener {
        default void message(String from, ChatMessage message, long envelopeId) {}

        /** A message arrived but could not be decrypted (e.g. forged, or sent to an old device). */
        default void undecryptable(String from, String reason) {}

        /** Something from a newer version that this one cannot show (it decrypted fine). */
        default void unsupported(String from, long groupId) {}

        default void delivered(long envelopeId) {}

        /** A friend's key no longer matches the pinned one; messages are blocked until re-verified. */
        default void keyChanged(String friend) {}

        /** Any other server push (friend requests, presence...). */
        default void push(Packet packet) {}

        default void disconnected() {}

        default void groupMessage(long groupId, String from, ChatMessage message, long envelopeId) {}

        /** Created, renamed, or members changed (including us being added). */
        default void groupUpdated(Packet.GroupInfo group) {}

        /** We left or were removed. */
        default void groupRemoved(long groupId) {}

        /** A friend shared a file; fetch it with {@link ChatClient#downloadFile}. */
        default void file(String from, FileMessage file, long envelopeId) {}

        default void groupFile(long groupId, String from, FileMessage file, long envelopeId) {}

        // Disappearing messages: expireSeconds > 0 means the message should be deleted that many
        // seconds after it arrives. The defaults forward to the plain callbacks.

        default void message(String from, ChatMessage message, long envelopeId, long expireSeconds) {
            message(from, message, envelopeId);
        }

        default void groupMessage(long groupId, String from, ChatMessage message, long envelopeId, long expireSeconds) {
            groupMessage(groupId, from, message, envelopeId);
        }

        default void file(String from, FileMessage file, long envelopeId, long expireSeconds) {
            file(from, file, envelopeId);
        }

        default void groupFile(long groupId, String from, FileMessage file, long envelopeId, long expireSeconds) {
            groupFile(groupId, from, file, envelopeId);
        }

        /** Someone changed the disappearing-message timer of a DM (groupId 0) or a group. */
        default void timerChanged(String from, long groupId, long seconds) {}

        /** We sent this from another of our computers (to {@code peer}, or to the group in it). */
        default void sentElsewhere(String peer, Incoming m) {}

        /** We did this from another of our computers (in the chat with {@code peer}, or in the group). */
        default void actionElsewhere(String peer, Action a) {}

        /** This computer was removed from the account on another one: sign out. */
        default void deviceRemoved() {}

        /** Another computer asks to join the account: show the code, then {@link ChatClient#approveLink} or deny. */
        default void linkRequest(DeviceLinking.LinkPrompt prompt) {}

        /** Any received chat or file message. The default forwards to the simpler callbacks above. */
        default void incoming(Incoming m) {
            if (m.poll() != null) { // simple listeners (the terminal client) see a poll as text
                ChatMessage text = ChatMessage.text("📊 " + m.poll().question() + " — " + String.join(" / ", m.poll().options()));
                if (m.groupId() == 0) message(m.from(), text, m.envelopeId(), m.expireSeconds());
                else groupMessage(m.groupId(), m.from(), text, m.envelopeId(), m.expireSeconds());
                return;
            }
            if (m.text() != null) {
                if (m.groupId() == 0) message(m.from(), m.text(), m.envelopeId(), m.expireSeconds());
                else groupMessage(m.groupId(), m.from(), m.text(), m.envelopeId(), m.expireSeconds());
            } else {
                if (m.groupId() == 0) file(m.from(), m.file(), m.envelopeId(), m.expireSeconds());
                else groupFile(m.groupId(), m.from(), m.file(), m.envelopeId(), m.expireSeconds());
            }
        }

        default void action(Action action) {}

        /** A voice call started, rang, connected, changed or ended (runs on the calls thread). */
        default void call(com.emirchat.call.Calls.Call call) {}

        /** Our voice room changed: joined, left, people, speakers, muting. */
        default void voice(com.emirchat.call.VoiceChannels.State state) {}

        /** Who is in the voice rooms of our groups. */
        default void voiceRooms(Map<Long, List<String>> rooms) {}
    }

    // ------------------------------------------------------------------ rich messages

    /** Sends a text with an id; {@code replyTo} may be null. */
    public Sent send(Chat chat, String text, long expireSeconds, byte[] replyTo)
            throws IOException, ApiException, KeyChangedException {
        return send(chat, text, expireSeconds, replyTo, newMessageId());
    }

    /** Like {@link #send(Chat, String, long, byte[])} with an id chosen beforehand (to store the message first). */
    public Sent send(Chat chat, String text, long expireSeconds, byte[] replyTo, byte[] messageId)
            throws IOException, ApiException, KeyChangedException {
        return sendMessage(chat, KIND_TEXT, ChatMessage.text(text).encode(), expireSeconds, replyTo, null, messageId);
    }

    /** Sends a poll; votes arrive as {@link ActionKind#VOTE} actions on its id. */
    public Sent sendPoll(Chat chat, com.emirchat.chat.Poll poll, long expireSeconds, byte[] messageId)
            throws IOException, ApiException, KeyChangedException {
        return sendMessage(chat, KIND_POLL, poll.encode(), expireSeconds, null, null, messageId);
    }

    /** An amber message: shown as a stone with its date until then (see {@link com.emirchat.chat.Capsule}). */
    public Sent sendCapsule(Chat chat, com.emirchat.chat.Capsule capsule, long expireSeconds, byte[] messageId)
            throws IOException, ApiException, KeyChangedException {
        return sendMessage(chat, KIND_CAPSULE, capsule.encode(), expireSeconds, null, null, messageId);
    }

    /** A reply inside the thread of {@code root} (shown in the thread, not in the chat itself). */
    public Sent sendThreadReply(Chat chat, byte[] root, String text, long expireSeconds, byte[] messageId)
            throws IOException, ApiException, KeyChangedException {
        if (root == null || root.length != MESSAGE_ID_BYTES) throw new IllegalArgumentException("A thread needs its first message");
        return sendMessage(chat, KIND_THREAD, ChatMessage.text(text).encode(), expireSeconds, root, null, messageId);
    }

    /** A fresh random message id. */
    public static byte[] newMessageId() {
        byte[] id = new byte[MESSAGE_ID_BYTES];
        RANDOM.nextBytes(id);
        return id;
    }

    /** Encrypts and uploads a file, then sends it with an id. */
    public Sent sendFile(Chat chat, String name, String mimeType, byte[] data, long expireSeconds, byte[] replyTo)
            throws IOException, ApiException, KeyChangedException {
        checkTimer(expireSeconds);
        if (chat.isGroup()) {
            if (!groups.containsKey(chat.groupId())) throw new ApiException(ErrorCode.GROUP_NOT_FOUND, "You are not in that group.");
        } else {
            pinnedKey(chat.user()); // refuse before uploading if their key changed
        }
        FileMessage file = upload(data, name, mimeType, chat.isGroup() ? "" : chat.user(), chat.groupId());
        return sendMessage(chat, KIND_FILE, file.encode(), expireSeconds, replyTo, file, newMessageId());
    }

    /** Sends a reaction, edit, deletion, read receipt or typing notice. */
    public void sendAction(Chat chat, ActionKind kind, byte[] target, String value)
            throws IOException, ApiException, KeyChangedException {
        byte[] t = target == null ? new byte[0] : target;
        if (t.length != 0 && t.length != MESSAGE_ID_BYTES) throw new IllegalArgumentException("Bad target");
        String v = value == null ? "" : value;
        if (v.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_ACTION_VALUE_BYTES) {
            throw new IllegalArgumentException("Too long");
        }
        Content content = new Content(CONTENT_ACTION, new Wire.Writer().u8(kind.code).bytes(t).string(v).toByteArray());
        if (chat.isGroup()) sendGroupContent(chat.groupId(), content);
        else sendPairwise(chat.user(), content);
    }

    /** Tests: an action of any code, as a newer version would send it. */
    void sendRawAction(Chat chat, int code, String value) throws IOException, ApiException, KeyChangedException {
        Content content = new Content(CONTENT_ACTION, new Wire.Writer().u8(code).bytes(new byte[0]).string(value).toByteArray());
        if (chat.isGroup()) sendGroupContent(chat.groupId(), content);
        else sendPairwise(chat.user(), content);
    }

    /** Tests: a message of any kind, as a newer version would send it. */
    Sent sendRawMessage(Chat chat, int kind, byte[] body) throws IOException, ApiException, KeyChangedException {
        return sendMessage(chat, kind, body, 0, null, null, newMessageId());
    }

    private Sent sendMessage(Chat chat, int kind, byte[] body, long expireSeconds, byte[] replyTo, FileMessage file,
                             byte[] id) throws IOException, ApiException, KeyChangedException {
        checkTimer(expireSeconds);
        byte[] reply = replyTo == null ? new byte[0] : replyTo;
        if (reply.length != 0 && reply.length != MESSAGE_ID_BYTES) throw new IllegalArgumentException("Bad reply id");
        if (id.length != MESSAGE_ID_BYTES) throw new IllegalArgumentException("Bad message id");
        Content content = new Content(CONTENT_MESSAGE, new Wire.Writer().u8(1).bytes(id).i64(expireSeconds).bytes(reply)
                .u8(kind).bytes(body).toByteArray());
        try {
            if (chat.isGroup()) return new Sent(id, 0, sendGroupContent(chat.groupId(), content), file);
            return new Sent(id, sendPairwise(chat.user(), content).id(), List.of(), file);
        } finally {
            byte[] sealedBody = lastSealed.get();
            lastSealed.remove();
            if (sealedBody != null) rememberSealed(id, new Sealed(sealedBody, chat.isGroup()));
        }
    }

    // ------------------------------------------------------------------ what the server carried

    /**
     * A message as it travelled: the exact bytes the server stored and passed on (an envelope
     * body), and how it was encrypted ({@code group}: the sender's group key, else the pairwise
     * session). Kept in memory for recent messages of this session only, to show what
     * end-to-end encryption looks like from the outside.
     */
    public record Sealed(byte[] ciphertext, boolean group) {}

    private static final int SEALED_KEPT = 2000;
    private final Map<String, Sealed> sealedById = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(64, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Sealed> eldest) {
                    return size() > SEALED_KEPT;
                }
            });
    /** The envelope body of the message being sent on this thread (set by the send paths). */
    private final ThreadLocal<byte[]> lastSealed = new ThreadLocal<>();
    /** The envelope body being opened (the inbox handles one envelope at a time). */
    private volatile byte[] opening;

    private void rememberSealed(byte[] messageId, Sealed sealed) {
        sealedById.put(java.util.HexFormat.of().formatHex(messageId), sealed);
    }

    /** What the server carried for a message of this session, or null (older, or from before a restart). */
    public Sealed sealed(byte[] messageId) {
        return messageId == null ? null : sealedById.get(java.util.HexFormat.of().formatHex(messageId));
    }

    private static byte[] randomId() {
        return newMessageId();
    }

    final ServerConnection connection;
    private final Messenger messenger;
    private final com.emirchat.e2e.GroupKeys groupKeys;
    private final Map<Long, Packet.GroupInfo> groups = new ConcurrentHashMap<>();
    private final Map<String, byte[]> memberKeys = new ConcurrentHashMap<>();
    private final Map<String, Boolean> online = new ConcurrentHashMap<>();
    private volatile Packet.FriendList lastFriends = new Packet.FriendList(List.of(), List.of(), List.of());
    Path dataDir;
    private GroupNames groupNames;
    private final KnownPeers contacts;
    final String username;
    private final Map<String, byte[]> friendKeys = new ConcurrentHashMap<>();
    /**
     * Who is a friend right now, as the server last said. Kept apart from {@link #friendKeys},
     * which is a cache of keys and also remembers people who are friends no longer.
     */
    final java.util.Set<String> friends = ConcurrentHashMap.newKeySet();
    private final Map<Long, Boolean> seen = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Boolean> e) { return size() > 10_000; }
    });
    /** Envelopes already processed (also across restarts), newest last; see {@link #receive}. */
    private final java.util.LinkedHashSet<Long> handled = new java.util.LinkedHashSet<>();
    private static final int HANDLED_KEPT = 2000;

    private void loadHandled() {
        try {
            Path file = dataDir.resolve("handled");
            if (!Files.exists(file)) return;
            for (String line : Files.readAllLines(file)) {
                try {
                    long id = Long.parseLong(line.strip());
                    handled.add(id);
                    seen.put(id, Boolean.TRUE);
                } catch (NumberFormatException ignored) {
                    // skip a damaged line
                }
            }
        } catch (IOException ignored) {
            // without the list, a re-delivered message is reported as undecryptable at worst
        }
    }

    private void markHandled(long envelopeId) {
        synchronized (handled) {
            handled.add(envelopeId);
            while (handled.size() > HANDLED_KEPT) handled.remove(handled.iterator().next());
            try {
                Path file = dataDir.resolve("handled");
                Path tmp = dataDir.resolve("handled.tmp");
                Files.write(tmp, handled.stream().map(String::valueOf).toList());
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                // best effort, as above
            }
        }
    }

    private volatile Thread inboxThread;
    private final ExecutorService inbox = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "inbox");
        t.setDaemon(true);
        inboxThread = t;
        return t;
    });

    /**
     * Waits until every server push received so far has been handled. The server sends what a
     * request changed (a new member, say) before it answers the request, but pushes are handled
     * on the inbox thread, so when the answer arrives our picture of a group can still be the old
     * one. Anything that acts on that picture right after calls this first.
     */
    private void catchUpWithPushes() {
        if (Thread.currentThread() == inboxThread || inbox.isShutdown()) return; // already in order
        try {
            inbox.submit(() -> { }).get(ServerConnection.CALL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException
                 | java.util.concurrent.RejectedExecutionException e) {
            // closing, or the inbox is stuck: go on with what we know
        }
    }
    private volatile Listener listener = new Listener() {};
    private volatile boolean closing;

    private ChatClient(ServerConnection connection, Messenger messenger, com.emirchat.e2e.GroupKeys groupKeys,
                       KnownPeers contacts, String username) {
        this.groupKeys = groupKeys;
        this.connection = connection;
        this.messenger = messenger;
        this.contacts = contacts;
        this.username = username;
    }

    /**
     * Logs in (or registers), then publishes encryption keys as needed and requests queued messages.
     *
     * @param accountDir per-account local storage (keys, sessions, pinned friend keys)
     */
    public static ChatClient start(ServerConnection connection, Identity device, Path accountDir, String user,
                                   String password, boolean register, Listener listener)
            throws IOException, ApiException {
        return start(connection, device, accountDir, user, password, null, register, listener);
    }

    /**
     * @param code a code from the authenticator app (or a recovery code) when the server asked for
     *             one with {@link com.emirchat.api.ErrorCode#CODE_REQUIRED}, else null
     */
    public static ChatClient start(ServerConnection connection, Identity device, Path accountDir, String user,
                                   String password, String code, boolean register, Listener listener)
            throws IOException, ApiException {
        Packet.LoggedIn loggedIn = register ? connection.register(user, password) : connection.login(user, password, code);
        Path dir = accountDir.resolve(loggedIn.username());
        Files.createDirectories(dir);
        // keys and sessions are encrypted at rest under the password the server just accepted
        com.emirchat.e2e.Vault.unlock(dir, password);
        Messenger messenger = Messenger.open(dir.resolve("e2e"), device);
        KnownPeers contacts = new KnownPeers(dir.resolve("contacts"), (name, fp) -> true); // TOFU, verify later
        ChatClient client = new ChatClient(connection, messenger, new com.emirchat.e2e.GroupKeys(dir.resolve("e2e")),
                contacts, loggedIn.username());
        client.listener = listener;
        client.dataDir = dir;
        client.groupNames = new GroupNames(dir.resolve("e2e"));
        client.loadHandled();
        client.deviceKey = device.publicKeyBytes();
        client.account = device;
        client.linking = new DeviceLinking(connection, () -> client.listener);
        client.deviceId = readDeviceId(dir);
        if (connection.hasAccountSecurity()) {
            client.dataDir = dir; // the vault files below are read and written there
            // a 3.6 server: say which of the account's computers this is, before keys are published
            byte[] secret = com.emirchat.e2e.Vault.read(dir.resolve(DeviceLinking.DEVICE_SECRET_FILE));
            Packet answer = connection.call(new Packet.SetDevice(client.deviceId, deviceName(), secret == null ? new byte[0] : secret));
            if (answer instanceof Packet.DeviceSecret issued) {
                com.emirchat.e2e.Vault.write(dir.resolve(DeviceLinking.DEVICE_SECRET_FILE), issued.secret());
            }
            client.multi = true;
        }
        client.calls = new com.emirchat.call.Calls(new com.emirchat.call.Calls.Transport() {
            @Override public void signal(String peer, int kind, byte[] callId, byte[] callKey) throws Exception {
                client.sendPairwise(peer, new Content(CONTENT_CALL,
                        new Wire.Writer().u8(kind).bytes(callId).bytes(callKey).toByteArray()));
            }

            @Override public void audio(String peer, byte[] data) {
                connection.sendAudio(peer, data);
            }

            @Override public java.net.InetSocketAddress directServer() {
                return connection.remoteAddress();
            }

            @Override public byte[] relayTicket(String peer) throws Exception {
                return ((Packet.RelayTicket) connection.call(new Packet.OpenRelay(peer))).ticket();
            }
        }, () -> client.audioDevices.get(), call -> client.listener.call(call));
        // Direct calls show each side's address to the other: by default only with people whose
        // key the user has verified (the app's settings can widen or close this).
        client.calls.directAllowed(friend -> client.isVerified(friend) && !client.keyChanged(friend));
        client.voice = new com.emirchat.call.VoiceChannels(loggedIn.username(), new com.emirchat.call.VoiceChannels.Transport() {
            @Override public List<String> join(long groupId) throws Exception {
                if (!client.voiceSupported()) throw new IOException(com.emirchat.call.VoiceChannels.UNSUPPORTED);
                return ((Packet.VoiceRoom) connection.call(new Packet.VoiceJoin(groupId))).participants();
            }

            @Override public void leave(long groupId) throws Exception {
                connection.call(new Packet.VoiceLeave(groupId));
            }

            @Override public void audio(long groupId, byte[] packet) {
                connection.sendVoice(groupId, packet);
            }

            @Override public void sendKey(long groupId, String encodedKey) throws Exception {
                client.sendAction(Chat.group(groupId), ActionKind.VOICE_KEY, null, encodedKey);
            }
        }, () -> client.audioDevices.get(), () -> client.calls.current() != null, new com.emirchat.call.VoiceChannels.Listener() {
            @Override public void state(com.emirchat.call.VoiceChannels.State state) { client.listener.voice(state); }

            @Override public void rooms(Map<Long, List<String>> rooms) { client.listener.voiceRooms(rooms); }
        });
        connection.onPush(p -> {
            if (p instanceof Packet.CallAudioIn a) client.calls.audio(a.from(), a.data()); // live: skip the inbox queue
            else if (p instanceof Packet.VoiceAudioIn v) client.voice.audio(v.groupId(), v.from(), v.data());
            else {
                try {
                    client.inbox.execute(() -> client.onPush(p));
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                    // logging out: the server delivers anything unacknowledged again next time
                }
            }
        });
        connection.onClosed(() -> {
            client.calls.connectionLost();
            if (!client.closing) client.inbox.execute(() -> client.listener.disconnected());
        });
        client.publishKeys();
        if (client.multi) {
            client.refreshOwnDevices();
            client.sealed.setUp();
        }
        client.refreshFriends();
        client.refreshGroups();
        for (Packet.GroupInfo g : client.groups()) client.maintainGroupName(g, false);
        connection.fetchPending();
        return client;
    }

    public String username() {
        return username;
    }

    public ServerConnection connection() {
        return connection;
    }

    /** Uploads the device's X3DH keys if the server lacks them, rotates the signed prekey, tops up one-time prekeys. */
    private void publishKeys() throws IOException, ApiException {
        Packet.KeyStatusResult status = connection.keyStatus();
        boolean rotated = messenger.rotateSignedPrekeyIfDue();
        Messenger.PublicKeys k = messenger.publicKeys();
        if (!status.hasKeys() || rotated || status.signedPrekeyId() != k.signedPrekeyId()) {
            connection.uploadKeys(new Packet.UploadKeys(k.dhIdentity(), k.dhIdentitySignature(), k.signedPrekeyId(),
                    k.signedPrekey(), k.signedPrekeySignature()));
        }
        int onServer = status.hasKeys() ? status.oneTimePrekeys() : 0;
        if (onServer < PREKEY_LOW_WATER) {
            List<Packet.Prekey> upload = new ArrayList<>();
            for (Map.Entry<Integer, byte[]> e : messenger.newOneTimePrekeys(PREKEY_BATCH)) {
                upload.add(new Packet.Prekey(e.getKey(), e.getValue()));
            }
            connection.uploadPrekeys(upload);
        }
    }

    public Packet.FriendList refreshFriends() throws IOException, ApiException {
        Packet.FriendList list = connection.listFriends();
        java.util.Set<String> current = new java.util.HashSet<>();
        for (Packet.Friend f : list.friends()) {
            friendKeys.put(f.username(), f.identityKey());
            online.put(f.username(), f.online());
            current.add(f.username());
        }
        friends.addAll(current);
        friends.retainAll(current);
        lastFriends = list;
        return list;
    }

    /** The friend list as of the last refresh (cheap, no network). */
    public Packet.FriendList friends() {
        return lastFriends;
    }

    public boolean isOnline(String user) {
        return online.getOrDefault(user, false);
    }

    byte[] deviceKey;

    /** This device's Ed25519 identity public key (what friends verify). */
    public byte[] deviceKey() {
        return deviceKey.clone();
    }

    /** This account's local data directory (history, keys, sessions). */
    public Path dataDir() {
        return dataDir;
    }

    /** The key the server currently reports for a friend or group member, or null. */
    public String currentFingerprint(String user) {
        byte[] key = knownKey(user);
        return key == null ? null : Identity.fingerprint(key);
    }

    /** True if the server now reports a different key than the one pinned for this user. */
    public boolean keyChanged(String user) {
        String pinned = contacts.fingerprint(user);
        String current = currentFingerprint(user);
        return pinned != null && current != null && !pinned.equals(current);
    }

    /** True if the user compared fingerprints with this contact and confirmed the currently pinned key. */
    public boolean isVerified(String user) {
        String pinned = contacts.fingerprint(user);
        if (pinned == null) return false;
        try {
            Path file = dataDir.resolve("verified");
            return Files.exists(file) && Files.readAllLines(file).contains(user + " " + pinned);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Records that the user verified this contact out-of-band. Pins the current key first if
     * nothing was pinned yet; refuses if the key changed (use {@link #trustNewKey}).
     */
    public void markVerified(String user) throws IOException, ApiException, KeyChangedException {
        pinnedKey(user); // pins on first use, throws if changed
        String line = user + " " + contacts.fingerprint(user);
        Path file = dataDir.resolve("verified");
        List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>();
        lines.removeIf(l -> l.startsWith(user + " "));
        lines.add(line);
        Files.write(file, lines);
    }

    /** Encrypts and sends a text message to a friend. */
    public Packet.EnvelopeAccepted sendText(String friend, String text) throws IOException, ApiException, KeyChangedException {
        return sendText(friend, text, 0);
    }

    /** @param expireSeconds 0, or how long after arrival the message disappears */
    public Packet.EnvelopeAccepted sendText(String friend, String text, long expireSeconds)
            throws IOException, ApiException, KeyChangedException {
        Sent sent = send(Chat.dm(friend), text, expireSeconds, null);
        return new Packet.EnvelopeAccepted(sent.envelopeId(), System.currentTimeMillis());
    }

    /** Sets the disappearing-message timer for both sides of a DM (0 turns it off). */
    public Packet.EnvelopeAccepted setTimer(String friend, long seconds) throws IOException, ApiException, KeyChangedException {
        return sendPairwise(friend, new Content(CONTENT_TIMER, timerPayload(seconds)));
    }

    /** Sets the disappearing-message timer for every member of a group. */
    public List<String> setGroupTimer(long groupId, long seconds) throws IOException, ApiException {
        return sendGroupContent(groupId, new Content(CONTENT_TIMER, timerPayload(seconds)));
    }

    private record Content(int type, byte[] payload) {}

    private static byte[] timerPayload(long seconds) {
        checkTimer(seconds);
        return new Wire.Writer().i64(seconds).toByteArray();
    }

    private static void checkTimer(long seconds) {
        if (seconds < 0 || seconds > MAX_TIMER_SECONDS) throw new IllegalArgumentException("Invalid timer");
    }

    private Packet.EnvelopeAccepted sendPairwise(String user, Content content)
            throws IOException, ApiException, KeyChangedException {
        return sendPairwise(user, content.type(), content.payload());
    }

    private Packet.EnvelopeAccepted sendPairwise(String user, int contentType, byte[] payload)
            throws IOException, ApiException, KeyChangedException {
        byte[] plaintext = new Wire.Writer().u8(contentType).bytes(payload).toByteArray();
        if (!multi) {
            byte[] key = pinnedKey(user);
            byte[] body = messenger.encrypt(user, key, () -> fetchBundle(user), plaintext);
            if (contentType == CONTENT_MESSAGE) lastSealed.set(body);
            return connection.sendEnvelope(user, body);
        }
        boolean self = user.equals(username);
        if (self && ownDevices.isEmpty()) return null; // no other computer to tell
        byte[] key = self ? deviceKey : pinnedKey(user);
        Packet.EnvelopeAccepted accepted = null;
        for (int attempt = 0; accepted == null; attempt++) {
            java.util.Set<Integer> devices = self ? ownDevices : devicesOf(user);
            List<Packet.DeviceEnvelope> copies = new ArrayList<>();
            for (int d : devices) {
                // padded only for 3.6 apps: older ones would not know the wrapper
                byte[] content = Boolean.TRUE.equals(deviceMulti.get(address(user, d))) ? pad(plaintext) : plaintext;
                byte[] body = messenger.encrypt(address(user, d), key, () -> takeBundle(user, d), content);
                if (contentType == CONTENT_MESSAGE && copies.isEmpty()) lastSealed.set(body);
                copies.add(new Packet.DeviceEnvelope(d, body));
            }
            try {
                accepted = self ? null : sealed.send(user, devices, copies);
                if (accepted == null) accepted = (Packet.EnvelopeAccepted) connection.call(new Packet.SendToDevices(user, copies));
            } catch (ApiException e) {
                if (e.code() != ErrorCode.STALE_DEVICES || attempt > 0) throw e;
                devicesChanged(user); // a computer was added or removed: ask again and encrypt for each
            }
        }
        if (!self && contentType == CONTENT_MESSAGE) sealed.giveKey(user);
        if (!self && SYNCED.contains(contentType) && !ownDevices.isEmpty()) {
            try {
                sendPairwise(username, CONTENT_SYNC, new Wire.Writer().string(user).u8(contentType).bytes(payload).toByteArray());
            } catch (IOException | ApiException e) {
                // the message went out; only our other computers miss it
            }
        }
        return accepted;
    }

    /** Sealed sending (3.6): access keys and the anonymous connection. */
    final SealedSending sealed = new SealedSending(this);

    /** Seal messages when we can (off in tests that look at what the server sees). */
    public void sealing(boolean on) {
        sealed.sealing(on);
    }

    /** What our other computers are told about when we send it. */
    private static final java.util.Set<Integer> SYNCED = java.util.Set.of(CONTENT_CHAT, CONTENT_FILE, CONTENT_EXPIRING,
            CONTENT_MESSAGE, CONTENT_ACTION);

    // ------------------------------------------------------------------ several devices (3.6)

    /** Which of the account's computers this is: 0 for the first, the number it got when linked otherwise. */
    int deviceId;
    /** The server knows several devices per account (3.6): messages go to each device on their own. */
    private volatile boolean multi;
    /** Our own other computers. */
    private volatile java.util.Set<Integer> ownDevices = java.util.Set.of();
    /** Devices of other people, as last fetched. */
    private final Map<String, java.util.Set<Integer>> knownDevices = new java.util.concurrent.ConcurrentHashMap<>();
    /** Which devices are 3.6 apps, by "user#device" (as last fetched). */
    final Map<String, Boolean> deviceMulti = new java.util.concurrent.ConcurrentHashMap<>();

    /** Wraps content so its length shows only which power of two (from 256 bytes) it fits in. */
    static byte[] pad(byte[] plaintext) {
        int bare = 1 + 2 + plaintext.length + 2;
        int size = 256;
        while (size < bare) size <<= 1;
        return new Wire.Writer().u8(CONTENT_PADDED).bytes(plaintext).bytes(new byte[size - bare]).toByteArray();
    }

    /** Bundles fetched but not used yet, by "user#device". */
    private final Map<String, com.emirchat.e2e.PrekeyBundle> freshBundles = new java.util.concurrent.ConcurrentHashMap<>();

    /** "efe" for someone's first device, "efe#2" for another: each has sessions of its own. */
    static String address(String user, int device) {
        return device == 0 ? user : user + "#" + device;
    }

    static int readDeviceId(Path dir) {
        try {
            Path f = dir.resolve(DeviceLinking.DEVICE_FILE);
            return Files.exists(f) ? Integer.parseInt(Files.readString(f).strip()) : 0;
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    /** This computer's name as other devices list it. */
    static String deviceName() {
        String name = System.getenv("COMPUTERNAME");
        if (name == null) name = System.getenv("HOSTNAME");
        if (name == null) {
            try {
                name = java.net.InetAddress.getLocalHost().getHostName();
            } catch (IOException e) {
                name = "";
            }
        }
        name = name.replaceAll("\\.local$", "").strip();
        String os = System.getProperty("os.name", "");
        String full = (name.isEmpty() ? "" : name + " · ") + os;
        byte[] b = full.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return b.length <= Packet.MAX_DEVICE_NAME_BYTES ? full : os;
    }

    /** Our other computers, as the server knows them. */
    private void refreshOwnDevices() throws IOException, ApiException {
        java.util.Set<Integer> mine = new java.util.TreeSet<>();
        for (Packet.DeviceInfo d : ((Packet.DeviceList) connection.call(new Packet.ListDevices())).devices()) {
            if (d.id() != deviceId) mine.add(d.id());
        }
        ownDevices = java.util.Set.copyOf(mine);
    }

    /** The account's computers, for the settings page (3.6 servers). */
    public List<Packet.DeviceInfo> devices() throws IOException, ApiException {
        if (!multi) return List.of();
        return ((Packet.DeviceList) connection.call(new Packet.ListDevices())).devices();
    }

    public void removeDevice(int id) throws IOException, ApiException {
        connection.call(new Packet.RemoveDevice(id));
        refreshOwnDevices();
        devicesChanged(username);
    }

    public int deviceId() {
        return deviceId;
    }

    private java.util.Set<Integer> devicesOf(String user) throws IOException, ApiException {
        java.util.Set<Integer> known = knownDevices.get(user);
        if (known != null) return known;
        fetchDevices(user);
        return knownDevices.getOrDefault(user, java.util.Set.of());
    }

    private void fetchDevices(String user) throws IOException, ApiException {
        Packet.DeviceBundles b = (Packet.DeviceBundles) connection.call(new Packet.FetchDeviceBundles(user));
        java.util.Set<Integer> ids = new java.util.TreeSet<>();
        for (Packet.DeviceBundle d : b.devices()) {
            ids.add(d.deviceId());
            freshBundles.put(address(user, d.deviceId()), d.bundle());
            deviceMulti.put(address(user, d.deviceId()), d.multi());
            sealed.deviceDh.put(address(user, d.deviceId()), d.bundle().dhIdentity());
        }
        if (user.equals(username)) {
            ids.remove(deviceId);
            ownDevices = java.util.Set.copyOf(ids);
        } else {
            knownDevices.put(user, java.util.Set.copyOf(ids));
        }
    }

    /** A bundle for starting a session with one device (fetched again when the last one was used). */
    private com.emirchat.e2e.PrekeyBundle takeBundle(String user, int device) throws IOException {
        com.emirchat.e2e.PrekeyBundle b = freshBundles.remove(address(user, device));
        if (b != null) return b;
        try {
            fetchDevices(user);
        } catch (ApiException e) {
            throw new IOException(e.getMessage(), e);
        }
        b = freshBundles.remove(address(user, device));
        if (b == null) throw new IOException(user + " has no keys for that computer");
        return b;
    }

    /** Someone's computers changed: forget what we knew, and give them our group keys again. */
    private void devicesChanged(String user) throws IOException, ApiException {
        knownDevices.remove(user);
        freshBundles.keySet().removeIf(k -> k.equals(user) || k.startsWith(user + "#"));
        if (user.equals(username)) refreshOwnDevices();
        for (Packet.GroupInfo g : groups.values()) {
            if (!user.equals(username) && !g.hasMember(user)) continue;
            groupKeys.unmarkDistributed(g.id(), user);
            groupNames.unmarkDistributed(g.id(), user);
        }
    }

    /** The account's identity, as this computer signed in with it (to approve another computer). */
    Identity account;
    /** Computers asking to join, while we compare codes with them. */
    private DeviceLinking linking;

    /** Approves a computer asking to join, after the user saw the same code on both. */
    public void approveLink(DeviceLinking.LinkPrompt prompt) throws IOException, ApiException {
        linking.approve(prompt, account);
    }

    public void approveLink(DeviceLinking.LinkPrompt prompt, Identity accountIdentity) throws IOException, ApiException {
        linking.approve(prompt, accountIdentity);
    }

    public void denyLink(DeviceLinking.LinkPrompt prompt) throws IOException, ApiException {
        linking.deny(prompt);
    }

    // ------------------------------------------------------------------ files

    /** The description of a file just sent, and the envelope that carried it. */
    public record SentFile(FileMessage file, long envelopeId) {}

    /** Encrypts a file, uploads the ciphertext, and sends its key to a friend end-to-end. */
    public SentFile sendFile(String friend, String name, String mimeType, byte[] data)
            throws IOException, ApiException, KeyChangedException {
        return sendFile(friend, name, mimeType, data, 0);
    }

    public SentFile sendFile(String friend, String name, String mimeType, byte[] data, long expireSeconds)
            throws IOException, ApiException, KeyChangedException {
        Sent sent = sendFile(Chat.dm(friend), name, mimeType, data, expireSeconds, null);
        return new SentFile(sent.file(), sent.envelopeId());
    }

    /**
     * Encrypts a file, uploads it for the group, and sends its key to the members.
     *
     * @return the file sent, and members skipped because their key changed and is not yet verified
     */
    public Map.Entry<FileMessage, List<String>> sendGroupFile(long groupId, String name, String mimeType, byte[] data)
            throws IOException, ApiException {
        return sendGroupFile(groupId, name, mimeType, data, 0);
    }

    public Map.Entry<FileMessage, List<String>> sendGroupFile(long groupId, String name, String mimeType, byte[] data,
                                                              long expireSeconds) throws IOException, ApiException {
        try {
            Sent sent = sendFile(Chat.group(groupId), name, mimeType, data, expireSeconds, null);
            return Map.entry(sent.file(), sent.skipped());
        } catch (KeyChangedException e) {
            throw new IllegalStateException(e); // groups skip members with changed keys instead
        }
    }

    /** Deletes the local encrypted copy of a file (after its message disappeared). */
    public void forgetFile(FileMessage file) {
        try {
            Files.deleteIfExists(attachmentFile(file.attachmentId()));
        } catch (IOException ignored) {
            // the copy is encrypted under a key that no longer exists anywhere
        }
    }

    private FileMessage upload(byte[] data, String name, String mimeType, String recipient, long groupId)
            throws IOException, ApiException {
        if (data.length > Attachments.MAX_FILE_BYTES) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Files can be at most " + Attachments.MAX_FILE_BYTES / (1024 * 1024) + " MB.");
        }
        Attachments.Sealed sealed = Attachments.seal(data);
        byte[] ciphertext = sealed.ciphertext();
        Packet started = connection.call(new Packet.UploadStart(ciphertext.length, recipient, groupId));
        if (!(started instanceof Packet.UploadStarted s)) throw new ApiException(ErrorCode.PROTOCOL, "Unexpected response");
        byte[] id = s.attachmentId();
        List<CompletableFuture<Packet>> inFlight = new ArrayList<>();
        for (int offset = 0; offset < ciphertext.length; offset += Packet.MAX_CHUNK_BYTES) {
            byte[] chunk = Arrays.copyOfRange(ciphertext, offset, Math.min(ciphertext.length, offset + Packet.MAX_CHUNK_BYTES));
            inFlight.add(connection.request(new Packet.UploadChunk(id, offset, chunk)));
            if (inFlight.size() >= TRANSFER_WINDOW) await(inFlight.remove(0));
        }
        for (CompletableFuture<Packet> f : inFlight) await(f);
        connection.call(new Packet.UploadFinish(id));
        cacheAttachment(id, ciphertext); // our own copy, still encrypted
        return new FileMessage(id, sealed.key(), sealed.digest(), data.length, name, mimeType, System.currentTimeMillis());
    }

    /**
     * Downloads (or reads from the local cache), checks and decrypts a shared file.
     *
     * @throws SecurityException if the server returned something other than what the sender described
     */
    public byte[] downloadFile(FileMessage file) throws IOException, ApiException {
        byte[] id = file.attachmentId();
        byte[] ciphertext = cachedAttachment(id, file.ciphertextSize());
        if (ciphertext == null) {
            ciphertext = new byte[Math.toIntExact(file.ciphertextSize())];
            List<CompletableFuture<Packet>> inFlight = new ArrayList<>();
            List<Long> offsets = new ArrayList<>();
            for (long offset = 0; offset < ciphertext.length; offset += Packet.MAX_CHUNK_BYTES) {
                inFlight.add(connection.request(new Packet.FetchAttachment(id, offset)));
                offsets.add(offset);
                if (inFlight.size() >= TRANSFER_WINDOW) place(ciphertext, offsets.remove(0), await(inFlight.remove(0)));
            }
            while (!inFlight.isEmpty()) place(ciphertext, offsets.remove(0), await(inFlight.remove(0)));
        }
        byte[] plaintext = Attachments.open(ciphertext, file.key(), file.digest());
        if (plaintext.length != file.size()) throw new SecurityException("File size does not match its description");
        cacheAttachment(id, ciphertext);
        return plaintext;
    }

    private static void place(byte[] target, long offset, Packet response) throws ApiException {
        if (!(response instanceof Packet.AttachmentChunk chunk)) throw new ApiException(ErrorCode.PROTOCOL, "Unexpected response");
        byte[] data = chunk.data();
        long expected = Math.min(Packet.MAX_CHUNK_BYTES, target.length - offset);
        if (chunk.totalSize() != target.length || data.length != expected) {
            throw new SecurityException("The server's copy has a different size than the sender described");
        }
        System.arraycopy(data, 0, target, (int) offset, data.length);
    }

    private static Packet await(CompletableFuture<Packet> future) throws IOException, ApiException {
        try {
            Packet p = future.get(ServerConnection.CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (p instanceof Packet.Error e) throw new ApiException(e.code(), e.message());
            return p;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("The server did not answer in time", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause().getMessage(), e.getCause());
        }
    }

    private Path attachmentFile(byte[] id) {
        return dataDir.resolve("attachments").resolve(Attachments.hex(id));
    }

    /** Encrypted copies of files we sent or opened, so they need not be downloaded again. */
    private void cacheAttachment(byte[] id, byte[] ciphertext) {
        try {
            Path file = attachmentFile(id);
            if (Files.exists(file)) return;
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "dl", ".tmp");
            Files.write(tmp, ciphertext);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            // the cache is an optimisation
        }
    }

    private byte[] cachedAttachment(byte[] id, long expectedSize) {
        try {
            Path file = attachmentFile(id);
            if (Files.exists(file) && Files.size(file) == expectedSize) return Files.readAllBytes(file);
        } catch (IOException ignored) {
            // fall back to downloading
        }
        return null;
    }

    // ------------------------------------------------------------------ groups

    /** Re-reads our groups; names are shown decrypted. */
    public List<Packet.GroupInfo> refreshGroups() throws IOException, ApiException {
        Packet.GroupList list = expectGroups(connection.call(new Packet.ListGroups()));
        java.util.Set<Long> current = new java.util.HashSet<>();
        for (Packet.GroupInfo g : list.groups()) {
            remember(g);
            current.add(g.id());
        }
        groups.keySet().retainAll(current);
        return groups();
    }

    /** Our groups, with names decrypted (or {@link #ENCRYPTED_GROUP_NAME} until the key arrives). */
    public List<Packet.GroupInfo> groups() {
        return List.copyOf(groups.values());
    }

    /**
     * Creates a group whose name only its members can read: the group is created without a name,
     * then a fresh name key encrypts the name, and the key goes to every member end-to-end.
     */
    public Packet.GroupInfo createGroup(String name, List<String> members) throws IOException, ApiException {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > GroupNames.MAX_NAME_BYTES) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Group names are 1-" + GroupNames.MAX_NAME_BYTES + " bytes.");
        }
        Packet response = connection.call(new Packet.CreateGroup("", members));
        if (!(response instanceof Packet.GroupInfo created)) throw new ApiException(ErrorCode.PROTOCOL, "Unexpected response");
        groupNames.rotate(created.id());
        byte[] sealed = groupNames.seal(created.id(), clean);
        connection.call(new Packet.RenameGroup(created.id(), sealed));
        Packet.GroupInfo g = new Packet.GroupInfo(created.id(), "", created.owner(), created.members(), sealed);
        remember(g);
        distributeNameKey(g);
        return groups.get(g.id());
    }

    /** Renames a group we administer; the new name is encrypted like the first. */
    public void renameGroup(long groupId, String name) throws IOException, ApiException {
        Packet.GroupInfo g = groups.get(groupId);
        if (g == null) throw new ApiException(ErrorCode.GROUP_NOT_FOUND, "You are not in that group.");
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > GroupNames.MAX_NAME_BYTES) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Group names are 1-" + GroupNames.MAX_NAME_BYTES + " bytes.");
        }
        if (!groupNames.hasKey(groupId)) groupNames.rotateAbove(groupId, GroupNames.versionOf(g.sealedName()));
        connection.call(new Packet.RenameGroup(groupId, groupNames.seal(groupId, clean)));
        distributeNameKey(g);
    }

    // ------------------------------------------------------------------ communities

    /** A new community (with only us in it); its name is encrypted like a group's. */
    public Packet.GroupInfo createCommunity(String name) throws IOException, ApiException {
        String clean = checkName(name);
        Packet response = connection.call(new Packet.CreateCommunity());
        if (!(response instanceof Packet.GroupInfo created)) throw new ApiException(ErrorCode.PROTOCOL, "Unexpected response");
        return nameNew(created, clean);
    }

    /** A new channel in a community we own or administer. */
    public Packet.GroupInfo createChannel(long communityId, String name) throws IOException, ApiException {
        String clean = checkName(name);
        Packet response = connection.call(new Packet.CreateChannel(communityId));
        if (!(response instanceof Packet.GroupInfo created)) throw new ApiException(ErrorCode.PROTOCOL, "Unexpected response");
        return nameNew(created, clean);
    }

    private Packet.GroupInfo nameNew(Packet.GroupInfo created, String name) throws IOException, ApiException {
        groupNames.rotate(created.id());
        byte[] sealed = groupNames.seal(created.id(), name);
        connection.call(new Packet.RenameGroup(created.id(), sealed));
        Packet.GroupInfo g = new Packet.GroupInfo(created.id(), "", created.owner(), created.members(), sealed,
                created.kind(), created.parentId(), created.position(), created.admins());
        remember(g);
        distributeNameKey(g);
        return groups.get(g.id());
    }

    private static String checkName(String name) throws ApiException {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > GroupNames.MAX_NAME_BYTES) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Names are 1-" + GroupNames.MAX_NAME_BYTES + " bytes.");
        }
        return clean;
    }

    public void deleteChannel(long channelId) throws IOException, ApiException {
        connection.call(new Packet.DeleteChannel(channelId));
    }

    public void setAdmin(long communityId, String user, boolean admin) throws IOException, ApiException {
        connection.call(new Packet.SetAdmin(communityId, user, admin));
    }

    /** @return the invite code (shown once; the server keeps only its hash) */
    public String createInvite(long communityId, int maxUses, int hours) throws IOException, ApiException {
        Packet response = connection.call(new Packet.CreateInvite(communityId, maxUses, hours));
        if (!(response instanceof Packet.InviteCreated created)) throw new ApiException(ErrorCode.PROTOCOL, "Unexpected response");
        return created.code();
    }

    public void revokeInvites(long communityId) throws IOException, ApiException {
        connection.call(new Packet.RevokeInvites(communityId));
    }

    /** Joins a community with an invite code; its channels arrive as group updates. */
    public Packet.GroupInfo joinCommunity(String code) throws IOException, ApiException {
        Packet response = connection.call(new Packet.JoinCommunity(code));
        if (!(response instanceof Packet.GroupInfo g)) throw new ApiException(ErrorCode.PROTOCOL, "Unexpected response");
        remember(g);
        return groups.get(g.id());
    }

    /** The channels of a community we are in (the community itself first: it is the general channel). */
    public List<Packet.GroupInfo> channels(long communityId) {
        List<Packet.GroupInfo> result = new ArrayList<>();
        Packet.GroupInfo root = groups.get(communityId);
        if (root != null) result.add(root);
        groups.values().stream().filter(g -> g.isChannel() && g.parentId() == communityId)
                .sorted(java.util.Comparator.comparingInt(Packet.GroupInfo::position).thenComparingLong(Packet.GroupInfo::id))
                .forEach(result::add);
        return result;
    }

    /**
     * The admin's upkeep of the group's encrypted name: moves a plaintext name from older versions
     * to an encrypted one, makes a new key after someone left, and hands the current key to members
     * who lack it. Other members do nothing here.
     */
    private void maintainGroupName(Packet.GroupInfo g, boolean someoneLeft) {
        boolean owner = g.owner().equals(username);
        if (!owner && !g.canManage(username)) return; // community admins hand out keys they hold, too
        try {
            byte[] sealed = g.sealedName();
            String plain = sealed.length > 0 ? groupNames.open(g.id(), sealed) : legacyName(g);
            if (plain == null || plain.isEmpty()) return; // a name we cannot read cannot be re-encrypted
            if (owner && (sealed.length == 0 || someoneLeft || !groupNames.hasKey(g.id()))) {
                groupNames.rotate(g.id());
                connection.call(new Packet.RenameGroup(g.id(), groupNames.seal(g.id(), plain)));
            }
            distributeNameKey(g);
        } catch (IOException | ApiException | IllegalArgumentException e) {
            listener.undecryptable("", "could not update the group name key: " + e.getMessage());
        }
    }

    private void distributeNameKey(Packet.GroupInfo g) throws IOException, ApiException {
        List<String> members = g.members().stream().map(Packet.Member::username).toList();
        for (String member : groupNames.needsKey(g.id(), members, username)) {
            try {
                sendPairwise(member, CONTENT_GROUP_NAME_KEY, groupNames.distribution(g.id()));
                groupNames.markDistributed(g.id(), member);
            } catch (KeyChangedException e) {
                listener.keyChanged(member); // they get the key once their new key is verified
            }
        }
    }

    /** A name key from the group's admin (the only one allowed to hand it out). */
    private void acceptGroupNameKey(String sender, byte[] distribution) throws IOException, ApiException {
        long groupId = GroupNames.groupOf(distribution);
        Packet.GroupInfo group = groups.get(groupId);
        if (group == null || !group.canManage(sender)) {
            refreshGroups();
            group = groups.get(groupId);
        }
        if (group == null || !group.canManage(sender)) {
            throw new SecurityException(sender + " sent a name key for a group they do not administer");
        }
        if (groupNames.accept(groupId, distribution)) {
            remember(group);
            listener.groupUpdated(groups.get(groupId));
        }
    }

    private static String legacyName(Packet.GroupInfo g) {
        return com.emirchat.chat.TerminalSanitizer.sanitize(g.name()).strip();
    }

    /** The name to show: decrypted, else the previous one we showed, else a placeholder. */
    private String displayName(Packet.GroupInfo g) {
        byte[] sealed = g.sealedName();
        if (sealed.length == 0) {
            String legacy = legacyName(g);
            return legacy.isEmpty() ? "New group" : legacy;
        }
        try {
            String name = groupNames.open(g.id(), sealed);
            if (name != null) return com.emirchat.chat.TerminalSanitizer.sanitize(name);
        } catch (IOException ignored) {
            // fall through
        }
        Packet.GroupInfo shown = groups.get(g.id());
        if (shown != null && !shown.name().equals(ENCRYPTED_GROUP_NAME) && GroupNames.versionOf(sealed) > 1) {
            return shown.name(); // a rename or new key is on its way; keep the last name we could read
        }
        return ENCRYPTED_GROUP_NAME;
    }

    public void addGroupMember(long groupId, String user) throws IOException, ApiException {
        connection.call(new Packet.AddGroupMember(groupId, user));
    }

    public void removeGroupMember(long groupId, String user) throws IOException, ApiException {
        connection.call(new Packet.RemoveGroupMember(groupId, user));
    }

    public void leaveGroup(long groupId) throws IOException, ApiException {
        connection.call(new Packet.LeaveGroup(groupId));
        groups.remove(groupId);
        groupKeys.forgetGroup(groupId);
        groupNames.forget(groupId);
    }

    /**
     * Sends a text to a group: first hands our sender key to members that lack it (over the
     * pairwise end-to-end channel), then sends one encrypted, signed body for the server to fan out.
     *
     * @return members that were skipped because their key changed and is not yet verified
     */
    public List<String> sendGroupText(long groupId, String text) throws IOException, ApiException {
        return sendGroupText(groupId, text, 0);
    }

    public List<String> sendGroupText(long groupId, String text, long expireSeconds) throws IOException, ApiException {
        try {
            return send(Chat.group(groupId), text, expireSeconds, null).skipped();
        } catch (KeyChangedException e) {
            throw new IllegalStateException(e); // groups skip members with changed keys instead
        }
    }

    private List<String> sendGroupContent(long groupId, Content content) throws IOException, ApiException {
        int contentType = content.type();
        byte[] payload = content.payload();
        // A member added a moment ago must get our sender key with this message, or they can never read it.
        catchUpWithPushes();
        Packet.GroupInfo group = groups.get(groupId);
        if (group == null) throw new ApiException(ErrorCode.GROUP_NOT_FOUND, "You are not in that group.");
        List<String> members = group.members().stream().map(Packet.Member::username).toList();
        List<String> skipped = new ArrayList<>();
        groupKeys.rotateIfAnyoneLeft(groupId, members);
        for (String member : groupKeys.needsKey(groupId, members, username)) {
            try {
                sendPairwise(member, CONTENT_SENDER_KEY, groupKeys.distribution(groupId));
                groupKeys.markDistributed(groupId, member);
            } catch (KeyChangedException e) {
                skipped.add(member);
                listener.keyChanged(member);
            }
        }
        if (multi && !ownDevices.isEmpty() && !groupKeys.needsKey(groupId, List.of(username), "").isEmpty()) {
            // our other computers read the group too: they need our key as well
            try {
                sendPairwise(username, CONTENT_SENDER_KEY, groupKeys.distribution(groupId));
                groupKeys.markDistributed(groupId, username);
            } catch (KeyChangedException e) {
                throw new IllegalStateException(e); // our own key never "changes" under us
            }
        }
        byte[] plaintext = new Wire.Writer().u8(contentType).bytes(payload).toByteArray();
        byte[] sealedBody = groupKeys.encrypt(groupId, plaintext);
        if (contentType == CONTENT_MESSAGE) lastSealed.set(sealedBody);
        connection.call(new Packet.SendGroupEnvelope(groupId, sealedBody));
        return skipped;
    }

    private void remember(Packet.GroupInfo g) {
        groups.put(g.id(), g.withName(displayName(g)));
        for (Packet.Member m : g.members()) memberKeys.put(m.username(), m.identityKey());
    }

    private static Packet.GroupList expectGroups(Packet p) throws ApiException {
        if (p instanceof Packet.GroupList l) return l;
        throw new ApiException(ErrorCode.PROTOCOL, "Unexpected response");
    }

    /** Membership changed: if anyone left, rotate our sender key so they cannot read what comes next. */
    private void groupChanged(Packet.GroupInfo now) {
        Packet.GroupInfo before = groups.get(now.id());
        remember(now);
        if (voice != null) voice.membersChanged(now.id(), now.members().stream().map(Packet.Member::username).toList());
        if (before != null) {
            try {
                boolean someoneLeft = false;
                for (Packet.Member m : before.members()) {
                    if (!now.hasMember(m.username())) {
                        someoneLeft = true;
                        groupKeys.forgetSender(now.id(), m.username());
                    }
                }
                if (someoneLeft) groupKeys.rotate(now.id());
                maintainGroupName(now, someoneLeft);
            } catch (IOException e) {
                listener.undecryptable("", "could not update group keys: " + e.getMessage());
            }
        } else {
            maintainGroupName(now, false);
        }
        listener.groupUpdated(groups.get(now.id()));
    }

    /** Accepts a friend's new key after out-of-band verification, and starts fresh sessions. */
    public void trustNewKey(String friend) throws IOException, ApiException {
        refreshFriends();
        byte[] key = friendKeys.get(friend);
        if (key == null) throw new ApiException(ErrorCode.NOT_FRIENDS, friend + " is not your friend.");
        contacts.replace(friend, Identity.fingerprint(key));
        messenger.resetSessions(friend);
        connection.fetchPending(); // messages held back while the key was unverified
    }

    /** The fingerprint pinned for a friend (what the user should compare), or null if none yet. */
    public String pinnedFingerprint(String friend) {
        return contacts.fingerprint(friend);
    }

    private com.emirchat.e2e.PrekeyBundle fetchBundle(String friend) throws IOException {
        try {
            return connection.fetchBundle(friend);
        } catch (ApiException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** The friend's key as reported by the server, checked against (or pinned into) local storage. */
    private byte[] pinnedKey(String friend) throws IOException, ApiException, KeyChangedException {
        byte[] key = knownKey(friend);
        if (key == null) {
            refreshFriends();
            refreshGroups();
            key = knownKey(friend);
        }
        if (key == null) throw new ApiException(ErrorCode.NOT_FRIENDS, friend + " is not your friend or in your groups.");
        try {
            contacts.check(friend, Identity.fingerprint(key));
        } catch (KeyChangedException e) {
            throw e;
        } catch (com.emirchat.trust.PeerRejectedException e) {
            throw new IllegalStateException(e); // contacts auto-accept first use
        }
        return key;
    }

    private byte[] knownKey(String user) {
        byte[] key = friendKeys.get(user);
        return key != null ? key : memberKeys.get(user);
    }

    private void onPush(Packet packet) {
        switch (packet) {
            case Packet.EnvelopeReceived r -> receive(r.envelope(), 0);
            case Packet.EnvelopeReceivedFrom r -> receive(r.envelope(), r.senderDevice());
            case Packet.LinkRequest l -> linking.offer(l);
            case Packet.LinkRevealed r -> linking.revealed(r);
            case Packet.DeviceRemoved d -> listener.deviceRemoved();
            case Packet.EnvelopeDelivered d -> listener.delivered(d.id());
            case Packet.FriendAdded a -> {
                friendKeys.put(a.friend().username(), a.friend().identityKey());
                friends.add(a.friend().username());
                online.put(a.friend().username(), a.friend().online());
                listener.push(packet);
            }
            case Packet.IdentityKeyChanged k -> {
                friendKeys.put(k.username(), k.identityKey());
                memberKeys.computeIfPresent(k.username(), (u, old) -> k.identityKey());
                listener.keyChanged(k.username());
            }
            case Packet.PresenceChanged p -> {
                online.put(p.username(), p.online());
                listener.push(packet);
            }
            case Packet.FriendRemoved f -> {
                friends.remove(f.username());
                listener.push(packet);
            }
            case Packet.GroupUpdated g -> groupChanged(g.group());
            case Packet.VoiceRoom v -> voice.roomChanged(v.groupId(), v.participants());
            case Packet.GroupRemoved g -> {
                groups.remove(g.groupId());
                if (voice != null && voice.state().groupId() == g.groupId()) voice.leave(); // out of the group: out of its room
                try {
                    groupKeys.forgetGroup(g.groupId());
                    groupNames.forget(g.groupId());
                } catch (IOException ignored) {
                    // keys of a group we are no longer in are useless anyway
                }
                listener.groupRemoved(g.groupId());
            }
            default -> listener.push(packet);
        }
    }

    private void receive(Packet.Envelope envelope, int senderDevice) {
        if (seen.put(envelope.id(), Boolean.TRUE) != null) {
            // Live push and queue fetch can overlap; and if our acknowledgement was lost (the app
            // closed right after a message), the server delivers it again after a restart. Its
            // message key is already used up, so acknowledge it again instead of reporting an error.
            if (handled.contains(envelope.id())) connection.ackAsync(List.of(envelope.id()));
            return;
        }
        boolean ack = true;
        try {
            if (envelope.sender().isEmpty()) {
                // sealed: who sent it is inside, signed; it must be the key we know for that name
                com.emirchat.e2e.SealedSender.Opened o = messenger.unseal(envelope.body());
                byte[] known = o.sender().equals(username) ? deviceKey : pinnedKey(o.sender());
                if (!java.util.Arrays.equals(known, o.identityKey())) throw new SecurityException("Sealed by another key than " + o.sender() + "'s");
                envelope = new Packet.Envelope(envelope.id(), o.sender(), 0, envelope.sentAt(), o.message());
                senderDevice = o.device();
            }
        } catch (KeyChangedException e) {
            seen.remove(envelope.id());
            return; // kept on the server until the new key is checked
        } catch (SecurityException | IOException | ApiException e) {
            listener.undecryptable("?", e.getMessage());
            markHandled(envelope.id());
            connection.ackAsync(List.of(envelope.id()));
            return;
        }
        opening = envelope.body();
        try {
            if (envelope.groupId() != 0) {
                receiveGroup(envelope, senderDevice);
                return;
            }
            boolean fromUs = envelope.sender().equals(username);
            if (fromUs && senderDevice == deviceId) throw new SecurityException("A message from this very computer");
            String from = address(envelope.sender(), senderDevice);
            byte[] key = fromUs ? deviceKey : pinnedKey(envelope.sender());
            byte[] plaintext = messenger.decrypt(from, key, envelope.body());
            Wire.Reader r = new Wire.Reader(plaintext);
            int contentType = r.u8();
            if (contentType == CONTENT_PADDED) {
                byte[] inner = r.bytes(MAX_CONTENT_BYTES + 16);
                r.bytes(MAX_PADDING);
                r.expectEnd();
                r = new Wire.Reader(inner);
                contentType = r.u8();
                if (contentType == CONTENT_PADDED) throw new ProtocolException("Padding inside padding");
            }
            byte[] payload = r.bytes(MAX_CONTENT_BYTES);
            r.expectEnd();
            switch (contentType) {
                case CONTENT_CHAT, CONTENT_FILE, CONTENT_EXPIRING, CONTENT_TIMER, CONTENT_MESSAGE, CONTENT_ACTION ->
                        deliver(envelope.sender(), 0, contentType, payload, envelope.id());
                case CONTENT_SENDER_KEY -> acceptSenderKey(envelope.sender(), from, payload);
                case CONTENT_SYNC -> {
                    if (!fromUs) throw new SecurityException("Only our own computers tell us what we sent");
                    Wire.Reader sync = new Wire.Reader(payload);
                    String peer = sync.string(Packet.MAX_USERNAME_BYTES);
                    int type = sync.u8();
                    byte[] inner = sync.bytes(MAX_CONTENT_BYTES);
                    sync.expectEnd();
                    if (!SYNCED.contains(type)) throw new ProtocolException("Not something to tell our other computers");
                    elsewhere.set(peer);
                    try {
                        deliver(username, 0, type, inner, envelope.id());
                    } finally {
                        elsewhere.remove();
                    }
                }
                case CONTENT_GROUP_NAME_KEY -> acceptGroupNameKey(envelope.sender(), payload);
                case CONTENT_CALL -> {
                    Wire.Reader call = new Wire.Reader(payload);
                    int kind = call.u8();
                    byte[] callId = call.exactBytes(com.emirchat.call.CallCrypto.CALL_ID_LENGTH);
                    byte[] callKey = call.bytes(64);
                    call.expectEnd();
                    // Members of a shared group can reach us over the pairwise channel (for group keys),
                    // but only friends may ring: the server relays call audio between friends only.
                    // Asked afresh for every call signal (there are a handful per call): the list here
                    // can be behind, for instance right after we removed someone ourselves.
                    refreshFriends();
                    if (fromUs) return; // calls between our own computers make no sense
                    if (!friends.contains(envelope.sender())) throw new SecurityException("Call from someone who is not a friend");
                    calls.signal(envelope.sender(), kind, callId, callKey, envelope.sentAt());
                }
                default -> throw new ProtocolException("Unknown content type " + contentType);
            }
        } catch (KeyChangedException e) {
            // Keep it queued on the server: once the user verifies the new key it can be read.
            ack = false;
            seen.remove(envelope.id());
            listener.keyChanged(envelope.sender());
        } catch (com.emirchat.e2e.GroupKeys.MissingKeyException e) {
            // A group message overtook its sender's key: keep it (unacknowledged) until the key arrives.
            ack = false;
            waitForSenderKey(envelope, senderDevice);
        } catch (SecurityException | IOException | ApiException e) {
            listener.undecryptable(envelope.sender(), e.getMessage());
        } finally {
            // Otherwise acknowledge either way: a message that cannot be decrypted now never will be.
            if (ack) {
                markHandled(envelope.id());
                connection.ackAsync(List.of(envelope.id()));
            }
        }
    }

    private void receiveGroup(Packet.Envelope envelope, int senderDevice) throws IOException, ApiException, KeyChangedException {
        Packet.GroupInfo group = groups.get(envelope.groupId());
        if (group == null) {
            refreshGroups();
            group = groups.get(envelope.groupId());
        }
        if (group == null || !group.hasMember(envelope.sender())) {
            throw new SecurityException(envelope.sender() + " is not a member of that group");
        }
        boolean fromUs = envelope.sender().equals(username);
        if (fromUs && senderDevice == deviceId) throw new SecurityException("A message from this very computer");
        byte[] plaintext = groupKeys.decrypt(envelope.groupId(), address(envelope.sender(), senderDevice), envelope.body());
        Wire.Reader r = new Wire.Reader(plaintext);
        int contentType = r.u8();
        byte[] payload = r.bytes(MAX_CONTENT_BYTES);
        r.expectEnd();
        switch (contentType) {
            case CONTENT_CHAT, CONTENT_FILE, CONTENT_EXPIRING, CONTENT_TIMER, CONTENT_MESSAGE, CONTENT_ACTION -> {
                if (fromUs) elsewhere.set(""); // we said this in the group from another computer
                try {
                    deliver(envelope.sender(), envelope.groupId(), contentType, payload, envelope.id());
                } finally {
                    elsewhere.remove();
                }
            }
            default -> throw new ProtocolException("Unknown group content");
        }
    }

    /** Hands a decrypted chat, file or timer message to the listener (groupId 0 for a DM). */
    private void deliver(String from, long groupId, int contentType, byte[] payload, long envelopeId) throws ProtocolException {
        if (contentType == CONTENT_ACTION) {
            Wire.Reader r = new Wire.Reader(payload);
            ActionKind kind = ActionKind.orNull(r.u8());
            if (kind == null) return; // an action from a newer version: nothing to show, nothing to report
            byte[] target = r.bytes(MESSAGE_ID_BYTES);
            String value = r.string(MAX_ACTION_VALUE_BYTES);
            r.expectEnd();
            boolean needsTarget = kind != ActionKind.TYPING && kind != ActionKind.TOPIC && kind != ActionKind.VOICE_KEY
                    && kind != ActionKind.CLOCK && kind != ActionKind.NUDGE && kind != ActionKind.ACCESS_KEY;
            if (kind == ActionKind.CLOCK && groupId != 0) throw new ProtocolException("Clocks are for direct messages");
            if (kind == ActionKind.NUDGE && groupId != 0) return; // nudges are for one person, never a whole group
            if (needsTarget != (target.length == MESSAGE_ID_BYTES) || (target.length != 0 && target.length != MESSAGE_ID_BYTES)) {
                throw new ProtocolException("Bad action target");
            }
            if (kind == ActionKind.ACCESS_KEY) {
                if (groupId != 0 || elsewhere.get() != null) return; // only a friend's own, in a direct message
                sealed.accept(from, value);
                return;
            }
            if (kind == ActionKind.VOICE_KEY) {
                if (groupId == 0) throw new ProtocolException("Voice keys belong to groups");
                voice.acceptKey(from, groupId, value); // a malformed key throws SecurityException: reported as undecryptable
                return;
            }
            Action action = new Action(from, groupId, kind, target, value);
            if (elsewhere.get() != null) listener.actionElsewhere(elsewhere.get().isEmpty() ? null : elsewhere.get(), action);
            else listener.action(action);
            return;
        }
        if (contentType == CONTENT_MESSAGE) {
            Wire.Reader r = new Wire.Reader(payload);
            if (r.u8() != 1) throw new ProtocolException("Unknown message format");
            byte[] id = r.exactBytes(MESSAGE_ID_BYTES);
            byte[] carried = opening;
            if (carried != null) rememberSealed(id, new Sealed(carried, groupId != 0));
            long expire = r.i64();
            byte[] replyTo = r.bytes(MESSAGE_ID_BYTES);
            int kind = r.u8();
            byte[] body = r.bytes(MAX_CONTENT_BYTES);
            r.expectEnd();
            if (expire < 0 || expire > MAX_TIMER_SECONDS) throw new ProtocolException("Invalid timer");
            if (replyTo.length != 0 && replyTo.length != MESSAGE_ID_BYTES) throw new ProtocolException("Bad reply id");
            switch (kind) {
                case KIND_TEXT -> emit(new Incoming(from, groupId, id, ChatMessage.decode(body), null, expire,
                        replyTo, envelopeId));
                case KIND_FILE -> emit(new Incoming(from, groupId, id, null, FileMessage.decode(body), expire,
                        replyTo, envelopeId));
                case KIND_POLL -> emit(new Incoming(from, groupId, id, null, null, expire, replyTo, envelopeId,
                        com.emirchat.chat.Poll.decode(body), false));
                case KIND_CAPSULE -> emit(new Incoming(from, groupId, id, null, null, expire, replyTo,
                        envelopeId, null, false, com.emirchat.chat.Capsule.decode(body)));
                case KIND_THREAD -> {
                    if (replyTo.length != MESSAGE_ID_BYTES) throw new ProtocolException("Thread reply without its thread");
                    emit(new Incoming(from, groupId, id, ChatMessage.decode(body), null, expire, replyTo,
                            envelopeId, null, true));
                }
                default -> listener.unsupported(from, groupId); // a newer kind of message: say so, not "undecryptable"
            }
            return;
        }
        long expire = 0;
        if (contentType == CONTENT_TIMER) {
            Wire.Reader r = new Wire.Reader(payload);
            long seconds = r.i64();
            r.expectEnd();
            if (seconds < 0 || seconds > MAX_TIMER_SECONDS) throw new ProtocolException("Invalid timer");
            listener.timerChanged(from, groupId, seconds);
            return;
        }
        if (contentType == CONTENT_EXPIRING) {
            Wire.Reader r = new Wire.Reader(payload);
            expire = r.i64();
            contentType = r.u8();
            payload = r.bytes(MAX_CONTENT_BYTES);
            r.expectEnd();
            if (expire <= 0 || expire > MAX_TIMER_SECONDS) throw new ProtocolException("Invalid timer");
        }
        // Older (3.1) format without a message id
        switch (contentType) {
            case CONTENT_CHAT -> emit(new Incoming(from, groupId, randomId(), ChatMessage.decode(payload), null,
                    expire, new byte[0], envelopeId));
            case CONTENT_FILE -> emit(new Incoming(from, groupId, randomId(), null, FileMessage.decode(payload),
                    expire, new byte[0], envelopeId));
            default -> throw new ProtocolException("Unknown content type " + contentType);
        }
    }

    /** A sender key arrived over the pairwise channel; only accepted from a current member of that group. */
    private void acceptSenderKey(String sender, String senderAddress, byte[] distribution) throws IOException, ApiException {
        long groupId;
        try {
            Wire.Reader r = new Wire.Reader(distribution);
            r.u8();
            groupId = r.i64();
        } catch (ProtocolException e) {
            throw new SecurityException("Malformed sender key");
        }
        Packet.GroupInfo group = groups.get(groupId);
        if (group == null || !group.hasMember(sender)) {
            refreshGroups();
            group = groups.get(groupId);
        }
        if (group == null || !group.hasMember(sender)) throw new SecurityException(sender + " sent a key for a group they are not in");
        groupKeys.acceptDistribution(groupId, senderAddress, distribution);
        retryWaiting(groupId, senderAddress);
    }

    /** Group messages waiting for their sender's key, by "groupId/sender". */
    private record Waiting(Packet.Envelope envelope, int senderDevice) {}

    private final Map<String, List<Waiting>> waitingForKey = new java.util.HashMap<>();
    private static final int MAX_WAITING = 500;

    private void waitForSenderKey(Packet.Envelope envelope, int senderDevice) {
        List<Waiting> list = waitingForKey.computeIfAbsent(envelope.groupId() + "/" + address(envelope.sender(), senderDevice),
                k -> new ArrayList<>());
        if (list.size() < MAX_WAITING) list.add(new Waiting(envelope, senderDevice)); // beyond that the server re-delivers them
    }

    /** The key arrived: decrypt what was waiting for it, in order (runs on the inbox thread). */
    private void retryWaiting(long groupId, String senderAddress) {
        List<Waiting> waiting = waitingForKey.remove(groupId + "/" + senderAddress);
        if (waiting == null) return;
        for (Waiting w : waiting) {
            seen.remove(w.envelope().id());
            receive(w.envelope(), w.senderDevice());
        }
    }

    /** A message for the app: ours from another computer, or someone else's. */
    private void emit(Incoming m) {
        String peer = elsewhere.get();
        if (peer == null) listener.incoming(m);
        else listener.sentElsewhere(peer.isEmpty() ? null : peer, m);
    }

    /** Set while delivering something we sent from another computer: the peer ("" in a group). */
    private final ThreadLocal<String> elsewhere = new ThreadLocal<>();

    /**
     * Logs out. Messages already being processed are finished first (stored, remembered as
     * handled and acknowledged), so closing right after a message arrives does not make the
     * server deliver it again.
     */
    private com.emirchat.call.Calls calls;
    private volatile java.util.function.Supplier<com.emirchat.call.AudioDevice> audioDevices =
            "none".equals(System.getProperty("securechat.audio"))
                    ? com.emirchat.call.SilentDevice::new : com.emirchat.call.SoundDevice::new;

    private com.emirchat.call.VoiceChannels voice;

    /** The voice rooms of our groups and channels. */
    public com.emirchat.call.VoiceChannels voice() {
        return voice;
    }

    /**
     * Asks the server who is in which voice room (after logging in or reconnecting).
     *
     * <p>Servers older than 3.4 do not know voice rooms and close the connection on the request.
     * If that happens, this server (by its key) is remembered for {@link #VOICE_RECHECK_MS} and
     * no voice packet is sent to it again until then: the app goes on without voice rooms instead
     * of reconnecting in a loop. After that time it asks once more, in case the server was updated.
     */
    public void refreshVoiceRooms() throws IOException, ApiException {
        if (!voiceSupported()) {
            voice.roomsLoaded(Map.of());
            return;
        }
        Packet answer;
        try {
            answer = connection.call(new Packet.ListVoiceRooms());
        } catch (IOException e) {
            markVoiceUnsupported();
            throw e;
        }
        Map<Long, List<String>> rooms = new java.util.HashMap<>();
        for (Packet.VoiceRoom r : ((Packet.VoiceRoomList) answer).rooms()) rooms.put(r.groupId(), r.participants());
        voice.roomsLoaded(rooms);
    }

    /** How long a server without voice rooms is left alone before asking again. */
    public static final long VOICE_RECHECK_MS = 6L * 3600 * 1000;

    /** False if this server turned out not to know voice rooms (see {@link #refreshVoiceRooms}). */
    public boolean voiceSupported() {
        try {
            Path file = dataDir.resolve("voice-unsupported");
            if (!Files.exists(file)) return true;
            String[] parts = Files.readString(file).strip().split(" ");
            return parts.length != 2 || !parts[0].equals(connection.serverFingerprint())
                    || System.currentTimeMillis() > Long.parseLong(parts[1]);
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    private void markVoiceUnsupported() {
        try {
            Files.writeString(dataDir.resolve("voice-unsupported"),
                    connection.serverFingerprint() + " " + (System.currentTimeMillis() + VOICE_RECHECK_MS));
        } catch (IOException ignored) {
            // then it asks again next time
        }
    }

    /** Voice and video calls with friends. */
    public com.emirchat.call.Calls calls() {
        return calls;
    }

    /**
     * The camera to send in a call; with {@code -Dsecurechat.video=pattern} a moving test picture
     * instead (tests and screenshots must not open the real camera).
     */
    public com.emirchat.video.VideoSource camera() {
        return "pattern".equals(System.getProperty("securechat.video"))
                ? new com.emirchat.video.PatternSource(480, 360, 15, false) : new com.emirchat.video.CameraSource();
    }

    /** The screen to share in a call (a test picture with {@code -Dsecurechat.video=pattern}). */
    public com.emirchat.video.VideoSource screen() {
        return "pattern".equals(System.getProperty("securechat.video"))
                ? new com.emirchat.video.PatternSource(1280, 800, 5, true) : new com.emirchat.video.ScreenSource();
    }

    /** Replaces the microphone and speaker (tests, or a device chosen in settings). */
    public void useAudioDevices(java.util.function.Supplier<com.emirchat.call.AudioDevice> devices) {
        audioDevices = devices;
    }

    /**
     * Changes the account's password on the server, then locks this computer's keys under the new
     * one too (a server before 3.6 cannot change passwords).
     */
    public void changePassword(String oldPassword, String newPassword) throws IOException, ApiException {
        if (!connection.hasAccountSecurity()) throw new ApiException(com.emirchat.api.ErrorCode.INVALID_REQUEST,
                "The server is too old to change passwords; ask whoever runs it to update it.");
        connection.changePassword(oldPassword, newPassword);
        com.emirchat.e2e.Vault.rewrap(dataDir, oldPassword, newPassword);
    }

    @Override
    public void close() {
        if (voice != null) voice.close();
        sealed.close();
        closing = true; // a deliberate logout is not reported as a lost connection
        if (calls != null) calls.close(); // hangs up a call going on
        inbox.shutdown();
        if (!Thread.currentThread().getName().equals("inbox")) {
            try {
                inbox.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        connection.close();
    }
}
