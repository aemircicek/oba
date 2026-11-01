package com.emirchat.ui;

import com.emirchat.trust.KeyChangedException;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** A pinned peer presented a different key. The only way forward is out-of-band verification. */
final class KeyChangedScreen {
    private final ScrollPane root;

    KeyChangedScreen(KeyChangedException e, Runnable onBack) {
        String peer = e.peerName();
        StackPane badge = new StackPane(Icons.of(Icons.ALERT, 28, "danger"));
        badge.getStyleClass().addAll("icon-badge", "danger");
        Label title = new Label(peer + "'s identity key has changed");
        title.getStyleClass().addAll("h1");
        title.setWrapText(true);
        title.setId("key-changed-title");
        title.setStyle("-fx-text-alignment: center;");
        Label body = new Label("The connection was blocked. Someone may be impersonating " + peer
                + " (a man-in-the-middle attack), or " + peer + " set up secure-chat again with a new key.");
        body.getStyleClass().add("body");
        body.setWrapText(true);
        body.setStyle("-fx-text-alignment: center;");
        VBox header = new VBox(10, badge, title, body);
        header.setAlignment(Pos.CENTER);

        VBox before = section("TRUSTED BEFORE", e.expectedFingerprint());
        VBox now = section("PRESENTED NOW", e.receivedFingerprint());
        Label nowLabel = (Label) now.getChildren().get(0);
        nowLabel.getStyleClass().add("danger-text");
        VBox compare = new VBox(16, before, separator(), now);
        compare.getStyleClass().add("card");

        Label howTitle = new Label("What to do");
        howTitle.getStyleClass().add("field-label");
        Label how = new Label("Ask " + peer + " over a phone call whether they reinstalled. If they did and the new "
                + "pattern matches theirs, remove the line starting with \"" + peer + "\" from this file and connect again:");
        how.getStyleClass().add("body");
        how.setWrapText(true);
        Label path = new Label(e.knownPeersFile());
        path.getStyleClass().addAll("mono", "mono-small");
        path.setWrapText(true);
        VBox advice = new VBox(8, howTitle, how, path);
        advice.getStyleClass().add("card-flat");

        Button back = new Button("Back to start");
        back.setId("back");
        back.getStyleClass().add("primary");
        back.setMaxWidth(Double.MAX_VALUE);
        back.setDefaultButton(true);
        back.setOnAction(ev -> onBack.run());

        VBox column = new VBox(20, header, compare, advice, back);
        column.setMaxWidth(420);
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

    private static VBox section(String label, String fingerprint) {
        Label l = new Label(label);
        l.getStyleClass().add("overline");
        return new VBox(8, l, FingerprintView.compact(fingerprint));
    }

    private static javafx.scene.layout.Region separator() {
        javafx.scene.layout.Region r = new javafx.scene.layout.Region();
        r.getStyleClass().add("separator-line");
        return r;
    }
}
