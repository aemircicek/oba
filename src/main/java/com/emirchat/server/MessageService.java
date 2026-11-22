package com.emirchat.server;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
import com.emirchat.crypto.Identity;
import com.emirchat.e2e.DeviceKeys;
import com.emirchat.e2e.PrekeyBundle;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Prekey distribution and the store-and-forward queue for end-to-end encrypted envelopes. The
 * server never sees plaintext: it checks who may talk to whom, signatures on published keys, and
 * sizes, then relays opaque bytes.
 */
final class MessageService {
    static final int MAX_STORED_PREKEYS = 200;
    static final int MAX_QUEUED_PER_RECIPIENT = 5000;
    static final int BUNDLES_PER_HOUR_PER_PAIR = 10;
    private static final int PUSH_BATCH = 500;

    private final Database db;
    private final Presence presence;
    private final Clock clock;
    private final RateLimiter bundleLimiter;

    MessageService(Database db, Presence presence, Clock clock) {
        this.db = db;
        this.presence = presence;
        this.clock = clock;
        this.bundleLimiter = new RateLimiter(BUNDLES_PER_HOUR_PER_PAIR, Duration.ofHours(1), clock);
    }

    void uploadKeys(Database.User me, byte[] identityKey, Packet.UploadKeys k) throws ApiException {
        uploadKeys(me, 0, false, identityKey, k);
    }

    /**
     * Publishes a device's keys. They must be signed by the account's identity key, which only the
     * account's own devices hold: a server cannot make up a device that others would encrypt to.
     */
    void uploadKeys(Database.User me, int deviceId, boolean multi, byte[] identityKey, Packet.UploadKeys k)
            throws ApiException {
        if (!Identity.verify(identityKey, DeviceKeys.dhIdentitySignedData(k.dhIdentity()), k.dhIdentitySignature())
                || !Identity.verify(identityKey, DeviceKeys.signedPrekeySignedData(k.signedPrekeyId(), k.signedPrekey()),
                        k.signedPrekeySignature())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Key signatures do not match your device key.");
        }
        try {
            db.putDeviceKeys(me.id(), deviceId, multi, new Database.DeviceKeysRow(k.dhIdentity(), k.dhIdentitySignature(),
                    k.signedPrekeyId(), k.signedPrekey(), k.signedPrekeySignature()));
        } catch (SQLException e) {
            throw storage();
        }
    }

    void uploadPrekeys(Database.User me, List<Packet.Prekey> prekeys) throws ApiException {
        uploadPrekeys(me, 0, prekeys);
    }

    void uploadPrekeys(Database.User me, int deviceId, List<Packet.Prekey> prekeys) throws ApiException {
        try {
            if (db.prekeyCount(me.id(), deviceId) + prekeys.size() > MAX_STORED_PREKEYS) {
                throw new ApiException(ErrorCode.LIMIT_REACHED, "Too many stored prekeys.");
            }
            for (Packet.Prekey p : prekeys) db.addPrekey(me.id(), deviceId, p.id(), p.key());
        } catch (SQLException e) {
            throw storage();
        }
    }

    Packet.KeyStatusResult status(Database.User me) throws ApiException {
        return status(me, 0);
    }

    Packet.KeyStatusResult status(Database.User me, int deviceId) throws ApiException {
        try {
            Optional<Database.DeviceKeysRow> keys = db.deviceKeys(me.id(), deviceId);
            return new Packet.KeyStatusResult(keys.isPresent(), keys.map(Database.DeviceKeysRow::spkId).orElse(-1),
                    db.prekeyCount(me.id(), deviceId));
        } catch (SQLException e) {
            throw storage();
        }
    }

