package com.emirchat.call;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * End-to-end encryption for voice rooms. Everyone in a room has their own random sending key,
 * which they hand to the group through its existing end-to-end encrypted channel (so it is
 * authenticated as theirs and the server never sees it), and replace whenever someone joins or
 * leaves: a person who left cannot decrypt what is said after.
 *
 * <pre>
 *   packet = u32 keyId ‖ u64 seq ‖ AES-256-GCM(key, nonce = keyId ‖ seq, aad = "scorpion voice v1" ‖ groupId ‖ sender, audio)
 * </pre>
 *
 * A receiver accepts each (sender, key, seq) at most once and never an older seq, so the relay
 * can drop or delay packets but not replay, rewind or alter them, nor make one person's audio
 * appear to come from another.
 */
public final class VoiceCrypto {
    public static final int KEY_BYTES = 32;
    /** Named like the call label (the app's former name); part of the protocol, never changes. */
    private static final byte[] LABEL = "scorpion voice v1".getBytes(StandardCharsets.US_ASCII);
    private static final int TAG_BYTES = 16;
    private static final int HEADER = 12;
    /** Older keys of a sender still accepted (packets in flight across a rotation). */
    private static final int KEYS_KEPT = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** One sending key: a random id (to tell keys apart) and 32 random bytes. */
    public record Key(int id, byte[] secret) {
        public Key {
            if (secret.length != KEY_BYTES) throw new IllegalArgumentException("Bad voice key");
            secret = secret.clone();
        }

        @Override public byte[] secret() { return secret.clone(); }

        @Override public String toString() { return "Key[" + id + "]"; } // never print the secret
    }

    private VoiceCrypto() {}

    public static Key newKey() {
        byte[] secret = new byte[KEY_BYTES];
        RANDOM.nextBytes(secret);
        return new Key(RANDOM.nextInt(), secret);
    }

    /** The key as sent to the group (inside its encrypted channel). */
    public static String encodeKey(Key key) {
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(4 + KEY_BYTES).putInt(key.id()).put(key.secret).array());
    }

    public static Key decodeKey(String value) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Malformed voice key");
        }
        if (raw.length != 4 + KEY_BYTES) throw new SecurityException("Malformed voice key");
        ByteBuffer b = ByteBuffer.wrap(raw);
        int id = b.getInt();
        byte[] secret = new byte[KEY_BYTES];
        b.get(secret);
        return new Key(id, secret);
    }

    private static byte[] aad(long groupId, String sender) {
        byte[] name = sender.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(LABEL.length + 8 + name.length).put(LABEL).putLong(groupId).put(name).array();
    }

    /** Our side: encrypts each audio packet with our current key and a fresh sequence number. */
    public static final class Sender {
        private final Key key;
        private final SecretKeySpec spec;
        private long seq;

        public Sender(Key key) {
            this.key = key;
            this.spec = new SecretKeySpec(key.secret, "AES");
        }

        public Key key() {
            return key;
        }

        public synchronized byte[] seal(long groupId, String me, byte[] audio) {
            long n = seq++;
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, spec, new GCMParameterSpec(TAG_BYTES * 8, nonce(key.id(), n)));
                c.updateAAD(aad(groupId, me));
                byte[] body = c.doFinal(audio);
                return ByteBuffer.allocate(HEADER + body.length).putInt(key.id()).putLong(n).put(body).array();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Everyone else's keys and where their sequence numbers stand. */
    public static final class Receiver {
        private final Map<String, Deque<Key>> keys = new HashMap<>();
        private final Map<String, Long> lastSeq = new HashMap<>();

        public synchronized void accept(String sender, Key key) {
            Deque<Key> list = keys.computeIfAbsent(sender, k -> new ArrayDeque<>());
            if (list.stream().anyMatch(k -> k.id() == key.id())) return;
            list.addFirst(key);
            while (list.size() > KEYS_KEPT) {
                Key dropped = list.removeLast();
                lastSeq.remove(sender + "/" + dropped.id());
            }
        }

        public synchronized void forget(String sender) {
            Deque<Key> list = keys.remove(sender);
            if (list != null) for (Key k : list) lastSeq.remove(sender + "/" + k.id());
        }

        public synchronized void clear() {
            keys.clear();
            lastSeq.clear();
        }

        public synchronized boolean hasKey(String sender) {
            Deque<Key> list = keys.get(sender);
            return list != null && !list.isEmpty();
        }

        /** The audio, or an exception if the packet is forged, damaged, replayed or from an unknown key. */
        public synchronized byte[] open(long groupId, String sender, byte[] packet) {
            if (packet.length < HEADER + TAG_BYTES) throw new SecurityException("Short voice packet");
            ByteBuffer b = ByteBuffer.wrap(packet);
            int keyId = b.getInt();
            long seq = b.getLong();
            Deque<Key> list = keys.get(sender);
            Key key = list == null ? null : list.stream().filter(k -> k.id() == keyId).findFirst().orElse(null);
            if (key == null) throw new SecurityException("No voice key from " + sender);
            String slot = sender + "/" + keyId;
            Long last = lastSeq.get(slot);
            if (seq < 0 || (last != null && seq <= last)) throw new SecurityException("Replayed voice packet");
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.secret, "AES"), new GCMParameterSpec(TAG_BYTES * 8, nonce(keyId, seq)));
                c.updateAAD(aad(groupId, sender));
                byte[] audio = c.doFinal(packet, HEADER, packet.length - HEADER);
                lastSeq.put(slot, seq);
                return audio;
            } catch (GeneralSecurityException e) {
                throw new SecurityException("Voice packet failed authentication");
            }
        }
    }

    private static byte[] nonce(int keyId, long seq) {
        return ByteBuffer.allocate(12).putInt(keyId).putLong(seq).array();
    }
}
