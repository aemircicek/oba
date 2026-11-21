package com.emirchat.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;

/**
 * A nudge: a small scorpion walks in from the right edge, stops by the composer, stings it (the
 * composer shakes) and scuttles off. About two seconds; without motion only the shake, gently.
 */
final class Nudge {
    private Nudge() {}

    /** Plays over {@code layer} (the conversation), aiming at {@code target} (the composer). */
    static void play(StackPane layer, Node target) {
        if (!Motion.enabled()) {
            shake(target, 3);
            return;
        }
        Pane stage = new Pane();
        stage.setMouseTransparent(true);
        stage.setId("nudge");
        Canvas canvas = new Canvas();
        canvas.widthProperty().bind(layer.widthProperty());
        canvas.heightProperty().bind(layer.heightProperty());
        stage.getChildren().add(canvas);
        layer.getChildren().add(stage);
        javafx.geometry.Bounds b = target.localToScene(target.getBoundsInLocal());
        javafx.geometry.Point2D aim = layer.sceneToLocal(b.getMaxX() - 60, b.getMinY() - 16); // its feet just above the box
        double startX = layer.getWidth() + 40;
        double y = aim.getY();
        new AnimationTimer() {
            private long first = -1;
            private boolean stung;

            @Override public void handle(long now) {
                if (first < 0) first = now;
                double t = (now - first) / 1e9;
                double x;
                double sting = 0;
                boolean leaving = false;
                if (t < 0.9) {
                    x = startX + (aim.getX() - startX) * ease(t / 0.9);          // walks in
                } else if (t < 1.4) {
                    x = aim.getX();
                    sting = Math.sin((t - 0.9) / 0.5 * Math.PI);                  // the tail comes down
                    if (!stung && t > 1.12) {
                        stung = true;
                        shake(target, 6);
                    }
                } else if (t < 2.2) {
                    x = aim.getX() + (startX - aim.getX()) * ease((t - 1.4) / 0.8); // and goes
                    leaving = true;
                } else {
                    stop();
                    layer.getChildren().remove(stage);
                    return;
                }
                GraphicsContext g = canvas.getGraphicsContext2D();
                g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
                scorpion(g, x, y, t, sting, leaving);
            }
        }.start();
    }

    private static double ease(double f) {
        f = Math.max(0, Math.min(1, f));
        return f * f * (3 - 2 * f);
    }

    /** A side-on scorpion facing left (or right when leaving), legs moving with {@code t}. */
    static void scorpion(GraphicsContext g, double x, double y, double t, double sting, boolean facingRight) {
        g.save();
        g.translate(x, y);
        if (facingRight) g.scale(-1, 1);
        Color shell = Color.web("#b5651d");
        Color dark = Color.web("#5a2e0c");
        g.setLineCap(StrokeLineCap.ROUND);
        // legs
        g.setStroke(dark);
        g.setLineWidth(1.6);
        for (int i = 0; i < 4; i++) {
            double lx = -2 + i * 6;
            double swing = Math.sin(t * 22 + i * 1.7) * 2.5;
            g.strokeLine(lx, 2, lx - 3 + swing, 9);
        }
        // body segments
        g.setFill(shell);
        for (int i = 0; i < 4; i++) g.fillOval(-6 + i * 6, -4, 9, 8);
        // claws
        g.setStroke(shell);
        g.setLineWidth(2.2);
        g.strokeLine(-6, 0, -14, -3);
        g.fillOval(-20, -7, 8, 6);
        g.strokeLine(-6, 2, -13, 5);
        // tail: rises in an arc, comes down over the head when it stings
        double lift = 1 - sting;
        double px = 18;
        double py = 0;
        g.setLineWidth(3);
        for (int i = 1; i <= 5; i++) {
            // raised: an arc up and back; stinging: on over the top and down in front of the head
            double a = Math.toRadians(-40 - i * (32 * lift + 46 * sting));
            double nx = px + Math.cos(a) * 6;
            double ny = py + Math.sin(a) * 6;
            g.strokeLine(px, py, nx, ny);
            px = nx;
            py = ny;
        }
        g.setFill(dark);
        g.fillOval(px - 2.5, py - 2.5, 5, 5);
        g.restore();
    }

    /** Shakes a node left and right, quickly and less each time. */
    static void shake(Node n, double amount) {
        javafx.animation.Timeline shake = new javafx.animation.Timeline();
        for (int i = 0; i <= 6; i++) {
            double x = i == 6 ? 0 : (i % 2 == 0 ? amount : -amount) * (1 - i / 7.0);
            shake.getKeyFrames().add(new javafx.animation.KeyFrame(javafx.util.Duration.millis(40 * i),
                    new javafx.animation.KeyValue(n.translateXProperty(), x)));
        }
        shake.play();
    }
}
