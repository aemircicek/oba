package com.emirchat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** The product's name and version, and where it keeps its data on each operating system. */
public final class AppInfo {
    public static final String NAME = "Scorpion";
    public static final String REPOSITORY = "aemircicek/secure-chat";
    public static final String WEBSITE = "https://github.com/" + REPOSITORY;

    private AppInfo() {}

    /** The version from the jar manifest, or "dev" when running from the build folder. */
    public static String version() {
        String v = AppInfo.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    /**
     * Where keys and history live: {@code %APPDATA%\Scorpion} on Windows,
     * {@code ~/Library/Application Support/Scorpion} on macOS, {@code $XDG_DATA_HOME/scorpion}
     * (default {@code ~/.local/share/scorpion}) elsewhere. Data from the old {@code ~/.securechat}
     * folder is moved there on first start.
     */
    public static Path defaultHome() {
        Path home = platformHome(System.getProperty("os.name", ""), System.getenv("APPDATA"),
                System.getenv("XDG_DATA_HOME"), Path.of(System.getProperty("user.home")));
        return adoptLegacy(Path.of(System.getProperty("user.home"), ".securechat"), home);
    }

    static Path platformHome(String os, String appData, String xdgDataHome, Path userHome) {
        String o = os.toLowerCase(Locale.ROOT);
        if (o.contains("win")) {
            return appData != null && !appData.isBlank() ? Path.of(appData, NAME) : userHome.resolve("AppData/Roaming/" + NAME);
        }
        if (o.contains("mac")) return userHome.resolve("Library/Application Support/" + NAME);
        return xdgDataHome != null && !xdgDataHome.isBlank() ? Path.of(xdgDataHome, "scorpion")
                : userHome.resolve(".local/share/scorpion");
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
