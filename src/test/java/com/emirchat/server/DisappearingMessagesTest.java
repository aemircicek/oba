package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.chat.ChatMessage;
import com.emirchat.chat.FileMessage;
import com.emirchat.client.ChatClient;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import com.emirchat.trust.KnownPeers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The disappearing-message timer and per-message expiry travel end-to-end. */
class DisappearingMessagesTest {
    private static final String PASSWORD = "correct horse battery";

    @TempDir Path dir;
    private ChatServer server;

    private final class Device implements AutoCloseable {
        final String user;
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        final BlockingQueue<String> problems = new LinkedBlockingQueue<>();
        ChatClient client;

        Device(String user) throws Exception {
            this.user = user;
            Path home = dir.resolve(user + "-device");
            Identity identity = Identity.loadOrCreate(home);
            ServerConnection c = ServerConnection.open("127.0.0.1", server.port(), identity,
                    new KnownPeers(home.resolve("known_servers"), (n, fp) -> true));
            client = ChatClient.start(c, identity, home.resolve("accounts"), user, PASSWORD, true, new ChatClient.Listener() {
                @Override public void message(String from, ChatMessage m, long id, long expire) {
                    events.add("dm " + from + ": " + m.text() + " /" + expire);
                }

                @Override public void groupMessage(long g, String from, ChatMessage m, long id, long expire) {
                    events.add("group " + from + ": " + m.text() + " /" + expire);
                }

                @Override public void file(String from, FileMessage f, long id, long expire) {
                    events.add("file " + from + ": " + f.name() + " /" + expire);
                }

                @Override public void groupFile(long g, String from, FileMessage f, long id, long expire) {
                    events.add("group-file " + from + ": " + f.name() + " /" + expire);
                }

                @Override public void timerChanged(String from, long groupId, long seconds) {
                    events.add("timer " + from + (groupId == 0 ? "" : " in group") + " = " + seconds);
                }

                @Override public void undecryptable(String from, String reason) { problems.add(reason); }
            });
        }

        String next() throws InterruptedException {
            String e = events.poll(10, TimeUnit.SECONDS);
            assertNotNull(e, user + " expected an event; problems: " + problems);
            return e;
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
    void timerAndExpiringMessagesReachAFriend() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe")) {
            befriend(emir, efe);
            emir.client.setTimer("efe", 3600);
            assertEquals("timer emir = 3600", efe.next());

            emir.client.sendText("efe", "bu mesaj bir saat sonra silinecek", 3600);
            assertEquals("dm emir: bu mesaj bir saat sonra silinecek /3600", efe.next());
            emir.client.sendText("efe", "normal mesaj");
            assertEquals("dm emir: normal mesaj /0", efe.next(), "messages without a timer do not expire");
            emir.client.sendFile("efe", "gizli.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8), 300);
            assertEquals("file emir: gizli.txt /300", efe.next());

            efe.client.setTimer("emir", 0);
            assertEquals("timer efe = 0", emir.next());
            assertTrue(efe.problems.isEmpty() && emir.problems.isEmpty());
        }
    }

    @Test
    void groupTimersAndExpiringGroupMessages() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe"); Device ayse = new Device("ayse")) {
            befriend(emir, efe);
            befriend(emir, ayse);
            long g = emir.client.createGroup("ekip", List.of("efe", "ayse")).id();
            for (Device d : List.of(efe, ayse)) {
                for (int i = 0; i < 100 && d.client.groups().stream().noneMatch(x -> x.id() == g); i++) Thread.sleep(50);
            }
            emir.client.setGroupTimer(g, 300);
            assertEquals("timer emir in group = 300", efe.next());
            assertEquals("timer emir in group = 300", ayse.next());

            emir.client.sendGroupText(g, "5 dakika", 300);
            assertEquals("group emir: 5 dakika /300", efe.next());
            assertEquals("group emir: 5 dakika /300", ayse.next());
            emir.client.sendGroupFile(g, "plan.png", "image/png", new byte[100], 300);
            assertEquals("group-file emir: plan.png /300", efe.next());
        }
    }

    @Test
    void invalidTimersAreRefusedBeforeSending() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe")) {
            befriend(emir, efe);
            assertThrows(IllegalArgumentException.class, () -> emir.client.sendText("efe", "x", -1));
            assertThrows(IllegalArgumentException.class,
                    () -> emir.client.sendText("efe", "x", ChatClient.MAX_TIMER_SECONDS + 1));
            assertThrows(IllegalArgumentException.class, () -> emir.client.setTimer("efe", -5));
        }
    }
}
