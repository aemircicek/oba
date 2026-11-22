package com.emirchat.ui;

import com.emirchat.api.ApiException;
import com.emirchat.api.Packet;
import com.emirchat.client.ServerConnection;
import java.util.List;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Settings → Account security: two-step login (an authenticator app's code when a new computer
 * signs in, with recovery codes) and changing the password. Needs a 3.6 server.
 */
final class SecurityDialogs {
    private final MainView main;

    SecurityDialogs(MainView main) {
        this.main = main;
    }

    void open() {
        ServerConnection c = main.session.client().connection();
        Button close = new Button(I18n.t("common.close"));
        close.setId("security-close");
        close.setOnAction(e -> main.overlay.close());
        if (!c.hasAccountSecurity()) {
            Label old = wrap(I18n.t("security.oldServer"), "body");
            VBox box = Overlay.dialog(I18n.t("security.title"), old, new HBox(UiKit.grow(), close));
            box.setId("security-dialog");
            main.overlay.show(box);
            return;
        }
        VBox twoStep = new VBox(8, UiKit.label(I18n.t("security.twoStep"), "h3"), wrap(I18n.t("security.loading"), "caption"));
        VBox password = passwordSection();
        VBox computers = new VBox(8, UiKit.label(I18n.t("devices.title"), "h3"), wrap(I18n.t("security.loading"), "caption"));
        computers.setId("devices-section");
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(new VBox(14, twoStep,
                new javafx.scene.control.Separator(), computers, new javafx.scene.control.Separator(), password));
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("dialog-scroll");
        scroll.setPrefViewportHeight(460);
        scroll.setMaxHeight(500);
        VBox box = Overlay.dialog(I18n.t("security.title"), scroll, new HBox(UiKit.grow(), close));
        box.setId("security-dialog");
        box.setMaxWidth(480);
        main.overlay.show(box);
        load(twoStep);
        loadDevices(computers);
    }

    /** The account's computers, with "Remove" for the others. */
    private void loadDevices(VBox section) {
        main.session.background().execute(() -> {
            try {
                List<Packet.DeviceInfo> list = main.session.client().devices();
                Platform.runLater(() -> showDevices(section, list));
            } catch (Exception e) {
                Platform.runLater(() -> section.getChildren().setAll(UiKit.label(I18n.t("devices.title"), "h3"),
                        wrap(message(e), "caption")));
            }
        });
    }

    private void showDevices(VBox section, List<Packet.DeviceInfo> list) {
        section.getChildren().setAll(UiKit.label(I18n.t("devices.title"), "h3"), wrap(I18n.t("devices.hint"), "caption"));
        for (Packet.DeviceInfo d : list) {
            String name = d.name().isBlank() ? (d.id() == 0 ? I18n.t("devices.first") : I18n.t("devices.unnamed")) : d.name();
            Label title = UiKit.label(name + (d.current() ? " · " + I18n.t("devices.thisOne") : ""), "row-title");
            Label sub = UiKit.label(d.online() ? I18n.t("conv.online")
                    : d.lastSeen() > 0 ? I18n.t("devices.lastSeen", UiKit.time(d.lastSeen())) : "", "row-sub");
            VBox text = new VBox(1, title, sub);
            HBox row = new HBox(8, Icons.of(Icons.MONITOR, 16, d.current() ? "accent" : "muted"), text, UiKit.grow());
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("list-row");
            row.setId("device-" + d.id());
            if (!d.current()) {
                Button remove = new Button(I18n.t("devices.remove"));
                remove.getStyleClass().addAll("small", "danger-ghost");
                remove.setId("device-remove-" + d.id());
                remove.setOnAction(e -> {
                    remove.setDisable(true);
                    main.session.background().execute(() -> {
                        try {
                            main.session.client().removeDevice(d.id());
                            Platform.runLater(() -> loadDevices(section));
                        } catch (Exception ex) {
                            Platform.runLater(() -> {
                                remove.setDisable(false);
                                section.getChildren().add(wrap(message(ex), "caption"));
                            });
                        }
                    });
                });
                row.getChildren().add(remove);
            }
            section.getChildren().add(row);
        }
        if (list.isEmpty()) section.getChildren().add(wrap(I18n.t("devices.oldServer"), "caption"));
    }

    private void load(VBox twoStep) {
        main.session.background().execute(() -> {
            try {
                Packet.TwoFactorState state = main.session.client().connection().twoFactorState();
                Platform.runLater(() -> showState(twoStep, state));
            } catch (Exception e) {
                Platform.runLater(() -> twoStep.getChildren().setAll(UiKit.label(I18n.t("security.twoStep"), "h3"),
                        wrap(message(e), "caption")));
            }
        });
    }

