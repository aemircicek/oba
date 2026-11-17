package com.emirchat.ui;

import com.emirchat.chat.FileMessage;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** The full-window viewer for the pictures of a conversation, with previous and next. */
final class ImageViewer {
    private final ConversationView chat;

    ImageViewer(ConversationView chat) {
        this.chat = chat;
    }

    /** A full-window viewer for the pictures in this conversation: arrows to browse, Esc to close. */
    void openViewer(FileMessage file) {
        int[] index = {Math.max(0, indexOf(file))};
        ImageView view = new ImageView();
        view.setPreserveRatio(true);
        view.setSmooth(true);
        // sized from the conversation pane (not from the viewer itself, which would grow in a loop)
        view.fitWidthProperty().bind(chat.root.widthProperty().subtract(150));
        view.fitHeightProperty().bind(chat.root.heightProperty().subtract(130));
        Label caption = UiKit.label("", "viewer-caption");
        Label count = UiKit.label("", "viewer-count");
        Button prev = viewerButton(Icons.CHEVRON_LEFT, I18n.t("viewer.previous"));
        Button next = viewerButton(Icons.CHEVRON_RIGHT, I18n.t("viewer.next"));
        Button save = viewerButton(Icons.DOWNLOAD, I18n.t("conv.saveTip"));
        Button close = viewerButton(Icons.X, I18n.t("common.close"));
        close.setId("viewer-close");
        StackPane veil = new StackPane();
        Runnable show = () -> {
            FileMessage f = chat.gallery.isEmpty() ? file : chat.gallery.get(index[0]);
            caption.setText(f.name() + " · " + ConversationView.humanSize(f.size()));
            count.setText(chat.gallery.size() > 1 ? (index[0] + 1) + " / " + chat.gallery.size() : "");
            prev.setDisable(index[0] == 0);
            next.setDisable(index[0] >= chat.gallery.size() - 1);
            Image cached = chat.images.get(ConversationView.fileKey(f));
            view.setImage(cached);
            if (cached == null) {
                chat.session.downloads().execute(() -> {
                    try {
                        Image img = ImageFiles.decode(chat.session.client().downloadFile(f));
                        if (img == null) return;
                        Platform.runLater(() -> {
                            chat.images.put(ConversationView.fileKey(f), img);
                            if (chat.gallery.isEmpty() || chat.gallery.get(index[0]) == f) view.setImage(img);
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
            index[0] = Math.min(chat.gallery.size() - 1, index[0] + 1);
            show.run();
        });
        save.setOnAction(e -> chat.sender.saveFile(chat.gallery.isEmpty() ? file : chat.gallery.get(index[0])));
        close.setOnAction(e -> chat.layer.getChildren().remove(veil));
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
        chat.layer.getChildren().add(veil);
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
        for (int i = 0; i < chat.gallery.size(); i++) if (ConversationView.fileKey(chat.gallery.get(i)).equals(ConversationView.fileKey(file))) return i;
        return -1;
    }

    private static Button viewerButton(String icon, String tip) {
        Button b = new Button();
        b.setGraphic(Icons.of(icon, 18, "on-dark"));
        b.getStyleClass().add("viewer-button");
        b.setTooltip(new Tooltip(tip));
        return b;
    }
}
