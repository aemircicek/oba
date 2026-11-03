package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EmojiTest {
    @Test
    void englishAndTurkishNames() {
        assertEquals("❤️", Emoji.suggest("heart", 5).get(0).emoji());
        assertEquals("❤️", Emoji.suggest("kalp", 5).get(0).emoji());
        assertEquals("🦂", Emoji.suggest("akrep", 5).get(0).emoji());
        assertTrue(Emoji.suggest("ateş", 5).stream().anyMatch(e -> e.emoji().equals("🔥")), "Turkish letters fold");
        assertTrue(Emoji.suggest("th", 3).size() <= 3);
    }

    @Test
    void shortcodeOnlyAfterSpaceOrStart() {
        assertEquals("smi", Emoji.typedShortcode(":smi", 4));
        assertEquals("ka", Emoji.typedShortcode("selam :ka", 9));
        assertNull(Emoji.typedShortcode("saat 10:30", 10), "times are not shortcodes");
        assertNull(Emoji.typedShortcode("https://x", 9));
        assertNull(Emoji.typedShortcode("hi :s", 5), "needs two letters");
    }

    @Test
    void namesAreUnique() {
        Set<String> names = new HashSet<>();
        for (Emoji.Entry e : Emoji.ALL) assertTrue(names.add(e.name()), "duplicate " + e.name());
    }
}
