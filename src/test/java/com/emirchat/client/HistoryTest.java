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
}
