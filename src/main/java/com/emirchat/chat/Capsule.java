package com.emirchat.chat;

import com.emirchat.protocol.Wire;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;

/**
 * An amber message (a time capsule): text that shows as a stone of amber with a date on it until
 * that day, then cracks open.
 *
 * <p>Not a cryptographic lock: the text arrives encrypted end to end like any message and is on
 * the recipient's computer from the start; the amber only keeps it out of sight until the date.
 * The app says so where the capsule is made.
 */
public record Capsule(long opensAt, String text) {
    public static final int MAX_TEXT_BYTES = 4000;
    /** At most this far ahead (ten years). */
    public static final long MAX_AHEAD_MILLIS = 10L * 366 * 24 * 3600 * 1000;
    /** Marks a stored capsule in the local history, like a poll (incoming text never contains it). */
    private static final char MARK = '\u0001';
    private static final String PREFIX = MARK + "amber" + MARK;

    public Capsule {
        if (text == null || text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Bad capsule text");
        }
        if (text.indexOf(MARK) >= 0) throw new IllegalArgumentException("Bad capsule text");
        if (opensAt <= 0) throw new IllegalArgumentException("Bad capsule date");
    }

    /** True once the date has come. */
    public boolean open(long now) {
        return now >= opensAt;
    }

    public byte[] encode() {
        return new Wire.Writer().i64(opensAt).string(text).toByteArray();
    }

    public static Capsule decode(byte[] data) throws ProtocolException {
        Wire.Reader r = new Wire.Reader(data);
        long at = r.i64();
        String text = r.string(MAX_TEXT_BYTES);
        r.expectEnd();
        try {
            return new Capsule(at, text);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(e.getMessage());
        }
    }

    /** How the capsule is kept in the history's text column. */
    public String toStored() {
        return PREFIX + opensAt + MARK + text;
    }

    public static boolean isStored(String text) {
        return text != null && text.startsWith(PREFIX);
    }

    /** The capsule from {@link #toStored}, or null if the text is not one. */
    public static Capsule fromStored(String stored) {
        if (!isStored(stored)) return null;
        String rest = stored.substring(PREFIX.length());
        int mark = rest.indexOf(MARK);
        if (mark < 0) return null;
        try {
            return new Capsule(Long.parseLong(rest.substring(0, mark)), rest.substring(mark + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
