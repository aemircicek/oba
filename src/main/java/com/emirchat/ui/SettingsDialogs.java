package com.emirchat.ui;

import java.io.IOException;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Settings and what belongs with them: privacy and notification switches, appearance, backups,
 * the welcome pages shown on first start, and About.
 */
final class SettingsDialogs {
    private final MainView main;

    SettingsDialogs(MainView main) {
        this.main = main;
    }

    /** A short tour the first time someone signs in. */
    void openWelcome(int page) {
        String[][] pages = {
                {Icons.LOCK, "welcome.title1", "welcome.body1"},
                {Icons.SHIELD_CHECK, "welcome.title2", "welcome.body2"},
                {Icons.KEY, "welcome.title3", "welcome.body3"}};
        String[] p = pages[page];
        StackPane badge = new StackPane(Icons.of(p[0], 28, "accent"));
        badge.getStyleClass().addAll("icon-badge", "accent");
        Label body = UiKit.label(I18n.t(p[2]), "body");
        body.setWrapText(true);
        body.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        Label step = UiKit.label((page + 1) + " / " + pages.length, "caption");
        Button back = new Button(I18n.t("welcome.back"));
        back.setVisible(page > 0);
        back.setOnAction(e -> openWelcome(page - 1));
        boolean last = page == pages.length - 1;
        Button next = new Button(last ? I18n.t("welcome.start") : I18n.t("welcome.next"));
        next.getStyleClass().add("primary");
        next.setId("welcome-next");
        next.setDefaultButton(true);
        next.setOnAction(e -> {
            if (last) {
                AppPrefs.setWelcomeShown();
                main.overlay.close();
            } else {
                openWelcome(page + 1);
            }
        });
        VBox box = Overlay.dialog(I18n.t(p[1]), badge, body, new HBox(10, step, UiKit.grow(), back, next));
        box.setId("welcome-dialog");
        main.overlay.show(box);
    }

    private void openAbout() {
        javafx.scene.image.ImageView logo = new javafx.scene.image.ImageView(
                new javafx.scene.image.Image(MainView.class.getResourceAsStream("icons/icon-128.png")));
        logo.setFitWidth(72);
        logo.setPreserveRatio(true);
        Label name = UiKit.label(com.emirchat.AppInfo.NAME + " " + com.emirchat.AppInfo.version(), "h2");
        Label what = UiKit.label(I18n.t("about.description"), "body");
        what.setWrapText(true);
        Label license = UiKit.label(I18n.t("about.license"), "caption");
        license.setWrapText(true);
        Button site = new Button(I18n.t("about.website"));
        site.getStyleClass().add("link");
        site.setOnAction(e -> MainView.browse(com.emirchat.AppInfo.WEBSITE));
        Button notices = new Button(I18n.t("about.notices"));
        notices.getStyleClass().add("link");
        notices.setOnAction(e -> MainView.browse(com.emirchat.AppInfo.WEBSITE + "/blob/main/THIRD-PARTY-NOTICES.md"));
        Button close = new Button(I18n.t("common.close"));
        close.getStyleClass().add("primary");
        close.setOnAction(e -> main.overlay.close());
        VBox head = new VBox(8, logo, name, what);
        head.setAlignment(Pos.CENTER);
        VBox box = Overlay.dialog(I18n.t("about.title"), head, license, new HBox(10, site, notices, UiKit.grow(), close));
        box.setId("about-dialog");
        main.overlay.show(box);
    }

