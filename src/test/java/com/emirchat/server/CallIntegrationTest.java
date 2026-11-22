package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.call.AudioDevice;
import com.emirchat.call.Calls;
import com.emirchat.client.ChatClient;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import com.emirchat.trust.KnownPeers;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Voice calls between friends through a real server, with fake microphones and speakers. */
class CallIntegrationTest {
    private static final String PASSWORD = "correct horse battery";

    @TempDir Path dir;
    private ChatServer server;

    /** A microphone the test speaks into and a speaker it listens to. */
    static final class FakeAudio implements AudioDevice {
        volatile Consumer<byte[]> microphone;
        final BlockingQueue<byte[]> speaker = new LinkedBlockingQueue<>();
        volatile boolean stopped;

        @Override public void start(Consumer<byte[]> captured) { microphone = captured; }

        @Override public void play(byte[] frame) { speaker.add(frame); }

        @Override public void stop() { stopped = true; }

        /**
         * Speaks one frame. The call reports "connected" a moment before it opens the microphone,
         * so wait for that rather than fail on a slow machine.
         */
        void say(byte[] frame) {
            long until = System.currentTimeMillis() + 5000;
            Consumer<byte[]> m = microphone;
            while (m == null && System.currentTimeMillis() < until) {
                Thread.onSpinWait();
                m = microphone;
            }
            if (m == null) throw new AssertionError("the call never opened the microphone");
            m.accept(frame);
        }
    }

    private final class Device implements AutoCloseable {
        final ChatClient client;
        final FakeAudio audio = new FakeAudio();
        final BlockingQueue<Calls.Call> events = new LinkedBlockingQueue<>();

        Device(String user) throws Exception {
            Path home = dir.resolve(user);
            Identity identity = Identity.loadOrCreate(home);
            ServerConnection c = ServerConnection.open("127.0.0.1", server.port(), identity,
                    new KnownPeers(home.resolve("known_servers"), (n, fp) -> true));
            client = ChatClient.start(c, identity, home.resolve("accounts"), user, PASSWORD, true, new ChatClient.Listener() {
                @Override public void call(Calls.Call call) { events.add(call); }
            });
            client.useAudioDevices(() -> audio);
        }

