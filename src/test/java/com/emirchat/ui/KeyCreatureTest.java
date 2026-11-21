package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class KeyCreatureTest {
    @Test
    void theSameKeyAlwaysGivesTheSameScorpionAndAnotherKeyAnother() {
        String a = "3f9a 77c1 0b2e 5d48 9e01 aa73 c4d2 1188";
        String b = "c2d1 0f6e 9a33 47b0 12fe 7c55 0a99 e3d4";
        assertEquals(KeyCreature.traits(a), KeyCreature.traits(a));
        assertNotEquals(KeyCreature.traits(a), KeyCreature.traits(b));
        KeyCreature.Traits t = KeyCreature.traits(a);
        assertEquals(0x3f * 360.0 / 256, t.hue(), 1e-9);
        assertEquals(5 + 0xc1 % 3, t.segments(), "segments from the fourth byte");
    }
}
