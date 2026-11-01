package com.emirchat.ui;

import java.util.HexFormat;

/**
 * Turns a 32-byte fingerprint into a deterministic colour pattern (like SSH's randomart), so two
 * people can spot a mismatch at a glance before comparing the hex digits.
 */
final class FingerprintArt {
    static final int COLUMNS = 8;
    static final int ROWS = 4;

    private FingerprintArt() {}

    /** Hue in degrees (0-360) for each of the 32 cells, row by row. */
    static double[] hues(String fingerprintHex) {
        byte[] bytes = HexFormat.of().parseHex(fingerprintHex);
        if (bytes.length != COLUMNS * ROWS) throw new IllegalArgumentException("Expected a 32-byte fingerprint");
        double[] hues = new double[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            hues[i] = (bytes[i] & 0xFF) * 360.0 / 256.0;
        }
        return hues;
    }

    /** A stable accent hue for avatars, derived from the fingerprint. */
    static double avatarHue(String fingerprintHex) {
        byte[] bytes = HexFormat.of().parseHex(fingerprintHex);
        return ((bytes[0] & 0xFF) << 8 | (bytes[1] & 0xFF)) * 360.0 / 65536.0;
    }

    /** Fingerprint as {@code rows} lines of space-separated 4-character groups. */
    static String[] hexLines(String fingerprintHex, int rows) {
        int groupsPerRow = (fingerprintHex.length() / 4) / rows;
        String[] lines = new String[rows];
        for (int r = 0; r < rows; r++) {
            StringBuilder sb = new StringBuilder();
            for (int g = 0; g < groupsPerRow; g++) {
                int start = (r * groupsPerRow + g) * 4;
                if (g > 0) sb.append(' ');
                sb.append(fingerprintHex, start, start + 4);
            }
            lines[r] = sb.toString();
        }
        return lines;
    }
}
