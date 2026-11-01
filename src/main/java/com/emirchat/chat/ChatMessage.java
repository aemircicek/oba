package com.emirchat.chat;

import com.emirchat.protocol.Wire;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;

/**
 * Application message carried inside the encrypted channel. The sender's name is deliberately
 * not part of the message: it comes from the authenticated handshake and cannot be spoofed.
 */
public record ChatMessage(Type type, long sentAtMillis, String text) {
    public static final int MAX_TEXT_BYTES = 4096;

    public enum Type {
        TEXT(1), BYE(2);

        final int code;

        Type(int code) {
            this.code = code;
        }

        static Type of(int code) throws ProtocolException {
            for (Type t : values()) if (t.code == code) return t;
            throw new ProtocolException("Unknown message type " + code);
        }
    }

    public ChatMessage {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Message longer than " + MAX_TEXT_BYTES + " bytes");
        }
    }

    public static ChatMessage text(String text) {
        return new ChatMessage(Type.TEXT, System.currentTimeMillis(), text);
    }

    public static ChatMessage bye() {
        return new ChatMessage(Type.BYE, System.currentTimeMillis(), "");
    }

    public byte[] encode() {
        return new Wire.Writer().u8(type.code).i64(sentAtMillis).string(text).toByteArray();
    }

    public static ChatMessage decode(byte[] data) throws ProtocolException {
        Wire.Reader r = new Wire.Reader(data);
        Type type = Type.of(r.u8());
        long sentAt = r.i64();
        String text = r.string(MAX_TEXT_BYTES);
        r.expectEnd();
        return new ChatMessage(type, sentAt, text);
    }
}
