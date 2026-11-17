package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every text colour of both themes, and of every accent, against the backgrounds it is used on:
 * at least 4.5 to 1, the WCAG 2 level AA for normal text.
 */
class ContrastTest {
    private static final double AA = 4.5;

    /** The palette of {@code .root.light} or {@code .root.dark} in the stylesheet: name to "#rrggbb". */
    private static Map<String, String> palette(String theme) throws Exception {
        String css;
        try (var in = ContrastTest.class.getResourceAsStream("/com/emirchat/ui/app.css")) {
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int start = css.indexOf(".root." + theme + " {");
        assertTrue(start >= 0, theme);
        String block = css.substring(start, css.indexOf('}', start));
        Map<String, String> colours = new HashMap<>();
        Matcher m = Pattern.compile("-sc-([a-z0-9-]+):\\s*(#[0-9a-fA-F]{6});").matcher(block);
        while (m.find()) colours.put(m.group(1), m.group(2));
        return colours;
    }

    private static double luminance(String hex) {
        double[] rgb = new double[3];
        for (int i = 0; i < 3; i++) {
            double c = Integer.parseInt(hex.substring(1 + 2 * i, 3 + 2 * i), 16) / 255.0;
            rgb[i] = c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
    }

    static double ratio(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static void check(List<String> problems, String where, String text, String background) {
        double r = ratio(text, background);
        if (r < AA) problems.add(String.format(java.util.Locale.ROOT, "%s: %s on %s is %.2f", where, text, background, r));
    }

    @Test
    void theFormulaIsTheStandardOne() {
        assertTrue(Math.abs(ratio("#000000", "#ffffff") - 21.0) < 0.01);
        assertTrue(Math.abs(ratio("#777777", "#ffffff") - 4.48) < 0.01, "the well-known grey just below AA");
    }

    @Test
    void textIsReadableInBothThemes() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String theme : List.of("light", "dark")) {
            Map<String, String> c = palette(theme);
            for (String text : List.of("text", "text-2", "text-3")) {
                for (String background : List.of("bg", "surface", "surface-2", "rail")) {
                    check(problems, theme + " " + text + "/" + background, c.get(text), c.get(background));
                }
            }
            for (String text : List.of("accent-text", "success", "warning", "danger")) {
                for (String background : List.of("bg", "surface", "surface-2")) {
                    check(problems, theme + " " + text + "/" + background, c.get(text), c.get(background));
                }
            }
            check(problems, theme + " accent-text/accent-weak", c.get("accent-text"), c.get("accent-weak"));
            check(problems, theme + " danger/danger-weak", c.get("danger"), c.get("danger-weak"));
            check(problems, theme + " on-accent/accent", c.get("on-accent"), c.get("accent"));
            check(problems, theme + " on-own-bubble/own-bubble", c.get("on-own-bubble"), c.get("own-bubble"));
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void everyAccentIsReadable() throws Exception {
        List<String> problems = new ArrayList<>();
        String[] themes = {"light", "dark"};
        assertFalse(Appearance.ACCENTS.isEmpty());
        for (var accent : Appearance.ACCENTS.entrySet()) {
            if (accent.getValue() == null) continue; // the stylesheet's own colours, checked above
            for (int i = 0; i < 2; i++) {
                Map<String, String> c = palette(themes[i]);
                String[] p = accent.getValue()[i]; // accent, hover, on-accent, weak, text, own bubble
                String where = accent.getKey() + " " + themes[i] + " ";
                check(problems, where + "on-accent/accent", p[2], p[0]);
                check(problems, where + "on-accent/hover", p[2], p[1]);
                check(problems, where + "text/weak", p[4], p[3]);
                for (String background : List.of("bg", "surface", "surface-2")) {
                    check(problems, where + "text/" + background, p[4], c.get(background));
                }
                check(problems, where + "on-own-bubble/own-bubble", c.get("on-own-bubble"), p[5]);
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
