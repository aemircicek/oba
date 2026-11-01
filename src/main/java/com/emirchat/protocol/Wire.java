package com.emirchat.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ProtocolException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;

/** Minimal, strict encoding for structured messages: u8, i64 and u16-length-prefixed byte strings. */
public final class Wire {
    private Wire() {}

    public static final class Writer {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final DataOutputStream data = new DataOutputStream(buffer);

        public Writer u8(int value) {
            try { data.writeByte(value); } catch (IOException e) { throw new UncheckedIOException(e); }
            return this;
        }

        public Writer u16(int value) {
            if (value < 0 || value > 0xFFFF) throw new IllegalArgumentException("u16 out of range");
            try { data.writeShort(value); } catch (IOException e) { throw new UncheckedIOException(e); }
            return this;
        }

        public Writer i64(long value) {
            try { data.writeLong(value); } catch (IOException e) { throw new UncheckedIOException(e); }
            return this;
        }

        public Writer bytes(byte[] value) {
            if (value.length > 0xFFFF) throw new IllegalArgumentException("Field too long");
            try {
                data.writeShort(value.length);
                data.write(value);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return this;
        }

        public Writer string(String value) {
            return bytes(value.getBytes(StandardCharsets.UTF_8));
        }

        public byte[] toByteArray() {
            return buffer.toByteArray();
        }
    }

    public static final class Reader {
        private final DataInputStream data;
        private final ByteArrayInputStream source;

        public Reader(byte[] input) {
            this.source = new ByteArrayInputStream(input);
            this.data = new DataInputStream(source);
        }

        public int u8() throws ProtocolException {
            try { return data.readUnsignedByte(); } catch (IOException e) { throw truncated(); }
        }

        public int u16() throws ProtocolException {
            try { return data.readUnsignedShort(); } catch (IOException e) { throw truncated(); }
        }

        /** A list length, rejected above {@code max} before anything is allocated. */
        public int count(int max) throws ProtocolException {
            int n = u16();
            if (n > max) throw new ProtocolException("List of " + n + " exceeds " + max);
            return n;
        }

        public long i64() throws ProtocolException {
            try { return data.readLong(); } catch (IOException e) { throw truncated(); }
        }

        public byte[] bytes(int maxLength) throws ProtocolException {
            try {
                int length = data.readUnsignedShort();
                if (length > maxLength) throw new ProtocolException("Field exceeds " + maxLength + " bytes");
                byte[] value = new byte[length];
                data.readFully(value);
                return value;
            } catch (ProtocolException e) {
                throw e;
            } catch (IOException e) {
                throw truncated();
            }
        }

        public byte[] exactBytes(int length) throws ProtocolException {
            byte[] value = bytes(length);
            if (value.length != length) throw new ProtocolException("Expected " + length + " bytes, got " + value.length);
            return value;
        }

        /** Decodes strictly: malformed UTF-8 is rejected instead of silently replaced. */
        public String string(int maxBytes) throws ProtocolException {
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes(maxBytes)))
                        .toString();
            } catch (CharacterCodingException e) {
                throw new ProtocolException("Invalid UTF-8");
            }
        }

        public void expectEnd() throws ProtocolException {
            if (source.available() != 0) throw new ProtocolException("Unexpected trailing bytes");
        }

        private static ProtocolException truncated() {
            return new ProtocolException("Truncated message");
        }
    }
}
