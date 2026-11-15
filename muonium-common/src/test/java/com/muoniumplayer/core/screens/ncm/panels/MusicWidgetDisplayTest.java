package com.muoniumplayer.core.screens.ncm.panels;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicWidgetDisplayTest {
    @Test
    void unknownDurationIsDistinctFromAZeroSecondRecording() {
        assertEquals("--:--", MusicWidget.formatDuration(0L));
        assertEquals("--:--", MusicWidget.formatDuration(-1L));
        assertEquals("00:00", MusicWidget.formatDuration(500L));
        assertEquals("00:01", MusicWidget.formatDuration(1_000L));
        assertEquals("59:59", MusicWidget.formatDuration(3_599_000L));
        assertEquals("01:00:00", MusicWidget.formatDuration(3_600_000L));
    }

    @Test
    void durationDigitsDoNotChangeWithSystemLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"));
            assertEquals("04:09", MusicWidget.formatDuration(249_000L));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void optionalBadgesCannotTakeTheMinimumTitleSpace() {
        assertFalse(MusicWidget.canShowBadges(119.0, 40.0));
        assertTrue(MusicWidget.canShowBadges(120.0, 40.0));
        assertFalse(MusicWidget.canShowBadges(0.0, 40.0));
        assertFalse(MusicWidget.canShowBadges(120.0, 0.0));
    }
}
