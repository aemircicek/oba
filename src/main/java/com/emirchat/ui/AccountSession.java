package com.emirchat.ui;

import com.emirchat.api.Packet;
import com.emirchat.chat.ChatMessage;
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
    }

    private final History history;
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ui-network");
        t.setDaemon(true);
        return t;
    });
    /** Delivery receipts that arrived before the send call returned the envelope id (fast LANs). */
    private final java.util.Set<Long> earlyDeliveries = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile ChatClient client;
    private volatile View view;

    AccountSession(History history) {
        this.history = history;
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
    public void message(String from, ChatMessage message, long envelopeId) {
        store(dm(from), from, message, envelopeId);
    }

    @Override
    public void groupMessage(long groupId, String from, ChatMessage message, long envelopeId) {
        store(group(groupId), from, message, envelopeId);
    }

    private void store(String conversation, String from, ChatMessage message, long envelopeId) {
        try {
            String text = TerminalSanitizer.sanitize(message.text());
            long id = history.add(conversation, from, text, message.sentAtMillis(), false, History.RECEIVED, envelopeId);
            History.Message stored = new History.Message(id, conversation, from, text, message.sentAtMillis(), false,
                    History.RECEIVED, envelopeId);
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
        history.close();
    }
}
