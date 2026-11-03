package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.ui.Markdown.Kind;
import com.emirchat.ui.Markdown.Span;
import com.emirchat.ui.Markdown.Style;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MarkdownTest {
    private static String text(List<Span> spans) {
        return spans.stream().map(Span::text).collect(Collectors.joining());
    }

    private static Span spanWith(List<Span> spans, String text) {
        return spans.stream().filter(s -> s.text().equals(text)).findFirst().orElseThrow(() -> new AssertionError(spans.toString()));
    }

    @Test
    void boldItalicStrikeAndCode() {
        List<Span> s = Markdown.inline("a **bold** and *it* or _it2_ ~~gone~~ `x = 1`");
        assertTrue(spanWith(s, "bold").has(Style.BOLD));
        assertTrue(spanWith(s, "it").has(Style.ITALIC));
        assertTrue(spanWith(s, "it2").has(Style.ITALIC));
        assertTrue(spanWith(s, "gone").has(Style.STRIKE));
        assertTrue(spanWith(s, "x = 1").has(Style.CODE));
        assertEquals("a bold and it or it2 gone x = 1", text(s));
    }

    @Test
    void nestedStyles() {
        List<Span> s = Markdown.inline("**bold *both***");
        assertTrue(spanWith(s, "both").has(Style.BOLD));
        assertTrue(spanWith(s, "both").has(Style.ITALIC));
    }

    @Test
    void unclosedOrSpacedMarkersStayText() {
        assertEquals("2 * 3 * 4", text(Markdown.inline("2 * 3 * 4")));
        assertEquals(1, Markdown.inline("2 * 3 * 4").size());
        assertEquals("**not closed", text(Markdown.inline("**not closed")));
        assertEquals("snake_case_name", text(Markdown.inline("snake_case_name")));
        assertEquals(1, Markdown.inline("snake_case_name").size());
    }

    @Test
    void codeIsNotFormattedInside() {
        List<Span> s = Markdown.inline("`**raw**`");
        assertEquals(1, s.size());
        assertEquals("**raw**", s.get(0).text());
        assertFalse(s.get(0).has(Style.BOLD));
    }

    @Test
    void links() {
        List<Span> s = Markdown.inline("see https://example.org/a?b=1, ok");
        assertTrue(spanWith(s, "https://example.org/a?b=1").has(Style.LINK));
        assertEquals("see https://example.org/a?b=1, ok", text(s));
    }

    @Test
    void codeBlockWithHighlighting() {
        List<Markdown.Block> blocks = Markdown.parse("look:\n```java\nint x = 42; // answer\nString s = \"hi\";\n```\ndone");
        assertEquals(3, blocks.size());
        assertEquals(Kind.CODE, blocks.get(1).kind());
        assertEquals("java", blocks.get(1).language());
        List<Span> code = blocks.get(1).spans();
        assertEquals("int x = 42; // answer\nString s = \"hi\";", text(code));
        assertTrue(spanWith(code, "int").has(Style.KEYWORD));
        assertTrue(spanWith(code, "42").has(Style.NUMBER));
        assertTrue(spanWith(code, "// answer").has(Style.COMMENT));
        assertTrue(spanWith(code, "\"hi\"").has(Style.STRING));
        assertTrue(code.stream().allMatch(sp -> sp.has(Style.CODE)));
    }

    @Test
    void hashIsACommentInPythonButNotInJava() {
        assertTrue(spanWith(Markdown.highlight("x = 1 # note", "python"), "# note").has(Style.COMMENT));
        assertTrue(Markdown.highlight("a # b", "java").stream().noneMatch(sp -> sp.has(Style.COMMENT)));
    }

    @Test
    void unclosedFenceKeepsEverything() {
        List<Markdown.Block> blocks = Markdown.parse("```\nline 1\nline 2");
        assertEquals(1, blocks.size());
        assertEquals("line 1\nline 2", text(blocks.get(0).spans()));
    }

    @Test
    void quotesAndParagraphs() {
        List<Markdown.Block> blocks = Markdown.parse("> quoted\n> more\nanswer\nsecond line");
        assertEquals(Kind.QUOTE, blocks.get(0).kind());
        assertEquals("quoted\nmore", text(blocks.get(0).spans()));
        assertEquals(Kind.PARAGRAPH, blocks.get(1).kind());
        assertEquals("answer\nsecond line", text(blocks.get(1).spans()));
    }

    @Test
    void plainTextIsDetected() {
        assertFalse(Markdown.hasFormatting("Selam! Evet, 8'de olur."));
        assertTrue(Markdown.hasFormatting("**önemli**"));
    }

    @Test
    void plainStripsMarksForPreviews() {
        assertEquals("Not: son hali kat-plani.png", Markdown.plain("**Not:** son hali `kat-plani.png`"));
        assertEquals("a b c", Markdown.plain("a\nb\nc"));
        assertEquals("Selam!", Markdown.plain("Selam!"));
    }

    @Test
    void mentions() {
        List<Span> s = Markdown.inline("@efe ve @ayse., bakar mısınız? mail: a@efe.com");
        assertTrue(spanWith(s, "@efe").has(Style.MENTION));
        assertTrue(spanWith(s, "@ayse").has(Style.MENTION), "a final dot is punctuation");
        assertEquals("@efe ve @ayse., bakar mısınız? mail: a@efe.com", text(s));
        assertEquals(2, s.stream().filter(x -> x.has(Style.MENTION)).count(), "not inside an e-mail address");
        assertTrue(Markdown.mentions("Selam @Emir!", "emir"));
        assertFalse(Markdown.mentions("emir@example.org", "emir"));
        assertFalse(Markdown.mentions("@emirhan", "emir"), "a longer name is someone else");
        assertFalse(Markdown.mentions("`@emir`", "emir"), "not inside code");
    }
}
