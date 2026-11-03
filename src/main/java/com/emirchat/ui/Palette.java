package com.emirchat.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The quick switcher (Ctrl+K or ⌘K): type a few letters of a chat, channel, friend or action
 * and press Enter. Matching is fuzzy: the letters must appear in order, and matches at the start
 * of words score higher. Turkish letters and case are ignored ("tsr" finds "Tasarım").
 */
final class Palette {
    enum Kind { CHAT, CHANNEL, COMMUNITY, FRIEND, ACTION }

    record Entry(Kind kind, String title, String subtitle, String id, Runnable action) {}

    private Palette() {}

    /** Higher is better; negative means no match. */
    static int score(String query, String text) {
        String q = Emoji.fold(query.strip());
        String t = Emoji.fold(text);
        if (q.isEmpty()) return 0;
        if (t.equals(q)) return 1000;
        if (t.startsWith(q)) return 900 - t.length();
        int contains = t.indexOf(q);
        int score = 0;
        int at = 0;
        int run = 0;
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (c == ' ') continue;
            int found = t.indexOf(c, at);
            if (found < 0) return -1;
            boolean wordStart = found == 0 || !Character.isLetterOrDigit(t.charAt(found - 1));
            run = found == at ? run + 1 : 0;
            score += 10 + (wordStart ? 30 : 0) + run * 8 - Math.min(20, found - at);
            at = found + 1;
        }
        if (contains >= 0) score += 200;
        return score - t.length() / 4;
    }

    /** The best {@code limit} entries for the query, best first (all, in order, for an empty query). */
    static List<Entry> rank(String query, List<Entry> entries, int limit) {
        if (query.isBlank()) return entries.size() > limit ? entries.subList(0, limit) : entries;
        record Scored(Entry entry, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (Entry e : entries) {
            int s = Math.max(score(query, e.title()), e.subtitle() == null ? -1 : score(query, e.subtitle()) - 100);
            if (s >= 0) scored.add(new Scored(e, s));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, scored.size()); i++) out.add(scored.get(i).entry());
        return out;
    }
}
