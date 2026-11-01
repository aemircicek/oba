package com.emirchat.ui;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

/** Small shared building blocks for the account app. */
final class UiKit {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private UiKit() {}

    /** A stable colour per name (not security relevant; fingerprints use FingerprintArt). */
    static Color colorFor(String name) {
        int h = 0;
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) h = h * 31 + b;
        return Color.hsb(Math.floorMod(h, 360), 0.5, 0.72);
    }

    static String css(Color c) {
        return String.format("#%02x%02x%02x", (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
    }

    /** Circle with the first letter; {@code sizeClass} is avatar, avatar-sm or avatar-xs. */
    static StackPane avatar(String name, String sizeClass, boolean group) {
        Label letter = new Label(group ? "#" : name.substring(0, 1).toUpperCase(Locale.ROOT));
        letter.getStyleClass().add("avatar-letter");
        StackPane a = new StackPane(letter);
        a.getStyleClass().add(sizeClass);
        a.setStyle("-fx-background-color: " + css(colorFor(group ? "group:" + name : name)) + ";");
        return a;
    }

    /** Avatar with an online/offline dot in the corner. */
    static Node avatarWithPresence(String name, boolean online) {
        StackPane a = avatar(name, "avatar-sm", false);
        Region dot = new Region();
        dot.getStyleClass().addAll("presence-dot", online ? "online" : "offline");
        StackPane wrap = new StackPane(a, dot);
        StackPane.setAlignment(dot, Pos.BOTTOM_RIGHT);
        wrap.setMaxSize(40, 40);
        return wrap;
    }

    static String time(long millis) {
        var z = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault());
        return z.toLocalDate().equals(LocalDate.now()) ? TIME.format(z) : DAY.format(z);
    }

    static String clock(long millis) {
        return TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    static Label label(String text, String... styles) {
        Label l = new Label(text);
        l.getStyleClass().addAll(styles);
        return l;
    }

    static Region grow() {
        Region r = new Region();
        javafx.scene.layout.HBox.setHgrow(r, javafx.scene.layout.Priority.ALWAYS);
        javafx.scene.layout.VBox.setVgrow(r, javafx.scene.layout.Priority.ALWAYS);
        return r;
    }
}
