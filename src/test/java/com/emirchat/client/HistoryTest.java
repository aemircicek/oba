package com.emirchat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryTest {
    @TempDir Path dir;

    @Test
    void storesLoadsAndSummarises() throws Exception {
        try (History h = History.open(dir.resolve("h.db"), "pw", 1000)) {
            long a = h.add("dm:efe", "emir", "selam", 1, true, History.SENT, 0);
            h.add("dm:efe", "efe", "merhaba", 2, false, History.RECEIVED, 7);
            h.add("g:3", "ayse", "grup mesajı", 3, false, History.RECEIVED, 8);
            h.setStatus(a, History.SENT, 42);
            assertEquals(a, h.markDelivered(42));

            List<History.Message> dm = h.load("dm:efe", 50);
            assertEquals(List.of("selam", "merhaba"), dm.stream().map(History.Message::text).toList());
            assertEquals(History.DELIVERED, dm.get(0).status());

            List<History.Summary> s = h.summaries();
            assertEquals("g:3", s.get(0).conversation(), "newest conversation first");
            assertEquals(1, s.get(1).unread());
            h.markRead("dm:efe");
            assertEquals(0, h.summaries().get(1).unread());
        }
    }

    @Test
    void textsAreEncryptedOnDisk() throws Exception {
        Path file = dir.resolve("h.db");
        try (History h = History.open(file, "pw", 1000)) {
            h.add("dm:efe", "emir", "the safe code is 4711", 1, true, History.SENT, 0);
        }
        for (Path p : Files.list(dir).toList()) {
            String raw = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            assertFalse(raw.contains("safe code"), p + " contains plaintext");
        }
        try (History wrong = History.open(file, "not the password", 1000)) {
            assertEquals("[unreadable]", wrong.load("dm:efe", 1).get(0).text());
        }
        try (History right = History.open(file, "pw", 1000)) {
            assertTrue(right.load("dm:efe", 1).get(0).text().contains("4711"));
        }
    }

    @Test
    void fileMessagesKeepTheirKeyEncryptedAtRest() throws Exception {
        Path file = dir.resolve("h.db");
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 0x7e); // '~' repeated: easy to spot in a raw dump
        com.emirchat.chat.FileMessage f = new com.emirchat.chat.FileMessage(new byte[16], key, new byte[32], 2048,
                "bütçe-2026.xlsx", "application/vnd.ms-excel", 5);
        try (History h = History.open(file, "pw", 1000)) {
            h.add("dm:efe", "efe", "önce bir mesaj", 4, false, History.RECEIVED, 1);
            h.addFile("dm:efe", "efe", f, 5, false, History.RECEIVED, 2);

            List<History.Message> loaded = h.load("dm:efe", 10);
            assertEquals(null, loaded.get(0).file());
            assertEquals(f, loaded.get(1).file());
            assertEquals("bütçe-2026.xlsx", loaded.get(1).text());
            assertEquals("📎 bütçe-2026.xlsx", h.summaries().get(0).lastText());
        }
        for (Path p : Files.list(dir).toList()) {
            String raw = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            assertFalse(raw.contains("~~~~~~~~"), p + " contains the file key");
            assertFalse(raw.contains("xlsx"), p + " contains the file name");
        }
    }

    @Test
    void disappearingMessagesAreDeletedOnTime() throws Exception {
        try (History h = History.open(dir.resolve("h.db"), "pw", 1000)) {
            com.emirchat.chat.FileMessage f = new com.emirchat.chat.FileMessage(new byte[16], new byte[32], new byte[32],
                    10, "foto.png", "image/png", 1);
            h.add("dm:efe", "efe", "kalıcı", 1, false, History.RECEIVED, 1);
            long soon = h.add("dm:efe", "efe", "5 dakika sonra silinir", 2, false, History.RECEIVED, 2, 10_000);
            long file = h.addFile("dm:efe", "efe", f, 3, false, History.RECEIVED, 3, 20_000);
            h.add("g:1", "ayse", "bir gün", 4, false, History.RECEIVED, 4, 90_000);

            assertEquals(10_000, h.load("dm:efe", 10).get(1).expiresAt());
            assertTrue(h.deleteExpired(9_999).isEmpty(), "nothing is due yet");

            List<History.Expired> gone = h.deleteExpired(20_000);
            assertEquals(List.of(soon, file), gone.stream().map(History.Expired::id).toList());
            assertEquals(f, gone.get(1).file(), "the file is reported so its cached copy can be deleted");
            assertEquals(List.of("kalıcı"), h.load("dm:efe", 10).stream().map(History.Message::text).toList());
            assertEquals(1, h.load("g:1", 10).size());
        }
    }

    @Test
    void timersArePerConversationAndPersist() throws Exception {
        Path file = dir.resolve("h.db");
        try (History h = History.open(file, "pw", 1000)) {
            assertEquals(0, h.timer("dm:efe"));
            h.setTimer("dm:efe", 3600);
            h.setTimer("g:2", 300);
            h.setTimer("g:2", 0);
        }
        try (History h = History.open(file, "pw", 1000)) {
            assertEquals(3600, h.timer("dm:efe"));
            assertEquals(0, h.timer("g:2"));
        }
    }

    private static byte[] uid(int n) {
        byte[] id = new byte[16];
        id[0] = (byte) n;
        id[15] = 1;
        return id;
    }

    @Test
    void onlyTheAuthorCanEditOrDelete() throws Exception {
        try (History h = History.open(dir.resolve("h.db"), "pw", 1000)) {
            long theirs = h.add("dm:efe", "efe", "orijinal", 1, false, History.RECEIVED, 1, 0, uid(1), null);
            assertEquals(-1, h.edit("dm:efe", uid(1), "mallory", "sahte"), "someone else cannot edit");
            assertEquals(theirs, h.edit("dm:efe", uid(1), "efe", "düzeltilmiş"));
            History.Message edited = h.get(theirs);
            assertEquals("düzeltilmiş", edited.text());
            assertTrue(edited.edited());

            assertEquals(null, h.delete("dm:efe", uid(1), "mallory"));
            h.react("dm:efe", uid(1), "emir", "👍");
            assertTrue(h.delete("dm:efe", uid(1), "efe") != null, "the author can delete");
            History.Message gone = h.get(theirs);
            assertTrue(gone.deleted());
            assertEquals(History.DELETED_TEXT, gone.text());
            assertTrue(h.reactions("dm:efe").isEmpty(), "reactions go with the message");
            assertEquals(-1, h.edit("dm:efe", uid(1), "efe", "geri getir"), "a deleted message stays deleted");
            assertEquals(-1, h.react("dm:efe", uid(1), "emir", "❤️"));
            assertEquals(History.DELETED_TEXT, h.summaries().get(0).lastText());
            assertEquals(-1, h.edit("g:1", uid(1), "efe", "x"), "ids are looked up per conversation");
        }
    }

    @Test
    void reactionsOnePerPersonAndRemovable() throws Exception {
        try (History h = History.open(dir.resolve("h.db"), "pw", 1000)) {
            long id = h.add("g:4", "ayse", "toplantı yarın", 1, false, History.RECEIVED, 1, 0, uid(2), null);
            h.react("g:4", uid(2), "emir", "👍");
            h.react("g:4", uid(2), "efe", "❤️");
            h.react("g:4", uid(2), "emir", "😂"); // changes emir's reaction
            assertEquals(java.util.Map.of("emir", "😂", "efe", "❤️"), h.reactions("g:4").get(id));
            h.react("g:4", uid(2), "efe", "");
            assertEquals(java.util.Map.of("emir", "😂"), h.reactions("g:4").get(id));
        }
    }

    @Test
    void readReceiptsMarkOurMessagesUpToTheTarget() throws Exception {
        try (History h = History.open(dir.resolve("h.db"), "pw", 1000)) {
            long a = h.add("dm:efe", "emir", "bir", 1, true, History.DELIVERED, 1, 0, uid(3), null);
            long b = h.add("dm:efe", "emir", "iki", 2, true, History.SENT, 2, 0, uid(4), null);
            h.add("dm:efe", "efe", "cevap", 3, false, History.RECEIVED, 3, 0, uid(5), null);
            long c = h.add("dm:efe", "emir", "üç", 4, true, History.SENT, 4, 0, uid(6), null);
            assertEquals(List.of(a, b), h.markReadUpTo("dm:efe", uid(4)));
            assertEquals(History.READ, h.get(b).status());
            assertEquals(History.SENT, h.get(c).status(), "later messages are not read yet");
            assertTrue(h.markReadUpTo("dm:efe", uid(5)).isEmpty(), "a receipt must point at one of our messages");
            assertTrue(h.markReadUpTo("dm:ayse", uid(6)).isEmpty(), "only in that conversation");

            // a receipt that overtakes our own bookkeeping is not undone by it
            long d = h.add("dm:efe", "emir", "dört", 5, true, History.PENDING, 0, 0, uid(13), null);
            assertEquals(List.of(c, d), h.markReadUpTo("dm:efe", uid(13)));
            h.setStatus(d, History.SENT, 99);
            assertEquals(History.READ, h.get(d).status());
            h.markDelivered(99);
            assertEquals(History.READ, h.get(d).status());
        }
    }

    @Test
    void repliesKeepTheirTarget() throws Exception {
        try (History h = History.open(dir.resolve("h.db"), "pw", 1000)) {
            h.add("dm:efe", "efe", "akşam müsait misin?", 1, false, History.RECEIVED, 1, 0, uid(7), null);
            long reply = h.add("dm:efe", "emir", "evet", 2, true, History.SENT, 2, 0, uid(8), uid(7));
            History.Message m = h.get(reply);
            assertTrue(java.util.Arrays.equals(uid(7), m.replyTo()));
            assertEquals("akşam müsait misin?", h.find("dm:efe", m.replyTo()).text());
        }
    }

    @Test
    void searchFindsTextsAndFileNamesButNotDeletedMessages() throws Exception {
        try (History h = History.open(dir.resolve("h.db"), "pw", 1000)) {
            h.add("dm:efe", "efe", "MobilCAD toplantısı yarın", 1, false, History.RECEIVED, 1, 0, uid(9), null);
            h.add("g:2", "ayse", "mobilcad için DWG araştırdım", 2, false, History.RECEIVED, 2, 0, uid(10), null);
            h.addFile("dm:efe", "efe", new com.emirchat.chat.FileMessage(new byte[16], new byte[32], new byte[32], 1,
                    "MobilCAD-sunum.pdf", "application/pdf", 3), 3, false, History.RECEIVED, 3, 0, uid(11), null);
            h.add("dm:efe", "efe", "silinecek mobilcad notu", 4, false, History.RECEIVED, 4, 0, uid(12), null);
            h.delete("dm:efe", uid(12), "efe");
            List<String> found = h.search("mobilcad", 10).stream().map(History.Message::text).toList();
            assertEquals(List.of("MobilCAD-sunum.pdf", "mobilcad için DWG araştırdım", "MobilCAD toplantısı yarın"), found);
            assertTrue(h.search("   ", 10).isEmpty());
        }
    }

    @Test
    void settingsPersist() throws Exception {
        Path file = dir.resolve("h.db");
        try (History h = History.open(file, "pw", 1000)) {
            assertEquals("true", h.setting("send-typing", "true"));
            h.setSetting("send-typing", "false");
        }
        try (History h = History.open(file, "pw", 1000)) {
            assertEquals("false", h.setting("send-typing", "true"));
        }
    }

    @Test
    void historiesFromBeforeFileSupportAreUpgraded() throws Exception {
        Path file = dir.resolve("old.db");
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file);
             java.sql.Statement s = c.createStatement()) {
            s.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, conversation TEXT NOT NULL, "
                    + "sender TEXT NOT NULL, body BLOB NOT NULL, sent_at INTEGER NOT NULL, outgoing INTEGER NOT NULL, "
                    + "status INTEGER NOT NULL, envelope_id INTEGER NOT NULL, unread INTEGER NOT NULL)");
        }
        try (History h = History.open(file, "pw", 1000)) {
            h.add("dm:efe", "emir", "still works", 1, true, History.SENT, 0);
            assertEquals("still works", h.load("dm:efe", 1).get(0).text());
        }
    }
}
