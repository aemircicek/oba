package com.emirchat.e2e;

import com.emirchat.crypto.Identity;
import com.emirchat.crypto.X25519;
import com.emirchat.protocol.Wire;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The key material of this device for end-to-end encryption:
 * <ul>
 *   <li>the Ed25519 {@link Identity} (signing, fingerprints),
 *   <li>an X25519 identity key for X3DH, signed by the Ed25519 identity,
 *   <li>a signed prekey, rotated weekly (the previous one is kept for late messages),
 *   <li>one-time prekeys, each deleted after its first use.
 * </ul>
 */
public final class DeviceKeys {
    static final Duration SIGNED_PREKEY_LIFETIME = Duration.ofDays(7);
    static final byte[] DH_IDENTITY_CONTEXT = "SecureChat-v3 X25519 identity".getBytes(StandardCharsets.UTF_8);
    static final byte[] SIGNED_PREKEY_CONTEXT = "SecureChat-v3 signed prekey".getBytes(StandardCharsets.UTF_8);

    record SignedPrekey(int id, KeyPair pair, byte[] signature, long createdAt) {}

    private final Identity identity;
    private KeyPair dhIdentity;
    private byte[] dhIdentitySignature;
    private SignedPrekey signedPrekey;
    private SignedPrekey previousSignedPrekey;
    private final Map<Integer, KeyPair> oneTimePrekeys = new LinkedHashMap<>();
    private int nextPrekeyId = 1;

    private DeviceKeys(Identity identity) {
        this.identity = identity;
    }

    /** Fresh keys for a device (call once, then persist). */
    static DeviceKeys create(Identity identity) {
        DeviceKeys k = new DeviceKeys(identity);
        k.dhIdentity = X25519.generate();
        k.dhIdentitySignature = identity.sign(dhIdentitySignedData(X25519.encode(k.dhIdentity.getPublic())));
        k.signedPrekey = k.newSignedPrekey();
        return k;
    }

    Identity identity() { return identity; }

    KeyPair dhIdentity() { return dhIdentity; }

    byte[] dhIdentitySignature() { return dhIdentitySignature.clone(); }

    SignedPrekey signedPrekey() { return signedPrekey; }

    /** Looks up a signed prekey by id: the current one, or the previous one for messages sent before rotation. */
    KeyPair signedPrekey(int id) {
        if (signedPrekey.id() == id) return signedPrekey.pair();
        if (previousSignedPrekey != null && previousSignedPrekey.id() == id) return previousSignedPrekey.pair();
        return null;
    }

    /** Returns and deletes a one-time prekey (forward secrecy for the first message). */
    KeyPair takeOneTimePrekey(int id) {
        return oneTimePrekeys.remove(id);
    }

    KeyPair peekOneTimePrekey(int id) {
        return oneTimePrekeys.get(id);
    }

    int oneTimePrekeyCount() {
        return oneTimePrekeys.size();
    }

    /** Generates {@code n} new one-time prekeys and returns their (id, public key) pairs for upload. */
    List<Map.Entry<Integer, byte[]>> generateOneTimePrekeys(int n) {
        List<Map.Entry<Integer, byte[]>> created = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int id = nextPrekeyId++;
            KeyPair pair = X25519.generate();
            oneTimePrekeys.put(id, pair);
            created.add(Map.entry(id, X25519.encode(pair.getPublic())));
        }
        return created;
    }

    /** @return true if the signed prekey was rotated (and must be uploaded again) */
    boolean rotateSignedPrekeyIfDue(long now) {
        if (now - signedPrekey.createdAt() < SIGNED_PREKEY_LIFETIME.toMillis()) return false;
        previousSignedPrekey = signedPrekey;
        signedPrekey = newSignedPrekey();
        return true;
    }

    private SignedPrekey newSignedPrekey() {
        KeyPair pair = X25519.generate();
        int id = nextPrekeyId++;
        byte[] signature = identity.sign(signedPrekeySignedData(id, X25519.encode(pair.getPublic())));
        return new SignedPrekey(id, pair, signature, System.currentTimeMillis());
    }

    public static byte[] dhIdentitySignedData(byte[] dhIdentityPublic) {
        return new Wire.Writer().bytes(DH_IDENTITY_CONTEXT).bytes(dhIdentityPublic).toByteArray();
    }

    public static byte[] signedPrekeySignedData(int id, byte[] prekeyPublic) {
        return new Wire.Writer().bytes(SIGNED_PREKEY_CONTEXT).i64(id).bytes(prekeyPublic).toByteArray();
    }

    // ------------------------------------------------------------------ persistence

    static DeviceKeys loadOrCreate(Path file, Identity identity) throws IOException {
        byte[] data = SecureFiles.read(file);
        if (data == null) {
            DeviceKeys created = create(identity);
            created.save(file);
            return created;
        }
        try {
            Wire.Reader r = new Wire.Reader(data);
            if (r.u8() != 1) throw new ProtocolException("Unknown key file version");
            byte[] owner = r.exactBytes(Identity.ENCODED_PUBLIC_KEY_LENGTH);
            if (!java.util.Arrays.equals(owner, identity.publicKeyBytes())) {
                throw new IOException("Key file " + file + " belongs to a different identity");
            }
            DeviceKeys k = new DeviceKeys(identity);
            k.dhIdentity = X25519.decodePair(r.bytes(64), r.bytes(128));
            k.dhIdentitySignature = r.bytes(128);
            k.signedPrekey = readSpk(r);
            k.previousSignedPrekey = r.u8() == 1 ? readSpk(r) : null;
            k.nextPrekeyId = (int) r.i64();
            int n = r.count(10_000);
            for (int i = 0; i < n; i++) {
                int id = (int) r.i64();
                k.oneTimePrekeys.put(id, X25519.decodePair(r.bytes(64), r.bytes(128)));
            }
            r.expectEnd();
            return k;
        } catch (ProtocolException | SecurityException e) {
            throw new IOException("Corrupt key file " + file + ": " + e.getMessage(), e);
        }
    }

    void save(Path file) throws IOException {
        Wire.Writer w = new Wire.Writer().u8(1).bytes(identity.publicKeyBytes());
        w.bytes(X25519.encode(dhIdentity.getPublic())).bytes(dhIdentity.getPrivate().getEncoded()).bytes(dhIdentitySignature);
        writeSpk(w, signedPrekey);
        if (previousSignedPrekey == null) {
            w.u8(0);
        } else {
            w.u8(1);
            writeSpk(w, previousSignedPrekey);
        }
        w.i64(nextPrekeyId).u16(oneTimePrekeys.size());
        oneTimePrekeys.forEach((id, pair) -> w.i64(id).bytes(X25519.encode(pair.getPublic())).bytes(pair.getPrivate().getEncoded()));
        SecureFiles.writeAtomically(file, w.toByteArray());
    }

    private static void writeSpk(Wire.Writer w, SignedPrekey s) {
        w.i64(s.id()).bytes(X25519.encode(s.pair().getPublic())).bytes(s.pair().getPrivate().getEncoded())
                .bytes(s.signature()).i64(s.createdAt());
    }

    private static SignedPrekey readSpk(Wire.Reader r) throws ProtocolException {
        int id = (int) r.i64();
        KeyPair pair = X25519.decodePair(r.bytes(64), r.bytes(128));
        return new SignedPrekey(id, pair, r.bytes(128), r.i64());
    }
}
