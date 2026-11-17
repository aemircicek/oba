package com.emirchat.ui;

import com.emirchat.api.Packet;
import com.emirchat.client.ChatClient;
import com.emirchat.crypto.Identity;
import com.emirchat.trust.SafetyNumber;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The dialogs about people and groups: a contact's page with the safety number to compare, your
 * own key, creating a group, and a group's page with its members.
 */
final class ContactDialogs {
    private final MainView main;

    ContactDialogs(MainView main) {
        this.main = main;
    }

    void openContact(String user) {
        ChatClient c = main.session.client();
        String current = c.currentFingerprint(user);
        String pinned = c.pinnedFingerprint(user);
        boolean changed = c.keyChanged(user);
        VBox mine = new VBox(8, UiKit.label(I18n.t("contact.yourKey", user.toUpperCase(I18n.locale())), "overline"),
                FingerprintView.compact(Identity.fingerprint(myKey())));
        mine.getStyleClass().add("card-flat");
        Label explain = UiKit.label(changed
                ? I18n.t("contact.changedExplain", user)
                : I18n.t("contact.explain", user),
                "body");
        explain.setWrapText(true);
        VBox box = Overlay.dialog(changed ? I18n.t("contact.changedTitle", user) : I18n.t("contact.title", user), explain);
        if (changed && pinned != null) {
            box.getChildren().add(new VBox(6, UiKit.label(I18n.t("keyChanged.before"), "overline"), FingerprintView.compact(pinned)));
        }
        if (current != null) {
            VBox theirs = new VBox(8, UiKit.label(changed ? I18n.t("contact.newKey") : I18n.t("chat.theirKey", user.toUpperCase(I18n.locale())), "overline"),
                    FingerprintView.compact(current));
            theirs.getStyleClass().add("card-flat");
            box.getChildren().add(theirs);
        }
        box.getChildren().add(mine);
        Button close = new Button(I18n.t("common.close"));
        close.setOnAction(e -> main.overlay.close());
        Button confirm = new Button(changed ? I18n.t("contact.trustNew") : I18n.t("contact.markVerified"));
        confirm.setId("confirm-verify");
        confirm.getStyleClass().add("primary");
        confirm.setOnAction(e -> main.call(() -> {
            if (changed) c.trustNewKey(user);
            c.markVerified(user);
            return null;
        }, r -> {
            main.overlay.close();
            if (main.current != null) main.current.refreshHeader();
            main.sidebarUi.refreshSidebar();
            main.firstSteps.refresh();
        }));
        if (current != null) box.getChildren().add(safetyNumber(user, current, confirm));
        HBox buttons = new HBox(10, UiKit.grow(), close, confirm);
        box.getChildren().add(buttons);
        box.setId("contact-dialog");
        main.overlay.show(box);
    }

