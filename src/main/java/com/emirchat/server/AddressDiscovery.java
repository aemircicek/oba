package com.emirchat.server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Tells a client the address it is seen from on the internet, so two clients can try to reach
 * each other directly in a call (like STUN, RFC 8489, but smaller). It answers on UDP, on the
 * same port number as the server's TCP port.
 *
 * <pre>
 *   request = "OBA?" ‖ 16 random bytes ‖ padding, exactly 64 bytes
 *   answer  = "OBA!" ‖ the same 16 bytes ‖ u8 family (4 or 6) ‖ u16 port ‖ address (4 or 16 bytes)
 * </pre>
 *
 * An answer is never larger than its request (at most 39 bytes for 64), and each address gets a
 * few answers a second at most, so the service cannot be used to flood someone else with traffic
 * by faking their address. It learns nothing a TCP connection does not already show.
 */
final class AddressDiscovery implements AutoCloseable {
    static final int REQUEST_SIZE = 64;
    static final byte[] ASK = {'O', 'B', 'A', '?'};
    static final byte[] TELL = {'O', 'B', 'A', '!'};
    /** Answers a second for one address (a client asks a handful of times per call). */
    static final int PER_SECOND = 10;

    private final DatagramSocket socket;
    private final Thread thread;
    /** Call packets relayed over this same port (3.6), or null. */
    private final UdpRelay relay;
    /**
     * Answers given this second, counted in a fixed table indexed by a keyed hash of the address
     * (IPv6 by /64). Its size never changes, whatever arrives; addresses that share a slot share
     * its limit, and as the key is secret, nobody can aim at someone else's slot. Faked source
     * addresses can only fill the slots they land in, never shut the service for everyone.
     */
    static final int SLOTS = 1 << 16;
    private final int[] thisSecond = new int[SLOTS];
    private final long salt = new java.security.SecureRandom().nextLong();
    private long second;

    AddressDiscovery(String bindAddress, int port) throws SocketException {
        this(bindAddress, port, null);
    }

    AddressDiscovery(String bindAddress, int port, UdpRelay relay) throws SocketException {
        this.relay = relay;
        socket = new DatagramSocket(new InetSocketAddress(bindAddress, port));
        thread = Thread.ofPlatform().daemon().name("address-discovery").start(this::serve);
    }

    int port() {
        return socket.getLocalPort();
    }

    private void serve() {
        byte[] buffer = new byte[2048]; // relayed call packets are at most 1200 bytes of data
        while (!socket.isClosed()) {
            DatagramPacket in = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(in);
                if (relay != null && UdpRelay.isRelay(in.getData(), in.getLength())) {
                    byte k = in.getData()[3];
                    boolean answerable = k == '?' || k == 'H' || k == 'K'; // data is never answered, only forwarded
                    UdpRelay.Out out = relay.handle(in.getData(), in.getLength(), (InetSocketAddress) in.getSocketAddress(),
                            answerable && allowed(in.getAddress()));
                    if (out != null) socket.send(new DatagramPacket(out.data(), out.data().length, out.to()));
                    continue;
                }
                byte[] answer = answer(in.getData(), in.getLength(), in.getAddress(), in.getPort());
                if (answer != null && allowed(in.getAddress())) {
                    socket.send(new DatagramPacket(answer, answer.length, in.getSocketAddress()));
                }
            } catch (IOException e) {
                if (socket.isClosed()) return;
            }
        }
    }

    /** @return the answer to a datagram, or null if it is not a well-formed request */
    static byte[] answer(byte[] data, int length, InetAddress from, int port) {
        if (length != REQUEST_SIZE || !Arrays.equals(Arrays.copyOf(data, 4), ASK)) return null;
        byte[] address = from.getAddress();
        return ByteBuffer.allocate(4 + 16 + 1 + 2 + address.length).put(TELL).put(data, 4, 16)
                .put((byte) (address.length == 4 ? 4 : 6)).putShort((short) port).put(address).array();
    }

    synchronized boolean allowed(InetAddress from) {
        long now = System.currentTimeMillis() / 1000;
        if (now != second) { // counts are per second: the past is simply dropped
            second = now;
            java.util.Arrays.fill(thisSecond, 0);
        }
        int slot = slot(Addresses.key(from));
        if (thisSecond[slot] >= PER_SECOND) return false;
        thisSecond[slot]++;
        return true;
    }

    /** SplitMix64 over the address key and the secret salt: an even spread nobody can predict. */
    private int slot(String key) {
        long z = key.hashCode() * 0x9E3779B97F4A7C15L ^ salt;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (int) (z & (SLOTS - 1));
    }

    @Override
    public void close() {
        socket.close();
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
