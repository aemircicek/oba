package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.scene.AccessibleRole;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;

/** These nodes are never shown, so no JavaFX window is needed. */
class A11yTest {
    private static KeyEvent press(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    @Test
    void aLoneSymbolIsNotAName() {
        assertFalse(A11y.sayable(null));
        assertFalse(A11y.sayable(""));
        assertFalse(A11y.sayable("✕"));
        assertFalse(A11y.sayable("+"));
        assertFalse(A11y.sayable("‹ ›"));
        assertTrue(A11y.sayable("Add"));
        assertTrue(A11y.sayable("Üye ekle"));
    }

    @Test
    void enterAndSpaceClickWhatOnlyTheMouseCouldReach() {
        HBox row = new HBox();
        int[] clicks = {0};
        row.setOnMouseClicked(e -> clicks[0]++);
        A11y.clickable(row, "Open the chat with efe");
        assertTrue(row.isFocusTraversable());
        assertEquals(AccessibleRole.BUTTON, row.getAccessibleRole());
        assertEquals("Open the chat with efe", row.getAccessibleText());
        javafx.event.Event.fireEvent(row, press(KeyCode.ENTER));
        javafx.event.Event.fireEvent(row, press(KeyCode.SPACE));
        javafx.event.Event.fireEvent(row, press(KeyCode.A));
        assertEquals(2, clicks[0]);
    }

    @Test
    void makingSomethingClickableTwiceClicksOnce() {
        HBox row = new HBox();
        int[] clicks = {0};
        row.setOnMouseClicked(e -> clicks[0]++);
        A11y.clickable(row, null);
        A11y.clickable(row, null);
        javafx.event.Event.fireEvent(row, press(KeyCode.ENTER));
        assertEquals(1, clicks[0]);
    }

    @Test
    void aKeyMeantForSomethingInsideIsLeftAlone() {
        HBox row = new HBox();
        HBox inner = new HBox();
        row.getChildren().add(inner);
        int[] clicks = {0};
        row.setOnMouseClicked(e -> clicks[0]++);
        A11y.clickable(row, null);
        javafx.event.Event.fireEvent(inner, press(KeyCode.ENTER)); // for example a button inside the row
        assertEquals(0, clicks[0]);
    }

    @Test
    void aLinkIsAnnouncedAsALink() {
        assertEquals(AccessibleRole.HYPERLINK, A11y.clickable(new Text("https://example.org"), null).getAccessibleRole());
    }

    @Test
    void theNameOfARowIsWhatItShows() {
        Text hidden = new Text("3 unread");
        hidden.setVisible(false);
        HBox row = new HBox(new Text("efe"), new VBox(new Text("online"), new Text(" ")), hidden);
        assertEquals("efe, online", A11y.shownText(row));
    }
}
