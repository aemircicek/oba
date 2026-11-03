package com.emirchat.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;

/**
 * Small desert scenes for empty screens, drawn from shapes so they follow the theme and accent:
 * a sun (or moon) over dunes, a cactus, and a tent with a small campfire.
 */
final class Illustrations {
    private Illustrations() {}

    /** The scene, {@code width} pixels wide (height is 60% of that). */
    static Node desert(double width) {
        double w = 300;
        double h = 180;
        Pane scene = new Pane();
        scene.setPrefSize(w, h);
        scene.setMinSize(w, h);
        scene.setMaxSize(w, h);
        Rectangle clip = new Rectangle(w, h);
        clip.setArcWidth(36);
        clip.setArcHeight(36);
        scene.setClip(clip);
        scene.getStyleClass().add("illus");

        for (double[] star : new double[][] {{40, 30, 1.4}, {92, 18, 1}, {250, 26, 1.2}, {214, 52, 0.9}, {150, 14, 1}}) {
            Circle s = new Circle(star[0], star[1], star[2]);
            s.getStyleClass().add("illus-star");
            scene.getChildren().add(s);
        }
        Circle sun = new Circle(216, 70, 26);
        sun.getStyleClass().add("illus-sun");
        Circle glow = new Circle(216, 70, 40);
        glow.getStyleClass().add("illus-glow");
        scene.getChildren().addAll(glow, sun);

        SVGPath far = path("M0 128 C 50 100, 90 104, 140 122 S 240 96, 300 112 L 300 180 L 0 180 Z", "illus-dune-far");
        SVGPath near = path("M0 150 C 60 128, 120 132, 170 148 S 260 136, 300 146 L 300 180 L 0 180 Z", "illus-dune-near");
        // a saguaro: trunk and two arms
        SVGPath cactus = path("M62 150 L62 96 Q62 88 69 88 Q76 88 76 96 L76 150 Z "
                + "M62 122 L54 122 Q46 122 46 114 L46 104 Q46 98 51 98 Q56 98 56 104 L56 112 L62 112 Z "
                + "M76 116 L84 116 Q90 116 90 108 L90 100 Q90 94 85 94 Q80 94 80 100 L80 106 L76 106 Z", "illus-cactus");
        scene.getChildren().addAll(far, cactus, near);

        // a tent on the dune with a small fire in front of it
        SVGPath tent = path("M226 150 L258 96 L290 150 Z", "illus-tent");
        SVGPath tentShade = path("M258 96 L290 150 L258 150 Z", "illus-tent-shade");
        SVGPath door = path("M258 122 L250 150 L268 150 Z", "illus-tent-door");
        SVGPath pennant = path("M257 96 L257 82 L270 86 L258 90 Z", "illus-pennant");
        scene.getChildren().addAll(tent, tentShade, door, pennant);
        Circle fireGlow = new Circle(200, 146, 26);
        fireGlow.getStyleClass().add("illus-fire-glow");
        Flame fire = new Flame(30);
        fire.setLayoutX(185);
        fire.setLayoutY(120);
        scene.getChildren().addAll(fireGlow, fire);
        // a few footprints in the sand
        for (int i = 0; i < 4; i++) {
            Circle print = new Circle(112 + i * 12, 162 - (i % 2) * 4, 1.8);
            print.getStyleClass().add("illus-print");
            scene.getChildren().add(print);
        }
        double scale = width / w;
        Group g = new Group(scene);
        g.setScaleX(scale);
        g.setScaleY(scale);
        return new Group(g);
    }

    private static SVGPath path(String d, String style) {
        SVGPath p = new SVGPath();
        p.setContent(d);
        p.getStyleClass().add(style);
        return p;
    }
}
