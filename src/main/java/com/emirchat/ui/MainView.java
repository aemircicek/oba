package com.emirchat.ui;

import com.emirchat.api.ApiException;
import com.emirchat.api.Packet;
import com.emirchat.client.ChatClient;
import com.emirchat.client.History;
import com.emirchat.crypto.Identity;
import com.emirchat.trust.SafetyNumber;
import com.emirchat.trust.KeyChangedException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** The logged-in window: sidebar with chats and friends, the open conversation, and dialogs. */
final class MainView implements AccountSession.View {
    private final AccountSession session;
    private final Runnable onLogout;
    private final StackPane stack = new StackPane();
    private final BorderPane frame = new BorderPane();
    private final Overlay overlay = new Overlay(stack);
    private final VBox list = new VBox();
    private final StackPane content = new StackPane();
    private final ToggleButton chatsTab = new ToggleButton("Chats");
    private final ToggleButton friendsTab = new ToggleButton("Friends");
    private final Label friendsBadge = UiKit.label("", "unread-badge");
    private ConversationView current;
    private final TextField search = new TextField();
    private final Notifier notifier = new Notifier();
    private boolean focusHooked;

    MainView(AccountSession session, Runnable onLogout) {
        this.session = session;
        this.onLogout = onLogout;
        ChatClient client = session.client();

        // Sidebar header
        Button logout = new Button();
        logout.setGraphic(Icons.of(Icons.LOG_OUT, 16, null));
        logout.getStyleClass().add("icon-button");
        logout.setTooltip(new Tooltip("Log out"));
        logout.setId("logout");
        logout.setOnAction(e -> onLogout.run());
        Button me = new Button();
        me.setGraphic(UiKit.avatar(client.username(), "avatar-xs", false));
        me.getStyleClass().add("icon-button");
        me.setTooltip(new Tooltip("Your security fingerprint"));
        me.setOnAction(e -> openMyKey());
        VBox meText = new VBox(0, UiKit.label(client.username(), "me-name"), UiKit.label("● connected", "row-sub", "accent-text"));
        Button settings = new Button();
        settings.setGraphic(Icons.of(Icons.SETTINGS, 16, null));
        settings.getStyleClass().add("icon-button");
        settings.setTooltip(new Tooltip("Settings"));
        settings.setId("settings");
        settings.setOnAction(e -> openSettings());
        HBox header = new HBox(me, meText, UiKit.grow(), settings, logout);
        header.getStyleClass().add("sidebar-header");
        header.setAlignment(Pos.CENTER_LEFT);

        // Tabs
        ToggleGroup tabs = new ToggleGroup();
        for (ToggleButton t : new ToggleButton[] {chatsTab, friendsTab}) {
            t.setToggleGroup(tabs);
            t.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(t, Priority.ALWAYS);
        }
        chatsTab.setId("tab-chats");
        friendsTab.setId("tab-friends");
        friendsBadge.managedProperty().bind(friendsBadge.visibleProperty());
        friendsTab.setGraphic(friendsBadge);
        friendsTab.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        tabs.selectedToggleProperty().addListener((o, old, now) -> {
            if (now == null) old.setSelected(true);
            refreshSidebar();
        });
        chatsTab.setSelected(true);
        HBox segmented = new HBox(chatsTab, friendsTab);
        segmented.getStyleClass().add("segmented");
        search.setId("search");
        search.setPromptText("Search messages");
        search.getStyleClass().add("search");
        search.textProperty().addListener((o, a, b) -> refreshSidebar());
        search.visibleProperty().bind(chatsTab.selectedProperty());
        search.managedProperty().bind(search.visibleProperty());
        VBox tabBox = new VBox(10, segmented, search);
        tabBox.getStyleClass().add("sidebar-section");

        ScrollPane listScroll = new ScrollPane(list);
        listScroll.setFitToWidth(true);
        listScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(listScroll, Priority.ALWAYS);
        VBox sidebar = new VBox(header, tabBox, listScroll);
        sidebar.getStyleClass().add("sidebar");

        showEmpty();
        frame.setLeft(sidebar);
        frame.setCenter(content);
        stack.getChildren().add(frame);
        refreshSidebar();
        session.setView(this);
    }

