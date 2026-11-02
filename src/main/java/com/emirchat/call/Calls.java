package com.emirchat.call;

import com.emirchat.crypto.X25519;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * One-to-one voice calls between friends: at most one call at a time.
 *
 * <p>Signalling (offer, answer, reject, hang up, busy) travels as ordinary end-to-end encrypted
 * messages, so a call offer is authenticated by the same keys as a chat message, and carries a
 * fresh call key. Audio goes through the server as {@link CallCrypto} packets that it cannot read
 * and does not store.
 */
public final class Calls implements AutoCloseable {
    public static final int OFFER = 1, ANSWER = 2, REJECT = 3, HANGUP = 4, BUSY = 5;
    /** An offer older than this (delivered late, say after a restart) is a missed call, not a ringing one. */
    public static final long OFFER_VALID_MS = 60_000;
    static final long RING_TIMEOUT_MS = 45_000;

    public enum State { CALLING, RINGING, CONNECTED, ENDED }

    public enum EndReason { HUNG_UP, REJECTED, BUSY, NO_ANSWER, MISSED, FAILED }

    /** What calls need from the messenger. Implementations may block (they run on a calls thread). */
    public interface Transport {
        /** Sends a signal end-to-end encrypted to a friend. */
        void signal(String peer, int kind, byte[] callId, byte[] callKey) throws Exception;

        /** Sends an audio packet through the server, without waiting. */
        void audio(String peer, byte[] data);
    }

    /** Runs on a calls thread. */
    public interface Listener {
        void changed(Call call);
    }

    /** A snapshot of a call. */
    public record Call(String peer, byte[] id, boolean outgoing, State state, EndReason reason, long connectedAt,
                       boolean muted, String error) {}

