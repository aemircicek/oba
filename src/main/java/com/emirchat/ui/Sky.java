package com.emirchat.ui;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

/**
 * A window onto a friend's sky, behind the chat header: the colours of their time of day, the sun
 * or the moon on its arc, stars at night and the dunes below. It fades into the header on the
 * left, where the name is, so the text stays readable.
 */
final class Sky extends Region {
    /** Key moments of the day (hour) and the sky's top and horizon colours then. */
    private static final double[] HOURS = {0, 4.8, 5.8, 7.2, 12, 16.5, 18.2, 19.3, 20.6, 24};
    private static final String[][] COLORS = {
            {"#070b1f", "#1b1838"},   // midnight
            {"#0b1130", "#2a2050"},   // before dawn
            {"#3a2a62", "#f0875a"},   // dawn
            {"#6aa9dd", "#f6d3a2"},   // morning
            {"#3f93d8", "#bfe1f4"},   // noon
            {"#5d9fd6", "#f3d9a8"},   // afternoon
            {"#e0864a", "#f7c77e"},   // golden hour
            {"#5a3470", "#ee7a55"},   // sunset
            {"#121638", "#3b2552"},   // dusk
            {"#070b1f", "#1b1838"}};  // midnight again

    private final Canvas canvas = new Canvas();
    private final ZoneId zone;
    private final boolean dark;
    private AnimationTimer twinkle;
    /** Fade into the header on the left (off for the welcome-back banner, which is all sky). */
    boolean fade = true;

    Sky(ZoneId zone, boolean dark) {
        this.zone = zone;
        this.dark = dark;
        getChildren().add(canvas);
        setMouseTransparent(true);
        setMinSize(0, 0);
        sceneProperty().addListener((o, a, scene) -> {
            if (scene == null && twinkle != null) twinkle.stop();
            if (scene != null && Motion.enabled() && night(hour()) > 0.2) {
                twinkle = new AnimationTimer() {
                    private long last;

                    @Override public void handle(long now) {
                        if (now - last < 66_000_000L) return; // 15 frames a second is plenty for stars
                        last = now;
                        draw(now / 1e9);
                    }
                };
                twinkle.start();
            }
        });
    }

    @Override protected void layoutChildren() {
        if (canvas.getWidth() != getWidth() || canvas.getHeight() != getHeight()) {
            canvas.setWidth(getWidth());
            canvas.setHeight(getHeight());
            draw(0); // after resizing: a resized canvas starts blank
        }
    }

    /** For the preview tool: draw this hour instead of the real one (negative: the real time). */
    double fixedHour = -1;

    double hour() {
        if (fixedHour >= 0) return fixedHour;
        ZonedDateTime t = ZonedDateTime.now(zone);
        return t.getHour() + t.getMinute() / 60.0;
    }

    /** 0 by day, 1 deep at night, in between at dawn and dusk. */
    static double night(double hour) {
        if (hour >= 7 && hour <= 18) return 0;
        if (hour >= 20.5 || hour <= 4.8) return 1;
        if (hour < 7) return 1 - (hour - 4.8) / 2.2;
        return (hour - 18) / 2.5;
    }

    /** The sky's top and horizon colour at an hour of the day. */
    static Color[] colors(double hour) {
        int i = 0;
        while (i < HOURS.length - 2 && hour >= HOURS[i + 1]) i++;
        double f = (hour - HOURS[i]) / (HOURS[i + 1] - HOURS[i]);
        f = f * f * (3 - 2 * f); // smooth
        return new Color[] {
                Color.web(COLORS[i][0]).interpolate(Color.web(COLORS[i + 1][0]), f),
                Color.web(COLORS[i][1]).interpolate(Color.web(COLORS[i + 1][1]), f)};
    }

    /** Where on its arc the sun (by day) or the moon (by night) is: 0 at rising, 1 at setting. */
    static double arc(double hour, boolean sun) {
        if (sun) return Math.max(0, Math.min(1, (hour - 6) / 12.5));
        double h = hour < 12 ? hour + 24 : hour; // the night runs from 18:30 to 06:00 next day
        return Math.max(0, Math.min(1, (h - 18.5) / 11.5));
    }

