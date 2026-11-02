package com.emirchat.ui;

import com.emirchat.net.Connector;
import com.emirchat.protocol.Handshake;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Choose to host or join, enter a name and address. */
final class StartScreen {
    /** What the user asked for. {@code host} is null when hosting. */
    record Request(String name, String host, int port) {
        boolean hosting() { return host == null; }
    }

    interface Actions {
        void start(Request request);
    }

    /** A message shown above the form: an error (red) or neutral information. */
    record Notice(String text, boolean error) {}

    private final VBox root;

    StartScreen(String myFingerprint, Prefs.Saved saved, Notice notice, Actions actions) {
        // Brand
        StackPane badge = new StackPane(Icons.of(Icons.SHIELD_CHECK, 28, "accent"));
        badge.getStyleClass().addAll("icon-badge", "accent");
        Label title = new Label(com.emirchat.AppInfo.NAME);
        title.getStyleClass().add("h1");
        Label tagline = new Label(I18n.t("start.tagline"));
        tagline.getStyleClass().add("body");
        tagline.setWrapText(true);
        tagline.setStyle("-fx-text-alignment: center;");
        VBox brand = new VBox(10, badge, title, tagline);
        brand.setAlignment(Pos.CENTER);

        // Mode switch
        ToggleGroup group = new ToggleGroup();
        ToggleButton hostToggle = segment(I18n.t("start.host"), group);
        ToggleButton joinToggle = segment(I18n.t("start.join"), group);
        hostToggle.setId("mode-host");
        joinToggle.setId("mode-join");
        HBox segmented = new HBox(hostToggle, joinToggle);
        segmented.getStyleClass().add("segmented");
        group.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) old.setSelected(true); // keep one selected
        });
        (saved.joining() ? joinToggle : hostToggle).setSelected(true);

        // Fields
        TextField name = field(I18n.t("login.usernamePrompt"), saved.name());
        name.setId("name");
        TextField address = field("192.168.1.3", saved.host());
        address.setId("address");
        TextField port = field(String.valueOf(Connector.DEFAULT_PORT), saved.port() == Connector.DEFAULT_PORT ? "" : String.valueOf(saved.port()));
        port.setId("port");
        port.setPrefColumnCount(6);

        VBox nameBox = labelled(I18n.t("start.name"), name);
        VBox addressBox = labelled(I18n.t("start.address"), address);
        VBox portBox = labelled(I18n.t("start.port"), port);
        HBox.setHgrow(addressBox, Priority.ALWAYS);
        HBox addressRow = new HBox(10, addressBox, portBox);

        Label modeHint = new Label();
        modeHint.getStyleClass().add("caption");
        modeHint.setWrapText(true);

        Label error = new Label();
        error.getStyleClass().addAll("notice", "error");
        error.setWrapText(true);
        error.setMaxWidth(Double.MAX_VALUE);
        error.managedProperty().bind(error.visibleProperty());
        error.setVisible(false);

        Button go = new Button();
        go.setId("start");
        go.getStyleClass().add("primary");
        go.setMaxWidth(Double.MAX_VALUE);
        go.setDefaultButton(true);

        VBox form = new VBox(16, segmented, nameBox, addressRow, modeHint, error, go);
        form.getStyleClass().add("card");

        Runnable refresh = () -> {
            boolean hosting = hostToggle.isSelected();
            go.setText(hosting ? I18n.t("start.startHosting") : I18n.t("start.connect"));
            addressBox.setVisible(!hosting);
            addressBox.setManaged(!hosting);
            modeHint.setText(hosting
                    ? I18n.t("start.hostHint")
                    : I18n.t("start.joinHint"));
        };
        hostToggle.selectedProperty().addListener((obs, o, n) -> refresh.run());
        refresh.run();

        go.setOnAction(e -> {
            name.getStyleClass().remove("invalid");
            address.getStyleClass().remove("invalid");
            port.getStyleClass().remove("invalid");
            String n = name.getText().strip();
            if (!Handshake.isValidName(n)) {
                fail(error, name, I18n.t("start.badName"));
                return;
            }
            int p = Connector.DEFAULT_PORT;
            if (!port.getText().isBlank()) {
                try {
                    p = Integer.parseInt(port.getText().strip());
                    if (p < 1 || p > 65535) throw new NumberFormatException();
                } catch (NumberFormatException ex) {
                    fail(error, port, I18n.t("start.badPort"));
                    return;
                }
            }
            String h = null;
            if (joinToggle.isSelected()) {
                h = address.getText().strip();
                if (h.isEmpty() || h.contains(" ")) {
                    fail(error, address, I18n.t("start.noAddress"));
                    return;
                }
            }
            error.setVisible(false);
            actions.start(new Request(n, h, p));
        });

        // Notice from a previous attempt
        Label noticeLabel = null;
        if (notice != null) {
            noticeLabel = new Label(notice.text());
            noticeLabel.getStyleClass().addAll("notice", notice.error() ? "error" : "info");
            noticeLabel.setWrapText(true);
            noticeLabel.setMaxWidth(Double.MAX_VALUE);
            noticeLabel.setId("notice");
        }

        // Identity footer
        Label idTitle = new Label(I18n.t("start.identity"));
        idTitle.getStyleClass().add("overline");
        Button copy = new Button(I18n.t("start.copy"));
        copy.getStyleClass().add("link");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(myFingerprint);
            Clipboard.getSystemClipboard().setContent(content);
            copy.setText(I18n.t("start.copied"));
        });
        copy.setTooltip(new Tooltip(I18n.t("start.copyTip")));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox idHeader = new HBox(idTitle, spacer, copy);
        idHeader.setAlignment(Pos.CENTER_LEFT);
        VBox identity = new VBox(10, idHeader, FingerprintView.compact(myFingerprint));
        identity.getStyleClass().add("card-flat");

        VBox column = new VBox(22);
        column.getChildren().add(brand);
        if (noticeLabel != null) column.getChildren().add(noticeLabel);
        column.getChildren().addAll(form, identity);
        column.setMaxWidth(400);
        column.setAlignment(Pos.TOP_CENTER);

        root = new VBox(column);
        root.setAlignment(Pos.CENTER);
        root.getStyleClass().add("screen");

        name.textProperty().addListener((o, a, b) -> name.getStyleClass().remove("invalid"));
        go.disableProperty().bind(Bindings.createBooleanBinding(() -> name.getText().isBlank(), name.textProperty()));
        javafx.application.Platform.runLater(() -> (name.getText().isBlank() ? name : go).requestFocus());
    }

    Parent root() {
        return root;
    }

    private static void fail(Label error, TextField field, String message) {
        error.setText(message);
        error.setVisible(true);
        field.getStyleClass().add("invalid");
        field.requestFocus();
    }

    private static ToggleButton segment(String text, ToggleGroup group) {
        ToggleButton b = new ToggleButton(text);
        b.setToggleGroup(group);
        b.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(b, Priority.ALWAYS);
        return b;
    }

    private static TextField field(String prompt, String value) {
        TextField f = new TextField(value == null ? "" : value);
        f.setPromptText(prompt);
        return f;
    }

    private static VBox labelled(String label, Node field) {
        Label l = new Label(label);
        l.getStyleClass().add("field-label");
        return new VBox(6, l, field);
    }
}
