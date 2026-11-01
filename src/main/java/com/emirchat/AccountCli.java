package com.emirchat;

import com.emirchat.api.ApiException;
import com.emirchat.api.Packet;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import com.emirchat.net.LocalAddresses;
import com.emirchat.server.ChatServer;
import com.emirchat.trust.KeyChangedException;
import com.emirchat.trust.KnownPeers;
import com.emirchat.trust.PeerRejectedException;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** Command-line entry points for the account-based (server) mode. */
final class AccountCli {
    static final String USAGE = String.join("\n",
            "Server mode:",
            "  secure-chat server   [--port " + ChatServer.DEFAULT_PORT + "] [--bind 0.0.0.0] [--data ~/.securechat-server]",
            "  secure-chat register --server <host[:port]> --username <name> [--home <dir>]",
            "  secure-chat login    --server <host[:port]> --username <name> [--home <dir>]",
            "  secure-chat client   --server <host[:port]> --username <name> [--home <dir>]   (interactive)",
            "  (add --password-stdin to read the password from standard input in scripts)");

    private AccountCli() {}

    static boolean handles(String command) {
        return command.equals("server") || command.equals("register") || command.equals("login")
                || command.equals("client");
    }

    static int run(String[] args) {
        String command = args[0];
        String server = null;
        String username = null;
        String bind = "0.0.0.0";
        int port = ChatServer.DEFAULT_PORT;
        Path home = SecureChat.Options.defaultHome();
        Path data = Path.of(System.getProperty("user.home"), ".securechat-server");
        boolean passwordStdin = false;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--password-stdin")) {
                passwordStdin = true;
                continue;
            }
            String v = (i + 1 < args.length) ? args[i + 1] : null;
            if (v == null) throw new IllegalArgumentException(a + " needs a value");
            switch (a) {
                case "--server" -> server = v;
                case "--username" -> username = v;
                case "--bind" -> bind = v;
                case "--port" -> port = SecureChat.Options.parsePort(v);
                case "--home" -> home = Path.of(v);
                case "--data" -> data = Path.of(v);
                default -> throw new IllegalArgumentException("Unknown option: " + a);
            }
            i++;
        }
        try {
            if (command.equals("server")) return serve(data, bind, port);
            if (server == null) throw new IllegalArgumentException("--server is required");
            if (username == null) throw new IllegalArgumentException("--username is required");
            return account(command, server, username, home, passwordStdin);
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static int serve(Path data, String bind, int port) throws IOException {
        ChatServer server = new ChatServer(ChatServer.defaultConfig(data, bind, port)).start();
        System.out.println("secure-chat server listening on " + bind + ":" + server.port());
        for (String address : LocalAddresses.lan()) {
            System.out.println("  reachable at " + address + ":" + server.port());
        }
        System.out.println("Server fingerprint (clients see this on first connection):");
        System.out.println(Identity.pretty(server.fingerprint()));
        System.out.println("Data directory: " + data.toAbsolutePath());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        try {
            new CountDownLatch(1).await(); // run until Ctrl+C
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    private static int account(String command, String server, String username, Path home, boolean passwordStdin)
            throws IOException {
        boolean register = command.equals("register");
        String host = server;
        int port = ChatServer.DEFAULT_PORT;
        int colon = server.lastIndexOf(':');
        if (colon > 0) {
            host = server.substring(0, colon);
            port = SecureChat.Options.parsePort(server.substring(colon + 1));
        }
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        Identity device = Identity.loadOrCreate(home);
        KnownPeers knownServers = new KnownPeers(home.resolve("known_servers"), (name, fp) -> {
            System.out.println("First connection to server " + name + ". Its fingerprint is:");
            System.out.println(Identity.pretty(fp));
            System.out.println("Compare it with the fingerprint printed by the server.");
            return ask(stdin, "Trust this server? [y/N] ").equalsIgnoreCase("y");
        });
        try (ServerConnection connection = ServerConnection.open(host, port, device, knownServers)) {
            String password = readPassword(stdin, "Password: ", passwordStdin);
            if (register) {
                String again = readPassword(stdin, "Repeat password: ", passwordStdin);
                if (!password.equals(again)) {
                    System.err.println("The passwords do not match.");
                    return 1;
                }
            }
            Packet.LoggedIn result = register ? connection.register(username, password) : connection.login(username, password);
            System.out.println((register ? "Account created. " : "") + "Logged in as " + result.username() + ".");
            if (result.newDevice()) {
                System.out.println("Note: this account is now bound to this computer's key; your friends will be told.");
            }
            if (command.equals("client")) return ClientShell.run(connection, stdin);
            return 0;
        } catch (KeyChangedException e) {
            System.err.println(e.getMessage().replace("IDENTITY KEY FOR", "SERVER KEY FOR"));
            return 3;
        } catch (PeerRejectedException e) {
            System.err.println("Server not trusted; nothing was sent.");
            return 3;
        } catch (ApiException e) {
            System.err.println(e.getMessage());
            return 4;
        }
    }

    private static String ask(BufferedReader stdin, String prompt) {
        System.out.print(prompt);
        System.out.flush();
        try {
            String line = stdin.readLine();
            return line == null ? "" : line.strip();
        } catch (IOException e) {
            return "";
        }
    }

    /** Reads without echo from the console, or from stdin when explicitly asked (scripts, tests). */
    private static String readPassword(BufferedReader stdin, String prompt, boolean fromStdin) {
        Console console = System.console();
        if (!fromStdin && console != null) {
            char[] chars = console.readPassword(prompt);
            return chars == null ? "" : new String(chars);
        }
        return ask(stdin, prompt);
    }
}
