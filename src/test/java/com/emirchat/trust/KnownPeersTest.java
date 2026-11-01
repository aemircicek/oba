package com.emirchat.trust;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnownPeersTest {
    private static final String FP_A = "a".repeat(64);
    private static final String FP_B = "b".repeat(64);

    @Test
    void trustOnFirstUseIsPersisted(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("known_peers");
        new KnownPeers(file, (n, fp) -> true).check("efe", FP_A);

        KnownPeers reloaded = new KnownPeers(file, (n, fp) -> { throw new AssertionError("must not ask again"); });
        assertTrue(reloaded.isKnown("efe"));
        assertDoesNotThrow(() -> reloaded.check("efe", FP_A));
    }

    @Test
    void changedKeyIsRefused(@TempDir Path dir) throws Exception {
        KnownPeers peers = new KnownPeers(dir.resolve("known_peers"), (n, fp) -> true);
        peers.check("efe", FP_A);
        PeerRejectedException e = assertThrows(PeerRejectedException.class, () -> peers.check("efe", FP_B));
        assertTrue(e.getMessage().contains("HAS CHANGED"));
    }

    @Test
    void declinedPeerIsNotSaved(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("known_peers");
        KnownPeers peers = new KnownPeers(file, (n, fp) -> false);
        assertThrows(PeerRejectedException.class, () -> peers.check("efe", FP_A));
        assertFalse(peers.isKnown("efe"));
        assertFalse(Files.exists(file));
    }

    @Test
    void malformedFileIsRejected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("known_peers");
        Files.writeString(file, "efe not-a-fingerprint\n");
        assertThrows(IOException.class, () -> new KnownPeers(file, (n, fp) -> true));
    }
}