    void openSettings() {
        VBox toggles = new VBox(10,
                toggle(I18n.t("settings.receipts"), I18n.t("settings.receiptsHint"),
                        AccountSession.SEND_READ_RECEIPTS),
                toggle(I18n.t("settings.typing"), I18n.t("settings.typingHint"),
                        AccountSession.SEND_TYPING),
                toggle(I18n.t("settings.clock"), I18n.t("settings.clockHint"),
                        AccountSession.SHARE_CLOCK),
                toggle(I18n.t("settings.notifications"), I18n.t("settings.notificationsHint"),
                        AccountSession.NOTIFICATIONS),
                toggle(I18n.t("settings.preview"), I18n.t("settings.previewHint"),
                        AccountSession.NOTIFICATION_PREVIEW));
        if (Tray.get().available()) {
            toggles.getChildren().add(appToggle(I18n.t("settings.keepRunning"), I18n.t("settings.keepRunningHint"),
                    "setting-keep-running", AppPrefs.keepRunning(), AppPrefs::setKeepRunning));
        }
        toggles.getChildren().add(appToggle(I18n.t("settings.updates"), I18n.t("settings.updatesHint"),
                "setting-updates", AppPrefs.checkUpdates(), AppPrefs::setCheckUpdates));
        if (Autostart.supported()) {
            toggles.getChildren().add(appToggle(I18n.t("settings.autostart"), I18n.t("settings.autostartHint"),
                    "setting-autostart", Autostart.enabled(), on -> {
                        try {
                            Autostart.set(on);
                        } catch (IOException ex) {
                            main.showError(ex.getMessage());
                        }
                    }));
        }
        Button done = new Button(I18n.t("common.done"));
        done.getStyleClass().add("primary");
        done.setId("settings-done");
        done.setOnAction(e -> main.overlay.close());
        javafx.scene.control.ComboBox<String> language = new javafx.scene.control.ComboBox<>();
        language.setId("setting-language");
        String[] codes = {I18n.SYSTEM, "en", "tr"};
        language.getItems().addAll(I18n.t("settings.languageSystem"), "English", "Türkçe");
        language.getSelectionModel().select(java.util.Arrays.asList(codes).indexOf(I18n.choice()));
        Label restartNote = UiKit.label("", "caption");
        language.setOnAction(e -> {
            I18n.choose(codes[language.getSelectionModel().getSelectedIndex()]);
            restartNote.setText(I18n.t("settings.languageRestart"));
        });
        HBox languageRow = new HBox(10, UiKit.label(I18n.t("settings.language"), "field-label"), language, restartNote);
        languageRow.setAlignment(Pos.CENTER_LEFT);
        toggles.getChildren().add(0, languageRow);
        // Direct calls: who may see our address (and we theirs) for a better call
        ComboBox<String> direct = new ComboBox<>();
        direct.setId("setting-direct-calls");
        direct.getItems().addAll(I18n.t("settings.directVerified"), I18n.t("settings.directFriends"), I18n.t("settings.directOff"));
        direct.getSelectionModel().select(java.util.Arrays.asList(AppPrefs.DIRECT_CHOICES).indexOf(AppPrefs.directCalls()));
        direct.setOnAction(e -> {
            AppPrefs.setDirectCalls(AppPrefs.DIRECT_CHOICES[direct.getSelectionModel().getSelectedIndex()]);
            main.applyDirectCalls();
        });
        Label directHint = UiKit.label(I18n.t("settings.directHint"), "caption");
        directHint.setWrapText(true);
        directHint.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox directRow = new HBox(10, UiKit.label(I18n.t("settings.direct"), "field-label"), direct);
        directRow.setAlignment(Pos.CENTER_LEFT);
        toggles.getChildren().add(new VBox(4, directRow, directHint));
        Button about = new Button(I18n.t("about.title"));
        about.getStyleClass().add("link");
        about.setId("about");
        about.setOnAction(e -> openAbout());
        Button logs = new Button(I18n.t("settings.openLogs"));
        logs.getStyleClass().add("link");
        logs.setVisible(AppLog.folder() != null);
        logs.managedProperty().bind(logs.visibleProperty());
        logs.setOnAction(e -> MainView.openFolder(AppLog.folder()));
        toggles.getChildren().add(new HBox(14, about, logs));
        Button backup = new Button(I18n.t("backup.button"));
        backup.setId("backup");
        backup.setOnAction(e -> openBackup());
        Button looks = new Button(I18n.t("appearance.title"));
        looks.setId("appearance");
        looks.setOnAction(e -> openAppearance());
        VBox box = Overlay.dialog(I18n.t("main.settings"), toggles, new HBox(10, backup, looks, UiKit.grow(), done));
        box.setId("settings-dialog");
        main.overlay.show(box);
    }