        Calls.Call await(Predicate<Calls.Call> condition) throws InterruptedException {
            while (true) {
                Calls.Call e = events.poll(10, TimeUnit.SECONDS);
                assertNotNull(e, "expected a call event");
                if (condition.test(e)) return e;
            }
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

    private static void befriend(Device a, Device b, String bName, String aName) throws Exception {
        a.client.connection().sendFriendRequest(bName);
        b.client.connection().respondFriendRequest(aName, true);
    }

    /** 20 ms of a steady 500 Hz tone, starting {@code seed} samples in (so frames join up). */
    private static byte[] tone(int seed) {
        byte[] pcm = new byte[AudioDevice.FRAME_BYTES];
        for (int i = 0; i < pcm.length / 2; i++) {
            short s = (short) (Math.sin(2 * Math.PI * 500 * (i + seed) / AudioDevice.SAMPLE_RATE) * 8000);
            pcm[2 * i] = (byte) s;
            pcm[2 * i + 1] = (byte) (s >> 8);
        }
        return pcm;
    }

    @Test
    void friendsTalkBothWaysAndHangUp() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe")) {
            befriend(emir, efe, "efe", "emir");
            emir.client.calls().call("efe");
            assertEquals(Calls.State.CALLING, emir.await(c -> true).state());
            Calls.Call ringing = efe.await(c -> c.state() == Calls.State.RINGING);
            assertEquals("emir", ringing.peer());
            assertFalse(ringing.outgoing());

            efe.client.calls().accept();
            efe.await(c -> c.state() == Calls.State.CONNECTED);
            emir.await(c -> c.state() == Calls.State.CONNECTED);

            // The first frames go out in μ-law, with an "I can play Opus" note; then both switch.
            byte[] hello = tone(0);
            emir.audio.say(hello);
            byte[] heard = efe.audio.speaker.poll(5, TimeUnit.SECONDS);
            assertNotNull(heard, "efe hears emir");
            assertEquals(hello.length, heard.length);
            efe.audio.say(tone(0));
            assertNotNull(emir.audio.speaker.poll(5, TimeUnit.SECONDS), "and emir hears efe");
            awaitCodec(emir, "opus");
            awaitCodec(efe, "opus");

            // a second of speech in Opus comes out at the same level and pitch
            efe.audio.speaker.clear();
            int frames = 50;
            for (int f = 0; f < frames; f++) emir.audio.say(tone(f * hello.length / 2));
            java.io.ByteArrayOutputStream got = new java.io.ByteArrayOutputStream();
            for (int f = 0; f < frames; f++) {
                byte[] frame = efe.audio.speaker.poll(5, TimeUnit.SECONDS);
                assertNotNull(frame, "frame " + f);
                got.writeBytes(frame);
            }
            byte[] sent = new byte[frames * hello.length];
            for (int f = 0; f < frames; f++) System.arraycopy(tone(f * hello.length / 2), 0, sent, f * hello.length, hello.length);
            assertTrue(similar(sent, got.toByteArray()), "Opus keeps the sound close");

            emir.client.calls().setMuted(true);
            emir.await(Calls.Call::muted);
            // the first muted frame may still carry the last few ms of sound (the codec's own delay)
            byte[] silence = null;
            for (int f = 0; f < 3; f++) {
                emir.audio.say(hello);
                silence = efe.audio.speaker.poll(5, TimeUnit.SECONDS);
                assertNotNull(silence);
            }
            int loudest = 0;
            for (short v : samples(silence)) loudest = Math.max(loudest, Math.abs(v));
            assertTrue(loudest < 64, "muted: efe hears silence (loudest sample " + loudest + ")");

            efe.client.calls().hangUp();
            assertEquals(Calls.EndReason.HUNG_UP, efe.await(c -> c.state() == Calls.State.ENDED).reason());
            assertEquals(Calls.EndReason.HUNG_UP, emir.await(c -> c.state() == Calls.State.ENDED).reason());
            assertTrue(emir.audio.stopped && efe.audio.stopped, "microphones are released");
            assertNull(emir.client.calls().current());
        }
    }

    /** Pictures as the test's screen sees them. */
    static final class FakeScreen implements com.emirchat.call.CallVideo.View {
        final BlockingQueue<com.emirchat.video.VideoFrame> remote = new LinkedBlockingQueue<>();
        final BlockingQueue<Boolean> screens = new LinkedBlockingQueue<>();
        final BlockingQueue<String> stops = new LinkedBlockingQueue<>();

        @Override public void remote(com.emirchat.video.VideoFrame frame, boolean screen) {
            screens.add(screen); // first: a test that saw the frame finds its kind already there
            remote.add(frame);
        }

        @Override public void remoteStopped() { stops.add("stopped"); }
    }

    @Test
    void videoFlowsAlongsideTheVoiceAndStops() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe")) {
            befriend(emir, efe, "efe", "emir");
            FakeScreen efeScreen = new FakeScreen();
            efe.client.calls().videoView(efeScreen);
            emir.client.calls().call("efe");
            efe.await(c -> c.state() == Calls.State.RINGING);
            efe.client.calls().accept();
            emir.await(c -> c.state() == Calls.State.CONNECTED);
            efe.await(c -> c.state() == Calls.State.CONNECTED);