    private final Transport transport;
    private final Supplier<AudioDevice> devices;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "calls");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "calls-timer");
        t.setDaemon(true);
        return t;
    });

    // guarded by this
    private String peer;
    private byte[] callId;
    private boolean outgoing;
    private State state = State.ENDED;
    private KeyPair myKey;
    private byte[] theirKey;
    private volatile CallCrypto crypto;
    private AudioDevice device;
    private volatile boolean muted;
    private long connectedAt;
    private ScheduledFuture<?> ringTimeout;

    public Calls(Transport transport, Supplier<AudioDevice> devices, Listener listener) {
        this.transport = transport;
        this.devices = devices;
        this.listener = listener;
    }

    /** The current call, or null. */
    public synchronized Call current() {
        return state == State.ENDED ? null : snapshot(null, null);
    }

    // ------------------------------------------------------------------ user actions

    /** Calls a friend. Fails (as an ended call) if a call is already going on. */
    public void call(String friend) {
        run(() -> {
            byte[] id;
            byte[] key;
            synchronized (this) {
                if (state != State.ENDED) return;
                peer = friend;
                callId = new byte[CallCrypto.CALL_ID_LENGTH];
                random.nextBytes(callId);
                outgoing = true;
                myKey = CallCrypto.newCallKey();
                state = State.CALLING;
                muted = false;
                id = callId;
                key = X25519.encode(myKey.getPublic());
                ringTimeout = timer.schedule(() -> run(this::noAnswer), RING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
            changed();
            try {
                transport.signal(friend, OFFER, id, key);
            } catch (Exception e) {
                end(EndReason.FAILED, e.getMessage());
            }
        });
    }

    public void accept() {
        run(() -> {
            byte[] id;
            byte[] key;
            String to;
            synchronized (this) {
                if (state != State.RINGING) return;
                myKey = CallCrypto.newCallKey();
                id = callId;
                to = peer;
                key = X25519.encode(myKey.getPublic());
            }
            try {
                transport.signal(to, ANSWER, id, key);
                connect();
            } catch (Exception e) {
                end(EndReason.FAILED, e.getMessage());
            }
        });
    }

    public void reject() {
        hangUp(REJECT, EndReason.REJECTED);
    }

    public void hangUp() {
        hangUp(HANGUP, EndReason.HUNG_UP);
    }

    private void hangUp(int signal, EndReason reason) {
        run(() -> {
            String to;
            byte[] id;
            synchronized (this) {
                if (state == State.ENDED) return;
                to = peer;
                id = callId;
            }
            end(reason, null);
            try {
                transport.signal(to, signal, id, new byte[0]);
            } catch (Exception ignored) {
                // they will notice the silence
            }
        });
    }

    public void setMuted(boolean mute) {
        muted = mute;
        run(this::changed);
    }

    // ------------------------------------------------------------------ from the network

    /** A signal from a friend, already decrypted and authenticated. */
    public void signal(String from, int kind, byte[] id, byte[] key, long sentAt) {
        run(() -> handle(from, kind, id, key, sentAt));
    }

    private void handle(String from, int kind, byte[] id, byte[] key, long sentAt) {
        boolean stale = Math.abs(System.currentTimeMillis() - sentAt) > OFFER_VALID_MS;
        if (kind == OFFER) {
            boolean busy;
            synchronized (this) {
                busy = state != State.ENDED;
            }
            if (stale) {
                listener.changed(new Call(from, id, false, State.ENDED, EndReason.MISSED, 0, false, null));
                return;
            }
            if (busy) {
                try {
                    transport.signal(from, BUSY, id, new byte[0]);
                } catch (Exception ignored) {
                    // they will see no answer
                }
                listener.changed(new Call(from, id, false, State.ENDED, EndReason.MISSED, 0, false, null));
                return;
            }
            if (key.length != X25519.PUBLIC_LENGTH) return;
            synchronized (this) {
                peer = from;
                callId = id.clone();
                outgoing = false;
                theirKey = key.clone();
                state = State.RINGING;
                muted = false;
                ringTimeout = timer.schedule(() -> run(this::noAnswer), RING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
            changed();
            return;
        }
        synchronized (this) {
            if (state == State.ENDED || !from.equals(peer) || !Arrays.equals(id, callId)) return; // not this call
        }
        switch (kind) {
            case ANSWER -> {
                synchronized (this) {
                    if (state != State.CALLING || key.length != X25519.PUBLIC_LENGTH) return;
                    theirKey = key.clone();
                }
                try {
                    connect();
                } catch (RuntimeException e) {
                    end(EndReason.FAILED, e.getMessage());
                }
            }
            case REJECT -> end(EndReason.REJECTED, null);
            case BUSY -> end(EndReason.BUSY, null);
            case HANGUP -> {
                boolean wasRinging;
                synchronized (this) {
                    wasRinging = state == State.RINGING;
                }
                end(wasRinging ? EndReason.MISSED : EndReason.HUNG_UP, null);
            }
            default -> { }
        }
    }

    /** An audio packet relayed by the server (called on the network thread; must not block). */
    public void audio(String from, byte[] data) {
        CallCrypto c = crypto;
        AudioDevice d;
        synchronized (this) {
            if (c == null || state != State.CONNECTED || !from.equals(peer) || data.length < CallCrypto.CALL_ID_LENGTH
                    || !Arrays.equals(Arrays.copyOf(data, CallCrypto.CALL_ID_LENGTH), callId)) {
                return;
            }
            d = device;
        }
        byte[] ulaw = c.open(Arrays.copyOfRange(data, CallCrypto.CALL_ID_LENGTH, data.length));
        if (ulaw != null && d != null) d.play(Mulaw.decode(ulaw));
    }

    // ------------------------------------------------------------------ internals

    private void connect() {
        String to;
        byte[] id;
        AudioDevice d = devices.get();
        synchronized (this) {
            if (state == State.ENDED) return;
            crypto = CallCrypto.derive(callId, myKey, theirKey, outgoing);
            myKey = null;
            state = State.CONNECTED;
            connectedAt = System.currentTimeMillis();
            if (ringTimeout != null) ringTimeout.cancel(false);
            device = d;
            to = peer;
            id = callId.clone();
        }
        changed();
        d.start(frame -> {
            CallCrypto c = crypto;
            if (c == null) return;
            byte[] audio = Mulaw.encode(muted ? new byte[frame.length] : frame); // keep sending silence when muted
            byte[] sealed = c.seal(audio);
            byte[] packet = new byte[id.length + sealed.length];
            System.arraycopy(id, 0, packet, 0, id.length);
            System.arraycopy(sealed, 0, packet, id.length, sealed.length);
            transport.audio(to, packet);
        });
    }

    private void noAnswer() {
        boolean ringingOut;
        String to;
        byte[] id;
        synchronized (this) {
            if (state != State.CALLING && state != State.RINGING) return;
            ringingOut = state == State.CALLING;
            to = peer;
            id = callId;
        }
        end(ringingOut ? EndReason.NO_ANSWER : EndReason.MISSED, null);
        if (ringingOut) {
            try {
                transport.signal(to, HANGUP, id, new byte[0]);
            } catch (Exception ignored) {
                // nothing more to do
            }
        }
    }

    private void end(EndReason reason, String error) {
        AudioDevice d;
        Call last;
        synchronized (this) {
            if (state == State.ENDED) return;
            state = State.ENDED;
            last = snapshot(reason, error);
            d = device;
            device = null;
            crypto = null; // forget the call keys
            myKey = null;
            theirKey = null;
            if (ringTimeout != null) ringTimeout.cancel(false);
        }
        if (d != null) d.stop();
        listener.changed(last);
    }

    private void changed() {
        Call now;
        synchronized (this) {
            if (state == State.ENDED) return;
            now = snapshot(null, null);
        }
        listener.changed(now);
    }

    private Call snapshot(EndReason reason, String error) {
        return new Call(peer, callId.clone(), outgoing, state, reason, connectedAt, muted, error);
    }

    /** Runs on the calls thread; after {@link #close()} there is nothing left to do. */
    private void run(Runnable task) {
        try {
            worker.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // closed: logged out
        }
    }

    @Override
    public void close() {
        hangUp();
        worker.shutdown();
        try {
            worker.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        timer.shutdownNow();
    }
}
