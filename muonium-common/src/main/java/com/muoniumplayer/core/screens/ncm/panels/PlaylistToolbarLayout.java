package com.muoniumplayer.core.screens.ncm.panels;

/** A wrapping toolbar whose rows are also the playlist viewport's top inset. */
final class PlaylistToolbarLayout {
    static final double HEIGHT = 22.0;
    private static final double GAP = 6.0;
    private double left;
    private double right;
    private double x;
    private double y;

    void reset(double panelWidth, double top) {
        left = Math.min(24.0, Math.max(0.0, panelWidth * .1));
        right = Math.max(left, panelWidth - left);
        x = left;
        y = top;
    }

    Slot next(double preferredWidth) {
        double width = Math.max(0.0, Math.min(preferredWidth, right - left));
        if (x > left && x + width > right) {
            x = left;
            y += HEIGHT + GAP;
        }
        Slot slot = new Slot(x, y, width, HEIGHT);
        x += width + GAP;
        return slot;
    }

    double bottom() {
        return y + HEIGHT;
    }

    record Slot(double x, double y, double width, double height) {}
}
