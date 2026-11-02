package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
import com.emirchat.client.ChatClient;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import com.emirchat.e2e.GroupNames;
import com.emirchat.trust.KnownPeers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Group names are end-to-end encrypted: members read them, the server and former members do not. */
class GroupNameIntegrationTest {
    private static final String PASSWORD = "correct horse battery";

    @TempDir Path dir;
    private ChatServer server;

    private final class Device implements AutoCloseable {
        final String user;
        final ChatClient client;

        Device(String user) throws Exception {
            this.user = user;
            Path home = dir.resolve(user + "-device");
            Identity identity = Identity.loadOrCreate(home);
            ServerConnection c = ServerConnection.open("127.0.0.1", server.port(), identity,
                    new KnownPeers(home.resolve("known_servers"), (n, fp) -> true));
            client = ChatClient.start(c, identity, home.resolve("accounts"), user, PASSWORD, true, new ChatClient.Listener() {});
        }

        /** Waits until this device's view of the group satisfies the condition. */
        Packet.GroupInfo await(long groupId, Predicate<Packet.GroupInfo> condition) throws Exception {
            for (int i = 0; i < 200; i++) {
                Packet.GroupInfo g = client.groups().stream().filter(x -> x.id() == groupId).findFirst().orElse(null);
                if (g != null && condition.test(g)) return g;
                Thread.sleep(25);
            }
            throw new AssertionError(user + " never saw the expected state of group " + groupId + ": " + client.groups());
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

    /** {plaintext name column, sealed name} as the server stores them. */
    private Object[] stored(long groupId) throws Exception {
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("server/server.db"));
             PreparedStatement ps = db.prepareStatement("SELECT name, sealed_name FROM groups_ WHERE id = ?")) {
            ps.setLong(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return new Object[] {rs.getString(1), rs.getBytes(2)};
            }
        }
    }

    @Test
    void membersSeeTheNameButTheServerDoesNot() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe"); Device ayse = new Device("ayse")) {
            befriend(emir, efe);
            befriend(emir, ayse);
            Packet.GroupInfo created = emir.client.createGroup("Gizli MobilCAD ekibi", List.of("efe", "ayse"));
            assertEquals("Gizli MobilCAD ekibi", created.name());
            long g = created.id();
            efe.await(g, x -> x.name().equals("Gizli MobilCAD ekibi"));
            ayse.await(g, x -> x.name().equals("Gizli MobilCAD ekibi"));

            Object[] row = stored(g);
            assertEquals("", row[0], "no plaintext name on the server");
            String sealed = new String((byte[]) row[1], StandardCharsets.ISO_8859_1);
            assertFalse(sealed.contains("MobilCAD") || sealed.contains("Gizli"));
        }
    }

    @Test
    void renamesReachMembersAndOnlyTheAdminMayRename() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe")) {
            befriend(emir, efe);
            long g = emir.client.createGroup("ilk ad", List.of("efe")).id();
            efe.await(g, x -> x.name().equals("ilk ad"));
            emir.client.renameGroup(g, "ikinci ad");
            efe.await(g, x -> x.name().equals("ikinci ad"));

            ApiException e = assertThrows(ApiException.class, () -> efe.client.renameGroup(g, "ele geçirildi"));
            assertEquals(ErrorCode.NOT_GROUP_ADMIN, e.code());
        }
    }

    @Test
    void afterARemovalTheNameMovesToANewKey() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe"); Device ayse = new Device("ayse")) {
            befriend(emir, efe);
            befriend(emir, ayse);
            long g = emir.client.createGroup("ekip", List.of("efe", "ayse")).id();
            efe.await(g, x -> x.name().equals("ekip"));
            ayse.await(g, x -> x.name().equals("ekip"));
            assertEquals(1, GroupNames.versionOf((byte[]) stored(g)[1]));

            emir.client.removeGroupMember(g, "ayse");
            for (int i = 0; i < 200 && GroupNames.versionOf((byte[]) stored(g)[1]) != 2; i++) Thread.sleep(25);
            assertEquals(2, GroupNames.versionOf((byte[]) stored(g)[1]), "re-encrypted under a key ayse never gets");
            efe.await(g, x -> x.name().equals("ekip") && x.members().size() == 2);
            emir.client.renameGroup(g, "ayşesiz ekip");
            efe.await(g, x -> x.name().equals("ayşesiz ekip"));
        }
    }

    @Test
    void groupsWithAPlaintextNameAreMigratedByTheirAdmin() throws Exception {
        long g;
        try (Device emir = new Device("emir"); Device efe = new Device("efe")) {
            befriend(emir, efe);
            // an older client created the group with a name the server can read
            g = ((Packet.GroupInfo) emir.client.connection().call(new Packet.CreateGroup("Eski grup", List.of("efe")))).id();
            // the current client notices the GroupUpdated push and encrypts the name
            for (int i = 0; i < 200 && !"".equals(stored(g)[0]); i++) Thread.sleep(25);
            assertEquals("", stored(g)[0], "plaintext name removed");
            assertEquals(1, GroupNames.versionOf((byte[]) stored(g)[1]));
            efe.await(g, x -> x.name().equals("Eski grup"));
        }
    }
}
