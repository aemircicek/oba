package com.emirchat.call;

import com.emirchat.video.Pacer;
import com.emirchat.video.VideoFrame;
import com.emirchat.video.VideoPackets;
import com.emirchat.video.VideoSource;
import com.emirchat.video.Vp8;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The video of one call, both ways: our camera or screen in VP8, cut into packets under the
 * call's video keys and paced into the share of the relay that audio leaves free; and the other
 * side's packets put back together, decoded and shown.
 *
 * <p>The relay passes at most 100 call packets a second from one device. Opus audio takes 50,
 * so video gets {@link #PACKETS_PER_SECOND}: about 300 kbit/s, enough for a 480x360 camera at
 * 15 frames a second or a shared screen at 5.
 */
public final class CallVideo implements AutoCloseable {
    static final int PACKETS_PER_SECOND = 40;
    static final int CAMERA_BITRATE = 260_000;
    static final int SCREEN_BITRATE = 280_000;
    /** The other side stopped sending if nothing came for this long (VIDEO_OFF may be lost). */
    static final long REMOTE_TIMEOUT_MS = 3000;
    /** We ask for a key frame at most this often. */
    static final long KEY_REQUEST_GAP_MS = 500;

    /** Sends one sealed video packet (the caller adds the call id and hands it to the relay). */
    public interface Out {
        void send(byte[] sealed);
    }

    /** Where pictures go; called on video threads. */
    public interface View {
        /** A picture from the other side ({@code screen}: a shared screen, show it whole). */
        default void remote(VideoFrame frame, boolean screen) {}

        /** The other side stopped its camera or screen. */
        default void remoteStopped() {}

        /** What we send, for the small preview. */
        default void local(VideoFrame frame, boolean screen) {}

        /** We stopped sending (we chose to, or the camera went away). */
        default void localStopped() {}
    }

    private final CallCrypto crypto;
    private final Out out;
    private volatile View view = new View() {};
    private final Pacer pacer = new Pacer(PACKETS_PER_SECOND, 2 * PACKETS_PER_SECOND);
    private final ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "video-send"));
    private final ThreadPoolExecutor encoderThread = single("video-encode");
    private final ThreadPoolExecutor decoderThread = single("video-decode");
    private final AtomicReference<VideoFrame> nextFrame = new AtomicReference<>();
    private final AtomicBoolean keyWanted = new AtomicBoolean();

    // sending (encoder thread)
    private volatile VideoSource source;
    private Vp8.Encoder encoder;
    private int frameNumber;
    private volatile boolean closed;

    // receiving (decoder thread)
    private final VideoPackets.Assembler assembler = new VideoPackets.Assembler();
    private Vp8.Decoder decoder;
    private volatile long lastRemoteAt;
    private volatile boolean remoteShowing;
    private long lastKeyRequest;

    CallVideo(CallCrypto crypto, Out out) {
        this.crypto = crypto;
        this.out = out;
        sender.scheduleWithFixedDelay(this::checkRemote, 1, 1, TimeUnit.SECONDS);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /** One thread with room for a single waiting task: a slow machine skips work instead of falling behind. */
    private static ThreadPoolExecutor single(String name) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64),
                r -> daemon(r, name), new ThreadPoolExecutor.DiscardPolicy());
    }

    void view(View v) {
        view = v == null ? new View() {} : v;
    }

    // ------------------------------------------------------------------ sending

    /** Starts sending pictures from {@code s}, replacing what was being sent. */
    synchronized void start(VideoSource s) {
        stopSource(false);
        s.start(frame -> {
            if (closed || source != s) return;
            view.local(frame, s.screen());
            if (nextFrame.getAndSet(frame) == null) encoderThread.execute(() -> encodeNext(s));
        });
        source = s;
        keyWanted.set(true);
    }

    /** Stops sending and tells the other side. */
    synchronized void stop() {
        stopSource(true);
    }

    /** True while our camera or screen is being sent. */
    public boolean sending() {
        return source != null;
    }

    /** True while what we send is a screen. */
    public boolean sendingScreen() {
        VideoSource s = source;
        return s != null && s.screen();
    }

    private void stopSource(boolean tell) {
        VideoSource s = source;
        source = null;
        if (s == null) return;
        s.stop();
        nextFrame.set(null);
        encoderThread.execute(this::closeEncoder);
        pacer.reset();
        if (tell && !closed) sendNow(new byte[] {VideoPackets.VIDEO_OFF});
        view.localStopped();
    }

    private void encodeNext(VideoSource s) {
        VideoFrame frame = nextFrame.getAndSet(null);
        if (frame == null || closed || source != s) return;
        try {
            if (encoder == null || encoder.width() != frame.width() || encoder.height() != frame.height()) {
                closeEncoder();
                encoder = new Vp8.Encoder(frame.width(), frame.height(), s.fps(), s.screen() ? SCREEN_BITRATE : CAMERA_BITRATE);
            }
            if (keyWanted.getAndSet(false)) encoder.keyFrame();
            Vp8.Packet packet = encoder.encode(frame);
            if (packet == null) return;
            int flags = (packet.key() ? VideoPackets.FLAG_KEY : 0) | (s.screen() ? VideoPackets.FLAG_SCREEN : 0);
            List<byte[]> chunks = VideoPackets.split(frameNumber, packet.data(), flags);
            long[] delays = pacer.book(chunks.size(), System.nanoTime());
            if (delays == null) {
                keyWanted.set(true); // dropped: the next frame must stand on its own
                return;
            }
            frameNumber++;
            for (int i = 0; i < chunks.size(); i++) {
                byte[] chunk = chunks.get(i);
                sender.schedule(() -> sendNow(chunk), delays[i], TimeUnit.NANOSECONDS);
            }
        } catch (RuntimeException e) {
            keyWanted.set(true); // a frame the encoder refused, or a frame too large: start again
        }
    }

    private void closeEncoder() {
        if (encoder != null) encoder.close();
        encoder = null;
    }

    /** Seals and sends at once (on the send thread, so sequence numbers go out in order). */
    private void sendNow(byte[] body) {
        try {
            sender.execute(() -> {
                if (!closed) out.send(crypto.seal(body));
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // the call ended
        }
    }

    // ------------------------------------------------------------------ receiving

    /**
     * A call packet that did not open as audio: if it opens under the video keys it is ours.
     * Called on the network thread.
     *
     * @return true if it was a video packet
     */
    boolean receive(byte[] packet) {
        byte[] body = crypto.open(packet);
        if (body == null || body.length == 0) return false;
        decoderThread.execute(() -> handle(body));
        return true;
    }

    private void handle(byte[] body) {
        if (closed) return;
        switch (body[0]) {
            case VideoPackets.KEYFRAME_PLEASE -> keyWanted.set(true);
            case VideoPackets.VIDEO_OFF -> remoteOff();
            case VideoPackets.CHUNK -> {
                for (VideoPackets.Frame f : assembler.add(body)) show(f);
                long now = System.currentTimeMillis();
                if (assembler.needKeyFrame() && now - lastKeyRequest > KEY_REQUEST_GAP_MS) {
                    lastKeyRequest = now;
                    sendNow(new byte[] {VideoPackets.KEYFRAME_PLEASE});
                }
            }
            default -> { } // something newer than us
        }
    }

    private void show(VideoPackets.Frame f) {
        try {
            if (decoder == null) decoder = new Vp8.Decoder();
        } catch (IllegalStateException e) {
            return; // this computer cannot decode video: stay with audio
        }
        VideoFrame picture = decoder.decode(f.data());
        if (picture == null) {
            assembler.reset(); // broken: wait for the next key frame
            sendNow(new byte[] {VideoPackets.KEYFRAME_PLEASE});
            return;
        }
        lastRemoteAt = System.currentTimeMillis();
        remoteShowing = true;
        view.remote(picture, f.screen());
    }

    private void remoteOff() {
        assembler.reset();
        if (remoteShowing) {
            remoteShowing = false;
            view.remoteStopped();
        }
    }

    private void checkRemote() {
        if (remoteShowing && System.currentTimeMillis() - lastRemoteAt > REMOTE_TIMEOUT_MS) {
            decoderThread.execute(this::remoteOff);
        }
    }

    /** True while pictures from the other side are arriving. */
    public boolean receiving() {
        return remoteShowing;
    }

    @Override
    public synchronized void close() {
        stopSource(false);
        closed = true;
        boolean wasShowing = remoteShowing;
        remoteShowing = false;
        encoderThread.execute(this::closeEncoder);
        decoderThread.execute(() -> {
            if (decoder != null) decoder.close();
            decoder = null;
        });
        encoderThread.shutdown();
        decoderThread.shutdown();
        sender.shutdown();
        if (wasShowing) view.remoteStopped();
    }
}
