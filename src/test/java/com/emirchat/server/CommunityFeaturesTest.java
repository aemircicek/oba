package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.client.ChatClient;
import com.emirchat.client.ChatClient.ActionKind;
import com.emirchat.client.ChatClient.Chat;
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

/** 3.4: polls, votes, pins, topics and thread replies travel end-to-end, in DMs and groups. */
class CommunityFeaturesTest {
    private static final String PASSWORD = "correct horse battery";

    @TempDir Path dir;
    private ChatServer server;

    private final class Device implements AutoCloseable {
        final String user;
        final BlockingQueue<ChatClient.Incoming> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<ChatClient.Action> actions = new LinkedBlockingQueue<>();
        final BlockingQueue<String> problems = new LinkedBlockingQueue<>();
        final ChatClient client;

        Device(String user) throws Exception {
            this.user = user;
            Path home = dir.resolve(user + "-device");
            Identity identity = Identity.loadOrCreate(home);
            ServerConnection c = ServerConnection.open("127.0.0.1", server.port(), identity,
                    new KnownPeers(home.resolve("known_servers"), (n, fp) -> true));
            client = ChatClient.start(c, identity, home.resolve("accounts"), user, PASSWORD, true, new ChatClient.Listener() {
                @Override public void incoming(ChatClient.Incoming m) { messages.add(m); }

                @Override public void action(ChatClient.Action a) { actions.add(a); }

                @Override public void undecryptable(String from, String reason) { problems.add(reason); }
            });
        }

        ChatClient.Incoming nextMessage() throws InterruptedException {
            ChatClient.Incoming m = messages.poll(10, TimeUnit.SECONDS);
            assertNotNull(m, user + " expected a message; problems: " + problems);
            return m;
        }

        ChatClient.Action nextAction() throws InterruptedException {
            ChatClient.Action a = actions.poll(10, TimeUnit.SECONDS);
            assertNotNull(a, user + " expected an action; problems: " + problems);
            return a;
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

    private static void befriend(Device a, Device b) throws Exception {
        a.client.connection().sendFriendRequest(b.user);
        b.client.connection().respondFriendRequest(a.user, true);
    }

    @Test
    void pollsVotesPinsTopicsAndThreads() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe")) {
            befriend(emir, efe);
            com.emirchat.chat.Poll poll = new com.emirchat.chat.Poll("Toplantı ne zaman?", List.of("Salı", "Perşembe", "Cuma"));
            ChatClient.Sent sent = emir.client.sendPoll(Chat.dm("efe"), poll, 0, ChatClient.newMessageId());
            ChatClient.Incoming got = efe.nextMessage();
            assertEquals(poll, got.poll(), "the poll arrives whole");
            assertArrayEquals(sent.messageId(), got.messageId());

            efe.client.sendAction(Chat.dm("emir"), ActionKind.VOTE, got.messageId(), "1");
            ChatClient.Action vote = emir.nextAction();
            assertEquals(ActionKind.VOTE, vote.kind());
            assertEquals("1", vote.value());

            emir.client.sendAction(Chat.dm("efe"), ActionKind.PIN, sent.messageId(), "1");
            assertEquals(ActionKind.PIN, efe.nextAction().kind());
            emir.client.sendAction(Chat.dm("efe"), ActionKind.TOPIC, null, "MobilCAD planlaması");
            ChatClient.Action topic = efe.nextAction();
            assertEquals(ActionKind.TOPIC, topic.kind());
            assertEquals(0, topic.target().length);
            assertEquals("MobilCAD planlaması", topic.value());

            ChatClient.Sent root = emir.client.send(Chat.dm("efe"), "Arayüz taslağı hazır", 0, null);
            efe.nextMessage();
            efe.client.sendThreadReply(Chat.dm("emir"), root.messageId(), "Bakıyorum", 0, ChatClient.newMessageId());
            ChatClient.Incoming reply = emir.nextMessage();
            assertTrue(reply.thread(), "marked as a thread reply");
            assertArrayEquals(root.messageId(), reply.replyTo());
            assertEquals("Bakıyorum", reply.text().text());
            assertThrows(IllegalArgumentException.class,
                    () -> efe.client.sendThreadReply(Chat.dm("emir"), null, "x", 0, ChatClient.newMessageId()));
        }
    }

    @Test
    void groupPollsAndThreads() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe")) {
            befriend(emir, efe);
            long g = emir.client.createGroup("ekip", List.of("efe")).id();
            for (int i = 0; i < 50 && efe.client.refreshGroups().stream().noneMatch(x -> x.id() == g); i++) Thread.sleep(100);
            ChatClient.Sent p = emir.client.sendPoll(Chat.group(g), new com.emirchat.chat.Poll("Pizza mı döner mi?", List.of("Pizza", "Döner")), 0,
                    ChatClient.newMessageId());
            ChatClient.Incoming got = efe.nextMessage();
            assertEquals(g, got.groupId());
            assertEquals(List.of("Pizza", "Döner"), got.poll().options());
            efe.client.sendThreadReply(Chat.group(g), p.messageId(), "Döner tabii", 0, ChatClient.newMessageId());
            ChatClient.Incoming reply = emir.nextMessage();
            assertTrue(reply.thread());
            assertEquals(g, reply.groupId());
        }
    }

    @Test
    void pollLimits() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new com.emirchat.chat.Poll("?", List.of("tek")));
        assertThrows(IllegalArgumentException.class, () -> new com.emirchat.chat.Poll(" ", List.of("a", "b")));
        assertThrows(IllegalArgumentException.class, () -> new com.emirchat.chat.Poll("q", java.util.Collections.nCopies(11, "a")));
        assertThrows(IllegalArgumentException.class, () -> new com.emirchat.chat.Poll("q", List.of("a\u0001b", "c")));
        com.emirchat.chat.Poll poll = new com.emirchat.chat.Poll("Soru?", List.of("Evet", "Hayır"));
        assertEquals(poll, com.emirchat.chat.Poll.decode(poll.encode()));
        assertEquals(poll, com.emirchat.chat.Poll.fromStored(poll.toStored()));
        assertTrue(com.emirchat.chat.Poll.isStored(poll.toStored()));
        assertEquals(null, com.emirchat.chat.Poll.fromStored("Soru?"));
        String forged = com.emirchat.chat.TerminalSanitizer.sanitizeMultiline(poll.toStored());
        assertTrue(!com.emirchat.chat.Poll.isStored(forged), "a text message can never turn into a poll");
    }
}
