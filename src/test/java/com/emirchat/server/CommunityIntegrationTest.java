package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
import com.emirchat.chat.ChatMessage;
import com.emirchat.client.ChatClient;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import com.emirchat.trust.KnownPeers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Communities with channels, roles and invite codes, through a real server. */
class CommunityIntegrationTest {
    private static final String PASSWORD = "correct horse battery";

    @TempDir Path dir;
    private ChatServer server;

    private final class Device implements AutoCloseable {
        final String user;
        final ChatClient client;
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<Long> removed = new LinkedBlockingQueue<>();

        Device(String user) throws Exception {
            this.user = user;
            Path home = dir.resolve(user + "-device");
            Identity identity = Identity.loadOrCreate(home);
            ServerConnection c = ServerConnection.open("127.0.0.1", server.port(), identity,
                    new KnownPeers(home.resolve("known_servers"), (n, fp) -> true));
            client = ChatClient.start(c, identity, home.resolve("accounts"), user, PASSWORD, true, new ChatClient.Listener() {
                @Override public void groupMessage(long g, String from, ChatMessage m, long id) {
                    messages.add(g + "/" + from + ": " + m.text());
                }

                @Override public void groupRemoved(long g) { removed.add(g); }
            });
        }

        Packet.GroupInfo await(long id, Predicate<Packet.GroupInfo> condition) throws Exception {
            for (int i = 0; i < 200; i++) {
                Packet.GroupInfo g = client.groups().stream().filter(x -> x.id() == id).findFirst().orElse(null);
                if (g != null && condition.test(g)) return g;
                Thread.sleep(25);
            }
            throw new AssertionError(user + " never saw the expected state of " + id + ": " + client.groups());
        }

        String nextMessage() throws InterruptedException {
            String m = messages.poll(10, TimeUnit.SECONDS);
            assertNotNull(m, user + " expected a message");
            return m;
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
    void strangersJoinWithAnInviteAndTalkInEveryChannel() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe")) {
            Packet.GroupInfo community = emir.client.createCommunity("MobilCAD Topluluğu");
            assertTrue(community.isCommunity());
            Packet.GroupInfo news = emir.client.createChannel(community.id(), "duyurular");
            assertEquals(community.id(), news.parentId());
            String code = emir.client.createInvite(community.id(), 0, 24);
            assertEquals(31, code.length(), "26 symbols in groups of five: " + code);

            // efe is not emir's friend; the code is typed in lower case with spaces
            Packet.GroupInfo joined = efe.client.joinCommunity(code.toLowerCase().replace('-', ' '));
            assertEquals(community.id(), joined.id());
            efe.await(community.id(), g -> g.name().equals("MobilCAD Topluluğu"));
            efe.await(news.id(), g -> g.name().equals("duyurular") && g.hasMember("efe"));
            emir.await(news.id(), g -> g.members().size() == 2);

            efe.client.sendGroupText(news.id(), "Merhaba duyurular kanalı");
            assertEquals(news.id() + "/efe: Merhaba duyurular kanalı", emir.nextMessage());
            emir.client.sendGroupText(community.id(), "Genel kanala hoş geldin");
            assertEquals(community.id() + "/emir: Genel kanala hoş geldin", efe.nextMessage());
            assertEquals(List.of(community.id(), news.id()),
                    efe.client.channels(community.id()).stream().map(Packet.GroupInfo::id).toList());
        }
    }

