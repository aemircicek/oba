package com.emirchat.server;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Relays a call's packets over UDP when the two computers cannot reach each other directly (like
 * TURN, RFC 8656, but smaller). It works on the same UDP port as {@link AddressDiscovery}:
 *
 * <pre>
 *   "OBR?" ‖ 16 bytes              → "OBR!" ‖ the same 16 bytes      does this server relay? (old ones stay silent)
 *   "OBRH" ‖ ticket                → "OBRC" ‖ cookie                  hello: a cookie for the address it came from
 *   "OBRK" ‖ ticket ‖ cookie       → "OBRO" ‖ ticket                  that address is really the ticket holder's
 *   "OBRD" ‖ ticket ‖ data         → (to the friend) "OBRF" ‖ data    a call packet
 * </pre>
 *
 * A ticket (16 random bytes) is handed out over the encrypted TCP connection, only to a user for a
 * friend ({@code OpenRelay}), and names the pair. Data from a ticket goes only to the friend's
 * ticket, and only to an address that answered a cookie: someone who fakes the source address of
 * a datagram never sees the cookie, so cannot make the relay send to a computer that did not ask.
 * No answer is larger than its request, each ticket is limited in packets and bytes a second, and
 * the data is the call's own end-to-end encrypted packets: the relay cannot read them.
 */
final class UdpRelay {
    static final byte[] ASK = {'O', 'B', 'R', '?'};
    static final byte[] YES = {'O', 'B', 'R', '!'};
    static final byte[] HELLO = {'O', 'B', 'R', 'H'};
    static final byte[] COOKIE = {'O', 'B', 'R', 'C'};
    static final byte[] CONFIRM = {'O', 'B', 'R', 'K'};
    static final byte[] CONFIRMED = {'O', 'B', 'R', 'O'};
    static final byte[] DATA = {'O', 'B', 'R', 'D'};
    static final byte[] FORWARD = {'O', 'B', 'R', 'F'};
    static final int TICKET = 16;
    static final int MAX_DATA = 1200;
    /** A direct call's rate (audio and up to 200 video packets) with room to spare. */
    static final int PACKETS_PER_SECOND = 300;
    static final int BYTES_PER_SECOND = 400_000;
    /** Tickets one user may hold (one per call; a few to cover a call being set up again). */
    static final int PER_USER = 4;
    /** A ticket nobody used for this long is dropped. */
    static final long IDLE_MS = 120_000;
    /** However busy, a ticket ends after this long (a call needs a new one; friendship is checked again then). */
    static final long MAX_LIFETIME_MS = 6 * 60 * 60_000L;

    /** Where to send what: the answer, or the forwarded packet. */
    record Out(byte[] data, InetSocketAddress to) {}

    private static final class Ticket {
        final byte[] bytes;
        final long user;
        final long peer;
        final long created;
        volatile InetSocketAddress address;
        volatile long lastSeen;
        long windowStart;
        int packets;
        long bytesInWindow;

