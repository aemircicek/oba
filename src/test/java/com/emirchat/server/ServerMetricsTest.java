package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.client.ChatClient;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import com.emirchat.trust.KnownPeers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code /metrics} page counts what happens and names nobody. */
class ServerMetricsTest {
    @TempDir Path dir;

    @Test
    void countsLoginsMessagesAndNamesNobody() throws Exception {
        try (ChatServer server = new ChatServer(new ChatServer.Config(dir.resolve("server"), "127.0.0.1", 0, 1_000)).start()) {
            server.metrics().serve("127.0.0.1", 0);
            ChatClient emir = client(server, "emirmetrics");
            ChatClient efe = client(server, "efemetrics");
            try {
                emir.connection().sendFriendRequest("efemetrics");
                efe.connection().respondFriendRequest("emirmetrics", true);
                emir.sendText("efemetrics", "hello");
                String page = fetch(server.metrics().port(), "/metrics");
                assertEquals(2, value(page, "oba_registrations_total"));
                assertEquals(2, value(page, "oba_users_online"));
                assertTrue(value(page, "oba_envelopes_total") >= 1, page);
                assertTrue(value(page, "oba_connections_open") >= 2, page);
                assertEquals(1, value(page, "oba_up"));
                assertFalse(page.contains("emirmetrics") || page.contains("efemetrics"), "no user names");
                assertFalse(page.contains("127.0.0.1"), "no addresses");
                assertTrue(page.contains("# TYPE oba_logins_total counter"));
                assertEquals(404, status(server.metrics().port(), "/"));
            } finally {
                emir.close();
                efe.close();
            }
        }
    }

    private ChatClient client(ChatServer server, String name) throws Exception {
        Path home = dir.resolve(name);
        Identity id = Identity.loadOrCreate(home);
        ServerConnection c = ServerConnection.open("127.0.0.1", server.port(), id,
                new KnownPeers(home.resolve("known_servers"), (n, fp) -> true));
        return ChatClient.start(c, id, home.resolve("accounts"), name, "correct horse battery", true,
                new ChatClient.Listener() {});
    }

    private static long value(String page, String name) {
        Matcher m = Pattern.compile("(?m)^" + name + " (\\d+)$").matcher(page);
        if (!m.find()) throw new AssertionError(name + " missing in\n" + page);
        return Long.parseLong(m.group(1));
    }

    private static String fetch(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static int status(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
