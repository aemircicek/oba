package com.emirchat.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * A small log for troubleshooting ({@code logs/} in the data folder, three files of 1 MB at most).
 * It records what happened (signed in, connection lost, an error and its stack trace), never
 * passwords, keys or message contents.
 */
final class AppLog {
    private static final Logger LOG = Logger.getLogger("scorpion");
    private static Path dir;

    private AppLog() {}

    static synchronized void init(Path home) {
        if (dir != null) return;
        try {
            dir = Files.createDirectories(home.resolve("logs"));
            System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s %5$s%6$s%n");
            FileHandler file = new FileHandler(dir.resolve("scorpion-%g.log").toString(), 1_000_000, 3, true);
            file.setFormatter(new SimpleFormatter());
            LOG.addHandler(file);
            LOG.setUseParentHandlers(false);
            LOG.setLevel(Level.INFO);
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> LOG.log(Level.SEVERE, "Uncaught in " + t.getName(), e));
        } catch (IOException e) {
            dir = null; // no log, the app still works
        }
    }

    static Path folder() {
        return dir;
    }

    static void info(String message) {
        LOG.info(message);
    }

    static void warn(String message, Throwable error) {
        LOG.log(Level.WARNING, message, error);
    }
}
