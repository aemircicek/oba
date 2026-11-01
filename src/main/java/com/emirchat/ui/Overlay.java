package com.emirchat.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** A modal layer over the main window: dims the app and shows one dialog card. Esc or a click outside closes it. */
final class Overlay {
    private final StackPane host;
    private StackPane current;

    Overlay(StackPane host) {
        this.host = host;
    }

    void show(VBox dialog) {
        close();
        dialog.getStyleClass().add("dialog");
        dialog.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        StackPane layer = new StackPane(dialog);
        layer.getStyleClass().add("overlay");
        layer.setAlignment(Pos.CENTER);
        layer.setOnMouseClicked(e -> {
            if (e.getTarget() == layer) close();
        });
        layer.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) close();
        });
        current = layer;
        host.getChildren().add(layer);
        layer.requestFocus();
    }

    void close() {
        if (current != null) {
            host.getChildren().remove(current);
            current = null;
        }
    }

    boolean isOpen() {
        return current != null;
    }

    static VBox dialog(String title, Node... content) {
        VBox box = new VBox(16);
        box.getChildren().add(UiKit.label(title, "h2"));
        box.getChildren().addAll(content);
        return box;
    }
}
