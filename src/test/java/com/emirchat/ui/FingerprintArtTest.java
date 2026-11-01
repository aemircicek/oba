package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.emirchat.crypto.Identity;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Pure logic only: runs without a display. */
class FingerprintArtTest {
    @Test
    void sameKeyGivesSamePatternOnBothSides() {
        String fp = Identity.generate().fingerprint();
        assertArrayEquals(FingerprintArt.hues(fp), FingerprintArt.hues(fp));
        assertEquals(32, FingerprintArt.hues(fp).length);
    }

    @Test
    void differentKeysGiveDifferentPatterns() {
        double[] a = FingerprintArt.hues(Identity.generate().fingerprint());
        double[] b = FingerprintArt.hues(Identity.generate().fingerprint());
        assertFalse(Arrays.equals(a, b));
    }

    @Test
    void huesStayInRange() {
        for (double h : FingerprintArt.hues("ff".repeat(32))) {
            assertEquals(358.59375, h, 1e-9);
        }
        for (double h : FingerprintArt.hues("00".repeat(32))) {
            assertEquals(0.0, h);
        }
    }

    @Test
    void hexLinesCoverTheWholeFingerprint() {
        String fp = Identity.generate().fingerprint();
        String[] four = FingerprintArt.hexLines(fp, 4);
        assertEquals(4, four.length);
        assertEquals(fp, String.join("", four).replace(" ", ""));
        assertEquals("abcd 0123 4567 89ab", FingerprintArt.hexLines("abcd0123456789ab".repeat(4), 4)[0]);
        assertEquals(fp, String.join("", FingerprintArt.hexLines(fp, 2)).replace(" ", ""));
    }

    @Test
    void rejectsWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> FingerprintArt.hues("abcd"));
    }
}
