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
                    Presence presence) {}

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
            if (u != null && services.presence().remove(u.id(), this)) {
                services.friends().onlineChanged(u, false);
            }
        }
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
            case Packet.AckEnvelopes a -> {
                services.messages().ack(me(), a.ids());
                yield new Packet.Ok();
            }
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
