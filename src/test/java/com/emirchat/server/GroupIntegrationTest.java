package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import com.emirchat.protocol.Wire;
import com.emirchat.trust.KnownPeers;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GroupIntegrationTest {
    private static final String PASSWORD = "correct horse battery";

    @TempDir Path dir;
    private ChatServer server;

    private final class Device implements AutoCloseable {
        final String user;
        final Identity identity;
        final Path home;
        final BlockingQueue<String> groupMessages = new LinkedBlockingQueue<>();
        final BlockingQueue<String> problems = new LinkedBlockingQueue<>();
        final BlockingQueue<Long> removedFrom = new LinkedBlockingQueue<>();
        ChatClient client;

        Device(String user) throws Exception {
            this.user = user;
            this.home = dir.resolve(user + "-device");
            this.identity = Identity.loadOrCreate(home);
        }

        Device start(boolean register) throws Exception {
            KnownPeers servers = new KnownPeers(home.resolve("known_servers"), (n, fp) -> true);
            ServerConnection c = ServerConnection.open("127.0.0.1", server.port(), identity, servers);
            client = ChatClient.start(c, identity, home.resolve("accounts"), user, PASSWORD, register, new ChatClient.Listener() {
                @Override public void groupMessage(long g, String from, ChatMessage m, long id) {
                    groupMessages.add(g + "/" + from + ": " + m.text());
                }

                @Override public void undecryptable(String from, String reason) { problems.add(from + ": " + reason); }

                @Override public void groupRemoved(long g) { removedFrom.add(g); }
            });
            return this;
        }

        String next() throws InterruptedException {
            String m = groupMessages.poll(5, TimeUnit.SECONDS);
            assertNotNull(m, user + " expected a group message; problems: " + problems);
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

    private static void befriend(Device a, Device b) throws Exception {
        a.client.connection().sendFriendRequest(b.user);
        b.client.connection().respondFriendRequest(a.user, true);
    }

    /** Waits until a client's cached view of the group matches the expected size. */
    private static void awaitMembers(Device d, long groupId, int count) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (d.client.groups().stream().anyMatch(g -> g.id() == groupId && g.members().size() == count)) return;
            Thread.sleep(50);
        }
        d.client.refreshGroups();
    }

    @Test
    void membersWhoAreNotFriendsCanStillTalkInAGroup() throws Exception {
        try (Device emir = new Device("emir").start(true); Device efe = new Device("efe").start(true);
             Device ayse = new Device("ayse").start(true)) {
            befriend(emir, efe);
            befriend(emir, ayse); // efe and ayse are not friends
            long g = emir.client.createGroup("MobilCAD ekibi", List.of("efe", "ayse")).id();
            awaitMembers(efe, g, 3);
            awaitMembers(ayse, g, 3);

            emir.client.sendGroupText(g, "Herkese selam!");
            assertEquals(g + "/emir: Herkese selam!", efe.next());
            assertEquals(g + "/emir: Herkese selam!", ayse.next());

            efe.client.sendGroupText(g, "Selam ayşe, tanıştığıma memnun oldum");
            assertEquals(g + "/efe: Selam ayşe, tanıştığıma memnun oldum", ayse.next());
            assertEquals(g + "/efe: Selam ayşe, tanıştığıma memnun oldum", emir.next());

            ayse.client.sendGroupText(g, "Ben de!");
            assertEquals(g + "/ayse: Ben de!", efe.next());
            assertEquals(g + "/ayse: Ben de!", emir.next());

            for (int i = 0; i < 5; i++) emir.client.sendGroupText(g, "m" + i);
            for (int i = 0; i < 5; i++) assertEquals(g + "/emir: m" + i, efe.next());
            assertTrue(emir.problems.isEmpty() && efe.problems.isEmpty() && ayse.problems.isEmpty());
        }
    }

    @Test
    void removedMemberGetsNothingAndKeysRotateEvenIfTheRemovalWasMissedOffline() throws Exception {
        Device efe = new Device("efe").start(true);
        Device emir = new Device("emir").start(true);
        Device ayse = new Device("ayse").start(true);
        befriend(efe, emir);
        befriend(efe, ayse);
        long g = efe.client.createGroup("ekip", List.of("emir", "ayse")).id();
        awaitMembers(emir, g, 3);
        ayse.close(); // ayse stays offline: her queue keeps what is sent to her

        emir.client.sendGroupText(g, "m1 (ayse may read this)");
        assertEquals(g + "/emir: m1 (ayse may read this)", efe.next());
        emir.close(); // emir is offline while ayse is removed

        efe.client.removeGroupMember(g, "ayse");
        efe.close();

        try (Device emirBack = new Device("emir").start(false)) {
            emirBack.client.sendGroupText(g, "m2 (ayse must not read this)");
        }
        List<Long> toAyse = groupKeyIds("ayse", g);
        List<Long> toEfe = groupKeyIds("efe", g);
        assertEquals(1, toAyse.size(), "nothing after her removal is queued for ayse");
        assertEquals(1, toEfe.size());
        assertNotEquals(toAyse.get(0), toEfe.get(0), "emir rotated his sender key before sending m2");

        try (Device efeBack = new Device("efe").start(false)) {
            assertEquals(g + "/emir: m2 (ayse must not read this)", efeBack.next(), "efe got the new key and the message");
        }
        try (Device ayseBack = new Device("ayse").start(false)) {
            // Even the message queued before her removal is dropped: her client only shows messages
            // of groups she is currently in.
            assertNull(ayseBack.groupMessages.poll(1, TimeUnit.SECONDS));
            String problem = ayseBack.problems.poll(5, TimeUnit.SECONDS);
            assertNotNull(problem);
        }
    }

    @Test
    void permissionsAreEnforced() throws Exception {
        try (Device emir = new Device("emir").start(true); Device efe = new Device("efe").start(true);
             Device stranger = new Device("stranger").start(true)) {
            befriend(emir, efe);
            long g = emir.client.createGroup("özel", List.of("efe")).id();
            ServerConnection s = stranger.client.connection();
            assertEquals(ErrorCode.GROUP_NOT_FOUND,
                    assertThrows(ApiException.class, () -> s.call(new Packet.SendGroupEnvelope(g, new byte[] {1}))).code());
            assertEquals(ErrorCode.GROUP_NOT_FOUND,
                    assertThrows(ApiException.class, () -> stranger.client.leaveGroup(g)).code());
            assertEquals(ErrorCode.NOT_GROUP_ADMIN,
                    assertThrows(ApiException.class, () -> efe.client.addGroupMember(g, "emir")).code());
            assertEquals(ErrorCode.NOT_FRIENDS,
                    assertThrows(ApiException.class, () -> emir.client.addGroupMember(g, "stranger")).code(),
                    "you can only add your own friends");
            assertEquals(ErrorCode.NOT_FRIENDS,
                    assertThrows(ApiException.class, () -> emir.client.createGroup("x", List.of("stranger"))).code());
            assertEquals(ErrorCode.INVALID_REQUEST,
                    assertThrows(ApiException.class, () -> emir.client.createGroup("  ", List.of("efe"))).code());
        }
    }

    @Test
    void ownershipPassesOnAndNewMembersJoinTheConversation() throws Exception {
        try (Device emir = new Device("emir").start(true); Device efe = new Device("efe").start(true);
             Device ayse = new Device("ayse").start(true)) {
            befriend(emir, efe);
            befriend(efe, ayse);
            long g = emir.client.createGroup("devir", List.of("efe")).id();
            awaitMembers(efe, g, 2);
            emir.client.sendGroupText(g, "before ayse");
            assertEquals(g + "/emir: before ayse", efe.next());

            emir.client.leaveGroup(g);
            assertEquals(g, emir.removedFrom.poll(5, TimeUnit.SECONDS));
            awaitMembers(efe, g, 1);
            Packet.GroupInfo info = efe.client.refreshGroups().get(0);
            assertEquals("efe", info.owner(), "the longest-standing member took over");

            efe.client.addGroupMember(g, "ayse"); // only possible as the new owner
            awaitMembers(ayse, g, 2);
            efe.client.sendGroupText(g, "welcome ayse");
            assertEquals(g + "/efe: welcome ayse", ayse.next());
        }
    }

    /** Key ids of group messages queued on the server for a user (parsed from the opaque body header). */
    private List<Long> groupKeyIds(String recipient, long groupId) throws Exception {
        List<Long> ids = new ArrayList<>();
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("server/server.db"));
             PreparedStatement ps = db.prepareStatement("SELECT e.body FROM envelopes e JOIN users u ON u.id = e.recipient_id "
                     + "WHERE u.username = ? AND e.group_id = ? ORDER BY e.id")) {
            ps.setString(1, recipient);
            ps.setLong(2, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Wire.Reader header = new Wire.Reader(new Wire.Reader(rs.getBytes(1)).bytes(64));
                    header.u8();
                    header.u8();
                    header.i64(); // group id
                    ids.add(header.i64());
                }
            }
        }
        return ids;
    }
}
