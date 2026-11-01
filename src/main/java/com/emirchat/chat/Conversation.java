package com.emirchat.chat;

import com.emirchat.protocol.Session;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A running chat over an authenticated {@link Session}, independent of any user interface.
 * Incoming messages are delivered to a {@link Listener} on a background thread.
 */
public final class Conversation {
    public enum EndReason { LOCAL_LEFT, PEER_LEFT, CONNECTION_LOST, SECURITY_ERROR }

    public interface Listener {
        void onMessage(ChatMessage message);

        /** Called exactly once, from whichever thread ended the conversation. */
        void onEnded(EndReason reason, String detail);
    }

    private final Session session;
    private final Closeable transport;
    private final AtomicBoolean ended = new AtomicBoolean();
    private volatile Listener listener;

    public Conversation(Session session, Closeable transport) {
        this.session = session;
        this.transport = transport;
    }

    public Session session() {
        return session;
    }

    public boolean isOpen() {
        return !ended.get();
    }

    public void start(Listener listener) {
        this.listener = listener;
        Thread receiver = new Thread(this::receiveLoop, "conversation-receiver");
        receiver.setDaemon(true);
        receiver.start();
    }

    /**
     * Encrypts and sends a text message.
     *
     * @throws IllegalArgumentException if the text is too long
     * @throws IOException              if the conversation is over or the connection failed
     */
    public ChatMessage send(String text) throws IOException {
        if (!isOpen()) throw new IOException("The conversation has ended");
        ChatMessage message = ChatMessage.text(text);
        try {
            session.channel().send(message.encode());
        } catch (IOException | SecurityException e) {
            end(EndReason.CONNECTION_LOST, e.getMessage());
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
        return message;
    }

    /** Tells the peer we are leaving (best effort) and closes the connection. */
    public void leave() {
        if (!isOpen()) return;
        try {
            session.channel().send(ChatMessage.bye().encode());
        } catch (IOException | SecurityException ignored) {
            // the connection is going away anyway
        }
        end(EndReason.LOCAL_LEFT, null);
    }

    private void receiveLoop() {
        try {
            while (isOpen()) {
                ChatMessage message = ChatMessage.decode(session.channel().receive());
                if (message.type() == ChatMessage.Type.BYE) {
                    end(EndReason.PEER_LEFT, null);
                    return;
                }
                Listener l = listener;
                if (l != null) l.onMessage(message);
            }
        } catch (SecurityException e) {
            end(EndReason.SECURITY_ERROR, e.getMessage());
        } catch (IOException e) {
            end(EndReason.CONNECTION_LOST, e.getMessage());
        }
    }

    private void end(EndReason reason, String detail) {
        if (!ended.compareAndSet(false, true)) return;
        try {
            transport.close();
        } catch (IOException ignored) {
            // nothing useful to do while shutting down
        }
        Listener l = listener;
        if (l != null) l.onEnded(reason, detail);
    }
}
