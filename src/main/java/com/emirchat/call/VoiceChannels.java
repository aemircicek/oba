package com.emirchat.call;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Voice rooms: every group and community channel has one that people drop into and out of, no
 * ringing. The server relays encrypted packets inside the room; keys travel through the group's
 * own end-to-end encrypted channel (see {@link VoiceCrypto}). Everyone's audio is mixed here.
 */
public final class VoiceChannels implements AutoCloseable {
    /** What the room needs from the network. */
    public interface Transport {
        /** Joins on the server; returns who is in the room now (including us). */
        List<String> join(long groupId) throws Exception;

        void leave(long groupId) throws Exception;

        /** One encrypted packet for the others in the room (fire and forget). */
        void audio(long groupId, byte[] packet);

        /** Hands our sending key to the group, inside its end-to-end encryption. */
        void sendKey(long groupId, String encodedKey) throws Exception;
    }

    public interface Listener {
        /** Our room's state changed (joined, left, someone came or went, muting). */
        void state(State state);

        /** Someone joined or left a room of one of our groups (also when we are not in it). */
        default void rooms(Map<Long, List<String>> rooms) {}
    }

    /**
     * @param groupId  the room we are in, or 0
     * @param speaking who is talking right now (we are in it too while our microphone hears us)
     */
    public record State(long groupId, List<String> participants, Set<String> speaking, boolean muted, boolean deafened,
                        String error) {
        public static final State IDLE = new State(0, List.of(), Set.of(), false, false, null);

        public boolean joined() {
            return groupId != 0;
        }
    }

    /** Louder than this counts as speaking (see {@link VoiceNotes#level}). */
    private static final float SPEAKING_LEVEL = 0.18f;
    private static final long SPEAKING_HOLD_MS = 350;
    /** At most this much audio (frames of 20 ms) waits per person; older frames are dropped to stay live. */
    private static final int MAX_QUEUED = 8;

