package com.emirchat.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.Group;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;

/**
 * The campfire from the app icon, as a small live shape: three nested flames (orange, yellow
 * and a white-hot core) that flicker. Colours come from CSS ({@code .flame-outer} ...), so the
 * outer flame follows the accent colour.
 *
 * <ul>
 *   <li>{@link #spinner} burns while something loads,</li>
 *   <li>{@link #status} burns while connected and dies down to a red ember while reconnecting.</li>
 * </ul>
 */
final class Flame extends Group {
    private final double size;
    private final SVGPath outer = new SVGPath();
    private final SVGPath inner = new SVGPath();
    private final SVGPath core = new SVGPath();
    private final Circle ember = new Circle();
    private AnimationTimer timer;
    private double strength = 1;   // 1: burning, 0: only the ember is left

    Flame(double size) {
        this.size = size;
        getStyleClass().add("flame");
        Rectangle frame = new Rectangle(size, size); // a fixed frame: the layout never jumps
        frame.setFill(javafx.scene.paint.Color.TRANSPARENT);
        frame.setMouseTransparent(true);
        outer.getStyleClass().add("flame-outer");
        inner.getStyleClass().add("flame-inner");
        core.getStyleClass().add("flame-core");
        ember.getStyleClass().add("flame-ember");
        getChildren().addAll(frame, ember, outer, inner, core);
        setMouseTransparent(true);
        shape(0);
    }

    /** One teardrop standing on (cx, base), as SVG path data. */
    private static String drop(double cx, double base, double halfWidth, double height, double lean) {
        double top = base - height;
        double tipX = cx + lean;
        return String.format(java.util.Locale.ROOT,
                "M %.2f %.2f C %.2f %.2f %.2f %.2f %.2f %.2f C %.2f %.2f %.2f %.2f %.2f %.2f Z",
                tipX, top,
                cx + halfWidth * 1.15, base - height * 0.45, cx + halfWidth, base, cx, base,
                cx - halfWidth, base, cx - halfWidth * 1.15, base - height * 0.45, tipX, top);
    }

    /** Draws the flame at time {@code t} (seconds): heights and lean sway a little. */
    private void shape(double t) {
        double base = size * 0.94;
        double cx = size / 2;
        double s = Math.max(0, strength);
        double sway = Math.sin(t * 7.3) * size * 0.04 + Math.sin(t * 12.1) * size * 0.02;
        double flick = 1 + Math.sin(t * 9.7) * 0.06 + Math.sin(t * 15.3) * 0.04;
        outer.setContent(drop(cx, base, size * 0.30, size * 0.86 * s * flick, sway * 1.2));
        inner.setContent(drop(cx, base, size * 0.19, size * 0.58 * s * flick, sway * 0.8));
        core.setContent(drop(cx, base, size * 0.09, size * 0.30 * s * flick, sway * 0.4));
        outer.setVisible(s > 0.05);
        inner.setVisible(s > 0.25);
        core.setVisible(s > 0.45);
        ember.setCenterX(cx);
        ember.setCenterY(base - size * 0.06);
        ember.setRadius(size * (0.14 + 0.06 * (1 - s)));
    }

    /** A loading indicator: a flame that keeps flickering. */
    static Flame spinner(double size) {
        Flame f = new Flame(size);
        f.getStyleClass().add("flame-spinner");
        f.burn();
        return f;
    }

    /** The connection light: burning while online, a pulsing red ember while reconnecting. */
    static Flame status(double size) {
        Flame f = new Flame(size);
        f.getStyleClass().add("flame-status");
        f.setOnline(true);
        return f;
    }

    private void burn() {
        if (timer != null) timer.stop();
        if (!Motion.enabled()) {
            shape(0.4);
            return;
        }
        timer = new AnimationTimer() {
            private long start = -1;

            @Override public void handle(long now) {
                if (start < 0) start = now;
                shape((now - start) / 1e9);
            }
        };
        sceneProperty().addListener((o, a, scene) -> {
            if (scene == null) timer.stop();
            else timer.start();
        });
        if (getScene() != null) timer.start();
    }

    void setOnline(boolean online) {
        getStyleClass().removeAll("danger");
        if (!online) getStyleClass().add("danger");
        if (timer != null) timer.stop();
        double from = strength;
        double target = online ? 1 : 0;
        if (!Motion.enabled()) {
            strength = target;
            setOpacity(1);
            shape(0.4);
            return;
        }
        timer = new AnimationTimer() {
            private long start = -1;

            @Override public void handle(long now) {
                if (start < 0) start = now;
                double t = (now - start) / 1e9;
                double p = Math.min(1, t / 0.6);
                strength = from + (target - from) * (1 - Math.pow(1 - p, 3)); // dies down, or flares up
                setOpacity(online ? 1 : 0.6 + 0.4 * (0.5 + 0.5 * Math.cos(t * 4))); // the ember breathes
                shape(t);
            }
        };
        timer.start();
    }
}
