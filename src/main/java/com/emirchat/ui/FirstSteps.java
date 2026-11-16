package com.emirchat.ui;

import com.emirchat.client.ChatClient;
import com.emirchat.api.Packet;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * The three things a new account should do, shown on the empty screen until they are done or the
 * card is closed: add a friend, compare keys with one, and make a backup. Each line does the
 * step when clicked, and ticks itself off when it is done (however it got done).
 */
final class FirstSteps {
    /**
     * @param why    one line on what the step is good for
     * @param go     the text of the line's button
     * @param action what the button does, or null if the step cannot be done yet
     */
    record Step(String id, String title, String why, String go, boolean done, Runnable action) {}

    private final MainView main;
    private final VBox holder = new VBox();

    FirstSteps(MainView main) {
        this.main = main;
        holder.setAlignment(Pos.CENTER);
    }

    /** The place of the card on the empty screen (empty itself once there is nothing left to do). */
    Node node() {
        rebuild();
        return holder;
    }

    /** Something a step depends on changed: tick it off if the card is on screen. */
    void refresh() {
        if (holder.getScene() != null) rebuild();
    }

    List<Step> steps() {
        ChatClient client = main.session.client();
        List<Packet.Friend> friends = client.friends().friends();
        String unverified = null;
        boolean anyVerified = false;
        for (Packet.Friend f : friends) {
            if (client.isVerified(f.username())) anyVerified = true;
            else if (unverified == null) unverified = f.username();
        }
        String toVerify = unverified;
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("friend", I18n.t("steps.friend"), I18n.t("steps.friendWhy"), I18n.t("steps.friendGo"),
                !friends.isEmpty(), this::goToAddFriend));
        steps.add(new Step("verify", I18n.t("steps.verify"), I18n.t("steps.verifyWhy"), I18n.t("steps.verifyGo"),
                anyVerified, toVerify == null ? null : () -> main.contacts.openContact(toVerify)));
        steps.add(new Step("backup", I18n.t("steps.backup"), I18n.t("steps.backupWhy"), I18n.t("steps.backupGo"),
                AppPrefs.flag("backed-up", client.username()), main.settingsUi::openBackup));
        return steps;
    }

    private void rebuild() {
        holder.getChildren().clear();
        String user = main.session.client().username();
        if (AppPrefs.flag("first-steps-closed", user)) return;
        List<Step> steps = steps();
        if (steps.stream().allMatch(Step::done)) return;

        Button close = new Button("✕");
        close.getStyleClass().addAll("link", "first-steps-close");
        close.setId("first-steps-close");
        close.setAccessibleText(I18n.t("steps.close"));
        close.setTooltip(new javafx.scene.control.Tooltip(I18n.t("steps.close")));
        close.setOnAction(e -> {
            AppPrefs.setFlag("first-steps-closed", user);
            rebuild();
        });
        long done = steps.stream().filter(Step::done).count();
        HBox head = new HBox(8, UiKit.label(I18n.t("steps.title"), "h3"),
                UiKit.label(done + " / " + steps.size(), "caption"), UiKit.grow(), close);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(10, head);
        for (Step s : steps) {
            Label mark = UiKit.label(s.done() ? "✓" : "○", "first-step-mark");
            Label title = UiKit.label(s.title(), "first-step-title");
            Label why = UiKit.label(s.why(), "caption");
            why.setWrapText(true);
            VBox text = new VBox(2, title, why);
            text.setMinWidth(0);
            HBox.setHgrow(text, javafx.scene.layout.Priority.ALWAYS); // the text gives way, never the button
            mark.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            HBox row = new HBox(10, mark, text);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("first-step");
            row.setId("first-step-" + s.id());
            if (s.done()) {
                row.getStyleClass().add("done");
            } else {
                Button go = new Button(s.go());
                go.getStyleClass().add("small");
                go.setMinWidth(92); // one width for all three, wide enough for the longest word
                go.setId("first-step-" + s.id() + "-go");
                go.setDisable(s.action() == null);
                if (s.action() != null) go.setOnAction(e -> s.action().run());
                row.getChildren().add(go);
            }
            card.getChildren().add(row);
        }
        card.getStyleClass().addAll("card-flat", "first-steps");
        card.setId("first-steps");
        card.setMaxWidth(480);
        holder.getChildren().add(card);
    }

    private void goToAddFriend() {
        main.friendsTab.setSelected(true); // shows the friends list, with the box to add one on top
        Platform.runLater(() -> {
            Node box = main.stack.lookup("#add-friend");
            if (box != null) box.requestFocus();
        });
    }
}
