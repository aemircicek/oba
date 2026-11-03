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

    /** Width and height of the avatar pattern (mirrored left to right, like an identicon). */
    static final int AVATAR_GRID = 5;

    /**
     * The avatar pattern for a key: a 5x5 grid mirrored around the middle column, so it reads as
     * a little emblem. Fifteen bits of the fingerprint choose the cells; the same key always gives
     * the same picture and a new key gives a different one.
     */
    static boolean[][] avatarGrid(String fingerprintHex) {
        byte[] bytes = HexFormat.of().parseHex(fingerprintHex);
        boolean[][] grid = new boolean[AVATAR_GRID][AVATAR_GRID];
        int bit = 0;
        int filled = 0;
        for (int row = 0; row < AVATAR_GRID; row++) {
            for (int col = 0; col < 3; col++, bit++) {
                boolean on = (bytes[4 + bit / 8] >> (bit % 8) & 1) == 1;
                grid[row][col] = on;
                grid[row][AVATAR_GRID - 1 - col] = on;
                if (on) filled++;
            }
        }
        if (filled == 0) grid[2][2] = true; // never an empty picture
        return grid;
    }

    /** Two hues (degrees) for the avatar's background gradient: a base and a neighbour 30-110 degrees on. */
    static double[] avatarHues(String fingerprintHex) {
        byte[] bytes = HexFormat.of().parseHex(fingerprintHex);
        double base = avatarHue(fingerprintHex);
        double offset = 30 + (bytes[2] & 0xFF) * 80.0 / 255.0;
        return new double[] {base, (base + offset) % 360};
    }

    /** A stand-in "fingerprint" for a name whose key we do not know (not security relevant). */
    static String pseudoFingerprint(String name) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("scorpion-avatar:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
