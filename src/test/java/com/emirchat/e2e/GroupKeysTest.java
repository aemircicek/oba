package com.emirchat.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GroupKeysTest {
    private static final long GROUP = 42;

    @TempDir Path dir;
    private GroupKeys emir;
    private GroupKeys efe;
    private GroupKeys ayse;

    @BeforeEach
    void setUp() {
        emir = new GroupKeys(dir.resolve("emir"));
        efe = new GroupKeys(dir.resolve("efe"));
        ayse = new GroupKeys(dir.resolve("ayse"));
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String s(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    /** emir hands his sender key to efe and ayse (over the pairwise channel in real life). */
    private void distributeEmir() throws Exception {
        byte[] d = emir.distribution(GROUP);
        efe.acceptDistribution(GROUP, "emir", d);
        ayse.acceptDistribution(GROUP, "emir", d);
    }

    @Test
    void oneCiphertextForEveryMember() throws Exception {
        distributeEmir();
        byte[] m = emir.encrypt(GROUP, b("akşam 8'de toplantı"));
        assertEquals("akşam 8'de toplantı", s(efe.decrypt(GROUP, "emir", m)));
        assertEquals("akşam 8'de toplantı", s(ayse.decrypt(GROUP, "emir", m)));
    }

    @Test
    void outOfOrderAndReplay() throws Exception {
        distributeEmir();
        byte[] a = emir.encrypt(GROUP, b("a"));
        byte[] b2 = emir.encrypt(GROUP, b("b"));
        byte[] c = emir.encrypt(GROUP, b("c"));
        assertEquals("c", s(efe.decrypt(GROUP, "emir", c)));
        assertEquals("a", s(efe.decrypt(GROUP, "emir", a)));
        assertEquals("b", s(efe.decrypt(GROUP, "emir", b2)));
        assertThrows(SecurityException.class, () -> efe.decrypt(GROUP, "emir", a), "replay");
        assertThrows(SecurityException.class, () -> efe.decrypt(GROUP, "emir", c), "replay");
    }

    @Test
    void aMemberCannotForgeAnotherMembersMessages() throws Exception {
        distributeEmir();
        // ayse knows emir's chain key (every member does) and tries to write as emir with her own signing key.
        byte[] ayseDistribution = ayse.distribution(GROUP);
        efe.acceptDistribution(GROUP, "ayse", ayseDistribution);
        byte[] forged = ayse.encrypt(GROUP, b("I am emir, send me the password"));
        assertThrows(SecurityException.class, () -> efe.decrypt(GROUP, "emir", forged),
                "the message is attributed to its real signer only");
        assertEquals("I am emir, send me the password", s(efe.decrypt(GROUP, "ayse", forged)));
    }

    @Test
    void tamperingIsDetectedWithoutAdvancingTheChain() throws Exception {
        distributeEmir();
        byte[] m = emir.encrypt(GROUP, b("real"));
        byte[] tampered = m.clone();
        tampered[30] ^= 1;
        assertThrows(SecurityException.class, () -> efe.decrypt(GROUP, "emir", tampered));
        assertEquals("real", s(efe.decrypt(GROUP, "emir", m)));
    }

    @Test
    void removedMemberCannotReadAfterRotation() throws Exception {
        distributeEmir();
        assertEquals("before", s(ayse.decrypt(GROUP, "emir", emir.encrypt(GROUP, b("before")))));

        // ayse is removed: emir rotates and only efe receives the new key.
        emir.rotate(GROUP);
        efe.acceptDistribution(GROUP, "emir", emir.distribution(GROUP));
        byte[] after = emir.encrypt(GROUP, b("after ayse left"));
        assertEquals("after ayse left", s(efe.decrypt(GROUP, "emir", after)));
        SecurityException e = assertThrows(SecurityException.class, () -> ayse.decrypt(GROUP, "emir", after));
        assertTrue(e.getMessage().contains("No sender key"), e.getMessage());
    }

    @Test
    void newMemberCannotReadOlderMessages() throws Exception {
        distributeEmir();
        byte[] old = emir.encrypt(GROUP, b("before you joined"));
        GroupKeys newcomer = new GroupKeys(dir.resolve("newcomer"));
        newcomer.acceptDistribution(GROUP, "emir", emir.distribution(GROUP)); // current position of the chain
        assertThrows(SecurityException.class, () -> newcomer.decrypt(GROUP, "emir", old));
        assertEquals("welcome", s(newcomer.decrypt(GROUP, "emir", emir.encrypt(GROUP, b("welcome")))));
    }

    @Test
    void redistributingAnOlderChainStateCannotEnableReplays() throws Exception {
        byte[] early = emir.distribution(GROUP);
        efe.acceptDistribution(GROUP, "emir", early);
        byte[] m = emir.encrypt(GROUP, b("read once"));
        assertEquals("read once", s(efe.decrypt(GROUP, "emir", m)));
        efe.acceptDistribution(GROUP, "emir", early); // an attacker replays the old distribution message
        assertThrows(SecurityException.class, () -> efe.decrypt(GROUP, "emir", m), "the chain was not rewound");
    }

    @Test
    void messagesAreBoundToTheirGroup() throws Exception {
        distributeEmir();
        byte[] m = emir.encrypt(GROUP, b("for group 42"));
        assertThrows(SecurityException.class, () -> efe.decrypt(43, "emir", m));
        assertThrows(SecurityException.class, () -> efe.acceptDistribution(43, "emir", emir.distribution(GROUP)));
    }

    @Test
    void stateSurvivesRestartAndDistributionIsTracked() throws Exception {
        distributeEmir();
        efe.decrypt(GROUP, "emir", emir.encrypt(GROUP, b("1")));
        emir.markDistributed(GROUP, "efe");
        assertEquals(java.util.Set.of("ayse"), emir.needsKey(GROUP, List.of("emir", "efe", "ayse"), "emir"));

        GroupKeys emir2 = new GroupKeys(dir.resolve("emir"));
        GroupKeys efe2 = new GroupKeys(dir.resolve("efe"));
        assertEquals("2", s(efe2.decrypt(GROUP, "emir", emir2.encrypt(GROUP, b("2")))));
        assertEquals(java.util.Set.of("ayse"), emir2.needsKey(GROUP, List.of("efe", "ayse"), "emir"));

        emir2.rotate(GROUP);
        assertEquals(java.util.Set.of("efe", "ayse"), emir2.needsKey(GROUP, List.of("efe", "ayse"), "emir"),
                "a rotated key has to be handed out again");
        assertNotEquals(0, emir2.distribution(GROUP).length);
    }
}
