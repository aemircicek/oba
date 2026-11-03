package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.chat.Poll;
import com.emirchat.client.History;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatchUpTest {
    @TempDir Path dir;

    private static byte[] uid(int n) {
        byte[] b = new byte[16];
        b[0] = (byte) n;
        return b;
    }

    @Test
    void findsMentionsOpenPollsThreadsAndBusyChats() throws Exception {
        long now = System.currentTimeMillis();
        try (History h = History.open(dir.resolve("h.db"), "pw")) {
            // a busy group with a mention, a poll and a thread on my message
            long mine = h.add("g:1", "emir", "Taslak hazır", now - 5000, true, History.SENT, 0, 0, uid(1), new byte[0]);
            h.add("g:1", "efe", "@emir bakabilir misin?", now - 4000, false, History.RECEIVED, 0, 0, uid(2), new byte[0]);
            h.add("g:1", "ayse", "emir@example.org yazdım", now - 3900, false, History.RECEIVED, 0, 0, uid(3), new byte[0]);
            long poll = h.add("g:1", "ayse", new Poll("Ne zaman?", List.of("Salı", "Cuma")).toStored(), now - 3000, false,
                    History.RECEIVED, 0, 0, uid(4), new byte[0]);
            long reply = h.add("g:1", "efe", "Güzel olmuş", now - 2000, false, History.RECEIVED, 0, 0, uid(5), uid(1));
            h.markThread(reply);
            // a quieter DM, and a poll I already voted in
            h.add("dm:ayse", "ayse", "Selam", now - 1000, false, History.RECEIVED, 0, 0, uid(6), new byte[0]);
            long voted = h.add("dm:ayse", "ayse", new Poll("Pizza?", List.of("Evet", "Hayır")).toStored(), now - 900, false,
                    History.RECEIVED, 0, 0, uid(7), new byte[0]);
            h.vote("dm:ayse", uid(7), "emir", 0);

            CatchUp.Digest d = CatchUp.compute(CatchUp.of(h), "emir", now);
            assertEquals(6, d.unread(), "everything received and unread");
            assertEquals(2, d.conversations());
            assertEquals(1, d.mentions().size(), "an e-mail address is not a mention");
            assertEquals("@emir bakabilir misin?", d.mentions().get(0).text());
            assertEquals(List.of(poll), d.openPolls().stream().map(History.Message::id).toList(), "voted polls are left out");
            assertEquals(List.of(reply), d.threadReplies().stream().map(History.Message::id).toList());
            assertEquals("g:1", d.busiest().get(0).conversation());
            assertTrue(d.worthShowing());
            assertTrue(mine > 0 && voted > 0);

            h.markRead("g:1");
            h.markRead("dm:ayse");
            CatchUp.Digest after = CatchUp.compute(CatchUp.of(h), "emir", now);
            assertEquals(0, after.unread());
            assertTrue(after.worthShowing(), "the open poll still waits for my vote");
        }
    }

    @Test
    void wording() {
        I18n.use("en");
        try {
            assertEquals("5 minutes", CatchUp.away(5 * 60_000));
            assertEquals("3 hours and 12 minutes", CatchUp.away((3 * 60 + 12) * 60_000L));
            assertEquals("2 days", CatchUp.away(50L * 3600_000));
            assertFalse(CatchUp.greeting(9).isEmpty());
        } finally {
            I18n.use(I18n.SYSTEM);
        }
    }
}
