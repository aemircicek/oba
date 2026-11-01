package com.emirchat.ui;

import com.emirchat.chat.ChatMessage;
import com.emirchat.client.ChatClient;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import com.emirchat.server.ChatServer;
import com.emirchat.trust.KnownPeers;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javax.imageio.ImageIO;

/**
 * Development tool: drives the real account UI (as "emir") against an in-process server and two
 * scripted users (efe, ayse), and saves a screenshot of every step.
 *
 * <pre>java -cp ... com.emirchat.ui.AccountPreview &lt;output dir&gt; [dark|light]</pre>
 */
public final class AccountPreview {
    private static final String PASSWORD = "Gizli-Anahtar-2026";
    private static Scene scene;
    private static Path out;
    private static String theme;
    private static ChatServer server;
    private static Path work;

    private AccountPreview() {}

    /** A scripted user driven directly through ChatClient. */
    private static final class Bot {
        final String name;
        final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        ChatClient client;

        Bot(String name, Path device, boolean register) throws Exception {
            this.name = name;
            Identity id = Identity.loadOrCreate(device);
            ServerConnection c = ServerConnection.open("127.0.0.1", server.port(), id,
                    new KnownPeers(device.resolve("known_servers"), (n, fp) -> true));
            client = ChatClient.start(c, id, device.resolve("accounts"), name, PASSWORD, register, new ChatClient.Listener() {
                @Override public void message(String from, ChatMessage m, long e) { inbox.add(from + ": " + m.text()); }

                @Override public void groupMessage(long g, String from, ChatMessage m, long e) { inbox.add("#" + g + " " + from + ": " + m.text()); }
            });
        }

        String next() throws InterruptedException {
            String m = inbox.poll(10, TimeUnit.SECONDS);
            if (m == null) throw new AssertionError(name + " expected a message");
            return m;
        }
    }

