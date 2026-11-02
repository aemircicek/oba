package com.emirchat.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/**
 * Line icons drawn on a 24x24 grid, after the Feather icon set (MIT License,
 * https://github.com/feathericons/feather). Colour comes from CSS ({@code .icon.accent} etc).
 */
final class Icons {
    static final String SHIELD_CHECK = "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z M9 12l2 2 4-4";
    static final String SHIELD = "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z";
    static final String LOCK = "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z M7 11V7a5 5 0 0 1 10 0v4";
    static final String SEND = "M22 2L11 13 M22 2l-7 20-4-9-9-4 20-7z";
    static final String ALERT = "M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z M12 9v4 M12 17h.01";
    static final String COPY = "M11 9h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1";
    static final String CHECK = "M20 6L9 17l-5-5";
    static final String LOG_OUT = "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4 M16 17l5-5-5-5 M21 12H9";
    static final String CHEVRON_DOWN = "M6 9l6 6 6-6";
    static final String CHEVRON_UP = "M18 15l-6-6-6 6";
    static final String WIFI = "M5 12.55a11 11 0 0 1 14.08 0 M1.42 9a16 16 0 0 1 21.16 0 M8.53 16.11a6 6 0 0 1 6.95 0 M12 20h.01";
    static final String KEY = "M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4";
    static final String PAPERCLIP = "M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48";
    static final String FILE = "M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z M13 2v7h7";
    static final String CLOCK = "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z M12 6v6l4 2";
    static final String DOWNLOAD = "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4 M7 10l5 5 5-5 M12 15V3";

    private Icons() {}

    /** An icon of {@code size} pixels; {@code tone} is an extra style class such as "accent" or "danger". */
    static Node of(String path, double size, String tone) {
        SVGPath svg = new SVGPath();
        svg.setContent(path);
        svg.getStyleClass().add("icon");
        if (tone != null) svg.getStyleClass().add(tone);
        double scale = size / 24.0;
        svg.setScaleX(scale);
        svg.setScaleY(scale);
        StackPane box = new StackPane(new Group(svg));
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setMouseTransparent(true);
        return box;
    }
}
