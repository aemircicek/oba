package com.emirchat.video;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;

/**
 * VP8 (RFC 6386), the video codec of WebRTC, through FFmpeg's libvpx: tuned for live calls (no
 * look-ahead, error resilience, the fastest real-time preset) at a fixed bit rate.
 */
public final class Vp8 {
    private Vp8() {}

    /** One encoded frame. */
    public record Packet(byte[] data, boolean key) {}

    /** One stream's encoder; not thread-safe. Close it. */
    public static final class Encoder implements AutoCloseable {
        private final int width;
        private final int height;
        private AVCodecContext context;
        private AVFrame frame;
        private AVPacket packet;
        private long pts;
        private boolean wantKey = true;

        /**
         * @param bitrate bits a second the encoder aims for
         * @param fps     frames a second it is fed
         */
        public Encoder(int width, int height, int fps, int bitrate) {
            Ffmpeg.load();
            this.width = width;
            this.height = height;
            AVCodec codec = avcodec_find_encoder_by_name("libvpx");
            if (codec == null) throw new IllegalStateException("This FFmpeg has no VP8 encoder");
            context = avcodec_alloc_context3(codec);
            context.width(width);
            context.height(height);
            context.pix_fmt(AV_PIX_FMT_YUV420P);
            context.time_base(av_make_q(1, fps));
            context.framerate(av_make_q(fps, 1));
            context.bit_rate(bitrate);
            context.rc_max_rate(bitrate);
            context.rc_buffer_size(bitrate / 2); // half a second: frames stay close to the average size
            context.gop_size(fps * 10);          // a key frame at least every ten seconds
            context.max_b_frames(0);
            context.thread_count(2);
            AVDictionary options = new AVDictionary(null);
            av_dict_set(options, "deadline", "realtime", 0);
            av_dict_set(options, "cpu-used", "8", 0);
            av_dict_set(options, "lag-in-frames", "0", 0);
            av_dict_set(options, "error-resilient", "1", 0);
            av_dict_set(options, "static-thresh", "100", 0); // skip blocks that did not change (screens)
            // A key frame at most four times the size of an average one: a whole new picture then
            // arrives within about a second, a little soft at first and sharpening as it goes.
            av_dict_set(options, "max-intra-rate", "400", 0);
            int r = avcodec_open2(context, codec, options);
            av_dict_free(options);
            if (r < 0) {
                close();
                throw new IllegalStateException("The VP8 encoder did not start (" + r + ")");
            }
            frame = av_frame_alloc();
            frame.format(AV_PIX_FMT_YUV420P);
            frame.width(width);
            frame.height(height);
            if (av_frame_get_buffer(frame, 32) < 0) {
                close();
                throw new IllegalStateException("No memory for video");
            }
            packet = av_packet_alloc();
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        /** The next frame is encoded on its own, for a receiver that lost something. */
        public void keyFrame() {
            wantKey = true;
        }

        /** @return the encoded frame, or null if the encoder skipped it (rate control) */
        public Packet encode(VideoFrame picture) {
            if (picture.width() != width || picture.height() != height) throw new IllegalArgumentException("Frame size changed");
            if (av_frame_make_writable(frame) < 0) throw new IllegalStateException("Video frame busy");
            Ffmpeg.put(picture, frame);
            frame.pts(pts++);
            frame.pict_type(wantKey ? AV_PICTURE_TYPE_I : AV_PICTURE_TYPE_NONE);
            wantKey = false;
            if (avcodec_send_frame(context, frame) < 0) return null;
            Packet out = null;
            while (avcodec_receive_packet(context, packet) >= 0) {
                byte[] data = new byte[packet.size()];
                packet.data().get(data);
                out = new Packet(data, (packet.flags() & AV_PKT_FLAG_KEY) != 0);
                av_packet_unref(packet);
            }
            return out;
        }

        @Override
        public void close() {
            if (packet != null) av_packet_free(packet);
            if (frame != null) av_frame_free(frame);
            if (context != null) avcodec_free_context(context);
            packet = null;
            frame = null;
            context = null;
        }
    }

    /** One stream's decoder; not thread-safe. Close it. */
    public static final class Decoder implements AutoCloseable {
        /** No picture larger than this is accepted from the other side (it would only cost memory). */
        static final int MAX_SIDE = 1920;
        private AVCodecContext context;
        private AVFrame frame;
        private AVPacket packet;

        public Decoder() {
            Ffmpeg.load();
            AVCodec codec = avcodec_find_decoder(AV_CODEC_ID_VP8);
            if (codec == null) throw new IllegalStateException("This FFmpeg has no VP8 decoder");
            context = avcodec_alloc_context3(codec);
            context.thread_count(1);
            if (avcodec_open2(context, codec, (AVDictionary) null) < 0) {
                close();
                throw new IllegalStateException("The VP8 decoder did not start");
            }
            frame = av_frame_alloc();
            packet = av_packet_alloc();
        }

        /** @return the picture, or null if the data is not a decodable frame (it is never trusted) */
        public VideoFrame decode(byte[] data) {
            if (data.length == 0 || av_new_packet(packet, data.length) < 0) return null;
            packet.data().put(data);
            try {
                if (avcodec_send_packet(context, packet) < 0) return null;
                VideoFrame out = null;
                while (avcodec_receive_frame(context, frame) >= 0) {
                    if (frame.format() == AV_PIX_FMT_YUV420P && frame.width() >= 2 && frame.height() >= 2
                            && frame.width() <= MAX_SIDE && frame.height() <= MAX_SIDE) {
                        out = Ffmpeg.take(frame);
                    }
                    av_frame_unref(frame);
                }
                return out;
            } finally {
                av_packet_unref(packet);
            }
        }

        @Override
        public void close() {
            if (packet != null) av_packet_free(packet);
            if (frame != null) av_frame_free(frame);
            if (context != null) avcodec_free_context(context);
            packet = null;
            frame = null;
            context = null;
        }
    }
}
