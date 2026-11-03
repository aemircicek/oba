package com.emirchat.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javafx.animation.AnimationTimer;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;

/**
 * A community as a desert seen from above, its channels as oases. The land comes from the
 * community's id, so everyone sees the same map without any syncing. An oasis tells its state at a
 * glance: rings spread on its water when there are unread messages, smoke rises where people talked
 * in the last hour, sand covers one that has been quiet for a week, and a campfire burns beside one
 * whose voice room has people in it. Drag to look around, scroll to zoom, click an oasis to go in.
 */
final class CommunityMap {
    /** What the map shows for one channel. */
    record Oasis(long id, String name, String topic, String lastLine, int unread, long lastAt, int inVoice) {
        boolean active(long now) {
            return lastAt > 0 && now - lastAt < 60 * 60_000L;
        }

        boolean quiet(long now) {
            return lastAt == 0 || now - lastAt > 7L * 24 * 3600_000;
        }
    }

    interface Actions {
        void open(long channelId);

        void joinVoice(long channelId);
    }

    private final StackPane root = new StackPane();
    private final Canvas canvas = new Canvas();
    private final Pane labels = new Pane();
    private final long seed;
    private final List<Oasis> oases;
    private final double[][] spots;          // world position of each oasis
    private final Actions actions;
    private final List<double[]> smoke = new ArrayList<>();
    private final Random random;
    private double zoom = 1;
    private double panX;
    private double panY;
    private double time;
    private AnimationTimer frames;
    private final long now = System.currentTimeMillis();

    CommunityMap(long communityId, String name, List<Oasis> oases, Actions actions) {
        this.seed = communityId * 104729L + 17;
        this.random = new Random(seed);
        this.oases = oases;
        this.actions = actions;
        this.spots = layout(communityId, oases.size());
        root.getStyleClass().add("community-map");
        root.setId("community-map");
        root.setMinSize(0, 0);
        canvas.widthProperty().bind(root.widthProperty());
        canvas.heightProperty().bind(root.heightProperty());
        canvas.widthProperty().addListener((o, a, b) -> redraw());
        canvas.heightProperty().addListener((o, a, b) -> redraw());
        labels.setPickOnBounds(false);

        Label title = UiKit.label(name, "map-title");
        Label sub = UiKit.label(I18n.t("map.hint"), "map-hint");
        VBox head = new VBox(3, title, sub);
        head.setMouseTransparent(true);
        head.setPadding(new javafx.geometry.Insets(16, 20, 0, 20));
        StackPane.setAlignment(head, Pos.TOP_LEFT);
        head.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox legend = new HBox(14, legend("map-legend-unread", I18n.t("map.unread")), legend("map-legend-active", I18n.t("map.active")),
                legend("map-legend-voice", I18n.t("map.voice")), legend("map-legend-quiet", I18n.t("map.quiet")));
        legend.getStyleClass().add("map-legend");
        legend.setMouseTransparent(true);
        legend.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
        StackPane.setAlignment(legend, Pos.BOTTOM_LEFT);
        StackPane.setMargin(legend, new javafx.geometry.Insets(0, 0, 16, 18));
        root.getChildren().addAll(canvas, labels, head, legend);

        double[] drag = new double[2];
        canvas.setOnMousePressed(e -> {
            drag[0] = e.getX();
            drag[1] = e.getY();
        });
        canvas.setOnMouseDragged(e -> {
            panX += e.getX() - drag[0];
            panY += e.getY() - drag[1];
            drag[0] = e.getX();
            drag[1] = e.getY();
            redraw();
        });
        canvas.setOnScroll(e -> {
            double before = zoom;
            zoom = Math.max(0.7, Math.min(2.4, zoom * Math.pow(1.0015, e.getDeltaY())));
            // keep the point under the pointer where it is
            double cx = e.getX() - root.getWidth() / 2 - panX;
            double cy = e.getY() - root.getHeight() / 2 - panY;
            panX -= cx * (zoom / before - 1);
            panY -= cy * (zoom / before - 1);
            redraw();
        });
        canvas.setCursor(javafx.scene.Cursor.MOVE);
        for (int i = 0; i < 90; i++) step(1 / 30.0);
        root.sceneProperty().addListener((o, a, scene) -> {
            if (scene == null && frames != null) frames.stop();
            if (scene != null && Motion.enabled()) {
                frames = new AnimationTimer() {
                    private long last = -1;

                    @Override public void handle(long t) {
                        double dt = last < 0 ? 0 : Math.min(0.05, (t - last) / 1e9);
                        last = t;
                        time += dt;
                        step(dt);
                        draw();
                    }
                };
                frames.start();
            }
        });
    }

