package com.emirchat.ui;

import com.emirchat.api.Packet;
import com.emirchat.client.ChatClient;
import com.emirchat.trust.KeyChangedException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The bar above a conversation: who or what it is, presence and typing, the topic, the
 * disappearing-message timer and the buttons for calls, voice rooms, pins and members.
 */
final class ConversationHeader {
    private final ConversationView chat;

    ConversationHeader(ConversationView chat) {
        this.chat = chat;
    }

    void refreshHeader() {
        ChatClient client = chat.session.client();
        HBox header = new HBox(12);
        header.getStyleClass().add("chat-header");
        header.setAlignment(Pos.CENTER_LEFT);
        VBox titles = new VBox(2);
        if (chat.peer != null) {
            chat.input.setPromptText(I18n.t("conv.prompt", chat.peer));
            header.getChildren().add(UiKit.avatarWithPresence(chat.peer, client.isOnline(chat.peer)));
            chat.presence.getStyleClass().setAll("label", "row-sub");
            refreshPresence();
            Label status = chat.presence;
            Label badge = client.isVerified(chat.peer) && !client.keyChanged(chat.peer)
                    ? UiKit.label("✓ " + I18n.t("conv.verified"), "badge-verified")
                    : UiKit.label(I18n.t("conv.notVerified"), "badge-unverified");
            badge.setId("verify-badge");
            A11y.clickable(badge, null);
            badge.setOnMouseClicked(e -> chat.actions.openContact(chat.peer));
            titles.getChildren().addAll(UiKit.label(chat.peer, "h2"), new HBox(8, status, UiKit.label("·", "row-sub"), badge));
        } else {
            Packet.GroupInfo g = chat.group();
            boolean inCommunity = g != null && g.community() != 0;
            Packet.GroupInfo root = inCommunity ? chat.session.client().groups().stream()
                    .filter(x -> x.id() == g.community()).findFirst().orElse(null) : null;
            String name = g == null ? I18n.t("conv.group") : inCommunity ? ConversationView.channelTitle(g) : ConversationView.groupName(g);
            chat.input.setPromptText(I18n.t("conv.prompt", name));
            header.getChildren().add(UiKit.avatar(root != null ? ConversationView.groupName(root) : name, "avatar-sm", true));
            Label members = UiKit.label(g == null ? "" : inCommunity && root != null
                    ? I18n.t("community.headerSub", ConversationView.groupName(root), g.members().size())
                    : I18n.t("conv.members", g.members().size()), "badge-verified");
            members.setId("group-info-link");
            members.setStyle("-fx-cursor: hand;");
            A11y.clickable(members, null);
            members.setOnMouseClicked(e -> chat.actions.openGroupInfo(chat.groupId));
            chat.presence.getStyleClass().setAll("label", "row-sub", "typing");
            refreshPresence();
            titles.getChildren().addAll(UiKit.label(name, "h2"), new HBox(8, members, chat.presence));
        }
        Node topicLine = topicLine();
        if (topicLine != null) titles.getChildren().add(topicLine);
        titles.setMinWidth(0);
        HBox.setHgrow(titles, Priority.SOMETIMES);
        header.getChildren().addAll(titles, UiKit.grow());
        int pinCount = chat.side.pinnedMessages().size();
        if (pinCount > 0 || mayModerate() && chat.groupId != 0) {
            Button pins = new Button(pinCount > 0 ? String.valueOf(pinCount) : "");
            pins.setGraphic(Icons.of(Icons.PIN, 15, pinCount > 0 ? "accent" : "muted"));
            pins.getStyleClass().addAll("small", "timer-button");
            if ("pins".equals(chat.side.sideMode)) pins.getStyleClass().add("active");
            pins.setTooltip(new Tooltip(I18n.t("pin.title")));
            pins.setId("pins-button");
            pins.setOnAction(e -> chat.side.toggleSide("pins", chat.side::showPins));
            header.getChildren().add(pins);
        }
        if (chat.groupId != 0 && !chat.actions.inVoice(chat.groupId) && chat.actions.voiceAvailable()) {
            int inRoom = chat.actions.voiceCount(chat.groupId);
            Button voice = new Button(inRoom > 0 ? I18n.t("voice.joinCount", inRoom) : I18n.t("voice.join"));
            voice.setGraphic(Icons.of(Icons.HEADPHONES, 15, "accent"));
            voice.getStyleClass().addAll("small", "timer-button", "voice-join");
            voice.setTooltip(new Tooltip(I18n.t("voice.joinTip")));
            voice.setId("voice-join");
            voice.setOnAction(e -> chat.actions.joinVoice(chat.groupId));
            header.getChildren().add(voice);
        }
        if (chat.groupId != 0) {
            Button people = new Button();
            people.setGraphic(Icons.of(Icons.USERS, 15, "muted"));
            people.getStyleClass().addAll("small", "timer-button");
            if ("members".equals(chat.side.sideMode)) people.getStyleClass().add("active");
            people.setTooltip(new Tooltip(I18n.t("members.title")));
            people.setId("members-button");
            people.setOnAction(e -> chat.side.toggleSide("members", chat.side::showMembers));
            header.getChildren().add(people);
        }
        java.time.ZoneId theirZone = chat.peer != null ? chat.session.clock(chat.peer) : null;
        Sky sky = theirZone != null ? new Sky(theirZone, Theme.isDark()) : null;
        if (sky != null) {
            Label chip = UiKit.label(sky.label(), "sky-chip");
            chip.setId("sky-chip");
            Tooltip.install(chip, new Tooltip(I18n.t("sky.theirTime", chat.peer, Sky.city(theirZone))));
            header.getChildren().add(chip);
        }
        Button media = new Button();
        media.setGraphic(Icons.of(Icons.IMAGE, 15, "muted"));
        media.getStyleClass().addAll("small", "timer-button");
        if ("media".equals(chat.side.sideMode)) media.getStyleClass().add("active");
        media.setTooltip(new Tooltip(I18n.t("media.title")));
        media.setId("media-button");
        media.setOnAction(e -> chat.side.toggleSide("media", () -> chat.side.showMedia(chat.side.mediaTab)));
        header.getChildren().add(media);
        header.getChildren().add(bellButton());
        header.getChildren().add(timerButton());
        if (chat.peer != null) {
            Button call = new Button();
            call.setGraphic(Icons.of(Icons.PHONE, 15, "accent"));
            call.getStyleClass().addAll("small", "timer-button");
            call.setTooltip(new Tooltip(I18n.t("call.start", chat.peer)));
            call.setId("call-button");
            call.setOnAction(e -> chat.actions.call(chat.peer));
            header.getChildren().add(call);
        }
        if (chat.peer == null) {
            Packet.GroupInfo shown = chat.group();
            Button info = new Button(shown != null && shown.community() != 0 ? I18n.t("community.settings") : I18n.t("conv.groupInfo"));
            info.getStyleClass().add("small");
            info.setOnAction(e -> chat.actions.openGroupInfo(chat.groupId));
            header.getChildren().add(info);
        }
        if (sky != null) {
            // their sky behind the header (it fades out under the name on the left)
            header.getStyleClass().add("on-sky");
            StackPane withSky = new StackPane(sky, header);
            withSky.getStyleClass().add("sky-header");
            chat.top.getChildren().setAll(withSky);
        } else {
            chat.top.getChildren().setAll(header);
        }
        if (chat.peer != null && client.keyChanged(chat.peer)) {
            Button verify = new Button(I18n.t("conv.verify"));
            verify.getStyleClass().addAll("small", "danger-ghost");
            verify.setId("banner-verify");
            verify.setOnAction(e -> chat.actions.openContact(chat.peer));
            Label text = UiKit.label(I18n.t("conv.keyBanner", chat.peer));
            text.setWrapText(true);
            HBox banner = new HBox(Icons.of(Icons.ALERT, 16, "danger"), text, UiKit.grow(), verify);
            banner.getStyleClass().add("banner-danger");
            banner.setAlignment(Pos.CENTER_LEFT);
            banner.setId("key-banner");
            chat.top.getChildren().add(banner);
        }
    }

