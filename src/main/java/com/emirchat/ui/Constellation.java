package com.emirchat.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * The trust constellation: friends as stars in the desert night, you in the middle. Friends whose
 * key you compared in person are bright and joined to you by a line; the others are faint; one
 * whose key changed flickers red until you look into it. Online friends twinkle.
 */
final class Constellation {
    enum Trust { VERIFIED, UNVERIFIED, CHANGED }

    record Star(String name, Trust trust, boolean online) {}

    static final double WIDTH = 520;
    static final double HEIGHT = 380;

    private Constellation() {}

    /** Where a friend's star is (always the same place for the same name), as fractions. */
    static double[] place(String name, int index, int count) {
        int h = name.hashCode();
        double angle = 2 * Math.PI * (index + 0.5) / Math.max(1, count) + ((h & 0xff) / 255.0 - 0.5) * 0.5;
        double r = 0.28 + (((h >>> 8) & 0xff) / 255.0) * 0.17;
        return new double[] {0.5 + Math.cos(angle) * r, 0.5 + Math.sin(angle) * r * 0.82};
    }

    /** @param open opens the chat with a friend whose star was clicked */
    static Canvas sky(String me, List<Star> stars, Consumer<String> open) {
        Canvas c = new Canvas(WIDTH, HEIGHT);
        c.setId("constellation");
        c.setAccessibleText(I18n.t("sky.accessible", stars.stream().filter(s -> s.trust() == Trust.VERIFIED).count(), stars.size()));
        List<double[]> dust = new ArrayList<>();
        java.util.Random r = new java.util.Random(me.hashCode());
        for (int i = 0; i < 140; i++) dust.add(new double[] {r.nextDouble() * WIDTH, r.nextDouble() * HEIGHT, r.nextDouble()});
        draw(c.getGraphicsContext2D(), me, stars, dust, 0);
        c.setOnMouseClicked(e -> {
            for (int i = 0; i < stars.size(); i++) {
                double[] p = place(stars.get(i).name(), i, stars.size());
                if (Math.hypot(e.getX() - p[0] * WIDTH, e.getY() - p[1] * HEIGHT) < 18) {
                    open.accept(stars.get(i).name());
                    return;
                }
            }
        });
        c.setOnMouseMoved(e -> {
            boolean over = false;
            for (int i = 0; i < stars.size() && !over; i++) {
                double[] p = place(stars.get(i).name(), i, stars.size());
                over = Math.hypot(e.getX() - p[0] * WIDTH, e.getY() - p[1] * HEIGHT) < 18;
            }
            c.setCursor(over ? javafx.scene.Cursor.HAND : javafx.scene.Cursor.DEFAULT);
        });
        if (Motion.enabled()) {
            AnimationTimer twinkle = new AnimationTimer() {
                private long first = -1;

                @Override public void handle(long now) {
                    if (first < 0) first = now;
                    draw(c.getGraphicsContext2D(), me, stars, dust, (now - first) / 1e9);
                }
            };
            c.sceneProperty().addListener((o, a, scene) -> {
                if (scene == null) twinkle.stop();
                else twinkle.start();
            });
        }
        return c;
    }

    static void draw(GraphicsContext g, String me, List<Star> stars, List<double[]> dust, double t) {
        g.setFill(new javafx.scene.paint.LinearGradient(0, 0, 0, 1, true, javafx.scene.paint.CycleMethod.NO_CYCLE,
                new javafx.scene.paint.Stop(0, Color.web("#0b1026")), new javafx.scene.paint.Stop(1, Color.web("#2a1b3d"))));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        for (double[] d : dust) {
            g.setFill(Color.rgb(255, 245, 225, 0.15 + 0.25 * d[2]));
            g.fillOval(d[0], d[1], 1.2, 1.2);
        }
        // dunes along the bottom
        g.setFill(Color.web("#1a1220"));
        g.beginPath();
        g.moveTo(0, HEIGHT);
        g.bezierCurveTo(WIDTH * 0.3, HEIGHT - 50, WIDTH * 0.6, HEIGHT - 10, WIDTH, HEIGHT - 40);
        g.lineTo(WIDTH, HEIGHT);
        g.closePath();
        g.fill();
        double cx = WIDTH / 2;
        double cy = HEIGHT / 2;
        // lines of trust first, under the stars
        for (int i = 0; i < stars.size(); i++) {
            Star s = stars.get(i);
            if (s.trust() != Trust.VERIFIED) continue;
            double[] p = place(s.name(), i, stars.size());
            g.setStroke(Color.rgb(255, 214, 150, 0.55));
            g.setLineWidth(1.4);
            g.strokeLine(cx, cy, p[0] * WIDTH, p[1] * HEIGHT);
        }
        g.setFont(Font.font(11));
        g.setTextAlign(TextAlignment.CENTER);
        for (int i = 0; i < stars.size(); i++) {
            Star s = stars.get(i);
            double[] p = place(s.name(), i, stars.size());
            double x = p[0] * WIDTH;
            double y = p[1] * HEIGHT;
            double twinkle = s.online() ? 0.75 + 0.25 * Math.sin(t * 2.4 + i) : 0.8;
            Color color;
            double radius;
            switch (s.trust()) {
                case VERIFIED -> { color = Color.rgb(255, 226, 170, twinkle); radius = 5.5; }
                case CHANGED -> {
                    double flicker = 0.45 + 0.55 * Math.abs(Math.sin(t * 6 + i));
                    color = Color.rgb(255, 90, 80, flicker);
                    radius = 5;
                }
                default -> { color = Color.rgb(200, 205, 230, 0.45 * twinkle); radius = 3.2; }
            }
            g.setFill(Color.color(color.getRed(), color.getGreen(), color.getBlue(), color.getOpacity() * 0.25));
            g.fillOval(x - radius * 2.6, y - radius * 2.6, radius * 5.2, radius * 5.2);
            g.setFill(color);
            g.fillOval(x - radius, y - radius, radius * 2, radius * 2);
            g.setFill(Color.rgb(255, 245, 230, s.trust() == Trust.UNVERIFIED ? 0.55 : 0.9));
            g.fillText(s.name(), x, y + radius + 14);
        }
        // us, in the middle
        g.setFill(Color.rgb(255, 200, 120, 0.25));
        g.fillOval(cx - 16, cy - 16, 32, 32);
        g.setFill(Color.web("#ffd48a"));
        g.fillOval(cx - 7, cy - 7, 14, 14);
        g.setFill(Color.rgb(255, 245, 230, 0.95));
        g.fillText(I18n.t("sky.you"), cx, cy + 24);
    }
}
