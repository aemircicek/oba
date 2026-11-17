package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AddressDiscoveryTest {
    private static byte[] request(byte fill) {
        byte[] r = new byte[AddressDiscovery.REQUEST_SIZE];
        System.arraycopy(AddressDiscovery.ASK, 0, r, 0, 4);
        Arrays.fill(r, 4, 20, fill);
        return r;
    }

    @Test
    void theAnswerIsTheAddressSeenAndNeverLargerThanTheQuestion() throws Exception {
        byte[] v4 = AddressDiscovery.answer(request((byte) 7), 64, InetAddress.getByName("203.0.113.9"), 51234);
        assertNotNull(v4);
        ByteBuffer b = ByteBuffer.wrap(v4);
        byte[] magic = new byte[4];
        b.get(magic);
        assertArrayEquals(AddressDiscovery.TELL, magic);
        byte[] id = new byte[16];
        b.get(id);
        assertArrayEquals(Arrays.copyOfRange(request((byte) 7), 4, 20), id, "the asker's id comes back");
        assertEquals(4, b.get());
        assertEquals(51234, b.getShort() & 0xffff);
        byte[] addr = new byte[4];
        b.get(addr);
        assertEquals("203.0.113.9", InetAddress.getByAddress(addr).getHostAddress());
        byte[] v6 = AddressDiscovery.answer(request((byte) 1), 64, InetAddress.getByName("2001:db8::5"), 1);
        assertTrue(v6.length < AddressDiscovery.REQUEST_SIZE, "even for IPv6: " + v6.length + " bytes");
    }

    @Test
    void anythingButAWellFormedQuestionIsIgnored() throws Exception {
        InetAddress a = InetAddress.getByName("203.0.113.9");
        assertNull(AddressDiscovery.answer(request((byte) 0), 63, a, 1), "too short: a 39-byte answer would amplify");
        assertNull(AddressDiscovery.answer(new byte[64], 64, a, 1), "wrong magic");
        byte[] longer = Arrays.copyOf(request((byte) 0), 65);
        assertNull(AddressDiscovery.answer(longer, 65, a, 1));
    }

    @Test
    void aFloodOfFakeAddressesDoesNotShutOutARealOne() throws Exception {
        try (AddressDiscovery service = new AddressDiscovery("127.0.0.1", 0)) {
            for (int attempt = 0; attempt < 3; attempt++) { // the flood and the asking must fall in the same second
                long second = System.currentTimeMillis() / 1000;
                for (int n = 0; n < 100_000; n++) { // faked sources, each once
                    service.allowed(InetAddress.getByAddress(new byte[] {10, (byte) (n >> 16), (byte) (n >> 8), (byte) n}));
                }
                // a real address that arrives only now, in the middle of the flood
                InetAddress real = InetAddress.getByAddress(new byte[] {(byte) 198, 51, 100, (byte) attempt});
                int refused = 0;
                for (int i = 0; i < 5; i++) if (!service.allowed(real)) refused++;
                if (System.currentTimeMillis() / 1000 != second) continue;
                // it shares its slot with about 1.5 faked sources (100,000 / 65,536), not with all of them
                assertEquals(0, refused, "the real address was refused " + refused + " times of 5");
                return;
            }
            throw new AssertionError("the machine is too slow to test this within a second");
        }
    }

    @Test
    void aRealServerAnswersOnItsPortAndLimitsEachAddress() throws Exception {
        try (AddressDiscovery service = new AddressDiscovery("127.0.0.1", 0);
             DatagramSocket client = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            client.setSoTimeout(1000);
            byte[] ask = request((byte) 3);
            client.send(new DatagramPacket(ask, ask.length, InetAddress.getLoopbackAddress(), service.port()));
            byte[] buf = new byte[100];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            client.receive(in);
            ByteBuffer b = ByteBuffer.wrap(buf, 21, 2);
            assertEquals(client.getLocalPort(), b.getShort() & 0xffff, "it saw our port");

            int answered = 0;
            for (int i = 0; i < 40; i++) client.send(new DatagramPacket(ask, ask.length, InetAddress.getLoopbackAddress(), service.port()));
            try {
                while (true) {
                    client.receive(new DatagramPacket(buf, buf.length));
                    answered++;
                }
            } catch (SocketTimeoutException done) {
                // no more
            }
            assertTrue(answered <= 2 * AddressDiscovery.PER_SECOND, "a flood gets only a few answers: " + answered);
        }
    }
}
