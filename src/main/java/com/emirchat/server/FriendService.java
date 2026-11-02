package com.emirchat.server;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Friend requests, friendships and the notifications they trigger. Mutations are serialised
 * (synchronized) so concurrent mutual requests cannot race; pushes are queued, never blocking.
 */
final class FriendService {
    static final int MAX_FRIENDS = 1000;
    static final int MAX_PENDING_PER_USER = 100;
    static final int REQUESTS_PER_HOUR = 30;

    private final Database db;
    private final Presence presence;
    private final RateLimiter requestLimiter;

    FriendService(Database db, Presence presence, Clock clock) {
        this.db = db;
        this.presence = presence;
        this.requestLimiter = new RateLimiter(REQUESTS_PER_HOUR, Duration.ofHours(1), clock);
    }

    /** @return true if this made us friends immediately (they had already asked us) */
    synchronized boolean request(Database.User me, String targetName) throws ApiException {
        Database.User target = find(targetName);
        if (target.id() == me.id()) throw new ApiException(ErrorCode.INVALID_REQUEST, "You can't add yourself.");
        try {
            if (db.areFriends(me.id(), target.id())) {
                throw new ApiException(ErrorCode.ALREADY_FRIENDS, "You are already friends with " + target.username() + ".");
            }
            if (db.requestExists(target.id(), me.id())) {
                befriend(me, target);
                return true;
            }
            if (db.requestExists(me.id(), target.id())) return false; // idempotent
            if (requestLimiter.isBlocked(me.username())) {
                throw new ApiException(ErrorCode.RATE_LIMITED, "Too many friend requests. Try again later.");
            }
            if (db.friendCount(me.id()) >= MAX_FRIENDS) {
                throw new ApiException(ErrorCode.LIMIT_REACHED, "You have reached " + MAX_FRIENDS + " friends.");
            }
            if (db.incomingRequestCount(target.id()) >= MAX_PENDING_PER_USER) {
                throw new ApiException(ErrorCode.LIMIT_REACHED, target.username() + " can't receive more requests right now.");
            }
            requestLimiter.recordFailure(me.username()); // counts requests, not failures
            db.addRequest(me.id(), target.id());
        } catch (SQLException e) {
            throw storage();
        }
        presence.push(target.id(), new Packet.FriendRequestReceived(me.username()));
        return false;
    }

    synchronized void respond(Database.User me, String fromName, boolean accept) throws ApiException {
        Database.User from = find(fromName);
        try {
            if (!db.requestExists(from.id(), me.id())) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "There is no pending request from " + from.username() + ".");
            }
            if (accept) {
                if (db.friendCount(me.id()) >= MAX_FRIENDS) {
                    throw new ApiException(ErrorCode.LIMIT_REACHED, "You have reached " + MAX_FRIENDS + " friends.");
                }
                befriend(me, from);
            } else {
                db.deleteRequest(from.id(), me.id()); // declining is silent, as in most messengers
            }
        } catch (SQLException e) {
            throw storage();
        }
    }

    /** Unfriends, or cancels our outgoing request. */
    synchronized void remove(Database.User me, String name) throws ApiException {
        Database.User other = find(name);
        try {
            if (db.deleteRequest(me.id(), other.id())) return;
            if (!db.areFriends(me.id(), other.id())) {
                throw new ApiException(ErrorCode.NOT_FRIENDS, "You are not friends with " + other.username() + ".");
            }
            db.removeFriends(me.id(), other.id());
        } catch (SQLException e) {
            throw storage();
        }
        presence.push(other.id(), new Packet.FriendRemoved(me.username()));
    }

    Packet.FriendList list(Database.User me) throws ApiException {
        try {
            List<Packet.Friend> friends = new ArrayList<>();
            for (Database.User f : db.friendsOf(me.id())) friends.add(describe(f));
            return new Packet.FriendList(friends, db.incomingRequests(me.id()), db.outgoingRequests(me.id()));
        } catch (SQLException e) {
            throw storage();
        }
    }

    void onlineChanged(Database.User user, boolean online) {
        notifyFriends(user, new Packet.PresenceChanged(user.username(), online));
    }

    void deviceChanged(Database.User user, byte[] newKey) {
        notifyFriends(user, new Packet.IdentityKeyChanged(user.username(), newKey));
    }

    private void notifyFriends(Database.User user, Packet packet) {
        try {
            for (Database.User f : db.friendsOf(user.id())) presence.push(f.id(), packet);
        } catch (SQLException e) {
            // notifications are best effort; clients re-sync with ListFriends
        }
    }

    private void befriend(Database.User me, Database.User other) throws SQLException {
        db.makeFriends(me.id(), other.id());
        Database.User meFresh = db.findUserById(me.id()).orElse(me);
        Database.User otherFresh = db.findUserById(other.id()).orElse(other);
        presence.push(me.id(), new Packet.FriendAdded(describe(otherFresh)));
        presence.push(other.id(), new Packet.FriendAdded(describe(meFresh)));
    }

    private Packet.Friend describe(Database.User u) {
        return new Packet.Friend(u.username(), presence.isOnline(u.id()), u.identityKey());
    }

    private Database.User find(String rawName) throws ApiException {
        String name = AccountService.normalize(rawName);
        try {
            if (AccountService.USERNAME.matcher(name).matches()) {
                var found = db.findUser(name);
                if (found.isPresent()) return found.get();
            }
        } catch (SQLException e) {
            throw storage();
        }
        throw new ApiException(ErrorCode.USER_NOT_FOUND, "No user called " + name + ".");
    }

    private static ApiException storage() {
        return new ApiException(ErrorCode.INTERNAL, "Server storage error.");
    }

    /** @return the user id of {@code name} if they are a friend of {@code me}, otherwise 0 */
    long friendId(Database.User me, String name) throws SQLException {
        Database.User other = db.findUser(name).orElse(null);
        return other != null && db.areFriends(me.id(), other.id()) ? other.id() : 0;
    }
}