package com.emirchat.ui;

import com.emirchat.crypto.Identity;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** First connection to a server: compare its fingerprint with what the server printed at start-up. */
final class ServerTrustScreen {
    private final VBox root;

    ServerTrustScreen(String server, String fingerprint, Runnable onTrust, Runnable onReject) {
        StackPane badge = new StackPane(Icons.of(Icons.SHIELD, 26, "accent"));
        badge.getStyleClass().addAll("icon-badge", "accent");
        Label title = UiKit.label(I18n.t("serverTrust.title"), "h1");
        Label body = UiKit.label(I18n.t("serverTrust.body", server), "body");
        body.setWrapText(true);
        body.setStyle("-fx-text-alignment: center;");
        VBox header = new VBox(10, badge, title, body);
        header.setAlignment(Pos.CENTER);

        VBox card = new VBox(16, UiKit.label(I18n.t("serverTrust.fingerprint"), "overline"), FingerprintView.large(fingerprint));
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("card");

        Button reject = new Button(I18n.t("common.cancel"));
        reject.setId("server-reject");
        reject.getStyleClass().add("danger-ghost");
        reject.setMaxWidth(Double.MAX_VALUE);
        reject.setOnAction(e -> onReject.run());
        Button trust = new Button(I18n.t("serverTrust.trust"));
        trust.setId("server-trust");
        trust.getStyleClass().add("primary");
        trust.setMaxWidth(Double.MAX_VALUE);
        trust.setDefaultButton(true);
        trust.setOnAction(e -> onTrust.run());
        HBox.setHgrow(reject, Priority.ALWAYS);
        HBox.setHgrow(trust, Priority.ALWAYS);

        VBox column = new VBox(20, header, card, new HBox(10, reject, trust),
                UiKit.label(Identity.pretty(fingerprint).replace('\n', ' '), "caption"));
        column.setMaxWidth(420);
        column.setAlignment(Pos.TOP_CENTER);
        root = new VBox(column);
        root.setAlignment(Pos.CENTER);
        root.getStyleClass().add("screen");
    }

    Parent root() {
        return root;
    }
}
