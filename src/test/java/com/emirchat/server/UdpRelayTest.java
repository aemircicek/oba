package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The server's UDP relay: who it answers, whom it forwards to, and what it refuses. */
class UdpRelayTest {
    private final UdpRelay relay = new UdpRelay(new ServerMetrics());
    private final InetSocketAddress emirAt = at("203.0.113.5", 40000);
    private final InetSocketAddress efeAt = at("198.51.100.7", 50000);
    private final InetSocketAddress victim = at("192.0.2.99", 12345);

    private static InetSocketAddress at(String ip, int port) {
        try {
            return new InetSocketAddress(InetAddress.getByName(ip), port);
        } catch (java.net.UnknownHostException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] msg(byte[] kind, byte[]... parts) {
        ByteBuffer b = ByteBuffer.allocate(4 + Arrays.stream(parts).mapToInt(p -> p.length).sum()).put(kind);
        for (byte[] p : parts) b.put(p);
        return b.array();
    }

    private UdpRelay.Out send(byte[] d, InetSocketAddress from) {
        return relay.handle(d, d.length, from, true);
    }

    /** Hello, cookie, confirm: the ticket's address is proven. */
    private void confirm(byte[] ticket, InetSocketAddress from) {
        UdpRelay.Out cookie = send(msg(UdpRelay.HELLO, ticket), from);
        assertNotNull(cookie);
        assertEquals(from, cookie.to());
        byte[] c = Arrays.copyOfRange(cookie.data(), 4, 20);
        UdpRelay.Out ok = send(msg(UdpRelay.CONFIRM, ticket, c), from);
        assertNotNull(ok);
        assertArrayEquals(msg(UdpRelay.CONFIRMED, ticket), ok.data());
    }

    @Test
    void answersTheQuestionWithoutGrowing() {
        byte[] ask = msg(UdpRelay.ASK, new byte[16]);
        UdpRelay.Out out = send(ask, emirAt);
        assertNotNull(out);
        assertTrue(out.data().length <= ask.length, "never larger than the request");
        assertNull(relay.handle(ask, ask.length, emirAt, false), "over the address's limit: silent");
    }

    @Test
    void forwardsBetweenFriendsOnceBothAddressesAreProven() {
        byte[] emir = relay.open(1, 2);
        byte[] efe = relay.open(2, 1);
        byte[] payload = "sealed call packet".getBytes();
        assertNull(send(msg(UdpRelay.DATA, emir, payload), emirAt), "not confirmed yet");
        confirm(emir, emirAt);
        assertNull(send(msg(UdpRelay.DATA, emir, payload), emirAt), "efe has not reached the relay");
        confirm(efe, efeAt);
        UdpRelay.Out out = send(msg(UdpRelay.DATA, emir, payload), emirAt);
        assertNotNull(out);
        assertEquals(efeAt, out.to());
        assertArrayEquals(msg(UdpRelay.FORWARD, payload), out.data());
        assertEquals(emirAt, send(msg(UdpRelay.DATA, efe, payload), efeAt).to());
        assertEquals(1, relay.sessions());
    }

    @Test
    void aFakedSourceCannotTakeOverATicket() {
        byte[] emir = relay.open(1, 2);
        byte[] efe = relay.open(2, 1);
        confirm(emir, emirAt);
        confirm(efe, efeAt);
        // someone who knows emir's ticket fakes the victim's address: the cookie goes to the victim, never to them
        UdpRelay.Out cookie = send(msg(UdpRelay.HELLO, emir), victim);
        assertEquals(victim, cookie.to());
        byte[] guessed = new byte[16];
        assertNull(send(msg(UdpRelay.CONFIRM, emir, guessed), victim));
        assertNull(send(msg(UdpRelay.CONFIRM, emir, relay.cookie(emir, emirAt)), victim), "a cookie is for one address");
        // data claiming to come from the victim is dropped, and nothing is ever sent towards it
        assertNull(send(msg(UdpRelay.DATA, emir, new byte[10]), victim));
        assertEquals(efeAt, send(msg(UdpRelay.DATA, emir, new byte[10]), emirAt).to(), "emir's own address still works");
    }

    @Test
    void unknownTicketsSizesAndFloodsAreRefused() {
        byte[] emir = relay.open(1, 2);
        byte[] efe = relay.open(2, 1);
        confirm(emir, emirAt);
        confirm(efe, efeAt);
        assertNull(send(msg(UdpRelay.DATA, new byte[16], new byte[10]), emirAt), "unknown ticket");
        assertNull(send(msg(UdpRelay.DATA, emir, new byte[UdpRelay.MAX_DATA + 1]), emirAt), "too large");
        assertNull(send(msg(UdpRelay.DATA, emir), emirAt), "empty");
        int forwarded = 0;
        for (int i = 0; i < UdpRelay.PACKETS_PER_SECOND + 50; i++) {
            if (send(msg(UdpRelay.DATA, emir, new byte[100]), emirAt) != null) forwarded++;
        }
        assertTrue(forwarded <= UdpRelay.PACKETS_PER_SECOND, "limited to " + forwarded);
    }

    @Test
    void aNewTicketReplacesTheOldAndLeavingEndsThem() {
        byte[] first = relay.open(1, 2);
        byte[] second = relay.open(1, 2);
        assertNull(send(msg(UdpRelay.HELLO, first), emirAt), "replaced");
        assertNotNull(send(msg(UdpRelay.HELLO, second), emirAt));
        for (int i = 0; i < 10; i++) relay.open(1, 100 + i);
        relay.forget(1);
        assertNull(send(msg(UdpRelay.HELLO, second), emirAt), "gone with the user");
    }

    @Test
    void unfriendingEndsTheRelayBetweenThem() {
        byte[] emir = relay.open(1, 2);
        byte[] efe = relay.open(2, 1);
        confirm(emir, emirAt);
        confirm(efe, efeAt);
        relay.forgetPair(2, 1);
        assertNull(send(msg(UdpRelay.DATA, emir, new byte[10]), emirAt));
        assertNull(send(msg(UdpRelay.HELLO, efe), efeAt));
    }
}
