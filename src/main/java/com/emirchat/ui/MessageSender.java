package com.emirchat.ui;

import com.emirchat.chat.ChatMessage;
import com.emirchat.chat.FileMessage;
import com.emirchat.e2e.Attachments;
import com.emirchat.client.ChatClient;
import com.emirchat.client.History;
import com.emirchat.trust.KeyChangedException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;

/**
 * Everything a conversation sends: text (with replies, edits and slash commands), files, polls,
 * votes, reactions, deletions, typing notices and read receipts, each shown at once and marked
 * as it is delivered.
 */
final class MessageSender {
    private final ConversationView chat;

    MessageSender(ConversationView chat) {
        this.chat = chat;
    }

    void maybeSendTyping(String text) {
        if (chat.peer == null || text == null || text.isBlank() || chat.editing != null) return;
        long now = System.currentTimeMillis();
        if (now - chat.lastTypingSent < 3000 || !chat.session.setting(AccountSession.SEND_TYPING)) return;
        if (!chat.session.client().isOnline(chat.peer)) return; // never queue typing notices on the server
        chat.lastTypingSent = now;
        chat.session.background().execute(() -> {
            try {
                chat.session.client().sendAction(ChatClient.Chat.dm(chat.peer), ChatClient.ActionKind.TYPING, null, "1");
            } catch (Exception ignored) {
                // a typing notice is best effort
            }
        });
    }

    void startReply(History.Message m) {
        chat.editing = null;
        chat.replyTo = m.uid();
        String who = m.outgoing() ? I18n.t("conv.yourself") : m.sender();
        showContext("↩ " + I18n.t("conv.replyingTo", who) + ": " + Markdown.plain(m.text()));
        chat.input.requestFocus();
    }

    void startEdit(History.Message m) {
        chat.replyTo = null;
        chat.editing = m;
        showContext("✎ " + I18n.t("conv.editing"));
        chat.input.setText(m.text());
        chat.input.positionCaret(chat.input.getText().length());
        chat.input.requestFocus();
    }

    private void showContext(String text) {
        chat.contextText.setText(text);
        chat.contextBar.setVisible(true);
        chat.contextBar.setManaged(true);
    }

    void clearContext() {
        if (chat.editing != null) chat.input.clear();
        chat.replyTo = null;
        chat.editing = null;
        chat.contextBar.setVisible(false);
        chat.contextBar.setManaged(false);
    }

    void react(History.Message m, String emoji) {
        String me = chat.session.client().username();
        chat.popId = m.id();
        chat.popEmoji = AccountSession.displayEmoji(emoji);
        try {
            chat.session.history().react(chat.conversation, m.uid(), me, emoji);
        } catch (IOException e) {
            return;
        }
        chat.refresh(m.id());
        sendAction(ChatClient.ActionKind.REACT, m.uid(), emoji);
    }

    void deleteForEveryone(History.Message m) {
        try {
            History.Message deleted = chat.session.history().delete(chat.conversation, m.uid(), chat.session.client().username());
            if (deleted != null && deleted.file() != null) chat.session.client().forgetFile(deleted.file());
        } catch (IOException e) {
            return;
        }
        chat.refresh(m.id());
        sendAction(ChatClient.ActionKind.DELETE, m.uid(), "");
    }

    void sendAction(ChatClient.ActionKind kind, byte[] target, String value) {
        chat.session.background().execute(() -> {
            String problem = null;
            try {
                chat.session.client().sendAction(chat.chat(), kind, target, value);
            } catch (KeyChangedException e) {
                problem = I18n.t("conv.keyChangedFirst", chat.peer);
            } catch (Exception e) {
                problem = I18n.t("conv.changeFailed", e.getMessage());
            }
            String p = problem;
            if (p != null) Platform.runLater(() -> chat.systemLine(p, true));
        });
    }

