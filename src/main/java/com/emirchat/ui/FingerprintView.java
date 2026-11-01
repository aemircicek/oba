package com.emirchat.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/** Visual + textual rendering of an identity fingerprint. */
final class FingerprintView {
    private FingerprintView() {}

    /** The colour grid alone. */
    static Node art(String fingerprint, double cell, double gap) {
        GridPane grid = new GridPane();
        grid.setHgap(gap);
        grid.setVgap(gap);
        double[] hues = FingerprintArt.hues(fingerprint);
        for (int i = 0; i < hues.length; i++) {
            Rectangle r = new Rectangle(cell, cell, Color.hsb(hues[i], 0.62, 0.86));
            r.setArcWidth(cell * 0.45);
            r.setArcHeight(cell * 0.45);
            grid.add(r, i % FingerprintArt.COLUMNS, i / FingerprintArt.COLUMNS);
        }
        grid.setMaxSize(GridPane.USE_PREF_SIZE, GridPane.USE_PREF_SIZE);
        return grid;
    }

    /** Large layout: grid above four rows of hex, for the verification screen. */
    static Node large(String fingerprint) {
        VBox hex = new VBox(4);
        hex.setAlignment(Pos.CENTER);
        for (String line : FingerprintArt.hexLines(fingerprint, 4)) {
            Label l = new Label(line);
            l.getStyleClass().addAll("mono", "mono-large");
            hex.getChildren().add(l);
        }
        VBox box = new VBox(18, art(fingerprint, 22, 5), hex);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    /** Compact layout: small grid beside two lines of hex. */
    static Node compact(String fingerprint) {
        VBox hex = new VBox(2);
        for (String line : FingerprintArt.hexLines(fingerprint, 2)) {
            Label l = new Label(line);
            l.getStyleClass().addAll("mono", "mono-small");
            hex.getChildren().add(l);
        }
        HBox box = new HBox(12, art(fingerprint, 8, 2), hex);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }
}
