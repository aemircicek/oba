package com.emirchat.server;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
import com.emirchat.crypto.Identity;
import com.emirchat.protocol.FrameIO;
import com.emirchat.protocol.Handshake;
import com.emirchat.protocol.Session;
import com.emirchat.trust.PeerRejectedException;
import java.io.IOException;
import java.util.List;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Serves one client: handshake, then a request/response loop. All outgoing frames (responses
 * and pushes) go through a bounded queue drained by a writer thread, so pushing to a slow client
 * never blocks other users; a client that falls too far behind is disconnected.
 */
final class ClientConnection {
    /** Unauthenticated connections must log in quickly; this bounds idle half-open sockets. */
    private static final int PRE_AUTH_TIMEOUT_MS = 60_000;
    private static final int OUTBOUND_CAPACITY = 1024;
    private static final String SERVER_NAME = "server";
    private static final byte[] CLOSE = new byte[0];

    /** Everything a connection needs from the server. */
    record Services(Identity identity, AccountService accounts, FriendService friends, MessageService messages,
                    GroupService groups, AttachmentService attachments, Presence presence, VoiceRooms voice) {}

    private final Socket socket;
    private final Services services;
    private final String address;
    private final BlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(OUTBOUND_CAPACITY);
    private Session session;
    private volatile Database.User user;

    ClientConnection(Socket socket, Services services) {
        this.socket = socket;
        this.services = services;
        this.address = socket.getInetAddress().getHostAddress();
    }

