package com.emirchat.ui;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * When a message may notify: per chat (everything, only when you are mentioned, or nothing, and
 * muting for a while) and quiet hours for all chats (with mentions let through if you choose).
 * Kept in the account's settings on this computer; nothing about it is sent anywhere.
 */
final class NotifyRules {
    enum Level {
        ALL, MENTIONS, NONE;

        static Level parse(String s) {
            try {
                return Level.valueOf(s);
            } catch (IllegalArgumentException | NullPointerException e) {
                return ALL;
            }
        }
    }

    /** What to do with one incoming message. */
    enum Decision {
        /** banner and sound */
        NOTIFY,
        /** banner without sound (quiet hours with mentions let through) */
        SILENT,
        /** nothing (the unread count still shows it, unless the chat is muted) */
        NONE
    }

    /** Quiet hours: from {@code start} to {@code end} (may cross midnight), every day. */
    record Quiet(boolean on, LocalTime start, LocalTime end, boolean mentionsThrough) {
        static final Quiet DEFAULT = new Quiet(false, LocalTime.of(23, 0), LocalTime.of(8, 0), true);

        boolean covers(LocalTime t) {
            if (!on || start.equals(end)) return false;
            return start.isBefore(end) ? !t.isBefore(start) && t.isBefore(end) : !t.isBefore(start) || t.isBefore(end);
        }

        String encode() {
            return (on ? "1" : "0") + ";" + start + ";" + end + ";" + (mentionsThrough ? "1" : "0");
        }

        static Quiet decode(String s) {
            if (s == null || s.isBlank()) return DEFAULT;
            try {
                String[] p = s.split(";");
                return new Quiet(p[0].equals("1"), LocalTime.parse(p[1]), LocalTime.parse(p[2]), p[3].equals("1"));
            } catch (RuntimeException e) {
                return DEFAULT;
            }
        }
    }

    static final String LEVEL = "notify.level:";
    static final String UNTIL = "notify.until:";
    static final String QUIET = "notify.quiet";
    /** "Muted until I unmute it". */
    static final long FOREVER = Long.MAX_VALUE;

    private final Function<String, String> read;
    private final BiConsumer<String, String> write;

    /** @param read setting by key (null when unset); @param write stores a setting (empty removes it) */
    NotifyRules(Function<String, String> read, BiConsumer<String, String> write) {
        this.read = read;
        this.write = write;
    }

    Level level(String conversation) {
        return Level.parse(read.apply(LEVEL + conversation));
    }

    void setLevel(String conversation, Level level) {
        write.accept(LEVEL + conversation, level == Level.ALL ? "" : level.name());
    }

    /** Until when the chat is muted (epoch millis), or 0. */
    long mutedUntil(String conversation) {
        try {
            String v = read.apply(UNTIL + conversation);
            return v == null || v.isEmpty() ? 0 : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    void muteUntil(String conversation, long until) {
        write.accept(UNTIL + conversation, until <= 0 ? "" : Long.toString(until));
    }

    boolean muted(String conversation, long now) {
        return mutedUntil(conversation) > now || level(conversation) == Level.NONE;
    }

    Quiet quiet() {
        return Quiet.decode(read.apply(QUIET));
    }

    void setQuiet(Quiet q) {
        write.accept(QUIET, q.encode());
    }

    /** Whether the message's chat counts toward the unread number on the window and the tray. */
    boolean counts(String conversation, long now) {
        return !muted(conversation, now);
    }

    Decision decide(String conversation, boolean mentionsMe, ZonedDateTime now) {
        long millis = now.toInstant().toEpochMilli();
        if (mutedUntil(conversation) > millis) return Decision.NONE;
        Level level = level(conversation);
        if (level == Level.NONE) return Decision.NONE;
        if (level == Level.MENTIONS && !mentionsMe) return Decision.NONE;
        Quiet q = quiet();
        if (q.covers(now.toLocalTime())) return q.mentionsThrough() && mentionsMe ? Decision.SILENT : Decision.NONE;
        return Decision.NOTIFY;
    }

    /** The end of "mute for…" choices: 0 = unmute; -1 = forever; -2 = until 8:00 tomorrow; else minutes. */
    static long until(int choice, ZonedDateTime now) {
        if (choice == 0) return 0;
        if (choice == -1) return FOREVER;
        if (choice == -2) {
            LocalDate tomorrow = now.toLocalDate().plusDays(now.toLocalTime().isBefore(LocalTime.of(8, 0)) ? 0 : 1);
            return tomorrow.atTime(8, 0).atZone(now.getZone()).toInstant().toEpochMilli();
        }
        return now.plusMinutes(choice).toInstant().toEpochMilli();
    }

    static ZonedDateTime now() {
        return ZonedDateTime.ofInstant(Instant.now(), ZoneId.systemDefault());
    }
}
