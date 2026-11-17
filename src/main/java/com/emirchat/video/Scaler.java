package com.emirchat.video;

import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;

/**
 * Converts and resizes pictures with FFmpeg's swscale: whatever a camera delivers, or a screen
 * shot, to I420 at the size we send; and I420 to BGRA for the screen. One per stream (it keeps
 * its conversion between calls); not thread-safe. Close it.
 */
public final class Scaler implements AutoCloseable {
    private SwsContext context;
    private AVFrame target;
    private int targetW;
    private int targetH;
    private int targetFormat;

    public Scaler() {
        Ffmpeg.load();
    }

    /** A camera frame (any format FFmpeg knows) to I420 of the given size. */
    VideoFrame toI420(AVFrame source, int width, int height) {
        convert(source.data(), source.linesize(), source.width(), source.height(), source.format(),
                width, height, AV_PIX_FMT_YUV420P);
        return Ffmpeg.take(target);
    }

    /** Packed 32-bit pixels (Java's TYPE_INT_RGB / ARGB in memory order B,G,R,A) to I420 of the given size. */
    public VideoFrame bgraToI420(byte[] bgra, int w, int h, int width, int height) {
        BytePointer in = new BytePointer(bgra.length);
        try {
            in.put(bgra);
            PointerPointer<BytePointer> data = new PointerPointer<>(in, null, null, null);
            IntPointer stride = new IntPointer(w * 4, 0, 0, 0);
            convert(data, stride, w, h, AV_PIX_FMT_BGRA, width, height, AV_PIX_FMT_YUV420P);
            data.close();
            stride.close();
        } finally {
            in.close();
        }
        return Ffmpeg.take(target);
    }

    /** I420 to BGRA (premultiplied is the same: opaque), the same size, for showing. */
    public byte[] toBgra(VideoFrame picture) {
        int w = picture.width();
        int h = picture.height();
        BytePointer in = new BytePointer(picture.i420().length);
        try {
            in.put(picture.i420());
            BytePointer u = new BytePointer(in).position((long) w * h);
            BytePointer v = new BytePointer(in).position((long) w * h + (long) (w / 2) * (h / 2));
            PointerPointer<BytePointer> data = new PointerPointer<>(in, u, v, null);
            IntPointer stride = new IntPointer(w, w / 2, w / 2, 0);
            convert(data, stride, w, h, AV_PIX_FMT_YUV420P, w, h, AV_PIX_FMT_BGRA);
            data.close();
            stride.close();
        } finally {
            in.close();
        }
        byte[] out = new byte[w * h * 4];
        BytePointer p = target.data(0);
        int lineSize = target.linesize(0);
        for (int row = 0; row < h; row++) p.position((long) row * lineSize).get(out, row * w * 4, w * 4);
        return out;
    }

    private void convert(PointerPointer<?> srcData, IntPointer srcStride, int srcW, int srcH, int srcFormat,
                         int dstW, int dstH, int dstFormat) {
        context = sws_getCachedContext(context, srcW, srcH, srcFormat, dstW, dstH, dstFormat, SWS_BILINEAR,
                null, null, (DoublePointer) null);
        if (context == null) throw new IllegalStateException("Cannot convert " + srcW + "x" + srcH + " pictures");
        if (target == null || targetW != dstW || targetH != dstH || targetFormat != dstFormat) {
            if (target != null) av_frame_free(target);
            target = av_frame_alloc();
            target.format(dstFormat);
            target.width(dstW);
            target.height(dstH);
            if (av_frame_get_buffer(target, 32) < 0) throw new IllegalStateException("No memory for video");
            targetW = dstW;
            targetH = dstH;
            targetFormat = dstFormat;
        }
        sws_scale(context, srcData, srcStride, 0, srcH, target.data(), target.linesize());
    }

    @Override
    public void close() {
        if (context != null) sws_freeContext(context);
        if (target != null) av_frame_free(target);
        context = null;
        target = null;
    }
}
