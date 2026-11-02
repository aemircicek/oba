package com.emirchat.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.function.BiConsumer;

/**
 * Keeps one Scorpion window per data folder. The first instance holds a lock file and listens on
 * a loopback port; a second start (from the Start menu, or by double-clicking a backup file)
 * hands its request to the first one and exits. The port and a random token are in an owner-only
 * file, so other users on the computer cannot drive the app.
 */
final class SingleInstance implements AutoCloseable {
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final ServerSocket server;
    private final String token;
    private volatile BiConsumer<String, String> handler = (command, argument) -> {};

    private SingleInstance(FileChannel lockChannel, FileLock lock, ServerSocket server, String token) {
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.server = server;
        this.token = token;
    }

    /**
     * @return this process's instance, or null if another one is running (it was asked to
     *         {@code command} {@code argument}, for example "show" or "open" a file)
     */
    static SingleInstance acquire(Path home, String command, String argument) throws IOException {
        Files.createDirectories(home);
        FileChannel channel = FileChannel.open(home.resolve("app.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (java.nio.channels.OverlappingFileLockException e) {
            lock = null; // held by this same process (only happens in tests)
        }
        if (lock == null) {
            channel.close();
            if (sendToRunning(home, command, argument)) return null;
            throw new IOException("Scorpion is already running but does not answer.");
        }
        ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        byte[] secret = new byte[16];
        new SecureRandom().nextBytes(secret);
        String token = HexFormat.of().formatHex(secret);
        Path portFile = home.resolve("app.port");
        Files.writeString(portFile, server.getLocalPort() + " " + token);
        ownerOnly(portFile);
        SingleInstance instance = new SingleInstance(channel, lock, server, token);
        Thread t = new Thread(instance::serve, "single-instance");
        t.setDaemon(true);
        t.start();
        return instance;
    }

    /** What to do with requests from later starts; called on this class's own thread. */
    void onRequest(BiConsumer<String, String> handler) {
        this.handler = handler;
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(2000);
                String line = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
                if (line == null) continue;
                String[] parts = line.split(" ", 3);
                if (parts.length < 2 || !MessageDigest.isEqual(parts[0].getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8))) {
                    continue;
                }
                handler.accept(parts[1], parts.length > 2 ? parts[2] : "");
            } catch (IOException e) {
                // a bad or closed connection: keep serving
            }
        }
    }

    private static boolean sendToRunning(Path home, String command, String argument) {
        try {
            String[] portAndToken = Files.readString(home.resolve("app.port")).strip().split(" ");
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(portAndToken[0]));
                 OutputStream out = s.getOutputStream()) {
                out.write((portAndToken[1] + " " + command + (argument == null ? "" : " " + argument) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void ownerOnly(Path file) {
        try {
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException ignored) {
            // the data folder itself is private to the user
        }
    }

    @Override
    public void close() {
        try {
            server.close();
            lock.release();
            lockChannel.close();
        } catch (IOException ignored) {
            // exiting anyway
        }
    }
}
