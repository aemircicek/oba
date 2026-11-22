package com.emirchat.e2e;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.emirchat.crypto.Identity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Ciphertexts and key distributions from anyone: refused (IOException or SecurityException, which the app reports as undecryptable), nothing else. */
class CryptoInputsFuzzTest {
    private static final Identity EMIR = Identity.generate();
    private static final Identity EFE = Identity.generate();

    @FuzzTest(maxDuration = "2m")
    void directMessages(byte[] data) throws Exception {
        Path dir = Files.createTempDirectory("fuzz-e2e");
        try {
            Messenger m = Messenger.open(dir, EMIR);
            try {
                m.decrypt("efe", EFE.publicKeyBytes(), data);
            } catch (IOException | SecurityException ignored) {
                // refused
            }
        } finally {
            delete(dir);
        }
    }

    @FuzzTest(maxDuration = "2m")
    void groupKeys(FuzzedDataProvider in) throws Exception {
        Path dir = Files.createTempDirectory("fuzz-groups");
        try {
            GroupKeys g = new GroupKeys(dir);
            try {
                g.acceptDistribution(7, "efe", in.consumeBytes(600));
            } catch (IOException | SecurityException ignored) {
                // refused
            }
            try {
                g.decrypt(7, "efe", in.consumeRemainingAsBytes());
            } catch (IOException | SecurityException ignored) {
                // refused
            }
        } finally {
            delete(dir);
        }
    }

    private static void delete(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
