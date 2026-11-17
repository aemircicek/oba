package com.emirchat.ui;

import com.emirchat.api.Packet;
import com.emirchat.client.ChatClient;
import com.emirchat.client.History;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The quick switcher (Ctrl+K or Cmd+K): type to jump to a chat, channel or friend, or to run an
 * action.
 */
final class QuickSwitcher {
    private final MainView main;

    QuickSwitcher(MainView main) {
        this.main = main;
    }

    /** Everything the quick switcher can jump to: recent chats first, then channels, friends and actions. */
    private List<Palette.Entry> paletteEntries() {
        ChatClient c = main.session.client();
        List<Palette.Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            for (History.Summary s : main.session.history().summaries()) {
                String conv = s.conversation();
                if (conv.startsWith("dm:")) {
                    out.add(new Palette.Entry(Palette.Kind.CHAT, conv.substring(3), I18n.t("palette.dm"), conv, () -> main.open(conv)));
                } else {
                    Packet.GroupInfo g = main.group(Long.parseLong(conv.substring(2)));
                    if (g == null) continue;
                    String title = g.community() != 0 ? ConversationView.channelTitle(g) : ConversationView.groupName(g);
                    String sub = g.community() != 0 ? main.sidebarUi.communityName(g.community()) : I18n.t("palette.group");
                    out.add(new Palette.Entry(g.community() != 0 ? Palette.Kind.CHANNEL : Palette.Kind.CHAT, title, sub, conv, () -> main.open(conv)));
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
                    main.selectedCommunity = g.id();
                    main.open(conv);
                }));
            } else {
                String title = g.community() != 0 ? ConversationView.channelTitle(g) : ConversationView.groupName(g);
                String sub = g.community() != 0 ? main.sidebarUi.communityName(g.community()) : I18n.t("palette.group");
                out.add(new Palette.Entry(g.community() != 0 ? Palette.Kind.CHANNEL : Palette.Kind.CHAT, title, sub, conv, () -> main.open(conv)));
            }
        }
        for (Packet.Friend f : c.friends().friends()) {
            String conv = AccountSession.dm(f.username());
            if (!seen.contains(conv)) {
                out.add(new Palette.Entry(Palette.Kind.FRIEND, f.username(), I18n.t("palette.friend"), conv, () -> main.open(conv)));
            }
        }
        out.add(new Palette.Entry(Palette.Kind.ACTION, I18n.t("main.settings"), I18n.t("palette.action"), "settings", main.settingsUi::openSettings));
        out.add(new Palette.Entry(Palette.Kind.ACTION, I18n.t("appearance.title"), I18n.t("palette.action"), "appearance", main.settingsUi::openAppearance));
        out.add(new Palette.Entry(Palette.Kind.ACTION, I18n.t("conv.newGroup"), I18n.t("palette.action"), "new-group", main.contacts::openNewGroup));
        out.add(new Palette.Entry(Palette.Kind.ACTION, I18n.t("community.new"), I18n.t("palette.action"), "new-community", main.communities::openNewCommunity));
        out.add(new Palette.Entry(Palette.Kind.ACTION, I18n.t("community.join"), I18n.t("palette.action"), "join-community", main.communities::openJoinCommunity));
        out.add(new Palette.Entry(Palette.Kind.ACTION, I18n.t("main.myKey"), I18n.t("palette.action"), "my-key", main.contacts::openMyKey));
        out.add(new Palette.Entry(Palette.Kind.ACTION, I18n.t("catchup.title"), I18n.t("palette.action"), "catch-up",
                () -> main.catchUp.showCatchUp(0, true)));
        out.add(new Palette.Entry(Palette.Kind.ACTION, Theme.isDark() ? I18n.t("palette.light") : I18n.t("palette.dark"),
                I18n.t("palette.action"), "theme", () -> {
                    AppPrefs.setTheme(Theme.isDark() ? "light" : "dark");
                    Theme.refreshAll();
                    main.sidebarUi.refreshSidebar();
                    if (main.current != null) main.current.refreshHeader();
                }));
        return out;
    }

    void openPalette() {
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
            main.overlay.close();
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
        main.overlay.show(box);
        Platform.runLater(query::requestFocus);
    }
}
