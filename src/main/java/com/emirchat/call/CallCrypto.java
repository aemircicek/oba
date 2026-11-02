package com.emirchat.call;

import com.emirchat.crypto.Hkdf;
import com.emirchat.crypto.X25519;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * End-to-end encryption of a call's audio. Each side picks a fresh X25519 key for the call and
 * sends its public half in the (already end-to-end encrypted and authenticated) call offer or
 * answer. Both derive one key per direction with HKDF, salted with the call id:
 *
 * <pre>
 *   shared    = X25519(my call key, their call key)
 *   keys      = HKDF-SHA256(salt = callId, ikm = shared, info = "scorpion call v1")  → 64 bytes
 *   packet    = u64 seq ‖ AES-256-GCM(key of the sender's direction, nonce = 0⁴ ‖ seq, aad = callId, audio)
 * </pre>
 *
 * A receiver accepts each sequence number at most once and never an older one, so the relaying
 * server can drop or delay packets but not replay, reorder into the past, or alter them. The keys
 * exist only for the call: hanging up forgets them (forward secrecy for calls).
 */
public final class CallCrypto {
    public static final int CALL_ID_LENGTH = 16;
    private static final byte[] INFO = "scorpion call v1".getBytes(StandardCharsets.US_ASCII);
    private static final int TAG_BYTES = 16;

    private final byte[] callId;
    private final SecretKeySpec sendKey;
    private final SecretKeySpec receiveKey;
    private long sendSeq;
    private long lastReceived = -1;

    private CallCrypto(byte[] callId, byte[] sendKey, byte[] receiveKey) {
        this.callId = callId.clone();
        this.sendKey = new SecretKeySpec(sendKey, "AES");
        this.receiveKey = new SecretKeySpec(receiveKey, "AES");
    }

    /** A fresh X25519 key pair for one call. */
    public static KeyPair newCallKey() {
        return X25519.generate();
    }

    /**
     * @param caller true on the side that placed the call (decides which half of the derived keys
     *               is used for sending)
     */
    public static CallCrypto derive(byte[] callId, KeyPair mine, byte[] theirPublic, boolean caller) {
        if (callId.length != CALL_ID_LENGTH) throw new IllegalArgumentException("Bad call id");
        byte[] shared = X25519.dh(mine, theirPublic);
        byte[] okm = Hkdf.expand(Hkdf.extract(callId, shared), INFO, 64);
        Arrays.fill(shared, (byte) 0);
        byte[] callerToCallee = Arrays.copyOfRange(okm, 0, 32);
        byte[] calleeToCaller = Arrays.copyOfRange(okm, 32, 64);
        Arrays.fill(okm, (byte) 0);
        return caller ? new CallCrypto(callId, callerToCallee, calleeToCaller)
                : new CallCrypto(callId, calleeToCaller, callerToCallee);
    }

    public synchronized byte[] seal(byte[] audio) {
        long seq = sendSeq++;
        byte[] ct = gcm(Cipher.ENCRYPT_MODE, sendKey, seq, audio, 0, audio.length);
        return ByteBuffer.allocate(8 + ct.length).putLong(seq).put(ct).array();
    }

    /**
     * @return the audio, or null for a packet that is late, replayed or not authentic (calls go on:
     *         a lost packet is a moment of silence)
     */
    public synchronized byte[] open(byte[] packet) {
        if (packet.length < 8 + TAG_BYTES) return null;
        long seq = ByteBuffer.wrap(packet, 0, 8).getLong();
        if (seq <= lastReceived || seq < 0) return null;
        byte[] audio;
        try {
            audio = gcm(Cipher.DECRYPT_MODE, receiveKey, seq, packet, 8, packet.length - 8);
        } catch (SecurityException e) {
            return null;
        }
        lastReceived = seq;
        return audio;
    }

    private byte[] gcm(int mode, SecretKeySpec key, long seq, byte[] data, int off, int len) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] nonce = ByteBuffer.allocate(12).putInt(0).putLong(seq).array();
            c.init(mode, key, new GCMParameterSpec(TAG_BYTES * 8, nonce));
            c.updateAAD(callId);
            return c.doFinal(data, off, len);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new SecurityException("Call packet failed authentication");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
