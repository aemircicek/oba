package com.emirchat.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecureChannelTest {
    private static final byte[] K1 = filled(1);
    private static final byte[] K2 = filled(2);

    private static byte[] filled(int b) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) b);
        return k;
    }

    /** Captures what a sender writes, as a list of raw records. */
    private static List<byte[]> send(String... messages) throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        SecureChannel sender = new SecureChannel(new FrameIO(new ByteArrayInputStream(new byte[0]), wire), K1, K2);
        for (String m : messages) sender.send(m.getBytes(StandardCharsets.UTF_8));
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(wire.toByteArray()));
        List<byte[]> records = new ArrayList<>();
        while (in.available() > 0) {
            byte[] r = new byte[in.readInt()];
            in.readFully(r);
            records.add(r);
        }
        return records;
    }

    /** A receiver that reads the given records, in the given order. */
    private static SecureChannel receiver(List<byte[]> records, byte[] sendKey, byte[] receiveKey) throws IOException {
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        FrameIO writer = new FrameIO(new ByteArrayInputStream(new byte[0]), framed);
        for (byte[] r : records) writer.write(r);
        return new SecureChannel(new FrameIO(new ByteArrayInputStream(framed.toByteArray()), new ByteArrayOutputStream()),
                sendKey, receiveKey);
    }

    @Test
    void roundTrip() throws IOException {
        SecureChannel rx = receiver(send("merhaba", "dünya"), K2, K1);
        assertEquals("merhaba", new String(rx.receive(), StandardCharsets.UTF_8));
        assertEquals("dünya", new String(rx.receive(), StandardCharsets.UTF_8));
    }

    @Test
    void identicalPlaintextsProduceDifferentCiphertexts() throws IOException {
        List<byte[]> records = send("same", "same");
        assertNotEquals(Arrays.toString(records.get(0)), Arrays.toString(records.get(1)),
                "ECB-style repetition must not leak through the ciphertext");
    }

    @Test
    void detectsTampering() throws IOException {
        List<byte[]> records = send("transfer 10 TL");
        records.get(0)[3] ^= 0x01;
        SecureChannel rx = receiver(records, K2, K1);
        assertThrows(SecurityException.class, rx::receive);
    }

    @Test
    void detectsReplay() throws IOException {
        List<byte[]> records = send("hello");
        SecureChannel rx = receiver(List.of(records.get(0), records.get(0)), K2, K1);
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), rx.receive());
        assertThrows(SecurityException.class, rx::receive);
    }

    @Test
    void detectsReordering() throws IOException {
        List<byte[]> records = send("first", "second");
        SecureChannel rx = receiver(List.of(records.get(1), records.get(0)), K2, K1);
        assertThrows(SecurityException.class, rx::receive);
    }

    @Test
    void rejectsReflectedMessages() throws IOException {
        // A record we sent must not be accepted if bounced back to us: directions use different keys.
        SecureChannel self = receiver(send("ping"), K1, K2);
        assertThrows(SecurityException.class, self::receive);
    }

    @Test
    void channelIsDeadAfterFailure() throws IOException {
        List<byte[]> records = send("a", "b");
        records.get(0)[0] ^= 0x01;
        SecureChannel rx = receiver(records, K2, K1);
        assertThrows(SecurityException.class, rx::receive);
        assertThrows(SecurityException.class, rx::receive);
    }

    @Test
    void rejectsKeyMisuse() {
        FrameIO io = new FrameIO(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        assertThrows(IllegalArgumentException.class, () -> new SecureChannel(io, K1, K1));
        assertThrows(IllegalArgumentException.class, () -> new SecureChannel(io, new byte[16], K2));
    }

    @Test
    void rejectsOversizedFrames() {
        byte[] header = {0x7f, 0x00, 0x00, 0x00};
        FrameIO io = new FrameIO(new ByteArrayInputStream(header), new ByteArrayOutputStream());
        assertThrows(java.net.ProtocolException.class, io::read);
    }
}
