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

/** Message ids, replies and actions (reactions, edits, deletes, receipts, typing) travel end-to-end. */
class RichMessagesTest {
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
    void repliesReactionsEditsDeletesReceiptsAndTyping() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe")) {
            befriend(emir, efe);
            ChatClient.Sent question = efe.client.send(Chat.dm("emir"), "Akşam müsait misin?", 0, null);
            ChatClient.Incoming got = emir.nextMessage();
            assertArrayEquals(question.messageId(), got.messageId(), "both sides know the message by the same id");
            assertEquals(0, got.replyTo().length);

            ChatClient.Sent answer = emir.client.send(Chat.dm("efe"), "Evet, 8'de", 0, got.messageId());
            ChatClient.Incoming reply = efe.nextMessage();
            assertArrayEquals(question.messageId(), reply.replyTo(), "the reply points at the question");

            efe.client.sendAction(Chat.dm("emir"), ActionKind.READ, answer.messageId(), "");
            ChatClient.Action read = emir.nextAction();
            assertEquals(ActionKind.READ, read.kind());
            assertArrayEquals(answer.messageId(), read.target());

            efe.client.sendAction(Chat.dm("emir"), ActionKind.REACT, answer.messageId(), "👍");
            ChatClient.Action react = emir.nextAction();
            assertEquals("efe", react.from());
            assertEquals("👍", react.value());

            emir.client.sendAction(Chat.dm("efe"), ActionKind.EDIT, answer.messageId(), "Evet, 9'da");
            assertEquals("Evet, 9'da", efe.nextAction().value());
            emir.client.sendAction(Chat.dm("efe"), ActionKind.DELETE, answer.messageId(), "");
            assertEquals(ActionKind.DELETE, efe.nextAction().kind());

            efe.client.sendAction(Chat.dm("emir"), ActionKind.TYPING, null, "1");
            ChatClient.Action typing = emir.nextAction();
            assertEquals(ActionKind.TYPING, typing.kind());
            assertEquals(0, typing.target().length);
            assertTrue(emir.problems.isEmpty() && efe.problems.isEmpty());
        }
    }

    @Test
    void groupActionsReachEveryMember() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe"); Device ayse = new Device("ayse")) {
            befriend(emir, efe);
            befriend(emir, ayse);
            long g = emir.client.createGroup("ekip", List.of("efe", "ayse")).id();
            for (Device d : List.of(efe, ayse)) {
                for (int i = 0; i < 100 && d.client.groups().stream().noneMatch(x -> x.id() == g); i++) Thread.sleep(50);
            }
            ChatClient.Sent sent = emir.client.send(Chat.group(g), "yarın 10:00", 0, null);
            assertArrayEquals(sent.messageId(), efe.nextMessage().messageId());
            assertArrayEquals(sent.messageId(), ayse.nextMessage().messageId());

            efe.client.sendAction(Chat.group(g), ActionKind.REACT, sent.messageId(), "🙏");
            ChatClient.Action atEmir = emir.nextAction();
            ChatClient.Action atAyse = ayse.nextAction();
            assertEquals(g, atEmir.groupId());
            assertEquals("efe", atAyse.from());
        }
    }

    @Test
    void malformedActionsAreRefusedBeforeSending() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe")) {
            befriend(emir, efe);
            assertThrows(IllegalArgumentException.class,
                    () -> emir.client.sendAction(Chat.dm("efe"), ActionKind.REACT, new byte[5], "👍"));
            assertThrows(IllegalArgumentException.class,
                    () -> emir.client.send(Chat.dm("efe"), "x", 0, new byte[3]));
        }
    }
}
