package com.emirchat.server;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * SQLite storage. All access goes through prepared statements (no string-built SQL). Methods are
 * synchronized: one connection, serialised writes, which is plenty for a self-hosted server.
 */
final class Database implements AutoCloseable {
    private static final int SCHEMA_VERSION = 1;

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
