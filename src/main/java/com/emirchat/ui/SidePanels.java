package com.emirchat.ui;

import com.emirchat.api.Packet;
import com.emirchat.chat.ChatMessage;
import com.emirchat.client.ChatClient;
import com.emirchat.client.History;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The panel beside a conversation: pinned messages, the member list, or one thread with its
 * replies.
 */
final class SidePanels {
    private final ConversationView chat;

    SidePanels(ConversationView chat) {
        this.chat = chat;
    }

    String sideMode;              // "members", "pins", "thread" or null
    History.Message threadRoot;   // the thread shown in the side panel
    void pin(History.Message m, boolean on) {
        try {
            if (chat.session.history().pin(chat.conversation, m.uid(), chat.session.client().username(), on) < 0) return;
        } catch (IOException e) {
            return;
        }
        chat.sender.sendAction(ChatClient.ActionKind.PIN, m.uid(), on ? "1" : "0");
        chat.systemLine(on ? I18n.t("pin.pinnedYou") : I18n.t("pin.unpinnedYou"), false);
        chat.pinsChanged();
    }

    List<History.Message> pinnedMessages() {
        try {
            return chat.session.history().pinned(chat.conversation);
        } catch (IOException e) {
            return List.of();
        }
    }

    void showPins() {
        VBox list = new VBox(6);
        boolean manager = chat.header.mayModerate();
        List<History.Message> pinned = pinnedMessages();
        if (pinned.isEmpty()) list.getChildren().add(wrapLabel(I18n.t("pin.none"), "caption"));
        for (History.Message m : pinned) {
            Label who = UiKit.label(m.outgoing() ? I18n.t("conv.you") : m.sender(), "row-title");
            Label text = wrapLabel(previewOf(m), "row-sub");
            text.setMaxHeight(40);
            HBox top = new HBox(6, who, UiKit.grow(), UiKit.label(UiKit.time(m.sentAt()), "row-time"));
            top.setAlignment(Pos.CENTER_LEFT);
            VBox card = new VBox(3, top, text);
            if (manager) {
                Button unpin = new Button(I18n.t("pin.unpin"));
                unpin.getStyleClass().addAll("small", "link");
                unpin.setOnAction(e -> pin(m, false));
                card.getChildren().add(unpin);
            }
            card.getStyleClass().add("pin-card");
            A11y.clickable(card, null);
            card.setOnMouseClicked(e -> chat.reveal(m.id()));
            list.getChildren().add(card);
        }
        showSide("pins", I18n.t("pin.title"), list);
    }

    void showMembers() {
        Packet.GroupInfo g = chat.group();
        if (g == null) return;
        Packet.GroupInfo root = g.community() == 0 ? g : chat.session.client().groups().stream()
                .filter(x -> x.id() == g.community()).findFirst().orElse(g);
        String me = chat.session.client().username();
        ChatClient c = chat.session.client();
        List<String> owners = new java.util.ArrayList<>();
        List<String> admins = new java.util.ArrayList<>();
        List<String> others = new java.util.ArrayList<>();
        for (Packet.Member member : g.members()) {
            String u = member.username();
            if (u.equals(root.owner())) owners.add(u);
            else if (root.admins().contains(u)) admins.add(u);
            else others.add(u);
        }
        java.util.Comparator<String> order = java.util.Comparator.<String, Boolean>comparing(u -> !(u.equals(me) || c.isOnline(u)))
                .thenComparing(String.CASE_INSENSITIVE_ORDER);
        VBox list = new VBox(2);
        String[][] sections = {{I18n.t("members.owner"), "o"}, {I18n.t("members.admins"), "a"}, {I18n.t("members.members"), "m"}};
        List<List<String>> groups = List.of(owners, admins, others);
        for (int i = 0; i < 3; i++) {
            List<String> people = new java.util.ArrayList<>(groups.get(i));
            if (people.isEmpty()) continue;
            people.sort(order);
            list.getChildren().add(UiKit.label(sections[i][0].toUpperCase(I18n.locale()) + " — " + people.size(), "overline", "member-heading"));
            for (String u : people) {
                boolean friend = c.friends().friends().stream().anyMatch(f -> f.username().equals(u));
                boolean online = u.equals(me) || c.isOnline(u);
                Node face = friend || u.equals(me) ? UiKit.avatarWithPresence(u, online) : UiKit.avatar(u, "avatar-sm", false);
                Label name = UiKit.label(u.equals(me) ? I18n.t("group.you", u) : u, "row-title");
                Label sub = UiKit.label(friend || u.equals(me) ? (online ? I18n.t("conv.online") : I18n.t("conv.offline")) : I18n.t("members.notFriend"), "row-sub");
                VBox text = new VBox(1, name, sub);
                HBox row = new HBox(10, face, text);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().addAll("list-row", "member-row");
                if (!online) row.getStyleClass().add("offline");
                row.setId("member-" + u);
                if (!u.equals(me)) A11y.clickable(row, null);
                if (!u.equals(me)) row.setOnMouseClicked(e -> chat.actions.openContact(u));
                list.getChildren().add(row);
            }
        }
        showSide("members", I18n.t("members.title"), list);
    }

