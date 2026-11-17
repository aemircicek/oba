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

        /** Where the server's address service listens (for direct calls), or null if unknown. */
        default java.net.InetSocketAddress directServer() {
            return null;
        }
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
    /** The audio of the connected call, or null. */
    private volatile Link link;
    /** The video of the connected call, or null. */
    private volatile CallVideo video;
    /** The direct path of the connected call, or null (not allowed with this person). */
    private volatile DirectPath direct;
    /** Whether we may show our address to this person by calling them directly. */
    private volatile java.util.function.Predicate<String> directAllowed = p -> false;
    private volatile CallVideo.View videoView;
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

    /** The connection to the server is gone: so is the call. */
    public void connectionLost() {
        run(() -> end(EndReason.FAILED, "connection lost"));
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
        Link l = link;
        AudioDevice d;
        synchronized (this) {
            if (l == null || state != State.CONNECTED || !from.equals(peer) || data.length < CallCrypto.CALL_ID_LENGTH
                    || !Arrays.equals(Arrays.copyOf(data, CallCrypto.CALL_ID_LENGTH), callId)) {
                return;
            }
            d = device;
        }
        if (d == null) return;
        byte[] pcm = l.receive(Arrays.copyOfRange(data, CallCrypto.CALL_ID_LENGTH, data.length));
        if (pcm != null) d.play(pcm);
    }

    // ------------------------------------------------------------------ direct path

    /**
     * Who may be called directly, which shows them our address (and us theirs). Asked when a
     * call connects; a side that says no never sends its address nor probes the other.
     */
    public void directAllowed(java.util.function.Predicate<String> allowed) {
        directAllowed = allowed == null ? p -> false : allowed;
    }

    /** True while the current call's audio and video go directly, not through the server. */
    public boolean direct() {
        DirectPath p = direct;
        return p != null && p.active();
    }

    private final java.util.concurrent.atomic.AtomicLong sentDirect = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong sentRelayed = new java.util.concurrent.atomic.AtomicLong();

    /** @return {packets sent directly, packets sent through the relay}, over every call so far */
    public long[] packetsSent() {
        return new long[] {sentDirect.get(), sentRelayed.get()};
    }

    /** Sends a call packet over the direct path if it is open, else through the relay. */
    private void deliver(String to, byte[] packet) {
        DirectPath p = direct;
        if (p != null && p.send(packet)) {
            sentDirect.incrementAndGet();
            return;
        }
        sentRelayed.incrementAndGet();
        transport.audio(to, packet);
    }

    private static byte[] withId(byte[] id, byte[] sealed) {
        byte[] packet = new byte[id.length + sealed.length];
        System.arraycopy(id, 0, packet, 0, id.length);
        System.arraycopy(sealed, 0, packet, id.length, sealed.length);
        return packet;
    }

    // ------------------------------------------------------------------ video

    /** Where the call's pictures go (set once; kept for every call). */
    public void videoView(CallVideo.View view) {
        videoView = view;
        CallVideo v = video;
        if (v != null) v.view(view);
    }

    /**
     * True if video can be sent now: a call is connected and the other side runs a version with
     * video (which it shows by playing Opus: the two came together).
     */
    public boolean videoPossible() {
        Link l = link;
        return l != null && l.peerOpus && video != null;
    }

    /**
     * Starts sending pictures from {@code source} (the camera or the screen), replacing what was
     * sent. Opening a camera can take a moment: call it off the UI thread.
     *
     * @throws IllegalStateException if there is no call with video, or the source cannot start
     */
    public void startVideo(com.emirchat.video.VideoSource source) {
        CallVideo v = video;
        if (v == null || !videoPossible()) throw new IllegalStateException("No call with video");
        v.start(source);
    }

    public void stopVideo() {
        CallVideo v = video;
        if (v != null) v.stop();
    }

    /** The video of the current call, or null. */
    public CallVideo video() {
        return video;
    }

    /**
     * What we send: "opus" once the other side has shown it plays Opus, "mulaw" before that and
     * with an older version; null without a connected call.
     */
    public String sendingCodec() {
        Link l = link;
        return l == null ? null : l.peerOpus ? "opus" : "mulaw";
    }

    /**
     * The audio of one call in both directions. Each side starts in μ-law, which every version
     * understands, and sends a small Opus "I can" packet now and then under the Opus keys. Once
     * the other side has shown it reads Opus (by sending any packet under those keys), we send
     * it Opus. An older version never authenticates those packets, so it only ever gets μ-law.
     */
    static final class Link {
        static final byte OPUS_FRAME = 1;
        static final byte CAN_OPUS = 2;
        /** An "I can" packet every second while we still send μ-law. */
        static final int CAN_EVERY = 50;

        private final CallCrypto ulaw;
        private final CallCrypto opus;
        /** The other side reads Opus (it sent us something under the Opus keys). */
        volatile boolean peerOpus;
        // sending side: used from the capture thread only
        private final OpusCodec.Encoder encoder = new OpusCodec.Encoder();
        private final Resample down = Resample.down();
        private int frames;
        // receiving side: used from the network thread only
        private final OpusCodec.Decoder decoder = new OpusCodec.Decoder();
        private final Resample up = Resample.up();
        /** Video packets arrive mixed with the audio ones; null in tests of audio alone. */
        volatile CallVideo video;
        /** So do the direct path's candidates; null when it is not allowed. */
        volatile DirectPath direct;

        Link(CallCrypto ulaw, CallCrypto opus) {
            this.ulaw = ulaw;
            this.opus = opus;
        }

        /** @param pcm one 20 ms frame at {@link AudioDevice#SAMPLE_RATE} @return the packets to send */
        java.util.List<byte[]> send(byte[] pcm) {
            if (peerOpus) {
                byte[] coded = encoder.encode(pcm);
                byte[] body = new byte[coded.length + 1];
                body[0] = OPUS_FRAME;
                System.arraycopy(coded, 0, body, 1, coded.length);
                return java.util.List.of(opus.seal(body));
            }
            byte[] packet = ulaw.seal(Mulaw.encode(down.process(pcm)));
            if (frames++ % CAN_EVERY == 0) return java.util.List.of(packet, opus.seal(new byte[] {CAN_OPUS}));
            return java.util.List.of(packet);
        }

        /**
         * @return audio to play, or null (an "I can" packet, or anything late, replayed or damaged).
         *         Synchronized: packets come from the relay and, during a switch, from the direct path too.
         */
        synchronized byte[] receive(byte[] packet) {
            byte[] body = opus.open(packet);
            if (body != null) {
                peerOpus = true;
                if (body.length < 2 || body[0] != OPUS_FRAME) return null;
                return decoder.decode(Arrays.copyOfRange(body, 1, body.length));
            }
            CallVideo v = video;
            if (v != null && v.receive(packet)) return null;
            DirectPath p = direct;
            if (p != null && p.receiveRelayed(packet)) return null;
            byte[] coded = ulaw.open(packet);
            return coded == null ? null : up.process(Mulaw.decode(coded));
        }
    }

    // ------------------------------------------------------------------ internals

    private void connect() {
        String to;
        byte[] id;
        AudioDevice d = devices.get();
        synchronized (this) {
            if (state == State.ENDED) return;
            link = new Link(CallCrypto.derive(callId, myKey, theirKey, outgoing),
                    CallCrypto.deriveOpus(callId, myKey, theirKey, outgoing));
            String videoTo = peer;
            byte[] videoId = callId.clone();
            video = new CallVideo(CallCrypto.deriveVideo(callId, myKey, theirKey, outgoing),
                    sealed -> deliver(videoTo, withId(videoId, sealed)));
            video.view(videoView);
            link.video = video;
            java.net.InetSocketAddress server = transport.directServer();
            if (directAllowed.test(peer)) {
                CallVideo callVideo = video;
                direct = new DirectPath(callId, CallCrypto.deriveDirectSignal(callId, myKey, theirKey, outgoing),
                        CallCrypto.deriveDirectControl(callId, myKey, theirKey, outgoing), server,
                        sealed -> transport.audio(videoTo, withId(videoId, sealed)), // candidates always go through the relay
                        packet -> audio(videoTo, packet),
                        on -> {
                            callVideo.direct(on);
                            changed();
                        });
                link.direct = direct;
            }
            myKey = null;
            state = State.CONNECTED;
            connectedAt = System.currentTimeMillis();
            if (ringTimeout != null) ringTimeout.cancel(false);
            device = d;
            to = peer;
            id = callId.clone();
        }
        changed();
        DirectPath path = direct;
        if (path != null) path.start(); // opens its socket and works in the background
        d.start(frame -> {
            Link l = link;
            if (l == null) return;
            for (byte[] sealed : l.send(muted ? new byte[frame.length] : frame)) { // silence goes on while muted
                deliver(to, withId(id, sealed));
            }
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
        CallVideo v;
        DirectPath p;
        Call last;
        synchronized (this) {
            if (state == State.ENDED) return;
            state = State.ENDED;
            last = snapshot(reason, error);
            d = device;
            device = null;
            link = null; // forget the call keys
            v = video;
            video = null;
            p = direct;
            direct = null;
            myKey = null;
            theirKey = null;
            if (ringTimeout != null) ringTimeout.cancel(false);
        }
        if (d != null) d.stop();
        if (v != null) v.close(); // stops the camera too
        if (p != null) p.close();
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
