package com.emirchat.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.chat.Capsule;
import com.emirchat.crypto.Identity;
import com.emirchat.server.ChatServer;
import com.emirchat.trust.KnownPeers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 3.6: amber messages, nudges, and the bytes the server carried for "watch it decrypt". */
class CampFeaturesTest {
    private static final String PASSWORD = "correct horse battery";

    @TempDir Path dir;

    private static final class Device implements AutoCloseable {
        final ChatClient client;
        final BlockingQueue<ChatClient.Incoming> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<ChatClient.Action> actions = new LinkedBlockingQueue<>();

        Device(Path dir, ChatServer server, String user) throws Exception {
            Path home = dir.resolve(user);
            Identity identity = Identity.loadOrCreate(home);
            ServerConnection c = ServerConnection.open("127.0.0.1", server.port(), identity,
                    new KnownPeers(home.resolve("known_servers"), (n, fp) -> true));
            client = ChatClient.start(c, identity, home.resolve("accounts"), user, PASSWORD, true, new ChatClient.Listener() {
                @Override public void incoming(ChatClient.Incoming m) { messages.add(m); }

                @Override public void action(ChatClient.Action a) { actions.add(a); }
            });
        }

        @Override public void close() { client.close(); }
    }

    @Test
    void anAmberMessageANudgeAndWhatTheServerCarried() throws Exception {
        try (ChatServer server = new ChatServer(new ChatServer.Config(dir.resolve("server"), "127.0.0.1", 0, 1_000)).start();
             Device emir = new Device(dir, server, "emir"); Device efe = new Device(dir, server, "efe")) {
            emir.client.connection().sendFriendRequest("efe");
            efe.client.connection().respondFriendRequest("emir", true);
            ChatClient.Chat toEfe = ChatClient.Chat.dm("efe");

            Capsule capsule = new Capsule(System.currentTimeMillis() + 86_400_000L, "Doğum günün kutlu olsun!");
            ChatClient.Sent sent = emir.client.sendCapsule(toEfe, capsule, 0, ChatClient.newMessageId());
            ChatClient.Incoming got = efe.messages.poll(10, TimeUnit.SECONDS);
            assertNotNull(got);
            assertEquals(capsule, got.capsule(), "the capsule arrives whole");
            assertNull(got.text(), "not as a plain text");

            // both ends remember what the server carried, and it is the same ciphertext
            ChatClient.Sealed mine = emir.client.sealed(sent.messageId());
            ChatClient.Sealed theirs = efe.client.sealed(got.messageId());
            assertNotNull(mine);
            assertNotNull(theirs);
            assertArrayEquals(mine.ciphertext(), theirs.ciphertext());
            assertFalse(mine.group());
            String carried = new String(theirs.ciphertext(), StandardCharsets.ISO_8859_1);
            assertFalse(carried.contains("Doğum") || carried.contains("kutlu"), "the server never saw the words");

            emir.client.sendAction(toEfe, ChatClient.ActionKind.NUDGE, null, "");
            ChatClient.Action nudge = efe.actions.poll(10, TimeUnit.SECONDS);
            assertNotNull(nudge);
            assertEquals(ChatClient.ActionKind.NUDGE, nudge.kind());
            assertEquals("emir", nudge.from());

            // nudges never go to a whole group
            long g = emir.client.createGroup("kamp", List.of("efe")).id();
            for (int i = 0; i < 100 && efe.client.refreshGroups().stream().noneMatch(x -> x.id() == g); i++) Thread.sleep(50);
            emir.client.sendAction(ChatClient.Chat.group(g), ChatClient.ActionKind.NUDGE, null, "");
            emir.client.sendGroupText(g, "after");
            ChatClient.Incoming after = efe.messages.poll(10, TimeUnit.SECONDS);
            assertNotNull(after);
            assertTrue(efe.actions.stream().noneMatch(a -> a.kind() == ChatClient.ActionKind.NUDGE), "a group nudge is dropped");
            assertTrue(efe.client.sealed(after.messageId()).group(), "group messages are marked as such");
        }
    }
}
