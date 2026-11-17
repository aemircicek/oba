package com.emirchat.video;

/**
 * One picture in I420 (YUV 4:2:0, planar): the full-size luma plane, then the quarter-size U and V
 * planes, rows packed without padding. Width and height are even.
 */
public record VideoFrame(int width, int height, byte[] i420) {
    public VideoFrame {
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) {
            throw new IllegalArgumentException("Bad frame size " + width + "x" + height);
        }
        if (i420.length != size(width, height)) throw new IllegalArgumentException("Bad frame data");
    }

    public static int size(int width, int height) {
        return width * height * 3 / 2;
    }

    /**
     * The size to send a picture of {@code w} by {@code h} at: as large as fits in {@code maxW} by
     * {@code maxH} without changing its shape (and never larger than it is), rounded to even.
     */
    public static int[] fit(int w, int h, int maxW, int maxH) {
        double scale = Math.min(1, Math.min((double) maxW / w, (double) maxH / h));
        int fw = Math.max(2, (int) Math.round(w * scale) & ~1);
        int fh = Math.max(2, (int) Math.round(h * scale) & ~1);
        return new int[] {fw, fh};
    }
}
