package com.emirchat.e2e;

import com.emirchat.crypto.Identity;
import com.emirchat.crypto.X25519;
import com.emirchat.protocol.Wire;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * End-to-end encryption for one account on this device: X3DH to start sessions, the Double
 * Ratchet for every message, and persistence of keys and sessions.
 *
 * <p>Message format (opaque to the server):
 * <pre>
 *   u8 version=3 ‖ u8 kind
 *   kind 1 (prekey):  senderDhIdentity ‖ signature ‖ ephemeral ‖ i64 spkId ‖ i64 opkId ‖ ratchet message
 *   kind 2 (normal):  ratchet message
 *   ratchet message:  header (ratchet key, counters) ‖ AES-256-GCM ciphertext
 * </pre>
 * The associated data of every message is {@code IK_initiator ‖ IK_responder}, so a message
 * cannot be moved to a session between different people.
 */
public final class Messenger {
    private static final int VERSION = 3;
    private static final int KIND_PREKEY = 1;
    private static final int KIND_NORMAL = 2;
    private static final int MAX_SESSIONS_PER_PEER = 4;
    private static final int MAX_CIPHERTEXT = 32 * 1024;
    private static final Pattern PEER = Pattern.compile("[a-z0-9_.]{3,20}");

    /** Fetches a peer's prekey bundle from the server when a new session is needed. */
    @FunctionalInterface
    public interface BundleSource {
        PrekeyBundle fetch() throws IOException;
    }

    /** What the server needs to publish for this device (one-time prekeys are uploaded separately). */
    public record PublicKeys(byte[] dhIdentity, byte[] dhIdentitySignature, int signedPrekeyId,
                             byte[] signedPrekey, byte[] signedPrekeySignature) {}

    private record Pending(byte[] ephemeral, int signedPrekeyId, int oneTimePrekeyId) {}

    private static final class Session {
        final byte[] peerIdentity;
        final byte[] associatedData;
        final byte[] baseKey; // the initiator's ephemeral key, identifies the session
        final DoubleRatchet ratchet;
        Pending pending;      // initiator only: resend X3DH data until the peer answers

        Session(byte[] peerIdentity, byte[] associatedData, byte[] baseKey, DoubleRatchet ratchet, Pending pending) {
            this.peerIdentity = peerIdentity;
            this.associatedData = associatedData;
            this.baseKey = baseKey;
            this.ratchet = ratchet;
            this.pending = pending;
        }
    }

    private final Path dir;
    private final Identity identity;
    private final DeviceKeys keys;
    private final Map<String, List<Session>> sessions = new HashMap<>();

    private Messenger(Path dir, Identity identity, DeviceKeys keys) {
        this.dir = dir;
        this.identity = identity;
        this.keys = keys;
    }

    /** Opens (or creates) the key and session store in {@code dir} for this device identity. */
    public static Messenger open(Path dir, Identity identity) throws IOException {
        return new Messenger(dir, identity, DeviceKeys.loadOrCreate(dir.resolve("device-keys.bin"), identity));
    }

    public synchronized PublicKeys publicKeys() {
        DeviceKeys.SignedPrekey spk = keys.signedPrekey();
        return new PublicKeys(X25519.encode(keys.dhIdentity().getPublic()), keys.dhIdentitySignature(),
                spk.id(), X25519.encode(spk.pair().getPublic()), spk.signature());
    }

    public synchronized int oneTimePrekeyCount() {
        return keys.oneTimePrekeyCount();
    }

    /** Creates, stores and returns {@code n} one-time prekeys as (id, public key) for upload. */
    public synchronized List<Map.Entry<Integer, byte[]>> newOneTimePrekeys(int n) throws IOException {
        List<Map.Entry<Integer, byte[]>> created = keys.generateOneTimePrekeys(n);
        saveKeys();
        return created;
    }

    /** @return true if the signed prekey was rotated and {@link #publicKeys()} must be re-uploaded */
    public synchronized boolean rotateSignedPrekeyIfDue() throws IOException {
        boolean rotated = keys.rotateSignedPrekeyIfDue(System.currentTimeMillis());
        if (rotated) saveKeys();
        return rotated;
    }

