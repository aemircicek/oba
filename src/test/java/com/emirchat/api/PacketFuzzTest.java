package com.emirchat.api;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.net.ProtocolException;

/** Whatever a client or server sends, decoding either refuses it cleanly or gives a packet that encodes again. */
class PacketFuzzTest {
    @FuzzTest(maxDuration = "2m")
    void decode(byte[] data) {
        Packet.Frame frame;
        try {
            frame = Packet.decode(data);
        } catch (ProtocolException e) {
            return; // refused: the connection is closed
        }
        byte[] again = Packet.encode(frame.requestId(), frame.packet());
        try {
            Packet.Frame second = Packet.decode(again);
            if (second.requestId() != frame.requestId() || second.packet().getClass() != frame.packet().getClass()) {
                throw new AssertionError("does not survive a round trip: " + frame.packet());
            }
        } catch (ProtocolException e) {
            throw new AssertionError("re-encoded packet refused: " + frame.packet(), e);
        }
    }
}
