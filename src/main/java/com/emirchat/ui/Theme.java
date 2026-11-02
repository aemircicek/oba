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
            return;
        }
        var preferences = Platform.getPreferences();
        apply(root, preferences.getColorScheme() == ColorScheme.DARK);
        preferences.colorSchemeProperty().addListener((obs, old, scheme) -> apply(root, scheme == ColorScheme.DARK));
    }

    static void apply(Parent root, boolean dark) {
        root.getStyleClass().removeAll("dark", "light");
        root.getStyleClass().add(dark ? "dark" : "light");
    }
}
