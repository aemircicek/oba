package com.emirchat.server;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite storage. All access goes through prepared statements (no string-built SQL). Methods are
 * synchronized: one connection, serialised writes, which is plenty for a self-hosted server.
 */
final class Database implements AutoCloseable {
    private static final int SCHEMA_VERSION = 4;

    record User(long id, String username, PasswordHasher.Hash password, byte[] identityKey) {}

    static final class UsernameTakenException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private final Connection connection;

    Database(Path file) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("PRAGMA secure_delete = ON"); // overwrite deleted rows (queued messages later)
        }
        migrate();
    }

    private void migrate() throws SQLException {
        int version;
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("PRAGMA user_version")) {
            version = rs.getInt(1);
        }
        if (version > SCHEMA_VERSION) {
            throw new SQLException("Database was created by a newer server version (" + version + ")");
        }
        if (version < 1) {
            try (Statement s = connection.createStatement()) {
                s.execute("""
                        CREATE TABLE users (
                            id            INTEGER PRIMARY KEY,
                            username      TEXT    NOT NULL UNIQUE,
                            password_hash BLOB    NOT NULL,
                            password_salt BLOB    NOT NULL,
                            iterations    INTEGER NOT NULL,
                            identity_key  BLOB    NOT NULL,
                            created_at    INTEGER NOT NULL
                        )""");
                s.execute("PRAGMA user_version = 1");
            }
        }
        if (version < 2) {
            try (Statement s = connection.createStatement()) {
                s.execute("""
                        CREATE TABLE friend_requests (
                            from_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            to_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            created_at INTEGER NOT NULL,
                            PRIMARY KEY (from_id, to_id)
                        )""");
                s.execute("CREATE INDEX friend_requests_to ON friend_requests(to_id)");
                s.execute("""
                        CREATE TABLE friends (
                            user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            friend_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            since     INTEGER NOT NULL,
                            PRIMARY KEY (user_id, friend_id)
                        )""");
                s.execute("PRAGMA user_version = 2");
            }
        }
        if (version < 3) {
            try (Statement s = connection.createStatement()) {
                s.execute("""
                        CREATE TABLE device_keys (
                            user_id     INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
                            dh_identity BLOB    NOT NULL,
                            dh_sig      BLOB    NOT NULL,
                            spk_id      INTEGER NOT NULL,
                            spk         BLOB    NOT NULL,
                            spk_sig     BLOB    NOT NULL,
                            updated_at  INTEGER NOT NULL
                        )""");
                s.execute("""
                        CREATE TABLE one_time_prekeys (
                            user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            key_id  INTEGER NOT NULL,
                            pub     BLOB    NOT NULL,
                            PRIMARY KEY (user_id, key_id)
                        )""");
                s.execute("""
                        CREATE TABLE envelopes (
                            id           INTEGER PRIMARY KEY AUTOINCREMENT,
                            recipient_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            sender_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            sent_at      INTEGER NOT NULL,
                            body         BLOB    NOT NULL
                        )""");
                s.execute("CREATE INDEX envelopes_recipient ON envelopes(recipient_id, id)");
                s.execute("PRAGMA user_version = 3");
            }
        }
        if (version < 4) {
            try (Statement s = connection.createStatement()) {
                s.execute("""
                        CREATE TABLE groups_ (
                            id         INTEGER PRIMARY KEY AUTOINCREMENT,
                            name       TEXT    NOT NULL,
                            owner_id   INTEGER NOT NULL REFERENCES users(id),
                            created_at INTEGER NOT NULL
                        )""");
                s.execute("""
                        CREATE TABLE group_members (
                            group_id  INTEGER NOT NULL REFERENCES groups_(id) ON DELETE CASCADE,
                            user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            joined_at INTEGER NOT NULL,
                            PRIMARY KEY (group_id, user_id)
                        )""");
                s.execute("CREATE INDEX group_members_user ON group_members(user_id)");
                s.execute("ALTER TABLE envelopes ADD COLUMN group_id INTEGER NOT NULL DEFAULT 0");
                s.execute("PRAGMA user_version = 4");
            }
        }
    }

    // ------------------------------------------------------------------ groups

    record Group(long id, String name, long ownerId) {}

    synchronized long createGroup(String name, long ownerId, List<Long> members) throws SQLException {
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            long id;
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO groups_ (name, owner_id, created_at) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setLong(2, ownerId);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            for (long m : members) insertMember(id, m);
            connection.commit();
            return id;
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(auto);
        }
    }

    private void insertMember(long groupId, long userId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO group_members (group_id, user_id, joined_at) VALUES (?, ?, ?)")) {
            ps.setLong(1, groupId);
            ps.setLong(2, userId);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    synchronized void addMember(long groupId, long userId) throws SQLException {
        insertMember(groupId, userId);
    }

    synchronized void removeMember(long groupId, long userId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM group_members WHERE group_id = ? AND user_id = ?")) {
            ps.setLong(1, groupId);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    synchronized void setOwner(long groupId, long ownerId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE groups_ SET owner_id = ? WHERE id = ?")) {
            ps.setLong(1, ownerId);
            ps.setLong(2, groupId);
            ps.executeUpdate();
        }
    }

    synchronized void deleteGroup(long groupId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM groups_ WHERE id = ?")) {
            ps.setLong(1, groupId);
            ps.executeUpdate();
        }
    }

    synchronized Optional<Group> group(long groupId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id, name, owner_id FROM groups_ WHERE id = ?")) {
            ps.setLong(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Group(rs.getLong(1), rs.getString(2), rs.getLong(3))) : Optional.empty();
            }
        }
    }

    /** Members ordered by join time (oldest first). */
    synchronized List<User> members(long groupId) throws SQLException {
        List<User> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT u.id, u.username, u.identity_key FROM group_members m JOIN users u ON u.id = m.user_id "
                        + "WHERE m.group_id = ? ORDER BY m.joined_at, u.id")) {
            ps.setLong(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new User(rs.getLong(1), rs.getString(2), null, rs.getBytes(3)));
            }
        }
        return result;
    }

    synchronized List<Long> groupsOf(long userId) throws SQLException {
        List<Long> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT group_id FROM group_members WHERE user_id = ? ORDER BY group_id")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getLong(1));
            }
        }
        return result;
    }

    synchronized boolean isMember(long groupId, long userId) throws SQLException {
        return exists("SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ?", groupId, userId);
    }

    synchronized boolean shareAGroup(long a, long b) throws SQLException {
        return exists("SELECT 1 FROM group_members x JOIN group_members y ON x.group_id = y.group_id "
                + "WHERE x.user_id = ? AND y.user_id = ? LIMIT 1", a, b);
    }

    // ------------------------------------------------------------------ keys and envelopes

    record DeviceKeysRow(byte[] dhIdentity, byte[] dhSig, int spkId, byte[] spk, byte[] spkSig) {}

    record QueuedEnvelope(long id, long senderId, String sender, long groupId, long sentAt, byte[] body) {}

    synchronized void putDeviceKeys(long userId, DeviceKeysRow k) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO device_keys (user_id, dh_identity, dh_sig, spk_id, spk, spk_sig, updated_at) VALUES (?,?,?,?,?,?,?) "
                        + "ON CONFLICT(user_id) DO UPDATE SET dh_identity=excluded.dh_identity, dh_sig=excluded.dh_sig, "
                        + "spk_id=excluded.spk_id, spk=excluded.spk, spk_sig=excluded.spk_sig, updated_at=excluded.updated_at")) {
            ps.setLong(1, userId);
            ps.setBytes(2, k.dhIdentity());
            ps.setBytes(3, k.dhSig());
            ps.setInt(4, k.spkId());
            ps.setBytes(5, k.spk());
            ps.setBytes(6, k.spkSig());
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    synchronized Optional<DeviceKeysRow> deviceKeys(long userId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT dh_identity, dh_sig, spk_id, spk, spk_sig FROM device_keys WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new DeviceKeysRow(rs.getBytes(1), rs.getBytes(2), rs.getInt(3), rs.getBytes(4), rs.getBytes(5)));
            }
        }
    }

    synchronized void addPrekey(long userId, int keyId, byte[] pub) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO one_time_prekeys (user_id, key_id, pub) VALUES (?, ?, ?)")) {
            ps.setLong(1, userId);
            ps.setInt(2, keyId);
            ps.setBytes(3, pub);
            ps.executeUpdate();
        }
    }

    synchronized int prekeyCount(long userId) throws SQLException {
        return count("SELECT COUNT(*) FROM one_time_prekeys WHERE user_id = ?", userId);
    }

    /** Removes and returns the oldest one-time prekey, if any (each is handed out exactly once). */
    synchronized Optional<java.util.Map.Entry<Integer, byte[]>> takePrekey(long userId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT key_id, pub FROM one_time_prekeys WHERE user_id = ? ORDER BY key_id LIMIT 1")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                int id = rs.getInt(1);
                byte[] pub = rs.getBytes(2);
                try (PreparedStatement del = connection.prepareStatement(
                        "DELETE FROM one_time_prekeys WHERE user_id = ? AND key_id = ?")) {
                    del.setLong(1, userId);
                    del.setInt(2, id);
                    del.executeUpdate();
                }
                return Optional.of(java.util.Map.entry(id, pub));
            }
        }
    }

    /** Forgets everything bound to the previous device: keys, prekeys, undeliverable envelopes. */
    synchronized void resetDevice(long userId) throws SQLException {
        for (String sql : new String[] {
                "DELETE FROM device_keys WHERE user_id = ?",
                "DELETE FROM one_time_prekeys WHERE user_id = ?",
                "DELETE FROM envelopes WHERE recipient_id = ?"}) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, userId);
                ps.executeUpdate();
            }
        }
    }

    synchronized long queueEnvelope(long recipientId, long senderId, long groupId, long sentAt, byte[] body)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO envelopes (recipient_id, sender_id, group_id, sent_at, body) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, recipientId);
            ps.setLong(2, senderId);
            ps.setLong(3, groupId);
            ps.setLong(4, sentAt);
            ps.setBytes(5, body);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    synchronized int queuedCount(long recipientId) throws SQLException {
        return count("SELECT COUNT(*) FROM envelopes WHERE recipient_id = ?", recipientId);
    }

    synchronized List<QueuedEnvelope> queuedFor(long recipientId, int limit) throws SQLException {
        List<QueuedEnvelope> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT e.id, e.sender_id, u.username, e.group_id, e.sent_at, e.body FROM envelopes e JOIN users u ON u.id = e.sender_id "
                        + "WHERE e.recipient_id = ? ORDER BY e.id LIMIT ?")) {
            ps.setLong(1, recipientId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new QueuedEnvelope(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getLong(5),
                            rs.getBytes(6)));
                }
            }
        }
        return result;
    }

    /** Deletes an envelope addressed to {@code recipientId}; returns its sender id, or -1 if none. */
    synchronized long deleteEnvelope(long recipientId, long envelopeId) throws SQLException {
        long sender;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT sender_id FROM envelopes WHERE id = ? AND recipient_id = ?")) {
            ps.setLong(1, envelopeId);
            ps.setLong(2, recipientId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return -1;
                sender = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM envelopes WHERE id = ?")) {
            ps.setLong(1, envelopeId);
            ps.executeUpdate();
        }
        return sender;
    }

    // ------------------------------------------------------------------ friends

    synchronized Optional<User> findUserById(long id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, username, password_hash, password_salt, iterations, identity_key FROM users WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new User(rs.getLong(1), rs.getString(2),
                        new PasswordHasher.Hash(rs.getBytes(3), rs.getBytes(4), rs.getInt(5)), rs.getBytes(6)));
            }
        }
    }

    synchronized boolean areFriends(long a, long b) throws SQLException {
        return exists("SELECT 1 FROM friends WHERE user_id = ? AND friend_id = ?", a, b);
    }

    synchronized boolean requestExists(long from, long to) throws SQLException {
        return exists("SELECT 1 FROM friend_requests WHERE from_id = ? AND to_id = ?", from, to);
    }

    synchronized int friendCount(long user) throws SQLException {
        return count("SELECT COUNT(*) FROM friends WHERE user_id = ?", user);
    }

    synchronized int incomingRequestCount(long user) throws SQLException {
        return count("SELECT COUNT(*) FROM friend_requests WHERE to_id = ?", user);
    }

    synchronized void addRequest(long from, long to) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO friend_requests (from_id, to_id, created_at) VALUES (?, ?, ?)")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** @return true if a request existed and was removed */
    synchronized boolean deleteRequest(long from, long to) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM friend_requests WHERE from_id = ? AND to_id = ?")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            return ps.executeUpdate() > 0;
        }
    }

    /** Atomically turns any pending requests between a and b into a friendship. */
    synchronized void makeFriends(long a, long b) throws SQLException {
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement del = connection.prepareStatement(
                    "DELETE FROM friend_requests WHERE (from_id = ? AND to_id = ?) OR (from_id = ? AND to_id = ?)")) {
                del.setLong(1, a);
                del.setLong(2, b);
                del.setLong(3, b);
                del.setLong(4, a);
                del.executeUpdate();
            }
            try (PreparedStatement ins = connection.prepareStatement(
                    "INSERT OR IGNORE INTO friends (user_id, friend_id, since) VALUES (?, ?, ?)")) {
                long now = System.currentTimeMillis();
                for (long[] pair : new long[][] {{a, b}, {b, a}}) {
                    ins.setLong(1, pair[0]);
                    ins.setLong(2, pair[1]);
                    ins.setLong(3, now);
                    ins.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(auto);
        }
    }

    synchronized void removeFriends(long a, long b) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM friends WHERE (user_id = ? AND friend_id = ?) OR (user_id = ? AND friend_id = ?)")) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            ps.setLong(3, b);
            ps.setLong(4, a);
            ps.executeUpdate();
        }
    }

    synchronized List<User> friendsOf(long user) throws SQLException {
        List<User> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT u.id, u.username, u.identity_key FROM friends f JOIN users u ON u.id = f.friend_id "
                        + "WHERE f.user_id = ? ORDER BY u.username")) {
            ps.setLong(1, user);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new User(rs.getLong(1), rs.getString(2), null, rs.getBytes(3)));
            }
        }
        return result;
    }

    synchronized List<String> incomingRequests(long user) throws SQLException {
        return names("SELECT u.username FROM friend_requests r JOIN users u ON u.id = r.from_id "
                + "WHERE r.to_id = ? ORDER BY r.created_at", user);
    }

    synchronized List<String> outgoingRequests(long user) throws SQLException {
        return names("SELECT u.username FROM friend_requests r JOIN users u ON u.id = r.to_id "
                + "WHERE r.from_id = ? ORDER BY r.created_at", user);
    }

    private boolean exists(String sql, long a, long b) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int count(String sql, long a) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, a);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private List<String> names(String sql, long a) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, a);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString(1));
            }
        }
        return result;
    }

    synchronized Optional<User> findUser(String username) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, username, password_hash, password_salt, iterations, identity_key FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new User(rs.getLong(1), rs.getString(2),
                        new PasswordHasher.Hash(rs.getBytes(3), rs.getBytes(4), rs.getInt(5)), rs.getBytes(6)));
            }
        }
    }

    synchronized long createUser(String username, PasswordHasher.Hash password, byte[] identityKey)
            throws SQLException, UsernameTakenException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO users (username, password_hash, password_salt, iterations, identity_key, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setBytes(2, password.hash());
            ps.setBytes(3, password.salt());
            ps.setInt(4, password.iterations());
            ps.setBytes(5, identityKey);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) throw new UsernameTakenException();
            throw e;
        }
    }

    synchronized void updateIdentityKey(long userId, byte[] identityKey) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE users SET identity_key = ? WHERE id = ?")) {
            ps.setBytes(1, identityKey);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
