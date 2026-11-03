package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SkyTest {
    @Test
    void dayAndNight() {
        assertEquals(1, Sky.night(2));
        assertEquals(0, Sky.night(12));
        assertTrue(Sky.night(6) > 0 && Sky.night(6) < 1, "dawn is in between");
        assertTrue(Sky.night(19) > 0 && Sky.night(19) < 1, "and so is dusk");
    }

    @Test
    void colorsAreContinuousAndCoverTheDay() {
        for (double h = 0; h < 24; h += 0.25) {
            javafx.scene.paint.Color[] now = Sky.colors(h);
            javafx.scene.paint.Color[] next = Sky.colors(h + 0.01);
            assertNotNull(now[0]);
            assertTrue(Math.abs(now[0].getRed() - next[0].getRed()) < 0.05, "no jumps at " + h);
        }
        assertTrue(Sky.colors(12)[0].getBlue() > Sky.colors(12)[0].getRed(), "noon is blue");
        assertTrue(Sky.colors(1)[0].getBrightness() < 0.25, "midnight is dark");
    }

    @Test
    void sunAndMoonArcs() {
        assertEquals(0, Sky.arc(5, true));
        assertEquals(0.48, Sky.arc(12, true), 0.01);
        assertEquals(0, Sky.arc(18, false));
        assertTrue(Sky.arc(0, false) > 0.4 && Sky.arc(0, false) < 0.6, "the moon is high at midnight");
        assertEquals("New York", Sky.city(java.time.ZoneId.of("America/New_York")));
    }

    @Test
    void onlyRealTimeZonesAreAccepted() {
        assertEquals("Europe/Istanbul", AccountSession.parseZone("Europe/Istanbul").getId());
        assertNull(AccountSession.parseZone("GMT+03:00"));
        assertNull(AccountSession.parseZone("../../etc/passwd"));
        assertNull(AccountSession.parseZone("x".repeat(100)));
        assertNull(AccountSession.parseZone(null));
    }
}
