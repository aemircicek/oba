package com.emirchat.call;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.net.InetSocketAddress;
import java.util.List;

/** The other side's candidate list: never more than the limit, never an address it must not point us at. */
class DirectPathFuzzTest {
    @FuzzTest(maxDuration = "1m")
    void candidates(byte[] body) {
        List<InetSocketAddress> list = DirectPath.decodeCandidates(body);
        if (list == null) return;
        if (list.size() > DirectPath.MAX_CANDIDATES) throw new AssertionError("too many");
        for (InetSocketAddress a : list) {
            if (DirectPath.unfit(a.getAddress(), a.getPort(), false)) throw new AssertionError("unfit address kept: " + a);
        }
    }
}
