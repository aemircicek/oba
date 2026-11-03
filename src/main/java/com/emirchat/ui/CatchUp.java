package com.emirchat.ui;

import com.emirchat.chat.Poll;
import com.emirchat.client.History;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "While you were away": what happened since you last looked, worked out on this computer from
 * the decrypted history (nothing is asked of the server). Pure logic; {@link MainView} draws it.
 */
final class CatchUp {
    /** Where the history comes from (tests give it a list). */
    interface Source {
        /** Unread messages, newest first. */
        List<History.Message> unread();

        /** Messages in both directions since a time, oldest first (polls and our thread replies are found among them). */
        List<History.Message> since(long millis);

        /** Voter → choice for a poll. */
        Map<String, Integer> votes(String conversation, long pollId);

        /** The message a reply or thread reply points at, or null. */
        History.Message find(String conversation, byte[] uid);

        /** Local ids of thread replies in a conversation. */
        Set<Long> threadReplies(String conversation);
    }

    /** The real history as a source. */
    static Source of(History h) {
        return new Source() {
            private final Map<String, Map<Long, Map<String, Integer>>> votes = new java.util.HashMap<>();

            @Override public List<History.Message> unread() { return io(() -> h.unreadMessages(500)); }

            @Override public List<History.Message> since(long millis) { return io(() -> h.since(millis, 2000)); }

            @Override public Map<String, Integer> votes(String conversation, long pollId) {
                return votes.computeIfAbsent(conversation, c -> io(() -> h.votes(c))).getOrDefault(pollId, Map.of());
            }

            @Override public History.Message find(String conversation, byte[] uid) { return io(() -> h.find(conversation, uid)); }

            @Override public Set<Long> threadReplies(String conversation) { return io(() -> h.threadReplies(conversation)); }
        };
    }

    private interface Io<T> {
        T get() throws java.io.IOException;
    }

    private static <T> T io(Io<T> call) {
        try {
            return call.get();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    record Busy(String conversation, int unread, History.Message latest) {}

    /**
     * @param mentions     unread messages that name you (@you), newest first
     * @param openPolls    recent polls you have not voted in
     * @param threadReplies unread replies in threads you started or answered
     * @param busiest      conversations by unread count, the top few
     */
    record Digest(int unread, int conversations, List<History.Message> mentions, List<History.Message> openPolls,
                  List<History.Message> threadReplies, List<Busy> busiest) {
        boolean worthShowing() {
            return unread > 0 || !openPolls.isEmpty();
        }
    }

    static final long POLL_WINDOW_MS = 7L * 24 * 3600 * 1000;

    private CatchUp() {}

    static Digest compute(Source source, String me, long now) {
        List<History.Message> unread = source.unread();
        Map<String, List<History.Message>> byConversation = new LinkedHashMap<>();
        List<History.Message> mentions = new ArrayList<>();
        List<History.Message> threadReplies = new ArrayList<>();
        Map<String, Set<Long>> threadIds = new java.util.HashMap<>();
        for (History.Message m : unread) {
            if (m.outgoing() || m.deleted()) continue;
            byConversation.computeIfAbsent(m.conversation(), k -> new ArrayList<>()).add(m);
            if (m.file() == null && Markdown.mentions(m.text(), me)) mentions.add(m);
            Set<Long> threads = threadIds.computeIfAbsent(m.conversation(), source::threadReplies);
            if (threads.contains(m.id()) && m.replyTo().length > 0) {
                History.Message root = source.find(m.conversation(), m.replyTo());
                boolean mine = root != null && root.outgoing();
                boolean answered = !mine && root != null && answeredThread(source, m.conversation(), root, threads, me);
                if (mine || answered) threadReplies.add(m);
            }
        }
        List<History.Message> openPolls = new ArrayList<>();
        for (History.Message m : source.since(now - POLL_WINDOW_MS)) {
            if (m.outgoing() || m.deleted() || !Poll.isStored(m.text())) continue;
            if (!source.votes(m.conversation(), m.id()).containsKey(me)) openPolls.add(m);
        }
        List<Busy> busiest = new ArrayList<>();
        byConversation.forEach((conv, list) -> busiest.add(new Busy(conv, list.size(), list.get(0))));
        busiest.sort(Comparator.comparingInt(Busy::unread).reversed());
        int total = byConversation.values().stream().mapToInt(List::size).sum();
        return new Digest(total, byConversation.size(), limit(mentions, 5), limit(openPolls, 4), limit(threadReplies, 5),
                limit(busiest, 4));
    }

    /** Did we write in this thread ourselves (so its replies concern us)? */
    private static boolean answeredThread(Source source, String conversation, History.Message root, Set<Long> threads, String me) {
        for (History.Message m : source.since(root.sentAt() - 1)) {
            if (m.outgoing() && threads.contains(m.id()) && java.util.Arrays.equals(m.replyTo(), root.uid())) return true;
        }
        return false;
    }

    private static <T> List<T> limit(List<T> list, int n) {
        return list.size() > n ? List.copyOf(list.subList(0, n)) : List.copyOf(list);
    }

    /** "3 hours and 12 minutes" (or days, or minutes) for the greeting. */
    static String away(long millis) {
        long minutes = millis / 60_000;
        if (minutes < 60) return I18n.plural(Math.max(1, minutes), "minute");
        long hours = minutes / 60;
        if (hours < 48) {
            long rest = minutes % 60;
            return rest == 0 ? I18n.plural(hours, "hour") : I18n.t("catchup.hoursMinutes", I18n.plural(hours, "hour"), I18n.plural(rest, "minute"));
        }
        return I18n.plural(hours / 24, "day");
    }

    /** Good morning / afternoon / evening / night, by the local hour. */
    static String greeting(int hour) {
        return hour < 5 ? I18n.t("catchup.night") : hour < 12 ? I18n.t("catchup.morning")
                : hour < 18 ? I18n.t("catchup.afternoon") : hour < 22 ? I18n.t("catchup.evening") : I18n.t("catchup.night");
    }
}
