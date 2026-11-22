package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

/** Any picked colour gives readable text, in both themes. */
class AccentMakerTest {
    private static final String[] PICKS = {"#ffff00", "#000000", "#ffffff", "#00ffff", "#2f9e8f", "#ff0000", "#0000ff",
            "#808080", "#f2a43a", "#7f00ff", "#00ff00", "#331100"};

    @Test
    void textOnAndInTheAccentIsReadable() {
        for (String pick : PICKS) {
            for (boolean dark : new boolean[] {false, true}) {
                String[] p = AccentMaker.palette(Color.web(pick), dark);
                Color surface = dark ? AccentMaker.DARK_SURFACE : AccentMaker.LIGHT_SURFACE;
                String where = pick + (dark ? " dark" : " light");
                assertTrue(AccentMaker.contrast(Color.web(p[4]), surface) >= 4.5, where + ": accent text " + p[4]);
                assertTrue(AccentMaker.contrast(Color.web(p[2]), Color.web(p[0])) >= 4.5, where + ": text on the accent " + p[0]);
                assertTrue(AccentMaker.contrast(Color.web(p[2]), Color.web(p[5])) >= 4.5, where + ": text in own bubbles " + p[5]);
                assertTrue(AccentMaker.contrast(Color.web(p[0]), surface) >= 3.0, where + ": the accent itself");
            }
        }
    }

    @Test
    void contrastMatchesWcag() {
        assertEquals(21.0, AccentMaker.contrast(Color.BLACK, Color.WHITE), 0.01);
        assertEquals(1.0, AccentMaker.contrast(Color.RED, Color.RED), 0.001);
        assertEquals("#2f9e8f", AccentMaker.hex(Color.web("#2f9e8f")));
    }
}
