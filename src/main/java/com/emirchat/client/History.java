package com.emirchat.client;

import com.emirchat.chat.FileMessage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Local message history, one SQLite file per account. Message texts are encrypted at rest with
 * AES-256-GCM under a key derived from the account password (PBKDF2, 600k iterations), so a copied
 * disk does not reveal conversations. Who/when metadata stays readable for sorting and unread counts.
 *
 * <p>Conversation keys are {@code "dm:<user>"} or {@code "g:<groupId>"}.
 */
public final class History implements AutoCloseable {
    public static final int SENT = 1;
    public static final int DELIVERED = 2;
    public static final int FAILED = 3;
    public static final int RECEIVED = 0;
    public static final int PENDING = 4;

    static final int KDF_ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** @param file the shared file for a file message (then {@code text} is its name), else null */
    public record Message(long id, String conversation, String sender, String text, long sentAt, boolean outgoing,
                          int status, long envelopeId, FileMessage file) {
        public Message(long id, String conversation, String sender, String text, long sentAt, boolean outgoing,
                       int status, long envelopeId) {
            this(id, conversation, sender, text, sentAt, outgoing, status, envelopeId, null);
        }
    }

    private static final int KIND_TEXT = 0;
    private static final int KIND_FILE = 1;

    public record Summary(String conversation, String lastSender, String lastText, long lastAt, int unread) {}

    private final Connection db;
    private final SecretKey key;

    private History(Connection db, SecretKey key) {
        this.db = db;
        this.key = key;
    }

    public static History open(Path file, String password) throws IOException {
        return open(file, password, KDF_ITERATIONS);
    }

