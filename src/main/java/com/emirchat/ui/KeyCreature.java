package com.emirchat.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;

/**
 * A scorpion born of a key: colour, shell pattern, claws, body and the curl of the tail all come
 * from the key's fingerprint, so the same key always gives the same scorpion and a different key
 * a different one. Next to the fingerprint it makes a changed key hard to miss: a stranger's key
 * shows a scorpion you have never seen.
 */
final class KeyCreature {
    /** What the fingerprint decides. */
    record Traits(double hue, int pattern, double claw, int segments, double curl, double legs, double sat) {}

    private KeyCreature() {}

    static Traits traits(String fingerprintHex) {
        String hex = fingerprintHex.replaceAll("[^0-9a-fA-F]", "");
        if (hex.length() < 16) hex = (hex + "0000000000000000").substring(0, 16);
        int[] b = new int[8];
        for (int i = 0; i < 8; i++) b[i] = Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return new Traits(
                b[0] * 360.0 / 256,                 // shell colour
                b[1] % 4,                           // plain, stripes, spots, rings
                0.75 + (b[2] / 255.0) * 0.6,        // claw size
                5 + b[3] % 3,                       // body segments
                0.6 + (b[4] / 255.0) * 0.9,         // how far the tail curls over
                0.8 + (b[5] / 255.0) * 0.5,         // leg length
                0.45 + (b[6] / 255.0) * 0.4);       // how vivid
    }

    /** A canvas of the scorpion, its tail swaying a little while it is on screen. */
    static Canvas of(String fingerprintHex, double size) {
        Traits t = traits(fingerprintHex);
        Canvas c = new Canvas(size, size);
        c.getStyleClass().add("key-creature");
        c.setAccessibleText(I18n.t("creature.accessible"));
        draw(c.getGraphicsContext2D(), t, size, 0);
        if (!Motion.enabled()) return c;
        AnimationTimer sway = new AnimationTimer() {
            private long first = -1;

            @Override public void handle(long now) {
                if (first < 0) first = now;
                draw(c.getGraphicsContext2D(), t, size, (now - first) / 1e9);
            }
        };
        c.sceneProperty().addListener((o, a, scene) -> {
            if (scene == null) sway.stop();
            else sway.start();
        });
        return c;
    }

    /** Seen from above, head down, tail up and over. */
    static void draw(GraphicsContext g, Traits t, double size, double time) {
        g.clearRect(0, 0, size, size);
        double s = size / 100;
        Color shell = Color.hsb(t.hue(), t.sat(), 0.72);
        Color dark = Color.hsb(t.hue(), Math.min(1, t.sat() + 0.2), 0.35);
        Color light = Color.hsb(t.hue(), t.sat() * 0.5, 0.92);
        g.save();
        g.scale(s, s);
        g.setLineCap(StrokeLineCap.ROUND);
        double cx = 50;
        double headY = 66;
        // legs: four a side
        g.setStroke(dark);
        g.setLineWidth(2.2);
        for (int i = 0; i < 4; i++) {
            double y = headY - 4 - i * 6;
            double reach = 16 * t.legs();
            double step = Math.sin(time * 3 + i) * 1.2;
            for (int side = -1; side <= 1; side += 2) {
                g.strokeLine(cx + side * 6, y, cx + side * (6 + reach * 0.6), y - 4 + step);
                g.strokeLine(cx + side * (6 + reach * 0.6), y - 4 + step, cx + side * (6 + reach), y + 6 + step);
            }
        }
        // claws: an arm and a pincer each side
        double claw = t.claw();
        g.setStroke(shell);
        g.setLineWidth(3.2);
        for (int side = -1; side <= 1; side += 2) {
            double ex = cx + side * 18;
            double ey = headY + 10;
            g.strokeLine(cx + side * 5, headY + 2, ex, ey);
            g.setFill(shell);
            g.fillOval(ex - 6 * claw, ey, 12 * claw, 14 * claw);
            g.setStroke(dark);
            g.setLineWidth(1.4);
            g.strokeLine(ex, ey + 14 * claw, ex + side * 2, ey + 6 * claw); // the pincer's gap
            g.setStroke(shell);
            g.setLineWidth(3.2);
        }
        // body: head and segments going up
        g.setFill(shell);
        g.fillOval(cx - 8, headY - 4, 16, 12);
        double y = headY - 4;
        for (int i = 0; i < t.segments(); i++) {
            double w = 18 - i * 0.8;
            double h = 7;
            y -= h - 1;
            g.setFill(shell);
            g.fillRoundRect(cx - w / 2, y, w, h, 5, 5);
            switch (t.pattern()) {
                case 1 -> { g.setFill(dark); g.fillRect(cx - w / 2 + 2, y + 2.5, w - 4, 1.6); }
                case 2 -> { g.setFill(light); g.fillOval(cx - 4, y + 2, 2.6, 2.6); g.fillOval(cx + 1.5, y + 2, 2.6, 2.6); }
                case 3 -> { g.setStroke(light); g.setLineWidth(1); g.strokeRoundRect(cx - w / 2 + 1.5, y + 1.2, w - 3, h - 2.4, 4, 4); }
                default -> { }
            }
        }
        // tail: segments bending over, sway with time
        double px = cx;
        double py = y;
        double angle = -Math.PI / 2;
        double sway = Math.sin(time * 1.6) * 0.06;
        g.setStroke(shell);
        for (int i = 0; i < 5; i++) {
            angle += (0.16 + sway) * t.curl() * (i + 1) * 0.5;
            double nx = px + Math.cos(angle) * 7;
            double ny = py + Math.sin(angle) * 7;
            g.setLineWidth(5 - i * 0.5);
            g.strokeLine(px, py, nx, ny);
            px = nx;
            py = ny;
        }
        g.setFill(dark);
        g.fillOval(px - 3.5, py - 3.5, 7, 7); // the sting
        g.setFill(light);
        g.fillOval(cx - 4, headY - 1, 2.4, 2.4); // eyes
        g.fillOval(cx + 1.6, headY - 1, 2.4, 2.4);
        g.restore();
    }
}
