package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Received pictures are measured before they are decoded. */
class ImageFilesTest {
    /** A PNG file that is only a header, declaring any size: what a "decompression bomb" starts with. */
    private static byte[] pngHeader(int width, int height) {
        byte[] ihdr = ByteBuffer.allocate(17).put("IHDR".getBytes(StandardCharsets.US_ASCII))
                .putInt(width).putInt(height).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0).array();
        CRC32 crc = new CRC32();
        crc.update(ihdr);
        return ByteBuffer.allocate(8 + 4 + 17 + 4)
                .put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})
                .putInt(13).put(ihdr).putInt((int) crc.getValue()).array();
    }

    @Test
    void theHeaderIsReadWithoutDecoding() throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(30, 20, BufferedImage.TYPE_INT_ARGB), "png", png);
        assertArrayEquals(new long[] {30, 20, 1}, ImageFiles.dimensions(png.toByteArray()));
        assertArrayEquals(new long[] {60_000, 60_000, 1}, ImageFiles.dimensions(pngHeader(60_000, 60_000)));
    }

    @Test
    void aPictureOfBillionsOfPixelsIsNeverDecoded() {
        assertNull(ImageFiles.decode(pngHeader(60_000, 60_000)), "3.6 billion pixels would need 14 GB");
        assertNull(ImageFiles.decode(pngHeader(Integer.MAX_VALUE, Integer.MAX_VALUE)));
    }

    @Test
    void whatIsNotAPictureIsRefused() {
        assertNull(ImageFiles.dimensions("just some text, whatever its name says".getBytes(StandardCharsets.UTF_8)));
        assertNull(ImageFiles.decode(new byte[0]));
    }
}
