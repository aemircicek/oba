package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RoomVideoGridTest {
    @Test
    void gridGrowsWithThePeopleSharing() {
        assertEquals(1, RoomVideoGrid.columns(1));
        assertEquals(2, RoomVideoGrid.columns(2));
        assertEquals(2, RoomVideoGrid.columns(4));
        assertEquals(3, RoomVideoGrid.columns(5));
        assertEquals(3, RoomVideoGrid.columns(9));
        assertEquals(4, RoomVideoGrid.columns(12));
    }
}
