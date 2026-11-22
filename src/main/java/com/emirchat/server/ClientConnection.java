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
    /**
     * How the server names itself in the handshake (signed with its key). Clients before 3.6 do
     * not look at it; 3.6 reads the version from it, to know which requests it may send without an
     * older server closing the connection on an unknown one.
     */
    static final String SERVER_NAME = "server-3.6";
    private static final byte[] CLOSE = new byte[0];

    /** Everything a connection needs from the server. */
    record Services(Identity identity, AccountService accounts, FriendService friends, MessageService messages,
                    GroupService groups, AttachmentService attachments, Presence presence, VoiceRooms voice,
                    ServerMetrics metrics, UdpRelay relay, LinkService links) {}

    private final Socket socket;
    private final Services services;
    /** The key this client's address is limited under, see {@link Addresses}. */
    private final String address;
    private final boolean local;
    private final BlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(OUTBOUND_CAPACITY);
    private Session session;
    private volatile Database.User user;
    /** Which of the account's devices this is (0 for the first, and for apps before 3.6). */
    private volatile int deviceId;
    /** Said SetDevice: a 3.6 app, which is told which device each envelope came from. */
    private volatile boolean multiDevice;

    int deviceId() {
        return deviceId;
    }

    boolean multiDevice() {
        return multiDevice;
    }

    /** Pushes an envelope in the form this app reads. */
    void pushEnvelope(Packet.Envelope envelope, int senderDevice) {
        push(multiDevice ? new Packet.EnvelopeReceivedFrom(envelope, senderDevice) : new Packet.EnvelopeReceived(envelope));
    }

    /** The account removed this device: it is told, then cut off. */
    void removed() {
        push(new Packet.DeviceRemoved(deviceId));
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(500);
                socket.close();
            } catch (IOException | InterruptedException ignored) {
                // gone
            }
        });
    }

    ClientConnection(Socket socket, Services services) {
        this.socket = socket;
        this.services = services;
        this.address = Addresses.key(socket.getInetAddress());
        this.local = Addresses.local(socket.getInetAddress());
    }

    void run() {
        Thread writer = null;
        try (socket) {
            socket.setSoTimeout(PRE_AUTH_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            keepAlive(socket);
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
                throttle();
                Packet response;
                try {
                    response = handle(frame.packet());
                } catch (ApiException e) {
                    if (frame.packet() instanceof Packet.Login || frame.packet() instanceof Packet.LoginWithCode) {
                        services.metrics().loginFailures.increment();
                    }
                    response = new Packet.Error(e.code(), e.getMessage());
                }
                services.metrics().requests.increment();
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
                services.relay().forget(u.id()); // no calls without a connection
            }
        }
    }

    /**
     * Logged-in clients may stay silent for days (the app sends nothing while nobody writes), so
     * a read timeout would cut off healthy ones. TCP keep-alive finds the dead ones instead: a
     * computer that vanished without closing the connection (pulled cable, sleep, a dropped
     * mobile link) stops answering probes and is closed after about two minutes, so it does not
     * stay "online" and hold a connection forever.
     */
    private static void keepAlive(Socket socket) {
        try {
            socket.setKeepAlive(true);
            setIfSupported(socket, jdk.net.ExtendedSocketOptions.TCP_KEEPIDLE, 60);
            setIfSupported(socket, jdk.net.ExtendedSocketOptions.TCP_KEEPINTERVAL, 20);
            setIfSupported(socket, jdk.net.ExtendedSocketOptions.TCP_KEEPCOUNT, 3);
        } catch (IOException | LinkageError e) {
            // the connection works without it (LinkageError: a runtime built without the jdk.net module)
        }
    }

    private static void setIfSupported(Socket socket, java.net.SocketOption<Integer> option, int value) throws IOException {
        if (socket.supportedOptions().contains(option)) socket.setOption(option, value);
    }

    /** Requests a second in the long run, and how many may come at once (file transfers send in bursts). */
    private static final int REQUESTS_PER_SECOND = 400;
    private static final int REQUEST_BURST = 800;
    private double requestTokens = REQUEST_BURST;
    private long requestTokensAt = System.nanoTime();

    /**
     * Slows a client that sends requests faster than any real use needs, by reading its next
     * request later. Nothing is refused, so a fast file transfer only waits a little, while a
     * flood from one client cannot keep the database busy for everyone else.
     */
    private void throttle() throws IOException {
        long now = System.nanoTime();
        requestTokens = Math.min(REQUEST_BURST, requestTokens + (now - requestTokensAt) / 1e9 * REQUESTS_PER_SECOND);
        requestTokensAt = now;
        if (requestTokens < 1) {
            services.metrics().throttled.increment();
            try {
                Thread.sleep((long) Math.ceil((1 - requestTokens) * 1000 / REQUESTS_PER_SECOND));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
            requestTokens = 1;
            requestTokensAt = System.nanoTime();
        }
        requestTokens -= 1;
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
        if (++audioInWindow > AUDIO_PER_SECOND) {
            services.metrics().callPacketsDropped.increment();
            return;
        }
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
        services.metrics().callPackets.increment();
        services.presence().pushDroppable(audioPeerId, new Packet.CallAudioIn(u.username(), audio.data()));
    }

    // ------------------------------------------------------------------ voice rooms

    /** Set once this client shows it knows voice rooms; 3.3 clients would drop the connection on them. */
    private volatile boolean voiceAware;

    boolean voiceAware() {
        return voiceAware;
    }

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

    private Packet.VoiceRoom announceVoice(long groupId) {
        return services.voice().announce(groupId);
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
        if (++voiceInWindow > AUDIO_PER_SECOND) {
            services.metrics().voicePacketsDropped.increment();
            return;
        }
        if (now - voiceCheckedAt > FRIEND_CHECK_MS) {
            voiceCheckedAt = now;
            if (!services.groups().isMemberOf(audio.groupId(), u.id())) {
                leaveVoice(); // removed from the group: out of its room too
                return;
            }
        }
        services.metrics().voicePackets.increment();
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
            services.metrics().slowClients.increment();
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
            case Packet.OpenRelay o -> {
                Database.User u = me();
                long peer;
                try {
                    peer = services.friends().friendId(u, o.peer());
                } catch (java.sql.SQLException e) {
                    throw new ApiException(ErrorCode.INTERNAL, "Try again.");
                }
                if (peer == 0) throw new ApiException(ErrorCode.NOT_FRIENDS, "You can only call friends.");
                yield new Packet.RelayTicket(services.relay().open(u.id(), peer));
            }
            case Packet.VoiceJoin j -> {
                voiceAware = true;
                yield joinVoice(j.groupId());
            }
            case Packet.VoiceLeave l -> {
                me();
                if (services.voice().roomOf(this) == l.groupId()) leaveVoice();
                yield new Packet.Ok();
            }
            case Packet.ListVoiceRooms l -> {
                voiceAware = true;
                List<Packet.VoiceRoom> rooms = new java.util.ArrayList<>();
                for (long g : services.groups().groupIdsOf(me())) {
                    List<String> people = services.voice().participants(g);
                    if (!people.isEmpty()) rooms.add(new Packet.VoiceRoom(g, people));
                }
                yield new Packet.VoiceRoomList(rooms);
            }
            case Packet.Register r -> {
                requireLoggedOut();
                Database.User created = services.accounts().register(r.username(), r.password(), session.peerIdentityKey(),
                        local ? null : address);
                loggedIn(created, false);
                services.metrics().registrations.increment();
                yield new Packet.LoggedIn(created.username(), false);
            }
            case Packet.Login l -> {
                requireLoggedOut();
                AccountService.LoginResult result = services.accounts().login(l.username(), l.password(),
                        session.peerIdentityKey(), address);
                loggedIn(result.user(), result.newDevice());
                services.metrics().logins.increment();
                yield new Packet.LoggedIn(result.user().username(), result.newDevice());
            }
            case Packet.LoginWithCode l -> {
                requireLoggedOut();
                AccountService.LoginResult result = services.accounts().login(l.username(), l.password(), l.code(),
                        session.peerIdentityKey(), address);
                loggedIn(result.user(), result.newDevice());
                services.metrics().logins.increment();
                yield new Packet.LoggedIn(result.user().username(), result.newDevice());
            }
            case Packet.SetDevice d -> {
                Database.User u = me();
                try {
                    if (!services.accounts().deviceExists(u, d.deviceId())) {
                        throw new ApiException(ErrorCode.INVALID_REQUEST, "This computer is not one of the account's devices.");
                    }
                    services.accounts().deviceSeen(u, d.deviceId(), d.name());
                } catch (java.sql.SQLException e) {
                    throw new ApiException(ErrorCode.INTERNAL, "Server storage error.");
                }
                deviceId = d.deviceId();
                multiDevice = true;
                yield new Packet.Ok();
            }
            case Packet.FetchDeviceBundles f -> services.messages().deviceBundles(me(), deviceId, f.username());
            case Packet.SendToDevices s -> {
                services.metrics().envelopes.increment();
                yield services.messages().sendToDevices(me(), deviceId, s.recipient(), s.copies());
            }
            case Packet.ListDevices l -> {
                Database.User u = me();
                List<Packet.DeviceInfo> list = new java.util.ArrayList<>();
                try {
                    for (Database.Device dev : services.accounts().devices(u)) {
                        boolean online = services.presence().connections(u.id()).stream().anyMatch(c -> c.deviceId() == dev.id());
                        list.add(new Packet.DeviceInfo(dev.id(), dev.name(), dev.createdAt(), dev.lastSeen(), online, dev.id() == deviceId));
                    }
                } catch (java.sql.SQLException e) {
                    throw new ApiException(ErrorCode.INTERNAL, "Server storage error.");
                }
                yield new Packet.DeviceList(list);
            }
            case Packet.RemoveDevice r -> {
                Database.User u = me();
                if (r.deviceId() == deviceId) throw new ApiException(ErrorCode.INVALID_REQUEST, "Sign out on that computer instead.");
                try {
                    services.accounts().removeDevice(u, r.deviceId());
                } catch (java.sql.SQLException e) {
                    throw new ApiException(ErrorCode.INTERNAL, "Server storage error.");
                }
                for (ClientConnection c : services.presence().connections(u.id())) if (c.deviceId() == r.deviceId()) c.removed();
                yield new Packet.Ok();
            }
            case Packet.LinkLogin l -> {
                requireLoggedOut();
                Database.User u = services.accounts().checkForLink(l.username(), l.password(), l.code(), address);
                yield new Packet.LinkPending(services.links().request(u, l.name(), l.ephemeral(), this));
            }
            case Packet.LinkApprove a -> {
                services.links().approve(me(), a.linkId(), a.ephemeral(), a.sealed());
                yield new Packet.Ok();
            }
            case Packet.LinkDeny d -> {
                services.links().deny(me(), d.linkId());
                yield new Packet.Ok();
            }
            case Packet.SetUpTwoFactor s -> new Packet.TwoFactorSecret(services.accounts().setUpTwoFactor(me()));
            case Packet.EnableTwoFactor e -> new Packet.RecoveryCodes(services.accounts().enableTwoFactor(me(), e.code()));
            case Packet.DisableTwoFactor d -> {
                services.accounts().disableTwoFactor(me(), d.password());
                yield new Packet.Ok();
            }
            case Packet.TwoFactorStatus t -> {
                int[] state = services.accounts().twoFactorState(me());
                yield new Packet.TwoFactorState(state[0] == 1, state[1]);
            }
            case Packet.ChangePassword c -> {
                services.accounts().changePassword(me(), c.oldPassword(), c.newPassword());
                yield new Packet.Ok();
            }
            case Packet.SendFriendRequest s -> new Packet.FriendRequestResult(services.friends().request(me(), s.username()));
            case Packet.RespondFriendRequest r -> {
                services.friends().respond(me(), r.username(), r.accept());
                yield new Packet.Ok();
            }
            case Packet.RemoveFriend r -> {
                long other;
                try {
                    other = services.friends().friendId(me(), r.username());
                } catch (java.sql.SQLException e) {
                    other = 0;
                }
                services.friends().remove(me(), r.username());
                if (other != 0) services.relay().forgetPair(me().id(), other); // no more relaying between them
                yield new Packet.Ok();
            }
            case Packet.ListFriends l -> services.friends().list(me());
            case Packet.UploadKeys k -> {
                services.messages().uploadKeys(me(), deviceId, multiDevice, session.peerIdentityKey(), k);
                yield new Packet.Ok();
            }
            case Packet.UploadPrekeys u -> {
                services.messages().uploadPrekeys(me(), deviceId, u.prekeys());
                yield new Packet.Ok();
            }
            case Packet.KeyStatus k -> services.messages().status(me(), deviceId);
            case Packet.FetchBundle f -> new Packet.Bundle(services.messages().bundle(me(), f.username()));
            case Packet.SendEnvelope s -> {
                services.metrics().envelopes.increment();
                yield services.messages().send(me(), s.recipient(), s.body());
            }
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
            case Packet.SendGroupEnvelope s -> {
                services.metrics().groupEnvelopes.increment();
                yield services.messages().sendToGroup(me(), deviceId, s.groupId(), s.body());
            }
            case Packet.AckEnvelopes a -> {
                services.messages().ack(me(), deviceId, a.ids());
                yield new Packet.Ok();
            }
            case Packet.UploadStart u -> services.attachments().start(me(), u.size(), u.recipient(), u.groupId());
            case Packet.UploadChunk u -> {
                services.attachments().chunk(me(), u.attachmentId(), u.offset(), u.data());
                services.metrics().attachmentBytes.add(u.data().length);
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
