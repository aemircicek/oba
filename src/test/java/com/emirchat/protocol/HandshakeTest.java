package com.emirchat.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.crypto.Identity;
import com.emirchat.trust.KnownPeers;
import com.emirchat.trust.PeerRejectedException;
import com.emirchat.trust.TrustPolicy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HandshakeTest {
    private static final TrustPolicy TRUST_ALL = (name, fp) -> {};

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    private interface Side {
        Session run(FrameIO io) throws Exception;
    }

    private record Pair(Future<Session> client, Future<Session> server) {}

    /** Connects client and server over loopback TCP, optionally through a frame-tampering relay. */
    private Pair connect(Side client, Side server, int tamperServerFrame) throws IOException {
        ServerSocket serverListener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Future<Session> serverResult = pool.submit(() -> {
            try (serverListener; Socket s = serverListener.accept()) {
                s.setSoTimeout(5000);
                return server.run(new FrameIO(s.getInputStream(), s.getOutputStream()));
            }
        });

        int clientPort = serverListener.getLocalPort();
        if (tamperServerFrame > 0) {
            ServerSocket relay = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            clientPort = relay.getLocalPort();
            pool.submit(() -> {
                try (relay; Socket fromClient = relay.accept();
                     Socket toServer = new Socket(InetAddress.getLoopbackAddress(), serverListener.getLocalPort())) {
                    FrameIO c = new FrameIO(fromClient.getInputStream(), fromClient.getOutputStream());
                    FrameIO s = new FrameIO(toServer.getInputStream(), toServer.getOutputStream());
                    pool.submit(() -> pump(c, s, -1));
                    pump(s, c, tamperServerFrame);
                }
                return null;
            });
        }

        int port = clientPort;
        Future<Session> clientResult = pool.submit(() -> {
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
                s.setSoTimeout(5000);
                return client.run(new FrameIO(s.getInputStream(), s.getOutputStream()));
            }
        });
        return new Pair(clientResult, serverResult);
    }

    private static Void pump(FrameIO from, FrameIO to, int tamperIndex) throws IOException {
        AtomicInteger index = new AtomicInteger();
        while (true) {
            byte[] frame = from.read();
            if (index.incrementAndGet() == tamperIndex) frame[frame.length - 1] ^= 0x01;
            to.write(frame);
        }
    }

    private static Throwable rootCause(Future<?> f) {
        ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(10, TimeUnit.SECONDS));
        Throwable t = e.getCause();
        while (t instanceof RuntimeException && t.getCause() != null && !(t instanceof SecurityException)) {
            t = t.getCause();
        }
        return t;
    }

    @Test
    void mutualAuthenticationAndMatchingKeys() throws Exception {
        Identity alice = Identity.generate();
        Identity bob = Identity.generate();

        ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Future<Session> server = pool.submit(() -> {
            try (listener; Socket s = listener.accept()) {
                Session session = Handshake.asServer(new FrameIO(s.getInputStream(), s.getOutputStream()), bob, "bob", TRUST_ALL);
                assertEquals("ping", new String(session.channel().receive(), StandardCharsets.UTF_8));
                session.channel().send("pong".getBytes(StandardCharsets.UTF_8));
                return session;
            }
        });
        Future<Session> client = pool.submit(() -> {
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort())) {
                Session session = Handshake.asClient(new FrameIO(s.getInputStream(), s.getOutputStream()), alice, "alice", TRUST_ALL);
                session.channel().send("ping".getBytes(StandardCharsets.UTF_8));
                assertEquals("pong", new String(session.channel().receive(), StandardCharsets.UTF_8));
                return session;
            }
        });

        Session c = client.get(10, TimeUnit.SECONDS);
        Session s = server.get(10, TimeUnit.SECONDS);
        assertEquals("bob", c.peerName());
        assertEquals(bob.fingerprint(), c.peerFingerprint());
        assertEquals("alice", s.peerName());
        assertEquals(alice.fingerprint(), s.peerFingerprint());
    }

    @Test
    void clientRejectsImpersonatorViaKnownPeers(@TempDir Path dir) throws Exception {
        Identity realBob = Identity.generate();
        Identity mallory = Identity.generate();
        KnownPeers known = new KnownPeers(dir.resolve("known_peers"), (n, fp) -> true);
        known.check("bob", realBob.fingerprint()); // previously trusted

        Pair pair = connect(
                io -> Handshake.asClient(io, Identity.generate(), "alice", known),
                io -> Handshake.asServer(io, mallory, "bob", TRUST_ALL), 0);

        Throwable cause = rootCause(pair.client());
        assertInstanceOf(PeerRejectedException.class, cause);
        assertTrue(cause.getMessage().contains("HAS CHANGED"));
    }

    @Test
    void serverRejectsUnconfirmedClient() throws Exception {
        Pair pair = connect(
                io -> Handshake.asClient(io, Identity.generate(), "alice", TRUST_ALL),
                io -> Handshake.asServer(io, Identity.generate(), "bob",
                        (n, fp) -> { throw new PeerRejectedException("no"); }), 0);
        assertInstanceOf(PeerRejectedException.class, rootCause(pair.server()));
        // The client must not believe a session exists when the server refused it.
        Throwable clientFailure = rootCause(pair.client());
        assertInstanceOf(IOException.class, clientFailure);
        assertTrue(clientFailure.getMessage().contains("rejected"), clientFailure.getMessage());
    }

    @Test
    void tamperedServerHelloIsDetected() throws Exception {
        // Frame 1 from the server is its HELLO: flipping a bit of its nonce changes the transcript,
        // so the keys diverge and the encrypted AUTH message fails to authenticate.
        Pair pair = connect(
                io -> Handshake.asClient(io, Identity.generate(), "alice", TRUST_ALL),
                io -> Handshake.asServer(io, Identity.generate(), "bob", TRUST_ALL), 1);
        assertInstanceOf(SecurityException.class, rootCause(pair.client()));
    }

    @Test
    void tamperedServerAuthIsDetected() throws Exception {
        Pair pair = connect(
                io -> Handshake.asClient(io, Identity.generate(), "alice", TRUST_ALL),
                io -> Handshake.asServer(io, Identity.generate(), "bob", TRUST_ALL), 2);
        assertInstanceOf(SecurityException.class, rootCause(pair.client()));
    }

    @Test
    void rejectsInvalidNames() {
        assertThrows(IllegalArgumentException.class,
                () -> Handshake.asClient(null, Identity.generate(), "bad name!", TRUST_ALL));
        assertTrue(Handshake.isValidName("emir.cicek-1"));
    }
}
