package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PaletteTest {
    private static Palette.Entry e(String title) {
        return new Palette.Entry(Palette.Kind.CHAT, title, null, title, () -> {});
    }

    @Test
    void fuzzyInOrderAndTurkishAware() {
        assertTrue(Palette.score("tsr", "# tasarım") > 0);
        assertTrue(Palette.score("tasarim", "# Tasarım") > 0);
        assertTrue(Palette.score("xyz", "efe") < 0);
        assertTrue(Palette.score("ef", "efe") > Palette.score("ef", "MobilCAD ekibi"), "prefix beats a scattered match");
        assertTrue(Palette.score("mc", "MobilCAD ekibi") > 0);
    }

    @Test
    void rankingPutsBestFirst() {
        List<Palette.Entry> all = List.of(e("MobilCAD ekibi"), e("efe"), e("# genel"), e("Settings"));
        assertEquals("efe", Palette.rank("efe", all, 5).get(0).title());
        assertEquals("# genel", Palette.rank("gen", all, 5).get(0).title());
        assertEquals(2, Palette.rank("", all, 2).size());
        assertTrue(Palette.rank("zzz", all, 5).isEmpty());
    }
}
