package com.emirchat.net;

import com.emirchat.crypto.Identity;
import com.emirchat.protocol.FrameIO;
import com.emirchat.protocol.Handshake;
import com.emirchat.protocol.Session;
import com.emirchat.trust.PeerRejectedException;
import com.emirchat.trust.TrustPolicy;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Opens a TCP connection (as host or as client) and runs the authenticated handshake on it.
 * Shared by the command-line and the desktop interface.
 *
 * <p>Plain TCP is used by design: every byte after the handshake is protected by the
 * authenticated AES-GCM channel in {@code com.emirchat.protocol}, so TLS would add nothing.
 */
public final class Connector {
    public static final int DEFAULT_PORT = 5055;
    public static final int HANDSHAKE_TIMEOUT_MS = 120_000;
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /** An authenticated session plus the socket carrying it (close the socket to end the session). */
    public record Established(Session session, Socket socket) {}

    /** Progress callbacks; all optional. Called on the connecting thread. */
    public interface Progress {
        /** The host is listening; closing {@code server} cancels the wait. */
        default void listening(ServerSocket server) {}

        /** A socket exists (connecting or connected); closing it cancels the attempt. */
        default void socket(Socket socket) {}
    }

    private Connector() {}

    public static Established host(Identity identity, String name, String bindAddress, int port,
                                   TrustPolicy trust, Progress progress) throws IOException, PeerRejectedException {
        Socket socket;
        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true); // avoids "Address already in use" right after a restart
            try {
                server.bind(new InetSocketAddress(bindAddress, port));
            } catch (BindException e) {
                throw new IOException("Port " + port + " is already in use by another program"
                        + " (on macOS, AirPlay Receiver occupies 5000 and 7000). Try another one, e.g. "
                        + (port == DEFAULT_PORT ? DEFAULT_PORT + 1 : DEFAULT_PORT), e);
            }
            progress.listening(server);
            socket = server.accept(); // one peer per session; the listening socket closes right after
        }
        progress.socket(socket);
        return handshake(socket, true, identity, name, trust);
    }

    public static Established connect(Identity identity, String name, String host, int port,
                                      TrustPolicy trust, Progress progress) throws IOException, PeerRejectedException {
        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        Socket socket = new Socket();
        progress.socket(socket);
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        } catch (IOException e) {
            closeQuietly(socket);
            throw new IOException("Could not reach " + host + ":" + port + " (" + e.getMessage() + ")", e);
        }
        return handshake(socket, false, identity, name, trust);
    }

    private static Established handshake(Socket socket, boolean isServer, Identity identity, String name,
                                          TrustPolicy trust)
            throws IOException, PeerRejectedException {
        boolean ok = false;
        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            FrameIO io = new FrameIO(socket.getInputStream(), socket.getOutputStream());
            Session session = isServer
                    ? Handshake.asServer(io, identity, name, trust)
                    : Handshake.asClient(io, identity, name, trust);
            socket.setSoTimeout(0);
            ok = true;
            return new Established(session, socket);
        } finally {
            if (!ok) closeQuietly(socket);
        }
    }

    public static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
