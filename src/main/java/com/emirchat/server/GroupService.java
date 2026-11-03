package com.emirchat.server;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
import com.emirchat.e2e.Attachments;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Group and community membership.
 *
 * <p>Groups: the owner adds (their own friends) and removes members; anyone may leave; if the
 * owner leaves, the longest-standing member takes over.
 *
 * <p>Communities: a community is a group that also acts as its "general" channel, with more
 * channels under it. Membership is the community's: everyone in the community is in every channel,
 * so channels cannot be joined or left on their own. The owner and admins manage channels,
 * members and invite codes; only the owner appoints admins, and admins cannot remove the owner or
 * other admins. People join with an invite code (no friendship needed); the server keeps only a
 * hash of each code.
 *
 * <p>Every change is pushed to all members, so clients can rotate their keys and show who joined
 * or left.
 */
final class GroupService {
    static final int MAX_MEMBERS = Packet.MAX_GROUP_MEMBERS;
    static final int MAX_GROUPS_PER_USER = 200;
    static final int MAX_CHANNELS = 50;
    static final int MAX_INVITE_HOURS = 30 * 24;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O, 1/I

    private final Database db;
    private final Presence presence;
    private final Clock clock;

    GroupService(Database db, Presence presence) {
        this(db, presence, Clock.systemUTC());
    }

