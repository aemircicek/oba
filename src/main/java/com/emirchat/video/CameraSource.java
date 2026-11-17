package com.emirchat.video;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avdevice.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

import java.util.Locale;
import java.util.function.Consumer;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avdevice.AVDeviceInfoList;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVInputFormat;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;

/**
 * The computer's camera, through FFmpeg's capture devices: AVFoundation on macOS, DirectShow on
 * Windows, Video4Linux2 on Linux. The first camera is used.
 */
public final class CameraSource implements VideoSource {
    public static final int FPS = 15;
    public static final int MAX_WIDTH = 480;
    public static final int MAX_HEIGHT = 360;
    /** How long a camera may stay silent before it counts as gone (unplugged, taken by another app). */
    private static final long SILENT_MS = 3000;

    private volatile boolean running;
    private Thread thread;

    @Override public boolean screen() { return false; }

    @Override public int fps() { return FPS; }

    @Override
    public synchronized void start(Consumer<VideoFrame> frames) {
        Ffmpeg.load();
        Opened camera = open();
        running = true;
        thread = Thread.ofPlatform().daemon().name("camera").start(() -> capture(camera, frames));
    }

    @Override
    public synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record Opened(AVFormatContext input, int stream, AVCodecContext decoder) {}

    private static Opened open() {
        avdevice_register_all();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String driver = os.contains("mac") ? "avfoundation" : os.contains("win") ? "dshow" : "video4linux2";
        AVInputFormat format = av_find_input_format(driver);
        if (format == null) throw new IllegalStateException("No camera support on this system");
        String device = os.contains("mac") ? "0:none" : firstDevice(format, os.contains("win"));
        // Ask for a small picture at a common rate; cameras that cannot do it are opened as they are.
        String[][] attempts = {{"640x480", "30"}, {"640x480", null}, {null, null}};
        for (String[] attempt : attempts) {
            AVFormatContext input = new AVFormatContext(null);
            AVDictionary options = new AVDictionary(null);
            if (attempt[0] != null) av_dict_set(options, "video_size", attempt[0], 0);
            if (attempt[1] != null) av_dict_set(options, "framerate", attempt[1], 0);
            if (os.contains("mac")) av_dict_set(options, "pixel_format", "nv12", 0); // what Mac cameras give natively
            int r = avformat_open_input(input, device, format, options);
            av_dict_free(options);
            if (r < 0) continue;
            Opened opened = prepare(input);
            if (opened != null) return opened;
        }
        throw new IllegalStateException("The camera could not be opened (it may be in use, or access was refused)");
    }

    /** The first camera's name as the driver wants it ("video=Integrated Camera" for DirectShow). */
    private static String firstDevice(AVInputFormat format, boolean windows) {
        AVDeviceInfoList list = new AVDeviceInfoList(null);
        try {
            if (avdevice_list_input_sources(format, (String) null, null, list) >= 0 && list.nb_devices() > 0) {
                int pick = Math.max(0, list.default_device());
                String name = list.devices(pick).device_name().getString();
                return windows ? "video=" + name : name;
            }
        } catch (RuntimeException ignored) {
            // fall through to the usual name
        } finally {
            avdevice_free_list_devices(list);
        }
        if (windows) throw new IllegalStateException("No camera found");
        return "/dev/video0";
    }

    private static Opened prepare(AVFormatContext input) {
        if (avformat_find_stream_info(input, (AVDictionary) null) < 0) {
            avformat_close_input(input);
            return null;
        }
        for (int i = 0; i < input.nb_streams(); i++) {
            AVStream stream = input.streams(i);
            if (stream.codecpar().codec_type() != AVMEDIA_TYPE_VIDEO) continue;
            AVCodec codec = avcodec_find_decoder(stream.codecpar().codec_id());
            if (codec == null) break;
            AVCodecContext decoder = avcodec_alloc_context3(codec);
            if (avcodec_parameters_to_context(decoder, stream.codecpar()) < 0
                    || avcodec_open2(decoder, codec, (AVDictionary) null) < 0) {
                avcodec_free_context(decoder);
                break;
            }
            return new Opened(input, i, decoder);
        }
        avformat_close_input(input);
        return null;
    }

    private void capture(Opened camera, Consumer<VideoFrame> frames) {
        AVPacket packet = av_packet_alloc();
        AVFrame frame = av_frame_alloc();
        long gap = 1_000_000_000L / FPS;
        long lastSent = 0;
        long lastFrame = System.currentTimeMillis();
        try (Scaler scaler = new Scaler()) {
            while (running) {
                if (av_read_frame(camera.input(), packet) < 0) {
                    // Most drivers say "try again" until the next picture is ready; one that stays
                    // silent for seconds is gone.
                    if (System.currentTimeMillis() - lastFrame > SILENT_MS) break;
                    Thread.sleep(3);
                    continue;
                }
                try {
                    if (packet.stream_index() != camera.stream() || avcodec_send_packet(camera.decoder(), packet) < 0) continue;
                } finally {
                    av_packet_unref(packet);
                }
                while (avcodec_receive_frame(camera.decoder(), frame) >= 0) {
                    lastFrame = System.currentTimeMillis();
                    long now = System.nanoTime();
                    if (now - lastSent >= gap * 9 / 10 && frame.width() > 0 && frame.height() > 0) {
                        lastSent = now;
                        int[] size = VideoFrame.fit(frame.width(), frame.height(), MAX_WIDTH, MAX_HEIGHT);
                        frames.accept(scaler.toI420(frame, size[0], size[1]));
                    }
                    av_frame_unref(frame);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // the camera went away or produced something we cannot convert: stop sending
        } finally {
            av_frame_free(frame);
            av_packet_free(packet);
            avcodec_free_context(camera.decoder());
            avformat_close_input(camera.input());
            running = false;
        }
    }
}
