package com.emirchat.ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;

/** Turns a JavaFX picture (from the clipboard) into PNG bytes, without the javafx-swing module. */
final class ImageFiles {
    private ImageFiles() {}

    /**
     * The most pixels ever decoded at full size, all frames of an animation together (4 bytes
     * each: about 100 MB).
     */
    static final long FULL_SIZE_PIXELS = 24_000_000L;
    /** Above this many pixels a picture is not opened at all. */
    static final long MAX_PIXELS = 200_000_000L;
    /** A larger still picture is read at about this many pixels, skipping rows and columns. */
    private static final long REDUCED_PIXELS = 8_000_000L;

    /**
     * Decodes a received picture for showing, without trusting it. A few kilobytes can describe a
     * picture of billions of pixels (or an animation of thousands of frames), and decoding one
     * would use up all memory and take the app down for everyone who opens the chat. So the
     * header is read first: enormous pictures are refused, large ones decoded smaller.
     *
     * @return the picture, or null if it is not a picture that can be shown safely
     */
    static Image decode(byte[] data) {
        long[] size = dimensions(data);
        if (size == null) return null;
        if (size[0] <= 0 || size[1] <= 0 || size[2] <= 0) return null;
        long pixels = size[0] * size[1];
        if (pixels > MAX_PIXELS) return null;
        if (size[2] > 1) {
            // An animation is decoded whole, every frame at the full canvas: no reduced form of it.
            if (pixels * size[2] > FULL_SIZE_PIXELS) return null;
        } else if (pixels > FULL_SIZE_PIXELS) {
            BufferedImage small = reduced(data, pixels);
            return small == null ? null : toFx(small);
        }
        Image image = new Image(new java.io.ByteArrayInputStream(data));
        return image.isError() ? null : image;
    }

    /**
     * Reads a large still picture at a fraction of its size. The reader skips rows and columns
     * while it reads, so the full picture is never in memory (asking the full decoder for a
     * smaller result would not promise that).
     */
    static BufferedImage reduced(byte[] data, long pixels) {
        int step = (int) Math.ceil(Math.sqrt((double) pixels / REDUCED_PIXELS));
        try (javax.imageio.stream.ImageInputStream in =
                     javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(data))) {
            java.util.Iterator<javax.imageio.ImageReader> readers = javax.imageio.ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                javax.imageio.ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static Image toFx(BufferedImage source) {
        int w = source.getWidth();
        int h = source.getHeight();
        javafx.scene.image.WritableImage out = new javafx.scene.image.WritableImage(w, h);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            source.getRGB(0, y, w, 1, row, 0, w);
            out.getPixelWriter().setPixels(0, y, w, 1, javafx.scene.image.PixelFormat.getIntArgbInstance(), row, 0, w);
        }
        return out;
    }

    /** A GIF's canvas ("logical screen"), which every frame is drawn on; its frames can be smaller. */
    private static long[] gifScreen(byte[] d) {
        if (d.length < 10 || d[0] != 'G' || d[1] != 'I' || d[2] != 'F') return null;
        return new long[] {(d[6] & 0xff) | (d[7] & 0xff) << 8, (d[8] & 0xff) | (d[9] & 0xff) << 8};
    }

    /** @return {width, height, frames} as the file's header declares them, or null if it is no known picture */
    static long[] dimensions(byte[] data) {
        try (javax.imageio.stream.ImageInputStream in =
                     javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(data))) {
            if (in == null) return null;
            java.util.Iterator<javax.imageio.ImageReader> readers = javax.imageio.ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(in, false, true);
                if (!"gif".equalsIgnoreCase(reader.getFormatName())) {
                    return new long[] {reader.getWidth(0), reader.getHeight(0), 1};
                }
                // What gets allocated is the canvas, once per frame, not the size of the first frame
                // (which the file can make tiny). Take the larger of the two in each direction.
                long[] screen = gifScreen(data);
                if (screen == null) return null;
                long frames = reader.getNumImages(true); // walks the blocks, decodes nothing
                return new long[] {Math.max(screen[0], reader.getWidth(0)), Math.max(screen[1], reader.getHeight(0)), frames};
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static byte[] png(Image image) throws IOException {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        if (w <= 0 || h <= 0) throw new IOException("Empty picture");
        PixelReader reader = image.getPixelReader();
        if (reader == null) throw new IOException("Picture not readable");
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            reader.getPixels(0, y, w, 1, javafx.scene.image.PixelFormat.getIntArgbInstance(), row, 0, w);
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(out, "png", bytes);
        return bytes.toByteArray();
    }
}
