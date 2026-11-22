package com.emirchat.video;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

/** Video chunks from the other side of a call: reassembly never grows without bound or breaks. */
class VideoPacketsFuzzTest {
    @FuzzTest(maxDuration = "1m")
    void assemble(FuzzedDataProvider in) {
        VideoPackets.Assembler a = new VideoPackets.Assembler();
        VideoRate.Loss loss = new VideoRate.Loss();
        int n = in.consumeInt(0, 200);
        for (int i = 0; i < n && in.remainingBytes() > 0; i++) {
            byte[] chunk = in.consumeBytes(in.consumeInt(0, 1100));
            for (VideoPackets.Frame f : a.add(chunk)) {
                if (f.data().length > VideoPackets.MAX_CHUNKS * VideoPackets.CHUNK_DATA) throw new AssertionError("frame too large");
            }
            if (a.waiting() > 5) throw new AssertionError("waiting frames grow: " + a.waiting());
            int[] info = VideoPackets.chunkInfo(chunk);
            if (info != null) loss.chunk(info[0], info[1]);
            VideoPackets.reportedLoss(chunk);
        }
        int l = loss.take();
        if (l < -1 || l > 1000) throw new AssertionError("loss out of range: " + l);
    }
}