    /** Tells a friend we read their messages up to the newest one shown (if they allow receipts). */
    void sendReadReceipt() {
        byte[] target = chat.pendingRead;
        chat.pendingRead = null;
        if (target == null || chat.peer == null || !chat.session.setting(AccountSession.SEND_READ_RECEIPTS)) return;
        if (chat.root.getScene() == null || chat.root.getScene().getWindow() == null || !chat.root.getScene().getWindow().isFocused()) {
            chat.pendingRead = target; // read when the window is looked at again
            return;
        }
        sendAction(ChatClient.ActionKind.READ, target, "");
    }

    void send() {
        if (chat.editing == null && chat.input.getText().strip().startsWith("/") && command(chat.input.getText().strip())) return;
        // (a command may have replaced the text: /shrug, /roll ...)
        String text = chat.input.getText().strip();
        if (text.isEmpty()) return;
        if (text.getBytes(StandardCharsets.UTF_8).length > ChatMessage.MAX_TEXT_BYTES) {
            chat.input.setStyle("-fx-border-color: -sc-danger;");
            return;
        }
        chat.input.setStyle("");
        String me = chat.session.client().username();
        if (chat.editing != null) {
            History.Message target = chat.editing;
            clearContext();
            chat.input.clear();
            if (text.equals(target.text())) return;
            try {
                chat.session.history().edit(chat.conversation, target.uid(), me, text);
            } catch (IOException e) {
                return;
            }
            chat.refresh(target.id());
            sendAction(ChatClient.ActionKind.EDIT, target.uid(), text);
            return;
        }
        chat.input.clear();
        strike();
        byte[] answering = chat.replyTo;
        clearContext();
        byte[] uid = ChatClient.newMessageId();
        long now = System.currentTimeMillis();
        long expire = chat.session.timer(chat.conversation);
        long expiresAt = AccountSession.expiresAt(now, expire);
        long localId;
        History.Message stored;
        try {
            localId = chat.session.history().add(chat.conversation, me, text, now, true, History.PENDING, 0, expiresAt, uid, answering);
            stored = chat.session.history().get(localId);
        } catch (IOException e) {
            return;
        }
        chat.append(stored);
        chat.session.background().execute(() -> {
            int status;
            String problem = null;
            try {
                ChatClient.Sent sent = chat.session.client().send(chat.chat(), text, expire, answering, uid);
                status = chat.session.sent(localId, sent.envelopeId());
                if (!sent.skipped().isEmpty()) {
                    problem = I18n.t("conv.notSentTo", String.join(", ", sent.skipped()));
                }
            } catch (KeyChangedException e) {
                status = History.FAILED;
                problem = I18n.t("conv.keyChangedResend", chat.peer);
                Platform.runLater(chat::refreshHeader);
            } catch (Exception e) {
                status = History.FAILED;
                problem = e.getMessage();
            }
            if (status == History.FAILED) {
                try {
                    chat.session.history().setStatus(localId, History.FAILED, 0);
                } catch (IOException ignored) {
                    // shown as failed in this session anyway
                }
            }
            int finalStatus = status;
            String finalProblem = problem;
            Platform.runLater(() -> {
                chat.setStatus(localId, finalStatus);
                if (finalProblem != null) chat.systemLine(finalProblem, true);
            });
        });
    }

