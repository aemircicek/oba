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
            if (db.queuedCount(recipient.id()) >= MAX_QUEUED_PER_RECIPIENT) {
                throw new ApiException(ErrorCode.LIMIT_REACHED, recipient.username() + "'s inbox is full.");
            }
            id = db.queueEnvelope(recipient.id(), me.id(), now, body);
        } catch (SQLException e) {
            throw storage();
        }
        presence.push(recipient.id(), new Packet.EnvelopeReceived(new Packet.Envelope(id, me.username(), now, body)));
        return new Packet.EnvelopeAccepted(id, now);
    }

    /** Queues pushes for everything waiting for this user; returns how many. */
    int pushPending(Database.User me, ClientConnection to) throws ApiException {
        try {
            List<Database.QueuedEnvelope> queued = db.queuedFor(me.id(), PUSH_BATCH);
            for (Database.QueuedEnvelope e : queued) {
                to.push(new Packet.EnvelopeReceived(new Packet.Envelope(e.id(), e.sender(), e.sentAt(), e.body())));
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

    private Database.User friend(Database.User me, String rawName) throws ApiException {
        String name = AccountService.normalize(rawName);
        try {
            if (AccountService.USERNAME.matcher(name).matches()) {
                Optional<Database.User> u = db.findUser(name);
                if (u.isPresent() && db.areFriends(me.id(), u.get().id())) return u.get();
            }
        } catch (SQLException e) {
            throw storage();
        }
        throw new ApiException(ErrorCode.NOT_FRIENDS, "You can only message friends.");
    }

    private static ApiException storage() {
        return new ApiException(ErrorCode.INTERNAL, "Server storage error.");
    }
}
