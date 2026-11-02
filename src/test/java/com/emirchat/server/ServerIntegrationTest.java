package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import com.emirchat.trust.KeyChangedException;
import com.emirchat.trust.KnownPeers;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A real server on loopback, real clients, real handshakes. */
class ServerIntegrationTest {
    private static final int FAST_ITERATIONS = 1_000; // production uses 600,000

    @TempDir Path dir;
    private ChatServer server;
    private KnownPeers knownServers;

    @BeforeEach
    void start() throws Exception {
        server = new ChatServer(new ChatServer.Config(dir.resolve("server"), "127.0.0.1", 0, FAST_ITERATIONS)).start();
        knownServers = new KnownPeers(dir.resolve("known_servers"), (n, fp) -> true);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private ServerConnection connect(Identity device) throws Exception {
        return ServerConnection.open("127.0.0.1", server.port(), device, knownServers);
    }

    @Test
    void registerThenLogin() throws Exception {
        Identity laptop = Identity.generate();
        try (ServerConnection c = connect(laptop)) {
            assertEquals(server.fingerprint(), c.serverFingerprint());
            Packet.LoggedIn r = c.register("Emir", "correct horse battery");
            assertEquals("emir", r.username(), "usernames are normalised to lowercase");
            assertFalse(r.newDevice());
            ApiException again = assertThrows(ApiException.class, () -> c.login("emir", "correct horse battery"));
            assertEquals(ErrorCode.ALREADY_LOGGED_IN, again.code());
        }
        try (ServerConnection c = connect(laptop)) {
            Packet.LoggedIn l = c.login("emir", "correct horse battery");
            assertEquals("emir", l.username());
            assertFalse(l.newDevice(), "same device key");
        }
    }

    @Test
    void loginFromAnotherDeviceIsFlagged() throws Exception {
        try (ServerConnection c = connect(Identity.generate())) {
            c.register("emir", "correct horse battery");
        }
        try (ServerConnection c = connect(Identity.generate())) {
            assertTrue(c.login("emir", "correct horse battery").newDevice());
        }
    }

    @Test
    void registrationRules() throws Exception {
        try (ServerConnection c = connect(Identity.generate())) {
            c.register("efe", "a strong password");
        }
        try (ServerConnection c = connect(Identity.generate())) {
            assertEquals(ErrorCode.USERNAME_TAKEN,
                    assertThrows(ApiException.class, () -> c.register("EFE", "another password")).code());
            assertEquals(ErrorCode.INVALID_USERNAME,
                    assertThrows(ApiException.class, () -> c.register("e", "a strong password")).code());
            assertEquals(ErrorCode.INVALID_USERNAME,
                    assertThrows(ApiException.class, () -> c.register("emir çiçek", "a strong password")).code());
            assertEquals(ErrorCode.WEAK_PASSWORD,
                    assertThrows(ApiException.class, () -> c.register("ayse", "short")).code());
            assertEquals(ErrorCode.WEAK_PASSWORD,
                    assertThrows(ApiException.class, () -> c.register("ayse", "ayse12345")).code());
        }
    }

    @Test
    void wrongPasswordAndUnknownUserLookIdentical() throws Exception {
        try (ServerConnection c = connect(Identity.generate())) {
            c.register("emir", "correct horse battery");
        }
        try (ServerConnection c = connect(Identity.generate())) {
            ApiException wrong = assertThrows(ApiException.class, () -> c.login("emir", "wrong password!"));
            ApiException unknown = assertThrows(ApiException.class, () -> c.login("nobody", "wrong password!"));
            assertEquals(ErrorCode.BAD_CREDENTIALS, wrong.code());
            assertEquals(wrong.code(), unknown.code());
            assertEquals(wrong.getMessage(), unknown.getMessage(), "no username enumeration via messages");
        }
    }

    @Test
    void bruteForceIsRateLimited() throws Exception {
        try (ServerConnection c = connect(Identity.generate())) {
            c.register("emir", "correct horse battery");
        }
        try (ServerConnection c = connect(Identity.generate())) {
            for (int i = 0; i < 5; i++) {
                assertEquals(ErrorCode.BAD_CREDENTIALS,
                        assertThrows(ApiException.class, () -> c.login("emir", "guess-" + System.nanoTime())).code());
            }
            ApiException blocked = assertThrows(ApiException.class, () -> c.login("emir", "correct horse battery"));
            assertEquals(ErrorCode.RATE_LIMITED, blocked.code(), "even the right password is refused while blocked");
        }
    }

    @Test
    void impersonatedServerIsDetected() throws Exception {
        try (ServerConnection c = connect(Identity.generate())) {
            c.register("emir", "correct horse battery"); // pins the real server's key
        }
        int port = server.port();
        server.close();
        // An attacker takes over the address with a different key.
        server = new ChatServer(new ChatServer.Config(dir.resolve("evil"), "127.0.0.1", port, FAST_ITERATIONS)).start();
        assertThrows(KeyChangedException.class, () -> connect(Identity.generate()));
    }

    @Test
    void accountsSurviveRestart() throws Exception {
        try (ServerConnection c = connect(Identity.generate())) {
            c.register("emir", "correct horse battery");
        }
        int port = server.port();
        server.close();
        server = new ChatServer(new ChatServer.Config(dir.resolve("server"), "127.0.0.1", port, FAST_ITERATIONS)).start();
        try (ServerConnection c = connect(Identity.generate())) {
            assertEquals("emir", c.login("emir", "correct horse battery").username());
        }
    }
}
