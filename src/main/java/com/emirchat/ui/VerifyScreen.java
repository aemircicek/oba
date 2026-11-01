package com.emirchat.ui;

import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** First contact with a peer: compare fingerprints out-of-band, then trust or reject. */
final class VerifyScreen {
    private final ScrollPane root;

    VerifyScreen(String peerName, String peerFingerprint, String myFingerprint, Runnable onTrust, Runnable onReject) {
        StackPane badge = new StackPane(Icons.of(Icons.KEY, 26, "accent"));
        badge.getStyleClass().addAll("icon-badge", "accent");
        Label title = new Label("Verify " + peerName);
        title.getStyleClass().add("h1");
        title.setId("verify-title");
        Label body = new Label("This is your first conversation with " + peerName + ". Compare the pattern and code "
                + "below with what " + peerName + " sees for you, over a phone call or in person. "
                + "If they differ, someone may be intercepting the connection.");
        body.getStyleClass().add("body");
        body.setWrapText(true);
        body.setStyle("-fx-text-alignment: center;");
        VBox header = new VBox(10, badge, title, body);
        header.setAlignment(Pos.CENTER);

        Label peerLabel = new Label(peerName.toUpperCase() + "'S FINGERPRINT");
        peerLabel.getStyleClass().add("overline");
        VBox peerCard = new VBox(16, peerLabel, FingerprintView.large(peerFingerprint));
        peerCard.setAlignment(Pos.CENTER);
        peerCard.getStyleClass().add("card");

        Label mineLabel = new Label(peerName.toUpperCase() + " SHOULD SEE THIS FOR YOU");
        mineLabel.getStyleClass().add("overline");
        VBox mineCard = new VBox(10, mineLabel, FingerprintView.compact(myFingerprint));
        mineCard.getStyleClass().add("card-flat");

        Button reject = new Button("They don't match");
        reject.setId("reject");
        reject.getStyleClass().add("danger-ghost");
        reject.setMaxWidth(Double.MAX_VALUE);
        reject.setOnAction(e -> onReject.run());
        Button trust = new Button("They match, trust " + peerName);
        trust.setId("trust");
        trust.getStyleClass().add("primary");
        trust.setMaxWidth(Double.MAX_VALUE);
        trust.setOnAction(e -> onTrust.run());
        HBox.setHgrow(reject, Priority.ALWAYS);
        HBox.setHgrow(trust, Priority.ALWAYS);
        HBox buttons = new HBox(10, reject, trust);

        Label note = new Label("Once trusted, the key is remembered. You'll be warned if it ever changes.");
        note.getStyleClass().add("caption");
        note.setWrapText(true);

        VBox column = new VBox(20, header, peerCard, mineCard, buttons, note);
        column.setMaxWidth(420);
        column.setAlignment(Pos.TOP_CENTER);
        note.setMaxWidth(420);

        VBox page = new VBox(column);
        page.setAlignment(Pos.CENTER);
        page.getStyleClass().add("screen");
        root = new ScrollPane(page);
        root.setFitToWidth(true);
        root.setFitToHeight(true);
        root.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    }

    Parent root() {
        return root;
    }
}
