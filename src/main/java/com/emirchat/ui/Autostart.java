package com.emirchat.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * "Start Scorpion when I log in": the installed app is registered to start hidden in the tray
 * ({@code --background}). Only available for the installed app, whose launcher sets
 * {@code jpackage.app-path}; a jar started by hand has no stable path to register.
 */
final class Autostart {
    static final String BACKGROUND_FLAG = "--background";
    private static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    private Autostart() {}

    private static String os() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    static Path appPath() {
        String p = System.getProperty("jpackage.app-path");
        return p == null ? null : Path.of(p);
    }

    static boolean supported() {
        return appPath() != null;
    }

    static boolean enabled() {
        Path app = appPath();
        if (app == null) return false;
        try {
            if (os().contains("win")) return run(List.of("reg", "query", RUN_KEY, "/v", "Scorpion")) == 0;
            return Files.exists(entryFile());
        } catch (IOException e) {
            return false;
        }
    }

    static void set(boolean on) throws IOException {
        Path app = appPath();
        if (app == null) throw new IOException("Only the installed app can start automatically.");
        if (os().contains("win")) {
            int code = on
                    ? run(List.of("reg", "add", RUN_KEY, "/v", "Scorpion", "/t", "REG_SZ", "/d",
                            "\"" + app + "\" " + BACKGROUND_FLAG, "/f"))
                    : run(List.of("reg", "delete", RUN_KEY, "/v", "Scorpion", "/f"));
            if (code != 0 && on) throw new IOException("Windows did not accept the start-up entry.");
            return;
        }
        Path entry = entryFile();
        if (!on) {
            Files.deleteIfExists(entry);
            return;
        }
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, os().contains("mac") ? launchAgent(app) : desktopEntry(app), StandardCharsets.UTF_8);
    }

    private static Path entryFile() {
        Path home = Path.of(System.getProperty("user.home"));
        if (os().contains("mac")) return home.resolve("Library/LaunchAgents/org.scorpion.app.plist");
        String config = System.getenv("XDG_CONFIG_HOME");
        Path base = config != null && !config.isBlank() ? Path.of(config) : home.resolve(".config");
        return base.resolve("autostart/scorpion.desktop");
    }

    static String launchAgent(Path app) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                  <key>Label</key><string>org.scorpion.app</string>
                  <key>ProgramArguments</key>
                  <array><string>%s</string><string>%s</string></array>
                  <key>RunAtLoad</key><true/>
                </dict>
                </plist>
                """.formatted(xml(app.toString()), BACKGROUND_FLAG);
    }

    static String desktopEntry(Path app) {
        return """
                [Desktop Entry]
                Type=Application
                Name=Scorpion
                Exec="%s" %s
                X-GNOME-Autostart-enabled=true
                """.formatted(app.toString().replace("\"", "\\\""), BACKGROUND_FLAG);
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static int run(List<String> command) throws IOException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