            // voice runs all the time, as in a real call: 50 packets a second each way
            java.util.concurrent.atomic.AtomicBoolean talking = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.concurrent.atomic.AtomicInteger said = new java.util.concurrent.atomic.AtomicInteger();
            Thread voices = Thread.ofPlatform().daemon().start(() -> {
                for (int f = 0; talking.get(); f++) {
                    emir.audio.say(tone(f * 320));
                    said.incrementAndGet();
                    efe.audio.say(tone(f * 320));
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            try {
                for (int i = 0; i < 100 && !emir.client.calls().videoPossible(); i++) Thread.sleep(20);
                assertTrue(emir.client.calls().videoPossible(), "both sides are current versions");

                java.util.concurrent.atomic.AtomicInteger made = new java.util.concurrent.atomic.AtomicInteger();
                com.emirchat.video.PatternSource pattern = new com.emirchat.video.PatternSource(480, 360, 15, false);
                emir.client.calls().startVideo(new com.emirchat.video.VideoSource() {
                    @Override public boolean screen() { return false; }

                    @Override public int fps() { return pattern.fps(); }

                    @Override public void start(java.util.function.Consumer<com.emirchat.video.VideoFrame> frames) {
                        pattern.start(f -> {
                            made.incrementAndGet();
                            frames.accept(f);
                        });
                    }

                    @Override public void stop() { pattern.stop(); }
                });
                com.emirchat.video.VideoFrame first = efeScreen.remote.poll(10, TimeUnit.SECONDS);
                assertNotNull(first, "efe sees emir");
                assertEquals(480, first.width());
                assertEquals(360, first.height());
                assertEquals(Boolean.FALSE, efeScreen.screens.poll(5, TimeUnit.SECONDS), "a camera, not a screen");

                // three seconds of video next to the voice: nearly every frame arrives, so the relay's limit is kept
                efeScreen.remote.clear();
                efe.audio.speaker.clear();
                int saidBefore = said.get();
                int madeBefore = made.get();
                Thread.sleep(3000);
                int frames = efeScreen.remote.size();
                int madeIn = made.get() - madeBefore;
                int spoken = said.get() - saidBefore;
                Thread.sleep(300); // the last frames still on their way
                int heard = efe.audio.speaker.size();
                // compared with what the camera made: a slow test machine makes fewer, not the relay
                assertTrue(madeIn >= 15, "the test camera ran: " + madeIn + " frames in 3 s");
                assertTrue(frames >= madeIn * 0.8 - 2, "nearly every frame came through: " + frames + " of " + madeIn);
                assertTrue(heard >= spoken * 0.95, "and the voice was not crowded out: " + heard + " of " + spoken + " heard");
                com.emirchat.video.VideoFrame latest = null;
                for (com.emirchat.video.VideoFrame f : efeScreen.remote) latest = f;
                assertNotNull(latest);

                emir.client.calls().startVideo(new com.emirchat.video.PatternSource(1280, 800, 5, true));
                efeScreen.screens.clear();
                boolean sawScreen = false;
                long until = System.currentTimeMillis() + 10_000;
                while (!sawScreen && System.currentTimeMillis() < until) {
                    sawScreen = Boolean.TRUE.equals(efeScreen.screens.poll(1, TimeUnit.SECONDS));
                }
                assertTrue(sawScreen, "switching to the screen is seen as a screen");

                emir.client.calls().stopVideo();
                assertNotNull(efeScreen.stops.poll(5, TimeUnit.SECONDS), "and efe learns it stopped");
            } finally {
                talking.set(false);
                voices.join();
            }
        }
    }

    /** Connects a call from emir to efe with a stream of silence both ways, as a real call has. */
    private static Thread connectTalking(Device emir, Device efe, java.util.concurrent.atomic.AtomicBoolean talking)
            throws Exception {
        return connectTalking(emir, efe, talking, new java.util.concurrent.atomic.AtomicInteger());
    }

    private static Thread connectTalking(Device emir, Device efe, java.util.concurrent.atomic.AtomicBoolean talking,
                                         java.util.concurrent.atomic.AtomicInteger said) throws Exception {
        emir.client.calls().call("efe");
        efe.await(c -> c.state() == Calls.State.RINGING);
        efe.client.calls().accept();
        emir.await(c -> c.state() == Calls.State.CONNECTED);
        efe.await(c -> c.state() == Calls.State.CONNECTED);
        return Thread.ofPlatform().daemon().start(() -> {
            for (int f = 0; talking.get(); f++) {
                emir.audio.say(tone(f * 320));
                said.incrementAndGet();
                efe.audio.say(tone(f * 320));
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    private static boolean awaitDirect(Device d, long millis) throws InterruptedException {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            if (d.client.calls().direct()) return true;
            Thread.sleep(50);
        }
        return false;
    }

    @Test
    void whenBothAllowItTheCallGoesDirectlyAndLeavesTheRelay() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe")) {
            befriend(emir, efe, "efe", "emir");
            emir.client.calls().directAllowed(u -> true);
            efe.client.calls().directAllowed(u -> true);
            java.util.concurrent.atomic.AtomicBoolean talking = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.concurrent.atomic.AtomicInteger said = new java.util.concurrent.atomic.AtomicInteger();
            Thread voices = connectTalking(emir, efe, talking, said);
            try {
                assertTrue(awaitDirect(emir, 10_000), "emir's side found a direct path");
                assertTrue(awaitDirect(efe, 10_000), "and so did efe's");
                long[] before = emir.client.calls().packetsSent();
                int saidBefore = said.get();
                efe.audio.speaker.clear();
                // until 30 packets were said (1.5 s normally; much longer on a slow CI machine)
                long deadline = System.currentTimeMillis() + 8000;
                while (said.get() - saidBefore < 30 && System.currentTimeMillis() < deadline) Thread.sleep(50);
                Thread.sleep(200); // the last ones on their way
                long[] after = emir.client.calls().packetsSent();
                int saidIn = said.get() - saidBefore;
                // compared with what was said: a slow test machine says less, it is not the path
                assertTrue(saidIn >= 15, "the test spoke: " + saidIn);
                assertTrue(after[0] - before[0] >= saidIn * 0.9 - 2, "audio goes directly: " + (after[0] - before[0]) + " of " + saidIn);
                assertTrue(after[1] - before[1] <= 2, "and no longer through the server: " + (after[1] - before[1]));
                assertTrue(efe.audio.speaker.size() >= saidIn * 0.9 - 2, "efe still hears emir: " + efe.audio.speaker.size() + " of " + saidIn);
            } finally {
                talking.set(false);
                voices.join();
            }
        }
    }

    @Test
    void byDefaultAnUnverifiedFriendIsNeverCalledDirectly() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe")) {
            befriend(emir, efe, "efe", "emir");
            efe.client.calls().directAllowed(u -> true); // efe would; emir keeps the default (verified only)
            java.util.concurrent.atomic.AtomicBoolean talking = new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread voices = connectTalking(emir, efe, talking);
            try {
                assertFalse(awaitDirect(emir, 4000), "emir has not verified efe: no direct path");
                assertFalse(efe.client.calls().direct(), "nor from efe's side: emir never answers or probes");
                assertTrue(emir.client.calls().packetsSent()[0] == 0, "emir sent nothing directly");
            } finally {
                talking.set(false);
                voices.join();
            }
        }
    }

