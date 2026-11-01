package com.emirchat;

import com.emirchat.chat.ChatSession;
import com.emirchat.crypto.Identity;
import com.emirchat.protocol.FrameIO;
import com.emirchat.protocol.Handshake;
import com.emirchat.protocol.Session;
import com.emirchat.trust.KnownPeers;
import com.emirchat.trust.PeerRejectedException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Command-line entry point. */
public final class SecureChat {
    static final int DEFAULT_PORT = 5055;
    private static final int HANDSHAKE_TIMEOUT_MS = 120_000;

    private static final String USAGE = String.join("\n",
            "Usage:",
            "  secure-chat host    --name <name> [--port 5055] [--bind 0.0.0.0]",
            "  secure-chat connect <host> --name <name> [--port 5055]",
            "  secure-chat fingerprint",
            "",
            "Options:",
            "  --home <dir>   key and known-peers directory (default: ~/.securechat)",
            "",
            "Type /quit to leave a chat.");

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
            switch (o.command) {
                case "fingerprint" -> {
                    System.out.println("Your identity fingerprint:");
                    System.out.println(Identity.pretty(identity.fingerprint()));
                    return 0;
                }
                case "host" -> {
                    return host(o, identity, keyboard);
                }
                case "connect" -> {
                    return connect(o, identity, keyboard);
                }
                default -> throw new IllegalArgumentException("Unknown command: " + o.command);
            }
        } catch (IOException | UncheckedIOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static int host(Options o, Identity identity, BufferedReader keyboard) throws IOException {
        Socket socket;
        // Plain TCP by design: every byte after the handshake is protected by the authenticated
        // AES-GCM channel in com.emirchat.protocol, so TLS would add no security here.
        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true); // avoids "Address already in use" right after a restart
            try {
                server.bind(new InetSocketAddress(o.bind, o.port));
            } catch (BindException e) {
                throw new IOException("Port " + o.port + " is already in use by another program"
                        + " (on macOS, AirPlay Receiver occupies 5000 and 7000). Try another one, e.g. --port "
                        + (o.port == DEFAULT_PORT ? DEFAULT_PORT + 1 : DEFAULT_PORT), e);
            }
            System.out.println("Listening on " + o.bind + ":" + o.port + " as '" + o.name + "'.");
            System.out.println("Your fingerprint: " + Identity.pretty(identity.fingerprint()).replace('\n', ' '));
            System.out.println("Waiting for a peer...");
            socket = server.accept(); // one peer per session; the listening socket closes right after
        }
        return chat(socket, true, o, identity, keyboard);
    }

    private static int connect(Options o, Identity identity, BufferedReader keyboard) throws IOException {
        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        Socket socket = new Socket(); // encrypted at the application layer, see host()
        socket.connect(new InetSocketAddress(o.host, o.port), 10_000);
        return chat(socket, false, o, identity, keyboard);
    }

    private static int chat(Socket socket, boolean isServer, Options o, Identity identity, BufferedReader keyboard)
            throws IOException {
        try {
            System.out.println("Connected to " + socket.getRemoteSocketAddress() + ", authenticating...");
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            KnownPeers trust = new KnownPeers(o.home.resolve("known_peers"), (name, fp) -> confirm(keyboard, name, fp));
            FrameIO io = new FrameIO(socket.getInputStream(), socket.getOutputStream());
            Session session = isServer
                    ? Handshake.asServer(io, identity, o.name, trust)
                    : Handshake.asClient(io, identity, o.name, trust);
            socket.setSoTimeout(0);

            System.out.println("Secure session established with '" + session.peerName() + "'"
                    + " (X25519 + Ed25519, AES-256-GCM). Type " + ChatSession.QUIT_COMMAND + " to leave.");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> closeQuietly(socket)));
            new ChatSession(session, socket, keyboard, System.out, () -> System.exit(0)).run();
            return 0;
        } catch (PeerRejectedException e) {
            System.err.println(e.getMessage());
            return 3;
        } catch (SecurityException e) {
            System.err.println("SECURITY: " + e.getMessage() + ". Aborting.");
            return 3;
        } catch (SocketTimeoutException e) {
            System.err.println("Handshake timed out.");
            return 1;
        } finally {
            closeQuietly(socket);
        }
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

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    record Options(String command, String host, String name, int port, String bind, Path home) {
        static Options parse(String[] args) {
            if (args.length == 0) throw new IllegalArgumentException("Missing command");
            String command = args[0];
            List<String> positional = new ArrayList<>();
            String name = null;
            String bind = "0.0.0.0";
            int port = DEFAULT_PORT;
            Path home = Path.of(System.getProperty("user.home"), ".securechat");
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
                case "fingerprint" -> {
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
                    if (!Handshake.isValidName(name)) {
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
