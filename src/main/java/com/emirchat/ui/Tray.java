package com.emirchat.ui;

import com.emirchat.AppInfo;
import java.awt.EventQueue;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The icon in the system tray (Windows notification area, macOS menu bar, most Linux desktops):
 * open or quit Scorpion while its window is closed, and show desktop notifications. JavaFX has no
 * tray API, so this uses AWT on its own thread. Where no tray exists everything here does nothing.
 */
final class Tray {
    private static final Tray INSTANCE = new Tray();
    private TrayIcon icon;
    private boolean unavailable;

    private Tray() {}

    static Tray get() {
        return INSTANCE;
    }

    /** True if this desktop has a tray (decided once; waits briefly for the AWT thread). */
    boolean available() {
        if (java.awt.GraphicsEnvironment.isHeadless()) return false;
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        EventQueue.invokeLater(() -> {
            try {
                result.complete(!unavailable && SystemTray.isSupported());
            } catch (RuntimeException | LinkageError e) {
                result.complete(false);
            }
        });
        try {
            return result.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    /** Puts the icon in the tray; {@code open} shows the window, {@code quit} ends the app. */
    void install(Runnable open, Runnable quit) {
        EventQueue.invokeLater(() -> {
            if (icon != null || unavailable) return;
            try {
                if (!SystemTray.isSupported()) {
                    unavailable = true;
                    return;
                }
                java.awt.Image image;
                try (var in = Tray.class.getResourceAsStream("icons/icon-32.png")) {
                    image = javax.imageio.ImageIO.read(in);
                }
                PopupMenu menu = new PopupMenu();
                MenuItem show = new MenuItem(I18n.t("tray.open"));
                show.addActionListener(e -> open.run());
                MenuItem exit = new MenuItem(I18n.t("tray.quit"));
                exit.addActionListener(e -> quit.run());
                menu.add(show);
                menu.addSeparator();
                menu.add(exit);
                icon = new TrayIcon(image, AppInfo.NAME, menu);
                icon.setImageAutoSize(true);
                icon.addActionListener(e -> open.run()); // double-click, or a click on a notification
                SystemTray.getSystemTray().add(icon);
            } catch (Exception | LinkageError e) {
                unavailable = true;
            }
        });
    }

    void notify(String title, String text) {
        EventQueue.invokeLater(() -> {
            if (icon != null) icon.displayMessage(title, text, TrayIcon.MessageType.NONE);
        });
    }

    /** Tooltip with the number of unread messages. */
    void setUnread(int unread) {
        EventQueue.invokeLater(() -> {
            if (icon != null) icon.setToolTip(unread > 0 ? I18n.t("tray.unread", AppInfo.NAME, unread) : AppInfo.NAME);
        });
    }

    void remove() {
        EventQueue.invokeLater(() -> {
            if (icon != null) SystemTray.getSystemTray().remove(icon);
            icon = null;
        });
    }
}
