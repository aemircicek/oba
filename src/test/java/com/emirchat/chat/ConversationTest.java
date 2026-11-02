package com.emirchat.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.emirchat.crypto.Identity;
import com.emirchat.net.Connector;
import com.emirchat.trust.TrustPolicy;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConversationTest {
    private static final TrustPolicy TRUST_ALL = (n, fp) -> {};

    private record Pair(Conversation host, Conversation client, java.net.Socket clientSocket) {}

    private static Pair connect() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        CompletableFuture<ServerSocket> listening = new CompletableFuture<>();
        CompletableFuture<Connector.Established> host = CompletableFuture.supplyAsync(() -> {
            try {
                return Connector.host(Identity.generate(), "emir", "127.0.0.1", port, TRUST_ALL, new Connector.Progress() {
                    @Override public void listening(ServerSocket server) { listening.complete(server); }
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        listening.get(5, TimeUnit.SECONDS);
        Connector.Established client = Connector.connect(Identity.generate(), "efe", "127.0.0.1", port, TRUST_ALL,
                new Connector.Progress() {});
        Connector.Established h = host.get(5, TimeUnit.SECONDS);
        return new Pair(new Conversation(h.session(), h.socket()), new Conversation(client.session(), client.socket()),
                client.socket());
    }

    private static final class Recorder implements Conversation.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Conversation.EndReason> ended = new CompletableFuture<>();

        @Override public void onMessage(ChatMessage message) { messages.add(message.text()); }

        @Override public void onEnded(Conversation.EndReason reason, String detail) { ended.complete(reason); }
    }

    @Test
    void messagesFlowBothWaysAndLeavingNotifiesThePeer() throws Exception {
        Pair p = connect();
        Recorder host = new Recorder();
        Recorder client = new Recorder();
        p.host().start(host);
        p.client().start(client);

        p.client().send("merhaba");
        assertEquals("merhaba", host.messages.poll(5, TimeUnit.SECONDS));
        p.host().send("selam 👋");
        assertEquals("selam 👋", client.messages.poll(5, TimeUnit.SECONDS));
        assertEquals("emir", p.client().session().peerName());

        p.client().leave();
        assertEquals(Conversation.EndReason.PEER_LEFT, host.ended.get(5, TimeUnit.SECONDS));
        assertEquals(Conversation.EndReason.LOCAL_LEFT, client.ended.get(5, TimeUnit.SECONDS));
        assertFalse(p.host().isOpen());
        assertThrows(IOException.class, () -> p.host().send("too late"));
    }

    @Test
    void droppedConnectionIsReportedAsLost() throws Exception {
        Pair p = connect();
        Recorder host = new Recorder();
        p.host().start(host);
        // The client vanishes without saying goodbye (crash, Wi-Fi off).
        p.clientSocket().close();
        assertEquals(Conversation.EndReason.CONNECTION_LOST, host.ended.get(5, TimeUnit.SECONDS));
        assertFalse(p.host().isOpen());
    }
}
