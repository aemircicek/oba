package com.emirchat.ui;

import com.emirchat.chat.Poll;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The little helpers typed in the composer ("bots" that run on your own device):
 *
 * <pre>
 * /poll Question | answer | answer ...   a poll everyone can vote in
 * /roll [NdM]                             dice, e.g. /roll 2d6 (default 1d6)
 * /flip                                   heads or tails
 * /remind 10m text                        a reminder on this computer only (s, m, h, d)
 * /shrug [text]                           ¯\_(ツ)_/¯
 * /me does something                      an action line
 * /topic text                             the channel's topic (owners and admins)
 * /help                                   this list
 * //text                                  sends "/text" as it is
 * </pre>
 *
 * Dice and coins are thrown on the sender's computer; the message says so, nobody else checks.
 */
final class SlashCommands {
    sealed interface Result permits NotACommand, Text, PollResult, Remind, Topic, Help, Problem {}

    /** Ordinary text: send it as typed. */
    record NotACommand(String text) implements Result {}

    /** Send this text instead. */
    record Text(String text) implements Result {}

    record PollResult(Poll poll) implements Result {}

    record Remind(long delayMillis, String text) implements Result {}

    record Topic(String topic) implements Result {}

    record Help() implements Result {}

    /** Shown to the user only; {@code key} is a translation key. */
    record Problem(String key, String arg) implements Result {}

    static final List<String> NAMES = List.of("poll", "roll", "flip", "remind", "shrug", "me", "topic", "help");

    private static final Pattern DICE = Pattern.compile("(\\d{1,2})?d(\\d{1,4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION = Pattern.compile("(\\d{1,4})\\s*(sn|sa|dk|s|m|h|d|g)(?=\\s|$)", Pattern.CASE_INSENSITIVE);
    private static final long MAX_REMIND_MILLIS = 7L * 24 * 3600 * 1000;

    private SlashCommands() {}

    static Result parse(String input, String me, RandomGenerator random) {
        String text = input.strip();
        if (!text.startsWith("/")) return new NotACommand(input);
        if (text.startsWith("//")) return new Text(text.substring(1));
        int space = text.indexOf(' ');
        String name = (space < 0 ? text.substring(1) : text.substring(1, space)).toLowerCase(Locale.ROOT);
        String rest = space < 0 ? "" : text.substring(space + 1).strip();
        return switch (name) {
            case "poll", "anket" -> poll(rest);
            case "roll", "zar" -> roll(rest, random);
            case "flip", "yazitura" -> new Text("🪙 " + (random.nextBoolean() ? I18n.t("slash.heads") : I18n.t("slash.tails")));
            case "remind", "hatirlat" -> remind(rest);
            case "shrug" -> new Text((rest.isEmpty() ? "" : rest + " ") + "¯\\_(ツ)_/¯");
            case "me", "ben" -> rest.isEmpty() ? new Problem("slash.meUsage", "") : new Text("_" + me + " " + rest + "_");
            case "topic", "konu" -> new Topic(rest);
            case "help", "yardim" -> new Help();
            default -> looksLikePath(text) ? new NotACommand(input) : new Problem("slash.unknown", name);
        };
    }

    /** "/usr/bin" or "/home/x" are not commands: send them as they are. */
    private static boolean looksLikePath(String text) {
        int space = text.indexOf(' ');
        String first = space < 0 ? text : text.substring(0, space);
        return first.indexOf('/', 1) > 0;
    }

    private static Result poll(String rest) {
        List<String> parts = new ArrayList<>();
        for (String p : rest.split("\\|")) if (!p.isBlank()) parts.add(p.strip());
        if (parts.size() < 1 + Poll.MIN_OPTIONS) return new Problem("slash.pollUsage", "");
        try {
            return new PollResult(new Poll(parts.get(0), parts.subList(1, parts.size())));
        } catch (IllegalArgumentException e) {
            return new Problem("slash.pollUsage", "");
        }
    }

    private static Result roll(String rest, RandomGenerator random) {
        int count = 1;
        int sides = 6;
        if (!rest.isEmpty()) {
            Matcher m = DICE.matcher(rest.strip());
            if (!m.matches()) return new Problem("slash.rollUsage", "");
            count = m.group(1) == null ? 1 : Integer.parseInt(m.group(1));
            sides = Integer.parseInt(m.group(2));
        }
        if (count < 1 || count > 20 || sides < 2 || sides > 1000) return new Problem("slash.rollUsage", "");
        List<String> throwsList = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < count; i++) {
            int value = 1 + random.nextInt(sides);
            total += value;
            throwsList.add(String.valueOf(value));
        }
        String dice = count + "d" + sides;
        String result = count == 1 ? "**" + total + "**" : String.join(" + ", throwsList) + " = **" + total + "**";
        return new Text("🎲 " + dice + ": " + result);
    }

    private static Result remind(String rest) {
        Matcher m = DURATION.matcher(rest);
        if (!m.lookingAt()) return new Problem("slash.remindUsage", "");
        long amount = Long.parseLong(m.group(1));
        long unit = switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "s", "sn" -> 1000L;
            case "m", "dk" -> 60_000L;
            case "h", "sa" -> 3_600_000L;
            default -> 86_400_000L;
        };
        long delay = amount * unit;
        String what = rest.substring(m.end()).strip();
        if (delay <= 0 || delay > MAX_REMIND_MILLIS || what.isEmpty()) return new Problem("slash.remindUsage", "");
        return new Remind(delay, what);
    }

    /** Commands whose name starts with what is typed after "/", for suggestions. */
    static List<String> complete(String typed) {
        if (!typed.startsWith("/") || typed.contains(" ") || typed.startsWith("//")) return List.of();
        String prefix = typed.substring(1).toLowerCase(Locale.ROOT);
        return NAMES.stream().filter(n -> n.startsWith(prefix)).toList();
    }
}