    @Test
    void inviteCodesHaveLimitsAndCanBeRevoked() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe"); Device ayse = new Device("ayse");
             Device can = new Device("can")) {
            long c = emir.client.createCommunity("Kulüp").id();
            String once = emir.client.createInvite(c, 1, 1);
            efe.client.joinCommunity(once);
            ApiException used = assertThrows(ApiException.class, () -> ayse.client.joinCommunity(once));
            assertEquals(ErrorCode.INVALID_REQUEST, used.code(), "single-use code already used");

            String code = emir.client.createInvite(c, 0, 24);
            emir.client.revokeInvites(c);
            assertThrows(ApiException.class, () -> ayse.client.joinCommunity(code), "revoked");
            assertThrows(ApiException.class, () -> can.client.joinCommunity("AAAAA-BBBBB-CCCCC-DDDDD-EEEEE-F"));
            assertThrows(ApiException.class, () -> emir.client.createInvite(c, 0, 24 * 31), "at most 30 days");
            assertFalse(ayse.client.groups().stream().anyMatch(g -> g.id() == c));
        }
    }

    @Test
    void rolesDecideWhoManagesTheCommunity() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe"); Device ayse = new Device("ayse")) {
            long c = emir.client.createCommunity("Ekip").id();
            String code = emir.client.createInvite(c, 0, 24);
            efe.client.joinCommunity(code);
            ayse.client.joinCommunity(code);

            assertEquals(ErrorCode.NOT_GROUP_ADMIN, assertThrows(ApiException.class,
                    () -> efe.client.createChannel(c, "benim kanalım")).code(), "members cannot add channels");
            assertEquals(ErrorCode.NOT_GROUP_ADMIN, assertThrows(ApiException.class,
                    () -> efe.client.createInvite(c, 0, 1)).code());
            assertEquals(ErrorCode.NOT_GROUP_ADMIN, assertThrows(ApiException.class,
                    () -> efe.client.setAdmin(c, "ayse", true)).code(), "only the owner appoints admins");

            emir.client.setAdmin(c, "efe", true);
            efe.await(c, g -> g.admins().contains("efe"));
            Packet.GroupInfo made = efe.client.createChannel(c, "efe'nin kanalı");
            // the admin made the channel and holds its name key: everyone can read the name
            ayse.await(made.id(), g -> g.name().equals("efe'nin kanalı"));
            emir.await(made.id(), g -> g.name().equals("efe'nin kanalı"));

            assertEquals(ErrorCode.NOT_GROUP_ADMIN, assertThrows(ApiException.class,
                    () -> efe.client.removeGroupMember(c, "emir")).code(), "admins cannot remove the owner");
            assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(ApiException.class,
                    () -> emir.client.removeGroupMember(made.id(), "ayse")).code(), "channels follow the community");

            efe.client.removeGroupMember(c, "ayse");
            List<Long> gone = List.of(ayse.removed.poll(5, TimeUnit.SECONDS), ayse.removed.poll(5, TimeUnit.SECONDS));
            assertTrue(gone.contains(c) && gone.contains(made.id()), "removed from the community and every channel");

            efe.client.deleteChannel(made.id());
            emir.await(c, g -> true);
            for (int i = 0; i < 100 && emir.client.groups().stream().anyMatch(g -> g.id() == made.id()); i++) Thread.sleep(25);
            assertFalse(emir.client.groups().stream().anyMatch(g -> g.id() == made.id()), "deleted for everyone");
        }
    }

    @Test
    void whenTheOwnerLeavesTheLongestMemberTakesOver() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe"); Device ayse = new Device("ayse")) {
            long c = emir.client.createCommunity("Ekip").id();
            long channel = emir.client.createChannel(c, "proje").id();
            String code = emir.client.createInvite(c, 0, 24);
            efe.client.joinCommunity(code);
            ayse.client.joinCommunity(code);
            efe.await(channel, g -> g.members().size() == 3);

            emir.client.leaveGroup(c);
            efe.await(c, g -> g.owner().equals("efe") && !g.hasMember("emir"));
            efe.await(channel, g -> g.owner().equals("efe") && !g.hasMember("emir"));
            efe.client.sendGroupText(channel, "artık ben yönetiyorum");
            assertEquals(channel + "/efe: artık ben yönetiyorum", ayse.nextMessage());
        }
    }

    @Test
    void theServerDoesNotKnowChannelNames() throws Exception {
        try (Device emir = new Device("emir")) {
            long c = emir.client.createCommunity("Gizli Kulüp").id();
            emir.client.createChannel(c, "plan-b");
            try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("server/server.db"));
                 Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT name, sealed_name FROM groups_")) {
                while (rs.next()) {
                    assertEquals("", rs.getString(1));
                    String sealed = new String(rs.getBytes(2), StandardCharsets.ISO_8859_1);
                    assertFalse(sealed.contains("Gizli") || sealed.contains("plan-b"));
                }
            }
        }
    }

    @Test
    void inviteCodesAreForgivingWhenTyped() {
        assertEquals("ABCDE-FGHJK", GroupService.normalizeCode(" abcde fghjk "));
        assertEquals("ABCDE-FGHJK", GroupService.normalizeCode("https://example.org/join/ABCDEFGHJK"));
    }
}
