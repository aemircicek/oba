package com.emirchat.ui;

import com.emirchat.api.Packet;
import com.emirchat.chat.ChatMessage;
import com.emirchat.chat.FileMessage;
import com.emirchat.chat.TerminalSanitizer;
import com.emirchat.client.ChatClient;
import com.emirchat.client.History;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.application.Platform;

/**
 * A logged-in account in the desktop app. Events from the network are written to the local
 * history first (so nothing is lost while no view is attached) and then forwarded to the view on
 * the JavaFX thread. Network calls from the UI run on {@link #background()}.
 */
final class AccountSession implements ChatClient.Listener, AutoCloseable {
    /** What the main view reacts to; every method is called on the JavaFX thread. */
    interface View {
        void messageStored(History.Message message);

        void statusChanged(long localId, int status);

        void friendsChanged();

        void groupsChanged();

        void keyChanged(String user);

        void problem(String text);

        void disconnected();

        /** Disappearing messages that were just deleted from history. */
        void messagesExpired(java.util.List<History.Expired> expired);

        /** The disappearing-message timer of a conversation changed (by us or by someone else). */
        void timerChanged(String conversation, String by, long seconds);
    }

    /** The timer choices offered in the app, in seconds (0 = off). */
    static final long[] TIMER_CHOICES = {0, 5 * 60, 3600, 24 * 3600, 7 * 24 * 3600};

    static String timerLabel(long seconds) {
        if (seconds <= 0) return "Off";
        if (seconds % (7 * 24 * 3600) == 0) return plural(seconds / (7 * 24 * 3600), "week");
        if (seconds % (24 * 3600) == 0) return plural(seconds / (24 * 3600), "day");
        if (seconds % 3600 == 0) return plural(seconds / 3600, "hour");
        if (seconds % 60 == 0) return plural(seconds / 60, "minute");
        return plural(seconds, "second");
    }

    private static String plural(long n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s");
    }

