package com.emirchat.ui;

import java.io.IOException;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/** The desktop application. */
public final class SecureChatApp extends Application {
    private static volatile Path home;
    private AppController controller;

    /** Launches the app with keys and known peers stored in {@code homeDirectory}. */
    public static void launchWith(Path homeDirectory) {
        home = homeDirectory;
        Application.launch(SecureChatApp.class);
    }

    @Override
    public void start(Stage stage) {
        StackPane root = new StackPane();
        Theme.install(root);
        Scene scene = new Scene(root, 460, 760);

        stage.setTitle("secure-chat");
        stage.setMinWidth(400);
        stage.setMinHeight(620);
        stage.setScene(scene);
        try {
            controller = new AppController(root, home != null ? home : defaultHome());
            controller.showStart(null);
        } catch (IOException e) {
            Label error = new Label("Could not load your identity key:\n" + e.getMessage());
            error.getStyleClass().addAll("notice", "error");
            error.setWrapText(true);
            root.getChildren().setAll(error);
        }
        stage.setOnCloseRequest(e -> {
            if (controller != null) controller.shutdown();
            Platform.exit();
        });
        stage.show();
    }

    @Override
    public void stop() {
        if (controller != null) controller.shutdown();
    }

    private static Path defaultHome() {
        return Path.of(System.getProperty("user.home"), ".securechat");
    }
}