    /** Theme, accent colour, text size, density, motion and the notification sound; applied at once. */
    void openAppearance() {
        ComboBox<String> theme = new ComboBox<>();
        theme.setId("appearance-theme");
        String[] themes = {"system", "light", "dark"};
        theme.getItems().addAll(I18n.t("appearance.system"), I18n.t("appearance.light"), I18n.t("appearance.dark"));
        theme.getSelectionModel().select(java.util.Arrays.asList(themes).indexOf(AppPrefs.theme()));
        theme.setOnAction(e -> {
            AppPrefs.setTheme(themes[theme.getSelectionModel().getSelectedIndex()]);
            Theme.refreshAll();
            main.sidebarUi.refreshSidebar();
        });
        HBox swatches = new HBox(10);
        swatches.setAlignment(Pos.CENTER_LEFT);
        Runnable[] drawSwatches = new Runnable[1];
        drawSwatches[0] = () -> {
            swatches.getChildren().clear();
            for (String name : Appearance.ACCENTS.keySet()) {
                String[][] palette = Appearance.ACCENTS.get(name);
                String hex = palette == null ? (Theme.isDark() ? "#f2a43a" : "#b45f0b") : palette[Theme.isDark() ? 1 : 0][0];
                javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(13, javafx.scene.paint.Color.web(hex));
                StackPane swatch = new StackPane(dot);
                swatch.getStyleClass().add("swatch");
                if (name.equals(AppPrefs.accent())) swatch.getStyleClass().add("selected");
                swatch.setId("accent-" + name);
                Tooltip.install(swatch, new Tooltip(accentName(name)));
                A11y.clickable(swatch, null);
                swatch.setOnMouseClicked(e -> {
                    AppPrefs.setAccent(name);
                    Theme.refreshAll();
                    drawSwatches[0].run();
                    main.sidebarUi.refreshSidebar();
                    if (main.current != null) main.current.refreshHeader();
                });
                swatches.getChildren().add(swatch);
            }
        };
        drawSwatches[0].run();
        ComboBox<String> size = new ComboBox<>();
        size.setId("appearance-size");
        String[] sizes = {"small", "normal", "large"};
        size.getItems().addAll(I18n.t("appearance.small"), I18n.t("appearance.normal"), I18n.t("appearance.large"));
        size.getSelectionModel().select(java.util.Arrays.asList(sizes).indexOf(AppPrefs.textSize()));
        size.setOnAction(e -> {
            AppPrefs.setTextSize(sizes[size.getSelectionModel().getSelectedIndex()]);
            Theme.refreshAll();
        });
        ComboBox<String> sound = new ComboBox<>();
        sound.setId("appearance-sound");
        for (String n : Sounds.NAMES) sound.getItems().add(soundName(n));
        sound.getSelectionModel().select(Math.max(0, Sounds.NAMES.indexOf(AppPrefs.sound())));
        sound.setOnAction(e -> {
            String chosen = Sounds.NAMES.get(sound.getSelectionModel().getSelectedIndex());
            AppPrefs.setSound(chosen);
            Sounds.play(chosen);
        });
        Button listen = new Button("▶");
        listen.getStyleClass().add("small");
        listen.setTooltip(new Tooltip(I18n.t("appearance.listen")));
        listen.setOnAction(e -> Sounds.play(AppPrefs.sound()));
        VBox rows = new VBox(14,
                field(I18n.t("appearance.theme"), theme),
                field(I18n.t("appearance.accent"), swatches),
                field(I18n.t("appearance.textSize"), size),
                appToggle(I18n.t("appearance.compact"), I18n.t("appearance.compactHint"), "appearance-compact",
                        AppPrefs.compact(), on -> {
                            AppPrefs.setCompact(on);
                            Theme.refreshAll();
                        }),
                appToggle(I18n.t("appearance.motion"), I18n.t("appearance.motionHint"), "appearance-motion",
                        AppPrefs.reduceMotion(), AppPrefs::setReduceMotion),
                field(I18n.t("appearance.sound"), new HBox(8, sound, listen)));
        Button done = new Button(I18n.t("common.done"));
        done.getStyleClass().add("primary");
        done.setId("appearance-done");
        done.setOnAction(e -> main.overlay.close());
        VBox box = Overlay.dialog(I18n.t("appearance.title"), rows, new HBox(UiKit.grow(), done));
        box.setId("appearance-dialog");
        main.overlay.show(box);
    }

