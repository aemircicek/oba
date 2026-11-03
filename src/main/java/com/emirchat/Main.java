package com.emirchat;

import com.emirchat.ui.SecureChatApp;
import java.nio.file.Path;

/**
 * Entry point of the jar: without arguments (or with {@code gui}) it opens the desktop app,
 * otherwise it runs the command-line interface.
 *
 * <p>This class deliberately does not extend {@code javafx.application.Application}, so the
 * JavaFX runtime can be loaded from the classpath of a single runnable jar.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        // Desktop extras: "--background" (start hidden in the tray) and a backup file opened by
        // double-click (the installer associates .scbackup files with Oba).
        boolean background = false;
        Path backupFile = null;
        java.util.List<String> rest = new java.util.ArrayList<>();
        for (String a : args) {
            if (a.equals("--background")) background = true;
            else if (a.toLowerCase(java.util.Locale.ROOT).endsWith(".scbackup")) backupFile = Path.of(a);
            else rest.add(a);
        }
        if (background || backupFile != null) {
            if (rest.isEmpty()) rest.add("gui");
            args = rest.toArray(String[]::new);
        }
        if (args.length == 0 || args[0].equals("gui")) {
            Path home = SecureChat.Options.defaultHome();
            if (args.length > 0) {
                try {
                    home = SecureChat.Options.parse(args).home();
                } catch (IllegalArgumentException e) {
                    System.err.println("Error: " + e.getMessage());
                    System.err.println();
                    System.err.println(SecureChat.USAGE);
                    System.exit(2);
                }
            }
            SecureChatApp.launchWith(home, background, backupFile);
            return;
        }
        if (args[0].equals("--help") || args[0].equals("-h") || args[0].equals("help")) {
            System.out.println(SecureChat.USAGE);
            return;
        }
        SecureChat.main(args);
    }
}
