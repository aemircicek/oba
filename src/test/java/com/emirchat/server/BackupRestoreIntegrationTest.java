package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.chat.ChatMessage;
import com.emirchat.client.Backup;
import com.emirchat.client.ChatClient;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import com.emirchat.trust.KnownPeers;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A restored backup moves the account to another folder (computer) without a key change. */
class BackupRestoreIntegrationTest {
    private static final String PASSWORD = "correct horse battery";

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

    private ChatClient login(Path home, String user, boolean register, BlockingQueue<String> inbox,
                             BlockingQueue<String> keyChanges) throws Exception {
        Identity id = Identity.loadOrCreate(home);
        ServerConnection c = ServerConnection.open("127.0.0.1", server.port(), id,
                new KnownPeers(home.resolve("known_servers"), (n, fp) -> true));
        return ChatClient.start(c, id, home.resolve("accounts"), user, PASSWORD, register, new ChatClient.Listener() {
            @Override public void message(String from, ChatMessage m, long e) { inbox.add(from + ": " + m.text()); }

            @Override public void keyChanged(String friend) { keyChanges.add(friend); }

            @Override public void undecryptable(String from, String reason) { inbox.add("! " + reason); }
        });
    }

    @Test
    void chatsContinueAfterRestoringOnANewComputer() throws Exception {
        BlockingQueue<String> emirInbox = new LinkedBlockingQueue<>();
        BlockingQueue<String> efeInbox = new LinkedBlockingQueue<>();
        BlockingQueue<String> efeKeyChanges = new LinkedBlockingQueue<>();
        Path oldLaptop = dir.resolve("old-laptop");
        ChatClient efe = login(dir.resolve("efe"), "efe", true, efeInbox, efeKeyChanges);
        ChatClient emir = login(oldLaptop, "emir", true, emirInbox, new LinkedBlockingQueue<>());
        emir.connection().sendFriendRequest("efe");
        efe.connection().respondFriendRequest("emir", true);
        emir.sendText("efe", "eski bilgisayardan");
        assertEquals("emir: eski bilgisayardan", efeInbox.poll(10, TimeUnit.SECONDS));
        efe.sendText("emir", "aldım");
        assertEquals("efe: aldım", emirInbox.poll(10, TimeUnit.SECONDS));
        emir.close();

        Path backup = dir.resolve("emir.scbackup");
        Backup.create(oldLaptop, backup, "yedek parolası 123".toCharArray(), Map.of());
        Path newLaptop = dir.resolve("new-laptop");
        Backup.restore(backup, "yedek parolası 123".toCharArray(), newLaptop);

        BlockingQueue<String> restoredInbox = new LinkedBlockingQueue<>();
        ChatClient restored = login(newLaptop, "emir", false, restoredInbox, new LinkedBlockingQueue<>());
        try {
            efe.sendText("emir", "yeni bilgisayar mı?");
            assertEquals("efe: yeni bilgisayar mı?", restoredInbox.poll(10, TimeUnit.SECONDS),
                    "the existing session continues: no new X3DH, no undecryptable message");
            restored.sendText("efe", "evet, yedekten döndüm");
            String got = efeInbox.poll(10, TimeUnit.SECONDS);
            assertNotNull(got);
            assertEquals("emir: evet, yedekten döndüm", got);
            assertTrue(efeKeyChanges.isEmpty(), "same identity, so efe sees no key change");
            assertFalse(restoredInbox.stream().anyMatch(s -> s.startsWith("!")));
        } finally {
            restored.close();
            efe.close();
        }
    }
}
