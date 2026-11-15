package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayQueueDisplayTest {
    @Test
    void drawerHasRoomForItsHeaderWithoutEscapingNarrowPanels() {
        for (double panelWidth : new double[]{0, 120, 180, 240, 320, 375, 640, 1024}) {
            double drawerWidth = PlayQueuePanel.drawerWidthFor(panelWidth);
            assertTrue(drawerWidth >= 0.0);
            assertTrue(drawerWidth <= Math.max(0.0, panelWidth - 16.0));
            if (panelWidth >= 296.0) assertTrue(drawerWidth >= 280.0);
        }
    }

    @Test
    void emptyQueueControlsKeepTheirCallbacksAliveForLaterAdditions() throws Exception {
        String source = Files.readString(ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer",
                "core", "screens", "ncm", "panels", "PlayQueuePanel.java")), StandardCharsets.UTF_8);
        source = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\\n]*", "");
        for (String control : new String[]{"flowSort", "clearAll"}) {
            assertFalse(source.contains(control + ".setHidden("),
                    "hidden controls stop rendering their own callbacks and cannot reappear");
            assertTrue(source.contains(control + ".setAlpha(visible ? 1f : 0f).setClickable(visible)"),
                    "visibility and hit testing must update together on every frame");
        }
    }
}
