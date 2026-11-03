package com.emirchat.ui;

import com.emirchat.call.VoiceChannels;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import javafx.animation.AnimationTimer;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
 * The campfire: a voice room seen from above, a desert at night with three fires. People sit
 * where they like; voices are as loud as they are near and come from their side (see
 * {@link VoiceChannels#gain} and {@link VoiceChannels#pan}). Drag yourself, or click the sand to
 * walk there. The rings around you show how far you hear.
 */
final class Campfire {
    private final AccountSession session;
    private final long groupId;
    private final StackPane root = new StackPane();
    private final Canvas ground = new Canvas();
    private final Pane people = new Pane();
    private final Map<String, VBox> faces = new HashMap<>();
    private final Map<String, double[]> shown = new HashMap<>();   // where each face is drawn (eased)
    private final List<double[]> embers = new ArrayList<>();       // x, y, vx, vy, life, fire
    private final Random random;
    private final long seed;
    private Set<String> speaking = Set.of();
    private AnimationTimer frames;
    private javafx.animation.Timeline follow;
    private double time;
    private long lastMove;

    Campfire(AccountSession session, long groupId, String title, Runnable back) {
        this.session = session;
        this.groupId = groupId;
        this.seed = groupId * 7919L;
        this.random = new Random(seed);
        root.getStyleClass().add("campfire");
        root.setId("campfire");
        root.setMinSize(0, 0);
        ground.widthProperty().bind(root.widthProperty());
        ground.heightProperty().bind(root.heightProperty());
        ground.widthProperty().addListener((o, a, b) -> draw());
        ground.heightProperty().addListener((o, a, b) -> draw());
        people.setPickOnBounds(false);

        Label name = UiKit.label("🔥 " + title, "campfire-title");
        Label hint = UiKit.label(I18n.t("campfire.hint"), "campfire-hint");
        hint.setWrapText(true);
        hint.setMaxWidth(380);
        VBox words = new VBox(4, name, hint);
        Button chat = new Button(I18n.t("campfire.back"));
        chat.setGraphic(Icons.of(Icons.MESSAGE, 14, "on-dark"));
        chat.getStyleClass().addAll("small", "campfire-button");
        chat.setId("campfire-back");
        chat.setOnAction(e -> back.run());
        HBox hud = new HBox(12, words, UiKit.grow(), chat);
        hud.setAlignment(Pos.TOP_LEFT);
        hud.setPickOnBounds(false);
        hud.setPadding(new javafx.geometry.Insets(16, 18, 0, 20));
        StackPane.setAlignment(hud, Pos.TOP_CENTER);
        hud.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);

        root.getChildren().addAll(ground, people, hud);
        // a click on the sand walks us there
        ground.setOnMouseClicked(e -> walkTo(wx(e.getX()), wy(e.getY())));
        ground.setCursor(javafx.scene.Cursor.CROSSHAIR);

        for (int i = 0; i < 160; i++) step(1 / 30.0); // embers already rising when it opens
        root.sceneProperty().addListener((o, a, scene) -> {
            if (scene == null) stop();
            else start();
        });
    }

    Node root() {
        return root;
    }

    long groupId() {
        return groupId;
    }

    void update(VoiceChannels.State state) {
        speaking = state.speaking();
        refresh();
    }

    private VoiceChannels voice() {
        return session.client().voice();
    }

    private String me() {
        return session.client().username();
    }

    // ------------------------------------------------------------------ life

    private void start() {
        follow = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.millis(100), e -> refresh()));
        follow.setCycleCount(javafx.animation.Animation.INDEFINITE);
        follow.play();
        refresh();
        if (!Motion.enabled()) {
            draw();
            return;
        }
        frames = new AnimationTimer() {
            private long last = -1;

            @Override public void handle(long now) {
                double dt = last < 0 ? 0 : Math.min(0.05, (now - last) / 1e9);
                last = now;
                time += dt;
                step(dt);
                ease(dt);
                draw();
            }
        };
        frames.start();
    }

    void stop() {
        if (frames != null) frames.stop();
        if (follow != null) follow.stop();
    }

    /** Puts a face for everyone in the room, at their place, as bright as they are loud to us. */
    private void refresh() {
        VoiceChannels v = voice();
        if (v == null) return;
        Map<String, double[]> places = v.places();
        String me = me();
        double[] here = places.getOrDefault(me, VoiceChannels.seat(0));
        faces.keySet().removeIf(u -> {
            if (places.containsKey(u)) return false;
            people.getChildren().remove(faces.get(u));
            shown.remove(u);
            return true;
        });
        for (var e : places.entrySet()) {
            String u = e.getKey();
            boolean talking = speaking.contains(u);
            VBox face = faces.get(u);
            boolean wasTalking = face != null && Boolean.TRUE.equals(face.getProperties().get("talking"));
            if (face == null || talking != wasTalking) {
                if (face != null) people.getChildren().remove(face);
                face = face(u, talking, u.equals(me));
                faces.put(u, face);
                people.getChildren().add(face);
            }
            if (!u.equals(me) || !Boolean.TRUE.equals(root.getProperties().get("dragging"))) {
                shown.computeIfAbsent(u, k -> e.getValue().clone());
                shown.put(u + "#target", e.getValue());
            }
            double gain = u.equals(me) ? 1 : VoiceChannels.gain(here, e.getValue());
            face.setOpacity(0.32 + 0.68 * gain);
            Node far = face.lookup(".campfire-far");
            if (far != null) far.setVisible(!u.equals(me) && gain <= 0);
        }
        if (!Motion.enabled()) {
            ease(10);
            draw();
        }
    }

    private VBox face(String user, boolean talking, boolean mine) {
        Node avatar = UiKit.voiceFace(user, talking, 46);
        Label name = UiKit.label(mine ? I18n.t("group.you", user) : user, "campfire-name");
        Label far = UiKit.label(I18n.t("campfire.far"), "campfire-far");
        far.setVisible(false);
        far.managedProperty().bind(far.visibleProperty());
        VBox box = new VBox(3, avatar, name, far);
        box.setAlignment(Pos.TOP_CENTER);
        box.getProperties().put("talking", talking);
        box.setId("camp-" + user);
        if (mine) {
            box.getStyleClass().add("campfire-me");
            box.setCursor(javafx.scene.Cursor.OPEN_HAND);
            double[] grab = new double[2];
            box.setOnMousePressed(e -> {
                grab[0] = e.getSceneX();
                grab[1] = e.getSceneY();
                root.getProperties().put("dragging", true);
                box.setCursor(javafx.scene.Cursor.CLOSED_HAND);
            });
            box.setOnMouseDragged(e -> {
                double[] at = shown.getOrDefault(user, VoiceChannels.seat(0));
                double[] to = clampToView(at[0] + (e.getSceneX() - grab[0]) / side(), at[1] + (e.getSceneY() - grab[1]) / side());
                grab[0] = e.getSceneX();
                grab[1] = e.getSceneY();
                double nx = to[0];
                double ny = to[1];
                shown.put(user, new double[] {nx, ny});
                shown.put(user + "#target", new double[] {nx, ny});
                place(box, nx, ny);
                long now = System.currentTimeMillis();
                if (now - lastMove > 90) { // ten updates a second is plenty
                    lastMove = now;
                    double fx = nx;
                    double fy = ny;
                    session.background().execute(() -> voice().moveTo(fx, fy));
                }
                if (!Motion.enabled()) draw();
            });
            box.setOnMouseReleased(e -> {
                root.getProperties().put("dragging", false);
                box.setCursor(javafx.scene.Cursor.OPEN_HAND);
                double[] at = shown.get(user);
                if (at != null) session.background().execute(() -> voice().moveTo(at[0], at[1]));
            });
        }
        return box;
    }

    private void walkTo(double x, double y) {
        String me = me();
        double[] target = clampToView(x, y);
        shown.put(me + "#target", target);
        session.background().execute(() -> voice().moveTo(target[0], target[1]));
    }

    /** Faces glide to where they are going rather than jumping. */
    private void ease(double dt) {
        double k = Math.min(1, dt * 7);
        for (var e : faces.entrySet()) {
            double[] at = shown.get(e.getKey());
            double[] to = shown.get(e.getKey() + "#target");
            if (at == null || to == null) continue;
            at[0] += (to[0] - at[0]) * k;
            at[1] += (to[1] - at[1]) * k;
            place(e.getValue(), at[0], at[1]);
        }
    }

    private void place(VBox face, double x, double y) {
        face.applyCss();
        face.autosize();
        face.relocate(sx(x) - face.getWidth() / 2, sy(y) - 26);
    }

    // ------------------------------------------------------------------ fire and embers

    // ------------------------------------------------------------------ the square world on screen

    /** The world is a square; it covers the view (cropping a little on the long side). */
    private double side() {
        return Math.max(root.getWidth(), root.getHeight());
    }

    private double sx(double x) {
        return (root.getWidth() - side()) / 2 + x * side();
    }

    private double sy(double y) {
        return (root.getHeight() - side()) / 2 + y * side();
    }

    private double wx(double px) {
        return (px - (root.getWidth() - side()) / 2) / side();
    }

    private double wy(double py) {
        return (py - (root.getHeight() - side()) / 2) / side();
    }

    /** The visible part of the world, so nobody walks off screen. */
    private double[] clampToView(double x, double y) {
        double m = 0.03;
        return new double[] {Math.max(wx(0) + m, Math.min(wx(root.getWidth()) - m, x)),
                Math.max(wy(0) + 0.08, Math.min(wy(root.getHeight()) - 0.06, y))};
    }

    private void step(double dt) {
        double[][] fires = VoiceChannels.FIRES;
        int wanted = 70;
        while (embers.size() < wanted) {
            int f = random.nextInt(fires.length);
            double spread = f == 0 ? 0.018 : 0.011;
            embers.add(new double[] {fires[f][0] + (random.nextDouble() - 0.5) * spread, fires[f][1] - 0.01,
                    (random.nextDouble() - 0.5) * 0.02, -(0.05 + random.nextDouble() * 0.09), 0, f});
        }
        for (double[] e : embers) {
            e[2] += Math.sin(time * 3 + e[5] * 7 + e[1] * 40) * 0.012 * dt; // they sway
            e[0] += e[2] * dt;
            e[1] += e[3] * dt;
            e[4] += dt;
        }
        embers.removeIf(e -> e[4] > 1.6 + (e[5] == 0 ? 0.8 : 0));
    }

    private double flicker(int fire) {
        return 0.82 + 0.10 * Math.sin(time * 9.1 + fire) + 0.08 * Math.sin(time * 14.3 + fire * 2.1);
    }

    private void draw() {
        double w = ground.getWidth();
        double h = ground.getHeight();
        if (w <= 0 || h <= 0) return;
        GraphicsContext g = ground.getGraphicsContext2D();
        g.setFill(new javafx.scene.paint.LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#15100c")), new Stop(1, Color.web("#2a1d13"))));
        g.fillRect(0, 0, w, h);

        // ripples in the sand
        g.setLineCap(StrokeLineCap.ROUND);
        g.setStroke(Color.rgb(255, 214, 160, 0.045));
        g.setLineWidth(2);
        for (int i = 0; i < 16; i++) {
            double base = h * (i + 0.5) / 16;
            g.beginPath();
            for (double x = 0; x <= w + 8; x += 8) {
                double y = base + Math.sin(x / w * 7 + i * 1.3) * h * 0.012 + Math.sin(x / w * 19 + i) * h * 0.004;
                if (x == 0) g.moveTo(x, y);
                else g.lineTo(x, y);
            }
            g.stroke();
        }
        // stones, dry bushes and two cacti, the same for everyone in this room
        Random r = new Random(seed);
        for (int i = 0; i < 26; i++) {
            double x = sx(r.nextDouble());
            double y = sy(r.nextDouble());
            double s = 3 + r.nextDouble() * 7;
            g.setFill(Color.rgb(90, 72, 56, 0.5 + r.nextDouble() * 0.3));
            g.fillOval(x, y, s * 1.4, s);
        }
        for (int i = 0; i < 2; i++) {
            double x = sx(0.08 + r.nextDouble() * 0.84);
            double y = sy(0.12 + r.nextDouble() * 0.76);
            cactus(g, x, y, side() * 0.032);
        }

        // warm light on the sand around each fire
        double[][] fires = VoiceChannels.FIRES;
        for (int f = 0; f < fires.length; f++) {
            double cx = sx(fires[f][0]);
            double cy = sy(fires[f][1]);
            double rad = (f == 0 ? 0.24 : 0.15) * side() * flicker(f);
            g.setFill(new RadialGradient(0, 0, cx, cy, rad, false, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.rgb(255, 150, 60, 0.42)), new Stop(0.45, Color.rgb(230, 110, 40, 0.14)),
                    new Stop(1, Color.TRANSPARENT)));
            g.fillOval(cx - rad, cy - rad, rad * 2, rad * 2);
        }

        // how far we hear: full voices inside the bright ring, none beyond the faint one
        double[] here = shown.getOrDefault(me(), VoiceChannels.seat(0));
        double hx = sx(here[0]);
        double hy = sy(here[1]);
        double rx = VoiceChannels.HEAR_FULL * side(); // the world is square: the rings are true circles
        double ry = rx;
        g.setStroke(Color.rgb(242, 164, 58, 0.55));
        g.setLineWidth(1.5);
        g.setLineDashes(6, 6);
        g.strokeOval(hx - rx, hy - ry, rx * 2, ry * 2);
        double fx = VoiceChannels.HEAR_NONE * side();
        double fy = fx;
        g.setStroke(Color.rgb(242, 164, 58, 0.16));
        g.strokeOval(hx - fx, hy - fy, fx * 2, fy * 2);
        g.setLineDashes((double[]) null);

        // fire pits: a ring of stones, crossed logs, flames
        for (int f = 0; f < fires.length; f++) firePit(g, sx(fires[f][0]), sy(fires[f][1]), (f == 0 ? 1.0 : 0.7) * side() / 700, f);

        // embers
        for (double[] e : embers) {
            double life = e[4] / (1.6 + (e[5] == 0 ? 0.8 : 0));
            double a = Math.max(0, 1 - life);
            g.setFill(Color.rgb(255, (int) (190 - 90 * life), 80, a * 0.9));
            double s = 1.4 + (1 - life) * 1.8;
            g.fillOval(sx(e[0]), sy(e[1]), s, s);
        }

        // sound waves from those who are talking
        for (String u : speaking) {
            double[] at = shown.get(u);
            if (at == null) continue;
            for (int k = 0; k < 3; k++) {
                double phase = ((time * 1.4) + k / 3.0) % 1;
                if (!Motion.enabled()) phase = (k + 1) / 3.5;
                double rr = 28 + phase * 46;
                g.setStroke(Color.rgb(125, 209, 138, 0.55 * (1 - phase)));
                g.setLineWidth(2);
                g.strokeOval(sx(at[0]) - rr, sy(at[1]) - rr, rr * 2, rr * 2);
            }
        }
    }

    private void firePit(GraphicsContext g, double cx, double cy, double scale, int f) {
        double s = 34 * scale;
        g.setFill(Color.web("#5a4a3c"));
        for (int i = 0; i < 9; i++) {
            double a = i * Math.PI * 2 / 9;
            g.fillOval(cx + Math.cos(a) * s - 5 * scale, cy + Math.sin(a) * s * 0.8 - 4 * scale, 10 * scale, 8 * scale);
        }
        g.save();
        g.translate(cx, cy);
        g.setFill(Color.web("#4a2f1c"));
        for (int i = 0; i < 3; i++) {
            g.save();
            g.rotate(i * 60 + 15);
            g.fillRoundRect(-s * 0.75, -4 * scale, s * 1.5, 8 * scale, 6 * scale, 6 * scale);
            g.restore();
        }
        g.restore();
        double fl = flicker(f);
        for (int i = 0; i < 5; i++) {
            double ox = Math.sin(time * 6 + i * 1.7 + f) * 4 * scale;
            double fh = (26 + 10 * Math.sin(time * 8 + i * 2.3 + f * 3)) * scale * fl * (i == 2 ? 1.25 : 0.8);
            double fw = (14 - Math.abs(i - 2) * 2.5) * scale;
            double x = cx + (i - 2) * 6 * scale + ox;
            g.setFill(new RadialGradient(0, 0, x, cy - fh * 0.15, fh * 0.75, false, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.rgb(255, 245, 190, 0.95)), new Stop(0.35, Color.rgb(255, 180, 70, 0.85)),
                    new Stop(0.75, Color.rgb(230, 90, 30, 0.35)), new Stop(1, Color.TRANSPARENT)));
            g.fillOval(x - fw, cy - fh, fw * 2, fh * 1.1);
        }
    }

    private static void cactus(GraphicsContext g, double x, double y, double s) {
        g.setFill(Color.rgb(0, 0, 0, 0.28));
        g.fillOval(x - s * 0.9 + s * 0.4, y - s * 0.4 + s * 0.6, s * 1.8, s * 0.9);
        g.setFill(Color.web("#3f6b3a"));
        g.fillOval(x - s * 0.5, y - s * 0.5, s, s);
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2 + 0.4;
            g.fillOval(x + Math.cos(a) * s * 0.55 - s * 0.22, y + Math.sin(a) * s * 0.55 - s * 0.22, s * 0.44, s * 0.44);
        }
        g.setFill(Color.web("#6fa05f"));
        g.fillOval(x - s * 0.18, y - s * 0.18, s * 0.36, s * 0.36);
    }
}
