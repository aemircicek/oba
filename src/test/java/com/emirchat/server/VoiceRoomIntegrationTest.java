package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.client.ChatClient;
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

/** Voice rooms: joining, end-to-end encrypted audio between members, keys, leaving, outsiders. */
class VoiceRoomIntegrationTest {
    private static final String PASSWORD = "correct horse battery";

    @TempDir Path dir;
    private ChatServer server;

    private final class Device implements AutoCloseable {
        final String user;
        final BlockingQueue<ChatClient.Incoming> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<ChatClient.Action> actions = new LinkedBlockingQueue<>();
        final BlockingQueue<String> problems = new LinkedBlockingQueue<>();
        final ChatClient client;
        final FakeAudio audio = new FakeAudio();
        final BlockingQueue<com.emirchat.call.VoiceChannels.State> states = new LinkedBlockingQueue<>();

        Device(String user) throws Exception {
            this.user = user;
            Path home = dir.resolve(user + "-device");
            Identity identity = Identity.loadOrCreate(home);
            ServerConnection c = ServerConnection.open("127.0.0.1", server.port(), identity,
                    new KnownPeers(home.resolve("known_servers"), (n, fp) -> true));
            client = ChatClient.start(c, identity, home.resolve("accounts"), user, PASSWORD, true, new ChatClient.Listener() {
                @Override public void incoming(ChatClient.Incoming m) { messages.add(m); }

                @Override public void action(ChatClient.Action a) { actions.add(a); }

                @Override public void undecryptable(String from, String reason) { problems.add(reason); }

                @Override public void voice(com.emirchat.call.VoiceChannels.State s) { states.add(s); }
            });
            client.useAudioDevices(() -> audio);
        }

        ChatClient.Incoming nextMessage() throws InterruptedException {
            ChatClient.Incoming m = messages.poll(10, TimeUnit.SECONDS);
            assertNotNull(m, user + " expected a message; problems: " + problems);
            return m;
        }

        ChatClient.Action nextAction() throws InterruptedException {
            ChatClient.Action a = actions.poll(10, TimeUnit.SECONDS);
            assertNotNull(a, user + " expected an action; problems: " + problems);
            return a;
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

    /** A microphone the test talks into and a speaker it listens to. */
    static final class FakeAudio implements com.emirchat.call.AudioDevice {
        volatile java.util.function.Consumer<byte[]> mic;
        final BlockingQueue<byte[]> heard = new LinkedBlockingQueue<>();

        @Override public void start(java.util.function.Consumer<byte[]> captured) { mic = captured; }

        @Override public void play(byte[] frame) { heard.add(frame); }

        @Override public void stop() { mic = null; }

        void say(int frames) throws InterruptedException {
            for (int f = 0; f < frames; f++) {
                byte[] frame = new byte[FRAME_BYTES];
                for (int i = 0; i < FRAME_BYTES / 2; i++) {
                    short s = (short) (Math.sin(2 * Math.PI * 300 * (f * 160 + i) / 8000.0) * 20000);
                    frame[2 * i] = (byte) s;
                    frame[2 * i + 1] = (byte) (s >> 8);
                }
                java.util.function.Consumer<byte[]> m = mic;
                if (m != null) m.accept(frame);
                Thread.sleep(20);
            }
        }

        /** True if something louder than silence came out of the speaker within the time. */
        boolean heardSpeech(long millis) throws InterruptedException {
            long end = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < end) {
                byte[] f = heard.poll(100, TimeUnit.MILLISECONDS);
                if (f != null && com.emirchat.call.VoiceNotes.level(f) > 0.3f) return true;
            }
            return false;
        }
    }

    private static void awaitRoom(Device d, long g, int people) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (d.client.voice().rooms().getOrDefault(g, List.of()).size() == people) return;
            Thread.sleep(50);
        }
        throw new AssertionError(d.user + " expected " + people + " in the room, saw " + d.client.voice().rooms().get(g));
    }

    @Test
    void membersHearEachOtherAndOutsidersCannotJoin() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe"); Device ayse = new Device("ayse");
             Device stranger = new Device("stranger")) {
            befriend(emir, efe);
            befriend(emir, ayse);
            long g = emir.client.createGroup("sesli", List.of("efe", "ayse")).id();
            for (Device d : List.of(efe, ayse)) {
                for (int i = 0; i < 100 && d.client.refreshGroups().stream().noneMatch(x -> x.id() == g); i++) Thread.sleep(50);
            }

            emir.client.voice().join(g);
            assertTrue(emir.client.voice().state().joined());
            efe.client.voice().join(g);
            awaitRoom(emir, g, 2);
            awaitRoom(ayse, g, 2); // not in the room, but sees who is
            Thread.sleep(600);     // keys rotate and travel through the group

            emir.audio.say(25);
            assertTrue(efe.audio.heardSpeech(3000), "efe hears emir; problems: " + efe.problems);
            assertTrue(ayse.audio.heard.isEmpty(), "ayse is not in the room: nothing is relayed to her");

            ayse.client.voice().join(g);
            awaitRoom(efe, g, 3);
            Thread.sleep(800);
            efe.audio.heard.clear();
            ayse.audio.say(25);
            assertTrue(emir.audio.heardSpeech(3000), "emir hears the newcomer");
            assertTrue(efe.audio.heardSpeech(3000), "and so does efe");

            ayse.client.voice().leave();
            awaitRoom(emir, g, 2);
            assertEquals(List.of("emir", "efe"), emir.client.voice().state().participants());

            assertThrows(Exception.class, () -> stranger.client.connection().call(new com.emirchat.api.Packet.VoiceJoin(g)),
                    "only members may join");
            stranger.client.voice().join(g);
            assertTrue(!stranger.client.voice().state().joined());

            emir.client.voice().setMuted(true);
            efe.audio.heard.clear();
            emir.audio.say(15);
            assertTrue(!efe.audio.heardSpeech(800), "muted means nothing is sent");

            emir.client.refreshVoiceRooms();
            assertEquals(List.of("emir", "efe"), emir.client.voice().rooms().get(g));
            emir.client.voice().leave();
            efe.client.voice().leave();
            awaitRoom(ayse, g, 0);
        }
    }

    @Test
    void aRemovedSilentListenerIsCutOff() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe"); Device ayse = new Device("ayse")) {
            befriend(emir, efe);
            befriend(emir, ayse);
            long g = emir.client.createGroup("gizli", List.of("efe", "ayse")).id();
            for (Device d : List.of(efe, ayse)) {
                for (int i = 0; i < 100 && d.client.refreshGroups().stream().noneMatch(x -> x.id() == g); i++) Thread.sleep(50);
            }
            for (Device d : List.of(emir, efe, ayse)) d.client.voice().join(g);
            awaitRoom(emir, g, 3);
            Thread.sleep(800);
            efe.audio.say(20);
            assertTrue(ayse.audio.heardSpeech(3000), "ayse hears while she is a member");

            // ayse never speaks again: only the server's sweep and the others' key change can cut her off
            emir.client.removeGroupMember(g, "ayse");
            awaitRoom(emir, g, 2);
            awaitRoom(efe, g, 2);
            Thread.sleep(600);
            ayse.audio.heard.clear();
            efe.audio.say(25);
            assertTrue(emir.audio.heardSpeech(3000), "the members still hear each other");
            assertTrue(!ayse.audio.heardSpeech(1500), "the removed listener hears nothing");
        }
    }
}
