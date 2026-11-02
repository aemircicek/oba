package com.emirchat.e2e;

import com.emirchat.crypto.Hkdf;
import com.emirchat.crypto.X25519;
import com.emirchat.protocol.Wire;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The Double Ratchet algorithm (Signal specification, revision 4), with X25519, HKDF-SHA256,
 * HMAC-SHA256 chain keys and AES-256-GCM message encryption.
 *
 * <p>Decryption is transactional: it works on a copy of the state and only commits if the
 * message authenticates, so a forged or replayed message can never corrupt the session.
 */
final class DoubleRatchet {
    static final int MAX_SKIP = 1000;
    static final int MAX_STORED_SKIPPED = 2000;
    private static final byte[] RK_INFO = "SecureChat-v3 ratchet".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MK_INFO = "SecureChat-v3 message keys".getBytes(StandardCharsets.UTF_8);

    /** Message header, authenticated as associated data. */
    record Header(byte[] dh, int previousChainLength, int number) {
        byte[] encode() {
            return new Wire.Writer().bytes(dh).i64(((long) previousChainLength << 32) | (number & 0xFFFFFFFFL)).toByteArray();
        }

        static Header decode(Wire.Reader r) throws ProtocolException {
            byte[] dh = r.exactBytes(X25519.PUBLIC_LENGTH);
            long packed = r.i64();
            int pn = (int) (packed >>> 32);
            int n = (int) packed;
            if (pn < 0 || n < 0) throw new ProtocolException("Invalid ratchet counters");
            return new Header(dh, pn, n);
        }
    }

    record Encrypted(Header header, byte[] ciphertext) {}

    private KeyPair dhSelf;
    private byte[] dhRemote;      // null until the first message is received (responder)
    private byte[] rootKey;
    private byte[] sendChain;     // null for the responder until it ratchets
    private byte[] receiveChain;  // null for the initiator until it receives
    private int sendCount;
    private int receiveCount;
    private int previousSendCount;
    private final LinkedHashMap<String, byte[]> skipped = new LinkedHashMap<>();

    private DoubleRatchet() {}

    /** Initiator ("Alice"): knows the responder's signed prekey as its first ratchet key. */
    static DoubleRatchet initiator(byte[] sharedSecret, byte[] responderRatchetKey) {
        DoubleRatchet r = new DoubleRatchet();
        r.dhSelf = X25519.generate();
        r.dhRemote = responderRatchetKey.clone();
        byte[][] rkCk = kdfRoot(sharedSecret, X25519.dh(r.dhSelf, r.dhRemote));
        r.rootKey = rkCk[0];
        r.sendChain = rkCk[1];
        return r;
    }

    /** Responder ("Bob"): its signed prekey pair is the first ratchet key. */
    static DoubleRatchet responder(byte[] sharedSecret, KeyPair signedPrekey) {
        DoubleRatchet r = new DoubleRatchet();
        r.dhSelf = signedPrekey;
        r.rootKey = sharedSecret.clone();
        return r;
    }

    boolean canSend() {
        return sendChain != null;
    }

    Encrypted encrypt(byte[] plaintext, byte[] associatedData) {
        if (sendChain == null) throw new IllegalStateException("Cannot send before receiving the first message");
        byte[][] ckMk = kdfChain(sendChain);
        sendChain = ckMk[0];
        Header header = new Header(X25519.encode(dhSelf.getPublic()), previousSendCount, sendCount);
        sendCount++;
        byte[] ciphertext = aead(Cipher.ENCRYPT_MODE, ckMk[1], plaintext, concat(associatedData, header.encode()));
        Arrays.fill(ckMk[1], (byte) 0);
        return new Encrypted(header, ciphertext);
    }

    /** @throws SecurityException if the message does not authenticate; the state is then unchanged */
    byte[] decrypt(Header header, byte[] ciphertext, byte[] associatedData) {
        DoubleRatchet trial = copy();
        byte[] plaintext = trial.decryptInPlace(header, ciphertext, associatedData);
        commit(trial);
        return plaintext;
    }

    private byte[] decryptInPlace(Header h, byte[] ciphertext, byte[] ad) {
        byte[] ad2 = concat(ad, h.encode());
        String skippedId = id(h.dh(), h.number());
        byte[] skippedKey = skipped.remove(skippedId);
        if (skippedKey != null) return aead(Cipher.DECRYPT_MODE, skippedKey, ciphertext, ad2);

        if (dhRemote == null || !Arrays.equals(h.dh(), dhRemote)) {
            skipMessageKeys(h.previousChainLength());
            dhRatchet(h);
        }
        skipMessageKeys(h.number());
        byte[][] ckMk = kdfChain(receiveChain);
        receiveChain = ckMk[0];
        receiveCount++;
        return aead(Cipher.DECRYPT_MODE, ckMk[1], ciphertext, ad2);
    }

