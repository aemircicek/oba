package com.emirchat.ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;

/** Turns a JavaFX picture (from the clipboard) into PNG bytes, without the javafx-swing module. */
final class ImageFiles {
    private ImageFiles() {}

    /** Above this many pixels a picture is decoded at a reduced size. */
    static final long FULL_SIZE_PIXELS = 24_000_000L;
    /** Above this many pixels (all frames of an animation together) a picture is not decoded at all. */
    static final long MAX_PIXELS = 200_000_000L;
    private static final int REDUCED_SIDE = 4096;

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
        long pixels = size[0] * size[1];
        if (size[0] <= 0 || size[1] <= 0 || pixels * Math.max(1, size[2]) > MAX_PIXELS) return null;
        Image image = pixels > FULL_SIZE_PIXELS
                ? new Image(new java.io.ByteArrayInputStream(data), REDUCED_SIDE, REDUCED_SIDE, true, true)
                : new Image(new java.io.ByteArrayInputStream(data));
        return image.isError() ? null : image;
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
                boolean animated = "gif".equalsIgnoreCase(reader.getFormatName());
                long frames = animated ? reader.getNumImages(true) : 1; // GIF: walks the blocks, decodes nothing
                return new long[] {reader.getWidth(0), reader.getHeight(0), frames};
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
