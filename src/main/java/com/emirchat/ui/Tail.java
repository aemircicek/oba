package com.emirchat.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.Group;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;

/**
 * The scorpion's tail from the app icon, as a small live shape: a chain of segments that curls
 * up by {@link #setCurl}, ending in a venom bulb and a stinger. Colours come from CSS
 * ({@code .tail-part}, {@code .tail.danger} ...), so it follows the theme and accent.
 *
 * <ul>
 *   <li>{@link #spinner} curls and uncurls while something loads,</li>
 *   <li>{@link #status} stands raised while connected and droops (pulsing) while reconnecting.</li>
 * </ul>
 */
final class Tail extends Group {
    private static final int SEGMENTS = 6;
    private final double size;
    private final Circle[] segments = new Circle[SEGMENTS];
    private final Circle bulb = new Circle();
    private final Polygon sting = new Polygon();
    private AnimationTimer timer;
    private double curl = 1;

    Tail(double size) {
        this.size = size;
        getStyleClass().add("tail");
        // a fixed, invisible frame so layout does not jump while the tail moves
        Rectangle frame = new Rectangle(size, size);
        frame.setFill(javafx.scene.paint.Color.TRANSPARENT);
        frame.setMouseTransparent(true);
        getChildren().add(frame);
        for (int i = 0; i < SEGMENTS; i++) {
            segments[i] = new Circle();
            segments[i].getStyleClass().add("tail-part");
            getChildren().add(segments[i]);
        }
        bulb.getStyleClass().addAll("tail-part", "tail-bulb");
        sting.getStyleClass().addAll("tail-part", "tail-sting");
        getChildren().addAll(bulb, sting);
        setMouseTransparent(true);
        layoutTail();
    }

    /** 0: lying almost flat, 1: fully raised over the back, ready to strike. */
    void setCurl(double value) {
        curl = Math.max(0, Math.min(1, value));
        layoutTail();
    }

    double curl() {
        return curl;
    }

    /**
     * Places the segments: starting at the bottom left, each one turns a little further than
     * the last (more when curled), so the chain bends into the familiar arch.
     */
    private void layoutTail() {
        double x = size * 0.22;
        double y = size * 0.92;
        double angle = -Math.PI / 2 + 0.35 * (1 - curl); // first segment points up (leans right when flat)
        double step = 0.20 + 0.42 * curl;
        double radius = size * 0.105;
        for (int i = 0; i < SEGMENTS; i++) {
            Circle c = segments[i];
            c.setCenterX(x);
            c.setCenterY(y);
            c.setRadius(radius);
            double length = radius * 1.55;
            x += Math.cos(angle) * length;
            y += Math.sin(angle) * length;
            angle += step;
            radius *= 0.90;
        }
        // the bulb hangs at the tip, the stinger points onward along the curve
        double bulbR = radius * 1.25;
        bulb.setCenterX(x);
        bulb.setCenterY(y);
        bulb.setRadius(bulbR);
        double ax = Math.cos(angle);
        double ay = Math.sin(angle);
        double tipX = x + ax * bulbR * 2.4;
        double tipY = y + ay * bulbR * 2.4;
        double nx = -ay * bulbR * 0.7;
        double ny = ax * bulbR * 0.7;
        sting.getPoints().setAll(x + nx, y + ny, tipX, tipY, x - nx, y - ny);
    }

    /** A loading indicator: the tail curls and uncurls continuously. */
    static Tail spinner(double size) {
        Tail t = new Tail(size);
        t.getStyleClass().add("tail-spinner");
        t.timer = new AnimationTimer() {
            private long start = -1;

            @Override public void handle(long now) {
                if (start < 0) start = now;
                double s = (now - start) / 1e9;
                t.setCurl(0.55 + 0.45 * Math.sin(s * 3.2));
                t.setRotate(Math.sin(s * 1.6) * 8);
            }
        };
        t.sceneProperty().addListener((o, a, scene) -> {
            if (scene == null) t.timer.stop();
            else t.timer.start();
        });
        return t;
    }

    /** The connection light in the sidebar: raised when online, lowered and pulsing when not. */
    static Tail status(double size) {
        Tail t = new Tail(size);
        t.getStyleClass().add("tail-status");
        t.setOnline(true);
        return t;
    }

    void setOnline(boolean online) {
        if (timer != null) timer.stop();
        getStyleClass().removeAll("danger");
        setOpacity(1);
        if (online) {
            animateCurlTo(1);
            return;
        }
        getStyleClass().add("danger");
        timer = new AnimationTimer() {
            private long start = -1;

            @Override public void handle(long now) {
                if (start < 0) start = now;
                double s = (now - start) / 1e9;
                setCurl(Math.max(0.1, 0.35 - s * 0.6));      // droops, then rests
                setOpacity(0.55 + 0.45 * (0.5 + 0.5 * Math.cos(s * 4)));
            }
        };
        timer.start();
    }

    private void animateCurlTo(double target) {
        double from = curl;
        timer = new AnimationTimer() {
            private long start = -1;

            @Override public void handle(long now) {
                if (start < 0) start = now;
                double p = Math.min(1, (now - start) / 4.5e8);
                double eased = 1 - Math.pow(1 - p, 3);
                setCurl(from + (target - from) * eased);
                if (p >= 1) stop();
            }
        };
        timer.start();
    }
}
