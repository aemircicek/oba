package com.emirchat.server;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
import com.emirchat.crypto.Identity;
import com.emirchat.protocol.FrameIO;
import com.emirchat.protocol.Handshake;
import com.emirchat.protocol.Session;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** Serves one client: handshake, then a request/response loop. */
final class ClientConnection {
    /** Unauthenticated connections must log in quickly; this bounds idle half-open sockets. */
    private static final int PRE_AUTH_TIMEOUT_MS = 60_000;
    private static final String SERVER_NAME = "server";

    private final Socket socket;
    private final Identity identity;
    private final AccountService accounts;
    private final String address;
    private Session session;
    private Database.User user;

    ClientConnection(Socket socket, Identity identity, AccountService accounts) {
        this.socket = socket;
        this.identity = identity;
        this.accounts = accounts;
        this.address = socket.getInetAddress().getHostAddress();
    }

    void run() {
        try (socket) {
            socket.setSoTimeout(PRE_AUTH_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            FrameIO io = new FrameIO(socket.getInputStream(), socket.getOutputStream());
            // Clients are authenticated by password after the handshake; any device key may connect.
            session = Handshake.asServer(io, identity, SERVER_NAME, (name, fp) -> {});
            while (true) {
                Packet.Frame frame = Packet.decode(session.channel().receive());
                Packet response;
                try {
                    response = handle(frame.packet());
                } catch (ApiException e) {
                    response = new Packet.Error(e.code(), e.getMessage());
                }
                session.channel().send(Packet.encode(frame.requestId(), response));
            }
        } catch (SocketTimeoutException e) {
            // idle unauthenticated client
        } catch (IOException | SecurityException | com.emirchat.trust.PeerRejectedException e) {
            // client went away or misbehaved; nothing to report to it
        }
    }

    private Packet handle(Packet packet) throws ApiException, IOException {
        return switch (packet) {
            case Packet.Ping p -> new Packet.Pong();
            case Packet.Register r -> {
                requireLoggedOut();
                user = accounts.register(r.username(), r.password(), session.peerIdentityKey());
                loggedIn();
                yield new Packet.LoggedIn(user.username(), false);
            }
            case Packet.Login l -> {
                requireLoggedOut();
                AccountService.LoginResult result = accounts.login(l.username(), l.password(),
                        session.peerIdentityKey(), address);
                user = result.user();
                loggedIn();
                yield new Packet.LoggedIn(user.username(), result.newDevice());
            }
            default -> throw new ApiException(ErrorCode.PROTOCOL, "Unexpected packet from client");
        };
    }

    private void requireLoggedOut() throws ApiException {
        if (user != null) throw new ApiException(ErrorCode.ALREADY_LOGGED_IN, "Already logged in.");
    }

    private void loggedIn() throws IOException {
        socket.setSoTimeout(0); // authenticated sessions may stay idle
    }
}
