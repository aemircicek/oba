package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.client.ChatClient;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import com.emirchat.trust.KnownPeers;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Once friends swapped access keys, the server stores their messages without knowing who sent them. */
class SealedSenderTest {
    @TempDir Path dir;
    private ChatServer server;

    @BeforeEach
    void start() throws Exception {
        server = new ChatServer(new ChatServer.Config(dir.resolve("server"), "127.0.0.1", 0, 1_000)).start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private record Client(ChatClient client, BlockingQueue<String> got, BlockingQueue<String> problems) {}

    private Client start(String user, boolean register) throws Exception {
        Path home = dir.resolve(user);
        Identity id = Identity.loadOrCreate(home);
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        BlockingQueue<String> problems = new LinkedBlockingQueue<>();
        ServerConnection c = ServerConnection.open("127.0.0.1", server.port(), id, new KnownPeers(home.resolve("ks"), (n, fp) -> true));
        ChatClient client = ChatClient.start(c, id, home.resolve("accounts"), user, "correct horse battery", register,
                new ChatClient.Listener() {
                    @Override public void incoming(ChatClient.Incoming m) { got.add(m.from() + ": " + m.text().text()); }

                    @Override public void undecryptable(String from, String reason) { problems.add(from + ": " + reason); }
                });
        return new Client(client, got, problems);
    }

    private static String next(BlockingQueue<String> q) throws InterruptedException {
        String s = q.poll(10, TimeUnit.SECONDS);
        assertNotNull(s, "expected a message");
        return s;
    }

    private long[] newestEnvelope() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("server/server.db"));
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT sender_id, recipient_id, sealed FROM envelopes ORDER BY id DESC LIMIT 1")) {
            assertTrue(rs.next(), "something is queued");
            return new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)};
        }
    }

    @Test
    void sealedMessagesHideTheSenderFromTheServer() throws Exception {
        Client emir = start("emir", true);
        Client efe = start("efe", true);
        try {
            emir.client().connection().sendFriendRequest("efe");
            efe.client().connection().respondFriendRequest("emir", true);
            // the first messages carry each one's access key to the other
            emir.client().sendText("efe", "merhaba");
            assertEquals("emir: merhaba", next(efe.got()));
            efe.client().sendText("emir", "selam");
            assertEquals("efe: selam", next(emir.got()));
            Thread.sleep(300);

            efe.client().close(); // offline: what emir sends now waits on the server, where we can look at it
            emir.client().sendText("efe", "kimden geldiğini sunucu bilmesin");
            Thread.sleep(300);
            long[] row = newestEnvelope();
            assertEquals(1, row[2], "sealed");
            assertEquals(row[1], row[0], "the server put the recipient where the sender would be");

            efe = start("efe", false);
            assertEquals("emir: kimden geldiğini sunucu bilmesin", next(efe.got()), "efe still knows it is from emir");
            assertTrue(efe.problems().isEmpty() && emir.problems().isEmpty(), efe.problems() + " " + emir.problems());
        } finally {
            emir.client().close();
            efe.client().close();
        }
    }

    @Test
    void contentIsPaddedToPowersOfTwo() throws Exception {
        var pad = ChatClient.class.getDeclaredMethod("pad", byte[].class);
        pad.setAccessible(true);
        assertEquals(256, ((byte[]) pad.invoke(null, (Object) new byte[10])).length);
        assertEquals(256, ((byte[]) pad.invoke(null, (Object) new byte[200])).length);
        assertEquals(512, ((byte[]) pad.invoke(null, (Object) new byte[300])).length);
        assertEquals(4096, ((byte[]) pad.invoke(null, (Object) new byte[3000])).length);
    }
}
