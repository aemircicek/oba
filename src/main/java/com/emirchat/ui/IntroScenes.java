package com.emirchat.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

/**
 * The little moving pictures of the first-start tour: what end-to-end encryption means (a message
 * turning to noise on its way through the server and back to words at the other end), how two
 * apps agree on a key nobody else has (X3DH: three pairs of key halves meet), and why comparing
 * safety numbers matters. With reduced motion each shows its last frame only.
 */
final class IntroScenes {
    static final double W = 340;
    static final double H = 130;

    private IntroScenes() {}

    /** The scene for page 0, 1 or 2; {@link #stop} it when the page changes. */
    static StackPane scene(int page) {
        Pane canvas = new Pane();
        canvas.setPrefSize(W, H);
        canvas.setMinSize(W, H);
        canvas.setMaxSize(W, H);
        Timeline timeline = switch (page) {
            case 0 -> encryption(canvas);
            case 1 -> agreement(canvas);
            default -> verification(canvas);
        };
        StackPane holder = new StackPane(canvas);
        holder.getStyleClass().add("intro-scene");
        holder.setId("intro-scene-" + page);
        holder.setAlignment(Pos.CENTER);
        holder.getProperties().put(Timeline.class, timeline);
        // however the dialog closes (Escape, a click outside), the animation stops with it
        holder.sceneProperty().addListener((o, was, now) -> {
            if (now == null) timeline.stop();
        });
        if (Motion.enabled()) {
            timeline.setCycleCount(Animation.INDEFINITE);
            timeline.play();
        } else {
            timeline.jumpTo(timeline.getTotalDuration().subtract(Duration.millis(1)));
        }
        return holder;
    }

    static void stop(Node scene) {
        if (scene != null && scene.getProperties().get(Timeline.class) instanceof Timeline t) t.stop();
    }

    // ------------------------------------------------------------------ 1: the server only sees noise

    private static Timeline encryption(Pane p) {
        Node you = tent(40, "intro-you");
        Node friend = tent(W - 40, "intro-friend");
        Rectangle server = new Rectangle(W / 2 - 28, 40, 56, 44);
        server.setArcWidth(10);
        server.setArcHeight(10);
        server.getStyleClass().add("intro-server");
        Label serverName = caption(I18n.t("intro.server"), W / 2, 92);
        Label youName = caption(I18n.t("intro.you"), 40, 100);
        Label friendName = caption(I18n.t("intro.friend"), W - 40, 100);
        Label message = new Label(I18n.t("intro.hello"));
        message.getStyleClass().add("intro-message");
        message.setLayoutY(16);
        Line road = new Line(64, 62, W - 64, 62);
        road.getStyleClass().add("intro-road");
        p.getChildren().addAll(road, server, serverName, you, friend, youName, friendName, message);
        String plain = I18n.t("intro.hello");
        String[] noise = {"9f 3a c1 7e", "e2 08 5b d4", "41 bb 0c 9a", "7d e5 26 f0"};
        Timeline t = new Timeline();
        double start = 60;
        double end = W - 60 - 70;
        for (int i = 0; i <= 40; i++) {
            double f = i / 40.0;
            double x = start + (end - start) * f;
            String text = f < 0.22 || f > 0.78 ? plain : noise[i % noise.length];
            boolean sealed = !(f < 0.22 || f > 0.78);
            t.getKeyFrames().add(new KeyFrame(Duration.millis(300 + i * 70), e -> {
                message.setText(text);
                message.pseudoClassStateChanged(SEALED, sealed);
            }, new KeyValue(message.layoutXProperty(), x)));
        }
        t.getKeyFrames().add(new KeyFrame(Duration.millis(300 + 41 * 70 + 1400)));
        message.setLayoutX(start);
        return t;
    }

    // ------------------------------------------------------------------ 2: X3DH, three pairs meet

