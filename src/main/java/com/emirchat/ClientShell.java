package com.emirchat;

import com.emirchat.api.ApiException;
import com.emirchat.api.Packet;
import com.emirchat.chat.ChatMessage;
import com.emirchat.chat.FileMessage;
import com.emirchat.chat.TerminalSanitizer;
import com.emirchat.client.ChatClient;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import com.emirchat.e2e.Attachments;
import com.emirchat.trust.KeyChangedException;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
            "  groups             list your groups        gmsg <id> <text>  message a group",
            "  group <name> <user,user,...>   create a group with friends",
            "  gadd <id> <user>   add a friend (admin)    gremove <id> <user>  remove a member (admin)",
            "  gleave <id>        leave a group       grename <id> <name>  rename a group (admin)",
            "  file <user> <path> send an encrypted file  gfile <id> <path>  send a file to a group",
            "  save <n> <folder>  save received file number n",
            "  timer <user|#id> <off|5m|1h|1d|1w>  disappearing messages for a chat",
            "  verify <user>      show the fingerprint to compare with your friend",
            "  trust <user>       accept a friend's new key after verifying it",
            "  help               this list               quit             leave");

    private ClientShell() {}

    static int run(ServerConnection connection, Identity device, Path accounts, String username, String password,
                   BufferedReader stdin) throws IOException {
        ChatClient client;
        Printer printer = new Printer();
        try {
            client = ChatClient.start(connection, device, accounts, username, password, false, printer);
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
                    case "groups" -> {
                        if (client.refreshGroups().isEmpty()) System.out.println("No groups yet.");
                        for (Packet.GroupInfo g : client.groups()) {
                            System.out.printf("  #%d %s (admin: %s) — %s%n", g.id(), g.name(), g.owner(),
                                    String.join(", ", g.members().stream().map(Packet.Member::username).toList()));
                        }
                    }
                    case "group" -> {
                        if (parts.length < 3) {
                            System.out.println("Usage: group <name> <user,user,...>");
                        } else {
                            Packet.GroupInfo g = client.createGroup(arg, java.util.List.of(parts[2].split("\\s*,\\s*")));
                            System.out.println("Created group #" + g.id() + " " + g.name() + ".");
                        }
                    }
                    case "gmsg" -> {
                        if (parts.length < 3) {
                            System.out.println("Usage: gmsg <id> <text>");
                        } else {
                            var skipped = client.sendGroupText(Long.parseLong(arg), parts[2]);
                            if (!skipped.isEmpty()) System.out.println("! Not delivered to (unverified key): " + skipped);
                        }
                    }
                    case "gadd" -> {
                        String[] a = (parts.length > 2 ? arg + " " + parts[2] : arg).split("\\s+");
                        client.addGroupMember(Long.parseLong(a[0]), a[1]);
                    }
                    case "gremove" -> {
                        String[] a = (parts.length > 2 ? arg + " " + parts[2] : arg).split("\\s+");
                        client.removeGroupMember(Long.parseLong(a[0]), a[1]);
                    }
                    case "grename" -> {
                        if (parts.length < 3) {
                            System.out.println("Usage: grename <id> <new name>");
                        } else {
                            client.renameGroup(Long.parseLong(arg), parts[2]);
                            System.out.println("Renamed (the name is end-to-end encrypted).");
                        }
                    }
                    case "gleave" -> {
                        client.leaveGroup(Long.parseLong(arg));
                        System.out.println("Left the group.");
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
                    case "file", "gfile" -> {
                        if (parts.length < 3) {
                            System.out.println("Usage: " + parts[0] + (parts[0].equals("file") ? " <user>" : " <id>") + " <path>");
                        } else {
                            Path path = Path.of(parts[2].strip());
                            if (Files.size(path) > Attachments.MAX_FILE_BYTES) {
                                System.out.println("! Files can be at most " + Attachments.MAX_FILE_BYTES / (1024 * 1024) + " MB.");
                            } else {
                                byte[] data = Files.readAllBytes(path);
                                String name = FileMessage.safeName(path.getFileName().toString());
                                String mime = java.util.Objects.requireNonNullElse(Files.probeContentType(path),
                                        "application/octet-stream");
                                if (parts[0].equals("file")) {
                                    client.sendFile(arg, name, mime, data);
                                } else {
                                    var skipped = client.sendGroupFile(Long.parseLong(arg), name, mime, data).getValue();
                                    if (!skipped.isEmpty()) System.out.println("! Not delivered to (unverified key): " + skipped);
                                }
                                System.out.println("Sent " + name + " (encrypted).");
                            }
                        }
                    }
                    case "save" -> {
                        if (parts.length < 3) {
                            System.out.println("Usage: save <number> <folder>");
                        } else {
                            FileMessage file = printer.file(Integer.parseInt(arg));
                            Path target = Path.of(parts[2].strip()).resolve(file.name()); // safe name: no directories
                            Files.write(target, client.downloadFile(file), StandardOpenOption.CREATE_NEW);
                            System.out.println("Saved " + target.toAbsolutePath() + ".");
                        }
                    }
                    case "timer" -> {
                        if (parts.length < 3) {
                            System.out.println("Usage: timer <user|#group-id> <off|30s|5m|1h|1d|1w>");
                        } else {
                            long seconds = parseDuration(parts[2].strip());
                            if (arg.startsWith("#")) {
                                client.setGroupTimer(Long.parseLong(arg.substring(1)), seconds);
                            } else {
                                client.setTimer(arg, seconds);
                            }
                            System.out.println(seconds == 0 ? "Disappearing messages off."
                                    : "Messages in that chat now disappear " + parts[2].strip() + " after they arrive (in the desktop app).");
                        }
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
            } catch (java.nio.file.FileAlreadyExistsException e) {
                System.out.println("! " + e.getFile() + " already exists.");
            } catch (SecurityException e) {
                System.out.println("! Refused: " + e.getMessage() + ".");
            } catch (IOException e) {
                System.out.println("! " + e.getMessage());
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException | java.nio.file.InvalidPathException e) {
                System.out.println("! Check the command format (type help).");
            }
        }
        client.close();
        return 0;
    }

    /** "off", or a number with s/m/h/d/w, e.g. 5m or 1d. */
    static long parseDuration(String text) {
        if (text.equalsIgnoreCase("off") || text.equals("0")) return 0;
        long n = Long.parseLong(text.substring(0, text.length() - 1));
        long unit = switch (Character.toLowerCase(text.charAt(text.length() - 1))) {
            case 's' -> 1;
            case 'm' -> 60;
            case 'h' -> 3600;
            case 'd' -> 86_400;
            case 'w' -> 604_800;
            default -> throw new NumberFormatException("unknown unit");
        };
        long seconds = Math.multiplyExact(n, unit);
        if (seconds <= 0 || seconds > ChatClient.MAX_TIMER_SECONDS) throw new NumberFormatException("out of range");
        return seconds;
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
        private final java.util.List<FileMessage> files = new java.util.concurrent.CopyOnWriteArrayList<>();

        FileMessage file(int number) {
            if (number < 1 || number > files.size()) throw new NumberFormatException("no such file");
            return files.get(number - 1);
        }

        @Override public void file(String from, FileMessage f, long id) {
            files.add(f);
            show(from + " sent a file: " + f.name() + " (" + f.size() + " bytes). Type: save " + files.size() + " <folder>");
        }

        @Override public void groupFile(long groupId, String from, FileMessage f, long id) {
            files.add(f);
            show("#" + groupId + " " + from + " sent a file: " + f.name() + " (" + f.size() + " bytes). Type: save "
                    + files.size() + " <folder>");
        }

        @Override public void message(String from, ChatMessage m, long id) {
            show("[" + TIME.format(Instant.ofEpochMilli(m.sentAtMillis())) + "] " + from + ": " + m.text());
        }

        @Override public void groupMessage(long groupId, String from, ChatMessage m, long id) {
            show("[" + TIME.format(Instant.ofEpochMilli(m.sentAtMillis())) + "] #" + groupId + " " + from + ": " + m.text());
        }

        @Override public void groupUpdated(Packet.GroupInfo g) {
            show("Group #" + g.id() + " " + g.name() + ": " + String.join(", ",
                    g.members().stream().map(Packet.Member::username).toList()));
        }

        @Override public void timerChanged(String from, long groupId, long seconds) {
            String where = groupId == 0 ? "your chat" : "group #" + groupId;
            show(from + (seconds == 0 ? " turned off disappearing messages in " + where + "."
                    : " turned on disappearing messages in " + where + " (" + seconds + " s). This terminal keeps no history,"
                    + " so nothing is stored here."));
        }

        @Override public void action(ChatClient.Action a) {
            String where = a.groupId() == 0 ? "" : " in #" + a.groupId();
            switch (a.kind()) {
                case REACT -> show(a.from() + (a.value().isEmpty() ? " took back a reaction" : " reacted " + a.value()) + where + ".");
                case EDIT -> show(a.from() + " edited a message" + where + ": " + a.value());
                case DELETE -> show(a.from() + " deleted a message" + where + ".");
                default -> { } // read receipts and typing are for the desktop app
            }
        }

        @Override public void groupRemoved(long groupId) {
            show("You are no longer in group #" + groupId + ".");
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
