package com.muoniumplayer.core.ncm.spotify;

import com.muoniumplayer.core.MuoniumPlayerEventHandler;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class SpotifyMirroredLyricClockTest {
    private final LyricLine first = new LyricLine(0, "第一句");
    private final LyricLine second = new LyricLine(5000, "第二句");
    private Music music;

    @BeforeEach void prepare() throws Exception {
        SpotifyMusicService.Track track = new SpotifyMusicService.Track();
        track.id = "lyric-clock"; track.name = "测试歌曲"; track.durationMillis = 20000;
        music = Music.fromSpotifyTrack(track);
        CloudMusic.player = null;
        CloudMusic.currentlyPlaying = music;
        CloudMusic.lyrics.clear();
        CloudMusic.lyrics.add(first); CloudMusic.lyrics.add(second);
        CloudMusic.currentLyric = first;
        CloudMusic.haveNoWords = true;
        CloudMusic.lyricsTrackKey = music.getStableKey();
        setMirrorField("soloist", false);
        setMirrorField("mirroring", true);
    }

    @AfterEach void reset() throws Exception {
        setMirrorField("mirroring", false);
        SpotifyConnectService.recordPlayback(null);
        CloudMusic.currentlyPlaying = null;
        CloudMusic.currentLyric = null;
        CloudMusic.lyricsTrackKey = null;
        CloudMusic.lyrics.clear();
    }

    @Test void clientLoopAdvancesLyricsWithoutAnAudioPlayerOrOpenScreen() {
        publish(6000, true);
        MuoniumPlayerEventHandler.getInstance().onLoop();
        assertSame(second, CloudMusic.currentLyric,
                "外部播放没有解码线程，主线程循环仍须推进 HUD 当前句");
    }

    @Test void pausedSeekCanReturnToAnEarlierLine() {
        CloudMusic.currentLyric = second;
        publish(1000, false);
        MuoniumPlayerEventHandler.getInstance().onLoop();
        assertSame(first, CloudMusic.currentLyric);
    }

    @Test void oldTrackLyricsAreNotAdvancedWithNewTrackClock() {
        CloudMusic.lyricsTrackKey = "previous-track";
        publish(6000, true);
        MuoniumPlayerEventHandler.getInstance().onLoop();
        assertSame(first, CloudMusic.currentLyric);
    }

    private void publish(long position, boolean playing) {
        SpotifyMusicService.Track track = new SpotifyMusicService.Track();
        track.id = music.getSourceId(); track.durationMillis = 20000;
        SpotifyConnectService.recordPlayback(new SpotifyConnectService.PlaybackState(
                "test", "test", "Computer", 50, true, playing,
                position, System.currentTimeMillis(), track, 20000));
    }

    private static void setMirrorField(String name, boolean value) throws Exception {
        Field field = SpotifyConnectMirror.class.getDeclaredField(name);
        field.setAccessible(true); field.setBoolean(null, value);
    }
}