    private static Timeline agreement(Pane p) {
        Node you = tent(40, "intro-you");
        Node friend = tent(W - 40, "intro-friend");
        p.getChildren().addAll(you, friend, caption(I18n.t("intro.you"), 40, 100), caption(I18n.t("intro.friend"), W - 40, 100));
        String[] kinds = {"identity", "signed", "onetime"};
        Timeline t = new Timeline();
        Group key = keyShape();
        key.setLayoutX(W / 2 - 16);
        key.setLayoutY(44);
        key.setOpacity(0);
        key.setScaleX(0.4);
        key.setScaleY(0.4);
        for (int i = 0; i < 3; i++) {
            double y = 34 + i * 22;
            Circle a = orb(64, y, kinds[i]);
            Circle b = orb(W - 64, y, kinds[i]);
            p.getChildren().addAll(a, b);
            Duration from = Duration.millis(300 + i * 450);
            Duration meet = Duration.millis(1500 + i * 450);
            t.getKeyFrames().addAll(
                    new KeyFrame(from, new KeyValue(a.centerXProperty(), 64), new KeyValue(b.centerXProperty(), W - 64),
                            new KeyValue(a.centerYProperty(), y), new KeyValue(b.centerYProperty(), y),
                            new KeyValue(a.opacityProperty(), 1), new KeyValue(b.opacityProperty(), 1)),
                    new KeyFrame(meet, new KeyValue(a.centerXProperty(), W / 2 - 7), new KeyValue(b.centerXProperty(), W / 2 + 7),
                            new KeyValue(a.centerYProperty(), 58), new KeyValue(b.centerYProperty(), 58)),
                    new KeyFrame(Duration.millis(3200), new KeyValue(a.opacityProperty(), 1), new KeyValue(b.opacityProperty(), 1)),
                    new KeyFrame(Duration.millis(3500), new KeyValue(a.opacityProperty(), 0), new KeyValue(b.opacityProperty(), 0)));
        }
        Label shared = caption(I18n.t("intro.sharedKey"), W / 2, 92);
        shared.setOpacity(0);
        p.getChildren().addAll(key, shared);
        t.getKeyFrames().addAll(
                new KeyFrame(Duration.millis(3200), new KeyValue(key.opacityProperty(), 0), new KeyValue(key.scaleXProperty(), 0.4),
                        new KeyValue(key.scaleYProperty(), 0.4), new KeyValue(shared.opacityProperty(), 0)),
                new KeyFrame(Duration.millis(3700), new KeyValue(key.opacityProperty(), 1), new KeyValue(key.scaleXProperty(), 1),
                        new KeyValue(key.scaleYProperty(), 1), new KeyValue(shared.opacityProperty(), 1)),
                new KeyFrame(Duration.millis(5600)));
        return t;
    }

    // ------------------------------------------------------------------ 3: compare the numbers

    private static Timeline verification(Pane p) {
        String[] groups = {"3815", "0927", "6640", "1172"};
        Label left = number(groups, 30);
        Label right = number(groups, W - 30 - 120);
        Label check = new Label("✓");
        check.getStyleClass().add("intro-check");
        check.setLayoutX(W / 2 - 14);
        check.setLayoutY(36);
        check.setOpacity(0);
        Label same = caption(I18n.t("intro.same"), W / 2, 92);
        same.setOpacity(0);
        Rectangle scan = new Rectangle(0, 20, 4, 70);
        scan.getStyleClass().add("intro-scan");
        p.getChildren().addAll(left, right, scan, check, same, caption(I18n.t("intro.you"), 90, 100),
                caption(I18n.t("intro.friend"), W - 90, 100));
        Timeline t = new Timeline(
                new KeyFrame(Duration.millis(300), new KeyValue(scan.xProperty(), 26), new KeyValue(scan.opacityProperty(), 0)),
                new KeyFrame(Duration.millis(400), new KeyValue(scan.opacityProperty(), 0.8)),
                new KeyFrame(Duration.millis(2600), new KeyValue(scan.xProperty(), W - 30), new KeyValue(scan.opacityProperty(), 0.8)),
                new KeyFrame(Duration.millis(2800), new KeyValue(scan.opacityProperty(), 0), new KeyValue(check.opacityProperty(), 0),
                        new KeyValue(check.scaleXProperty(), 0.5), new KeyValue(check.scaleYProperty(), 0.5),
                        new KeyValue(same.opacityProperty(), 0)),
                new KeyFrame(Duration.millis(3200), new KeyValue(check.opacityProperty(), 1), new KeyValue(check.scaleXProperty(), 1),
                        new KeyValue(check.scaleYProperty(), 1), new KeyValue(same.opacityProperty(), 1)),
                new KeyFrame(Duration.millis(5200)));
        return t;
    }

    // ------------------------------------------------------------------ pieces

    private static final javafx.css.PseudoClass SEALED = javafx.css.PseudoClass.getPseudoClass("sealed");

    private static Node tent(double x, String id) {
        SVGPath tent = new SVGPath();
        tent.setContent("M0 30 L18 0 L36 30 Z M14 30 L18 18 L22 30");
        tent.getStyleClass().add("intro-tent");
        tent.setLayoutX(x - 18);
        tent.setLayoutY(46);
        tent.setId(id);
        return tent;
    }

    private static Circle orb(double x, double y, String kind) {
        Circle c = new Circle(x, y, 7);
        c.getStyleClass().addAll("intro-orb", "intro-orb-" + kind);
        return c;
    }

    private static Group keyShape() {
        Circle bow = new Circle(8, 14, 8);
        Rectangle blade = new Rectangle(14, 11, 20, 6);
        Rectangle tooth = new Rectangle(28, 17, 4, 6);
        for (Node n : new Node[] {bow, blade, tooth}) n.getStyleClass().add("intro-key");
        Circle hole = new Circle(8, 14, 3, Color.TRANSPARENT);
        hole.getStyleClass().add("intro-key-hole");
        return new Group(bow, blade, tooth, hole);
    }

    private static Label number(String[] groups, double x) {
        Label l = new Label(String.join(" ", groups));
        l.getStyleClass().add("intro-number");
        l.setLayoutX(x);
        l.setLayoutY(40);
        l.setPrefWidth(120);
        return l;
    }

    private static Label caption(String text, double centerX, double y) {
        Label l = new Label(text);
        l.getStyleClass().add("intro-caption");
        l.setPrefWidth(110);
        l.setAlignment(Pos.CENTER);
        l.setLayoutX(centerX - 55);
        l.setLayoutY(y);
        return l;
    }
}
