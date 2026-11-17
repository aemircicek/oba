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

        void say(byte[] frame) { microphone.accept(frame); }
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
