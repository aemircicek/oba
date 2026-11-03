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

    public ChatServer(Config config) throws IOException {
        Files.createDirectories(config.dataDir());
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
                groups, attachments, presence, voice);
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
    }

    public int port() {
        return listener.getLocalPort();
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
                connections.submit(new ClientConnection(socket, services)::run);
            } catch (IOException e) {
                if (!closed) System.err.println("accept failed: " + e.getMessage());
            }
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
        try {
            db.close();
        } catch (SQLException | NullPointerException ignored) {
            // shutting down
        }
    }
}
