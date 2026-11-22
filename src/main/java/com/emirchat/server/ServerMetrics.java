package com.emirchat.server;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Counters about what the server is doing, in the Prometheus text format at {@code /metrics}.
 *
 * <p>Nothing here names a user, a group or an address: only totals (connections, logins, relayed
 * packets, queued envelopes), so the page tells an operator whether the server is healthy without
 * adding to what it knows about who talks to whom. It is off unless the server is started with
 * {@code --metrics}, and by default listens only on the loopback address.
 */
public final class ServerMetrics implements AutoCloseable {
    final LongAdder logins = new LongAdder();
    final LongAdder loginFailures = new LongAdder();
    final LongAdder registrations = new LongAdder();
    final LongAdder requests = new LongAdder();
    final LongAdder throttled = new LongAdder();
    final LongAdder envelopes = new LongAdder();
    final LongAdder groupEnvelopes = new LongAdder();
    final LongAdder callPackets = new LongAdder();
    final LongAdder callPacketsDropped = new LongAdder();
    final LongAdder voicePackets = new LongAdder();
    final LongAdder voicePacketsDropped = new LongAdder();
    final LongAdder udpRelayed = new LongAdder();
    final LongAdder udpRelayedBytes = new LongAdder();
    final LongAdder udpDropped = new LongAdder();
    final LongAdder slowClients = new LongAdder();
    final LongAdder refusedConnections = new LongAdder();
    final LongAdder attachmentBytes = new LongAdder();

    private final long started = System.currentTimeMillis();
    private volatile LongSupplier openConnections = () -> 0;
    private volatile LongSupplier usersOnline = () -> 0;
    private volatile LongSupplier voiceRooms = () -> 0;
    private volatile LongSupplier voiceParticipants = () -> 0;
    private volatile LongSupplier udpSessions = () -> 0;
    private HttpServer http;

    void gauges(LongSupplier openConnections, LongSupplier usersOnline, LongSupplier voiceRooms,
                LongSupplier voiceParticipants, LongSupplier udpSessions) {
        this.openConnections = openConnections;
        this.usersOnline = usersOnline;
        this.voiceRooms = voiceRooms;
        this.voiceParticipants = voiceParticipants;
        this.udpSessions = udpSessions;
    }

    /** The page as Prometheus reads it. */
    public String render() {
        StringBuilder b = new StringBuilder(2048);
        Runtime rt = Runtime.getRuntime();
        gauge(b, "oba_up", "1 while the server runs", 1);
        gauge(b, "oba_uptime_seconds", "Seconds since the server started", (System.currentTimeMillis() - started) / 1000);
        b.append("# HELP oba_build_info The running version\n# TYPE oba_build_info gauge\n")
                .append("oba_build_info{version=\"").append(escape(com.emirchat.AppInfo.version())).append("\"} 1\n");
        gauge(b, "oba_connections_open", "Open connections", openConnections.getAsLong());
        gauge(b, "oba_users_online", "Accounts with at least one connection", usersOnline.getAsLong());
        gauge(b, "oba_voice_rooms_active", "Voice rooms with someone in them", voiceRooms.getAsLong());
        gauge(b, "oba_voice_participants", "People in voice rooms", voiceParticipants.getAsLong());
        gauge(b, "oba_udp_relay_sessions", "Calls relayed over UDP right now", udpSessions.getAsLong());
        counter(b, "oba_logins_total", "Successful logins", logins.sum());
        counter(b, "oba_login_failures_total", "Refused logins (wrong password, unknown name, limits)", loginFailures.sum());
        counter(b, "oba_registrations_total", "Accounts created", registrations.sum());
        counter(b, "oba_requests_total", "Requests answered", requests.sum());
        counter(b, "oba_requests_throttled_total", "Requests slowed down by the per-connection limit", throttled.sum());
        counter(b, "oba_envelopes_total", "Encrypted direct envelopes accepted", envelopes.sum());
        counter(b, "oba_group_envelopes_total", "Encrypted group envelopes accepted", groupEnvelopes.sum());
        counter(b, "oba_call_packets_relayed_total", "Call packets relayed over TCP", callPackets.sum());
        counter(b, "oba_call_packets_dropped_total", "Call packets over the per-second limit", callPacketsDropped.sum());
        counter(b, "oba_voice_packets_relayed_total", "Voice room packets relayed", voicePackets.sum());
        counter(b, "oba_voice_packets_dropped_total", "Voice room packets over the per-second limit", voicePacketsDropped.sum());
        counter(b, "oba_udp_relayed_packets_total", "Call packets relayed over UDP", udpRelayed.sum());
        counter(b, "oba_udp_relayed_bytes_total", "Bytes of call packets relayed over UDP", udpRelayedBytes.sum());
        counter(b, "oba_udp_dropped_packets_total", "UDP relay packets refused (unknown, unauthenticated or over the limit)",
                udpDropped.sum());
        counter(b, "oba_slow_clients_total", "Connections closed for falling too far behind", slowClients.sum());
        counter(b, "oba_connections_refused_total", "Connections refused over the per-address or total limit",
                refusedConnections.sum());
        counter(b, "oba_attachment_bytes_total", "Encrypted attachment bytes received", attachmentBytes.sum());
        gauge(b, "oba_jvm_memory_used_bytes", "Heap in use", rt.totalMemory() - rt.freeMemory());
        gauge(b, "oba_jvm_memory_max_bytes", "Largest heap allowed", rt.maxMemory());
        gauge(b, "oba_jvm_threads", "Live platform threads", Thread.activeCount());
        return b.toString();
    }

    /**
     * Serves {@code /metrics} on {@code host:port}. Anything but the loopback address must be asked
     * for explicitly: the numbers are harmless, but there is no reason to show them to the world.
     */
    public void serve(String host, int port) throws IOException {
        InetAddress address = InetAddress.getByName(host);
        http = HttpServer.create(new InetSocketAddress(address, port), 8);
        http.createContext("/metrics", exchange -> {
            try (exchange) {
                if (!exchange.getRequestMethod().equals("GET") && !exchange.getRequestMethod().equals("HEAD")) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                byte[] body = render().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                if (exchange.getRequestMethod().equals("HEAD")) {
                    exchange.sendResponseHeaders(200, -1);
                    return;
                }
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
        http.createContext("/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(404, -1);
            }
        });
        http.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "metrics");
            t.setDaemon(true);
            return t;
        }));
        http.start();
    }

    /** The port {@code /metrics} is served on, or -1. */
    public int port() {
        return http == null ? -1 : http.getAddress().getPort();
    }

    @Override
    public void close() {
        if (http != null) http.stop(0);
    }

    private static void gauge(StringBuilder b, String name, String help, long value) {
        line(b, name, help, "gauge", value);
    }

    private static void counter(StringBuilder b, String name, String help, long value) {
        line(b, name, help, "counter", value);
    }

    private static void line(StringBuilder b, String name, String help, String type, long value) {
        b.append("# HELP ").append(name).append(' ').append(help).append('\n')
                .append("# TYPE ").append(name).append(' ').append(type).append('\n')
                .append(name).append(' ').append(String.format(Locale.ROOT, "%d", value)).append('\n');
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
