package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
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

class FriendsIntegrationTest {
    private static final String PASSWORD = "correct horse battery";

    @TempDir Path dir;
    private ChatServer server;
    private KnownPeers knownServers;

    /** A logged-in user plus the pushes they received. */
    private record User(ServerConnection conn, BlockingQueue<Packet> pushes, Identity device) implements AutoCloseable {
        Packet nextPush() throws InterruptedException {
            Packet p = pushes.poll(5, TimeUnit.SECONDS);
            if (p == null) throw new AssertionError("expected a push");
            return p;
        }

        /** Next push of the given type, skipping presence noise. */
        <T extends Packet> T next(Class<T> type) throws InterruptedException {
            while (true) {
                Packet p = nextPush();
                if (type.isInstance(p)) return type.cast(p);
                if (!(p instanceof Packet.PresenceChanged)) throw new AssertionError("expected " + type.getSimpleName() + " but got " + p);
            }
        }

        @Override public void close() { conn.close(); }
    }

    @BeforeEach
    void start() throws Exception {
        server = new ChatServer(new ChatServer.Config(dir.resolve("server"), "127.0.0.1", 0, 1_000)).start();
        knownServers = new KnownPeers(dir.resolve("known_servers"), (n, fp) -> true);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private User open(String name, boolean register, Identity device) throws Exception {
        ServerConnection c = ServerConnection.open("127.0.0.1", server.port(), device, knownServers);
        BlockingQueue<Packet> pushes = new LinkedBlockingQueue<>();
        c.onPush(pushes::add);
        if (register) c.register(name, PASSWORD); else c.login(name, PASSWORD);
        return new User(c, pushes, device);
    }

    private User register(String name) throws Exception {
        return open(name, true, Identity.generate());
    }

    @Test
    void requestAcceptAndPresence() throws Exception {
        try (User emir = register("emir"); User efe = register("efe")) {
            assertFalse(emir.conn().sendFriendRequest("Efe"));
            assertEquals("emir", efe.next(Packet.FriendRequestReceived.class).from());

            Packet.FriendList efeView = efe.conn().listFriends();
            assertEquals(List.of("emir"), efeView.incoming());
            assertEquals(List.of("efe"), emir.conn().listFriends().outgoing());

            efe.conn().respondFriendRequest("emir", true);
            Packet.Friend efeSeenByEmir = emir.next(Packet.FriendAdded.class).friend();
            assertEquals("efe", efeSeenByEmir.username());
            assertTrue(efeSeenByEmir.online());
            assertEquals(efe.device().fingerprint(), efeSeenByEmir.fingerprint(), "server reports the real device key");
            assertEquals("emir", efe.next(Packet.FriendAdded.class).friend().username());

            Packet.FriendList list = emir.conn().listFriends();
            assertEquals(1, list.friends().size());
            assertTrue(list.incoming().isEmpty() && list.outgoing().isEmpty());

            efe.conn().close(); // efe goes offline
            Packet.PresenceChanged offline = emir.next(Packet.PresenceChanged.class);
            assertEquals("efe", offline.username());
            assertFalse(offline.online());
            assertFalse(emir.conn().listFriends().friends().get(0).online());

            try (User efeAgain = open("efe", false, efe.device())) {
                Packet.PresenceChanged online = emir.next(Packet.PresenceChanged.class);
                assertTrue(online.online());
                assertTrue(efeAgain.conn().isOpen());
            }
        }
    }

    @Test
    void mutualRequestsBecomeFriendsImmediately() throws Exception {
        try (User emir = register("emir"); User efe = register("efe")) {
            assertFalse(emir.conn().sendFriendRequest("efe"));
            assertTrue(efe.conn().sendFriendRequest("emir"), "efe had a pending request from emir");
            assertEquals(1, emir.conn().listFriends().friends().size());
            assertEquals(ErrorCode.ALREADY_FRIENDS,
                    assertThrows(ApiException.class, () -> emir.conn().sendFriendRequest("efe")).code());
        }
    }

    @Test
    void declineIsSilentAndRemoveNotifies() throws Exception {
        try (User emir = register("emir"); User efe = register("efe"); User ayse = register("ayse")) {
            ayse.conn().sendFriendRequest("emir");
            emir.next(Packet.FriendRequestReceived.class);
            emir.conn().respondFriendRequest("ayse", false);
            assertTrue(ayse.conn().listFriends().outgoing().isEmpty(), "request is gone");
            assertNull(ayse.pushes().poll(300, TimeUnit.MILLISECONDS), "decliner is not revealed by a push");

            emir.conn().sendFriendRequest("efe");
            efe.conn().respondFriendRequest("emir", true);
            emir.conn().removeFriend("efe");
            // efe's pushes arrive in order: request, then friendship, then removal.
            assertEquals("emir", efe.next(Packet.FriendRequestReceived.class).from());
            assertEquals("emir", efe.next(Packet.FriendAdded.class).friend().username());
            assertEquals("emir", efe.next(Packet.FriendRemoved.class).username());
            assertTrue(efe.conn().listFriends().friends().isEmpty());
            assertEquals(ErrorCode.NOT_FRIENDS, assertThrows(ApiException.class, () -> emir.conn().removeFriend("efe")).code());
        }
    }

    @Test
    void cancelOwnRequest() throws Exception {
        try (User emir = register("emir"); User efe = register("efe")) {
            emir.conn().sendFriendRequest("efe");
            emir.conn().removeFriend("efe");
            assertTrue(efe.conn().listFriends().incoming().isEmpty());
        }
    }

    @Test
    void invalidRequests() throws Exception {
        try (User emir = register("emir")) {
            assertEquals(ErrorCode.USER_NOT_FOUND, assertThrows(ApiException.class, () -> emir.conn().sendFriendRequest("ghost")).code());
            assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(ApiException.class, () -> emir.conn().sendFriendRequest("emir")).code());
            assertEquals(ErrorCode.USER_NOT_FOUND, assertThrows(ApiException.class, () -> emir.conn().sendFriendRequest("bad name;--")).code());
            register("efe").close();
            assertEquals(ErrorCode.INVALID_REQUEST,
                    assertThrows(ApiException.class, () -> emir.conn().respondFriendRequest("efe", true)).code(),
                    "cannot accept a request that was never sent");
        }
    }