    /** The topic under the title; managers can click it (or "Add a topic") to change it. */
    private Node topicLine() {
        boolean manager = chat.groupId != 0 && mayModerate();
        if (chat.topic.isEmpty() && !manager) return null;
        Label line = UiKit.label(chat.topic.isEmpty() ? "+ " + I18n.t("topic.add") : chat.topic, "topic-line");
        if (chat.topic.isEmpty()) line.getStyleClass().add("empty");
        line.setId("topic");
        line.setMinWidth(0);
        if (!chat.topic.isEmpty()) Tooltip.install(line, new Tooltip(chat.topic));
        if (manager) {
            line.setStyle("-fx-cursor: hand;");
            A11y.clickable(line, null);
            line.setOnMouseClicked(e -> editTopic());
        }
        return line;
    }

    /** Notifications for this chat: everything, mentions only or nothing, and muting for a while. */
    private Node bellButton() {
        NotifyRules rules = chat.session.notifyRules();
        long now = System.currentTimeMillis();
        boolean muted = rules.muted(chat.conversation, now);
        NotifyRules.Level level = rules.level(chat.conversation);
        MenuButton button = new MenuButton();
        button.setId("bell");
        button.getStyleClass().addAll("small", "timer-button");
        if (muted || level != NotifyRules.Level.ALL) button.getStyleClass().add("active");
        button.setGraphic(Icons.of(muted ? Icons.BELL_OFF : Icons.BELL, 15, muted ? "muted" : "accent"));
        long until = rules.mutedUntil(chat.conversation);
        button.setTooltip(new Tooltip(until > now ? (until == NotifyRules.FOREVER ? I18n.t("notify.mutedForever")
                : I18n.t("notify.mutedUntil", UiKit.time(until))) : levelText(level)));
        for (NotifyRules.Level l : NotifyRules.Level.values()) {
            if (l == NotifyRules.Level.MENTIONS && chat.peer != null) continue; // in a direct chat every message is for you
            MenuItem item = new MenuItem((l == level ? "✓ " : "   ") + levelText(l));
            item.setId("notify-" + l.name().toLowerCase(java.util.Locale.ROOT));
            item.setOnAction(e -> {
                rules.setLevel(chat.conversation, l);
                notifyChanged();
            });
            button.getItems().add(item);
        }
        button.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        int[][] choices = {{60, 0}, {8 * 60, 1}, {-2, 2}, {-1, 3}};
        String[] keys = {"notify.mute1h", "notify.mute8h", "notify.muteMorning", "notify.muteForever"};
        for (int i = 0; i < choices.length; i++) {
            int choice = choices[i][0];
            MenuItem item = new MenuItem("   " + I18n.t(keys[i]));
            item.setId("mute-" + i);
            item.setOnAction(e -> {
                rules.muteUntil(chat.conversation, NotifyRules.until(choice, NotifyRules.now()));
                notifyChanged();
            });
            button.getItems().add(item);
        }
        if (until > now) {
            MenuItem unmute = new MenuItem("   " + I18n.t("notify.unmute"));
            unmute.setId("unmute");
            unmute.setOnAction(e -> {
                rules.muteUntil(chat.conversation, 0);
                notifyChanged();
            });
            button.getItems().add(unmute);
        }
        return button;
    }

