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
    private static final int SCHEMA_VERSION = 2;

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
