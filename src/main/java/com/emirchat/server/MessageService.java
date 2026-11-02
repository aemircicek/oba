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
        if (!Identity.verify(identityKey, DeviceKeys.dhIdentitySignedData(k.dhIdentity()), k.dhIdentitySignature())
                || !Identity.verify(identityKey, DeviceKeys.signedPrekeySignedData(k.signedPrekeyId(), k.signedPrekey()),
                        k.signedPrekeySignature())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Key signatures do not match your device key.");
        }
        try {
            db.putDeviceKeys(me.id(), new Database.DeviceKeysRow(k.dhIdentity(), k.dhIdentitySignature(),
                    k.signedPrekeyId(), k.signedPrekey(), k.signedPrekeySignature()));
        } catch (SQLException e) {
            throw storage();
        }
    }

    void uploadPrekeys(Database.User me, List<Packet.Prekey> prekeys) throws ApiException {
        try {
            if (db.prekeyCount(me.id()) + prekeys.size() > MAX_STORED_PREKEYS) {
                throw new ApiException(ErrorCode.LIMIT_REACHED, "Too many stored prekeys.");
            }
            for (Packet.Prekey p : prekeys) db.addPrekey(me.id(), p.id(), p.key());
        } catch (SQLException e) {
            throw storage();
        }
    }

    Packet.KeyStatusResult status(Database.User me) throws ApiException {
        try {
            Optional<Database.DeviceKeysRow> keys = db.deviceKeys(me.id());
            return new Packet.KeyStatusResult(keys.isPresent(), keys.map(Database.DeviceKeysRow::spkId).orElse(-1),
                    db.prekeyCount(me.id()));
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
        long now = clock.millis();
        long last = 0;
        try {
            if (!db.isMember(groupId, me.id())) {
                throw new ApiException(ErrorCode.GROUP_NOT_FOUND, "You are not in that group.");
            }
            for (Database.User member : db.members(groupId)) {
                if (member.id() == me.id()) continue;
                try {
                    last = queue(member, me, groupId, now, body);
                } catch (ApiException full) {
                    // one member's full inbox must not block the whole group
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
        long id = db.queueEnvelope(recipient.id(), sender.id(), groupId, now, body);
        presence.push(recipient.id(), new Packet.EnvelopeReceived(new Packet.Envelope(id, sender.username(), groupId, now, body)));
        return id;
    }

    /** Queues pushes for everything waiting for this user; returns how many. */
    int pushPending(Database.User me, ClientConnection to) throws ApiException {
        try {
            List<Database.QueuedEnvelope> queued = db.queuedFor(me.id(), PUSH_BATCH);
            for (Database.QueuedEnvelope e : queued) {
                to.push(new Packet.EnvelopeReceived(new Packet.Envelope(e.id(), e.sender(), e.groupId(), e.sentAt(), e.body())));
            }
            return queued.size();
        } catch (SQLException e) {
            throw storage();
        }
    }

    /** Deletes acknowledged envelopes and tells their senders they were delivered. */
    void ack(Database.User me, List<Long> ids) throws ApiException {
        try {
            for (long id : ids) {
                long sender = db.deleteEnvelope(me.id(), id);
                if (sender >= 0) presence.push(sender, new Packet.EnvelopeDelivered(id));
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
