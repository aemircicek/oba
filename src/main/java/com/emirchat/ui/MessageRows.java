package com.emirchat.ui;

import com.emirchat.chat.FileMessage;
import com.emirchat.client.History;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Draws the messages of a conversation: bubbles with their text, quotes, reactions, files, polls
 * and thread links, the tools that appear on hover, and the date lines between days.
 */
final class MessageRows {
    private final ConversationView chat;

    MessageRows(ConversationView chat) {
        this.chat = chat;
    }

    /** "Scroll up for older messages" at the top while there are more. */
    Node olderHint() {
        Label hint = UiKit.label("↑ " + I18n.t("conv.older"), "system-line");
        HBox row = new HBox(hint);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(10, 0, 0, 0));
        row.setId("older-hint");
        hint.setOnMouseClicked(e -> chat.loadOlder());
        hint.setStyle("-fx-cursor: hand;");
        return row;
    }

    Node buildRow(History.Message m, boolean continued) {
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
            chat.statusLabels.put(m.id(), status);
            applyStatus(status, m.status());
            meta.getChildren().add(status);
        }
        bubble.getChildren().addAll(body, meta);
        bubble.maxWidthProperty().bind(chat.messages.widthProperty().multiply(0.70));
        // plain text hugs its content; formatted text (TextFlow) wraps at the bubble's limit instead
        bubble.setMinWidth(body instanceof Label || body instanceof HBox || body.getStyleClass().contains("poll-card")
                ? Region.USE_PREF_SIZE : 0);
        bubble.setMinHeight(Region.USE_PREF_SIZE);
        if (!own && !m.deleted() && m.file() == null && Markdown.mentions(m.text(), chat.session.client().username())) {
            bubble.getStyleClass().add("mentions-me"); // someone named us: the bubble says so
        }
        if (m.hasUid() && !m.deleted()) {
            bubble.setOnContextMenuRequested(e -> menu(m).show(bubble, e.getScreenX(), e.getScreenY()));
        }

        VBox column = new VBox(bubble);
        column.setAlignment(own ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        if (chat.groupId != 0 && !own && !continued) {
            Label name = UiKit.label(m.sender(), "sender-name");
            name.setStyle("-fx-text-fill: " + UiKit.css(UiKit.nameColor(m.sender(), Theme.isDark())) + ";");
            column.getChildren().add(0, name);
        }
        Map<String, String> theirs = chat.reactions.get(m.id());
        if (theirs != null && !theirs.isEmpty()) column.getChildren().add(reactionChips(m, theirs, own));
        Node threadSummary = m.hasUid() ? threadLink(m) : null;
        if (threadSummary != null) column.getChildren().add(threadSummary);
        HBox row = new HBox(6);
        if (chat.groupId != 0 && !own) {
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
            History.Message original = chat.session.history().find(chat.conversation, target);
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
        String me = chat.session.client().username();
        byEmoji.forEach((emoji, people) -> {
            Label chip = UiKit.label(emoji + (people.size() > 1 ? " " + people.size() : ""), "reaction-chip");
            if (people.contains(me)) chip.getStyleClass().add("mine");
            if (m.id() == chat.popId && emoji.equals(chat.popEmoji)) {
                chat.popId = 0;
                pop(chip);
            }
            Tooltip.install(chip, new Tooltip(String.join(", ", people.stream().map(p -> p.equals(me) ? I18n.t("conv.youLower") : p).toList())));
            chip.setOnMouseClicked(e -> chat.sender.react(m, people.contains(me) ? "" : emoji));
            chips.getChildren().add(chip);
        });
        return chips;
    }

    private javafx.scene.control.ContextMenu menu(History.Message m) {
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        MenuItem reply = new MenuItem(I18n.t("conv.reply"));
        reply.setId("menu-reply");
        reply.setOnAction(e -> chat.sender.startReply(m));
        javafx.scene.control.Menu react = new javafx.scene.control.Menu(I18n.t("conv.react"));
        String mine = AccountSession.displayEmoji(chat.reactions.getOrDefault(m.id(), Map.of()).getOrDefault(chat.session.client().username(), ""));
        for (String emoji : AccountSession.REACTIONS) {
            MenuItem item = new MenuItem(emoji.equals(mine) ? emoji + "  ✓" : emoji);
            item.setId("react-" + AccountSession.REACTIONS.indexOf(emoji));
            item.setOnAction(e -> chat.sender.react(m, emoji.equals(mine) ? "" : emoji));
            react.getItems().add(item);
        }
        menu.getItems().addAll(reply, react);
        if (chat.groupId != 0) {
            MenuItem inThread = new MenuItem(I18n.t("thread.reply"));
            inThread.setId("menu-thread");
            inThread.setOnAction(e -> chat.side.openThread(m));
            menu.getItems().add(inThread);
        }
        if (chat.header.mayModerate()) {
            boolean pinned = chat.side.pinnedMessages().stream().anyMatch(p -> p.id() == m.id());
            MenuItem pinItem = new MenuItem(pinned ? I18n.t("pin.unpin") : I18n.t("pin.pin"));
            pinItem.setId("menu-pin");
            pinItem.setOnAction(e -> chat.side.pin(m, !pinned));
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
                edit.setOnAction(e -> chat.sender.startEdit(m));
                menu.getItems().add(edit);
            }
            MenuItem delete = new MenuItem(I18n.t("conv.delete"));
            delete.setId("menu-delete");
            delete.setOnAction(e -> chat.sender.deleteForEveryone(m));
            menu.getItems().add(delete);
        }
        return menu;
    }

    void applyStatus(Label l, int status) {
        l.getStyleClass().remove("failed");
        switch (status) {
            case History.PENDING -> l.setText("…");
            case History.SENT -> l.setText(chat.groupId != 0 ? "✓" : "✓");
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

    private Node fileCard(FileMessage file) {
        if (com.emirchat.call.VoiceNotes.isVoice(file.mimeType(), file.name())) return chat.voiceUi.voiceCard(file);
        StackPane icon = new StackPane(Icons.of(Icons.FILE, 18, "accent"));
        icon.getStyleClass().add("file-icon");
        VBox titles = new VBox(1, UiKit.label(file.name(), "file-name"), UiKit.label(ConversationView.humanSize(file.size()), "file-size"));
        Button save = new Button();
        save.getStyleClass().addAll("small", "attach");
        save.setGraphic(Icons.of(Icons.DOWNLOAD, 16, "muted"));
        save.setTooltip(new Tooltip(I18n.t("conv.saveTip")));
        save.setOnAction(e -> chat.sender.saveFile(file));
        HBox card = new HBox(icon, titles, UiKit.grow(), save);
        card.getStyleClass().add("file-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMinWidth(220);
        if (!file.isPreviewableImage()) return card;
        if (chat.gallery.stream().noneMatch(f -> ConversationView.fileKey(f).equals(ConversationView.fileKey(file)))) chat.gallery.add(file);
        VBox withPreview = new VBox(6, card);
        Image known = chat.images.get(ConversationView.fileKey(file));
        Runnable fetch = () -> chat.session.downloads().execute(() -> {
            try {
                Image image = known != null ? known : ImageFiles.decode(chat.session.client().downloadFile(file));
                if (image == null || image.isError()) return;
                Platform.runLater(() -> chat.images.put(ConversationView.fileKey(file), image));
                Platform.runLater(() -> {
                    ImageView view = new ImageView(image);
                    view.setPreserveRatio(true);
                    view.setFitWidth(Math.min(280, image.getWidth()));
                    view.setSmooth(true);
                    view.getStyleClass().add("file-preview");
                    view.setOnMouseClicked(e -> chat.viewer.openViewer(file));
                    Tooltip.install(view, new Tooltip(I18n.t("viewer.openTip")));
                    withPreview.getChildren().add(0, view);
                });
            } catch (Exception ignored) {
                // no preview; the file can still be saved, which reports any problem
            }
        });
        // decrypt pictures only when they come into view: long chats open fast and use less memory
        chat.whenVisible.put(withPreview, fetch);
        Platform.runLater(chat::checkVisible);
        return withPreview;
    }

    /** Quick reactions, reply and "more" beside a message while the pointer is over it. */
    private Node hoverTools(History.Message m, Node bubble) {
        HBox tools = new HBox(1);
        tools.getStyleClass().add("msg-tools");
        tools.setAlignment(Pos.CENTER);
        tools.setVisible(false);
        tools.setMaxHeight(Region.USE_PREF_SIZE);
        String mine = AccountSession.displayEmoji(chat.reactions.getOrDefault(m.id(), Map.of()).getOrDefault(chat.session.client().username(), ""));
        for (String emoji : List.of("👍", "❤️", "😂")) {
            Button b = new Button(emoji);
            b.getStyleClass().addAll("tool-button", "tool-emoji");
            if (emoji.equals(mine)) b.getStyleClass().add("mine");
            b.setOnAction(e -> chat.sender.react(m, emoji.equals(mine) ? "" : emoji));
            tools.getChildren().add(b);
        }
        Button more = toolButton(Icons.SMILE_PLUS, I18n.t("emoji.reactTip"), () -> chat.tools.togglePicker(m));
        more.setId("tool-react-" + m.id());
        Button reply = toolButton(Icons.CORNER_UP_LEFT, I18n.t("conv.reply"), () -> chat.sender.startReply(m));
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

    Node dateSeparator(java.time.LocalDate day) {
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

    /** A message with Markdown: styled text, quotes and colour-coded code blocks. */
    Node richText(String text) {
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
        if (span.has(Markdown.Style.MENTION) && span.text().substring(1).equalsIgnoreCase(chat.session.client().username())) {
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

    /** A poll: the question, each answer as a bar filled by its share of the votes, and the total. */
    private Node pollCard(History.Message m, com.emirchat.chat.Poll poll) {
        Map<String, Integer> byVoter = chat.votes.getOrDefault(m.id(), Map.of());
        String me = chat.session.client().username();
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
            if (!m.deleted()) option.setOnMouseClicked(e -> chat.sender.vote(m, index));
            card.getChildren().add(option);
        }
        card.getChildren().add(UiKit.label(total == 0 ? I18n.t("poll.noVotes") : total == 1 ? I18n.t("poll.oneVote")
                : I18n.t("poll.votes", total), "poll-total"));
        return card;
    }

    /** "3 replies · 14:02" under a message that has a thread. */
    private Node threadLink(History.Message m) {
        List<History.Message> replies = chat.threads.get(ConversationView.hex(m.uid()));
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
        link.setOnMouseClicked(e -> chat.side.openThread(m));
        return link;
    }
}
