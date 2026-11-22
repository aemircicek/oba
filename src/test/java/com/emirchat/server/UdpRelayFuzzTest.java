package com.emirchat.server;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/** Datagrams from anyone on the internet: the relay never answers bigger, nor to an address that did not prove itself. */
class UdpRelayFuzzTest {
    @FuzzTest(maxDuration = "2m")
    void datagrams(FuzzedDataProvider in) throws Exception {
        UdpRelay relay = new UdpRelay(new ServerMetrics());
        byte[] ticket = relay.open(1, 2);
        relay.open(2, 1);
        InetSocketAddress from = new InetSocketAddress(InetAddress.getByAddress(new byte[] {10, 0, 0, (byte) in.consumeInt(1, 3)}),
                in.consumeInt(1024, 65535));
        int n = in.consumeInt(1, 20);
        for (int i = 0; i < n && in.remainingBytes() > 0; i++) {
            byte[] d = in.consumeBoolean() ? withTicket(ticket, in.consumeBytes(200)) : in.consumeBytes(1300);
            UdpRelay.Out out = relay.handle(d, d.length, from, in.consumeBoolean());
            if (out == null) continue;
            boolean forwarded = out.data().length >= 4 && out.data()[3] == 'F';
            if (!forwarded && out.data().length > d.length) throw new AssertionError("answer larger than the request");
            if (!forwarded && !out.to().equals(from)) throw new AssertionError("answered someone else");
        }
        byte[] d = in.consumeRemainingAsBytes();
        AddressDiscovery.answer(d, d.length, from.getAddress(), from.getPort());
    }

    private static byte[] withTicket(byte[] ticket, byte[] rest) {
        byte[] d = new byte[4 + ticket.length + rest.length];
        d[0] = 'O';
        d[1] = 'B';
        d[2] = 'R';
        d[3] = rest.length > 0 ? rest[0] : (byte) 'D';
        System.arraycopy(ticket, 0, d, 4, ticket.length);
        System.arraycopy(rest, 0, d, 4 + ticket.length, rest.length);
        return d;
    }
}
