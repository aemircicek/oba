package com.emirchat.video;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/**
 * Cuts encoded video frames into packets small enough for the call relay, and puts them back
 * together. Inside a call's video encryption, every packet starts with its kind:
 *
 * <pre>
 *   CHUNK          u8 1 ‖ u32 frame ‖ u8 index ‖ u8 count ‖ u8 flags ‖ data
 *   KEYFRAME_PLEASE u8 2           (the receiver lost something: start again from a whole picture)
 *   VIDEO_OFF      u8 3            (the sender stopped its camera or its screen)
 * </pre>
 *
 * flags: 1 = key frame (decodable on its own), 2 = a screen rather than a camera.
 */
public final class VideoPackets {
    public static final byte CHUNK = 1;
    public static final byte KEYFRAME_PLEASE = 2;
    public static final byte VIDEO_OFF = 3;
    public static final int FLAG_KEY = 1;
    public static final int FLAG_SCREEN = 2;
    static final int HEADER = 8;
    /**
     * Data per chunk. The relay takes at most 1024 bytes of call data per packet, of which the
     * call id, sequence number and authentication tag take 40 and this header 8.
     */
    public static final int CHUNK_DATA = 960;
    public static final int MAX_CHUNKS = 255;
    /** Frames waiting for missing chunks are given up after this many newer frames have started. */
    private static final int MAX_PENDING = 4;

    private VideoPackets() {}

    /** @return the chunks of one encoded frame, in order */
    public static List<byte[]> split(int frame, byte[] encoded, int flags) {
        int count = Math.max(1, (encoded.length + CHUNK_DATA - 1) / CHUNK_DATA);
        if (count > MAX_CHUNKS) throw new IllegalArgumentException("Frame too large: " + encoded.length + " bytes");
        List<byte[]> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int from = i * CHUNK_DATA;
            int n = Math.min(CHUNK_DATA, encoded.length - from);
            out.add(ByteBuffer.allocate(HEADER + n).put(CHUNK).putInt(frame).put((byte) i).put((byte) count)
                    .put((byte) flags).put(encoded, from, n).array());
        }
        return out;
    }

    /** A whole encoded frame. */
    public record Frame(int number, byte[] data, boolean key, boolean screen) {}

    /**
     * Puts chunks back together and decides what can be decoded. A frame that depends on an
     * earlier one is only handed out if every frame since the last one handed out arrived whole;
     * otherwise everything waits for the next key frame, and {@link #needKeyFrame} says so.
     */
    public static final class Assembler {
        private final TreeMap<Integer, byte[][]> pending = new TreeMap<>();
        private final TreeMap<Integer, Integer> flagsOf = new TreeMap<>();
        private int lastHanded = -1;
        private boolean broken = true; // nothing to build on until the first key frame
        private boolean askForKey;

        /** @return frames now complete and decodable, oldest first (often none, often one) */
        public List<Frame> add(byte[] chunk) {
            if (chunk.length < HEADER || chunk[0] != CHUNK) return List.of();
            ByteBuffer b = ByteBuffer.wrap(chunk);
            b.get();
            int frame = b.getInt();
            int index = b.get() & 0xff;
            int count = b.get() & 0xff;
            int flags = b.get() & 0xff;
            if (count == 0 || index >= count || frame < 0 || (lastHanded >= 0 && frame <= lastHanded)) return List.of();
            byte[][] parts = pending.computeIfAbsent(frame, k -> new byte[count][]);
            if (parts.length != count) return List.of(); // contradicts what came before: ignore
            parts[index] = Arrays.copyOfRange(chunk, HEADER, chunk.length);
            flagsOf.put(frame, flags);
            while (pending.size() > MAX_PENDING) { // the oldest will not be completed any more
                pending.pollFirstEntry();
                broken = true;
                askForKey = true;
            }
            return ready();
        }

        private List<Frame> ready() {
            List<Frame> out = new ArrayList<>();
            while (!pending.isEmpty()) {
                var first = pending.firstEntry();
                int number = first.getKey();
                byte[][] parts = first.getValue();
                for (byte[] p : parts) if (p == null) return out; // still waiting for this one
                pending.pollFirstEntry();
                int flags = flagsOf.remove(number);
                boolean key = (flags & FLAG_KEY) != 0;
                boolean follows = lastHanded >= 0 && number == lastHanded + 1;
                if (!key && (broken || !follows)) {
                    broken = true; // a frame is missing: what follows cannot be decoded until a key frame
                    askForKey = true;
                    lastHanded = number;
                    flagsOf.headMap(number, true).clear();
                    continue;
                }
                broken = false;
                lastHanded = number;
                flagsOf.headMap(number, true).clear();
                int size = 0;
                for (byte[] p : parts) size += p.length;
                ByteBuffer data = ByteBuffer.allocate(size);
                for (byte[] p : parts) data.put(p);
                out.add(new Frame(number, data.array(), key, (flags & FLAG_SCREEN) != 0));
            }
            return out;
        }

        /** True once after something was lost: the sender should be asked for a key frame. */
        public boolean needKeyFrame() {
            boolean ask = askForKey;
            askForKey = false;
            return ask;
        }

        /** The sender started again (camera or screen switched on): forget what we had. */
        public void reset() {
            pending.clear();
            flagsOf.clear();
            lastHanded = -1;
            broken = true;
            askForKey = false;
        }
    }
}
