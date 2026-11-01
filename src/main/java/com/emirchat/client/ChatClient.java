package com.emirchat.client;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
import com.emirchat.chat.ChatMessage;
import com.emirchat.crypto.Identity;
import com.emirchat.e2e.Messenger;
import com.emirchat.protocol.Wire;
import com.emirchat.trust.KeyChangedException;
import com.emirchat.trust.KnownPeers;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    }

    private final ServerConnection connection;
    private final Messenger messenger;
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

    private ChatClient(ServerConnection connection, Messenger messenger, KnownPeers contacts, String username) {
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
        ChatClient client = new ChatClient(connection, messenger, contacts, loggedIn.username());
        client.listener = listener;
        connection.onPush(p -> client.inbox.execute(() -> client.onPush(p)));
        connection.onClosed(() -> {
            if (!client.closing) client.inbox.execute(() -> client.listener.disconnected());
        });
        client.publishKeys();
        client.refreshFriends();
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
        for (Packet.Friend f : list.friends()) friendKeys.put(f.username(), f.identityKey());
        return list;
    }

    /** Encrypts and sends a text message to a friend. */
    public Packet.EnvelopeAccepted sendText(String friend, String text) throws IOException, ApiException, KeyChangedException {
        byte[] key = pinnedKey(friend);
        byte[] plaintext = new Wire.Writer().u8(CONTENT_CHAT).bytes(ChatMessage.text(text).encode()).toByteArray();
        byte[] body = messenger.encrypt(friend, key, () -> fetchBundle(friend), plaintext);
        return connection.sendEnvelope(friend, body);
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
        byte[] key = friendKeys.get(friend);
        if (key == null) {
            refreshFriends();
            key = friendKeys.get(friend);
        }
        if (key == null) throw new ApiException(ErrorCode.NOT_FRIENDS, friend + " is not your friend.");
        try {
            contacts.check(friend, Identity.fingerprint(key));
        } catch (KeyChangedException e) {
            throw e;
        } catch (com.emirchat.trust.PeerRejectedException e) {
            throw new IllegalStateException(e); // contacts auto-accept first use
        }
        return key;
    }

    private void onPush(Packet packet) {
        switch (packet) {
            case Packet.EnvelopeReceived r -> receive(r.envelope());
            case Packet.EnvelopeDelivered d -> listener.delivered(d.id());
            case Packet.FriendAdded a -> {
                friendKeys.put(a.friend().username(), a.friend().identityKey());
                listener.push(packet);
            }
            case Packet.IdentityKeyChanged k -> {
                friendKeys.put(k.username(), k.identityKey());
                listener.keyChanged(k.username());
            }
            default -> listener.push(packet);
        }
    }

    private void receive(Packet.Envelope envelope) {
        if (seen.put(envelope.id(), Boolean.TRUE) != null) return; // live push and queue fetch can overlap
        boolean ack = true;
        try {
            byte[] key = pinnedKey(envelope.sender());
            byte[] plaintext = messenger.decrypt(envelope.sender(), key, envelope.body());
            Wire.Reader r = new Wire.Reader(plaintext);
            int contentType = r.u8();
            if (contentType != CONTENT_CHAT) throw new ProtocolException("Unknown content type " + contentType);
            ChatMessage message = ChatMessage.decode(r.bytes(ChatMessage.MAX_TEXT_BYTES + 64));
            r.expectEnd();
            listener.message(envelope.sender(), message, envelope.id());
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

    @Override
    public void close() {
        closing = true; // a deliberate logout is not reported as a lost connection
        connection.close();
        inbox.shutdown();
    }
}
