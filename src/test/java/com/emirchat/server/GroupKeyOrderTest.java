package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.chat.ChatMessage;
import com.emirchat.client.ChatClient;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import com.emirchat.trust.KnownPeers;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A group message that arrives before its sender's key waits for the key instead of being lost. */
class GroupKeyOrderTest {
    private static final String PASSWORD = "correct horse battery";

    @TempDir Path dir;
    private ChatServer server;

    private final class Device implements AutoCloseable {
        final ChatClient client;
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<String> problems = new LinkedBlockingQueue<>();
        final BlockingQueue<String> keyChanges = new LinkedBlockingQueue<>();

        Device(String deviceName, String user, boolean register) throws Exception {
            Path home = dir.resolve(deviceName);
            Identity identity = Identity.loadOrCreate(home);
            ServerConnection c = ServerConnection.open("127.0.0.1", server.port(), identity,
                    new KnownPeers(home.resolve("known_servers"), (n, fp) -> true));
            client = ChatClient.start(c, identity, home.resolve("accounts"), user, PASSWORD, register, new ChatClient.Listener() {
                @Override public void groupMessage(long g, String from, ChatMessage m, long id) { messages.add(from + ": " + m.text()); }

                @Override public void undecryptable(String from, String reason) { problems.add(from + ": " + reason); }

                @Override public void keyChanged(String friend) { keyChanges.add(friend); }
            });
        }

        @Override public void close() {
            client.close();
        }
    }

    @BeforeEach
    void start() throws Exception {
        server = new ChatServer(new ChatServer.Config(dir.resolve("server"), "127.0.0.1", 0, 1_000)).start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void aMessageFromANewDeviceWaitsUntilItsKeyIsTrusted() throws Exception {
        try (Device emir = new Device("emir-laptop", "emir", true)) {
            Device efe = new Device("efe-laptop", "efe", true);
            emir.client.connection().sendFriendRequest("efe");
            efe.client.connection().respondFriendRequest("emir", true);
            long g = emir.client.createGroup("ekip", List.of("efe")).id();
            for (int i = 0; i < 100 && efe.client.groups().stream().noneMatch(x -> x.id() == g); i++) Thread.sleep(25);
            emir.client.sendGroupText(g, "ilk mesaj");
            efe.close();

            try (Device efeNew = new Device("efe-new-laptop", "efe", false)) {
                assertEquals("efe", emir.keyChanges.poll(10, TimeUnit.SECONDS), "emir is warned about the new device");
                efeNew.client.refreshGroups();
                efeNew.client.sendGroupText(g, "yeni bilgisayardan selam");

                // efe's new sender key travels over the pairwise channel, held until emir verifies efe:
                // the group message must wait for it, not be dropped as undecryptable
                assertNull(emir.messages.poll(1500, TimeUnit.MILLISECONDS));
                assertTrue(emir.problems.isEmpty(), emir.problems.toString());

                emir.client.trustNewKey("efe");
                String m = emir.messages.poll(10, TimeUnit.SECONDS);
                assertNotNull(m, "delivered once the key arrived; problems: " + emir.problems);
                assertEquals("efe: yeni bilgisayardan selam", m);
                assertTrue(emir.problems.isEmpty(), emir.problems.toString());
            }
        }
    }
}
