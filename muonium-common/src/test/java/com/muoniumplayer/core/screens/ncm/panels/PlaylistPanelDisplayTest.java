package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaylistPanelDisplayTest {
    @Test
    void neteaseCoverSizingPreservesTheExistingQueryAndFragment() {
        assertEquals("https://p1.music.126.net/a.jpg?token=hello%20world&param=256y256#cover",
                PlaylistPanel.imageRequestUrl("http://p1.music.126.net/a.jpg?token=hello%20world&param=32y32#cover",
                        MusicPlatform.NETEASE, 256));
        assertEquals("https://p1.music.126.net/a.jpg?param=32y32",
                PlaylistPanel.imageRequestUrl("https://p1.music.126.net/a.jpg", MusicPlatform.NETEASE, 32));
    }

    @Test
    void otherProvidersKeepTheirImageQueriesUnchanged() {
        String url = "https://other.example/image.jpg?signature=abc&size=300#cover";
        assertEquals(url, PlaylistPanel.imageRequestUrl(url, MusicPlatform.QISHUI, 256));
        assertEquals(url, PlaylistPanel.imageRequestUrl(url, MusicPlatform.QQ, 256));
        assertEquals("", PlaylistPanel.imageRequestUrl(null, MusicPlatform.NETEASE, 256));
    }

    @Test
    void renderingPlayingRowCannotRestartAFailedPlaylistRequest() throws Exception {
        String source = Files.readString(ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer",
                "core", "screens", "ncm", "panels", "PlaylistPanel.java")), StandardCharsets.UTF_8);
        source = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\\n]*", "");
        int start = source.indexOf("private int playingRowOffset()");
        int end = source.indexOf("private void autoScrollToPlayingIfNeeded()", start);
        assertTrue(start >= 0 && end > start);
        String row = source.substring(start, end);
        assertFalse(row.contains("playList.getMusics()"),
                "render-only state must not start a fresh network request after failure");
        assertTrue(row.contains("playList.musics"));
        assertTrue(source.contains("if (generation != this.buildGeneration) return;"),
                "old load callbacks cannot replace a rebuilt panel");
    }
}
