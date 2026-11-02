package com.emirchat.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The server the app connects to unless the user picks another, read from
 * {@code default-server.properties} on the classpath:
 *
 * <pre>
 *   address     = chat.example.org:5056
 *   fingerprint = 997e7038...   (optional: the server's identity key, pinned in advance)
 * </pre>
 *
 * Shipping the fingerprint means even the very first connection is protected against a
 * man-in-the-middle, and users are not asked to compare it.
 */
public record DefaultServer(String address, String fingerprint) {
    public static DefaultServer load() {
        Properties p = new Properties();
        try (InputStream in = DefaultServer.class.getResourceAsStream("/default-server.properties")) {
            if (in != null) p.load(in);
        } catch (IOException ignored) {
            // no default server
        }
        String fp = p.getProperty("fingerprint", "").replaceAll("\\s", "").toLowerCase(java.util.Locale.ROOT);
        return new DefaultServer(p.getProperty("address", "").strip(), fp);
    }

    public boolean configured() {
        return !address.isEmpty();
    }

    public boolean hasFingerprint() {
        return fingerprint.matches("[0-9a-f]{64}");
    }
}
