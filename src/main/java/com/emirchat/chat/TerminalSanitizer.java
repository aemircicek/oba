package com.emirchat.chat;

/**
 * Makes untrusted text safe to print on a terminal. Without this, a peer could send ANSI escape
 * sequences (clear the screen, fake prompts, rewrite earlier lines, set the window title) or
 * Unicode bidirectional overrides that make text display differently from what it contains.
 */
public final class TerminalSanitizer {
    private static final char REPLACEMENT = '�';

    private TerminalSanitizer() {}

    public static String sanitize(String input) {
        StringBuilder out = new StringBuilder(input.length());
        input.codePoints().forEach(cp -> {
            if (isDangerous(cp)) {
                out.append(REPLACEMENT);
            } else {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }

    private static boolean isDangerous(int cp) {
        return cp < 0x20                         // C0 controls incl. ESC, CR, LF, BEL, BS
                || cp == 0x7F                    // DEL
                || (cp >= 0x80 && cp <= 0x9F)    // C1 controls incl. CSI
                || (cp >= 0x202A && cp <= 0x202E) // bidi embeddings/overrides
                || (cp >= 0x2066 && cp <= 0x2069) // bidi isolates
                || cp == 0x200E || cp == 0x200F || cp == 0x061C; // directional marks
    }
}
