package com.emirchat.ui;

import java.util.prefs.Preferences;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

/** App-wide settings (not per account) and the window's last position. Never holds secrets. */
final class AppPrefs {
    private static final Preferences NODE = Preferences.userNodeForPackage(AppPrefs.class).node("app");

    private AppPrefs() {}

    /** Closing the window keeps Scorpion running in the tray (receiving messages and notifications). */
    static boolean keepRunning() {
        return NODE.getBoolean("keep-running", true);
    }

    static void setKeepRunning(boolean on) {
        NODE.putBoolean("keep-running", on);
    }

    /** True once the user has been told that closing the window keeps Scorpion running. */
    static boolean trayHintShown() {
        return NODE.getBoolean("tray-hint-shown", false);
    }

    static void setTrayHintShown() {
        NODE.putBoolean("tray-hint-shown", true);
    }

    static boolean welcomeShown() {
        return NODE.getBoolean("welcome-shown", false);
    }

    static void setWelcomeShown() {
        NODE.putBoolean("welcome-shown", true);
    }

    static void saveWindow(Stage stage) {
        if (stage.isIconified()) return;
        NODE.putBoolean("window.maximized", stage.isMaximized());
        if (stage.isMaximized()) return;
        NODE.putDouble("window.x", stage.getX());
        NODE.putDouble("window.y", stage.getY());
        NODE.putDouble("window.w", stage.getWidth());
        NODE.putDouble("window.h", stage.getHeight());
    }

    /** Puts the window where it was, if that place is still on one of the screens. */
    static void restoreWindow(Stage stage) {
        double w = NODE.getDouble("window.w", -1);
        double h = NODE.getDouble("window.h", -1);
        double x = NODE.getDouble("window.x", Double.NaN);
        double y = NODE.getDouble("window.y", Double.NaN);
        if (w > 0 && h > 0 && !Double.isNaN(x) && !Double.isNaN(y)
                && !Screen.getScreensForRectangle(new Rectangle2D(x, y, Math.max(w, 1), 40)).isEmpty()) {
            stage.setX(x);
            stage.setY(y);
            stage.setWidth(w);
            stage.setHeight(h);
        }
        stage.setMaximized(NODE.getBoolean("window.maximized", false));
    }
}
