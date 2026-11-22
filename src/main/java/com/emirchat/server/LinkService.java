package com.emirchat.server;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Linking a new computer to an account (3.6). The new computer proves the password (and the
 * two-step code), then waits; the account's devices are asked. One of them offers its one-time key;
 * only then does the new computer reveal the key it had committed to, so both can show a code made
 * from both keys, which the user compares. The approving device then seals the account's keys to
 * the new computer. The server only passes things on: it cannot read the sealed keys, approve on
 * its own, or pick keys that would give matching codes.
 */
final class LinkService {
    /** A request nobody answered is dropped after this long. */
    static final long WAIT_MS = 5 * 60_000;
    static final int MAX_PENDING_PER_USER = 3;
    /** Devices one account may have. */
    static final int MAX_DEVICES = Packet.MAX_DEVICES;

    private static final class Pending {
        final long id;
        final long userId;
        final String name;
        final byte[] commitment;
        final ClientConnection waiting;
        final long since;
        ClientConnection offerer;
        byte[] offered;
        boolean revealed;

        Pending(long id, long userId, String name, byte[] commitment, ClientConnection waiting, long since) {
            this.id = id;
            this.userId = userId;
            this.name = name;
            this.commitment = commitment;
            this.waiting = waiting;
            this.since = since;
        }

        long userId() { return userId; }

        long since() { return since; }

        String name() { return name; }

        ClientConnection waiting() { return waiting; }
    }

    private final Database db;
    private final Presence presence;
    private final SecureRandom random = new SecureRandom();
    private final Map<Long, Pending> pending = new HashMap<>();

    LinkService(Database db, Presence presence) {
        this.db = db;
        this.presence = presence;
    }

    /** A new computer asks; its password (and code) were checked. Asks the account's 3.6 devices. */
    long request(Database.User user, String name, byte[] commitment, ClientConnection waiting) throws ApiException {
        long now = System.currentTimeMillis();
        boolean asked = false;
        long id;
        synchronized (this) {
            pending.values().removeIf(p -> now - p.since() > WAIT_MS);
            if (pending.values().stream().filter(p -> p.userId() == user.id()).count() >= MAX_PENDING_PER_USER) {
                throw new ApiException(ErrorCode.RATE_LIMITED, "Too many computers are waiting to join. Try again in a few minutes.");
            }
            try {
                if (db.devices(user.id()).size() >= MAX_DEVICES) {
                    throw new ApiException(ErrorCode.LIMIT_REACHED, "This account has " + MAX_DEVICES + " computers already.");
                }
            } catch (SQLException e) {
                throw new ApiException(ErrorCode.INTERNAL, "Server storage error.");
            }
            do {
                id = random.nextLong() & Long.MAX_VALUE;
            } while (id == 0 || pending.containsKey(id));
            pending.put(id, new Pending(id, user.id(), name, commitment.clone(), waiting, now));
        }
        for (ClientConnection c : presence.connections(user.id())) {
            if (c.multiDevice()) {
                c.push(new Packet.LinkRequest(id, name, commitment));
                asked = true;
            }
        }
        if (!asked) {
            synchronized (this) {
                pending.remove(id);
            }
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Open Oba (3.6 or newer) on a computer already signed in to this account, then try again: it has to approve.");
        }
        return id;
    }

    /** One of the account's devices offers its one-time key (the first to offer handles the request). */
    void offer(Database.User user, long linkId, byte[] ephemeral, ClientConnection from) throws ApiException {
        Pending p;
        synchronized (this) {
            p = pending.get(linkId);
            if (p == null || p.userId != user.id() || p.offerer != null || System.currentTimeMillis() - p.since > WAIT_MS) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "That computer is no longer waiting.");
            }
            p.offerer = from;
            p.offered = ephemeral.clone();
        }
        p.waiting.push(new Packet.LinkOffered(linkId, ephemeral));
    }

    /** The waiting computer reveals the key it committed to; the offering device checks the commitment. */
    void reveal(long linkId, byte[] ephemeral, ClientConnection from) throws ApiException {
        Pending p;
        synchronized (this) {
            p = pending.get(linkId);
            if (p == null || p.waiting != from || p.offerer == null || p.revealed) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Not expected now.");
            }
            p.revealed = true;
        }
        p.offerer.push(new Packet.LinkRevealed(linkId, p.name, ephemeral));
    }

    /** The offering device approves: a new device id, and the sealed keys go to the waiting computer. */
    void approve(Database.User approver, long linkId, byte[] ephemeral, byte[] sealed) throws ApiException {
        Pending p = take(approver, linkId);
        if (!p.revealed || !java.util.Arrays.equals(p.offered, ephemeral)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Compare the codes first.");
        }
        int deviceId;
        try {
            java.util.Set<Integer> used = new java.util.HashSet<>();
            for (Database.Device d : db.devices(approver.id())) used.add(d.id());
            deviceId = 1;
            while (used.contains(deviceId)) deviceId++;
        } catch (SQLException e) {
            throw new ApiException(ErrorCode.INTERNAL, "Server storage error.");
        }
        byte[] secret = new byte[Packet.DEVICE_SECRET_BYTES];
        random.nextBytes(secret);
        try {
            db.addDevice(approver.id(), deviceId, p.name(), System.currentTimeMillis());
            db.setDeviceSecret(approver.id(), deviceId, hash(secret));
        } catch (SQLException e) {
            throw new ApiException(ErrorCode.INTERNAL, "Server storage error.");
        }
        p.waiting().push(new Packet.LinkGranted(deviceId, ephemeral, sealed, secret));
    }

    void deny(Database.User user, long linkId) throws ApiException {
        Pending p = take(user, linkId);
        p.waiting().push(new Packet.LinkGranted(-1, new byte[0], new byte[0], new byte[0]));
    }

    /** Device secrets are kept as SHA-256: the database alone does not let anyone pose as a device. */
    static byte[] hash(byte[] secret) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(secret);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private synchronized Pending take(Database.User user, long linkId) throws ApiException {
        Pending p = pending.get(linkId);
        if (p == null || p.userId() != user.id() || System.currentTimeMillis() - p.since() > WAIT_MS) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "That computer is no longer waiting.");
        }
        pending.remove(linkId);
        return p;
    }
}