        Ticket(byte[] bytes, long user, long peer, long now) {
            this.bytes = bytes;
            this.user = user;
            this.peer = peer;
            this.created = now;
            this.lastSeen = now;
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final byte[] secret = new byte[32];
    private final Map<String, Ticket> byTicket = new HashMap<>();
    /** "user→peer": the newest ticket of that direction. */
    private final Map<String, Ticket> byPair = new HashMap<>();
    private final ServerMetrics metrics;

    UdpRelay(ServerMetrics metrics) {
        this.metrics = metrics;
        random.nextBytes(secret);
    }

    /** A new ticket for {@code user} to reach {@code peer} (the caller has checked they are friends). */
    synchronized byte[] open(long user, long peer) {
        long now = System.currentTimeMillis();
        sweep(now);
        List<Ticket> mine = new ArrayList<>();
        for (Ticket t : byTicket.values()) if (t.user == user) mine.add(t);
        mine.sort((a, b) -> Long.compare(a.created, b.created));
        while (mine.size() >= PER_USER) remove(mine.remove(0));
        byte[] bytes = new byte[TICKET];
        random.nextBytes(bytes);
        Ticket t = new Ticket(bytes, user, peer, now);
        Ticket old = byPair.put(user + ">" + peer, t);
        if (old != null) byTicket.remove(key(old.bytes));
        byTicket.put(key(bytes), t);
        return bytes.clone();
    }

    /** The user is gone (their last connection closed): their tickets go too. */
    synchronized void forget(long user) {
        for (Ticket t : new ArrayList<>(byTicket.values())) if (t.user == user) remove(t);
    }

    /** Two people are no longer friends: neither may relay to the other any more. */
    synchronized void forgetPair(long a, long b) {
        for (Ticket t : new ArrayList<>(byTicket.values())) {
            if ((t.user == a && t.peer == b) || (t.user == b && t.peer == a)) remove(t);
        }
    }

    /** Calls relayed right now (tickets with a confirmed address on both sides, counted once). */
    synchronized int sessions() {
        int n = 0;
        for (Ticket t : byTicket.values()) {
            Ticket other = byPair.get(t.peer + ">" + t.user);
            if (t.address != null && other != null && other.address != null && t.user < t.peer) n++;
        }
        return n;
    }

    /** True if a datagram belongs to the relay (it starts with "OBR"). */
    static boolean isRelay(byte[] d, int length) {
        return length >= 4 && d[0] == 'O' && d[1] == 'B' && d[2] == 'R';
    }

    /**
     * Handles one datagram that came from {@code from}.
     *
     * @param answerAllowed whether the address may get an answer now (the address service's limit)
     * @return what to send, or null
     */
    Out handle(byte[] d, int length, InetSocketAddress from, boolean answerAllowed) {
        if (length < 4) return null;
        byte[] kind = Arrays.copyOf(d, 4);
        if (Arrays.equals(kind, ASK)) {
            if (length != 4 + 16 || !answerAllowed) return null;
            return new Out(concat(YES, Arrays.copyOfRange(d, 4, 20)), from);
        }
        if (length < 4 + TICKET) return null;
        byte[] ticketBytes = Arrays.copyOfRange(d, 4, 4 + TICKET);
        long now = System.currentTimeMillis();
        if (Arrays.equals(kind, DATA)) return data(ticketBytes, d, length, from, now);
        Ticket t;
        synchronized (this) {
            t = byTicket.get(key(ticketBytes));
        }
        if (t == null) {
            metrics.udpDropped.increment();
            return null;
        }
        if (Arrays.equals(kind, HELLO)) {
            if (length != 4 + TICKET || !answerAllowed) return null;
            return new Out(concat(COOKIE, cookie(ticketBytes, from)), from);
        }
        if (Arrays.equals(kind, CONFIRM)) {
            if (length != 4 + TICKET + 16 || !answerAllowed) return null;
            byte[] given = Arrays.copyOfRange(d, 4 + TICKET, 4 + TICKET + 16);
            if (!MessageDigest.isEqual(given, cookie(ticketBytes, from))) {
                metrics.udpDropped.increment();
                return null;
            }
            t.address = from;
            t.lastSeen = now;
            return new Out(concat(CONFIRMED, ticketBytes), from);
        }
        return null;
    }

    private Out data(byte[] ticketBytes, byte[] d, int length, InetSocketAddress from, long now) {
        int size = length - 4 - TICKET;
        Ticket t;
        Ticket other;
        synchronized (this) {
            t = byTicket.get(key(ticketBytes));
            if (t != null && now - t.created > MAX_LIFETIME_MS) {
                remove(t);
                t = null;
            }
            if (t == null || !from.equals(t.address) || size <= 0 || size > MAX_DATA) {
                metrics.udpDropped.increment();
                return null;
            }
            if (now - t.windowStart >= 1000) {
                t.windowStart = now;
                t.packets = 0;
                t.bytesInWindow = 0;
            }
            if (++t.packets > PACKETS_PER_SECOND || (t.bytesInWindow += size) > BYTES_PER_SECOND) {
                metrics.udpDropped.increment();
                return null;
            }
            t.lastSeen = now;
            other = byPair.get(t.peer + ">" + t.user);
        }
        InetSocketAddress to = other == null ? null : other.address;
        if (to == null) return null; // the friend has not reached the relay (yet)
        metrics.udpRelayed.increment();
        metrics.udpRelayedBytes.add(size);
        return new Out(concat(FORWARD, Arrays.copyOfRange(d, 4 + TICKET, length)), to);
    }

    /** Proof that a datagram's source address can receive: only the address itself ever sees it. */
    byte[] cookie(byte[] ticket, InetSocketAddress address) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(ticket);
            mac.update(address.getAddress().getAddress());
            mac.update(ByteBuffer.allocate(4).putInt(address.getPort()).array());
            return Arrays.copyOf(mac.doFinal(), 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private void sweep(long now) {
        for (Ticket t : new ArrayList<>(byTicket.values())) {
            if (now - t.lastSeen > IDLE_MS || now - t.created > MAX_LIFETIME_MS) remove(t);
        }
    }

    synchronized void sweep() {
        sweep(System.currentTimeMillis());
    }

    private void remove(Ticket t) {
        byTicket.remove(key(t.bytes));
        byPair.remove(t.user + ">" + t.peer, t);
    }

    private static String key(byte[] ticket) {
        return java.util.HexFormat.of().formatHex(ticket);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
