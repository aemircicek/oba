package com.emirchat.ui;

import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.event.Event;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.text.Text;

/**
 * Makes the interface usable without a mouse and without sight.
 *
 * <p>{@link #install} watches a window and gives every control a name a screen reader can say:
 * a button that shows only an icon takes its tooltip, a text box without a label takes its
 * prompt. {@link #clickable} turns something that only reacted to the mouse (a row in a list, a
 * badge, a picture) into a stop for the Tab key that Enter and Space activate.
 */
final class A11y {
    private static final Object WATCHED = new Object();
    private static final String NAMED = "a11y-click-name";
    private static final List<Node> pending = new ArrayList<>();

    private A11y() {}

    /** Names everything under {@code root}, now and whenever something is added to it. */
    static void install(Parent root) {
        watch(root);
    }

    private static void watch(Node node) {
        later(node);
        if (!(node instanceof Parent parent)) return;
        if (parent.getProperties().putIfAbsent(WATCHED, Boolean.TRUE) != null) return;
        for (Node child : parent.getChildrenUnmodifiable()) watch(child);
        parent.getChildrenUnmodifiable().addListener((ListChangeListener<Node>) change -> {
            while (change.next()) {
                for (Node added : change.getAddedSubList()) watch(added);
            }
        });
    }

    /**
     * Names are given a moment later, in one go: a node is usually added to the window first and
     * given its tooltip or prompt right after.
     */
    private static void later(Node node) {
        if (!(node instanceof ButtonBase) && !(node instanceof TextInputControl)) return;
        if (pending.isEmpty()) {
            Platform.runLater(() -> {
                List<Node> batch = new ArrayList<>(pending);
                pending.clear();
                batch.forEach(A11y::name);
            });
        }
        pending.add(node);
    }

    private static void name(Node node) {
        if (node.getAccessibleText() != null) return;
        if (node instanceof ButtonBase button && !sayable(button.getText())) {
            Tooltip tip = button.getTooltip();
            if (tip != null && sayable(tip.getText())) button.setAccessibleText(tip.getText());
        } else if (node instanceof TextInputControl input && sayable(input.getPromptText())) {
            input.setAccessibleText(input.getPromptText());
        }
    }

    /** False for nothing at all and for a lone symbol ("✕", "+", "‹"), which says nothing when read out. */
    static boolean sayable(String text) {
        return text != null && text.codePoints().filter(Character::isLetterOrDigit).count() >= 2;
    }

    /**
     * Lets the keyboard reach and activate a node that has a mouse-click handler: Tab stops on
     * it, Enter and Space click it, and a screen reader announces it as a button.
     *
     * @param name what a screen reader says, or null to say the text the node shows
     */
    static <T extends Node> T clickable(T node, String name) {
        if (node.getProperties().containsKey(NAMED)) return node;
        node.getProperties().put(NAMED, name == null ? "" : name);
        node.setFocusTraversable(true);
        node.setAccessibleRole(node instanceof Text ? AccessibleRole.HYPERLINK : AccessibleRole.BUTTON);
        node.getStyleClass().add("keyboard-click");
        if (name != null) {
            node.setAccessibleText(name);
        } else {
            // what the node shows can change while it is on screen: read it when it is reached
            node.focusedProperty().addListener((o, was, now) -> {
                if (now) node.setAccessibleText(shownText(node));
            });
        }
        node.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getTarget() != node || (e.getCode() != KeyCode.ENTER && e.getCode() != KeyCode.SPACE)) return;
            e.consume();
            Event.fireEvent(node, new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                    false, false, false, false, false, false, false, true, false, true, new PickResult(node, 0, 0)));
        });
        return node;
    }

    /** The texts inside a node, in order, as one phrase. */
    static String shownText(Node node) {
        StringBuilder out = new StringBuilder();
        collect(node, out);
        return out.toString().strip();
    }

    private static void collect(Node node, StringBuilder out) {
        String text = node instanceof Labeled l ? l.getText() : node instanceof Text t ? t.getText() : null;
        if (text != null && !text.isBlank()) {
            if (out.length() > 0) out.append(", ");
            out.append(text.strip());
            return;
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                if (child.isVisible()) collect(child, out);
            }
        }
    }
}
