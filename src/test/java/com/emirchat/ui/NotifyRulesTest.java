package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotifyRulesTest {
    private final Map<String, String> store = new HashMap<>();
    private final NotifyRules rules = new NotifyRules(store::get, (k, v) -> {
        if (v.isEmpty()) store.remove(k);
        else store.put(k, v);
    });
    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");

    private static ZonedDateTime at(int hour, int minute) {
        return ZonedDateTime.of(2026, 11, 22, hour, minute, 0, 0, IST);
    }

    @Test
    void levelsDecidePerChat() {
        assertEquals(NotifyRules.Decision.NOTIFY, rules.decide("g:1", false, at(12, 0)));
        rules.setLevel("g:1", NotifyRules.Level.MENTIONS);
        assertEquals(NotifyRules.Decision.NONE, rules.decide("g:1", false, at(12, 0)));
        assertEquals(NotifyRules.Decision.NOTIFY, rules.decide("g:1", true, at(12, 0)));
        rules.setLevel("g:1", NotifyRules.Level.NONE);
        assertEquals(NotifyRules.Decision.NONE, rules.decide("g:1", true, at(12, 0)));
        assertFalse(rules.counts("g:1", 0), "a silenced chat does not count toward the unread number");
        rules.setLevel("g:1", NotifyRules.Level.ALL);
        assertTrue(store.isEmpty(), "the default is not stored");
    }

    @Test
    void mutingEndsByItself() {
        long now = at(12, 0).toInstant().toEpochMilli();
        rules.muteUntil("dm:efe", NotifyRules.until(60, at(12, 0)));
        assertEquals(NotifyRules.Decision.NONE, rules.decide("dm:efe", true, at(12, 30)));
        assertEquals(NotifyRules.Decision.NOTIFY, rules.decide("dm:efe", true, at(13, 1)));
        assertTrue(rules.muted("dm:efe", now));
        assertEquals(at(8, 0).plusDays(1).toInstant().toEpochMilli(), NotifyRules.until(-2, at(22, 0)));
        assertEquals(at(8, 0).toInstant().toEpochMilli(), NotifyRules.until(-2, at(3, 0)), "after midnight: this morning");
        assertEquals(NotifyRules.FOREVER, NotifyRules.until(-1, at(3, 0)));
        rules.muteUntil("dm:efe", 0);
        assertFalse(rules.muted("dm:efe", now));
    }

    @Test
    void quietHoursCrossMidnightAndLetMentionsThroughSilently() {
        rules.setQuiet(new NotifyRules.Quiet(true, LocalTime.of(23, 0), LocalTime.of(8, 0), true));
        assertEquals(NotifyRules.Decision.NOTIFY, rules.decide("g:1", false, at(22, 59)));
        assertEquals(NotifyRules.Decision.NONE, rules.decide("g:1", false, at(23, 0)));
        assertEquals(NotifyRules.Decision.SILENT, rules.decide("g:1", true, at(2, 0)));
        assertEquals(NotifyRules.Decision.NOTIFY, rules.decide("g:1", false, at(8, 0)));
        rules.setQuiet(new NotifyRules.Quiet(true, LocalTime.of(13, 0), LocalTime.of(14, 0), false));
        assertEquals(NotifyRules.Decision.NONE, rules.decide("g:1", true, at(13, 30)));
        assertEquals(rules.quiet(), NotifyRules.Quiet.decode(rules.quiet().encode()));
        assertEquals(NotifyRules.Quiet.DEFAULT, NotifyRules.Quiet.decode("garbage"));
    }
}
