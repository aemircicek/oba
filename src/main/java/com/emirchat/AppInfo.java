package com.emirchat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** The product's name and version, and where it keeps its data on each operating system. */
public final class AppInfo {
    public static final String NAME = "Oba";
    /** The app's name until 3.4 (its data folder, saved password and autostart entry are moved over). */
    public static final String FORMER_NAME = "Scorpion";
    public static final String REPOSITORY = "aemircicek/oba";
    public static final String WEBSITE = "https://github.com/" + REPOSITORY;

    private AppInfo() {}

    /** The version from the jar manifest, or "dev" when running from the build folder. */
    public static String version() {
        String v = AppInfo.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    /**
     * Where keys and history live: {@code %APPDATA%\Oba} on Windows,
     * {@code ~/Library/Application Support/Oba} on macOS, {@code $XDG_DATA_HOME/oba}
     * (default {@code ~/.local/share/oba}) elsewhere. On first start the data of earlier versions
     * is moved there: the Scorpion folder, or the even older {@code ~/.securechat}.
     */
    public static Path defaultHome() {
        String os = System.getProperty("os.name", "");
        String appData = System.getenv("APPDATA");
        String xdg = System.getenv("XDG_DATA_HOME");
        Path user = Path.of(System.getProperty("user.home"));
        Path home = adoptLegacy(platformHome(os, appData, xdg, user, FORMER_NAME), platformHome(os, appData, xdg, user));
        return adoptLegacy(user.resolve(".securechat"), home);
    }

    static Path platformHome(String os, String appData, String xdgDataHome, Path userHome) {
        return platformHome(os, appData, xdgDataHome, userHome, NAME);
    }

    static Path platformHome(String os, String appData, String xdgDataHome, Path userHome, String name) {
        String o = os.toLowerCase(Locale.ROOT);
        if (o.contains("win")) {
            return appData != null && !appData.isBlank() ? Path.of(appData, name) : userHome.resolve("AppData/Roaming/" + name);
        }
        if (o.contains("mac")) return userHome.resolve("Library/Application Support/" + name);
        String lower = name.toLowerCase(Locale.ROOT);
        return xdgDataHome != null && !xdgDataHome.isBlank() ? Path.of(xdgDataHome, lower)
                : userHome.resolve(".local/share/" + lower);
    }

    /**
     * Moves the data folder of earlier versions to the new location, once. If that is not possible
     * (for example a different drive), the old folder keeps being used.
     */
    static Path adoptLegacy(Path legacy, Path target) {
        if (Files.exists(target) || !Files.isDirectory(legacy)) return target;
        try {
            Files.createDirectories(target.getParent());
            Files.move(legacy, target);
            return target;
        } catch (IOException e) {
            return legacy;
        }
    }
}
