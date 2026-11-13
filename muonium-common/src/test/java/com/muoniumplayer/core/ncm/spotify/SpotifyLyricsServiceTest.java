package com.muoniumplayer.core.ncm.spotify;

import com.muoniumplayer.core.screens.ncm.LyricLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotifyLyricsServiceTest {

    @Test
    @DisplayName("Spotify 行级时间轴解析出歌词与逐行时长")
    void parsesLineSyncedLyrics() {
        List<LyricLine> lines = SpotifyLyricsService.parse("{\"lyrics\":{\"syncType\":\"LINE_SYNCED\","
                + "\"lines\":[{\"startTimeMs\":\"1200\",\"words\":\"first line\"},"
                + "{\"startTimeMs\":\"2450\",\"words\":\"second line\"}]}}");

        assertEquals(2, lines.size());
        assertEquals(1200L, lines.get(0).timestamp);
        assertEquals(1250L, lines.get(0).duration);
        assertEquals("first line", lines.get(0).lyric);
        assertEquals(2450L, lines.get(1).timestamp);
    }

    @Test
    @DisplayName("Spotify 无歌词或响应损坏时返回空结果供其他歌词源继续回退")
    void missingLyricsAreAnEmptyFallbackResult() {
        assertTrue(SpotifyLyricsService.parse("{\"lyrics\":{\"syncType\":\"NONE\",\"lines\":[]}}").isEmpty());
        assertTrue(SpotifyLyricsService.parse("{\"lyrics\":{\"lines\":[]}}").isEmpty());
        assertTrue(SpotifyLyricsService.parse("not-json").isEmpty());
        assertTrue(SpotifyLyricsService.parse("{\"lyrics\":{\"syncType\":\"UNSYNCED\",\"lines\":[{\"words\":\"untimed\",\"startTimeMs\":\"0\"}]}}").isEmpty());
    }
}