    Node root() {
        return root;
    }

    private static Node legend(String style, String text) {
        javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(5);
        dot.getStyleClass().add(style);
        HBox row = new HBox(6, dot, UiKit.label(text, "map-legend-text"));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Where the oases lie: the first channel in the middle, the rest on a spiral around it, each
     * nudged by the community's own randomness but kept apart. The same for everyone.
     */
    static double[][] layout(long communityId, int n) {
        Random r = new Random(communityId * 31 + 7);
        double[][] out = new double[n][];
        for (int i = 0; i < n; i++) {
            double angle = i * 2.39996 + r.nextDouble() * 0.5;
            double radius = i == 0 ? 0 : 0.15 + 0.085 * Math.sqrt(i) + r.nextDouble() * 0.03;
            double x = 0.5 + Math.cos(angle) * radius;
            double y = 0.52 + Math.sin(angle) * radius;
            for (int tries = 0; tries < 20 && tooClose(out, i, x, y); tries++) {
                angle += 0.35;
                x = 0.5 + Math.cos(angle) * radius;
                y = 0.52 + Math.sin(angle) * radius;
            }
            out[i] = new double[] {Math.max(0.08, Math.min(0.92, x)), Math.max(0.12, Math.min(0.9, y))};
        }
        return out;
    }

    private static boolean tooClose(double[][] placed, int count, double x, double y) {
        for (int j = 0; j < count; j++) if (Math.hypot(placed[j][0] - x, placed[j][1] - y) < 0.14) return true;
        return false;
    }

    // ------------------------------------------------------------------ world on screen

    private double side() {
        return Math.min(root.getWidth(), root.getHeight()) * 1.08 * zoom;
    }

    private double sx(double x) {
        return root.getWidth() / 2 + panX + (x - 0.5) * side();
    }

    private double sy(double y) {
        return root.getHeight() / 2 + panY + (y - 0.52) * side();
    }

    // ------------------------------------------------------------------ smoke

    private void step(double dt) {
        for (int i = 0; i < oases.size(); i++) {
            Oasis o = oases.get(i);
            if (!o.active(now) && o.inVoice() == 0) continue;
            if (random.nextDouble() < dt * (o.inVoice() > 0 ? 9 : 5)) {
                double[] at = spots[i];
                smoke.add(new double[] {at[0] + 0.045, at[1] - 0.02, 0, i});
            }
        }
        for (double[] p : smoke) {
            p[2] += dt;
            p[1] -= dt * 0.025;
            p[0] += dt * (0.008 + 0.006 * Math.sin(time * 2 + p[3]));
        }
        smoke.removeIf(p -> p[2] > 3.2);
    }

    // ------------------------------------------------------------------ drawing

    private void redraw() {
        draw();
        placeLabels();
    }

    private void draw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;
        GraphicsContext g = canvas.getGraphicsContext2D();
        double hour = java.time.LocalTime.now().getHour() + java.time.LocalTime.now().getMinute() / 60.0;
        double night = Sky.night(hour);
        Color sandLight = Color.web("#e3c08d").interpolate(Color.web("#3a2c22"), 0.25 + night * 0.55);
        Color sandDark = Color.web("#c49a63").interpolate(Color.web("#241a13"), 0.25 + night * 0.6);
        g.setFill(new RadialGradient(0, 0, w / 2 + panX, h / 2 + panY, Math.max(w, h) * 0.8 * zoom, false, CycleMethod.NO_CYCLE,
                new Stop(0, sandLight), new Stop(1, sandDark)));
        g.fillRect(0, 0, w, h);

        // dunes: long ridges, light on one side and shadow on the other
        Random r = new Random(seed);
        g.setLineCap(StrokeLineCap.ROUND);
        for (int i = 0; i < 22; i++) {
            double y0 = -0.1 + i * 0.058;
            double phase = r.nextDouble() * 6;
            double amp = 0.012 + r.nextDouble() * 0.02;
            for (int pass = 0; pass < 2; pass++) {
                g.setStroke(pass == 0 ? Color.rgb(255, 240, 210, 0.10 * (1 - night * 0.7)) : Color.rgb(60, 35, 15, 0.10 + night * 0.05));
                g.setLineWidth((pass == 0 ? 3 : 2) * zoom);
                g.beginPath();
                for (double x = -0.2; x <= 1.2; x += 0.02) {
                    double y = y0 + Math.sin(x * 6 + phase) * amp + Math.sin(x * 15 + phase * 2) * amp * 0.3 + pass * 0.006;
                    if (x <= -0.2) g.moveTo(sx(x), sy(y));
                    else g.lineTo(sx(x), sy(y));
                }
                g.stroke();
            }
        }
        // rock fields
        for (int c = 0; c < 6; c++) {
            double cx = r.nextDouble();
            double cy = r.nextDouble();
            for (int k = 0; k < 9; k++) {
                double x = cx + (r.nextDouble() - 0.5) * 0.08;
                double y = cy + (r.nextDouble() - 0.5) * 0.06;
                double s = (3 + r.nextDouble() * 8) * zoom;
                g.setFill(Color.rgb(40, 25, 15, 0.22));
                g.fillOval(sx(x) + s * 0.3, sy(y) + s * 0.3, s * 1.5, s);
                g.setFill(Color.web("#8a7563").interpolate(Color.web("#3d3229"), night * 0.6));
                g.fillOval(sx(x), sy(y), s * 1.4, s);
            }
        }

        // the caravan trail from oasis to oasis, in channel order
        g.setStroke(Color.rgb(110, 75, 40, 0.38 + night * 0.1));
        g.setLineWidth(2.2 * zoom);
        g.setLineDashes(7 * zoom, 9 * zoom);
        for (int i = 1; i < spots.length; i++) {
            double[] a = spots[i - 1];
            double[] b = spots[i];
            double mx = (a[0] + b[0]) / 2 + (b[1] - a[1]) * 0.18;
            double my = (a[1] + b[1]) / 2 - (b[0] - a[0]) * 0.18;
            g.beginPath();
            g.moveTo(sx(a[0]), sy(a[1]));
            g.quadraticCurveTo(sx(mx), sy(my), sx(b[0]), sy(b[1]));
            g.stroke();
        }
        g.setLineDashes((double[]) null);

        for (int i = 0; i < oases.size(); i++) oasis(g, oases.get(i), spots[i], night);

        // smoke over the busy ones
        for (double[] p : smoke) {
            double life = p[2] / 3.2;
            double rr = (6 + life * 22) * zoom;
            g.setFill(Color.rgb(235, 225, 210, 0.32 * (1 - life)));
            g.fillOval(sx(p[0]) - rr, sy(p[1]) - rr, rr * 2, rr * 2);
        }

        // night falls on the map with your own evening
        if (night > 0) {
            g.setFill(Color.rgb(10, 14, 40, 0.30 * night));
            g.fillRect(0, 0, w, h);
            for (int i = 0; i < oases.size(); i++) {
                if (oases.get(i).inVoice() == 0) continue;
                double cx = sx(spots[i][0] + 0.06);
                double cy = sy(spots[i][1] + 0.02);
                double rad = 70 * zoom;
                g.setFill(new RadialGradient(0, 0, cx, cy, rad, false, CycleMethod.NO_CYCLE,
                        new Stop(0, Color.rgb(255, 160, 70, 0.35 * night)), new Stop(1, Color.TRANSPARENT)));
                g.fillOval(cx - rad, cy - rad, rad * 2, rad * 2);
            }
        }
    }

