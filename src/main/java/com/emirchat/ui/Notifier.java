package com.emirchat.ui;

import java.awt.EventQueue;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

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
            BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = image.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new java.awt.Color(0x13824F));
            g.fillOval(0, 0, 16, 16);
            g.setColor(java.awt.Color.WHITE);
            g.fillRoundRect(5, 7, 6, 5, 2, 2);
            g.setStroke(new java.awt.BasicStroke(1.4f));
            g.drawArc(6, 3, 4, 6, 0, 180);
            g.dispose();
            icon = new TrayIcon(image, "secure-chat");
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
