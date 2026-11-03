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
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", I18n.locale());

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

    /** How much we trust the key behind an avatar; decides the ring around it. */
    enum Trust { UNKNOWN, KNOWN, VERIFIED, CHANGED }

    /** Where avatars look up keys: set by the logged-in window, empty before that. */
    interface Keys {
        /** The user's current key fingerprint (hex), or null if we have none. */
        String fingerprint(String user);

        Trust trust(String user);
    }

    private static volatile Keys keys;

    private static final Keys NO_KEYS = new Keys() {
        @Override public String fingerprint(String user) { return null; }

        @Override public Trust trust(String user) { return Trust.UNKNOWN; }
    };

    static {
        keys = NO_KEYS;
    }

    /** {@code null} goes back to name-only avatars (after logging out). */
    static void useKeys(Keys k) {
        keys = k == null ? NO_KEYS : k;
    }

    /** The colour of someone's name in a group: the first hue of their avatar, so the two match. */
    static Color nameColor(String user, boolean dark) {
        String fp = keys.fingerprint(user);
        double hue = FingerprintArt.avatarHues(fp != null ? fp : FingerprintArt.pseudoFingerprint(user))[0];
        return dark ? Color.hsb(hue, 0.50, 0.92) : Color.hsb(hue, 0.75, 0.55);
    }

    static double avatarSize(String sizeClass) {
        return switch (sizeClass) {
            case "avatar-xs" -> 30;
            case "avatar-sm" -> 40;
            case "avatar-lg" -> 72;
            default -> 38;
        };
    }

    /**
     * A person's avatar, drawn from their key: a mirrored pattern over a two-hue gradient (see
     * {@link FingerprintArt#avatarGrid}). Because the picture comes from the key, a new key gives
     * a new face. Verified friends get an accent ring, a changed key a red dashed ring. Without a
     * known key the picture comes from the name and shows the initial, so it never looks verified.
     * Groups show "#" over a gradient.
     */
    static StackPane avatar(String name, String sizeClass, boolean group) {
        double size = avatarSize(sizeClass);
        String fp = group ? null : keys.fingerprint(name);
        Trust trust = group ? Trust.UNKNOWN : fp == null ? Trust.UNKNOWN : keys.trust(name);
        String source = fp != null ? fp : FingerprintArt.pseudoFingerprint((group ? "group:" : "") + name);
        javafx.scene.canvas.Canvas canvas = new javafx.scene.canvas.Canvas(size, size);
        paintAvatar(canvas.getGraphicsContext2D(), size, source, fp != null && !group);
        StackPane a = new StackPane(canvas);
        a.getStyleClass().addAll("avatar-art", sizeClass);
        if (fp == null) {
            Label letter = new Label(group ? "#" : name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT));
            letter.getStyleClass().add("avatar-letter");
            a.getChildren().add(letter);
        }
        if (trust == Trust.VERIFIED || trust == Trust.CHANGED) {
            javafx.scene.shape.Circle ring = new javafx.scene.shape.Circle(size / 2 + 2.5);
            ring.getStyleClass().addAll("avatar-ring", trust == Trust.VERIFIED ? "verified" : "changed");
            ring.setMouseTransparent(true);
            a.getChildren().add(0, ring);
            a.setUserData(trust);
        }
        a.setMinSize(size, size);
        a.setPrefSize(size, size);
        a.setMaxSize(size, size);
        return a;
    }

    private static void paintAvatar(javafx.scene.canvas.GraphicsContext g, double size, String fp, boolean fromKey) {
        double[] hues = FingerprintArt.avatarHues(fp);
        g.setFill(new javafx.scene.paint.LinearGradient(0, 0, 1, 1, true, javafx.scene.paint.CycleMethod.NO_CYCLE,
                new javafx.scene.paint.Stop(0, Color.hsb(hues[0], 0.58, 0.78)),
                new javafx.scene.paint.Stop(1, Color.hsb(hues[1], 0.70, 0.46))));
        g.fillOval(0, 0, size, size);
        if (!fromKey) return; // name-only avatars stay plain, with the initial on top
        boolean[][] grid = FingerprintArt.avatarGrid(fp);
        int n = FingerprintArt.AVATAR_GRID;
        double inner = size * 0.56;           // the pattern fits inside the circle
        double cell = inner / n;
        double origin = (size - inner) / 2;
        g.setFill(Color.rgb(255, 250, 240, 0.92));
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (!grid[r][c]) continue;
                double gap = Math.max(0.6, cell * 0.12);
                g.fillRoundRect(origin + c * cell + gap / 2, origin + r * cell + gap / 2, cell - gap, cell - gap,
                        cell * 0.45, cell * 0.45);
            }
        }
    }

    /** A community's icon on the rail: its initials on a rounded square in its own colours. */
    static Node communityIcon(String name, long id) {
        double size = 42;
        double[] hues = FingerprintArt.avatarHues(FingerprintArt.pseudoFingerprint("community:" + id));
        Region square = new Region();
        square.setMinSize(size, size);
        square.setMaxSize(size, size);
        square.setStyle("-fx-background-radius: 14; -fx-background-color: linear-gradient(to bottom right, "
                + css(Color.hsb(hues[0], 0.55, 0.80)) + ", " + css(Color.hsb(hues[1], 0.70, 0.48)) + ");");
        StringBuilder initials = new StringBuilder();
        for (String word : name.strip().split("\\s+")) {
            if (!word.isEmpty() && initials.length() < 2) initials.appendCodePoint(word.codePointAt(0));
        }
        Label text = new Label(initials.toString().toUpperCase(I18n.locale()));
        text.getStyleClass().add("community-initials");
        StackPane icon = new StackPane(square, text);
        icon.setMinSize(size, size);
        icon.setMaxSize(size, size);
        return icon;
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
