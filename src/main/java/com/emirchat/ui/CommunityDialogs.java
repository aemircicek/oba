package com.emirchat.ui;

import com.emirchat.api.Packet;
import com.emirchat.client.ChatClient;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The dialogs of communities: creating and joining one, new channels, invite codes, and the
 * community's own page with its members and their roles.
 */
final class CommunityDialogs {
    private final MainView main;

    CommunityDialogs(MainView main) {
        this.main = main;
    }

    void openNewCommunity() {
        TextField name = new TextField();
        name.setId("community-name");
        name.setPromptText(I18n.t("community.namePrompt"));
        Label explain = UiKit.label(I18n.t("community.newExplain"), "caption");
        explain.setWrapText(true);
        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> main.overlay.close());
        Button create = new Button(I18n.t("community.create"));
        create.getStyleClass().add("primary");
        create.setId("create-community");
        create.disableProperty().bind(name.textProperty().isEmpty());
        create.setOnAction(e -> main.call(() -> main.session.client().createCommunity(name.getText()), g -> {
            main.overlay.close();
            main.selectedCommunity = g.id();
            main.communitiesTab.setSelected(true);
            main.open(AccountSession.group(g.id()));
        }));
        main.overlay.show(Overlay.dialog(I18n.t("community.new"), explain, name, new HBox(10, UiKit.grow(), cancel, create)));
        Platform.runLater(name::requestFocus);
    }

    void openJoinCommunity() {
        TextField code = new TextField();
        code.setId("invite-code");
        code.setPromptText("XXXXX-XXXXX-XXXXX-XXXXX-XXXXX-X");
        Label explain = UiKit.label(I18n.t("community.joinExplain"), "caption");
        explain.setWrapText(true);
        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> main.overlay.close());
        Button join = new Button(I18n.t("community.joinButton"));
        join.getStyleClass().add("primary");
        join.setId("join-confirm");
        join.disableProperty().bind(code.textProperty().length().lessThan(10));
        join.setOnAction(e -> main.call(() -> main.session.client().joinCommunity(code.getText()), g -> {
            main.overlay.close();
            main.selectedCommunity = g.id();
            main.communitiesTab.setSelected(true);
            main.open(AccountSession.group(g.id()));
        }));
        main.overlay.show(Overlay.dialog(I18n.t("community.join"), explain, code, new HBox(10, UiKit.grow(), cancel, join)));
        Platform.runLater(code::requestFocus);
    }

    void openNewChannel(long communityId) {
        TextField name = new TextField();
        name.setId("channel-name");
        name.setPromptText(I18n.t("community.channelPrompt"));
        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> main.overlay.close());
        Button create = new Button(I18n.t("community.addChannel"));
        create.getStyleClass().add("primary");
        create.setId("create-channel");
        create.disableProperty().bind(name.textProperty().isEmpty());
        create.setOnAction(e -> main.call(() -> main.session.client().createChannel(communityId,
                name.getText().strip().replaceFirst("^#\\s*", "")), g -> {
            main.overlay.close();
            main.open(AccountSession.group(g.id()));
        }));
        main.overlay.show(Overlay.dialog(I18n.t("community.addChannel"), name, new HBox(10, UiKit.grow(), cancel, create)));
        Platform.runLater(name::requestFocus);
    }

    /** A fresh invite code, with how long and how often it works. */
    void openInvite(long communityId) {
        javafx.scene.control.ComboBox<String> valid = new javafx.scene.control.ComboBox<>();
        int[] hours = {24, 7 * 24, 30 * 24};
        valid.getItems().addAll(I18n.plural(1, "day"), I18n.plural(7, "day"), I18n.plural(30, "day"));
        valid.getSelectionModel().select(1);
        javafx.scene.control.ComboBox<String> uses = new javafx.scene.control.ComboBox<>();
        int[] limits = {1, 10, 0};
        uses.getItems().addAll(I18n.t("invite.once"), I18n.t("invite.times", 10), I18n.t("invite.unlimited"));
        uses.getSelectionModel().select(1);
        Label codeLabel = UiKit.label("", "invite-code");
        codeLabel.setId("invite-code-shown");
        Button copy = new Button(I18n.t("invite.copy"));
        copy.getStyleClass().add("small");
        copy.setDisable(true);
        copy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(codeLabel.getText());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            copy.setText(I18n.t("start.copied"));
        });
        Label note = UiKit.label(I18n.t("invite.note"), "caption");
        note.setWrapText(true);
        note.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        Button make = new Button(I18n.t("invite.make"));
        make.getStyleClass().add("primary");
        make.setId("invite-make");
        make.setOnAction(e -> main.call(() -> main.session.client().createInvite(communityId,
                limits[uses.getSelectionModel().getSelectedIndex()], hours[valid.getSelectionModel().getSelectedIndex()]), code -> {
            codeLabel.setText(code);
            copy.setDisable(false);
        }));
        Button revoke = new Button(I18n.t("invite.revoke"));
        revoke.getStyleClass().addAll("small", "danger-ghost");
        revoke.setOnAction(e -> main.call(() -> {
            main.session.client().revokeInvites(communityId);
            return null;
        }, r -> {
            codeLabel.setText("");
            copy.setDisable(true);
            note.setText(I18n.t("invite.revoked"));
        }));
        Button close = new Button(I18n.t("common.close"));
        close.setId("invite-close");
        close.setOnAction(e -> main.overlay.close());
        HBox options = new HBox(10, UiKit.label(I18n.t("invite.valid"), "field-label"), valid,
                UiKit.label(I18n.t("invite.uses"), "field-label"), uses);
        options.setAlignment(Pos.CENTER_LEFT);
        HBox codeRow = new HBox(10, codeLabel, UiKit.grow(), copy);
        codeRow.setAlignment(Pos.CENTER_LEFT);
        codeRow.getStyleClass().add("card-flat");
        VBox box = Overlay.dialog(I18n.t("invite.title", main.sidebarUi.communityName(communityId)), options, make, codeRow, note,
                new HBox(10, revoke, UiKit.grow(), close));
        box.setId("invite-dialog");
        main.overlay.show(box);
    }

    /** Members and roles, renaming and leaving. */
    void openCommunityInfo(long communityId) {
        ChatClient c = main.session.client();
        Packet.GroupInfo root = main.group(communityId);
        if (root == null) return;
        String me = c.username();
        boolean owner = root.owner().equals(me);
        boolean manager = root.canManage(me);
        VBox rows = new VBox(4);
        for (Packet.Member m : root.members()) {
            String who = m.username();
            boolean isOwner = who.equals(root.owner());
            boolean isAdmin = root.admins().contains(who);
            HBox right = new HBox(6);
            right.setAlignment(Pos.CENTER_RIGHT);
            right.getChildren().add(UiKit.label(isOwner ? I18n.t("community.owner") : isAdmin ? I18n.t("group.admin") : "", "caption"));
            if (owner && !isOwner) {
                Button role = new Button(isAdmin ? I18n.t("community.unmakeAdmin") : I18n.t("community.makeAdmin"));
                role.getStyleClass().add("small");
                role.setId("role-" + who);
                role.setOnAction(e -> main.call(() -> {
                    c.setAdmin(communityId, who, !isAdmin);
                    return null;
                }, r -> main.overlay.close()));
                right.getChildren().add(role);
            }
            if (manager && !who.equals(me) && !isOwner && (owner || !isAdmin)) {
                Button remove = new Button(I18n.t("group.remove"));
                remove.getStyleClass().addAll("small", "danger-ghost");
                remove.setId("remove-" + who);
                remove.setOnAction(e -> main.call(() -> {
                    c.removeGroupMember(communityId, who);
                    return null;
                }, r -> main.overlay.close()));
                right.getChildren().add(remove);
            }
            String label = who.equals(me) ? I18n.t("group.you", who) : who;
            HBox row = new HBox(10, UiKit.avatar(who, "avatar-xs", false), UiKit.label(label, "row-title"), UiKit.grow(), right);
            row.setAlignment(Pos.CENTER_LEFT);
            rows.getChildren().add(row);
        }
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(260, 34.0 * root.members().size()));
        Label about = UiKit.label(I18n.t("community.about", root.members().size()), "caption");
        about.setWrapText(true);
        about.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        VBox box = Overlay.dialog(ConversationView.groupName(root), about, scroll);
        if (manager) {
            TextField newName = new TextField(ConversationView.groupName(root));
            newName.setId("community-rename");
            HBox.setHgrow(newName, Priority.ALWAYS);
            Button rename = new Button(I18n.t("group.rename"));
            rename.getStyleClass().add("small");
            rename.disableProperty().bind(newName.textProperty().isEmpty());
            rename.setOnAction(e -> main.call(() -> {
                c.renameGroup(communityId, newName.getText());
                return null;
            }, r -> main.overlay.close()));
            box.getChildren().add(new HBox(8, newName, rename));
        }
        Button leave = new Button(I18n.t("community.leave"));
        leave.setId("leave-community");
        leave.getStyleClass().add("danger-ghost");
        leave.setOnAction(e -> main.call(() -> {
            c.leaveGroup(communityId);
            return null;
        }, r -> {
            main.overlay.close();
            main.selectedCommunity = 0;
            main.showEmpty();
            main.sidebarUi.refreshSidebar();
        }));
        Button close = new Button(I18n.t("common.close"));
        close.setOnAction(e -> main.overlay.close());
        box.getChildren().add(new HBox(10, leave, UiKit.grow(), close));
        box.setId("community-dialog");
        main.overlay.show(box);
    }
}
