package com.muoniumplayer.core.screens.ncm.panels;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressSeekGestureTest {
    @Test
    void seeksOnceForACompleteTapOnTheTrack() {
        ProgressSeekGesture gesture = new ProgressSeekGesture();
        assertFalse(gesture.sample(true, true, false, false));
        assertFalse(gesture.sample(true, true, false, false));
        assertTrue(gesture.sample(false, true, false, false));
        assertFalse(gesture.sample(false, true, false, false));
    }

    @Test
    void releasingOverTheTrackAfterPressingElsewhereNeverSeeks() {
        ProgressSeekGesture gesture = new ProgressSeekGesture();
        assertFalse(gesture.sample(true, false, false, false));
        assertFalse(gesture.sample(true, true, false, false));
        assertFalse(gesture.sample(false, true, false, false));
    }

    @Test
    void draggingOrReleasingOutsideDoesNotSeek() {
        ProgressSeekGesture gesture = new ProgressSeekGesture();
        gesture.sample(true, true, false, false);
        assertFalse(gesture.sample(false, true, false, true));
        gesture.sample(true, true, false, false);
        assertFalse(gesture.sample(false, false, false, false));
    }

    @Test
    void thePreviousGesturesDragFlagCannotCancelANewTap() {
        ProgressSeekGesture gesture = new ProgressSeekGesture();
        gesture.sample(true, true, false, false);
        assertFalse(gesture.sample(false, true, false, true));
        // TouchGestures keeps the previous release's drag result until the next release.
        assertFalse(gesture.sample(true, true, false, true));
        assertTrue(gesture.sample(false, true, false, false));
    }

    @Test
    void openingAModalCancelsTheEntirePress() {
        ProgressSeekGesture gesture = new ProgressSeekGesture();
        gesture.sample(true, true, false, false);
        assertFalse(gesture.sample(true, true, true, false));
        assertFalse(gesture.sample(false, true, false, false));
    }

    @Test
    void finishingPreparationWhilePressedDoesNotSeekTheNewTrack() {
        ProgressSeekGesture gesture = new ProgressSeekGesture();
        gesture.cancel(true);
        assertFalse(gesture.sample(false, true, false, false));
        gesture.sample(true, true, false, false);
        assertTrue(gesture.sample(false, true, false, false));
    }
}
