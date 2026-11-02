package com.emirchat.e2e;

import com.emirchat.crypto.Hkdf;
import com.emirchat.crypto.Identity;
import com.emirchat.protocol.Wire;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Group encryption with Sender Keys (as in Signal and WhatsApp groups).
 *
 * <p>Each member owns a sender key per group: a hash chain (each message key is used once and the
 * chain only moves forward) plus an Ed25519 signing key. The current chain state is handed to
 * every member over the pairwise end-to-end channel. A message is encrypted once and signed, so
 * members can read it but cannot forge messages in someone else's name. When anyone leaves or is
 * removed, everyone rotates to a fresh sender key that the departed member never receives.
 *
 * <pre>
 *   group message: u8 3 ‖ u8 kind=3 ‖ i64 groupId ‖ i64 keyId ‖ i64 iteration ‖ ciphertext ‖ signature
 *   distribution:  u8 3 ‖ i64 groupId ‖ i64 keyId ‖ i64 iteration ‖ chainKey ‖ signing public key
 * </pre>
 */
public final class GroupKeys {
    static final int MAX_SKIP = 2000;
    private static final int VERSION = 3;
    private static final int KIND_GROUP = 3;
    private static final int MAX_CIPHERTEXT = 32 * 1024;
    private static final byte[] MK_INFO = "SecureChat-v3 group message keys".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Our own sender key for a group, and who has received it. */
    private static final class Own {
        int keyId;
        byte[] chainKey;
        int iteration;
        KeyPair signing;
        final Set<String> distributedTo = new LinkedHashSet<>();
    }

    /** Someone else's sender key as we know it. */
    private static final class Received {
        byte[] chainKey;
        int iteration;
        byte[] signingPublic;
        final LinkedHashMap<Integer, byte[]> skipped = new LinkedHashMap<>();

        Received copy() {
            Received c = new Received();
            c.chainKey = chainKey.clone();
            c.iteration = iteration;
            c.signingPublic = signingPublic;
            skipped.forEach((k, v) -> c.skipped.put(k, v.clone()));
            return c;
        }
    }

    private static final class GroupState {
        Own own;
        /** sender → keyId → state. Older key ids are kept briefly for messages in flight. */
        final Map<String, LinkedHashMap<Integer, Received>> received = new HashMap<>();
    }

    private final Path dir;
    private final Map<Long, GroupState> groups = new HashMap<>();

    public GroupKeys(Path dir) {
        this.dir = dir;
    }

    // ------------------------------------------------------------------ sending

    /** Members (excluding ourselves) that have not yet received our current sender key. */
    public synchronized Set<String> needsKey(long groupId, Collection<String> members, String me) throws IOException {
        Own own = own(groupId);
        Set<String> missing = new LinkedHashSet<>();
        for (String m : members) if (!m.equals(me) && !own.distributedTo.contains(m)) missing.add(m);
        return missing;
    }

    /** Our current sender key state, to send to a member over the pairwise channel. */
    public synchronized byte[] distribution(long groupId) throws IOException {
        Own own = own(groupId);
        return new Wire.Writer().u8(VERSION).i64(groupId).i64(own.keyId).i64(own.iteration)
                .bytes(own.chainKey).bytes(own.signing.getPublic().getEncoded()).toByteArray();
    }

    public synchronized void markDistributed(long groupId, String member) throws IOException {
        own(groupId).distributedTo.add(member);
        save(groupId);
    }

    /**
     * Rotates if anyone who holds our current key is no longer a member. Checked before every send,
     * so a removal is honoured even if the membership push was missed while we were offline.
     *
     * @return true if the key was rotated
     */
    public synchronized boolean rotateIfAnyoneLeft(long groupId, Collection<String> members) throws IOException {
        Own own = own(groupId);
        for (String holder : own.distributedTo) {
            if (!members.contains(holder)) {
                rotate(groupId);
                return true;
            }
        }
        return false;
    }

    /** Starts a fresh sender key; nobody has it until it is distributed again. */
    public synchronized void rotate(long groupId) throws IOException {
        state(groupId).own = newOwn();
        save(groupId);
    }

    public synchronized byte[] encrypt(long groupId, byte[] plaintext) throws IOException {
        Own own = own(groupId);
        byte[][] next = chain(own.chainKey);
        byte[] header = header(groupId, own.keyId, own.iteration);
        byte[] ciphertext = aead(Cipher.ENCRYPT_MODE, next[1], plaintext, header);
        own.chainKey = next[0];
        own.iteration++;
        byte[] signed = new Wire.Writer().bytes(header).bytes(ciphertext).toByteArray();
        byte[] signature = sign(own.signing, signed);
        save(groupId);
        return new Wire.Writer().bytes(header).bytes(ciphertext).bytes(signature).toByteArray();
    }

