package com.emirchat.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.emirchat.crypto.Identity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessengerTest {
    @TempDir Path dir;

    /** A user with their device identity, messenger and a fake "server" holding their prekeys. */
    private static final class User {
        final String name;
        final Identity identity = Identity.generate();
        final Path home;
        Messenger messenger;
        List<Map.Entry<Integer, byte[]>> serverPrekeys;

        User(String name, Path root) throws Exception {
            this.name = name;
            this.home = root.resolve(name);
            this.messenger = Messenger.open(home, identity);
            this.serverPrekeys = new java.util.ArrayList<>(messenger.newOneTimePrekeys(5));
        }

        /** What the server would hand out: consumes one one-time prekey per fetch, like the real server. */
        PrekeyBundle bundle() {
            Messenger.PublicKeys k = messenger.publicKeys();
            Map.Entry<Integer, byte[]> opk = serverPrekeys.isEmpty() ? null : serverPrekeys.remove(0);
            return new PrekeyBundle(identity.publicKeyBytes(), k.dhIdentity(), k.dhIdentitySignature(),
                    k.signedPrekeyId(), k.signedPrekey(), k.signedPrekeySignature(),
                    opk == null ? -1 : opk.getKey(), opk == null ? new byte[0] : opk.getValue());
        }

        byte[] send(User to, String text) throws Exception {
            return messenger.encrypt(to.name, to.identity.publicKeyBytes(), to::bundle, bytes(text));
        }

        String receive(User from, byte[] message) throws Exception {
            return new String(messenger.decrypt(from.name, from.identity.publicKeyBytes(), message), StandardCharsets.UTF_8);
        }

        void restart() throws Exception {
            messenger = Messenger.open(home, identity);
        }
    }

    private User emir;
    private User efe;

    @BeforeEach
    void users() throws Exception {
        emir = new User("emir", dir);
        efe = new User("efe", dir);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void conversationBothWays() throws Exception {
        assertEquals("merhaba", efe.receive(emir, emir.send(efe, "merhaba")));
        assertEquals("selam!", emir.receive(efe, efe.send(emir, "selam!")));
        for (int i = 0; i < 20; i++) {
            assertEquals("e" + i, efe.receive(emir, emir.send(efe, "e" + i)));
            if (i % 3 == 0) assertEquals("f" + i, emir.receive(efe, efe.send(emir, "f" + i)));
        }
    }

    @Test
    void firstMessagesCarryX3dhUntilAnswered() throws Exception {
        byte[] m1 = emir.send(efe, "one");
        byte[] m2 = emir.send(efe, "two");
        assertEquals(1, m1[1], "prekey message");
        assertEquals(1, m2[1], "still prekey: efe has not answered yet");
        assertEquals("two", efe.receive(emir, m2)); // the first one was lost
        assertEquals("one", efe.receive(emir, m1)); // ...and arrives late
        emir.receive(efe, efe.send(emir, "got them"));
        assertEquals(2, emir.send(efe, "three")[1], "normal message after the answer");
    }

    @Test
    void outOfOrderDelivery() throws Exception {
        efe.receive(emir, emir.send(efe, "hello"));
        emir.receive(efe, efe.send(emir, "hi"));
        byte[] a = emir.send(efe, "a");
        byte[] b = emir.send(efe, "b");
        byte[] c = emir.send(efe, "c");
        assertEquals("c", efe.receive(emir, c));
        assertEquals("a", efe.receive(emir, a));
        assertEquals("b", efe.receive(emir, b));
    }

    @Test
    void replayIsRejected() throws Exception {
        byte[] m = emir.send(efe, "pay 10 TL");
        efe.receive(emir, m);
        emir.receive(efe, efe.send(emir, "ok"));
        byte[] n = emir.send(efe, "normal");
        efe.receive(emir, n);
        assertThrows(SecurityException.class, () -> efe.receive(emir, n));
    }

    @Test
    void tamperingIsDetectedAndDoesNotBreakTheSession() throws Exception {
        efe.receive(emir, emir.send(efe, "hello"));
        emir.receive(efe, efe.send(emir, "hi"));
        byte[] m = emir.send(efe, "real");
        byte[] forged = m.clone();
        forged[forged.length - 1] ^= 1;
        assertThrows(SecurityException.class, () -> efe.receive(emir, forged));
        assertEquals("real", efe.receive(emir, m), "the forged copy left the state untouched");
    }

    @Test
    void headerFieldsAreAuthenticated() throws Exception {
        efe.receive(emir, emir.send(efe, "hello"));
        emir.receive(efe, efe.send(emir, "hi"));
        byte[] m = emir.send(efe, "normal message");
        assertEquals(2, m[1]);
        // Layout: version, kind, u16 header length, u16 dh length, 44-byte dh, i64 (pn << 32 | n).
        // The previous-chain-length (pn) does not feed key derivation inside a chain, so only the
        // associated-data binding of the header can catch this modification.
        byte[] tampered = m.clone();
        tampered[2 + 2 + 2 + 44 + 3] ^= 0x01;
        assertThrows(SecurityException.class, () -> efe.receive(emir, tampered));
        assertEquals("normal message", efe.receive(emir, m));
    }

    @Test
    void wrongSenderKeyIsRejected() throws Exception {
        byte[] m = emir.send(efe, "hello");
        byte[] impostorKey = Identity.generate().publicKeyBytes();
        assertThrows(SecurityException.class,
                () -> efe.messenger.decrypt("emir", impostorKey, m), "X3DH binding signature does not verify");
    }

    @Test
    void bundleForADifferentIdentityIsRefused() {
        User mallory = assertDoesNotThrowUser("mallory");
        // The server hands out mallory's (validly self-signed) bundle while emir expects efe's key.
        assertThrows(SecurityException.class,
                () -> emir.messenger.encrypt("efe", efe.identity.publicKeyBytes(), mallory::bundle, bytes("x")));
    }

    @Test
    void forgedSignedPrekeyIsRefused() {
        PrekeyBundle real = efe.bundle();
        PrekeyBundle forged = new PrekeyBundle(real.identityKey(), real.dhIdentity(), real.dhIdentitySignature(),
                real.signedPrekeyId(), com.emirchat.crypto.X25519.encode(com.emirchat.crypto.X25519.generate().getPublic()),
                real.signedPrekeySignature(), -1, new byte[0]);
        assertThrows(SecurityException.class,
                () -> emir.messenger.encrypt("efe", efe.identity.publicKeyBytes(), () -> forged, bytes("x")));
    }

    @Test
    void oneTimePrekeyIsUsedOnlyOnce() throws Exception {
        int before = efe.messenger.oneTimePrekeyCount();
        efe.receive(emir, emir.send(efe, "hi"));
        assertEquals(before - 1, efe.messenger.oneTimePrekeyCount());
        efe.restart();
        assertEquals(before - 1, efe.messenger.oneTimePrekeyCount(), "deletion is persisted");
    }

    @Test
    void forgedFirstMessageDoesNotBurnAOneTimePrekey() throws Exception {
        int before = efe.messenger.oneTimePrekeyCount();
        byte[] m = emir.send(efe, "hi");
        byte[] forged = m.clone();
        forged[forged.length - 1] ^= 1;
        assertThrows(SecurityException.class, () -> efe.receive(emir, forged));
        assertEquals(before, efe.messenger.oneTimePrekeyCount());
        assertEquals("hi", efe.receive(emir, m));
    }

    @Test
    void worksWhenServerRanOutOfOneTimePrekeys() throws Exception {
        efe.serverPrekeys.clear();
        assertEquals("no opk", efe.receive(emir, emir.send(efe, "no opk")));
        assertEquals("reply", emir.receive(efe, efe.send(emir, "reply")));
    }

    @Test
    void simultaneousFirstMessagesConverge() throws Exception {
        byte[] fromEmir = emir.send(efe, "I start");
        byte[] fromEfe = efe.send(emir, "No, I start");
        assertEquals("I start", efe.receive(emir, fromEmir));
        assertEquals("No, I start", emir.receive(efe, fromEfe));
        for (int i = 0; i < 5; i++) {
            assertEquals("x" + i, efe.receive(emir, emir.send(efe, "x" + i)));
            assertEquals("y" + i, emir.receive(efe, efe.send(emir, "y" + i)));
        }
    }

    @Test
    void sessionsSurviveRestart() throws Exception {
        efe.receive(emir, emir.send(efe, "before"));
        emir.receive(efe, efe.send(emir, "before too"));
        emir.restart();
        efe.restart();
        assertEquals("after", efe.receive(emir, emir.send(efe, "after")));
        assertEquals("after too", emir.receive(efe, efe.send(emir, "after too")));
    }

    @Test
    void messagesUsingThePreviousSignedPrekeyStillDecrypt() throws Exception {
        byte[] m = emir.send(efe, "sent before rotation");
        // Force a rotation on efe's side: age the current signed prekey.
        java.lang.reflect.Field f = DeviceKeys.class.getDeclaredField("signedPrekey");
        f.setAccessible(true);
        java.lang.reflect.Field k = Messenger.class.getDeclaredField("keys");
        k.setAccessible(true);
        DeviceKeys keys = (DeviceKeys) k.get(efe.messenger);
        DeviceKeys.SignedPrekey old = (DeviceKeys.SignedPrekey) f.get(keys);
        f.set(keys, new DeviceKeys.SignedPrekey(old.id(), old.pair(), old.signature(), 0));
        assertEquals(true, efe.messenger.rotateSignedPrekeyIfDue());
        assertNotEquals(old.id(), efe.messenger.publicKeys().signedPrekeyId());
        assertEquals("sent before rotation", efe.receive(emir, m));
    }

    @Test
    void ciphertextsNeverRepeatAndKeysRatchet() throws Exception {
        efe.receive(emir, emir.send(efe, "start"));
        emir.receive(efe, efe.send(emir, "ok"));
        byte[] a = emir.send(efe, "same text");
        byte[] b = emir.send(efe, "same text");
        assertFalse(Arrays.equals(a, b));
        efe.receive(emir, a);
        efe.receive(emir, b);
        byte[] beforeReply = Arrays.copyOfRange(emir.send(efe, "k1"), 4, 48);
        efe.receive(emir, emir.send(efe, "k1b"));
        emir.receive(efe, efe.send(emir, "reply ratchets"));
        byte[] afterReply = Arrays.copyOfRange(emir.send(efe, "k2"), 4, 48);
        assertFalse(Arrays.equals(beforeReply, afterReply), "a new DH ratchet key after the peer's reply");
    }

    @Test
    void resetSessionsStartsFresh() throws Exception {
        efe.receive(emir, emir.send(efe, "one"));
        emir.messenger.resetSessions("efe");
        byte[] m = emir.send(efe, "fresh");
        assertEquals(1, m[1], "a new X3DH handshake");
        assertEquals("fresh", efe.receive(emir, m));
    }

    private User assertDoesNotThrowUser(String name) {
        try {
            return new User(name, dir);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
