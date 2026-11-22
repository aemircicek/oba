package com.emirchat.server;

import com.emirchat.crypto.Identity;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The secure-chat server: accounts, and (in later stages) friends, groups and an offline queue
 * of end-to-end encrypted envelopes it cannot read. One virtual thread per connection.
 */
public final class ChatServer implements AutoCloseable {
    public static final int DEFAULT_PORT = 5056;
    /** How often voice rooms are checked for people who are no longer members. */
    static final long VOICE_SWEEP_MS = 2_000;

    /** @param pbkdf2Iterations lower only in tests; production uses {@link #defaultConfig}. */
    public record Config(Path dataDir, String bindAddress, int port, int pbkdf2Iterations) {}

    public static Config defaultConfig(Path dataDir, String bindAddress, int port) {
        return new Config(dataDir, bindAddress, port, PasswordHasher.DEFAULT_ITERATIONS);
    }

    private final Identity identity;
    private final ServerMetrics metrics = new ServerMetrics();
    private final UdpRelay relay = new UdpRelay(metrics);
    private final Database db;
    private final ClientConnection.Services services;
    private final ServerSocket listener;
    private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "server-maintenance");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;
    /** The UDP address service for direct calls, or null if its port could not be opened. */
    private AddressDiscovery discovery;
    /** Open connections per address key (see {@link Addresses}); local connections are not counted. */
    private final Map<String, Integer> perAddress = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger open = new java.util.concurrent.atomic.AtomicInteger();
    /** One network (a home, a campus behind one address) may hold this many connections. */
    static final int MAX_CONNECTIONS_PER_ADDRESS = 64;
    /** All connections together; each costs a little memory even when idle. */
    static final int MAX_CONNECTIONS = 8192;

    public ChatServer(Config config) throws IOException {
        Files.createDirectories(config.dataDir());
        ownerOnly(config.dataDir()); // password hashes, queued envelopes and the server key live here
        this.identity = Identity.loadOrCreate(config.dataDir().resolve("identity"));
        try {
            this.db = new Database(config.dataDir().resolve("server.db"));
        } catch (SQLException e) {
            throw new IOException("Could not open the database: " + e.getMessage(), e);
        }
        Clock clock = Clock.systemUTC();
        Presence presence = new Presence();
        MessageService messages = new MessageService(db, presence, clock);
        AttachmentService attachments = new AttachmentService(db, messages, config.dataDir().resolve("attachments"), clock);
        GroupService groups = new GroupService(db, presence);
        VoiceRooms voice = new VoiceRooms(groups, presence);
        maintenance.scheduleWithFixedDelay(() -> {
            try {
                voice.sweep();
            } catch (RuntimeException ignored) {
                // try again next time
            }
        }, VOICE_SWEEP_MS, VOICE_SWEEP_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        this.services = new ClientConnection.Services(identity,
                new AccountService(db, config.pbkdf2Iterations(), clock),
                new FriendService(db, presence, clock), messages,
                groups, attachments, presence, voice, metrics, relay);
        metrics.gauges(open::get, presence::onlineCount, voice::activeRooms, voice::seated, relay::sessions);
        maintenance.scheduleWithFixedDelay(relay::sweep, 30, 30, TimeUnit.SECONDS);
        attachments.cleanUp();
        maintenance.scheduleAtFixedRate(attachments::cleanUp, 1, 1, TimeUnit.HOURS);
        // Plain TCP by design: ClientConnection runs the authenticated handshake on every socket.
        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        this.listener = new ServerSocket();
        listener.setReuseAddress(true);
        try {
            listener.bind(new InetSocketAddress(config.bindAddress(), config.port()));
        } catch (IOException e) {
            close();
            throw new IOException("Could not listen on port " + config.port() + ": " + e.getMessage(), e);
        }
        try {
            discovery = new AddressDiscovery(config.bindAddress(), listener.getLocalPort(), relay);
        } catch (java.net.SocketException e) {
            // calls still work, only through the server
            System.err.println("warning: UDP port " + listener.getLocalPort() + " is not available, so calls cannot"
                    + " go directly between users: " + e.getMessage());
        }
    }

    public int port() {
        return listener.getLocalPort();
    }

    /** The UDP port of the address service (the TCP port), or -1 if it is not running. */
    int discoveryPort() {
        return discovery == null ? -1 : discovery.port();
    }

    /** What the server is doing, for {@code --metrics}. */
    public ServerMetrics metrics() {
        return metrics;
    }

    public String fingerprint() {
        return identity.fingerprint();
    }

    /** Starts accepting connections on a background thread. */
    public ChatServer start() {
        Thread acceptor = new Thread(this::acceptLoop, "server-acceptor");
        acceptor.setDaemon(false);
        acceptor.start();
        return this;
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = listener.accept();
                String key = Addresses.local(socket.getInetAddress()) ? null : Addresses.key(socket.getInetAddress());
                if (!admit(key)) {
                    metrics.refusedConnections.increment();
                    socket.close(); // over a limit: refuse before spending a handshake on it
                    continue;
                }
                try {
                    connections.submit(() -> {
                        try {
                            new ClientConnection(socket, services).run();
                        } finally {
                            release(key);
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    release(key);
                    socket.close(); // shutting down
                }
            } catch (IOException e) {
                if (!closed) System.err.println("accept failed: " + e.getMessage());
            }
        }
    }

    private boolean admit(String key) {
        if (open.incrementAndGet() > MAX_CONNECTIONS) {
            open.decrementAndGet();
            return false;
        }
        if (key == null) return true;
        boolean[] ok = {true};
        perAddress.compute(key, (k, n) -> {
            int now = n == null ? 0 : n;
            if (now >= MAX_CONNECTIONS_PER_ADDRESS) {
                ok[0] = false;
                return n;
            }
            return now + 1;
        });
        if (!ok[0]) open.decrementAndGet();
        return ok[0];
    }

    private void release(String key) {
        open.decrementAndGet();
        if (key != null) perAddress.computeIfPresent(key, (k, n) -> n <= 1 ? null : n - 1);
    }

    /** Open connections right now (for tests). */
    int openConnections() {
        return open.get();
    }

    private static void ownerOnly(Path dir) {
        try {
            if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            }
        } catch (IOException | UnsupportedOperationException e) {
            System.err.println("warning: could not make " + dir + " private to this user: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            listener.close();
        } catch (IOException ignored) {
            // shutting down
        }
        connections.shutdownNow();
        maintenance.shutdownNow();
        metrics.close();
        if (discovery != null) discovery.close();
        try {
            db.close();
        } catch (SQLException | NullPointerException ignored) {
            // shutting down
        }
    }
}
