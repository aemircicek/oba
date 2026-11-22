package com.emirchat.ui;

import javafx.scene.paint.Color;

/**
 * Turns one colour the user picked into the six accent colours of a palette (accent, hover,
 * text on the accent, weak background, accent text, own bubble) for the light or the dark
 * theme, keeping text readable: the accent text and the text on the accent reach the WCAG AA
 * contrast of 4.5:1 against what they are drawn on, whatever colour was picked.
 */
final class AccentMaker {
    /** Background the accent text is drawn on (the themes' surfaces). */
    static final Color LIGHT_SURFACE = Color.web("#fffcf7");
    static final Color DARK_SURFACE = Color.web("#171411");
    static final double AA = 4.5;

    private AccentMaker() {}

    static String[] palette(Color picked, boolean dark) {
        Color surface = dark ? DARK_SURFACE : LIGHT_SURFACE;
        // the accent itself: lighter on dark, darker on light, until it stands out from the surface (3:1, as for UI parts)
        Color accent = towards(picked, surface, 3.0, dark);
        Color hover = dark ? accent.deriveColor(0, 0.95, 1.12, 1) : accent.deriveColor(0, 1.0, 0.86, 1);
        Color onAccent = contrast(Color.WHITE, accent) >= contrast(Color.BLACK, accent) ? Color.WHITE : Color.web("#111111");
        Color weak = mix(accent, surface, dark ? 0.82 : 0.86);
        Color text = towards(accent, surface, AA, dark);
        // own bubbles carry text in the on-accent colour: keep that readable too
        Color bubble = accent;
        while (contrast(onAccent, bubble) < AA && bubble.getBrightness() > 0.05 && bubble.getBrightness() < 0.98) {
            bubble = onAccent.equals(Color.WHITE) ? bubble.deriveColor(0, 1, 0.92, 1) : bubble.deriveColor(0, 0.95, 1.06, 1);
        }
        return new String[] {hex(accent), hex(hover), hex(onAccent), hex(weak), hex(text), hex(bubble)};
    }

    /** Moves the colour lighter (on dark) or darker (on light) until it has {@code ratio} against {@code against}. */
    static Color towards(Color c, Color against, double ratio, boolean lighter) {
        Color x = c;
        for (int i = 0; i < 60 && contrast(x, against) < ratio; i++) {
            x = lighter ? mix(x, Color.WHITE, 0.08) : mix(x, Color.BLACK, 0.08);
        }
        return x;
    }

    /** {@code a} moved {@code amount} of the way to {@code b}. */
    static Color mix(Color a, Color b, double amount) {
        return new Color(a.getRed() + (b.getRed() - a.getRed()) * amount, a.getGreen() + (b.getGreen() - a.getGreen()) * amount,
                a.getBlue() + (b.getBlue() - a.getBlue()) * amount, 1);
    }

    /** WCAG contrast ratio, 1 to 21. */
    static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    static String hex(Color c) {
        return String.format(java.util.Locale.ROOT, "#%02x%02x%02x", Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255),
                Math.round(c.getBlue() * 255));
    }
}