    private final String me;
    private final Transport transport;
    private final Supplier<AudioDevice> devices;
    private final BooleanSupplier busy;
    private final Listener listener;
    private final VoiceCrypto.Receiver receiver = new VoiceCrypto.Receiver();
    private final Map<Long, List<String>> rooms = new HashMap<>();
    private final Map<String, Deque<byte[]>> queues = new HashMap<>();
    private final Map<String, Long> loudAt = new HashMap<>();
    private final ScheduledExecutorService mixer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "voice-mixer");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> mixing;
    private ScheduledFuture<?> ticking;
    private long groupId;
    private volatile long joining;        // the room being joined (its keys may arrive before the answer)
    private List<String> participants = List.of();
    private VoiceCrypto.Sender sender;
    private AudioDevice device;
    private boolean muted;
    private boolean deafened;
    private Set<String> lastSpeaking = Set.of();

    /** @param busy true while a one-to-one call uses the microphone */
    public VoiceChannels(String me, Transport transport, Supplier<AudioDevice> devices, BooleanSupplier busy, Listener listener) {
        this.me = me;
        this.transport = transport;
        this.devices = devices;
        this.busy = busy;
        this.listener = listener;
    }

    public synchronized State state() {
        return snapshot(null);
    }

    private State snapshot(String error) {
        return new State(groupId, participants, speakingNow(), muted, deafened, error);
    }

    public synchronized Map<Long, List<String>> rooms() {
        return Map.copyOf(rooms);
    }

    /** Joins a room (leaving any other); runs the network calls on the caller's thread. */
    public void join(long group) {
        synchronized (this) {
            if (groupId == group) return;
        }
        if (busy.getAsBoolean()) {
            listener.state(snapshot("busy"));
            return;
        }
        leave();
        List<String> people;
        joining = group;
        try {
            people = transport.join(group);
        } catch (Exception e) {
            joining = 0;
            listener.state(snapshot(e.getMessage() == null ? "failed" : e.getMessage()));
            return;
        }
        AudioDevice d = devices.get();
        try {
            d.start(this::captured);
        } catch (IllegalStateException e) {
            try {
                transport.leave(group);
            } catch (Exception ignored) {
                // leaving anyway
            }
            joining = 0;
            listener.state(snapshot(e.getMessage()));
            return;
        }
        synchronized (this) {
            joining = 0;
            groupId = group;
            participants = List.copyOf(people);
            rooms.put(group, participants);
            device = d;
            muted = false;
            mixing = mixer.scheduleAtFixedRate(this::mix, 20, 20, TimeUnit.MILLISECONDS);
            ticking = mixer.scheduleAtFixedRate(this::tick, 100, 100, TimeUnit.MILLISECONDS);
        }
        rotateKey();
        publish();
    }

    public void leave() {
        long group;
        AudioDevice d;
        synchronized (this) {
            group = groupId;
            if (group == 0) return;
            d = device;
            device = null;
            groupId = 0;
            participants = List.of();
            sender = null;
            queues.clear();
            loudAt.clear();
            deafened = false;
            muted = false;
            if (mixing != null) mixing.cancel(false);
            if (ticking != null) ticking.cancel(false);
            List<String> left = new ArrayList<>(rooms.getOrDefault(group, List.of()));
            left.remove(me);
            if (left.isEmpty()) rooms.remove(group);
            else rooms.put(group, List.copyOf(left));
        }
        receiver.clear(); // keys of a room we left are of no further use
        if (d != null) d.stop();
        try {
            transport.leave(group);
        } catch (Exception ignored) {
            // the server drops us when the connection goes anyway
        }
        publish();
    }

    public void setMuted(boolean mute) {
        synchronized (this) {
            muted = mute;
            if (mute) loudAt.remove(me);
        }
        publish();
    }

    /** Deafened: hear nothing (and, as in most apps, send nothing either). */
    public void setDeafened(boolean deaf) {
        synchronized (this) {
            deafened = deaf;
            if (deaf) {
                muted = true;
                queues.clear();
            }
        }
        publish();
    }

    /** A new random sending key, given to the group; done on joining and whenever the room changes. */
    private void rotateKey() {
        long group;
        VoiceCrypto.Key key = VoiceCrypto.newKey();
        synchronized (this) {
            group = groupId;
            if (group == 0) return;
            sender = new VoiceCrypto.Sender(key);
        }
        try {
            transport.sendKey(group, VoiceCrypto.encodeKey(key));
        } catch (Exception e) {
            listener.state(snapshot("key: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ from the network

    /** The server says who is in a room now. */
    public void roomChanged(long group, List<String> people) {
        boolean ours;
        boolean changed;
        synchronized (this) {
            List<String> before = rooms.getOrDefault(group, List.of());
            changed = !before.equals(people);
            if (people.isEmpty()) rooms.remove(group);
            else rooms.put(group, List.copyOf(people));
            ours = group == groupId;
            if (ours) {
                for (String gone : before) {
                    if (!people.contains(gone)) {
                        receiver.forget(gone);
                        queues.remove(gone);
                        loudAt.remove(gone);
                    }
                }
                participants = List.copyOf(people);
            }
        }
        if (ours && changed) rotateKey(); // newcomers get our key; leavers lose the ability to listen
        if (changed) publish();
    }

    /**
     * The group's member list changed. If someone in our room is no longer a member, we stop
     * accepting their audio and replace our key right away (the server also takes them out).
     */
    public void membersChanged(long group, java.util.Collection<String> members) {
        boolean rotate = false;
        synchronized (this) {
            if (group != groupId) return;
            for (String p : participants) {
                if (!members.contains(p)) {
                    receiver.forget(p);
                    queues.remove(p);
                    loudAt.remove(p);
                    rotate = true;
                }
            }
            if (rotate) participants = participants.stream().filter(members::contains).toList();
        }
        if (rotate) {
            rotateKey();
            publish();
        }
    }

    /** Someone's sending key arrived through the group's encrypted channel. */
    public void acceptKey(String from, long group, String value) {
        if (from.equals(me)) return;
        VoiceCrypto.Key key = VoiceCrypto.decodeKey(value);
        boolean ours;
        synchronized (this) {
            ours = group == groupId || group == joining;
        }
        if (ours) receiver.accept(from, key);
    }

    /** An encrypted packet relayed by the server; anything that does not verify is dropped. */
    public void audio(long group, String from, byte[] packet) {
        byte[] ulaw;
        synchronized (this) {
            if (group != groupId || deafened || !participants.contains(from)) return;
        }
        try {
            ulaw = receiver.open(group, from, packet);
        } catch (SecurityException e) {
            return;
        }
        byte[] pcm = Mulaw.decode(ulaw);
        synchronized (this) {
            Deque<byte[]> q = queues.computeIfAbsent(from, k -> new ArrayDeque<>());
            q.addLast(pcm);
            while (q.size() > MAX_QUEUED) q.removeFirst();
            if (VoiceNotes.level(pcm) > SPEAKING_LEVEL) loudAt.put(from, System.currentTimeMillis());
        }
    }

    // ------------------------------------------------------------------ audio

    private void captured(byte[] frame) {
        VoiceCrypto.Sender s;
        long group;
        synchronized (this) {
            if (groupId == 0 || muted || sender == null) return;
            s = sender;
            group = groupId;
            if (VoiceNotes.level(frame) > SPEAKING_LEVEL) loudAt.put(me, System.currentTimeMillis());
        }
        transport.audio(group, s.seal(group, me, Mulaw.encode(frame)));
    }

    /** Every 20 ms: one frame from each speaker, summed (with clipping) and played. */
    private void mix() {
        AudioDevice d;
        int[] sum = null;
        synchronized (this) {
            d = device;
            if (d == null || deafened) return;
            for (Deque<byte[]> q : queues.values()) {
                byte[] frame = q.pollFirst();
                if (frame == null) continue;
                if (sum == null) sum = new int[frame.length / 2];
                for (int i = 0; i < sum.length && 2 * i + 1 < frame.length; i++) {
                    sum[i] += (short) ((frame[2 * i] & 0xff) | (frame[2 * i + 1] << 8));
                }
            }
        }
        if (sum == null) return;
        d.play(mixPcm(sum));
    }

    static byte[] mixPcm(int[] sum) {
        byte[] out = new byte[sum.length * 2];
        for (int i = 0; i < sum.length; i++) {
            int v = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sum[i]));
            out[2 * i] = (byte) v;
            out[2 * i + 1] = (byte) (v >> 8);
        }
        return out;
    }

    private Set<String> speakingNow() {
        long now = System.currentTimeMillis();
        Set<String> out = new LinkedHashSet<>();
        loudAt.forEach((who, at) -> {
            if (now - at < SPEAKING_HOLD_MS) out.add(who);
        });
        return out;
    }

    /** Ten times a second: tell the UI when the set of speakers changed. */
    private void tick() {
        Set<String> now;
        synchronized (this) {
            now = speakingNow();
            if (now.equals(lastSpeaking)) return;
            lastSpeaking = now;
        }
        publish();
    }

    private void publish() {
        State s;
        Map<Long, List<String>> r;
        synchronized (this) {
            s = snapshot(null);
            r = Map.copyOf(rooms);
        }
        listener.state(s);
        listener.rooms(r);
    }

    /** The list of rooms as fetched after logging in (or reconnecting). */
    public void roomsLoaded(Map<Long, List<String>> all) {
        synchronized (this) {
            rooms.clear();
            rooms.putAll(all);
            if (groupId != 0 && !all.containsKey(groupId)) rooms.put(groupId, participants);
        }
        publish();
    }

    @Override
    public void close() {
        leave();
        mixer.shutdownNow();
    }
}
