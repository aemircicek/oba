package com.emirchat.client;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The words of a message as the search index stores them: folded (case, Turkish letters and
 * accents: "Işık" and "isik" are the same word), cut into prefixes so that typing the start of a
 * word finds it, and each prefix replaced by a keyed hash. The index on disk therefore holds no
 * words, only tokens that mean nothing without the history key (derived from the password).
 *
 * <p>What it still shows to someone with the file but not the password: how many distinct words
 * a message has and which messages share a word. The README says so.
 */
final class SearchTerms {
    /** Shorter prefixes match too much to be worth storing. */
    static final int MIN_PREFIX = 2;
    /** Longer words are indexed by their first this many letters (the match is checked on the text). */
    static final int MAX_PREFIX = 16;
    /** Bytes kept of each HMAC: plenty to keep different words apart in one person's history. */
    static final int TOKEN_BYTES = 12;
    /** A huge message (a pasted log) is indexed by its first this many distinct words. */
    static final int MAX_WORDS = 400;

    private final byte[] key;

    SearchTerms(byte[] key) {
        this.key = key.clone();
    }

    /** Lower case, without accents and with Turkish letters folded to their Latin base. */
    static String fold(String s) {
        String lower = s.toLowerCase(Locale.ROOT)
                .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g').replace('ç', 'c').replace('ö', 'o').replace('ü', 'u');
        // "İ" lower-cases to "i" plus a combining dot, which goes with the other marks here
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    /** The distinct words of a text (letters and digits), folded. */
    static Set<String> words(String text) {
        Set<String> words = new LinkedHashSet<>();
        for (String w : fold(text).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= MIN_PREFIX) words.add(w);
            if (words.size() >= MAX_WORDS) break;
        }
        return words;
    }

    /** Every prefix of every word that the index stores, as tokens. */
    Set<Token> tokens(String text) {
        Set<String> prefixes = new LinkedHashSet<>();
        for (String w : words(text)) {
            for (int n = MIN_PREFIX; n <= Math.min(w.length(), MAX_PREFIX); n++) prefixes.add(w.substring(0, n));
        }
        Set<Token> tokens = new LinkedHashSet<>();
        for (String p : prefixes) tokens.add(token(p));
        return tokens;
    }

    /** The token to look up for one word of a query (its first {@link #MAX_PREFIX} letters). */
    Token token(String foldedWord) {
        String w = foldedWord.length() > MAX_PREFIX ? foldedWord.substring(0, MAX_PREFIX) : foldedWord;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] full = mac.doFinal(w.getBytes(StandardCharsets.UTF_8));
            return new Token(java.util.Arrays.copyOf(full, TOKEN_BYTES));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A stored token (bytes compared by value). */
    record Token(byte[] bytes) {
        @Override public boolean equals(Object o) {
            return o instanceof Token t && java.util.Arrays.equals(t.bytes, bytes);
        }

        @Override public int hashCode() {
            return java.util.Arrays.hashCode(bytes);
        }

        @Override public byte[] bytes() {
            return bytes.clone();
        }
    }
}
