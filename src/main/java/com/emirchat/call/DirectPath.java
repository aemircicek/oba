package com.emirchat.call;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A direct UDP path between the two computers of a call, tried next to the relay through the
 * server, in the manner of ICE (RFC 8445) but much smaller:
 *
 * <ol>
 *   <li>Each side collects its candidates: its own addresses, and the address the server's
 *       {@code AddressDiscovery} sees it from (the outside of its router).
 *   <li>It sends them to the other side through the relay, under the call's "direct" signalling
 *       keys, which an older version cannot open.
 *   <li>Both sides send authenticated probes to every candidate of the other. The first probe that
 *       is answered opens the path; probes from both sides at once also open most home routers
 *       ("hole punching").
 *   <li>Audio and video then go over the path in the very packets that would have gone to the
 *       relay. If nothing comes over it for a few seconds the call goes back to the relay and
 *       keeps trying.
 * </ol>
 *
 * Only a side whose user allows direct calls with this person creates a path at all, so a side
 * that does not never sends its addresses and never probes: its address stays hidden.
 */
public final class DirectPath implements AutoCloseable {
    // first byte of every datagram on the path
    static final byte MEDIA = 1;
    static final byte CONTROL = 2;
    // control bodies (sealed)
    static final byte CANDIDATES = 1;
    static final byte PROBE = 2;
    static final byte PROBE_ACK = 3;
    static final byte ALIVE = 4;

    static final int MAX_CANDIDATES = 8;
    /** Their addresses we keep at most: their list, and a few more seen on probes from outside. */
    static final int MAX_THEIRS = MAX_CANDIDATES + 4;
    /** Candidate lists we take from them per call (they send three; a few more after a change). */
    static final int MAX_CANDIDATE_UPDATES = 6;
    /** Probes we send per call at most, to every address together. */
    static final int MAX_PROBES = 1500;
    static final long PROBE_EVERY_MS = 100;
    /** Probing goes on this long after the last news about candidates. */
    static final long PROBE_FOR_MS = 10_000;
    static final long ALIVE_EVERY_MS = 1000;
    /** Nothing heard over the path for this long: back to the relay. */
    static final long LOST_AFTER_MS = 3500;
    /** After a loss, try the known candidates again this often. */
    static final long RETRY_EVERY_MS = 15_000;

    /** Sends a sealed signalling body through the relay (the caller adds the call id). */
    public interface Relay {
        void send(byte[] sealed);
    }

    private final byte[] callId;
    private final CallCrypto signalling; // candidates, through the relay
    private final CallCrypto control;    // probes and keep-alives, over the path
    private final InetSocketAddress server;
    private final Relay relay;
    private final Consumer<byte[]> media;
    private final Consumer<Boolean> changed;
    private final SecureRandom random = new SecureRandom();

    private DatagramSocket socket;
    private final Set<InetSocketAddress> mine = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<InetSocketAddress> theirs = Collections.synchronizedSet(new LinkedHashSet<>());
    private volatile InetSocketAddress selected;
    private volatile long heardAt;
    private volatile long probeUntil;
    private volatile boolean closed;
    private final byte[] discoveryId = new byte[16];
    private int candidateUpdates;
    private int probesSent;
    private volatile InetSocketAddress reflexive;

    /**
     * @param server  where the server's address service listens (its host, its port), or null
     * @param media   receives media packets that came over the path (call id ‖ sealed)
     * @param changed told true when the path opens and false when it is lost
     */
    DirectPath(byte[] callId, CallCrypto signalling, CallCrypto control, InetSocketAddress server, Relay relay,
               Consumer<byte[]> media, Consumer<Boolean> changed) {
        this.callId = callId.clone();
        this.signalling = signalling;
        this.control = control;
        this.server = server;
        this.relay = relay;
        this.media = media;
        this.changed = changed;
    }

    /** Opens the socket and starts working in the background. */
    void start() {
        try {
            socket = new DatagramSocket(0);
        } catch (SocketException e) {
            return; // no UDP here: the relay it is
        }
        random.nextBytes(discoveryId);
        Thread.ofPlatform().daemon().name("direct-receive").start(this::receiveLoop);
        Thread.ofPlatform().daemon().name("direct-work").start(this::work);
    }

    /** True while media goes over the path. */
    public boolean active() {
        return selected != null;
    }

    /**
     * Sends a media packet over the path if it is open.
     *
     * @return false if it is not (send it through the relay)
     */
    boolean send(byte[] packet) {
        InetSocketAddress to = selected;
        if (to == null || closed) return false;
        byte[] datagram = new byte[packet.length + 1];
        datagram[0] = MEDIA;
        System.arraycopy(packet, 0, datagram, 1, packet.length);
        return transmit(datagram, to);
    }

