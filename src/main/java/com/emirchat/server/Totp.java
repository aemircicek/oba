package com.emirchat.server;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time codes (TOTP, RFC 6238: HMAC-SHA1, 30-second steps, six digits), the kind
 * every authenticator app shows, and one-time recovery codes for a lost phone.
 */
final class Totp {
    static final int SECRET_BYTES = 20;
    static final long STEP_SECONDS = 30;
    /** Codes from one step before and after count too (clocks are rarely exact). */
    static final int WINDOW = 1;
    static final int RECOVERY_CODES = 8;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {}

    static byte[] newSecret() {
        byte[] s = new byte[SECRET_BYTES];
        RANDOM.nextBytes(s);
        return s;
    }

    /** The six-digit code for a 30-second step. */
    static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = h[h.length - 1] & 0x0f;
            int binary = ((h[offset] & 0x7f) << 24) | ((h[offset + 1] & 0xff) << 16) | ((h[offset + 2] & 0xff) << 8)
                    | (h[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static long step(long epochMillis) {
        return Math.floorDiv(epochMillis / 1000, STEP_SECONDS);
    }

    /**
     * The step a code belongs to, if it is right for now (give or take a step) and newer than the
     * last code used, so a code seen over someone's shoulder cannot be used again.
     *
     * @return the step, or -1
     */
    static long verify(byte[] secret, String code, long epochMillis, long lastUsedStep) {
        if (code == null) return -1;
        String c = code.replaceAll("\\s", "");
        if (!c.matches("\\d{6}")) return -1;
        long now = step(epochMillis);
        long found = -1;
        for (long s = now - WINDOW; s <= now + WINDOW; s++) {
            // compare every candidate in constant time and do not stop early
            if (MessageDigest.isEqual(code(secret, s).getBytes(StandardCharsets.US_ASCII), c.getBytes(StandardCharsets.US_ASCII))) {
                found = s;
            }
        }
        return found > lastUsedStep ? found : -1;
    }

    /** Fresh recovery codes, as the user writes them down ("XXXX-XXXX"). */
    static List<String> newRecoveryCodes() {
        List<String> codes = new ArrayList<>();
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O, 1/I
        for (int i = 0; i < RECOVERY_CODES; i++) {
            StringBuilder b = new StringBuilder();
            for (int j = 0; j < 8; j++) {
                if (j == 4) b.append('-');
                b.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
            }
            codes.add(b.toString());
        }
        return codes;
    }

    /** How a recovery code is kept: its SHA-256 over the normalised form (case and dashes ignored). */
    static byte[] recoveryHash(String code) {
        String n = code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        try {
            return MessageDigest.getInstance("SHA-256").digest(("oba recovery " + n).getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean looksLikeRecoveryCode(String code) {
        return code != null && code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "").length() == 8
                && !code.replaceAll("\\s", "").matches("\\d{6}");
    }

    /** RFC 4648 base32 without padding, as authenticator apps take secrets. */
    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }
}
