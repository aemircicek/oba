package com.emirchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ClientShellTest {
    @Test
    void durations() {
        assertEquals(0, ClientShell.parseDuration("off"));
        assertEquals(30, ClientShell.parseDuration("30s"));
        assertEquals(300, ClientShell.parseDuration("5m"));
        assertEquals(3600, ClientShell.parseDuration("1h"));
        assertEquals(86_400, ClientShell.parseDuration("1D"));
        assertEquals(604_800, ClientShell.parseDuration("1w"));
        assertThrows(NumberFormatException.class, () -> ClientShell.parseDuration("5y"));
        assertThrows(NumberFormatException.class, () -> ClientShell.parseDuration("-1h"));
        assertThrows(NumberFormatException.class, () -> ClientShell.parseDuration("5w"), "longer than four weeks");
        assertThrows(RuntimeException.class, () -> ClientShell.parseDuration("99999999999999999w"));
    }
}
