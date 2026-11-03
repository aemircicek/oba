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
import javafx.scene.layout.Region;
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
    private final ToggleButton chatsTab = new ToggleButton(I18n.t("main.chats"));
    private final ToggleButton friendsTab = new ToggleButton(I18n.t("main.friends"));
    private final ToggleButton communitiesTab = new ToggleButton(I18n.t("community.tab"));
    private long selectedCommunity; // 0: the list of communities
    private final Label friendsBadge = UiKit.label("", "unread-badge");
    private ConversationView current;
    private final TextField search = new TextField();

    private boolean focusHooked;
    private final HBox updateBanner = new HBox(8);
    private final HBox callBar = new HBox(10);
    private final HBox voiceBar = new HBox(10);
    private com.emirchat.call.VoiceChannels.State voiceState = com.emirchat.call.VoiceChannels.State.IDLE;
    private Map<Long, List<String>> voiceRooms = Map.of();
    private javafx.animation.Timeline callTicker;
    private javafx.animation.PauseTransition callHide;
    /** The strip at the far left: home, one icon per community, and "+" (like a server list). */
    private final VBox rail = new VBox(8);
    private final Label connectionLabel = UiKit.label(I18n.t("main.connected"), "row-sub", "success-text");
    private final Tail connectionTail = Tail.status(16);

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
        me.setOnAction(e -> openMyKey());
        HBox connection = new HBox(5, connectionTail, connectionLabel);
        connection.setAlignment(Pos.CENTER_LEFT);
        VBox meText = new VBox(1, UiKit.label(client.username(), "me-name"), connection);
        Button settings = new Button();
        settings.setGraphic(Icons.of(Icons.SETTINGS, 16, null));
        settings.getStyleClass().add("icon-button");
        settings.setTooltip(new Tooltip(I18n.t("main.settings")));
        settings.setId("settings");
        settings.setOnAction(e -> openSettings());
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
            refreshSidebar();
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
        search.textProperty().addListener((o, a, b) -> refreshSidebar());
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
        frame.setCenter(new VBox(callBar, voiceBar, content));
        stack.getChildren().add(frame);
        stack.getStyleClass().add("main-view");
        stack.setUserData(this); // lets the preview tool open the welcome-back screen
        stack.sceneProperty().addListener((o, a, scene) -> {
            if (scene != null) scene.getAccelerators().put(
                    new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.K,
                            javafx.scene.input.KeyCombination.SHORTCUT_DOWN), this::openPalette);
        });
        refreshSidebar();
        session.setView(this);
        if (AppPrefs.checkUpdates()) {
            session.downloads().execute(() -> UpdateCheck.newerVersion().ifPresent(v -> Platform.runLater(() -> showUpdate(v))));
        }
        if (!AppPrefs.welcomeShown()) Platform.runLater(() -> openWelcome(0));
        else Platform.runLater(this::maybeCatchUp);
    }

    private void showUpdate(String version) {
        AppLog.info("Update available: " + version);
        Label text = UiKit.label(I18n.t("update.available", version), "row-sub");
        text.setWrapText(true);
        Button get = new Button(I18n.t("update.download"));
        get.getStyleClass().addAll("small", "primary");
        get.setId("update-download");
        get.setOnAction(e -> browse(UpdateCheck.RELEASES));
        updateBanner.getChildren().setAll(Icons.of(Icons.DOWNLOAD, 16, "accent"), text, UiKit.grow(), get);
        updateBanner.getStyleClass().setAll("update-banner");
        updateBanner.setAlignment(Pos.CENTER_LEFT);
        updateBanner.setVisible(true);
    }

    /** Opens a web page in the default browser (off the UI thread: AWT may need a moment). */
    static void browse(String url) {
        new Thread(() -> {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            } catch (Exception e) {
                AppLog.warn("Could not open " + url, e);
            }
        }, "browse").start();
    }

    private static void openFolder(java.nio.file.Path folder) {
        new Thread(() -> {
            try {
                java.awt.Desktop.getDesktop().open(folder.toFile());
            } catch (Exception e) {
                AppLog.warn("Could not open " + folder, e);
            }
        }, "open-folder").start();
    }

    // ------------------------------------------------------------------ first start

    /** A short tour the first time someone signs in. */
    private void openWelcome(int page) {
        String[][] pages = {
                {Icons.LOCK, "welcome.title1", "welcome.body1"},
                {Icons.SHIELD_CHECK, "welcome.title2", "welcome.body2"},
                {Icons.KEY, "welcome.title3", "welcome.body3"}};
        String[] p = pages[page];
        StackPane badge = new StackPane(Icons.of(p[0], 28, "accent"));
        badge.getStyleClass().addAll("icon-badge", "accent");
        Label body = UiKit.label(I18n.t(p[2]), "body");
        body.setWrapText(true);
        body.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        Label step = UiKit.label((page + 1) + " / " + pages.length, "caption");
        Button back = new Button(I18n.t("welcome.back"));
        back.setVisible(page > 0);
        back.setOnAction(e -> openWelcome(page - 1));
        boolean last = page == pages.length - 1;
        Button next = new Button(last ? I18n.t("welcome.start") : I18n.t("welcome.next"));
        next.getStyleClass().add("primary");
        next.setId("welcome-next");
        next.setDefaultButton(true);
        next.setOnAction(e -> {
            if (last) {
                AppPrefs.setWelcomeShown();
                overlay.close();
            } else {
                openWelcome(page + 1);
            }
        });
        VBox box = Overlay.dialog(I18n.t(p[1]), badge, body, new HBox(10, step, UiKit.grow(), back, next));
        box.setId("welcome-dialog");
        overlay.show(box);
    }

    private void openAbout() {
        javafx.scene.image.ImageView logo = new javafx.scene.image.ImageView(
                new javafx.scene.image.Image(MainView.class.getResourceAsStream("icons/icon-128.png")));
        logo.setFitWidth(72);
        logo.setPreserveRatio(true);
        Label name = UiKit.label(com.emirchat.AppInfo.NAME + " " + com.emirchat.AppInfo.version(), "h2");
        Label what = UiKit.label(I18n.t("about.description"), "body");
        what.setWrapText(true);
        Label license = UiKit.label(I18n.t("about.license"), "caption");
        license.setWrapText(true);
        Button site = new Button(I18n.t("about.website"));
        site.getStyleClass().add("link");
        site.setOnAction(e -> browse(com.emirchat.AppInfo.WEBSITE));
        Button notices = new Button(I18n.t("about.notices"));
        notices.getStyleClass().add("link");
        notices.setOnAction(e -> browse(com.emirchat.AppInfo.WEBSITE + "/blob/main/THIRD-PARTY-NOTICES.md"));
        Button close = new Button(I18n.t("common.close"));
        close.getStyleClass().add("primary");
        close.setOnAction(e -> overlay.close());
        VBox head = new VBox(8, logo, name, what);
        head.setAlignment(Pos.CENTER);
        VBox box = Overlay.dialog(I18n.t("about.title"), head, license, new HBox(10, site, notices, UiKit.grow(), close));
        box.setId("about-dialog");
        overlay.show(box);
    }

    Parent root() {
        return stack;
    }

    // ------------------------------------------------------------------ sidebar

    /** Home, then every community as a rounded icon with an unread dot, then "+". */
    private void buildRail() {
        rail.getChildren().clear();
        boolean home = !communitiesTab.isSelected();
        Map<String, History.Summary> summaries = summaries();
        int homeUnread = 0;
        for (History.Summary s : summaries.values()) {
            if (s.conversation().startsWith("dm:") || isPlainGroup(s.conversation())) homeUnread += unread(s.conversation(), summaries);
        }
        javafx.scene.image.ImageView logo = new javafx.scene.image.ImageView(
                new javafx.scene.image.Image(MainView.class.getResourceAsStream("icons/icon-64.png")));
        logo.setFitWidth(30);
        logo.setPreserveRatio(true);
        rail.getChildren().add(railItem(logo, I18n.t("rail.home"), home, homeUnread > 0, "rail-home", () -> {
            selectedCommunity = 0;
            chatsTab.setSelected(true);
        }));
        Region line = new Region();
        line.getStyleClass().add("rail-line");
        rail.getChildren().add(line);
        ChatClient c = session.client();
        List<Packet.GroupInfo> roots = c.groups().stream().filter(Packet.GroupInfo::isCommunity)
                .sorted(java.util.Comparator.comparing(ConversationView::groupName, String.CASE_INSENSITIVE_ORDER)).toList();
        for (Packet.GroupInfo root : roots) {
            int unread = c.channels(root.id()).stream().mapToInt(ch -> unread(AccountSession.group(ch.id()), summaries)).sum();
            boolean selected = communitiesTab.isSelected() && selectedCommunity == root.id();
            String name = ConversationView.groupName(root);
            rail.getChildren().add(railItem(UiKit.communityIcon(name, root.id()), name, selected, unread > 0,
                    "rail-community-" + root.id(), () -> {
                        selectedCommunity = root.id();
                        open(AccountSession.group(lastChannel.getOrDefault(root.id(), root.id())));
                    }));
        }
        Label plus = UiKit.label("+", "rail-plus");
        rail.getChildren().add(railItem(plus, I18n.t("rail.add"), communitiesTab.isSelected() && selectedCommunity == 0, false,
                "rail-add", () -> {
                    selectedCommunity = 0;
                    if (!communitiesTab.isSelected()) communitiesTab.setSelected(true);
                    else refreshSidebar();
                }));
    }

    /** Which channel was last open in each community, so coming back lands there. */
    private final Map<Long, Long> lastChannel = new java.util.HashMap<>();

    private boolean isPlainGroup(String conversation) {
        if (!conversation.startsWith("g:")) return false;
        Packet.GroupInfo g = group(Long.parseLong(conversation.substring(2)));
        return g != null && g.community() == 0;
    }

    private Node railItem(Node icon, String tip, boolean selected, boolean unread, String id, Runnable action) {
        StackPane face = new StackPane(icon);
        face.getStyleClass().add("rail-face");
        if (selected) face.getStyleClass().add("selected");
        Region pill = new Region();
        pill.getStyleClass().add("rail-pill");
        pill.setVisible(selected || unread);
        if (!selected) pill.getStyleClass().add("small");
        StackPane item = new StackPane(face, pill);
        StackPane.setAlignment(pill, Pos.CENTER_LEFT);
        item.getStyleClass().add("rail-item");
        item.setId(id);
        Tooltip.install(item, new Tooltip(tip));
        item.setOnMouseClicked(e -> action.run());
        return item;
    }

    private void refreshSidebar() {
        buildRail();
        Packet.FriendList friends = session.client().friends();
        int requests = friends.incoming().size();
        friendsBadge.setText(String.valueOf(requests));
        friendsBadge.setVisible(requests > 0);
        list.getChildren().clear();
        showUnread();
        if (communitiesTab.isSelected()) buildCommunities();
        else if (!chatsTab.isSelected()) buildFriends(friends);
        else if (!search.getText().isBlank()) buildSearch(search.getText());
        else buildChats();
    }

    /** Search results across all conversations (decrypted locally; nothing is sent anywhere). */
    private void buildSearch(String query) {
        list.getChildren().add(heading(I18n.t("main.results")));
        List<History.Message> found;
        try {
            found = session.history().search(query, 50);
        } catch (IOException e) {
            found = List.of();
        }
        if (found.isEmpty()) {
            Label none = UiKit.label(I18n.t("main.noResults", query.strip()), "caption");
            none.setPadding(new Insets(6, 14, 0, 14));
            list.getChildren().add(none);
        }
        for (History.Message m : found) {
            String where = m.conversation().startsWith("g:") ? groupName(Long.parseLong(m.conversation().substring(2)))
                    : m.conversation().substring(3);
            Label sub = UiKit.label((m.outgoing() ? "You: " : m.conversation().startsWith("g:") ? m.sender() + ": " : "")
                    + (m.file() != null ? "📎 " : "") + Markdown.plain(m.text()), "row-sub");
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
        return session.client().groups().stream().filter(g -> g.id() == id)
                .map(g -> g.community() == 0 ? ConversationView.groupName(g)
                        : communityName(g.community()) + " › " + ConversationView.channelTitle(g))
                .findFirst()
                .orElse(I18n.t("main.formerGroup"));
    }

    // ------------------------------------------------------------------ communities

    private String communityName(long id) {
        Packet.GroupInfo root = group(id);
        return root == null ? I18n.t("main.formerGroup") : ConversationView.groupName(root);
    }

    private int unread(String conversation, Map<String, History.Summary> summaries) {
        History.Summary s = summaries.get(conversation);
        boolean open = current != null && current.conversation().equals(conversation);
        return s == null || open ? 0 : s.unread();
    }

    private Map<String, History.Summary> summaries() {
        Map<String, History.Summary> map = new java.util.HashMap<>();
        try {
            for (History.Summary s : session.history().summaries()) map.put(s.conversation(), s);
        } catch (IOException ignored) {
            // no counts
        }
        return map;
    }

    private void buildCommunities() {
        ChatClient c = session.client();
        if (selectedCommunity != 0 && group(selectedCommunity) == null) selectedCommunity = 0; // left or removed
        Map<String, History.Summary> summaries = summaries();
        if (selectedCommunity == 0) {
            Button create = new Button(I18n.t("community.new"));
            create.setId("new-community");
            create.getStyleClass().add("small");
            create.setOnAction(e -> openNewCommunity());
            Button join = new Button(I18n.t("community.join"));
            join.setId("join-community");
            join.getStyleClass().add("small");
            join.setOnAction(e -> openJoinCommunity());
            HBox heading = new HBox(6, UiKit.label(I18n.t("community.heading"), "overline"), UiKit.grow(), join, create);
            heading.getStyleClass().add("list-heading");
            heading.setAlignment(Pos.CENTER_LEFT);
            list.getChildren().add(heading);
            List<Packet.GroupInfo> roots = c.groups().stream().filter(Packet.GroupInfo::isCommunity)
                    .sorted(java.util.Comparator.comparing(ConversationView::groupName, String.CASE_INSENSITIVE_ORDER)).toList();
            if (roots.isEmpty()) {
                Label empty = UiKit.label(I18n.t("community.none"), "caption");
                empty.setWrapText(true);
                VBox box = new VBox(10, Illustrations.desert(220), empty);
                box.setAlignment(Pos.CENTER);
                box.setPadding(new Insets(14, 14, 0, 14));
                list.getChildren().add(box);
            }
            for (Packet.GroupInfo root : roots) {
                int unread = c.channels(root.id()).stream().mapToInt(ch -> unread(AccountSession.group(ch.id()), summaries)).sum();
                String name = ConversationView.groupName(root);
                VBox text = new VBox(2, UiKit.label(name, "row-title"),
                        UiKit.label(I18n.t("community.summary", root.members().size(), c.channels(root.id()).size()), "row-sub"));
                text.setMinWidth(0);
                HBox row = new HBox(10, UiKit.avatar(name, "avatar-sm", true), text, UiKit.grow());
                if (unread > 0) row.getChildren().add(UiKit.label(String.valueOf(unread), "unread-badge"));
                row.getStyleClass().add("list-row");
                row.setAlignment(Pos.CENTER_LEFT);
                row.setId("community-" + root.id());
                row.setOnMouseClicked(e -> {
                    selectedCommunity = root.id();
                    open(AccountSession.group(root.id()));
                });
                list.getChildren().add(row);
            }
            return;
        }
        Packet.GroupInfo root = group(selectedCommunity);
        boolean manager = root.canManage(c.username());
        Button back = new Button("←");
        back.getStyleClass().addAll("small", "attach");
        back.setId("community-back");
        back.setOnAction(e -> {
            selectedCommunity = 0;
            refreshSidebar();
        });
        Label name = UiKit.label(ConversationView.groupName(root), "row-title");
        name.setMinWidth(0);
        Button settings = new Button();
        settings.setGraphic(Icons.of(Icons.SETTINGS, 15, "muted"));
        settings.getStyleClass().addAll("small", "attach");
        settings.setTooltip(new Tooltip(I18n.t("community.settings")));
        settings.setId("community-settings");
        settings.setOnAction(e -> openCommunityInfo(root.id()));
        HBox top = new HBox(6, back, name, UiKit.grow(), settings);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(4, 10, 4, 6));
        list.getChildren().add(top);
        if (manager) {
            Button invite = new Button(I18n.t("community.invite"));
            invite.getStyleClass().addAll("small", "primary");
            invite.setId("community-invite");
            invite.setOnAction(e -> openInvite(root.id()));
            Button addChannel = new Button(I18n.t("community.addChannel"));
            addChannel.getStyleClass().add("small");
            addChannel.setId("add-channel");
            addChannel.setOnAction(e -> openNewChannel(root.id()));
            HBox actions = new HBox(8, invite, addChannel);
            actions.setPadding(new Insets(0, 14, 6, 14));
            list.getChildren().add(actions);
        }
        list.getChildren().add(heading(I18n.t("community.channels")));
        for (Packet.GroupInfo ch : c.channels(root.id())) {
            String conversation = AccountSession.group(ch.id());
            Label title = UiKit.label(ConversationView.channelTitle(ch), "row-title");
            HBox row = new HBox(8, title, UiKit.grow());
            int unread = unread(conversation, summaries);
            if (unread > 0) {
                row.getChildren().add(UiKit.label(String.valueOf(unread), "unread-badge"));
                title.setStyle("-fx-font-weight: bold;");
            }
            boolean open = current != null && current.conversation().equals(conversation);
            row.getStyleClass().addAll("list-row", "channel-row");
            if (open) row.getStyleClass().add("selected");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setId("channel-" + ch.id());
            row.setOnMouseClicked(e -> open(conversation));
            list.getChildren().add(row);
            addVoiceParticipants(ch.id());
        }
    }

    /** Who is in a channel's (or group's) voice room, under its row: faces light up while they talk. */
    private void addVoiceParticipants(long groupId) {
        List<String> people = voiceRooms.getOrDefault(groupId, List.of());
        if (people.isEmpty()) return;
        VBox box = new VBox(2);
        box.getStyleClass().add("voice-people");
        for (String u : people) {
            boolean talking = voiceState.groupId() == groupId && voiceState.speaking().contains(u);
            Label name = UiKit.label(u.equals(session.client().username()) ? I18n.t("group.you", u) : u, "voice-name");
            HBox row = new HBox(8, UiKit.voiceFace(u, talking, 22), name);
            if (voiceState.groupId() == groupId && u.equals(session.client().username()) && voiceState.muted()) {
                row.getChildren().add(Icons.of(Icons.MIC_OFF, 12, "danger"));
            }
            row.setAlignment(Pos.CENTER_LEFT);
            row.setId("voice-person-" + groupId + "-" + u);
            box.getChildren().add(row);
        }
        list.getChildren().add(box);
    }

    private void openNewCommunity() {
        TextField name = new TextField();
        name.setId("community-name");
        name.setPromptText(I18n.t("community.namePrompt"));
        Label explain = UiKit.label(I18n.t("community.newExplain"), "caption");
        explain.setWrapText(true);
        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> overlay.close());
        Button create = new Button(I18n.t("community.create"));
        create.getStyleClass().add("primary");
        create.setId("create-community");
        create.disableProperty().bind(name.textProperty().isEmpty());
        create.setOnAction(e -> call(() -> session.client().createCommunity(name.getText()), g -> {
            overlay.close();
            selectedCommunity = g.id();
            communitiesTab.setSelected(true);
            open(AccountSession.group(g.id()));
        }));
        overlay.show(Overlay.dialog(I18n.t("community.new"), explain, name, new HBox(10, UiKit.grow(), cancel, create)));
        Platform.runLater(name::requestFocus);
    }

    private void openJoinCommunity() {
        TextField code = new TextField();
        code.setId("invite-code");
        code.setPromptText("XXXXX-XXXXX-XXXXX-XXXXX-XXXXX-X");
        Label explain = UiKit.label(I18n.t("community.joinExplain"), "caption");
        explain.setWrapText(true);
        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> overlay.close());
        Button join = new Button(I18n.t("community.joinButton"));
        join.getStyleClass().add("primary");
        join.setId("join-confirm");
        join.disableProperty().bind(code.textProperty().length().lessThan(10));
        join.setOnAction(e -> call(() -> session.client().joinCommunity(code.getText()), g -> {
            overlay.close();
            selectedCommunity = g.id();
            communitiesTab.setSelected(true);
            open(AccountSession.group(g.id()));
        }));
        overlay.show(Overlay.dialog(I18n.t("community.join"), explain, code, new HBox(10, UiKit.grow(), cancel, join)));
        Platform.runLater(code::requestFocus);
    }

    private void openNewChannel(long communityId) {
        TextField name = new TextField();
        name.setId("channel-name");
        name.setPromptText(I18n.t("community.channelPrompt"));
        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> overlay.close());
        Button create = new Button(I18n.t("community.addChannel"));
        create.getStyleClass().add("primary");
        create.setId("create-channel");
        create.disableProperty().bind(name.textProperty().isEmpty());
        create.setOnAction(e -> call(() -> session.client().createChannel(communityId,
                name.getText().strip().replaceFirst("^#\\s*", "")), g -> {
            overlay.close();
            open(AccountSession.group(g.id()));
        }));
        overlay.show(Overlay.dialog(I18n.t("community.addChannel"), name, new HBox(10, UiKit.grow(), cancel, create)));
        Platform.runLater(name::requestFocus);
    }

    /** A fresh invite code, with how long and how often it works. */
    private void openInvite(long communityId) {
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
        make.setOnAction(e -> call(() -> session.client().createInvite(communityId,
                limits[uses.getSelectionModel().getSelectedIndex()], hours[valid.getSelectionModel().getSelectedIndex()]), code -> {
            codeLabel.setText(code);
            copy.setDisable(false);
        }));
        Button revoke = new Button(I18n.t("invite.revoke"));
        revoke.getStyleClass().addAll("small", "danger-ghost");
        revoke.setOnAction(e -> call(() -> {
            session.client().revokeInvites(communityId);
            return null;
        }, r -> {
            codeLabel.setText("");
            copy.setDisable(true);
            note.setText(I18n.t("invite.revoked"));
        }));
        Button close = new Button(I18n.t("common.close"));
        close.setId("invite-close");
        close.setOnAction(e -> overlay.close());
        HBox options = new HBox(10, UiKit.label(I18n.t("invite.valid"), "field-label"), valid,
                UiKit.label(I18n.t("invite.uses"), "field-label"), uses);
        options.setAlignment(Pos.CENTER_LEFT);
        HBox codeRow = new HBox(10, codeLabel, UiKit.grow(), copy);
        codeRow.setAlignment(Pos.CENTER_LEFT);
        codeRow.getStyleClass().add("card-flat");
        VBox box = Overlay.dialog(I18n.t("invite.title", communityName(communityId)), options, make, codeRow, note,
                new HBox(10, revoke, UiKit.grow(), close));
        box.setId("invite-dialog");
        overlay.show(box);
    }

    /** Members and roles, renaming and leaving. */
    private void openCommunityInfo(long communityId) {
        ChatClient c = session.client();
        Packet.GroupInfo root = group(communityId);
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
                role.setOnAction(e -> call(() -> {
                    c.setAdmin(communityId, who, !isAdmin);
                    return null;
                }, r -> overlay.close()));
                right.getChildren().add(role);
            }
            if (manager && !who.equals(me) && !isOwner && (owner || !isAdmin)) {
                Button remove = new Button(I18n.t("group.remove"));
                remove.getStyleClass().addAll("small", "danger-ghost");
                remove.setId("remove-" + who);
                remove.setOnAction(e -> call(() -> {
                    c.removeGroupMember(communityId, who);
                    return null;
                }, r -> overlay.close()));
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
            rename.setOnAction(e -> call(() -> {
                c.renameGroup(communityId, newName.getText());
                return null;
            }, r -> overlay.close()));
            box.getChildren().add(new HBox(8, newName, rename));
        }
        Button leave = new Button(I18n.t("community.leave"));
        leave.setId("leave-community");
        leave.getStyleClass().add("danger-ghost");
        leave.setOnAction(e -> call(() -> {
            c.leaveGroup(communityId);
            return null;
        }, r -> {
            overlay.close();
            selectedCommunity = 0;
            showEmpty();
            refreshSidebar();
        }));
        Button close = new Button(I18n.t("common.close"));
        close.setOnAction(e -> overlay.close());
        box.getChildren().add(new HBox(10, leave, UiKit.grow(), close));
        box.setId("community-dialog");
        overlay.show(box);
    }

    // ------------------------------------------------------------------ settings

    private void openSettings() {
        VBox toggles = new VBox(10,
                toggle(I18n.t("settings.receipts"), I18n.t("settings.receiptsHint"),
                        AccountSession.SEND_READ_RECEIPTS),
                toggle(I18n.t("settings.typing"), I18n.t("settings.typingHint"),
                        AccountSession.SEND_TYPING),
                toggle(I18n.t("settings.clock"), I18n.t("settings.clockHint"),
                        AccountSession.SHARE_CLOCK),
                toggle(I18n.t("settings.notifications"), I18n.t("settings.notificationsHint"),
                        AccountSession.NOTIFICATIONS),
                toggle(I18n.t("settings.preview"), I18n.t("settings.previewHint"),
                        AccountSession.NOTIFICATION_PREVIEW));
        if (Tray.get().available()) {
            toggles.getChildren().add(appToggle(I18n.t("settings.keepRunning"), I18n.t("settings.keepRunningHint"),
                    "setting-keep-running", AppPrefs.keepRunning(), AppPrefs::setKeepRunning));
        }
        toggles.getChildren().add(appToggle(I18n.t("settings.updates"), I18n.t("settings.updatesHint"),
                "setting-updates", AppPrefs.checkUpdates(), AppPrefs::setCheckUpdates));
        if (Autostart.supported()) {
            toggles.getChildren().add(appToggle(I18n.t("settings.autostart"), I18n.t("settings.autostartHint"),
                    "setting-autostart", Autostart.enabled(), on -> {
                        try {
                            Autostart.set(on);
                        } catch (IOException ex) {
                            showError(ex.getMessage());
                        }
                    }));
        }
        Button done = new Button(I18n.t("common.done"));
        done.getStyleClass().add("primary");
        done.setId("settings-done");
        done.setOnAction(e -> overlay.close());
        javafx.scene.control.ComboBox<String> language = new javafx.scene.control.ComboBox<>();
        language.setId("setting-language");
        String[] codes = {I18n.SYSTEM, "en", "tr"};
        language.getItems().addAll(I18n.t("settings.languageSystem"), "English", "Türkçe");
        language.getSelectionModel().select(java.util.Arrays.asList(codes).indexOf(I18n.choice()));
        Label restartNote = UiKit.label("", "caption");
        language.setOnAction(e -> {
            I18n.choose(codes[language.getSelectionModel().getSelectedIndex()]);
            restartNote.setText(I18n.t("settings.languageRestart"));
        });
        HBox languageRow = new HBox(10, UiKit.label(I18n.t("settings.language"), "field-label"), language, restartNote);
        languageRow.setAlignment(Pos.CENTER_LEFT);
        toggles.getChildren().add(0, languageRow);
        Button about = new Button(I18n.t("about.title"));
        about.getStyleClass().add("link");
        about.setId("about");
        about.setOnAction(e -> openAbout());
        Button logs = new Button(I18n.t("settings.openLogs"));
        logs.getStyleClass().add("link");
        logs.setVisible(AppLog.folder() != null);
        logs.managedProperty().bind(logs.visibleProperty());
        logs.setOnAction(e -> openFolder(AppLog.folder()));
        toggles.getChildren().add(new HBox(14, about, logs));
        Button backup = new Button(I18n.t("backup.button"));
        backup.setId("backup");
        backup.setOnAction(e -> openBackup());
        Button looks = new Button(I18n.t("appearance.title"));
        looks.setId("appearance");
        looks.setOnAction(e -> openAppearance());
        VBox box = Overlay.dialog(I18n.t("main.settings"), toggles, new HBox(10, backup, looks, UiKit.grow(), done));
        box.setId("settings-dialog");
        overlay.show(box);
    }

    /** Theme, accent colour, text size, density, motion and the notification sound; applied at once. */
    private void openAppearance() {
        ComboBox<String> theme = new ComboBox<>();
        theme.setId("appearance-theme");
        String[] themes = {"system", "light", "dark"};
        theme.getItems().addAll(I18n.t("appearance.system"), I18n.t("appearance.light"), I18n.t("appearance.dark"));
        theme.getSelectionModel().select(java.util.Arrays.asList(themes).indexOf(AppPrefs.theme()));
        theme.setOnAction(e -> {
            AppPrefs.setTheme(themes[theme.getSelectionModel().getSelectedIndex()]);
            Theme.refreshAll();
            refreshSidebar();
        });
        HBox swatches = new HBox(10);
        swatches.setAlignment(Pos.CENTER_LEFT);
        Runnable[] drawSwatches = new Runnable[1];
        drawSwatches[0] = () -> {
            swatches.getChildren().clear();
            for (String name : Appearance.ACCENTS.keySet()) {
                String[][] palette = Appearance.ACCENTS.get(name);
                String hex = palette == null ? (Theme.isDark() ? "#f2a43a" : "#c0650c") : palette[Theme.isDark() ? 1 : 0][0];
                javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(13, javafx.scene.paint.Color.web(hex));
                StackPane swatch = new StackPane(dot);
                swatch.getStyleClass().add("swatch");
                if (name.equals(AppPrefs.accent())) swatch.getStyleClass().add("selected");
                swatch.setId("accent-" + name);
                Tooltip.install(swatch, new Tooltip(accentName(name)));
                swatch.setOnMouseClicked(e -> {
                    AppPrefs.setAccent(name);
                    Theme.refreshAll();
                    drawSwatches[0].run();
                    refreshSidebar();
                    if (current != null) current.refreshHeader();
                });
                swatches.getChildren().add(swatch);
            }
        };
        drawSwatches[0].run();
        ComboBox<String> size = new ComboBox<>();
        size.setId("appearance-size");
        String[] sizes = {"small", "normal", "large"};
        size.getItems().addAll(I18n.t("appearance.small"), I18n.t("appearance.normal"), I18n.t("appearance.large"));
        size.getSelectionModel().select(java.util.Arrays.asList(sizes).indexOf(AppPrefs.textSize()));
        size.setOnAction(e -> {
            AppPrefs.setTextSize(sizes[size.getSelectionModel().getSelectedIndex()]);
            Theme.refreshAll();
        });
        ComboBox<String> sound = new ComboBox<>();
        sound.setId("appearance-sound");
        for (String n : Sounds.NAMES) sound.getItems().add(soundName(n));
        sound.getSelectionModel().select(Math.max(0, Sounds.NAMES.indexOf(AppPrefs.sound())));
        sound.setOnAction(e -> {
            String chosen = Sounds.NAMES.get(sound.getSelectionModel().getSelectedIndex());
            AppPrefs.setSound(chosen);
            Sounds.play(chosen);
        });
        Button listen = new Button("▶");
        listen.getStyleClass().add("small");
        listen.setTooltip(new Tooltip(I18n.t("appearance.listen")));
        listen.setOnAction(e -> Sounds.play(AppPrefs.sound()));
        VBox rows = new VBox(14,
                field(I18n.t("appearance.theme"), theme),
                field(I18n.t("appearance.accent"), swatches),
                field(I18n.t("appearance.textSize"), size),
                appToggle(I18n.t("appearance.compact"), I18n.t("appearance.compactHint"), "appearance-compact",
                        AppPrefs.compact(), on -> {
                            AppPrefs.setCompact(on);
                            Theme.refreshAll();
                        }),
                appToggle(I18n.t("appearance.motion"), I18n.t("appearance.motionHint"), "appearance-motion",
                        AppPrefs.reduceMotion(), AppPrefs::setReduceMotion),
                field(I18n.t("appearance.sound"), new HBox(8, sound, listen)));
        Button done = new Button(I18n.t("common.done"));
        done.getStyleClass().add("primary");
        done.setId("appearance-done");
        done.setOnAction(e -> overlay.close());
        VBox box = Overlay.dialog(I18n.t("appearance.title"), rows, new HBox(UiKit.grow(), done));
        box.setId("appearance-dialog");
        overlay.show(box);
    }

    private static Node field(String label, Node control) {
        Label l = UiKit.label(label, "field-label");
        l.setMinWidth(120);
        HBox row = new HBox(12, l, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static String accentName(String name) {
        return switch (name) {
            case "venom" -> I18n.t("appearance.venom");
            case "sky" -> I18n.t("appearance.sky");
            case "rose" -> I18n.t("appearance.rose");
            case "violet" -> I18n.t("appearance.violet");
            default -> I18n.t("appearance.amber");
        };
    }

    private static String soundName(String name) {
        return switch (name) {
            case "tock" -> I18n.t("sound.tock");
            case "drop" -> I18n.t("sound.drop");
            case "rattle" -> I18n.t("sound.rattle");
            case "off" -> I18n.t("sound.off");
            default -> I18n.t("sound.chime");
        };
    }

    // ------------------------------------------------------------------ quick switcher

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
    }

    /** Everything the quick switcher can jump to: recent chats first, then channels, friends and actions. */
    private List<Palette.Entry> paletteEntries() {
        ChatClient c = session.client();
        List<Palette.Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            for (History.Summary s : session.history().summaries()) {
                String conv = s.conversation();
                if (conv.startsWith("dm:")) {
                    out.add(new Palette.Entry(Palette.Kind.CHAT, conv.substring(3), I18n.t("palette.dm"), conv, () -> open(conv)));
                } else {
                    Packet.GroupInfo g = group(Long.parseLong(conv.substring(2)));
                    if (g == null) continue;
                    String title = g.community() != 0 ? ConversationView.channelTitle(g) : ConversationView.groupName(g);
                    String sub = g.community() != 0 ? communityName(g.community()) : I18n.t("palette.group");
                    out.add(new Palette.Entry(g.community() != 0 ? Palette.Kind.CHANNEL : Palette.Kind.CHAT, title, sub, conv, () -> open(conv)));
                }
                seen.add(conv);
            }
        } catch (IOException ignored) {
            // the rest still works
        }
        for (Packet.GroupInfo g : c.groups()) {
            String conv = AccountSession.group(g.id());
            if (seen.contains(conv)) continue;
            if (g.isCommunity()) {
                out.add(new Palette.Entry(Palette.Kind.COMMUNITY, ConversationView.groupName(g), I18n.t("palette.community"), conv, () -> {
                    selectedCommunity = g.id();
                    open(conv);
                }));
            } else {
                String title = g.community() != 0 ? ConversationView.channelTitle(g) : ConversationView.groupName(g);
                String sub = g.community() != 0 ? communityName(g.community()) : I18n.t("palette.group");
                out.add(new Palette.Entry(g.community() != 0 ? Palette.Kind.CHANNEL : Palette.Kind.CHAT, title, sub, conv, () -> open(conv)));
            }
        }
        for (Packet.Friend f : c.friends().friends()) {
            String conv = AccountSession.dm(f.username());
            if (!seen.contains(conv)) {
                out.add(new Palette.Entry(Palette.Kind.FRIEND, f.username(), I18n.t("palette.friend"), conv, () -> open(conv)));
            }
        }
        out.add(new Palette.Entry(Palette.Kind.ACTION, I18n.t("main.settings"), I18n.t("palette.action"), "settings", this::openSettings));
        out.add(new Palette.Entry(Palette.Kind.ACTION, I18n.t("appearance.title"), I18n.t("palette.action"), "appearance", this::openAppearance));
        out.add(new Palette.Entry(Palette.Kind.ACTION, I18n.t("conv.newGroup"), I18n.t("palette.action"), "new-group", this::openNewGroup));
        out.add(new Palette.Entry(Palette.Kind.ACTION, I18n.t("community.new"), I18n.t("palette.action"), "new-community", this::openNewCommunity));
        out.add(new Palette.Entry(Palette.Kind.ACTION, I18n.t("community.join"), I18n.t("palette.action"), "join-community", this::openJoinCommunity));
        out.add(new Palette.Entry(Palette.Kind.ACTION, I18n.t("main.myKey"), I18n.t("palette.action"), "my-key", this::openMyKey));
        out.add(new Palette.Entry(Palette.Kind.ACTION, I18n.t("catchup.title"), I18n.t("palette.action"), "catch-up",
                () -> showCatchUp(0, true)));
        out.add(new Palette.Entry(Palette.Kind.ACTION, Theme.isDark() ? I18n.t("palette.light") : I18n.t("palette.dark"),
                I18n.t("palette.action"), "theme", () -> {
                    AppPrefs.setTheme(Theme.isDark() ? "light" : "dark");
                    Theme.refreshAll();
                    refreshSidebar();
                    if (current != null) current.refreshHeader();
                }));
        return out;
    }

    private void openPalette() {
        List<Palette.Entry> entries = paletteEntries();
        TextField query = new TextField();
        query.setId("palette-query");
        query.setPromptText(I18n.t("palette.prompt"));
        query.getStyleClass().add("palette-query");
        VBox results = new VBox(2);
        results.setId("palette-results");
        List<Palette.Entry> shown = new ArrayList<>();
        int[] selected = {0};
        Runnable[] render = new Runnable[1];
        java.util.function.Consumer<Palette.Entry> go = entry -> {
            overlay.close();
            entry.action().run();
        };
        render[0] = () -> {
            shown.clear();
            shown.addAll(Palette.rank(query.getText(), entries, 9));
            selected[0] = Math.min(selected[0], Math.max(0, shown.size() - 1));
            results.getChildren().clear();
            if (shown.isEmpty()) results.getChildren().add(UiKit.label(I18n.t("palette.nothing"), "caption"));
            for (int i = 0; i < shown.size(); i++) {
                Palette.Entry entry = shown.get(i);
                Node icon = switch (entry.kind()) {
                    case CHAT -> entry.id().startsWith("g:") ? UiKit.avatar(entry.title(), "avatar-xs", true)
                            : UiKit.avatar(entry.title(), "avatar-xs", false);
                    case FRIEND -> UiKit.avatar(entry.title(), "avatar-xs", false);
                    case CHANNEL -> Icons.of(Icons.MESSAGE, 16, "muted");
                    case COMMUNITY -> Icons.of(Icons.USERS, 16, "muted");
                    case ACTION -> Icons.of(Icons.SETTINGS, 16, "muted");
                };
                StackPane iconBox = new StackPane(icon);
                iconBox.setMinWidth(30);
                HBox row = new HBox(10, iconBox, UiKit.label(entry.title(), "row-title"), UiKit.grow(),
                        UiKit.label(entry.subtitle() == null ? "" : entry.subtitle(), "row-time"));
                row.getStyleClass().add("palette-row");
                if (i == selected[0]) row.getStyleClass().add("selected");
                row.setAlignment(Pos.CENTER_LEFT);
                row.setId("palette-" + entry.id().replace(':', '-'));
                row.setOnMouseClicked(e -> go.accept(entry));
                results.getChildren().add(row);
            }
        };
        query.textProperty().addListener((o, a, b) -> {
            selected[0] = 0;
            render[0].run();
        });
        query.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            switch (e.getCode()) {
                case DOWN -> {
                    selected[0] = Math.min(shown.size() - 1, selected[0] + 1);
                    render[0].run();
                    e.consume();
                }
                case UP -> {
                    selected[0] = Math.max(0, selected[0] - 1);
                    render[0].run();
                    e.consume();
                }
                case ENTER -> {
                    if (!shown.isEmpty()) go.accept(shown.get(selected[0]));
                    e.consume();
                }
                default -> { }
            }
        });
        render[0].run();
        HBox head = new HBox(10, Icons.of(Icons.SEARCH, 18, "muted"), query);
        HBox.setHgrow(query, Priority.ALWAYS);
        head.setAlignment(Pos.CENTER_LEFT);
        Label keys = UiKit.label(I18n.t("palette.keys"), "caption");
        VBox box = new VBox(10, head, results, keys);
        box.getStyleClass().addAll("dialog", "palette");
        box.setId("palette");
        overlay.show(box);
        Platform.runLater(query::requestFocus);
    }

    /** An encrypted backup of keys, sessions and history, protected by its own passphrase. */
    private void openBackup() {
        javafx.scene.control.PasswordField pass = new javafx.scene.control.PasswordField();
        pass.setPromptText(I18n.t("backup.passPrompt", com.emirchat.client.Backup.MIN_PASSPHRASE));
        pass.setId("backup-pass");
        javafx.scene.control.PasswordField repeat = new javafx.scene.control.PasswordField();
        repeat.setPromptText(I18n.t("backup.repeat"));
        repeat.setId("backup-repeat");
        Label explain = UiKit.label(I18n.t("backup.explain"), "caption");
        explain.setWrapText(true);
        explain.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        Label status = UiKit.label("", "caption");
        status.setWrapText(true);
        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> overlay.close());
        Button save = new Button(I18n.t("backup.save"));
        save.getStyleClass().add("primary");
        save.setId("backup-save");
        save.disableProperty().bind(pass.textProperty().length().lessThan(com.emirchat.client.Backup.MIN_PASSPHRASE)
                .or(pass.textProperty().isNotEqualTo(repeat.textProperty())));
        save.setOnAction(e -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle(I18n.t("backup.saveTitle"));
            chooser.setInitialFileName(com.emirchat.client.Backup.suggestedName(session.client().username()));
            java.io.File target = chooser.showSaveDialog(stack.getScene().getWindow());
            if (target == null) return;
            char[] secret = pass.getText().toCharArray();
            status.setText(I18n.t("backup.encrypting"));
            save.disableProperty().unbind();
            save.setDisable(true);
            session.background().execute(() -> {
                String result;
                boolean failed = false;
                try {
                    session.backup(target.toPath(), secret);
                    result = I18n.t("backup.saved", target.getAbsolutePath());
                } catch (Exception ex) {
                    result = I18n.t("backup.failed", ex.getMessage());
                    failed = true;
                } finally {
                    java.util.Arrays.fill(secret, '\0');
                }
                String text = result;
                boolean danger = failed;
                Platform.runLater(() -> {
                    overlay.close();
                    notice(text, danger);
                    if (!danger && current == null) showInfo(I18n.t("backup.title"), text);
                });
            });
        });
        VBox box = Overlay.dialog(I18n.t("backup.title"), explain, pass, repeat, status, new HBox(10, UiKit.grow(), cancel, save));
        box.setId("backup-dialog");
        overlay.show(box);
        Platform.runLater(pass::requestFocus);
    }

    /** A switch for an app-wide setting (not stored per account). */
    private Node appToggle(String title, String explanation, String id, boolean value, Consumer<Boolean> change) {
        CheckBox box = new CheckBox(title);
        box.setId(id);
        box.setSelected(value);
        box.selectedProperty().addListener((o, a, b) -> change.accept(b));
        Label hint = UiKit.label(explanation, "caption");
        hint.setWrapText(true);
        hint.setPadding(new Insets(0, 0, 0, 26));
        return new VBox(2, box, hint);
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
        Button newGroup = new Button(I18n.t("conv.newGroup"));
        newGroup.setId("new-group");
        newGroup.getStyleClass().add("small");
        newGroup.setOnAction(e -> openNewGroup());
        HBox heading = new HBox(UiKit.label(I18n.t("main.conversations"), "overline"), UiKit.grow(), newGroup);
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
            if (g.community() != 0) continue; // community channels live in their own tab
            String key = AccountSession.group(g.id());
            if (!shown.contains(key)) list.getChildren().add(chatRow(key, null));
        }
        if (list.getChildren().size() == 1) {
            Label empty = UiKit.label(I18n.t("main.noConversations"), "caption");
            empty.setWrapText(true);
            empty.setPadding(new Insets(10, 14, 0, 14));
            list.getChildren().add(empty);
        }
    }

    /** The question of a stored poll, or null if the text is not one. */
    static String pollQuestion(String text) {
        com.emirchat.chat.Poll p = com.emirchat.chat.Poll.fromStored(text);
        return p == null ? null : p.question();
    }

    /** Voice messages are files named voice-….wav (see ConversationView#startRecording). */
    static boolean isVoiceName(String text) {
        String name = text.startsWith("📎 ") ? text.substring("📎 ".length()) : text;
        return name.startsWith("voice-") && name.endsWith(".wav") && !name.contains(" ");
    }

    private Node chatRow(String conversation, History.Summary s) {
        ChatClient client = session.client();
        boolean group = conversation.startsWith("g:");
        String title;
        Node avatar;
        if (group) {
            long id = Long.parseLong(conversation.substring(2));
            Packet.GroupInfo g = client.groups().stream().filter(x -> x.id() == id).findFirst().orElse(null);
            if (g == null || g.community() != 0) return null; // left, or a community channel (own tab)
            title = ConversationView.groupName(g);
            avatar = UiKit.avatar(title, "avatar-sm", true);
        } else {
            title = conversation.substring(3);
            avatar = UiKit.avatarWithPresence(title, client.isOnline(title));
        }
        String last = s == null ? "" : s.lastText().equals(History.DELETED_TEXT) ? I18n.t("conv.deleted")
                : isVoiceName(s.lastText()) ? "🎤 " + I18n.t("voice.message")
                : pollQuestion(s.lastText()) != null ? "📊 " + pollQuestion(s.lastText())
                : Markdown.plain(s.lastText());
        String preview = s == null ? I18n.t("main.noMessages")
                : (s.lastSender().equals(client.username()) ? I18n.t("conv.you") + ": " : group ? s.lastSender() + ": " : "") + last;
        Label sub = UiKit.label(preview, "row-sub");
        VBox text = new VBox(2, UiKit.label(title, "row-title"), sub);
        text.setMinWidth(0); // the preview shrinks (with an ellipsis), never the time or badge
        VBox right = new VBox(4);
        right.setAlignment(Pos.TOP_RIGHT);
        right.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        if (s != null) right.getChildren().add(UiKit.label(UiKit.time(s.lastAt()), "row-time"));
        boolean open = current != null && current.conversation().equals(conversation);
        if (group) {
            int inVoice = voiceRooms.getOrDefault(Long.parseLong(conversation.substring(2)), List.of()).size();
            if (inVoice > 0) right.getChildren().add(UiKit.label("🔊 " + inVoice, "voice-badge"));
        }
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
        add.setPromptText(I18n.t("main.addFriend"));
        add.setId("add-friend");
        HBox.setHgrow(add, Priority.ALWAYS);
        Button send = new Button(I18n.t("main.add"));
        send.setId("add-friend-send");
        send.getStyleClass().addAll("small", "primary");
        send.disableProperty().bind(add.textProperty().isEmpty());
        Runnable doAdd = () -> {
            String name = add.getText().strip().toLowerCase(java.util.Locale.ROOT);
            add.clear();
            call(() -> session.client().connection().sendFriendRequest(name),
                    nowFriends -> notice(nowFriends ? I18n.t("main.nowFriends", name) : I18n.t("main.requestSent", name), false));
        };
        add.setOnAction(e -> doAdd.run());
        send.setOnAction(e -> doAdd.run());
        HBox addRow = new HBox(8, add, send);
        addRow.setAlignment(Pos.CENTER);
        addRow.setPadding(new Insets(4, 14, 8, 14));
        list.getChildren().add(addRow);

        if (!friends.incoming().isEmpty()) {
            list.getChildren().add(heading(I18n.t("main.requests")));
            for (String from : friends.incoming()) {
                Button accept = new Button(I18n.t("main.accept"));
                accept.setId("accept-" + from);
                accept.getStyleClass().addAll("small", "primary");
                accept.setOnAction(e -> call(() -> {
                    session.client().connection().respondFriendRequest(from, true);
                    return null;
                }, r -> session.refreshFriendsLater()));
                Button decline = new Button(I18n.t("main.decline"));
                decline.getStyleClass().add("small");
                decline.setOnAction(e -> call(() -> {
                    session.client().connection().respondFriendRequest(from, false);
                    return null;
                }, r -> session.refreshFriendsLater()));
                decline.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
                accept.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
                list.getChildren().add(row(UiKit.avatar(from, "avatar-sm", false),
                        new VBox(2, UiKit.label(from, "row-title"), UiKit.label(I18n.t("main.friendRequest"), "row-sub")),
                        new HBox(6, decline, accept)));
            }
        }
        if (!friends.outgoing().isEmpty()) {
            list.getChildren().add(heading(I18n.t("main.sent")));
            for (String to : friends.outgoing()) {
                Button cancel = new Button(I18n.t("common.cancel"));
                cancel.getStyleClass().add("small");
                cancel.setOnAction(e -> call(() -> {
                    session.client().connection().removeFriend(to);
                    return null;
                }, r -> session.refreshFriendsLater()));
                list.getChildren().add(row(UiKit.avatar(to, "avatar-sm", false),
                        new VBox(2, UiKit.label(to, "row-title"), UiKit.label(I18n.t("main.waitingAnswer"), "row-sub")), cancel));
            }
        }
        list.getChildren().add(heading(I18n.t("main.friendsHeading", friends.friends().size())));
        if (friends.friends().isEmpty()) {
            Label none = UiKit.label(I18n.t("main.noFriends"), "caption");
            none.setWrapText(true);
            VBox box = new VBox(10, Illustrations.desert(220), none);
            box.setAlignment(Pos.CENTER);
            box.setPadding(new Insets(14, 14, 0, 14));
            list.getChildren().add(box);
        }
        for (Packet.Friend f : friends.friends()) {
            ChatClient c = session.client();
            String state = c.keyChanged(f.username()) ? "⚠ " + I18n.t("main.keyChanged") : c.isVerified(f.username()) ? "✓ " + I18n.t("main.verifiedLower") : f.online() ? I18n.t("conv.online") : I18n.t("conv.offline");
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
        if (current != null) current.dispose();
        current = null;
        Label shortcut = UiKit.label(I18n.t("palette.hint", isMac() ? "⌘K" : "Ctrl+K"), "shortcut-hint");
        VBox empty = new VBox(12, Illustrations.desert(300),
                UiKit.label(I18n.t("main.select"), "h2"),
                UiKit.label(I18n.t("main.selectHint"), "caption"), shortcut);
        empty.setAlignment(Pos.CENTER);
        empty.getStyleClass().add("content-empty");
        content.getChildren().setAll(empty);
    }

    void open(String conversation) {
        hookFocus();
        ConversationView.Actions actions = new ConversationView.Actions() {
            @Override public void openContact(String user) { MainView.this.openContact(user); }

            @Override public void openGroupInfo(long groupId) { MainView.this.openGroupInfo(groupId); }

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

            @Override public int voiceCount(long groupId) { return voiceRooms.getOrDefault(groupId, List.of()).size(); }
        };
        if (current != null) current.dispose();
        current = conversation.startsWith("g:")
                ? ConversationView.group(session, Long.parseLong(conversation.substring(2)), actions)
                : ConversationView.dm(session, conversation.substring(3), actions);
        content.getChildren().setAll(current.root());
        Packet.GroupInfo opened = conversation.startsWith("g:") ? group(Long.parseLong(conversation.substring(2))) : null;
        if (opened != null && opened.community() != 0) {
            selectedCommunity = opened.community();
            lastChannel.put(opened.community(), opened.id());
            if (!communitiesTab.isSelected()) communitiesTab.setSelected(true); else refreshSidebar();
        } else if (!chatsTab.isSelected() && conversation.startsWith("dm:")) {
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
        VBox mine = new VBox(8, UiKit.label(I18n.t("contact.yourKey", user.toUpperCase(I18n.locale())), "overline"),
                FingerprintView.compact(Identity.fingerprint(myKey())));
        mine.getStyleClass().add("card-flat");
        Label explain = UiKit.label(changed
                ? I18n.t("contact.changedExplain", user)
                : I18n.t("contact.explain", user),
                "body");
        explain.setWrapText(true);
        VBox box = Overlay.dialog(changed ? I18n.t("contact.changedTitle", user) : I18n.t("contact.title", user), explain);
        if (changed && pinned != null) {
            box.getChildren().add(new VBox(6, UiKit.label(I18n.t("keyChanged.before"), "overline"), FingerprintView.compact(pinned)));
        }
        if (current != null) {
            VBox theirs = new VBox(8, UiKit.label(changed ? I18n.t("contact.newKey") : I18n.t("chat.theirKey", user.toUpperCase(I18n.locale())), "overline"),
                    FingerprintView.compact(current));
            theirs.getStyleClass().add("card-flat");
            box.getChildren().add(theirs);
        }
        box.getChildren().add(mine);
        Button close = new Button(I18n.t("common.close"));
        close.setOnAction(e -> overlay.close());
        Button confirm = new Button(changed ? I18n.t("contact.trustNew") : I18n.t("contact.markVerified"));
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
        Button check = new Button(I18n.t("safety.check"));
        check.setId("check-qr");
        check.getStyleClass().add("small");
        check.setOnAction(e -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle(I18n.t("safety.pick", user));
            chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(I18n.t("safety.images"), "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"));
            java.io.File file = chooser.showOpenDialog(stack.getScene().getWindow());
            if (file == null) return;
            String scanned = QrCodes.read(new javafx.scene.image.Image(file.toURI().toString()));
            result.getStyleClass().removeAll("danger-text", "accent-text");
            if (scanned == null) {
                result.setText(I18n.t("safety.noCode"));
                result.getStyleClass().add("danger-text");
            } else if (number.matches(scanned)) {
                result.setText("✓ " + I18n.t("safety.match", user));
                result.getStyleClass().add("accent-text");
                confirm.fire();
            } else {
                result.setText("✗ " + I18n.t("safety.mismatch"));
                result.getStyleClass().add("danger-text");
            }
        });
        Label how = UiKit.label(I18n.t("safety.how"), "caption");
        how.setWrapText(true);
        how.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        result.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        result.setMaxWidth(260);
        VBox right = new VBox(8, digits, check, result);
        HBox row = new HBox(16, qr, right);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(8, UiKit.label(I18n.t("safety.title"), "overline"), row, how);
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
        Label text = UiKit.label(I18n.t("myKey.explain"), "body");
        text.setWrapText(true);
        Button ok = new Button(I18n.t("common.done"));
        ok.getStyleClass().add("primary");
        ok.setOnAction(e -> overlay.close());
        overlay.show(Overlay.dialog(I18n.t("main.myKey"), text, card, new HBox(UiKit.grow(), ok)));
    }

    private void openNewGroup() {
        TextField name = new TextField();
        name.setPromptText(I18n.t("group.name"));
        name.setId("group-name");
        VBox members = new VBox(8);
        List<CheckBox> boxes = new ArrayList<>();
        for (Packet.Friend f : session.client().friends().friends()) {
            CheckBox cb = new CheckBox(f.username());
            cb.setId("pick-" + f.username());
            boxes.add(cb);
            members.getChildren().add(cb);
        }
        if (boxes.isEmpty()) members.getChildren().add(UiKit.label(I18n.t("group.addFriendsFirst"), "caption"));
        ScrollPane scroll = new ScrollPane(members);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(220, 28.0 * Math.max(1, boxes.size())));
        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> overlay.close());
        Button create = new Button(I18n.t("group.create"));
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
        overlay.show(Overlay.dialog(I18n.t("conv.newGroup"), new VBox(6, UiKit.label(I18n.t("group.nameLabel"), "field-label"), name),
                new VBox(6, UiKit.label(I18n.t("group.members"), "field-label"), scroll), new HBox(10, UiKit.grow(), cancel, create)));
        Platform.runLater(name::requestFocus);
    }

    private Packet.GroupInfo group(long id) {
        return session.client().groups().stream().filter(x -> x.id() == id).findFirst().orElse(null);
    }

    private void openGroupInfo(long groupId) {
        ChatClient c = session.client();
        Packet.GroupInfo g = c.groups().stream().filter(x -> x.id() == groupId).findFirst().orElse(null);
        if (g == null) return;
        if (g.community() != 0) {
            openCommunityInfo(g.community());
            return;
        }
        boolean admin = g.owner().equals(c.username());
        VBox rows = new VBox(4);
        for (Packet.Member m : g.members()) {
            HBox right = new HBox(6);
            right.setAlignment(Pos.CENTER_RIGHT);
            if (m.username().equals(g.owner())) right.getChildren().add(UiKit.label(I18n.t("group.admin"), "caption"));
            if (admin && !m.username().equals(c.username())) {
                Button remove = new Button(I18n.t("group.remove"));
                remove.setId("remove-" + m.username());
                remove.getStyleClass().addAll("small", "danger-ghost");
                remove.setOnAction(e -> call(() -> {
                    c.removeGroupMember(groupId, m.username());
                    return null;
                }, r -> overlay.close()));
                right.getChildren().add(remove);
            }
            String who = m.username().equals(c.username()) ? I18n.t("group.you", m.username()) : m.username();
            HBox row = new HBox(10, UiKit.avatar(m.username(), "avatar-xs", false), UiKit.label(who, "row-title"), UiKit.grow(), right);
            row.setAlignment(Pos.CENTER_LEFT);
            rows.getChildren().add(row);
        }
        Label about = UiKit.label(I18n.t("group.about", g.members().size()), "caption");
        about.setWrapText(true);
        VBox box = Overlay.dialog(ConversationView.groupName(g), about, rows);
        if (admin) {
            ComboBox<String> candidates = new ComboBox<>();
            c.friends().friends().stream().map(Packet.Friend::username).filter(u -> !g.hasMember(u)).forEach(candidates.getItems()::add);
            candidates.setPromptText(candidates.getItems().isEmpty() ? I18n.t("group.allHere") : I18n.t("group.addFriend"));
            candidates.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(candidates, Priority.ALWAYS);
            Button add = new Button(I18n.t("main.add"));
            add.getStyleClass().addAll("small", "primary");
            add.disableProperty().bind(candidates.valueProperty().isNull());
            add.setOnAction(e -> call(() -> {
                c.addGroupMember(groupId, candidates.getValue());
                return null;
            }, r -> overlay.close()));
            box.getChildren().add(new HBox(8, candidates, add));
            TextField newName = new TextField(g.name().equals(ChatClient.ENCRYPTED_GROUP_NAME) ? "" : g.name());
            newName.setId("group-name");
            newName.setPromptText(I18n.t("group.name"));
            HBox.setHgrow(newName, Priority.ALWAYS);
            Button rename = new Button(I18n.t("group.rename"));
            rename.setId("rename-group");
            rename.getStyleClass().add("small");
            rename.disableProperty().bind(newName.textProperty().isEmpty());
            rename.setOnAction(e -> call(() -> {
                c.renameGroup(groupId, newName.getText());
                return null;
            }, r -> overlay.close()));
            box.getChildren().add(new HBox(8, newName, rename));
        }
        Button leave = new Button(I18n.t("group.leave"));
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
        Button close = new Button(I18n.t("common.close"));
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
        if (!friendsTab.isSelected()) refreshSidebar();
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
        refreshSidebar();
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
        if (online != wasOnline) connectionTail.setOnline(online);
        connectionLabel.setId(online ? "status-online" : "status-offline");
    }

    @Override
    public void messageChanged(String conversation, long localId) {
        if (current != null && current.conversation().equals(conversation)) current.refresh(localId);
        refreshSidebar();
    }

    @Override
    public void voice(com.emirchat.call.VoiceChannels.State state) {
        boolean wasJoined = voiceState.joined();
        long before = voiceState.groupId();
        voiceState = state;
        if (state.error() != null) {
            notice("busy".equals(state.error()) ? I18n.t("voice.busy") : I18n.t("voice.failed", state.error()), true);
        }
        if (!state.joined()) {
            voiceBar.setVisible(false);
        } else {
            showVoiceBar(state);
        }
        if (current != null && (wasJoined != state.joined() || before != state.groupId())) current.refreshHeader();
        refreshSidebar();
    }

    @Override
    public void clockChanged(String user) {
        if (current != null && user.equals(current.peer())) current.refreshHeader();
    }

    @Override
    public void voiceRooms(Map<Long, List<String>> rooms) {
        voiceRooms = rooms;
        refreshSidebar();
    }

    /** The bar over the conversation while we are in a voice room: who is there, mute, deafen, leave. */
    private void showVoiceBar(com.emirchat.call.VoiceChannels.State state) {
        com.emirchat.call.VoiceChannels voice = session.client().voice();
        Packet.GroupInfo g = group(state.groupId());
        String where = g == null ? "" : g.community() != 0
                ? ConversationView.channelTitle(g) + " · " + communityName(g.community()) : ConversationView.groupName(g);
        Label title = UiKit.label(I18n.t("voice.inRoom", where), "row-title");
        Label sub = UiKit.label("🔒 " + I18n.t("voice.encrypted"), "row-sub");
        VBox text = new VBox(2, title, sub);
        text.setMinWidth(0);
        HBox faces = new HBox(6);
        faces.setAlignment(Pos.CENTER_LEFT);
        for (String u : state.participants()) {
            Node face = UiKit.voiceFace(u, state.speaking().contains(u), 30);
            Tooltip.install(face, new Tooltip(u));
            faces.getChildren().add(face);
        }
        Button mute = new Button(state.muted() ? I18n.t("call.unmute") : I18n.t("call.mute"));
        mute.setGraphic(Icons.of(state.muted() ? Icons.MIC_OFF : Icons.MIC, 14, state.muted() ? "danger" : "muted"));
        mute.getStyleClass().add("small");
        mute.setId("voice-mute");
        mute.setOnAction(e -> session.background().execute(() -> voice.setMuted(!state.muted())));
        Button deaf = new Button(state.deafened() ? I18n.t("voice.undeafen") : I18n.t("voice.deafen"));
        deaf.setGraphic(Icons.of(Icons.HEADPHONES, 14, state.deafened() ? "danger" : "muted"));
        deaf.getStyleClass().add("small");
        deaf.setId("voice-deafen");
        deaf.setOnAction(e -> session.background().execute(() -> voice.setDeafened(!state.deafened())));
        Button leave = new Button(I18n.t("voice.leave"));
        leave.setGraphic(Icons.of(Icons.PHONE, 14, "danger"));
        leave.getStyleClass().addAll("small", "danger-ghost");
        leave.setId("voice-leave");
        leave.setOnAction(e -> session.background().execute(voice::leave));
        StackPane badge = new StackPane(Icons.of(Icons.HEADPHONES, 18, "accent"));
        badge.getStyleClass().add("voice-badge-icon");
        voiceBar.getChildren().setAll(badge, text, faces, UiKit.grow(), mute, deaf, leave);
        voiceBar.getStyleClass().setAll("call-bar", "voice-bar");
        voiceBar.setAlignment(Pos.CENTER_LEFT);
        voiceBar.setId("voice-bar");
        voiceBar.setVisible(true);
    }

    @Override
    public void threadReply(History.Message reply, long rootId) {
        if (current != null && current.conversation().equals(reply.conversation())) current.threadReply(reply, rootId);
        if (!friendsTab.isSelected()) refreshSidebar();
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
            if (c.reason() == com.emirchat.call.Calls.EndReason.MISSED && !c.outgoing()) {
                notifyCall(c.peer(), I18n.t("call.missedFrom", c.peer()));
                if (calls.current() != null) return; // missed while talking to someone else: keep that bar
            }
            Label text = UiKit.label(endText(c), "row-title");
            text.setWrapText(true);
            showCallBar(c.peer(), text);
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
                buttons.getChildren().add(hangUpButton(I18n.t("call.cancel"), calls::hangUp));
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
                buttons.getChildren().addAll(hangUpButton(I18n.t("call.reject"), calls::reject), accept);
                notifyCall(c.peer(), I18n.t("call.incomingFrom", c.peer()));
                ring();
            }
            case CONNECTED -> {
                Runnable tick = () -> status.setText("🔒 " + I18n.t("call.encrypted") + " · "
                        + duration(System.currentTimeMillis() - c.connectedAt()));
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
                buttons.getChildren().addAll(mute, hangUpButton(I18n.t("call.hangUp"), calls::hangUp));
            }
            default -> { }
        }
        HBox row = new HBox(10, text, UiKit.grow(), buttons);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(row, Priority.ALWAYS);
        showCallBar(c.peer(), row);
    }

    private void showCallBar(String peer, Node body) {
        callBar.getChildren().setAll(UiKit.avatar(peer, "avatar-sm", false), body);
        HBox.setHgrow(body, Priority.ALWAYS);
        callBar.getStyleClass().setAll("call-bar");
        callBar.setAlignment(Pos.CENTER_LEFT);
        callBar.setId("call-bar");
        callBar.setVisible(true);
    }

    private static Button hangUpButton(String text, Runnable action) {
        Button b = new Button(text);
        b.setGraphic(Icons.of(Icons.PHONE, 14, "danger"));
        b.getStyleClass().addAll("small", "danger-ghost");
        b.setId("call-hangup");
        b.setOnAction(e -> action.run());
        return b;
    }

    private static String endText(com.emirchat.call.Calls.Call c) {
        return switch (c.reason()) {
            case HUNG_UP -> c.connectedAt() > 0
                    ? I18n.t("call.ended", duration(System.currentTimeMillis() - c.connectedAt()))
                    : I18n.t("call.cancelled");
            case REJECTED -> c.outgoing() ? I18n.t("call.rejected", c.peer()) : I18n.t("call.cancelled");
            case BUSY -> I18n.t("call.busy", c.peer());
            case NO_ANSWER -> I18n.t("call.noAnswer", c.peer());
            case MISSED -> I18n.t("call.missedFrom", c.peer());
            case FAILED -> I18n.t("call.failed", c.error() == null ? "" : c.error());
        };
    }

    static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format("%02d:%02d", s / 60, s % 60);
    }

    /** Rings (a system beep) every two seconds while the call is ringing, and brings the window forward. */
    private void ring() {
        if (stack.getScene() != null && stack.getScene().getWindow() instanceof javafx.stage.Stage stage) {
            if (stage.isIconified()) stage.setIconified(false);
            stage.toFront();
        }
        if (!Boolean.parseBoolean(System.getProperty("securechat.notifications", "true"))) return;
        Runnable beep = () -> {
            try {
                java.awt.Toolkit.getDefaultToolkit().beep();
            } catch (UnsupportedOperationException ignored) { // includes HeadlessException
                // no sound: the bar still shows the call
            }
        };
        beep.run();
        callTicker = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(2), e -> beep.run()));
        callTicker.setCycleCount(javafx.animation.Animation.INDEFINITE);
        callTicker.play();
    }

    private void notifyCall(String title, String body) {
        if (!session.setting(AccountSession.NOTIFICATIONS) || !Boolean.parseBoolean(System.getProperty("securechat.notifications", "true"))) return;
        Tray.get().notify(title, body);
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
        String title = m.conversation().startsWith("g:") ? I18n.t("notify.inGroup", m.sender(), where) : where;
        String body = session.setting(AccountSession.NOTIFICATION_PREVIEW)
                ? (m.file() != null ? (isVoiceName(m.text()) ? "🎤 " + I18n.t("voice.message") : "📎 " + m.text())
                        : pollQuestion(m.text()) != null ? "📊 " + pollQuestion(m.text())
                        : Markdown.plain(m.text()))
                : I18n.t("notify.newMessage");
        Tray.get().notify(title, body);
        Sounds.play();
    }

    /** Called once the view is in a window: report reads when the window comes back into focus. */
    private void hookFocus() {
        if (focusHooked || stack.getScene() == null || stack.getScene().getWindow() == null) return;
        focusHooked = true;
        stack.getScene().getWindow().focusedProperty().addListener((o, a, focused) -> {
            if (focused && current != null) current.windowFocused();
            if (!focused) AppPrefs.setLastSeen(System.currentTimeMillis());
            else maybeCatchUp();
        });
    }

    /** After a long enough absence (and with something to show), the welcome-back screen. */
    static final long AWAY_MS = 20 * 60_000;

    private void maybeCatchUp() {
        long last = AppPrefs.lastSeen();
        long now = System.currentTimeMillis();
        if (last <= 0 || now - last < AWAY_MS || overlayOpen()) return;
        AppPrefs.setLastSeen(now);
        showCatchUp(now - last, false);
    }

    private boolean overlayOpen() {
        return stack.lookup(".overlay") != null;
    }

    /**
     * "Welcome back": your own sky, how long you were away, and what is waiting (mentions, polls
     * without your vote, replies in your threads, the busiest chats), worked out from the local
     * history. {@code always}: also when there is nothing new (from the quick switcher).
     */
    void showCatchUp(long awayMillis, boolean always) {
        String me = session.client().username();
        session.background().execute(() -> {
            CatchUp.Digest d;
            try {
                d = CatchUp.compute(CatchUp.of(session.history()), me, System.currentTimeMillis());
            } catch (RuntimeException e) {
                return;
            }
            if (!always && !d.worthShowing()) return;
            CatchUp.Digest digest = d;
            Platform.runLater(() -> openCatchUp(digest, awayMillis));
        });
    }

    private void openCatchUp(CatchUp.Digest d, long awayMillis) {
        String me = session.client().username();
        // the banner: our own sky right now, with the greeting on it
        Sky sky = new Sky(java.time.ZoneId.systemDefault(), Theme.isDark());
        sky.fade = false;
        Label hello = UiKit.label(CatchUp.greeting(java.time.LocalTime.now().getHour()) + ", " + me, "catchup-hello");
        Label away = UiKit.label(awayMillis > 0 ? I18n.t("catchup.away", CatchUp.away(awayMillis)) : I18n.t("catchup.title"), "catchup-away");
        VBox words = new VBox(4, hello, away);
        words.setPadding(new Insets(22, 24, 22, 24));
        words.setAlignment(Pos.BOTTOM_LEFT);
        StackPane banner = new StackPane(sky, words);
        banner.getStyleClass().add("catchup-banner");
        banner.setMinHeight(130);
        banner.setPrefHeight(130);
        javafx.scene.shape.Rectangle round = new javafx.scene.shape.Rectangle();
        round.setArcWidth(28);
        round.setArcHeight(28);
        round.widthProperty().bind(banner.widthProperty());
        round.heightProperty().bind(banner.heightProperty());
        banner.setClip(round);

        HBox stats = new HBox(10, statTile(d.unread(), I18n.t("catchup.messages"), "stat-messages"),
                statTile(d.conversations(), I18n.t("catchup.chats"), "stat-chats"),
                statTile(d.mentions().size(), I18n.t("catchup.mentions"), "stat-mentions"));
        VBox body = new VBox(14, stats);
        if (!d.mentions().isEmpty()) {
            body.getChildren().add(catchUpSection(I18n.t("catchup.mentionedYou"), d.mentions().stream().map(m ->
                    catchUpRow(m.sender(), where(m.conversation()), Markdown.plain(m.text()), () -> jumpTo(m))).toList()));
        }
        if (!d.openPolls().isEmpty()) {
            body.getChildren().add(catchUpSection(I18n.t("catchup.polls"), d.openPolls().stream().map(m -> {
                com.emirchat.chat.Poll p = com.emirchat.chat.Poll.fromStored(m.text());
                return catchUpRow(m.sender(), where(m.conversation()), "📊 " + (p == null ? "" : p.question()), () -> jumpTo(m));
            }).toList()));
        }
        if (!d.threadReplies().isEmpty()) {
            body.getChildren().add(catchUpSection(I18n.t("catchup.threads"), d.threadReplies().stream().map(m ->
                    catchUpRow(m.sender(), where(m.conversation()), Markdown.plain(m.text()), () -> jumpToThread(m))).toList()));
        }
        if (!d.busiest().isEmpty()) {
            int top = d.busiest().get(0).unread();
            body.getChildren().add(catchUpSection(I18n.t("catchup.busiest"), d.busiest().stream().map(b -> {
                Region bar = new Region();
                bar.getStyleClass().add("busy-bar");
                bar.setPrefWidth(Math.max(10, 140.0 * b.unread() / Math.max(1, top)));
                bar.setMaxHeight(8);
                HBox right = new HBox(8, bar, UiKit.label(String.valueOf(b.unread()), "unread-badge"));
                right.setAlignment(Pos.CENTER_RIGHT);
                HBox row = catchUpRow(null, where(b.conversation()), Markdown.plain(b.latest().text()), () -> open(b.conversation()));
                row.getChildren().add(right);
                return row;
            }).toList()));
        }
        if (d.unread() == 0 && d.openPolls().isEmpty()) body.getChildren().add(UiKit.label(I18n.t("catchup.nothing"), "caption"));
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(Math.min(360, 90 + 66.0 * (d.mentions().size() + d.openPolls().size() + d.threadReplies().size() + d.busiest().size())));
        Button allRead = new Button(I18n.t("catchup.allRead"));
        allRead.setId("catchup-all-read");
        allRead.setOnAction(e -> {
            try {
                for (History.Summary s : session.history().summaries()) session.history().markRead(s.conversation());
            } catch (IOException ignored) {
                // the counts stay
            }
            overlay.close();
            refreshSidebar();
        });
        allRead.setDisable(d.unread() == 0);
        Button go = new Button(I18n.t("catchup.continue"));
        go.getStyleClass().add("primary");
        go.setId("catchup-continue");
        go.setDefaultButton(true);
        go.setOnAction(e -> overlay.close());
        VBox box = new VBox(16, banner, scroll, new HBox(10, allRead, UiKit.grow(), go));
        box.getStyleClass().addAll("dialog", "catchup");
        box.setId("catchup");
        overlay.show(box);
    }

    private Node statTile(int value, String label, String id) {
        Label number = UiKit.label("0", "stat-number");
        number.setId(id);
        VBox tile = new VBox(2, number, UiKit.label(label, "stat-label"));
        tile.getStyleClass().add("stat-tile");
        HBox.setHgrow(tile, Priority.ALWAYS);
        tile.setMaxWidth(Double.MAX_VALUE);
        if (Motion.enabled() && value > 0) {
            // count up, quickly at first and slowing down at the end
            javafx.animation.Transition count = new javafx.animation.Transition() {
                {
                    setCycleDuration(javafx.util.Duration.millis(Math.min(1200, 400 + value * 40L)));
                    setInterpolator(javafx.animation.Interpolator.EASE_OUT);
                }

                @Override protected void interpolate(double f) {
                    number.setText(String.valueOf(Math.round(value * f)));
                }
            };
            count.play();
        } else {
            number.setText(String.valueOf(value));
        }
        return tile;
    }

    private static Node catchUpSection(String title, List<HBox> rows) {
        VBox box = new VBox(4, UiKit.label(title.toUpperCase(I18n.locale()), "overline"));
        box.getChildren().addAll(rows);
        return box;
    }

    private HBox catchUpRow(String who, String where, String text, Runnable action) {
        Node face = who == null ? Icons.of(Icons.MESSAGE, 18, "accent") : UiKit.avatar(who, "avatar-xs", false);
        StackPane faceBox = new StackPane(face);
        faceBox.setMinWidth(32);
        Label title = UiKit.label(who == null ? where : who + " · " + where, "row-title");
        Label snippet = UiKit.label(text, "row-sub");
        VBox words = new VBox(1, title, snippet);
        words.setMinWidth(0);
        HBox.setHgrow(words, Priority.ALWAYS);
        HBox row = new HBox(10, faceBox, words);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("catchup-row");
        row.setOnMouseClicked(e -> {
            overlay.close();
            action.run();
        });
        return row;
    }

    /** "efe" for a direct chat, "# general · MobilCAD" for a channel, the group's name otherwise. */
    private String where(String conversation) {
        if (conversation.startsWith("dm:")) return conversation.substring(3);
        return groupName(Long.parseLong(conversation.substring(2)));
    }

    private void jumpTo(History.Message m) {
        open(m.conversation());
        if (current != null) current.reveal(m.id());
    }

    private void jumpToThread(History.Message reply) {
        open(reply.conversation());
        try {
            History.Message root = session.history().find(reply.conversation(), reply.replyTo());
            if (root != null && current != null) current.act(root.id(), "thread");
        } catch (IOException ignored) {
            // the chat is open anyway
        }
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
    private void showUnread() {
        int unread = 0;
        try {
            for (History.Summary s : session.history().summaries()) {
                if (current == null || !current.conversation().equals(s.conversation())) unread += s.unread();
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
    private <T> void call(Callable<T> action, Consumer<T> onSuccess) {
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

    private void showInfo(String title, String message) {
        Button ok = new Button(I18n.t("common.ok"));
        ok.getStyleClass().add("primary");
        ok.setOnAction(e -> overlay.close());
        Label text = UiKit.label(message, "body");
        text.setWrapText(true);
        overlay.show(Overlay.dialog(title, text, new HBox(UiKit.grow(), ok)));
    }

    private void showError(String message) {
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
