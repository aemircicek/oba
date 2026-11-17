package com.emirchat.video;

import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR;
import static org.bytedeco.ffmpeg.global.avutil.av_log_set_level;

import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.BytePointer;

/** Loading FFmpeg's native libraries (once, and only when video is used) and moving pictures in and out of it. */
public final class Ffmpeg {
    private static volatile boolean loaded;
    private static volatile Throwable failed;

    private Ffmpeg() {}

    /**
     * Loads the libraries; the first call takes a moment (they are unpacked on first use).
     *
     * @throws IllegalStateException if this computer cannot run them (video is then unavailable)
     */
    static void load() {
        if (loaded) return;
        synchronized (Ffmpeg.class) {
            if (loaded) return;
            if (failed != null) throw new IllegalStateException("Video is not available on this computer", failed);
            try {
                org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.avcodec.class);
                org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.swscale.class);
                av_log_set_level(AV_LOG_ERROR); // FFmpeg's chatter would go to the console otherwise
                loaded = true;
            } catch (Throwable t) { // UnsatisfiedLinkError, or a system the jar has no libraries for
                failed = t;
                throw new IllegalStateException("Video is not available on this computer", t);
            }
        }
    }

    /** True if video can work here (loads the libraries if needed). */
    public static boolean available() {
        try {
            load();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Copies a packed I420 picture into an allocated YUV420P frame (whose rows may be padded). */
    static void put(VideoFrame from, AVFrame to) {
        int w = from.width();
        int h = from.height();
        byte[] src = from.i420();
        int offset = 0;
        for (int plane = 0; plane < 3; plane++) {
            int pw = plane == 0 ? w : w / 2;
            int ph = plane == 0 ? h : h / 2;
            BytePointer dst = to.data(plane);
            int stride = to.linesize(plane);
            for (int row = 0; row < ph; row++) {
                dst.position((long) row * stride).put(src, offset, pw);
                offset += pw;
            }
        }
    }

    /** Copies a YUV420P frame out into a packed I420 picture. */
    static VideoFrame take(AVFrame from) {
        int w = from.width() & ~1;
        int h = from.height() & ~1;
        byte[] out = new byte[VideoFrame.size(w, h)];
        int offset = 0;
        for (int plane = 0; plane < 3; plane++) {
            int pw = plane == 0 ? w : w / 2;
            int ph = plane == 0 ? h : h / 2;
            BytePointer src = from.data(plane);
            int stride = from.linesize(plane);
            for (int row = 0; row < ph; row++) {
                src.position((long) row * stride).get(out, offset, pw);
                offset += pw;
            }
        }
        return new VideoFrame(w, h, out);
    }
}