    // ------------------------------------------------------------------ receiving

    /** Stores a member's sender key received over the (authenticated) pairwise channel. */
    public synchronized void acceptDistribution(long expectedGroupId, String sender, byte[] distribution) throws IOException {
        try {
            Wire.Reader r = new Wire.Reader(distribution);
            if (r.u8() != VERSION) throw new SecurityException("Unsupported sender key version");
            long groupId = r.i64();
            if (groupId != expectedGroupId) throw new SecurityException("Sender key for a different group");
            int keyId = (int) r.i64();
            int iteration = (int) r.i64();
            Received rec = new Received();
            rec.chainKey = r.exactBytes(32);
            rec.iteration = iteration;
            rec.signingPublic = r.exactBytes(Identity.ENCODED_PUBLIC_KEY_LENGTH);
            r.expectEnd();
            if (iteration < 0) throw new SecurityException("Invalid sender key");
            LinkedHashMap<Integer, Received> keys = state(groupId).received.computeIfAbsent(sender, s -> new LinkedHashMap<>());
            // A chain we already hold can derive every later key itself; accepting the same key id again
            // could only rewind it and re-open messages we have already read (a replay).
            if (keys.containsKey(keyId)) return;
            keys.put(keyId, rec);
            while (keys.size() > 3) keys.remove(keys.keySet().iterator().next());
            save(groupId);
        } catch (ProtocolException e) {
            throw new SecurityException("Malformed sender key: " + e.getMessage(), e);
        }
    }

    /** @throws SecurityException for unknown keys, bad signatures, replays or forgeries (state unchanged) */
    public synchronized byte[] decrypt(long groupId, String sender, byte[] message) throws IOException {
        try {
            Wire.Reader r = new Wire.Reader(message);
            byte[] header = r.bytes(64);
            byte[] ciphertext = r.bytes(MAX_CIPHERTEXT);
            byte[] signature = r.exactBytes(64);
            r.expectEnd();
            Wire.Reader h = new Wire.Reader(header);
            if (h.u8() != VERSION || h.u8() != KIND_GROUP) throw new SecurityException("Not a group message");
            if (h.i64() != groupId) throw new SecurityException("Message for a different group");
            int keyId = (int) h.i64();
            int iteration = (int) h.i64();
            h.expectEnd();

            Map<Integer, Received> keys = state(groupId).received.get(sender);
            Received stored = keys == null ? null : keys.get(keyId);
            if (stored == null) throw new SecurityException("No sender key from " + sender + " (yet)");
            if (!verify(stored.signingPublic, new Wire.Writer().bytes(header).bytes(ciphertext).toByteArray(), signature)) {
                throw new SecurityException("Bad signature: not sent by " + sender);
            }

            Received trial = stored.copy();
            byte[] mk = trial.skipped.remove(iteration);
            if (mk == null) {
                if (iteration < trial.iteration) throw new SecurityException("Replayed group message");
                if (iteration - trial.iteration > MAX_SKIP) throw new SecurityException("Too many skipped messages");
                while (trial.iteration < iteration) {
                    byte[][] next = chain(trial.chainKey);
                    trial.skipped.put(trial.iteration, next[1]);
                    trial.chainKey = next[0];
                    trial.iteration++;
                }
                byte[][] next = chain(trial.chainKey);
                mk = next[1];
                trial.chainKey = next[0];
                trial.iteration++;
                Iterator<Integer> it = trial.skipped.keySet().iterator();
                while (trial.skipped.size() > MAX_SKIP && it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            byte[] plaintext = aead(Cipher.DECRYPT_MODE, mk, ciphertext, header);
            keys.put(keyId, trial); // commit only after authentication
            save(groupId);
            return plaintext;
        } catch (ProtocolException e) {
            throw new SecurityException("Malformed group message: " + e.getMessage(), e);
        }
    }

    /** Drops what we know about a member's keys (they left), so later messages under them are refused. */
    public synchronized void forgetSender(long groupId, String sender) throws IOException {
        if (state(groupId).received.remove(sender) != null) save(groupId);
    }

    public synchronized void forgetGroup(long groupId) throws IOException {
        groups.remove(groupId);
        Files.deleteIfExists(file(groupId));
    }

    // ------------------------------------------------------------------ crypto helpers

    private static byte[] header(long groupId, int keyId, int iteration) {
        return new Wire.Writer().u8(VERSION).u8(KIND_GROUP).i64(groupId).i64(keyId).i64(iteration).toByteArray();
    }

    /** @return {next chain key, message key} */
    private static byte[][] chain(byte[] chainKey) {
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

    private static byte[] aead(int mode, byte[] messageKey, byte[] input, byte[] ad) {
        byte[] okm = Hkdf.expand(Hkdf.extract(new byte[32], messageKey), MK_INFO, 44);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(okm, 0, 32, "AES"), new GCMParameterSpec(128, okm, 32, 12));
            cipher.updateAAD(ad);
            return cipher.doFinal(input);
        } catch (AEADBadTagException e) {
            throw new SecurityException("Group message failed authentication", e);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("Malformed group message", e);
        } finally {
            java.util.Arrays.fill(okm, (byte) 0);
        }
    }

    private static byte[] sign(KeyPair key, byte[] data) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key.getPrivate());
            s.update(data);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean verify(byte[] publicKey, byte[] data, byte[] signature) {
        return Identity.verify(publicKey, data, signature);
    }

