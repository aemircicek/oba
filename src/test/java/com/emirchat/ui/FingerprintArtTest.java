package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void avatarIsMirroredAndStable() {
        String fp = Identity.generate().fingerprint();
        boolean[][] grid = FingerprintArt.avatarGrid(fp);
        for (boolean[] row : grid) {
            assertEquals(row[0], row[4]);
            assertEquals(row[1], row[3]);
        }
        assertTrue(Arrays.deepEquals(grid, FingerprintArt.avatarGrid(fp)));
    }

    @Test
    void newKeyGivesNewAvatar() {
        // 15 bits pick the pattern: two random keys collide with probability 1/32768, so try a few
        String first = Identity.generate().fingerprint();
        boolean differs = false;
        for (int i = 0; i < 4 && !differs; i++) {
            differs = !Arrays.deepEquals(FingerprintArt.avatarGrid(first), FingerprintArt.avatarGrid(Identity.generate().fingerprint()));
        }
        assertTrue(differs);
    }

    @Test
    void avatarNeverEmptyAndHuesInRange() {
        boolean[][] grid = FingerprintArt.avatarGrid("00".repeat(32));
        assertTrue(grid[2][2]);
        for (String fp : new String[] {"00".repeat(32), "ff".repeat(32), Identity.generate().fingerprint()}) {
            for (double h : FingerprintArt.avatarHues(fp)) assertTrue(h >= 0 && h < 360);
        }
        assertEquals(FingerprintArt.pseudoFingerprint("efe"), FingerprintArt.pseudoFingerprint("efe"));
        assertEquals(64, FingerprintArt.pseudoFingerprint("efe").length());
    }
}
