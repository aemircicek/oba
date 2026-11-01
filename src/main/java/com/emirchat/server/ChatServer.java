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

/**
 * The secure-chat server: accounts, and (in later stages) friends, groups and an offline queue
 * of end-to-end encrypted envelopes it cannot read. One virtual thread per connection.
 */
public final class ChatServer implements AutoCloseable {
    public static final int DEFAULT_PORT = 5056;

    /** @param pbkdf2Iterations lower only in tests; production uses {@link #defaultConfig}. */
    public record Config(Path dataDir, String bindAddress, int port, int pbkdf2Iterations) {}

    public static Config defaultConfig(Path dataDir, String bindAddress, int port) {
        return new Config(dataDir, bindAddress, port, PasswordHasher.DEFAULT_ITERATIONS);
    }

    private final Identity identity;
    private final Database db;
    private final AccountService accounts;
    private final ServerSocket listener;
    private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean closed;

    public ChatServer(Config config) throws IOException {
        Files.createDirectories(config.dataDir());
        this.identity = Identity.loadOrCreate(config.dataDir().resolve("identity"));
        try {
            this.db = new Database(config.dataDir().resolve("server.db"));
        } catch (SQLException e) {
            throw new IOException("Could not open the database: " + e.getMessage(), e);
        }
        this.accounts = new AccountService(db, config.pbkdf2Iterations(), Clock.systemUTC());
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
                connections.submit(new ClientConnection(socket, identity, accounts)::run);
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
        try {
            db.close();
        } catch (SQLException | NullPointerException ignored) {
            // shutting down
        }
    }
}
