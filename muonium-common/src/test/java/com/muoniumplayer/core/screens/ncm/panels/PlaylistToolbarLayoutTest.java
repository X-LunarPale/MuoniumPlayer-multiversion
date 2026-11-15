package com.muoniumplayer.core.screens.ncm.panels;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaylistToolbarLayoutTest {
    @Test
    void everyActionCombinationStaysInsideThePanelWithoutOverlapping() {
        for (double width : new double[]{180, 240, 280, 320, 375, 414, 640, 1024}) {
            for (int mask = 0; mask < 32; mask++) {
                PlaylistToolbarLayout layout = new PlaylistToolbarLayout();
                layout.reset(width, 132.0);
                List<PlaylistToolbarLayout.Slot> slots = new ArrayList<>();
                slots.add(layout.next(68.0));
                for (int action = 0; action < 5; action++) {
                    if ((mask & (1 << action)) != 0) slots.add(layout.next(68.0));
                }
                slots.add(layout.next(116.0));
                slots.add(layout.next(28.0));
                for (int i = 0; i < slots.size(); i++) {
                    PlaylistToolbarLayout.Slot slot = slots.get(i);
                    assertTrue(slot.x() >= 0.0 && slot.x() + slot.width() <= width,
                            "action escaped panel width " + width);
                    assertTrue(slot.y() + slot.height() <= layout.bottom(),
                            "playlist viewport must begin below every toolbar row");
                    for (int j = 0; j < i; j++) {
                        PlaylistToolbarLayout.Slot prior = slots.get(j);
                        boolean separated = slot.x() >= prior.x() + prior.width()
                                || prior.x() >= slot.x() + slot.width()
                                || slot.y() >= prior.y() + prior.height()
                                || prior.y() >= slot.y() + slot.height();
                        assertTrue(separated, "actions overlap at panel width " + width);
                    }
                }
            }
        }
    }

    @Test
    void disappearingActionReleasesItsSlotOnTheNextLayout() {
        PlaylistToolbarLayout layout = new PlaylistToolbarLayout();
        layout.reset(375.0, 132.0);
        layout.next(68.0);
        layout.next(68.0);
        PlaylistToolbarLayout.Slot afterRoomButton = layout.next(68.0);
        layout.reset(375.0, 132.0);
        layout.next(68.0);
        PlaylistToolbarLayout.Slot withoutRoomButton = layout.next(68.0);
        assertEquals(afterRoomButton.x() - 74.0, withoutRoomButton.x(), 1e-9);
        assertEquals(afterRoomButton.y(), withoutRoomButton.y(), 1e-9);
    }

    @Test
    void anOversizedActionFitsEvenInATransitioningPanel() {
        PlaylistToolbarLayout layout = new PlaylistToolbarLayout();
        layout.reset(40.0, 0.0);
        PlaylistToolbarLayout.Slot slot = layout.next(116.0);
        assertEquals(32.0, slot.width(), 1e-9);
        assertTrue(slot.x() + slot.width() <= 40.0);
        layout.reset(0.0, 0.0);
        assertEquals(0.0, layout.next(116.0).width());
    }
}
