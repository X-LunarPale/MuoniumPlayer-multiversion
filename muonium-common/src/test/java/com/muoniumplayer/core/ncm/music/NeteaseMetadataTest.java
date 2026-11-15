package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.ncm.music.dto.Album;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeteaseMetadataTest {
    private static final Gson GSON = new Gson();

    @Test
    void modernAndLegacyNeteasePayloadsKeepTheSameDisplayMetadata() {
        Music modern = GSON.fromJson("""
                {"id":123,"name":"Song","ar":[{"id":1,"name":"Artist"}],
                 "al":{"id":2,"name":"Album","picUrl":"https://p1.music.126.net/cover.jpg"},
                 "dt":182345}
                """, Music.class);
        Music legacy = GSON.fromJson("""
                {"id":123,"name":"Song","artists":[{"id":1,"name":"Artist"}],
                 "album":{"id":2,"name":"Album","picUrl":"https://p1.music.126.net/cover.jpg"},
                 "duration":182345}
                """, Music.class);

        assertEquals(modern.getArtistsName(), legacy.getArtistsName());
        assertEquals(modern.getAlbum().getName(), legacy.getAlbum().getName());
        assertEquals(modern.getDuration(), legacy.getDuration());
        assertEquals(modern.getCoverUrl(128), legacy.getCoverUrl(128));
    }

    @Test
    void coverResizingPreservesExistingQueryAndFragment() {
        Music music = song("https://p1.music.126.net/cover.jpg?token=abc#artwork");
        assertEquals("https://p1.music.126.net/cover.jpg?token=abc&param=128y128#artwork",
                music.getCoverUrl(128));
    }

    @Test
    void coverResizingReplacesAnExistingSizeParameter() {
        Music music = song("https://p1.music.126.net/cover.jpg?param=40y40&token=abc");
        assertEquals("https://p1.music.126.net/cover.jpg?token=abc&param=256y256",
                music.getCoverUrl(256));
    }

    @Test
    void plainAndForeignCoverUrlsKeepTheirExpectedShape() {
        Music plain = song("https://p1.music.126.net/cover.jpg");
        assertEquals("https://p1.music.126.net/cover.jpg?param=128y128", plain.getCoverUrl(128));
        plain.setForeignCover(true);
        assertEquals("https://p1.music.126.net/cover.jpg", plain.getCoverUrl(128));
    }

    @Test
    void decodedDurationIsPassedToTheSourceTrackWhenMetadataHasNoLength() {
        Music music = song("");
        assertEquals(0L, music.getDuration());
        assertEquals(0L, music.toMuTrack().getDurationMs());
        music.applyDecodedDuration(182345L);
        assertEquals(182345L, music.getDuration());
        assertEquals(182345L, music.toMuTrack().getDurationMs());
    }

    @Test
    void decodedDurationDoesNotReplaceKnownMetadataLength() {
        Music music = GSON.fromJson("{\"id\":123,\"name\":\"Song\",\"dt\":182345}", Music.class);
        music.toMuTrack();

        music.applyDecodedDuration(30000L);

        assertEquals(182345L, music.getDuration());
        assertEquals(182345L, music.toMuTrack().getDurationMs());
    }

    @Test
    void queueCopiesDoNotInheritDecodedDurationThroughTheSourceTrackCache() {
        Music music = song("");
        music.applyDecodedDuration(182345L);
        music.toMuTrack();

        Music queued = music.queueIdentityCopy();

        assertEquals(0L, queued.getDuration());
        assertEquals(0L, queued.toMuTrack().getDurationMs());
    }

    @Test
    void emptySearchResultsCompleteImmediatelyInsteadOfLoadingForever() {
        PlayList playlist = new PlayList(1L, "Search", "", 0, 0L, null, "", 0L);
        playlist.setSearchMode(true);
        AtomicReference<List<Music>> result = new AtomicReference<>();

        playlist.loadMusicsWithCallback(result::set);

        assertNotNull(result.get());
        assertTrue(result.get().isEmpty());
        assertTrue(playlist.musicsLoaded);
        assertEquals("", playlist.getMusicLoadError());
    }

    @Test
    void queueCopiesKeepNeteaseEntitlementsAndAvailableQuality() {
        Music original = GSON.fromJson("""
                {"id":123,"name":"Song","fee":4,"payed":1}
                """, Music.class);
        original.applyNeteaseMetadata(null, JsonParser.parseString("""
                {"id":123,"maxBrLevel":"hires"}
                """).getAsJsonObject());

        Music queued = original.queueIdentityCopy();

        assertEquals(4, queued.getFee());
        assertEquals(1, queued.getPayed());
        assertTrue(queued.isDigitalAlbumTrack());
        assertTrue(queued.hasVipRestriction());
        assertEquals("Hi-Res", queued.getHighestQualityLabel());
    }

    private static Music song(String coverUrl) {
        return new Music("Song", "Song", "", 123L, Collections.emptyList(), Collections.emptyList(),
                new Album(2L, "Album", coverUrl, Collections.emptyList()),
                0L, 0L, 0L, Collections.emptyList());
    }
}
