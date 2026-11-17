package com.emirchat.ui;

import com.emirchat.api.Packet;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The bars at the top of the window while a call rings or runs, or while you are in a voice
 * room, with ringing and the notification of a call.
 */
final class CallBars {
    private final MainView main;

    CallBars(MainView main) {
        this.main = main;
    }

    /** The bar over the conversation while we are in a voice room: who is there, mute, deafen, leave. */
    void showVoiceBar(com.emirchat.call.VoiceChannels.State state) {
        com.emirchat.call.VoiceChannels voice = main.session.client().voice();
        Packet.GroupInfo g = main.group(state.groupId());
        String where = g == null ? "" : g.community() != 0
                ? ConversationView.channelTitle(g) + " · " + main.sidebarUi.communityName(g.community()) : ConversationView.groupName(g);
        Label title = UiKit.label(I18n.t("voice.inRoom", where), "row-title");
        Label sub = UiKit.label("🔒 " + I18n.t("voice.encrypted"), "row-sub");
        VBox text = new VBox(2, title, sub);
        text.setMinWidth(0);
        HBox faces = new HBox(6);
        faces.setAlignment(Pos.CENTER_LEFT);
        for (String u : state.participants()) {
            Node face = UiKit.voiceFace(u, state.speaking().contains(u), 30);
            Tooltip.install(face, new Tooltip(u));
            faces.getChildren().add(face);
        }
        Button mute = new Button();
        mute.setTooltip(new Tooltip(state.muted() ? I18n.t("call.unmute") : I18n.t("call.mute")));
        mute.setGraphic(Icons.of(state.muted() ? Icons.MIC_OFF : Icons.MIC, 15, state.muted() ? "danger" : "muted"));
        mute.getStyleClass().add("small");
        mute.setId("voice-mute");
        mute.setOnAction(e -> main.session.background().execute(() -> voice.setMuted(!state.muted())));
        Button deaf = new Button();
        deaf.setTooltip(new Tooltip(state.deafened() ? I18n.t("voice.undeafen") : I18n.t("voice.deafen")));
        deaf.setGraphic(Icons.of(Icons.HEADPHONES, 15, state.deafened() ? "danger" : "muted"));
        deaf.getStyleClass().add("small");
        deaf.setId("voice-deafen");
        deaf.setOnAction(e -> main.session.background().execute(() -> voice.setDeafened(!state.deafened())));
        Button leave = new Button(I18n.t("voice.leave"));
        leave.setGraphic(Icons.of(Icons.PHONE, 14, "danger"));
        leave.getStyleClass().addAll("small", "danger-ghost");
        leave.setId("voice-leave");
        leave.setOnAction(e -> main.session.background().execute(voice::leave));
        StackPane badge = new StackPane(Icons.of(Icons.HEADPHONES, 18, "accent"));
        badge.getStyleClass().add("voice-badge-icon");
        Button fire = new Button(main.campfireShown ? I18n.t("campfire.chat") : I18n.t("campfire.open"));
        fire.setMinWidth(Region.USE_PREF_SIZE);
        fire.getStyleClass().add("small");
        fire.setId("voice-campfire");
        fire.setOnAction(e -> {
            if (main.campfireShown) main.places.backFromCampfire();
            else main.places.showCampfire();
        });
        main.voiceBar.getChildren().setAll(badge, text, faces, UiKit.grow(), fire, mute, deaf, leave);
        main.voiceBar.getStyleClass().setAll("call-bar", "voice-bar");
        main.voiceBar.setAlignment(Pos.CENTER_LEFT);
        main.voiceBar.setId("voice-bar");
        main.voiceBar.setVisible(true);
    }

    void showCallBar(String peer, Node body) {
        main.callBar.getChildren().setAll(UiKit.avatar(peer, "avatar-sm", false), body);
        HBox.setHgrow(body, Priority.ALWAYS);
        main.callBar.getStyleClass().setAll("call-bar");
        main.callBar.setAlignment(Pos.CENTER_LEFT);
        main.callBar.setId("call-bar");
        main.callBar.setVisible(true);
    }