    private void showState(VBox twoStep, Packet.TwoFactorState state) {
        Label head = UiKit.label(I18n.t("security.twoStep"), "h3");
        if (!state.enabled()) {
            Button on = new Button(I18n.t("security.turnOn"));
            on.getStyleClass().add("primary");
            on.setId("two-step-on");
            on.setOnAction(e -> setUp(twoStep));
            twoStep.getChildren().setAll(head, wrap(I18n.t("security.offHint"), "body"), on);
            return;
        }
        PasswordField pw = new PasswordField();
        pw.setPromptText(I18n.t("security.currentPassword"));
        pw.setId("two-step-off-password");
        Button off = new Button(I18n.t("security.turnOff"));
        off.getStyleClass().add("danger-ghost");
        off.setId("two-step-off");
        Label problem = wrap("", "caption");
        off.setOnAction(e -> {
            off.setDisable(true);
            String given = pw.getText();
            main.session.background().execute(() -> {
                try {
                    main.session.client().connection().disableTwoFactor(given);
                    Platform.runLater(() -> load(twoStep));
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        off.setDisable(false);
                        problem.setText(message(ex));
                    });
                }
            });
        });
        Label on = UiKit.label("✓ " + I18n.t("security.isOn", state.recoveryLeft()), "badge-verified");
        on.setId("two-step-state");
        twoStep.getChildren().setAll(head, on, wrap(I18n.t("security.onHint"), "caption"), new HBox(8, pw, off), problem);
    }

    /** Step 1: the secret as a QR code for the app; step 2: its first code turns it on. */
    private void setUp(VBox twoStep) {
        main.session.background().execute(() -> {
            try {
                byte[] secret = main.session.client().connection().setUpTwoFactor();
                Platform.runLater(() -> showSecret(twoStep, secret));
            } catch (Exception e) {
                Platform.runLater(() -> twoStep.getChildren().add(wrap(message(e), "caption")));
            }
        });
    }

    private void showSecret(VBox twoStep, byte[] secret) {
        String key = base32(secret);
        String user = main.session.client().username();
        String uri = "otpauth://totp/Oba:" + user + "?secret=" + key + "&issuer=Oba&algorithm=SHA1&digits=6&period=30";
        ImageView qr = new ImageView(QrCodes.render(uri, 180));
        qr.setId("two-step-qr");
        qr.setAccessibleText(I18n.t("security.qrTip"));
        Label text = UiKit.label(key.replaceAll("(.{4})", "$1 ").strip(), "fingerprint");
        text.setId("two-step-secret");
        text.setWrapText(true);
        TextField code = new TextField();
        code.setPromptText("123 456");
        code.setId("two-step-code");
        code.setPrefColumnCount(8);
        Button confirm = new Button(I18n.t("security.confirm"));
        confirm.getStyleClass().add("primary");
        confirm.setId("two-step-confirm");
        Label problem = wrap("", "caption");
        Runnable go = () -> {
            confirm.setDisable(true);
            String given = code.getText().strip();
            main.session.background().execute(() -> {
                try {
                    List<String> recovery = main.session.client().connection().enableTwoFactor(given);
                    Platform.runLater(() -> showRecovery(twoStep, recovery));
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        confirm.setDisable(false);
                        problem.setText(message(e));
                    });
                }
            });
        };
        confirm.setOnAction(e -> go.run());
        code.setOnAction(e -> go.run());
        confirm.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        VBox right = new VBox(8, wrap(I18n.t("security.scan"), "body"), text, code, confirm, problem);
        HBox.setHgrow(right, javafx.scene.layout.Priority.ALWAYS);
        right.setMinWidth(0);
        HBox row = new HBox(14, qr, right);
        row.setAlignment(Pos.TOP_LEFT);
        twoStep.getChildren().setAll(UiKit.label(I18n.t("security.twoStep"), "h3"), row);
        Platform.runLater(code::requestFocus);
    }

    private void showRecovery(VBox twoStep, List<String> codes) {
        Label list = UiKit.label(String.join("\n", codes), "fingerprint");
        list.setId("recovery-codes");
        Button copy = new Button(I18n.t("security.copyCodes"));
        copy.setId("recovery-copy");
        copy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(String.join("\n", codes));
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            copy.setText("✓ " + I18n.t("security.copied"));
        });
        Button done = new Button(I18n.t("security.saved"));
        done.getStyleClass().add("primary");
        done.setId("recovery-done");
        done.setOnAction(e -> load(twoStep));
        twoStep.getChildren().setAll(UiKit.label(I18n.t("security.twoStep"), "h3"),
                wrap(I18n.t("security.recoveryHint"), "body"), list, new HBox(8, copy, UiKit.grow(), done));
    }

    private VBox passwordSection() {
        PasswordField old = field("security.currentPassword", "password-old");
        PasswordField fresh = field("security.newPassword", "password-new");
        PasswordField again = field("security.repeatPassword", "password-again");
        Button change = new Button(I18n.t("security.change"));
        change.setId("password-change");
        Label problem = wrap("", "caption");
        problem.setId("password-problem");
        change.setOnAction(e -> {
            if (!fresh.getText().equals(again.getText())) {
                problem.setText(I18n.t("login.mismatch"));
                return;
            }
            change.setDisable(true);
            String o = old.getText();
            String n = fresh.getText();
            problem.setText(I18n.t("security.changing"));
            main.session.background().execute(() -> {
                try {
                    main.session.changePassword(o, n);
                    Platform.runLater(() -> {
                        old.clear();
                        fresh.clear();
                        again.clear();
                        change.setDisable(false);
                        problem.setText("✓ " + I18n.t("security.changed"));
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        change.setDisable(false);
                        problem.setText(message(ex));
                    });
                }
            });
        });
        return new VBox(8, UiKit.label(I18n.t("security.password"), "h3"), wrap(I18n.t("security.passwordHint"), "caption"),
                old, fresh, again, new HBox(8, change, problem));
    }

    private static PasswordField field(String prompt, String id) {
        PasswordField f = new PasswordField();
        f.setPromptText(I18n.t(prompt));
        f.setId(id);
        return f;
    }

    private static Label wrap(String text, String style) {
        Label l = UiKit.label(text, style);
        l.setWrapText(true);
        l.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        return l;
    }

    private static String message(Exception e) {
        return e instanceof ApiException a ? I18n.error(a) : e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /** RFC 4648 base32 without padding, as authenticator apps take secrets. */
    static String base32(byte[] data) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(alphabet.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) out.append(alphabet.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }
}
