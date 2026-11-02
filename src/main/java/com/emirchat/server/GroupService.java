package com.emirchat.server;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Group membership. The owner adds (their own friends) and removes members; anyone may leave; if
 * the owner leaves, the longest-standing member takes over. Every change is pushed to all
 * members, so clients can rotate their sender keys and show who joined or left.
 */
final class GroupService {
    static final int MAX_MEMBERS = Packet.MAX_GROUP_MEMBERS;
    static final int MAX_GROUPS_PER_USER = 200;

    private final Database db;
    private final Presence presence;

    GroupService(Database db, Presence presence) {
        this.db = db;
        this.presence = presence;
    }

    /**
     * @param rawName empty for current clients, which set an end-to-end encrypted name right after
     *                creating the group; older clients send a plaintext name
     */
    synchronized Packet.GroupInfo create(Database.User me, String rawName, List<String> memberNames) throws ApiException {
        String name = rawName == null ? "" : rawName.strip();
        if (name.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Invalid group name.");
        }
        try {
            if (db.groupsOf(me.id()).size() >= MAX_GROUPS_PER_USER) {
                throw new ApiException(ErrorCode.LIMIT_REACHED, "You are in too many groups.");
            }
            Set<Long> ids = new LinkedHashSet<>();
            ids.add(me.id());
            for (String n : memberNames) ids.add(friend(me, n).id());
            if (ids.size() > MAX_MEMBERS) throw new ApiException(ErrorCode.LIMIT_REACHED, "At most " + MAX_MEMBERS + " members.");
            long id = db.createGroup(name, me.id(), List.copyOf(ids));
            Packet.GroupInfo info = info(id);
            broadcast(info);
            return info;
        } catch (SQLException e) {
            throw storage();
        }
    }

    synchronized void add(Database.User me, long groupId, String username) throws ApiException {
        try {
            Database.Group g = ownedGroup(me, groupId);
            Database.User newcomer = friend(me, username);
            if (db.isMember(g.id(), newcomer.id())) return;
            if (db.members(g.id()).size() >= MAX_MEMBERS) {
                throw new ApiException(ErrorCode.LIMIT_REACHED, "The group is full.");
            }
            db.addMember(g.id(), newcomer.id());
            broadcast(info(g.id()));
        } catch (SQLException e) {
            throw storage();
        }
    }

    /** Sets the encrypted name. The server checks only who may rename and the size. */
    synchronized void rename(Database.User me, long groupId, byte[] sealedName) throws ApiException {
        if (sealedName.length == 0) throw new ApiException(ErrorCode.INVALID_REQUEST, "Missing group name.");
        try {
            Database.Group g = ownedGroup(me, groupId);
            db.renameGroup(g.id(), sealedName);
            broadcast(info(g.id()));
        } catch (SQLException e) {
            throw storage();
        }
    }

    synchronized void remove(Database.User me, long groupId, String username) throws ApiException {
        try {
            Database.Group g = ownedGroup(me, groupId);
            Database.User target = db.findUser(AccountService.normalize(username))
                    .filter(u -> isMember(g.id(), u.id()))
                    .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST, username + " is not in the group."));
            if (target.id() == me.id()) throw new ApiException(ErrorCode.INVALID_REQUEST, "Use leave to leave your own group.");
            db.removeMember(g.id(), target.id());
            presence.push(target.id(), new Packet.GroupRemoved(g.id()));
            broadcast(info(g.id()));
        } catch (SQLException e) {
            throw storage();
        }
    }

    synchronized void leave(Database.User me, long groupId) throws ApiException {
        try {
            Database.Group g = memberGroup(me, groupId);
            db.removeMember(g.id(), me.id());
            presence.push(me.id(), new Packet.GroupRemoved(g.id()));
            List<Database.User> rest = db.members(g.id());
            if (rest.isEmpty()) {
                db.deleteGroup(g.id());
                return;
            }
            if (g.ownerId() == me.id()) db.setOwner(g.id(), rest.get(0).id()); // longest-standing member
            broadcast(info(g.id()));
        } catch (SQLException e) {
            throw storage();
        }
    }

    Packet.GroupList list(Database.User me) throws ApiException {
        try {
            List<Packet.GroupInfo> groups = new ArrayList<>();
            for (long id : db.groupsOf(me.id())) groups.add(info(id));
            return new Packet.GroupList(groups);
        } catch (SQLException e) {
            throw storage();
        }
    }

    private Packet.GroupInfo info(long groupId) throws SQLException {
        Database.Group g = db.group(groupId).orElseThrow(() -> new SQLException("group vanished"));
        List<Packet.Member> members = new ArrayList<>();
        String owner = "";
        for (Database.User u : db.members(groupId)) {
            members.add(new Packet.Member(u.username(), u.identityKey()));
            if (u.id() == g.ownerId()) owner = u.username();
        }
        return new Packet.GroupInfo(g.id(), g.name(), owner, members, g.sealedName());
    }

    private void broadcast(Packet.GroupInfo info) throws SQLException {
        for (Database.User u : db.members(info.id())) presence.push(u.id(), new Packet.GroupUpdated(info));
    }

    private Database.Group memberGroup(Database.User me, long groupId) throws SQLException, ApiException {
        Optional<Database.Group> g = db.group(groupId);
        if (g.isEmpty() || !db.isMember(groupId, me.id())) {
            throw new ApiException(ErrorCode.GROUP_NOT_FOUND, "No such group.");
        }
        return g.get();
    }

    private Database.Group ownedGroup(Database.User me, long groupId) throws SQLException, ApiException {
        Database.Group g = memberGroup(me, groupId);
        if (g.ownerId() != me.id()) throw new ApiException(ErrorCode.NOT_GROUP_ADMIN, "Only the group's admin can do that.");
        return g;
    }

    private boolean isMember(long groupId, long userId) {
        try {
            return db.isMember(groupId, userId);
        } catch (SQLException e) {
            return false;
        }
    }

    private Database.User friend(Database.User me, String rawName) throws SQLException, ApiException {
        String name = AccountService.normalize(rawName);
        if (AccountService.USERNAME.matcher(name).matches()) {
            Optional<Database.User> u = db.findUser(name);
            if (u.isPresent() && (u.get().id() == me.id() || db.areFriends(me.id(), u.get().id()))) return u.get();
        }
        throw new ApiException(ErrorCode.NOT_FRIENDS, "You can only add your friends to a group (" + name + ").");
    }

    private static ApiException storage() {
        return new ApiException(ErrorCode.INTERNAL, "Server storage error.");
    }
}
