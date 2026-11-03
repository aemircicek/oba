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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    /** Outgoing direct message the recipient has read. */
    public static final int READ = 5;

    static final int KDF_ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * @param file      the shared file for a file message (then {@code text} is its name), else null
     * @param expiresAt when a disappearing message is deleted (epoch millis), or 0
     * @param uid       the id shared by every copy of the message (empty for old messages)
     * @param replyTo   the uid of the message this one answers, or empty
     * @param edited    the author changed the text
     * @param deleted   the author deleted it for everyone (text and file are gone)
     */
    public record Message(long id, String conversation, String sender, String text, long sentAt, boolean outgoing,
                          int status, long envelopeId, FileMessage file, long expiresAt, byte[] uid, byte[] replyTo,
                          boolean edited, boolean deleted) {
        public Message {
            uid = uid == null ? new byte[0] : uid.clone();
            replyTo = replyTo == null ? new byte[0] : replyTo.clone();
        }

        public Message(long id, String conversation, String sender, String text, long sentAt, boolean outgoing,
                       int status, long envelopeId) {
            this(id, conversation, sender, text, sentAt, outgoing, status, envelopeId, null, 0);
        }

        public Message(long id, String conversation, String sender, String text, long sentAt, boolean outgoing,
                       int status, long envelopeId, FileMessage file) {
            this(id, conversation, sender, text, sentAt, outgoing, status, envelopeId, file, 0);
        }

        public Message(long id, String conversation, String sender, String text, long sentAt, boolean outgoing,
                       int status, long envelopeId, FileMessage file, long expiresAt) {
            this(id, conversation, sender, text, sentAt, outgoing, status, envelopeId, file, expiresAt, null, null,
                    false, false);
        }

        @Override public byte[] uid() { return uid.clone(); }

        @Override public byte[] replyTo() { return replyTo.clone(); }

        public boolean hasUid() { return uid.length > 0; }

        @Override public boolean equals(Object o) { return o instanceof Message m && m.id == id; }

        @Override public int hashCode() { return Long.hashCode(id); }
    }

    private static final String COLUMNS = "id, conversation, sender, body, sent_at, outgoing, status, envelope_id, kind, "
            + "expires_at, uid, reply_to, edited, deleted";

    /** A disappearing message that was just deleted. */
    public record Expired(long id, String conversation, FileMessage file) {}

    private static final int KIND_TEXT = 0;
    private static final int KIND_FILE = 1;

    public record Summary(String conversation, String lastSender, String lastText, long lastAt, int unread) {}

    private final Connection db;
    private final SecretKey key;
    private final Path file;

    private History(Connection db, SecretKey key, Path file) {
        this.db = db;
        this.key = key;
        this.file = file;
    }

    /** The database file (for backups). */
    public Path file() {
        return file;
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
                java.util.Set<String> columns = new java.util.HashSet<>();
                try (ResultSet cols = s.executeQuery("PRAGMA table_info(messages)")) {
                    while (cols.next()) columns.add(cols.getString("name"));
                }
                if (!columns.contains("kind")) s.execute("ALTER TABLE messages ADD COLUMN kind INTEGER NOT NULL DEFAULT 0");
                if (!columns.contains("expires_at")) {
                    s.execute("ALTER TABLE messages ADD COLUMN expires_at INTEGER NOT NULL DEFAULT 0");
                }
                s.execute("CREATE INDEX IF NOT EXISTS messages_expiry ON messages(expires_at) WHERE expires_at > 0");
                if (!columns.contains("uid")) {
                    s.execute("ALTER TABLE messages ADD COLUMN uid BLOB NOT NULL DEFAULT x''");
                    s.execute("ALTER TABLE messages ADD COLUMN reply_to BLOB NOT NULL DEFAULT x''");
                    s.execute("ALTER TABLE messages ADD COLUMN edited INTEGER NOT NULL DEFAULT 0");
                    s.execute("ALTER TABLE messages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0");
                }
                s.execute("CREATE INDEX IF NOT EXISTS messages_uid ON messages(conversation, uid)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS reactions (
                            message_id INTEGER NOT NULL,
                            reactor    TEXT    NOT NULL,
                            emoji      BLOB    NOT NULL,
                            PRIMARY KEY (message_id, reactor)
                        )""");
                s.execute("CREATE TABLE IF NOT EXISTS settings (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS timers (conversation TEXT PRIMARY KEY, seconds INTEGER NOT NULL)""");
                // 3.4: threads, polls, pins and channel topics
                if (!columns.contains("thread")) s.execute("ALTER TABLE messages ADD COLUMN thread INTEGER NOT NULL DEFAULT 0");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS votes (
                            message_id INTEGER NOT NULL,
                            voter      TEXT    NOT NULL,
                            choice     BLOB    NOT NULL,
                            PRIMARY KEY (message_id, voter)
                        )""");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS pins (
                            message_id INTEGER PRIMARY KEY,
                            pinned_by  TEXT    NOT NULL,
                            pinned_at  INTEGER NOT NULL
                        )""");
                s.execute("CREATE TABLE IF NOT EXISTS topics (conversation TEXT PRIMARY KEY, body BLOB NOT NULL)");
            }
            byte[] salt = meta(db, "salt");
            if (salt == null) {
                salt = new byte[16];
                RANDOM.nextBytes(salt);
                putMeta(db, "salt", salt);
            }
            restrictPermissions(file);
            return new History(db, deriveKey(password, salt, iterations), file.toAbsolutePath());
        } catch (SQLException e) {
            throw new IOException("Could not open message history: " + e.getMessage(), e);
        }
    }

    public synchronized long add(String conversation, String sender, String text, long sentAt, boolean outgoing,
                                 int status, long envelopeId) throws IOException {
        return add(conversation, sender, text, sentAt, outgoing, status, envelopeId, 0);
    }

    /** @param expiresAt epoch millis when this disappearing message is deleted, or 0 to keep it */
    public synchronized long add(String conversation, String sender, String text, long sentAt, boolean outgoing,
                                 int status, long envelopeId, long expiresAt) throws IOException {
        return add(conversation, sender, text, sentAt, outgoing, status, envelopeId, expiresAt, null, null);
    }

    /** @param uid the message's shared id (null if none); @param replyTo the uid it answers, or null */
    public synchronized long add(String conversation, String sender, String text, long sentAt, boolean outgoing,
                                 int status, long envelopeId, long expiresAt, byte[] uid, byte[] replyTo) throws IOException {
        return insert(conversation, sender, KIND_TEXT, text.getBytes(StandardCharsets.UTF_8), sentAt, outgoing, status,
                envelopeId, expiresAt, uid, replyTo);
    }

    /** Stores a file message; its description (including the file key) is encrypted like texts. */
    public synchronized long addFile(String conversation, String sender, FileMessage file, long sentAt, boolean outgoing,
                                     int status, long envelopeId) throws IOException {
        return addFile(conversation, sender, file, sentAt, outgoing, status, envelopeId, 0);
    }

    public synchronized long addFile(String conversation, String sender, FileMessage file, long sentAt, boolean outgoing,
                                     int status, long envelopeId, long expiresAt) throws IOException {
        return addFile(conversation, sender, file, sentAt, outgoing, status, envelopeId, expiresAt, null, null);
    }

    public synchronized long addFile(String conversation, String sender, FileMessage file, long sentAt, boolean outgoing,
                                     int status, long envelopeId, long expiresAt, byte[] uid, byte[] replyTo)
            throws IOException {
        return insert(conversation, sender, KIND_FILE, file.encode(), sentAt, outgoing, status, envelopeId, expiresAt,
                uid, replyTo);
    }

    // ------------------------------------------------------------------ edits, reactions, receipts

    public synchronized Message get(long id) throws IOException {
        try (PreparedStatement ps = db.prepareStatement("SELECT " + COLUMNS + " FROM messages WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? row(rs) : null;
            }
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** The message with this shared id in this conversation, or null. */
    public synchronized Message find(String conversation, byte[] uid) throws IOException {
        if (uid == null || uid.length == 0) return null;
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT " + COLUMNS + " FROM messages WHERE conversation = ? AND uid = ? ORDER BY id LIMIT 1")) {
            ps.setString(1, conversation);
            ps.setBytes(2, uid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? row(rs) : null;
            }
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Replaces the text of a message, if {@code editor} wrote it and it is a text that still exists.
     *
     * @return the local id of the edited message, or -1 if the edit is not allowed
     */
    public synchronized long edit(String conversation, byte[] uid, String editor, String newText) throws IOException {
        Message m = find(conversation, uid);
        if (m == null || m.deleted() || m.file() != null || !m.sender().equals(editor)) return -1;
        try (PreparedStatement ps = db.prepareStatement("UPDATE messages SET body = ?, edited = 1 WHERE id = ?")) {
            ps.setBytes(1, seal(newText.getBytes(StandardCharsets.UTF_8)));
            ps.setLong(2, m.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
        return m.id();
    }

    /**
     * Deletes a message for everyone, if {@code deleter} wrote it: its content is overwritten and
     * its reactions removed; the row stays as "This message was deleted".
     *
     * @return the message as it was (to remove a cached file), or null if not allowed
     */
    public synchronized Message delete(String conversation, byte[] uid, String deleter) throws IOException {
        Message m = find(conversation, uid);
        if (m == null || m.deleted() || !m.sender().equals(deleter)) return null;
        try (PreparedStatement ps = db.prepareStatement(
                "UPDATE messages SET body = ?, kind = 0, deleted = 1, edited = 0 WHERE id = ?");
             PreparedStatement r = db.prepareStatement("DELETE FROM reactions WHERE message_id = ?")) {
            ps.setBytes(1, seal(new byte[0]));
            ps.setLong(2, m.id());
            ps.executeUpdate();
            r.setLong(1, m.id());
            r.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
        return m;
    }

    /**
     * Sets {@code reactor}'s reaction to a message (one per person); an empty emoji removes it.
     *
     * @return the local id of the message, or -1 if it does not exist or was deleted
     */
    public synchronized long react(String conversation, byte[] uid, String reactor, String emoji) throws IOException {
        Message m = find(conversation, uid);
        if (m == null || m.deleted()) return -1;
        try {
            if (emoji.isEmpty()) {
                try (PreparedStatement ps = db.prepareStatement("DELETE FROM reactions WHERE message_id = ? AND reactor = ?")) {
                    ps.setLong(1, m.id());
                    ps.setString(2, reactor);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = db.prepareStatement(
                        "INSERT INTO reactions (message_id, reactor, emoji) VALUES (?, ?, ?) "
                                + "ON CONFLICT(message_id, reactor) DO UPDATE SET emoji = excluded.emoji")) {
                    ps.setLong(1, m.id());
                    ps.setString(2, reactor);
                    ps.setBytes(3, seal(emoji.getBytes(StandardCharsets.UTF_8)));
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
        return m.id();
    }

    // ------------------------------------------------------------------ threads, polls, pins, topics

    /** Marks a stored message as a reply inside the thread of the message it answers. */
    public synchronized void markThread(long id) throws IOException {
        try (PreparedStatement ps = db.prepareStatement("UPDATE messages SET thread = 1 WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Local ids of the thread replies in a conversation (shown in their thread, not in the channel). */
    public synchronized java.util.Set<Long> threadReplies(String conversation) throws IOException {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        try (PreparedStatement ps = db.prepareStatement("SELECT id FROM messages WHERE conversation = ? AND thread = 1")) {
            ps.setString(1, conversation);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
        return ids;
    }

    /**
     * Records {@code voter}'s choice in a poll (one vote per person; a negative choice takes it back).
     *
     * @return the poll's local id, or -1 if there is no such (undeleted) message
     */
    public synchronized long vote(String conversation, byte[] uid, String voter, int choice) throws IOException {
        Message m = find(conversation, uid);
        if (m == null || m.deleted()) return -1;
        try {
            if (choice < 0) {
                try (PreparedStatement ps = db.prepareStatement("DELETE FROM votes WHERE message_id = ? AND voter = ?")) {
                    ps.setLong(1, m.id());
                    ps.setString(2, voter);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = db.prepareStatement(
                        "INSERT INTO votes (message_id, voter, choice) VALUES (?, ?, ?) "
                                + "ON CONFLICT(message_id, voter) DO UPDATE SET choice = excluded.choice")) {
                    ps.setLong(1, m.id());
                    ps.setString(2, voter);
                    ps.setBytes(3, seal(Integer.toString(choice).getBytes(StandardCharsets.UTF_8)));
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
        return m.id();
    }

    /** Votes in a conversation: poll id → (voter → choice). */
    public synchronized Map<Long, Map<String, Integer>> votes(String conversation) throws IOException {
        Map<Long, Map<String, Integer>> result = new HashMap<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT v.message_id, v.voter, v.choice FROM votes v JOIN messages m ON m.id = v.message_id "
                        + "WHERE m.conversation = ? ORDER BY v.voter")) {
            ps.setString(1, conversation);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byte[] plain = open(rs.getBytes(3));
                    if (plain == null) continue;
                    try {
                        result.computeIfAbsent(rs.getLong(1), k -> new java.util.TreeMap<>())
                                .put(rs.getString(2), Integer.parseInt(new String(plain, StandardCharsets.UTF_8)));
                    } catch (NumberFormatException ignored) {
                        // a damaged row counts as no vote
                    }
                }
            }
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
        return result;
    }

    /**
     * Pins or unpins a message.
     *
     * @return its local id, or -1 if there is no such (undeleted) message
     */
    public synchronized long pin(String conversation, byte[] uid, String by, boolean on) throws IOException {
        Message m = find(conversation, uid);
        if (m == null || (on && m.deleted())) return -1;
        try (PreparedStatement ps = db.prepareStatement(on
                ? "INSERT INTO pins (message_id, pinned_by, pinned_at) VALUES (?, ?, ?) ON CONFLICT(message_id) DO NOTHING"
                : "DELETE FROM pins WHERE message_id = ?")) {
            ps.setLong(1, m.id());
            if (on) {
                ps.setString(2, by);
                ps.setLong(3, System.currentTimeMillis());
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
        return m.id();
    }

    /** Pinned messages of a conversation, most recently pinned first (deleted ones are left out). */
    public synchronized List<Message> pinned(String conversation) throws IOException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT p.message_id FROM pins p JOIN messages m ON m.id = p.message_id "
                        + "WHERE m.conversation = ? AND m.deleted = 0 ORDER BY p.pinned_at DESC")) {
            ps.setString(1, conversation);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
        List<Message> out = new ArrayList<>();
        for (long id : ids) {
            Message m = get(id);
            if (m != null) out.add(m);
        }
        return out;
    }

    /** A channel's or group's topic (empty to clear it). */
    public synchronized void setTopic(String conversation, String topic) throws IOException {
        try (PreparedStatement ps = db.prepareStatement(topic.isEmpty()
                ? "DELETE FROM topics WHERE conversation = ?"
                : "INSERT INTO topics (conversation, body) VALUES (?, ?) ON CONFLICT(conversation) DO UPDATE SET body = excluded.body")) {
            ps.setString(1, conversation);
            if (!topic.isEmpty()) ps.setBytes(2, seal(topic.getBytes(StandardCharsets.UTF_8)));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    public synchronized String topic(String conversation) throws IOException {
        try (PreparedStatement ps = db.prepareStatement("SELECT body FROM topics WHERE conversation = ?")) {
            ps.setString(1, conversation);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "";
                byte[] plain = open(rs.getBytes(1));
                return plain == null ? "" : new String(plain, StandardCharsets.UTF_8);
            }
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Reactions in a conversation: local message id → (reactor → emoji). */
    public synchronized Map<Long, Map<String, String>> reactions(String conversation) throws IOException {
        Map<Long, Map<String, String>> result = new HashMap<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT r.message_id, r.reactor, r.emoji FROM reactions r JOIN messages m ON m.id = r.message_id "
                        + "WHERE m.conversation = ? ORDER BY r.reactor")) {
            ps.setString(1, conversation);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byte[] plain = open(rs.getBytes(3));
                    if (plain == null) continue;
                    result.computeIfAbsent(rs.getLong(1), k -> new java.util.TreeMap<>())
                            .put(rs.getString(2), new String(plain, StandardCharsets.UTF_8));
                }
            }
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
        return result;
    }

    /**
     * A read receipt: our messages in the conversation up to and including {@code uid} were read.
     *
     * @return local ids whose status changed to {@link #READ}
     */
    public synchronized List<Long> markReadUpTo(String conversation, byte[] uid) throws IOException {
        Message target = find(conversation, uid);
        List<Long> changed = new ArrayList<>();
        if (target == null || !target.outgoing()) return changed;
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id FROM messages WHERE conversation = ? AND outgoing = 1 AND id <= ? AND status IN (?, ?, ?)")) {
            ps.setString(1, conversation);
            ps.setLong(2, target.id());
            ps.setInt(3, SENT);
            ps.setInt(4, DELIVERED);
            ps.setInt(5, PENDING); // the receipt can overtake our own "sent" bookkeeping
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) changed.add(rs.getLong(1));
            }
            for (long id : changed) update("UPDATE messages SET status = ? WHERE id = ?", READ, id);
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
        return changed;
    }

    // ------------------------------------------------------------------ settings and search

    /** An account setting (stored in plain text: never put secrets here). */
    public synchronized String setting(String key, String defaultValue) {
        try (PreparedStatement ps = db.prepareStatement("SELECT v FROM settings WHERE k = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : defaultValue;
            }
        } catch (SQLException e) {
            return defaultValue;
        }
    }

    public synchronized void setSetting(String key, String value) throws IOException {
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO settings (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Finds messages whose text or file name contains {@code query} (ignoring case), newest first.
     * Texts are encrypted at rest, so every message is decrypted here, in memory.
     */
    public synchronized List<Message> search(String query, int limit) throws IOException {
        List<Message> result = new ArrayList<>();
        String needle = query.strip().toLowerCase(java.util.Locale.ROOT);
        if (needle.isEmpty()) return result;
        try (Statement s = db.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + COLUMNS + " FROM messages WHERE deleted = 0 ORDER BY id DESC")) {
            while (rs.next() && result.size() < limit) {
                Message m = row(rs);
                if (m.text().toLowerCase(java.util.Locale.ROOT).contains(needle)) result.add(m);
            }
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
        return result;
    }

    /** Writes a consistent copy of the whole history (still encrypted) to {@code target}, for backups. */
    public synchronized void copyTo(Path target) throws IOException {
        try (PreparedStatement ps = db.prepareStatement("VACUUM INTO ?")) {
            ps.setString(1, target.toAbsolutePath().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Could not copy the history: " + e.getMessage(), e);
        }
    }

    /** The disappearing-message timer of a conversation in seconds (0 = off). */
    public synchronized long timer(String conversation) throws IOException {
        try (PreparedStatement ps = db.prepareStatement("SELECT seconds FROM timers WHERE conversation = ?")) {
            ps.setString(1, conversation);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    public synchronized void setTimer(String conversation, long seconds) throws IOException {
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO timers (conversation, seconds) VALUES (?, ?) ON CONFLICT(conversation) DO UPDATE SET seconds = excluded.seconds")) {
            ps.setString(1, conversation);
            ps.setLong(2, seconds);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Deletes every disappearing message whose time has come (rows are overwritten on disk:
     * {@code secure_delete} is on) and returns them, so their views and cached files can go too.
     */
    public synchronized List<Expired> deleteExpired(long now) throws IOException {
        List<Expired> expired = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id, conversation, kind, body FROM messages WHERE expires_at > 0 AND expires_at <= ?")) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    expired.add(new Expired(rs.getLong(1), rs.getString(2), rs.getInt(3) == KIND_FILE ? file(rs.getBytes(4)) : null));
                }
            }
            try (PreparedStatement del = db.prepareStatement("DELETE FROM messages WHERE expires_at > 0 AND expires_at <= ?");
                 PreparedStatement r = db.prepareStatement("DELETE FROM reactions WHERE message_id = ?");
                 PreparedStatement v = db.prepareStatement("DELETE FROM votes WHERE message_id = ?");
                 PreparedStatement p = db.prepareStatement("DELETE FROM pins WHERE message_id = ?")) {
                for (Expired e : expired) {
                    for (PreparedStatement st : new PreparedStatement[] {r, v, p}) {
                        st.setLong(1, e.id());
                        st.executeUpdate();
                    }
                }
                del.setLong(1, now);
                del.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
        return expired;
    }

    private long insert(String conversation, String sender, int kind, byte[] content, long sentAt, boolean outgoing,
                        int status, long envelopeId, long expiresAt, byte[] uid, byte[] replyTo) throws IOException {
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO messages (conversation, sender, body, sent_at, outgoing, status, envelope_id, unread, kind, "
                        + "expires_at, uid, reply_to) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setBytes(11, uid == null ? new byte[0] : uid);
            ps.setBytes(12, replyTo == null ? new byte[0] : replyTo);
            ps.setString(1, conversation);
            ps.setString(2, sender);
            ps.setBytes(3, seal(content));
            ps.setLong(4, sentAt);
            ps.setInt(5, outgoing ? 1 : 0);
            ps.setInt(6, status);
            ps.setLong(7, envelopeId);
            ps.setInt(8, outgoing ? 0 : 1);
            ps.setInt(9, kind);
            ps.setLong(10, expiresAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Sets a status; a receipt that arrived first (delivered or read) is never downgraded to "sent". */
    public synchronized void setStatus(long messageId, int status, long envelopeId) throws IOException {
        try (PreparedStatement ps = db.prepareStatement(
                "UPDATE messages SET envelope_id = ?, status = CASE WHEN ? = ? AND status IN (?, ?) THEN status ELSE ? END "
                        + "WHERE id = ?")) {
            ps.setLong(1, envelopeId);
            ps.setInt(2, status);
            ps.setInt(3, SENT);
            ps.setInt(4, DELIVERED);
            ps.setInt(5, READ);
            ps.setInt(6, status);
            ps.setLong(7, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** @return the local message id that was marked, or -1 */
    public synchronized long markDelivered(long envelopeId) throws IOException {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id FROM messages WHERE envelope_id = ? AND outgoing = 1 AND conversation LIKE 'dm:%'")) {
            ps.setLong(1, envelopeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return -1;
                long id = rs.getLong(1);
                update("UPDATE messages SET status = ? WHERE id = ? AND status <> " + READ, DELIVERED, id);
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
                "SELECT " + COLUMNS + " FROM messages WHERE conversation = ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, conversation);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(0, row(rs));
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
                       (SELECT COUNT(*) FROM messages u WHERE u.conversation = m.conversation AND u.unread = 1), m.kind,
                       m.deleted
                FROM messages m
                WHERE m.id = (SELECT MAX(id) FROM messages x WHERE x.conversation = m.conversation)
                ORDER BY m.id DESC""")) {
            while (rs.next()) {
                String text;
                if (rs.getInt(7) == 1) {
                    text = DELETED_TEXT;
                } else if (rs.getInt(6) == KIND_FILE) {
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

    /** Shown in place of a message its author deleted. */
    public static final String DELETED_TEXT = "This message was deleted";

    /** Maps a row selected with {@link #COLUMNS}. */
    private Message row(ResultSet rs) throws SQLException {
        boolean deleted = rs.getInt(14) == 1;
        FileMessage file = !deleted && rs.getInt(9) == KIND_FILE ? file(rs.getBytes(4)) : null;
        String text;
        if (deleted) text = DELETED_TEXT;
        else if (rs.getInt(9) == KIND_FILE) text = file == null ? "[unreadable file]" : file.name();
        else text = text(rs.getBytes(4));
        return new Message(rs.getLong(1), rs.getString(2), rs.getString(3), text, rs.getLong(5), rs.getInt(6) == 1,
                rs.getInt(7), rs.getLong(8), file, rs.getLong(10), rs.getBytes(11), rs.getBytes(12), rs.getInt(13) == 1,
                deleted);
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