    /**
     * The safety number and QR code both sides compute from both keys. Checking a picture of the
     * friend's QR code verifies them in one step; a mismatch is shown loudly.
     */
    private Node safetyNumber(String user, String theirFingerprint, Button confirm) {
        ChatClient c = main.session.client();
        SafetyNumber number = SafetyNumber.of(c.username(), Identity.fingerprint(myKey()), user, theirFingerprint);
        javafx.scene.image.ImageView qr = new javafx.scene.image.ImageView(QrCodes.render(number.qrPayload(), 300));
        qr.setFitWidth(132);
        qr.setPreserveRatio(true);
        qr.setId("safety-qr");
        Label digits = UiKit.label(number.digits(), "safety-digits");
        digits.setId("safety-digits");
        Label result = UiKit.label("", "caption");
        result.setWrapText(true);
        Button check = new Button(I18n.t("safety.check"));
        check.setId("check-qr");
        check.getStyleClass().add("small");
        check.setOnAction(e -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle(I18n.t("safety.pick", user));
            chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(I18n.t("safety.images"), "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"));
            java.io.File file = chooser.showOpenDialog(main.stack.getScene().getWindow());
            if (file == null) return;
            String scanned = QrCodes.read(new javafx.scene.image.Image(file.toURI().toString()));
            result.getStyleClass().removeAll("danger-text", "accent-text");
            if (scanned == null) {
                result.setText(I18n.t("safety.noCode"));
                result.getStyleClass().add("danger-text");
            } else if (number.matches(scanned)) {
                result.setText("✓ " + I18n.t("safety.match", user));
                result.getStyleClass().add("accent-text");
                confirm.fire();
            } else {
                result.setText("✗ " + I18n.t("safety.mismatch"));
                result.getStyleClass().add("danger-text");
            }
        });
        Label how = UiKit.label(I18n.t("safety.how"), "caption");
        how.setWrapText(true);
        how.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        result.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        result.setMaxWidth(260);
        VBox right = new VBox(8, digits, check, result);
        HBox row = new HBox(16, qr, right);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(8, UiKit.label(I18n.t("safety.title"), "overline"), row, how);
        card.getStyleClass().add("card-flat");
        return card;
    }

    private byte[] myKey() {
        return main.session.client().deviceKey();
    }

    void openMyKey() {
        VBox card = new VBox(14, FingerprintView.large(Identity.fingerprint(myKey())));
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("card");
        Label text = UiKit.label(I18n.t("myKey.explain"), "body");
        text.setWrapText(true);
        Button ok = new Button(I18n.t("common.done"));
        ok.getStyleClass().add("primary");
        ok.setOnAction(e -> main.overlay.close());
        main.overlay.show(Overlay.dialog(I18n.t("main.myKey"), text, card, new HBox(UiKit.grow(), ok)));
    }

    void openNewGroup() {
        TextField name = new TextField();
        name.setPromptText(I18n.t("group.name"));
        name.setId("group-name");
        VBox members = new VBox(8);
        List<CheckBox> boxes = new ArrayList<>();
        for (Packet.Friend f : main.session.client().friends().friends()) {
            CheckBox cb = new CheckBox(f.username());
            cb.setId("pick-" + f.username());
            boxes.add(cb);
            members.getChildren().add(cb);
        }
        if (boxes.isEmpty()) members.getChildren().add(UiKit.label(I18n.t("group.addFriendsFirst"), "caption"));
        ScrollPane scroll = new ScrollPane(members);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(220, 28.0 * Math.max(1, boxes.size())));
        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> main.overlay.close());
        Button create = new Button(I18n.t("group.create"));
        create.setId("create-group");
        create.getStyleClass().add("primary");
        create.disableProperty().bind(name.textProperty().isEmpty());
        create.setOnAction(e -> {
            List<String> picked = boxes.stream().filter(CheckBox::isSelected).map(CheckBox::getText).toList();
            main.call(() -> main.session.client().createGroup(name.getText().strip(), picked), g -> {
                main.overlay.close();
                main.open(AccountSession.group(g.id()));
            });
        });
        main.overlay.show(Overlay.dialog(I18n.t("conv.newGroup"), new VBox(6, UiKit.label(I18n.t("group.nameLabel"), "field-label"), name),
                new VBox(6, UiKit.label(I18n.t("group.members"), "field-label"), scroll), new HBox(10, UiKit.grow(), cancel, create)));
        Platform.runLater(name::requestFocus);
    }

    void openGroupInfo(long groupId) {
        ChatClient c = main.session.client();
        Packet.GroupInfo g = c.groups().stream().filter(x -> x.id() == groupId).findFirst().orElse(null);
        if (g == null) return;
        if (g.community() != 0) {
            main.communities.openCommunityInfo(g.community());
            return;
        }
        boolean admin = g.owner().equals(c.username());
        VBox rows = new VBox(4);
        for (Packet.Member m : g.members()) {
            HBox right = new HBox(6);
            right.setAlignment(Pos.CENTER_RIGHT);
            if (m.username().equals(g.owner())) right.getChildren().add(UiKit.label(I18n.t("group.admin"), "caption"));
            if (admin && !m.username().equals(c.username())) {
                Button remove = new Button(I18n.t("group.remove"));
                remove.setId("remove-" + m.username());
                remove.getStyleClass().addAll("small", "danger-ghost");
                remove.setOnAction(e -> main.call(() -> {
                    c.removeGroupMember(groupId, m.username());
                    return null;
                }, r -> main.overlay.close()));
                right.getChildren().add(remove);
            }
            String who = m.username().equals(c.username()) ? I18n.t("group.you", m.username()) : m.username();
            HBox row = new HBox(10, UiKit.avatar(m.username(), "avatar-xs", false), UiKit.label(who, "row-title"), UiKit.grow(), right);
            row.setAlignment(Pos.CENTER_LEFT);
            rows.getChildren().add(row);
        }
        Label about = UiKit.label(I18n.t("group.about", g.members().size()), "caption");
        about.setWrapText(true);
        VBox box = Overlay.dialog(ConversationView.groupName(g), about, rows);
        if (admin) {
            ComboBox<String> candidates = new ComboBox<>();
            c.friends().friends().stream().map(Packet.Friend::username).filter(u -> !g.hasMember(u)).forEach(candidates.getItems()::add);
            candidates.setPromptText(candidates.getItems().isEmpty() ? I18n.t("group.allHere") : I18n.t("group.addFriend"));
            candidates.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(candidates, Priority.ALWAYS);
            Button add = new Button(I18n.t("main.add"));
            add.getStyleClass().addAll("small", "primary");
            add.disableProperty().bind(candidates.valueProperty().isNull());
            add.setOnAction(e -> main.call(() -> {
                c.addGroupMember(groupId, candidates.getValue());
                return null;
            }, r -> main.overlay.close()));
            box.getChildren().add(new HBox(8, candidates, add));
            TextField newName = new TextField(g.name().equals(ChatClient.ENCRYPTED_GROUP_NAME) ? "" : g.name());
            newName.setId("group-name");
            newName.setPromptText(I18n.t("group.name"));
            HBox.setHgrow(newName, Priority.ALWAYS);
            Button rename = new Button(I18n.t("group.rename"));
            rename.setId("rename-group");
            rename.getStyleClass().add("small");
            rename.disableProperty().bind(newName.textProperty().isEmpty());
            rename.setOnAction(e -> main.call(() -> {
                c.renameGroup(groupId, newName.getText());
                return null;
            }, r -> main.overlay.close()));
            box.getChildren().add(new HBox(8, newName, rename));
        }
        Button leave = new Button(I18n.t("group.leave"));
        leave.setId("leave-group");
        leave.getStyleClass().add("danger-ghost");
        leave.setOnAction(e -> main.call(() -> {
            c.leaveGroup(groupId);
            return null;
        }, r -> {
            main.overlay.close();
            main.showEmpty();
            main.sidebarUi.refreshSidebar();
        }));
        Button close = new Button(I18n.t("common.close"));
        close.setOnAction(e -> main.overlay.close());
        box.getChildren().add(new HBox(10, leave, UiKit.grow(), close));
        box.setId("group-dialog");
        main.overlay.show(box);
    }
}
