package com.emirchat.call;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Voice messages: speech recorded at 8 kHz, stored as a μ-law WAV file (format 7, readable by
 * ordinary audio players) and sent like any other encrypted attachment. Pure logic, no sound
 * hardware: encoding, decoding and the waveform drawn in the chat.
 */
public final class VoiceNotes {
    public static final String MIME = "audio/wav";
    /** Longest recording (five minutes: about 2.4 MB). */
    public static final int MAX_SECONDS = 300;
    private static final int RATE = (int) AudioDevice.SAMPLE_RATE;
    private static final int HEADER = 58; // RIFF + fmt (18 bytes, as non-PCM formats need) + fact + data headers

    private VoiceNotes() {}

    /** True for a file the chat shows with a player. */
    public static boolean isVoice(String mimeType, String name) {
        return MIME.equals(mimeType) && name.toLowerCase(java.util.Locale.ROOT).endsWith(".wav");
    }

    /** A μ-law WAV file from 16-bit little-endian PCM samples at 8 kHz. */
    public static byte[] wav(byte[] pcm) {
        byte[] ulaw = Mulaw.encode(pcm);
        ByteBuffer b = ByteBuffer.allocate(HEADER + ulaw.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(HEADER - 8 + ulaw.length);
        b.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(18);
        b.putShort((short) 7)             // μ-law
                .putShort((short) 1)      // mono
                .putInt(RATE)
                .putInt(RATE)             // bytes per second
                .putShort((short) 1)      // block align
                .putShort((short) 8)      // bits per sample
                .putShort((short) 0);     // no extra format bytes
        b.put("fact".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(4).putInt(ulaw.length);
        b.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(ulaw.length);
        b.put(ulaw);
        return b.array();
    }

    /**
     * 16-bit little-endian PCM from a voice message. Accepts what {@link #wav} writes, and also
     * plain 16-bit PCM WAV at 8 kHz mono; anything else is refused rather than played as noise.
     */
    public static byte[] decode(byte[] wav) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (wav.length < 12 || !tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) throw new IOException("Not a WAV file");
        int pos = 12;
        int format = -1;
        int channels = 0;
        int rate = 0;
        int bits = 0;
        while (pos + 8 <= wav.length) {
            String id = new String(wav, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long size = Integer.toUnsignedLong(b.getInt(pos + 4));
            int body = pos + 8;
            if (size > wav.length - body) size = wav.length - body; // tolerate a truncated last chunk
            if (id.equals("fmt ") && size >= 16) {
                format = b.getShort(body) & 0xffff;
                channels = b.getShort(body + 2) & 0xffff;
                rate = b.getInt(body + 4);
                bits = b.getShort(body + 14) & 0xffff;
            } else if (id.equals("data")) {
                if (channels != 1 || rate != RATE) throw new IOException("Unsupported voice format");
                byte[] data = java.util.Arrays.copyOfRange(wav, body, body + (int) size);
                if (format == 7 && bits == 8) return Mulaw.decode(data);
                if (format == 1 && bits == 16) return data.length % 2 == 0 ? data : java.util.Arrays.copyOf(data, data.length - 1);
                throw new IOException("Unsupported voice format");
            }
            pos = body + (int) size + (int) (size & 1); // chunks are padded to even sizes
        }
        throw new IOException("No audio in the file");
    }

    private static boolean tag(ByteBuffer b, int at, String tag) {
        for (int i = 0; i < 4; i++) if (b.get(at + i) != tag.charAt(i)) return false;
        return true;
    }

    /** Length of 16-bit PCM at 8 kHz, in milliseconds. */
    public static long millis(byte[] pcm) {
        return pcm.length / 2 * 1000L / RATE;
    }

    /**
     * {@code bars} loudness values between 0 and 1 for drawing: the peak of each slice, scaled so
     * the loudest slice reaches 1 (quiet recordings still show a shape).
     */
    public static float[] waveform(byte[] pcm, int bars) {
        float[] out = new float[bars];
        int samples = pcm.length / 2;
        if (samples == 0 || bars == 0) return out;
        float max = 0;
        for (int bar = 0; bar < bars; bar++) {
            int from = (int) ((long) samples * bar / bars);
            int to = Math.max(from + 1, (int) ((long) samples * (bar + 1) / bars));
            int peak = 0;
            for (int i = from; i < to && i < samples; i++) {
                int s = Math.abs((short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8)));
                if (s > peak) peak = s;
            }
            out[bar] = peak;
            max = Math.max(max, peak);
        }
        for (int i = 0; i < bars; i++) out[i] = max == 0 ? 0 : (float) Math.sqrt(out[i] / max); // sqrt: quiet parts stay visible
        return out;
    }

    /** Loudness of one captured frame (0-1), for the live meter while recording. */
    public static float level(byte[] frame) {
        int peak = 0;
        for (int i = 0; i + 1 < frame.length; i += 2) {
            peak = Math.max(peak, Math.abs((short) ((frame[i] & 0xff) | (frame[i + 1] << 8))));
        }
        return Math.min(1f, (float) Math.sqrt(peak / 32768f) * 1.2f);
    }
}
