package com.emirchat.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM record layer. Each direction has its own key and a 64-bit message counter
 * that forms the nonce. The counter is never transmitted: a dropped, replayed, reordered or
 * modified record makes authentication fail and the channel is unusable afterwards.
 */
public final class SecureChannel {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;
    private static final int NONCE_LENGTH = 12;
    private static final byte[] AAD = "SecureChat-v1 record".getBytes(StandardCharsets.UTF_8);

    private final FrameIO io;
    private final SecretKey sendKey;
    private final SecretKey receiveKey;
    private long sendCounter;
    private long receiveCounter;
    private volatile boolean failed;

    public SecureChannel(FrameIO io, byte[] sendKey, byte[] receiveKey) {
        if (sendKey.length != 32 || receiveKey.length != 32) {
            throw new IllegalArgumentException("AES-256 keys must be 32 bytes");
        }
        if (Arrays.equals(sendKey, receiveKey)) {
            throw new IllegalArgumentException("Send and receive keys must differ");
        }
        this.io = io;
        this.sendKey = new SecretKeySpec(sendKey, "AES");
        this.receiveKey = new SecretKeySpec(receiveKey, "AES");
    }

    public synchronized void send(byte[] plaintext) throws IOException {
        ensureUsable();
        if (sendCounter == Long.MAX_VALUE) throw new IOException("Send counter exhausted; reconnect to rekey");
        byte[] record = crypt(Cipher.ENCRYPT_MODE, sendKey, sendCounter, plaintext);
        sendCounter++;
        io.write(record);
    }

    public byte[] receive() throws IOException {
        ensureUsable();
        byte[] record = io.read();
        try {
            byte[] plaintext = crypt(Cipher.DECRYPT_MODE, receiveKey, receiveCounter, record);
            receiveCounter++;
            return plaintext;
        } catch (SecurityException e) {
            failed = true;
            throw e;
        }
    }

    private void ensureUsable() {
        if (failed) throw new SecurityException("Channel closed after an authentication failure");
    }

    private static byte[] crypt(int mode, SecretKey key, long counter, byte[] input) {
        byte[] nonce = ByteBuffer.allocate(NONCE_LENGTH).putInt(0).putLong(counter).array();
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            return cipher.doFinal(input);
        } catch (AEADBadTagException e) {
            throw new SecurityException("Message authentication failed (tampered, replayed or reordered record)", e);
        } catch (GeneralSecurityException e) {
            if (mode == Cipher.DECRYPT_MODE) {
                throw new SecurityException("Malformed encrypted record", e);
            }
            throw new IllegalStateException("AES-GCM unavailable", e);
        }
    }
}
