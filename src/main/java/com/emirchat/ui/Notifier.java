package com.emirchat.ui;

import java.awt.EventQueue;
import java.awt.SystemTray;
import java.awt.TrayIcon;

/**
 * Desktop notifications through the operating system's tray (Windows notification centre, most
 * Linux desktops; macOS shows them where the tray is supported). JavaFX has no notification API,
 * so this uses AWT on its own thread. Where no tray exists it does nothing.
 */
final class Notifier {
    private TrayIcon icon;
    private boolean unavailable;

    void show(String title, String text) {
        EventQueue.invokeLater(() -> {
            TrayIcon i = icon();
            if (i != null) i.displayMessage(title, text, TrayIcon.MessageType.NONE);
        });
    }

    private TrayIcon icon() {
        if (icon != null || unavailable) return icon;
        try {
            if (java.awt.GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
                unavailable = true;
                return null;
            }
            java.awt.Image image;
            try (var in = Notifier.class.getResourceAsStream("icons/icon-32.png")) {
                image = javax.imageio.ImageIO.read(in);
            }
            icon = new TrayIcon(image, com.emirchat.AppInfo.NAME);
            icon.setImageAutoSize(true);
            SystemTray.getSystemTray().add(icon);
        } catch (Exception | LinkageError e) {
            unavailable = true; // no tray on this desktop: notifications are simply off
        }
        return icon;
    }

    void close() {
        EventQueue.invokeLater(() -> {
            if (icon != null) SystemTray.getSystemTray().remove(icon);
            icon = null;
        });
    }
}