    @Test
    void friendOperationsRequireLogin() throws Exception {
        try (ServerConnection anonymous = ServerConnection.open("127.0.0.1", server.port(), Identity.generate(), knownServers)) {
            ApiException e = assertThrows(ApiException.class, anonymous::listFriends);
            assertEquals(ErrorCode.NOT_LOGGED_IN, e.code());
        }
    }

    @Test
    void friendRequestsAreRateLimited() throws Exception {
        try (User spammer = register("spammer")) {
            for (int i = 0; i < FriendService.REQUESTS_PER_HOUR; i++) {
                register("target" + i).close();
                spammer.conn().sendFriendRequest("target" + i);
            }
            register("onemore").close();
            assertEquals(ErrorCode.RATE_LIMITED,
                    assertThrows(ApiException.class, () -> spammer.conn().sendFriendRequest("onemore")).code());
        }
    }

    @Test
    void friendsAreWarnedWhenAKeyChanges() throws Exception {
        try (User emir = register("emir"); User efe = register("efe")) {
            emir.conn().sendFriendRequest("efe");
            efe.conn().respondFriendRequest("emir", true);
            emir.next(Packet.FriendAdded.class);
            efe.conn().close(); // efe goes offline
            emir.next(Packet.PresenceChanged.class);

            Identity newLaptop = Identity.generate();
            try (User efeNew = open("efe", false, newLaptop)) {
                Packet.IdentityKeyChanged changed = emir.next(Packet.IdentityKeyChanged.class);
                assertEquals("efe", changed.username());
                assertEquals(newLaptop.fingerprint(), Identity.fingerprint(changed.identityKey()));
                assertEquals(newLaptop.fingerprint(), emir.conn().listFriends().friends().get(0).fingerprint());
                assertInstanceOf(ServerConnection.class, efeNew.conn());
            }
        }
    }

    @Test
    void stateSurvivesRestart() throws Exception {
        try (User emir = register("emir"); User efe = register("efe")) {
            emir.conn().sendFriendRequest("efe");
            efe.conn().respondFriendRequest("emir", true);
        }
        int port = server.port();
        server.close();
        server = new ChatServer(new ChatServer.Config(dir.resolve("server"), "127.0.0.1", port, 1_000)).start();
        try (ServerConnection c = ServerConnection.open("127.0.0.1", port, Identity.generate(), knownServers)) {
            c.login("emir", PASSWORD);
            assertEquals("efe", c.listFriends().friends().get(0).username());
        }
    }
}
