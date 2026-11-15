package com.emirchat.ui;

import com.emirchat.api.Packet;
import com.emirchat.client.History;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * What helps while writing a message: the growing text box and its keys, suggestions for emoji,
 * commands and mentions, the emoji picker, and dropping or pasting files.
 */
final class ComposerTools {
    private final ConversationView chat;

    ComposerTools(ConversationView chat) {
        this.chat = chat;
    }

    final EmojiSuggestions suggestions = new EmojiSuggestions();
    Node picker;                 // the open emoji picker, or null
    /** Grows the composer with its text, up to six lines (then it scrolls). */
    void resizeComposer() {
        String text = chat.input.getText();
        double width = Math.max(120, chat.input.getWidth() - 32);
        double charsPerLine = Math.max(10, width / 7.4);
        int rows = 0;
        for (String line : text.split("\n", -1)) rows += 1 + (int) (line.length() / charsPerLine);
        chat.input.setPrefRowCount(Math.max(1, Math.min(6, rows)));
        suggestions.update();
    }

    /**
     * Enter sends, Shift+Enter starts a new line, Escape cancels a reply or edit, Up in an empty
     * composer edits your last message, and pasting a picture or a file sends it.
     */
    void composerKey(javafx.scene.input.KeyEvent e) {
        if (suggestions.handle(e)) {
            e.consume();
            return;
        }
        switch (e.getCode()) {
            case ENTER -> {
                e.consume();
                if (e.isShiftDown() || e.isAltDown()) chat.input.replaceSelection("\n");
                else chat.sender.send();
            }
            case ESCAPE -> {
                if (picker != null) closePicker();
                else chat.sender.clearContext();
            }
            case UP -> {
                if (chat.input.getText().isEmpty() && chat.editing == null && editLastOwn()) e.consume();
            }
            case V -> {
                if (e.isShortcutDown() && pasteAttachment()) e.consume();
            }
            default -> { }
        }
    }

    private boolean editLastOwn() {
        try {
            List<History.Message> recent = chat.session.history().load(chat.conversation, 30);
            for (int i = recent.size() - 1; i >= 0; i--) {
                History.Message m = recent.get(i);
                if (m.outgoing() && m.hasUid() && !m.deleted() && m.file() == null) {
                    chat.sender.startEdit(m);
                    return true;
                }
            }
        } catch (IOException ignored) {
            // nothing to edit
        }
        return false;
    }

    /** The ":name" suggestions above the composer: arrows move, Enter or Tab inserts. */
    final class EmojiSuggestions {
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
            String text = chat.input.getText();
            commands = text.startsWith("/") && !text.contains(" ") && !text.contains("\n") ? SlashCommands.complete(text) : List.of();
            String typedName = commands.isEmpty() ? typedMention(text, chat.input.getCaretPosition()) : null;
            if (typedName != null) {
                commands = mentionCandidates(typedName);
                mentionMode = !commands.isEmpty();
                if (mentionMode) {
                    selected = 0;
                    renderCommands();
                    StackPane.setMargin(box, new Insets(0, 0, chat.composer.getHeight() + 4, 56));
                    box.setVisible(true);
                    box.setManaged(true);
                    return;
                }
            }
            mentionMode = false;
            String code = commands.isEmpty() ? Emoji.typedShortcode(text, chat.input.getCaretPosition()) : null;
            items = code == null ? List.of() : Emoji.suggest(code, 6);
            selected = 0;
            if (!commands.isEmpty()) {
                renderCommands();
                StackPane.setMargin(box, new Insets(0, 0, chat.composer.getHeight() + 4, 56));
                box.setVisible(true);
                box.setManaged(true);
                return;
            }
            if (items.isEmpty()) {
                hide();
                return;
            }
            render();
            StackPane.setMargin(box, new Insets(0, 0, chat.composer.getHeight() + 4, 56));
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
                int caret = chat.input.getCaretPosition();
                String typed = typedMention(chat.input.getText(), caret);
                if (typed == null) return;
                int start = caret - typed.length() - 1;
                String name = commands.get(selected);
                chat.input.replaceText(start, caret, "@" + name + " ");
                chat.input.positionCaret(start + name.length() + 2);
                mentionMode = false;
                hide();
                chat.input.requestFocus();
                return;
            }
            if (!commands.isEmpty()) {
                chat.input.setText("/" + commands.get(selected) + " ");
                chat.input.positionCaret(chat.input.getText().length());
                hide();
                chat.input.requestFocus();
                return;
            }
            int caret = chat.input.getCaretPosition();
            String code = Emoji.typedShortcode(chat.input.getText(), caret);
            if (code == null || items.isEmpty()) return;
            int start = caret - code.length() - 1;
            chat.input.replaceText(start, caret, items.get(selected).emoji() + " ");
            chat.input.positionCaret(start + items.get(selected).emoji().length() + 1);
            hide();
            chat.input.requestFocus();
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
        String me = chat.session.client().username();
        List<String> people = new java.util.ArrayList<>();
        if (chat.peer != null) {
            people.add(chat.peer);
        } else {
            Packet.GroupInfo g = chat.group();
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

    /**
     * Opens or closes the emoji picker. With a message it picks a reaction to that message;
     * without one it inserts the emoji where the caret is.
     */
    void togglePicker(History.Message reactTo) {
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
                        chat.sender.react(reactTo, entry.emoji());
                        closePicker();
                    } else {
                        chat.input.replaceSelection(entry.emoji());
                        chat.input.requestFocus();
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
        chat.layer.getChildren().add(panel);
        StackPane.setAlignment(panel, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(panel, new Insets(0, 14, chat.composer.getHeight() + 6, 0));
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

    void closePicker() {
        if (picker == null) return;
        chat.layer.getChildren().remove(picker);
        picker = null;
        chat.input.requestFocus();
    }

    static boolean isInside(Object target, Node container) {
        for (Node n = target instanceof Node node ? node : null; n != null; n = n.getParent()) {
            if (n == container) return true;
        }
        return false;
    }

    /** Files dropped on the conversation are sent, one by one. */
    void installDropTarget() {
        Label hint = UiKit.label(I18n.t("conv.dropHint"), "drop-hint");
        StackPane veil = new StackPane(new VBox(10, Icons.of(Icons.PAPERCLIP, 34, "accent"), hint) {
            {
                setAlignment(Pos.CENTER);
            }
        });
        veil.getStyleClass().add("drop-veil");
        veil.setVisible(false);
        veil.setMouseTransparent(true);
        chat.layer.getChildren().add(veil);
        chat.layer.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
                veil.setVisible(true);
            }
            e.consume();
        });
        chat.layer.setOnDragExited(e -> veil.setVisible(false));
        chat.layer.setOnDragDropped(e -> {
            veil.setVisible(false);
            boolean ok = e.getDragboard().hasFiles();
            if (ok) e.getDragboard().getFiles().forEach(chat.sender::sendFile);
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
            clip.getFiles().forEach(chat.sender::sendFile);
            return true;
        }
        if (clip.hasImage() && !clip.hasString()) {
            Image image = clip.getImage();
            String name = "screenshot-" + java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png";
            chat.sender.sendBytes(name, () -> ImageFiles.png(image), "image/png");
            return true;
        }
        return false;
    }
}
