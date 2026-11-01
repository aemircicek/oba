package com.emirchat.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.KeyAgreement;

/**
 * X25519 helpers. Public keys travel in their 44-byte X.509 encoding, private keys are stored in
 * PKCS#8; both are validated on decode.
 */
public final class X25519 {
    public static final int PUBLIC_LENGTH = 44;

    private X25519() {}

    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("X25519 unavailable", e);
        }
    }

    public static byte[] encode(PublicKey key) {
        return key.getEncoded();
    }

    public static PublicKey decodePublic(byte[] encoded) {
        if (encoded == null || encoded.length != PUBLIC_LENGTH) throw new SecurityException("Invalid X25519 public key");
        try {
            return KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException e) {
            throw new SecurityException("Invalid X25519 public key", e);
        }
    }

    public static KeyPair decodePair(byte[] publicEncoded, byte[] privatePkcs8) {
        try {
            PrivateKey priv = KeyFactory.getInstance("X25519").generatePrivate(new PKCS8EncodedKeySpec(privatePkcs8));
            return new KeyPair(decodePublic(publicEncoded), priv);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("Invalid X25519 private key", e);
        }
    }

    /** Diffie-Hellman; rejects low-order peer keys that would yield an all-zero secret. */
    public static byte[] dh(KeyPair mine, byte[] peerPublicEncoded) {
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("X25519");
            agreement.init(mine.getPrivate());
            agreement.doPhase(decodePublic(peerPublicEncoded), true);
            byte[] secret = agreement.generateSecret();
            int acc = 0;
            for (byte b : secret) acc |= b;
            if (acc == 0) throw new SecurityException("Low-order X25519 key");
            return secret;
        } catch (GeneralSecurityException e) {
            throw new SecurityException("X25519 agreement failed", e);
        }
    }
}
