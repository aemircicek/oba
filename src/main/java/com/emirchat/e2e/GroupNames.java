package com.emirchat.e2e;

import com.emirchat.protocol.Wire;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * End-to-end encrypted group names. The group's admin holds a random 32-byte name key, encrypts
 * the name with it and stores only the ciphertext on the server; members receive the key over the
 * pairwise end-to-end channel. Whenever someone leaves, the admin makes a new key (a new version),
 * so former members cannot read later names.
 *
 * <pre>
 *   sealed name  = u8 1 ‖ i64 version ‖ nonce (12) ‖ AES-256-GCM(key, name)
 *   associated   = "secure-chat group name v1" ‖ i64 groupId ‖ i64 version
 *   distribution = i64 groupId ‖ i64 version ‖ key
 * </pre>
 * Binding the group id means the server cannot move a name from one group to another.
 */
public final class GroupNames {
    public static final int MAX_NAME_BYTES = 64;
    private static final int FORMAT = 1;
    private static final int KEY_LENGTH = 32;
    private static final int NONCE_LENGTH = 12;
    private static final byte[] LABEL = "secure-chat group name v1".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final class State {
        long version;
        byte[] key;
        final Set<String> distributedTo = new LinkedHashSet<>();
    }

    private final Path dir;
    private final Map<Long, State> cache = new HashMap<>();

    public GroupNames(Path e2eDir) {
        this.dir = e2eDir.resolve("group-names");
    }

    public synchronized boolean hasKey(long groupId) throws IOException {
        return state(groupId) != null;
    }

    /** Makes a new name key (the next version); members must receive it before they can read new names. */
    public synchronized void rotate(long groupId) throws IOException {
        State old = state(groupId);
        State s = new State();
        s.version = old == null ? 1 : old.version + 1;
        s.key = new byte[KEY_LENGTH];
        RANDOM.nextBytes(s.key);
        cache.put(groupId, s);
        save(groupId, s);
    }

    public synchronized byte[] seal(long groupId, String name) throws IOException {
        State s = state(groupId);
        if (s == null) throw new IllegalStateException("No name key for group " + groupId);
        byte[] plain = name.getBytes(StandardCharsets.UTF_8);
        if (plain.length == 0 || plain.length > MAX_NAME_BYTES) throw new IllegalArgumentException("Group names are 1-64 bytes");
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        byte[] ct = gcm(Cipher.ENCRYPT_MODE, s.key, nonce, associated(groupId, s.version), plain, 0, plain.length);
        return new Wire.Writer().u8(FORMAT).i64(s.version).bytes(nonce).bytes(ct).toByteArray();
    }

    /** @return the name, or null if we lack the key for that version or the ciphertext is not authentic */
    public synchronized String open(long groupId, byte[] sealed) throws IOException {
        State s = state(groupId);
        if (s == null || sealed.length == 0) return null;
        try {
            Wire.Reader r = new Wire.Reader(sealed);
            if (r.u8() != FORMAT) return null;
            long version = r.i64();
            byte[] nonce = r.exactBytes(NONCE_LENGTH);
            byte[] ct = r.bytes(MAX_NAME_BYTES + 16);
            r.expectEnd();
            if (version != s.version) return null;
            byte[] plain = gcm(Cipher.DECRYPT_MODE, s.key, nonce, associated(groupId, version), ct, 0, ct.length);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (ProtocolException | SecurityException e) {
            return null;
        }
    }

    /** The version a sealed name claims, or -1 if malformed. */
    public static long versionOf(byte[] sealed) {
        try {
            Wire.Reader r = new Wire.Reader(sealed);
            return r.u8() == FORMAT ? r.i64() : -1;
        } catch (ProtocolException e) {
            return -1;
        }
    }

    public synchronized byte[] distribution(long groupId) throws IOException {
        State s = state(groupId);
        if (s == null) throw new IllegalStateException("No name key for group " + groupId);
        return new Wire.Writer().i64(groupId).i64(s.version).bytes(s.key).toByteArray();
    }

    /** The group a distribution is for (checked by the caller against the sender's role). */
    public static long groupOf(byte[] distribution) throws ProtocolException {
        return new Wire.Reader(distribution).i64();
    }

    /**
     * Stores a key handed out by the group's admin. Older or equal versions are ignored, so a
     * replayed distribution cannot roll the key back.
     *
     * @return true if the key was new
     */
    public synchronized boolean accept(long expectedGroupId, byte[] distribution) throws IOException {
        long version;
        byte[] key;
        try {
            Wire.Reader r = new Wire.Reader(distribution);
            if (r.i64() != expectedGroupId) throw new SecurityException("Name key for another group");
            version = r.i64();
            key = r.exactBytes(KEY_LENGTH);
            r.expectEnd();
        } catch (ProtocolException e) {
            throw new SecurityException("Malformed group name key");
        }
        State current = state(expectedGroupId);
        if (version < 1 || (current != null && version <= current.version)) return false;
        State s = new State();
        s.version = version;
        s.key = key;
        cache.put(expectedGroupId, s);
        save(expectedGroupId, s);
        return true;
    }

    /** Members (other than us) who have not received the current key yet. */
    public synchronized Set<String> needsKey(long groupId, Collection<String> members, String me) throws IOException {
        State s = state(groupId);
        Set<String> result = new LinkedHashSet<>();
        if (s == null) return result;
        for (String m : members) if (!m.equals(me) && !s.distributedTo.contains(m)) result.add(m);
        return result;
    }

    public synchronized void markDistributed(long groupId, String member) throws IOException {
        State s = state(groupId);
        if (s != null && s.distributedTo.add(member)) save(groupId, s);
    }

    public synchronized void forget(long groupId) throws IOException {
        cache.remove(groupId);
        Files.deleteIfExists(file(groupId));
    }

    private static byte[] associated(long groupId, long version) {
        return new Wire.Writer().bytes(LABEL).i64(groupId).i64(version).toByteArray();
    }

    private static byte[] gcm(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] data, int off, int len) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            c.updateAAD(aad);
            return c.doFinal(data, off, len);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new SecurityException("Group name failed authentication");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable", e);
        }
    }

    private Path file(long groupId) {
        return dir.resolve(groupId + ".bin");
    }

    private State state(long groupId) throws IOException {
        State s = cache.get(groupId);
        if (s != null) return s;
        byte[] data = SecureFiles.read(file(groupId));
        if (data == null) return null;
        try {
            Wire.Reader r = new Wire.Reader(data);
            s = new State();
            s.version = r.i64();
            s.key = r.exactBytes(KEY_LENGTH);
            int n = r.count(1000);
            for (int i = 0; i < n; i++) s.distributedTo.add(r.string(64));
            r.expectEnd();
        } catch (ProtocolException e) {
            throw new IOException("Corrupt group name key for group " + groupId, e);
        }
        cache.put(groupId, s);
        return s;
    }

    private void save(long groupId, State s) throws IOException {
        Files.createDirectories(dir);
        Wire.Writer w = new Wire.Writer().i64(s.version).bytes(s.key).u16(s.distributedTo.size());
        for (String m : s.distributedTo) w.string(m);
        SecureFiles.writeAtomically(file(groupId), w.toByteArray());
    }
}
