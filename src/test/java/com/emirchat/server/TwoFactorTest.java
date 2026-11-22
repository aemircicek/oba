package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import com.emirchat.trust.KnownPeers;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Two-step login, recovery codes and changing the password. */
class TwoFactorTest {
    private static final String PASSWORD = "correct horse battery";
    @TempDir Path dir;
    private ChatServer server;
    private KnownPeers knownServers;

    @BeforeEach
    void start() throws Exception {
        server = new ChatServer(new ChatServer.Config(dir.resolve("server"), "127.0.0.1", 0, 1_000)).start();
        knownServers = new KnownPeers(dir.resolve("known_servers"), (n, fp) -> true);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private ServerConnection connect(Identity device) throws Exception {
        return ServerConnection.open("127.0.0.1", server.port(), device, knownServers);
    }

    private static String now(byte[] secret) {
        return Totp.code(secret, Totp.step(System.currentTimeMillis()));
    }

    private static String next(byte[] secret) {
        return Totp.code(secret, Totp.step(System.currentTimeMillis()) + 1);
    }

    @Test
    void theServerSaysItsVersionInTheHandshake() throws Exception {
        try (ServerConnection c = connect(Identity.generate())) {
            assertEquals(306, c.serverLevel());
            assertTrue(c.hasAccountSecurity());
        }
        assertEquals(0, levelOf("server"), "servers before 3.6");
        assertEquals(410, levelOf("server-4.10"));
    }

    private static int levelOf(String name) throws Exception {
        var m = ServerConnection.class.getDeclaredMethod("level", String.class);
        m.setAccessible(true);
        return (int) m.invoke(null, name);
    }

    @Test
    void aNewDeviceNeedsACodeTheBoundOneDoesNot() throws Exception {
        Identity laptop = Identity.generate();
        byte[] secret;
        List<String> recovery;
        try (ServerConnection c = connect(laptop)) {
            c.register("emir", PASSWORD);
            assertFalse(c.twoFactorState().enabled());
            secret = c.setUpTwoFactor();
            ApiException wrong = assertThrows(ApiException.class, () -> c.enableTwoFactor("000000".equals(now(secret)) ? "111111" : "000000"));
            assertEquals(ErrorCode.BAD_CODE, wrong.code());
            recovery = c.enableTwoFactor(now(secret));
            assertEquals(Totp.RECOVERY_CODES, recovery.size());
            assertTrue(c.twoFactorState().enabled());
        }
        try (ServerConnection c = connect(laptop)) {
            c.login("emir", PASSWORD); // the bound device: no code
        }
        Identity phone = Identity.generate();
        try (ServerConnection c = connect(phone)) {
            ApiException ask = assertThrows(ApiException.class, () -> c.login("emir", PASSWORD));
            assertEquals(ErrorCode.CODE_REQUIRED, ask.code());
            ApiException bad = assertThrows(ApiException.class, () -> c.login("emir", "wrong password", "123456"));
            assertEquals(ErrorCode.BAD_CREDENTIALS, bad.code(), "the password is checked first");
            c.login("emir", PASSWORD, recovery.get(0).toLowerCase());
        }
        try (ServerConnection c = connect(Identity.generate())) {
            ApiException reused = assertThrows(ApiException.class, () -> c.login("emir", PASSWORD, recovery.get(0)));
            assertEquals(ErrorCode.BAD_CODE, reused.code(), "a recovery code works once");
            c.login("emir", PASSWORD, next(secret)); // the code shown now was used to turn it on: the next one
            assertEquals(Totp.RECOVERY_CODES - 1, c.twoFactorState().recoveryLeft());
        }
        try (ServerConnection c = connect(Identity.generate())) {
            ApiException again = assertThrows(ApiException.class, () -> c.login("emir", PASSWORD, next(secret)));
            assertEquals(ErrorCode.BAD_CODE, again.code(), "the same code never works twice");
        }
    }

    @Test
    void turningItOffAndChangingThePasswordNeedThePassword() throws Exception {
        Identity laptop = Identity.generate();
        try (ServerConnection c = connect(laptop)) {
            c.register("emir", PASSWORD);
            byte[] secret = c.setUpTwoFactor();
            c.enableTwoFactor(now(secret));
            assertThrows(ApiException.class, () -> c.disableTwoFactor("not it"));
            c.disableTwoFactor(PASSWORD);
            assertFalse(c.twoFactorState().enabled());
            ApiException weak = assertThrows(ApiException.class, () -> c.changePassword(PASSWORD, "short"));
            assertEquals(ErrorCode.WEAK_PASSWORD, weak.code());
            assertThrows(ApiException.class, () -> c.changePassword("not it at all", "a brand new passphrase"));
            c.changePassword(PASSWORD, "a brand new passphrase");
        }
        try (ServerConnection c = connect(laptop)) {
            assertThrows(ApiException.class, () -> c.login("emir", PASSWORD));
        }
        try (ServerConnection c = connect(laptop)) {
            c.login("emir", "a brand new passphrase");
        }
    }

    @Test
    void codesFollowRfc6238() {
        // RFC 6238 appendix B, SHA-1, 8 digits there; the last six of 94287082 at T = 59 s
        byte[] secret = "12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals("287082", Totp.code(secret, 1));
        assertEquals(1, Totp.verify(secret, "287 082", 59_000, -1));
        assertEquals(-1, Totp.verify(secret, "287082", 59_000, 1), "used already");
        assertEquals(-1, Totp.verify(secret, "287082", 200_000, -1), "too old");
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Totp.base32(secret));
    }
}
