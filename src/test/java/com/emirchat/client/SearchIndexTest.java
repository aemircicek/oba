package com.emirchat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The encrypted search index: what it finds, and that the file holds no words. */
class SearchIndexTest {
    @TempDir Path dir;

    private static List<String> texts(List<History.Message> found) {
        return found.stream().map(History.Message::text).toList();
    }

    @Test
    void foldsTurkishLettersCaseAndAccents() {
        assertEquals("isik", SearchTerms.fold("IŞIK"));
        assertEquals("istanbul", SearchTerms.fold("İstanbul"));
        assertEquals("cafe gunes", SearchTerms.fold("Café Güneş"));
        assertEquals(java.util.Set.of("akşam", "mi").stream().map(SearchTerms::fold).collect(java.util.stream.Collectors.toSet()),
                SearchTerms.words("Akşam? mi! a"));
    }

    @Test
    void findsWordStartsInAnySpellingAndCombinesWords() throws Exception {
        try (History h = History.open(dir.resolve("h.db"), "pw", 1000)) {
            h.add("dm:efe", "efe", "Işık yarın İstanbul'da", 1, false, History.RECEIVED, 1);
            h.add("dm:efe", "emir", "isik nerede", 2, true, History.SENT, 2);
            h.add("g:4", "ayse", "Kamp ateşi akşam", 3, false, History.RECEIVED, 3);
            assertEquals(List.of("isik nerede", "Işık yarın İstanbul'da"), texts(h.search("IŞI", 10)));
            assertEquals(List.of("Işık yarın İstanbul'da"), texts(h.search("isik istanbul", 10)), "every word must match");
            assertEquals(List.of("Kamp ateşi akşam"), texts(h.search("ates aks", 10)));
            assertTrue(h.search("stanbul", 10).isEmpty(), "words match from their start");
        }
    }

    @Test
    void filtersBySenderChatFilesAndLinks() throws Exception {
        try (History h = History.open(dir.resolve("h.db"), "pw", 1000)) {
            h.add("dm:efe", "efe", "plan https://example.org/plan", 1, false, History.RECEIVED, 1);
            h.add("dm:efe", "emir", "plan tamam", 2, true, History.SENT, 2);
            h.add("g:4", "efe", "grupta plan", 3, false, History.RECEIVED, 3);
            h.addFile("dm:efe", "efe", new com.emirchat.chat.FileMessage(new byte[16], new byte[32], new byte[32], 1,
                    "plan.pdf", "application/pdf", 3), 4, false, History.RECEIVED, 4);
            assertEquals(List.of("plan.pdf", "grupta plan", "plan https://example.org/plan"), texts(h.search("plan from:Efe", 10)));
            assertEquals(List.of("plan.pdf", "plan tamam", "plan https://example.org/plan"), texts(h.search("plan in:dm:efe", 10)));
            assertEquals(List.of("plan.pdf"), texts(h.search("has:file", 10)));
            assertEquals(List.of("plan https://example.org/plan"), texts(h.search("has:link", 10)));
            assertEquals(List.of("grupta plan"), texts(h.search("from:@efe in:g:4", 10)));
        }
    }

    @Test
    void editsDeletesAndExpiryKeepTheIndexRight() throws Exception {
        try (History h = History.open(dir.resolve("h.db"), "pw", 1000)) {
            byte[] a = {1};
            byte[] b = {2};
            h.add("dm:efe", "efe", "eski metin", 1, false, History.RECEIVED, 1, 0, a, null);
            h.add("dm:efe", "efe", "gizli plan", 2, false, History.RECEIVED, 2, 0, b, null);
            h.add("dm:efe", "efe", "uçacak plan", 3, false, History.RECEIVED, 3, 10, new byte[] {3}, null);
            h.edit("dm:efe", a, "efe", "yeni metin");
            assertTrue(h.search("eski", 10).isEmpty());
            assertEquals(List.of("yeni metin"), texts(h.search("yeni", 10)));
            h.delete("dm:efe", b, "efe");
            h.deleteExpired(100);
            assertTrue(h.search("plan", 10).isEmpty());
            assertEquals(0, count(dir.resolve("h.db"), "SELECT COUNT(*) FROM search_index WHERE message_id IN "
                    + "(SELECT id FROM messages WHERE deleted = 1) OR message_id NOT IN (SELECT id FROM messages)"));
        }
    }

    @Test
    void oldHistoriesAreSearchableBeforeAndAfterTheBacklogIsIndexed() throws Exception {
        Path file = dir.resolve("h.db");
        try (History h = History.open(file, "pw", 1000)) {
            for (int i = 0; i < 30; i++) h.add("dm:efe", "efe", "mesaj " + i + (i == 7 ? " kervan" : ""), i, false, History.RECEIVED, i);
        }
        // as if written by 3.5: nothing indexed
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            s.execute("DELETE FROM search_index");
            s.execute("UPDATE messages SET indexed = 0");
        }
        try (History h = History.open(file, "pw", 1000)) {
            assertEquals(List.of("mesaj 7 kervan"), texts(h.search("kervan", 10)), "found by scanning");
            assertEquals(10, h.indexBacklog(10));
            assertEquals(20, h.indexBacklog(100));
            assertEquals(0, h.indexBacklog(100));
            assertEquals(List.of("mesaj 7 kervan"), texts(h.search("kervan", 10)), "found in the index");
        }
    }

    @Test
    void theIndexHoldsNoWordsAndDependsOnThePassword() throws Exception {
        Path file = dir.resolve("h.db");
        try (History h = History.open(file, "pw", 1000)) {
            h.add("dm:efe", "efe", "kervansaray buluşması", 1, false, History.RECEIVED, 1);
        }
        byte[] raw = Files.readAllBytes(file);
        String asText = new String(raw, StandardCharsets.ISO_8859_1);
        assertFalse(asText.contains("kervan") || asText.contains("bulus") || asText.contains("kervansaray"));
        try (History other = History.open(file, "another password", 1000)) {
            assertTrue(other.search("kervan", 10).isEmpty(), "another key gives other tokens");
        }
    }

    private static long count(Path file, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement();
             var rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }
}