    private static Node field(String label, Node control) {
        Label l = UiKit.label(label, "field-label");
        l.setMinWidth(120);
        HBox row = new HBox(12, l, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static String accentName(String name) {
        return switch (name) {
            case "venom" -> I18n.t("appearance.venom");
            case "sky" -> I18n.t("appearance.sky");
            case "rose" -> I18n.t("appearance.rose");
            case "violet" -> I18n.t("appearance.violet");
            default -> I18n.t("appearance.amber");
        };
    }

    private static String soundName(String name) {
        return switch (name) {
            case "tock" -> I18n.t("sound.tock");
            case "drop" -> I18n.t("sound.drop");
            case "crackle" -> I18n.t("sound.crackle");
            case "off" -> I18n.t("sound.off");
            default -> I18n.t("sound.chime");
        };
    }

    /** An encrypted backup of keys, sessions and history, protected by its own passphrase. */
    void openBackup() {
        javafx.scene.control.PasswordField pass = new javafx.scene.control.PasswordField();
        pass.setPromptText(I18n.t("backup.passPrompt", com.emirchat.client.Backup.MIN_PASSPHRASE));
        pass.setId("backup-pass");
        javafx.scene.control.PasswordField repeat = new javafx.scene.control.PasswordField();
        repeat.setPromptText(I18n.t("backup.repeat"));
        repeat.setId("backup-repeat");
        Label explain = UiKit.label(I18n.t("backup.explain"), "caption");
        explain.setWrapText(true);
        explain.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        Label status = UiKit.label("", "caption");
        status.setWrapText(true);
        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> main.overlay.close());
        Button save = new Button(I18n.t("backup.save"));
        save.getStyleClass().add("primary");
        save.setId("backup-save");
        save.disableProperty().bind(pass.textProperty().length().lessThan(com.emirchat.client.Backup.MIN_PASSPHRASE)
                .or(pass.textProperty().isNotEqualTo(repeat.textProperty())));
        save.setOnAction(e -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle(I18n.t("backup.saveTitle"));
            chooser.setInitialFileName(com.emirchat.client.Backup.suggestedName(main.session.client().username()));
            java.io.File target = chooser.showSaveDialog(main.stack.getScene().getWindow());
            if (target == null) return;
            char[] secret = pass.getText().toCharArray();
            status.setText(I18n.t("backup.encrypting"));
            save.disableProperty().unbind();
            save.setDisable(true);
            main.session.background().execute(() -> {
                String result;
                boolean failed = false;
                try {
                    main.session.backup(target.toPath(), secret);
                    result = I18n.t("backup.saved", target.getAbsolutePath());
                } catch (Exception ex) {
                    result = I18n.t("backup.failed", ex.getMessage());
                    failed = true;
                } finally {
                    java.util.Arrays.fill(secret, '\0');
                }
                String text = result;
                boolean danger = failed;
                Platform.runLater(() -> {
                    main.overlay.close();
                    if (!danger) {
                        AppPrefs.setFlag("backed-up", main.session.client().username());
                        main.firstSteps.refresh();
                    }
                    main.notice(text, danger);
                    if (!danger && main.current == null) main.showInfo(I18n.t("backup.title"), text);
                });
            });
        });
        VBox box = Overlay.dialog(I18n.t("backup.title"), explain, pass, repeat, status, new HBox(10, UiKit.grow(), cancel, save));
        box.setId("backup-dialog");
        main.overlay.show(box);
        Platform.runLater(pass::requestFocus);
    }

    /** A switch for an app-wide setting (not stored per account). */
    private Node appToggle(String title, String explanation, String id, boolean value, Consumer<Boolean> change) {
        CheckBox box = new CheckBox(title);
        box.setId(id);
        box.setSelected(value);
        box.selectedProperty().addListener((o, a, b) -> change.accept(b));
        Label hint = UiKit.label(explanation, "caption");
        hint.setWrapText(true);
        hint.setPadding(new Insets(0, 0, 0, 26));
        return new VBox(2, box, hint);
    }

    private Node toggle(String title, String explanation, String key) {
        CheckBox box = new CheckBox(title);
        box.setId("setting-" + key);
        box.setSelected(main.session.setting(key));
        box.selectedProperty().addListener((o, a, b) -> main.session.setSetting(key, b));
        Label hint = UiKit.label(explanation, "caption");
        hint.setWrapText(true);
        hint.setPadding(new Insets(0, 0, 0, 26));
        return new VBox(2, box, hint);
    }
}
