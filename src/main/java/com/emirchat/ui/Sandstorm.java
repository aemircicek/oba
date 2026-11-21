package com.emirchat.ui;

import java.util.Random;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/**
 * The sandstorm: a privacy curtain against someone looking over your shoulder. A shortcut (or,
 * if chosen in the settings, leaving the window) blurs everything and blows sand over it; moving
 * the mouse over the window, a key or a click clears it.
 *
 * <p>Not a security boundary: the app keeps running and nothing is locked. The curtain says so.
 */
final class Sandstorm {
    static final String SHORTCUT_TEXT = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")
            ? "⌘⇧H" : "Ctrl+Shift+H";
    static final javafx.scene.input.KeyCombination SHORTCUT = new javafx.scene.input.KeyCodeCombination(
            javafx.scene.input.KeyCode.H, javafx.scene.input.KeyCombination.SHORTCUT_DOWN, javafx.scene.input.KeyCombination.SHIFT_DOWN);
    /** Movement right after the storm rose (the hand still on the mouse) does not clear it. */
    private static final long GRACE_MS = 600;

    private final StackPane host;
    private StackPane curtain;
    private AnimationTimer wind;
    private long raisedAt;
    private java.util.List<Node> blurred = java.util.List.of();

    /** @param host the window's top layer: everything in it is blurred and the curtain goes on top */
    Sandstorm(StackPane host) {
        this.host = host;
    }

    boolean raised() {
        return curtain != null;
    }

    void toggle() {
        if (raised()) clear();
        else raise();
    }

    void raise() {
        if (raised()) return;
        raisedAt = System.currentTimeMillis();
        // everything in the window blurs, open dialogs too
        blurred = new java.util.ArrayList<>(host.getChildren());
        GaussianBlur blur = new GaussianBlur(28);
        for (Node n : blurred) n.setEffect(blur);
        Canvas sand = new Canvas();
        sand.widthProperty().bind(host.widthProperty());
        sand.heightProperty().bind(host.heightProperty());
        Label title = UiKit.label("🌪  " + I18n.t("storm.title"), "storm-title");
        Label hint = UiKit.label(I18n.t("storm.hint", SHORTCUT_TEXT), "storm-hint");
        hint.setWrapText(true);
        hint.setMaxWidth(420);
        VBox words = new VBox(8, title, hint);
        words.setAlignment(Pos.CENTER);
        words.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
        curtain = new StackPane(sand, words);
        curtain.getStyleClass().add("sandstorm");
        curtain.setId("sandstorm");
        curtain.setOnMouseMoved(e -> {
            if (System.currentTimeMillis() - raisedAt > GRACE_MS) clear();
        });
        curtain.setOnMouseClicked(e -> clear());
        curtain.setOnKeyPressed(e -> clear());
        host.getChildren().add(curtain);
        curtain.setFocusTraversable(true);
        curtain.requestFocus();
        Random random = new Random();
        double[][] grains = new double[700][];
        for (int i = 0; i < grains.length; i++) grains[i] = grain(random, true);
        if (Motion.enabled()) {
            FadeTransition in = new FadeTransition(Duration.millis(350), curtain);
            in.setFromValue(0);
            in.setToValue(1);
            in.play();
        }
        wind = new AnimationTimer() {
            private long last = -1;

            @Override public void handle(long now) {
                double dt = last < 0 ? 0 : Math.min(0.05, (now - last) / 1e9);
                last = now;
                GraphicsContext g = sand.getGraphicsContext2D();
                double w = sand.getWidth();
                double h = sand.getHeight();
                g.clearRect(0, 0, w, h);
                for (double[] p : grains) {
                    if (Motion.enabled()) {
                        p[0] += p[2] * dt;
                        p[1] += p[3] * dt + Math.sin(now / 3e8 + p[4]) * 0.4;
                    }
                    if (p[0] > 1.05) {
                        double[] fresh = grain(random, false);
                        System.arraycopy(fresh, 0, p, 0, p.length);
                    }
                    g.setFill(Color.rgb(222, 178, 112, p[5]));
                    g.fillOval(p[0] * w, p[1] * h, p[6], p[6] * 0.8);
                }
            }
        };
        wind.start();
    }

    /** x, y (fractions), vx, vy (fractions a second), phase, opacity, size. */
    private static double[] grain(Random r, boolean anywhere) {
        return new double[] {anywhere ? r.nextDouble() : -0.05, r.nextDouble(), 0.35 + r.nextDouble() * 0.6,
                (r.nextDouble() - 0.4) * 0.05, r.nextDouble() * 6, 0.25 + r.nextDouble() * 0.55, 1 + r.nextDouble() * 2.6};
    }

    void clear() {
        if (!raised()) return;
        StackPane c = curtain;
        curtain = null;
        if (wind != null) wind.stop();
        for (Node n : blurred) n.setEffect(null);
        blurred = java.util.List.of();
        if (!Motion.enabled()) {
            host.getChildren().remove(c);
            return;
        }
        FadeTransition out = new FadeTransition(Duration.millis(300), c);
        out.setToValue(0);
        out.setOnFinished(e -> host.getChildren().remove(c));
        out.play();
    }
}
