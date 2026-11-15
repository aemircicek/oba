package com.emirchat.server;

import java.io.IOException;
import java.nio.file.Path;

/** Starts a server again on a port another server of the test just gave up. */
final class Restart {
    private Restart() {}

    /**
     * The port was picked by the system for the first server, from the same range it hands to
     * outgoing connections, so for a moment after closing it something else on the machine can
     * hold that number. Tries again for a few seconds instead of failing the test.
     */
    static ChatServer on(Path dataDir, int port, int iterations) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (true) {
            try {
                return new ChatServer(new ChatServer.Config(dataDir, "127.0.0.1", port, iterations)).start();
            } catch (IOException e) {
                if (System.currentTimeMillis() > deadline) throw e;
                Thread.sleep(50);
            }
        }
    }
}
