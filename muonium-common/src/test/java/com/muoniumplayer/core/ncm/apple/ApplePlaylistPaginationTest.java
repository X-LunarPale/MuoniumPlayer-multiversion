package com.muoniumplayer.core.ncm.apple;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApplePlaylistPaginationTest {
    private final List<AppleApiClient.Request> requests = new ArrayList<>();

    @BeforeEach void setUp() {
        AppleConfigStore.putForTest("test-media-token", "cn", false, false);
        AppleTokenManager.putForTest("test-web-token", 0L);
        AppleApiClient.markAccountValid();
    }

    @AfterEach void tearDown() {
        AppleApiClient.resetTransportForTest();
        AppleApiClient.markAccountValid();
        AppleTokenManager.clearForTest();
        AppleConfigStore.clearForTest();
    }

    @ParameterizedTest(name = "{0}, card count {1}: loads all 235 songs")
    @CsvSource({
            "library-songs, 0", "library-songs, 37", "library-songs, 100",
            "p.123, 0", "p.123, 37", "p.123, 100",
            "pl.test, 0", "pl.test, 37", "pl.test, 100"
    })
    void missingOrStaleCardCountDoesNotTruncatePlaylist(String playlistId, int cardCount) throws Exception {
        boolean catalog = playlistId.startsWith("pl.");
        String tracksPath = catalog ? "/v1/catalog/cn/playlists/pl.test/tracks"
                : playlistId.equals("library-songs") ? "/v1/me/library/songs"
                : "/v1/me/library/playlists/p.123/tracks";
        AppleApiClient.setTransportForTest(request -> {
            requests.add(request);
            URI uri = URI.create(request.url);
            JsonObject payload;
            if (catalog && uri.getPath().equals("/v1/catalog/cn/playlists/pl.test")) {
                JsonObject relationships = new JsonObject();
                relationships.add("tracks", page(tracksPath, 0));
                JsonObject playlist = new JsonObject();
                playlist.addProperty("id", playlistId);
                playlist.add("relationships", relationships);
                JsonArray data = new JsonArray();
                data.add(playlist);
                payload = new JsonObject();
                payload.add("data", data);
            } else {
                assertEquals(tracksPath, uri.getPath());
                int offset = 0;
                for (String part : uri.getQuery().split("&")) {
                    if (part.startsWith("offset=")) offset = Integer.parseInt(part.substring(7));
                }
                payload = page(tracksPath, offset);
            }
            return new AppleApiClient.Response(200, -1, payload.toString());
        });
        AppleMusicService.Playlist source = new AppleMusicService.Playlist();
        source.id = playlistId;
        source.name = "Pagination fixture";
        source.trackCount = cardCount;
        PlayList card = AppleMusicService.toPlayListCard(source);
        Method load = PlayList.class.getDeclaredMethod("queryMusics");
        load.setAccessible(true);
        load.invoke(card);

        assertTrue(card.musicsLoaded);
        assertEquals(235, card.musics.size(), "卡片曲数不能成为整个歌单的加载上限");
        for (int i = 0; i < 235; i++) {
            Music music = card.musics.get(i);
            assertEquals("Song " + i, music.getName());
            assertEquals(MusicPlatform.APPLE, music.getSource());
        }
        assertEquals(3, requests.size());
        for (AppleApiClient.Request request : requests) {
            assertEquals(!catalog, request.headers.containsKey("Media-User-Token"));
        }
    }

    private static JsonObject page(String tracksPath, int offset) {
        JsonArray data = new JsonArray();
        for (int i = offset; i < Math.min(offset + 100, 235); i++) {
            JsonObject attributes = new JsonObject();
            attributes.addProperty("name", "Song " + i);
            attributes.addProperty("artistName", "Test artist");
            attributes.addProperty("durationInMillis", 180000);
            JsonObject song = new JsonObject();
            song.addProperty("id", String.valueOf(100000 + i));
            song.addProperty("type", "songs");
            song.add("attributes", attributes);
            data.add(song);
        }
        JsonObject page = new JsonObject();
        page.add("data", data);
        if (offset + 100 < 235) page.addProperty("next", tracksPath + "?limit=100&offset=" + (offset + 100));
        return page;
    }
}
