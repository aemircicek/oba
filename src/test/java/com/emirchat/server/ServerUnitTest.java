package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
import java.net.ProtocolException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ServerUnitTest {
    /** A clock the test can move forward. */
    static final class TestClock extends Clock {
        long millis = 1_000_000;

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }

        @Override public long millis() { return millis; }
    }

    @Test
    void passwordHashesVerifyAndAreSalted() {
        PasswordHasher hasher = new PasswordHasher(1000);
        PasswordHasher.Hash a = hasher.hash("correct horse battery");
        PasswordHasher.Hash b = hasher.hash("correct horse battery");
        assertTrue(hasher.verify("correct horse battery", a));
        assertFalse(hasher.verify("correct horse batterY", a));
        assertFalse(Arrays.equals(a.salt(), b.salt()), "salts must be random");
        assertFalse(Arrays.equals(a.hash(), b.hash()), "same password, different salt => different hash");
        assertEquals(32, a.hash().length);
        assertEquals(1000, a.iterations());
    }

    @Test
    void productionIterationCountMeetsOwaspMinimum() {
        assertTrue(PasswordHasher.DEFAULT_ITERATIONS >= 600_000);
    }

    @Test
    void rateLimiterBlocksAfterMaxFailuresAndForgetsAfterWindow() {
        TestClock clock = new TestClock();
        RateLimiter limiter = new RateLimiter(3, Duration.ofMinutes(15), clock);
        for (int i = 0; i < 3; i++) {
            assertFalse(limiter.isBlocked("emir"));
            limiter.recordFailure("emir");
        }
        assertTrue(limiter.isBlocked("emir"));
        assertFalse(limiter.isBlocked("efe"), "limits are per key");
        clock.millis += Duration.ofMinutes(15).toMillis() + 1;
        assertFalse(limiter.isBlocked("emir"));
        limiter.recordFailure("emir");
        limiter.reset("emir");
        assertFalse(limiter.isBlocked("emir"));
    }

    @Test
    void packetsRoundTrip() throws ProtocolException {
        Packet[] packets = {
                new Packet.Register("emir", "s3cret-pass"), new Packet.Login("efe", "pässwörd"),
                new Packet.Ping(), new Packet.Ok(), new Packet.Pong(),
                new Packet.Error(ErrorCode.USERNAME_TAKEN, "taken"), new Packet.LoggedIn("emir", true),
                new Packet.UploadStart(4096, "efe", 0), new Packet.UploadStart(4096, "", 7),
                new Packet.UploadChunk(new byte[16], 32768, new byte[] {1, 2, 3}), new Packet.UploadFinish(new byte[16]),
                new Packet.FetchAttachment(new byte[16], 65536), new Packet.UploadStarted(new byte[16]),
                new Packet.AttachmentChunk(100_000, new byte[] {9, 8, 7}),
                new Packet.Error(ErrorCode.ATTACHMENT_NOT_FOUND, "gone")};
        long id = 1;
        for (Packet p : packets) {
            Packet.Frame frame = Packet.decode(Packet.encode(id, p));
            assertEquals(id, frame.requestId());
            assertEquals(p, frame.packet());
            id++;
        }
    }

    @Test
    void packetDecodingIsStrict() {
        byte[] ok = Packet.encode(7, new Packet.Ok());
        assertThrows(ProtocolException.class, () -> Packet.decode(Arrays.copyOf(ok, ok.length + 1)));
        byte[] unknown = ok.clone();
        unknown[0] = (byte) 250;
        assertThrows(ProtocolException.class, () -> Packet.decode(unknown));
        assertThrows(ProtocolException.class,
                () -> Packet.decode(Packet.encode(1, new Packet.Register("x".repeat(Packet.MAX_USERNAME_BYTES + 1), "p"))));
    }

    @Test
    void passwordsNeverAppearInToString() {
        assertFalse(new Packet.Login("emir", "hunter2hunter2").toString().contains("hunter2"));
        assertFalse(new Packet.Register("emir", "hunter2hunter2").toString().contains("hunter2"));
    }

    @Test
    void longErrorMessagesAreTruncatedNotRejected() throws ProtocolException {
        Packet.Error big = new Packet.Error(ErrorCode.INTERNAL, "é".repeat(1000));
        Packet.Error decoded = (Packet.Error) Packet.decode(Packet.encode(1, big)).packet();
        assertTrue(decoded.message().length() < big.message().length());
        assertNotEquals(0, decoded.message().length());
    }
}
