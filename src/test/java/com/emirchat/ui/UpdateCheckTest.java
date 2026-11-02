package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UpdateCheckTest {
    @Test
    void versionsCompareNumerically() {
        assertTrue(UpdateCheck.isNewer("3.10.0", "3.9.2"));
        assertTrue(UpdateCheck.isNewer("4.0", "3.99.99"));
        assertTrue(UpdateCheck.isNewer("3.2.1", "3.2"));
        assertFalse(UpdateCheck.isNewer("3.2.0", "3.2.0"));
        assertFalse(UpdateCheck.isNewer("3.2.0-rc1", "3.2.0"));
        assertFalse(UpdateCheck.isNewer("3.1.9", "3.2.0"));
    }
}