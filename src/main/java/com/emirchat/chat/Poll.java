package com.emirchat.chat;

import com.emirchat.protocol.Wire;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A poll: a question and 2-10 answers. Sent inside an encrypted message like any text; votes are
 * separate actions that refer to the poll's message id, one per person.
 */
public record Poll(String question, List<String> options) {
    public static final int MAX_QUESTION_BYTES = 300;
    public static final int MAX_OPTION_BYTES = 100;
    public static final int MIN_OPTIONS = 2;
    public static final int MAX_OPTIONS = 10;
    /** Marks a stored poll in the local history; incoming text can never contain it (controls are replaced). */
    private static final char MARK = '\u0001';

    public Poll {
        options = List.copyOf(options);
        if (question.isBlank() || question.getBytes(StandardCharsets.UTF_8).length > MAX_QUESTION_BYTES) {
            throw new IllegalArgumentException("Bad poll question");
        }
        if (options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS) throw new IllegalArgumentException("A poll needs 2 to 10 answers");
        for (String o : options) {
            if (o.isBlank() || o.getBytes(StandardCharsets.UTF_8).length > MAX_OPTION_BYTES) throw new IllegalArgumentException("Bad poll answer");
            if (o.indexOf(MARK) >= 0) throw new IllegalArgumentException("Bad poll answer");
        }
        if (question.indexOf(MARK) >= 0) throw new IllegalArgumentException("Bad poll question");
    }

    public byte[] encode() {
        Wire.Writer w = new Wire.Writer().string(question).u8(options.size());
        for (String o : options) w.string(o);
        return w.toByteArray();
    }

    public static Poll decode(byte[] data) throws ProtocolException {
        Wire.Reader r = new Wire.Reader(data);
        String question = r.string(MAX_QUESTION_BYTES);
        int n = r.u8();
        if (n < MIN_OPTIONS || n > MAX_OPTIONS) throw new ProtocolException("Bad poll");
        List<String> options = new ArrayList<>();
        for (int i = 0; i < n; i++) options.add(r.string(MAX_OPTION_BYTES));
        r.expectEnd();
        try {
            return new Poll(question, options);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(e.getMessage());
        }
    }

    /** How the poll is kept in the history's text column. */
    public String toStored() {
        StringBuilder b = new StringBuilder().append(MARK).append("poll").append(MARK).append(question);
        for (String o : options) b.append(MARK).append(o);
        return b.toString();
    }

    public static boolean isStored(String text) {
        return text != null && text.startsWith(MARK + "poll" + MARK);
    }

    /** The poll from {@link #toStored}, or null if the text is not one. */
    public static Poll fromStored(String text) {
        if (!isStored(text)) return null;
        String[] parts = text.substring(6).split(String.valueOf(MARK), -1);
        try {
            return new Poll(parts[0], List.of(parts).subList(1, parts.length));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
