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

    /** A room holds at most 12 people (the server's limit); with our own picture that is 12 tiles. */
    static final int MAX_TILES = 12;

    private final StackPane root = new StackPane();
    private final Map<String, Tile> tiles = new LinkedHashMap<>();
    /** When each person's picture last stopped: a frame still on its way then must not bring the tile back. */
    private final Map<String, Long> stoppedAt = new java.util.HashMap<>();
    static final long LATE_FRAME_MS = 800;
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
        Tile t = tile(who);
        if (t != null) t.offer(frame, screen);
    }

    @Override public void remoteStopped(String who) {
        Tile gone;
        synchronized (tiles) {
            gone = tiles.remove(who);
            stoppedAt.put(who, System.currentTimeMillis());
        }
        if (gone != null) gone.close();
        Platform.runLater(this::afterRemoval);
    }

    @Override public void local(VideoFrame frame, boolean screen) {
        Tile t = tile(ME);
        if (t != null) t.offer(frame, screen);
    }

    @Override public void localStopped() {
        Tile gone;
        synchronized (tiles) {
            gone = tiles.remove(ME);
            stoppedAt.put(ME, System.currentTimeMillis());
        }
        if (gone != null) gone.close();
        Platform.runLater(this::afterRemoval);
    }

    private void afterRemoval() {
        if (spotlight != null && !tiles.containsKey(spotlight)) spotlight = null;
        layout();
    }

    /** Left the room: nothing to show. */
    void clear() {
        List<Tile> gone;
        synchronized (tiles) {
            gone = new ArrayList<>(tiles.values());
            tiles.clear();
        }
        for (Tile t : gone) t.close();
        Platform.runLater(() -> {
            spotlight = null;
            layout();
        });
    }

    /** Names with a picture now, in the order they started (for tests and the screen reader). */
    List<String> showing() {
        return List.copyOf(tiles.keySet());
    }

    /** The tile for someone, made on their first picture; null when the grid is full. */
    private Tile tile(String who) {
        synchronized (tiles) {
            Tile t = tiles.get(who);
            if (t != null) return t;
            if (tiles.size() >= MAX_TILES) return null;
            Long stopped = stoppedAt.get(who);
            if (stopped != null && System.currentTimeMillis() - stopped < LATE_FRAME_MS) return null;
            Tile created = new Tile(who);
            tiles.put(who, created);
            Platform.runLater(this::layout);
            return created;
        }
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
        private volatile boolean closed;

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

        /** Frees the native scaler; pictures that still arrive for this tile are dropped. */
        synchronized void close() {
            closed = true;
            if (scaler != null) scaler.close();
            scaler = null;
        }

        void offer(VideoFrame frame, boolean isScreen) {
            byte[] bgra;
            synchronized (this) {
                if (closed) return;
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
            if (bgra == null || closed) return;
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
