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
