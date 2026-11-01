package com.emirchat;

import com.emirchat.chat.ChatSession;
import com.emirchat.chat.Conversation;
import com.emirchat.crypto.Identity;
import com.emirchat.net.Connector;
import com.emirchat.trust.KnownPeers;
import com.emirchat.trust.PeerRejectedException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Command-line interface. */
public final class SecureChat {
    static final int DEFAULT_PORT = Connector.DEFAULT_PORT;

    static final String USAGE = String.join("\n",
            "Usage:",
            "  secure-chat                         open the desktop app",
            "  secure-chat gui     [--home <dir>]  open the desktop app",
            "  secure-chat host    --name <name> [--port " + DEFAULT_PORT + "] [--bind 0.0.0.0]",
            "  secure-chat connect <host> --name <name> [--port " + DEFAULT_PORT + "]",
            "  secure-chat fingerprint",
            "",
            "Options:",
            "  --home <dir>   key and known-peers directory (default: ~/.securechat)",
            "",
            "Type " + ChatSession.QUIT_COMMAND + " to leave a chat.");

    private SecureChat() {}

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            System.err.println(USAGE);
            code = 2;
        }
        System.exit(code);
    }

    static int run(String[] args) {
        Options o = Options.parse(args);
        BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            Identity identity = Identity.loadOrCreate(o.home);
            if (o.command.equals("fingerprint")) {
                System.out.println("Your identity fingerprint:");
                System.out.println(Identity.pretty(identity.fingerprint()));
                return 0;
            }
            KnownPeers trust = new KnownPeers(o.home.resolve("known_peers"), (name, fp) -> confirm(keyboard, name, fp));
            Connector.Progress progress = new Connector.Progress() {
                @Override
                public void listening(ServerSocket server) {
                    System.out.println("Listening on " + o.bind + ":" + o.port + " as '" + o.name + "'.");
                    System.out.println("Your fingerprint: " + Identity.pretty(identity.fingerprint()).replace('\n', ' '));
                    System.out.println("Waiting for a peer...");
                }

                @Override
                public void socket(Socket socket) {
                    if (socket.isConnected()) {
                        System.out.println("Peer connected from " + socket.getRemoteSocketAddress() + ", authenticating...");
                    } else {
                        System.out.println("Connecting to " + o.host + ":" + o.port + " and authenticating...");
                    }
                }
            };
            Connector.Established established = o.command.equals("host")
                    ? Connector.host(identity, o.name, o.bind, o.port, trust, progress)
                    : Connector.connect(identity, o.name, o.host, o.port, trust, progress);
            return chat(established, keyboard);
        } catch (PeerRejectedException e) {
            System.err.println(e.getMessage());
            return 3;
        } catch (SecurityException e) {
            System.err.println("SECURITY: " + e.getMessage() + ". Aborting.");
            return 3;
        } catch (SocketTimeoutException e) {
            System.err.println("Handshake timed out.");
            return 1;
        } catch (IOException | UncheckedIOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static int chat(Connector.Established established, BufferedReader keyboard) throws IOException {
        Socket socket = established.socket();
        System.out.println("Secure session established with '" + established.session().peerName() + "'"
                + " (X25519 + Ed25519, AES-256-GCM). Type " + ChatSession.QUIT_COMMAND + " to leave.");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> Connector.closeQuietly(socket)));
        Conversation conversation = new Conversation(established.session(), socket);
        new ChatSession(conversation, keyboard, System.out, () -> System.exit(0)).run();
        return 0;
    }

    private static boolean confirm(BufferedReader keyboard, String name, String fingerprint) {
        System.out.println();
        System.out.println("First connection to '" + name + "'. Their identity fingerprint is:");
        System.out.println(Identity.pretty(fingerprint));
        System.out.println("Compare it with " + name + " over another channel (in person, phone call).");
        System.out.print("Trust and remember this key? [y/N] ");
        System.out.flush();
        try {
            String answer = keyboard.readLine();
            return answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"));
        } catch (IOException e) {
            return false;
        }
    }

    record Options(String command, String host, String name, int port, String bind, Path home) {
        static Path defaultHome() {
            return Path.of(System.getProperty("user.home"), ".securechat");
        }

        static Options parse(String[] args) {
            if (args.length == 0) throw new IllegalArgumentException("Missing command");
            String command = args[0];
            List<String> positional = new ArrayList<>();
            String name = null;
            String bind = "0.0.0.0";
            int port = DEFAULT_PORT;
            Path home = defaultHome();
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--name" -> name = value(args, ++i, a);
                    case "--bind" -> bind = value(args, ++i, a);
                    case "--home" -> home = Path.of(value(args, ++i, a));
                    case "--port" -> port = port(value(args, ++i, a));
                    default -> {
                        if (a.startsWith("--")) throw new IllegalArgumentException("Unknown option: " + a);
                        positional.add(a);
                    }
                }
            }
            String host = null;
            switch (command) {
                case "fingerprint", "gui" -> {
                    if (!positional.isEmpty()) throw new IllegalArgumentException("Unexpected argument");
                }
                case "host", "connect" -> {
                    if (command.equals("connect")) {
                        if (positional.size() != 1) throw new IllegalArgumentException("connect needs exactly one <host>");
                        host = positional.get(0);
                    } else if (!positional.isEmpty()) {
                        throw new IllegalArgumentException("Unexpected argument: " + positional.get(0));
                    }
                    if (name == null) throw new IllegalArgumentException("--name is required");
                    if (!com.emirchat.protocol.Handshake.isValidName(name)) {
                        throw new IllegalArgumentException("--name must be 1-32 chars of letters, digits, '_', '.', '-'");
                    }
                }
                default -> throw new IllegalArgumentException("Unknown command: " + command);
            }
            return new Options(command, host, name, port, bind, home);
        }

        private static String value(String[] args, int i, String option) {
            if (i >= args.length) throw new IllegalArgumentException(option + " needs a value");
            return args[i];
        }

        private static int port(String s) {
            try {
                int p = Integer.parseInt(s);
                if (p < 1 || p > 65535) throw new NumberFormatException();
                return p;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid port: " + s);
            }
        }
    }
}
