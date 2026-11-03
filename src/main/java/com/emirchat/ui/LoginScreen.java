package com.emirchat.ui;

import com.emirchat.client.DefaultServer;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Sign in or create an account. With a default server configured, the server field is tucked
 * away under "Advanced" (an empty field means the default server).
 */
final class LoginScreen {
    /** @param server empty for the default server */
    record Request(String server, String username, String password, boolean register, boolean remember) {}

    interface Actions {
        void submit(Request request);

        void directMode();

        /** Restore keys and history from an encrypted backup file. */
        default void restore() {}

        /** Switch the interface language ("en" or "tr"). */
        default void language(String code) {}
    }

    private final javafx.scene.control.ScrollPane root;
    private final Label error = new Label();
    private final Button go = new Button();

    LoginScreen(String savedServer, String savedUser, String notice, DefaultServer defaults, boolean canRemember,
                Actions actions) {
        javafx.scene.image.ImageView logo = new javafx.scene.image.ImageView(
                new javafx.scene.image.Image(LoginScreen.class.getResourceAsStream("icons/icon-128.png")));
        logo.setFitWidth(64);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        StackPane badge = new StackPane(logo);
        Label title = UiKit.label(com.emirchat.AppInfo.NAME, "h1");
        Label tagline = UiKit.label(I18n.t("login.tagline"), "body");
        tagline.setWrapText(true);
        tagline.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        tagline.setStyle("-fx-text-alignment: center;");
        VBox brand = new VBox(10, badge, title, tagline);
        brand.setAlignment(Pos.CENTER);

        ToggleGroup mode = new ToggleGroup();
        ToggleButton signIn = new ToggleButton(I18n.t("login.signIn"));
        ToggleButton create = new ToggleButton(I18n.t("login.create"));
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
        server.setPromptText(defaults.configured() ? defaults.address() : I18n.t("login.serverPrompt"));
        server.setId("server");
        TextField user = new TextField(savedUser);
        user.setPromptText(I18n.t("login.usernamePrompt"));
        user.setId("username");
        PasswordField password = new PasswordField();
        password.setPromptText(I18n.t("login.passwordPrompt"));
        password.setId("password");
        PasswordField repeat = new PasswordField();
        repeat.setPromptText(I18n.t("login.repeat"));
        repeat.setId("password-repeat");

        VBox repeatBox = field(I18n.t("login.repeat"), repeat);
        repeatBox.managedProperty().bind(repeatBox.visibleProperty());
        repeatBox.visibleProperty().bind(create.selectedProperty());

        // Server: always visible without a default; otherwise behind "Advanced"
        VBox serverBox = field(I18n.t("login.server"), server);
        serverBox.managedProperty().bind(serverBox.visibleProperty());
        Button advanced = new Button(I18n.t("login.advanced"));
        advanced.getStyleClass().add("link");
        advanced.setId("advanced");
        Label serverNote = UiKit.label(I18n.t("login.defaultServerNote"), "caption");
        serverNote.setWrapText(true);
        serverNote.managedProperty().bind(serverNote.visibleProperty());
        if (defaults.configured()) {
            boolean custom = savedServer != null && !savedServer.isBlank();
            serverBox.setVisible(custom);
            serverNote.setVisible(custom);
            advanced.setVisible(!custom);
            advanced.setOnAction(e -> {
                serverBox.setVisible(true);
                serverNote.setVisible(true);
                advanced.setVisible(false);
                server.requestFocus();
            });
        } else {
            serverNote.setVisible(false);
            advanced.setVisible(false);
        }
        advanced.managedProperty().bind(advanced.visibleProperty());

        CheckBox remember = new CheckBox(I18n.t("login.remember"));
        remember.setId("remember");
        remember.setVisible(canRemember);
        remember.managedProperty().bind(remember.visibleProperty());

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
        go.textProperty().bind(Bindings.when(create.selectedProperty())
                .then(I18n.t("login.create")).otherwise(I18n.t("login.signIn")));
        go.disableProperty().bind(Bindings.createBooleanBinding(
                () -> (!defaults.configured() && server.getText().isBlank()) || user.getText().isBlank()
                        || password.getText().isEmpty(),
                server.textProperty(), user.textProperty(), password.textProperty()));
        go.setOnAction(e -> {
            if (create.isSelected() && !password.getText().equals(repeat.getText())) {
                showError(I18n.t("login.mismatch"));
                return;
            }
            error.setVisible(false);
            actions.submit(new Request(server.getText().strip(), user.getText().strip(), password.getText(),
                    create.isSelected(), remember.isSelected()));
        });

        VBox form = new VBox(14, segmented, serverBox, serverNote, field(I18n.t("login.username"), user),
                field(I18n.t("login.password"), password), repeatBox, remember, error, go, advanced);
        form.getStyleClass().add("card");

        Button direct = new Button(I18n.t("login.direct"));
        direct.getStyleClass().add("link");
        direct.setId("direct-mode");
        direct.setOnAction(e -> actions.directMode());

        VBox column = new VBox(18, brand);
        if (notice != null) {
            Label n = UiKit.label(notice, "notice", "info");
            n.setWrapText(true);
            n.setMaxWidth(Double.MAX_VALUE);
            n.setId("login-notice");
            column.getChildren().add(n);
        }
        Button restore = new Button(I18n.t("login.restore"));
        restore.getStyleClass().add("link");
        restore.setId("restore-backup");
        restore.setOnAction(e -> actions.restore());
        HBox languages = new HBox(6);
        languages.setAlignment(Pos.CENTER);
        for (String[] l : new String[][] {{"en", "English"}, {"tr", "Türkçe"}}) {
            if (!languages.getChildren().isEmpty()) languages.getChildren().add(UiKit.label("·", "caption"));
            Button b = new Button(l[1]);
            b.getStyleClass().add("link");
            b.setId("language-" + l[0]);
            if (I18n.language().equals(l[0])) b.setStyle("-fx-font-weight: bold;");
            b.setOnAction(e -> actions.language(l[0]));
            languages.getChildren().add(b);
        }
        column.getChildren().addAll(form, direct, restore, languages);
        column.setAlignment(Pos.TOP_CENTER);
        column.setMaxWidth(400);
        VBox page = new VBox(column);
        page.setAlignment(Pos.CENTER);
        page.getStyleClass().add("screen");
        root = new javafx.scene.control.ScrollPane(page); // small windows scroll instead of cutting the top off
        root.setFitToWidth(true);
        root.setFitToHeight(true);
        root.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        root.getStyleClass().add("screen-scroll");
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