    private void oasis(GraphicsContext g, Oasis o, double[] at, double night) {
        boolean quiet = o.quiet(now);
        double cx = sx(at[0]);
        double cy = sy(at[1]);
        double s = side() * (o.id() == oases.get(0).id() ? 0.075 : 0.06) * (quiet ? 0.8 : 1);
        // grass ring, then water (or what is left of it under the sand)
        g.setFill(quiet ? Color.rgb(140, 120, 80, 0.35) : Color.web("#6f9a4c").interpolate(Color.web("#2f4a2b"), night * 0.6));
        g.fillOval(cx - s * 1.45, cy - s * 1.05, s * 2.9, s * 2.1);
        Color deep = quiet ? Color.web("#a48a62") : Color.web("#1f7f8c").interpolate(Color.web("#123a4a"), night * 0.6);
        Color shallow = quiet ? Color.web("#c9ab7c") : Color.web("#55c1c4").interpolate(Color.web("#2a6f7a"), night * 0.6);
        g.setFill(new RadialGradient(0, 0, cx - s * 0.2, cy - s * 0.15, s * 1.2, false, CycleMethod.NO_CYCLE,
                new Stop(0, shallow), new Stop(1, deep)));
        g.fillOval(cx - s, cy - s * 0.68, s * 2, s * 1.36);
        if (!quiet) {
            g.setStroke(Color.rgb(255, 255, 255, 0.22));
            g.setLineWidth(1.4 * zoom);
            g.strokeArc(cx - s * 0.7, cy - s * 0.5, s * 1.1, s * 0.6, 200, 80, javafx.scene.shape.ArcType.OPEN);
        }
        if (o.unread() > 0 && !quiet) { // rings spread on the water
            for (int k = 0; k < 3; k++) {
                double phase = Motion.enabled() ? (time * 0.6 + k / 3.0) % 1 : (k + 1) / 4.0;
                double rr = s * (0.25 + phase * 0.75);
                g.setStroke(Color.rgb(255, 214, 140, 0.75 * (1 - phase)));
                g.setLineWidth(2 * zoom);
                g.strokeOval(cx - rr, cy - rr * 0.68, rr * 2, rr * 1.36);
            }
        }
        // palms around the water (dry ones on a quiet oasis), always in the same places
        Random r = new Random(o.id() * 977);
        int palms = quiet ? 2 : 4 + Math.min(3, o.unread());
        for (int p = 0; p < palms; p++) {
            double a = r.nextDouble() * Math.PI * 2;
            double px = cx + Math.cos(a) * s * (1.15 + r.nextDouble() * 0.2);
            double py = cy + Math.sin(a) * s * (0.82 + r.nextDouble() * 0.15);
            palm(g, px, py, s * (0.42 + r.nextDouble() * 0.15), quiet, night, r.nextDouble() * Math.PI);
        }
        if (quiet) { // drifts of sand over it
            g.setFill(Color.rgb(222, 190, 140, 0.55));
            g.fillOval(cx - s * 0.9, cy - s * 0.1, s * 1.3, s * 0.6);
            g.fillOval(cx - s * 0.2, cy - s * 0.55, s * 1.1, s * 0.5);
        }
        if (o.inVoice() > 0) campfire(g, cx + s * 1.55, cy + s * 0.35, s * 0.32);
    }

