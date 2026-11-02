package com.emirchat;

import com.emirchat.client.Backup;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/** {@code secure-chat backup} and {@code secure-chat restore}. */
final class BackupCli {
    static final String USAGE = String.join("\n",
            "  secure-chat backup  --out <file> [--home <dir>]   encrypted backup of keys and history",
            "  secure-chat restore --in <file>  [--home <dir>]   restore one (current data is moved aside)");

    private BackupCli() {}

    static boolean handles(String command) {
        return command.equals("backup") || command.equals("restore");
    }

    static int run(String[] args) {
        String command = args[0];
        Path home = SecureChat.Options.defaultHome();
        Path file = null;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (i + 1 >= args.length) throw new IllegalArgumentException(a + " needs a value");
            switch (a) {
                case "--home" -> home = Path.of(args[++i]);
                case "--out", "--in" -> file = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("Unknown option: " + a);
            }
        }
        if (file == null) throw new IllegalArgumentException(command.equals("backup") ? "--out is required" : "--in is required");
        try {
            if (command.equals("backup")) {
                char[] pass = readPassphrase("Backup passphrase (at least " + Backup.MIN_PASSPHRASE + " characters): ");
                char[] again = readPassphrase("Repeat it: ");
                if (!java.util.Arrays.equals(pass, again)) {
                    System.err.println("The passphrases differ.");
                    return 2;
                }
                // Close the desktop app first: an open history is copied consistently only from inside the app.
                Backup.create(home, file, pass, Map.of());
                System.out.println("Encrypted backup written to " + file.toAbsolutePath() + ".");
            } else {
                Path aside = Backup.restore(file, readPassphrase("Backup passphrase: "), home);
                System.out.println("Restored into " + home.toAbsolutePath() + "."
                        + (aside == null ? "" : " Previous data moved to " + aside + "."));
            }
            return 0;
        } catch (SecurityException e) {
            System.err.println(e.getMessage());
            return 3;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static char[] readPassphrase(String prompt) throws IOException {
        Console console = System.console();
        if (console != null) {
            char[] p = console.readPassword(prompt);
            return p == null ? new char[0] : p;
        }
        System.out.print(prompt);
        System.out.flush();
        String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        return line == null ? new char[0] : line.toCharArray();
    }
}