    @Test
    void withoutADirectPathTheCallUsesTheUdpRelayAndShowsNoAddress() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe")) {
            befriend(emir, efe, "efe", "emir");
            emir.client.calls().directAllowed(u -> false);
            efe.client.calls().directAllowed(u -> false);
            java.util.concurrent.atomic.AtomicBoolean talking = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.concurrent.atomic.AtomicInteger said = new java.util.concurrent.atomic.AtomicInteger();
            Thread voices = connectTalking(emir, efe, talking, said);
            try {
                long until = System.currentTimeMillis() + 10_000;
                while (!(emir.client.calls().udpRelayed() && efe.client.calls().udpRelayed()) && System.currentTimeMillis() < until) {
                    Thread.sleep(50);
                }
                assertTrue(emir.client.calls().udpRelayed() && efe.client.calls().udpRelayed(), "both on the UDP relay");
                assertFalse(emir.client.calls().direct(), "which is not a direct call");
                long[] before = emir.client.calls().packetsSent();
                int saidBefore = said.get();
                efe.audio.speaker.clear();
                long deadline = System.currentTimeMillis() + 8000;
                while (said.get() - saidBefore < 30 && System.currentTimeMillis() < deadline) Thread.sleep(50);
                Thread.sleep(200);
                long[] after = emir.client.calls().packetsSent();
                int saidIn = said.get() - saidBefore;
                assertTrue(saidIn >= 15, "the test spoke: " + saidIn);
                assertEquals(0, after[0] - before[0], "nothing went directly");
                assertTrue(after[2] - before[2] >= saidIn * 0.9 - 2, "audio goes over the UDP relay: " + (after[2] - before[2]));
                assertTrue(after[1] - before[1] <= 2, "not over TCP: " + (after[1] - before[1]));
                assertTrue(efe.audio.speaker.size() >= saidIn * 0.9 - 2, "efe hears emir: " + efe.audio.speaker.size());
                assertTrue(server.metrics().render().matches("(?s).*oba_udp_relayed_packets_total [1-9].*"), "the server counted it");
            } finally {
                talking.set(false);
                voices.join();
            }
        }
    }

    @Test
    void rejectedAndBusyCalls() throws Exception {
        try (Device emir = new Device("emir"); Device efe = new Device("efe"); Device ayse = new Device("ayse")) {
            befriend(emir, efe, "efe", "emir");
            befriend(ayse, efe, "efe", "ayse");

            emir.client.calls().call("efe");
            efe.await(c -> c.state() == Calls.State.RINGING);
            efe.client.calls().reject();
            assertEquals(Calls.EndReason.REJECTED, emir.await(c -> c.state() == Calls.State.ENDED).reason());
            efe.await(c -> c.state() == Calls.State.ENDED);

            emir.client.calls().call("efe");
            efe.await(c -> c.state() == Calls.State.RINGING);
            efe.client.calls().accept();
            emir.await(c -> c.state() == Calls.State.CONNECTED);

            ayse.client.calls().call("efe");
            assertEquals(Calls.EndReason.BUSY, ayse.await(c -> c.state() == Calls.State.ENDED).reason());
            assertEquals(Calls.EndReason.MISSED, efe.await(c -> c.state() == Calls.State.ENDED).reason(),
                    "efe learns about the missed call");
            assertEquals(Calls.State.CONNECTED, efe.client.calls().current().state(), "the first call goes on");
        }
    }

    @Test
    void theServerOnlyRelaysAudioBetweenFriends() throws Exception {
        try (Device emir = new Device("emir"); Device stranger = new Device("stranger")) {
            BlockingQueue<Object> got = new LinkedBlockingQueue<>();
            emir.client.connection().onPush(p -> {
                if (p instanceof com.emirchat.api.Packet.CallAudioIn) got.add(p);
            });
            stranger.client.connection().sendAudio("emir", new byte[64]);
            assertNull(got.poll(1, TimeUnit.SECONDS), "not friends: dropped");
        }
    }

    private static void awaitCodec(Device d, String codec) throws InterruptedException {
        for (int i = 0; i < 100 && !codec.equals(d.client.calls().sendingCodec()); i++) Thread.sleep(50);
        assertEquals(codec, d.client.calls().sendingCodec());
    }

    /**
     * True if what was heard is what was said: the same level (within a quarter) and, at the best
     * delay up to 10 ms (codecs and filters delay sound a little), nearly the same shape. The
     * first 100 ms are skipped: a codec needs a moment to settle.
     */
    static boolean similar(byte[] said, byte[] heard) {
        int skip = (int) (AudioDevice.SAMPLE_RATE / 10);
        int maxLag = (int) (AudioDevice.SAMPLE_RATE / 100);
        short[] a = samples(said);
        short[] b = samples(heard);
        int n = Math.min(a.length, b.length) - maxLag;
        double best = -1;
        for (int lag = 0; lag <= maxLag; lag++) {
            double ab = 0, aa = 0, bb = 0;
            for (int i = skip; i < n; i++) {
                ab += a[i] * (double) b[i + lag];
                aa += a[i] * (double) a[i];
                bb += b[i + lag] * (double) b[i + lag];
            }
            if (aa > 0 && bb > 0) best = Math.max(best, ab / Math.sqrt(aa * bb));
        }
        double la = 0, lb = 0;
        for (int i = skip; i < n; i++) {
            la += Math.abs(a[i]);
            lb += Math.abs(b[i]);
        }
        return best > 0.9 && lb > 0.75 * la && lb < 1.25 * la;
    }

    private static short[] samples(byte[] pcm) {
        short[] out = new short[pcm.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
        return out;
    }
}