    private void skipMessageKeys(int until) {
        if (receiveChain == null) return;
        if (until - receiveCount > MAX_SKIP) throw new SecurityException("Too many skipped messages");
        while (receiveCount < until) {
            byte[][] ckMk = kdfChain(receiveChain);
            receiveChain = ckMk[0];
            skipped.put(id(dhRemote, receiveCount), ckMk[1]);
            receiveCount++;
        }
        Iterator<Map.Entry<String, byte[]>> it = skipped.entrySet().iterator();
        while (skipped.size() > MAX_STORED_SKIPPED && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private void dhRatchet(Header h) {
        previousSendCount = sendCount;
        sendCount = 0;
        receiveCount = 0;
        dhRemote = h.dh().clone();
        byte[][] rkCk = kdfRoot(rootKey, X25519.dh(dhSelf, dhRemote));
        rootKey = rkCk[0];
        receiveChain = rkCk[1];
        dhSelf = X25519.generate();
        rkCk = kdfRoot(rootKey, X25519.dh(dhSelf, dhRemote));
        rootKey = rkCk[0];
        sendChain = rkCk[1];
    }

    // ------------------------------------------------------------------ KDFs and AEAD

    private static byte[][] kdfRoot(byte[] rootKey, byte[] dhOut) {
        byte[] okm = Hkdf.expand(Hkdf.extract(rootKey, dhOut), RK_INFO, 64);
        Arrays.fill(dhOut, (byte) 0);
        return new byte[][] {Arrays.copyOfRange(okm, 0, 32), Arrays.copyOfRange(okm, 32, 64)};
    }

    /** @return {next chain key, message key} */
    private static byte[][] kdfChain(byte[] chainKey) {
        return new byte[][] {hmac(chainKey, (byte) 0x02), hmac(chainKey, (byte) 0x01)};
    }

    private static byte[] hmac(byte[] key, byte constant) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(new byte[] {constant});
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Each message key is used exactly once, so a nonce derived from it is safe. */
    private static byte[] aead(int mode, byte[] messageKey, byte[] input, byte[] ad) {
        byte[] okm = Hkdf.expand(Hkdf.extract(new byte[32], messageKey), MK_INFO, 44);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(okm, 0, 32, "AES"), new GCMParameterSpec(128, okm, 32, 12));
            cipher.updateAAD(ad);
            return cipher.doFinal(input);
        } catch (AEADBadTagException e) {
            throw new SecurityException("Message failed authentication", e);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("Malformed message", e);
        } finally {
            Arrays.fill(okm, (byte) 0);
        }
    }

    // ------------------------------------------------------------------ state handling

    private static String id(byte[] dh, int n) {
        return HexFormat.of().formatHex(dh) + ":" + n;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        return ByteBuffer.allocate(a.length + b.length).put(a).put(b).array();
    }

    private static byte[] copyOrNull(byte[] b) {
        return b == null ? null : b.clone();
    }

    private DoubleRatchet copy() {
        DoubleRatchet c = new DoubleRatchet();
        c.dhSelf = dhSelf;
        c.dhRemote = copyOrNull(dhRemote);
        c.rootKey = rootKey.clone();
        c.sendChain = copyOrNull(sendChain);
        c.receiveChain = copyOrNull(receiveChain);
        c.sendCount = sendCount;
        c.receiveCount = receiveCount;
        c.previousSendCount = previousSendCount;
        skipped.forEach((k, v) -> c.skipped.put(k, v.clone()));
        return c;
    }

    private void commit(DoubleRatchet t) {
        dhSelf = t.dhSelf;
        dhRemote = t.dhRemote;
        rootKey = t.rootKey;
        sendChain = t.sendChain;
        receiveChain = t.receiveChain;
        sendCount = t.sendCount;
        receiveCount = t.receiveCount;
        previousSendCount = t.previousSendCount;
        skipped.clear();
        skipped.putAll(t.skipped);
    }

    void write(Wire.Writer w) {
        w.bytes(X25519.encode(dhSelf.getPublic())).bytes(dhSelf.getPrivate().getEncoded());
        w.bytes(dhRemote == null ? new byte[0] : dhRemote).bytes(rootKey);
        w.bytes(sendChain == null ? new byte[0] : sendChain).bytes(receiveChain == null ? new byte[0] : receiveChain);
        w.i64(sendCount).i64(receiveCount).i64(previousSendCount);
        w.u16(skipped.size());
        skipped.forEach((k, v) -> w.string(k).bytes(v));
    }

    static DoubleRatchet read(Wire.Reader r) throws ProtocolException {
        DoubleRatchet d = new DoubleRatchet();
        byte[] pub = r.bytes(64);
        byte[] priv = r.bytes(128);
        d.dhSelf = X25519.decodePair(pub, priv);
        d.dhRemote = emptyToNull(r.bytes(64));
        d.rootKey = r.exactBytes(32);
        d.sendChain = emptyToNull(r.bytes(32));
        d.receiveChain = emptyToNull(r.bytes(32));
        d.sendCount = (int) r.i64();
        d.receiveCount = (int) r.i64();
        d.previousSendCount = (int) r.i64();
        int n = r.count(MAX_STORED_SKIPPED);
        for (int i = 0; i < n; i++) d.skipped.put(r.string(200), r.exactBytes(32));
        return d;
    }

    private static byte[] emptyToNull(byte[] b) {
        return b.length == 0 ? null : b;
    }
}
