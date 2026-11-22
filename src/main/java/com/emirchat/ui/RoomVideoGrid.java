package com.emirchat.ui;

import com.emirchat.call.RoomVideo;
import com.emirchat.video.Scaler;
import com.emirchat.video.VideoFrame;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

/**
 * Every camera and screen shared at the fire, side by side: one fills the stage, two sit next to
 * each other, more make a grid. Click a picture to make it large (the others go into a strip
 * below it), click it again to go back to the grid. Whoever speaks gets a glowing frame.
 *
 * <p>Pictures arrive on video threads; each tile converts its own there and draws only the newest
 * on the next pulse, so a busy room skips pictures instead of falling behind.
 */
final class RoomVideoGrid implements RoomVideo.View {
    static final String ME = "";

    private final StackPane root = new StackPane();
    private final Map<String, Tile> tiles = new LinkedHashMap<>();
    private String spotlight;
    private Set<String> speaking = Set.of();

    RoomVideoGrid() {
        root.getStyleClass().add("room-video");
        root.setId("room-video");
        root.setPickOnBounds(false);
        root.setVisible(false);
    }

    StackPane root() {
        return root;
    }

    /** Who is speaking now (their tiles glow). */
    void speaking(Set<String> who) {
        speaking = who;
        for (var e : tiles.entrySet()) e.getValue().box.pseudoClassStateChanged(SPEAKING, who.contains(e.getKey()));
    }

    // ------------------------------------------------------------------ RoomVideo.View (video threads)

    @Override public void remote(String who, VideoFrame frame, boolean screen) {
        tile(who).offer(frame, screen);
    }

    @Override public void remoteStopped(String who) {
        Platform.runLater(() -> remove(who));
    }

    @Override public void local(VideoFrame frame, boolean screen) {
        tile(ME).offer(frame, screen);
    }

    @Override public void localStopped() {
        Platform.runLater(() -> remove(ME));
    }

    /** Left the room: nothing to show. */
    void clear() {
        Platform.runLater(() -> {
            tiles.clear();
            spotlight = null;
            layout();
        });
    }

    /** Names with a picture now, in the order they started (for tests and the screen reader). */
    List<String> showing() {
        return List.copyOf(tiles.keySet());
    }

    private Tile tile(String who) {
        synchronized (tiles) {
            Tile t = tiles.get(who);
            if (t != null) return t;
            Tile created = new Tile(who);
            tiles.put(who, created);
            Platform.runLater(this::layout);
            return created;
        }
    }

    private void remove(String who) {
        synchronized (tiles) {
            tiles.remove(who);
        }
        if (who.equals(spotlight)) spotlight = null;
        layout();
    }

    private void toggleSpotlight(String who) {
        spotlight = who.equals(spotlight) || tiles.size() < 2 ? null : who;
        layout();
    }

    /** Columns for {@code n} equal tiles: 1, 2, 2x2, 3x2, 3x3, 4x3. */
    static int columns(int n) {
        if (n <= 1) return 1;
        if (n <= 4) return 2;
        if (n <= 9) return 3;
        return 4;
    }

    private void layout() {
        List<Tile> all;
        synchronized (tiles) {
            all = new ArrayList<>(tiles.values());
        }
        root.setVisible(!all.isEmpty());
        if (all.isEmpty()) {
            root.getChildren().clear();
            return;
        }
        double width = Math.max(200, root.getWidth() > 0 ? root.getWidth() : 720);
        double height = Math.max(150, root.getHeight() > 0 ? root.getHeight() : 450);
        if (spotlight != null && tiles.containsKey(spotlight)) {
            Tile big = tiles.get(spotlight);
            HBox strip = new HBox(8);
            strip.setAlignment(Pos.CENTER);
            double stripH = Math.min(110, height * 0.22);
            for (Tile t : all) {
                if (t == big) continue;
                t.size(stripH * 16 / 9, stripH);
                strip.getChildren().add(t.box);
            }
            big.size(width, height - stripH - 8);
            VBox column = new VBox(8, big.box, strip);
            column.setAlignment(Pos.CENTER);
            column.setPickOnBounds(false);
            root.getChildren().setAll(column);
            return;
        }
        int cols = columns(all.size());
        int rows = (all.size() + cols - 1) / cols;
        double gap = 8;
        double w = (width - gap * (cols - 1)) / cols;
        double h = Math.min((height - gap * (rows - 1)) / rows, w * 9 / 16 + 1);
        GridPane grid = new GridPane();
        grid.setHgap(gap);
        grid.setVgap(gap);
        grid.setAlignment(Pos.CENTER);
        grid.setPickOnBounds(false);
        for (int i = 0; i < all.size(); i++) {
            Tile t = all.get(i);
            t.size(w, h);
            grid.add(t.box, i % cols, i / cols);
        }
        root.getChildren().setAll(grid);
    }

    private static final javafx.css.PseudoClass SPEAKING = javafx.css.PseudoClass.getPseudoClass("speaking");

    /** One person's picture with their name. */
    private final class Tile {
        final String who;
        final StackPane box = new StackPane();
        final ImageView view = new ImageView();
        final Label name;
        private final AtomicReference<byte[]> latest = new AtomicReference<>();
        private Scaler scaler;
        private int width;
        private int height;
        private boolean screen;
        private WritableImage image;

        Tile(String who) {
            this.who = who;
            view.setPreserveRatio(true);
            view.setSmooth(true);
            name = UiKit.label(who.equals(ME) ? I18n.t("video.you") : who, "video-tile-name");
            StackPane.setAlignment(name, Pos.BOTTOM_LEFT);
            StackPane.setMargin(name, new Insets(6));
            box.getChildren().addAll(view, name);
            box.getStyleClass().add("video-tile");
            box.setId("video-tile-" + (who.equals(ME) ? "me" : who));
            Rectangle clip = new Rectangle();
            clip.setArcWidth(14);
            clip.setArcHeight(14);
            clip.widthProperty().bind(box.widthProperty());
            clip.heightProperty().bind(box.heightProperty());
            box.setClip(clip);
            box.setAccessibleText(name.getText());
            A11y.clickable(box, name.getText());
            box.setOnMouseClicked(e -> toggleSpotlight(who));
            Platform.runLater(() -> box.pseudoClassStateChanged(SPEAKING, speaking.contains(who)));
        }

        void size(double w, double h) {
            box.setMinSize(w, h);
            box.setPrefSize(w, h);
            box.setMaxSize(w, h);
            view.setFitWidth(w);
            view.setFitHeight(h);
        }

        void offer(VideoFrame frame, boolean isScreen) {
            byte[] bgra;
            synchronized (this) {
                try {
                    if (scaler == null) scaler = new Scaler();
                    bgra = scaler.toBgra(frame);
                } catch (IllegalStateException e) {
                    return;
                }
                width = frame.width();
                height = frame.height();
                screen = isScreen;
            }
            if (latest.getAndSet(bgra) == null) Platform.runLater(this::draw);
        }

        private void draw() {
            byte[] bgra = latest.getAndSet(null);
            if (bgra == null) return;
            int w;
            int h;
            boolean isScreen;
            synchronized (this) {
                w = width;
                h = height;
                isScreen = screen;
            }
            if (bgra.length != w * h * 4) return;
            if (image == null || (int) image.getWidth() != w || (int) image.getHeight() != h) image = new WritableImage(w, h);
            image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getByteBgraPreInstance(), bgra, 0, w * 4);
            if (view.getImage() != image) view.setImage(image);
            String label = who.equals(ME) ? I18n.t("video.you") : who;
            name.setText(isScreen ? "🖥 " + label : label);
        }
    }
}
