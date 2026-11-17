package com.emirchat.call;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusBandwidth;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusSignal;

/**
 * Opus (RFC 6716) for calls and voice rooms: wideband speech, 16 kHz mono in 20 ms frames, at
 * 24 kbit/s with in-band error correction. Twice the audio bandwidth of μ-law at about a third
 * of the data. Through Concentus, a pure Java port of the reference library.
 */
public final class OpusCodec {
    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME_SAMPLES = SAMPLE_RATE / 50;
    static final int BITRATE = 24_000;
    /** Far above what 20 ms at {@link #BITRATE} takes (about 60 bytes), and under the relay's packet limit. */
    static final int MAX_PACKET = 400;

    private OpusCodec() {}

    /** One sender's encoder (keeps state between frames: one per stream, used from one thread). */
    public static final class Encoder {
        private final OpusEncoder encoder;
        private final byte[] buffer = new byte[MAX_PACKET];

        public Encoder() {
            try {
                encoder = new OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            } catch (OpusException e) {
                throw new IllegalStateException("Opus encoder unavailable", e);
            }
            encoder.setBitrate(BITRATE);
            encoder.setBandwidth(OpusBandwidth.OPUS_BANDWIDTH_WIDEBAND);
            encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
            encoder.setComplexity(5);            // pure Java: a middle setting keeps a call under a few % of a core
            encoder.setUseInbandFEC(true);       // each packet carries a rough copy of the one before
            encoder.setPacketLossPercent(10);
        }

        /** @param pcm one 20 ms frame: {@value #FRAME_SAMPLES} samples, 16-bit little-endian */
        public byte[] encode(byte[] pcm) {
            if (pcm.length != FRAME_SAMPLES * 2) throw new IllegalArgumentException("Opus frames are 20 ms");
            try {
                int n = encoder.encode(Pcm.samples(pcm), 0, FRAME_SAMPLES, buffer, 0, buffer.length);
                return java.util.Arrays.copyOf(buffer, n);
            } catch (OpusException e) {
                throw new IllegalStateException("Opus could not encode a frame", e);
            }
        }
    }

    /** One sender's decoder (keeps state between frames: one per sender). */
    public static final class Decoder {
        private final OpusDecoder decoder;
        private final short[] samples = new short[FRAME_SAMPLES * 6]; // the longest frame Opus allows is 120 ms

        public Decoder() {
            try {
                decoder = new OpusDecoder(SAMPLE_RATE, 1);
            } catch (OpusException e) {
                throw new IllegalStateException("Opus decoder unavailable", e);
            }
        }

        /** @return 16-bit little-endian samples, or null for a packet that is not valid Opus */
        public byte[] decode(byte[] packet) {
            if (packet.length == 0 || packet.length > MAX_PACKET) return null;
            try {
                int n = decoder.decode(packet, 0, packet.length, samples, 0, samples.length, false);
                return n > 0 ? Pcm.bytes(samples, n) : null;
            } catch (OpusException | RuntimeException e) {
                return null; // a damaged or hostile packet is a moment of silence, never a crash
            }
        }
    }
}
