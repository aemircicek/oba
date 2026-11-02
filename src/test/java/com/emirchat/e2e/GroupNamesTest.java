package com.emirchat.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GroupNamesTest {
    @TempDir Path dir;

    @Test
    void membersWithTheKeyReadTheName() throws Exception {
        GroupNames admin = new GroupNames(dir.resolve("admin"));
        GroupNames member = new GroupNames(dir.resolve("member"));
        admin.rotate(7);
        byte[] sealed = admin.seal(7, "MobilCAD ekibi");
        assertFalse(new String(sealed, StandardCharsets.ISO_8859_1).contains("MobilCAD"));
        assertNull(member.open(7, sealed), "no key yet");

        assertTrue(member.accept(7, admin.distribution(7)));
        assertEquals("MobilCAD ekibi", member.open(7, sealed));
    }

    @Test
    void aNameCannotBeMovedToAnotherGroup() throws Exception {
        GroupNames admin = new GroupNames(dir);
        admin.rotate(7);
        admin.rotate(8);
        byte[] sealedFor7 = admin.seal(7, "Gizli proje");
        assertNull(admin.open(8, sealedFor7), "bound to group 7 by the associated data");
    }

    @Test
    void aModifiedNameIsRejected() throws Exception {
        GroupNames admin = new GroupNames(dir);
        admin.rotate(1);
        byte[] sealed = admin.seal(1, "ekip");
        sealed[sealed.length - 1] ^= 1;
        assertNull(admin.open(1, sealed));
    }

    @Test
    void rotationLocksOutFormerMembers() throws Exception {
        GroupNames admin = new GroupNames(dir.resolve("admin"));
        GroupNames former = new GroupNames(dir.resolve("former"));
        admin.rotate(3);
        former.accept(3, admin.distribution(3));
        admin.rotate(3); // someone left: new key, version 2
        byte[] renamed = admin.seal(3, "Yeni ad");
        assertEquals(2, GroupNames.versionOf(renamed));
        assertNull(former.open(3, renamed), "the old key does not open names under the new one");
    }

    @Test
    void oldKeysCannotBeReplayed() throws Exception {
        GroupNames admin = new GroupNames(dir.resolve("admin"));
        GroupNames member = new GroupNames(dir.resolve("member"));
        admin.rotate(5);
        byte[] v1 = admin.distribution(5);
        admin.rotate(5);
        byte[] v2 = admin.distribution(5);
        assertTrue(member.accept(5, v2));
        assertFalse(member.accept(5, v1), "an older version is ignored");
        assertFalse(member.accept(5, v2), "the same version again is ignored");
        assertEquals("son ad", member.open(5, admin.seal(5, "son ad")));
        assertThrows(SecurityException.class, () -> member.accept(6, v2), "a key for another group");
    }

    @Test
    void keysAndDistributionStateSurviveARestart() throws Exception {
        GroupNames admin = new GroupNames(dir);
        admin.rotate(9);
        admin.markDistributed(9, "efe");
        byte[] sealed = admin.seal(9, "kalıcı");
        GroupNames reopened = new GroupNames(dir);
        assertEquals("kalıcı", reopened.open(9, sealed));
        assertEquals(Set.of("ayse"), reopened.needsKey(9, List.of("emir", "efe", "ayse"), "emir"));
        reopened.forget(9);
        assertFalse(new GroupNames(dir).hasKey(9));
    }

    @Test
    void nameLimits() throws Exception {
        GroupNames admin = new GroupNames(dir);
        admin.rotate(1);
        assertThrows(IllegalArgumentException.class, () -> admin.seal(1, ""));
        assertThrows(IllegalArgumentException.class, () -> admin.seal(1, "x".repeat(65)));
        assertThrows(IllegalStateException.class, () -> new GroupNames(dir.resolve("none")).seal(1, "x"));
    }
}