    private static Own newOwn() {
        Own own = new Own();
        own.keyId = RANDOM.nextInt(Integer.MAX_VALUE);
        own.chainKey = new byte[32];
        RANDOM.nextBytes(own.chainKey);
        try {
            own.signing = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        return own;
    }

    // ------------------------------------------------------------------ persistence

    private Own own(long groupId) throws IOException {
        GroupState s = state(groupId);
        if (s.own == null) {
            s.own = newOwn();
            save(groupId);
        }
        return s.own;
    }

    private Path file(long groupId) {
        return dir.resolve("groups").resolve(groupId + ".bin");
    }

    private GroupState state(long groupId) throws IOException {
        GroupState s = groups.get(groupId);
        if (s != null) return s;
        s = new GroupState();
        byte[] data = SecureFiles.read(file(groupId));
        if (data != null) {
            try {
                Wire.Reader r = new Wire.Reader(data);
                if (r.u8() == 1) {
                    Own own = new Own();
                    own.keyId = (int) r.i64();
                    own.chainKey = r.exactBytes(32);
                    own.iteration = (int) r.i64();
                    KeyFactory kf = KeyFactory.getInstance("Ed25519");
                    own.signing = new KeyPair(kf.generatePublic(new X509EncodedKeySpec(r.bytes(64))),
                            kf.generatePrivate(new PKCS8EncodedKeySpec(r.bytes(128))));
                    int n = r.count(1000);
                    for (int i = 0; i < n; i++) own.distributedTo.add(r.string(64));
                    s.own = own;
                }
                int senders = r.count(1000);
                for (int i = 0; i < senders; i++) {
                    String sender = r.string(64);
                    LinkedHashMap<Integer, Received> keys = new LinkedHashMap<>();
                    int k = r.count(10);
                    for (int j = 0; j < k; j++) {
                        int keyId = (int) r.i64();
                        Received rec = new Received();
                        rec.chainKey = r.exactBytes(32);
                        rec.iteration = (int) r.i64();
                        rec.signingPublic = r.exactBytes(Identity.ENCODED_PUBLIC_KEY_LENGTH);
                        int sk = r.count(MAX_SKIP);
                        for (int x = 0; x < sk; x++) rec.skipped.put((int) r.i64(), r.exactBytes(32));
                        keys.put(keyId, rec);
                    }
                    s.received.put(sender, keys);
                }
                r.expectEnd();
            } catch (ProtocolException | GeneralSecurityException e) {
                throw new IOException("Corrupt group key file for group " + groupId, e);
            }
        }
        groups.put(groupId, s);
        return s;
    }

    private void save(long groupId) throws IOException {
        GroupState s = groups.get(groupId);
        Wire.Writer w = new Wire.Writer();
        if (s.own == null) {
            w.u8(0);
        } else {
            w.u8(1).i64(s.own.keyId).bytes(s.own.chainKey).i64(s.own.iteration)
                    .bytes(s.own.signing.getPublic().getEncoded()).bytes(s.own.signing.getPrivate().getEncoded())
                    .u16(s.own.distributedTo.size());
            for (String m : s.own.distributedTo) w.string(m);
        }
        w.u16(s.received.size());
        s.received.forEach((sender, keys) -> {
            w.string(sender).u16(keys.size());
            keys.forEach((keyId, rec) -> {
                w.i64(keyId).bytes(rec.chainKey).i64(rec.iteration).bytes(rec.signingPublic).u16(rec.skipped.size());
                rec.skipped.forEach((it, mk) -> w.i64(it).bytes(mk));
            });
        });
        SecureFiles.writeAtomically(file(groupId), w.toByteArray());
    }
}
