package com.emirchat;

import com.emirchat.api.ApiException;
import com.emirchat.api.Packet;
import com.emirchat.chat.ChatMessage;
import com.emirchat.chat.TerminalSanitizer;
import com.emirchat.client.ChatClient;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import com.emirchat.trust.KeyChangedException;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** A small interactive client for the server mode, until the desktop app supports it. */
final class ClientShell {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final String HELP = String.join("\n",
            "Commands:",
            "  msg <user> <text>  send an end-to-end encrypted message",
            "  friends            list friends and pending requests",
            "  add <user>         send a friend request",
            "  accept <user>      accept a request        decline <user>   decline a request",
            "  remove <user>      unfriend / cancel your request",
            "  verify <user>      show the fingerprint to compare with your friend",
            "  trust <user>       accept a friend's new key after verifying it",
            "  help               this list               quit             leave");

    private ClientShell() {}

    static int run(ServerConnection connection, Identity device, Path accounts, String username, String password,
                   BufferedReader stdin) throws IOException {
        ChatClient client;
        try {
            client = ChatClient.start(connection, device, accounts, username, password, false, new Printer());
        } catch (ApiException e) {
            System.err.println(e.getMessage());
            return 4;
        }
        System.out.println("Logged in as " + client.username() + ". Your fingerprint: "
                + Identity.pretty(device.fingerprint()).replace('\n', ' '));
        System.out.println(HELP);
        String line;
        while (connection.isOpen() && (line = prompt(stdin)) != null) {
            String[] parts = line.strip().split("\\s+", 3);
            String arg = parts.length > 1 ? parts[1].strip() : "";
            try {
                switch (parts[0]) {
                    case "" -> { }
                    case "msg" -> {
                        if (parts.length < 3) {
                            System.out.println("Usage: msg <user> <text>");
                        } else {
                            client.sendText(arg, parts[2]);
                        }
                    }
                    case "friends" -> printFriends(client.refreshFriends(), client);
                    case "add" -> System.out.println(client.connection().sendFriendRequest(arg)
                            ? "You and " + arg + " are now friends." : "Request sent to " + arg + ".");
                    case "accept" -> {
                        client.connection().respondFriendRequest(arg, true);
                        System.out.println("You and " + arg + " are now friends.");
                    }
                    case "decline" -> {
                        client.connection().respondFriendRequest(arg, false);
                        System.out.println("Declined.");
                    }
                    case "remove" -> {
                        client.connection().removeFriend(arg);
                        System.out.println("Removed " + arg + ".");
                    }
                    case "verify" -> {
                        String fp = client.pinnedFingerprint(arg);
                        System.out.println(fp == null ? "No key pinned for " + arg + " yet (send them a message first)."
                                : arg + "'s key (compare it with what " + arg + " sees as their own):\n" + Identity.pretty(fp));
                    }
                    case "trust" -> {
                        client.trustNewKey(arg);
                        System.out.println("Now trusting " + arg + "'s new key:\n" + Identity.pretty(client.pinnedFingerprint(arg)));
                    }
                    case "help" -> System.out.println(HELP);
                    case "quit", "exit" -> {
                        client.close();
                        return 0;
                    }
                    default -> System.out.println("Unknown command. Type help.");
                }
            } catch (KeyChangedException e) {
                System.out.println("! " + arg + "'s key changed. Run 'verify " + arg + "', compare it with them, then 'trust " + arg + "'.");
            } catch (ApiException e) {
                System.out.println("! " + e.getMessage());
            } catch (IOException e) {
                System.out.println("! " + e.getMessage());
            }
        }
        client.close();
        return 0;
    }

    private static String prompt(BufferedReader stdin) throws IOException {
        System.out.print("> ");
        System.out.flush();
        return stdin.readLine();
    }

    private static void printFriends(Packet.FriendList list, ChatClient client) {
        if (list.friends().isEmpty()) System.out.println("No friends yet. Add someone with: add <user>");
        for (Packet.Friend f : list.friends()) {
            String pinned = client.pinnedFingerprint(f.username());
            String state = pinned == null ? "" : pinned.equals(f.fingerprint()) ? "" : "  ⚠ key changed";
            System.out.printf("  %s %-20s%s%n", f.online() ? "●" : "○", f.username(), state);
        }
        if (!list.incoming().isEmpty()) System.out.println("Requests for you: " + String.join(", ", list.incoming()));
        if (!list.outgoing().isEmpty()) System.out.println("Waiting for: " + String.join(", ", list.outgoing()));
    }

    /** Prints everything the client hears, without corrupting the prompt line. */
    private static final class Printer implements ChatClient.Listener {
        @Override public void message(String from, ChatMessage m, long id) {
            show("[" + TIME.format(Instant.ofEpochMilli(m.sentAtMillis())) + "] " + from + ": " + m.text());
        }

        @Override public void undecryptable(String from, String reason) {
            show("! A message from " + from + " could not be decrypted (" + reason + ").");
        }

        @Override public void delivered(long id) {
            // the terminal stays quiet; the desktop app shows ticks
        }

        @Override public void keyChanged(String friend) {
            show("WARNING: " + friend + "'s key changed (new device?). Their messages are held until you run: verify "
                    + friend + " / trust " + friend);
        }

        @Override public void push(Packet packet) {
            show(switch (packet) {
                case Packet.FriendRequestReceived r -> r.from() + " wants to be your friend. Type: accept " + r.from();
                case Packet.FriendAdded a -> "You and " + a.friend().username() + " are now friends.";
                case Packet.FriendRemoved r -> r.username() + " removed you as a friend.";
                case Packet.PresenceChanged p -> p.username() + (p.online() ? " is online." : " went offline.");
                default -> packet.getClass().getSimpleName();
            });
        }

        @Override public void disconnected() {
            show("Disconnected from the server.");
        }

        private static void show(String text) {
            System.out.println("\n* " + TerminalSanitizer.sanitize(text));
            System.out.print("> ");
            System.out.flush();
        }
    }
}