    static String levelText(NotifyRules.Level level) {
        return switch (level) {
            case ALL -> I18n.t("notify.level.all");
            case MENTIONS -> I18n.t("notify.level.mentions");
            case NONE -> I18n.t("notify.level.none");
        };
    }

    private void notifyChanged() {
        chat.refreshHeader();
        chat.actions.sidebarChanged();
    }

    /** Shows the disappearing-message timer and lets the user change it for everyone in the chat. */
    private Node timerButton() {
        long current = chat.session.timer(chat.conversation);
        MenuButton button = new MenuButton(current > 0 ? AccountSession.timerLabel(current) : "");
        button.setId("timer");
        button.getStyleClass().addAll("small", "timer-button");
        if (current > 0) button.getStyleClass().add("active");
        button.setGraphic(Icons.of(Icons.CLOCK, 15, current > 0 ? "accent" : "muted"));
        button.setTooltip(new Tooltip(current > 0
                ? I18n.t("conv.timerOnTip", AccountSession.timerLabel(current))
                : I18n.t("conv.timerOffTip")));
        for (long seconds : AccountSession.TIMER_CHOICES) {
            MenuItem item = new MenuItem((seconds == current ? "✓ " : "   ") + AccountSession.timerLabel(seconds));
            item.setId("timer-" + seconds);
            item.setOnAction(e -> setTimer(seconds));
            button.getItems().add(item);
        }
        return button;
    }