    /**
     * The camera or screen-sharing button of a connected call: turns that source on, or off
     * if it is the one being sent. Opening a camera takes a moment, so it happens in the background.
     */
    Button videoButton(com.emirchat.call.Calls calls, boolean screen) {
        Button b = new Button();
        b.getStyleClass().add("small");
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setId(screen ? "call-share-screen" : "call-camera");
        b.setOnAction(e -> {
            com.emirchat.call.CallVideo v = calls.video();
            boolean thisOne = v != null && v.sending() && v.sendingScreen() == screen;
            b.setDisable(true);
            main.session.background().execute(() -> {
                String problem = null;
                try {
                    if (thisOne) {
                        calls.stopVideo();
                    } else {
                        calls.startVideo(screen ? main.session.client().screen() : main.session.client().camera());
                    }
                } catch (IllegalStateException ex) {
                    problem = ex.getMessage();
                }
                String error = problem;
                javafx.application.Platform.runLater(() -> {
                    refreshVideoButtons(calls, screen ? null : b, screen ? b : null);
                    if (error != null) main.notice(I18n.t(screen ? "video.screenFailed" : "video.cameraFailed", error), true);
                });
            });
        });
        return b;
    }

    /** Sets the texts and whether the buttons can be used (video needs a current version on both sides). */
    void refreshVideoButtons(com.emirchat.call.Calls calls, Button camera, Button share) {
        com.emirchat.call.CallVideo v = calls.video();
        boolean possible = calls.videoPossible();
        boolean sendingCamera = v != null && v.sending() && !v.sendingScreen();
        boolean sendingScreen = v != null && v.sending() && v.sendingScreen();
        String unavailable = I18n.t("video.needsNewVersion");
        if (camera != null) {
            camera.setText(sendingCamera ? I18n.t("call.cameraOff") : I18n.t("call.camera"));
            camera.setGraphic(Icons.of(Icons.VIDEO, 14, sendingCamera ? "accent" : "muted"));
            camera.setDisable(!possible);
            camera.setTooltip(new Tooltip(possible ? I18n.t("call.cameraTip") : unavailable));
        }
        if (share != null) {
            share.setText(sendingScreen ? I18n.t("call.stopSharing") : I18n.t("call.shareScreen"));
            share.setGraphic(Icons.of(Icons.MONITOR, 14, sendingScreen ? "accent" : "muted"));
            share.setDisable(!possible);
            share.setTooltip(new Tooltip(possible ? I18n.t("call.shareTip") : unavailable));
        }
    }

    static Button hangUpButton(String text, Runnable action) {
        Button b = new Button(text);
        b.setGraphic(Icons.of(Icons.PHONE, 14, "danger"));
        b.getStyleClass().addAll("small", "danger-ghost");
        b.setId("call-hangup");
        b.setOnAction(e -> action.run());
        return b;
    }

    static String endText(com.emirchat.call.Calls.Call c) {
        return switch (c.reason()) {
            case HUNG_UP -> c.connectedAt() > 0
                    ? I18n.t("call.ended", duration(System.currentTimeMillis() - c.connectedAt()))
                    : I18n.t("call.cancelled");
            case REJECTED -> c.outgoing() ? I18n.t("call.rejected", c.peer()) : I18n.t("call.cancelled");
            case BUSY -> I18n.t("call.busy", c.peer());
            case NO_ANSWER -> I18n.t("call.noAnswer", c.peer());
            case MISSED -> I18n.t("call.missedFrom", c.peer());
            case FAILED -> I18n.t("call.failed", c.error() == null ? "" : c.error());
        };
    }

    static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format("%02d:%02d", s / 60, s % 60);
    }

    /** Rings (a system beep) every two seconds while the call is ringing, and brings the window forward. */
    void ring() {
        if (main.stack.getScene() != null && main.stack.getScene().getWindow() instanceof javafx.stage.Stage stage) {
            if (stage.isIconified()) stage.setIconified(false);
            stage.toFront();
        }
        if (!Boolean.parseBoolean(System.getProperty("securechat.notifications", "true"))) return;
        Runnable beep = () -> {
            try {
                java.awt.Toolkit.getDefaultToolkit().beep();
            } catch (UnsupportedOperationException ignored) { // includes HeadlessException
                // no sound: the bar still shows the call
            }
        };
        beep.run();
        main.callTicker = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(2), e -> beep.run()));
        main.callTicker.setCycleCount(javafx.animation.Animation.INDEFINITE);
        main.callTicker.play();
    }

    void notifyCall(String title, String body) {
        if (!main.session.setting(AccountSession.NOTIFICATIONS) || !Boolean.parseBoolean(System.getProperty("securechat.notifications", "true"))) return;
        Tray.get().notify(title, body);
    }
}
