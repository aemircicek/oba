package com.emirchat.ui;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.client.ChatClient;
import com.emirchat.client.DefaultServer;
import com.emirchat.client.History;
import com.emirchat.client.SecretStore;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import com.emirchat.server.ChatServer;
import com.emirchat.trust.KeyChangedException;
import com.emirchat.trust.KnownPeers;
import com.emirchat.trust.PeerRejectedException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
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

/**
 * Top-level flow of the desktop app: login (or automatic sign-in for a remembered account) →
 * main window, reconnecting by itself when the connection drops; plus the account-free direct mode.
 */
final class AppShell {
    /** Waits between reconnection attempts, in seconds (the last one repeats). */
    private static final int[] RECONNECT_DELAYS = {1, 2, 4, 8, 15, 30};

    private final StackPane stage;
    private final Path home;
    private Identity identity; // replaced after restoring a backup
    private final Preferences prefs;
    private final DefaultServer defaults = DefaultServer.load();
    private final SecretStore secrets;
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "login");
        t.setDaemon(true);
        return t;
    });
    private AccountSession session;
    private Login current; // what the open session logged in with (for reconnecting)
    private MainView mainView;
    private AppController direct;

    /** Everything needed to log in again after the connection drops. */
    private record Login(String serverText, String host, int port, String user, char[] password) {
        boolean isDefault(DefaultServer d) {
            return serverText.isEmpty() && d.configured();
        }
    }

    AppShell(StackPane stage, Path home) throws IOException {
        this.stage = stage;
        this.home = home;
        this.identity = Identity.loadOrCreate(home);
        this.prefs = new Prefs(home).node().node("account");
        this.secrets = SecretStore.forThisSystem(home);
    }

    /** First screen: signs in by itself if the user asked to be remembered. */
    void start() {
        String user = prefs.get("username", "");
        if (prefs.getBoolean("remember", false) && !user.isEmpty() && secrets.available()) {
            char[] password = null;
            try {
                password = secrets.load(account(prefs.get("server", ""), user));
            } catch (IOException ignored) {
                // fall back to the login screen
            }
            if (password != null) {
                LoginScreen screen = showLogin(I18n.t("shell.signingIn", user));
                screen.setBusy(true);
                login(screen, new LoginScreen.Request(prefs.get("server", ""), user, new String(password), false, true));
                Arrays.fill(password, '\0');
                return;
            }
        }
        showLogin(null);
    }

    LoginScreen showLogin(String notice) {
        LoginScreen[] screen = new LoginScreen[1];
        screen[0] = new LoginScreen(prefs.get("server", ""), prefs.get("username", ""), notice, defaults,
                secrets.available(), new LoginScreen.Actions() {
                    @Override public void submit(LoginScreen.Request r) { login(screen[0], r); }

                    @Override public void directMode() { openDirectMode(); }

                    @Override public void restore() { restoreBackup(screen[0]); }

                    @Override public void language(String code) {
                        I18n.choose(code);
                        I18n.use(code);
                        showLogin(notice);
                    }
                });
        show(screen[0].root());
        loginScreen = screen[0];
        return screen[0];
    }

    private LoginScreen loginScreen;

    /** A backup file was opened (double-click or "Open with"): restore it from the login screen. */
    void openBackupFile(Path file) {
        if (session != null) {
            if (mainView != null) mainView.notice(I18n.t("restore.logOutFirst"), true);
            return;
        }
        if (loginScreen == null) showLogin(null);
        restoreBackup(loginScreen, file);
    }

    private static String account(String server, String user) {
        return (server.isEmpty() ? "default" : server) + "|" + user.toLowerCase(Locale.ROOT);
    }

    /**
     * Restores an encrypted backup into the key directory. Whatever is there now is moved aside to
     * a sibling folder, never deleted.
     */
    private void restoreBackup(LoginScreen screen) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(I18n.t("restore.choose"));
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(I18n.t("restore.fileType"), "*.scbackup"));
        java.io.File file = chooser.showOpenDialog(stage.getScene() == null ? null : stage.getScene().getWindow());
        if (file != null) restoreBackup(screen, file.toPath());
    }

    /** Also used when the app is opened with a backup file (double-click). */
    void restoreBackup(LoginScreen screen, Path file) {
        javafx.scene.control.Dialog<String> ask = new javafx.scene.control.Dialog<>();
        ask.setTitle(I18n.t("restore.title"));
        ask.setHeaderText(I18n.t("restore.question", file.getFileName()));
        javafx.scene.control.PasswordField pass = new javafx.scene.control.PasswordField();
        pass.setPromptText(I18n.t("restore.passphrase"));
        ask.getDialogPane().setContent(pass);
        ask.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        ask.setResultConverter(b -> b == javafx.scene.control.ButtonType.OK ? pass.getText() : null);
        Platform.runLater(pass::requestFocus);
        String passphrase = ask.showAndWait().orElse(null);
        if (passphrase == null || passphrase.isEmpty()) return;
        screen.setBusy(true);
        worker.execute(() -> {
            String message;
            try {
                Path aside = com.emirchat.client.Backup.restore(file, passphrase.toCharArray(), home);
                identity = Identity.loadOrCreate(home);
                message = I18n.t("restore.done") + (aside == null ? "" : " " + I18n.t("restore.keptAside", aside));
            } catch (SecurityException e) {
                message = I18n.t("restore.wrongPassphrase");
            } catch (IOException | RuntimeException e) {
                message = I18n.t("restore.failed", e.getMessage());
            }
            String text = message;
            Platform.runLater(() -> showLogin(text));
        });
    }

    private void openDirectMode() {
        try {
            direct = new AppController(stage, home);
            direct.showStart(null);
        } catch (IOException e) {
            showLogin(I18n.t("shell.directUnavailable", e.getMessage()));
        }
    }

    private void login(LoginScreen screen, LoginScreen.Request r) {
        String text = r.server();
        String address = text.isEmpty() && defaults.configured() ? defaults.address() : text;
        String host = address;
        int port = ChatServer.DEFAULT_PORT;
        int colon = address.lastIndexOf(':');
        if (colon > 0) {
            try {
                port = Integer.parseInt(address.substring(colon + 1));
                host = address.substring(0, colon);
            } catch (NumberFormatException e) {
                screen.setBusy(false);
                screen.showError(I18n.t("shell.badPort"));
                return;
            }
        }
        String user = r.username().strip().toLowerCase(Locale.ROOT);
        prefs.put("server", text);
        prefs.put("username", user);
        screen.setBusy(true);
        Login login = new Login(text, host, port, user, r.password().toCharArray());
        worker.execute(() -> {
            AccountSession s = null;
            try {
                Path accounts = accountsDir(login);
                History history = History.open(accounts.resolve(user).resolve("history.db"), r.password());
                s = new AccountSession(history);
                s.setHome(home);
                ChatClient client = ChatClient.start(open(login, screen), identity, accounts, user, r.password(), r.register(), s);
                s.attach(client);
                rememberChoice(text, user, r.remember(), r.password());
                AccountSession ready = s;
                Platform.runLater(() -> openMain(ready, login));
            } catch (KeyChangedException e) {
                fail(screen, s, login.isDefault(defaults) && defaults.hasFingerprint()
                        ? I18n.t("shell.pinnedMismatch") : I18n.t("shell.serverKeyChanged", address, home.resolve("known_servers")));
            } catch (PeerRejectedException e) {
                fail(screen, s, login.isDefault(defaults) && defaults.hasFingerprint()
                        ? I18n.t("shell.pinnedMismatch") : I18n.t("shell.serverNotTrusted"));
            } catch (ApiException e) {
                if (e.code() == ErrorCode.BAD_CREDENTIALS) forget(text, user); // a remembered password went stale
                fail(screen, s, I18n.error(e));
            } catch (IOException e) {
                String m = e.getMessage() == null ? "" : e.getMessage();
                fail(screen, s, m.contains("refused") || m.contains("timed out") || m.contains("connect")
                        ? I18n.t("shell.unreachable", address) : m);
            } catch (RuntimeException e) {
                fail(screen, s, I18n.t("shell.unexpected", e.getMessage()));
            }
        });
    }

    private Path accountsDir(Login login) {
        return home.resolve("accounts").resolve(login.host().replace(':', '_') + "_" + login.port());
    }

    /**
     * Opens the encrypted channel. The default server's key may be built into the app; then only
     * that key is accepted. Other servers are pinned on first use after the user compared the key.
     */
    private ServerConnection open(Login login, LoginScreen screen) throws IOException, PeerRejectedException,
            KeyChangedException {
        boolean pinned = login.isDefault(defaults) && defaults.hasFingerprint();
        KnownPeers servers = new KnownPeers(home.resolve("known_servers"), (name, fp) -> pinned
                ? fp.replaceAll("\\s", "").equalsIgnoreCase(defaults.fingerprint())
                : screen != null && askServerTrust(name, fp, screen));
        return ServerConnection.open(login.host(), login.port(), identity, servers);
    }

    private void rememberChoice(String server, String user, boolean remember, String password) {
        prefs.putBoolean("remember", remember);
        if (!secrets.available()) return;
        try {
            if (remember) secrets.save(account(server, user), password.toCharArray());
            else secrets.delete(account(server, user));
        } catch (IOException ignored) {
            // the user simply has to type the password next time
        }
    }

    private void forget(String server, String user) {
        prefs.putBoolean("remember", false);
        try {
            if (secrets.available()) secrets.delete(account(server, user));
        } catch (IOException ignored) {
            // nothing stored
        }
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

    private void openMain(AccountSession s, Login login) {
        session = s;
        current = login;
        s.onConnectionLost(() -> reconnect(s, login));
        javafx.stage.Window w = stage.getScene() == null ? null : stage.getScene().getWindow();
        if (w != null && w.getWidth() < 960) {
            w.setWidth(1100);
            w.setHeight(Math.max(w.getHeight(), 720));
            w.centerOnScreen();
        }
        mainView = new MainView(s, this::logout);
        show(mainView.root());
    }

    /**
     * The connection dropped (network change, sleep, server restart): log in again in the
     * background, waiting a little longer after each failed attempt. The session, its history and
     * the open window stay as they are.
     */
    private void reconnect(AccountSession s, Login login) {
        worker.execute(() -> {
            for (int attempt = 0; session == s && !s.isClosed(); attempt++) {
                int delay = RECONNECT_DELAYS[Math.min(attempt, RECONNECT_DELAYS.length - 1)];
                Platform.runLater(() -> s.reconnecting(delay));
                try {
                    Thread.sleep(delay * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
                if (session != s || s.isClosed()) return;
                try {
                    ChatClient old = s.client();
                    ChatClient client = ChatClient.start(open(login, null), identity, accountsDir(login), login.user(),
                            new String(login.password()), false, s);
                    s.attach(client);
                    if (old != null) old.close();
                    Platform.runLater(s::reconnected);
                    return;
                } catch (ApiException e) {
                    if (e.code() == ErrorCode.BAD_CREDENTIALS) { // the password was changed elsewhere
                        forget(login.serverText(), login.user());
                        Platform.runLater(() -> logout(I18n.t("shell.signedOut")));
                        return;
                    }
                } catch (PeerRejectedException e) { // includes a changed server key
                    Platform.runLater(() -> logout(I18n.t("shell.serverKeyChanged", login.host(), home.resolve("known_servers"))));
                    return;
                } catch (IOException | RuntimeException e) {
                    // still offline: try again later
                }
            }
        });
    }

    /** The user logged out: the remembered password is forgotten too. */
    private void logout() {
        Login l = current;
        if (l != null) forget(l.serverText(), l.user());
        logout(null);
    }

    private void logout(String notice) {
        if (mainView != null) {
            mainView.dispose();
            mainView = null;
        }
        if (session != null) {
            session.close();
            session = null;
        }
        if (current != null) {
            Arrays.fill(current.password(), '\0');
            current = null;
        }
        showLogin(notice);
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