    public static void main(String[] args) throws Exception {
        out = Path.of(args[0]);
        theme = args.length > 1 ? args[1] : "dark";
        System.setProperty("securechat.theme", theme);
        Files.createDirectories(out);
        work = Files.createTempDirectory("securechat-account-preview");
        server = new ChatServer(new ChatServer.Config(work.resolve("server"), "127.0.0.1", 0, 1_000)).start();

        Bot efe = new Bot("efe", work.resolve("efe"), true);
        Bot ayse = new Bot("ayse", work.resolve("ayse"), true);

        CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> started.complete(null));
        started.get(10, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
        StackPane root = fx(() -> {
            StackPane r = new StackPane();
            Theme.install(r);
            scene = new Scene(r, 1100, 720);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.setX(-20000);
            stage.setY(-20000);
            stage.show();
            return r;
        });
        AppShell shell = fx(() -> new AppShell(root, work.resolve("emir")));
        fx(() -> { shell.showLogin(null); return null; });
        pause();
        snap("01-login");

        fx(() -> {
            ((ToggleButton) find("#mode-register")).setSelected(true);
            ((TextField) find("#server")).setText("127.0.0.1:" + server.port());
            ((TextField) find("#username")).setText("emir");
            ((TextField) find("#password")).setText(PASSWORD);
            ((TextField) find("#password-repeat")).setText(PASSWORD);
            ((Button) find("#login")).fire();
            return null;
        });
        waitFor("#server-trust");
        pause();
        snap("02-verify-server");
        fx(() -> { ((Button) find("#server-trust")).fire(); return null; });
        waitFor("#tab-chats");
        pause();
        snap("03-main-empty");

        // efe and ayse send friend requests
        efe.client.connection().sendFriendRequest("emir");
        ayse.client.connection().sendFriendRequest("emir");
        Thread.sleep(600);
        fx(() -> { ((ToggleButton) find("#tab-friends")).setSelected(true); return null; });
        waitFor("#accept-efe");
        pause();
        snap("04-friend-requests");
        fx(() -> { ((Button) find("#accept-efe")).fire(); return null; });
        Thread.sleep(500);
        waitFor("#accept-ayse");
        fx(() -> { ((Button) find("#accept-ayse")).fire(); return null; });
        waitFor("#friend-ayse");
        Thread.sleep(400);

        // A DM conversation
        efe.client.sendText("emir", "Selam Emir! Paftaları bitirdim 🎉");
        efe.client.sendText("emir", "Akşam MobilCAD için müsait misin?");
        Thread.sleep(500);
        fx(() -> { click(find("#friend-efe")); return null; });
        waitFor("#composer");
        type("Selam! Evet, 8'de olur.");
        String reply = efe.next();
        if (!reply.equals("emir: Selam! Evet, 8'de olur.")) throw new AssertionError("efe got: " + reply);
        type("Bu arada uygulama artık uçtan uca şifreli ✓");
        efe.next();
        efe.client.sendText("emir", "Harika, görüşürüz!");
        Thread.sleep(800);
        snap("05-dm");

        fx(() -> { click(find("#verify-badge")); return null; });
        waitFor("#contact-dialog");
        pause();
        snap("06-verify-contact");
        fx(() -> { ((Button) find("#confirm-verify")).fire(); return null; });
        Thread.sleep(500);

        // A group
        fx(() -> { ((ToggleButton) find("#tab-chats")).setSelected(true); return null; });
        waitFor("#new-group");
        fx(() -> { ((Button) find("#new-group")).fire(); return null; });
        waitFor("#group-name");
        fx(() -> {
            ((TextField) find("#group-name")).setText("MobilCAD ekibi");
            ((CheckBox) find("#pick-efe")).setSelected(true);
            ((CheckBox) find("#pick-ayse")).setSelected(true);
            return null;
        });
        pause();
        snap("07-new-group");
        fx(() -> { ((Button) find("#create-group")).fire(); return null; });
        waitFor("#group-info-link");
        Thread.sleep(600);
        long groupId = efe.client.refreshGroups().get(0).id();
        ayse.client.refreshGroups();
        type("Ekibe hoş geldiniz! İlk toplantı yarın 10:00.");
        efe.next();
        ayse.next();
        efe.client.sendGroupText(groupId, "Ben arayüz çizimlerini getiririm.");
        Thread.sleep(300);
        ayse.client.sendGroupText(groupId, "Ben de DWG dosya formatını araştırırım.");
        Thread.sleep(300);
        ayse.client.sendGroupText(groupId, "LibreDWG lisansı GPL, dikkat etmeliyiz.");
        Thread.sleep(900);
        snap("08-group");

        fx(() -> { click(find("#group-info-link")); return null; });
        waitFor("#group-dialog");
        pause();
        snap("09-group-info");
        fx(() -> { scene.getRoot().fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                javafx.scene.input.KeyCode.ESCAPE, false, false, false, false)); return null; });
        fx(() -> {
            Node layer = scene.lookup(".overlay");
            if (layer != null) ((StackPane) layer.getParent()).getChildren().remove(layer);
            return null;
        });

        // efe moves to a new computer: the key changes
        efe.client.close();
        Thread.sleep(300);
        Bot efeNew = new Bot("efe", work.resolve("efe-new-laptop"), false);
        Thread.sleep(800);
        fx(() -> { click(find("#chat-dm-efe")); return null; });
        waitFor("#key-banner");
        pause();
        snap("10-key-changed");
        fx(() -> { ((Button) find("#banner-verify")).fire(); return null; });
        waitFor("#contact-dialog");
        pause();
        snap("11-key-changed-dialog");
        if (efeNew.client == null) throw new AssertionError();

        fx(() -> { shell.shutdown(); return null; });
        efeNew.client.close();
        ayse.client.close();
        server.close();
        System.out.println("Account UI flow completed; screenshots in " + out.toAbsolutePath());
        Platform.exit();
        System.exit(0);
    }

    private static void pause() throws InterruptedException {
        Thread.sleep(400);
    }

    private static void type(String text) throws Exception {
        fx(() -> {
            ((TextField) find("#composer")).setText(text);
            ((Button) find("#send")).fire();
            return null;
        });
        Thread.sleep(300);
    }

    private static void click(Node n) {
        n.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null));
    }

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
        String err = fx(() -> {
            Node e = scene.lookup("#login-error");
            Node d = scene.lookup("#error-dialog");
            return (e instanceof Labeled l && e.isVisible() ? l.getText() : "") + (d != null ? " dialog open" : "");
        });
        throw new IllegalStateException("Timed out waiting for " + selector + " " + err);
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
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) buffered.setRGB(x, y, reader.getArgb(x, y));
        File file = out.resolve(theme + "-" + name + ".png").toFile();
        ImageIO.write(buffered, "png", file);
        System.out.println("saved " + file.getName());
    }
}
