package com.emirchat.chat;

import com.emirchat.protocol.Session;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs an interactive chat over an established {@link Session}. */
public final class ChatSession {
    public static final String QUIT_COMMAND = "/quit";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final Session session;
    private final Closeable transport;
    private final BufferedReader input;
    private final PrintStream output;
    private final Runnable onPeerClosed;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * @param transport    closed when the chat ends (normally the socket)
     * @param onPeerClosed invoked from the receiver thread when the peer leaves or the connection breaks
     */
    public ChatSession(Session session, Closeable transport, BufferedReader input, PrintStream output,
                       Runnable onPeerClosed) {
        this.session = session;
        this.transport = transport;
        this.input = input;
        this.output = output;
        this.onPeerClosed = onPeerClosed;
    }

    /** Blocks until the local user quits, input ends, or the peer disconnects. */
    public void run() throws IOException {
        Thread receiver = new Thread(this::receiveLoop, "chat-receiver");
        receiver.setDaemon(true);
        receiver.start();

        String line;
        while (!closed.get() && (line = input.readLine()) != null) {
            if (line.equals(QUIT_COMMAND)) {
                trySend(ChatMessage.bye());
                break;
            }
            if (line.isBlank()) continue;
            ChatMessage message;
            try {
                message = ChatMessage.text(line);
            } catch (IllegalArgumentException e) {
                output.println("! " + e.getMessage() + " - not sent.");
                continue;
            }
            if (!trySend(message)) break;
        }
        close();
    }

    private boolean trySend(ChatMessage message) {
        try {
            session.channel().send(message.encode());
            return true;
        } catch (IOException | SecurityException e) {
            if (!closed.get()) output.println("! Could not send: " + e.getMessage());
            return false;
        }
    }

    private void receiveLoop() {
        String peer = session.peerName();
        try {
            while (!closed.get()) {
                ChatMessage message = ChatMessage.decode(session.channel().receive());
                if (message.type() == ChatMessage.Type.BYE) {
                    output.println("* " + peer + " left the chat.");
                    break;
                }
                String time = TIME.format(Instant.ofEpochMilli(message.sentAtMillis()));
                output.println("[" + time + "] " + peer + ": " + TerminalSanitizer.sanitize(message.text()));
            }
        } catch (SecurityException e) {
            output.println("! SECURITY: " + e.getMessage() + ". Connection terminated.");
        } catch (IOException e) {
            if (!closed.get()) output.println("* Connection to " + peer + " closed.");
        } finally {
            if (close()) onPeerClosed.run();
        }
    }

    /** @return true if this call performed the close */
    private boolean close() {
        if (!closed.compareAndSet(false, true)) return false;
        try {
            transport.close();
        } catch (IOException ignored) {
            // nothing useful to do while shutting down
        }
        return true;
    }
}
