package com.emirchat.ui;

import com.emirchat.client.ChatClient;
import java.util.Random;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * "Watch it decrypt": the exact bytes the server stored and passed on for a message, then the
 * text appearing out of them letter by letter, with the layers that protect it. The bytes are
 * real (kept in memory for this session's messages); the letter-by-letter part is a picture of
 * what the keys on the two ends do, not a live decryption.
 */
final class DecryptView {
    private static final int SHOWN_BYTES = 384;
    private static final int PER_LINE = 16;

    private DecryptView() {}

    static VBox dialog(ChatClient.Sealed sealed, String plain, Runnable close) {
        byte[] c = sealed.ciphertext();
        StringBuilder hex = new StringBuilder();
        int shown = Math.min(c.length, SHOWN_BYTES);
        for (int i = 0; i < shown; i++) {
            if (i > 0) hex.append(i % PER_LINE == 0 ? '\n' : ' ');
            hex.append(String.format("%02x", c[i] & 0xff));
        }
        if (c.length > shown) hex.append("\n…");
        Label bytes = UiKit.label(hex.toString(), "cipher-bytes");
        bytes.setId("cipher-bytes");
        Label caption = UiKit.label(I18n.t("decrypt.serverSaw", c.length), "caption");
        caption.setWrapText(true);
        Label layers = UiKit.label(I18n.t(sealed.group() ? "decrypt.layersGroup" : "decrypt.layersDirect"), "cipher-layers");
        layers.setWrapText(true);
        Label text = UiKit.label("", "cipher-plain");
        text.setId("cipher-plain");
        text.setWrapText(true);
        text.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        Label note = UiKit.label(I18n.t("decrypt.note"), "caption");
        note.setWrapText(true);
        note.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        javafx.scene.control.Button done = new javafx.scene.control.Button(I18n.t("common.done"));
        done.getStyleClass().add("primary");
        done.setId("decrypt-done");
        Timeline reveal = reveal(text, plain);
        done.setOnAction(e -> {
            reveal.stop();
            close.run();
        });
        VBox box = Overlay.dialog(I18n.t("decrypt.title"), caption, bytes, layers, text, note,
                new javafx.scene.layout.HBox(UiKit.grow(), done));
        box.setId("decrypt-dialog");
        box.setMaxWidth(560);
        reveal.play();
        return box;
    }

    /** Scrambled hex at first; from the left, each place settles on its real letter. */
    private static Timeline reveal(Label target, String plain) {
        String text = plain.length() > 600 ? plain.substring(0, 600) + "…" : plain;
        Random random = new Random();
        char[] noise = "0123456789abcdef".toCharArray();
        int steps = Math.max(12, Math.min(60, text.length()));
        int[] step = {0};
        Timeline t = new Timeline();
        if (!Motion.enabled()) {
            target.setText(text);
            return t;
        }
        t.getKeyFrames().add(new KeyFrame(Duration.millis(45), e -> {
            step[0]++;
            int settled = (int) Math.round((double) text.length() * step[0] / steps);
            StringBuilder b = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                b.append(i < settled || Character.isWhitespace(ch) ? ch : noise[random.nextInt(noise.length)]);
            }
            target.setText(b.toString());
        }));
        t.setCycleCount(steps);
        t.setDelay(Duration.millis(700)); // first look at the bytes
        t.setOnFinished(e -> target.setText(text));
        return t;
    }
}
