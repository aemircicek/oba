package com.emirchat.ui;

import com.emirchat.api.ApiException;
import com.emirchat.client.ChatClient;
import com.emirchat.client.History;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import com.emirchat.server.ChatServer;
import com.emirchat.trust.KeyChangedException;
import com.emirchat.trust.KnownPeers;
import com.emirchat.trust.PeerRejectedException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/** Top-level flow of the desktop app: login → main window, plus the account-free direct mode. */
final class AppShell {
    private final StackPane stage;
    private final Path home;
    private final Identity identity;
    private final Preferences prefs;
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "login");
        t.setDaemon(true);
        return t;
    });
    private AccountSession session;
    private AppController direct;

    AppShell(StackPane stage, Path home) throws IOException {
        this.stage = stage;
        this.home = home;
        this.identity = Identity.loadOrCreate(home);
        this.prefs = new Prefs(home).node().node("account");
    }

    void showLogin(String notice) {
        LoginScreen[] screen = new LoginScreen[1];
        screen[0] = new LoginScreen(prefs.get("server", ""), prefs.get("username", ""), notice, new LoginScreen.Actions() {
            @Override public void submit(LoginScreen.Request r) { login(screen[0], r); }

            @Override public void directMode() { openDirectMode(); }
        });
        show(screen[0].root());
    }

    private void openDirectMode() {
        try {
            direct = new AppController(stage, home);
            direct.showStart(null);
        } catch (IOException e) {
            showLogin("Direct mode is unavailable: " + e.getMessage());
        }
    }

    private void login(LoginScreen screen, LoginScreen.Request r) {
        String host = r.server();
        int port = ChatServer.DEFAULT_PORT;
        int colon = host.lastIndexOf(':');
        if (colon > 0) {
            try {
                port = Integer.parseInt(host.substring(colon + 1));
                host = host.substring(0, colon);
            } catch (NumberFormatException e) {
                screen.showError("The port after ':' must be a number.");
                return;
            }
        }
        prefs.put("server", r.server());
        prefs.put("username", r.username());
        screen.setBusy(true);
        String finalHost = host;
        int finalPort = port;
        worker.execute(() -> {
            AccountSession s = null;
            try {
                KnownPeers servers = new KnownPeers(home.resolve("known_servers"),
                        (name, fp) -> askServerTrust(name, fp, screen));
                ServerConnection connection = ServerConnection.open(finalHost, finalPort, identity, servers);
                String user = r.username().strip().toLowerCase(Locale.ROOT);
                Path accounts = home.resolve("accounts").resolve(finalHost.replace(':', '_') + "_" + finalPort);
                History history = History.open(accounts.resolve(user).resolve("history.db"), r.password());
                s = new AccountSession(history);
                ChatClient client = ChatClient.start(connection, identity, accounts, user, r.password(), r.register(), s);
                s.attach(client);
                AccountSession ready = s;
                Platform.runLater(() -> openMain(ready));
            } catch (KeyChangedException e) {
                fail(screen, s, "The server at " + r.server() + " presented a different key than before. "
                        + "Someone may be impersonating it. If the server was reinstalled, remove its line from "
                        + home.resolve("known_servers") + ".");
            } catch (PeerRejectedException e) {
                fail(screen, s, "Server not trusted; nothing was sent.");
            } catch (ApiException e) {
                fail(screen, s, e.getMessage());
            } catch (IOException e) {
                String m = e.getMessage() == null ? "" : e.getMessage();
                fail(screen, s, m.contains("refused") || m.contains("timed out")
                        ? "Could not reach a secure-chat server at " + r.server() + ". Is it running?" : m);
            } catch (RuntimeException e) {
                fail(screen, s, "Unexpected error: " + e.getMessage());
            }
        });
    }

    private void fail(LoginScreen screen, AccountSession s, String message) {
        if (s != null) s.close();
        Platform.runLater(() -> {
            show(screen.root());
            screen.setBusy(false);
            screen.showError(message);
        });
    }

    /** Called on the login thread for a server seen for the first time; blocks until the user answers. */
    private boolean askServerTrust(String server, String fingerprint, LoginScreen screen) {
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        Platform.runLater(() -> show(new ServerTrustScreen(server, fingerprint,
                () -> {
                    show(screen.root());
                    answer.complete(true);
                },
                () -> answer.complete(false)).root()));
        try {
            return answer.get();
        } catch (Exception e) {
            return false;
        }
    }

    private void openMain(AccountSession s) {
        session = s;
        javafx.stage.Window w = stage.getScene() == null ? null : stage.getScene().getWindow();
        if (w != null && w.getWidth() < 960) {
            w.setWidth(1100);
            w.setHeight(Math.max(w.getHeight(), 720));
            w.centerOnScreen();
        }
        mainView = new MainView(s, this::logout);
        show(mainView.root());
    }

    private MainView mainView;

    private void logout() {
        if (mainView != null) {
            mainView.dispose();
            mainView = null;
        }
        if (session != null) {
            boolean lost = session.client() != null && !session.client().connection().isOpen();
            session.close();
            session = null;
            showLogin(lost ? "Disconnected from the server." : null);
        }
    }

    void shutdown() {
        if (mainView != null) mainView.dispose();
        if (session != null) session.close();
        if (direct != null) direct.shutdown();
        worker.shutdownNow();
    }

    private void show(Parent screen) {
        stage.getChildren().setAll(screen);
        FadeTransition fade = new FadeTransition(Duration.millis(160), screen);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
    }
}
