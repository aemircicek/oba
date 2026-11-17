package com.emirchat.server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

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
    private final Map<InetAddress, int[]> recent = new HashMap<>(); // address -> {second, count}

    AddressDiscovery(String bindAddress, int port) throws SocketException {
        socket = new DatagramSocket(new InetSocketAddress(bindAddress, port));
        thread = Thread.ofPlatform().daemon().name("address-discovery").start(this::serve);
    }

    int port() {
        return socket.getLocalPort();
    }

    private void serve() {
        byte[] buffer = new byte[REQUEST_SIZE + 1]; // one more: a longer datagram is not a request
        while (!socket.isClosed()) {
            DatagramPacket in = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(in);
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

    private synchronized boolean allowed(InetAddress from) {
        int second = (int) (System.currentTimeMillis() / 1000);
        if (recent.size() > 50_000) recent.values().removeIf(r -> r[0] != second); // forget the past
        int[] r = recent.computeIfAbsent(from, k -> new int[] {second, 0});
        if (r[0] != second) {
            r[0] = second;
            r[1] = 0;
        }
        return ++r[1] <= PER_SECOND;
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
