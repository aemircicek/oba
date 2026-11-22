package com.emirchat.ui;

import com.emirchat.api.Packet;
import com.emirchat.client.ChatClient;
import com.emirchat.client.History;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The left side of the main window: the rail of communities, and the list beside it (chats,
 * friends and requests, a community's channels with who is in their voice rooms, or search
 * results).
 */
final class Sidebar {
    private final MainView main;

    Sidebar(MainView main) {
        this.main = main;
    }

    /** Home, then every community as a rounded icon with an unread dot, then "+". */
    private void buildRail() {
        main.rail.getChildren().clear();
        boolean home = !main.communitiesTab.isSelected();
        Map<String, History.Summary> summaries = summaries();
        int homeUnread = 0;
        for (History.Summary s : summaries.values()) {
            if (s.conversation().startsWith("dm:") || isPlainGroup(s.conversation())) homeUnread += unread(s.conversation(), summaries);
        }
        javafx.scene.image.ImageView logo = new javafx.scene.image.ImageView(
                new javafx.scene.image.Image(MainView.class.getResourceAsStream("icons/icon-64.png")));
        logo.setFitWidth(30);
        logo.setPreserveRatio(true);
        main.rail.getChildren().add(railItem(logo, I18n.t("rail.home"), home, homeUnread > 0, "rail-home", () -> {
            main.selectedCommunity = 0;
            main.chatsTab.setSelected(true);
        }));
        Region line = new Region();
        line.getStyleClass().add("rail-line");
        main.rail.getChildren().add(line);
        ChatClient c = main.session.client();
        List<Packet.GroupInfo> roots = c.groups().stream().filter(Packet.GroupInfo::isCommunity)
                .sorted(java.util.Comparator.comparing(ConversationView::groupName, String.CASE_INSENSITIVE_ORDER)).toList();
        for (Packet.GroupInfo root : roots) {
            int unread = c.channels(root.id()).stream().mapToInt(ch -> unread(AccountSession.group(ch.id()), summaries)).sum();
            boolean selected = main.communitiesTab.isSelected() && main.selectedCommunity == root.id();
            String name = ConversationView.groupName(root);
            main.rail.getChildren().add(railItem(UiKit.communityIcon(name, root.id()), name, selected, unread > 0,
                    "rail-community-" + root.id(), () -> {
                        main.selectedCommunity = root.id();
                        if (!main.communitiesTab.isSelected()) main.communitiesTab.setSelected(true);
                        main.places.showMap(root.id());
                    }));
        }
        Label plus = UiKit.label("+", "rail-plus");
        main.rail.getChildren().add(railItem(plus, I18n.t("rail.add"), main.communitiesTab.isSelected() && main.selectedCommunity == 0, false,
                "rail-add", () -> {
                    main.selectedCommunity = 0;
                    if (!main.communitiesTab.isSelected()) main.communitiesTab.setSelected(true);
                    else refreshSidebar();
                }));
    }

    private boolean isPlainGroup(String conversation) {
        if (!conversation.startsWith("g:")) return false;
        Packet.GroupInfo g = main.group(Long.parseLong(conversation.substring(2)));
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
        A11y.clickable(item, null);
        item.setOnMouseClicked(e -> action.run());
        return item;
    }

    void refreshSidebar() {
        buildRail();
        Packet.FriendList friends = main.session.client().friends();
        int requests = friends.incoming().size();
        main.friendsBadge.setText(String.valueOf(requests));
        main.friendsBadge.setVisible(requests > 0);
        main.list.getChildren().clear();
        main.showUnread();
        if (main.communitiesTab.isSelected()) buildCommunities();
        else if (!main.chatsTab.isSelected()) buildFriends(friends);
        else if (!main.search.getText().isBlank()) buildSearch(main.search.getText());
        else buildChats();
    }

