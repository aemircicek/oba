package com.emirchat.client;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
import com.emirchat.protocol.Wire;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sealed sender, the client's side (3.6): our access key (made once, kept in the vault, its hash
 * given to the server), friends' access keys (they give theirs end to end, as an action), and
 * sending sealed copies over a connection that never logs in (see the e2e {@code SealedSender}).
 */
final class SealedSending {
    private final ChatClient client;

    SealedSending(ChatClient client) {
        this.client = client;
    }

    /** Closes the anonymous connection. */
    void close() {
        ServerConnection a = anonymous;
        if (a != null) a.close();
    }


    static final String ACCESS_KEY_FILE = "access-key.bin";
    static final String ACCESS_KEYS_FILE = "friends-access-keys.bin";
    /** Our access key: client.friends who have it may send us sealed messages. */
    private byte[] accessKey;
    /** Friends' access keys, and to whom we gave ours. */
    private final Map<String, byte[]> friendAccessKeys = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> gaveAccessKey = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** X25519 identity keys of devices we may seal to, by "user#device". */
    final Map<String, byte[]> deviceDh = new java.util.concurrent.ConcurrentHashMap<>();
    private ServerConnection anonymous;
    private volatile boolean sealing = true;

    void sealing(boolean on) {
        sealing = on;
    }

    /** Sets up our access key (made once, kept in the vault) and tells the server its hash. */
    void setUp() throws IOException, ApiException {
        byte[] key = com.emirchat.e2e.Vault.read(client.dataDir.resolve(ACCESS_KEY_FILE));
        if (key == null || key.length != Packet.ACCESS_KEY_BYTES) {
            key = new byte[Packet.ACCESS_KEY_BYTES];
            new java.security.SecureRandom().nextBytes(key);
            com.emirchat.e2e.Vault.write(client.dataDir.resolve(ACCESS_KEY_FILE), key);
        }
        accessKey = key;
        try {
            client.connection.call(new Packet.SetAccessKey(java.security.MessageDigest.getInstance("SHA-256").digest(key)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] stored = com.emirchat.e2e.Vault.read(client.dataDir.resolve(ACCESS_KEYS_FILE));
        if (stored != null) {
            try {
                Wire.Reader r = new Wire.Reader(stored);
                int n = r.count(10_000);
                for (int i = 0; i < n; i++) friendAccessKeys.put(r.string(Packet.MAX_USERNAME_BYTES), r.exactBytes(Packet.ACCESS_KEY_BYTES));
                int m = r.count(10_000);
                for (int i = 0; i < m; i++) gaveAccessKey.add(r.string(Packet.MAX_USERNAME_BYTES));
                r.expectEnd();
            } catch (ProtocolException e) {
                // start again: keys are given again as messages are sent
            }
        }
    }

    private synchronized void saveAccessKeys() {
        try {
            Wire.Writer w = new Wire.Writer().u16(friendAccessKeys.size());
            friendAccessKeys.forEach((u, k) -> w.string(u).bytes(k));
            w.u16(gaveAccessKey.size());
            for (String u : gaveAccessKey) w.string(u);
            com.emirchat.e2e.Vault.write(client.dataDir.resolve(ACCESS_KEYS_FILE), w.toByteArray());
        } catch (IOException ignored) {
            // given again next time
        }
    }

    void accept(String friend, String value) {
        try {
            byte[] key = java.util.Base64.getDecoder().decode(value);
            if (key.length != Packet.ACCESS_KEY_BYTES) return;
            friendAccessKeys.put(friend, key);
            saveAccessKeys();
        } catch (IllegalArgumentException ignored) {
            // a damaged key is simply not used
        }
    }

    /** Gives a friend our access key once (end-to-end encrypted), so they can seal what they send us. */
    void giveKey(String friend) {
        if (accessKey == null || gaveAccessKey.contains(friend) || !client.friends.contains(friend)) return;
        gaveAccessKey.add(friend);
        try {
            client.sendAction(ChatClient.Chat.dm(friend), ChatClient.ActionKind.ACCESS_KEY, null, java.util.Base64.getEncoder().encodeToString(accessKey));
            saveAccessKeys();
        } catch (Exception e) {
            gaveAccessKey.remove(friend);
        }
    }

    /**
     * Sends the copies sealed, over a client.connection that never logged in, when the friend gave us
     * their access key and every one of their devices can open sealed messages.
     *
     * @return the server's answer, or null to send them the usual way
     */
    Packet.EnvelopeAccepted send(String user, java.util.Set<Integer> devices, List<Packet.DeviceEnvelope> copies)
            throws ApiException {
        byte[] key = friendAccessKeys.get(user);
        if (!sealing || key == null || devices.isEmpty()) return null;
        List<Packet.DeviceEnvelope> sealed = new ArrayList<>();
        try {
            for (Packet.DeviceEnvelope c : copies) {
                String addr = ChatClient.address(user, c.deviceId());
                byte[] dh = deviceDh.get(addr);
                if (dh == null || !Boolean.TRUE.equals(client.deviceMulti.get(addr))) return null;
                sealed.add(new Packet.DeviceEnvelope(c.deviceId(),
                        com.emirchat.e2e.SealedSender.seal(client.account, client.username, client.deviceId, dh, c.body())));
            }
            ServerConnection a = anonymous;
            if (a == null || a.isClosed()) anonymous = a = client.connection.anonymous();
            return (Packet.EnvelopeAccepted) a.call(new Packet.SendSealed(user, key, sealed));
        } catch (ApiException e) {
            if (e.code() == ErrorCode.STALE_DEVICES) throw e;
            if (e.code() == ErrorCode.NOT_FRIENDS) friendAccessKeys.remove(user); // their key changed: use theirs once it comes
            return null;
        } catch (IOException | com.emirchat.trust.PeerRejectedException | RuntimeException e) {
            return null;
        }
    }
}
