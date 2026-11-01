package com.emirchat.client;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
import com.emirchat.crypto.Identity;
import com.emirchat.net.Connector;
import com.emirchat.protocol.FrameIO;
import com.emirchat.protocol.Handshake;
import com.emirchat.protocol.Session;
import com.emirchat.trust.KnownPeers;
import com.emirchat.trust.PeerRejectedException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Client side of the client/server protocol: an authenticated encrypted connection to a
 * secure-chat server, with request/response matching and server pushes.
 *
 * <p>The server's identity key is pinned per {@code host:port} on first use (TOFU), exactly like
 * peers in direct mode, so a later impersonation of the server is detected.
 */
public final class ServerConnection implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final long CALL_TIMEOUT_SECONDS = 30;
    private static final String CLIENT_NAME = "client";

    private final Socket socket;
    private final Session session;
    private final String serverFingerprint;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<Packet>> pending = new ConcurrentHashMap<>();
    private volatile Consumer<Packet> pushListener = p -> {};
    private volatile Runnable onClosed = () -> {};
    private volatile boolean closed;

    private ServerConnection(Socket socket, Session session) {
        this.socket = socket;
        this.session = session;
        this.serverFingerprint = session.peerFingerprint();
    }

    /**
     * Connects and authenticates the server.
     *
     * @param knownServers pinned server keys; unknown servers go through its confirmation callback
     *                     with the name {@code host:port}
     */
    public static ServerConnection open(String host, int port, Identity device, KnownPeers knownServers)
            throws IOException, PeerRejectedException {
        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(Connector.HANDSHAKE_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            FrameIO io = new FrameIO(socket.getInputStream(), socket.getOutputStream());
            String serverKey = host + ":" + port;
            Session session = Handshake.asClient(io, device, CLIENT_NAME,
                    (name, fingerprint) -> knownServers.check(serverKey, fingerprint));
            socket.setSoTimeout(0);
            ServerConnection connection = new ServerConnection(socket, session);
            connection.startReader();
            return connection;
        } catch (IOException | PeerRejectedException | RuntimeException e) {
            Connector.closeQuietly(socket);
            throw e;
        }
    }

    public String serverFingerprint() {
        return serverFingerprint;
    }

    /** Server-initiated packets (incoming messages, presence...) are delivered here. */
    public void onPush(Consumer<Packet> listener) {
        this.pushListener = listener;
    }

    public void onClosed(Runnable listener) {
        this.onClosed = listener;
    }

    public CompletableFuture<Packet> request(Packet packet) {
        long id = nextId.getAndIncrement();
        CompletableFuture<Packet> result = new CompletableFuture<>();
        pending.put(id, result);
        try {
            session.channel().send(Packet.encode(id, packet));
        } catch (IOException | SecurityException e) {
            pending.remove(id);
            result.completeExceptionally(e);
        }
        return result;
    }

    /** Sends a request and waits for the answer; server errors become {@link ApiException}. */
    public Packet call(Packet packet) throws IOException, ApiException {
        try {
            Packet response = request(packet).get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (response instanceof Packet.Error error) throw new ApiException(error.code(), error.message());
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } catch (TimeoutException e) {
            throw new IOException("The server did not answer in time", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException(cause.getMessage(), cause);
        }
    }

    public Packet.LoggedIn register(String username, String password) throws IOException, ApiException {
        return expect(call(new Packet.Register(username, password)), Packet.LoggedIn.class);
    }

    public Packet.LoggedIn login(String username, String password) throws IOException, ApiException {
        return expect(call(new Packet.Login(username, password)), Packet.LoggedIn.class);
    }

    /** @return true if this made you friends at once (they had already asked you) */
    public boolean sendFriendRequest(String username) throws IOException, ApiException {
        return expect(call(new Packet.SendFriendRequest(username)), Packet.FriendRequestResult.class).nowFriends();
    }

    public void respondFriendRequest(String username, boolean accept) throws IOException, ApiException {
        expect(call(new Packet.RespondFriendRequest(username, accept)), Packet.Ok.class);
    }

    /** Unfriends, or cancels your own pending request. */
    public void removeFriend(String username) throws IOException, ApiException {
        expect(call(new Packet.RemoveFriend(username)), Packet.Ok.class);
    }

    public Packet.FriendList listFriends() throws IOException, ApiException {
        return expect(call(new Packet.ListFriends()), Packet.FriendList.class);
    }

    private static <T extends Packet> T expect(Packet response, Class<T> type) throws ApiException {
        if (!type.isInstance(response)) throw new ApiException(ErrorCode.PROTOCOL, "Unexpected response " + response);
        return type.cast(response);
    }

    private void startReader() {
        Thread reader = new Thread(() -> {
            try {
                while (!closed) {
                    Packet.Frame frame = Packet.decode(session.channel().receive());
                    if (frame.requestId() == 0) {
                        pushListener.accept(frame.packet());
                    } else {
                        CompletableFuture<Packet> waiting = pending.remove(frame.requestId());
                        if (waiting != null) waiting.complete(frame.packet());
                    }
                }
            } catch (IOException | SecurityException e) {
                failAll(e);
            } finally {
                close();
            }
        }, "server-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void failAll(Exception cause) {
        IOException error = new IOException("Connection to the server was lost", cause);
        pending.values().forEach(f -> f.completeExceptionally(error));
        pending.clear();
    }

    public boolean isOpen() {
        return !closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Connector.closeQuietly(socket);
        failAll(new IOException("closed"));
        onClosed.run();
    }
}
