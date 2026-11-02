package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.emirchat.trust.SafetyNumber;
import com.google.zxing.common.BitMatrix;
import org.junit.jupiter.api.Test;

class QrCodesTest {
    private static int[] pixels(BitMatrix m, int scale, int padding) {
        int w = m.getWidth() * scale + 2 * padding;
        int[] argb = new int[w * w];
        java.util.Arrays.fill(argb, 0xFFFFFFFF);
        for (int y = 0; y < m.getHeight(); y++) {
            for (int x = 0; x < m.getWidth(); x++) {
                if (!m.get(x, y)) continue;
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) argb[(padding + y * scale + dy) * w + padding + x * scale + dx] = 0xFF16191D;
                }
            }
        }
        return argb;
    }

    @Test
    void aSafetyNumberSurvivesTheRoundTripThroughAPicture() {
        String payload = SafetyNumber.of("emir", "ab".repeat(32), "efe", "cd".repeat(32)).qrPayload();
        BitMatrix m = QrCodes.encode(payload, 200);
        int scale = 2;
        int padding = 40; // like a screenshot with some surroundings
        int size = m.getWidth() * scale + 2 * padding;
        assertEquals(payload, QrCodes.decode(pixels(m, scale, padding), size, size));
    }

    @Test
    void aPictureWithoutACodeGivesNull() {
        int[] blank = new int[100 * 100];
        java.util.Arrays.fill(blank, 0xFFFFFFFF);
        assertNull(QrCodes.decode(blank, 100, 100));
    }
}
