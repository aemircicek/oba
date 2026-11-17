package com.emirchat.ui;

import com.emirchat.call.CallVideo;
import com.emirchat.video.Scaler;
import com.emirchat.video.VideoFrame;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

/**
 * The picture of a video call, above the conversation: the other side's camera or screen large,
 * ours small in a corner. Shown while either side sends video.
 *
 * <p>Pictures arrive on video threads; each is converted there and only the newest is drawn on
 * the next pulse of the screen, so a slow window skips pictures instead of falling behind.
 */
final class VideoStage implements CallVideo.View {
    private final StackPane root = new StackPane();
    private final ImageView remote = new ImageView();
    private final ImageView local = new ImageView();
    private final StackPane localBox = new StackPane(local);
    private final Label caption = UiKit.label("", "video-caption");
    private final Picture remotePicture = new Picture();
    private final Picture localPicture = new Picture();
    private volatile boolean remoteOn;
    private volatile boolean localOn;
    private String peer = "";

    VideoStage() {
        root.getStyleClass().add("video-stage");
        root.setId("video-stage");
        root.setVisible(false);
        root.managedProperty().bind(root.visibleProperty());
        remote.setPreserveRatio(true);
        remote.setSmooth(true);
        remote.fitWidthProperty().bind(root.widthProperty().subtract(24));
        remote.fitHeightProperty().bind(root.heightProperty().subtract(24));
        remote.setId("video-remote");
        remote.setAccessibleText(I18n.t("video.remote"));
        local.setPreserveRatio(true);
        local.setSmooth(true);
        local.setFitWidth(180);
        local.setFitHeight(120);
        local.setId("video-local");
        local.setAccessibleText(I18n.t("video.you"));
        Rectangle clip = new Rectangle();
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        clip.widthProperty().bind(localBox.widthProperty());
        clip.heightProperty().bind(localBox.heightProperty());
        localBox.setClip(clip);
        localBox.getStyleClass().add("video-local");
        localBox.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
        StackPane.setAlignment(localBox, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(localBox, new Insets(14));
        StackPane.setAlignment(caption, Pos.TOP_LEFT);
        StackPane.setMargin(caption, new Insets(12));
        root.getChildren().addAll(remote, caption, localBox);
    }

    StackPane root() {
        return root;
    }

    /** Whose picture this is (for the caption); called when a call starts. */
    void peer(String name) {
        peer = name == null ? "" : name;
    }

    // ------------------------------------------------------------------ CallVideo.View (video threads)

    @Override public void remote(VideoFrame frame, boolean screen) {
        remoteOn = true;
        remotePicture.offer(frame, screen);
    }

    @Override public void remoteStopped() {
        remoteOn = false;
        Platform.runLater(this::layout);
    }

    @Override public void local(VideoFrame frame, boolean screen) {
        localOn = true;
        localPicture.offer(frame, screen);
    }

    @Override public void localStopped() {
        localOn = false;
        Platform.runLater(this::layout);
    }

    /** The call ended: hide everything. */
    void clear() {
        remoteOn = false;
        localOn = false;
        layout();
    }

    private void layout() {
        root.setVisible(remoteOn || localOn);
        remote.setVisible(remoteOn);
        localBox.setVisible(localOn);
        if (!remoteOn) caption.setText("");
        if (!remoteOn) remote.setImage(null);
        if (!localOn) local.setImage(null);
    }

    /** One stream's newest picture, converted off the UI thread and drawn on the next pulse. */
    private final class Picture {
        private final AtomicReference<byte[]> latest = new AtomicReference<>();
        private Scaler scaler;
        private int width;
        private int height;
        private boolean screen;
        private WritableImage image;

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
            int w, h;
            boolean isScreen;
            synchronized (this) {
                w = width;
                h = height;
                isScreen = screen;
            }
            if (bgra.length != w * h * 4) return;
            if (image == null || (int) image.getWidth() != w || (int) image.getHeight() != h) image = new WritableImage(w, h);
            image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getByteBgraPreInstance(), bgra, 0, w * 4);
            if (this == remotePicture) {
                if (remote.getImage() != image) remote.setImage(image);
                caption.setText(isScreen ? I18n.t("video.sharingScreen", peer) : "");
                caption.setVisible(isScreen);
            } else if (local.getImage() != image) {
                local.setImage(image);
            }
            layout();
        }
    }
}