    /** Hands out a friend's bundle, consuming one of their one-time prekeys. */
    PrekeyBundle bundle(Database.User me, String friendName) throws ApiException {
        Database.User friend = friend(me, friendName);
        String pair = me.id() + ">" + friend.id();
        if (bundleLimiter.isBlocked(pair)) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Too many key requests for " + friend.username() + ".");
        }
        bundleLimiter.recordFailure(pair); // counts requests
        try {
            Database.DeviceKeysRow k = db.deviceKeys(friend.id()).orElseThrow(() -> new ApiException(ErrorCode.NO_KEYS,
                    friend.username() + " has not set up encryption yet (they need to log in once)."));
            Optional<Map.Entry<Integer, byte[]>> opk = db.takePrekey(friend.id());
            return new PrekeyBundle(friend.identityKey(), k.dhIdentity(), k.dhSig(), k.spkId(), k.spk(), k.spkSig(),
                    opk.map(Map.Entry::getKey).orElse(-1), opk.map(Map.Entry::getValue).orElse(new byte[0]));
        } catch (SQLException e) {
            throw storage();
        }
    }

    /** The bundles of every receiving device of a user (or of our own other devices, for {@code me}). */
    Packet.DeviceBundles deviceBundles(Database.User me, int myDevice, String name) throws ApiException {
        Database.User target = AccountService.normalize(name).equals(me.username()) ? me : friend(me, name);
        String pair = me.id() + ">" + target.id();
        if (bundleLimiter.isBlocked(pair)) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Too many key requests for " + target.username() + ".");
        }
        bundleLimiter.recordFailure(pair);
        try {
            List<Packet.DeviceBundle> out = new java.util.ArrayList<>();
            for (Map.Entry<Integer, Boolean> d : db.receivingDevices(target.id()).entrySet()) {
                if (target.id() == me.id() && d.getKey() == myDevice) continue;
                Optional<Database.DeviceKeysRow> k = db.deviceKeys(target.id(), d.getKey());
                if (k.isEmpty()) continue;
                Optional<Map.Entry<Integer, byte[]>> opk = db.takePrekey(target.id(), d.getKey());
                out.add(new Packet.DeviceBundle(d.getKey(), d.getValue(), new PrekeyBundle(target.identityKey(),
                        k.get().dhIdentity(), k.get().dhSig(), k.get().spkId(), k.get().spk(), k.get().spkSig(),
                        opk.map(Map.Entry::getKey).orElse(-1), opk.map(Map.Entry::getValue).orElse(new byte[0]))));
            }
            if (out.isEmpty() && target.id() != me.id()) {
                throw new ApiException(ErrorCode.NO_KEYS, target.username() + " has not set up encryption yet (they need to log in once).");
            }
            return new Packet.DeviceBundles(out);
        } catch (SQLException e) {
            throw storage();
        }
    }

    /**
     * One message, encrypted separately for each device of the recipient (to ourselves: for our
     * other devices). It must cover exactly the devices that can receive, so nobody's computer is
     * left out without the sender knowing.
     */
    Packet.EnvelopeAccepted sendToDevices(Database.User me, int myDevice, String recipientName,
                                          List<Packet.DeviceEnvelope> copies) throws ApiException {
        boolean self = AccountService.normalize(recipientName).equals(me.username());
        Database.User recipient = self ? me : friend(me, recipientName);
        long now = clock.millis();
        try {
            java.util.Set<Integer> wanted = new java.util.TreeSet<>(db.receivingDevices(recipient.id()).keySet());
            if (self) wanted.remove(myDevice);
            java.util.Set<Integer> given = new java.util.TreeSet<>();
            for (Packet.DeviceEnvelope c : copies) given.add(c.deviceId());
            if (!wanted.equals(given) || given.size() != copies.size()) {
                throw new ApiException(ErrorCode.STALE_DEVICES, "The devices of " + recipient.username() + " changed.");
            }
            if (db.queuedCount(recipient.id()) + copies.size() > MAX_QUEUED_PER_RECIPIENT) {
                throw new ApiException(ErrorCode.LIMIT_REACHED, recipient.username() + "'s inbox is full.");
            }
            long batch = 0;
            for (Packet.DeviceEnvelope c : copies) {
                long id = db.queueEnvelope(recipient.id(), c.deviceId(), me.id(), myDevice, 0, now, c.body(), batch);
                if (batch == 0) batch = id;
                pushToDevice(recipient.id(), c.deviceId(), new Packet.Envelope(id, me.username(), 0, now, c.body()), myDevice);
            }
            return new Packet.EnvelopeAccepted(batch, now);
        } catch (SQLException e) {
            throw storage();
        }
    }

    /** Wrong access keys from one address before it is refused for a while (guessing 128 bits is hopeless anyway). */
    private final RateLimiter sealedFailures = new RateLimiter(20, Duration.ofMinutes(15), Clock.systemUTC());

    /**
     * A sealed message (3.6): accepted from a connection that need not be logged in, if it shows the
     * recipient's access key, which only their friends got from them, end-to-end encrypted. Every
     * receiving device must be a 3.6 app (older ones could not open it) and get a copy.
     */
    Packet.EnvelopeAccepted sendSealed(String recipientName, byte[] accessKey, List<Packet.DeviceEnvelope> copies,
                                       String address) throws ApiException {
        if (address != null && sealedFailures.isBlocked(address)) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Too many refused messages. Try again later.");
        }
        try {
            String name = AccountService.normalize(recipientName);
            Optional<Database.User> found = AccountService.USERNAME.matcher(name).matches() ? db.findUser(name) : Optional.empty();
            byte[] stored = found.isPresent() ? db.accessKey(found.get().id()) : null;
            byte[] given = java.security.MessageDigest.getInstance("SHA-256").digest(accessKey);
            if (stored == null || !java.security.MessageDigest.isEqual(stored, given)) {
                if (address != null) sealedFailures.recordFailure(address);
                throw new ApiException(ErrorCode.NOT_FRIENDS, "Not accepted.");
            }
            Database.User recipient = found.get();
            Map<Integer, Boolean> devices = db.receivingDevices(recipient.id());
            java.util.Set<Integer> covered = new java.util.TreeSet<>();
            for (Packet.DeviceEnvelope c : copies) covered.add(c.deviceId());
            if (!devices.keySet().equals(covered) || covered.size() != copies.size() || devices.containsValue(false)) {
                throw new ApiException(ErrorCode.STALE_DEVICES, "The devices changed.");
            }
            if (db.queuedCount(recipient.id()) + copies.size() > MAX_QUEUED_PER_RECIPIENT) {
                throw new ApiException(ErrorCode.LIMIT_REACHED, "The inbox is full.");
            }
            long now = clock.millis();
            long batch = 0;
            for (Packet.DeviceEnvelope c : copies) {
                long id = db.queueSealed(recipient.id(), c.deviceId(), now, c.body(), batch);
                if (batch == 0) batch = id;
                pushToDevice(recipient.id(), c.deviceId(), new Packet.Envelope(id, "", 0, now, c.body()), 0);
            }
            return new Packet.EnvelopeAccepted(batch, now);
        } catch (SQLException e) {
            throw storage();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    void setAccessKey(Database.User me, byte[] hash) throws ApiException {
        try {
            db.setAccessKey(me.id(), hash);
        } catch (SQLException e) {
            throw storage();
        }
    }

    private void pushToDevice(long userId, int deviceId, Packet.Envelope e, int senderDevice) {
        for (ClientConnection c : presence.connections(userId)) {
            if (c.deviceId() == deviceId) c.pushEnvelope(e, senderDevice);
        }
    }

    Packet.EnvelopeAccepted send(Database.User me, String recipientName, byte[] body) throws ApiException {
        Database.User recipient = friend(me, recipientName);
        long now = clock.millis();
        long id;
        try {
            id = queue(recipient, me, 0, now, body);
        } catch (SQLException e) {
            throw storage();
        }
        return new Packet.EnvelopeAccepted(id, now);
    }

    /** Fans one group envelope out to every other member; the body is the same opaque ciphertext for all. */
    Packet.EnvelopeAccepted sendToGroup(Database.User me, long groupId, byte[] body) throws ApiException {
        return sendToGroup(me, 0, groupId, body);
    }

    /**
     * Fans one group envelope out to every device of every other member, and to our own other
     * devices; the body is the same opaque ciphertext for all (sender keys).
     */
    Packet.EnvelopeAccepted sendToGroup(Database.User me, int myDevice, long groupId, byte[] body) throws ApiException {
        long now = clock.millis();
        long last = 0;
        try {
            if (!db.isMember(groupId, me.id())) {
                throw new ApiException(ErrorCode.GROUP_NOT_FOUND, "You are not in that group.");
            }
            for (Database.User member : db.members(groupId)) {
                java.util.Set<Integer> devices = new java.util.TreeSet<>(db.receivingDevices(member.id()).keySet());
                if (devices.isEmpty()) devices.add(0); // queued for when the first device sets up its keys
                if (member.id() == me.id()) devices.remove(myDevice);
                for (int device : devices) {
                    if (db.queuedCount(member.id()) >= MAX_QUEUED_PER_RECIPIENT) break; // one full inbox must not block the group
                    long id = db.queueEnvelope(member.id(), device, me.id(), myDevice, groupId, now, body, 0);
                    pushToDevice(member.id(), device, new Packet.Envelope(id, me.username(), groupId, now, body), myDevice);
                    if (member.id() != me.id()) last = id;
                }
            }
        } catch (SQLException e) {
            throw storage();
        }
        return new Packet.EnvelopeAccepted(last, now);
    }

    private long queue(Database.User recipient, Database.User sender, long groupId, long now, byte[] body)
            throws SQLException, ApiException {
        if (db.queuedCount(recipient.id()) >= MAX_QUEUED_PER_RECIPIENT) {
            throw new ApiException(ErrorCode.LIMIT_REACHED, recipient.username() + "'s inbox is full.");
        }
        // an app before 3.6 sent this, encrypted for the session it has: the recipient's first device
        long id = db.queueEnvelope(recipient.id(), sender.id(), groupId, now, body);
        pushToDevice(recipient.id(), 0, new Packet.Envelope(id, sender.username(), groupId, now, body), 0);
        return id;
    }

    /** Queues pushes for everything waiting for this user; returns how many. */
    int pushPending(Database.User me, ClientConnection to) throws ApiException {
        try {
            List<Database.QueuedEnvelope> queued = db.queuedFor(me.id(), to.deviceId(), PUSH_BATCH);
            for (Database.QueuedEnvelope e : queued) {
                to.pushEnvelope(new Packet.Envelope(e.id(), e.sealed() ? "" : e.sender(), e.groupId(), e.sentAt(), e.body()),
                        e.senderDevice());
            }
            return queued.size();
        } catch (SQLException e) {
            throw storage();
        }
    }

    /** Deletes acknowledged envelopes and tells their senders they were delivered. */
    void ack(Database.User me, List<Long> ids) throws ApiException {
        ack(me, 0, ids);
    }

    /** Deletes acknowledged envelopes of this device and tells their senders (by the id they know) they arrived. */
    void ack(Database.User me, int deviceId, List<Long> ids) throws ApiException {
        try {
            for (long id : ids) {
                long[] gone = db.deleteEnvelope(me.id(), deviceId, id);
                if (gone != null && gone[0] != me.id()) presence.push(gone[0], new Packet.EnvelopeDelivered(gone[1]));
            }
        } catch (SQLException e) {
            throw storage();
        }
    }

    /** A new device cannot read what was encrypted for the old one. */
    void deviceChanged(Database.User user) {
        try {
            db.resetDevice(user.id());
        } catch (SQLException e) {
            // the next upload overwrites the keys anyway
        }
    }

    /** The user named, if {@code me} may open a pairwise channel to them (friends, or a shared group). */
    Database.User friend(Database.User me, String rawName) throws ApiException {
        String name = AccountService.normalize(rawName);
        try {
            if (AccountService.USERNAME.matcher(name).matches()) {
                Optional<Database.User> u = db.findUser(name);
                // Pairwise channels are allowed between friends and between members of a common group
                // (group sender keys travel over them).
                if (u.isPresent() && (db.areFriends(me.id(), u.get().id()) || db.shareAGroup(me.id(), u.get().id()))) {
                    return u.get();
                }
            }
        } catch (SQLException e) {
            throw storage();
        }
        throw new ApiException(ErrorCode.NOT_FRIENDS, "You can only message friends and members of your groups.");
    }

    private static ApiException storage() {
        return new ApiException(ErrorCode.INTERNAL, "Server storage error.");
    }
}
