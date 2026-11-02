package com.emirchat.ui;

import com.emirchat.api.Packet;
import com.emirchat.chat.ChatMessage;
import com.emirchat.client.ChatClient;
import com.emirchat.client.History;
import com.emirchat.trust.KeyChangedException;
import java.io.IOException;
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
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

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
    private final TextField input = new TextField();
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
        HBox composer = new HBox(input, send);
        composer.getStyleClass().add("composer");
        composer.setAlignment(Pos.CENTER);

        root.setTop(top);
        root.setCenter(scroller);
        root.setBottom(composer);
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
            Label status = UiKit.label(client.isOnline(peer) ? "online" : "offline", "row-sub");
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
            titles.getChildren().addAll(UiKit.label(name, "h2"), members);
        }
        header.getChildren().addAll(titles, UiKit.grow());
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
            for (History.Message m : list) append(m);
            session.history().markRead(conversation);
        } catch (IOException e) {
            messages.getChildren().add(UiKit.label("Could not load history: " + e.getMessage(), "system-line", "danger"));
        }
    }

    void append(History.Message m) {
        boolean own = m.outgoing();
        boolean continued = m.sender().equals(lastSender) && m.sentAt() - lastAt < GROUP_WINDOW_MS;
        VBox bubble = new VBox(2);
        bubble.getStyleClass().addAll("bubble", own ? "own" : "peer");
        if (continued) bubble.getStyleClass().add("continued");
        Label body = UiKit.label(m.text(), "bubble-text");
        body.setWrapText(true);
        HBox meta = new HBox(5, UiKit.label(UiKit.clock(m.sentAt()), "bubble-time"));
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

        VBox column = new VBox(bubble);
        column.setAlignment(own ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        if (groupId != 0 && !own && !continued) {
            Label name = UiKit.label(m.sender(), "sender-name");
            name.setStyle("-fx-text-fill: " + UiKit.css(UiKit.colorFor(m.sender())) + ";");
            column.getChildren().add(0, name);
        }
        HBox row = new HBox(column);
        row.setAlignment(own ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        row.setPadding(new Insets(continued ? 3 : 12, 0, 0, 0));
        messages.getChildren().add(row);
        lastSender = m.sender();
        lastAt = m.sentAt();
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
            case History.DELIVERED -> l.setText("✓✓");
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
        input.clear();
        String me = session.client().username();
        long now = System.currentTimeMillis();
        long localId;
        try {
            localId = session.history().add(conversation, me, text, now, true, History.PENDING, 0);
        } catch (IOException e) {
            return;
        }
        append(new History.Message(localId, conversation, me, text, now, true, History.PENDING, 0));
        session.background().execute(() -> {
            int status;
            String problem = null;
            try {
                if (peer != null) {
                    long envelope = session.client().sendText(peer, text).id();
                    status = session.sent(localId, envelope);
                } else {
                    List<String> skipped = session.client().sendGroupText(groupId, text);
                    status = session.sent(localId, 0);
                    if (!skipped.isEmpty()) problem = "Not sent to " + String.join(", ", skipped) + " until their new key is verified.";
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
