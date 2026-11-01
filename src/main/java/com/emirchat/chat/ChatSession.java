package com.emirchat.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Terminal front-end for a {@link Conversation}. */
public final class ChatSession {
    public static final String QUIT_COMMAND = "/quit";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final Conversation conversation;
    private final BufferedReader input;
    private final PrintStream output;
    private final Runnable onPeerClosed;

    /**
     * @param onPeerClosed invoked when the peer leaves or the connection breaks while we are
     *                     still waiting for keyboard input (the CLI uses it to exit)
     */
    public ChatSession(Conversation conversation, BufferedReader input, PrintStream output, Runnable onPeerClosed) {
        this.conversation = conversation;
        this.input = input;
        this.output = output;
        this.onPeerClosed = onPeerClosed;
    }

    /** Blocks until the local user quits, input ends, or the peer disconnects. */
    public void run() throws IOException {
        String peer = conversation.session().peerName();
        conversation.start(new Conversation.Listener() {
            @Override
            public void onMessage(ChatMessage message) {
                String time = TIME.format(Instant.ofEpochMilli(message.sentAtMillis()));
                output.println("[" + time + "] " + peer + ": " + TerminalSanitizer.sanitize(message.text()));
            }

            @Override
            public void onEnded(Conversation.EndReason reason, String detail) {
                switch (reason) {
                    case LOCAL_LEFT -> { return; }
                    case PEER_LEFT -> output.println("* " + peer + " left the chat.");
                    case SECURITY_ERROR -> output.println("! SECURITY: " + detail + ". Connection terminated.");
                    case CONNECTION_LOST -> output.println("* Connection to " + peer + " closed.");
                }
                onPeerClosed.run();
            }
        });

        String line;
        while (conversation.isOpen() && (line = input.readLine()) != null) {
            if (line.equals(QUIT_COMMAND)) break;
            if (line.isBlank()) continue;
            try {
                conversation.send(line);
            } catch (IllegalArgumentException e) {
                output.println("! " + e.getMessage() + " - not sent.");
            } catch (IOException e) {
                break;
            }
        }
        conversation.leave();
    }
}