    Parent root() {
        return stack;
    }

    // ------------------------------------------------------------------ sidebar

    private void refreshSidebar() {
        Packet.FriendList friends = session.client().friends();
        int requests = friends.incoming().size();
        friendsBadge.setText(String.valueOf(requests));
        friendsBadge.setVisible(requests > 0);
        list.getChildren().clear();
        if (!chatsTab.isSelected()) buildFriends(friends);
        else if (!search.getText().isBlank()) buildSearch(search.getText());
        else buildChats();
    }

    /** Search results across all conversations (decrypted locally; nothing is sent anywhere). */
    private void buildSearch(String query) {
        list.getChildren().add(heading("RESULTS"));
        List<History.Message> found;
        try {
            found = session.history().search(query, 50);
        } catch (IOException e) {
            found = List.of();
        }
        if (found.isEmpty()) {
            Label none = UiKit.label("No messages contain \"" + query.strip() + "\".", "caption");
            none.setPadding(new Insets(6, 14, 0, 14));
            list.getChildren().add(none);
        }
        for (History.Message m : found) {
            String where = m.conversation().startsWith("g:") ? groupName(Long.parseLong(m.conversation().substring(2)))
                    : m.conversation().substring(3);
            Label sub = UiKit.label((m.outgoing() ? "You: " : m.conversation().startsWith("g:") ? m.sender() + ": " : "")
                    + (m.file() != null ? "📎 " : "") + m.text(), "row-sub");
            VBox text = new VBox(2, UiKit.label(where, "row-title"), sub);
            text.setMinWidth(0);
            HBox row = new HBox(10, text, UiKit.grow(), UiKit.label(UiKit.time(m.sentAt()), "row-time"));
            row.getStyleClass().add("list-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setId("result-" + m.id());
            row.setOnMouseClicked(e -> {
                open(m.conversation());
                if (current != null) current.reveal(m.id());
            });
            list.getChildren().add(row);
        }
    }

    private String groupName(long id) {
        return session.client().groups().stream().filter(g -> g.id() == id).map(Packet.GroupInfo::name).findFirst()
                .orElse("Former group");
    }

    // ------------------------------------------------------------------ settings

    private void openSettings() {
        VBox toggles = new VBox(10,
                toggle("Send read receipts", "Friends see ✓✓ in colour once you have read their messages.",
                        AccountSession.SEND_READ_RECEIPTS),
                toggle("Send typing indicators", "Friends who are online see when you are typing to them.",
                        AccountSession.SEND_TYPING),
                toggle("Desktop notifications", "Show a notification when a message arrives while the window is in the background.",
                        AccountSession.NOTIFICATIONS),
                toggle("Show message text in notifications", "Off: notifications only say who wrote, not what.",
                        AccountSession.NOTIFICATION_PREVIEW));
        Button done = new Button("Done");
        done.getStyleClass().add("primary");
        done.setId("settings-done");
        done.setOnAction(e -> overlay.close());
        Button backup = new Button("Back up this account…");
        backup.setId("backup");
        backup.setOnAction(e -> openBackup());
        VBox box = Overlay.dialog("Settings", toggles, new HBox(10, backup, UiKit.grow(), done));
        box.setId("settings-dialog");
        overlay.show(box);
    }

    /** An encrypted backup of keys, sessions and history, protected by its own passphrase. */
    private void openBackup() {
        javafx.scene.control.PasswordField pass = new javafx.scene.control.PasswordField();
        pass.setPromptText("Backup passphrase (at least " + com.emirchat.client.Backup.MIN_PASSPHRASE + " characters)");
        pass.setId("backup-pass");
        javafx.scene.control.PasswordField repeat = new javafx.scene.control.PasswordField();
        repeat.setPromptText("Repeat the passphrase");
        repeat.setId("backup-repeat");
        Label explain = UiKit.label("The backup holds your identity key, contacts, encryption sessions and message "
                + "history, encrypted with this passphrase. Keep it somewhere safe; without the passphrase it is useless. "
                + "Restoring it on another computer moves your account there, so stop using this one afterwards.", "caption");
        explain.setWrapText(true);
        explain.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        Label status = UiKit.label("", "caption");
        status.setWrapText(true);
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> overlay.close());
        Button save = new Button("Save backup…");
        save.getStyleClass().add("primary");
        save.setId("backup-save");
        save.disableProperty().bind(pass.textProperty().length().lessThan(com.emirchat.client.Backup.MIN_PASSPHRASE)
                .or(pass.textProperty().isNotEqualTo(repeat.textProperty())));
        save.setOnAction(e -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle("Save backup");
            chooser.setInitialFileName(com.emirchat.client.Backup.suggestedName(session.client().username()));
            java.io.File target = chooser.showSaveDialog(stack.getScene().getWindow());
            if (target == null) return;
            char[] secret = pass.getText().toCharArray();
            status.setText("Encrypting… (this takes a few seconds)");
            save.disableProperty().unbind();
            save.setDisable(true);
            session.background().execute(() -> {
                String result;
                try {
                    session.backup(target.toPath(), secret);
                    result = "Backup saved to " + target.getAbsolutePath() + ".";
                } catch (Exception ex) {
                    result = "Backup failed: " + ex.getMessage();
                } finally {
                    java.util.Arrays.fill(secret, '\0');
                }
                String text = result;
                Platform.runLater(() -> {
                    overlay.close();
                    problem(text);
                });
            });
        });
        VBox box = Overlay.dialog("Back up this account", explain, pass, repeat, status, new HBox(10, UiKit.grow(), cancel, save));
        box.setId("backup-dialog");
        overlay.show(box);
        Platform.runLater(pass::requestFocus);
    }

    private Node toggle(String title, String explanation, String key) {
        CheckBox box = new CheckBox(title);
        box.setId("setting-" + key);
        box.setSelected(session.setting(key));
        box.selectedProperty().addListener((o, a, b) -> session.setSetting(key, b));
        Label hint = UiKit.label(explanation, "caption");
        hint.setWrapText(true);
        hint.setPadding(new Insets(0, 0, 0, 26));
        return new VBox(2, box, hint);
    }

    private void buildChats() {
        ChatClient client = session.client();
        Button newGroup = new Button("New group");
        newGroup.setId("new-group");
        newGroup.getStyleClass().add("small");
        newGroup.setOnAction(e -> openNewGroup());
        HBox heading = new HBox(UiKit.label("CONVERSATIONS", "overline"), UiKit.grow(), newGroup);
        heading.getStyleClass().add("list-heading");
        heading.setAlignment(Pos.CENTER_LEFT);
        list.getChildren().add(heading);

        Map<String, History.Summary> summaries = new LinkedHashMap<>();
        try {
            for (History.Summary s : session.history().summaries()) summaries.put(s.conversation(), s);
        } catch (IOException ignored) {
            // show what we can
        }
        Set<String> shown = new HashSet<>();
        for (History.Summary s : summaries.values()) {
            Node row = chatRow(s.conversation(), s);
            if (row != null) {
                list.getChildren().add(row);
                shown.add(s.conversation());
            }
        }
        for (Packet.GroupInfo g : client.groups()) {
            String key = AccountSession.group(g.id());
            if (!shown.contains(key)) list.getChildren().add(chatRow(key, null));
        }
        if (list.getChildren().size() == 1) {
            Label empty = UiKit.label("No conversations yet. Add friends in the Friends tab, then say hello.", "caption");
            empty.setWrapText(true);
            empty.setPadding(new Insets(10, 14, 0, 14));
            list.getChildren().add(empty);
        }
    }

    private Node chatRow(String conversation, History.Summary s) {
        ChatClient client = session.client();
        boolean group = conversation.startsWith("g:");
        String title;
        Node avatar;
        if (group) {
            long id = Long.parseLong(conversation.substring(2));
            Packet.GroupInfo g = client.groups().stream().filter(x -> x.id() == id).findFirst().orElse(null);
            if (g == null) return null; // a group we left: keep history, hide from the list
            title = g.name();
            avatar = UiKit.avatar(g.name(), "avatar-sm", true);
        } else {
            title = conversation.substring(3);
            avatar = UiKit.avatarWithPresence(title, client.isOnline(title));
        }
        String preview = s == null ? "No messages yet"
                : (s.lastSender().equals(client.username()) ? "You: " : group ? s.lastSender() + ": " : "") + s.lastText();
        Label sub = UiKit.label(preview, "row-sub");
        VBox text = new VBox(2, UiKit.label(title, "row-title"), sub);
        text.setMinWidth(0); // the preview shrinks (with an ellipsis), never the time or badge
        VBox right = new VBox(4);
        right.setAlignment(Pos.TOP_RIGHT);
        right.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        if (s != null) right.getChildren().add(UiKit.label(UiKit.time(s.lastAt()), "row-time"));
        boolean open = current != null && current.conversation().equals(conversation);
        if (s != null && s.unread() > 0 && !open) right.getChildren().add(UiKit.label(String.valueOf(s.unread()), "unread-badge"));
        if (avatar instanceof javafx.scene.layout.Region r) r.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox row = new HBox(avatar, text, UiKit.grow(), right);
        row.getStyleClass().add("list-row");
        if (open) row.getStyleClass().add("selected");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setId("chat-" + conversation.replace(':', '-'));
        row.setOnMouseClicked(e -> open(conversation));
        return row;
    }

    private void buildFriends(Packet.FriendList friends) {
        TextField add = new TextField();
        add.setPromptText("Add a friend by username");
        add.setId("add-friend");
        HBox.setHgrow(add, Priority.ALWAYS);
        Button send = new Button("Add");
        send.setId("add-friend-send");
        send.getStyleClass().addAll("small", "primary");
        send.disableProperty().bind(add.textProperty().isEmpty());
        Runnable doAdd = () -> {
            String name = add.getText().strip().toLowerCase(java.util.Locale.ROOT);
            add.clear();
            call(() -> session.client().connection().sendFriendRequest(name),
                    nowFriends -> problem(nowFriends ? "You and " + name + " are now friends." : "Request sent to " + name + "."));
        };
        add.setOnAction(e -> doAdd.run());
        send.setOnAction(e -> doAdd.run());
        HBox addRow = new HBox(8, add, send);
        addRow.setAlignment(Pos.CENTER);
        addRow.setPadding(new Insets(4, 14, 8, 14));
        list.getChildren().add(addRow);

        if (!friends.incoming().isEmpty()) {
            list.getChildren().add(heading("REQUESTS"));
            for (String from : friends.incoming()) {
                Button accept = new Button("Accept");
                accept.setId("accept-" + from);
                accept.getStyleClass().addAll("small", "primary");
                accept.setOnAction(e -> call(() -> {
                    session.client().connection().respondFriendRequest(from, true);
                    return null;
                }, r -> session.refreshFriendsLater()));
                Button decline = new Button("Decline");
                decline.getStyleClass().add("small");
                decline.setOnAction(e -> call(() -> {
                    session.client().connection().respondFriendRequest(from, false);
                    return null;
                }, r -> session.refreshFriendsLater()));
                decline.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
                accept.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
                list.getChildren().add(row(UiKit.avatar(from, "avatar-sm", false),
                        new VBox(2, UiKit.label(from, "row-title"), UiKit.label("friend request", "row-sub")),
                        new HBox(6, decline, accept)));
            }
        }
        if (!friends.outgoing().isEmpty()) {
            list.getChildren().add(heading("SENT"));
            for (String to : friends.outgoing()) {
                Button cancel = new Button("Cancel");
                cancel.getStyleClass().add("small");
                cancel.setOnAction(e -> call(() -> {
                    session.client().connection().removeFriend(to);
                    return null;
                }, r -> session.refreshFriendsLater()));
                list.getChildren().add(row(UiKit.avatar(to, "avatar-sm", false),
                        new VBox(2, UiKit.label(to, "row-title"), UiKit.label("waiting for an answer", "row-sub")), cancel));
            }
        }
        list.getChildren().add(heading("FRIENDS · " + friends.friends().size()));
        if (friends.friends().isEmpty()) {
            Label none = UiKit.label("Nobody yet. Ask your friends for their username.", "caption");
            none.setPadding(new Insets(4, 14, 0, 14));
            list.getChildren().add(none);
        }
        for (Packet.Friend f : friends.friends()) {
            ChatClient c = session.client();
            String state = c.keyChanged(f.username()) ? "⚠ key changed" : c.isVerified(f.username()) ? "✓ verified" : f.online() ? "online" : "offline";
            Label sub = UiKit.label(state, "row-sub");
            if (state.startsWith("⚠")) sub.getStyleClass().add("danger-text");
            if (state.startsWith("✓")) sub.getStyleClass().add("accent-text");
            Node r = row(UiKit.avatarWithPresence(f.username(), f.online()),
                    new VBox(2, UiKit.label(f.username(), "row-title"), sub), new HBox());
            r.setId("friend-" + f.username());
            r.setOnMouseClicked(e -> open(AccountSession.dm(f.username())));
            list.getChildren().add(r);
        }
    }

    private static Node heading(String text) {
        HBox h = new HBox(UiKit.label(text, "overline"));
        h.getStyleClass().add("list-heading");
        return h;
    }

    private static HBox row(Node avatar, Node text, Node right) {
        if (text instanceof javafx.scene.layout.Region r) r.setMinWidth(0);
        if (right instanceof javafx.scene.layout.Region r) r.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        if (avatar instanceof javafx.scene.layout.Region r) r.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox row = new HBox(avatar, text, UiKit.grow(), right);
        row.getStyleClass().add("list-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ------------------------------------------------------------------ content

    private void showEmpty() {
        current = null;
        VBox empty = new VBox(12, Icons.of(Icons.LOCK, 40, "muted"),
                UiKit.label("Select a conversation", "h2"),
                UiKit.label("Everything you send is end-to-end encrypted.", "caption"));
        empty.setAlignment(Pos.CENTER);
        empty.getStyleClass().add("content-empty");
        content.getChildren().setAll(empty);
    }

    void open(String conversation) {
        hookFocus();
        ConversationView.Actions actions = new ConversationView.Actions() {
            @Override public void openContact(String user) { MainView.this.openContact(user); }

            @Override public void openGroupInfo(long groupId) { MainView.this.openGroupInfo(groupId); }
        };
        current = conversation.startsWith("g:")
                ? ConversationView.group(session, Long.parseLong(conversation.substring(2)), actions)
                : ConversationView.dm(session, conversation.substring(3), actions);
        content.getChildren().setAll(current.root());
        if (!chatsTab.isSelected() && conversation.startsWith("dm:")) {
            chatsTab.setSelected(true); // also refreshes
        } else {
            refreshSidebar();
        }
    }

    // ------------------------------------------------------------------ dialogs

    private void openContact(String user) {
        ChatClient c = session.client();
        String current = c.currentFingerprint(user);
        String pinned = c.pinnedFingerprint(user);
        boolean changed = c.keyChanged(user);
        VBox mine = new VBox(8, UiKit.label("YOUR KEY (" + user.toUpperCase() + " SHOULD SEE THIS)", "overline"),
                FingerprintView.compact(Identity.fingerprint(myKey())));
        mine.getStyleClass().add("card-flat");
        Label explain = UiKit.label(changed
                ? user + " now has a different key. That is expected after a reinstall or a new computer, and is "
                        + "also what an attack looks like. Call " + user + " and compare the new pattern before accepting it."
                : "Compare this pattern and code with what " + user + " sees for their own key, over a call or in person.",
                "body");
        explain.setWrapText(true);
        VBox box = Overlay.dialog(changed ? user + "'s key changed" : "Verify " + user, explain);
        if (changed && pinned != null) {
            box.getChildren().add(new VBox(6, UiKit.label("TRUSTED BEFORE", "overline"), FingerprintView.compact(pinned)));
        }
        if (current != null) {
            VBox theirs = new VBox(8, UiKit.label(changed ? "NEW KEY" : user.toUpperCase() + "'S KEY", "overline"),
                    FingerprintView.compact(current));
            theirs.getStyleClass().add("card-flat");
            box.getChildren().add(theirs);
        }
        box.getChildren().add(mine);
        Button close = new Button("Close");
        close.setOnAction(e -> overlay.close());
        Button confirm = new Button(changed ? "I verified the new key" : "Mark as verified");
        confirm.setId("confirm-verify");
        confirm.getStyleClass().add("primary");
        confirm.setOnAction(e -> call(() -> {
            if (changed) c.trustNewKey(user);
            c.markVerified(user);
            return null;
        }, r -> {
            overlay.close();
            if (this.current != null) this.current.refreshHeader();
            refreshSidebar();
        }));
        if (current != null) box.getChildren().add(safetyNumber(user, current, confirm));
        HBox buttons = new HBox(10, UiKit.grow(), close, confirm);
        box.getChildren().add(buttons);
        box.setId("contact-dialog");
        overlay.show(box);
    }

    /**
     * The safety number and QR code both sides compute from both keys. Checking a picture of the
     * friend's QR code verifies them in one step; a mismatch is shown loudly.
     */
    private Node safetyNumber(String user, String theirFingerprint, Button confirm) {
        ChatClient c = session.client();
        SafetyNumber number = SafetyNumber.of(c.username(), Identity.fingerprint(myKey()), user, theirFingerprint);
        javafx.scene.image.ImageView qr = new javafx.scene.image.ImageView(QrCodes.render(number.qrPayload(), 300));
        qr.setFitWidth(132);
        qr.setPreserveRatio(true);
        qr.setId("safety-qr");
        Label digits = UiKit.label(number.digits(), "safety-digits");
        digits.setId("safety-digits");
        Label result = UiKit.label("", "caption");
        result.setWrapText(true);
        Button check = new Button("Check their QR code…");
        check.setId("check-qr");
        check.getStyleClass().add("small");
        check.setOnAction(e -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle("Picture of " + user + "'s QR code");
            chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"));
            java.io.File file = chooser.showOpenDialog(stack.getScene().getWindow());
            if (file == null) return;
            String scanned = QrCodes.read(new javafx.scene.image.Image(file.toURI().toString()));
            result.getStyleClass().removeAll("danger-text", "accent-text");
            if (scanned == null) {
                result.setText("No QR code found in that picture.");
                result.getStyleClass().add("danger-text");
            } else if (number.matches(scanned)) {
                result.setText("✓ The codes match. Marking " + user + " as verified…");
                result.getStyleClass().add("accent-text");
                confirm.fire();
            } else {
                result.setText("✗ The codes do NOT match. Do not trust this key until you find out why "
                        + "(a reinstall on one side, or someone in the middle).");
                result.getStyleClass().add("danger-text");
            }
        });
        Label how = UiKit.label("Both of you see the same number and QR code if your keys are genuine. Read the "
                + "numbers out on a call, or send each other a picture of the QR code and check it here.", "caption");
        how.setWrapText(true);
        how.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        result.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        result.setMaxWidth(260);
        VBox right = new VBox(8, digits, check, result);
        HBox row = new HBox(16, qr, right);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(8, UiKit.label("SAFETY NUMBER", "overline"), row, how);
        card.getStyleClass().add("card-flat");
        return card;
    }

    private byte[] myKey() {
        return session.client().deviceKey();
    }

    private void openMyKey() {
        VBox card = new VBox(14, FingerprintView.large(Identity.fingerprint(myKey())));
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("card");
        Label text = UiKit.label("Friends verify you by comparing this with what their app shows for you.", "body");
        text.setWrapText(true);
        Button ok = new Button("Done");
        ok.getStyleClass().add("primary");
        ok.setOnAction(e -> overlay.close());
        overlay.show(Overlay.dialog("Your security fingerprint", text, card, new HBox(UiKit.grow(), ok)));
    }

    private void openNewGroup() {
        TextField name = new TextField();
        name.setPromptText("Group name");
        name.setId("group-name");
        VBox members = new VBox(8);
        List<CheckBox> boxes = new ArrayList<>();
        for (Packet.Friend f : session.client().friends().friends()) {
            CheckBox cb = new CheckBox(f.username());
            cb.setId("pick-" + f.username());
            boxes.add(cb);
            members.getChildren().add(cb);
        }
        if (boxes.isEmpty()) members.getChildren().add(UiKit.label("Add friends first; groups are made of friends.", "caption"));
        ScrollPane scroll = new ScrollPane(members);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(220, 28.0 * Math.max(1, boxes.size())));
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> overlay.close());
        Button create = new Button("Create group");
        create.setId("create-group");
        create.getStyleClass().add("primary");
        create.disableProperty().bind(name.textProperty().isEmpty());
        create.setOnAction(e -> {
            List<String> picked = boxes.stream().filter(CheckBox::isSelected).map(CheckBox::getText).toList();
            call(() -> session.client().createGroup(name.getText().strip(), picked), g -> {
                overlay.close();
                open(AccountSession.group(g.id()));
            });
        });
        overlay.show(Overlay.dialog("New group", new VBox(6, UiKit.label("Name", "field-label"), name),
                new VBox(6, UiKit.label("Members", "field-label"), scroll), new HBox(10, UiKit.grow(), cancel, create)));
        Platform.runLater(name::requestFocus);
    }

    private void openGroupInfo(long groupId) {
        ChatClient c = session.client();
        Packet.GroupInfo g = c.groups().stream().filter(x -> x.id() == groupId).findFirst().orElse(null);
        if (g == null) return;
        boolean admin = g.owner().equals(c.username());
        VBox rows = new VBox(4);
        for (Packet.Member m : g.members()) {
            HBox right = new HBox(6);
            right.setAlignment(Pos.CENTER_RIGHT);
            if (m.username().equals(g.owner())) right.getChildren().add(UiKit.label("admin", "caption"));
            if (admin && !m.username().equals(c.username())) {
                Button remove = new Button("Remove");
                remove.setId("remove-" + m.username());
                remove.getStyleClass().addAll("small", "danger-ghost");
                remove.setOnAction(e -> call(() -> {
                    c.removeGroupMember(groupId, m.username());
                    return null;
                }, r -> overlay.close()));
                right.getChildren().add(remove);
            }
            String who = m.username().equals(c.username()) ? m.username() + " (you)" : m.username();
            HBox row = new HBox(10, UiKit.avatar(m.username(), "avatar-xs", false), UiKit.label(who, "row-title"), UiKit.grow(), right);
            row.setAlignment(Pos.CENTER_LEFT);
            rows.getChildren().add(row);
        }
        Label about = UiKit.label(g.members().size() + " members · messages and the group name are end-to-end "
                + "encrypted; the member list is visible to the server.", "caption");
        about.setWrapText(true);
        VBox box = Overlay.dialog(g.name(), about, rows);
        if (admin) {
            ComboBox<String> candidates = new ComboBox<>();
            c.friends().friends().stream().map(Packet.Friend::username).filter(u -> !g.hasMember(u)).forEach(candidates.getItems()::add);
            candidates.setPromptText(candidates.getItems().isEmpty() ? "All your friends are here" : "Add a friend");
            candidates.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(candidates, Priority.ALWAYS);
            Button add = new Button("Add");
            add.getStyleClass().addAll("small", "primary");
            add.disableProperty().bind(candidates.valueProperty().isNull());
            add.setOnAction(e -> call(() -> {
                c.addGroupMember(groupId, candidates.getValue());
                return null;
            }, r -> overlay.close()));
            box.getChildren().add(new HBox(8, candidates, add));
            TextField newName = new TextField(g.name().equals(ChatClient.ENCRYPTED_GROUP_NAME) ? "" : g.name());
            newName.setId("group-name");
            newName.setPromptText("Group name");
            HBox.setHgrow(newName, Priority.ALWAYS);
            Button rename = new Button("Rename");
            rename.setId("rename-group");
            rename.getStyleClass().add("small");
            rename.disableProperty().bind(newName.textProperty().isEmpty());
            rename.setOnAction(e -> call(() -> {
                c.renameGroup(groupId, newName.getText());
                return null;
            }, r -> overlay.close()));
            box.getChildren().add(new HBox(8, newName, rename));
        }
        Button leave = new Button("Leave group");
        leave.setId("leave-group");
        leave.getStyleClass().add("danger-ghost");
        leave.setOnAction(e -> call(() -> {
            c.leaveGroup(groupId);
            return null;
        }, r -> {
            overlay.close();
            showEmpty();
            refreshSidebar();
        }));
        Button close = new Button("Close");
        close.setOnAction(e -> overlay.close());
        box.getChildren().add(new HBox(10, leave, UiKit.grow(), close));
        box.setId("group-dialog");
        overlay.show(box);
    }

    // ------------------------------------------------------------------ AccountSession.View

    @Override
    public void messageStored(History.Message m) {
        if (current != null && current.conversation().equals(m.conversation())) {
            current.append(m);
            try {
                session.history().markRead(m.conversation());
            } catch (IOException ignored) {
                // unread count is cosmetic
            }
        }
        if (chatsTab.isSelected()) refreshSidebar();
    }

    @Override
    public void statusChanged(long localId, int status) {
        if (current != null) current.setStatus(localId, status);
    }

    @Override
    public void friendsChanged() {
        refreshSidebar();
        if (current != null) current.refreshHeader();
    }

    @Override
    public void groupsChanged() {
        if (current != null && current.groupId() != 0
                && session.client().groups().stream().noneMatch(g -> g.id() == current.groupId())) {
            showEmpty();
        } else if (current != null) {
            current.refreshHeader();
        }
        refreshSidebar();
    }

    @Override
    public void keyChanged(String user) {
        if (current != null) current.refreshHeader();
        refreshSidebar();
    }

    @Override
    public void problem(String text) {
        if (current != null) {
            current.systemLine(text, text.contains("could not") || text.contains("not "));
        }
    }

    @Override
    public void disconnected() {
        onLogout.run();
    }

    @Override
    public void messagesExpired(List<History.Expired> expired) {
        if (current != null) {
            current.remove(expired.stream().filter(e -> e.conversation().equals(current.conversation()))
                    .map(History.Expired::id).toList());
        }
        refreshSidebar();
    }

    @Override
    public void messageChanged(String conversation, long localId) {
        if (current != null && current.conversation().equals(conversation)) current.refresh(localId);
        refreshSidebar();
    }

    @Override
    public void typing(String conversation, String who) {
        if (current != null && current.conversation().equals(conversation)) current.typing(who);
    }

    @Override
    public void notifyIncoming(History.Message m) {
        if (!session.setting(AccountSession.NOTIFICATIONS) || !Boolean.parseBoolean(System.getProperty("securechat.notifications", "true"))) return;
        boolean focused = stack.getScene() != null && stack.getScene().getWindow() != null
                && stack.getScene().getWindow().isFocused();
        boolean visible = current != null && current.conversation().equals(m.conversation());
        if (focused && visible) return;
        String where = m.conversation().startsWith("g:") ? groupName(Long.parseLong(m.conversation().substring(2)))
                : m.sender();
        String title = m.conversation().startsWith("g:") ? m.sender() + " in " + where : where;
        String body = session.setting(AccountSession.NOTIFICATION_PREVIEW)
                ? (m.file() != null ? "📎 " + m.text() : m.text())
                : "New message";
        notifier.show(title, body);
    }

    /** Called once the view is in a window: report reads when the window comes back into focus. */
    private void hookFocus() {
        if (focusHooked || stack.getScene() == null || stack.getScene().getWindow() == null) return;
        focusHooked = true;
        stack.getScene().getWindow().focusedProperty().addListener((o, a, focused) -> {
            if (focused && current != null) current.windowFocused();
        });
    }

    void dispose() {
        notifier.close();
    }

    @Override
    public void timerChanged(String conversation, String by, long seconds) {
        if (current != null && current.conversation().equals(conversation)) current.timerChanged(by, seconds);
    }

    // ------------------------------------------------------------------ helpers

    /** Runs a network call off the UI thread; shows errors in the open conversation or a dialog. */
    private <T> void call(Callable<T> action, Consumer<T> onSuccess) {
        session.background().execute(() -> {
            try {
                T result = action.call();
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (ApiException | IOException | KeyChangedException e) {
                Platform.runLater(() -> showError(e.getMessage()));
            } catch (Exception e) {
                Platform.runLater(() -> showError("Unexpected error: " + e.getMessage()));
            }
        });
    }

    private void showError(String message) {
        Button ok = new Button("OK");
        ok.getStyleClass().add("primary");
        ok.setOnAction(e -> overlay.close());
        Label text = UiKit.label(message, "body");
        text.setWrapText(true);
        VBox box = Overlay.dialog("Something went wrong", text, new HBox(UiKit.grow(), ok));
        box.setId("error-dialog");
        overlay.show(box);
    }
}
