package com.emirchat.ui;

import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Shown while hosting (waiting for a peer) or while connecting to a host. */
final class WaitingScreen {
    private final VBox root;
    private final Label title = new Label();
    private final Label body = new Label();
    private final Label status = new Label();
    private final VBox addresses = new VBox(8);

    WaitingScreen(String titleText, String bodyText, Runnable onCancel) {
        Flame spinner = Flame.spinner(46);

        title.setText(titleText);
        title.getStyleClass().add("h2");
        body.setText(bodyText);
        body.getStyleClass().add("body");
        body.setWrapText(true);
        body.setStyle("-fx-text-alignment: center;");
        status.getStyleClass().add("caption");
        status.setId("status");

        addresses.setAlignment(Pos.CENTER);
        addresses.setMaxWidth(320);
        addresses.managedProperty().bind(addresses.visibleProperty());
        addresses.setVisible(false);

        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setId("cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> onCancel.run());

        VBox column = new VBox(14, spinner, title, body, addresses, status, cancel);
        VBox.setMargin(cancel, new javafx.geometry.Insets(10, 0, 0, 0));
        column.setAlignment(Pos.CENTER);
        column.setMaxWidth(380);

        root = new VBox(column);
        root.setAlignment(Pos.CENTER);
        root.getStyleClass().add("screen");
    }

    Parent root() {
        return root;
    }

    void setStatus(String text) {
        status.setText(text);
    }

    void setTitle(String text, String bodyText) {
        title.setText(text);
        body.setText(bodyText);
    }

    void showAddresses(List<String> hosts, int port) {
        addresses.getChildren().clear();
        if (hosts.isEmpty()) {
            Label none = new Label(I18n.t("waiting.noAddress"));
            none.getStyleClass().addAll("notice", "info");
            none.setWrapText(true);
            addresses.getChildren().add(none);
        }
        for (String host : hosts) {
            Label address = new Label(host + (port == com.emirchat.net.Connector.DEFAULT_PORT ? "" : ":" + port));
            address.getStyleClass().addAll("mono", "mono-large");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Button copy = new Button();
            copy.setGraphic(Icons.of(Icons.COPY, 16, "muted"));
            copy.getStyleClass().add("icon-button");
            copy.setTooltip(new Tooltip(I18n.t("waiting.copy")));
            copy.setOnAction(e -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(host);
                Clipboard.getSystemClipboard().setContent(content);
                copy.setGraphic(Icons.of(Icons.CHECK, 16, "accent"));
            });
            HBox chip = new HBox(8, address, spacer, copy);
            chip.setAlignment(Pos.CENTER_LEFT);
            chip.getStyleClass().add("chip");
            addresses.getChildren().add(chip);
        }
        if (port != com.emirchat.net.Connector.DEFAULT_PORT) {
            Label hint = new Label(I18n.t("waiting.port", port));
            hint.getStyleClass().add("caption");
            addresses.getChildren().add(hint);
        }
        addresses.setVisible(true);
    }
}
