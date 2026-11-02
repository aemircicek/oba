package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
import com.emirchat.chat.ChatMessage;
import com.emirchat.client.ChatClient;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import com.emirchat.trust.KeyChangedException;
import com.emirchat.trust.KnownPeers;
import java.nio.charset.StandardCharsets;
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

/** End-to-end encrypted messaging through a real server that is treated as untrusted. */
class MessagingIntegrationTest {
    private static final String PASSWORD = "correct horse battery";

    @TempDir Path dir;
    private ChatServer server;

    /** One device of one user, with everything its listener saw. */
    private final class Device implements AutoCloseable {
        final Identity identity;
        final Path home;
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<String> problems = new LinkedBlockingQueue<>();
        final BlockingQueue<Long> delivered = new LinkedBlockingQueue<>();
        final BlockingQueue<String> keyChanges = new LinkedBlockingQueue<>();
        ChatClient client;

        Device(String deviceName) throws Exception {
            this.home = dir.resolve(deviceName);
            this.identity = Identity.loadOrCreate(home);
        }

        Device start(String user, boolean register) throws Exception {
            KnownPeers servers = new KnownPeers(home.resolve("known_servers"), (n, fp) -> true);
            ServerConnection c = ServerConnection.open("127.0.0.1", server.port(), identity, servers);
            client = ChatClient.start(c, identity, home.resolve("accounts"), user, PASSWORD, register, new ChatClient.Listener() {
                @Override public void message(String from, ChatMessage m, long id) { messages.add(from + ": " + m.text()); }

                @Override public void undecryptable(String from, String reason) { problems.add(from + ": " + reason); }

                @Override public void delivered(long id) { delivered.add(id); }

                @Override public void keyChanged(String friend) { keyChanges.add(friend); }
            });
            return this;
        }

        String nextMessage() throws InterruptedException {
            String m = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(m, "expected a message");
            return m;
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

    private void befriend(Device a, Device b) throws Exception {
        a.client.connection().sendFriendRequest(b.client.username());
        b.client.connection().respondFriendRequest(a.client.username(), true);
    }

    @Test
    void liveMessagesBothWaysWithDeliveryReceipts() throws Exception {
        try (Device emir = new Device("emir-laptop").start("emir", true);
             Device efe = new Device("efe-laptop").start("efe", true)) {
            befriend(emir, efe);
            long id = emir.client.sendText("efe", "Selam Efe 👋").id();
            assertEquals("emir: Selam Efe 👋", efe.nextMessage());
            assertEquals(id, emir.delivered.poll(5, TimeUnit.SECONDS), "efe's device acknowledged it");
            efe.client.sendText("emir", "Selam! Akşam MobilCAD?");
            assertEquals("efe: Selam! Akşam MobilCAD?", emir.nextMessage());
            for (int i = 0; i < 10; i++) {
                emir.client.sendText("efe", "m" + i);
                assertEquals("emir: m" + i, efe.nextMessage());
            }
            assertTrue(emir.problems.isEmpty() && efe.problems.isEmpty());
        }
    }

    @Test
    void offlineMessagesAreQueuedAndDeliveredInOrder() throws Exception {
        try (Device emir = new Device("emir-laptop").start("emir", true)) {
            Device efe = new Device("efe-laptop").start("efe", true);
            befriend(emir, efe);
            efe.close(); // efe goes offline

            for (int i = 1; i <= 3; i++) emir.client.sendText("efe", "offline " + i);
            assertEquals(3, queued(), "waiting on the server");

            try (Device efeBack = new Device("efe-laptop").start("efe", false)) {
                for (int i = 1; i <= 3; i++) assertEquals("emir: offline " + i, efeBack.nextMessage());
                Thread.sleep(300);
                assertEquals(0, queued(), "acknowledged and deleted from the server");
                assertTrue(efeBack.problems.isEmpty(), efeBack.problems.toString());
            }
        }
    }

    @Test
    void aMessageDeliveredAgainAfterALostAcknowledgementIsNotAnError() throws Exception {
        try (Device emir = new Device("emir-laptop").start("emir", true)) {
            Device efe = new Device("efe-laptop").start("efe", true);
            befriend(emir, efe);
            efe.close();
            emir.client.sendText("efe", "bir kez");
            Object[] row;
            String url = "jdbc:sqlite:" + dir.resolve("server/server.db");
            try (Connection db = DriverManager.getConnection(url); Statement s = db.createStatement();
                 ResultSet rs = s.executeQuery("SELECT id, recipient_id, sender_id, sent_at, body, group_id FROM envelopes")) {
                assertTrue(rs.next());
                row = new Object[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getBytes(5), rs.getLong(6)};
            }
            try (Device efeBack = new Device("efe-laptop").start("efe", false)) {
                assertEquals("emir: bir kez", efeBack.nextMessage());
                Thread.sleep(300);
                assertEquals(0, queued());
            }
            // The app closed before the server got the acknowledgement: the server still has it.
            try (Connection db = DriverManager.getConnection(url);
                 java.sql.PreparedStatement ps = db.prepareStatement(
                         "INSERT INTO envelopes (id, recipient_id, sender_id, sent_at, body, group_id) VALUES (?, ?, ?, ?, ?, ?)")) {
                for (int i = 0; i < row.length; i++) ps.setObject(i + 1, row[i]);
                ps.executeUpdate();
            }
            try (Device efeAgain = new Device("efe-laptop").start("efe", false)) {
                for (int i = 0; i < 40 && queued() > 0; i++) Thread.sleep(50);
                assertEquals(0, queued(), "acknowledged again, so the server stops re-sending it");
                assertNull(efeAgain.messages.poll(300, TimeUnit.MILLISECONDS), "not shown twice");
                assertTrue(efeAgain.problems.isEmpty(), "no 'could not be decrypted' warning: " + efeAgain.problems);
            }
        }
    }

    @Test
    void theServerOnlyStoresCiphertext() throws Exception {
        try (Device emir = new Device("emir-laptop").start("emir", true)) {
            Device efe = new Device("efe-laptop").start("efe", true);
            befriend(emir, efe);
            efe.close();
            String secret = "the password for the wifi is hunter2";
            emir.client.sendText("efe", secret);
            try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("server/server.db"));
                 Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT body FROM envelopes")) {
                assertTrue(rs.next());
                String stored = new String(rs.getBytes(1), StandardCharsets.ISO_8859_1);
                assertFalse(stored.contains("hunter2"), "no plaintext on the server");
                assertFalse(stored.contains("wifi"));
            }
        }
    }

