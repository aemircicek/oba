package com.emirchat.ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;

/** Turns a JavaFX picture (from the clipboard) into PNG bytes, without the javafx-swing module. */
final class ImageFiles {
    private ImageFiles() {}

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
