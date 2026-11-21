package com.emirchat.ui;

import com.emirchat.api.Packet;
import com.emirchat.chat.FileMessage;
import com.emirchat.e2e.Attachments;
import com.emirchat.client.ChatClient;
import com.emirchat.client.History;
import java.io.File;
import java.io.IOException;
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
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

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

        /** False on a server that is too old for voice rooms. */
        default boolean voiceAvailable() { return true; }

        /** Shows a dialog over the window; the runnable given to the builder closes it. */
        default void dialog(java.util.function.Function<Runnable, javafx.scene.layout.VBox> build) {}
    }

    final AccountSession session;
    final String conversation;
    final String peer;          // DM only
    final long groupId;         // group only (0 for DMs)
    final Actions actions;
    final BorderPane root = new BorderPane();
    /** root plus floating layers: emoji suggestions, the emoji picker and the image viewer. */
    final StackPane layer = new StackPane(root);
    final VBox top = new VBox();
    final VBox messages = new VBox();
    private final ScrollPane scroller = new ScrollPane(messages);
    final Map<Long, Label> statusLabels = new HashMap<>();
    private final Map<Long, Node> rows = new HashMap<>();
    final javafx.scene.control.TextArea input = new javafx.scene.control.TextArea();
    final HBox composer = new HBox();
    /** Shown images in order, for the viewer's previous/next; decrypted pictures are kept here. */
    final List<FileMessage> gallery = new java.util.ArrayList<>();
    final Map<String, Image> images = new HashMap<>();
    private java.time.LocalDate lastDay;
    long popId;                  // message whose reaction just changed (to animate it)
    String popEmoji;
    private final Button micButton = new Button();
    /** Thread replies by the hex id of their first message (they are shown in the thread panel). */
    final Map<String, List<History.Message>> threads = new HashMap<>();
    Map<Long, Map<String, Integer>> votes = new HashMap<>();
    String topic = "";
    /** Everything read from history so far (oldest first), threads included. */
    private final List<History.Message> loaded = new java.util.ArrayList<>();
    private boolean hasOlder;
    private boolean loadingOlder;
    private boolean atBottom = true;
    /** Pictures waiting to be fetched until they scroll into view. */
    final Map<Node, Runnable> whenVisible = new java.util.LinkedHashMap<>();
    final Button sendButton = new Button();
    private final Map<Long, Boolean> continuedById = new HashMap<>();
    Map<Long, Map<String, String>> reactions = new HashMap<>();
    final HBox contextBar = new HBox(8);
    final Label contextText = new Label();
    final Label presence = new Label();
    private final javafx.animation.PauseTransition typingTimeout =
            new javafx.animation.PauseTransition(javafx.util.Duration.seconds(5));
    private final javafx.animation.PauseTransition readDelay =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(700));
    byte[] replyTo;            // uid of the message being answered, or null
    History.Message editing;   // our own message being edited, or null
    byte[] pendingRead;        // newest incoming uid not yet reported as read
    long lastTypingSent;
    String typingWho;
    private String lastSender;
    private long lastAt;

    static ConversationView dm(AccountSession session, String peer, Actions actions) {
        return new ConversationView(session, AccountSession.dm(peer), peer, 0, actions);
    }

    static ConversationView group(AccountSession session, long groupId, Actions actions) {
        return new ConversationView(session, AccountSession.group(groupId), null, groupId, actions);
    }

    // The parts of this view, each in its own file. They are created last: they may use the fields above.
    final ConversationHeader header = new ConversationHeader(this);
    final MessageRows bubbles = new MessageRows(this);
    final VoiceMessages voiceUi = new VoiceMessages(this);
    final ComposerTools tools = new ComposerTools(this);
    final ImageViewer viewer = new ImageViewer(this);
    final SidePanels side = new SidePanels(this);
    final MessageSender sender = new MessageSender(this);

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
        input.textProperty().addListener((o, a, b) -> tools.resizeComposer());
        input.widthProperty().addListener((o, a, b) -> tools.resizeComposer());
        HBox.setHgrow(input, Priority.ALWAYS);
        Button send = sendButton;
        send.setId("send");
        send.getStyleClass().add("send");
        send.setGraphic(Icons.of(Icons.SEND, 17, "on-accent"));
        send.setTooltip(new Tooltip(I18n.t("conv.sendTip")));
        send.disableProperty().bind(input.textProperty().isEmpty());
        send.setOnAction(e -> sender.send());
        Button attach = new Button();
        attach.setId("attach");
        attach.getStyleClass().add("attach");
        attach.setGraphic(Icons.of(Icons.PAPERCLIP, 19, "muted"));
        attach.setTooltip(new Tooltip(I18n.t("conv.attachTip", Attachments.MAX_FILE_BYTES / (1024 * 1024))));
        attach.setOnAction(e -> sender.chooseFile());
        Button emoji = new Button();
        emoji.setId("emoji-button");
        emoji.getStyleClass().add("attach");
        emoji.setGraphic(Icons.of(Icons.SMILE, 19, "muted"));
        emoji.setTooltip(new Tooltip(I18n.t("emoji.pickerTip")));
        emoji.setOnAction(e -> tools.togglePicker(null));
        micButton.setId("mic");
        micButton.getStyleClass().add("send");
        micButton.setGraphic(Icons.of(Icons.MIC, 17, "on-accent"));
        micButton.setTooltip(new Tooltip(I18n.t("voice.recordTip")));
        micButton.setOnAction(e -> voiceUi.startRecording());
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
        cancelContext.setOnAction(e -> sender.clearContext());
        contextText.getStyleClass().add("context-text");
        contextText.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(contextText, Priority.ALWAYS);
        contextBar.getChildren().addAll(contextText, cancelContext);
        contextBar.getStyleClass().add("context-bar");
        contextBar.setAlignment(Pos.CENTER_LEFT);
        contextBar.setVisible(false);
        contextBar.setManaged(false);
        input.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, tools::composerKey);
        input.caretPositionProperty().addListener((o, a, b) -> tools.suggestions.update());
        input.textProperty().addListener((o, a, b) -> sender.maybeSendTyping(b));
        typingTimeout.setOnFinished(e -> {
            typingWho = null;
            header.refreshPresence();
        });
        readDelay.setOnFinished(e -> sender.sendReadReceipt());

        layer.getStyleClass().add("conversation-layer");
        layer.setUserData(this); // lets the preview tool reach the open conversation
        root.setTop(top);
        root.setCenter(scroller);
        root.setBottom(new VBox(contextBar, composer));
        layer.getChildren().add(tools.suggestions.box);
        StackPane.setAlignment(tools.suggestions.box, Pos.BOTTOM_LEFT);
        tools.installDropTarget();
        layer.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (tools.picker != null && !ComposerTools.isInside(e.getTarget(), tools.picker) && !ComposerTools.isInside(e.getTarget(), composer)) tools.closePicker();
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

    /** Someone is typing here; the notice fades after a few seconds without news. */
    void typing(String who) {
        typingWho = who;
        header.refreshPresence();
        typingTimeout.playFromStart();
    }

    ChatClient.Chat chat() {
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

    Packet.GroupInfo group() {
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
            if (hasOlder) messages.getChildren().add(0, bubbles.olderHint());
            session.history().markRead(conversation);
        } catch (IOException e) {
            messages.getChildren().add(UiKit.label(I18n.t("conv.loadFailed", e.getMessage()), "system-line", "danger"));
        }
    }

    /**
     * Reads the page before the oldest shown message and draws everything again, keeping the
     * message that was at the top where it was.
     */
    void loadOlder() {
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
            if (hasOlder) messages.getChildren().add(bubbles.olderHint());
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
    void checkVisible() {
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
            messages.getChildren().add(bubbles.dateSeparator(day));
            lastDay = day;
            lastSender = null;
        }
        boolean continued = m.sender().equals(lastSender) && m.sentAt() - lastAt < GROUP_WINDOW_MS;
        Node row = bubbles.buildRow(m, continued);
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
            Node row = bubbles.buildRow(m, continuedById.getOrDefault(localId, false));
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

    void act(long localId, String action) {
        try {
            History.Message m = session.history().get(localId);
            if (m == null) return;
            switch (action) {
                case "reply" -> sender.startReply(m);
                case "edit" -> sender.startEdit(m);
                case "delete" -> sender.deleteForEveryone(m);
                case "pin" -> side.pin(m, true);
                case "thread" -> side.openThread(m);
                default -> sender.react(m, action);
            }
        } catch (IOException ignored) {
            // nothing to act on
        }
    }

    /** The window regained focus: report what is now visible as read. */
    void windowFocused() {
        if (pendingRead != null) readDelay.playFromStart();
    }

    void setStatus(long localId, int status) {
        Label l = statusLabels.get(localId);
        if (l != null) bubbles.applyStatus(l, status);
    }

    // ------------------------------------------------------------------ shared helpers and events

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

    static String fileKey(FileMessage f) {
        return java.util.HexFormat.of().formatHex(f.attachmentId());
    }

    static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    void topicChanged(String by, String newTopic) {
        topic = newTopic;
        boolean mine = by.equals(session.client().username());
        systemLine(newTopic.isEmpty()
                ? (mine ? I18n.t("topic.clearedYou") : I18n.t("topic.cleared", by))
                : (mine ? I18n.t("topic.changedYou", newTopic) : I18n.t("topic.changed", by, newTopic)), false);
        refreshHeader();
    }

    void pinsChanged() {
        refreshHeader();
        if ("pins".equals(side.sideMode)) side.showPins();
    }

    static String hex(byte[] b) {
        return java.util.HexFormat.of().formatHex(b);
    }

    /** A reply arrived (or was sent) in a thread: update the count, and the panel if it is open. */
    void threadReply(History.Message reply, long rootId) {
        threads.computeIfAbsent(hex(reply.replyTo()), k -> new java.util.ArrayList<>()).add(reply);
        refresh(rootId);
        if ("thread".equals(side.sideMode) && side.threadRoot != null && hex(side.threadRoot.uid()).equals(hex(reply.replyTo()))) side.openThread(side.threadRoot);
    }

    /** The view is going away (another chat opened, or logging out): stop the microphone and any playback. */
    void dispose() {
        if (voiceUi.cancelRecording != null) voiceUi.cancelRecording.run();
        com.emirchat.call.VoicePlayer.stopAll();
    }

    Node focusTarget() {
        return input;
    }

    void refreshHeader() {
        header.refreshHeader();
    }

    void sendFile(File chosen) {
        sender.sendFile(chosen);
    }
}
