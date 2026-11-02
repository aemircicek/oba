package com.emirchat.ui;

import com.emirchat.chat.Conversation;
import com.emirchat.crypto.Identity;
import com.emirchat.net.Connector;
import com.emirchat.net.LocalAddresses;
import com.emirchat.trust.KeyChangedException;
import com.emirchat.trust.KnownPeers;
import com.emirchat.trust.PeerRejectedException;
import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/**
 * Drives the desktop flow: start → waiting → (verify) → chat. Networking runs on background
 * threads; every UI change is marshalled onto the JavaFX application thread.
 */
final class AppController {
    private final StackPane stage;
    private final Identity identity;
    private final KnownPeers knownPeers;
    private final Prefs prefs;
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "connector");
        t.setDaemon(true);
        return t;
    });

    /** The connection attempt in progress, so it can be cancelled. */
    private volatile Attempt attempt;
    private ChatScreen chat;

    private static final class Attempt {
        volatile boolean cancelled;
        volatile Closeable resource;
        volatile CompletableFuture<Boolean> pendingTrust;

        void cancel() {
            cancelled = true;
            CompletableFuture<Boolean> trust = pendingTrust;
            if (trust != null) trust.complete(false);
            Connector.closeQuietly(resource);
        }
    }

    AppController(StackPane stage, Path home) throws IOException {
        this.stage = stage;
        this.identity = Identity.loadOrCreate(home);
        this.prefs = new Prefs(home);
        this.knownPeers = new KnownPeers(home.resolve("known_peers"), this::askTrust);
    }

    String fingerprint() {
        return identity.fingerprint();
    }

    void showStart(StartScreen.Notice notice) {
        chat = null;
        show(new StartScreen(identity.fingerprint(), prefs.load(), notice, this::start).root());
    }

    private void start(StartScreen.Request request) {
        prefs.save(request);
        Attempt a = new Attempt();
        attempt = a;
        WaitingScreen waiting = request.hosting()
                ? new WaitingScreen(I18n.t("direct.waiting"), I18n.t("direct.waitingBody"), this::cancel)
                : new WaitingScreen(I18n.t("direct.connecting", request.host()), I18n.t("direct.connectingBody"), this::cancel);
        show(waiting.root());

        Connector.Progress progress = new Connector.Progress() {
            @Override
            public void listening(ServerSocket server) {
                a.resource = server;
                if (a.cancelled) Connector.closeQuietly(server);
                var addresses = LocalAddresses.lan();
                Platform.runLater(() -> {
                    waiting.showAddresses(addresses, request.port());
                    waiting.setStatus(I18n.t("direct.listening", request.port()));
                });
            }

            @Override
            public void socket(Socket socket) {
                a.resource = socket;
                if (a.cancelled) Connector.closeQuietly(socket);
                if (socket.isConnected()) {
                    Platform.runLater(() -> {
                        waiting.setTitle(I18n.t("direct.peerConnected"), I18n.t("direct.checking"));
                        waiting.setStatus(I18n.t("direct.authenticating", socket.getInetAddress().getHostAddress()));
                    });
                }
            }
        };

        worker.submit(() -> {
            try {
                Connector.Established est = request.hosting()
                        ? Connector.host(identity, request.name(), "0.0.0.0", request.port(), knownPeers, progress)
                        : Connector.connect(identity, request.name(), request.host(), request.port(), knownPeers, progress);
                if (a.cancelled) {
                    Connector.closeQuietly(est.socket());
                    return;
                }
                Platform.runLater(() -> openChat(est));
            } catch (KeyChangedException e) {
                if (!a.cancelled) Platform.runLater(() -> show(new KeyChangedScreen(e, () -> showStart(null)).root()));
            } catch (PeerRejectedException e) {
                if (!a.cancelled) Platform.runLater(() -> showStart(new StartScreen.Notice(
                        I18n.t("direct.notConfirmed"), false)));
            } catch (SocketTimeoutException e) {
                if (!a.cancelled) fail(I18n.t("direct.timeout"));
            } catch (SecurityException e) {
                if (!a.cancelled) fail(I18n.t("direct.insecure", e.getMessage()));
            } catch (IOException e) {
                if (!a.cancelled) fail(friendly(e));
            } catch (RuntimeException e) {
                if (!a.cancelled) fail(I18n.t("shell.unexpected", e.getMessage()));
            }
        });
    }

    private void fail(String message) {
        Platform.runLater(() -> showStart(new StartScreen.Notice(message, true)));
    }

    private static String friendly(IOException e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (m.contains("rejected your identity key")) {
            return I18n.t("direct.peerRejected");
        }
        if (m.contains("Connection refused")) {
            return I18n.t("direct.nobodyHosting");
        }
        if (e instanceof java.io.EOFException || m.contains("Connection reset") || m.contains("Socket closed")) {
            return I18n.t("direct.closedDuringSetup");
        }
        return m;
    }

    private void cancel() {
        Attempt a = attempt;
        if (a != null) a.cancel();
        showStart(null);
    }

    /** Called by the handshake thread for unknown peers; blocks until the user decides. */
    private boolean askTrust(String peerName, String peerFingerprint) {
        Attempt a = attempt;
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        if (a != null) {
            a.pendingTrust = answer;
            if (a.cancelled) return false;
        }
        Platform.runLater(() -> show(new VerifyScreen(peerName, peerFingerprint, identity.fingerprint(),
                () -> answer.complete(true),
                () -> answer.complete(false)).root()));
        try {
            return answer.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return false;
        }
    }

    private void openChat(Connector.Established est) {
        attempt = null;
        Conversation conversation = new Conversation(est.session(), est.socket());
        chat = new ChatScreen(conversation, identity.fingerprint(), () -> showStart(null));
        show(chat.root());
    }

    void shutdown() {
        Attempt a = attempt;
        if (a != null) a.cancel();
        if (chat != null) chat.shutdown();
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
