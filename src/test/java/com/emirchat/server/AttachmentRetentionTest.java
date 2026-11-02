package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Expiry of stored attachments, with a controllable clock. */
class AttachmentRetentionTest {
    @TempDir Path dir;
    private Database db;
    private AttachmentService attachments;
    private Database.User emir;
    private Database.User efe;
    private Instant now = Instant.parse("2026-11-02T10:00:00Z");

    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(java.time.ZoneId zone) { return this; }

        @Override public Instant instant() { return now; }
    };

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(dir.resolve("server.db"));
        PasswordHasher hasher = new PasswordHasher(1_000);
        emir = user("emir", hasher);
        efe = user("efe", hasher);
        db.makeFriends(emir.id(), efe.id());
        MessageService messages = new MessageService(db, new Presence(), clock);
        attachments = new AttachmentService(db, messages, dir.resolve("attachments"), clock);
    }

    private Database.User user(String name, PasswordHasher hasher) throws Exception {
        long id = db.createUser(name, hasher.hash("password-" + name), new byte[44]);
        return db.findUserById(id).orElseThrow();
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    private byte[] upload(int size, boolean finish) throws ApiException {
        byte[] id = attachments.start(emir, size, "efe", 0).attachmentId();
        attachments.chunk(emir, id, 0, new byte[size]);
        if (finish) attachments.finish(emir, id);
        return id;
    }

    @Test
    void filesAreDeletedAfterTheRetentionPeriod() throws Exception {
        byte[] id = upload(100, true);
        assertArrayEquals(new byte[100], attachments.fetch(efe, id, 0).data());

        now = now.plus(AttachmentService.RETENTION).minus(Duration.ofMinutes(1));
        assertEquals(0, attachments.cleanUp());
        now = now.plus(Duration.ofMinutes(2));
        assertEquals(1, attachments.cleanUp());

        assertEquals(ErrorCode.ATTACHMENT_NOT_FOUND, assertThrows(ApiException.class, () -> attachments.fetch(efe, id, 0)).code());
        try (var files = Files.list(dir.resolve("attachments"))) {
            assertEquals(0, files.count(), "the blob is gone from disk");
        }
    }

    @Test
    void abandonedUploadsAreDeletedSoonAndFreeTheirQuota() throws Exception {
        upload(100, false);
        assertEquals(100, db.attachmentBytes(emir.id()));
        now = now.plus(AttachmentService.UNFINISHED_RETENTION).plusSeconds(1);
        assertEquals(1, attachments.cleanUp());
        assertEquals(0, db.attachmentBytes(emir.id()));
        assertEquals(0, db.openUploads(emir.id()));
    }

    @Test
    void storageQuotaPerUser() throws Exception {
        long big = com.emirchat.e2e.Attachments.MAX_CIPHERTEXT_BYTES;
        int fits = (int) (AttachmentService.MAX_BYTES_PER_USER / big);
        for (int i = 0; i < fits; i++) {
            byte[] id = attachments.start(emir, big, "efe", 0).attachmentId();
            db.setReceived(id, big); // pretend it was uploaded, without writing 25 MB per file
            db.markComplete(id);
        }
        ApiException full = assertThrows(ApiException.class, () -> attachments.start(emir, big, "efe", 0));
        assertEquals(ErrorCode.LIMIT_REACHED, full.code());
        assertTrue(full.getMessage().contains("expire"));
        assertFalse(db.attachmentBytes(efe.id()) > 0, "the quota is per uploader");
    }
}