    /**
     * The send button pulls back, lunges and settles, and throws a few sparks up towards the
     * conversation, like a log settling in the fire.
     */
    private void strike() {
        if (!Motion.enabled()) return;
        Node icon = chat.sendButton.getGraphic();
        javafx.animation.Timeline lunge = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.ZERO,
                        new javafx.animation.KeyValue(chat.sendButton.scaleXProperty(), 1),
                        new javafx.animation.KeyValue(chat.sendButton.scaleYProperty(), 1),
                        new javafx.animation.KeyValue(icon.rotateProperty(), 0)),
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(70),
                        new javafx.animation.KeyValue(chat.sendButton.scaleXProperty(), 0.84),
                        new javafx.animation.KeyValue(chat.sendButton.scaleYProperty(), 0.84),
                        new javafx.animation.KeyValue(icon.rotateProperty(), 28)),
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(170),
                        new javafx.animation.KeyValue(chat.sendButton.scaleXProperty(), 1.12, javafx.animation.Interpolator.EASE_OUT),
                        new javafx.animation.KeyValue(chat.sendButton.scaleYProperty(), 1.12, javafx.animation.Interpolator.EASE_OUT),
                        new javafx.animation.KeyValue(icon.rotateProperty(), -14, javafx.animation.Interpolator.EASE_OUT)),
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(320),
                        new javafx.animation.KeyValue(chat.sendButton.scaleXProperty(), 1, javafx.animation.Interpolator.EASE_BOTH),
                        new javafx.animation.KeyValue(chat.sendButton.scaleYProperty(), 1, javafx.animation.Interpolator.EASE_BOTH),
                        new javafx.animation.KeyValue(icon.rotateProperty(), 0, javafx.animation.Interpolator.EASE_BOTH)));
        lunge.play();
        if (!(chat.sendButton.getParent() instanceof javafx.scene.layout.Pane composer)) return;
        javafx.geometry.Bounds b = chat.sendButton.getBoundsInParent();
        java.util.Random r = new java.util.Random();
        for (int k = 0; k < 6; k++) {
            javafx.scene.shape.Circle spark = new javafx.scene.shape.Circle(1.6 + r.nextDouble() * 1.6);
            spark.getStyleClass().add(k % 2 == 0 ? "spark" : "spark-hot");
            spark.setManaged(false);
            spark.setMouseTransparent(true);
            spark.setCenterX(b.getCenterX());
            spark.setCenterY(b.getMinY() + 8);
            composer.getChildren().add(spark);
            double angle = -Math.PI / 2 + (r.nextDouble() - 0.5) * 1.6; // upwards, spreading out
            double distance = 30 + r.nextDouble() * 40;
            javafx.animation.TranslateTransition fly = new javafx.animation.TranslateTransition(
                    javafx.util.Duration.millis(380 + r.nextInt(220)), spark);
            fly.setByX(Math.cos(angle) * distance);
            fly.setByY(Math.sin(angle) * distance);
            fly.setInterpolator(javafx.animation.Interpolator.EASE_OUT);
            javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(fly.getDuration(), spark);
            fade.setToValue(0);
            javafx.animation.ParallelTransition all = new javafx.animation.ParallelTransition(fly, fade);
            all.setDelay(javafx.util.Duration.millis(110));
            all.setOnFinished(e -> composer.getChildren().remove(spark));
            all.play();
        }
    }

    void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("conv.sendFile"));
        File chosen = chooser.showOpenDialog(chat.root.getScene().getWindow());
        if (chosen == null) return;
        sendFile(chosen);
    }

    /** Encrypts, uploads and sends one file (from the chooser, a drop or a paste). */
    void sendFile(File chosen) {
        if (chosen.isDirectory()) return;
        if (chosen.length() > Attachments.MAX_FILE_BYTES) {
            chat.systemLine(I18n.t("conv.tooLarge", chosen.getName(), Attachments.MAX_FILE_BYTES / (1024 * 1024)), true);
            return;
        }
        sendBytes(FileMessage.safeName(chosen.getName()), () -> Files.readAllBytes(chosen.toPath()), mimeType(chosen.toPath()));
    }

    interface Bytes {
        byte[] read() throws IOException;
    }

    void sendBytes(String fileName, Bytes source, String mime) {
        String name = FileMessage.safeName(fileName);
        Label progress = UiKit.label(I18n.t("conv.sending", name), "system-line");
        HBox row = new HBox(progress);
        row.setAlignment(Pos.CENTER_RIGHT);
        row.setPadding(new Insets(12, 0, 0, 0));
        chat.messages.getChildren().add(row);
        long expire = chat.session.timer(chat.conversation);
        byte[] answering = chat.replyTo;
        clearContext();
        chat.session.background().execute(() -> {
            String problem = null;
            History.Message stored = null;
            try {
                byte[] data = source.read();
                if (data.length > Attachments.MAX_FILE_BYTES) throw new IOException(I18n.t("conv.tooLarge", name, Attachments.MAX_FILE_BYTES / (1024 * 1024)));
                String me = chat.session.client().username();
                ChatClient.Sent sent = chat.session.client().sendFile(chat.chat(), name, mime, data, expire, answering);
                long expiresAt = AccountSession.expiresAt(System.currentTimeMillis(), expire);
                long id = chat.session.history().addFile(chat.conversation, me, sent.file(), sent.file().sentAtMillis(), true,
                        History.SENT, sent.envelopeId(), expiresAt, sent.messageId(), answering);
                chat.session.sent(id, sent.envelopeId());
                stored = chat.session.history().get(id);
                if (!sent.skipped().isEmpty()) {
                    problem = I18n.t("conv.notSentTo", String.join(", ", sent.skipped()));
                }
            } catch (KeyChangedException e) {
                problem = I18n.t("conv.keyChangedResend", chat.peer);
                Platform.runLater(chat::refreshHeader);
            } catch (Exception e) {
                problem = I18n.t("conv.sendFailed", name, e.getMessage());
            }
            History.Message finalStored = stored;
            String finalProblem = problem;
            Platform.runLater(() -> {
                chat.messages.getChildren().remove(row);
                if (finalStored != null) chat.append(finalStored);
                if (finalProblem != null) chat.systemLine(finalProblem, true);
            });
        });
    }

    void saveFile(FileMessage file) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("conv.saveFile"));
        chooser.setInitialFileName(file.name());
        File target = chooser.showSaveDialog(chat.root.getScene().getWindow());
        if (target == null) return;
        chat.session.downloads().execute(() -> {
            String result;
            boolean danger = false;
            try {
                Files.write(target.toPath(), chat.session.client().downloadFile(file));
                result = I18n.t("conv.saved", target.getName());
            } catch (SecurityException e) {
                result = I18n.t("conv.notSaved", file.name(), e.getMessage());
                danger = true;
            } catch (Exception e) {
                result = I18n.t("conv.downloadFailed", file.name(), e.getMessage());
                danger = true;
            }
            String text = result;
            boolean isDanger = danger;
            Platform.runLater(() -> chat.systemLine(text, isDanger));
        });
    }

    private static String mimeType(Path file) {
        try {
            String probed = Files.probeContentType(file);
            if (probed != null) return probed;
        } catch (IOException ignored) {
            // fall back to the extension
        }
        String n = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    /** Votes for an answer, or takes the vote back when it is clicked again. */
    void vote(History.Message m, int choice) {
        String me = chat.session.client().username();
        Integer mine = chat.votes.getOrDefault(m.id(), Map.of()).get(me);
        int value = mine != null && mine == choice ? -1 : choice;
        try {
            chat.session.history().vote(chat.conversation, m.uid(), me, value);
        } catch (IOException e) {
            return;
        }
        chat.refresh(m.id());
        sendAction(ChatClient.ActionKind.VOTE, m.uid(), value < 0 ? "" : String.valueOf(value));
    }

    private void sendPoll(com.emirchat.chat.Poll poll) {
        String me = chat.session.client().username();
        byte[] uid = ChatClient.newMessageId();
        long now = System.currentTimeMillis();
        long expire = chat.session.timer(chat.conversation);
        long localId;
        try {
            localId = chat.session.history().add(chat.conversation, me, poll.toStored(), now, true, History.PENDING, 0,
                    AccountSession.expiresAt(now, expire), uid, new byte[0]);
            chat.append(chat.session.history().get(localId));
        } catch (IOException e) {
            return;
        }
        deliver(localId, () -> chat.session.client().sendPoll(chat.chat(), poll, expire, uid));
    }

    private void sendAmber(com.emirchat.chat.Capsule capsule) {
        String me = chat.session.client().username();
        byte[] uid = ChatClient.newMessageId();
        long now = System.currentTimeMillis();
        long expire = chat.session.timer(chat.conversation);
        long localId;
        try {
            localId = chat.session.history().add(chat.conversation, me, capsule.toStored(), now, true, History.PENDING, 0,
                    AccountSession.expiresAt(now, expire), uid, new byte[0]);
            chat.append(chat.session.history().get(localId));
        } catch (IOException e) {
            return;
        }
        deliver(localId, () -> chat.session.client().sendCapsule(chat.chat(), capsule, expire, uid));
    }

    private long lastNudge;

    /** Sends a scorpion to the person of this direct chat (at most every ten seconds). */
    void nudge() {
        if (chat.chat().isGroup()) {
            chat.systemLine(I18n.t("nudge.onlyDirect"), true);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastNudge < AccountSession.NUDGE_GAP_MS) {
            chat.systemLine(I18n.t("nudge.wait"), true);
            return;
        }
        lastNudge = now;
        sendAction(ChatClient.ActionKind.NUDGE, null, "");
        chat.systemLine("🦂 " + I18n.t("nudge.sent", chat.peer), false);
    }

    interface Send {
        ChatClient.Sent run() throws Exception;
    }

    /** Sends in the background and shows the outcome on the stored message. */
    void deliver(long localId, Send send) {
        chat.session.background().execute(() -> {
            int status;
            String problem = null;
            try {
                ChatClient.Sent sent = send.run();
                status = chat.session.sent(localId, sent.envelopeId());
                if (!sent.skipped().isEmpty()) problem = I18n.t("conv.notSentTo", String.join(", ", sent.skipped()));
            } catch (KeyChangedException e) {
                status = History.FAILED;
                problem = I18n.t("conv.keyChangedResend", chat.peer);
            } catch (Exception e) {
                status = History.FAILED;
                problem = e.getMessage();
            }
            if (status == History.FAILED) {
                try {
                    chat.session.history().setStatus(localId, History.FAILED, 0);
                } catch (IOException ignored) {
                    // shown as failed in this session anyway
                }
            }
            int finalStatus = status;
            String finalProblem = problem;
            Platform.runLater(() -> {
                chat.setStatus(localId, finalStatus);
                if (finalProblem != null) chat.systemLine(finalProblem, true);
            });
        });
    }

    /** Runs a "/" command; true if it was one (and the composer was handled). */
    private boolean command(String text) {
        SlashCommands.Result result = SlashCommands.parse(text, chat.session.client().username(), ConversationView.RANDOM);
        switch (result) {
            case SlashCommands.NotACommand n -> {
                return false;
            }
            case SlashCommands.Text t -> {
                chat.input.setText(t.text());
                return false; // send the replacement as an ordinary message
            }
            case SlashCommands.PollResult p -> {
                chat.input.clear();
                strike();
                sendPoll(p.poll());
            }
            case SlashCommands.Remind r -> {
                chat.input.clear();
                long at = System.currentTimeMillis() + r.delayMillis();
                chat.systemLine("⏰ " + I18n.t("slash.remindSet", UiKit.time(at) + (r.delayMillis() > 86_400_000L ? "" : " " + UiKit.clock(at)),
                        r.text()), false);
                javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(javafx.util.Duration.millis(r.delayMillis()));
                wait.setOnFinished(e -> {
                    Tray.get().notify(I18n.t("slash.reminder"), r.text());
                    Sounds.play();
                    chat.systemLine("⏰ " + r.text(), false);
                });
                wait.play();
            }
            case SlashCommands.Topic t -> {
                chat.input.clear();
                chat.header.setTopic(t.topic());
            }
            case SlashCommands.Help h -> {
                chat.input.clear();
                chat.systemLine(I18n.t("slash.help").replace("\\n", "\n"), false);
            }
            case SlashCommands.Problem p -> chat.systemLine(I18n.t(p.key(), p.arg()), true);
            case SlashCommands.Amber a -> {
                chat.input.clear();
                strike();
                sendAmber(a.capsule());
            }
            case SlashCommands.Nudge n -> {
                chat.input.clear();
                nudge();
            }
        }
        return true;
    }
}