    static History open(Path file, String password, int iterations) throws IOException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Connection db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement s = db.createStatement()) {
                s.execute("PRAGMA journal_mode = WAL");
                s.execute("PRAGMA secure_delete = ON");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v BLOB NOT NULL)""");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS messages (
                            id           INTEGER PRIMARY KEY AUTOINCREMENT,
                            conversation TEXT    NOT NULL,
                            sender       TEXT    NOT NULL,
                            body         BLOB    NOT NULL,
                            sent_at      INTEGER NOT NULL,
                            outgoing     INTEGER NOT NULL,
                            status       INTEGER NOT NULL,
                            envelope_id  INTEGER NOT NULL,
                            unread       INTEGER NOT NULL
                        )""");
                s.execute("CREATE INDEX IF NOT EXISTS messages_conv ON messages(conversation, id)");
                boolean hasKind = false;
                try (ResultSet cols = s.executeQuery("PRAGMA table_info(messages)")) {
                    while (cols.next()) hasKind |= "kind".equals(cols.getString("name"));
                }
                if (!hasKind) s.execute("ALTER TABLE messages ADD COLUMN kind INTEGER NOT NULL DEFAULT 0");
            }
            byte[] salt = meta(db, "salt");
            if (salt == null) {
                salt = new byte[16];
                RANDOM.nextBytes(salt);
                putMeta(db, "salt", salt);
            }
            restrictPermissions(file);
            return new History(db, deriveKey(password, salt, iterations));
        } catch (SQLException e) {
            throw new IOException("Could not open message history: " + e.getMessage(), e);
        }
    }

    public synchronized long add(String conversation, String sender, String text, long sentAt, boolean outgoing,
                                 int status, long envelopeId) throws IOException {
        return insert(conversation, sender, KIND_TEXT, text.getBytes(StandardCharsets.UTF_8), sentAt, outgoing, status,
                envelopeId);
    }

    /** Stores a file message; its description (including the file key) is encrypted like texts. */
    public synchronized long addFile(String conversation, String sender, FileMessage file, long sentAt, boolean outgoing,
                                     int status, long envelopeId) throws IOException {
        return insert(conversation, sender, KIND_FILE, file.encode(), sentAt, outgoing, status, envelopeId);
    }

    private long insert(String conversation, String sender, int kind, byte[] content, long sentAt, boolean outgoing,
                        int status, long envelopeId) throws IOException {
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO messages (conversation, sender, body, sent_at, outgoing, status, envelope_id, unread, kind) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, conversation);
            ps.setString(2, sender);
            ps.setBytes(3, seal(content));
            ps.setLong(4, sentAt);
            ps.setInt(5, outgoing ? 1 : 0);
            ps.setInt(6, status);
            ps.setLong(7, envelopeId);
            ps.setInt(8, outgoing ? 0 : 1);
            ps.setInt(9, kind);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    public synchronized void setStatus(long messageId, int status, long envelopeId) throws IOException {
        update("UPDATE messages SET status = ?, envelope_id = ? WHERE id = ?", status, envelopeId, messageId);
    }

    /** @return the local message id that was marked, or -1 */
    public synchronized long markDelivered(long envelopeId) throws IOException {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id FROM messages WHERE envelope_id = ? AND outgoing = 1 AND conversation LIKE 'dm:%'")) {
            ps.setLong(1, envelopeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return -1;
                long id = rs.getLong(1);
                update("UPDATE messages SET status = ? WHERE id = ?", DELIVERED, id);
                return id;
            }
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    public synchronized void markRead(String conversation) throws IOException {
        try (PreparedStatement ps = db.prepareStatement("UPDATE messages SET unread = 0 WHERE conversation = ? AND unread = 1")) {
            ps.setString(1, conversation);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** The newest {@code limit} messages of a conversation, oldest first. */
    public synchronized List<Message> load(String conversation, int limit) throws IOException {
        List<Message> result = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id, conversation, sender, body, sent_at, outgoing, status, envelope_id, kind FROM messages "
                        + "WHERE conversation = ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, conversation);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    FileMessage file = rs.getInt(9) == KIND_FILE ? file(rs.getBytes(4)) : null;
                    String text = rs.getInt(9) == KIND_FILE ? (file == null ? "[unreadable file]" : file.name())
                            : text(rs.getBytes(4));
                    result.add(0, new Message(rs.getLong(1), rs.getString(2), rs.getString(3), text,
                            rs.getLong(5), rs.getInt(6) == 1, rs.getInt(7), rs.getLong(8), file));
                }
            }
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
        return result;
    }

    /** One row per conversation: its latest message and unread count, newest conversation first. */
    public synchronized List<Summary> summaries() throws IOException {
        List<Summary> result = new ArrayList<>();
        try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("""
                SELECT m.conversation, m.sender, m.body, m.sent_at,
                       (SELECT COUNT(*) FROM messages u WHERE u.conversation = m.conversation AND u.unread = 1), m.kind
                FROM messages m
                WHERE m.id = (SELECT MAX(id) FROM messages x WHERE x.conversation = m.conversation)
                ORDER BY m.id DESC""")) {
            while (rs.next()) {
                String text;
                if (rs.getInt(6) == KIND_FILE) {
                    FileMessage file = file(rs.getBytes(3));
                    text = "📎 " + (file == null ? "file" : file.name());
                } else {
                    text = text(rs.getBytes(3));
                }
                result.add(new Summary(rs.getString(1), rs.getString(2), text, rs.getLong(4), rs.getInt(5)));
            }
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
        return result;
    }

    @Override
    public synchronized void close() {
        try {
            db.close();
        } catch (SQLException ignored) {
            // closing anyway
        }
    }

    // ------------------------------------------------------------------ crypto

    private byte[] seal(byte[] content) throws IOException {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] ct = c.doFinal(content);
            return ByteBuffer.allocate(12 + ct.length).put(nonce).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not encrypt history", e);
        }
    }

    /** @return the decrypted content, or null if the row was tampered with or the password differs */
    private byte[] open(byte[] sealed) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed, 0, 12));
            return c.doFinal(sealed, 12, sealed.length - 12);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    private String text(byte[] sealed) {
        byte[] plain = open(sealed);
        return plain == null ? "[unreadable]" : new String(plain, StandardCharsets.UTF_8);
    }

    private FileMessage file(byte[] sealed) {
        byte[] plain = open(sealed);
        if (plain == null) return null;
        try {
            return FileMessage.decode(plain);
        } catch (java.net.ProtocolException e) {
            return null;
        }
    }

    private static SecretKey deriveKey(String password, byte[] salt, int iterations) throws IOException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            SecretKey k = new SecretKeySpec(raw, "AES");
            Arrays.fill(raw, (byte) 0);
            return k;
        } catch (GeneralSecurityException e) {
            throw new IOException("Key derivation failed", e);
        } finally {
            spec.clearPassword();
        }
    }

    private void update(String sql, long a, long b) throws IOException {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private void update(String sql, long a, long b, long c) throws IOException {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            ps.setLong(3, c);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static byte[] meta(Connection db, String k) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT v FROM meta WHERE k = ?")) {
            ps.setString(1, k);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    private static void putMeta(Connection db, String k, byte[] v) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO meta (k, v) VALUES (?, ?)")) {
            ps.setString(1, k);
            ps.setBytes(2, v);
            ps.executeUpdate();
        }
    }

    private static void restrictPermissions(Path file) {
        try {
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException ignored) {
            // best effort
        }
    }
}