    @Test
    void aTamperingServerIsCaught() throws Exception {
        try (Device emir = new Device("emir-laptop").start("emir", true)) {
            Device efe = new Device("efe-laptop").start("efe", true);
            befriend(emir, efe);
            efe.close();
            emir.client.sendText("efe", "transfer 10 TL");
            try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("server/server.db"));
                 Statement s = db.createStatement()) {
                // A malicious server operator flips the last byte (inside the GCM tag) of the stored message.
                s.executeUpdate("UPDATE envelopes SET body = substr(body, 1, length(body) - 1) || "
                        + "CAST(char((unicode(substr(body, -1)) + 1) % 256) AS BLOB)");
            }
            try (Device efeBack = new Device("efe-laptop").start("efe", false)) {
                String problem = efeBack.problems.poll(5, TimeUnit.SECONDS);
                assertNotNull(problem, "the forged message is rejected, not shown");
                assertTrue(efeBack.messages.isEmpty());
            }
        }
    }

    @Test
    void onlyFriendsCanMessageOrFetchKeys() throws Exception {
        try (Device emir = new Device("emir-laptop").start("emir", true);
             Device stranger = new Device("stranger").start("stranger", true)) {
            ApiException e = assertThrows(ApiException.class, () -> stranger.client.connection().fetchBundle("emir"));
            assertEquals(ErrorCode.NOT_FRIENDS, e.code());
            assertEquals(ErrorCode.NOT_FRIENDS, assertThrows(ApiException.class,
                    () -> stranger.client.connection().sendEnvelope("emir", new byte[] {1, 2, 3})).code());
            assertEquals(ErrorCode.NOT_FRIENDS, assertThrows(ApiException.class,
                    () -> stranger.client.sendText("emir", "hi")).code());
            assertNull(emir.messages.poll(300, TimeUnit.MILLISECONDS), "nothing reached emir");
        }
    }

    @Test
    void keysWithForgedSignaturesAreRejectedByTheServer() throws Exception {
        try (Device emir = new Device("emir-laptop").start("emir", true)) {
            byte[] junk44 = com.emirchat.crypto.X25519.encode(com.emirchat.crypto.X25519.generate().getPublic());
            ApiException e = assertThrows(ApiException.class, () -> emir.client.connection().uploadKeys(
                    new Packet.UploadKeys(junk44, new byte[64], 1, junk44, new byte[64])));
            assertEquals(ErrorCode.INVALID_REQUEST, e.code());
        }
    }

    @Test
    void aFriendsNewDeviceMustBeVerifiedBeforeMessagesFlow() throws Exception {
        try (Device emir = new Device("emir-laptop").start("emir", true)) {
            Device efe = new Device("efe-laptop").start("efe", true);
            befriend(emir, efe);
            emir.client.sendText("efe", "hi");
            assertEquals("emir: hi", efe.nextMessage());
            efe.close();

            try (Device efePhone = new Device("efe-new-computer").start("efe", false)) {
                assertEquals("efe", emir.keyChanges.poll(5, TimeUnit.SECONDS), "emir is warned immediately");
                assertThrows(KeyChangedException.class, () -> emir.client.sendText("efe", "blocked"),
                        "nothing is encrypted to an unverified key");

                // efe writes from the new computer; emir must not lose it while unverified.
                efePhone.client.sendText("emir", "new laptop, same efe");
                assertNull(emir.messages.poll(500, TimeUnit.MILLISECONDS), "held back until verified");

                String before = emir.client.pinnedFingerprint("efe");
                emir.client.trustNewKey("efe"); // after comparing fingerprints out-of-band
                assertFalse(before.equals(emir.client.pinnedFingerprint("efe")));
                assertEquals("efe: new laptop, same efe", emir.nextMessage(), "delivered after verification");

                emir.client.sendText("efe", "verified, welcome back");
                assertEquals("emir: verified, welcome back", efePhone.nextMessage());
            }
        }
    }

    @Test
    void oneTimePrekeysAreReplenished() throws Exception {
        try (Device emir = new Device("emir-laptop").start("emir", true)) {
            int initial = emir.client.connection().keyStatus().oneTimePrekeys();
            assertEquals(50, initial);
            for (int i = 0; i < 9; i++) {
                try (Device friend = new Device("f" + i).start("friend" + i, true)) {
                    befriend(friend, emir);
                    friend.client.sendText("emir", "hello " + i); // each first contact consumes one prekey
                }
            }
            assertEquals(initial - 9, emir.client.connection().keyStatus().oneTimePrekeys());
        }
    }

    private int queued() throws Exception {
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("server/server.db"));
             Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM envelopes")) {
            return rs.getInt(1);
        }
    }
}
