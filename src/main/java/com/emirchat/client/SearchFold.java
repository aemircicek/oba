package com.emirchat.client;

/** How search compares words, for the UI to highlight what a search matched. */
public final class SearchFold {
    private SearchFold() {}

    /** Lower case, without accents, Turkish letters folded ("Işık" → "isik"). */
    public static String fold(String s) {
        return SearchTerms.fold(s);
    }
}