    // ------------------------------------------------------------------ sending

    /**
     * Encrypts for {@code peer}, starting a new session through X3DH when there is none for the
     * peer's current identity key.
     *
     * @param peerIdentity the peer's Ed25519 key as pinned/verified by the user
     */
    public synchronized byte[] encrypt(String peer, byte[] peerIdentity, BundleSource bundles, byte[] plaintext)
            throws IOException {
        checkPeer(peer);
        List<Session> list = sessions(peer);
        Session session = list.stream()
                .filter(s -> Arrays.equals(s.peerIdentity, peerIdentity) && s.ratchet.canSend())
                .findFirst().orElse(null);
        if (session == null) {
            X3dh.Initiation init = X3dh.initiate(keys, peerIdentity, bundles.fetch());
            byte[] ad = concat(identity.publicKeyBytes(), peerIdentity);
            session = new Session(peerIdentity.clone(), ad, init.ephemeralPublic(),
                    DoubleRatchet.initiator(init.sharedSecret(), init.responderRatchetKey()),
                    new Pending(init.ephemeralPublic(), init.signedPrekeyId(), init.oneTimePrekeyId()));
            list.add(0, session);
            trim(list);
        } else {
            promote(list, session);
        }
        DoubleRatchet.Encrypted e = session.ratchet.encrypt(plaintext, session.associatedData);
        Wire.Writer w = new Wire.Writer().u8(VERSION);
        if (session.pending != null) {
            Pending p = session.pending;
            w.u8(KIND_PREKEY).bytes(X25519.encode(keys.dhIdentity().getPublic())).bytes(keys.dhIdentitySignature())
                    .bytes(p.ephemeral()).i64(p.signedPrekeyId()).i64(p.oneTimePrekeyId());
        } else {
            w.u8(KIND_NORMAL);
        }
        w.bytes(e.header().encode()).bytes(e.ciphertext());
        saveSessions(peer);
        return w.toByteArray();
    }

    // ------------------------------------------------------------------ receiving

    /**
     * Decrypts a message from {@code peer}.
     *
     * @param peerIdentity the sender's Ed25519 key as pinned/verified by the user
     * @throws SecurityException if the message is forged, replayed, or from a different key
     */
    public synchronized byte[] decrypt(String peer, byte[] peerIdentity, byte[] message) throws IOException {
        checkPeer(peer);
        try {
            Wire.Reader r = new Wire.Reader(message);
            if (r.u8() != VERSION) throw new SecurityException("Unsupported message version");
            int kind = r.u8();
            List<Session> list = sessions(peer);
            byte[] plaintext;
            if (kind == KIND_PREKEY) {
                byte[] senderDh = r.exactBytes(X25519.PUBLIC_LENGTH);
                byte[] senderDhSig = r.exactBytes(64);
                byte[] ephemeral = r.exactBytes(X25519.PUBLIC_LENGTH);
                int spkId = (int) r.i64();
                int opkId = (int) r.i64();
                DoubleRatchet.Header header = DoubleRatchet.Header.decode(new Wire.Reader(r.bytes(200)));
                byte[] ciphertext = r.bytes(MAX_CIPHERTEXT);
                r.expectEnd();
                Session existing = list.stream()
                        .filter(s -> Arrays.equals(s.baseKey, ephemeral) && Arrays.equals(s.peerIdentity, peerIdentity))
                        .findFirst().orElse(null);
                if (existing != null) {
                    plaintext = existing.ratchet.decrypt(header, ciphertext, existing.associatedData);
                    promote(list, existing);
                } else {
                    byte[] sk = X3dh.respond(keys, peerIdentity, senderDh, senderDhSig, ephemeral, spkId, opkId);
                    KeyPair spk = keys.signedPrekey(spkId);
                    byte[] ad = concat(peerIdentity, identity.publicKeyBytes());
                    Session created = new Session(peerIdentity.clone(), ad, ephemeral, DoubleRatchet.responder(sk, spk), null);
                    plaintext = created.ratchet.decrypt(header, ciphertext, ad); // throws before any state is kept
                    if (opkId >= 0) {
                        keys.takeOneTimePrekey(opkId); // used exactly once
                        saveKeys();
                    }
                    list.add(0, created);
                    trim(list);
                }
            } else if (kind == KIND_NORMAL) {
                DoubleRatchet.Header header = DoubleRatchet.Header.decode(new Wire.Reader(r.bytes(200)));
                byte[] ciphertext = r.bytes(MAX_CIPHERTEXT);
                r.expectEnd();
                plaintext = null;
                for (Session s : List.copyOf(list)) {
                    if (!Arrays.equals(s.peerIdentity, peerIdentity)) continue;
                    try {
                        plaintext = s.ratchet.decrypt(header, ciphertext, s.associatedData);
                    } catch (SecurityException notThisOne) {
                        continue;
                    }
                    s.pending = null; // the peer has answered: X3DH data no longer needed
                    promote(list, s);
                    break;
                }
                if (plaintext == null) throw new SecurityException("No session could decrypt the message");
            } else {
                throw new SecurityException("Unknown message kind");
            }
            saveSessions(peer);
            return plaintext;
        } catch (ProtocolException e) {
            throw new SecurityException("Malformed message: " + e.getMessage(), e);
        }
    }

