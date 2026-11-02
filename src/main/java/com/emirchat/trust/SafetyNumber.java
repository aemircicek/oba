package com.emirchat.trust;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * A code that two people compare to check that each has the other's real key, like Signal's
 * safety numbers. It is built from both usernames and both identity-key fingerprints in a fixed
 * order, so both sides compute exactly the same value: if the codes match, neither side is
 * talking to an impostor (or a server-substituted key).
 *
 * <p>{@link #qrPayload()} is what the QR code holds; {@link #digits()} is a 60-digit form to read
 * out over a call.
 */
public final class SafetyNumber {
    private static final String PREFIX = "SCV1";

    private final String payload;

    private SafetyNumber(String payload) {
        this.payload = payload;
    }

    /** @param fingerprintA hex fingerprint of {@code userA}'s identity key (as {@code Identity.fingerprint}) */
    public static SafetyNumber of(String userA, String fingerprintA, String userB, String fingerprintB) {
        String a = userA.toLowerCase(Locale.ROOT);
        String b = userB.toLowerCase(Locale.ROOT);
        String fa = normalize(fingerprintA);
        String fb = normalize(fingerprintB);
        boolean swap = a.compareTo(b) > 0 || (a.equals(b) && fa.compareTo(fb) > 0);
        return swap ? new SafetyNumber(PREFIX + "|" + b + "|" + fb + "|" + a + "|" + fa)
                : new SafetyNumber(PREFIX + "|" + a + "|" + fa + "|" + b + "|" + fb);
    }

    private static String normalize(String fingerprint) {
        return fingerprint.replaceAll("\\s", "").toLowerCase(Locale.ROOT);
    }

    public String qrPayload() {
        return payload;
    }

    /** True if a scanned QR code holds the same code (compared in constant time). */
    public boolean matches(String scanned) {
        return scanned != null && MessageDigest.isEqual(payload.getBytes(StandardCharsets.UTF_8),
                scanned.strip().getBytes(StandardCharsets.UTF_8));
    }

    /** Twelve groups of five digits derived from SHA-512 of the code. */
    public String digits() {
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-512").digest(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder sb = new StringBuilder();
        for (int group = 0; group < 12; group++) {
            long v = 0;
            for (int i = 0; i < 5; i++) v = (v << 8) | (hash[group * 5 + i] & 0xff);
            if (group > 0) sb.append(group % 4 == 0 ? "\n" : " ");
            sb.append(String.format(Locale.ROOT, "%05d", v % 100_000));
        }
        return sb.toString();
    }
}
