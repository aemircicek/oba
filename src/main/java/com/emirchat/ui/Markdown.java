package com.emirchat.ui;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The small Markdown dialect chat messages use: {@code **bold**}, {@code *italic*} or
 * {@code _italic_}, {@code ~~strike~~}, {@code `code`}, fenced {@code ```code blocks```} with
 * syntax colouring, {@code > quotes} and plain {@code http(s)} links. Pure logic (no JavaFX), so it
 * is tested on its own; {@link ConversationView} turns the result into text nodes.
 *
 * <p>Nothing here ever runs or loads anything: links are only shown, and opened by the user.
 */
final class Markdown {
    enum Style { BOLD, ITALIC, STRIKE, CODE, LINK, KEYWORD, STRING, COMMENT, NUMBER }

    enum Kind { PARAGRAPH, QUOTE, CODE }

    record Span(String text, Set<Style> styles) {
        boolean has(Style s) {
            return styles.contains(s);
        }
    }

    record Block(Kind kind, String language, List<Span> spans) {}

    private static final Pattern FENCE = Pattern.compile("^```\\s*([A-Za-z0-9+#._-]*)\\s*$");
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"`]+[^\\s<>\"`.,;:!?)\\]}'*_~]");

    private Markdown() {}

    /** True if the text uses any formatting at all (plain messages keep the simple label). */
    static boolean hasFormatting(String text) {
        return text.contains("*") || text.contains("_") || text.contains("`") || text.contains("~~")
                || text.contains("http://") || text.contains("https://") || text.startsWith(">") || text.contains("\n>");
    }

    /** The text without formatting marks, on one line (chat list previews, quotes, notifications). */
    static String plain(String text) {
        if (!hasFormatting(text) && !text.contains("\n")) return text;
        StringBuilder b = new StringBuilder();
        for (Block block : parse(text)) {
            if (!b.isEmpty()) b.append(' ');
            for (Span span : block.spans()) b.append(span.text());
        }
        return b.toString().replace('\n', ' ').strip();
    }

    static List<Block> parse(String text) {
        List<Block> blocks = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        StringBuilder para = new StringBuilder();
        StringBuilder quote = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher fence = FENCE.matcher(line.strip());
            if (fence.matches()) {
                // a code block runs to the closing fence, or to the end if it is never closed
                int end = i + 1;
                while (end < lines.length && !lines[end].strip().equals("```")) end++;
                flush(blocks, para, Kind.PARAGRAPH);
                flush(blocks, quote, Kind.QUOTE);
                String code = String.join("\n", java.util.Arrays.copyOfRange(lines, i + 1, Math.min(end, lines.length)));
                String language = fence.group(1).toLowerCase(Locale.ROOT);
                blocks.add(new Block(Kind.CODE, language, highlight(code, language)));
                i = end;
                continue;
            }
            if (line.startsWith(">")) {
                flush(blocks, para, Kind.PARAGRAPH);
                if (!quote.isEmpty()) quote.append('\n');
                quote.append(line.substring(line.startsWith("> ") ? 2 : 1));
            } else {
                flush(blocks, quote, Kind.QUOTE);
                if (!para.isEmpty()) para.append('\n');
                para.append(line);
            }
        }
        flush(blocks, para, Kind.PARAGRAPH);
        flush(blocks, quote, Kind.QUOTE);
        return blocks;
    }

    private static void flush(List<Block> blocks, StringBuilder text, Kind kind) {
        if (text.isEmpty()) return;
        blocks.add(new Block(kind, "", inline(text.toString())));
        text.setLength(0);
    }

    // ------------------------------------------------------------------ inline

    /** Inline formatting. Markers only count when they wrap something and are closed. */
    static List<Span> inline(String text) {
        List<Span> out = new ArrayList<>();
        parseInline(text, EnumSet.noneOf(Style.class), out);
        return merge(out);
    }

    private static void parseInline(String text, Set<Style> outer, List<Span> out) {
        int i = 0;
        StringBuilder plain = new StringBuilder();
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '`') {
                int close = text.indexOf('`', i + 1);
                if (close > i + 1) {
                    emit(plain, outer, out);
                    out.add(new Span(text.substring(i + 1, close), with(outer, Style.CODE)));
                    i = close + 1;
                    continue;
                }
            }
            if ((c == 'h') && (text.startsWith("http://", i) || text.startsWith("https://", i))) {
                Matcher m = URL.matcher(text);
                if (m.find(i) && m.start() == i) {
                    emit(plain, outer, out);
                    out.add(new Span(m.group(), with(outer, Style.LINK)));
                    i = m.end();
                    continue;
                }
            }
            String marker = text.startsWith("**", i) ? "**" : text.startsWith("~~", i) ? "~~"
                    : c == '*' || c == '_' ? String.valueOf(c) : null;
            if (marker != null && opensAt(text, i, marker)) {
                int close = closing(text, i + marker.length(), marker);
                if (close > 0) {
                    emit(plain, outer, out);
                    Style style = switch (marker) {
                        case "**" -> Style.BOLD;
                        case "~~" -> Style.STRIKE;
                        default -> Style.ITALIC;
                    };
                    parseInline(text.substring(i + marker.length(), close), with(outer, style), out);
                    i = close + marker.length();
                    continue;
                }
            }
            plain.append(c);
            i++;
        }
        emit(plain, outer, out);
    }

    /** A marker opens only before a non-space, and "_" only at a word start (so snake_case stays). */
    private static boolean opensAt(String text, int i, String marker) {
        int after = i + marker.length();
        if (after >= text.length() || Character.isWhitespace(text.charAt(after))) return false;
        if (marker.equals("_") && i > 0 && (Character.isLetterOrDigit(text.charAt(i - 1)) || text.charAt(i - 1) == '\\')) return false;
        return true;
    }

    private static int closing(String text, int from, String marker) {
        int at = from;
        while (true) {
            int close = text.indexOf(marker, at);
            if (close < 0) return -1;
            // "***" ends an inner *italic* and then the **bold**: the bold closes on the last two stars
            if (marker.equals("**")) while (close + 2 < text.length() && text.charAt(close + 2) == '*') close++;
            boolean single = marker.length() == 1;
            boolean doubled = single && marker.equals("*") && close + 1 < text.length() && text.charAt(close + 1) == '*';
            if (close > from && !Character.isWhitespace(text.charAt(close - 1)) && !doubled
                    && (!marker.equals("_") || close + 1 >= text.length() || !Character.isLetterOrDigit(text.charAt(close + 1)))) {
                return close;
            }
            at = close + (doubled ? 2 : 1);
        }
    }

    private static Set<Style> with(Set<Style> outer, Style s) {
        Set<Style> set = outer.isEmpty() ? EnumSet.noneOf(Style.class) : EnumSet.copyOf(outer);
        set.add(s);
        return set;
    }

    private static void emit(StringBuilder plain, Set<Style> styles, List<Span> out) {
        if (plain.isEmpty()) return;
        out.add(new Span(plain.toString(), styles.isEmpty() ? EnumSet.noneOf(Style.class) : EnumSet.copyOf(styles)));
        plain.setLength(0);
    }

    private static List<Span> merge(List<Span> spans) {
        List<Span> out = new ArrayList<>();
        for (Span s : spans) {
            if (!out.isEmpty() && out.get(out.size() - 1).styles().equals(s.styles()) && !s.has(Style.LINK)
                    && !out.get(out.size() - 1).has(Style.LINK)) {
                Span last = out.remove(out.size() - 1);
                out.add(new Span(last.text() + s.text(), last.styles()));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ code blocks

    private static final Set<String> KEYWORDS = Set.of(
            // Java, Kotlin, C-family, JavaScript/TypeScript, Python, Go, Rust, shell: the common ones
            "abstract", "and", "as", "async", "await", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "def", "default", "del", "do", "double", "elif", "else", "enum", "except", "export",
            "extends", "false", "final", "finally", "float", "fn", "for", "from", "func", "function", "go", "if",
            "impl", "implements", "import", "in", "instanceof", "int", "interface", "is", "lambda", "let", "long",
            "match", "mod", "mut", "new", "nil", "None", "not", "null", "or", "package", "pass", "private",
            "protected", "pub", "public", "raise", "record", "return", "self", "short", "static", "struct", "super",
            "switch", "synchronized", "this", "throw", "throws", "trait", "True", "False", "true", "try", "type",
            "typeof", "use", "val", "var", "void", "volatile", "while", "with", "yield", "fi", "then", "done", "echo");

    private static final Pattern TOKENS = Pattern.compile(
            "(?<comment>//[^\\n]*|#(?![!{])[^\\n]*|/\\*[\\s\\S]*?\\*/|--[^\\n]*)"
                    + "|(?<string>\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'|`[^`]*`)"
                    + "|(?<number>\\b\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[lLfFdD]?\\b|\\b0x[0-9a-fA-F]+\\b)"
                    + "|(?<word>[A-Za-z_][A-Za-z0-9_]*)");

    /**
     * Colours a code block: comments, strings, numbers and keywords of the common languages. It is
     * a light tokenizer, not a parser, so odd code may be coloured imperfectly but never lost.
     */
    static List<Span> highlight(String code, String language) {
        List<Span> out = new ArrayList<>();
        boolean hashComments = !Set.of("c", "cpp", "c++", "cs", "c#", "java", "js", "javascript", "ts", "typescript",
                "go", "rust", "rs", "kotlin", "kt", "swift").contains(language);
        boolean dashComments = Set.of("sql", "lua", "haskell", "hs").contains(language);
        Matcher m = TOKENS.matcher(code);
        int at = 0;
        while (m.find()) {
            String comment = m.group("comment");
            if (comment != null && ((comment.startsWith("#") && !hashComments) || (comment.startsWith("--") && !dashComments))) {
                // not a comment in this language: colour only what follows normally
                if (m.start() > at) out.add(plainCode(code.substring(at, m.start())));
                out.add(plainCode(comment.substring(0, 1)));
                at = m.start() + 1;
                m.region(at, code.length());
                continue;
            }
            if (m.start() > at) out.add(plainCode(code.substring(at, m.start())));
            Style style = comment != null ? Style.COMMENT : m.group("string") != null ? Style.STRING
                    : m.group("number") != null ? Style.NUMBER
                    : KEYWORDS.contains(m.group("word")) ? Style.KEYWORD : null;
            out.add(style == null ? plainCode(m.group()) : new Span(m.group(), EnumSet.of(Style.CODE, style)));
            at = m.end();
        }
        if (at < code.length()) out.add(plainCode(code.substring(at)));
        return merge(out);
    }

    private static Span plainCode(String text) {
        return new Span(text, EnumSet.of(Style.CODE));
    }
}
