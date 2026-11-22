package com.emirchat.client;

import com.emirchat.chat.FileMessage;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

/**
 * The history's search index (3.6): keyed word tokens per message (see {@link SearchTerms}),
 * kept up to date as messages are stored, edited and deleted, and built for older messages in
 * the background. The history's own lock is held by every caller.
 */
final class SearchIndex {
    private final Connection db;
    private final SearchTerms terms;
    private final LongFunction<History.Message> get;

    SearchIndex(Connection db, SearchTerms terms, LongFunction<History.Message> get) {
        this.db = db;
        this.terms = terms;
        this.get = get;
    }

    /**
     * Indexes up to {@code max} messages stored before the index existed (newest first), so a
     * long history becomes searchable quickly without one long pause.
     *
     * @return how many were indexed (0 when everything is)
     */
    int backlog(int max) throws IOException {
        List<long[]> rows = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id FROM messages WHERE indexed = 0 ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, max);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new long[] {rs.getLong(1)});
            }
            db.setAutoCommit(false);
            try {
                for (long[] r : rows) {
                    History.Message m = get.apply(r[0]);
                    if (m == null) continue;
                    add(m.id(), m.deleted() ? "" : m.text());
                }
                db.commit();
            } finally {
                db.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
        return rows.size();
    }

    void add(long id, String text) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO search_index (token, message_id) VALUES (?, ?)")) {
            for (SearchTerms.Token t : terms.tokens(text)) {
                ps.setBytes(1, t.bytes());
                ps.setLong(2, id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = db.prepareStatement("UPDATE messages SET indexed = 1 WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    void remove(long id) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("DELETE FROM search_index WHERE message_id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    static String fileName(byte[] encoded) {
        try {
            return FileMessage.decode(encoded).name();
        } catch (java.net.ProtocolException e) {
            return "";
        }
    }
}
