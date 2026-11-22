package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.api.ApiException;
import com.emirchat.api.Packet;
import com.emirchat.client.ChatClient;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import com.emirchat.trust.KnownPeers;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One account on two computers: linking by approval, messages to both, what we sent on both, removal. */
class MultiDeviceTest {
    private static final String PASSWORD = "correct horse battery";
    @TempDir Path dir;
    private ChatServer server;

    /** One computer: its own folder, the key it connects with, and what its listener heard. */
    private final class Computer implements AutoCloseable {
        final Path home;
        Identity identity;
        ChatClient client;
        final BlockingQueue<String> got = new LinkedBlockingQueue<>();
        final BlockingQueue<String> ours = new LinkedBlockingQueue<>();
        final BlockingQueue<String> problems = new LinkedBlockingQueue<>();
        final BlockingQueue<ChatClient.LinkPrompt> links = new LinkedBlockingQueue<>();
        final BlockingQueue<Boolean> removed = new LinkedBlockingQueue<>();

        Computer(String name) throws Exception {
            home = dir.resolve(name);
            identity = Identity.loadOrCreate(home);
        }

        ServerConnection connect(Identity as) throws Exception {
            return ServerConnection.open("127.0.0.1", server.port(), as,
                    new KnownPeers(home.resolve("known_servers"), (n, fp) -> true));
        }

        Computer start(String user, boolean register) throws Exception {
            client = ChatClient.start(connect(identity), identity, home.resolve("accounts"), user, PASSWORD, register,
                    new ChatClient.Listener() {
                        @Override public void incoming(ChatClient.Incoming m) {
                            got.add(m.from() + (m.groupId() != 0 ? "@group" : "") + ": " + m.text().text());
                        }

                        @Override public void sentElsewhere(String peer, ChatClient.Incoming m) {
                            ours.add((peer == null ? "group" : peer) + ": " + m.text().text());
                        }

                        @Override public void undecryptable(String from, String reason) { problems.add(from + ": " + reason); }

                        @Override public void linkRequest(ChatClient.LinkPrompt r) { links.add(r); }

                        @Override public void deviceRemoved() { removed.add(true); }
                    });
            return this;
        }

        String next(BlockingQueue<String> q) throws InterruptedException {
            String s = q.poll(10, TimeUnit.SECONDS);
            assertNotNull(s, "expected something to arrive");
            return s;
        }

        @Override public void close() {
            if (client != null) client.close();
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

    /** The desktop asks to join; the laptop compares the code and approves. */
    private Computer link(Computer laptop, String name) throws Exception {
        Computer desktop = new Computer(name);
        CompletableFuture<String> shown = new CompletableFuture<>();
        CompletableFuture<ChatClient.Linked> linked = CompletableFuture.supplyAsync(() -> {
            try (ServerConnection c = desktop.connect(desktop.identity)) {
                return ChatClient.link(c, desktop.home.resolve("accounts"), "emir", PASSWORD, null, shown::complete);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        ChatClient.LinkPrompt request = laptop.links.poll(10, TimeUnit.SECONDS);
        assertNotNull(request, "the laptop was asked");
        assertEquals(shown.get(5, TimeUnit.SECONDS), request.code(), "both show the same code");
        laptop.client.approveLink(request, laptop.identity);
        ChatClient.Linked result = linked.get(10, TimeUnit.SECONDS);
        assertEquals(laptop.identity.fingerprint(), result.account().fingerprint(), "the same account key");
        desktop.identity = result.account();
        return desktop.start("emir", false);
    }

    @Test
    void messagesReachEveryComputerAndWhatWeSendShowsOnAll() throws Exception {
        try (Computer laptop = new Computer("emir-laptop").start("emir", true);
             Computer efe = new Computer("efe").start("efe", true)) {
            laptop.client.connection().sendFriendRequest("efe");
            efe.client.connection().respondFriendRequest("emir", true);
            laptop.client.sendText("efe", "önce tek bilgisayar");
            assertEquals("emir: önce tek bilgisayar", efe.next(efe.got));

            try (Computer desktop = link(laptop, "emir-desktop")) {
                assertEquals(1, desktop.client.deviceId());
                efe.client.sendText("emir", "iki bilgisayara birden");
                assertEquals("efe: iki bilgisayara birden", laptop.next(laptop.got));
                assertEquals("efe: iki bilgisayara birden", desktop.next(desktop.got));

                desktop.client.sendText("efe", "masaüstünden selam");
                assertEquals("emir: masaüstünden selam", efe.next(efe.got));
                assertEquals("efe: masaüstünden selam", laptop.next(laptop.ours), "the laptop shows it as ours");

                long g = laptop.client.createGroup("kamp", List.of("efe")).id();
                efe.client.sendGroupText(g, "gruba selam");
                assertEquals("efe@group: gruba selam", laptop.next(laptop.got));
                assertEquals("efe@group: gruba selam", desktop.next(desktop.got));
                desktop.client.sendGroupText(g, "masaüstü grupta");
                assertEquals("emir@group: masaüstü grupta", efe.next(efe.got));
                assertEquals("group: masaüstü grupta", laptop.next(laptop.ours));

                assertTrue(laptop.problems.isEmpty() && desktop.problems.isEmpty() && efe.problems.isEmpty(),
                        laptop.problems + " " + desktop.problems + " " + efe.problems);
                assertEquals(2, laptop.client.devices().size());

                // the laptop removes the desktop: it is cut off, and messages reach the laptop alone
                laptop.client.removeDevice(1);
                assertNotNull(desktop.removed.poll(5, TimeUnit.SECONDS), "the desktop was told");
                efe.client.sendText("emir", "yalnız dizüstüne");
                assertEquals("efe: yalnız dizüstüne", laptop.next(laptop.got));
                assertNull(desktop.got.poll(1, TimeUnit.SECONDS));
            }
            // the removed desktop cannot come back as itself, nor as the laptop
            Computer again = new Computer("emir-desktop");
            Identity account = ChatClient.linkedIdentity(again.home.resolve("accounts"), "emir", PASSWORD);
            assertNotNull(account);
            again.identity = account;
            assertThrows(ApiException.class, () -> again.start("emir", false));
        }
    }
}
