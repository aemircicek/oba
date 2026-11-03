package com.emirchat.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Keeps an account password for "remember me" in the operating system's own protected storage,
 * never in plain text:
 * <ul>
 *   <li>Windows: encrypted with DPAPI for the current Windows user, stored in the data folder;
 *   <li>macOS: the login Keychain (through {@code security -i}, so the password never appears on
 *       a command line);
 *   <li>Linux: the Secret Service ({@code secret-tool}, password on standard input).
 * </ul>
 * Anyone who can log in as the same OS user can still open the account; that is the trade-off
 * the user accepts by ticking "remember me".
 */
public interface SecretStore {
    String SERVICE = "Oba";
    /** The service name before the app was renamed; a password found there is moved to {@link #SERVICE}. */
    String FORMER_SERVICE = "Scorpion";

    boolean available();

    void save(String account, char[] password) throws IOException;

    /** @return the password, or null if none is stored (or it cannot be read any more) */
    char[] load(String account) throws IOException;

    void delete(String account) throws IOException;

    /** The store for this operating system; {@code dataDir} is used on Windows. */
    static SecretStore forThisSystem(Path dataDir) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return new Dpapi(dataDir.resolve("remembered"));
        if (os.contains("mac")) return new Keychain();
        return new SecretTool();
    }

    /** Windows: DPAPI-encrypted blobs, one file per account. */
    final class Dpapi implements SecretStore {
        private final Path dir;

        Dpapi(Path dir) {
            this.dir = dir;
        }

        @Override public boolean available() {
            try {
                Class.forName("com.sun.jna.platform.win32.Crypt32Util");
                return true;
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        }

        private Path file(String account) {
            return dir.resolve(Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(account.getBytes(StandardCharsets.UTF_8)) + ".bin");
        }

        @Override public void save(String account, char[] password) throws IOException {
            byte[] plain = utf8(password);
            try {
                byte[] sealed = com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(plain);
                Files.createDirectories(dir);
                Files.write(file(account), sealed);
            } catch (RuntimeException | LinkageError e) {
                throw new IOException("Windows could not protect the password: " + e.getMessage(), e);
            } finally {
                java.util.Arrays.fill(plain, (byte) 0);
            }
        }

        @Override public char[] load(String account) throws IOException {
            Path f = file(account);
            if (!Files.exists(f)) return null;
            try {
                byte[] plain = com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(Files.readAllBytes(f));
                char[] password = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(plain)).array();
                java.util.Arrays.fill(plain, (byte) 0);
                return trim(password);
            } catch (RuntimeException | LinkageError e) {
                return null; // made by another Windows user or machine (for example a restored backup)
            }
        }

        @Override public void delete(String account) throws IOException {
            Files.deleteIfExists(file(account));
        }
    }

    /** macOS login Keychain. */
    final class Keychain implements SecretStore {
        @Override public boolean available() {
            return Files.isExecutable(Path.of("/usr/bin/security"));
        }

        @Override public void save(String account, char[] password) throws IOException {
            // "security -i" reads commands from standard input: nothing secret on the command line.
            String command = "add-generic-password -U -s " + quote(SERVICE) + " -a " + quote(account) + " -w "
                    + quote(new String(password)) + "\n";
            run(List.of("/usr/bin/security", "-i"), command, false);
        }

        @Override public char[] load(String account) throws IOException {
            String out = run(List.of("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", account, "-w"), "", true);
            if (out != null) return out.strip().toCharArray();
            String former = run(List.of("/usr/bin/security", "find-generic-password", "-s", FORMER_SERVICE, "-a", account, "-w"), "", true);
            if (former == null) return null;
            char[] password = former.strip().toCharArray();
            save(account, password); // moved under the new name, once
            run(List.of("/usr/bin/security", "delete-generic-password", "-s", FORMER_SERVICE, "-a", account), "", true);
            return password;
        }

        @Override public void delete(String account) throws IOException {
            run(List.of("/usr/bin/security", "delete-generic-password", "-s", SERVICE, "-a", account), "", true);
            run(List.of("/usr/bin/security", "delete-generic-password", "-s", FORMER_SERVICE, "-a", account), "", true);
        }

        private static String quote(String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    /** Linux Secret Service (GNOME Keyring, KWallet) through secret-tool. */
    final class SecretTool implements SecretStore {
        private static final Path TOOL = Path.of("/usr/bin/secret-tool");

        @Override public boolean available() {
            return Files.isExecutable(TOOL);
        }

        @Override public void save(String account, char[] password) throws IOException {
            run(List.of(TOOL.toString(), "store", "--label=" + SERVICE, "service", "oba", "account", account),
                    new String(password), false);
        }

        @Override public char[] load(String account) throws IOException {
            String out = run(List.of(TOOL.toString(), "lookup", "service", "oba", "account", account), "", true);
            if (out != null && !out.isEmpty()) return out.toCharArray();
            String former = run(List.of(TOOL.toString(), "lookup", "service", "scorpion", "account", account), "", true);
            if (former == null || former.isEmpty()) return null;
            char[] password = former.toCharArray();
            save(account, password); // moved under the new name, once
            run(List.of(TOOL.toString(), "clear", "service", "scorpion", "account", account), "", true);
            return password;
        }

        @Override public void delete(String account) throws IOException {
            run(List.of(TOOL.toString(), "clear", "service", "oba", "account", account), "", true);
            run(List.of(TOOL.toString(), "clear", "service", "scorpion", "account", account), "", true);
        }
    }

    /** Runs a tool with {@code input} on stdin; returns stdout, or null if it failed and {@code quiet}. */
    private static String run(List<String> command, String input, boolean quiet) throws IOException {
        Process p = new ProcessBuilder(command).redirectErrorStream(false).start();
        try (OutputStream in = p.getOutputStream()) {
            in.write(input.getBytes(StandardCharsets.UTF_8));
        }
        String out;
        try (InputStream o = p.getInputStream()) {
            out = new String(o.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException(command.get(0) + " did not answer");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        if (p.exitValue() != 0) {
            if (quiet) return null;
            throw new IOException(command.get(0) + " failed (exit " + p.exitValue() + ")");
        }
        return out;
    }

    private static byte[] utf8(char[] chars) {
        java.nio.ByteBuffer b = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars));
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    private static char[] trim(char[] chars) {
        int n = chars.length;
        while (n > 0 && chars[n - 1] == '\0') n--;
        return java.util.Arrays.copyOf(chars, n);
    }
}
