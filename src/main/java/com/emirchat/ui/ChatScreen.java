package com.emirchat.ui;

import com.emirchat.chat.ChatMessage;
import com.emirchat.chat.Conversation;
import com.emirchat.chat.TerminalSanitizer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/** The conversation itself. */
final class ChatScreen {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    /** Messages from the same side within this window are visually grouped. */
    private static final long GROUP_WINDOW_MS = 2 * 60_000;
    private static final int COUNTER_THRESHOLD = ChatMessage.MAX_TEXT_BYTES - 400;

    private final Conversation conversation;
    private final String peerName;
    private final Runnable onExit;
    private final BorderPane root = new BorderPane();
    private final VBox messages = new VBox();
    private final ScrollPane scroller = new ScrollPane(messages);
    private final VBox bottom = new VBox();
    private Button leaveButton;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chat-sender");
        t.setDaemon(true);
        return t;
    });

    private Boolean lastWasOwn;
    private long lastAt;
    private HBox lastRow;

    ChatScreen(Conversation conversation, String myFingerprint, Runnable onExit) {
        this.conversation = conversation;
        this.peerName = conversation.session().peerName();
        this.onExit = onExit;
        String peerFingerprint = conversation.session().peerFingerprint();

        // Header -------------------------------------------------------------
        StackPane avatar = new StackPane(new Label(peerName.substring(0, 1).toUpperCase()));
        avatar.getStyleClass().add("avatar");
        avatar.getChildren().get(0).getStyleClass().add("avatar-letter");
        // Inline style: it must win over the stylesheet's .avatar rules.
        avatar.setStyle("-fx-background-color: " + css(Color.hsb(FingerprintArt.avatarHue(peerFingerprint), 0.55, 0.72)) + ";");

        Label name = new Label(peerName);
        name.getStyleClass().add("h2");
        name.setId("peer-name");
        Label badgeText = new Label(I18n.t("chat.verifiedBadge"));
        Node chevron = Icons.of(Icons.CHEVRON_DOWN, 13, "accent");
        HBox badge = new HBox(Icons.of(Icons.SHIELD_CHECK, 13, "accent"), badgeText, chevron);
        badge.getStyleClass().add("secure-badge");
        badge.setAlignment(Pos.CENTER_LEFT);
        badge.setId("secure-badge");
        Tooltip.install(badge, new Tooltip(I18n.t("chat.detailsTip")));
        VBox titles = new VBox(2, name, badge);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button leave = new Button(I18n.t("chat.leave"));
        leave.setId("leave");
        leave.setGraphic(Icons.of(Icons.LOG_OUT, 14, null));
        leave.setOnAction(e -> sender.submit(conversation::leave));
        leaveButton = leave;
        HBox header = new HBox(avatar, titles, spacer, leave);
        header.getStyleClass().add("chat-header");
        header.setAlignment(Pos.CENTER_LEFT);

        // Security details (collapsed by default) ----------------------------
        VBox details = new VBox(12,
                detail(I18n.t("chat.theirKey", peerName.toUpperCase(I18n.locale())), FingerprintView.compact(peerFingerprint)),
                detail(I18n.t("chat.yourKey"), FingerprintView.compact(myFingerprint)),
                detail(I18n.t("chat.protocol"), caption(I18n.t("chat.protocolText"))));
        details.getStyleClass().add("details");
        details.setVisible(false);
        details.managedProperty().bind(details.visibleProperty());
        details.setId("details");
        badge.setOnMouseClicked(e -> {
            details.setVisible(!details.isVisible());
            badge.getChildren().set(2, Icons.of(details.isVisible() ? Icons.CHEVRON_UP : Icons.CHEVRON_DOWN, 13, "accent"));
        });

        // Messages -----------------------------------------------------------
        messages.getStyleClass().add("messages");
        messages.setFillWidth(true);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        messages.heightProperty().addListener((obs, o, n) -> scroller.setVvalue(1.0));
        addSystem(I18n.t("chat.started", peerName), false);

        // Composer -----------------------------------------------------------
        TextField input = new TextField();
        input.setId("composer");
        input.setPromptText(I18n.t("conv.prompt", peerName));
        HBox.setHgrow(input, Priority.ALWAYS);
        Button send = new Button();
        send.setId("send");
        send.getStyleClass().add("send");
        send.setGraphic(Icons.of(Icons.SEND, 17, "on-accent"));
        send.setTooltip(new Tooltip(I18n.t("conv.sendTip")));
        Label counter = new Label();
        counter.getStyleClass().add("caption");
        counter.managedProperty().bind(counter.visibleProperty());
        counter.setVisible(false);
        input.textProperty().addListener((obs, o, text) -> {
            int bytes = text.getBytes(StandardCharsets.UTF_8).length;
            counter.setVisible(bytes > COUNTER_THRESHOLD);
            counter.setText(bytes + " / " + ChatMessage.MAX_TEXT_BYTES);
            counter.getStyleClass().remove("danger-text");
            if (bytes > ChatMessage.MAX_TEXT_BYTES) counter.getStyleClass().add("danger-text");
            send.setDisable(text.isBlank() || bytes > ChatMessage.MAX_TEXT_BYTES);
        });
        send.setDisable(true);
        Runnable doSend = () -> {
            String text = input.getText();
            if (text.isBlank() || send.isDisabled()) return;
            input.clear();
            long now = System.currentTimeMillis();
            addBubble(text, true, now);
            sender.submit(() -> {
                try {
                    conversation.send(text);
                } catch (Exception ex) {
                    Platform.runLater(() -> addSystem(I18n.t("chat.sendFailed"), true));
                }
            });
        };
        input.setOnAction(e -> doSend.run());
        send.setOnAction(e -> doSend.run());
        HBox composer = new HBox(input, counter, send);
        composer.getStyleClass().add("composer");
        composer.setAlignment(Pos.CENTER);
        bottom.getChildren().add(composer);

        root.setTop(new VBox(header, details));
        root.setCenter(scroller);
        root.setBottom(bottom);
        Platform.runLater(input::requestFocus);

        conversation.start(new Conversation.Listener() {
            @Override
            public void onMessage(ChatMessage message) {
                Platform.runLater(() -> addBubble(TerminalSanitizer.sanitize(message.text()), false, message.sentAtMillis()));
            }

            @Override
            public void onEnded(Conversation.EndReason reason, String detail) {
                Platform.runLater(() -> ended(reason, detail));
            }
        });
    }

    Parent root() {
        return root;
    }

    /** Leaves the conversation without user interaction (window closing). */
    void shutdown() {
        conversation.leave();
        sender.shutdownNow();
    }

    private void addBubble(String text, boolean own, long at) {
        boolean continued = lastWasOwn != null && lastWasOwn == own && at - lastAt < GROUP_WINDOW_MS && lastRow != null;
        Label body = new Label(text);
        body.getStyleClass().add("bubble-text");
        body.setWrapText(true);
        Label time = new Label(TIME.format(Instant.ofEpochMilli(at)));
        time.getStyleClass().add("bubble-time");
        HBox timeRow = new HBox(time);
        timeRow.setAlignment(Pos.CENTER_RIGHT);
        VBox bubble = new VBox(body, timeRow);
        bubble.getStyleClass().addAll("bubble", own ? "own" : "peer");
        if (continued) bubble.getStyleClass().add("continued");
        bubble.maxWidthProperty().bind(messages.widthProperty().multiply(0.74));
        bubble.setMinWidth(Region.USE_PREF_SIZE);
        bubble.setMinHeight(Region.USE_PREF_SIZE);

        HBox row = new HBox(bubble);
        row.setAlignment(own ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        row.setPadding(new Insets(continued ? 3 : 12, 0, 0, 0));
        row.getStyleClass().add(own ? "row-own" : "row-peer");
        messages.getChildren().add(row);
        lastWasOwn = own;
        lastAt = at;
        lastRow = row;
    }

    private void addSystem(String text, boolean danger) {
        Label line = new Label(text);
        line.getStyleClass().add("system-line");
        if (danger) line.getStyleClass().add("danger");
        line.setWrapText(true);
        line.setStyle("-fx-text-alignment: center;");
        line.setMaxWidth(380);
        HBox row = new HBox(line);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(14, 0, 4, 0));
        messages.getChildren().add(row);
        lastWasOwn = null;
        lastRow = null;
    }

    private void ended(Conversation.EndReason reason, String detail) {
        sender.shutdown();
        String text = switch (reason) {
            case LOCAL_LEFT -> I18n.t("chat.youLeft");
            case PEER_LEFT -> I18n.t("chat.peerLeft", peerName);
            case CONNECTION_LOST -> I18n.t("chat.lost", peerName);
            case SECURITY_ERROR -> I18n.t("chat.integrity", detail);
        };
        if (reason == Conversation.EndReason.LOCAL_LEFT) {
            onExit.run();
            return;
        }
        addSystem(text, reason == Conversation.EndReason.SECURITY_ERROR);
        leaveButton.setDisable(true);
        Label endedLabel = new Label(I18n.t("chat.ended"));
        endedLabel.getStyleClass().add("body");
        endedLabel.setWrapText(true);
        endedLabel.setId("ended");
        HBox.setHgrow(endedLabel, Priority.ALWAYS);
        endedLabel.setMaxWidth(Double.MAX_VALUE);
        Button again = new Button(I18n.t("chat.again"));
        again.setId("new-chat");
        again.getStyleClass().add("primary");
        again.setDefaultButton(true);
        again.setOnAction(e -> onExit.run());
        HBox bar = new HBox(endedLabel, again);
        bar.getStyleClass().add("ended-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bottom.getChildren().setAll(bar);
        again.requestFocus();
    }

    private static Node detail(String label, Node content) {
        Label l = new Label(label);
        l.getStyleClass().add("overline");
        return new VBox(6, l, content);
    }

    private static String css(Color c) {
        return String.format("#%02x%02x%02x", (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
    }

    private static Label caption(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("caption");
        l.setWrapText(true);
        return l;
    }
}
