package com.emirchat.trust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SafetyNumberTest {
    private static final String EMIR = "e41e2a9a3ac43c7063a447630f758bcfd9de0cc6ea752ea23b87146137dac02a";
    private static final String EFE = "0087200b470be8b05f87a136041e436cc1ea0979e741e96885ad33c619a0d62b";
    private static final String MALLORY = "ffff200b470be8b05f87a136041e436cc1ea0979e741e96885ad33c619a0d62b";

    @Test
    void bothSidesComputeTheSameNumber() {
        SafetyNumber atEmir = SafetyNumber.of("emir", EMIR, "efe", EFE);
        SafetyNumber atEfe = SafetyNumber.of("efe", EFE, "emir", EMIR);
        assertEquals(atEmir.digits(), atEfe.digits());
        assertTrue(atEmir.matches(atEfe.qrPayload()));
    }

    @Test
    void aSubstitutedKeyChangesTheNumber() {
        SafetyNumber real = SafetyNumber.of("emir", EMIR, "efe", EFE);
        SafetyNumber attacked = SafetyNumber.of("emir", EMIR, "efe", MALLORY); // the server swapped efe's key
        assertNotEquals(real.digits(), attacked.digits());
        assertFalse(real.matches(attacked.qrPayload()));
        assertFalse(real.matches(null));
    }

    @Test
    void formattingAndNormalisation() {
        SafetyNumber n = SafetyNumber.of("Emir", EMIR.toUpperCase(), "efe", "0087 200b 470b e8b0 5f87 a136 041e 436c\n"
                + "c1ea 0979 e741 e968 85ad 33c6 19a0 d62b");
        assertEquals(SafetyNumber.of("emir", EMIR, "efe", EFE).digits(), n.digits(), "case and spacing do not matter");
        String digits = n.digits();
        assertEquals(3, digits.split("\n").length);
        assertTrue(digits.replaceAll("\\s", "").matches("\\d{60}"));
    }
}
