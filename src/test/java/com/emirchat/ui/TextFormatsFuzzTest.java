package com.emirchat.ui;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

/** Text a friend wrote, shown by the app: formatting, links, mentions, search words. */
class TextFormatsFuzzTest {
    @FuzzTest(maxDuration = "1m")
    void markdownAndSearch(FuzzedDataProvider in) {
        String text = in.consumeRemainingAsString();
        Markdown.parse(text);
        String plain = Markdown.plain(text);
        if (plain == null) throw new AssertionError();
        for (String url : Markdown.links(text)) {
            if (!(url.startsWith("http://") || url.startsWith("https://"))) throw new AssertionError("not a web link: " + url);
        }
        Markdown.mentions(text, "emir");
        Sidebar.matches(text, com.emirchat.client.History.Query.parse(text).words());
        com.emirchat.client.SearchFold.fold(text);
    }

    @FuzzTest(maxDuration = "1m")
    void slashCommandsAndSettings(FuzzedDataProvider in) {
        String text = in.consumeString(500);
        SlashCommands.parse(text, "emir", new java.util.Random(in.consumeLong()));
        NotifyRules.Quiet.decode(in.consumeString(100));
        AccountSession.parseZone(in.consumeString(80));
        AccountSession.parseChoice(in.consumeRemainingAsString());
    }

    @FuzzTest(maxDuration = "2m")
    void imagesAndVoice(byte[] data) {
        try {
            ImageFiles.decode(data); // pictures from friends: ImageIO behind limits
        } catch (RuntimeException e) {
            if (!(e instanceof IllegalArgumentException)) throw e;
        }
        try {
            com.emirchat.call.VoiceNotes.decode(data);
        } catch (java.io.IOException ignored) {
            // refused
        }
    }
}
