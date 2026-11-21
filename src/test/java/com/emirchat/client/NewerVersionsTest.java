package com.emirchat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.crypto.Identity;
import com.emirchat.server.ChatServer;
import com.emirchat.trust.KnownPeers;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What newer versions send must not look like an attack or a broken message here: an unknown
 * action is skipped quietly, an unknown kind of message is reported as "update to see it".
 */
class NewerVersionsTest {
    private static final String PASSWORD = "correct horse battery";

    @TempDir Path dir;

    private static final class Device implements AutoCloseable {
        final ChatClient client;
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();

        Device(Path dir, ChatServer server, String user) throws Exception {
            Path home = dir.resolve(user);
            Identity identity = Identity.loadOrCreate(home);
            ServerConnection c = ServerConnection.open("127.0.0.1", server.port(), identity,
                    new KnownPeers(home.resolve("known_servers"), (n, fp) -> true));
            client = ChatClient.start(c, identity, home.resolve("accounts"), user, PASSWORD, true, new ChatClient.Listener() {
                @Override public void incoming(ChatClient.Incoming m) { events.add("message " + (m.text() == null ? "" : m.text().text())); }

                @Override public void action(ChatClient.Action a) { events.add("action " + a.kind()); }

                @Override public void undecryptable(String from, String reason) { events.add("undecryptable " + reason); }

                @Override public void unsupported(String from, long groupId) { events.add("unsupported " + from); }
            });
        }

        @Override public void close() { client.close(); }
    }

    @Test
    void unknownActionsAreSkippedAndUnknownMessagesAskForAnUpdate() throws Exception {
        try (ChatServer server = new ChatServer(new ChatServer.Config(dir.resolve("server"), "127.0.0.1", 0, 1_000)).start();
             Device emir = new Device(dir, server, "emir"); Device efe = new Device(dir, server, "efe")) {
            emir.client.connection().sendFriendRequest("efe");
            efe.client.connection().respondFriendRequest("emir", true);
            ChatClient.Chat toEfe = ChatClient.Chat.dm("efe");

            emir.client.sendRawAction(toEfe, 200, "something new");
            emir.client.sendRawMessage(toEfe, 200, new byte[] {1, 2, 3});
            emir.client.sendText("efe", "after both");

            String first = efe.events.poll(10, TimeUnit.SECONDS);
            assertNotNull(first);
            assertEquals("unsupported emir", first, "the action left no trace; the message asks for an update");
            assertEquals("message after both", efe.events.poll(10, TimeUnit.SECONDS), "and the conversation goes on");
            assertTrue(efe.events.isEmpty(), "nothing reported as undecryptable: " + efe.events);
        }
    }
}