    private void palm(GraphicsContext g, double x, double y, double size, boolean dry, double night, double turn) {
        g.setFill(Color.rgb(30, 20, 10, 0.25));
        g.fillOval(x - size * 0.4 + size * 0.5, y - size * 0.25 + size * 0.5, size * 1.4, size * 0.7);
        Color leaf = dry ? Color.web("#9b7f52") : Color.web("#3f7a3a").interpolate(Color.web("#1e3a22"), night * 0.6);
        Color light = dry ? Color.web("#b89a66") : Color.web("#6aa85a").interpolate(Color.web("#2d5530"), night * 0.6);
        for (int i = 0; i < 6; i++) {
            double a = turn + i * Math.PI / 3;
            g.save();
            g.translate(x, y);
            g.rotate(Math.toDegrees(a));
            g.setFill(i % 2 == 0 ? leaf : light);
            g.fillOval(0, -size * 0.13, size * 0.75, size * 0.26);
            g.restore();
        }
        g.setFill(Color.web("#6b4a2a"));
        g.fillOval(x - size * 0.09, y - size * 0.09, size * 0.18, size * 0.18);
    }

    private void campfire(GraphicsContext g, double x, double y, double s) {
        double fl = 0.85 + 0.15 * Math.sin(time * 11);
        g.setFill(new RadialGradient(0, 0, x, y, s * 3.2, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(255, 160, 60, 0.5 * fl)), new Stop(1, Color.TRANSPARENT)));
        g.fillOval(x - s * 3.2, y - s * 3.2, s * 6.4, s * 6.4);
        g.setFill(Color.web("#4a2f1c"));
        g.fillRoundRect(x - s * 0.8, y - s * 0.12, s * 1.6, s * 0.24, s * 0.2, s * 0.2);
        g.setFill(new RadialGradient(0, 0, x, y - s * 0.3, s, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(255, 240, 180, 0.95)), new Stop(0.5, Color.rgb(255, 150, 50, 0.85)), new Stop(1, Color.TRANSPARENT)));
        g.fillOval(x - s * 0.55, y - s * 1.1 * fl, s * 1.1, s * 1.2);
    }

    /** Names, badges and the clickable areas, kept over the drawing as it moves. */
    private void placeLabels() {
        labels.getChildren().clear();
        for (int i = 0; i < oases.size(); i++) {
            Oasis o = oases.get(i);
            double s = side() * (i == 0 ? 0.075 : 0.06);
            double cx = sx(spots[i][0]);
            double cy = sy(spots[i][1]);
            javafx.scene.shape.Ellipse hit = new javafx.scene.shape.Ellipse(cx, cy, s * 1.5, s * 1.1);
            hit.setFill(Color.TRANSPARENT);
            hit.setCursor(javafx.scene.Cursor.HAND);
            hit.setId("oasis-" + o.id());
            hit.setOnMouseClicked(e -> actions.open(o.id()));
            Tooltip.install(hit, new Tooltip(tooltip(o)));
            hit.setOnMouseEntered(e -> hit.setStroke(Color.rgb(255, 230, 180, 0.7)));
            hit.setOnMouseExited(e -> hit.setStroke(null));
            hit.setStrokeWidth(2);
            Label name = UiKit.label(o.name(), "map-name");
            if (o.quiet(now)) name.getStyleClass().add("quiet");
            HBox chip = new HBox(6, name);
            chip.setAlignment(Pos.CENTER);
            if (o.unread() > 0) chip.getChildren().add(UiKit.label(String.valueOf(o.unread()), "unread-badge"));
            chip.setMouseTransparent(true);
            chip.applyCss();
            chip.autosize();
            chip.relocate(cx - chip.getWidth() / 2, cy + s * 1.15);
            labels.getChildren().addAll(hit, chip);
            if (o.inVoice() > 0) {
                Label fire = UiKit.label("🔊 " + o.inVoice(), "map-voice");
                fire.setId("map-voice-" + o.id());
                fire.setCursor(javafx.scene.Cursor.HAND);
                fire.setOnMouseClicked(e -> actions.joinVoice(o.id()));
                Tooltip.install(fire, new Tooltip(I18n.t("map.joinVoice")));
                fire.applyCss();
                fire.autosize();
                fire.relocate(cx + s * 1.55 - fire.getWidth() / 2, cy + s * 0.35 + s * 0.45);
                labels.getChildren().add(fire);
            }
        }
    }

    private static String tooltip(Oasis o) {
        StringBuilder b = new StringBuilder(o.name());
        if (!o.topic().isEmpty()) b.append("\n").append(o.topic());
        if (o.lastAt() > 0) b.append("\n").append(UiKit.time(o.lastAt())).append(" · ").append(o.lastLine());
        else b.append("\n").append(I18n.t("map.empty"));
        return b.toString();
    }
}
