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
    private AppShell shell;

    /** Launches the app with keys and known peers stored in {@code homeDirectory}. */
    public static void launchWith(Path homeDirectory) {
        home = homeDirectory;
        Application.launch(SecureChatApp.class);
    }

    @Override
    public void start(Stage stage) {
        StackPane root = new StackPane();
        Theme.install(root);
        Scene scene = new Scene(root, 1040, 720);

        stage.setTitle(com.emirchat.AppInfo.NAME);
        for (int size : new int[] {16, 24, 32, 48, 64, 128, 256}) {
            var icon = SecureChatApp.class.getResourceAsStream("icons/icon-" + size + ".png");
            if (icon != null) stage.getIcons().add(new javafx.scene.image.Image(icon));
        }
        stage.setMinWidth(420);
        stage.setMinHeight(620);
        stage.setScene(scene);
        try {
            shell = new AppShell(root, home != null ? home : defaultHome());
            shell.showLogin(null);
        } catch (IOException e) {
            Label error = new Label("Could not load your identity key:\n" + e.getMessage());
            error.getStyleClass().addAll("notice", "error");
            error.setWrapText(true);
            root.getChildren().setAll(error);
        }
        stage.setOnCloseRequest(e -> {
            if (shell != null) shell.shutdown();
            Platform.exit();
        });
        stage.show();
    }

    @Override
    public void stop() {
        if (shell != null) shell.shutdown();
    }

    private static Path defaultHome() {
        return com.emirchat.AppInfo.defaultHome();
    }
}