    private final History history;
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ui-network");
        t.setDaemon(true);
        return t;
    });
    private final java.util.concurrent.ScheduledExecutorService sweeper =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ui-expiry");
                t.setDaemon(true);
                return t;
            });
    private final ExecutorService downloads = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ui-download");
        t.setDaemon(true);
        return t;
    });
    /** Delivery receipts that arrived before the send call returned the envelope id (fast LANs). */
    private final java.util.Set<Long> earlyDeliveries = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile ChatClient client;
    private volatile View view;

    AccountSession(History history) {
        this.history = history;
        sweeper.scheduleWithFixedDelay(this::deleteExpired, 0, 2, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Deletes disappearing messages whose time is up, with their cached files. */
    void deleteExpired() {
        try {
            java.util.List<History.Expired> expired = history.deleteExpired(System.currentTimeMillis());
            if (expired.isEmpty()) return;
            ChatClient c = client;
            for (History.Expired e : expired) {
                if (e.file() != null && c != null) c.forgetFile(e.file());
            }
            fx(v -> v.messagesExpired(expired));
        } catch (Exception ignored) {
            // tried again in a moment
        }
    }

    /** The current timer of a conversation, in seconds. */
    long timer(String conversation) {
        try {
            return history.timer(conversation);
        } catch (IOException e) {
            return 0;
        }
    }

    /** When a message stored now with this conversation's timer should disappear (0 = never). */
    static long expiresAt(long now, long seconds) {
        return seconds > 0 ? now + seconds * 1000 : 0;
    }

    void attach(ChatClient client) {
        this.client = client;
    }

    void setView(View view) {
        this.view = view;
    }

    ChatClient client() {
        return client;
    }

    History history() {
        return history;
    }

    /** One thread for UI-initiated network calls, keeping sends in order. */
    ExecutorService background() {
        return background;
    }

    /** Downloads run apart from sends, so a large file never holds up a text message. */
    ExecutorService downloads() {
        return downloads;
    }

    static String dm(String user) {
        return "dm:" + user;
    }

    static String group(long id) {
        return "g:" + id;
    }

    /** Records a completed send; returns the status to show (delivered if the receipt already came). */
    int sent(long localId, long envelopeId) throws IOException {
        history.setStatus(localId, History.SENT, envelopeId);
        if (envelopeId > 0 && earlyDeliveries.remove(envelopeId) && history.markDelivered(envelopeId) >= 0) {
            return History.DELIVERED;
        }
        return History.SENT;
    }

    // ------------------------------------------------------------------ ChatClient.Listener (inbox thread)

    @Override
    public void message(String from, ChatMessage message, long envelopeId, long expireSeconds) {
        store(dm(from), from, message, envelopeId, expireSeconds);
    }

    @Override
    public void groupMessage(long groupId, String from, ChatMessage message, long envelopeId, long expireSeconds) {
        store(group(groupId), from, message, envelopeId, expireSeconds);
    }

    @Override
    public void file(String from, FileMessage file, long envelopeId, long expireSeconds) {
        storeFile(dm(from), from, file, envelopeId, expireSeconds);
    }

    @Override
    public void groupFile(long groupId, String from, FileMessage file, long envelopeId, long expireSeconds) {
        storeFile(group(groupId), from, file, envelopeId, expireSeconds);
    }

    @Override
    public void timerChanged(String from, long groupId, long seconds) {
        String conversation = groupId == 0 ? dm(from) : group(groupId);
        try {
            history.setTimer(conversation, seconds);
        } catch (IOException e) {
            fx(v -> v.problem("Could not save the timer: " + e.getMessage()));
            return;
        }
        fx(v -> v.timerChanged(conversation, from, seconds));
    }

    private void storeFile(String conversation, String from, FileMessage file, long envelopeId, long expireSeconds) {
        try {
            // A disappearing message's clock starts when it arrives (clocks of different devices may differ).
            long expiresAt = expiresAt(System.currentTimeMillis(), expireSeconds);
            long id = history.addFile(conversation, from, file, file.sentAtMillis(), false, History.RECEIVED, envelopeId,
                    expiresAt);
            History.Message stored = new History.Message(id, conversation, from, file.name(), file.sentAtMillis(), false,
                    History.RECEIVED, envelopeId, file, expiresAt);
            fx(v -> v.messageStored(stored));
        } catch (IOException e) {
            fx(v -> v.problem("Could not save a file message: " + e.getMessage()));
        }
    }

    private void store(String conversation, String from, ChatMessage message, long envelopeId, long expireSeconds) {
        try {
            String text = TerminalSanitizer.sanitize(message.text());
            long expiresAt = expiresAt(System.currentTimeMillis(), expireSeconds);
            long id = history.add(conversation, from, text, message.sentAtMillis(), false, History.RECEIVED, envelopeId,
                    expiresAt);
            History.Message stored = new History.Message(id, conversation, from, text, message.sentAtMillis(), false,
                    History.RECEIVED, envelopeId, null, expiresAt);
            fx(v -> v.messageStored(stored));
        } catch (IOException e) {
            fx(v -> v.problem("Could not save a message: " + e.getMessage()));
        }
    }

    @Override
    public void delivered(long envelopeId) {
        try {
            long id = history.markDelivered(envelopeId);
            if (id >= 0) {
                fx(v -> v.statusChanged(id, History.DELIVERED));
            } else {
                earlyDeliveries.add(envelopeId);
            }
        } catch (IOException ignored) {
            // the tick is cosmetic
        }
    }

    @Override
    public void undecryptable(String from, String reason) {
        fx(v -> v.problem("A message from " + from + " could not be decrypted (" + reason + ")."));
    }

    @Override
    public void keyChanged(String friend) {
        fx(v -> v.keyChanged(friend));
    }

    @Override
    public void push(Packet packet) {
        refreshFriendsLater();
    }

    @Override
    public void groupUpdated(Packet.GroupInfo group) {
        fx(View::groupsChanged);
    }

    @Override
    public void groupRemoved(long groupId) {
        fx(View::groupsChanged);
    }

    @Override
    public void disconnected() {
        fx(View::disconnected);
    }

    /** Friend pushes carry partial information; re-read the list off the inbox thread. */
    void refreshFriendsLater() {
        background.execute(() -> {
            try {
                ChatClient c = client;
                if (c != null) c.refreshFriends();
            } catch (Exception ignored) {
                // the view keeps the last known list
            }
            fx(View::friendsChanged);
        });
    }

    private void fx(java.util.function.Consumer<View> action) {
        Platform.runLater(() -> {
            View v = view;
            if (v != null) action.accept(v);
        });
    }

    @Override
    public void close() {
        ChatClient c = client;
        if (c != null) c.close();
        background.shutdownNow();
        sweeper.shutdownNow();
        downloads.shutdownNow();
        history.close();
    }
}
