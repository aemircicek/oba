package com.emirchat.api;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/** The wire format: what every request and push goes through. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class PacketBench {
    private final Packet envelope = new Packet.SendEnvelope("efe", new byte[400]);
    private final byte[] encoded = Packet.encode(42, envelope);

    @Benchmark
    public byte[] encodeEnvelope() {
        return Packet.encode(42, envelope);
    }

    @Benchmark
    public Packet.Frame decodeEnvelope() throws Exception {
        return Packet.decode(encoded);
    }
}