    private void draw(double seconds) {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) return;
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        double hour = hour();
        Color[] sky = colors(hour);
        g.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE, new Stop(0, sky[0]), new Stop(1, sky[1])));
        g.fillRect(0, 0, w, h);

        double darkness = night(hour);
        if (darkness > 0) {
            java.util.Random r = new java.util.Random(zone.getId().hashCode());
            for (int i = 0; i < 46; i++) {
                double x = r.nextDouble() * w;
                double y = r.nextDouble() * h * 0.75;
                double phase = r.nextDouble() * Math.PI * 2;
                double size = 0.6 + r.nextDouble() * 1.3;
                double tw = 0.55 + 0.45 * Math.sin(seconds * (1.2 + r.nextDouble() * 2) + phase);
                g.setFill(Color.rgb(255, 246, 220, Math.min(1, darkness * tw * 0.9)));
                g.fillOval(x, y, size, size);
            }
        }

        boolean sunUp = hour >= 6 && hour <= 18.5;
        double t = arc(hour, sunUp);
        double cx = w * (0.38 + 0.56 * t);
        double cy = h * 0.95 - Math.sin(Math.PI * t) * h * 0.72;
        if (sunUp) {
            Color sunColor = Color.web(t < 0.15 || t > 0.85 ? "#ffb56b" : "#fff1c4");
            g.setFill(new RadialGradient(0, 0, cx, cy, h * 0.9, false, CycleMethod.NO_CYCLE,
                    new Stop(0, sunColor.deriveColor(0, 1, 1, 0.55)), new Stop(1, Color.TRANSPARENT)));
            g.fillOval(cx - h * 0.9, cy - h * 0.9, h * 1.8, h * 1.8);
            g.setFill(sunColor);
            g.fillOval(cx - h * 0.16, cy - h * 0.16, h * 0.32, h * 0.32);
        } else {
            double r = h * 0.13;
            g.setFill(new RadialGradient(0, 0, cx, cy, r * 4, false, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.rgb(246, 236, 210, 0.25)), new Stop(1, Color.TRANSPARENT)));
            g.fillOval(cx - r * 4, cy - r * 4, r * 8, r * 8);
            g.setFill(Color.web("#f6ecd2"));
            g.fillOval(cx - r, cy - r, r * 2, r * 2);
            g.setFill(sky[0].interpolate(sky[1], 0.4)); // a crescent: the night eats part of the disc
            g.fillOval(cx - r * 0.45, cy - r * 1.15, r * 2, r * 2);
        }

        // two rows of dunes along the bottom
        Color duneFar = sky[1].interpolate(Color.web("#3b2a1c"), 0.55 + darkness * 0.3);
        Color duneNear = sky[1].interpolate(Color.web("#2a1d12"), 0.7 + darkness * 0.25);
        dunes(g, w, h, h * 0.80, h * 0.10, 0.011, 1.3, duneFar);
        dunes(g, w, h, h * 0.90, h * 0.08, 0.017, 4.1, duneNear);

        if (!fade) return;
        // fade into the header on the left, where the name and status are
        Color surface = Color.web(dark ? "#171411" : "#fffcf7");
        g.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, surface), new Stop(0.30, surface), new Stop(0.52, surface.deriveColor(0, 1, 1, 0)),
                new Stop(1, surface.deriveColor(0, 1, 1, 0))));
        g.fillRect(0, 0, w, h);
    }

    private static void dunes(GraphicsContext g, double w, double h, double base, double height, double freq, double seed, Color c) {
        g.setFill(c);
        g.beginPath();
        g.moveTo(0, h);
        for (double x = 0; ; x = Math.min(w, x + 6)) {
            double y = base - height * (0.6 * Math.sin(x * freq + seed) + 0.4 * Math.sin(x * freq * 2.3 + seed * 1.7));
            g.lineTo(x, y);
            if (x >= w) break; // end exactly at the right edge
        }
        g.lineTo(w, h);
        g.closePath();
        g.fill();
    }

    /** "03:12 · night" for the label on the sky. */
    String label() {
        ZonedDateTime t = ZonedDateTime.now(zone);
        double hour = hour();
        String part = hour < 5 ? I18n.t("sky.night") : hour < 7.5 ? I18n.t("sky.dawn") : hour < 12 ? I18n.t("sky.morning")
                : hour < 17 ? I18n.t("sky.afternoon") : hour < 20 ? I18n.t("sky.evening") : I18n.t("sky.night");
        return (night(hour) > 0.5 ? "🌙 " : "☀ ") + String.format("%02d:%02d", t.getHour(), t.getMinute()) + " · " + part;
    }

    /** "New York" from America/New_York. */
    static String city(ZoneId zone) {
        String id = zone.getId();
        return id.substring(id.lastIndexOf('/') + 1).replace('_', ' ');
    }
}
