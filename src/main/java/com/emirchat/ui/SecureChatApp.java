package com.emirchat.ui;

import java.io.IOException;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * The desktop application. One window per data folder: a second start brings the first window
 * forward. Closing the window keeps Oba running in the tray (unless turned off in Settings,
 * or the desktop has no tray), so messages and notifications keep arriving.
 */
public final class SecureChatApp extends Application {
    private static volatile Path home;
    private static volatile boolean background;
    private static volatile Path openFile;
    private AppShell shell;
    private SingleInstance instance;
    private Stage stage;
    private boolean trayAvailable;

    /**
     * @param startInBackground start hidden in the tray (used when starting with the computer)
     * @param backupFile        a backup the user double-clicked, or null
     */
    public static void launchWith(Path homeDirectory, boolean startInBackground, Path backupFile) {
        home = homeDirectory;
        background = startInBackground;
        openFile = backupFile;
        Application.launch(SecureChatApp.class);
    }

    public static void launchWith(Path homeDirectory) {
        launchWith(homeDirectory, false, null);
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Path dataDir = home != null ? home : com.emirchat.AppInfo.defaultHome();
        AppLog.init(dataDir);
        Thread.ofPlatform().daemon().name("autostart-rename").start(Autostart::adoptFormer); // Scorpion → Oba, once
        boolean handedOver;
        try {
            instance = SingleInstance.acquire(dataDir, openFile != null ? "open" : "show",
                    openFile != null ? openFile.toAbsolutePath().toString() : null);
            handedOver = instance == null;
        } catch (IOException e) {
            instance = null; // could not coordinate with a running copy: run anyway
            handedOver = false;
        }
        if (handedOver) { // the running Oba shows itself (and opens the file, if any)
            Platform.exit();
            return;
        }

        AppLog.info(com.emirchat.AppInfo.NAME + " " + com.emirchat.AppInfo.version() + " started on "
                + System.getProperty("os.name") + ", Java " + System.getProperty("java.version"));
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
        AppPrefs.restoreWindow(stage);

        Platform.setImplicitExit(false);
        trayAvailable = Tray.get().available();
        if (trayAvailable) {
            Tray.get().install(() -> Platform.runLater(this::bringToFront), () -> Platform.runLater(this::quit));
        }
        if (instance != null) {
            instance.onRequest((command, argument) -> Platform.runLater(() -> {
                bringToFront();
                if (command.equals("open") && !argument.isEmpty() && shell != null) shell.openBackupFile(Path.of(argument));
            }));
        }

        try {
            shell = new AppShell(root, dataDir);
            shell.start();
            if (openFile != null) shell.openBackupFile(openFile);
        } catch (IOException e) {
            Label error = new Label(I18n.t("app.identityError", e.getMessage()));
            error.getStyleClass().addAll("notice", "error");
            error.setWrapText(true);
            root.getChildren().setAll(error);
        }
        stage.setOnCloseRequest(e -> {
            e.consume();
            AppPrefs.saveWindow(stage);
            if (trayAvailable && AppPrefs.keepRunning()) {
                stage.hide();
                if (!AppPrefs.trayHintShown()) {
                    Tray.get().notify(com.emirchat.AppInfo.NAME, I18n.t("tray.stillRunning"));
                    AppPrefs.setTrayHintShown();
                }
            } else {
                quit();
            }
        });
        if (!background || !trayAvailable) stage.show();
    }


    private void bringToFront() {
        if (!stage.isShowing()) stage.show();
        stage.setIconified(false);
        stage.toFront();
        stage.requestFocus();
    }

    private void quit() {
        if (stage.isShowing()) AppPrefs.saveWindow(stage);
        if (shell != null) shell.shutdown();
        Tray.get().remove();
        if (instance != null) instance.close();
        Platform.exit();
        // the AWT thread behind the tray icon would otherwise keep the JVM alive
        new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                // exiting
            }
            System.exit(0);
        }, "exit").start();
    }

    @Override
    public void stop() {
        if (shell != null) shell.shutdown();
    }
}
