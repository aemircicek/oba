package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.ui.SlashCommands.Result;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SlashCommandsTest {
    @BeforeEach
    void english() {
        I18n.use("en");
    }

    @AfterEach
    void reset() {
        I18n.use(I18n.SYSTEM);
    }

    private static Result run(String text) {
        return SlashCommands.parse(text, "emir", new Random(42));
    }

    @Test
    void plainTextAndPathsAreNotCommands() {
        assertInstanceOf(SlashCommands.NotACommand.class, run("Selam!"));
        assertInstanceOf(SlashCommands.NotACommand.class, run("/usr/bin/java nerede?"));
        assertEquals(new SlashCommands.Text("/roll is a command"), run("//roll is a command"));
        assertInstanceOf(SlashCommands.Problem.class, run("/nope"));
    }

    @Test
    void polls() {
        SlashCommands.PollResult p = (SlashCommands.PollResult) run("/poll Ne zaman? | Salı | Perşembe |  ");
        assertEquals("Ne zaman?", p.poll().question());
        assertEquals(List.of("Salı", "Perşembe"), p.poll().options());
        assertInstanceOf(SlashCommands.Problem.class, run("/poll Tek seçenek | A"));
        assertInstanceOf(SlashCommands.PollResult.class, run("/anket Soru | A | B"), "Turkish name works too");
    }

    @Test
    void dice() {
        String one = ((SlashCommands.Text) run("/roll")).text();
        assertTrue(one.startsWith("🎲 1d6: **"), one);
        String two = ((SlashCommands.Text) run("/roll 3d20")).text();
        assertTrue(two.matches("🎲 3d20: \\d+ \\+ \\d+ \\+ \\d+ = \\*\\*\\d+\\*\\*"), two);
        for (int seed = 0; seed < 200; seed++) {
            String t = ((SlashCommands.Text) SlashCommands.parse("/roll d6", "emir", new Random(seed))).text();
            int v = Integer.parseInt(t.replaceAll("\\D+", " ").strip().split(" ")[2]);
            assertTrue(v >= 1 && v <= 6, t);
        }
        assertInstanceOf(SlashCommands.Problem.class, run("/roll 99d6"));
        assertInstanceOf(SlashCommands.Problem.class, run("/roll d1"));
        assertInstanceOf(SlashCommands.Problem.class, run("/roll lots"));
    }

    @Test
    void remindersMeShrugTopic() {
        assertEquals(new SlashCommands.Remind(600_000, "çayı demle"), run("/remind 10m çayı demle"));
        assertEquals(new SlashCommands.Remind(7_200_000, "toplantı"), run("/hatirlat 2sa toplantı"));
        assertInstanceOf(SlashCommands.Problem.class, run("/remind 10m"));
        assertInstanceOf(SlashCommands.Problem.class, run("/remind 30d too far"));
        assertEquals(new SlashCommands.Text("_emir dans ediyor_"), run("/me dans ediyor"));
        assertEquals(new SlashCommands.Text("tamam ¯\\_(ツ)_/¯"), run("/shrug tamam"));
        assertEquals(1, Markdown.inline("¯\\_(ツ)_/¯").size(), "the shrug stays plain text");
        assertEquals(new SlashCommands.Topic("Haftalık plan"), run("/topic Haftalık plan"));
        assertInstanceOf(SlashCommands.Help.class, run("/help"));
        assertTrue(run("/flip") instanceof SlashCommands.Text t && t.text().startsWith("🪙 "));
    }

    @Test
    void completion() {
        assertEquals(List.of("poll"), SlashCommands.complete("/po"));
        assertEquals(SlashCommands.NAMES, SlashCommands.complete("/"));
        assertEquals(List.of(), SlashCommands.complete("/poll x"));
    }

    @Test
    void amberTakesADateOrASpanAndText() {
        long now = java.time.LocalDate.of(2026, 11, 22).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        var dated = (SlashCommands.Amber) SlashCommands.amber("2027-01-01 Mutlu yıllar", now);
        assertEquals(java.time.LocalDate.of(2027, 1, 1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                dated.capsule().opensAt());
        assertEquals("Mutlu yıllar", dated.capsule().text());
        var turkish = (SlashCommands.Amber) SlashCommands.amber("31.12.2026 yılbaşı", now);
        assertEquals(java.time.LocalDate.of(2026, 12, 31).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                turkish.capsule().opensAt());
        var span = (SlashCommands.Amber) SlashCommands.amber("3g sürpriz", now);
        assertEquals(now + 3 * 86_400_000L, span.capsule().opensAt());
        assertInstanceOf(SlashCommands.Problem.class, SlashCommands.amber("2020-01-01 geçmiş", now), "not in the past");
        assertInstanceOf(SlashCommands.Problem.class, SlashCommands.amber("2027-01-01", now), "needs text");
        assertInstanceOf(SlashCommands.Problem.class, SlashCommands.amber("2027-02-30 x", now), "a real date");
        assertInstanceOf(SlashCommands.Problem.class, SlashCommands.amber("2040-01-01 x", now), "at most ten years");
        assertInstanceOf(SlashCommands.Nudge.class, run("/dürt"));
        assertInstanceOf(SlashCommands.Amber.class, run("/kehribar 1h x"));
    }
}
