package com.emirchat.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ProtocolException;
import org.junit.jupiter.api.Test;

class CapsuleTest {
    @Test
    void travelsAndIsStoredWhole() throws Exception {
        Capsule c = new Capsule(1_800_000_000_000L, "Mutlu yıllar!\nikinci satır");
        assertEquals(c, Capsule.decode(c.encode()));
        assertEquals(c, Capsule.fromStored(c.toStored()));
        assertTrue(Capsule.isStored(c.toStored()));
        assertFalse(Poll.isStored(c.toStored()), "not mistaken for a poll");
        assertNull(Capsule.fromStored("an ordinary message"));
    }

    @Test
    void opensOnItsDate() {
        Capsule c = new Capsule(1000, "x");
        assertFalse(c.open(999));
        assertTrue(c.open(1000));
    }

    @Test
    void refusesWhatCannotBeACapsule() {
        assertThrows(IllegalArgumentException.class, () -> new Capsule(1000, " "));
        assertThrows(IllegalArgumentException.class, () -> new Capsule(0, "x"));
        assertThrows(IllegalArgumentException.class, () -> new Capsule(1000, "a\u0001b"));
        assertThrows(ProtocolException.class, () -> Capsule.decode(new byte[] {1, 2, 3}));
    }
}
