package com.emirchat.ui;

import com.emirchat.chat.FileMessage;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * Voice messages in a conversation: recording one with a live meter, and the card that plays one
 * back with its waveform.
 */
final class VoiceMessages {
    private final ConversationView chat;

    VoiceMessages(ConversationView chat) {
        this.chat = chat;
    }

    private com.emirchat.call.VoiceRecorder recorder;   // while recording a voice message
    /** Decoded voice messages, the most recently used few only (each can be a few MB). */
    private final Map<String, byte[]> voices = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) { return size() > 12; }
    };
    Runnable cancelRecording;    // discards the recording in progress, if any
    /** Swaps the composer for a recording bar: live level meter, timer, cancel and send. */
    void startRecording() {
        if (recorder != null) return;
        com.emirchat.call.VoiceRecorder r = new com.emirchat.call.VoiceRecorder();
        java.util.ArrayDeque<Float> levels = new java.util.ArrayDeque<>();
        javafx.scene.canvas.Canvas meter = new javafx.scene.canvas.Canvas(260, 30);
        Label clock = UiKit.label("0:00", "voice-clock");
        clock.setId("voice-clock");
        long started = System.currentTimeMillis();
        javafx.animation.Timeline tick = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(100), e -> {
                    clock.setText(voiceTime(System.currentTimeMillis() - started));
                    synchronized (levels) {
                        drawLevels(meter, levels);
                    }
                }));
        tick.setCycleCount(javafx.animation.Animation.INDEFINITE);
        Runnable[] finish = new Runnable[1];
        try {
            r.start(level -> {
                synchronized (levels) {
                    levels.addLast(level);
                    while (levels.size() > 60) levels.removeFirst();
                }
            }, () -> Platform.runLater(() -> finish[0].run()));
        } catch (IllegalStateException e) {
            chat.systemLine(I18n.t("voice.noMicrophone", e.getMessage()), true);
            return;
        }
        recorder = r;
        List<Node> normal = List.copyOf(chat.composer.getChildren());
        Button cancel = new Button();
        cancel.setId("voice-cancel");
        cancel.getStyleClass().add("attach");
        cancel.setGraphic(Icons.of(Icons.TRASH, 18, "danger"));
        cancel.setTooltip(new Tooltip(I18n.t("voice.cancel")));
        Button done = new Button();
        done.setId("voice-send");
        done.getStyleClass().add("send");
        done.setGraphic(Icons.of(Icons.SEND, 17, "on-accent"));
        done.setTooltip(new Tooltip(I18n.t("voice.send")));
        javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(5);
        dot.getStyleClass().add("rec-dot");
        javafx.animation.FadeTransition blink = new javafx.animation.FadeTransition(javafx.util.Duration.millis(600), dot);
        blink.setFromValue(1);
        blink.setToValue(0.25);
        blink.setAutoReverse(true);
        blink.setCycleCount(javafx.animation.Animation.INDEFINITE);
        StackPane meterBox = new StackPane(meter);
        meterBox.setAlignment(Pos.CENTER_LEFT);
        meterBox.setMinWidth(0);
        HBox.setHgrow(meterBox, Priority.ALWAYS);
        meter.widthProperty().bind(meterBox.widthProperty());
        HBox bar = new HBox(12, dot, clock, meterBox);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("recording-bar");
        HBox.setHgrow(bar, Priority.ALWAYS);
        chat.composer.getChildren().setAll(cancel, bar, done);
        chat.composer.setAlignment(Pos.CENTER);
        java.util.function.Consumer<Boolean> end = sendIt -> {
            if (recorder != r) return;
            tick.stop();
            blink.stop();
            recorder = null;
            cancelRecording = null;
            byte[] pcm = r.stop();
            chat.composer.getChildren().setAll(normal);
            chat.composer.setAlignment(Pos.BOTTOM_CENTER);
            chat.input.requestFocus();
            if (!sendIt) return;
            if (com.emirchat.call.VoiceNotes.millis(pcm) < 500) {
                chat.systemLine(I18n.t("voice.tooShort"), false);
                return;
            }
            String name = "voice-" + java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".wav";
            chat.sender.sendBytes(name, () -> com.emirchat.call.VoiceNotes.wav(pcm), com.emirchat.call.VoiceNotes.MIME);
        };
        // At the length limit the microphone stops, and the recording waits for Send or Discard:
        // nothing is ever sent without the user asking.
        finish[0] = () -> {
            if (recorder != r) return;
            tick.stop();
            blink.stop();
            r.stop();
            clock.setText(voiceTime(com.emirchat.call.VoiceNotes.MAX_SECONDS * 1000L) + " · " + I18n.t("voice.limit"));
        };
        cancelRecording = () -> end.accept(false);
        cancel.setOnAction(e -> end.accept(false));
        done.setOnAction(e -> end.accept(true));
        tick.play();
        blink.play();
    }

    private static String voiceTime(long millis) {
        long s = millis / 1000;
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    private static void drawLevels(javafx.scene.canvas.Canvas c, java.util.Deque<Float> levels) {
        var g = c.getGraphicsContext2D();
        double w = c.getWidth();
        double h = c.getHeight();
        g.clearRect(0, 0, w, h);
        g.setFill(Appearance.accentColor(Theme.isDark()));
        double barW = 3;
        double gap = 2;
        int i = 0;
        // newest on the right, scrolling left
        Float[] values = levels.toArray(new Float[0]);
        for (int k = values.length - 1; k >= 0; k--, i++) {
            double x = w - (i + 1) * (barW + gap);
            if (x < 0) break;
            double bh = Math.max(2, values[k] * h);
            g.fillRoundRect(x, (h - bh) / 2, barW, bh, 3, 3);
        }
    }

    /** A voice message: play/pause, a waveform that fills as it plays (click to seek) and its length. */
    Node voiceCard(FileMessage file) {
        Button play = new Button();
        play.getStyleClass().add("voice-play");
        play.setGraphic(Icons.of(Icons.PLAY, 16, "on-accent"));
        play.setDisable(true);
        javafx.scene.canvas.Canvas wave = new javafx.scene.canvas.Canvas(200, 32);
        wave.getStyleClass().add("voice-wave");
        Label length = UiKit.label("…", "voice-length");
        HBox card = new HBox(10, play, wave, length);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("voice-card");
        card.setMinWidth(Region.USE_PREF_SIZE);
        String key = ConversationView.fileKey(file);
        float[][] bars = {new float[0]};
        com.emirchat.call.VoicePlayer[] player = {null};
        double[] at = {0};
        boolean[] own = {false};
        Runnable redraw = () -> drawWave(wave, bars[0], at[0], own[0]);
        card.parentProperty().addListener((o, a, parent) -> {
            // the bubble decides the colours: cream on our amber bubbles, accent on theirs
            for (Node n = parent; n != null; n = n.getParent()) {
                if (n.getStyleClass().contains("bubble")) {
                    own[0] = n.getStyleClass().contains("own");
                    redraw.run();
                    break;
                }
            }
        });
        java.util.function.Consumer<byte[]> ready = pcm -> {
            bars[0] = com.emirchat.call.VoiceNotes.waveform(pcm, 40);
            length.setText(voiceTime(com.emirchat.call.VoiceNotes.millis(pcm)));
            player[0] = new com.emirchat.call.VoicePlayer(pcm);
            play.setDisable(false);
            redraw.run();
        };
        play.setOnAction(e -> {
            com.emirchat.call.VoicePlayer p = player[0];
            if (p == null) return;
            if (p.playing()) {
                p.stop();
                play.setGraphic(Icons.of(Icons.PLAY, 16, "on-accent"));
                return;
            }
            try {
                p.play(at[0] >= 0.999 ? 0 : at[0], f -> Platform.runLater(() -> {
                    at[0] = f;
                    redraw.run();
                }), () -> Platform.runLater(() -> {
                    at[0] = 0;
                    play.setGraphic(Icons.of(Icons.PLAY, 16, "on-accent"));
                    redraw.run();
                }));
                play.setGraphic(Icons.of(Icons.PAUSE, 16, "on-accent"));
            } catch (IllegalStateException ex) {
                chat.systemLine(I18n.t("voice.noSpeaker", ex.getMessage()), true);
            }
        });
        wave.setOnMouseClicked(e -> {
            at[0] = Math.max(0, Math.min(1, e.getX() / wave.getWidth()));
            redraw.run();
            com.emirchat.call.VoicePlayer p = player[0];
            if (p != null && p.playing()) {
                p.stop();
                play.fire();
            }
        });
        byte[] cached = voices.get(key);
        long limit = 64 + 2L * (long) com.emirchat.call.AudioDevice.SAMPLE_RATE * com.emirchat.call.VoiceNotes.MAX_SECONDS;
        if (cached != null) {
            ready.accept(cached);
        } else if (file.size() > limit) {
            length.setText(I18n.t("voice.unplayable")); // longer than any recording: still saveable as a file
        } else {
            chat.session.downloads().execute(() -> {
                try {
                    byte[] pcm = com.emirchat.call.VoiceNotes.decode(chat.session.client().downloadFile(file));
                    if (com.emirchat.call.VoiceNotes.millis(pcm) > com.emirchat.call.VoiceNotes.MAX_SECONDS * 1000L + 1000) {
                        throw new IOException("too long");
                    }
                    Platform.runLater(() -> {
                        voices.put(key, pcm);
                        ready.accept(pcm);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> length.setText(I18n.t("voice.unplayable")));
                }
            });
        }
        redraw.run();
        return card;
    }

    private static void drawWave(javafx.scene.canvas.Canvas c, float[] bars, double played, boolean own) {
        var g = c.getGraphicsContext2D();
        double w = c.getWidth();
        double h = c.getHeight();
        g.clearRect(0, 0, w, h);
        int n = bars.length == 0 ? 40 : bars.length;
        double step = w / n;
        double barW = Math.max(2, step * 0.6);
        javafx.scene.paint.Color on = own ? javafx.scene.paint.Color.rgb(255, 248, 236) : Appearance.accentColor(Theme.isDark());
        javafx.scene.paint.Color off = own ? javafx.scene.paint.Color.rgb(255, 248, 236, 0.42)
                : Theme.isDark() ? javafx.scene.paint.Color.rgb(182, 170, 154, 0.45) : javafx.scene.paint.Color.rgb(95, 84, 70, 0.35);
        for (int i = 0; i < n; i++) {
            double v = bars.length == 0 ? 0.15 : Math.max(0.08, bars[i]);
            double bh = v * h;
            g.setFill((i + 0.5) / n <= played ? on : off);
            g.fillRoundRect(i * step, (h - bh) / 2, barW, bh, barW, barW);
        }
    }
}