    void run() {
        Thread writer = null;
        try (socket) {
            socket.setSoTimeout(PRE_AUTH_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            FrameIO io = new FrameIO(socket.getInputStream(), socket.getOutputStream());
            // Clients are authenticated by password after the handshake; any device key may connect.
            session = Handshake.asServer(io, services.identity(), SERVER_NAME, (name, fp) -> {});
            writer = Thread.ofVirtual().name("writer-" + address).start(this::writeLoop);
            while (true) {
                Packet.Frame frame = Packet.decode(session.channel().receive());
                if (frame.packet() instanceof Packet.CallAudio audio) {
                    relayAudio(audio); // never answered: live audio has no time for round trips
                    continue;
                }
                if (frame.packet() instanceof Packet.VoiceAudio audio) {
                    relayVoice(audio);
                    continue;
                }
                Packet response;
                try {
                    response = handle(frame.packet());
                } catch (ApiException e) {
                    response = new Packet.Error(e.code(), e.getMessage());
                }
                enqueue(Packet.encode(frame.requestId(), response));
            }
        } catch (SocketTimeoutException e) {
            // idle unauthenticated client
        } catch (IOException | SecurityException | PeerRejectedException e) {
            // client went away or misbehaved; nothing to report to it
        } finally {
            outbound.clear();
            outbound.offer(CLOSE);
            if (writer != null) writer.interrupt();
            Database.User u = user;
            if (u != null) leaveVoice();
            if (u != null && services.presence().remove(u.id(), this)) {
                services.friends().onlineChanged(u, false);
            }
        }
    }

    private static final int AUDIO_PER_SECOND = 100; // 50 packets a second is a normal call
    private static final long FRIEND_CHECK_MS = 10_000;
    private long audioWindowStart;
    private int audioInWindow;
    private String audioPeer;
    private long audioPeerId;
    private long audioPeerCheckedAt;

    /** Relays a call's audio packet to a friend's online devices; anything else is silently dropped. */
    private void relayAudio(Packet.CallAudio audio) {
        Database.User u = user;
        if (u == null) return;
        long now = System.currentTimeMillis();
        if (now - audioWindowStart >= 1000) {
            audioWindowStart = now;
            audioInWindow = 0;
        }
        if (++audioInWindow > AUDIO_PER_SECOND) return;
        try {
            if (!audio.peer().equals(audioPeer) || now - audioPeerCheckedAt > FRIEND_CHECK_MS) {
                audioPeer = null;
                long peerId = services.friends().friendId(u, audio.peer());
                if (peerId == 0) return;
                audioPeer = audio.peer();
                audioPeerId = peerId;
                audioPeerCheckedAt = now;
            }
        } catch (java.sql.SQLException e) {
            return;
        }
        services.presence().pushDroppable(audioPeerId, new Packet.CallAudioIn(u.username(), audio.data()));
    }

    // ------------------------------------------------------------------ voice rooms

    private long voiceWindowStart;
    private int voiceInWindow;
    private long voiceCheckedAt;

    private Packet.VoiceRoom joinVoice(long groupId) throws ApiException {
        Database.User u = me();
        if (!services.groups().isMemberOf(groupId, u.id())) throw new ApiException(ErrorCode.GROUP_NOT_FOUND, "You are not in that group.");
        long left = services.voice().join(groupId, u, this);
        if (left != 0 && left != groupId) announceVoice(left);
        voiceCheckedAt = System.currentTimeMillis();
        return announceVoice(groupId);
    }

    private void leaveVoice() {
        long left = services.voice().leave(this);
        if (left != 0) announceVoice(left);
    }

    /** Tells every online member of the group who is in its room now. */
    private Packet.VoiceRoom announceVoice(long groupId) {
        Packet.VoiceRoom room = new Packet.VoiceRoom(groupId, services.voice().participants(groupId));
        for (long member : services.groups().memberIds(groupId)) services.presence().push(member, room);
        return room;
    }

    /**
     * Relays an audio packet to the others in our room (only the room we are in, rate limited,
     * re-checking now and then that we are still a member). The server cannot read it.
     */
    private void relayVoice(Packet.VoiceAudio audio) {
        Database.User u = user;
        if (u == null || services.voice().roomOf(this) != audio.groupId()) return;
        long now = System.currentTimeMillis();
        if (now - voiceWindowStart >= 1000) {
            voiceWindowStart = now;
            voiceInWindow = 0;
        }
        if (++voiceInWindow > AUDIO_PER_SECOND) return;
        if (now - voiceCheckedAt > FRIEND_CHECK_MS) {
            voiceCheckedAt = now;
            if (!services.groups().isMemberOf(audio.groupId(), u.id())) {
                leaveVoice(); // removed from the group: out of its room too
                return;
            }
        }
        Packet.VoiceAudioIn out = new Packet.VoiceAudioIn(audio.groupId(), u.username(), audio.data());
        for (ClientConnection other : services.voice().others(audio.groupId(), this)) other.pushDroppable(out);
    }

    /** Queues live audio unless this device is already behind; a lost packet is a moment of silence. */
    void pushDroppable(Packet packet) {
        if (outbound.size() < OUTBOUND_CAPACITY / 4) enqueue(Packet.encode(0, packet));
    }

    /** Queues a server push; drops the connection if the client is not keeping up. */
    void push(Packet packet) {
        enqueue(Packet.encode(0, packet));
    }

    private void enqueue(byte[] frame) {
        if (!outbound.offer(frame)) {
            try {
                socket.close(); // too far behind: disconnect rather than buffer without limit
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    private void writeLoop() {
        try {
            while (true) {
                byte[] frame = outbound.take();
                if (frame == CLOSE) return;
                session.channel().send(frame);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | SecurityException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    private Packet handle(Packet packet) throws ApiException, IOException {
        return switch (packet) {
            case Packet.Ping p -> new Packet.Pong();
            case Packet.VoiceJoin j -> joinVoice(j.groupId());
            case Packet.VoiceLeave l -> {
                me();
                if (services.voice().roomOf(this) == l.groupId()) leaveVoice();
                yield new Packet.Ok();
            }
            case Packet.ListVoiceRooms l -> {
                List<Packet.VoiceRoom> rooms = new java.util.ArrayList<>();
                for (long g : services.groups().groupIdsOf(me())) {
                    List<String> people = services.voice().participants(g);
                    if (!people.isEmpty()) rooms.add(new Packet.VoiceRoom(g, people));
                }
                yield new Packet.VoiceRoomList(rooms);
            }
            case Packet.Register r -> {
                requireLoggedOut();
                Database.User created = services.accounts().register(r.username(), r.password(), session.peerIdentityKey());
                loggedIn(created, false);
                yield new Packet.LoggedIn(created.username(), false);
            }
            case Packet.Login l -> {
                requireLoggedOut();
                AccountService.LoginResult result = services.accounts().login(l.username(), l.password(),
                        session.peerIdentityKey(), address);
                loggedIn(result.user(), result.newDevice());
                yield new Packet.LoggedIn(result.user().username(), result.newDevice());
            }
            case Packet.SendFriendRequest s -> new Packet.FriendRequestResult(services.friends().request(me(), s.username()));
            case Packet.RespondFriendRequest r -> {
                services.friends().respond(me(), r.username(), r.accept());
                yield new Packet.Ok();
            }
            case Packet.RemoveFriend r -> {
                services.friends().remove(me(), r.username());
                yield new Packet.Ok();
            }
            case Packet.ListFriends l -> services.friends().list(me());
            case Packet.UploadKeys k -> {
                services.messages().uploadKeys(me(), session.peerIdentityKey(), k);
                yield new Packet.Ok();
            }
            case Packet.UploadPrekeys u -> {
                services.messages().uploadPrekeys(me(), u.prekeys());
                yield new Packet.Ok();
            }
            case Packet.KeyStatus k -> services.messages().status(me());
            case Packet.FetchBundle f -> new Packet.Bundle(services.messages().bundle(me(), f.username()));
            case Packet.SendEnvelope s -> services.messages().send(me(), s.recipient(), s.body());
            case Packet.FetchPending f -> new Packet.PendingCount(services.messages().pushPending(me(), this));
            case Packet.CreateGroup c -> services.groups().create(me(), c.name(), c.members());
            case Packet.AddGroupMember a -> {
                services.groups().add(me(), a.groupId(), a.username());
                yield new Packet.Ok();
            }
            case Packet.CreateCommunity c -> services.groups().createCommunity(me());
            case Packet.CreateChannel c -> services.groups().createChannel(me(), c.communityId());
            case Packet.DeleteChannel d -> {
                services.groups().deleteChannel(me(), d.channelId());
                yield new Packet.Ok();
            }
            case Packet.SetAdmin s -> {
                services.groups().setAdmin(me(), s.communityId(), s.username(), s.admin());
                yield new Packet.Ok();
            }
            case Packet.CreateInvite i -> services.groups().createInvite(me(), i.communityId(), i.maxUses(), i.hours());
            case Packet.JoinCommunity j -> services.groups().join(me(), j.code());
            case Packet.RevokeInvites r -> {
                services.groups().revokeInvites(me(), r.communityId());
                yield new Packet.Ok();
            }
            case Packet.RenameGroup r -> {
                services.groups().rename(me(), r.groupId(), r.sealedName());
                yield new Packet.Ok();
            }
            case Packet.RemoveGroupMember r -> {
                services.groups().remove(me(), r.groupId(), r.username());
                yield new Packet.Ok();
            }
            case Packet.LeaveGroup l -> {
                services.groups().leave(me(), l.groupId());
                yield new Packet.Ok();
            }
            case Packet.ListGroups l -> services.groups().list(me());
            case Packet.SendGroupEnvelope s -> services.messages().sendToGroup(me(), s.groupId(), s.body());
            case Packet.AckEnvelopes a -> {
                services.messages().ack(me(), a.ids());
                yield new Packet.Ok();
            }
            case Packet.UploadStart u -> services.attachments().start(me(), u.size(), u.recipient(), u.groupId());
            case Packet.UploadChunk u -> {
                services.attachments().chunk(me(), u.attachmentId(), u.offset(), u.data());
                yield new Packet.Ok();
            }
            case Packet.UploadFinish u -> {
                services.attachments().finish(me(), u.attachmentId());
                yield new Packet.Ok();
            }
            case Packet.FetchAttachment f -> services.attachments().fetch(me(), f.attachmentId(), f.offset());
            default -> throw new ApiException(ErrorCode.PROTOCOL, "Unexpected packet from client");
        };
    }

    private Database.User me() throws ApiException {
        Database.User u = user;
        if (u == null) throw new ApiException(ErrorCode.NOT_LOGGED_IN, "Log in first.");
        return u;
    }

    private void requireLoggedOut() throws ApiException {
        if (user != null) throw new ApiException(ErrorCode.ALREADY_LOGGED_IN, "Already logged in.");
    }

    private void loggedIn(Database.User u, boolean newDevice) throws IOException {
        socket.setSoTimeout(0); // authenticated sessions may stay idle
        user = u;
        if (services.presence().add(u.id(), this)) services.friends().onlineChanged(u, true);
        if (newDevice) {
            services.messages().deviceChanged(u);
            services.friends().deviceChanged(u, session.peerIdentityKey());
        }
    }
}
