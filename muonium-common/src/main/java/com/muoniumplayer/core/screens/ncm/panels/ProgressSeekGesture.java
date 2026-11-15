package com.muoniumplayer.core.screens.ncm.panels;

/** A seek is a complete tap on the progress track, never an arbitrary mouse release. */
final class ProgressSeekGesture {
    private boolean previousDown;
    private boolean pressedInside;

    boolean sample(boolean down, boolean inside, boolean blocked, boolean dragged) {
        if (blocked) {
            cancel(down);
            return false;
        }
        if (down && !previousDown) pressedInside = inside;
        boolean tap = previousDown && !down && pressedInside && inside && !dragged;
        previousDown = down;
        if (!down) pressedInside = false;
        return tap;
    }

    void cancel(boolean down) {
        previousDown = down;
        pressedInside = false;
    }
}
