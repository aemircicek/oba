package com.emirchat.bench;

import com.emirchat.call.OpusCodec;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Opus for 20 ms of speech: it has 20 ms to finish, for every person in a room. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class VoiceBench {
    private OpusCodec.Encoder encoder;
    private OpusCodec.Decoder decoder;
    private byte[] pcm;
    private byte[] packet;

    @Setup
    public void setUp() {
        encoder = new OpusCodec.Encoder();
        decoder = new OpusCodec.Decoder();
        pcm = new byte[OpusCodec.FRAME_SAMPLES * 2];
        for (int i = 0; i < OpusCodec.FRAME_SAMPLES; i++) {
            short s = (short) (8000 * Math.sin(2 * Math.PI * 440 * i / OpusCodec.SAMPLE_RATE));
            pcm[2 * i] = (byte) s;
            pcm[2 * i + 1] = (byte) (s >> 8);
        }
        packet = encoder.encode(pcm);
    }

    @Benchmark
    public byte[] encode() {
        return encoder.encode(pcm);
    }

    @Benchmark
    public byte[] decode() {
        return decoder.decode(packet);
    }
}