    /** The thread panel: the first message, its replies, and a composer that answers in the thread. */
    void openThread(History.Message rootMessage) {
        threadRoot = rootMessage;
        VBox list = new VBox(8);
        VBox first = new VBox(3, UiKit.label(rootMessage.outgoing() ? I18n.t("conv.you") : rootMessage.sender(), "row-title"),
                wrapLabel(previewOf(rootMessage), "body"), UiKit.label(UiKit.time(rootMessage.sentAt()).equals(UiKit.clock(rootMessage.sentAt()))
                ? UiKit.clock(rootMessage.sentAt()) : UiKit.time(rootMessage.sentAt()) + " " + UiKit.clock(rootMessage.sentAt()), "row-time"));
        first.getStyleClass().add("thread-root");
        list.getChildren().add(first);
        List<History.Message> replies = chat.threads.getOrDefault(ConversationView.hex(rootMessage.uid()), List.of());
        list.getChildren().add(UiKit.label(replies.isEmpty() ? I18n.t("thread.empty")
                : replies.size() == 1 ? I18n.t("thread.oneReply") : I18n.t("thread.replies", replies.size()), "overline", "member-heading"));
        for (History.Message r : replies) {
            Node face = UiKit.avatar(r.sender(), "avatar-xs", false);
            Label name = UiKit.label(r.outgoing() ? I18n.t("conv.you") : r.sender(), "row-title");
            Label when = UiKit.label(UiKit.clock(r.sentAt()), "row-time");
            HBox top = new HBox(6, name, when);
            top.setAlignment(Pos.BASELINE_LEFT);
            Node body = Markdown.hasFormatting(r.text()) ? chat.bubbles.richText(r.text()) : wrapLabel(r.text(), "thread-text");
            VBox text = new VBox(2, top, body);
            text.getStyleClass().addAll("bubble", r.outgoing() ? "own" : "peer", "thread-reply");
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(8, face, text);
            row.setAlignment(Pos.TOP_LEFT);
            list.getChildren().add(row);
        }
        TextField answer = new TextField();
        answer.setId("thread-input");
        answer.setPromptText(I18n.t("thread.prompt"));
        answer.getStyleClass().add("search");
        HBox.setHgrow(answer, Priority.ALWAYS);
        Button go = new Button();
        go.setId("thread-send");
        go.getStyleClass().add("send");
        go.setGraphic(Icons.of(Icons.SEND, 15, "on-accent"));
        go.disableProperty().bind(answer.textProperty().isEmpty());
        Runnable send = () -> {
            String text = answer.getText().strip();
            if (text.isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > ChatMessage.MAX_TEXT_BYTES) return;
            sendThreadReply(rootMessage, text);
        };
        answer.setOnAction(e -> send.run());
        go.setOnAction(e -> send.run());
        HBox composerRow = new HBox(8, answer, go);
        composerRow.setAlignment(Pos.CENTER);
        composerRow.getStyleClass().add("thread-composer");
        showSide("thread", I18n.t("thread.title"), list);
        if (chat.root.getRight() instanceof BorderPane panel) panel.setBottom(composerRow);
        Platform.runLater(answer::requestFocus);
    }

    private void sendThreadReply(History.Message rootMessage, String text) {
        String me = chat.session.client().username();
        byte[] uid = ChatClient.newMessageId();
        long now = System.currentTimeMillis();
        long expire = chat.session.timer(chat.conversation);
        long localId;
        History.Message stored;
        try {
            localId = chat.session.history().add(chat.conversation, me, text, now, true, History.PENDING, 0,
                    AccountSession.expiresAt(now, expire), uid, rootMessage.uid());
            chat.session.history().markThread(localId);
            stored = chat.session.history().get(localId);
        } catch (IOException e) {
            return;
        }
        chat.threadReply(stored, rootMessage.id());
        chat.sender.deliver(localId, () -> chat.session.client().sendThreadReply(chat.chat(), rootMessage.uid(), text, expire, uid));
    }

    /** One panel at a time at the right: members, pins or a thread. Clicking its button again closes it. */
    private void showSide(String mode, String title, Node content) {
        BorderPane panel = new BorderPane();
        panel.getStyleClass().add("side-panel");
        panel.setId("side-" + mode);
        Button close = new Button();
        close.getStyleClass().add("icon-button");
        close.setGraphic(Icons.of(Icons.X, 15, "muted"));
        close.setId("side-close");
        close.setOnAction(e -> closeSide());
        HBox head = new HBox(8, UiKit.label(title, "h2"), UiKit.grow(), close);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("side-head");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        if (content instanceof Region r) r.setPadding(new Insets(8, 12, 12, 12));
        panel.setTop(head);
        panel.setCenter(scroll);
        boolean opening = !mode.equals(sideMode);
        sideMode = mode;
        chat.root.setRight(panel);
        if (opening && Motion.enabled()) {
            panel.setTranslateX(40);
            panel.setOpacity(0);
            javafx.animation.TranslateTransition t = new javafx.animation.TranslateTransition(javafx.util.Duration.millis(160), panel);
            t.setToX(0);
            javafx.animation.FadeTransition f = new javafx.animation.FadeTransition(javafx.util.Duration.millis(160), panel);
            f.setToValue(1);
            new javafx.animation.ParallelTransition(t, f).play();
        }
    }

    void toggleSide(String mode, Runnable show) {
        if (mode.equals(sideMode)) closeSide();
        else show.run();
    }

    private void closeSide() {
        sideMode = null;
        threadRoot = null;
        chat.root.setRight(null);
        chat.input.requestFocus();
    }

    private static Label wrapLabel(String text, String style) {
        Label l = UiKit.label(text, style);
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }

    /** How a message reads in a list (pins, threads): files by name, polls by question. */
    private static String previewOf(History.Message m) {
        if (m.deleted()) return I18n.t("conv.deleted");
        if (m.file() != null) return (com.emirchat.call.VoiceNotes.isVoice(m.file().mimeType(), m.file().name()) ? "🎤 " + I18n.t("voice.message") : "📎 " + m.file().name());
        com.emirchat.chat.Poll poll = com.emirchat.chat.Poll.fromStored(m.text());
        if (poll != null) return "📊 " + poll.question();
        return Markdown.plain(m.text());
    }
}
