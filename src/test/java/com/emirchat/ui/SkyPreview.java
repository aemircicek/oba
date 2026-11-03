package com.emirchat.ui;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Development tool: the chat header sky at six hours of the day, one above the other, in one PNG. */
public final class SkyPreview {
    private SkyPreview() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("securechat.preview", "true");
        CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> started.complete(null));
        started.get(10, TimeUnit.SECONDS);
        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                VBox rows = new VBox(4);
                for (double h : new double[] {3, 6.2, 9, 13, 18.4, 19.6}) {
                    Sky sky = new Sky(java.time.ZoneId.of("America/New_York"), true);
                    sky.fixedHour = h;
                    sky.setPrefSize(700, 76);
                    rows.getChildren().add(new StackPane(sky));
                }
                StackPane root = new StackPane(rows);
                Theme.install(root);
                root.getStyleClass().add("dark");
                Scene scene = new Scene(root, 700, 76 * 6 + 20);
                Stage stage = new Stage();
                stage.setScene(scene);
                stage.setX(-20000);
                stage.show();
                root.applyCss();
                root.layout();
                var image = scene.snapshot(null);
                java.awt.image.BufferedImage out = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < out.getHeight(); y++) {
                    for (int x = 0; x < out.getWidth(); x++) out.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                }
                javax.imageio.ImageIO.write(out, "png", Path.of(args[0]).toFile());
                done.complete(null);
            } catch (Exception e) {
                done.completeExceptionally(e);
            }
        });
        done.get(20, TimeUnit.SECONDS);
        Platform.exit();
        System.exit(0);
    }
}
