package com.emirchat.crypto;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HKDF with HMAC-SHA256 as specified in RFC 5869. */
public final class Hkdf {
    private static final String HMAC = "HmacSHA256";
    private static final int HASH_LEN = 32;

    private Hkdf() {}

    public static byte[] extract(byte[] salt, byte[] ikm) {
        byte[] effectiveSalt = (salt == null || salt.length == 0) ? new byte[HASH_LEN] : salt;
        return hmac(effectiveSalt, ikm);
    }

    public static byte[] expand(byte[] prk, byte[] info, int length) {
        if (length <= 0 || length > 255 * HASH_LEN) {
            throw new IllegalArgumentException("Invalid HKDF output length: " + length);
        }
        ByteArrayOutputStream okm = new ByteArrayOutputStream(length);
        byte[] previous = new byte[0];
        for (int i = 1; okm.size() < length; i++) {
            byte[] input = new byte[previous.length + info.length + 1];
            System.arraycopy(previous, 0, input, 0, previous.length);
            System.arraycopy(info, 0, input, previous.length, info.length);
            input[input.length - 1] = (byte) i;
            Arrays.fill(previous, (byte) 0);
            previous = hmac(prk, input);
            okm.writeBytes(previous);
        }
        Arrays.fill(previous, (byte) 0);
        return Arrays.copyOf(okm.toByteArray(), length);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
