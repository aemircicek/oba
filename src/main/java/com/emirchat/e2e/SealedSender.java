package com.emirchat.e2e;

import com.emirchat.crypto.Hkdf;
import com.emirchat.crypto.Identity;
import com.emirchat.crypto.X25519;
import com.emirchat.protocol.Wire;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Sealed sender (3.6): the name of who sent a message travels inside, encrypted to the recipient
 * device's X25519 identity key, so the server, which accepts the message without a login, does not
 * learn it.
 *
 * <pre>
 *   sealed = u8 1 ‖ ephemeral X25519 key ‖ nonce(12) ‖ AES-256-GCM(key, inner)
 *   key    = HKDF-SHA256(salt = ephemeral ‖ recipient key, X25519(ephemeral, recipient key))
 *   inner  = string sender ‖ i64 device ‖ bytes sender identity key ‖ bytes signature ‖ bytes message
 *   signature = Ed25519 by the sender over "oba sealed v1" ‖ recipient key ‖ ephemeral ‖ SHA-256(message)
 *               ‖ sender ‖ device
 * </pre>
 *
 * The signature ties the sender's identity to this very envelope and recipient, so it cannot be
 * lifted onto another; the recipient checks that the identity key is the one it knows for that
 * name before it opens the message itself with the Double Ratchet.
 */
public final class SealedSender {
    private static final byte[] INFO = "oba sealed sender v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONTEXT = "oba sealed v1".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_MESSAGE = 40_000;

    private SealedSender() {}

    /** What a sealed envelope said about its sender, signature checked. */
    public record Opened(String sender, int device, byte[] identityKey, byte[] message) {}

    public static byte[] seal(Identity sender, String senderName, int senderDevice, byte[] recipientDh, byte[] message)
            throws IOException {
        KeyPair eph = X25519.generate();
        byte[] ephPub = X25519.encode(eph.getPublic());
        byte[] signature = sender.sign(signed(recipientDh, ephPub, message, senderName, senderDevice));
        byte[] inner = new Wire.Writer().string(senderName).i64(senderDevice).bytes(sender.publicKeyBytes()).bytes(signature)
                .bytes(message).toByteArray();
        byte[] key = key(X25519.dh(eph, recipientDh), ephPub, recipientDh);
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ct = c.doFinal(inner);
            return ByteBuffer.allocate(1 + ephPub.length + 12 + ct.length).put((byte) 1).put(ephPub).put(nonce).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not seal", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * Opens with our device's X25519 identity key and checks the sender's signature.
     *
     * @throws SecurityException if it is not for us, damaged, or the signature does not match
     */
    static Opened open(KeyPair ourDh, byte[] sealed) {
        int keyLength = X25519.PUBLIC_LENGTH;
        if (sealed.length < 1 + keyLength + 12 + 16 || sealed[0] != 1) throw new SecurityException("Not a sealed envelope");
        byte[] ephPub = Arrays.copyOfRange(sealed, 1, 1 + keyLength);
        byte[] ourPub = X25519.encode(ourDh.getPublic());
        byte[] shared;
        try {
            shared = X25519.dh(ourDh, ephPub);
        } catch (RuntimeException e) {
            throw new SecurityException("Not a sealed envelope", e);
        }
        byte[] key = key(shared, ephPub, ourPub);
        byte[] inner;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, sealed, 1 + keyLength, 12));
            inner = c.doFinal(sealed, 1 + keyLength + 12, sealed.length - 1 - keyLength - 12);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("A sealed envelope that is not for us", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        try {
            Wire.Reader r = new Wire.Reader(inner);
            String sender = r.string(20);
            long device = r.i64();
            byte[] identity = r.exactBytes(Identity.ENCODED_PUBLIC_KEY_LENGTH);
            byte[] signature = r.exactBytes(64);
            byte[] message = r.bytes(MAX_MESSAGE);
            r.expectEnd();
            if (device < 0 || device > Integer.MAX_VALUE) throw new SecurityException("Bad device");
            if (!Identity.verify(identity, signed(ourPub, ephPub, message, sender, (int) device), signature)) {
                throw new SecurityException("The sender's signature does not match");
            }
            return new Opened(sender, (int) device, identity, message);
        } catch (ProtocolException e) {
            throw new SecurityException("Malformed sealed envelope", e);
        }
    }

    private static byte[] signed(byte[] recipientDh, byte[] ephPub, byte[] message, String sender, int device) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(message);
            return new Wire.Writer().bytes(CONTEXT).bytes(recipientDh).bytes(ephPub).bytes(digest).string(sender).i64(device)
                    .toByteArray();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] key(byte[] shared, byte[] ephPub, byte[] recipientPub) {
        try {
            byte[] salt = ByteBuffer.allocate(ephPub.length + recipientPub.length).put(ephPub).put(recipientPub).array();
            return Hkdf.expand(Hkdf.extract(salt, shared), INFO, 32);
        } finally {
            Arrays.fill(shared, (byte) 0);
        }
    }
}
