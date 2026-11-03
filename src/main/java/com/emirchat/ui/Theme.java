package com.emirchat.ui;

import java.util.Objects;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.scene.Parent;

/** Applies the stylesheet and keeps light/dark in sync with the operating system. */
final class Theme {
    static final String STYLESHEET = Objects.requireNonNull(
            Theme.class.getResource("/com/emirchat/ui/app.css"), "app.css missing").toExternalForm();

    private Theme() {}

    static void install(Parent root) {
        root.getStylesheets().add(STYLESHEET);
        String forced = System.getProperty("securechat.theme"); // "dark" or "light", for screenshots/testing
        if (forced != null) {
            apply(root, forced.equalsIgnoreCase("dark"));
            ROOTS.add(new java.lang.ref.WeakReference<>(root));
            return;
        }
        var preferences = Platform.getPreferences();
        apply(root, isDark());
        preferences.colorSchemeProperty().addListener((obs, old, scheme) -> apply(root, isDark()));
        ROOTS.add(new java.lang.ref.WeakReference<>(root));
    }

    private static final java.util.List<java.lang.ref.WeakReference<Parent>> ROOTS = new java.util.ArrayList<>();

    /** Dark unless the user chose light, or chose "system" and the system is light. */
    static boolean isDark() {
        String forced = System.getProperty("securechat.theme");
        if (forced != null) return forced.equalsIgnoreCase("dark");
        return switch (AppPrefs.theme()) {
            case "dark" -> true;
            case "light" -> false;
            default -> Platform.getPreferences().getColorScheme() == ColorScheme.DARK;
        };
    }

    /** Applies changed appearance settings to every open window. */
    static void refreshAll() {
        ROOTS.removeIf(r -> r.get() == null);
        for (var r : ROOTS) {
            Parent p = r.get();
            if (p != null) apply(p, isDark());
        }
    }

    static void apply(Parent root, boolean dark) {
        root.getStyleClass().removeAll("dark", "light");
        root.getStyleClass().add(dark ? "dark" : "light");
        Appearance.apply(root, dark);
    }
}
