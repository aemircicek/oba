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

    /** A GIF whose canvas is {@code screen} pixels square but whose frames are 1 by 1. */
    private static byte[] gif(int screen, int frames) {
        return gif(screen, frames, 1);
    }

    /** @param lastFrame the width and height of the last frame (the others are 1 by 1) */
    private static byte[] gif(int screen, int frames, int lastFrame) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes("GIF89a".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[] {(byte) screen, (byte) (screen >> 8), (byte) screen, (byte) (screen >> 8),
                (byte) 0x80, 0, 0, 0, 0, 0, (byte) 255, (byte) 255, (byte) 255}); // 2-colour global table
        for (int i = 0; i < frames; i++) {
            int side = i == frames - 1 ? lastFrame : 1;
            out.writeBytes(new byte[] {0x2C, 0, 0, 0, 0, (byte) side, (byte) (side >> 8), (byte) side, (byte) (side >> 8),
                    0, 2, 2, 0x4C, 0x01, 0});
        }
        out.write(0x3B);
        return out.toByteArray();
    }

    @Test
    void aGifIsMeasuredByItsCanvasNotItsFirstFrame() {
        assertArrayEquals(new long[] {60_000, 60_000, 1}, ImageFiles.dimensions(gif(60_000, 1)));
        assertNull(ImageFiles.decode(gif(60_000, 1)), "tiny frame, enormous canvas");
        assertArrayEquals(new long[] {2_000, 2_000, 50}, ImageFiles.dimensions(gif(2_000, 50)));
        assertNull(ImageFiles.decode(gif(2_000, 50)), "200 million pixels over all frames");
    }

    @Test
    void aGifWithAnyFrameLargerThanItsCanvasIsRefused() {
        assertArrayEquals(new long[] {100, 100, 3}, ImageFiles.dimensions(gif(100, 3, 100)), "a frame that fills the canvas is fine");
        assertNull(ImageFiles.dimensions(gif(100, 3, 60_000)), "the third frame is far larger than the canvas");
        assertNull(ImageFiles.decode(gif(100, 3, 60_000)));
        assertNull(ImageFiles.dimensions(gif(10, ImageFiles.MAX_FRAMES + 1)), "too many frames to be a real animation");
    }

    @Test
    void aLargePictureIsReadSmallerNotWhole() throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(6_000, 5_000, BufferedImage.TYPE_BYTE_GRAY), "png", png);
        BufferedImage small = ImageFiles.reduced(png.toByteArray(), 30_000_000L);
        org.junit.jupiter.api.Assertions.assertEquals(3_000, small.getWidth());
        org.junit.jupiter.api.Assertions.assertEquals(2_500, small.getHeight());
    }

    @Test
    void whatIsNotAPictureIsRefused() {
        assertNull(ImageFiles.dimensions("just some text, whatever its name says".getBytes(StandardCharsets.UTF_8)));
        assertNull(ImageFiles.decode(new byte[0]));
    }
}
