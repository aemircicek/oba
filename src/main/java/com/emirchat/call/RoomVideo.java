package com.emirchat.call;

import com.emirchat.video.VideoFrame;
import com.emirchat.video.VideoPackets;
import com.emirchat.video.VideoSource;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Cameras and shared screens in a voice room. Everyone may send one picture stream; it goes to
 * the others inside the room's own encryption, in packets of a kind that versions without room
 * video skip, paced like a call's video so that it fits beside the voice in the relay's 100
 * packets a second per person: no server change.
 *
 * <p>One {@link CallVideo} sends ours; one more per person who sends receives theirs. A request
 * for a key frame names whose stream it is about, so only that sender restarts its picture.
 */
public final class RoomVideo implements AutoCloseable {
    /** Where room pictures go; called on video threads. */
    public interface View {
        default void remote(String who, VideoFrame frame, boolean screen) {}

        default void remoteStopped(String who) {}

        default void local(VideoFrame frame, boolean screen) {}

        default void localStopped() {}
    }

    /** Seals and sends one room packet body (the room adds its kind byte and its key). */
    interface Out {
        void send(byte[] body);
    }

    private final String me;
    private final Out out;
    private volatile View view = new View() {};
    private final Map<String, CallVideo> incoming = new HashMap<>();
    private CallVideo outgoing;
    private boolean closed;

    RoomVideo(String me, Out out) {
        this.me = me;
        this.out = out;
    }

    void view(View v) {
        view = v == null ? new View() {} : v;
    }

    /** Starts sending our camera or screen to the room (replacing what was sent). */
    synchronized void start(VideoSource source) {
        if (closed) throw new IllegalStateException("Not in a voice room");
        if (outgoing == null) {
            outgoing = new CallVideo(new CallVideo.Crypto() {
                @Override public byte[] seal(byte[] body) { return body; }

                @Override public byte[] open(byte[] packet) { return packet; }
            }, out::send);
            outgoing.view(new CallVideo.View() {
                @Override public void local(VideoFrame frame, boolean screen) { view.local(frame, screen); }

                @Override public void localStopped() { view.localStopped(); }
            });
        }
        outgoing.start(source);
    }

    synchronized void stop() {
        if (outgoing != null) outgoing.stop();
    }

    public synchronized boolean sending() {
        return outgoing != null && outgoing.sending();
    }

    public synchronized boolean sendingScreen() {
        return outgoing != null && outgoing.sendingScreen();
    }

    /** Who is sending pictures to us right now. */
    public synchronized Set<String> receiving() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        incoming.forEach((who, v) -> {
            if (v.receiving()) out.add(who);
        });
        return out;
    }

    /** A room video body from {@code from} (already opened by the room). */
    void receive(String from, byte[] body) {
        if (body.length == 0 || from.equals(me)) return;
        if (body[0] == VideoPackets.KEYFRAME_PLEASE) {
            // [KEYFRAME_PLEASE][whose stream]: only that sender starts a new picture
            String whose = new String(body, 1, body.length - 1, StandardCharsets.UTF_8);
            CallVideo o;
            synchronized (this) {
                o = outgoing;
            }
            if (o != null && whose.equals(me)) o.receive(new byte[] {VideoPackets.KEYFRAME_PLEASE});
            return;
        }
        CallVideo in;
        synchronized (this) {
            if (closed) return;
            in = incoming.computeIfAbsent(from, this::receiverFor);
        }
        in.receive(body);
    }

    private CallVideo receiverFor(String who) {
        byte[] name = who.getBytes(StandardCharsets.UTF_8);
        CallVideo v = new CallVideo(new CallVideo.Crypto() {
            @Override public byte[] seal(byte[] body) {
                if (body.length == 1 && body[0] == VideoPackets.KEYFRAME_PLEASE) {
                    byte[] ask = new byte[1 + name.length];
                    ask[0] = VideoPackets.KEYFRAME_PLEASE;
                    System.arraycopy(name, 0, ask, 1, name.length);
                    return ask;
                }
                return body;
            }

            @Override public byte[] open(byte[] packet) { return packet; }
        }, out::send);
        v.view(new CallVideo.View() {
            @Override public void remote(VideoFrame frame, boolean screen) { view.remote(who, frame, screen); }

            @Override public void remoteStopped() { view.remoteStopped(who); }
        });
        return v;
    }

    /** Someone left the room: drop their stream. */
    void forget(String who) {
        CallVideo v;
        synchronized (this) {
            v = incoming.remove(who);
        }
        if (v != null) v.close();
    }

    @Override
    public void close() {
        CallVideo o;
        java.util.List<CallVideo> all;
        synchronized (this) {
            closed = true;
            o = outgoing;
            outgoing = null;
            all = new java.util.ArrayList<>(incoming.values());
            incoming.clear();
        }
        if (o != null) o.close();
        for (CallVideo v : all) v.close();
    }
}
