package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.spotify.SpotifyMusicService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SoloistQueueSelectionTest {
    @BeforeEach @AfterEach void reset() {
        CloudMusic.playList = new ArrayList<>();
        CloudMusic.curIdx = 0;
        CloudMusic.currentlyPlaying = null;
        CloudMusic.playNextLedger.clear();
        CloudMusic.playMode = CloudMusic.PlayMode.Sequential;
    }

    @Test void ordinaryNextPreviousAndSequentialEndKeepPlaylistOrder() {
        var first = song("A"); var second = song("B");
        CloudMusic.playList.addAll(List.of(first, second));
        assertSame(second, CloudMusic.selectSoloistQueueStep(1, false));
        assertNull(CloudMusic.selectSoloistQueueStep(1, true));
        assertEquals(1, CloudMusic.curIdx);
        assertSame(first, CloudMusic.selectSoloistQueueStep(-1, false));
    }

    @Test void singleRepeatConsumesUserQueueFirstButManualNextStillChangesTrack() {
        var first = song("A"); var second = song("B"); var queued = song("C");
        CloudMusic.playList.addAll(List.of(first, queued, second));
        CloudMusic.playMode = CloudMusic.PlayMode.LoopSingle;
        assertSame(first, CloudMusic.selectSoloistQueueStep(1, true));
        CloudMusic.playNextLedger.remember(queued);
        assertSame(queued, CloudMusic.selectSoloistQueueStep(1, true));
        CloudMusic.noteQueuedTrackStarted(queued);
        assertSame(queued, CloudMusic.selectSoloistQueueStep(1, true));
        assertSame(second, CloudMusic.selectSoloistQueueStep(1, false));
    }

    @Test void playlistLoopWrapsAndDropsOldPendingLedger() {
        var first = song("A"); var second = song("B");
        CloudMusic.playList.addAll(List.of(first, second));
        CloudMusic.curIdx = 1;
        CloudMusic.playMode = CloudMusic.PlayMode.LoopInList;
        CloudMusic.playNextLedger.remember(second);
        assertSame(first, CloudMusic.selectSoloistQueueStep(1, true));
        assertEquals(0, CloudMusic.playNextLedger.tracked());
    }

    @Test void randomCycleStillContainsOnlyTheOriginalPlaylistSongs() {
        var songs = List.of(song("A"), song("B"), song("C"));
        CloudMusic.playList.addAll(songs);
        CloudMusic.curIdx = 2;
        CloudMusic.playMode = CloudMusic.PlayMode.Random;
        assertNotNull(CloudMusic.selectSoloistQueueStep(1, true));
        assertEquals(0, CloudMusic.curIdx);
        assertEquals(new java.util.HashSet<>(songs), new java.util.HashSet<>(CloudMusic.playList));
    }

    private static Music song(String id) {
        SpotifyMusicService.Track track = new SpotifyMusicService.Track();
        track.id = id; track.name = id;
        return Music.fromSpotifyTrack(track);
    }
}
