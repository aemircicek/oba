package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.crypto.Identity;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The limits that keep one client from locking others out or wearing the server down. */
class AbuseLimitsTest {
    private static final String PASSWORD = "correct horse battery";

    @TempDir Path dir;
    private Database db;
    private ServerUnitTest.TestClock clock;
    private AccountService accounts;

    @BeforeEach
    void open() throws Exception {
        db = new Database(dir.resolve("server.db"));
        clock = new ServerUnitTest.TestClock();
        accounts = new AccountService(db, 1_000, clock);
    }

    @AfterEach
    void close() throws Exception {
        db.close();
    }

    @Test
    void aStrangerFailingOnPurposeCannotLockTheOwnerOut() throws Exception {
        byte[] owner = Identity.generate().publicKeyBytes();
        byte[] stranger = Identity.generate().publicKeyBytes();
        accounts.register("emir", PASSWORD, owner);
        for (int i = 0; i < 5; i++) {
            String from = "203.0.113." + i; // a different address each time: only the username limit counts
            assertEquals(ErrorCode.BAD_CREDENTIALS,
                    assertThrows(ApiException.class, () -> accounts.login("emir", "wrong password", stranger, from)).code());
        }
        // the username is now locked for everyone else, even with the right password...
        assertEquals(ErrorCode.RATE_LIMITED,
                assertThrows(ApiException.class, () -> accounts.login("emir", PASSWORD, stranger, "198.51.100.1")).code());
        // ...but not for the device the account belongs to
        assertFalse(accounts.login("emir", PASSWORD, owner, "198.51.100.2").newDevice());
    }

    @Test
    void theOwnersDeviceIsStillLimitedByAddress() throws Exception {
        byte[] owner = Identity.generate().publicKeyBytes();
        accounts.register("emir", PASSWORD, owner);
        for (int i = 0; i < 20; i++) {
            assertEquals(ErrorCode.BAD_CREDENTIALS,
                    assertThrows(ApiException.class, () -> accounts.login("emir", "wrong password", owner, "203.0.113.9")).code());
        }
        assertEquals(ErrorCode.RATE_LIMITED,
                assertThrows(ApiException.class, () -> accounts.login("emir", PASSWORD, owner, "203.0.113.9")).code());
    }

    @Test
    void registrationsAreLimitedPerAddressAndComeBackAfterAnHour() throws Exception {
        byte[] key = Identity.generate().publicKeyBytes();
        for (int i = 0; i < AccountService.REGISTRATIONS_PER_HOUR; i++) {
            accounts.register("user" + i, PASSWORD, key, "203.0.113.7");
        }
        assertEquals(ErrorCode.RATE_LIMITED,
                assertThrows(ApiException.class, () -> accounts.register("onemore", PASSWORD, key, "203.0.113.7")).code());
        accounts.register("elsewhere", PASSWORD, key, "203.0.113.8"); // another address is not affected
        accounts.register("local", PASSWORD, key, null);              // nor is this computer itself
        clock.millis += Duration.ofHours(1).toMillis() + 1;
        accounts.register("onemore", PASSWORD, key, "203.0.113.7");
    }

    @Test
    void aTakenNameIsRefusedAndStillCountsAsAnAttempt() throws Exception {
        byte[] key = Identity.generate().publicKeyBytes();
        accounts.register("emir", PASSWORD, key, "203.0.113.7");
        for (int i = 1; i < AccountService.REGISTRATIONS_PER_HOUR; i++) {
            assertEquals(ErrorCode.USERNAME_TAKEN,
                    assertThrows(ApiException.class, () -> accounts.register("emir", PASSWORD, key, "203.0.113.7")).code());
        }
        assertEquals(ErrorCode.RATE_LIMITED,
                assertThrows(ApiException.class, () -> accounts.register("emir", PASSWORD, key, "203.0.113.7")).code());
    }

    @Test
    void theLimiterForgetsExpiredKeysInsteadOfGrowingForever() {
        RateLimiter limiter = new RateLimiter(3, Duration.ofMinutes(15), clock);
        for (int i = 0; i < RateLimiter.SWEEP_AT; i++) limiter.recordFailure("key" + i);
        assertEquals(RateLimiter.SWEEP_AT, limiter.size());
        clock.millis += Duration.ofMinutes(15).toMillis() + 1;
        limiter.recordFailure("new");
        assertEquals(1, limiter.size());
    }

    @Test
    void aBlockedKeyStaysBlockedHoweverOftenItTriesAgain() {
        RateLimiter limiter = new RateLimiter(3, Duration.ofMinutes(15), clock);
        for (int i = 0; i < 50; i++) limiter.recordFailure("emir");
        assertTrue(limiter.isBlocked("emir"));
        clock.millis += Duration.ofMinutes(15).toMillis() + 1;
        assertFalse(limiter.isBlocked("emir"));
    }

    @Test
    void ipv6IsLimitedByNetworkAndIpv4ByAddress() throws Exception {
        assertEquals(Addresses.key(InetAddress.getByName("2001:db8:1:2::1")),
                Addresses.key(InetAddress.getByName("2001:db8:1:2:ffff:ffff:ffff:ffff")), "one /64 is one customer");
        assertNotEquals(Addresses.key(InetAddress.getByName("2001:db8:1:2::1")),
                Addresses.key(InetAddress.getByName("2001:db8:1:3::1")));
        assertEquals("203.0.113.7", Addresses.key(InetAddress.getByName("203.0.113.7")));
        assertNotEquals(Addresses.key(InetAddress.getByName("203.0.113.7")), Addresses.key(InetAddress.getByName("203.0.113.8")));
        assertTrue(Addresses.local(InetAddress.getByName("127.0.0.1")));
        assertTrue(Addresses.local(InetAddress.getByName("::1")));
        assertFalse(Addresses.local(InetAddress.getByName("203.0.113.7")));
    }

    @Test
    void theServersDataFolderIsPrivateToItsUser() throws Exception {
        Path data = dir.resolve("data");
        Files.createDirectories(data);
        if (!data.getFileSystem().supportedFileAttributeViews().contains("posix")) return;
        Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rwxr-xr-x"));
        try (ChatServer server = new ChatServer(new ChatServer.Config(data, "127.0.0.1", 0, 1_000))) {
            assertEquals(64, server.fingerprint().length());
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(data)));
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(data.resolve("identity"))));
        }
    }

    @Test
    void connectionsAreCountedAndGivenBack() throws Exception {
        try (ChatServer server = new ChatServer(new ChatServer.Config(dir.resolve("data"), "127.0.0.1", 0, 1_000)).start()) {
            try (java.net.Socket a = new java.net.Socket("127.0.0.1", server.port());
                 java.net.Socket b = new java.net.Socket("127.0.0.1", server.port())) {
                await(() -> server.openConnections() == 2);
                assertTrue(a.isConnected() && b.isConnected());
            }
            await(() -> server.openConnections() == 0); // closed sockets free their place
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("condition not reached in time");
            Thread.sleep(10);
        }
    }
}
