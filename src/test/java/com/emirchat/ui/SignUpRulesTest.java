package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SignUpRulesTest {
    private static boolean ok(String key, String user, String pass, String repeat) {
        return SignUpRules.checks(user, pass, repeat).stream().filter(c -> c.id().equals(key)).findFirst().orElseThrow().ok();
    }

    @Test
    void aGoodFormMeetsEveryRule() {
        assertTrue(SignUpRules.allMet("emir", "correct horse battery", "correct horse battery"));
        assertTrue(SignUpRules.allMet("  Emir.C_22 ", "12345678", "12345678"), "names are trimmed and lowercased, as on the server");
    }

    @Test
    void namesFollowTheServersRule() {
        for (String bad : List.of("ab", "a".repeat(21), "emir çiçek", "emir-c", "ş", "")) {
            assertFalse(ok("name", bad, "", ""), bad);
        }
        for (String good : List.of("abc", "a".repeat(20), "e.m_1", "EMIR")) {
            assertTrue(ok("name", good, "", ""), good);
        }
    }

    @Test
    void passwordsNeedEightCharactersAndMustNotHoldTheName() {
        assertFalse(ok("length", "emir", "1234567", ""));
        assertTrue(ok("length", "emir", "12345678", ""));
        assertFalse(ok("length", "emir", "x".repeat(129), ""));
        assertFalse(ok("apart", "emir", "my-EMIR-password", ""), "whatever the case");
        assertTrue(ok("apart", "emir", "something else", ""));
        assertFalse(SignUpRules.allMet("emir", "emir12345", "emir12345"));
    }

    @Test
    void theRepeatMustMatch() {
        assertFalse(ok("repeat", "emir", "12345678", "1234567"));
        assertFalse(ok("repeat", "emir", "", ""), "two empty fields are not a match");
        assertTrue(ok("repeat", "emir", "12345678", "12345678"));
    }

    @Test
    void nothingIsCalledWrongBeforeItIsTyped() {
        for (SignUpRules.Check c : SignUpRules.checks("", "", "")) assertFalse(c.typed(), c.id());
        assertEquals(List.of(true, true, true, false),
                SignUpRules.checks("emir", "12345678", "").stream().map(SignUpRules.Check::typed).toList());
    }
}
