package com.emirchat.e2e;

import com.emirchat.crypto.Hkdf;
import com.emirchat.crypto.Identity;
import com.emirchat.crypto.X25519;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;

/**
 * The X3DH key agreement (Signal specification, revision 1), adapted to separate Ed25519 signing
 * and X25519 agreement identity keys (as in Matrix's Olm): the X25519 identity key is signed by
 * the Ed25519 identity, and every signature is checked before any DH is computed.
 */
final class X3dh {
    private static final byte[] INFO = "SecureChat-v3 X3DH".getBytes(StandardCharsets.UTF_8);
    private static final byte[] F = filled(32, (byte) 0xFF);

    /** Result for the initiator: the shared secret plus what goes into the first message. */
    record Initiation(byte[] sharedSecret, byte[] ephemeralPublic, int signedPrekeyId, int oneTimePrekeyId,
                      byte[] responderRatchetKey) {}

    private X3dh() {}

    /**
     * @param expectedIdentity the responder's Ed25519 key as pinned by the user; the bundle must match
     * @throws SecurityException if the bundle is not signed by {@code expectedIdentity}
     */
    static Initiation initiate(DeviceKeys mine, byte[] expectedIdentity, PrekeyBundle b) {
        if (!Arrays.equals(expectedIdentity, b.identityKey())) {
            throw new SecurityException("Prekey bundle is for a different identity key");
        }
        verify(b.identityKey(), DeviceKeys.dhIdentitySignedData(b.dhIdentity()), b.dhIdentitySignature(),
                "identity key binding");
        verify(b.identityKey(), DeviceKeys.signedPrekeySignedData(b.signedPrekeyId(), b.signedPrekey()),
                b.signedPrekeySignature(), "signed prekey");

        KeyPair ephemeral = X25519.generate();
        ByteArrayOutputStream km = new ByteArrayOutputStream();
        km.writeBytes(F);
        km.writeBytes(X25519.dh(mine.dhIdentity(), b.signedPrekey()));   // DH1 = DH(IK_A, SPK_B)
        km.writeBytes(X25519.dh(ephemeral, b.dhIdentity()));             // DH2 = DH(EK_A, IK_B)
        km.writeBytes(X25519.dh(ephemeral, b.signedPrekey()));           // DH3 = DH(EK_A, SPK_B)
        if (b.hasOneTimePrekey()) km.writeBytes(X25519.dh(ephemeral, b.oneTimePrekey())); // DH4
        byte[] sk = kdf(km.toByteArray());
        return new Initiation(sk, X25519.encode(ephemeral.getPublic()), b.signedPrekeyId(),
                b.hasOneTimePrekey() ? b.oneTimePrekeyId() : -1, b.signedPrekey());
    }

    /**
     * Responder side. Does not consume the one-time prekey: the caller deletes it only once the
     * first message has authenticated, so a forged message cannot burn it.
     */
    static byte[] respond(DeviceKeys mine, byte[] senderIdentityKey, byte[] senderDhIdentity, byte[] senderDhSignature,
                          byte[] ephemeral, int signedPrekeyId, int oneTimePrekeyId) {
        verify(senderIdentityKey, DeviceKeys.dhIdentitySignedData(senderDhIdentity), senderDhSignature,
                "sender identity key binding");
        KeyPair spk = mine.signedPrekey(signedPrekeyId);
        if (spk == null) throw new SecurityException("Unknown signed prekey " + signedPrekeyId);
        ByteArrayOutputStream km = new ByteArrayOutputStream();
        km.writeBytes(F);
        km.writeBytes(X25519.dh(spk, senderDhIdentity));
        km.writeBytes(X25519.dh(mine.dhIdentity(), ephemeral));
        km.writeBytes(X25519.dh(spk, ephemeral));
        if (oneTimePrekeyId >= 0) {
            KeyPair opk = mine.peekOneTimePrekey(oneTimePrekeyId);
            if (opk == null) throw new SecurityException("One-time prekey " + oneTimePrekeyId + " already used or unknown");
            km.writeBytes(X25519.dh(opk, ephemeral));
        }
        return kdf(km.toByteArray());
    }

    private static byte[] kdf(byte[] keyMaterial) {
        byte[] sk = Hkdf.expand(Hkdf.extract(new byte[32], keyMaterial), INFO, 32);
        Arrays.fill(keyMaterial, (byte) 0);
        return sk;
    }

    private static void verify(byte[] identityKey, byte[] data, byte[] signature, String what) {
        if (!Identity.verify(identityKey, data, signature)) {
            throw new SecurityException("Invalid signature on " + what);
        }
    }

    private static byte[] filled(int n, byte value) {
        byte[] b = new byte[n];
        Arrays.fill(b, value);
        return b;
    }
}
