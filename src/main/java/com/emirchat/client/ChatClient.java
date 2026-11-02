package com.emirchat.client;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
import com.emirchat.chat.ChatMessage;
import com.emirchat.chat.FileMessage;
import com.emirchat.crypto.Identity;
import com.emirchat.e2e.Attachments;
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
    private static final int MAX_CONTENT_BYTES = ChatMessage.MAX_TEXT_BYTES + 256;
    /** Upload/download requests kept in flight at once (the server answers them in order). */
    private static final int TRANSFER_WINDOW = 8;

    /** Everything the UI needs to hear about. All callbacks run on the client's inbox thread. */
    public interface Listener {
        default void message(String from, ChatMessage message, long envelopeId) {}

        /** A message arrived but could not be decrypted (e.g. forged, or sent to an old device). */
        default void undecryptable(String from, String reason) {}

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
    }

    private final ServerConnection connection;
    private final Messenger messenger;
    private final com.emirchat.e2e.GroupKeys groupKeys;
    private final Map<Long, Packet.GroupInfo> groups = new ConcurrentHashMap<>();
    private final Map<String, byte[]> memberKeys = new ConcurrentHashMap<>();
    private final Map<String, Boolean> online = new ConcurrentHashMap<>();
    private volatile Packet.FriendList lastFriends = new Packet.FriendList(List.of(), List.of(), List.of());
    private Path dataDir;
    private final KnownPeers contacts;
    private final String username;
    private final Map<String, byte[]> friendKeys = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> seen = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Boolean> e) { return size() > 10_000; }
    });
    private final ExecutorService inbox = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "inbox");
        t.setDaemon(true);
        return t;
    });
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
        Packet.LoggedIn loggedIn = register ? connection.register(user, password) : connection.login(user, password);
        Path dir = accountDir.resolve(loggedIn.username());
        Files.createDirectories(dir);
        Messenger messenger = Messenger.open(dir.resolve("e2e"), device);
        KnownPeers contacts = new KnownPeers(dir.resolve("contacts"), (name, fp) -> true); // TOFU, verify later
        ChatClient client = new ChatClient(connection, messenger, new com.emirchat.e2e.GroupKeys(dir.resolve("e2e")),
                contacts, loggedIn.username());
        client.listener = listener;
        client.dataDir = dir;
        client.deviceKey = device.publicKeyBytes();
        connection.onPush(p -> client.inbox.execute(() -> client.onPush(p)));
        connection.onClosed(() -> {
            if (!client.closing) client.inbox.execute(() -> client.listener.disconnected());
        });
        client.publishKeys();
        client.refreshFriends();
        client.refreshGroups();
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
        for (Packet.Friend f : list.friends()) {
            friendKeys.put(f.username(), f.identityKey());
            online.put(f.username(), f.online());
        }
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

    private byte[] deviceKey;

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
        return sendPairwise(friend, CONTENT_CHAT, ChatMessage.text(text).encode());
    }

    private Packet.EnvelopeAccepted sendPairwise(String user, int contentType, byte[] payload)
            throws IOException, ApiException, KeyChangedException {
        byte[] key = pinnedKey(user);
        byte[] plaintext = new Wire.Writer().u8(contentType).bytes(payload).toByteArray();
        byte[] body = messenger.encrypt(user, key, () -> fetchBundle(user), plaintext);
        return connection.sendEnvelope(user, body);
    }

    // ------------------------------------------------------------------ files

    /** The description of a file just sent, and the envelope that carried it. */
    public record SentFile(FileMessage file, long envelopeId) {}

    /** Encrypts a file, uploads the ciphertext, and sends its key to a friend end-to-end. */
    public SentFile sendFile(String friend, String name, String mimeType, byte[] data)
            throws IOException, ApiException, KeyChangedException {
        pinnedKey(friend); // refuse before uploading if their key changed
        FileMessage file = upload(data, name, mimeType, friend, 0);
        return new SentFile(file, sendPairwise(friend, CONTENT_FILE, file.encode()).id());
    }

    /**
     * Encrypts a file, uploads it for the group, and sends its key to the members.
     *
     * @return the file sent, and members skipped because their key changed and is not yet verified
     */
    public Map.Entry<FileMessage, List<String>> sendGroupFile(long groupId, String name, String mimeType, byte[] data)
            throws IOException, ApiException {
        if (!groups.containsKey(groupId)) throw new ApiException(ErrorCode.GROUP_NOT_FOUND, "You are not in that group.");
        FileMessage file = upload(data, name, mimeType, "", groupId);
        return Map.entry(file, sendGroupContent(groupId, CONTENT_FILE, file.encode()));
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

    public List<Packet.GroupInfo> refreshGroups() throws IOException, ApiException {
        Packet.GroupList list = expectGroups(connection.call(new Packet.ListGroups()));
        groups.clear();
        for (Packet.GroupInfo g : list.groups()) remember(g);
        return list.groups();
    }

    public List<Packet.GroupInfo> groups() {
        return List.copyOf(groups.values());
    }

    public Packet.GroupInfo createGroup(String name, List<String> members) throws IOException, ApiException {
        Packet response = connection.call(new Packet.CreateGroup(name, members));
        if (!(response instanceof Packet.GroupInfo g)) throw new ApiException(ErrorCode.PROTOCOL, "Unexpected response");
        remember(g);
        return g;
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
    }

    /**
     * Sends a text to a group: first hands our sender key to members that lack it (over the
     * pairwise end-to-end channel), then sends one encrypted, signed body for the server to fan out.
     *
     * @return members that were skipped because their key changed and is not yet verified
     */
    public List<String> sendGroupText(long groupId, String text) throws IOException, ApiException {
        return sendGroupContent(groupId, CONTENT_CHAT, ChatMessage.text(text).encode());
    }

    private List<String> sendGroupContent(long groupId, int contentType, byte[] payload) throws IOException, ApiException {
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
        byte[] plaintext = new Wire.Writer().u8(contentType).bytes(payload).toByteArray();
        connection.call(new Packet.SendGroupEnvelope(groupId, groupKeys.encrypt(groupId, plaintext)));
        return skipped;
    }

    private void remember(Packet.GroupInfo g) {
        groups.put(g.id(), g);
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
            } catch (IOException e) {
                listener.undecryptable("", "could not update group keys: " + e.getMessage());
            }
        }
        listener.groupUpdated(now);
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
            case Packet.EnvelopeReceived r -> receive(r.envelope());
            case Packet.EnvelopeDelivered d -> listener.delivered(d.id());
            case Packet.FriendAdded a -> {
                friendKeys.put(a.friend().username(), a.friend().identityKey());
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
            case Packet.GroupUpdated g -> groupChanged(g.group());
            case Packet.GroupRemoved g -> {
                groups.remove(g.groupId());
                try {
                    groupKeys.forgetGroup(g.groupId());
                } catch (IOException ignored) {
                    // keys of a group we are no longer in are useless anyway
                }
                listener.groupRemoved(g.groupId());
            }
            default -> listener.push(packet);
        }
    }

    private void receive(Packet.Envelope envelope) {
        if (seen.put(envelope.id(), Boolean.TRUE) != null) return; // live push and queue fetch can overlap
        boolean ack = true;
        try {
            if (envelope.groupId() != 0) {
                receiveGroup(envelope);
                return;
            }
            byte[] key = pinnedKey(envelope.sender());
            byte[] plaintext = messenger.decrypt(envelope.sender(), key, envelope.body());
            Wire.Reader r = new Wire.Reader(plaintext);
            int contentType = r.u8();
            byte[] payload = r.bytes(MAX_CONTENT_BYTES);
            r.expectEnd();
            switch (contentType) {
                case CONTENT_CHAT -> listener.message(envelope.sender(), ChatMessage.decode(payload), envelope.id());
                case CONTENT_SENDER_KEY -> acceptSenderKey(envelope.sender(), payload);
                case CONTENT_FILE -> listener.file(envelope.sender(), FileMessage.decode(payload), envelope.id());
                default -> throw new ProtocolException("Unknown content type " + contentType);
            }
        } catch (KeyChangedException e) {
            // Keep it queued on the server: once the user verifies the new key it can be read.
            ack = false;
            seen.remove(envelope.id());
            listener.keyChanged(envelope.sender());
        } catch (SecurityException | IOException | ApiException e) {
            listener.undecryptable(envelope.sender(), e.getMessage());
        } finally {
            // Otherwise acknowledge either way: a message that cannot be decrypted now never will be.
            if (ack) connection.ackAsync(List.of(envelope.id()));
        }
    }

    private void receiveGroup(Packet.Envelope envelope) throws IOException, ApiException, KeyChangedException {
        Packet.GroupInfo group = groups.get(envelope.groupId());
        if (group == null) {
            refreshGroups();
            group = groups.get(envelope.groupId());
        }
        if (group == null || !group.hasMember(envelope.sender())) {
            throw new SecurityException(envelope.sender() + " is not a member of that group");
        }
        byte[] plaintext = groupKeys.decrypt(envelope.groupId(), envelope.sender(), envelope.body());
        Wire.Reader r = new Wire.Reader(plaintext);
        int contentType = r.u8();
        byte[] payload = r.bytes(MAX_CONTENT_BYTES);
        r.expectEnd();
        switch (contentType) {
            case CONTENT_CHAT -> listener.groupMessage(envelope.groupId(), envelope.sender(), ChatMessage.decode(payload),
                    envelope.id());
            case CONTENT_FILE -> listener.groupFile(envelope.groupId(), envelope.sender(), FileMessage.decode(payload),
                    envelope.id());
            default -> throw new ProtocolException("Unknown group content");
        }
    }

    /** A sender key arrived over the pairwise channel; only accepted from a current member of that group. */
    private void acceptSenderKey(String sender, byte[] distribution) throws IOException, ApiException {
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
        groupKeys.acceptDistribution(groupId, sender, distribution);
    }

    @Override
    public void close() {
        closing = true; // a deliberate logout is not reported as a lost connection
        connection.close();
        inbox.shutdown();
    }
}
