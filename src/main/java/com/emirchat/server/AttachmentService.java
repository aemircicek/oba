package com.emirchat.server;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
import com.emirchat.e2e.Attachments;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Storage for encrypted attachment blobs. The server cannot read them (the key is only in the
 * end-to-end encrypted message) but it does decide who may download them: the uploader, and the
 * one audience named at upload time — a friend or group partner for a direct message, or the
 * current members of a group. Blobs are deleted after {@link #RETENTION}.
 *
 * <p>Unknown, unfinished and forbidden attachments all give the same error, so ids cannot be
 * probed. Ids are 128-bit random values chosen here.
 */
final class AttachmentService {
    static final Duration RETENTION = Duration.ofDays(30);
    static final Duration UNFINISHED_RETENTION = Duration.ofHours(1);
    static final long MAX_BYTES_PER_USER = 250L * 1024 * 1024;
    static final int MAX_OPEN_UPLOADS = 3;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Database db;
    private final MessageService messages;
    private final Path dir;
    private final Clock clock;

    AttachmentService(Database db, MessageService messages, Path dir, Clock clock) throws IOException {
        this.db = db;
        this.messages = messages;
        this.dir = Files.createDirectories(dir);
        this.clock = clock;
    }

    Packet.UploadStarted start(Database.User me, long size, String recipient, long groupId) throws ApiException {
        if (size < Attachments.OVERHEAD || size > Attachments.MAX_CIPHERTEXT_BYTES) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Files can be at most " + Attachments.MAX_FILE_BYTES / (1024 * 1024) + " MB.");
        }
        boolean direct = recipient != null && !recipient.isEmpty();
        if (direct == (groupId != 0)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "An attachment is for one person or one group.");
        }
        try {
            long recipientId = 0;
            if (direct) {
                recipientId = messages.friend(me, recipient).id();
            } else if (!db.isMember(groupId, me.id())) {
                throw new ApiException(ErrorCode.GROUP_NOT_FOUND, "You are not in that group.");
            }
            if (db.openUploads(me.id()) >= MAX_OPEN_UPLOADS) {
                throw new ApiException(ErrorCode.LIMIT_REACHED, "Too many uploads at once.");
            }
            if (db.attachmentBytes(me.id()) + size > MAX_BYTES_PER_USER) {
                throw new ApiException(ErrorCode.LIMIT_REACHED, "Your storage on this server is full; files expire after "
                        + RETENTION.toDays() + " days.");
            }
            byte[] id = new byte[Attachments.ID_LENGTH];
            RANDOM.nextBytes(id);
            Files.write(file(id), new byte[0], StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            db.createAttachment(id, me.id(), recipientId, groupId, size, clock.millis());
            return new Packet.UploadStarted(id);
        } catch (SQLException | IOException e) {
            throw storage();
        }
    }

    synchronized void chunk(Database.User me, byte[] id, long offset, byte[] data) throws ApiException {
        try {
            Database.Attachment a = db.attachment(id).orElseThrow(AttachmentService::notFound);
            if (a.uploaderId() != me.id() || a.complete()) throw notFound();
            if (offset != a.received() || data.length == 0 || a.received() + data.length > a.size()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Upload chunk out of order or too large.");
            }
            try (FileChannel channel = FileChannel.open(file(id), StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                long position = offset;
                while (buffer.hasRemaining()) position += channel.write(buffer, position);
            }
            db.setReceived(id, a.received() + data.length);
        } catch (SQLException | IOException e) {
            throw storage();
        }
    }

    synchronized void finish(Database.User me, byte[] id) throws ApiException {
        try {
            Database.Attachment a = db.attachment(id).orElseThrow(AttachmentService::notFound);
            if (a.uploaderId() != me.id() || a.complete()) throw notFound();
            if (a.received() != a.size()) throw new ApiException(ErrorCode.INVALID_REQUEST, "Upload is incomplete.");
            db.markComplete(id);
        } catch (SQLException e) {
            throw storage();
        }
    }

    Packet.AttachmentChunk fetch(Database.User me, byte[] id, long offset) throws ApiException {
        try {
            Optional<Database.Attachment> found = db.attachment(id);
            if (found.isEmpty() || !found.get().complete() || !mayRead(me, found.get())) throw notFound();
            Database.Attachment a = found.get();
            if (offset < 0 || offset >= a.size()) throw new ApiException(ErrorCode.INVALID_REQUEST, "Offset out of range.");
            int length = (int) Math.min(Packet.MAX_CHUNK_BYTES, a.size() - offset);
            ByteBuffer buffer = ByteBuffer.allocate(length);
            try (FileChannel channel = FileChannel.open(file(id), StandardOpenOption.READ)) {
                long position = offset;
                while (buffer.hasRemaining()) {
                    int n = channel.read(buffer, position);
                    if (n < 0) throw new IOException("Attachment file is shorter than recorded");
                    position += n;
                }
            }
            return new Packet.AttachmentChunk(a.size(), buffer.array());
        } catch (SQLException | IOException e) {
            throw storage();
        }
    }

    private boolean mayRead(Database.User me, Database.Attachment a) throws SQLException {
        if (a.uploaderId() == me.id()) return true;
        if (a.groupId() != 0) return db.isMember(a.groupId(), me.id()); // current members only
        return a.recipientId() == me.id();
    }

    /** Deletes expired blobs and abandoned uploads; returns how many. */
    int cleanUp() {
        long now = clock.millis();
        int deleted = 0;
        try {
            for (byte[] id : db.expiredAttachments(now - RETENTION.toMillis(), now - UNFINISHED_RETENTION.toMillis())) {
                Files.deleteIfExists(file(id));
                db.deleteAttachment(id);
                deleted++;
            }
        } catch (SQLException | IOException e) {
            System.err.println("attachment clean-up failed: " + e.getMessage());
        }
        return deleted;
    }

    private Path file(byte[] id) {
        return dir.resolve(Attachments.hex(id)); // server-chosen hex id: no path tricks possible
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.ATTACHMENT_NOT_FOUND, "That file is not available (it may have expired).");
    }

    private static ApiException storage() {
        return new ApiException(ErrorCode.INTERNAL, "Server storage error.");
    }
}
