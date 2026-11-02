package com.emirchat.ui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.Map;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

/** QR codes for safety numbers: drawn into a JavaFX image, and read back from a picture. */
final class QrCodes {
    private QrCodes() {}

    static Image render(String text, int size) {
        BitMatrix matrix = encode(text, size);
        WritableImage image = new WritableImage(matrix.getWidth(), matrix.getHeight());
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                image.getPixelWriter().setColor(x, y, matrix.get(x, y) ? Color.web("#16191d") : Color.WHITE);
            }
        }
        return image;
    }

    static BitMatrix encode(String text, int size) {
        try {
            return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2,
                            EncodeHintType.CHARACTER_SET, "UTF-8"));
        } catch (WriterException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @param argb pixels row by row; @return the QR code's text, or null */
    static String decode(int[] argb, int width, int height) {
        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, argb)));
            return new QRCodeReader().decode(bitmap, Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE,
                    DecodeHintType.CHARACTER_SET, "UTF-8")).getText();
        } catch (ReaderException e) {
            return null; // no QR code, or not readable
        }
    }

    /** @return the text of the QR code in the picture, or null if none was found */
    static String read(Image image) {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        if (w <= 0 || h <= 0) return null;
        int[] pixels = new int[w * h];
        PixelReader reader = image.getPixelReader();
        if (reader == null) return null;
        reader.getPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, w);
        return decode(pixels, w, h);
    }
}
