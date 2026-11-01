package com.emirchat.ui;

import com.emirchat.net.Connector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.prefs.Preferences;

/** Remembers the last name, mode and address, per identity directory. Never stores secrets. */
final class Prefs {
    record Saved(String name, String host, int port, boolean joining) {}

    private final Preferences node;

    Prefs(Path home) {
        String key = UUID.nameUUIDFromBytes(home.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8)).toString();
        this.node = Preferences.userNodeForPackage(Prefs.class).node(key);
    }

    Preferences node() {
        return node;
    }

    Saved load() {
        return new Saved(node.get("name", ""), node.get("host", ""),
                node.getInt("port", Connector.DEFAULT_PORT), node.getBoolean("joining", false));
    }

    void save(StartScreen.Request r) {
        node.put("name", r.name());
        if (r.host() != null) node.put("host", r.host());
        node.putInt("port", r.port());
        node.putBoolean("joining", !r.hosting());
    }
}
