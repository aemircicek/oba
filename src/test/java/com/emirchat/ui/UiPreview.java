package com.emirchat.ui;

import com.emirchat.chat.ChatMessage;
import com.emirchat.chat.Conversation;
import com.emirchat.crypto.Identity;
import com.emirchat.net.Connector;
import com.emirchat.trust.KnownPeers;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javax.imageio.ImageIO;

/**
 * Development tool: drives the real desktop UI through a complete session against a scripted
 * peer, without opening a window, and saves a PNG of every step.
 *
 * <pre>java -cp ... com.emirchat.ui.UiPreview &lt;output dir&gt; [dark|light]</pre>
 */
public final class UiPreview {
    private static Scene scene;
    private static Path out;
    private static String theme;

    private UiPreview() {}

    public static void main(String[] args) throws Exception {
        out = Path.of(args[0]);
        theme = args.length > 1 ? args[1] : "dark";
        System.setProperty("securechat.theme", theme);
        Files.createDirectories(out);
        Path work = Files.createTempDirectory("securechat-preview");

        CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> started.complete(null));
        started.get(10, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);

        StackPane root = fx(() -> {
            StackPane r = new StackPane();
            Theme.install(r);
            scene = new Scene(r, 460, 760);
            // A real (but off-screen) window, so the scene gets pulses exactly like the app does.
            javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.setScene(scene);
            stage.setX(-20000);
            stage.setY(-20000);
            stage.show();
            return r;
        });
        AppController app = fx(() -> new AppController(root, work.resolve("emir")));
        fx(() -> { app.showStart(null); return null; });
        Thread.sleep(400);
        snap("1-start");

        // Host as "emir"
        fx(() -> {
            ((TextField) find("#name")).setText("emir");
            ((ToggleButton) find("#mode-host")).setSelected(true);
            ((Button) find("#start")).fire();
            return null;
        });
        waitFor("#cancel");
        Thread.sleep(400);
        snap("2-waiting");

        // A scripted peer "efe" connects and trusts us automatically.
        Identity efe = Identity.loadOrCreate(work.resolve("efe"));
        KnownPeers efeTrust = new KnownPeers(work.resolve("efe/known_peers"), (n, fp) -> true);
        CompletableFuture<Connector.Established> peer = CompletableFuture.supplyAsync(() -> {
            try {
                return Connector.connect(efe, "efe", "127.0.0.1", Connector.DEFAULT_PORT, efeTrust, new Connector.Progress() {});
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        waitFor("#trust");
        Thread.sleep(300);
        snap("3-verify");
        fx(() -> { ((Button) find("#trust")).fire(); return null; });

        Conversation efeChat = new Conversation(peer.get(15, TimeUnit.SECONDS).session(), peer.get().socket());
        CompletableFuture<String> received = new CompletableFuture<>();
        efeChat.start(new Conversation.Listener() {
            @Override public void onMessage(ChatMessage m) { received.complete(m.text()); }
            @Override public void onEnded(Conversation.EndReason r, String d) {}
        });
        waitFor("#composer");
        Thread.sleep(300);
        snap("4-chat-empty");

        efeChat.send("Selam Emir! Paftaları bitirdim, akşam MobilCAD'e bakalım mı?");
        Thread.sleep(150);
        efeChat.send("Bu arada bağlantı gerçekten şifreli mi? 🙂");
        Thread.sleep(300);
        typeAndSend("Evet, uçtan uca. Anahtar her oturumda yeniden üretiliyor.");
        String got = received.get(5, TimeUnit.SECONDS);
        if (!got.startsWith("Evet")) throw new AssertionError("Peer received: " + got);
        typeAndSend("Parmak izlerimiz de eşleşti, yani arada kimse yok.");
        Thread.sleep(150);
        efeChat.send("Süper. Saat 8'de görüşürüz ‮exe.gnp");   // bidi override is neutralised
        Thread.sleep(500);
        snap("5-chat");

        fx(() -> {
            Node badge = find("#secure-badge");
            badge.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                    false, false, false, false, true, false, false, true, false, false, null));
            return null;
        });
        Thread.sleep(300);
        snap("6-chat-details");

        efeChat.leave();
        waitFor("#new-chat");
        Thread.sleep(300);
        snap("7-ended");

        // Impersonation: someone else connects as "efe" with a different key.
        fx(() -> { ((Button) find("#new-chat")).fire(); return null; });
        waitFor("#start");
        fx(() -> { ((Button) find("#start")).fire(); return null; });
        waitFor("#cancel");
        Thread.sleep(300);
        Identity mallory = Identity.generate();
        CompletableFuture.runAsync(() -> {
            try {
                Connector.connect(mallory, "efe", "127.0.0.1", Connector.DEFAULT_PORT, (n, fp) -> {}, new Connector.Progress() {});
            } catch (Exception expected) {
                // the host blocks us
            }
        });
        waitFor("#key-changed-title");
        Thread.sleep(300);
        snap("8-key-changed");

        // Error notice: join a host that does not exist.
        fx(() -> { ((Button) find("#back")).fire(); return null; });
        waitFor("#start");
        fx(() -> {
            ((ToggleButton) find("#mode-join")).setSelected(true);
            ((TextField) find("#address")).setText("127.0.0.1");
            ((TextField) find("#port")).setText("5999");
            ((Button) find("#start")).fire();
            return null;
        });
        waitFor("#notice");
        Thread.sleep(300);
        snap("9-error");

        fx(() -> { app.shutdown(); return null; });
        System.out.println("UI flow completed; screenshots in " + out.toAbsolutePath());
        Platform.exit();
        System.exit(0);
    }

    private static void typeAndSend(String text) throws Exception {
        fx(() -> {
            ((TextField) find("#composer")).setText(text);
            ((Button) find("#send")).fire();
            return null;
        });
        Thread.sleep(150);
    }

    /** Offscreen scenes get no pulses, so skins (e.g. ScrollPane content) only exist after an explicit pass. */
    private static Node lookup(String selector) {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return scene.lookup(selector);
    }

    private static Node find(String selector) {
        Node n = lookup(selector);
        if (n == null) throw new IllegalStateException("Not found: " + selector);
        return n;
    }

    private static void waitFor(String selector) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (fx(() -> lookup(selector) != null)) return;
            Thread.sleep(50);
        }
        throw new IllegalStateException("Timed out waiting for " + selector);
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(15, TimeUnit.SECONDS);
    }

    private static void snap(String name) throws Exception {
        WritableImage image = fx(() -> scene.snapshot(null));
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        File file = out.resolve(theme + "-" + name + ".png").toFile();
        ImageIO.write(buffered, "png", file);
        System.out.println("saved " + file.getName());
    }
}