    /**
     * A packet from the relay that did not open as audio or video: if it opens under the
     * signalling keys, it carries the other side's candidates.
     *
     * @return true if it was ours
     */
    boolean receiveRelayed(byte[] packet) {
        byte[] body = signalling.open(packet);
        if (body == null) return false;
        if (body.length >= 2 && body[0] == CANDIDATES) {
            List<InetSocketAddress> list = decodeCandidates(body, loopbackAllowed());
            synchronized (theirs) {
                if (list != null && candidateUpdates < MAX_CANDIDATE_UPDATES) {
                    candidateUpdates++;
                    for (InetSocketAddress a : list) if (theirs.size() < MAX_THEIRS) theirs.add(a);
                    probeUntil = System.currentTimeMillis() + PROBE_FOR_MS;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ background work

    private void work() {
        try {
            gather();
            long sentCandidates = 0;
            int timesSent = 0;
            long lastAlive = 0;
            long lastRetry = System.currentTimeMillis();
            while (!closed) {
                long now = System.currentTimeMillis();
                // our candidates: at once, then twice more (the relay may drop a packet)
                if (timesSent < 3 && now - sentCandidates >= (timesSent == 0 ? 0 : 2000L * timesSent)) {
                    sendCandidates();
                    sentCandidates = now;
                    timesSent++;
                }
                InetSocketAddress path = selected;
                if (path == null && now < probeUntil) {
                    synchronized (theirs) {
                        for (InetSocketAddress to : theirs) {
                            if (probesSent >= MAX_PROBES) break; // the other side may be steering us: stop
                            sendControl(PROBE, to);
                            probesSent++;
                        }
                    }
                }
                if (path != null && now - lastAlive >= ALIVE_EVERY_MS) {
                    sendControl(ALIVE, path);
                    lastAlive = now;
                }
                if (path != null && now - heardAt > LOST_AFTER_MS) {
                    selected = null; // lost: back to the relay
                    changed.accept(false);
                    lastRetry = now;
                }
                if (path == null && !theirs.isEmpty() && now >= probeUntil && now - lastRetry > RETRY_EVERY_MS) {
                    probeUntil = now + PROBE_FOR_MS; // the network may have changed: try again
                    lastRetry = now;
                }
                Thread.sleep(PROBE_EVERY_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Our own addresses, and the outside address the server sees us from. */
    private void gather() throws InterruptedException {
        int port = socket.getLocalPort();
        for (InetAddress a : localAddresses()) mine.add(new InetSocketAddress(a, port));
        if (server == null) return;
        byte[] ask = ByteBuffer.allocate(64).put(new byte[] {'O', 'B', 'A', '?'}).put(discoveryId).array();
        for (int attempt = 0; attempt < 4 && reflexive == null && !closed; attempt++) {
            transmit(ask, server);
            Thread.sleep(150L * (attempt + 1));
        }
        if (reflexive != null) mine.add(reflexive);
    }

    /** Addresses of this computer that another one might reach: no loopback, no link-local. */
    static List<InetAddress> localAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isPointToPoint()) continue;
                for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()) continue;
                    out.add(a instanceof Inet6Address v6 ? withoutScope(v6) : a);
                }
            }
        } catch (SocketException e) {
            // none, then
        }
        out.sort((x, y) -> Boolean.compare(x instanceof Inet6Address, y instanceof Inet6Address)); // IPv4 first
        return out.size() > MAX_CANDIDATES - 1 ? out.subList(0, MAX_CANDIDATES - 1) : out;
    }

    private static InetAddress withoutScope(Inet6Address a) {
        try {
            return InetAddress.getByAddress(a.getAddress());
        } catch (UnknownHostException e) {
            return a;
        }
    }

    private void sendCandidates() {
        List<InetSocketAddress> list;
        synchronized (mine) {
            list = new ArrayList<>(mine);
        }
        if (list.isEmpty()) return;
        relay.send(signalling.seal(encodeCandidates(list)));
    }

    static byte[] encodeCandidates(List<InetSocketAddress> list) {
        ByteBuffer b = ByteBuffer.allocate(2 + MAX_CANDIDATES * 19);
        int n = Math.min(MAX_CANDIDATES, list.size());
        b.put(CANDIDATES).put((byte) n);
        for (int i = 0; i < n; i++) {
            byte[] a = list.get(i).getAddress().getAddress();
            b.put((byte) (a.length == 4 ? 4 : 6)).putShort((short) list.get(i).getPort()).put(a);
        }
        return Arrays.copyOf(b.array(), b.position());
    }

    /**
     * Loopback candidates are taken only when the server itself is on this computer (development
     * and tests); otherwise the other side could make us probe our own computer's services.
     */
    private boolean loopbackAllowed() {
        return server != null && server.getAddress().isLoopbackAddress();
    }

    /** @return the candidates, or null if the body is malformed */
    static List<InetSocketAddress> decodeCandidates(byte[] body) {
        return decodeCandidates(body, false);
    }

    /**
     * True for an address the other side has no business pointing us at: this computer, link-local,
     * broadcast and multicast, "any", and the low ports of system services (NAT mappings never use them).
     */
    static boolean unfit(InetAddress a, int port, boolean loopbackAllowed) {
        if (port < 1024 || a.isAnyLocalAddress() || a.isMulticastAddress() || a.isLinkLocalAddress()) return true;
        if (a.isLoopbackAddress()) return !loopbackAllowed;
        byte[] b = a.getAddress();
        return b.length == 4 && (b[3] & 0xff) == 255; // 255.255.255.255 and the usual subnet broadcasts
    }

    /** @return the candidates, or null if the body is malformed */
    static List<InetSocketAddress> decodeCandidates(byte[] body, boolean loopbackAllowed) {
        try {
            ByteBuffer b = ByteBuffer.wrap(body);
            if (b.get() != CANDIDATES) return null;
            int n = b.get() & 0xff;
            if (n > MAX_CANDIDATES) return null;
            List<InetSocketAddress> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int family = b.get();
                int port = b.getShort() & 0xffff;
                byte[] a = new byte[family == 4 ? 4 : family == 6 ? 16 : -1];
                b.get(a);
                InetAddress addr = InetAddress.getByAddress(a);
                if (unfit(addr, port, loopbackAllowed)) continue; // never aim at these
                out.add(new InetSocketAddress(addr, port));
            }
            return b.hasRemaining() ? null : out;
        } catch (RuntimeException | UnknownHostException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ the socket

    private void sendControl(byte kind, InetSocketAddress to) {
        sendControl(new byte[] {kind}, to);
    }

    private void sendControl(byte[] body, InetSocketAddress to) {
        byte[] sealed = control.seal(body);
        byte[] datagram = ByteBuffer.allocate(1 + callId.length + sealed.length).put(CONTROL).put(callId).put(sealed).array();
        transmit(datagram, to);
    }

    private boolean transmit(byte[] datagram, InetSocketAddress to) {
        try {
            socket.send(new DatagramPacket(datagram, datagram.length, to));
            return true;
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            return false; // unreachable family, closed socket...
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[2048];
        while (!closed) {
            DatagramPacket in = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(in);
            } catch (IOException e) {
                if (closed || socket.isClosed()) return;
                continue;
            }
            byte[] d = Arrays.copyOf(in.getData(), in.getLength());
            InetSocketAddress from = (InetSocketAddress) in.getSocketAddress();
            try {
                handle(d, from);
            } catch (RuntimeException ignored) {
                // a malformed datagram is dropped
            }
        }
    }

    private void handle(byte[] d, InetSocketAddress from) {
        if (d.length >= 4 && d[0] == 'O' && d[1] == 'B' && d[2] == 'A' && d[3] == '!') {
            if (server != null && from.getAddress().equals(server.getAddress())) reflexive = parseDiscovery(d);
            return;
        }
        if (d.length < 1 + callId.length || !Arrays.equals(Arrays.copyOfRange(d, 1, 1 + callId.length), callId)) return;
        byte[] rest = Arrays.copyOfRange(d, 1 + callId.length, d.length);
        if (d[0] == MEDIA) {
            // checked by the call's own keys: a forgery is dropped there. It does not count as a
            // sign of life, which only the authenticated keep-alives give.
            media.accept(Arrays.copyOfRange(d, 1, d.length));
            return;
        }
        if (d[0] != CONTROL) return;
        byte[] body = control.open(rest);
        if (body == null || body.length == 0) return; // not from the other side of this call
        heardAt = System.currentTimeMillis();
        switch (body[0]) {
            case PROBE -> {
                sendControl(PROBE_ACK, from); // the path from them to us works: say so on the way back
                synchronized (theirs) { // seen from outside: their router's mapping towards us
                    if (theirs.size() < MAX_THEIRS && theirs.add(from)) probeUntil = System.currentTimeMillis() + PROBE_FOR_MS;
                }
            }
            case PROBE_ACK -> {
                if (selected == null) {
                    selected = from;
                    changed.accept(true);
                }
            }
            default -> { } // ALIVE, or something newer
        }
    }

    private InetSocketAddress parseDiscovery(byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d);
        b.position(4);
        byte[] id = new byte[16];
        b.get(id);
        if (!Arrays.equals(id, discoveryId)) return null;
        int family = b.get();
        int port = b.getShort() & 0xffff;
        byte[] a = new byte[family == 4 ? 4 : 16];
        b.get(a);
        try {
            return new InetSocketAddress(InetAddress.getByAddress(a), port);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    @Override
    public void close() {
        closed = true;
        boolean was = selected != null;
        selected = null;
        if (socket != null) socket.close();
        if (was) changed.accept(false);
    }
}