    /** The query the last search was started for; results of an older one are thrown away. */
    private String searching = "";
    private final javafx.animation.PauseTransition searchPause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(160));

    /**
     * Search results across all conversations, looked up in the encrypted index off the UI thread
     * (a moment after the last key press) and decrypted locally; nothing is sent anywhere.
     */
    private void buildSearch(String query) {
        main.list.getChildren().add(heading(I18n.t("main.results")));
        Label hint = UiKit.label(I18n.t("main.searchHint"), "caption");
        hint.setId("search-hint");
        hint.setWrapText(true);
        hint.setPadding(new Insets(0, 14, 4, 14));
        main.list.getChildren().add(hint);
        Label wait = UiKit.label(I18n.t("main.searching"), "caption");
        wait.setPadding(new Insets(6, 14, 0, 14));
        main.list.getChildren().add(wait);
        searching = query;
        searchPause.setOnFinished(e -> main.session.downloads().execute(() -> {
            if (!query.equals(searching)) return;
            List<History.Message> found;
            try {
                found = main.session.history().search(query, 100);
            } catch (IOException ex) {
                found = List.of();
            }
            List<History.Message> results = found;
            javafx.application.Platform.runLater(() -> {
                if (!query.equals(searching) || !main.list.getChildren().contains(wait)) return;
                main.list.getChildren().remove(wait);
                showResults(query, results);
            });
        }));
        searchPause.playFromStart();
    }

    private void showResults(String query, List<History.Message> found) {
        if (found.isEmpty()) {
            Label none = UiKit.label(I18n.t("main.noResults", query.strip()), "caption");
            none.setId("no-results");
            none.setPadding(new Insets(6, 14, 0, 14));
            main.list.getChildren().add(none);
        }
        List<String> words = History.Query.parse(query).words();
        for (History.Message m : found) {
            String where = m.conversation().startsWith("g:") ? groupName(Long.parseLong(m.conversation().substring(2)))
                    : m.conversation().substring(3);
            String prefix = (m.outgoing() ? I18n.t("conv.you") + ": " : m.conversation().startsWith("g:") ? m.sender() + ": " : "")
                    + (m.file() != null ? "📎 " : "");
            javafx.scene.text.TextFlow sub = highlighted(prefix, Markdown.plain(m.text()), words);
            sub.getStyleClass().add("row-sub");
            sub.setMaxHeight(36);
            VBox text = new VBox(2, UiKit.label(where, "row-title"), sub);
            text.setMinWidth(0);
            HBox row = new HBox(10, text, UiKit.grow(), UiKit.label(UiKit.time(m.sentAt()), "row-time"));
            row.getStyleClass().add("list-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setId("result-" + m.id());
            A11y.clickable(row, where + ": " + prefix + Markdown.plain(m.text()));
            row.setOnMouseClicked(e -> {
                main.open(m.conversation());
                if (main.current != null) main.current.reveal(m.id());
            });
            main.list.getChildren().add(row);
        }
    }

    /** The text with the starts of matching words in bold (matched as the search matches them). */
    static javafx.scene.text.TextFlow highlighted(String prefix, String text, List<String> words) {
        String shown = text.length() > 160 ? text.substring(0, 160) + "…" : text;
        javafx.scene.text.TextFlow flow = new javafx.scene.text.TextFlow();
        if (!prefix.isEmpty()) flow.getChildren().add(styled(prefix, "search-plain"));
        int plainFrom = 0;
        for (int[] span : matches(shown, words)) {
            if (span[0] > plainFrom) flow.getChildren().add(styled(shown.substring(plainFrom, span[0]), "search-plain"));
            flow.getChildren().add(styled(shown.substring(span[0], span[1]), "search-hit"));
            plainFrom = span[1];
        }
        if (plainFrom < shown.length()) flow.getChildren().add(styled(shown.substring(plainFrom), "search-plain"));
        return flow;
    }

    /** [start, end) of each word start in {@code text} that a query word matches. */
    static List<int[]> matches(String text, List<String> words) {
        List<int[]> spans = new java.util.ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            if (!Character.isLetterOrDigit(text.codePointAt(i))) {
                i += Character.charCount(text.codePointAt(i));
                continue;
            }
            int end = i;
            while (end < text.length() && Character.isLetterOrDigit(text.codePointAt(end))) end += Character.charCount(text.codePointAt(end));
            String word = com.emirchat.client.SearchFold.fold(text.substring(i, end));
            int best = 0;
            for (String w : words) if (word.startsWith(w)) best = Math.max(best, w.length());
            if (best > 0) spans.add(new int[] {i, Math.min(end, i + best)});
            i = end;
        }
        return spans;
    }

    private static javafx.scene.text.Text styled(String s, String style) {
        javafx.scene.text.Text t = new javafx.scene.text.Text(s);
        t.getStyleClass().add(style);
        return t;
    }

    String groupName(long id) {
        return main.session.client().groups().stream().filter(g -> g.id() == id)
                .map(g -> g.community() == 0 ? ConversationView.groupName(g)
                        : communityName(g.community()) + " › " + ConversationView.channelTitle(g))
                .findFirst()
                .orElse(I18n.t("main.formerGroup"));
    }

    String communityName(long id) {
        Packet.GroupInfo root = main.group(id);
        return root == null ? I18n.t("main.formerGroup") : ConversationView.groupName(root);
    }

    int unread(String conversation, Map<String, History.Summary> summaries) {
        History.Summary s = summaries.get(conversation);
        boolean open = main.current != null && main.current.conversation().equals(conversation);
        return s == null || open ? 0 : s.unread();
    }

    Map<String, History.Summary> summaries() {
        Map<String, History.Summary> map = new java.util.HashMap<>();
        try {
            for (History.Summary s : main.session.history().summaries()) map.put(s.conversation(), s);
        } catch (IOException ignored) {
            // no counts
        }
        return map;
    }

    private void buildCommunities() {
        ChatClient c = main.session.client();
        if (main.selectedCommunity != 0 && main.group(main.selectedCommunity) == null) main.selectedCommunity = 0; // left or removed
        Map<String, History.Summary> summaries = summaries();
        if (main.selectedCommunity == 0) {
            Button create = new Button(I18n.t("community.new"));
            create.setId("new-community");
            create.getStyleClass().add("small");
            create.setOnAction(e -> main.communities.openNewCommunity());
            Button join = new Button(I18n.t("community.join"));
            join.setId("join-community");
            join.getStyleClass().add("small");
            join.setOnAction(e -> main.communities.openJoinCommunity());
            HBox heading = new HBox(6, UiKit.label(I18n.t("community.heading"), "overline"), UiKit.grow(), join, create);
            heading.getStyleClass().add("list-heading");
            heading.setAlignment(Pos.CENTER_LEFT);
            main.list.getChildren().add(heading);
            List<Packet.GroupInfo> roots = c.groups().stream().filter(Packet.GroupInfo::isCommunity)
                    .sorted(java.util.Comparator.comparing(ConversationView::groupName, String.CASE_INSENSITIVE_ORDER)).toList();
            if (roots.isEmpty()) {
                Label empty = UiKit.label(I18n.t("community.none"), "caption");
                empty.setWrapText(true);
                VBox box = new VBox(10, Illustrations.desert(220), empty);
                box.setAlignment(Pos.CENTER);
                box.setPadding(new Insets(14, 14, 0, 14));
                main.list.getChildren().add(box);
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
                A11y.clickable(row, null);
                row.setOnMouseClicked(e -> {
                    main.selectedCommunity = root.id();
                    main.open(AccountSession.group(root.id()));
                });
                main.list.getChildren().add(row);
            }
            return;
        }
        Packet.GroupInfo root = main.group(main.selectedCommunity);
        boolean manager = root.canManage(c.username());
        Button back = new Button("←");
        back.getStyleClass().addAll("small", "attach");
        back.setId("community-back");
        back.setOnAction(e -> {
            main.selectedCommunity = 0;
            refreshSidebar();
        });
        Label name = UiKit.label(ConversationView.groupName(root), "row-title");
        name.setMinWidth(0);
        Button settings = new Button();
        settings.setGraphic(Icons.of(Icons.SETTINGS, 15, "muted"));
        settings.getStyleClass().addAll("small", "attach");
        settings.setTooltip(new Tooltip(I18n.t("community.settings")));
        settings.setId("community-settings");
        settings.setOnAction(e -> main.communities.openCommunityInfo(root.id()));
        Button mapButton = new Button();
        mapButton.setGraphic(Icons.of(Icons.MAP, 15, main.mapShown == root.id() ? "accent" : "muted"));
        mapButton.getStyleClass().addAll("small", "attach");
        mapButton.setTooltip(new Tooltip(I18n.t("map.open")));
        mapButton.setId("community-map-button");
        mapButton.setOnAction(e -> main.places.showMap(root.id()));
        HBox top = new HBox(6, back, name, UiKit.grow(), mapButton, settings);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(4, 10, 4, 6));
        main.list.getChildren().add(top);
        if (manager) {
            Button invite = new Button(I18n.t("community.invite"));
            invite.getStyleClass().addAll("small", "primary");
            invite.setId("community-invite");
            invite.setOnAction(e -> main.communities.openInvite(root.id()));
            Button addChannel = new Button(I18n.t("community.addChannel"));
            addChannel.getStyleClass().add("small");
            addChannel.setId("add-channel");
            addChannel.setOnAction(e -> main.communities.openNewChannel(root.id()));
            HBox actions = new HBox(8, invite, addChannel);
            actions.setPadding(new Insets(0, 14, 6, 14));
            main.list.getChildren().add(actions);
        }
        main.list.getChildren().add(heading(I18n.t("community.channels")));
        for (Packet.GroupInfo ch : c.channels(root.id())) {
            String conversation = AccountSession.group(ch.id());
            Label title = UiKit.label(ConversationView.channelTitle(ch), "row-title");
            HBox row = new HBox(8, title, UiKit.grow());
            int unread = unread(conversation, summaries);
            if (unread > 0) {
                row.getChildren().add(UiKit.label(String.valueOf(unread), "unread-badge"));
                title.setStyle("-fx-font-weight: bold;");
            }
            boolean open = main.current != null && main.current.conversation().equals(conversation);
            row.getStyleClass().addAll("list-row", "channel-row");
            if (open) row.getStyleClass().add("selected");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setId("channel-" + ch.id());
            A11y.clickable(row, null);
            row.setOnMouseClicked(e -> main.open(conversation));
            main.list.getChildren().add(row);
            addVoiceParticipants(ch.id());
        }
    }

    /** Who is in a channel's (or group's) voice room, under its row: faces light up while they talk. */
    private void addVoiceParticipants(long groupId) {
        List<String> people = main.voiceRooms.getOrDefault(groupId, List.of());
        if (people.isEmpty()) return;
        VBox box = new VBox(2);
        box.getStyleClass().add("voice-people");
        for (String u : people) {
            boolean talking = main.voiceState.groupId() == groupId && main.voiceState.speaking().contains(u);
            Label name = UiKit.label(u.equals(main.session.client().username()) ? I18n.t("group.you", u) : u, "voice-name");
            HBox row = new HBox(8, UiKit.voiceFace(u, talking, 22), name);
            if (main.voiceState.groupId() == groupId && u.equals(main.session.client().username()) && main.voiceState.muted()) {
                row.getChildren().add(Icons.of(Icons.MIC_OFF, 12, "danger"));
            }
            row.setAlignment(Pos.CENTER_LEFT);
            row.setId("voice-person-" + groupId + "-" + u);
            box.getChildren().add(row);
        }
        main.list.getChildren().add(box);
    }

    private void buildChats() {
        ChatClient client = main.session.client();
        Button newGroup = new Button(I18n.t("conv.newGroup"));
        newGroup.setId("new-group");
        newGroup.getStyleClass().add("small");
        newGroup.setOnAction(e -> main.contacts.openNewGroup());
        HBox heading = new HBox(UiKit.label(I18n.t("main.conversations"), "overline"), UiKit.grow(), newGroup);
        heading.getStyleClass().add("list-heading");
        heading.setAlignment(Pos.CENTER_LEFT);
        main.list.getChildren().add(heading);

        Map<String, History.Summary> summaries = new LinkedHashMap<>();
        try {
            for (History.Summary s : main.session.history().summaries()) summaries.put(s.conversation(), s);
        } catch (IOException ignored) {
            // show what we can
        }
        Set<String> shown = new HashSet<>();
        for (History.Summary s : summaries.values()) {
            Node row = chatRow(s.conversation(), s);
            if (row != null) {
                main.list.getChildren().add(row);
                shown.add(s.conversation());
            }
        }
        for (Packet.GroupInfo g : client.groups()) {
            if (g.community() != 0) continue; // community channels live in their own tab
            String key = AccountSession.group(g.id());
            if (!shown.contains(key)) main.list.getChildren().add(chatRow(key, null));
        }
        if (main.list.getChildren().size() == 1) {
            Label empty = UiKit.label(I18n.t("main.noConversations"), "caption");
            empty.setWrapText(true);
            empty.setPadding(new Insets(10, 14, 0, 14));
            main.list.getChildren().add(empty);
        }
    }

    /**
     * How a stored poll or amber message is previewed (in the chat list, notifications), or null
     * for an ordinary text. An amber message never shows its text before its date.
     */
    static String specialPreview(String text) {
        com.emirchat.chat.Poll p = com.emirchat.chat.Poll.fromStored(text);
        if (p != null) return "📊 " + p.question();
        com.emirchat.chat.Capsule c = com.emirchat.chat.Capsule.fromStored(text);
        if (c == null) return null;
        return c.open(System.currentTimeMillis()) ? "🟠 " + Markdown.plain(c.text()) : "🟠 " + I18n.t("amber.preview", UiKit.time(c.opensAt()));
    }

    /** Voice messages are files named voice-….wav (see ConversationView#startRecording). */
    static boolean isVoiceName(String text) {
        String name = text.startsWith("📎 ") ? text.substring("📎 ".length()) : text;
        return name.startsWith("voice-") && name.endsWith(".wav") && !name.contains(" ");
    }

    private Node chatRow(String conversation, History.Summary s) {
        ChatClient client = main.session.client();
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
                : specialPreview(s.lastText()) != null ? specialPreview(s.lastText())
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
        boolean open = main.current != null && main.current.conversation().equals(conversation);
        if (group) {
            int inVoice = main.voiceRooms.getOrDefault(Long.parseLong(conversation.substring(2)), List.of()).size();
            if (inVoice > 0) right.getChildren().add(UiKit.label("🔊 " + inVoice, "voice-badge"));
        }
        if (s != null && s.unread() > 0 && !open) right.getChildren().add(UiKit.label(String.valueOf(s.unread()), "unread-badge"));
        if (avatar instanceof javafx.scene.layout.Region r) r.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox row = new HBox(avatar, text, UiKit.grow(), right);
        row.getStyleClass().add("list-row");
        if (open) row.getStyleClass().add("selected");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setId("chat-" + conversation.replace(':', '-'));
        A11y.clickable(row, null);
        row.setOnMouseClicked(e -> main.open(conversation));
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
            main.call(() -> main.session.client().connection().sendFriendRequest(name),
                    nowFriends -> main.notice(nowFriends ? I18n.t("main.nowFriends", name) : I18n.t("main.requestSent", name), false));
        };
        add.setOnAction(e -> doAdd.run());
        send.setOnAction(e -> doAdd.run());
        HBox addRow = new HBox(8, add, send);
        addRow.setAlignment(Pos.CENTER);
        addRow.setPadding(new Insets(4, 14, 8, 14));
        main.list.getChildren().add(addRow);

        if (!friends.incoming().isEmpty()) {
            main.list.getChildren().add(heading(I18n.t("main.requests")));
            for (String from : friends.incoming()) {
                Button accept = new Button(I18n.t("main.accept"));
                accept.setId("accept-" + from);
                accept.getStyleClass().addAll("small", "primary");
                accept.setOnAction(e -> main.call(() -> {
                    main.session.client().connection().respondFriendRequest(from, true);
                    return null;
                }, r -> main.session.refreshFriendsLater()));
                Button decline = new Button(I18n.t("main.decline"));
                decline.getStyleClass().add("small");
                decline.setOnAction(e -> main.call(() -> {
                    main.session.client().connection().respondFriendRequest(from, false);
                    return null;
                }, r -> main.session.refreshFriendsLater()));
                decline.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
                accept.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
                main.list.getChildren().add(row(UiKit.avatar(from, "avatar-sm", false),
                        new VBox(2, UiKit.label(from, "row-title"), UiKit.label(I18n.t("main.friendRequest"), "row-sub")),
                        new HBox(6, decline, accept)));
            }
        }
        if (!friends.outgoing().isEmpty()) {
            main.list.getChildren().add(heading(I18n.t("main.sent")));
            for (String to : friends.outgoing()) {
                Button cancel = new Button(I18n.t("common.cancel"));
                cancel.getStyleClass().add("small");
                cancel.setOnAction(e -> main.call(() -> {
                    main.session.client().connection().removeFriend(to);
                    return null;
                }, r -> main.session.refreshFriendsLater()));
                main.list.getChildren().add(row(UiKit.avatar(to, "avatar-sm", false),
                        new VBox(2, UiKit.label(to, "row-title"), UiKit.label(I18n.t("main.waitingAnswer"), "row-sub")), cancel));
            }
        }
        HBox friendsHeading = heading(I18n.t("main.friendsHeading", friends.friends().size()));
        if (!friends.friends().isEmpty()) {
            Button sky = new Button("✦ " + I18n.t("sky.button"));
            sky.getStyleClass().addAll("small", "link");
            sky.setId("open-sky");
            sky.setOnAction(e -> openSky(friends));
            friendsHeading.getChildren().addAll(UiKit.grow(), sky);
        }
        main.list.getChildren().add(friendsHeading);
        if (friends.friends().isEmpty()) {
            Label none = UiKit.label(I18n.t("main.noFriends"), "caption");
            none.setWrapText(true);
            VBox box = new VBox(10, Illustrations.desert(220), none);
            box.setAlignment(Pos.CENTER);
            box.setPadding(new Insets(14, 14, 0, 14));
            main.list.getChildren().add(box);
        }
        for (Packet.Friend f : friends.friends()) {
            ChatClient c = main.session.client();
            String state = c.keyChanged(f.username()) ? "⚠ " + I18n.t("main.keyChanged") : c.isVerified(f.username()) ? "✓ " + I18n.t("main.verifiedLower") : f.online() ? I18n.t("conv.online") : I18n.t("conv.offline");
            Label sub = UiKit.label(state, "row-sub");
            if (state.startsWith("⚠")) sub.getStyleClass().add("danger-text");
            if (state.startsWith("✓")) sub.getStyleClass().add("accent-text");
            Node r = row(UiKit.avatarWithPresence(f.username(), f.online()),
                    new VBox(2, UiKit.label(f.username(), "row-title"), sub), new HBox());
            r.setId("friend-" + f.username());
            A11y.clickable(r, null);
            r.setOnMouseClicked(e -> main.open(AccountSession.dm(f.username())));
            main.list.getChildren().add(r);
        }
    }

    /** The trust constellation of all friends, in a dialog. */
    private void openSky(Packet.FriendList friends) {
        ChatClient c = main.session.client();
        List<Constellation.Star> stars = friends.friends().stream().map(f -> new Constellation.Star(f.username(),
                c.keyChanged(f.username()) ? Constellation.Trust.CHANGED
                        : c.isVerified(f.username()) ? Constellation.Trust.VERIFIED : Constellation.Trust.UNVERIFIED,
                f.online())).toList();
        javafx.scene.canvas.Canvas canvas = Constellation.sky(c.username(), stars, name -> {
            main.overlay.close();
            main.open(AccountSession.dm(name));
        });
        Label legend = UiKit.label(I18n.t("sky.legend"), "caption");
        legend.setWrapText(true);
        legend.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        Button close = new Button(I18n.t("common.close"));
        close.setId("sky-close");
        close.setOnAction(e -> main.overlay.close());
        VBox box = Overlay.dialog(I18n.t("sky.title"), canvas, legend, new HBox(UiKit.grow(), close));
        box.setId("sky-dialog");
        box.setMaxWidth(Constellation.WIDTH + 60);
        main.overlay.show(box);
    }

    private static HBox heading(String text) {
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
}
