package com.emirchat.ui;

import com.emirchat.api.Packet;
import com.emirchat.chat.ChatMessage;
import com.emirchat.chat.FileMessage;
import com.emirchat.e2e.Attachments;
import com.emirchat.client.ChatClient;
import com.emirchat.client.History;
import com.emirchat.trust.KeyChangedException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/** One conversation (a DM or a group) in the main window. */
final class ConversationView {
    private static final long GROUP_WINDOW_MS = 2 * 60_000;

    interface Actions {
        void openContact(String user);

        void openGroupInfo(long groupId);
    }

    private final AccountSession session;
    private final String conversation;
    private final String peer;          // DM only
    private final long groupId;         // group only (0 for DMs)
    private final Actions actions;
    private final BorderPane root = new BorderPane();
    private final VBox top = new VBox();
    private final VBox messages = new VBox();
    private final ScrollPane scroller = new ScrollPane(messages);
    private final Map<Long, Label> statusLabels = new HashMap<>();
    private final Map<Long, Node> rows = new HashMap<>();
    private final TextField input = new TextField();
    private final Map<Long, Boolean> continuedById = new HashMap<>();
    private Map<Long, Map<String, String>> reactions = new HashMap<>();
    private final HBox contextBar = new HBox(8);
    private final Label contextText = new Label();
    private final Label presence = new Label();
    private final javafx.animation.PauseTransition typingTimeout =
            new javafx.animation.PauseTransition(javafx.util.Duration.seconds(5));
    private final javafx.animation.PauseTransition readDelay =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(700));
    private byte[] replyTo;            // uid of the message being answered, or null
    private History.Message editing;   // our own message being edited, or null
    private byte[] pendingRead;        // newest incoming uid not yet reported as read
    private long lastTypingSent;
    private String typingWho;
    private String lastSender;
    private long lastAt;

    static ConversationView dm(AccountSession session, String peer, Actions actions) {
        return new ConversationView(session, AccountSession.dm(peer), peer, 0, actions);
    }

    static ConversationView group(AccountSession session, long groupId, Actions actions) {
        return new ConversationView(session, AccountSession.group(groupId), null, groupId, actions);
    }

    private ConversationView(AccountSession session, String conversation, String peer, long groupId, Actions actions) {
        this.session = session;
        this.conversation = conversation;
        this.peer = peer;
        this.groupId = groupId;
        this.actions = actions;

        messages.getStyleClass().add("messages");
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        messages.heightProperty().addListener((o, a, b) -> scroller.setVvalue(1.0));

        input.setId("composer");
        HBox.setHgrow(input, Priority.ALWAYS);
        Button send = new Button();
        send.setId("send");
        send.getStyleClass().add("send");
        send.setGraphic(Icons.of(Icons.SEND, 17, "on-accent"));
        send.setTooltip(new Tooltip("Send (Enter)"));
        send.disableProperty().bind(input.textProperty().isEmpty());
        input.setOnAction(e -> send());
        send.setOnAction(e -> send());
        Button attach = new Button();
        attach.setId("attach");
        attach.getStyleClass().add("attach");
        attach.setGraphic(Icons.of(Icons.PAPERCLIP, 19, "muted"));
        attach.setTooltip(new Tooltip("Send a file (encrypted, up to " + Attachments.MAX_FILE_BYTES / (1024 * 1024) + " MB)"));
        attach.setOnAction(e -> chooseFile());
        HBox composer = new HBox(attach, input, send);
        composer.getStyleClass().add("composer");
        composer.setAlignment(Pos.CENTER);

        // "Replying to …" / "Editing message" bar above the composer
        Button cancelContext = new Button("✕");
        cancelContext.getStyleClass().addAll("small", "attach");
        cancelContext.setId("cancel-context");
        cancelContext.setOnAction(e -> clearContext());
        contextText.getStyleClass().add("context-text");
        contextText.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(contextText, Priority.ALWAYS);
        contextBar.getChildren().addAll(contextText, cancelContext);
        contextBar.getStyleClass().add("context-bar");
        contextBar.setAlignment(Pos.CENTER_LEFT);
        contextBar.setVisible(false);
        contextBar.setManaged(false);
        input.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) clearContext();
        });
        input.textProperty().addListener((o, a, b) -> maybeSendTyping(b));
        typingTimeout.setOnFinished(e -> {
            typingWho = null;
            refreshPresence();
        });
        readDelay.setOnFinished(e -> sendReadReceipt());

        root.setTop(top);
        root.setCenter(scroller);
        root.setBottom(new VBox(contextBar, composer));
        refreshHeader();
        load();
        Platform.runLater(input::requestFocus);
    }

    Parent root() {
        return root;
    }

    String conversation() {
        return conversation;
    }

    String peer() {
        return peer;
    }

    long groupId() {
        return groupId;
    }

    // ------------------------------------------------------------------ header

    void refreshHeader() {
        ChatClient client = session.client();
        HBox header = new HBox(12);
        header.getStyleClass().add("chat-header");
        header.setAlignment(Pos.CENTER_LEFT);
        VBox titles = new VBox(2);
        if (peer != null) {
            input.setPromptText("Message " + peer);
            header.getChildren().add(UiKit.avatarWithPresence(peer, client.isOnline(peer)));
            presence.getStyleClass().setAll("label", "row-sub");
            refreshPresence();
            Label status = presence;
            Label badge = client.isVerified(peer) && !client.keyChanged(peer)
                    ? UiKit.label("✓ Verified", "badge-verified")
                    : UiKit.label("Not verified", "badge-unverified");
            badge.setId("verify-badge");
            badge.setOnMouseClicked(e -> actions.openContact(peer));
            titles.getChildren().addAll(UiKit.label(peer, "h2"), new HBox(8, status, UiKit.label("·", "row-sub"), badge));
        } else {
            Packet.GroupInfo g = group();
            String name = g == null ? "Group" : g.name();
            input.setPromptText("Message " + name);
            header.getChildren().add(UiKit.avatar(name, "avatar-sm", true));
            Label members = UiKit.label(g == null ? "" : g.members().size() + " members · end-to-end encrypted", "badge-verified");
            members.setId("group-info-link");
            members.setStyle("-fx-cursor: hand;");
            members.setOnMouseClicked(e -> actions.openGroupInfo(groupId));
            presence.getStyleClass().setAll("label", "row-sub", "typing");
            refreshPresence();
            titles.getChildren().addAll(UiKit.label(name, "h2"), new HBox(8, members, presence));
        }
        header.getChildren().addAll(titles, UiKit.grow(), timerButton());
        if (peer == null) {
            Button info = new Button("Group info");
            info.getStyleClass().add("small");
            info.setOnAction(e -> actions.openGroupInfo(groupId));
            header.getChildren().add(info);
        }
        top.getChildren().setAll(header);
        if (peer != null && client.keyChanged(peer)) {
            Button verify = new Button("Verify");
            verify.getStyleClass().addAll("small", "danger-ghost");
            verify.setId("banner-verify");
            verify.setOnAction(e -> actions.openContact(peer));
            Label text = UiKit.label(peer + "'s security key changed (new device?). Messages are paused until you verify it.");
            text.setWrapText(true);
            HBox banner = new HBox(Icons.of(Icons.ALERT, 16, "danger"), text, UiKit.grow(), verify);
            banner.getStyleClass().add("banner-danger");
            banner.setAlignment(Pos.CENTER_LEFT);
            banner.setId("key-banner");
            top.getChildren().add(banner);
        }
    }

    /** Shows the disappearing-message timer and lets the user change it for everyone in the chat. */
    private Node timerButton() {
        long current = session.timer(conversation);
        MenuButton button = new MenuButton(current > 0 ? AccountSession.timerLabel(current) : "");
        button.setId("timer");
        button.getStyleClass().addAll("small", "timer-button");
        if (current > 0) button.getStyleClass().add("active");
        button.setGraphic(Icons.of(Icons.CLOCK, 15, current > 0 ? "accent" : "muted"));
        button.setTooltip(new Tooltip(current > 0
                ? "Disappearing messages: new messages are deleted " + AccountSession.timerLabel(current).toLowerCase(java.util.Locale.ROOT) + " after they arrive"
                : "Disappearing messages: off"));
        for (long seconds : AccountSession.TIMER_CHOICES) {
            MenuItem item = new MenuItem((seconds == current ? "✓ " : "   ") + AccountSession.timerLabel(seconds));
            item.setId("timer-" + seconds);
            item.setOnAction(e -> setTimer(seconds));
            button.getItems().add(item);
        }
        return button;
    }

    private void setTimer(long seconds) {
        if (seconds == session.timer(conversation)) return;
        session.background().execute(() -> {
            String problem = null;
            try {
                if (peer != null) {
                    session.client().setTimer(peer, seconds);
                } else {
                    List<String> skipped = session.client().setGroupTimer(groupId, seconds);
                    if (!skipped.isEmpty()) problem = "Not changed for " + String.join(", ", skipped) + " until their new key is verified.";
                }
                session.history().setTimer(conversation, seconds);
            } catch (KeyChangedException e) {
                problem = peer + "'s key changed. Verify it first.";
            } catch (Exception e) {
                problem = "Could not change the timer: " + e.getMessage();
            }
            String finalProblem = problem;
            Platform.runLater(() -> {
                if (finalProblem != null) systemLine(finalProblem, true);
                timerChanged(session.client().username(), session.timer(conversation));
            });
        });
    }

    /** Someone (or we) changed the timer: note it in the chat and update the header. */
    void timerChanged(String by, long seconds) {
        String who = by.equals(session.client().username()) ? "You" : by;
        systemLine(seconds > 0
                ? who + " turned on disappearing messages. New messages disappear " + AccountSession.timerLabel(seconds) + " after they arrive."
                : who + " turned off disappearing messages.", false);
        refreshHeader();
    }

    /** Removes messages that disappeared. */
    void remove(List<Long> ids) {
        for (long id : ids) {
            Node row = rows.remove(id);
            if (row != null) messages.getChildren().remove(row);
            statusLabels.remove(id);
        }
        if (!ids.isEmpty()) lastSender = null;
    }

    /** "online"/"offline" for a friend, or "… is typing" while someone types. */
    private void refreshPresence() {
        if (typingWho != null) {
            presence.setText(peer != null ? "typing…" : typingWho + " is typing…");
            if (!presence.getStyleClass().contains("typing")) presence.getStyleClass().add("typing");
        } else {
            presence.getStyleClass().remove("typing");
            presence.setText(peer != null ? (session.client().isOnline(peer) ? "online" : "offline") : "");
        }
    }

    /** Someone is typing here; the notice fades after a few seconds without news. */
    void typing(String who) {
        typingWho = who;
        refreshPresence();
        typingTimeout.playFromStart();
    }

    private void maybeSendTyping(String text) {
        if (peer == null || text == null || text.isBlank() || editing != null) return;
        long now = System.currentTimeMillis();
        if (now - lastTypingSent < 3000 || !session.setting(AccountSession.SEND_TYPING)) return;
        if (!session.client().isOnline(peer)) return; // never queue typing notices on the server
        lastTypingSent = now;
        session.background().execute(() -> {
            try {
                session.client().sendAction(ChatClient.Chat.dm(peer), ChatClient.ActionKind.TYPING, null, "1");
            } catch (Exception ignored) {
                // a typing notice is best effort
            }
        });
    }

    private ChatClient.Chat chat() {
        return peer != null ? ChatClient.Chat.dm(peer) : ChatClient.Chat.group(groupId);
    }

    private Packet.GroupInfo group() {
        return session.client().groups().stream().filter(g -> g.id() == groupId).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ messages

    private void load() {
        try {
            List<History.Message> list = session.history().load(conversation, 300);
            if (list.isEmpty()) {
                Label hint = UiKit.label(peer != null
                        ? "Messages to " + peer + " are end-to-end encrypted. Only the two of you can read them."
                        : "Messages in this group are end-to-end encrypted for its members.", "system-line");
                hint.setWrapText(true);
                HBox row = new HBox(hint);
                row.setAlignment(Pos.CENTER);
                row.setPadding(new Insets(14, 0, 4, 0));
                messages.getChildren().add(row);
            }
            reactions = session.history().reactions(conversation);
            for (History.Message m : list) append(m);
            session.history().markRead(conversation);
        } catch (IOException e) {
            messages.getChildren().add(UiKit.label("Could not load history: " + e.getMessage(), "system-line", "danger"));
        }
    }

    void append(History.Message m) {
        boolean continued = m.sender().equals(lastSender) && m.sentAt() - lastAt < GROUP_WINDOW_MS;
        Node row = buildRow(m, continued);
        messages.getChildren().add(row);
        rows.put(m.id(), row);
        continuedById.put(m.id(), continued);
        lastSender = m.sender();
        lastAt = m.sentAt();
        if (!m.outgoing() && m.hasUid() && peer != null) {
            pendingRead = m.uid();
            readDelay.playFromStart();
        }
    }

    /** Re-draws one message after an edit, deletion or reaction. */
    void refresh(long localId) {
        Node old = rows.get(localId);
        if (old == null) return;
        try {
            History.Message m = session.history().get(localId);
            reactions = session.history().reactions(conversation);
            int index = messages.getChildren().indexOf(old);
            if (m == null || index < 0) return;
            Node row = buildRow(m, continuedById.getOrDefault(localId, false));
            messages.getChildren().set(index, row);
            rows.put(localId, row);
        } catch (IOException ignored) {
            // the old rendering stays
        }
    }

    /** Scrolls to a message and highlights it briefly (search results). */
    void reveal(long localId) {
        Node row = rows.get(localId);
        if (row == null) return;
        Platform.runLater(() -> {
            double content = messages.getHeight() - scroller.getViewportBounds().getHeight();
            if (content > 0) scroller.setVvalue(Math.max(0, Math.min(1, row.getBoundsInParent().getMinY() / content)));
            row.getStyleClass().add("highlight");
            javafx.animation.PauseTransition t = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
            t.setOnFinished(e -> row.getStyleClass().remove("highlight"));
            t.play();
        });
    }

    private Node buildRow(History.Message m, boolean continued) {
        boolean own = m.outgoing();
        VBox bubble = new VBox(2);
        bubble.getStyleClass().addAll("bubble", own ? "own" : "peer");
        if (continued) bubble.getStyleClass().add("continued");
        if (m.replyTo().length > 0 && !m.deleted()) bubble.getChildren().add(quote(m.replyTo()));
        Node body;
        if (m.deleted()) {
            body = UiKit.label("🚫 " + History.DELETED_TEXT, "bubble-text", "bubble-deleted");
        } else if (m.file() != null) {
            body = fileCard(m.file());
        } else {
            Label text = UiKit.label(m.text(), "bubble-text");
            text.setWrapText(true);
            body = text;
        }
        HBox meta = new HBox(5, UiKit.label(UiKit.clock(m.sentAt()), "bubble-time"));
        if (m.edited()) meta.getChildren().add(0, UiKit.label("edited", "bubble-time"));
        if (m.expiresAt() > 0) {
            Label timer = UiKit.label("⏱", "bubble-time");
            Tooltip.install(timer, new Tooltip("Disappears at " + UiKit.clock(m.expiresAt())));
            meta.getChildren().add(0, timer);
        }
        meta.setAlignment(Pos.CENTER_RIGHT);
        if (own) {
            Label status = UiKit.label("", "bubble-status");
            statusLabels.put(m.id(), status);
            applyStatus(status, m.status());
            meta.getChildren().add(status);
        }
        bubble.getChildren().addAll(body, meta);
        bubble.maxWidthProperty().bind(messages.widthProperty().multiply(0.70));
        bubble.setMinWidth(Region.USE_PREF_SIZE);
        bubble.setMinHeight(Region.USE_PREF_SIZE);
        if (m.hasUid() && !m.deleted()) {
            bubble.setOnContextMenuRequested(e -> menu(m).show(bubble, e.getScreenX(), e.getScreenY()));
        }

        VBox column = new VBox(bubble);
        column.setAlignment(own ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        if (groupId != 0 && !own && !continued) {
            Label name = UiKit.label(m.sender(), "sender-name");
            name.setStyle("-fx-text-fill: " + UiKit.css(UiKit.colorFor(m.sender())) + ";");
            column.getChildren().add(0, name);
        }
        Map<String, String> theirs = reactions.get(m.id());
        if (theirs != null && !theirs.isEmpty()) column.getChildren().add(reactionChips(m, theirs, own));
        HBox row = new HBox(column);
        row.setAlignment(own ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        row.setPadding(new Insets(continued ? 3 : 12, 0, 0, 0));
        row.setUserData(m.id());
        return row;
    }

    /** The answered message, shown small at the top of a reply. */
    private Node quote(byte[] target) {
        String who;
        String text;
        try {
            History.Message original = session.history().find(conversation, target);
            who = original == null ? "" : original.outgoing() ? "You" : original.sender();
            text = original == null ? "Message not available" : original.text();
        } catch (IOException e) {
            who = "";
            text = "Message not available";
        }
        Label sender = UiKit.label(who, "quote-sender");
        Label snippet = UiKit.label(text, "quote-text");
        snippet.setMaxWidth(320);
        VBox box = new VBox(1, sender, snippet);
        box.getStyleClass().add("reply-quote");
        return box;
    }

    private Node reactionChips(History.Message m, Map<String, String> byPerson, boolean own) {
        Map<String, List<String>> byEmoji = new java.util.LinkedHashMap<>();
        byPerson.forEach((person, emoji) -> byEmoji.computeIfAbsent(AccountSession.displayEmoji(emoji), k -> new java.util.ArrayList<>()).add(person));
        HBox chips = new HBox(4);
        chips.setAlignment(own ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        String me = session.client().username();
        byEmoji.forEach((emoji, people) -> {
            Label chip = UiKit.label(emoji + (people.size() > 1 ? " " + people.size() : ""), "reaction-chip");
            if (people.contains(me)) chip.getStyleClass().add("mine");
            Tooltip.install(chip, new Tooltip(String.join(", ", people.stream().map(p -> p.equals(me) ? "you" : p).toList())));
            chip.setOnMouseClicked(e -> react(m, people.contains(me) ? "" : emoji));
            chips.getChildren().add(chip);
        });
        return chips;
    }

    private javafx.scene.control.ContextMenu menu(History.Message m) {
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        MenuItem reply = new MenuItem("Reply");
        reply.setId("menu-reply");
        reply.setOnAction(e -> startReply(m));
        javafx.scene.control.Menu react = new javafx.scene.control.Menu("React");
        String mine = AccountSession.displayEmoji(reactions.getOrDefault(m.id(), Map.of()).getOrDefault(session.client().username(), ""));
        for (String emoji : AccountSession.REACTIONS) {
            MenuItem item = new MenuItem(emoji.equals(mine) ? emoji + "  ✓" : emoji);
            item.setId("react-" + AccountSession.REACTIONS.indexOf(emoji));
            item.setOnAction(e -> react(m, emoji.equals(mine) ? "" : emoji));
            react.getItems().add(item);
        }
        menu.getItems().addAll(reply, react);
        if (m.file() == null) {
            MenuItem copy = new MenuItem("Copy text");
            copy.setOnAction(e -> {
                javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                content.putString(m.text());
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            });
            menu.getItems().add(copy);
        }
        if (m.outgoing()) {
            if (m.file() == null) {
                MenuItem edit = new MenuItem("Edit");
                edit.setId("menu-edit");
                edit.setOnAction(e -> startEdit(m));
                menu.getItems().add(edit);
            }
            MenuItem delete = new MenuItem("Delete for everyone");
            delete.setId("menu-delete");
            delete.setOnAction(e -> deleteForEveryone(m));
            menu.getItems().add(delete);
        }
        return menu;
    }

    void act(long localId, String action) {
        try {
            History.Message m = session.history().get(localId);
            if (m == null) return;
            switch (action) {
                case "reply" -> startReply(m);
                case "edit" -> startEdit(m);
                case "delete" -> deleteForEveryone(m);
                default -> react(m, action);
            }
        } catch (IOException ignored) {
            // nothing to act on
        }
    }

    private void startReply(History.Message m) {
        editing = null;
        replyTo = m.uid();
        String who = m.outgoing() ? "yourself" : m.sender();
        showContext("↩ Replying to " + who + ": " + m.text());
        input.requestFocus();
    }

    private void startEdit(History.Message m) {
        replyTo = null;
        editing = m;
        showContext("✎ Editing message");
        input.setText(m.text());
        input.positionCaret(input.getText().length());
        input.requestFocus();
    }

    private void showContext(String text) {
        contextText.setText(text);
        contextBar.setVisible(true);
        contextBar.setManaged(true);
    }

    private void clearContext() {
        if (editing != null) input.clear();
        replyTo = null;
        editing = null;
        contextBar.setVisible(false);
        contextBar.setManaged(false);
    }

    private void react(History.Message m, String emoji) {
        String me = session.client().username();
        try {
            session.history().react(conversation, m.uid(), me, emoji);
        } catch (IOException e) {
            return;
        }
        refresh(m.id());
        sendAction(ChatClient.ActionKind.REACT, m.uid(), emoji);
    }

    private void deleteForEveryone(History.Message m) {
        try {
            History.Message deleted = session.history().delete(conversation, m.uid(), session.client().username());
            if (deleted != null && deleted.file() != null) session.client().forgetFile(deleted.file());
        } catch (IOException e) {
            return;
        }
        refresh(m.id());
        sendAction(ChatClient.ActionKind.DELETE, m.uid(), "");
    }

    private void sendAction(ChatClient.ActionKind kind, byte[] target, String value) {
        session.background().execute(() -> {
            String problem = null;
            try {
                session.client().sendAction(chat(), kind, target, value);
            } catch (KeyChangedException e) {
                problem = peer + "'s key changed. Verify it first.";
            } catch (Exception e) {
                problem = "Could not send the change: " + e.getMessage();
            }
            String p = problem;
            if (p != null) Platform.runLater(() -> systemLine(p, true));
        });
    }

    /** Tells a friend we read their messages up to the newest one shown (if they allow receipts). */
    private void sendReadReceipt() {
        byte[] target = pendingRead;
        pendingRead = null;
        if (target == null || peer == null || !session.setting(AccountSession.SEND_READ_RECEIPTS)) return;
        if (root.getScene() == null || root.getScene().getWindow() == null || !root.getScene().getWindow().isFocused()) {
            pendingRead = target; // read when the window is looked at again
            return;
        }
        sendAction(ChatClient.ActionKind.READ, target, "");
    }

    /** The window regained focus: report what is now visible as read. */
    void windowFocused() {
        if (pendingRead != null) readDelay.playFromStart();
    }

    void setStatus(long localId, int status) {
        Label l = statusLabels.get(localId);
        if (l != null) applyStatus(l, status);
    }

    private void applyStatus(Label l, int status) {
        l.getStyleClass().remove("failed");
        switch (status) {
            case History.PENDING -> l.setText("…");
            case History.SENT -> l.setText(groupId != 0 ? "✓" : "✓");
            case History.DELIVERED -> {
                l.setText("✓✓");
                l.getStyleClass().remove("read");
            }
            case History.READ -> {
                l.setText("✓✓");
                if (!l.getStyleClass().contains("read")) l.getStyleClass().add("read");
            }
            case History.FAILED -> {
                l.setText("not sent");
                l.getStyleClass().add("failed");
            }
            default -> l.setText("");
        }
    }

    // ------------------------------------------------------------------ sending

    private void send() {
        String text = input.getText().strip();
        if (text.isEmpty()) return;
        if (text.getBytes(StandardCharsets.UTF_8).length > ChatMessage.MAX_TEXT_BYTES) {
            input.setStyle("-fx-border-color: -sc-danger;");
            return;
        }
        input.setStyle("");
        String me = session.client().username();
        if (editing != null) {
            History.Message target = editing;
            clearContext();
            input.clear();
            if (text.equals(target.text())) return;
            try {
                session.history().edit(conversation, target.uid(), me, text);
            } catch (IOException e) {
                return;
            }
            refresh(target.id());
            sendAction(ChatClient.ActionKind.EDIT, target.uid(), text);
            return;
        }
        input.clear();
        byte[] answering = replyTo;
        clearContext();
        byte[] uid = ChatClient.newMessageId();
        long now = System.currentTimeMillis();
        long expire = session.timer(conversation);
        long expiresAt = AccountSession.expiresAt(now, expire);
        long localId;
        History.Message stored;
        try {
            localId = session.history().add(conversation, me, text, now, true, History.PENDING, 0, expiresAt, uid, answering);
            stored = session.history().get(localId);
        } catch (IOException e) {
            return;
        }
        append(stored);
        session.background().execute(() -> {
            int status;
            String problem = null;
            try {
                ChatClient.Sent sent = session.client().send(chat(), text, expire, answering, uid);
                status = session.sent(localId, sent.envelopeId());
                if (!sent.skipped().isEmpty()) {
                    problem = "Not sent to " + String.join(", ", sent.skipped()) + " until their new key is verified.";
                }
            } catch (KeyChangedException e) {
                status = History.FAILED;
                problem = peer + "'s key changed. Verify it, then send again.";
                Platform.runLater(this::refreshHeader);
            } catch (Exception e) {
                status = History.FAILED;
                problem = e.getMessage();
            }
            if (status == History.FAILED) {
                try {
                    session.history().setStatus(localId, History.FAILED, 0);
                } catch (IOException ignored) {
                    // shown as failed in this session anyway
                }
            }
            int finalStatus = status;
            String finalProblem = problem;
            Platform.runLater(() -> {
                setStatus(localId, finalStatus);
                if (finalProblem != null) systemLine(finalProblem, true);
            });
        });
    }

    // ------------------------------------------------------------------ files

    private Node fileCard(FileMessage file) {
        StackPane icon = new StackPane(Icons.of(Icons.FILE, 18, "accent"));
        icon.getStyleClass().add("file-icon");
        VBox titles = new VBox(1, UiKit.label(file.name(), "file-name"), UiKit.label(humanSize(file.size()), "file-size"));
        Button save = new Button();
        save.getStyleClass().addAll("small", "attach");
        save.setGraphic(Icons.of(Icons.DOWNLOAD, 16, "muted"));
        save.setTooltip(new Tooltip("Save to disk"));
        save.setOnAction(e -> saveFile(file));
        HBox card = new HBox(icon, titles, UiKit.grow(), save);
        card.getStyleClass().add("file-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMinWidth(220);
        if (!file.isPreviewableImage()) return card;
        VBox withPreview = new VBox(6, card);
        session.downloads().execute(() -> {
            try {
                Image image = new Image(new ByteArrayInputStream(session.client().downloadFile(file)));
                if (image.isError()) return;
                Platform.runLater(() -> {
                    ImageView view = new ImageView(image);
                    view.setPreserveRatio(true);
                    view.setFitWidth(Math.min(280, image.getWidth()));
                    view.setSmooth(true);
                    view.getStyleClass().add("file-preview");
                    view.setOnMouseClicked(e -> saveFile(file));
                    withPreview.getChildren().add(0, view);
                });
            } catch (Exception ignored) {
                // no preview; the file can still be saved, which reports any problem
            }
        });
        return withPreview;
    }

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Send a file");
        File chosen = chooser.showOpenDialog(root.getScene().getWindow());
        if (chosen == null) return;
        if (chosen.length() > Attachments.MAX_FILE_BYTES) {
            systemLine(chosen.getName() + " is larger than " + Attachments.MAX_FILE_BYTES / (1024 * 1024) + " MB.", true);
            return;
        }
        String name = FileMessage.safeName(chosen.getName());
        Label progress = UiKit.label("Encrypting and sending " + name + "…", "system-line");
        HBox row = new HBox(progress);
        row.setAlignment(Pos.CENTER_RIGHT);
        row.setPadding(new Insets(12, 0, 0, 0));
        messages.getChildren().add(row);
        long expire = session.timer(conversation);
        byte[] answering = replyTo;
        clearContext();
        session.background().execute(() -> {
            String problem = null;
            History.Message stored = null;
            try {
                byte[] data = Files.readAllBytes(chosen.toPath());
                String mime = mimeType(chosen.toPath());
                String me = session.client().username();
                ChatClient.Sent sent = session.client().sendFile(chat(), name, mime, data, expire, answering);
                long expiresAt = AccountSession.expiresAt(System.currentTimeMillis(), expire);
                long id = session.history().addFile(conversation, me, sent.file(), sent.file().sentAtMillis(), true,
                        History.SENT, sent.envelopeId(), expiresAt, sent.messageId(), answering);
                session.sent(id, sent.envelopeId());
                stored = session.history().get(id);
                if (!sent.skipped().isEmpty()) {
                    problem = "Not sent to " + String.join(", ", sent.skipped()) + " until their new key is verified.";
                }
            } catch (KeyChangedException e) {
                problem = peer + "'s key changed. Verify it, then send again.";
                Platform.runLater(this::refreshHeader);
            } catch (Exception e) {
                problem = "Could not send " + name + ": " + e.getMessage();
            }
            History.Message finalStored = stored;
            String finalProblem = problem;
            Platform.runLater(() -> {
                messages.getChildren().remove(row);
                if (finalStored != null) append(finalStored);
                if (finalProblem != null) systemLine(finalProblem, true);
            });
        });
    }

    private void saveFile(FileMessage file) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save file");
        chooser.setInitialFileName(file.name());
        File target = chooser.showSaveDialog(root.getScene().getWindow());
        if (target == null) return;
        session.downloads().execute(() -> {
            String result;
            boolean danger = false;
            try {
                Files.write(target.toPath(), session.client().downloadFile(file));
                result = "Saved " + target.getName() + ".";
            } catch (SecurityException e) {
                result = "Did not save " + file.name() + ": " + e.getMessage() + ".";
                danger = true;
            } catch (Exception e) {
                result = "Could not download " + file.name() + ": " + e.getMessage();
                danger = true;
            }
            String text = result;
            boolean isDanger = danger;
            Platform.runLater(() -> systemLine(text, isDanger));
        });
    }

    private static String mimeType(Path file) {
        try {
            String probed = Files.probeContentType(file);
            if (probed != null) return probed;
        } catch (IOException ignored) {
            // fall back to the extension
        }
        String n = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    void systemLine(String text, boolean danger) {
        Label line = UiKit.label(text, "system-line");
        if (danger) line.getStyleClass().add("danger");
        line.setWrapText(true);
        line.setMaxWidth(420);
        HBox row = new HBox(line);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(12, 0, 4, 0));
        messages.getChildren().add(row);
        lastSender = null;
    }

    Node focusTarget() {
        return input;
    }
}