    GroupService(Database db, Presence presence, Clock clock) {
        this.db = db;
        this.presence = presence;
        this.clock = clock;
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
            checkRoom(me, 1);
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

    // ------------------------------------------------------------------ communities

    /** A new community with only its creator in it. */
    synchronized Packet.GroupInfo createCommunity(Database.User me) throws ApiException {
        try {
            checkRoom(me, 1);
            long id = db.createGroup("", me.id(), List.of(me.id()), Database.KIND_COMMUNITY, 0, 0);
            Packet.GroupInfo info = info(id);
            broadcast(info);
            return info;
        } catch (SQLException e) {
            throw storage();
        }
    }

    synchronized Packet.GroupInfo createChannel(Database.User me, long communityId) throws ApiException {
        try {
            Database.Group root = managedCommunity(me, communityId);
            if (db.channels(root.id()).size() >= MAX_CHANNELS) {
                throw new ApiException(ErrorCode.LIMIT_REACHED, "A community can have at most " + MAX_CHANNELS + " channels.");
            }
            List<Long> members = db.members(root.id()).stream().map(Database.User::id).toList();
            long id = db.createGroup("", root.ownerId(), members, Database.KIND_CHANNEL, root.id(), db.nextPosition(root.id()));
            Packet.GroupInfo info = info(id);
            broadcast(info);
            return info;
        } catch (SQLException e) {
            throw storage();
        }
    }

    synchronized void deleteChannel(Database.User me, long channelId) throws ApiException {
        try {
            Database.Group channel = memberGroup(me, channelId);
            if (!channel.isChannel()) throw new ApiException(ErrorCode.INVALID_REQUEST, "That is not a channel.");
            managedCommunity(me, channel.parentId());
            List<Database.User> members = db.members(channel.id());
            db.deleteGroup(channel.id());
            for (Database.User u : members) presence.push(u.id(), new Packet.GroupRemoved(channel.id()));
        } catch (SQLException e) {
            throw storage();
        }
    }

    synchronized void setAdmin(Database.User me, long communityId, String username, boolean admin) throws ApiException {
        try {
            Database.Group root = memberGroup(me, communityId);
            if (!root.isCommunity()) throw new ApiException(ErrorCode.INVALID_REQUEST, "That is not a community.");
            if (root.ownerId() != me.id()) throw new ApiException(ErrorCode.NOT_GROUP_ADMIN, "Only the owner can choose admins.");
            Database.User target = member(root.id(), username);
            if (target.id() == root.ownerId()) throw new ApiException(ErrorCode.INVALID_REQUEST, "The owner is always in charge.");
            db.setAdmin(root.id(), target.id(), admin);
            broadcastCommunity(root.id());
        } catch (SQLException e) {
            throw storage();
        }
    }

    /** @return a new invite code (shown once); the database keeps only its hash */
    synchronized Packet.InviteCreated createInvite(Database.User me, long communityId, int maxUses, int hours)
            throws ApiException {
        if (maxUses < 0 || maxUses > 1000 || hours < 1 || hours > MAX_INVITE_HOURS) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Invites last 1 hour to 30 days, for up to 1000 people.");
        }
        try {
            Database.Group root = managedCommunity(me, communityId);
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < 26; i++) { // 26 symbols of 5 bits: 130 random bits
                if (i > 0 && i % 5 == 0) code.append('-');
                code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            }
            db.createInvite(hash(code.toString()), root.id(), me.id(),
                    clock.millis() + Duration.ofHours(hours).toMillis(), maxUses);
            return new Packet.InviteCreated(code.toString());
        } catch (SQLException e) {
            throw storage();
        }
    }

    synchronized Packet.GroupInfo join(Database.User me, String rawCode) throws ApiException {
        try {
            byte[] h = hash(normalizeCode(rawCode));
            Database.Invite invite = db.invite(h).orElseThrow(GroupService::badInvite);
            if (clock.millis() > invite.expiresAt() || (invite.maxUses() > 0 && invite.uses() >= invite.maxUses())) {
                throw badInvite();
            }
            Database.Group root = db.group(invite.rootId()).orElseThrow(GroupService::badInvite);
            if (db.isMember(root.id(), me.id())) return info(root.id());
            List<Long> channels = db.channels(root.id());
            checkRoom(me, 1 + channels.size());
            if (db.members(root.id()).size() >= MAX_MEMBERS) throw new ApiException(ErrorCode.LIMIT_REACHED, "The community is full.");
            db.useInvite(h);
            db.addMember(root.id(), me.id());
            for (long channel : channels) db.addMember(channel, me.id());
            broadcastCommunity(root.id());
            return info(root.id());
        } catch (SQLException e) {
            throw storage();
        }
    }

    synchronized void revokeInvites(Database.User me, long communityId) throws ApiException {
        try {
            db.deleteInvites(managedCommunity(me, communityId).id());
        } catch (SQLException e) {
            throw storage();
        }
    }

    /** Codes are typed by people: case, spaces and dashes do not matter. */
    static String normalizeCode(String raw) {
        String s = raw == null ? "" : raw.strip();
        int slash = s.lastIndexOf('/'); // a pasted link: take the last part
        if (slash >= 0) s = s.substring(slash + 1);
        StringBuilder out = new StringBuilder();
        for (char ch : s.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            if (CODE_ALPHABET.indexOf(ch) >= 0) {
                if (out.length() > 0 && out.length() % 6 == 5) out.append('-');
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static byte[] hash(String code) {
        return Attachments.sha256(code.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static ApiException badInvite() {
        return new ApiException(ErrorCode.INVALID_REQUEST, "That invite code is not valid (it may have expired).");
    }

    // ------------------------------------------------------------------ membership

    synchronized void add(Database.User me, long groupId, String username) throws ApiException {
        try {
            Database.Group g = memberGroup(me, groupId);
            if (g.isChannel()) throw new ApiException(ErrorCode.INVALID_REQUEST, "Add people to the community instead.");
            if (g.isCommunity()) managedCommunity(me, g.id()); else ownedGroup(me, groupId);
            Database.User newcomer = friend(me, username);
            if (db.isMember(g.id(), newcomer.id())) return;
            if (db.members(g.id()).size() >= MAX_MEMBERS) {
                throw new ApiException(ErrorCode.LIMIT_REACHED, "The group is full.");
            }
            db.addMember(g.id(), newcomer.id());
            if (g.isCommunity()) {
                for (long channel : db.channels(g.id())) db.addMember(channel, newcomer.id());
                broadcastCommunity(g.id());
            } else {
                broadcast(info(g.id()));
            }
        } catch (SQLException e) {
            throw storage();
        }
    }

    synchronized void remove(Database.User me, long groupId, String username) throws ApiException {
        try {
            Database.Group g = memberGroup(me, groupId);
            if (g.isChannel()) throw new ApiException(ErrorCode.INVALID_REQUEST, "Remove people from the community instead.");
            Database.User target = member(g.id(), username);
            if (target.id() == me.id()) throw new ApiException(ErrorCode.INVALID_REQUEST, "Use leave to leave your own group.");
            if (g.isCommunity()) {
                managedCommunity(me, g.id());
                boolean byOwner = g.ownerId() == me.id();
                if (target.id() == g.ownerId() || (!byOwner && db.isAdmin(g.id(), target.id()))) {
                    throw new ApiException(ErrorCode.NOT_GROUP_ADMIN, "Only the owner can remove an admin.");
                }
                removeFromCommunity(g.id(), target.id());
                broadcastCommunity(g.id());
            } else {
                ownedGroup(me, groupId);
                db.removeMember(g.id(), target.id());
                presence.push(target.id(), new Packet.GroupRemoved(g.id()));
                broadcast(info(g.id()));
            }
        } catch (SQLException e) {
            throw storage();
        }
    }

    synchronized void leave(Database.User me, long groupId) throws ApiException {
        try {
            Database.Group g = memberGroup(me, groupId);
            if (g.isChannel()) throw new ApiException(ErrorCode.INVALID_REQUEST, "Leave the community instead.");
            if (g.isCommunity()) {
                removeFromCommunity(g.id(), me.id());
                List<Database.User> rest = db.members(g.id());
                if (rest.isEmpty()) {
                    for (long channel : db.channels(g.id())) db.deleteGroup(channel);
                    db.deleteGroup(g.id());
                    return;
                }
                if (g.ownerId() == me.id()) {
                    long heir = rest.get(0).id(); // longest-standing member
                    db.setOwner(g.id(), heir);
                    db.setAdmin(g.id(), heir, false);
                    for (long channel : db.channels(g.id())) db.setOwner(channel, heir);
                }
                broadcastCommunity(g.id());
                return;
            }
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

    private void removeFromCommunity(long rootId, long userId) throws SQLException {
        for (long channel : db.channels(rootId)) {
            db.removeMember(channel, userId);
            presence.push(userId, new Packet.GroupRemoved(channel));
        }
        db.removeMember(rootId, userId);
        db.setAdmin(rootId, userId, false);
        presence.push(userId, new Packet.GroupRemoved(rootId));
    }

    /** Sets the encrypted name. The server checks only who may rename and the size. */
    synchronized void rename(Database.User me, long groupId, byte[] sealedName) throws ApiException {
        if (sealedName.length == 0) throw new ApiException(ErrorCode.INVALID_REQUEST, "Missing group name.");
        try {
            Database.Group g = memberGroup(me, groupId);
            if (g.root() != 0) managedCommunity(me, g.root()); else ownedGroup(me, groupId);
            db.renameGroup(g.id(), sealedName);
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
        List<String> admins = g.root() != 0 ? db.adminNames(g.root()) : List.of();
        return new Packet.GroupInfo(g.id(), g.name(), owner, members, g.sealedName(), g.kind(), g.parentId(),
                g.position(), admins);
    }

    private void broadcast(Packet.GroupInfo info) throws SQLException {
        for (Database.User u : db.members(info.id())) presence.push(u.id(), new Packet.GroupUpdated(info));
    }

    /** The community and every channel changed (members, owner or admins). */
    private void broadcastCommunity(long rootId) throws SQLException {
        broadcast(info(rootId));
        for (long channel : db.channels(rootId)) broadcast(info(channel));
    }

    private void checkRoom(Database.User me, int more) throws SQLException, ApiException {
        if (db.groupsOf(me.id()).size() + more > MAX_GROUPS_PER_USER) {
            throw new ApiException(ErrorCode.LIMIT_REACHED, "You are in too many groups.");
        }
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

    /** A community that {@code me} owns or administers. */
    private Database.Group managedCommunity(Database.User me, long communityId) throws SQLException, ApiException {
        Database.Group root = memberGroup(me, communityId);
        if (!root.isCommunity()) throw new ApiException(ErrorCode.INVALID_REQUEST, "That is not a community.");
        if (root.ownerId() != me.id() && !db.isAdmin(root.id(), me.id())) {
            throw new ApiException(ErrorCode.NOT_GROUP_ADMIN, "Only the community's owner and admins can do that.");
        }
        return root;
    }

    private Database.User member(long groupId, String username) throws SQLException, ApiException {
        return db.findUser(AccountService.normalize(username))
                .filter(u -> isMember(groupId, u.id()))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST, username + " is not in the group."));
    }

    /** For voice rooms: whether the user is in the group or channel right now. */
    boolean isMemberOf(long groupId, long userId) {
        return isMember(groupId, userId);
    }

    /** User ids of a group's members (for voice room pushes); empty if it does not exist. */
    List<Long> memberIds(long groupId) {
        try {
            return db.members(groupId).stream().map(Database.User::id).toList();
        } catch (SQLException e) {
            return List.of();
        }
    }

    /** Ids of the groups and channels the user is in. */
    List<Long> groupIdsOf(Database.User me) {
        try {
            return list(me).groups().stream().map(Packet.GroupInfo::id).toList();
        } catch (ApiException e) {
            return List.of();
        }
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
