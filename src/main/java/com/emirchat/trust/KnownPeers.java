package com.emirchat.trust;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

/**
 * Trust-on-first-use store, like SSH's known_hosts. The first time a name is seen the user is
 * asked to confirm its fingerprint; afterwards a different key for the same name is refused.
 */
public final class KnownPeers implements TrustPolicy {
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    private final Path file;
    private final BiPredicate<String, String> askUser;
    private final Map<String, String> peers = new LinkedHashMap<>();

    /**
     * @param file    the known-peers file (created on first save)
     * @param askUser called for unknown peers with (name, fingerprint); returns true to trust and remember
     */
    public KnownPeers(Path file, BiPredicate<String, String> askUser) throws IOException {
        this.file = file;
        this.askUser = askUser;
        if (Files.exists(file)) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length != 2 || !FINGERPRINT.matcher(parts[1]).matches()) {
                    throw new IOException(file + ":" + (i + 1) + ": malformed entry");
                }
                peers.put(parts[0], parts[1]);
            }
        }
    }

    @Override
    public synchronized void check(String peerName, String fingerprint) throws PeerRejectedException {
        String known = peers.get(peerName);
        if (known != null) {
            if (known.equals(fingerprint)) return;
            throw new KeyChangedException(peerName, known, fingerprint, file);
        }
        if (!askUser.test(peerName, fingerprint)) {
            throw new PeerRejectedException("Fingerprint for '" + peerName + "' was not confirmed");
        }
        peers.put(peerName, fingerprint);
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, peerName + " " + fingerprint + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save " + file, e);
        }
    }

    /** The pinned fingerprint for a name, or null. */
    public synchronized String fingerprint(String peerName) {
        return peers.get(peerName);
    }

    /** Re-pins a name to a new fingerprint (after out-of-band verification) and rewrites the file. */
    public synchronized void replace(String peerName, String fingerprint) throws java.io.IOException {
        if (!FINGERPRINT.matcher(fingerprint).matches()) throw new IllegalArgumentException("Invalid fingerprint");
        peers.put(peerName, fingerprint);
        StringBuilder sb = new StringBuilder();
        peers.forEach((n, fp) -> sb.append(n).append(' ').append(fp).append(System.lineSeparator()));
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    public Path file() {
        return file;
    }

    public synchronized boolean isKnown(String peerName) {
        return peers.containsKey(peerName);
    }
}
