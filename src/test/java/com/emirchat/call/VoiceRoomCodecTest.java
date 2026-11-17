package com.emirchat.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * A voice room speaks Opus only while everyone in it can play Opus: one person on a version
 * before Opus turns the room to μ-law, and when they go, the room goes back to Opus.
 */
class VoiceRoomCodecTest {
    private static final long ROOM = 7;
    private static final byte AUDIO = 0, OPUS = 2, CAN_OPUS = 3;

    private static final class FakeDevice implements AudioDevice {
        volatile Consumer<byte[]> mic;
        final BlockingQueue<byte[]> speaker = new LinkedBlockingQueue<>();

        @Override public void start(Consumer<byte[]> captured) { mic = captured; }

        @Override public void play(byte[] frame) { speaker.add(frame); }

        @Override public void stop() { mic = null; }
    }

    /** The network as emir's room sees it: what it sends is kept here. */
    private static final class FakeNet implements VoiceChannels.Transport {
        final List<byte[]> sent = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        List<String> people;

        @Override public List<String> join(long groupId) { return people; }

        @Override public void leave(long groupId) {}

        @Override public synchronized void audio(long groupId, byte[] packet) { sent.add(packet); }

        @Override public synchronized void sendKey(long groupId, String encodedKey) { keys.add(encodedKey); }

        synchronized byte[] last() { return sent.get(sent.size() - 1); }

        synchronized VoiceCrypto.Key key() { return VoiceCrypto.decodeKey(keys.get(keys.size() - 1)); }
    }

    /** What emir's next frame of speech looks like to someone holding emir's current key. */
    private static byte[] nextFrame(VoiceChannels emir, FakeDevice device, FakeNet net) {
        device.mic.accept(OpusTest.tone(300, (int) AudioDevice.SAMPLE_RATE, 0, 9000));
        VoiceCrypto.Receiver listener = new VoiceCrypto.Receiver();
        listener.accept("emir", net.key());
        return listener.open(ROOM, "emir", net.last());
    }

    /** Someone else in the room, as emir receives them. */
    private static VoiceCrypto.Sender member(VoiceChannels emir, String name) {
        VoiceCrypto.Key key = VoiceCrypto.newKey();
        emir.acceptKey(name, ROOM, VoiceCrypto.encodeKey(key));
        return new VoiceCrypto.Sender(key);
    }

    @Test
    void theRoomSpeaksTheLanguageEveryoneInItUnderstands() throws Exception {
        FakeNet net = new FakeNet();
        FakeDevice device = new FakeDevice();
        net.people = List.of("emir", "efe", "eski");
        try (VoiceChannels emir = new VoiceChannels("emir", net, () -> device, () -> false, s -> {})) {
            emir.join(ROOM);
            VoiceCrypto.Sender efe = member(emir, "efe");    // a current version
            VoiceCrypto.Sender eski = member(emir, "eski");  // a version before Opus: never says it can

            byte[] canNote = null;
            for (byte[] p : List.copyOf(net.sent)) {
                VoiceCrypto.Receiver r = new VoiceCrypto.Receiver();
                r.accept("emir", net.key());
                byte[] plain = r.open(ROOM, "emir", p);
                if (plain.length == 1 && plain[0] == CAN_OPUS) canNote = plain;
            }
            assertNotNull(canNote, "on joining, emir says at once that it plays Opus");

            byte[] first = nextFrame(emir, device, net);
            assertEquals(AUDIO, first[0], "before anyone has said anything: μ-law");
            assertEquals(1 + 160, first.length, "20 ms at 8 kHz, as versions before Opus expect");

            emir.audio(ROOM, "efe", efe.seal(ROOM, "efe", new byte[] {CAN_OPUS}));
            assertEquals(AUDIO, nextFrame(emir, device, net)[0], "eski cannot play Opus yet: still μ-law");
            assertFalse(emir.sendingOpus());

            emir.roomChanged(ROOM, List.of("emir", "efe")); // eski leaves
            byte[] opus = nextFrame(emir, device, net);
            assertEquals(OPUS, opus[0], "only people who play Opus are left: Opus");
            assertTrue(opus.length < 1 + 120, "and much smaller: " + opus.length + " bytes");

            emir.roomChanged(ROOM, List.of("emir", "efe", "eski")); // eski comes back
            assertEquals(AUDIO, nextFrame(emir, device, net)[0], "back to μ-law at once");

            // and emir plays what both send, at its own rate
            eski = member(emir, "eski");
            emir.audio(ROOM, "eski", eski.seal(ROOM, "eski",
                    concat(AUDIO, Mulaw.encode(OpusTest.tone(300, 8000, 0, 9000)))));
            byte[] heard = device.speaker.poll(2, TimeUnit.SECONDS);
            assertNotNull(heard, "eski's μ-law is played");
            assertEquals(AudioDevice.FRAME_BYTES, heard.length);
            OpusCodec.Encoder encoder = new OpusCodec.Encoder();
            emir.audio(ROOM, "efe", efe.seal(ROOM, "efe",
                    concat(OPUS, encoder.encode(OpusTest.tone(300, (int) AudioDevice.SAMPLE_RATE, 0, 9000)))));
            assertNotNull(device.speaker.poll(2, TimeUnit.SECONDS), "efe's Opus is played");
        }
    }

    private static byte[] concat(byte kind, byte[] body) {
        byte[] out = new byte[body.length + 1];
        out[0] = kind;
        System.arraycopy(body, 0, out, 1, body.length);
        return out;
    }
}
