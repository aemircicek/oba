package com.emirchat.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DirectPathTest {
    @Test
    void candidatesRoundTripAndNothingDangerousIsAimedAt() throws Exception {
        List<InetSocketAddress> list = List.of(new InetSocketAddress("192.168.1.20", 50000),
                new InetSocketAddress("2001:db8::7", 50001), new InetSocketAddress("203.0.113.9", 61000));
        assertEquals(list, DirectPath.decodeCandidates(DirectPath.encodeCandidates(list)));
        byte[] steering = DirectPath.encodeCandidates(List.of(new InetSocketAddress("224.0.0.1", 5000),
                new InetSocketAddress("0.0.0.0", 5000), new InetSocketAddress("127.0.0.1", 5000),
                new InetSocketAddress("169.254.1.1", 5000), new InetSocketAddress("192.168.1.255", 5000),
                new InetSocketAddress("10.0.0.3", 53), new InetSocketAddress("10.0.0.2", 50007)));
        assertEquals(List.of(new InetSocketAddress("10.0.0.2", 50007)), DirectPath.decodeCandidates(steering),
                "multicast, any, loopback, link-local, broadcast and system ports are dropped");
        assertEquals(2, DirectPath.decodeCandidates(steering, true).size(), "loopback only with a server on this computer");
        assertNull(DirectPath.decodeCandidates(new byte[] {DirectPath.CANDIDATES, 99}), "more than allowed");
        assertNull(DirectPath.decodeCandidates(new byte[] {DirectPath.CANDIDATES, 1, 4, 0}), "truncated");
        for (InetAddress a : DirectPath.localAddresses()) {
            assertFalse(a.isLoopbackAddress() || a.isLinkLocalAddress(), a.toString());
        }
    }

    /** A stand-in for the server's address service, on loopback. */
    private static DatagramSocket fakeDiscovery() throws Exception {
        DatagramSocket s = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().start(() -> {
            byte[] buf = new byte[100];
            while (!s.isClosed()) {
                try {
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    s.receive(in);
                    byte[] a = in.getAddress().getAddress();
                    byte[] out = ByteBuffer.allocate(23 + a.length).put(new byte[] {'O', 'B', 'A', '!'}).put(buf, 4, 16)
                            .put((byte) (a.length == 4 ? 4 : 6)).putShort((short) in.getPort()).put(a).array();
                    s.send(new DatagramPacket(out, out.length, in.getSocketAddress()));
                } catch (Exception e) {
                    return;
                }
            }
        });
        return s;
    }

    private record Side(DirectPath path, BlockingQueue<byte[]> media, BlockingQueue<Boolean> changes) {}

    @Test
    void twoSidesFindEachOtherCarryMediaAndNoticeWhenThePathDies() throws Exception {
        byte[] id = new byte[CallCrypto.CALL_ID_LENGTH];
        new SecureRandom().nextBytes(id);
        KeyPair ka = CallCrypto.newCallKey();
        KeyPair kb = CallCrypto.newCallKey();
        byte[] pa = com.emirchat.crypto.X25519.encode(ka.getPublic());
        byte[] pb = com.emirchat.crypto.X25519.encode(kb.getPublic());
        try (DatagramSocket discovery = fakeDiscovery()) {
            InetSocketAddress server = new InetSocketAddress(InetAddress.getLoopbackAddress(), discovery.getLocalPort());
            Side[] sides = new Side[2];
            // the relay between them, in memory
            DirectPath.Relay toB = sealed -> sides[1].path().receiveRelayed(sealed);
            DirectPath.Relay toA = sealed -> sides[0].path().receiveRelayed(sealed);
            for (int i = 0; i < 2; i++) {
                boolean caller = i == 0;
                KeyPair mine = caller ? ka : kb;
                byte[] theirs = caller ? pb : pa;
                BlockingQueue<byte[]> media = new LinkedBlockingQueue<>();
                BlockingQueue<Boolean> changes = new LinkedBlockingQueue<>();
                sides[i] = new Side(new DirectPath(id, CallCrypto.deriveDirectSignal(id, mine, theirs, caller),
                        CallCrypto.deriveDirectControl(id, mine, theirs, caller), server, caller ? toB : toA,
                        media::add, changes::add), media, changes);
            }
            sides[0].path().start();
            sides[1].path().start();
            assertEquals(Boolean.TRUE, sides[0].changes().poll(10, TimeUnit.SECONDS), "the caller found a way");
            assertEquals(Boolean.TRUE, sides[1].changes().poll(10, TimeUnit.SECONDS), "and so did the callee");

            byte[] packet = new byte[id.length + 5];
            System.arraycopy(id, 0, packet, 0, id.length);
            assertTrue(sides[0].path().send(packet));
            assertNotNull(sides[1].media().poll(2, TimeUnit.SECONDS), "media goes straight across");

            sides[1].path().close(); // the other computer is gone (sleep, a lost network)
            assertEquals(Boolean.FALSE, sides[0].changes().poll(DirectPath.LOST_AFTER_MS + 3000, TimeUnit.MILLISECONDS),
                    "the caller notices and goes back to the relay");
            assertFalse(sides[0].path().send(packet), "and sends nothing directly any more");
            sides[0].path().close();
        }
    }

    @Test
    void probesFromAStrangerOpenNothing() throws Exception {
        byte[] id = new byte[CallCrypto.CALL_ID_LENGTH];
        KeyPair a = CallCrypto.newCallKey();
        KeyPair b = CallCrypto.newCallKey();
        KeyPair mallory = CallCrypto.newCallKey();
        byte[] pb = com.emirchat.crypto.X25519.encode(b.getPublic());
        BlockingQueue<Boolean> changes = new LinkedBlockingQueue<>();
        DirectPath path = new DirectPath(id, CallCrypto.deriveDirectSignal(id, a, pb, true),
                CallCrypto.deriveDirectControl(id, a, pb, true), null, sealed -> {}, p -> {}, changes::add);
        path.start();
        // a stranger who knows the call id but not the keys
        CallCrypto forged = CallCrypto.deriveDirectSignal(id, mallory, pb, false);
        assertTrue(!path.receiveRelayed(forged.seal(DirectPath.encodeCandidates(
                List.of(new InetSocketAddress("203.0.113.66", 4444))))), "candidates under other keys are not ours");
        assertNull(changes.poll(1, TimeUnit.SECONDS));
        assertFalse(path.active());
        path.close();
    }
}
