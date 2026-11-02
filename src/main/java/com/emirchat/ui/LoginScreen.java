package com.emirchat.ui;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Sign in or create an account on a secure-chat server. */
final class LoginScreen {
    record Request(String server, String username, String password, boolean register) {}

    interface Actions {
        void submit(Request request);

        void directMode();
    }

    private final VBox root;
    private final Label error = new Label();
    private final Button go = new Button();

    LoginScreen(String savedServer, String savedUser, String notice, Actions actions) {
        StackPane badge = new StackPane(Icons.of(Icons.SHIELD_CHECK, 28, "accent"));
        badge.getStyleClass().addAll("icon-badge", "accent");
        Label title = UiKit.label("secure-chat", "h1");
        Label tagline = UiKit.label("End-to-end encrypted messages, friends and groups.\nYour server never sees what you write.", "body");
        tagline.setWrapText(true);
        tagline.setStyle("-fx-text-alignment: center;");
        VBox brand = new VBox(10, badge, title, tagline);
        brand.setAlignment(Pos.CENTER);

        ToggleGroup mode = new ToggleGroup();
        ToggleButton signIn = new ToggleButton("Sign in");
        ToggleButton create = new ToggleButton("Create account");
        signIn.setId("mode-signin");
        create.setId("mode-register");
        for (ToggleButton b : new ToggleButton[] {signIn, create}) {
            b.setToggleGroup(mode);
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, Priority.ALWAYS);
        }
        mode.selectedToggleProperty().addListener((o, old, now) -> {
            if (now == null) old.setSelected(true);
        });
        signIn.setSelected(true);
        HBox segmented = new HBox(signIn, create);
        segmented.getStyleClass().add("segmented");

        TextField server = new TextField(savedServer);
        server.setPromptText("192.168.1.3 or 192.168.1.3:5056");
        server.setId("server");
        TextField user = new TextField(savedUser);
        user.setPromptText("e.g. emir");
        user.setId("username");
        PasswordField password = new PasswordField();
        password.setPromptText("At least 8 characters");
        password.setId("password");
        PasswordField repeat = new PasswordField();
        repeat.setPromptText("Repeat password");
        repeat.setId("password-repeat");

        VBox repeatBox = field("Repeat password", repeat);
        repeatBox.managedProperty().bind(repeatBox.visibleProperty());
        repeatBox.visibleProperty().bind(create.selectedProperty());

        error.getStyleClass().addAll("notice", "error");
        error.setWrapText(true);
        error.setMaxWidth(Double.MAX_VALUE);
        error.managedProperty().bind(error.visibleProperty());
        error.setVisible(false);
        error.setId("login-error");

        go.setId("login");
        go.getStyleClass().add("primary");
        go.setMaxWidth(Double.MAX_VALUE);
        go.setDefaultButton(true);
        go.textProperty().bind(Bindings.when(create.selectedProperty()).then("Create account").otherwise("Sign in"));
        go.disableProperty().bind(Bindings.createBooleanBinding(
                () -> server.getText().isBlank() || user.getText().isBlank() || password.getText().isEmpty(),
                server.textProperty(), user.textProperty(), password.textProperty()));
        go.setOnAction(e -> {
            if (create.isSelected() && !password.getText().equals(repeat.getText())) {
                showError("The passwords do not match.");
                return;
            }
            error.setVisible(false);
            actions.submit(new Request(server.getText().strip(), user.getText().strip(), password.getText(),
                    create.isSelected()));
        });

        VBox form = new VBox(14, segmented, field("Server", server), field("Username", user),
                field("Password", password), repeatBox, error, go);
        form.getStyleClass().add("card");

        Button direct = new Button("Direct connection without an account");
        direct.getStyleClass().add("link");
        direct.setId("direct-mode");
        direct.setOnAction(e -> actions.directMode());

        VBox column = new VBox(22, brand);
        if (notice != null) {
            Label n = UiKit.label(notice, "notice", "info");
            n.setWrapText(true);
            n.setMaxWidth(Double.MAX_VALUE);
            column.getChildren().add(n);
        }
        column.getChildren().addAll(form, direct);
        column.setAlignment(Pos.TOP_CENTER);
        column.setMaxWidth(400);
        root = new VBox(column);
        root.setAlignment(Pos.CENTER);
        root.getStyleClass().add("screen");
        Platform.runLater(() -> (user.getText().isBlank() ? user : password).requestFocus());
    }

    Parent root() {
        return root;
    }

    void showError(String message) {
        error.setText(message);
        error.setVisible(true);
    }

    void setBusy(boolean busy) {
        root.setDisable(busy);
    }

    private static VBox field(String label, javafx.scene.Node input) {
        return new VBox(6, UiKit.label(label, "field-label"), input);
    }
}
