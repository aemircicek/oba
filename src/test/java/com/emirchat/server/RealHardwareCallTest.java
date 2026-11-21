package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.call.AudioDevice;
import com.emirchat.call.Calls;
import com.emirchat.call.SoundDevice;
import com.emirchat.client.ChatClient;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import com.emirchat.trust.KnownPeers;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * A call through the computer's real microphone and speaker, not run by {@code mvn test}:
 * {@code mvn test -Dtest=RealHardwareCallTest -Doba.hardware=true} (turn the volume up, keep the
 * room quiet). Efe sends a 700 Hz tone; Emir's real speaker plays it, Emir's real microphone
 * picks it up again and the call carries it back to Efe. Efe hearing 700 Hz means the sound went
 * through both real devices and through Opus both ways.
 */
@EnabledIfSystemProperty(named = "oba.hardware", matches = "true")
class RealHardwareCallTest {
    private static final String PASSWORD = "correct horse battery";
    private static final double TONE = 700;

    @TempDir Path dir;
    private ChatServer server;

    private final class Device implements AutoCloseable {
        final ChatClient client;
        final BlockingQueue<Calls.Call> events = new LinkedBlockingQueue<>();

        Device(String user, AudioDevice audio) throws Exception {
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

        @Override public void close() { client.close(); }
    }

    @BeforeEach
    void start() throws Exception {
        server = new ChatServer(new ChatServer.Config(dir.resolve("server"), "127.0.0.1", 0, 1_000)).start();
    }

    @AfterEach
    void stop() { server.close(); }

    @Test
    void aToneGoesOutOfTheSpeakerAndBackInThroughTheMicrophone() throws Exception {
        CallIntegrationTest.FakeAudio efeAudio = new CallIntegrationTest.FakeAudio();
        try (Device emir = new Device("emir", new SoundDevice()); Device efe = new Device("efe", efeAudio)) {
            emir.client.connection().sendFriendRequest("efe");
            efe.client.connection().respondFriendRequest("emir", true);
            emir.client.calls().call("efe");
            efe.await(c -> c.state() == Calls.State.RINGING);
            efe.client.calls().accept();
            emir.await(c -> c.state() == Calls.State.CONNECTED);
            efe.await(c -> c.state() == Calls.State.CONNECTED);

            // three seconds of tone, at real time so the speaker's queue never overflows
            int frames = 150;
            int perFrame = AudioDevice.FRAME_BYTES / 2;
            long t0 = System.nanoTime();
            java.io.ByteArrayOutputStream back = new java.io.ByteArrayOutputStream();
            for (int f = 0; f < frames; f++) {
                efeAudio.say(tone(f * perFrame));
                byte[] got;
                while ((got = efeAudio.speaker.poll()) != null) back.writeBytes(got);
                long due = t0 + (f + 1) * 20_000_000L;
                long wait = due - System.nanoTime();
                if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            }
            // the microphone never stops, so collect for a fixed half second more
            long end = System.currentTimeMillis() + 500;
            byte[] got;
            while (System.currentTimeMillis() < end) {
                if ((got = efeAudio.speaker.poll(50, TimeUnit.MILLISECONDS)) != null) back.writeBytes(got);
            }
            assertEquals("opus", efe.client.calls().sendingCodec(), "the call moved to Opus");

            short[] heard = samples(back.toByteArray());
            assertTrue(heard.length > AudioDevice.SAMPLE_RATE, "efe received at least a second of audio: " + heard.length);
            // skip the first half second (the speaker and the codec start up), then compare the
            // tone's energy with that of neighbouring pitches
            int from = (int) (AudioDevice.SAMPLE_RATE / 2);
            double atTone = goertzel(heard, from, TONE);
            double around = (goertzel(heard, from, 450) + goertzel(heard, from, 1100) + goertzel(heard, from, 1600)) / 3;
            int loudest = 0;
            for (int i = from; i < heard.length; i++) loudest = Math.max(loudest, Math.abs(heard[i]));
            System.out.printf("real hardware: %d samples back, loudest %d, 700 Hz power %.3g vs %.3g around (x%.1f), codec %s%n",
                    heard.length, loudest, atTone, around, atTone / Math.max(around, 1e-9), emir.client.calls().sendingCodec());
            assertTrue(loudest > 50, "the microphone heard something (loudest sample " + loudest
                    + "; is microphone access allowed for the terminal?)");
            assertTrue(atTone > 10 * around, "the 700 Hz tone came back through speaker and microphone");
            efe.client.calls().hangUp();
            emir.await(c -> c.state() == Calls.State.ENDED);
        }
    }

    private static byte[] tone(int seed) {
        byte[] pcm = new byte[AudioDevice.FRAME_BYTES];
        for (int i = 0; i < pcm.length / 2; i++) {
            short s = (short) (Math.sin(2 * Math.PI * TONE * (i + seed) / AudioDevice.SAMPLE_RATE) * 12000);
            pcm[2 * i] = (byte) s;
            pcm[2 * i + 1] = (byte) (s >> 8);
        }
        return pcm;
    }

    /** Power of one frequency in the samples from {@code from} on. */
    private static double goertzel(short[] x, int from, double freq) {
        double w = 2 * Math.PI * freq / AudioDevice.SAMPLE_RATE;
        double coeff = 2 * Math.cos(w);
        double s1 = 0, s2 = 0;
        for (int i = from; i < x.length; i++) {
            double s = x[i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s;
        }
        int n = Math.max(1, x.length - from);
        return (s1 * s1 + s2 * s2 - coeff * s1 * s2) / ((double) n * n);
    }

    private static short[] samples(byte[] pcm) {
        short[] out = new short[pcm.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
        return out;
    }
}
