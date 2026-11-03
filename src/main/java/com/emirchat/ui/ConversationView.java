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
    /** Messages read from history at a time; older pages load when scrolling to the top. */
    static final int PAGE = 80;

    interface Actions {
        void openContact(String user);

        void openGroupInfo(long groupId);

        default void call(String friend) {}

        default void joinVoice(long groupId) {}

        default boolean inVoice(long groupId) { return false; }

        default int voiceCount(long groupId) { return 0; }
    }

    private final AccountSession session;
    private final String conversation;
    private final String peer;          // DM only
    private final long groupId;         // group only (0 for DMs)
    private final Actions actions;
    private final BorderPane root = new BorderPane();
    /** root plus floating layers: emoji suggestions, the emoji picker and the image viewer. */
    private final StackPane layer = new StackPane(root);
    private final VBox top = new VBox();
    private final VBox messages = new VBox();
    private final ScrollPane scroller = new ScrollPane(messages);
    private final Map<Long, Label> statusLabels = new HashMap<>();
    private final Map<Long, Node> rows = new HashMap<>();
    private final javafx.scene.control.TextArea input = new javafx.scene.control.TextArea();
    private final HBox composer = new HBox();
    private final EmojiSuggestions suggestions = new EmojiSuggestions();
    private Node picker;                 // the open emoji picker, or null
    /** Shown images in order, for the viewer's previous/next; decrypted pictures are kept here. */
    private final List<FileMessage> gallery = new java.util.ArrayList<>();
    private final Map<String, Image> images = new HashMap<>();
    private java.time.LocalDate lastDay;
    private long popId;                  // message whose reaction just changed (to animate it)
    private String popEmoji;
    private final Button micButton = new Button();
    private com.emirchat.call.VoiceRecorder recorder;   // while recording a voice message
    /** Decoded voice messages, the most recently used few only (each can be a few MB). */
    private final Map<String, byte[]> voices = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) { return size() > 12; }
    };
    private Runnable cancelRecording;    // discards the recording in progress, if any
    /** Thread replies by the hex id of their first message (they are shown in the thread panel). */
    private final Map<String, List<History.Message>> threads = new HashMap<>();
    private Map<Long, Map<String, Integer>> votes = new HashMap<>();
    private String topic = "";
    private String sideMode;              // "members", "pins", "thread" or null
    /** Everything read from history so far (oldest first), threads included. */
    private final List<History.Message> loaded = new java.util.ArrayList<>();
    private boolean hasOlder;
    private boolean loadingOlder;
    private boolean atBottom = true;
    /** Pictures waiting to be fetched until they scroll into view. */
    private final Map<Node, Runnable> whenVisible = new java.util.LinkedHashMap<>();
    private History.Message threadRoot;   // the thread shown in the side panel
    private final Button sendButton = new Button();
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
        // Follow new messages only while already at the bottom: reading older ones must not jump.
        // The view sticks to the bottom (new messages stay in sight) until the user scrolls up
        // themselves; scrolling back down to the end sticks it again.
        messages.heightProperty().addListener((o, a, b) -> {
            if (atBottom && !loadingOlder) scroller.setVvalue(1.0);
            Platform.runLater(this::checkVisible);
        });
        scroller.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (e.getDeltaY() > 0) atBottom = false;
        });
        scroller.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, e -> atBottom = false); // the scroll bar
        scroller.vvalueProperty().addListener((o, a, b) -> {
            if (b.doubleValue() >= 0.995) atBottom = true;
            if (b.doubleValue() <= 0.001 && hasOlder && !loadingOlder && !atBottom) loadOlder();
            checkVisible();
        });
        scroller.viewportBoundsProperty().addListener((o, a, b) -> checkVisible());

        input.setId("composer");
        input.setWrapText(true);
        input.setPrefRowCount(1);
        input.getStyleClass().add("composer-input");
        input.textProperty().addListener((o, a, b) -> resizeComposer());
        input.widthProperty().addListener((o, a, b) -> resizeComposer());
        HBox.setHgrow(input, Priority.ALWAYS);
        Button send = sendButton;
        send.setId("send");
        send.getStyleClass().add("send");
        send.setGraphic(Icons.of(Icons.SEND, 17, "on-accent"));
        send.setTooltip(new Tooltip(I18n.t("conv.sendTip")));
        send.disableProperty().bind(input.textProperty().isEmpty());
        send.setOnAction(e -> send());
        Button attach = new Button();
        attach.setId("attach");
        attach.getStyleClass().add("attach");
        attach.setGraphic(Icons.of(Icons.PAPERCLIP, 19, "muted"));
        attach.setTooltip(new Tooltip(I18n.t("conv.attachTip", Attachments.MAX_FILE_BYTES / (1024 * 1024))));
        attach.setOnAction(e -> chooseFile());
        Button emoji = new Button();
        emoji.setId("emoji-button");
        emoji.getStyleClass().add("attach");
        emoji.setGraphic(Icons.of(Icons.SMILE, 19, "muted"));
        emoji.setTooltip(new Tooltip(I18n.t("emoji.pickerTip")));
        emoji.setOnAction(e -> togglePicker(null));
        micButton.setId("mic");
        micButton.getStyleClass().add("send");
        micButton.setGraphic(Icons.of(Icons.MIC, 17, "on-accent"));
        micButton.setTooltip(new Tooltip(I18n.t("voice.recordTip")));
        micButton.setOnAction(e -> startRecording());
        // the microphone stands where the send button is while there is nothing to send
        micButton.visibleProperty().bind(input.textProperty().isEmpty());
        micButton.managedProperty().bind(micButton.visibleProperty());
        send.visibleProperty().bind(micButton.visibleProperty().not());
        send.managedProperty().bind(send.visibleProperty());
        composer.getChildren().addAll(attach, input, emoji, send, micButton);
        composer.getStyleClass().add("composer");
        composer.setAlignment(Pos.BOTTOM_CENTER);

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
        input.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, this::composerKey);
        input.caretPositionProperty().addListener((o, a, b) -> suggestions.update());
        input.textProperty().addListener((o, a, b) -> maybeSendTyping(b));
        typingTimeout.setOnFinished(e -> {
            typingWho = null;
            refreshPresence();
        });
        readDelay.setOnFinished(e -> sendReadReceipt());

        layer.getStyleClass().add("conversation-layer");
        layer.setUserData(this); // lets the preview tool reach the open conversation
        root.setTop(top);
        root.setCenter(scroller);
        root.setBottom(new VBox(contextBar, composer));
        layer.getChildren().add(suggestions.box);
        StackPane.setAlignment(suggestions.box, Pos.BOTTOM_LEFT);
        installDropTarget();
        layer.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (picker != null && !isInside(e.getTarget(), picker) && !isInside(e.getTarget(), composer)) closePicker();
        });
        refreshHeader();
        if (peer != null) {
            session.shareClock(peer);
            // the sky and the clock on it move on: redraw the header once a minute
            javafx.animation.Timeline minute = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                    javafx.util.Duration.seconds(60), e -> {
                        if (session.clock(peer) != null) refreshHeader();
                    }));
            minute.setCycleCount(javafx.animation.Animation.INDEFINITE);
            minute.play();
            layer.sceneProperty().addListener((o, a, scene) -> {
                if (scene == null) minute.stop();
            });
        }
        load();
        Platform.runLater(input::requestFocus);
    }

    Parent root() {
        return layer;
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
            input.setPromptText(I18n.t("conv.prompt", peer));
            header.getChildren().add(UiKit.avatarWithPresence(peer, client.isOnline(peer)));
            presence.getStyleClass().setAll("label", "row-sub");
            refreshPresence();
            Label status = presence;
            Label badge = client.isVerified(peer) && !client.keyChanged(peer)
                    ? UiKit.label("✓ " + I18n.t("conv.verified"), "badge-verified")
                    : UiKit.label(I18n.t("conv.notVerified"), "badge-unverified");
            badge.setId("verify-badge");
            badge.setOnMouseClicked(e -> actions.openContact(peer));
            titles.getChildren().addAll(UiKit.label(peer, "h2"), new HBox(8, status, UiKit.label("·", "row-sub"), badge));
        } else {
            Packet.GroupInfo g = group();
            boolean inCommunity = g != null && g.community() != 0;
            Packet.GroupInfo root = inCommunity ? session.client().groups().stream()
                    .filter(x -> x.id() == g.community()).findFirst().orElse(null) : null;
            String name = g == null ? I18n.t("conv.group") : inCommunity ? channelTitle(g) : groupName(g);
            input.setPromptText(I18n.t("conv.prompt", name));
            header.getChildren().add(UiKit.avatar(root != null ? groupName(root) : name, "avatar-sm", true));
            Label members = UiKit.label(g == null ? "" : inCommunity && root != null
                    ? I18n.t("community.headerSub", groupName(root), g.members().size())
                    : I18n.t("conv.members", g.members().size()), "badge-verified");
            members.setId("group-info-link");
            members.setStyle("-fx-cursor: hand;");
            members.setOnMouseClicked(e -> actions.openGroupInfo(groupId));
            presence.getStyleClass().setAll("label", "row-sub", "typing");
            refreshPresence();
            titles.getChildren().addAll(UiKit.label(name, "h2"), new HBox(8, members, presence));
        }
        Node topicLine = topicLine();
        if (topicLine != null) titles.getChildren().add(topicLine);
        titles.setMinWidth(0);
        HBox.setHgrow(titles, Priority.SOMETIMES);
        header.getChildren().addAll(titles, UiKit.grow());
        int pinCount = pinnedMessages().size();
        if (pinCount > 0 || mayModerate() && groupId != 0) {
            Button pins = new Button(pinCount > 0 ? String.valueOf(pinCount) : "");
            pins.setGraphic(Icons.of(Icons.PIN, 15, pinCount > 0 ? "accent" : "muted"));
            pins.getStyleClass().addAll("small", "timer-button");
            if ("pins".equals(sideMode)) pins.getStyleClass().add("active");
            pins.setTooltip(new Tooltip(I18n.t("pin.title")));
            pins.setId("pins-button");
            pins.setOnAction(e -> toggleSide("pins", this::showPins));
            header.getChildren().add(pins);
        }
        if (groupId != 0 && !actions.inVoice(groupId)) {
            int inRoom = actions.voiceCount(groupId);
            Button voice = new Button(inRoom > 0 ? I18n.t("voice.joinCount", inRoom) : I18n.t("voice.join"));
            voice.setGraphic(Icons.of(Icons.HEADPHONES, 15, "accent"));
            voice.getStyleClass().addAll("small", "timer-button", "voice-join");
            voice.setTooltip(new Tooltip(I18n.t("voice.joinTip")));
            voice.setId("voice-join");
            voice.setOnAction(e -> actions.joinVoice(groupId));
            header.getChildren().add(voice);
        }
        if (groupId != 0) {
            Button people = new Button();
            people.setGraphic(Icons.of(Icons.USERS, 15, "muted"));
            people.getStyleClass().addAll("small", "timer-button");
            if ("members".equals(sideMode)) people.getStyleClass().add("active");
            people.setTooltip(new Tooltip(I18n.t("members.title")));
            people.setId("members-button");
            people.setOnAction(e -> toggleSide("members", this::showMembers));
            header.getChildren().add(people);
        }
        java.time.ZoneId theirZone = peer != null ? session.clock(peer) : null;
        Sky sky = theirZone != null ? new Sky(theirZone, Theme.isDark()) : null;
        if (sky != null) {
            Label chip = UiKit.label(sky.label(), "sky-chip");
            chip.setId("sky-chip");
            Tooltip.install(chip, new Tooltip(I18n.t("sky.theirTime", peer, Sky.city(theirZone))));
            header.getChildren().add(chip);
        }
        header.getChildren().add(timerButton());
        if (peer != null) {
            Button call = new Button();
            call.setGraphic(Icons.of(Icons.PHONE, 15, "accent"));
            call.getStyleClass().addAll("small", "timer-button");
            call.setTooltip(new Tooltip(I18n.t("call.start", peer)));
            call.setId("call-button");
            call.setOnAction(e -> actions.call(peer));
            header.getChildren().add(call);
        }
        if (peer == null) {
            Packet.GroupInfo shown = group();
            Button info = new Button(shown != null && shown.community() != 0 ? I18n.t("community.settings") : I18n.t("conv.groupInfo"));
            info.getStyleClass().add("small");
            info.setOnAction(e -> actions.openGroupInfo(groupId));
            header.getChildren().add(info);
        }
        if (sky != null) {
            // their sky behind the header (it fades out under the name on the left)
            header.getStyleClass().add("on-sky");
            StackPane withSky = new StackPane(sky, header);
            withSky.getStyleClass().add("sky-header");
            top.getChildren().setAll(withSky);
        } else {
            top.getChildren().setAll(header);
        }
        if (peer != null && client.keyChanged(peer)) {
            Button verify = new Button(I18n.t("conv.verify"));
            verify.getStyleClass().addAll("small", "danger-ghost");
            verify.setId("banner-verify");
            verify.setOnAction(e -> actions.openContact(peer));
            Label text = UiKit.label(I18n.t("conv.keyBanner", peer));
            text.setWrapText(true);
            HBox banner = new HBox(Icons.of(Icons.ALERT, 16, "danger"), text, UiKit.grow(), verify);
            banner.getStyleClass().add("banner-danger");
            banner.setAlignment(Pos.CENTER_LEFT);
            banner.setId("key-banner");
            top.getChildren().add(banner);
        }
    }

    /** The topic under the title; managers can click it (or "Add a topic") to change it. */
    private Node topicLine() {
        boolean manager = groupId != 0 && mayModerate();
        if (topic.isEmpty() && !manager) return null;
        Label line = UiKit.label(topic.isEmpty() ? "+ " + I18n.t("topic.add") : topic, "topic-line");
        if (topic.isEmpty()) line.getStyleClass().add("empty");
        line.setId("topic");
        line.setMinWidth(0);
        if (!topic.isEmpty()) Tooltip.install(line, new Tooltip(topic));
        if (manager) {
            line.setStyle("-fx-cursor: hand;");
            line.setOnMouseClicked(e -> editTopic());
        }
        return line;
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
        if (seconds == session.timer(conversation)) return;
        session.background().execute(() -> {
            String problem = null;
            try {
                if (peer != null) {
                    session.client().setTimer(peer, seconds);
                } else {
                    List<String> skipped = session.client().setGroupTimer(groupId, seconds);
                    if (!skipped.isEmpty()) problem = I18n.t("conv.notChangedFor", String.join(", ", skipped));
                }
                session.history().setTimer(conversation, seconds);
            } catch (KeyChangedException e) {
                problem = I18n.t("conv.keyChangedFirst", peer);
            } catch (Exception e) {
                problem = I18n.t("session.timerFailed", e.getMessage());
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
        boolean mine = by.equals(session.client().username());
        systemLine(seconds > 0
                ? (mine ? I18n.t("conv.timerOnYou", AccountSession.timerLabel(seconds)) : I18n.t("conv.timerOn", by, AccountSession.timerLabel(seconds)))
                : (mine ? I18n.t("conv.timerOffYou") : I18n.t("conv.timerOff", by)), false);
        refreshHeader();
    }

    /** Removes messages that disappeared. */
    void remove(List<Long> ids) {
        loaded.removeIf(m -> ids.contains(m.id()));
        for (long id : ids) {
            Node row = rows.remove(id);
            if (row != null) SandDissolve.play(row, layer, () -> messages.getChildren().remove(row)); // turns to sand
            statusLabels.remove(id);
        }
        if (!ids.isEmpty()) lastSender = null;
    }

    /** "online"/"offline" for a friend, or "… is typing" while someone types. */
    private void refreshPresence() {
        if (typingWho != null) {
            presence.setText(peer != null ? I18n.t("conv.typing") : I18n.t("conv.typingWho", typingWho));
            if (!presence.getStyleClass().contains("typing")) presence.getStyleClass().add("typing");
        } else {
            presence.getStyleClass().remove("typing");
            presence.setText(peer != null ? (session.client().isOnline(peer) ? I18n.t("conv.online") : I18n.t("conv.offline")) : "");
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

    /** "# general" for a community's own channel, "# name" for its other channels. */
    static String channelTitle(Packet.GroupInfo g) {
        return "# " + (g.isCommunity() ? I18n.t("community.general") : groupName(g));
    }

    /** A group's name, or a translated placeholder while its name key has not arrived yet. */
    static String groupName(Packet.GroupInfo g) {
        if (g.name().equals(ChatClient.ENCRYPTED_GROUP_NAME)) return I18n.t("conv.encryptedGroup");
        if (g.name().equals("New group")) return I18n.t("conv.newGroup");
        return g.name();
    }

    private Packet.GroupInfo group() {
        return session.client().groups().stream().filter(g -> g.id() == groupId).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ messages

    private void load() {
        try {
            List<History.Message> list = session.history().load(conversation, PAGE);
            loaded.addAll(list);
            hasOlder = list.size() == PAGE;
            if (list.isEmpty()) {
                Label hint = UiKit.label(peer != null
                        ? I18n.t("conv.hintDm", peer)
                        : I18n.t("conv.hintGroup"), "system-line");
                hint.setWrapText(true);
                VBox row = new VBox(12, Illustrations.desert(240), hint);
                row.setAlignment(Pos.CENTER);
                row.setPadding(new Insets(30, 0, 4, 0));
                messages.getChildren().add(row);
            }
            reactions = session.history().reactions(conversation);
            votes = session.history().votes(conversation);
            topic = session.history().topic(conversation);
            java.util.Set<Long> inThreads = session.history().threadReplies(conversation);
            for (History.Message m : list) {
                if (inThreads.contains(m.id())) threads.computeIfAbsent(hex(m.replyTo()), k -> new java.util.ArrayList<>()).add(m);
            }
            for (History.Message m : list) if (!inThreads.contains(m.id())) append(m);
            if (hasOlder) messages.getChildren().add(0, olderHint());
            session.history().markRead(conversation);
        } catch (IOException e) {
            messages.getChildren().add(UiKit.label(I18n.t("conv.loadFailed", e.getMessage()), "system-line", "danger"));
        }
    }

    /** "Scroll up for older messages" at the top while there are more. */
    private Node olderHint() {
        Label hint = UiKit.label("↑ " + I18n.t("conv.older"), "system-line");
        HBox row = new HBox(hint);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(10, 0, 0, 0));
        row.setId("older-hint");
        hint.setOnMouseClicked(e -> loadOlder());
        hint.setStyle("-fx-cursor: hand;");
        return row;
    }

    /**
     * Reads the page before the oldest shown message and draws everything again, keeping the
     * message that was at the top where it was.
     */
    private void loadOlder() {
        if (loaded.isEmpty() || loadingOlder) return;
        loadingOlder = true;
        try {
            List<History.Message> older = session.history().loadBefore(conversation, loaded.get(0).id(), PAGE);
            hasOlder = older.size() == PAGE;
            if (older.isEmpty()) {
                messages.getChildren().removeIf(n -> "older-hint".equals(n.getId()));
                loadingOlder = false;
                return;
            }
            Node anchor = messages.getChildren().stream().filter(n -> n.getUserData() instanceof Long).findFirst().orElse(null);
            Object anchorId = anchor == null ? null : anchor.getUserData();
            loaded.addAll(0, older);
            java.util.Set<Long> inThreads = session.history().threadReplies(conversation);
            threads.clear();
            for (History.Message m : loaded) {
                if (inThreads.contains(m.id())) threads.computeIfAbsent(hex(m.replyTo()), k -> new java.util.ArrayList<>()).add(m);
            }
            reactions = session.history().reactions(conversation);
            votes = session.history().votes(conversation);
            messages.getChildren().clear();
            rows.clear();
            statusLabels.clear();
            continuedById.clear();
            whenVisible.clear();
            lastDay = null;
            lastSender = null;
            if (hasOlder) messages.getChildren().add(olderHint());
            for (History.Message m : loaded) if (!inThreads.contains(m.id())) append(m);
            Node again = anchorId == null ? null : rows.get((Long) anchorId);
            Platform.runLater(() -> {
                messages.applyCss();
                messages.layout();
                double content = messages.getHeight() - scroller.getViewportBounds().getHeight();
                if (again != null && content > 0) scroller.setVvalue(Math.min(1, again.getBoundsInParent().getMinY() / content));
                loadingOlder = false;
            });
        } catch (IOException e) {
            loadingOlder = false;
        }
    }

    /** Starts the downloads of pictures that are now on screen (or close to it). */
    private void checkVisible() {
        if (whenVisible.isEmpty() || scroller.getScene() == null) return;
        javafx.geometry.Bounds view = scroller.localToScene(scroller.getBoundsInLocal());
        double margin = view.getHeight(); // one screen ahead, so pictures are ready when they arrive
        var it = whenVisible.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getKey().getScene() == null) continue;
            javafx.geometry.Bounds b = e.getKey().localToScene(e.getKey().getBoundsInLocal());
            if (b.getMaxY() >= view.getMinY() - margin && b.getMinY() <= view.getMaxY() + margin) {
                it.remove();
                e.getValue().run();
            }
        }
    }

    void append(History.Message m) {
        if (!loaded.contains(m)) loaded.add(m);
        if (m.outgoing()) atBottom = true; // our own new message: show it
        java.time.LocalDate day = java.time.Instant.ofEpochMilli(m.sentAt()).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        if (!day.equals(lastDay)) {
            messages.getChildren().add(dateSeparator(day));
            lastDay = day;
            lastSender = null;
        }
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
            votes = session.history().votes(conversation);
            int index = messages.getChildren().indexOf(old);
            if (m == null || index < 0) return;
            Node row = buildRow(m, continuedById.getOrDefault(localId, false));
            rows.put(localId, row);
            boolean justDeleted = m.deleted() && !Boolean.TRUE.equals(old.getProperties().get("deleted"));
            if (justDeleted) {
                // deleted for everyone: the old bubble blows away as sand, then the placeholder appears
                SandDissolve.play(old, layer, () -> {
                    int at = messages.getChildren().indexOf(old);
                    if (at >= 0) messages.getChildren().set(at, row);
                });
            } else {
                messages.getChildren().set(index, row);
            }
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
            body = UiKit.label("🚫 " + I18n.t("conv.deleted"), "bubble-text", "bubble-deleted");
        } else if (m.file() != null) {
            body = fileCard(m.file());
        } else if (com.emirchat.chat.Poll.fromStored(m.text()) != null) {
            body = pollCard(m, com.emirchat.chat.Poll.fromStored(m.text()));
        } else if (Markdown.hasFormatting(m.text())) {
            body = richText(m.text());
        } else {
            Label text = UiKit.label(m.text(), "bubble-text");
            text.setWrapText(true);
            body = text;
        }
        HBox meta = new HBox(5, UiKit.label(UiKit.clock(m.sentAt()), "bubble-time"));
        if (m.edited()) meta.getChildren().add(0, UiKit.label(I18n.t("conv.edited"), "bubble-time"));
        if (m.expiresAt() > 0) {
            Label timer = UiKit.label("⏱", "bubble-time");
            Tooltip.install(timer, new Tooltip(I18n.t("conv.disappearsAt", UiKit.clock(m.expiresAt()))));
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
        // plain text hugs its content; formatted text (TextFlow) wraps at the bubble's limit instead
        bubble.setMinWidth(body instanceof Label || body instanceof HBox || body.getStyleClass().contains("poll-card")
                ? Region.USE_PREF_SIZE : 0);
        bubble.setMinHeight(Region.USE_PREF_SIZE);
        if (!own && !m.deleted() && m.file() == null && Markdown.mentions(m.text(), session.client().username())) {
            bubble.getStyleClass().add("mentions-me"); // someone named us: the bubble says so
        }
        if (m.hasUid() && !m.deleted()) {
            bubble.setOnContextMenuRequested(e -> menu(m).show(bubble, e.getScreenX(), e.getScreenY()));
        }

        VBox column = new VBox(bubble);
        column.setAlignment(own ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        if (groupId != 0 && !own && !continued) {
            Label name = UiKit.label(m.sender(), "sender-name");
            name.setStyle("-fx-text-fill: " + UiKit.css(UiKit.nameColor(m.sender(), Theme.isDark())) + ";");
            column.getChildren().add(0, name);
        }
        Map<String, String> theirs = reactions.get(m.id());
        if (theirs != null && !theirs.isEmpty()) column.getChildren().add(reactionChips(m, theirs, own));
        Node threadSummary = m.hasUid() ? threadLink(m) : null;
        if (threadSummary != null) column.getChildren().add(threadSummary);
        HBox row = new HBox(6);
        if (groupId != 0 && !own) {
            // the sender's face beside the first message of a run, a matching gap beside the rest
            Node face = continued ? new Region() : UiKit.avatar(m.sender(), "avatar-xs", false);
            if (face instanceof Region r) {
                r.setMinWidth(30);
                r.setPrefWidth(30);
            }
            VBox faceBox = new VBox(face);
            faceBox.setPadding(new Insets(continued ? 0 : 18, 0, 0, 0));
            row.getChildren().add(faceBox);
        }
        row.getChildren().add(column);
        if (m.hasUid() && !m.deleted()) {
            Node tools = hoverTools(m, bubble);
            if (own) row.getChildren().add(0, tools);
            else row.getChildren().add(tools);
            row.hoverProperty().addListener((o, a, hover) -> tools.setVisible(hover));
        }
        row.setAlignment(own ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        row.setMinWidth(0);
        row.setPadding(new Insets(continued ? 3 : 12, 0, 0, 0));
        row.setUserData(m.id());
        row.getProperties().put("deleted", m.deleted());
        return row;
    }

    /** The answered message, shown small at the top of a reply. */
    private Node quote(byte[] target) {
        String who;
        String text;
        try {
            History.Message original = session.history().find(conversation, target);
            who = original == null ? "" : original.outgoing() ? I18n.t("conv.you") : original.sender();
            text = original == null ? I18n.t("conv.unavailable") : original.deleted() ? I18n.t("conv.deleted") : Markdown.plain(original.text());
        } catch (IOException e) {
            who = "";
            text = I18n.t("conv.unavailable");
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
            if (m.id() == popId && emoji.equals(popEmoji)) {
                popId = 0;
                pop(chip);
            }
            Tooltip.install(chip, new Tooltip(String.join(", ", people.stream().map(p -> p.equals(me) ? I18n.t("conv.youLower") : p).toList())));
            chip.setOnMouseClicked(e -> react(m, people.contains(me) ? "" : emoji));
            chips.getChildren().add(chip);
        });
        return chips;
    }

    private javafx.scene.control.ContextMenu menu(History.Message m) {
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        MenuItem reply = new MenuItem(I18n.t("conv.reply"));
        reply.setId("menu-reply");
        reply.setOnAction(e -> startReply(m));
        javafx.scene.control.Menu react = new javafx.scene.control.Menu(I18n.t("conv.react"));
        String mine = AccountSession.displayEmoji(reactions.getOrDefault(m.id(), Map.of()).getOrDefault(session.client().username(), ""));
        for (String emoji : AccountSession.REACTIONS) {
            MenuItem item = new MenuItem(emoji.equals(mine) ? emoji + "  ✓" : emoji);
            item.setId("react-" + AccountSession.REACTIONS.indexOf(emoji));
            item.setOnAction(e -> react(m, emoji.equals(mine) ? "" : emoji));
            react.getItems().add(item);
        }
        menu.getItems().addAll(reply, react);
        if (groupId != 0) {
            MenuItem inThread = new MenuItem(I18n.t("thread.reply"));
            inThread.setId("menu-thread");
            inThread.setOnAction(e -> openThread(m));
            menu.getItems().add(inThread);
        }
        if (mayModerate()) {
            boolean pinned = pinnedMessages().stream().anyMatch(p -> p.id() == m.id());
            MenuItem pinItem = new MenuItem(pinned ? I18n.t("pin.unpin") : I18n.t("pin.pin"));
            pinItem.setId("menu-pin");
            pinItem.setOnAction(e -> pin(m, !pinned));
            menu.getItems().add(pinItem);
        }
        boolean isPoll = com.emirchat.chat.Poll.isStored(m.text());
        if (m.file() == null && !isPoll) {
            MenuItem copy = new MenuItem(I18n.t("conv.copy"));
            copy.setOnAction(e -> {
                javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                content.putString(m.text());
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            });
            menu.getItems().add(copy);
        }
        if (m.outgoing()) {
            if (m.file() == null && !isPoll) {
                MenuItem edit = new MenuItem(I18n.t("conv.edit"));
                edit.setId("menu-edit");
                edit.setOnAction(e -> startEdit(m));
                menu.getItems().add(edit);
            }
            MenuItem delete = new MenuItem(I18n.t("conv.delete"));
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
                case "pin" -> pin(m, true);
                case "thread" -> openThread(m);
                default -> react(m, action);
            }
        } catch (IOException ignored) {
            // nothing to act on
        }
    }

    private void startReply(History.Message m) {
        editing = null;
        replyTo = m.uid();
        String who = m.outgoing() ? I18n.t("conv.yourself") : m.sender();
        showContext("↩ " + I18n.t("conv.replyingTo", who) + ": " + Markdown.plain(m.text()));
        input.requestFocus();
    }

    private void startEdit(History.Message m) {
        replyTo = null;
        editing = m;
        showContext("✎ " + I18n.t("conv.editing"));
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
        popId = m.id();
        popEmoji = AccountSession.displayEmoji(emoji);
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
                problem = I18n.t("conv.keyChangedFirst", peer);
            } catch (Exception e) {
                problem = I18n.t("conv.changeFailed", e.getMessage());
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
                l.setText(I18n.t("conv.notSent"));
                l.getStyleClass().add("failed");
            }
            default -> l.setText("");
        }
    }

    // ------------------------------------------------------------------ sending

    private void send() {
        if (editing == null && input.getText().strip().startsWith("/") && command(input.getText().strip())) return;
        // (a command may have replaced the text: /shrug, /roll ...)
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
        strike();
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
                    problem = I18n.t("conv.notSentTo", String.join(", ", sent.skipped()));
                }
            } catch (KeyChangedException e) {
                status = History.FAILED;
                problem = I18n.t("conv.keyChangedResend", peer);
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

    /**
     * The send button pulls back, lunges and settles, and throws a few sparks up towards the
     * conversation, like a log settling in the fire.
     */
    private void strike() {
        if (!Motion.enabled()) return;
        Node icon = sendButton.getGraphic();
        javafx.animation.Timeline lunge = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.ZERO,
                        new javafx.animation.KeyValue(sendButton.scaleXProperty(), 1),
                        new javafx.animation.KeyValue(sendButton.scaleYProperty(), 1),
                        new javafx.animation.KeyValue(icon.rotateProperty(), 0)),
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(70),
                        new javafx.animation.KeyValue(sendButton.scaleXProperty(), 0.84),
                        new javafx.animation.KeyValue(sendButton.scaleYProperty(), 0.84),
                        new javafx.animation.KeyValue(icon.rotateProperty(), 28)),
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(170),
                        new javafx.animation.KeyValue(sendButton.scaleXProperty(), 1.12, javafx.animation.Interpolator.EASE_OUT),
                        new javafx.animation.KeyValue(sendButton.scaleYProperty(), 1.12, javafx.animation.Interpolator.EASE_OUT),
                        new javafx.animation.KeyValue(icon.rotateProperty(), -14, javafx.animation.Interpolator.EASE_OUT)),
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(320),
                        new javafx.animation.KeyValue(sendButton.scaleXProperty(), 1, javafx.animation.Interpolator.EASE_BOTH),
                        new javafx.animation.KeyValue(sendButton.scaleYProperty(), 1, javafx.animation.Interpolator.EASE_BOTH),
                        new javafx.animation.KeyValue(icon.rotateProperty(), 0, javafx.animation.Interpolator.EASE_BOTH)));
        lunge.play();
        if (!(sendButton.getParent() instanceof javafx.scene.layout.Pane composer)) return;
        javafx.geometry.Bounds b = sendButton.getBoundsInParent();
        java.util.Random r = new java.util.Random();
        for (int k = 0; k < 6; k++) {
            javafx.scene.shape.Circle spark = new javafx.scene.shape.Circle(1.6 + r.nextDouble() * 1.6);
            spark.getStyleClass().add(k % 2 == 0 ? "spark" : "spark-hot");
            spark.setManaged(false);
            spark.setMouseTransparent(true);
            spark.setCenterX(b.getCenterX());
            spark.setCenterY(b.getMinY() + 8);
            composer.getChildren().add(spark);
            double angle = -Math.PI / 2 + (r.nextDouble() - 0.5) * 1.6; // upwards, spreading out
            double distance = 30 + r.nextDouble() * 40;
            javafx.animation.TranslateTransition fly = new javafx.animation.TranslateTransition(
                    javafx.util.Duration.millis(380 + r.nextInt(220)), spark);
            fly.setByX(Math.cos(angle) * distance);
            fly.setByY(Math.sin(angle) * distance);
            fly.setInterpolator(javafx.animation.Interpolator.EASE_OUT);
            javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(fly.getDuration(), spark);
            fade.setToValue(0);
            javafx.animation.ParallelTransition all = new javafx.animation.ParallelTransition(fly, fade);
            all.setDelay(javafx.util.Duration.millis(110));
            all.setOnFinished(e -> composer.getChildren().remove(spark));
            all.play();
        }
    }

    // ------------------------------------------------------------------ files

    private Node fileCard(FileMessage file) {
        if (com.emirchat.call.VoiceNotes.isVoice(file.mimeType(), file.name())) return voiceCard(file);
        StackPane icon = new StackPane(Icons.of(Icons.FILE, 18, "accent"));
        icon.getStyleClass().add("file-icon");
        VBox titles = new VBox(1, UiKit.label(file.name(), "file-name"), UiKit.label(humanSize(file.size()), "file-size"));
        Button save = new Button();
        save.getStyleClass().addAll("small", "attach");
        save.setGraphic(Icons.of(Icons.DOWNLOAD, 16, "muted"));
        save.setTooltip(new Tooltip(I18n.t("conv.saveTip")));
        save.setOnAction(e -> saveFile(file));
        HBox card = new HBox(icon, titles, UiKit.grow(), save);
        card.getStyleClass().add("file-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMinWidth(220);
        if (!file.isPreviewableImage()) return card;
        if (gallery.stream().noneMatch(f -> fileKey(f).equals(fileKey(file)))) gallery.add(file);
        VBox withPreview = new VBox(6, card);
        Image known = images.get(fileKey(file));
        Runnable fetch = () -> session.downloads().execute(() -> {
            try {
                Image image = known != null ? known : new Image(new ByteArrayInputStream(session.client().downloadFile(file)));
                if (image.isError()) return;
                Platform.runLater(() -> images.put(fileKey(file), image));
                Platform.runLater(() -> {
                    ImageView view = new ImageView(image);
                    view.setPreserveRatio(true);
                    view.setFitWidth(Math.min(280, image.getWidth()));
                    view.setSmooth(true);
                    view.getStyleClass().add("file-preview");
                    view.setOnMouseClicked(e -> openViewer(file));
                    Tooltip.install(view, new Tooltip(I18n.t("viewer.openTip")));
                    withPreview.getChildren().add(0, view);
                });
            } catch (Exception ignored) {
                // no preview; the file can still be saved, which reports any problem
            }
        });
        // decrypt pictures only when they come into view: long chats open fast and use less memory
        whenVisible.put(withPreview, fetch);
        Platform.runLater(this::checkVisible);
        return withPreview;
    }

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("conv.sendFile"));
        File chosen = chooser.showOpenDialog(root.getScene().getWindow());
        if (chosen == null) return;
        sendFile(chosen);
    }

    /** Encrypts, uploads and sends one file (from the chooser, a drop or a paste). */
    void sendFile(File chosen) {
        if (chosen.isDirectory()) return;
        if (chosen.length() > Attachments.MAX_FILE_BYTES) {
            systemLine(I18n.t("conv.tooLarge", chosen.getName(), Attachments.MAX_FILE_BYTES / (1024 * 1024)), true);
            return;
        }
        sendBytes(FileMessage.safeName(chosen.getName()), () -> Files.readAllBytes(chosen.toPath()), mimeType(chosen.toPath()));
    }

    interface Bytes {
        byte[] read() throws IOException;
    }

    void sendBytes(String fileName, Bytes source, String mime) {
        String name = FileMessage.safeName(fileName);
        Label progress = UiKit.label(I18n.t("conv.sending", name), "system-line");
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
                byte[] data = source.read();
                if (data.length > Attachments.MAX_FILE_BYTES) throw new IOException(I18n.t("conv.tooLarge", name, Attachments.MAX_FILE_BYTES / (1024 * 1024)));
                String me = session.client().username();
                ChatClient.Sent sent = session.client().sendFile(chat(), name, mime, data, expire, answering);
                long expiresAt = AccountSession.expiresAt(System.currentTimeMillis(), expire);
                long id = session.history().addFile(conversation, me, sent.file(), sent.file().sentAtMillis(), true,
                        History.SENT, sent.envelopeId(), expiresAt, sent.messageId(), answering);
                session.sent(id, sent.envelopeId());
                stored = session.history().get(id);
                if (!sent.skipped().isEmpty()) {
                    problem = I18n.t("conv.notSentTo", String.join(", ", sent.skipped()));
                }
            } catch (KeyChangedException e) {
                problem = I18n.t("conv.keyChangedResend", peer);
                Platform.runLater(this::refreshHeader);
            } catch (Exception e) {
                problem = I18n.t("conv.sendFailed", name, e.getMessage());
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
        chooser.setTitle(I18n.t("conv.saveFile"));
        chooser.setInitialFileName(file.name());
        File target = chooser.showSaveDialog(root.getScene().getWindow());
        if (target == null) return;
        session.downloads().execute(() -> {
            String result;
            boolean danger = false;
            try {
                Files.write(target.toPath(), session.client().downloadFile(file));
                result = I18n.t("conv.saved", target.getName());
            } catch (SecurityException e) {
                result = I18n.t("conv.notSaved", file.name(), e.getMessage());
                danger = true;
            } catch (Exception e) {
                result = I18n.t("conv.downloadFailed", file.name(), e.getMessage());
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

    // ------------------------------------------------------------------ voice messages

    /** Swaps the composer for a recording bar: live level meter, timer, cancel and send. */
    private void startRecording() {
        if (recorder != null) return;
        com.emirchat.call.VoiceRecorder r = new com.emirchat.call.VoiceRecorder();
        java.util.ArrayDeque<Float> levels = new java.util.ArrayDeque<>();
        javafx.scene.canvas.Canvas meter = new javafx.scene.canvas.Canvas(260, 30);
        Label clock = UiKit.label("0:00", "voice-clock");
        clock.setId("voice-clock");
        long started = System.currentTimeMillis();
        javafx.animation.Timeline tick = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(100), e -> {
                    clock.setText(voiceTime(System.currentTimeMillis() - started));
                    synchronized (levels) {
                        drawLevels(meter, levels);
                    }
                }));
        tick.setCycleCount(javafx.animation.Animation.INDEFINITE);
        Runnable[] finish = new Runnable[1];
        try {
            r.start(level -> {
                synchronized (levels) {
                    levels.addLast(level);
                    while (levels.size() > 60) levels.removeFirst();
                }
            }, () -> Platform.runLater(() -> finish[0].run()));
        } catch (IllegalStateException e) {
            systemLine(I18n.t("voice.noMicrophone", e.getMessage()), true);
            return;
        }
        recorder = r;
        List<Node> normal = List.copyOf(composer.getChildren());
        Button cancel = new Button();
        cancel.setId("voice-cancel");
        cancel.getStyleClass().add("attach");
        cancel.setGraphic(Icons.of(Icons.TRASH, 18, "danger"));
        cancel.setTooltip(new Tooltip(I18n.t("voice.cancel")));
        Button done = new Button();
        done.setId("voice-send");
        done.getStyleClass().add("send");
        done.setGraphic(Icons.of(Icons.SEND, 17, "on-accent"));
        done.setTooltip(new Tooltip(I18n.t("voice.send")));
        javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(5);
        dot.getStyleClass().add("rec-dot");
        javafx.animation.FadeTransition blink = new javafx.animation.FadeTransition(javafx.util.Duration.millis(600), dot);
        blink.setFromValue(1);
        blink.setToValue(0.25);
        blink.setAutoReverse(true);
        blink.setCycleCount(javafx.animation.Animation.INDEFINITE);
        StackPane meterBox = new StackPane(meter);
        meterBox.setAlignment(Pos.CENTER_LEFT);
        meterBox.setMinWidth(0);
        HBox.setHgrow(meterBox, Priority.ALWAYS);
        meter.widthProperty().bind(meterBox.widthProperty());
        HBox bar = new HBox(12, dot, clock, meterBox);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("recording-bar");
        HBox.setHgrow(bar, Priority.ALWAYS);
        composer.getChildren().setAll(cancel, bar, done);
        composer.setAlignment(Pos.CENTER);
        java.util.function.Consumer<Boolean> end = sendIt -> {
            if (recorder != r) return;
            tick.stop();
            blink.stop();
            recorder = null;
            cancelRecording = null;
            byte[] pcm = r.stop();
            composer.getChildren().setAll(normal);
            composer.setAlignment(Pos.BOTTOM_CENTER);
            input.requestFocus();
            if (!sendIt) return;
            if (com.emirchat.call.VoiceNotes.millis(pcm) < 500) {
                systemLine(I18n.t("voice.tooShort"), false);
                return;
            }
            String name = "voice-" + java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".wav";
            sendBytes(name, () -> com.emirchat.call.VoiceNotes.wav(pcm), com.emirchat.call.VoiceNotes.MIME);
        };
        // At the length limit the microphone stops, and the recording waits for Send or Discard:
        // nothing is ever sent without the user asking.
        finish[0] = () -> {
            if (recorder != r) return;
            tick.stop();
            blink.stop();
            r.stop();
            clock.setText(voiceTime(com.emirchat.call.VoiceNotes.MAX_SECONDS * 1000L) + " · " + I18n.t("voice.limit"));
        };
        cancelRecording = () -> end.accept(false);
        cancel.setOnAction(e -> end.accept(false));
        done.setOnAction(e -> end.accept(true));
        tick.play();
        blink.play();
    }

    private static String voiceTime(long millis) {
        long s = millis / 1000;
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    private static void drawLevels(javafx.scene.canvas.Canvas c, java.util.Deque<Float> levels) {
        var g = c.getGraphicsContext2D();
        double w = c.getWidth();
        double h = c.getHeight();
        g.clearRect(0, 0, w, h);
        g.setFill(Appearance.accentColor(Theme.isDark()));
        double barW = 3;
        double gap = 2;
        int i = 0;
        // newest on the right, scrolling left
        Float[] values = levels.toArray(new Float[0]);
        for (int k = values.length - 1; k >= 0; k--, i++) {
            double x = w - (i + 1) * (barW + gap);
            if (x < 0) break;
            double bh = Math.max(2, values[k] * h);
            g.fillRoundRect(x, (h - bh) / 2, barW, bh, 3, 3);
        }
    }

    /** A voice message: play/pause, a waveform that fills as it plays (click to seek) and its length. */
    private Node voiceCard(FileMessage file) {
        Button play = new Button();
        play.getStyleClass().add("voice-play");
        play.setGraphic(Icons.of(Icons.PLAY, 16, "on-accent"));
        play.setDisable(true);
        javafx.scene.canvas.Canvas wave = new javafx.scene.canvas.Canvas(200, 32);
        wave.getStyleClass().add("voice-wave");
        Label length = UiKit.label("…", "voice-length");
        HBox card = new HBox(10, play, wave, length);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("voice-card");
        card.setMinWidth(Region.USE_PREF_SIZE);
        String key = fileKey(file);
        float[][] bars = {new float[0]};
        com.emirchat.call.VoicePlayer[] player = {null};
        double[] at = {0};
        boolean[] own = {false};
        Runnable redraw = () -> drawWave(wave, bars[0], at[0], own[0]);
        card.parentProperty().addListener((o, a, parent) -> {
            // the bubble decides the colours: cream on our amber bubbles, accent on theirs
            for (Node n = parent; n != null; n = n.getParent()) {
                if (n.getStyleClass().contains("bubble")) {
                    own[0] = n.getStyleClass().contains("own");
                    redraw.run();
                    break;
                }
            }
        });
        java.util.function.Consumer<byte[]> ready = pcm -> {
            bars[0] = com.emirchat.call.VoiceNotes.waveform(pcm, 40);
            length.setText(voiceTime(com.emirchat.call.VoiceNotes.millis(pcm)));
            player[0] = new com.emirchat.call.VoicePlayer(pcm);
            play.setDisable(false);
            redraw.run();
        };
        play.setOnAction(e -> {
            com.emirchat.call.VoicePlayer p = player[0];
            if (p == null) return;
            if (p.playing()) {
                p.stop();
                play.setGraphic(Icons.of(Icons.PLAY, 16, "on-accent"));
                return;
            }
            try {
                p.play(at[0] >= 0.999 ? 0 : at[0], f -> Platform.runLater(() -> {
                    at[0] = f;
                    redraw.run();
                }), () -> Platform.runLater(() -> {
                    at[0] = 0;
                    play.setGraphic(Icons.of(Icons.PLAY, 16, "on-accent"));
                    redraw.run();
                }));
                play.setGraphic(Icons.of(Icons.PAUSE, 16, "on-accent"));
            } catch (IllegalStateException ex) {
                systemLine(I18n.t("voice.noSpeaker", ex.getMessage()), true);
            }
        });
        wave.setOnMouseClicked(e -> {
            at[0] = Math.max(0, Math.min(1, e.getX() / wave.getWidth()));
            redraw.run();
            com.emirchat.call.VoicePlayer p = player[0];
            if (p != null && p.playing()) {
                p.stop();
                play.fire();
            }
        });
        byte[] cached = voices.get(key);
        long limit = 64 + 2L * (long) com.emirchat.call.AudioDevice.SAMPLE_RATE * com.emirchat.call.VoiceNotes.MAX_SECONDS;
        if (cached != null) {
            ready.accept(cached);
        } else if (file.size() > limit) {
            length.setText(I18n.t("voice.unplayable")); // longer than any recording: still saveable as a file
        } else {
            session.downloads().execute(() -> {
                try {
                    byte[] pcm = com.emirchat.call.VoiceNotes.decode(session.client().downloadFile(file));
                    if (com.emirchat.call.VoiceNotes.millis(pcm) > com.emirchat.call.VoiceNotes.MAX_SECONDS * 1000L + 1000) {
                        throw new IOException("too long");
                    }
                    Platform.runLater(() -> {
                        voices.put(key, pcm);
                        ready.accept(pcm);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> length.setText(I18n.t("voice.unplayable")));
                }
            });
        }
        redraw.run();
        return card;
    }

    private static void drawWave(javafx.scene.canvas.Canvas c, float[] bars, double played, boolean own) {
        var g = c.getGraphicsContext2D();
        double w = c.getWidth();
        double h = c.getHeight();
        g.clearRect(0, 0, w, h);
        int n = bars.length == 0 ? 40 : bars.length;
        double step = w / n;
        double barW = Math.max(2, step * 0.6);
        javafx.scene.paint.Color on = own ? javafx.scene.paint.Color.rgb(255, 248, 236) : Appearance.accentColor(Theme.isDark());
        javafx.scene.paint.Color off = own ? javafx.scene.paint.Color.rgb(255, 248, 236, 0.42)
                : Theme.isDark() ? javafx.scene.paint.Color.rgb(182, 170, 154, 0.45) : javafx.scene.paint.Color.rgb(95, 84, 70, 0.35);
        for (int i = 0; i < n; i++) {
            double v = bars.length == 0 ? 0.15 : Math.max(0.08, bars[i]);
            double bh = v * h;
            g.setFill((i + 0.5) / n <= played ? on : off);
            g.fillRoundRect(i * step, (h - bh) / 2, barW, bh, barW, barW);
        }
    }

    // ------------------------------------------------------------------ composer

    /** Grows the composer with its text, up to six lines (then it scrolls). */
    private void resizeComposer() {
        String text = input.getText();
        double width = Math.max(120, input.getWidth() - 32);
        double charsPerLine = Math.max(10, width / 7.4);
        int rows = 0;
        for (String line : text.split("\n", -1)) rows += 1 + (int) (line.length() / charsPerLine);
        input.setPrefRowCount(Math.max(1, Math.min(6, rows)));
        suggestions.update();
    }

    /**
     * Enter sends, Shift+Enter starts a new line, Escape cancels a reply or edit, Up in an empty
     * composer edits your last message, and pasting a picture or a file sends it.
     */
    private void composerKey(javafx.scene.input.KeyEvent e) {
        if (suggestions.handle(e)) {
            e.consume();
            return;
        }
        switch (e.getCode()) {
            case ENTER -> {
                e.consume();
                if (e.isShiftDown() || e.isAltDown()) input.replaceSelection("\n");
                else send();
            }
            case ESCAPE -> {
                if (picker != null) closePicker();
                else clearContext();
            }
            case UP -> {
                if (input.getText().isEmpty() && editing == null && editLastOwn()) e.consume();
            }
            case V -> {
                if (e.isShortcutDown() && pasteAttachment()) e.consume();
            }
            default -> { }
        }
    }

    private boolean editLastOwn() {
        try {
            List<History.Message> recent = session.history().load(conversation, 30);
            for (int i = recent.size() - 1; i >= 0; i--) {
                History.Message m = recent.get(i);
                if (m.outgoing() && m.hasUid() && !m.deleted() && m.file() == null) {
                    startEdit(m);
                    return true;
                }
            }
        } catch (IOException ignored) {
            // nothing to edit
        }
        return false;
    }

    /** The ":name" suggestions above the composer: arrows move, Enter or Tab inserts. */
    private final class EmojiSuggestions {
        final VBox box = new VBox(2);
        private List<Emoji.Entry> items = List.of();
        private List<String> commands = List.of();
        private boolean mentionMode;    // "commands" holds usernames after "@"
        private int selected;

        private void renderCommands() {
            box.getChildren().clear();
            for (int i = 0; i < commands.size(); i++) {
                String name = commands.get(i);
                HBox row = mentionMode
                        ? new HBox(10, UiKit.avatar(name, "avatar-xs", false), UiKit.label("@" + name, "suggest-command"))
                        : new HBox(10, UiKit.label("/" + name, "suggest-command"), UiKit.label(commandHint(name), "suggest-name"));
                row.getStyleClass().add("suggest-row");
                if (i == selected) row.getStyleClass().add("selected");
                row.setAlignment(Pos.CENTER_LEFT);
                int index = i;
                row.setOnMouseClicked(e -> {
                    selected = index;
                    insert();
                });
                box.getChildren().add(row);
            }
        }

        private int size() {
            return commands.isEmpty() ? items.size() : commands.size();
        }

        EmojiSuggestions() {
            box.getStyleClass().add("suggest-box");
            box.setId("emoji-suggestions");
            box.setVisible(false);
            box.setManaged(false);
            box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        }

        boolean showing() {
            return box.isVisible();
        }

        void update() {
            String text = input.getText();
            commands = text.startsWith("/") && !text.contains(" ") && !text.contains("\n") ? SlashCommands.complete(text) : List.of();
            String typedName = commands.isEmpty() ? typedMention(text, input.getCaretPosition()) : null;
            if (typedName != null) {
                commands = mentionCandidates(typedName);
                mentionMode = !commands.isEmpty();
                if (mentionMode) {
                    selected = 0;
                    renderCommands();
                    StackPane.setMargin(box, new Insets(0, 0, composer.getHeight() + 4, 56));
                    box.setVisible(true);
                    box.setManaged(true);
                    return;
                }
            }
            mentionMode = false;
            String code = commands.isEmpty() ? Emoji.typedShortcode(text, input.getCaretPosition()) : null;
            items = code == null ? List.of() : Emoji.suggest(code, 6);
            selected = 0;
            if (!commands.isEmpty()) {
                renderCommands();
                StackPane.setMargin(box, new Insets(0, 0, composer.getHeight() + 4, 56));
                box.setVisible(true);
                box.setManaged(true);
                return;
            }
            if (items.isEmpty()) {
                hide();
                return;
            }
            render();
            StackPane.setMargin(box, new Insets(0, 0, composer.getHeight() + 4, 56));
            box.setVisible(true);
            box.setManaged(true);
        }

        private void render() {
            box.getChildren().clear();
            for (int i = 0; i < items.size(); i++) {
                Emoji.Entry entry = items.get(i);
                HBox row = new HBox(10, UiKit.label(entry.emoji(), "suggest-emoji"), UiKit.label(":" + entry.name() + ":", "suggest-name"));
                row.getStyleClass().add("suggest-row");
                if (i == selected) row.getStyleClass().add("selected");
                row.setAlignment(Pos.CENTER_LEFT);
                int index = i;
                row.setOnMouseClicked(e -> {
                    selected = index;
                    insert();
                });
                box.getChildren().add(row);
            }
        }

        void hide() {
            box.setVisible(false);
            box.setManaged(false);
        }

        boolean handle(javafx.scene.input.KeyEvent e) {
            if (!showing()) return false;
            switch (e.getCode()) {
                case UP -> selected = (selected + size() - 1) % size();
                case DOWN -> selected = (selected + 1) % size();
                case ENTER, TAB -> {
                    insert();
                    return true;
                }
                case ESCAPE -> {
                    hide();
                    return true;
                }
                default -> {
                    return false;
                }
            }
            if (commands.isEmpty()) render();
            else renderCommands();
            return true;
        }

        private void insert() {
            if (mentionMode) {
                int caret = input.getCaretPosition();
                String typed = typedMention(input.getText(), caret);
                if (typed == null) return;
                int start = caret - typed.length() - 1;
                String name = commands.get(selected);
                input.replaceText(start, caret, "@" + name + " ");
                input.positionCaret(start + name.length() + 2);
                mentionMode = false;
                hide();
                input.requestFocus();
                return;
            }
            if (!commands.isEmpty()) {
                input.setText("/" + commands.get(selected) + " ");
                input.positionCaret(input.getText().length());
                hide();
                input.requestFocus();
                return;
            }
            int caret = input.getCaretPosition();
            String code = Emoji.typedShortcode(input.getText(), caret);
            if (code == null || items.isEmpty()) return;
            int start = caret - code.length() - 1;
            input.replaceText(start, caret, items.get(selected).emoji() + " ");
            input.positionCaret(start + items.get(selected).emoji().length() + 1);
            hide();
            input.requestFocus();
        }
    }

    /** The name being typed after "@" just before the caret (possibly empty), or null. */
    static String typedMention(String text, int caret) {
        if (caret > text.length()) return null;
        int i = caret - 1;
        while (i >= 0 && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_' || text.charAt(i) == '.')) i--;
        if (i < 0 || text.charAt(i) != '@') return null;
        if (i > 0 && !Character.isWhitespace(text.charAt(i - 1)) && text.charAt(i - 1) != '(') return null;
        return text.substring(i + 1, caret).toLowerCase(java.util.Locale.ROOT);
    }

    /** People in this chat whose name starts with what is typed (not ourselves). */
    private List<String> mentionCandidates(String prefix) {
        String me = session.client().username();
        List<String> people = new java.util.ArrayList<>();
        if (peer != null) {
            people.add(peer);
        } else {
            Packet.GroupInfo g = group();
            if (g != null) g.members().forEach(m -> people.add(m.username()));
        }
        return people.stream().filter(u -> !u.equals(me) && u.startsWith(prefix)).sorted().limit(6).toList();
    }

    private static String commandHint(String name) {
        return switch (name) {
            case "poll" -> I18n.t("slash.hintPoll");
            case "roll" -> I18n.t("slash.hintRoll");
            case "flip" -> I18n.t("slash.hintFlip");
            case "remind" -> I18n.t("slash.hintRemind");
            case "shrug" -> I18n.t("slash.hintShrug");
            case "me" -> I18n.t("slash.hintMe");
            case "topic" -> I18n.t("slash.hintTopic");
            default -> I18n.t("slash.hintHelp");
        };
    }

    // ------------------------------------------------------------------ emoji picker

    /**
     * Opens or closes the emoji picker. With a message it picks a reaction to that message;
     * without one it inserts the emoji where the caret is.
     */
    private void togglePicker(History.Message reactTo) {
        if (picker != null) {
            closePicker();
            if (reactTo == null) return;
        }
        VBox panel = new VBox(8);
        panel.getStyleClass().add("emoji-picker");
        panel.setId("emoji-picker");
        panel.setMaxSize(344, 330);
        panel.setPrefSize(344, 330);
        TextField find = new TextField();
        find.setPromptText(I18n.t("emoji.search"));
        find.getStyleClass().add("search");
        find.setId("emoji-search");
        javafx.scene.layout.FlowPane grid = new javafx.scene.layout.FlowPane(2, 2);
        grid.getStyleClass().add("emoji-grid");
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        HBox tabs = new HBox(2);
        tabs.getStyleClass().add("emoji-tabs");
        Runnable[] show = new Runnable[1];
        java.util.function.Consumer<List<Emoji.Entry>> fill = entries -> {
            grid.getChildren().clear();
            for (Emoji.Entry entry : entries) {
                Label cell = UiKit.label(entry.emoji(), "emoji-cell");
                Tooltip.install(cell, new Tooltip(":" + entry.name() + ":"));
                cell.setOnMouseClicked(e -> {
                    if (reactTo != null) {
                        react(reactTo, entry.emoji());
                        closePicker();
                    } else {
                        input.replaceSelection(entry.emoji());
                        input.requestFocus();
                    }
                });
                grid.getChildren().add(cell);
            }
            scroll.setVvalue(0);
        };
        for (var category : Emoji.CATEGORIES.entrySet()) {
            Label tab = UiKit.label(category.getValue().get(0).emoji(), "emoji-tab");
            Tooltip.install(tab, new Tooltip(categoryName(category.getKey())));
            tab.setOnMouseClicked(e -> {
                find.clear();
                tabs.getChildren().forEach(t -> t.getStyleClass().remove("selected"));
                tab.getStyleClass().add("selected");
                fill.accept(category.getValue());
            });
            tabs.getChildren().add(tab);
        }
        show[0] = () -> {
            tabs.getChildren().get(0).getStyleClass().add("selected");
            fill.accept(Emoji.CATEGORIES.values().iterator().next());
        };
        find.textProperty().addListener((o, a, b) -> {
            if (b.isBlank()) show[0].run();
            else fill.accept(Emoji.suggest(b.strip().replace(":", ""), 80));
        });
        find.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) closePicker();
        });
        panel.getChildren().addAll(find, tabs, scroll);
        show[0].run();
        picker = panel;
        layer.getChildren().add(panel);
        StackPane.setAlignment(panel, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(panel, new Insets(0, 14, composer.getHeight() + 6, 0));
        if (Motion.enabled()) {
            panel.setOpacity(0);
            panel.setTranslateY(10);
            javafx.animation.FadeTransition f = new javafx.animation.FadeTransition(javafx.util.Duration.millis(140), panel);
            f.setToValue(1);
            javafx.animation.TranslateTransition t = new javafx.animation.TranslateTransition(javafx.util.Duration.millis(140), panel);
            t.setToY(0);
            new javafx.animation.ParallelTransition(f, t).play();
        }
        Platform.runLater(find::requestFocus);
    }

    private static String categoryName(String key) {
        return switch (key) {
            case "smileys" -> I18n.t("emoji.smileys");
            case "gestures" -> I18n.t("emoji.gestures");
            case "hearts" -> I18n.t("emoji.hearts");
            case "nature" -> I18n.t("emoji.nature");
            case "food" -> I18n.t("emoji.food");
            case "activities" -> I18n.t("emoji.activities");
            case "objects" -> I18n.t("emoji.objects");
            default -> I18n.t("emoji.symbols");
        };
    }

    private void closePicker() {
        if (picker == null) return;
        layer.getChildren().remove(picker);
        picker = null;
        input.requestFocus();
    }

    private static boolean isInside(Object target, Node container) {
        for (Node n = target instanceof Node node ? node : null; n != null; n = n.getParent()) {
            if (n == container) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ hover tools

    /** Quick reactions, reply and "more" beside a message while the pointer is over it. */
    private Node hoverTools(History.Message m, Node bubble) {
        HBox tools = new HBox(1);
        tools.getStyleClass().add("msg-tools");
        tools.setAlignment(Pos.CENTER);
        tools.setVisible(false);
        tools.setMaxHeight(Region.USE_PREF_SIZE);
        String mine = AccountSession.displayEmoji(reactions.getOrDefault(m.id(), Map.of()).getOrDefault(session.client().username(), ""));
        for (String emoji : List.of("👍", "❤️", "😂")) {
            Button b = new Button(emoji);
            b.getStyleClass().addAll("tool-button", "tool-emoji");
            if (emoji.equals(mine)) b.getStyleClass().add("mine");
            b.setOnAction(e -> react(m, emoji.equals(mine) ? "" : emoji));
            tools.getChildren().add(b);
        }
        Button more = toolButton(Icons.SMILE_PLUS, I18n.t("emoji.reactTip"), () -> togglePicker(m));
        more.setId("tool-react-" + m.id());
        Button reply = toolButton(Icons.CORNER_UP_LEFT, I18n.t("conv.reply"), () -> startReply(m));
        reply.setId("tool-reply-" + m.id());
        Button menuButton = toolButton(Icons.MORE, I18n.t("conv.more"), null);
        menuButton.setOnAction(e -> {
            javafx.geometry.Bounds b = menuButton.localToScreen(menuButton.getBoundsInLocal());
            menu(m).show(menuButton, b.getMinX(), b.getMaxY());
        });
        tools.getChildren().addAll(more, reply, menuButton);
        tools.setVisible(true);
        VBox holder = new VBox(tools);
        holder.setAlignment(Pos.CENTER);
        holder.setMinWidth(0); // gives way first when the conversation gets narrow (side panel open)
        holder.setVisible(false); // still takes its place, so the bubble never jumps when it appears
        return holder;
    }

    private Button toolButton(String icon, String tip, Runnable action) {
        Button b = new Button();
        b.setGraphic(Icons.of(icon, 15, "muted"));
        b.getStyleClass().add("tool-button");
        b.setTooltip(new Tooltip(tip));
        if (action != null) b.setOnAction(e -> action.run());
        return b;
    }

    /** A little bounce for a reaction that was just added. */
    private static void pop(Node chip) {
        if (!Motion.enabled()) return;
        chip.setScaleX(0.4);
        chip.setScaleY(0.4);
        javafx.animation.ScaleTransition grow = new javafx.animation.ScaleTransition(javafx.util.Duration.millis(260), chip);
        grow.setToX(1);
        grow.setToY(1);
        grow.setInterpolator(new javafx.animation.Interpolator() {
            @Override protected double curve(double t) { // overshoots a little, then settles
                double c = 1.9;
                return 1 + (c + 1) * Math.pow(t - 1, 3) + c * Math.pow(t - 1, 2);
            }
        });
        grow.play();
    }

    // ------------------------------------------------------------------ dates

    private Node dateSeparator(java.time.LocalDate day) {
        java.time.LocalDate today = java.time.LocalDate.now();
        String text = day.equals(today) ? I18n.t("conv.today")
                : day.equals(today.minusDays(1)) ? I18n.t("conv.yesterday")
                : day.format(java.time.format.DateTimeFormatter.ofPattern(
                        day.getYear() == today.getYear() ? "d MMMM, EEEE" : "d MMMM yyyy", I18n.locale()));
        Region left = new Region();
        Region right = new Region();
        left.getStyleClass().add("date-line");
        right.getStyleClass().add("date-line");
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox row = new HBox(10, left, UiKit.label(text, "date-chip"), right);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(16, 0, 4, 0));
        row.getStyleClass().add("date-separator");
        return row;
    }

    // ------------------------------------------------------------------ formatted text

    /** A message with Markdown: styled text, quotes and colour-coded code blocks. */
    private Node richText(String text) {
        VBox box = new VBox(6);
        for (Markdown.Block block : Markdown.parse(text)) {
            javafx.scene.text.TextFlow flow = new javafx.scene.text.TextFlow();
            flow.getStyleClass().add("md-flow");
            for (Markdown.Span span : block.spans()) {
                Node n = spanNode(span);
                // code blocks are always dark, in both themes: plain code is light text on them
                if (block.kind() == Markdown.Kind.CODE && n instanceof javafx.scene.text.Text t && span.styles().size() == 1) {
                    t.setStyle("-fx-fill: #eee3d3;");
                }
                flow.getChildren().add(n);
            }
            switch (block.kind()) {
                case CODE -> {
                    flow.getStyleClass().add("md-code-flow");
                    VBox code = new VBox(flow);
                    code.getStyleClass().add("md-code-block");
                    if (!block.language().isEmpty()) code.getChildren().add(0, UiKit.label(block.language(), "md-code-lang"));
                    box.getChildren().add(code);
                }
                case QUOTE -> {
                    HBox quote = new HBox(flow);
                    quote.getStyleClass().add("md-quote");
                    box.getChildren().add(quote);
                }
                default -> box.getChildren().add(flow);
            }
        }
        return box;
    }

    private Node spanNode(Markdown.Span span) {
        javafx.scene.text.Text t = new javafx.scene.text.Text(span.text());
        t.getStyleClass().add("md");
        for (Markdown.Style style : span.styles()) t.getStyleClass().add("md-" + style.name().toLowerCase(java.util.Locale.ROOT));
        if (span.has(Markdown.Style.STRIKE)) t.setStrikethrough(true);
        if (span.has(Markdown.Style.MENTION) && span.text().substring(1).equalsIgnoreCase(session.client().username())) {
            t.getStyleClass().add("md-mention-me");
        }
        if (span.has(Markdown.Style.LINK)) {
            t.setUnderline(true);
            t.setCursor(javafx.scene.Cursor.HAND);
            Tooltip.install(t, new Tooltip(span.text()));
            t.setOnMouseClicked(e -> {
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) MainView.browse(span.text());
            });
        }
        return t;
    }

    // ------------------------------------------------------------------ drop and paste

    /** Files dropped on the conversation are sent, one by one. */
    private void installDropTarget() {
        Label hint = UiKit.label(I18n.t("conv.dropHint"), "drop-hint");
        StackPane veil = new StackPane(new VBox(10, Icons.of(Icons.PAPERCLIP, 34, "accent"), hint) {
            {
                setAlignment(Pos.CENTER);
            }
        });
        veil.getStyleClass().add("drop-veil");
        veil.setVisible(false);
        veil.setMouseTransparent(true);
        layer.getChildren().add(veil);
        layer.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
                veil.setVisible(true);
            }
            e.consume();
        });
        layer.setOnDragExited(e -> veil.setVisible(false));
        layer.setOnDragDropped(e -> {
            veil.setVisible(false);
            boolean ok = e.getDragboard().hasFiles();
            if (ok) e.getDragboard().getFiles().forEach(this::sendFile);
            e.setDropCompleted(ok);
            e.consume();
        });
    }

    /**
     * Pasting copied files sends them; pasting a picture (a screenshot, say) sends it as a PNG.
     * Plain text pastes normally.
     */
    private boolean pasteAttachment() {
        javafx.scene.input.Clipboard clip = javafx.scene.input.Clipboard.getSystemClipboard();
        if (clip.hasFiles()) {
            clip.getFiles().forEach(this::sendFile);
            return true;
        }
        if (clip.hasImage() && !clip.hasString()) {
            Image image = clip.getImage();
            String name = "screenshot-" + java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png";
            sendBytes(name, () -> ImageFiles.png(image), "image/png");
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ image viewer

    private static String fileKey(FileMessage f) {
        return java.util.HexFormat.of().formatHex(f.attachmentId());
    }

    /** A full-window viewer for the pictures in this conversation: arrows to browse, Esc to close. */
    private void openViewer(FileMessage file) {
        int[] index = {Math.max(0, indexOf(file))};
        ImageView view = new ImageView();
        view.setPreserveRatio(true);
        view.setSmooth(true);
        // sized from the conversation pane (not from the viewer itself, which would grow in a loop)
        view.fitWidthProperty().bind(root.widthProperty().subtract(150));
        view.fitHeightProperty().bind(root.heightProperty().subtract(130));
        Label caption = UiKit.label("", "viewer-caption");
        Label count = UiKit.label("", "viewer-count");
        Button prev = viewerButton(Icons.CHEVRON_LEFT, I18n.t("viewer.previous"));
        Button next = viewerButton(Icons.CHEVRON_RIGHT, I18n.t("viewer.next"));
        Button save = viewerButton(Icons.DOWNLOAD, I18n.t("conv.saveTip"));
        Button close = viewerButton(Icons.X, I18n.t("common.close"));
        close.setId("viewer-close");
        StackPane veil = new StackPane();
        Runnable show = () -> {
            FileMessage f = gallery.isEmpty() ? file : gallery.get(index[0]);
            caption.setText(f.name() + " · " + humanSize(f.size()));
            count.setText(gallery.size() > 1 ? (index[0] + 1) + " / " + gallery.size() : "");
            prev.setDisable(index[0] == 0);
            next.setDisable(index[0] >= gallery.size() - 1);
            Image cached = images.get(fileKey(f));
            view.setImage(cached);
            if (cached == null) {
                session.downloads().execute(() -> {
                    try {
                        Image img = new Image(new ByteArrayInputStream(session.client().downloadFile(f)));
                        Platform.runLater(() -> {
                            images.put(fileKey(f), img);
                            if (gallery.isEmpty() || gallery.get(index[0]) == f) view.setImage(img);
                        });
                    } catch (Exception ignored) {
                        // stays empty; saving reports the problem
                    }
                });
            }
        };
        prev.setOnAction(e -> {
            index[0] = Math.max(0, index[0] - 1);
            show.run();
        });
        next.setOnAction(e -> {
            index[0] = Math.min(gallery.size() - 1, index[0] + 1);
            show.run();
        });
        save.setOnAction(e -> saveFile(gallery.isEmpty() ? file : gallery.get(index[0])));
        close.setOnAction(e -> layer.getChildren().remove(veil));
        HBox bar = new HBox(10, caption, UiKit.grow(), count, save, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("viewer-bar");
        HBox middle = new HBox(14, prev, view, next);
        middle.setAlignment(Pos.CENTER);
        VBox content = new VBox(12, bar, middle);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(16));
        veil.getChildren().add(content);
        for (Region r : new Region[] {veil, content, middle, bar}) r.setMinSize(0, 0);
        veil.getStyleClass().add("viewer");
        veil.setId("image-viewer");
        veil.setFocusTraversable(true);
        veil.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ESCAPE -> close.fire();
                case LEFT -> prev.fire();
                case RIGHT -> next.fire();
                default -> { }
            }
        });
        veil.setOnMouseClicked(e -> {
            if (e.getTarget() == veil || e.getTarget() == content || e.getTarget() == middle) close.fire();
        });
        layer.getChildren().add(veil);
        show.run();
        veil.requestFocus();
        if (Motion.enabled()) {
            veil.setOpacity(0);
            javafx.animation.FadeTransition f = new javafx.animation.FadeTransition(javafx.util.Duration.millis(160), veil);
            f.setToValue(1);
            f.play();
        }
    }

    private int indexOf(FileMessage file) {
        for (int i = 0; i < gallery.size(); i++) if (fileKey(gallery.get(i)).equals(fileKey(file))) return i;
        return -1;
    }

    private static Button viewerButton(String icon, String tip) {
        Button b = new Button();
        b.setGraphic(Icons.of(icon, 18, "on-dark"));
        b.getStyleClass().add("viewer-button");
        b.setTooltip(new Tooltip(tip));
        return b;
    }

    // ------------------------------------------------------------------ polls

    /** A poll: the question, each answer as a bar filled by its share of the votes, and the total. */
    private Node pollCard(History.Message m, com.emirchat.chat.Poll poll) {
        Map<String, Integer> byVoter = votes.getOrDefault(m.id(), Map.of());
        String me = session.client().username();
        int total = byVoter.size();
        int[] counts = new int[poll.options().size()];
        byVoter.values().forEach(choice -> {
            if (choice >= 0 && choice < counts.length) counts[choice]++;
        });
        Integer mine = byVoter.get(me);
        HBox head = new HBox(6, Icons.of(Icons.BAR_CHART, 14, m.outgoing() ? "on-own" : "accent"), UiKit.label(I18n.t("poll.label"), "poll-kind"));
        head.setAlignment(Pos.CENTER_LEFT);
        Label question = UiKit.label(poll.question(), "poll-question");
        question.setWrapText(true);
        VBox card = new VBox(8, head, question);
        card.getStyleClass().add("poll-card");
        card.setMinWidth(260);
        for (int i = 0; i < poll.options().size(); i++) {
            int index = i;
            double share = total == 0 ? 0 : (double) counts[i] / total;
            Region fill = new Region();
            fill.getStyleClass().add("poll-fill");
            if (mine != null && mine == i) fill.getStyleClass().add("mine");
            Label answer = UiKit.label((mine != null && mine == i ? "✓ " : "") + poll.options().get(i), "poll-answer");
            answer.setWrapText(true);
            Label count = UiKit.label(total == 0 ? "" : Math.round(share * 100) + "%", "poll-count");
            HBox text = new HBox(8, answer, UiKit.grow(), count);
            text.setAlignment(Pos.CENTER_LEFT);
            text.setPadding(new Insets(7, 10, 7, 10));
            StackPane option = new StackPane(fill, text);
            option.getStyleClass().add("poll-option");
            option.setId("poll-option-" + m.id() + "-" + i);
            StackPane.setAlignment(fill, Pos.CENTER_LEFT);
            fill.maxWidthProperty().bind(option.widthProperty().multiply(share));
            fill.prefWidthProperty().bind(fill.maxWidthProperty());
            fill.setMaxHeight(Double.MAX_VALUE);
            List<String> who = byVoter.entrySet().stream().filter(e -> e.getValue() == index)
                    .map(e -> e.getKey().equals(me) ? I18n.t("conv.youLower") : e.getKey()).toList();
            if (!who.isEmpty()) Tooltip.install(option, new Tooltip(String.join(", ", who)));
            if (!m.deleted()) option.setOnMouseClicked(e -> vote(m, index));
            card.getChildren().add(option);
        }
        card.getChildren().add(UiKit.label(total == 0 ? I18n.t("poll.noVotes") : total == 1 ? I18n.t("poll.oneVote")
                : I18n.t("poll.votes", total), "poll-total"));
        return card;
    }

    /** Votes for an answer, or takes the vote back when it is clicked again. */
    private void vote(History.Message m, int choice) {
        String me = session.client().username();
        Integer mine = votes.getOrDefault(m.id(), Map.of()).get(me);
        int value = mine != null && mine == choice ? -1 : choice;
        try {
            session.history().vote(conversation, m.uid(), me, value);
        } catch (IOException e) {
            return;
        }
        refresh(m.id());
        sendAction(ChatClient.ActionKind.VOTE, m.uid(), value < 0 ? "" : String.valueOf(value));
    }

    private void sendPoll(com.emirchat.chat.Poll poll) {
        String me = session.client().username();
        byte[] uid = ChatClient.newMessageId();
        long now = System.currentTimeMillis();
        long expire = session.timer(conversation);
        long localId;
        try {
            localId = session.history().add(conversation, me, poll.toStored(), now, true, History.PENDING, 0,
                    AccountSession.expiresAt(now, expire), uid, new byte[0]);
            append(session.history().get(localId));
        } catch (IOException e) {
            return;
        }
        deliver(localId, () -> session.client().sendPoll(chat(), poll, expire, uid));
    }

    interface Send {
        ChatClient.Sent run() throws Exception;
    }

    /** Sends in the background and shows the outcome on the stored message. */
    private void deliver(long localId, Send send) {
        session.background().execute(() -> {
            int status;
            String problem = null;
            try {
                ChatClient.Sent sent = send.run();
                status = session.sent(localId, sent.envelopeId());
                if (!sent.skipped().isEmpty()) problem = I18n.t("conv.notSentTo", String.join(", ", sent.skipped()));
            } catch (KeyChangedException e) {
                status = History.FAILED;
                problem = I18n.t("conv.keyChangedResend", peer);
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

    // ------------------------------------------------------------------ slash commands

    /** Runs a "/" command; true if it was one (and the composer was handled). */
    private boolean command(String text) {
        SlashCommands.Result result = SlashCommands.parse(text, session.client().username(), RANDOM);
        switch (result) {
            case SlashCommands.NotACommand n -> {
                return false;
            }
            case SlashCommands.Text t -> {
                input.setText(t.text());
                return false; // send the replacement as an ordinary message
            }
            case SlashCommands.PollResult p -> {
                input.clear();
                strike();
                sendPoll(p.poll());
            }
            case SlashCommands.Remind r -> {
                input.clear();
                long at = System.currentTimeMillis() + r.delayMillis();
                systemLine("⏰ " + I18n.t("slash.remindSet", UiKit.time(at) + (r.delayMillis() > 86_400_000L ? "" : " " + UiKit.clock(at)),
                        r.text()), false);
                javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(javafx.util.Duration.millis(r.delayMillis()));
                wait.setOnFinished(e -> {
                    Tray.get().notify(I18n.t("slash.reminder"), r.text());
                    Sounds.play();
                    systemLine("⏰ " + r.text(), false);
                });
                wait.play();
            }
            case SlashCommands.Topic t -> {
                input.clear();
                setTopic(t.topic());
            }
            case SlashCommands.Help h -> {
                input.clear();
                systemLine(I18n.t("slash.help").replace("\\n", "\n"), false);
            }
            case SlashCommands.Problem p -> systemLine(I18n.t(p.key(), p.arg()), true);
        }
        return true;
    }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    // ------------------------------------------------------------------ topic

    private boolean mayModerate() {
        return session.mayModerate(groupId, session.client().username());
    }

    private void setTopic(String text) {
        String topicText = text.strip();
        if (!mayModerate()) {
            systemLine(I18n.t("topic.notAllowed"), true);
            return;
        }
        if (topicText.getBytes(StandardCharsets.UTF_8).length > AccountSession.MAX_TOPIC_BYTES) {
            systemLine(I18n.t("topic.tooLong"), true);
            return;
        }
        try {
            session.history().setTopic(conversation, topicText);
        } catch (IOException e) {
            return;
        }
        topicChanged(session.client().username(), topicText);
        sendAction(ChatClient.ActionKind.TOPIC, null, topicText);
    }

    void topicChanged(String by, String newTopic) {
        topic = newTopic;
        boolean mine = by.equals(session.client().username());
        systemLine(newTopic.isEmpty()
                ? (mine ? I18n.t("topic.clearedYou") : I18n.t("topic.cleared", by))
                : (mine ? I18n.t("topic.changedYou", newTopic) : I18n.t("topic.changed", by, newTopic)), false);
        refreshHeader();
    }

    /** A small editor for the topic, over the conversation. */
    private void editTopic() {
        TextField field = new TextField(topic);
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
        Runnable close = () -> layer.getChildren().remove(veil);
        cancel.setOnAction(e -> close.run());
        save.setOnAction(e -> {
            close.run();
            if (!field.getText().strip().equals(topic)) setTopic(field.getText());
        });
        field.setOnAction(e -> save.fire());
        field.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) close.run();
        });
        layer.getChildren().add(veil);
        Platform.runLater(field::requestFocus);
    }

    // ------------------------------------------------------------------ pins

    private void pin(History.Message m, boolean on) {
        try {
            if (session.history().pin(conversation, m.uid(), session.client().username(), on) < 0) return;
        } catch (IOException e) {
            return;
        }
        sendAction(ChatClient.ActionKind.PIN, m.uid(), on ? "1" : "0");
        systemLine(on ? I18n.t("pin.pinnedYou") : I18n.t("pin.unpinnedYou"), false);
        pinsChanged();
    }

    private List<History.Message> pinnedMessages() {
        try {
            return session.history().pinned(conversation);
        } catch (IOException e) {
            return List.of();
        }
    }

    void pinsChanged() {
        refreshHeader();
        if ("pins".equals(sideMode)) showPins();
    }

    private void showPins() {
        VBox list = new VBox(6);
        boolean manager = mayModerate();
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
            card.setOnMouseClicked(e -> reveal(m.id()));
            list.getChildren().add(card);
        }
        showSide("pins", I18n.t("pin.title"), list);
    }

    // ------------------------------------------------------------------ members

    private void showMembers() {
        Packet.GroupInfo g = group();
        if (g == null) return;
        Packet.GroupInfo root = g.community() == 0 ? g : session.client().groups().stream()
                .filter(x -> x.id() == g.community()).findFirst().orElse(g);
        String me = session.client().username();
        ChatClient c = session.client();
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
                if (!u.equals(me)) row.setOnMouseClicked(e -> actions.openContact(u));
                list.getChildren().add(row);
            }
        }
        showSide("members", I18n.t("members.title"), list);
    }

    // ------------------------------------------------------------------ threads

    private static String hex(byte[] b) {
        return java.util.HexFormat.of().formatHex(b);
    }

    /** A reply arrived (or was sent) in a thread: update the count, and the panel if it is open. */
    void threadReply(History.Message reply, long rootId) {
        threads.computeIfAbsent(hex(reply.replyTo()), k -> new java.util.ArrayList<>()).add(reply);
        refresh(rootId);
        if ("thread".equals(sideMode) && threadRoot != null && hex(threadRoot.uid()).equals(hex(reply.replyTo()))) openThread(threadRoot);
    }

    /** "3 replies · 14:02" under a message that has a thread. */
    private Node threadLink(History.Message m) {
        List<History.Message> replies = threads.get(hex(m.uid()));
        if (replies == null || replies.isEmpty()) return null;
        History.Message last = replies.get(replies.size() - 1);
        Label text = UiKit.label((replies.size() == 1 ? I18n.t("thread.oneReply") : I18n.t("thread.replies", replies.size()))
                + " · " + UiKit.time(last.sentAt()), "thread-link");
        HBox faces = new HBox(-6);
        replies.stream().map(History.Message::sender).distinct().limit(3).forEach(u -> faces.getChildren().add(UiKit.avatar(u, "avatar-xs", false)));
        faces.setScaleX(0.7);
        faces.setScaleY(0.7);
        HBox link = new HBox(2, faces, Icons.of(Icons.MESSAGE, 13, "accent"), text);
        link.setAlignment(Pos.CENTER_LEFT);
        link.getStyleClass().add("thread-summary");
        link.setId("thread-link-" + m.id());
        link.setOnMouseClicked(e -> openThread(m));
        return link;
    }

    /** The thread panel: the first message, its replies, and a composer that answers in the thread. */
    private void openThread(History.Message rootMessage) {
        threadRoot = rootMessage;
        VBox list = new VBox(8);
        VBox first = new VBox(3, UiKit.label(rootMessage.outgoing() ? I18n.t("conv.you") : rootMessage.sender(), "row-title"),
                wrapLabel(previewOf(rootMessage), "body"), UiKit.label(UiKit.time(rootMessage.sentAt()).equals(UiKit.clock(rootMessage.sentAt()))
                ? UiKit.clock(rootMessage.sentAt()) : UiKit.time(rootMessage.sentAt()) + " " + UiKit.clock(rootMessage.sentAt()), "row-time"));
        first.getStyleClass().add("thread-root");
        list.getChildren().add(first);
        List<History.Message> replies = threads.getOrDefault(hex(rootMessage.uid()), List.of());
        list.getChildren().add(UiKit.label(replies.isEmpty() ? I18n.t("thread.empty")
                : replies.size() == 1 ? I18n.t("thread.oneReply") : I18n.t("thread.replies", replies.size()), "overline", "member-heading"));
        for (History.Message r : replies) {
            Node face = UiKit.avatar(r.sender(), "avatar-xs", false);
            Label name = UiKit.label(r.outgoing() ? I18n.t("conv.you") : r.sender(), "row-title");
            Label when = UiKit.label(UiKit.clock(r.sentAt()), "row-time");
            HBox top = new HBox(6, name, when);
            top.setAlignment(Pos.BASELINE_LEFT);
            Node body = Markdown.hasFormatting(r.text()) ? richText(r.text()) : wrapLabel(r.text(), "thread-text");
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
        if (root.getRight() instanceof BorderPane panel) panel.setBottom(composerRow);
        Platform.runLater(answer::requestFocus);
    }

    private void sendThreadReply(History.Message rootMessage, String text) {
        String me = session.client().username();
        byte[] uid = ChatClient.newMessageId();
        long now = System.currentTimeMillis();
        long expire = session.timer(conversation);
        long localId;
        History.Message stored;
        try {
            localId = session.history().add(conversation, me, text, now, true, History.PENDING, 0,
                    AccountSession.expiresAt(now, expire), uid, rootMessage.uid());
            session.history().markThread(localId);
            stored = session.history().get(localId);
        } catch (IOException e) {
            return;
        }
        threadReply(stored, rootMessage.id());
        deliver(localId, () -> session.client().sendThreadReply(chat(), rootMessage.uid(), text, expire, uid));
    }

    // ------------------------------------------------------------------ side panel

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
        root.setRight(panel);
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

    private void toggleSide(String mode, Runnable show) {
        if (mode.equals(sideMode)) closeSide();
        else show.run();
    }

    private void closeSide() {
        sideMode = null;
        threadRoot = null;
        root.setRight(null);
        input.requestFocus();
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

    /** The view is going away (another chat opened, or logging out): stop the microphone and any playback. */
    void dispose() {
        if (cancelRecording != null) cancelRecording.run();
        com.emirchat.call.VoicePlayer.stopAll();
    }

    Node focusTarget() {
        return input;
    }
}
