package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.screens.ncm.LyricLine;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SpotifyLyricCachePriorityTest {
    private static List<LyricLine> ordinary() { return List.of(new LyricLine(1000, "平台普通歌词")); }

    @Test void automaticSpotifyLineFallbackMustRetryWordSourcesAfterACachedFailure() {
        assertFalse(LyricLoadService.canReuseCachedLyrics(true, LyricSourceMemory.AUTO, false, ordinary()));
    }

    @Test void explicitlyChosenSourceAndManualCandidateRetainTheirCache() {
        assertTrue(LyricLoadService.canReuseCachedLyrics(true, LyricSourceMemory.PLATFORM, false, ordinary()));
        assertTrue(LyricLoadService.canReuseCachedLyrics(true, LyricSourceMemory.AUTO, true, ordinary()));
        assertTrue(LyricLoadService.canReuseCachedLyrics(false, LyricSourceMemory.AUTO, false, ordinary()));
    }

    @Test void realWordTimingRetainsTheFastCachePath() {
        LyricLine wordLine = new LyricLine(1000, "逐字");
        wordLine.words.add(new LyricLine.Word("逐", 1000, 1000));
        wordLine.words.add(new LyricLine.Word("字", 2000, 1000));
        assertTrue(LyricLoadService.canReuseCachedLyrics(true, LyricSourceMemory.AUTO, false, List.of(wordLine)));
    }

    @Test void unavailableSourcesPreserveExistingReadableFallback() {
        var cached = ordinary();
        var empty = LyricLoadService.LyricLoadResult.empty();
        var readable = empty.orCached(cached);
        assertSame(cached, readable.lines);
        assertSame(readable, readable.orCached(List.of(new LyricLine(2000, "其他缓存"))));
        assertSame(empty, empty.orCached(List.of()));
    }
}
