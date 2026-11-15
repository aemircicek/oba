package com.emirchat.ui;

import com.emirchat.client.History;
import java.io.IOException;
import java.util.List;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The welcome-back summary after a while away: how long you were gone, mentions, polls without
 * your vote, replies in your threads and the busiest chats, each a click away.
 */
final class CatchUpDialog {
    private final MainView main;

    CatchUpDialog(MainView main) {
        this.main = main;
    }

    void maybeCatchUp() {
        long last = AppPrefs.lastSeen();
        long now = System.currentTimeMillis();
        if (last <= 0 || now - last < MainView.AWAY_MS || main.overlayOpen()) return;
        AppPrefs.setLastSeen(now);
        showCatchUp(now - last, false);
    }

    /**
     * "Welcome back": your own sky, how long you were away, and what is waiting (mentions, polls
     * without your vote, replies in your threads, the busiest chats), worked out from the local
     * history. {@code always}: also when there is nothing new (from the quick switcher).
     */
    void showCatchUp(long awayMillis, boolean always) {
        String me = main.session.client().username();
        main.session.background().execute(() -> {
            CatchUp.Digest d;
            try {
                d = CatchUp.compute(CatchUp.of(main.session.history()), me, System.currentTimeMillis());
            } catch (RuntimeException e) {
                return;
            }
            if (!always && !d.worthShowing()) return;
            CatchUp.Digest digest = d;
            Platform.runLater(() -> openCatchUp(digest, awayMillis));
        });
    }

    private void openCatchUp(CatchUp.Digest d, long awayMillis) {
        String me = main.session.client().username();
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
                HBox row = catchUpRow(null, where(b.conversation()), Markdown.plain(b.latest().text()), () -> main.open(b.conversation()));
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
                for (History.Summary s : main.session.history().summaries()) main.session.history().markRead(s.conversation());
            } catch (IOException ignored) {
                // the counts stay
            }
            main.overlay.close();
            main.sidebarUi.refreshSidebar();
        });
        allRead.setDisable(d.unread() == 0);
        Button go = new Button(I18n.t("catchup.continue"));
        go.getStyleClass().add("primary");
        go.setId("catchup-continue");
        go.setDefaultButton(true);
        go.setOnAction(e -> main.overlay.close());
        VBox box = new VBox(16, banner, scroll, new HBox(10, allRead, UiKit.grow(), go));
        box.getStyleClass().addAll("dialog", "catchup");
        box.setId("catchup");
        main.overlay.show(box);
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
            main.overlay.close();
            action.run();
        });
        return row;
    }

    /** "efe" for a direct chat, "# general · MobilCAD" for a channel, the group's name otherwise. */
    private String where(String conversation) {
        if (conversation.startsWith("dm:")) return conversation.substring(3);
        return main.sidebarUi.groupName(Long.parseLong(conversation.substring(2)));
    }

    private void jumpTo(History.Message m) {
        main.open(m.conversation());
        if (main.current != null) main.current.reveal(m.id());
    }

    private void jumpToThread(History.Message reply) {
        main.open(reply.conversation());
        try {
            History.Message root = main.session.history().find(reply.conversation(), reply.replyTo());
            if (root != null && main.current != null) main.current.act(root.id(), "thread");
        } catch (IOException ignored) {
            // the chat is open anyway
        }
    }
}
