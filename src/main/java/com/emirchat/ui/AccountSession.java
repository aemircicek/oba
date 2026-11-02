package com.emirchat.ui;

import com.emirchat.api.Packet;
import com.emirchat.chat.ChatMessage;
import com.emirchat.chat.FileMessage;
import com.emirchat.chat.TerminalSanitizer;
import com.emirchat.client.ChatClient;
import com.emirchat.client.History;
import java.io.IOException;
import java.util.List;
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

        /** A stored message was edited, deleted or reacted to: re-read it from history. */
        void messageChanged(String conversation, long localId);

        /** Someone is typing in a conversation. */
        void typing(String conversation, String who);

        /** A new message arrived (after it was stored); used for notifications. */
        default void notifyIncoming(History.Message message) {}
    }

    /** Setting keys (stored per account in the history database). */
    static final String SEND_READ_RECEIPTS = "send-read-receipts";
    static final String SEND_TYPING = "send-typing";
    static final String NOTIFICATIONS = "notifications";
    static final String NOTIFICATION_PREVIEW = "notification-preview";

    boolean setting(String key) {
        return Boolean.parseBoolean(history.setting(key, "true"));
    }

    void setSetting(String key, boolean value) {
        try {
            history.setSetting(key, Boolean.toString(value));
        } catch (IOException ignored) {
            // a setting that could not be saved keeps its old value next time
        }
    }

    /** The reactions offered in the app. */
    static final List<String> REACTIONS = List.of("👍", "❤", "😂", "😮", "😢", "🙏");

    /** Emoji variation selectors (U+FE0E/U+FE0F) render as boxes in JavaFX fonts; the base symbol is enough. */
    static String displayEmoji(String emoji) {
        return emoji.replace("\uFE0F", "").replace("\uFE0E", "");
    }
    private static final int MAX_REACTION_BYTES = 32;

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

    private java.nio.file.Path home;

    /** The key directory this account lives in (for backups). */
    void setHome(java.nio.file.Path home) {
        this.home = home;
    }

    /** Writes an encrypted backup of the key directory, including a consistent copy of this history. */
    void backup(java.nio.file.Path out, char[] passphrase) throws IOException {
        com.emirchat.client.Backup.create(home, out, passphrase, java.util.Map.of(history.file(), history));
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
        if (envelopeId > 0 && earlyDeliveries.remove(envelopeId)) history.markDelivered(envelopeId);
        History.Message m = history.get(localId);
        return m == null ? History.SENT : m.status(); // a read receipt may already have arrived
    }

    // ------------------------------------------------------------------ ChatClient.Listener (inbox thread)

    @Override
    public void incoming(ChatClient.Incoming m) {
        String conversation = m.groupId() == 0 ? dm(m.from()) : group(m.groupId());
        if (m.text() != null) {
            store(conversation, m.from(), m.text(), m.envelopeId(), m.expireSeconds(), m.messageId(), m.replyTo());
        } else {
            storeFile(conversation, m.from(), m.file(), m.envelopeId(), m.expireSeconds(), m.messageId(), m.replyTo());
        }
    }

    /**
     * Applies a reaction, edit, deletion, read receipt or typing notice. History enforces who may
     * do what: only the author can edit or delete a message, and a read receipt only touches our
     * own messages in that direct chat.
     */
    @Override
    public void action(ChatClient.Action a) {
        String conversation = a.groupId() == 0 ? dm(a.from()) : group(a.groupId());
        try {
            switch (a.kind()) {
                case REACT -> {
                    String emoji = TerminalSanitizer.sanitize(a.value()).strip();
                    if (emoji.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_REACTION_BYTES) return;
                    long id = history.react(conversation, a.target(), a.from(), emoji);
                    if (id >= 0) fx(v -> v.messageChanged(conversation, id));
                }
                case EDIT -> {
                    long id = history.edit(conversation, a.target(), a.from(), TerminalSanitizer.sanitize(a.value()));
                    if (id >= 0) fx(v -> v.messageChanged(conversation, id));
                }
                case DELETE -> {
                    History.Message deleted = history.delete(conversation, a.target(), a.from());
                    if (deleted != null) {
                        ChatClient c = client;
                        if (deleted.file() != null && c != null) c.forgetFile(deleted.file());
                        fx(v -> v.messageChanged(conversation, deleted.id()));
                    }
                }
                case READ -> {
                    if (a.groupId() != 0) return; // receipts are for direct chats only
                    for (long id : history.markReadUpTo(conversation, a.target())) fx(v -> v.statusChanged(id, History.READ));
                }
                case TYPING -> {
                    if ("1".equals(a.value())) fx(v -> v.typing(conversation, a.from()));
                }
            }
        } catch (IOException e) {
            fx(v -> v.problem("Could not apply a change from " + a.from() + ": " + e.getMessage()));
        }
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

    private void storeFile(String conversation, String from, FileMessage file, long envelopeId, long expireSeconds,
                           byte[] uid, byte[] replyTo) {
        try {
            // A disappearing message's clock starts when it arrives (clocks of different devices may differ).
            long expiresAt = expiresAt(System.currentTimeMillis(), expireSeconds);
            long id = history.addFile(conversation, from, file, file.sentAtMillis(), false, History.RECEIVED, envelopeId,
                    expiresAt, uid, replyTo);
            History.Message stored = history.get(id);
            fx(v -> {
                v.messageStored(stored);
                v.notifyIncoming(stored);
            });
        } catch (IOException e) {
            fx(v -> v.problem("Could not save a file message: " + e.getMessage()));
        }
    }

    private void store(String conversation, String from, ChatMessage message, long envelopeId, long expireSeconds,
                       byte[] uid, byte[] replyTo) {
        try {
            String text = TerminalSanitizer.sanitize(message.text());
            long expiresAt = expiresAt(System.currentTimeMillis(), expireSeconds);
            long id = history.add(conversation, from, text, message.sentAtMillis(), false, History.RECEIVED, envelopeId,
                    expiresAt, uid, replyTo);
            History.Message stored = history.get(id);
            fx(v -> {
                v.messageStored(stored);
                v.notifyIncoming(stored);
            });
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
            } else if (earlyDeliveries.size() < 1000) { // receipts for typing notices etc. match nothing
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
