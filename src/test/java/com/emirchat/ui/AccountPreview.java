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
        final java.util.List<ChatClient.Incoming> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        ChatClient client;

        Bot(String name, Path device, boolean register) throws Exception {
            this.name = name;
            Identity id = Identity.loadOrCreate(device);
            ServerConnection c = ServerConnection.open("127.0.0.1", server.port(), id,
                    new KnownPeers(device.resolve("known_servers"), (n, fp) -> true));
            client = ChatClient.start(c, id, device.resolve("accounts"), name, PASSWORD, register, new ChatClient.Listener() {
                @Override public void message(String from, ChatMessage m, long e) { inbox.add(from + ": " + m.text()); }

                @Override public void groupMessage(long g, String from, ChatMessage m, long e) { inbox.add("#" + g + " " + from + ": " + m.text()); }

                @Override public void incoming(ChatClient.Incoming m) {
                    received.add(m);
                    ChatClient.Listener.super.incoming(m);
                }
            });
        }

        String next() throws InterruptedException {
            String m = inbox.poll(10, TimeUnit.SECONDS);
            if (m == null) throw new AssertionError(name + " expected a message");
            return m;
        }
    }

    /** A small floor-plan-like PNG drawn in code, so the preview needs no asset files. */
    private static byte[] samplePlan() throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(480, 300, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(0xF4F1EA));
        g.fillRect(0, 0, 480, 300);
        g.setColor(new java.awt.Color(0x2B3A55));
        g.setStroke(new java.awt.BasicStroke(6));
        g.drawRect(30, 30, 420, 240);
        g.drawLine(250, 30, 250, 170);
        g.drawLine(30, 170, 330, 170);
        g.drawLine(330, 170, 330, 270);
        g.setColor(new java.awt.Color(0x13824F));
        g.setStroke(new java.awt.BasicStroke(3));
        g.drawArc(230, 150, 40, 40, 0, 90);
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 16));
        g.setColor(new java.awt.Color(0x2B3A55));
        g.drawString("SALON", 110, 105);
        g.drawString("YATAK ODASI", 300, 105);
        g.drawString("MUTFAK", 140, 225);
        g.drawString("BANYO", 360, 225);
        g.dispose();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        ImageIO.write(img, "png", bytes);
        return bytes.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        out = Path.of(args[0]);
        theme = args.length > 1 ? args[1] : "dark";
        System.setProperty("securechat.theme", theme);
        I18n.use(System.getProperty("preview.language", "en"));
        System.setProperty("securechat.preview", "true");
        java.util.prefs.Preferences.userNodeForPackage(AppPrefs.class).node("preview-app").clear();
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
        waitFor("#welcome-dialog");
        pause();
        snap("03a-welcome");
        for (int i = 0; i < 3; i++) fx(() -> { ((Button) find("#welcome-next")).fire(); return null; });
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

        // Encrypted files: an image (previewed inline) and a document
        efe.client.sendFile("emir", "kat-plani.png", "image/png", samplePlan());
        efe.client.sendFile("emir", "MobilCAD-sunum.pdf", "application/pdf", new byte[184_320]);
        Thread.sleep(1500);
        snap("05b-files");

        // Replies, reactions, edits and read receipts, driven from efe's side
        System.setProperty("securechat.notifications", "false");
        ChatClient.Chat toEmir = ChatClient.Chat.dm("emir");
        java.util.List<ChatClient.Incoming> fromEmir = efe.received.stream().filter(m -> m.text() != null).toList();
        ChatClient.Incoming first = fromEmir.get(0);
        ChatClient.Incoming second = fromEmir.get(fromEmir.size() - 1);
        efe.client.sendAction(toEmir, ChatClient.ActionKind.READ, second.messageId(), "");
        efe.client.sendAction(toEmir, ChatClient.ActionKind.REACT, second.messageId(), "❤️");
        efe.client.send(toEmir, "Tamam, 8'de görüşürüz 👍", 0, first.messageId());
        ChatClient.Sent typo = efe.client.send(toEmir, "Sunumu yarn bitiririm", 0, null);
        Thread.sleep(400);
        efe.client.sendAction(toEmir, ChatClient.ActionKind.EDIT, typo.messageId(), "Sunumu yarın bitiririm");
        Thread.sleep(400);
        type("Süper, sunumu bekliyorum!");
        efe.next();
        ChatClient.Incoming latest = efe.received.get(efe.received.size() - 1);
        efe.client.sendAction(toEmir, ChatClient.ActionKind.READ, latest.messageId(), "");
        efe.client.sendAction(toEmir, ChatClient.ActionKind.REACT, latest.messageId(), "❤️");
        efe.client.sendAction(toEmir, ChatClient.ActionKind.TYPING, null, "1");
        Thread.sleep(900);
        snap("05d-rich");

        fx(() -> { ((TextField) find("#search")).setText("sunum"); return null; });
        Thread.sleep(400);
        snap("05e-search");
        fx(() -> { ((TextField) find("#search")).setText(""); return null; });
        fx(() -> { ((Button) find("#settings")).fire(); return null; });
        waitFor("#settings-dialog");
        pause();
        snap("05f-settings");
        fx(() -> { ((Button) find("#settings-done")).fire(); return null; });
        Thread.sleep(300);

        // Disappearing messages: turn on a one-hour timer, then send a message
        fx(() -> {
            javafx.scene.control.MenuButton timer = (javafx.scene.control.MenuButton) find("#timer");
            timer.getItems().stream().filter(i -> "timer-3600".equals(i.getId())).findFirst().orElseThrow().fire();
            return null;
        });
        Thread.sleep(800);
        type("Bu mesaj bir saat sonra iki tarafta da silinecek.");
        efe.next();
        Thread.sleep(500);
        snap("05c-disappearing");

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

        // The server goes away and comes back: the app reconnects by itself, without logging out
        int port = server.port();
        server.close();
        waitFor("#status-offline");
        pause();
        snap("12-offline");
        server = new ChatServer(new ChatServer.Config(work.resolve("server"), "127.0.0.1", port, 1_000)).start();
        waitFor("#status-online", 60);
        pause();
        snap("13-reconnected");

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
        waitFor(selector, 15);
    }

    private static void waitFor(String selector, int seconds) throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
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
