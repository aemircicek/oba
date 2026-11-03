package com.emirchat.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import javafx.scene.Parent;

/**
 * The user's look-and-feel choices on top of the theme: accent colour, text size, compact mode
 * and a forced light/dark theme. Colours are CSS looked-up colours set inline on the root, so
 * everything that uses {@code -sc-accent} follows.
 */
final class Appearance {
    /** Accent name → {light palette, dark palette}; each is accent, hover, on-accent, weak, text, own bubble. */
    static final Map<String, String[][]> ACCENTS = new LinkedHashMap<>();

    static {
        ACCENTS.put("amber", null); // the stylesheet's own colours
        ACCENTS.put("venom", new String[][] {
                {"#13824f", "#0f7044", "#ffffff", "#e2f3ea", "#0f6e42", "#13824f"},
                {"#34c27f", "#46d08f", "#04170d", "#13281f", "#5ad69a", "#1d7a4d"}});
        ACCENTS.put("sky", new String[][] {
                {"#1f6fc4", "#195ea8", "#ffffff", "#e1edf9", "#185aa0", "#1f6fc4"},
                {"#5aa9f5", "#78baf7", "#04121f", "#132233", "#7dbcf7", "#235a92"}});
        ACCENTS.put("rose", new String[][] {
                {"#c23a63", "#a72f54", "#ffffff", "#f9e3ea", "#a32e52", "#c23a63"},
                {"#f27598", "#f590ad", "#24060f", "#301821", "#f590ad", "#97324f"}});
        ACCENTS.put("violet", new String[][] {
                {"#6c4fc4", "#5b41a8", "#ffffff", "#ece6f9", "#5a40a6", "#6c4fc4"},
                {"#a58cf5", "#b8a3f7", "#120a26", "#221b35", "#bba8f8", "#5b46a6"}});
    }

    private static final String[] VARS = {"-sc-accent", "-sc-accent-hover", "-sc-on-accent", "-sc-accent-weak",
            "-sc-accent-text", "-sc-own-bubble"};

    private Appearance() {}

    /** Re-applies every choice; called by {@link Theme} and whenever a setting changes. */
    static void apply(Parent root, boolean dark) {
        String[][] palette = ACCENTS.get(AppPrefs.accent());
        if (palette == null) {
            root.setStyle("");
        } else {
            String[] p = palette[dark ? 1 : 0];
            StringBuilder css = new StringBuilder();
            for (int i = 0; i < VARS.length; i++) css.append(VARS[i]).append(": ").append(p[i]).append("; ");
            root.setStyle(css.toString());
        }
        root.getStyleClass().removeAll("text-small", "text-large", "compact");
        String size = AppPrefs.textSize();
        if (size.equals("small") || size.equals("large")) root.getStyleClass().add("text-" + size);
        if (AppPrefs.compact()) root.getStyleClass().add("compact");
    }
}
