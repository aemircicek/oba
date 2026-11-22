package com.emirchat.client;

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
 * Handing the account's identity key to a computer being linked (3.6).
 *
 * <p>Both computers make a one-time X25519 key. The new one first sends only a commitment (a hash)
 * to its key; the approving one then sends its key; only then does the new one reveal its own.
 * Both show a six-digit code made from both keys, which the user compares. A server in the middle
 * would have to fix the keys it passes on before it sees the other side's, so it can do no better
 * than guess a code (one chance in a million, with the user watching), as in ZRTP or Bluetooth's
 * numeric comparison. The approving computer then seals the identity key to the new one: X25519,
 * HKDF-SHA256, AES-256-GCM. The server can read none of it.
 */
public final class DeviceLink {
    private static final byte[] INFO = "oba device link v1".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private DeviceLink() {}

    /** What the approving computer sends: its one-time public key and the sealed keys. */
    public record Sealed(byte[] ephemeral, byte[] ciphertext) {}

    /** The new computer's commitment to its one-time key. */
    public static byte[] commitment(byte[] joinerEphemeral) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update("oba link commit v1".getBytes(StandardCharsets.US_ASCII));
            return d.digest(joinerEphemeral);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The code both computers show, from both one-time keys ("482 913"). */
    public static String code(byte[] joinerEphemeral, byte[] approverEphemeral) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update("oba link code v1".getBytes(StandardCharsets.US_ASCII));
            d.update(joinerEphemeral);
            byte[] h = d.digest(approverEphemeral);
            int n = (ByteBuffer.wrap(h).getInt() & 0x7fffffff) % 1_000_000;
            String s = String.format(java.util.Locale.ROOT, "%06d", n);
            return s.substring(0, 3) + " " + s.substring(3);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Seals the account's identity key to the new computer's one-time key, with our own one-time key. */
    public static Sealed seal(Identity account, KeyPair mine, byte[] theirEphemeral) throws IOException {
        byte[] key = key(mine, theirEphemeral, X25519.encode(mine.getPublic()), theirEphemeral);
        byte[] plain = new Wire.Writer().u8(1).bytes(account.publicKeyBytes()).bytes(account.exportPrivate()).toByteArray();
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ct = c.doFinal(plain);
            return new Sealed(X25519.encode(mine.getPublic()),
                    ByteBuffer.allocate(12 + ct.length).put(nonce).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not seal the account's keys", e);
        } finally {
            Arrays.fill(plain, (byte) 0);
            Arrays.fill(key, (byte) 0);
        }
    }

    /** Opens what the approving computer sealed to our one-time key. */
    public static Identity open(KeyPair ourEphemeral, byte[] theirEphemeral, byte[] sealed) throws IOException {
        if (sealed.length < 12 + 16) throw new IOException("The keys from the other computer are damaged");
        byte[] key = key(ourEphemeral, theirEphemeral, theirEphemeral, X25519.encode(ourEphemeral.getPublic()));
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, sealed, 0, 12));
            byte[] plain = c.doFinal(sealed, 12, sealed.length - 12);
            try {
                Wire.Reader r = new Wire.Reader(plain);
                if (r.u8() != 1) throw new IOException("A newer kind of link: update Oba on this computer");
                byte[] pub = r.exactBytes(Identity.ENCODED_PUBLIC_KEY_LENGTH);
                byte[] priv = r.bytes(256);
                r.expectEnd();
                return Identity.fromEncoded(pub, priv);
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        } catch (GeneralSecurityException | ProtocolException e) {
            throw new IOException("The keys from the other computer could not be opened", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** Both sides: the shared secret, bound to both one-time keys (approver's first). */
    private static byte[] key(KeyPair mine, byte[] theirs, byte[] approver, byte[] joiner) {
        byte[] shared = X25519.dh(mine, theirs);
        try {
            byte[] salt = ByteBuffer.allocate(approver.length + joiner.length).put(approver).put(joiner).array();
            return Hkdf.expand(Hkdf.extract(salt, shared), INFO, 32);
        } finally {
            Arrays.fill(shared, (byte) 0);
        }
    }
}