    private void setTimer(long seconds) {
        if (seconds == chat.session.timer(chat.conversation)) return;
        chat.session.background().execute(() -> {
            String problem = null;
            try {
                if (chat.peer != null) {
                    chat.session.client().setTimer(chat.peer, seconds);
                } else {
                    List<String> skipped = chat.session.client().setGroupTimer(chat.groupId, seconds);
                    if (!skipped.isEmpty()) problem = I18n.t("conv.notChangedFor", String.join(", ", skipped));
                }
                chat.session.history().setTimer(chat.conversation, seconds);
            } catch (KeyChangedException e) {
                problem = I18n.t("conv.keyChangedFirst", chat.peer);
            } catch (Exception e) {
                problem = I18n.t("session.timerFailed", e.getMessage());
            }
            String finalProblem = problem;
            Platform.runLater(() -> {
                if (finalProblem != null) chat.systemLine(finalProblem, true);
                chat.timerChanged(chat.session.client().username(), chat.session.timer(chat.conversation));
            });
        });
    }

    /** "online"/"offline" for a friend, or "… is typing" while someone types. */
    void refreshPresence() {
        if (chat.typingWho != null) {
            chat.presence.setText(chat.peer != null ? I18n.t("conv.typing") : I18n.t("conv.typingWho", chat.typingWho));
            if (!chat.presence.getStyleClass().contains("typing")) chat.presence.getStyleClass().add("typing");
        } else {
            chat.presence.getStyleClass().remove("typing");
            chat.presence.setText(chat.peer != null ? (chat.session.client().isOnline(chat.peer) ? I18n.t("conv.online") : I18n.t("conv.offline")) : "");
        }
    }

    boolean mayModerate() {
        return chat.session.mayModerate(chat.groupId, chat.session.client().username());
    }

    void setTopic(String text) {
        String topicText = text.strip();
        if (!mayModerate()) {
            chat.systemLine(I18n.t("topic.notAllowed"), true);
            return;
        }
        if (topicText.getBytes(StandardCharsets.UTF_8).length > AccountSession.MAX_TOPIC_BYTES) {
            chat.systemLine(I18n.t("topic.tooLong"), true);
            return;
        }
        try {
            chat.session.history().setTopic(chat.conversation, topicText);
        } catch (IOException e) {
            return;
        }
        chat.topicChanged(chat.session.client().username(), topicText);
        chat.sender.sendAction(ChatClient.ActionKind.TOPIC, null, topicText);
    }

    /** A small editor for the topic, over the conversation. */
    private void editTopic() {
        TextField field = new TextField(chat.topic);
        field.setId("topic-field");
        field.setPromptText(I18n.t("topic.prompt"));
        Button cancel = new Button(I18n.t("common.cancel"));
        Button save = new Button(I18n.t("topic.save"));
        save.getStyleClass().add("primary");
        save.setId("topic-save");
        VBox card = new VBox(12, UiKit.label(I18n.t("topic.title"), "h2"), field, new HBox(10, UiKit.grow(), cancel, save));
        card.getStyleClass().add("dialog");
        StackPane veil = new StackPane(card);
        veil.getStyleClass().add("overlay");
        Runnable close = () -> chat.layer.getChildren().remove(veil);
        cancel.setOnAction(e -> close.run());
        save.setOnAction(e -> {
            close.run();
            if (!field.getText().strip().equals(chat.topic)) setTopic(field.getText());
        });
        field.setOnAction(e -> save.fire());
        field.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) close.run();
        });
        chat.layer.getChildren().add(veil);
        Platform.runLater(field::requestFocus);
    }
}