    /** Forgets all sessions with a peer (e.g. after their key changed and was re-verified). */
    public synchronized void resetSessions(String peer) throws IOException {
        checkPeer(peer);
        sessions.put(peer, new ArrayList<>());
        Files.deleteIfExists(sessionFile(peer));
    }

    // ------------------------------------------------------------------ helpers & persistence

    private static void promote(List<Session> list, Session s) {
        list.remove(s);
        list.add(0, s);
    }

    private static void trim(List<Session> list) {
        while (list.size() > MAX_SESSIONS_PER_PEER) list.remove(list.size() - 1);
    }

    private static void checkPeer(String peer) {
        if (peer == null || !PEER.matcher(peer).matches()) throw new IllegalArgumentException("Invalid peer name");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private Path sessionFile(String peer) {
        return dir.resolve("sessions").resolve(peer + ".bin");
    }

    private void saveKeys() throws IOException {
        keys.save(dir.resolve("device-keys.bin"));
    }

    private List<Session> sessions(String peer) throws IOException {
        List<Session> list = sessions.get(peer);
        if (list != null) return list;
        list = new ArrayList<>();
        byte[] data = SecureFiles.read(sessionFile(peer));
        if (data != null) {
            try {
                Wire.Reader r = new Wire.Reader(data);
                int n = r.count(MAX_SESSIONS_PER_PEER);
                for (int i = 0; i < n; i++) {
                    byte[] peerIdentity = r.exactBytes(Identity.ENCODED_PUBLIC_KEY_LENGTH);
                    byte[] ad = r.bytes(200);
                    byte[] base = r.exactBytes(X25519.PUBLIC_LENGTH);
                    Pending pending = r.u8() == 1
                            ? new Pending(r.exactBytes(X25519.PUBLIC_LENGTH), (int) r.i64(), (int) r.i64()) : null;
                    list.add(new Session(peerIdentity, ad, base, DoubleRatchet.read(r), pending));
                }
                r.expectEnd();
            } catch (ProtocolException | SecurityException e) {
                throw new IOException("Corrupt session file for " + peer, e);
            }
        }
        sessions.put(peer, list);
        return list;
    }

    private void saveSessions(String peer) throws IOException {
        List<Session> list = sessions.get(peer);
        Wire.Writer w = new Wire.Writer().u16(list.size());
        for (Session s : list) {
            w.bytes(s.peerIdentity).bytes(s.associatedData).bytes(s.baseKey);
            if (s.pending == null) {
                w.u8(0);
            } else {
                w.u8(1).bytes(s.pending.ephemeral()).i64(s.pending.signedPrekeyId()).i64(s.pending.oneTimePrekeyId());
            }
            s.ratchet.write(w);
        }
        SecureFiles.writeAtomically(sessionFile(peer), w.toByteArray());
    }
}
