package com.emirchat.ui;

import com.emirchat.api.ApiException;
import com.emirchat.api.Packet;
import com.emirchat.client.ChatClient;
import com.emirchat.client.History;
import com.emirchat.crypto.Identity;
import com.emirchat.trust.KeyChangedException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
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
    final AccountSession session;
    private final Runnable onLogout;
    final StackPane stack = new StackPane();
    private final BorderPane frame = new BorderPane();
    final Overlay overlay = new Overlay(stack);
    final VBox list = new VBox();
    final StackPane content = new StackPane();
    final ToggleButton chatsTab = new ToggleButton(I18n.t("main.chats"));
    final ToggleButton friendsTab = new ToggleButton(I18n.t("main.friends"));
    final ToggleButton communitiesTab = new ToggleButton(I18n.t("community.tab"));
    long selectedCommunity; // 0: the list of communities
    final Label friendsBadge = UiKit.label("", "unread-badge");
    ConversationView current;
    final TextField search = new TextField();

    private boolean focusHooked;
    private final HBox updateBanner = new HBox(8);
    final HBox callBar = new HBox(10);
    final VideoStage videoStage = new VideoStage();
    final HBox voiceBar = new HBox(10);
    com.emirchat.call.VoiceChannels.State voiceState = com.emirchat.call.VoiceChannels.State.IDLE;
    Map<Long, List<String>> voiceRooms = Map.of();
    Campfire campfire;           // the voice room's scene, while we are in a room
    boolean campfireShown;
    long mapShown;               // the community whose map is on screen, or 0
    javafx.animation.Timeline callTicker;
    private javafx.animation.PauseTransition callHide;
    /** The strip at the far left: home, one icon per community, and "+" (like a server list). */
    final VBox rail = new VBox(8);
    private final Label connectionLabel = UiKit.label(I18n.t("main.connected"), "row-sub", "success-text");
    private final Flame connectionFlame = Flame.status(16);

    // The parts of the main window, each in its own file. None of them touches this window while it
    // is being built: they only keep a reference to it.
    final Sidebar sidebarUi = new Sidebar(this);
    final CommunityDialogs communities = new CommunityDialogs(this);
    final SettingsDialogs settingsUi = new SettingsDialogs(this);
    final QuickSwitcher switcher = new QuickSwitcher(this);
    final ContactDialogs contacts = new ContactDialogs(this);
    final CallBars callBars = new CallBars(this);
    final CatchUpDialog catchUp = new CatchUpDialog(this);
    final VoicePlaces places = new VoicePlaces(this);
    final FirstSteps firstSteps = new FirstSteps(this);

    MainView(AccountSession session, Runnable onLogout) {
        this.session = session;
        this.onLogout = onLogout;
        ChatClient client = session.client();
        String myFingerprint = Identity.fingerprint(client.deviceKey());
        UiKit.useKeys(new UiKit.Keys() {
            @Override public String fingerprint(String user) {
                return user.equals(client.username()) ? myFingerprint : client.currentFingerprint(user);
            }

            @Override public UiKit.Trust trust(String user) {
                if (user.equals(client.username())) return UiKit.Trust.KNOWN;
                if (client.keyChanged(user)) return UiKit.Trust.CHANGED;
                return client.isVerified(user) ? UiKit.Trust.VERIFIED : UiKit.Trust.KNOWN;
            }
        });

        // Sidebar header
        Button logout = new Button();
        logout.setGraphic(Icons.of(Icons.LOG_OUT, 16, null));
        logout.getStyleClass().add("icon-button");
        logout.setTooltip(new Tooltip(I18n.t("main.logout")));
        logout.setId("logout");
        logout.setOnAction(e -> onLogout.run());
        Button me = new Button();
        me.setGraphic(UiKit.avatar(client.username(), "avatar-xs", false));
        me.getStyleClass().add("icon-button");
        me.setTooltip(new Tooltip(I18n.t("main.myKey")));
        me.setOnAction(e -> contacts.openMyKey());
        HBox connection = new HBox(5, connectionFlame, connectionLabel);
        connection.setAlignment(Pos.CENTER_LEFT);
        VBox meText = new VBox(1, UiKit.label(client.username(), "me-name"), connection);
        Button settings = new Button();
        settings.setGraphic(Icons.of(Icons.SETTINGS, 16, null));
        settings.getStyleClass().add("icon-button");
        settings.setTooltip(new Tooltip(I18n.t("main.settings")));
        settings.setId("settings");
        settings.setOnAction(e -> settingsUi.openSettings());
        HBox header = new HBox(me, meText, UiKit.grow(), settings, logout);
        header.getStyleClass().add("sidebar-header");
        header.setAlignment(Pos.CENTER_LEFT);

        // Tabs
        ToggleGroup tabs = new ToggleGroup();
        for (ToggleButton t : new ToggleButton[] {chatsTab, communitiesTab, friendsTab}) {
            t.setToggleGroup(tabs);
            t.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(t, Priority.ALWAYS);
        }
        chatsTab.setId("tab-chats");
        friendsTab.setId("tab-friends");
        communitiesTab.setId("tab-communities");
        friendsBadge.managedProperty().bind(friendsBadge.visibleProperty());
        friendsTab.setGraphic(friendsBadge);
        friendsTab.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        tabs.selectedToggleProperty().addListener((o, old, now) -> {
            if (now == null) old.setSelected(true);
            sidebarUi.refreshSidebar();
        });
        chatsTab.setSelected(true);
        HBox segmented = new HBox(chatsTab, friendsTab);
        segmented.getStyleClass().add("segmented");
        segmented.visibleProperty().bind(communitiesTab.selectedProperty().not());
        segmented.managedProperty().bind(segmented.visibleProperty());
        communitiesTab.setVisible(false);
        communitiesTab.setManaged(false);
        search.setId("search");
        search.setPromptText(I18n.t("main.search"));
        search.getStyleClass().add("search");
        search.textProperty().addListener((o, a, b) -> sidebarUi.refreshSidebar());
        search.visibleProperty().bind(chatsTab.selectedProperty());
        search.managedProperty().bind(search.visibleProperty());
        VBox tabBox = new VBox(10, segmented, search, communitiesTab);
        tabBox.getStyleClass().add("sidebar-section");

        ScrollPane listScroll = new ScrollPane(list);
        listScroll.setFitToWidth(true);
        listScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(listScroll, Priority.ALWAYS);
        updateBanner.managedProperty().bind(updateBanner.visibleProperty());
        updateBanner.setVisible(false);
        VBox sidebar = new VBox(header, updateBanner, tabBox, listScroll);
        sidebar.getStyleClass().add("sidebar");

        showEmpty();
        rail.getStyleClass().add("rail");
        rail.setAlignment(Pos.TOP_CENTER);
        ScrollPane railScroll = new ScrollPane(rail);
        railScroll.setFitToWidth(true);
        railScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        railScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        railScroll.getStyleClass().add("rail-scroll");
        frame.setLeft(new HBox(railScroll, sidebar));
        callBar.managedProperty().bind(callBar.visibleProperty());
        callBar.setVisible(false);
        VBox.setVgrow(content, Priority.ALWAYS);
        voiceBar.managedProperty().bind(voiceBar.visibleProperty());
        voiceBar.setVisible(false);
        // the picture of a video call sits above the conversation and takes a little under half of it,
        // so a few messages and the composer stay in view even in a small window
        videoStage.root().prefHeightProperty().bind(frame.heightProperty().multiply(0.45));
        videoStage.root().setMinHeight(0);
        session.client().calls().videoView(videoStage);
        applyDirectCalls();
        frame.setCenter(new VBox(callBar, voiceBar, videoStage.root(), content));
        stack.getChildren().add(frame);
        stack.getStyleClass().add("main-view");
        stack.setUserData(this); // lets the preview tool open the welcome-back screen
        stack.sceneProperty().addListener((o, a, scene) -> {
            if (scene == null) return;
            scene.getAccelerators().put(
                    new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.K,
                            javafx.scene.input.KeyCombination.SHORTCUT_DOWN), switcher::openPalette);
            scene.getAccelerators().put(Sandstorm.SHORTCUT, storm::toggle);
            if (scene.getWindow() != null) watchFocus(scene.getWindow());
            else scene.windowProperty().addListener((oo, aa, w) -> {
                if (w != null) watchFocus(w);
            });
        });
        sidebarUi.refreshSidebar();
        session.setView(this);
        if (AppPrefs.checkUpdates()) {
            session.downloads().execute(() -> UpdateCheck.newerVersion().ifPresent(v -> Platform.runLater(() -> showUpdate(v))));
        }
        if (!AppPrefs.welcomeShown()) Platform.runLater(() -> settingsUi.openWelcome(0));
        else Platform.runLater(catchUp::maybeCatchUp);
    }

    private void showUpdate(String version) {
        AppLog.info("Update available: " + version);
        Label text = UiKit.label(I18n.t("update.available", version), "row-sub");
        text.setWrapText(true);
        Updater.Kind kind = Updater.kind();
        Button get = new Button(I18n.t(kind == Updater.Kind.MANUAL ? "update.download" : "update.install"));
        get.getStyleClass().addAll("small", "primary");
        get.setId("update-download");
        if (kind == Updater.Kind.MANUAL) {
            get.setOnAction(e -> browse(UpdateCheck.RELEASES));
        } else {
            get.setTooltip(new javafx.scene.control.Tooltip(I18n.t("update.installTip")));
            get.setOnAction(e -> installUpdate(version, kind, text, get));
        }
        updateBanner.getChildren().setAll(Icons.of(Icons.DOWNLOAD, 16, "accent"), text, UiKit.grow(), get);
        updateBanner.getStyleClass().setAll("update-banner");
        updateBanner.setAlignment(Pos.CENTER_LEFT);
        updateBanner.setVisible(true);
    }

    /** Downloads, checks the signature and hash, then hands over to the installer and quits. */
    private void installUpdate(String version, Updater.Kind kind, Label text, Button button) {
        button.setDisable(true);
        text.setText(I18n.t("update.downloading", 0));
        java.nio.file.Path folder = com.emirchat.AppInfo.defaultHome().resolve("updates");
        session.downloads().execute(() -> {
            try {
                Updater.Ready ready = Updater.download(version, kind, folder,
                        p -> Platform.runLater(() -> text.setText(I18n.t("update.downloading", (int) Math.round(p * 100)))));
                Platform.runLater(() -> {
                    text.setText(I18n.t("update.ready", version));
                    button.setText(I18n.t("update.restart"));
                    button.setDisable(false);
                    button.setOnAction(e -> {
                        button.setDisable(true);
                        session.downloads().execute(() -> {
                            try {
                                Updater.install(ready);
                                AppLog.info("Installing " + version + "; quitting");
                                SecureChatApp.quitApp();
                            } catch (java.io.IOException ex) {
                                updateFailed(ex, text, button);
                            }
                        });
                    });
                });
            } catch (java.io.IOException ex) {
                updateFailed(ex, text, button);
            }
        });
    }

    private void updateFailed(Exception e, Label text, Button button) {
        AppLog.warn("Update failed", e);
        Platform.runLater(() -> {
            text.setText(I18n.t("update.failed"));
            button.setText(I18n.t("update.download"));
            button.setDisable(false);
            button.setOnAction(ev -> browse(UpdateCheck.RELEASES));
        });
    }

    /** Opens a web page in the default browser (off the UI thread: AWT may need a moment). */
    static void browse(String url) {
        new Thread(() -> {
            try {
                java.net.URI uri = java.net.URI.create(url);
                // Links come from messages: only ever hand web addresses to the system, never
                // file:, a custom app scheme or anything else that could start a program.
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
                if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null) {
                    AppLog.info("Refused to open a link that is not a web address");
                    return;
                }
                java.awt.Desktop.getDesktop().browse(uri);
            } catch (Exception e) {
                AppLog.warn("Could not open " + url, e);
            }
        }, "browse").start();
    }

    static void openFolder(java.nio.file.Path folder) {
        new Thread(() -> {
            try {
                java.awt.Desktop.getDesktop().open(folder.toFile());
            } catch (Exception e) {
                AppLog.warn("Could not open " + folder, e);
            }
        }, "open-folder").start();
    }

    Parent root() {
        return stack;
    }

    /** Which channel was last open in each community, so coming back lands there. */
    private final Map<Long, Long> lastChannel = new java.util.HashMap<>();

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
    }

    // ------------------------------------------------------------------ content

    /**
     * Who calls may go to directly (each side then sees the other's address): verified contacts
     * by default, every friend, or nobody.
     */
    void applyDirectCalls() {
        com.emirchat.client.ChatClient c = session.client();
        switch (AppPrefs.directCalls()) {
            case "friends" -> c.calls().directAllowed(u -> !c.keyChanged(u));
            case "off" -> c.calls().directAllowed(u -> false);
            default -> c.calls().directAllowed(u -> c.isVerified(u) && !c.keyChanged(u));
        }
    }

    void showEmpty() {
        if (current != null) current.dispose();
        current = null;
        mapShown = 0;
        Label shortcut = UiKit.label(I18n.t("palette.hint", isMac() ? "⌘K" : "Ctrl+K"), "shortcut-hint");
        VBox empty = new VBox(12, Illustrations.desert(300),
                UiKit.label(I18n.t("main.select"), "h2"),
                UiKit.label(I18n.t("main.selectHint"), "caption"), shortcut, firstSteps.node());
        empty.setAlignment(Pos.CENTER);
        empty.getStyleClass().add("content-empty");
        content.getChildren().setAll(empty);
    }

    void open(String conversation) {
        hookFocus();
        ConversationView.Actions actions = new ConversationView.Actions() {
            @Override public void openContact(String user) { MainView.this.contacts.openContact(user); }

            @Override public void openGroupInfo(long groupId) { MainView.this.contacts.openGroupInfo(groupId); }

            @Override public void sidebarChanged() {
                sidebarUi.refreshSidebar();
            }

            @Override public void dialog(java.util.function.Function<Runnable, javafx.scene.layout.VBox> build) {
                overlay.show(build.apply(overlay::close));
            }

            @Override public void call(String friend) {
                if (voiceState.joined()) {
                    notice(I18n.t("voice.leaveFirst"), true);
                    return;
                }
                session.client().calls().call(friend);
            }

            @Override public void joinVoice(long groupId) {
                session.background().execute(() -> session.client().voice().join(groupId));
            }

            @Override public boolean inVoice(long groupId) { return voiceState.groupId() == groupId; }

            @Override public boolean voiceAvailable() { return session.client().voiceSupported(); }

            @Override public int voiceCount(long groupId) { return voiceRooms.getOrDefault(groupId, List.of()).size(); }
        };
        if (current != null) current.dispose();
        current = conversation.startsWith("g:")
                ? ConversationView.group(session, Long.parseLong(conversation.substring(2)), actions)
                : ConversationView.dm(session, conversation.substring(3), actions);
        content.getChildren().setAll(current.root());
        mapShown = 0;
        if (campfireShown) {
            campfireShown = false;
            if (voiceState.joined()) callBars.showVoiceBar(voiceState);
        }
        Packet.GroupInfo opened = conversation.startsWith("g:") ? group(Long.parseLong(conversation.substring(2))) : null;
        if (opened != null && opened.community() != 0) {
            selectedCommunity = opened.community();
            lastChannel.put(opened.community(), opened.id());
            if (!communitiesTab.isSelected()) communitiesTab.setSelected(true); else sidebarUi.refreshSidebar();
        } else if (!chatsTab.isSelected() && conversation.startsWith("dm:")) {
            chatsTab.setSelected(true); // also refreshes
        } else {
            sidebarUi.refreshSidebar();
        }
    }

    Packet.GroupInfo group(long id) {
        return session.client().groups().stream().filter(x -> x.id() == id).findFirst().orElse(null);
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
        if (!friendsTab.isSelected()) sidebarUi.refreshSidebar();
        if (current == null) places.refreshMap();
    }

    @Override
    public void statusChanged(long localId, int status) {
        if (current != null) current.setStatus(localId, status);
    }

    @Override
    public void friendsChanged() {
        sidebarUi.refreshSidebar();
        firstSteps.refresh();
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
        sidebarUi.refreshSidebar();
    }

    @Override
    public void keyChanged(String user) {
        if (current != null) current.refreshHeader();
        sidebarUi.refreshSidebar();
    }

    @Override
    public void problem(String text) {
        notice(text, true);
    }

    /** A line in the open conversation (or a dialog if none is open). */
    void notice(String text, boolean danger) {
        if (current != null) {
            current.systemLine(text, danger);
        } else if (danger) {
            showError(text);
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
        sidebarUi.refreshSidebar();
    }

    @Override
    public void connection(boolean online, int retryInSeconds) {
        connectionLabel.getStyleClass().removeAll("success-text", "danger-text");
        boolean wasOnline = "status-online".equals(connectionLabel.getId()) || connectionLabel.getId() == null;
        if (online) {
            connectionLabel.setText(I18n.t("main.connected"));
            connectionLabel.getStyleClass().add("success-text");
        } else {
            connectionLabel.setText(I18n.t("main.reconnecting", retryInSeconds));
            connectionLabel.getStyleClass().add("danger-text");
        }
        if (online != wasOnline) connectionFlame.setOnline(online);
        connectionLabel.setId(online ? "status-online" : "status-offline");
    }

    @Override
    public void messageChanged(String conversation, long localId) {
        if (current != null && current.conversation().equals(conversation)) current.refresh(localId);
        sidebarUi.refreshSidebar();
    }

    @Override
    public void voice(com.emirchat.call.VoiceChannels.State state) {
        boolean wasJoined = voiceState.joined();
        long before = voiceState.groupId();
        voiceState = state;
        if (state.error() != null) {
            notice("busy".equals(state.error()) ? I18n.t("voice.busy")
                    : com.emirchat.call.VoiceChannels.UNSUPPORTED.equals(state.error()) ? I18n.t("voice.serverTooOld")
                    : I18n.t("voice.failed", state.error()), true);
        }
        if (!state.joined()) {
            voiceBar.setVisible(false);
            if (campfire != null) {
                campfire.stop();
                if (campfireShown) places.backFromCampfire();
                campfire = null;
            }
        } else {
            if (campfire == null || campfire.groupId() != state.groupId()) {
                if (campfire != null) campfire.stop();
                Packet.GroupInfo g = group(state.groupId());
                String title = g == null ? "" : g.community() != 0 ? ConversationView.channelTitle(g) : ConversationView.groupName(g);
                campfire = new Campfire(session, state.groupId(), title, places::backFromCampfire, this::notice);
                places.showCampfire(); // joining a room takes you to its fire
            }
            campfire.update(state);
            callBars.showVoiceBar(state);
        }
        if (current != null && (wasJoined != state.joined() || before != state.groupId())) current.refreshHeader();
        sidebarUi.refreshSidebar();
    }

    /** The privacy curtain over the whole window. */
    final Sandstorm storm = new Sandstorm(stack);

    private void watchFocus(javafx.stage.Window window) {
        window.focusedProperty().addListener((o, was, now) -> {
            if (!now && AppPrefs.stormOnBlur()) storm.raise();
        });
    }

    @Override
    public void nudged(String user) {
        boolean focused = stack.getScene() != null && stack.getScene().getWindow() != null && stack.getScene().getWindow().isFocused();
        // a muted chat or quiet hours keep a nudge silent too
        NotifyRules.Decision decision = session.notifyRules().decide(AccountSession.dm(user), true, NotifyRules.now());
        if (decision == NotifyRules.Decision.NOTIFY) Sounds.play();
        if (!focused && decision != NotifyRules.Decision.NONE) Tray.get().notify("Oba", "🦂 " + I18n.t("nudge.received", user));
        if (current != null && user.equals(current.peer())) {
            Nudge.play(current.layer, current.input); // waits on screen for when they look back, too
            return;
        }
        notice("🦂 " + I18n.t("nudge.received", user), false);
    }

    @Override
    public void clockChanged(String user) {
        if (current != null && user.equals(current.peer())) current.refreshHeader();
    }

    @Override
    public void voiceRooms(Map<Long, List<String>> rooms) {
        voiceRooms = rooms;
        sidebarUi.refreshSidebar();
        places.refreshMap();
    }

    @Override
    public void threadReply(History.Message reply, long rootId) {
        if (current != null && current.conversation().equals(reply.conversation())) current.threadReply(reply, rootId);
        if (!friendsTab.isSelected()) sidebarUi.refreshSidebar();
    }

    @Override
    public void pinsChanged(String conversation) {
        if (current != null && current.conversation().equals(conversation)) current.pinsChanged();
    }

    @Override
    public void topicChanged(String conversation, String by, String topic) {
        if (current != null && current.conversation().equals(conversation)) current.topicChanged(by, topic);
    }

    @Override
    public void votesChanged(String conversation, long pollId) {
        if (current != null && current.conversation().equals(conversation)) current.refresh(pollId);
    }

    @Override
    public void typing(String conversation, String who) {
        if (current != null && current.conversation().equals(conversation)) current.typing(who);
    }

    // ------------------------------------------------------------------ calls

    @Override
    public void call(com.emirchat.call.Calls.Call c) {
        com.emirchat.call.Calls calls = session.client().calls();
        if (callTicker != null) callTicker.stop();
        if (callHide != null) callHide.stop();
        if (c.state() == com.emirchat.call.Calls.State.ENDED) {
            videoStage.clear();
            if (c.reason() == com.emirchat.call.Calls.EndReason.MISSED && !c.outgoing()) {
                callBars.notifyCall(c.peer(), I18n.t("call.missedFrom", c.peer()));
                if (calls.current() != null) return; // missed while talking to someone else: keep that bar
            }
            Label text = UiKit.label(CallBars.endText(c), "row-title");
            text.setWrapText(true);
            callBars.showCallBar(c.peer(), text);
            callHide = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(4));
            callHide.setOnFinished(e -> {
                if (calls.current() == null) callBar.setVisible(false);
            });
            callHide.play();
            return;
        }
        Label status = UiKit.label("", "row-sub");
        Label title = UiKit.label(c.peer(), "row-title");
        VBox text = new VBox(2, title, status);
        HBox buttons = new HBox(8);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        switch (c.state()) {
            case CALLING -> {
                status.setText(I18n.t("call.calling"));
                buttons.getChildren().add(CallBars.hangUpButton(I18n.t("call.cancel"), calls::hangUp));
            }
            case RINGING -> {
                status.setText(I18n.t("call.incoming"));
                Button accept = new Button(I18n.t("call.accept"));
                accept.setGraphic(Icons.of(Icons.PHONE, 14, "on-accent"));
                accept.getStyleClass().addAll("small", "primary");
                accept.setId("call-accept");
                accept.setOnAction(e -> {
                    if (!voiceState.joined()) {
                        calls.accept();
                        return;
                    }
                    session.background().execute(() -> { // the call takes the microphone: leave the room first
                        session.client().voice().leave();
                        calls.accept();
                    });
                });
                buttons.getChildren().addAll(CallBars.hangUpButton(I18n.t("call.reject"), calls::reject), accept);
                callBars.notifyCall(c.peer(), I18n.t("call.incomingFrom", c.peer()));
                callBars.ring();
            }
            case CONNECTED -> {
                videoStage.peer(c.peer());
                Button camera = callBars.videoButton(calls, false);
                Button share = callBars.videoButton(calls, true);
                Runnable tick = () -> {
                    status.setText("🔒 " + I18n.t("call.encrypted") + " · "
                            + (calls.direct() ? I18n.t("call.direct") + " · " : "")
                            + CallBars.duration(System.currentTimeMillis() - c.connectedAt()));
                    callBars.refreshVideoButtons(calls, camera, share);
                };
                tick.run();
                callTicker = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1),
                        e -> tick.run()));
                callTicker.setCycleCount(javafx.animation.Animation.INDEFINITE);
                callTicker.play();
                Button mute = new Button(c.muted() ? I18n.t("call.unmute") : I18n.t("call.mute"));
                mute.setGraphic(Icons.of(c.muted() ? Icons.MIC_OFF : Icons.MIC, 14, c.muted() ? "danger" : "muted"));
                mute.getStyleClass().add("small");
                mute.setId("call-mute");
                mute.setOnAction(e -> calls.setMuted(!c.muted()));
                buttons.getChildren().addAll(camera, share, mute, CallBars.hangUpButton(I18n.t("call.hangUp"), calls::hangUp));
            }
            default -> { }
        }
        HBox row = new HBox(10, text, UiKit.grow(), buttons);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(row, Priority.ALWAYS);
        callBars.showCallBar(c.peer(), row);
    }

    @Override
    public void notifyIncoming(History.Message m) {
        if (!session.setting(AccountSession.NOTIFICATIONS) || !Boolean.parseBoolean(System.getProperty("securechat.notifications", "true"))) return;
        boolean focused = stack.getScene() != null && stack.getScene().getWindow() != null
                && stack.getScene().getWindow().isFocused();
        boolean visible = current != null && current.conversation().equals(m.conversation());
        if (focused && visible) return;
        boolean mentioned = m.file() == null && Markdown.mentions(m.text(), session.client().username());
        NotifyRules.Decision decision = session.notifyRules().decide(m.conversation(),
                mentioned || m.conversation().startsWith("dm:"), NotifyRules.now());
        if (decision == NotifyRules.Decision.NONE) return;
        String where = m.conversation().startsWith("g:") ? sidebarUi.groupName(Long.parseLong(m.conversation().substring(2)))
                : m.sender();
        String title = m.conversation().startsWith("g:") ? I18n.t("notify.inGroup", m.sender(), where) : where;
        String body = session.setting(AccountSession.NOTIFICATION_PREVIEW)
                ? (m.file() != null ? (Sidebar.isVoiceName(m.text()) ? "🎤 " + I18n.t("voice.message") : "📎 " + m.text())
                        : Sidebar.specialPreview(m.text()) != null ? Sidebar.specialPreview(m.text())
                        : Markdown.plain(m.text()))
                : I18n.t("notify.newMessage");
        Tray.get().notify(title, body);
        if (decision == NotifyRules.Decision.NOTIFY) Sounds.play();
    }

    /** Called once the view is in a window: report reads when the window comes back into focus. */
    private void hookFocus() {
        if (focusHooked || stack.getScene() == null || stack.getScene().getWindow() == null) return;
        focusHooked = true;
        stack.getScene().getWindow().focusedProperty().addListener((o, a, focused) -> {
            if (focused && current != null) current.windowFocused();
            if (!focused) AppPrefs.setLastSeen(System.currentTimeMillis());
            else catchUp.maybeCatchUp();
        });
    }

    /** After a long enough absence (and with something to show), the welcome-back screen. */
    static final long AWAY_MS = 20 * 60_000;

    boolean overlayOpen() {
        return stack.lookup(".overlay") != null;
    }

    void dispose() {
        AppPrefs.setLastSeen(System.currentTimeMillis());
        if (current != null) current.dispose();
        UiKit.useKeys(null);
        Tray.get().setUnread(0);
        if (stack.getScene() != null && stack.getScene().getWindow() instanceof javafx.stage.Stage s) {
            s.setTitle(com.emirchat.AppInfo.NAME);
        }
    }

    /** Unread messages in the window title and the tray tooltip. */
    void showUnread() {
        int unread = 0;
        try {
            long now = System.currentTimeMillis();
            for (History.Summary s : session.history().summaries()) {
                if ((current == null || !current.conversation().equals(s.conversation()))
                        && session.notifyRules().counts(s.conversation(), now)) unread += s.unread();
            }
        } catch (IOException ignored) {
            // leave the title as it is
        }
        Tray.get().setUnread(unread);
        if (stack.getScene() != null && stack.getScene().getWindow() instanceof javafx.stage.Stage s) {
            s.setTitle(unread > 0 ? "(" + unread + ") " + com.emirchat.AppInfo.NAME : com.emirchat.AppInfo.NAME);
        }
    }

    @Override
    public void timerChanged(String conversation, String by, long seconds) {
        if (current != null && current.conversation().equals(conversation)) current.timerChanged(by, seconds);
    }

    // ------------------------------------------------------------------ helpers

    /** Runs a network call off the UI thread; shows errors in the open conversation or a dialog. */
    <T> void call(Callable<T> action, Consumer<T> onSuccess) {
        session.background().execute(() -> {
            try {
                T result = action.call();
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (ApiException | IOException | KeyChangedException e) {
                Platform.runLater(() -> showError(e instanceof ApiException api ? I18n.error(api) : e.getMessage()));
            } catch (Exception e) {
                Platform.runLater(() -> showError(I18n.t("shell.unexpected", e.getMessage())));
            }
        });
    }

    void showInfo(String title, String message) {
        Button ok = new Button(I18n.t("common.ok"));
        ok.getStyleClass().add("primary");
        ok.setOnAction(e -> overlay.close());
        Label text = UiKit.label(message, "body");
        text.setWrapText(true);
        overlay.show(Overlay.dialog(title, text, new HBox(UiKit.grow(), ok)));
    }

    void showError(String message) {
        Button ok = new Button(I18n.t("common.ok"));
        ok.getStyleClass().add("primary");
        ok.setOnAction(e -> overlay.close());
        Label text = UiKit.label(message, "body");
        text.setWrapText(true);
        VBox box = Overlay.dialog(I18n.t("main.errorTitle"), text, new HBox(UiKit.grow(), ok));
        box.setId("error-dialog");
        overlay.show(box);
    }
}
