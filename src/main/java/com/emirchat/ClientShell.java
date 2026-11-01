package com.emirchat;

import com.emirchat.api.ApiException;
import com.emirchat.api.Packet;
import com.emirchat.chat.TerminalSanitizer;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import java.io.BufferedReader;
import java.io.IOException;

/** A small interactive client for the server mode, until the desktop app supports it. */
final class ClientShell {
    private static final String HELP = String.join("\n",
            "Commands:",
            "  friends            list friends and pending requests",
            "  add <user>         send a friend request",
            "  accept <user>      accept a request        decline <user>   decline a request",
            "  remove <user>      unfriend / cancel your request",
            "  help               this list               quit             leave");

    private ClientShell() {}

    static int run(ServerConnection connection, BufferedReader stdin) throws IOException {
        connection.onPush(ClientShell::showPush);
        connection.onClosed(() -> System.out.println("* Disconnected from the server."));
        System.out.println(HELP);
        String line;
        while (connection.isOpen() && (line = prompt(stdin)) != null) {
            String[] parts = line.strip().split("\\s+", 2);
            String arg = parts.length > 1 ? parts[1].strip() : "";
            try {
                switch (parts[0]) {
                    case "" -> { }
                    case "friends" -> printFriends(connection.listFriends());
                    case "add" -> System.out.println(connection.sendFriendRequest(arg)
                            ? "You and " + arg + " are now friends." : "Request sent to " + arg + ".");
                    case "accept" -> {
                        connection.respondFriendRequest(arg, true);
                        System.out.println("You and " + arg + " are now friends.");
                    }
                    case "decline" -> {
                        connection.respondFriendRequest(arg, false);
                        System.out.println("Declined.");
                    }
                    case "remove" -> {
                        connection.removeFriend(arg);
                        System.out.println("Removed " + arg + ".");
                    }
                    case "help" -> System.out.println(HELP);
                    case "quit", "exit" -> { return 0; }
                    default -> System.out.println("Unknown command. Type help.");
                }
            } catch (ApiException e) {
                System.out.println("! " + e.getMessage());
            }
        }
        return 0;
    }

    private static String prompt(BufferedReader stdin) throws IOException {
        System.out.print("> ");
        System.out.flush();
        return stdin.readLine();
    }

    private static void printFriends(Packet.FriendList list) {
        if (list.friends().isEmpty()) System.out.println("No friends yet. Add someone with: add <user>");
        for (Packet.Friend f : list.friends()) {
            System.out.printf("  %s %-20s %s%n", f.online() ? "●" : "○", f.username(),
                    Identity.pretty(f.fingerprint()).replace('\n', ' ').substring(0, 19) + "…");
        }
        if (!list.incoming().isEmpty()) System.out.println("Requests for you: " + String.join(", ", list.incoming()));
        if (!list.outgoing().isEmpty()) System.out.println("Waiting for: " + String.join(", ", list.outgoing()));
    }

    private static void showPush(Packet packet) {
        String text = switch (packet) {
            case Packet.FriendRequestReceived r -> r.from() + " wants to be your friend. Type: accept " + r.from();
            case Packet.FriendAdded a -> "You and " + a.friend().username() + " are now friends.";
            case Packet.FriendRemoved r -> r.username() + " removed you as a friend.";
            case Packet.PresenceChanged p -> p.username() + (p.online() ? " is online." : " went offline.");
            case Packet.IdentityKeyChanged k -> "WARNING: " + k.username()
                    + " logged in from a new device. Their key changed; verify it before trusting new messages.";
            default -> packet.toString();
        };
        System.out.println("\n* " + TerminalSanitizer.sanitize(text));
        System.out.print("> ");
        System.out.flush();
    }
}
