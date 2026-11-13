package com.muoniumplayer.core.ncm.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SpotifyDesktopCatalogTest {
    private static final String ID = "0123456789abcdefghijkl";
    private final List<SpotifyWebApiClient.Request> requests = new ArrayList<>();

    @BeforeEach void login() {
        SpotifyConfigStore.putForTest("developer-client", "US");
        SpotifyAuthStore.putForTest("access", "refresh", System.currentTimeMillis() + 3600000);
        SpotifyAuthStore.saveAuthorization(JsonParser.parseString("{\"access_token\":\"access\",\"refresh_token\":\"refresh\",\"expires_in\":3600}").getAsJsonObject(), SpotifyConfigStore.BROWSER_CLIENT_ID);
    }
    @AfterEach void reset() {
        SpotifyWebApiClient.resetTransportForTest();
        SpotifyAuthStore.clearForTest();
        SpotifyConfigStore.clearForTest();
    }
    private void respond(java.util.function.Function<JsonObject, String> body) {
        SpotifyWebApiClient.setTransportForTest(request -> {
            requests.add(request);
            assertEquals("https://api-partner.spotify.com/pathfinder/v2/query", request.url);
            assertEquals("POST", request.method);
            assertEquals("Bearer access", request.headers.get("Authorization"));
            JsonObject query = JsonParser.parseString(request.jsonBody).getAsJsonObject();
            assertEquals(64, query.getAsJsonObject("extensions").getAsJsonObject("persistedQuery").get("sha256Hash").getAsString().length());
            return new SpotifyWebApiClient.Response(200, -1, body.apply(query));
        });
    }
    private static String track() {
        return "{\"__typename\":\"Track\",\"uri\":\"spotify:track:" + ID + "\",\"name\":\"没有歌词也保留\",\"artists\":{\"items\":[{\"profile\":{\"name\":\"Artist\"}}]},\"trackDuration\":{\"totalMilliseconds\":123000},\"albumOfTrack\":{\"name\":\"Album\",\"coverArt\":{\"sources\":[{\"url\":\"small\",\"width\":64},{\"url\":\"large\",\"width\":640}]}}}";
    }
    @Test void desktopQueryAndLyricsIdentifyCompatibleHttpClient() throws Exception {
        SpotifyWebApiClient.setTransportForTest(request -> {
            assertEquals("Mozilla/5.0 MuoniumPlayer", request.headers.get("User-Agent"));
            return new SpotifyWebApiClient.Response(200, -1, "{\"data\":{}}");
        });
        SpotifyWebApiClient.desktopQuery(new JsonObject());
        SpotifyWebApiClient.lyrics(ID);
    }
    @Test void libraryFailureIncludesHttpStatusWithoutClearingAccount() {
        SpotifyWebApiClient.setTransportForTest(request ->
                new SpotifyWebApiClient.Response(403, -1, "{\"error\":{\"message\":\"Client/request not allowed\"}}"));
        SpotifyMusicService.refreshUserPlaylists();
        assertTrue(SpotifyMusicService.getUserPlaylistsError().contains("403"));
        assertEquals("access", SpotifyAuthStore.snapshot().accessToken);
    }
    @Test void browserLibraryIncludesPrivatePlaylistAndLikedSongs() {
        respond(query -> switch(query.get("operationName").getAsString()) {
            case "fetchLibraryTracks" -> "{\"data\":{\"me\":{\"library\":{\"tracks\":{\"totalCount\":7,\"items\":[]}}}}}";
            case "libraryV3" -> {
                assertTrue(query.getAsJsonObject("variables").get("flatten").getAsBoolean());
                yield "{\"data\":{\"me\":{\"libraryV3\":{\"totalCount\":1,\"items\":[{\"item\":{\"data\":{\"__typename\":\"Playlist\",\"uri\":\"spotify:playlist:" + ID + "\",\"name\":\"Private\",\"content\":{\"totalCount\":9},\"ownerV2\":{\"data\":{\"name\":\"Me\"}}}}}]}}}}";
            }
            default -> throw new AssertionError(query);
        });
        List<SpotifyMusicService.Playlist> cards = SpotifyMusicService.userPlaylists(50);
        assertEquals(2, cards.size());
        assertEquals("liked", cards.get(0).id);
        assertEquals(7, cards.get(0).trackCount);
        assertEquals(ID, cards.get(1).id);
        assertEquals("Me", cards.get(1).owner);
    }
    @Test void skippedPodcastRowsAdvanceRawOffsetAndDoNotHideSongsWithoutLyrics() {
        respond(query -> {
            assertEquals("fetchPlaylist", query.get("operationName").getAsString());
            int offset = query.getAsJsonObject("variables").get("offset").getAsInt();
            assertTrue(offset == 0 || offset == 1);
            String row = offset == 0 ? "{\"__typename\":\"Episode\"}" : track();
            return "{\"data\":{\"playlistV2\":{\"content\":{\"totalCount\":2,\"items\":[{\"itemV2\":{\"data\":" + row + "}}]}}}}";
        });
        List<SpotifyMusicService.Track> songs = SpotifyMusicService.playlistTracks(ID, 20);
        assertEquals(2, requests.size());
        assertEquals(1, songs.size());
        assertEquals("没有歌词也保留", songs.get(0).name);
        assertEquals("large", songs.get(0).coverUrl);
        assertEquals(123000, songs.get(0).durationMillis);
    }
    @Test void playlistWithMissingOrStaleCardCountLoadsBeyondOneHundredRows() throws Exception {
        for (int cardCount : new int[]{0, 10}) {
            requests.clear();
            respond(query -> {
                assertEquals("fetchPlaylist", query.get("operationName").getAsString());
                int offset = query.getAsJsonObject("variables").get("offset").getAsInt();
                int limit = query.getAsJsonObject("variables").get("limit").getAsInt();
                StringBuilder rows = new StringBuilder();
                for (int i = offset; i < Math.min(110, offset + limit); i++) {
                    if (rows.length() > 0) rows.append(',');
                    rows.append("{\"itemV2\":{\"data\":").append(track()).append("}}");
                }
                return "{\"data\":{\"playlistV2\":{\"content\":{\"totalCount\":110,\"items\":[" + rows + "]}}}}";
            });
            SpotifyMusicService.Playlist playlist = new SpotifyMusicService.Playlist();
            playlist.id = ID;
            playlist.trackCount = cardCount;
            var card = SpotifyMusicService.toPlayListCard(playlist);
            var query = card.getClass().getDeclaredMethod("queryMusics");
            query.setAccessible(true);
            query.invoke(card);
            assertEquals(110, card.musics.size(), "Card count is a hint, not a playlist limit");
            assertEquals(2, requests.size());
        }
    }
    @Test void playlistKeepsPlayableEpisodeAndItsOriginalUriWithoutLyrics() {
        respond(query -> "{\"data\":{\"playlistV2\":{\"content\":{\"totalCount\":1,\"items\":[{\"itemV2\":{\"data\":{"
                + "\"__typename\":\"Episode\",\"uri\":\"spotify:episode:" + ID + "\",\"name\":\"我們倆\","
                + "\"description\":\"我們倆-郭頂 感謝以下贊助我的聽眾\",\"episodeDuration\":{\"totalMilliseconds\":193908},"
                + "\"coverArt\":{\"sources\":[{\"url\":\"cover\",\"width\":640}]},"
                + "\"podcastV2\":{\"data\":{\"name\":\"Music uploads\",\"publisher\":{\"name\":\"Uploader\"}}}"
                + "}}}]}}}}");
        var songs = SpotifyMusicService.playlistTracks(ID, 20);
        assertEquals(1, songs.size(), "A playable episode must not disappear from a music playlist");
        assertEquals("episode:" + ID, songs.get(0).id);
        assertEquals("我們倆", songs.get(0).name);
        assertEquals("郭頂", songs.get(0).artist);
        assertEquals(193908, songs.get(0).durationMillis);
        var music = com.muoniumplayer.core.ncm.music.dto.Music.fromSpotifyTrack(songs.get(0));
        assertNotNull(music);
        assertEquals("episode:" + ID, music.getSourceId());
    }
    @Test void likedTrackCanUseUriBesideMetadata() {
        respond(query -> "{\"data\":{\"me\":{\"library\":{\"tracks\":{\"totalCount\":1,\"items\":[{\"track\":{\"_uri\":\"spotify:track:" + ID + "\",\"data\":{\"name\":\"Liked\"}}}]}}}}}");
        assertEquals(ID, SpotifyMusicService.playlistTracks("liked", 20).get(0).id);
    }
    @Test void desktopSearchUsesSessionIdentityEvenWhenNextLoginModeIsDeveloper() {
        respond(query -> {
            assertEquals("searchTracks", query.get("operationName").getAsString());
            return "{\"data\":{\"searchV2\":{\"tracksV2\":{\"totalCount\":1,\"items\":[{\"item\":{\"data\":" + track() + "}}]}}}}";
        });
        assertEquals(1, SpotifyMusicService.search("test", 10).size());
    }
    @Test void failedRefreshPreservesPreviousLibrarySnapshot() {
        respond(query -> query.get("operationName").getAsString().equals("fetchLibraryTracks")
                ? "{\"data\":{\"me\":{\"library\":{\"tracks\":{\"totalCount\":0,\"items\":[]}}}}}"
                : "{\"data\":{\"me\":{\"libraryV3\":{\"totalCount\":0,\"items\":[]}}}}");
        SpotifyMusicService.refreshUserPlaylists();
        assertTrue(SpotifyMusicService.areUserPlaylistsLoaded());
        int before = SpotifyMusicService.getUserPlaylistsSnapshot().size();
        respond(query -> "{\"errors\":[{\"message\":\"denied\"}],\"data\":null}");
        SpotifyMusicService.refreshUserPlaylists();
        assertEquals(before, SpotifyMusicService.getUserPlaylistsSnapshot().size());
    }
    @Test void officialLyricsUseAuthenticatedPipeline() {
        SpotifyWebApiClient.setTransportForTest(request -> {
            requests.add(request);
            assertTrue(request.url.startsWith("https://spclient.wg.spotify.com/color-lyrics/v2/track/" + ID));
            assertEquals("Bearer access", request.headers.get("Authorization"));
            if (!"WebPlayer".equals(request.headers.get("app-platform"))) {
                return new SpotifyWebApiClient.Response(400, -1, "{}");
            }
            return new SpotifyWebApiClient.Response(200, -1, "{\"lyrics\":{\"syncType\":\"LINE_SYNCED\",\"lines\":[{\"startTimeMs\":\"100\",\"words\":\"Line\"}]}}");
        });
        assertEquals(1, SpotifyLyricsService.resolve(ID).size());
        assertEquals(1, requests.size());
    }
    @Test void lyricPlatformHeaderSurvivesRetryAndStaysOffCatalogRequests() throws Exception {
        SpotifyWebApiClient.setTransportForTest(request -> {
            requests.add(request);
            if (request.url.contains("/color-lyrics/")) {
                assertEquals("WebPlayer", request.headers.get("app-platform"));
                if (requests.size() == 1) return new SpotifyWebApiClient.Response(503, -1, "{}");
                return new SpotifyWebApiClient.Response(200, -1, "{\"lyrics\":{\"syncType\":\"LINE_SYNCED\",\"lines\":[{\"startTimeMs\":\"100\",\"words\":\"Line\"}]}}");
            }
            assertFalse(request.headers.containsKey("app-platform"));
            return new SpotifyWebApiClient.Response(200, -1, "{\"data\":{}}");
        });
        assertEquals(1, SpotifyLyricsService.resolve(ID).size());
        SpotifyWebApiClient.desktopQuery(new JsonObject());
        assertEquals(3, requests.size());
    }
    @Test void newAuthorizationInvalidatesLoadedLibrary() {
        respond(query -> query.get("operationName").getAsString().equals("fetchLibraryTracks")
                ? "{\"data\":{\"me\":{\"library\":{\"tracks\":{\"totalCount\":0,\"items\":[]}}}}}"
                : "{\"data\":{\"me\":{\"libraryV3\":{\"totalCount\":0,\"items\":[]}}}}");
        SpotifyMusicService.refreshUserPlaylists();
        assertTrue(SpotifyMusicService.areUserPlaylistsLoaded());
        SpotifyAuthStore.saveAuthorization(JsonParser.parseString("{\"access_token\":\"other\",\"expires_in\":3600}").getAsJsonObject(), SpotifyConfigStore.BROWSER_CLIENT_ID);
        assertTrue(SpotifyMusicService.getUserPlaylistsSnapshot().isEmpty());
        assertFalse(SpotifyMusicService.areUserPlaylistsLoaded());
    }
    @Test void inFlightLibraryCannotPublishAfterAccountChanges() {
        respond(query -> {
            if (query.get("operationName").getAsString().equals("fetchLibraryTracks"))
                return "{\"data\":{\"me\":{\"library\":{\"tracks\":{\"totalCount\":0,\"items\":[]}}}}}";
            SpotifyAuthStore.saveAuthorization(JsonParser.parseString("{\"access_token\":\"other\",\"expires_in\":3600}").getAsJsonObject(), SpotifyConfigStore.BROWSER_CLIENT_ID);
            return "{\"data\":{\"me\":{\"libraryV3\":{\"totalCount\":0,\"items\":[]}}}}";
        });
        SpotifyMusicService.refreshUserPlaylists();
        assertFalse(SpotifyMusicService.areUserPlaylistsLoaded());
        assertTrue(SpotifyMusicService.getUserPlaylistsSnapshot().isEmpty());
    }
    @Test void folderRowsDoNotEndLibraryPagination() throws Exception {
        respond(query -> {
            if (query.get("operationName").getAsString().equals("fetchLibraryTracks"))
                return "{\"data\":{\"me\":{\"library\":{\"tracks\":{\"totalCount\":0,\"items\":[]}}}}}";
            int offset = query.getAsJsonObject("variables").get("offset").getAsInt();
            assertTrue(offset == 0 || offset == 1);
            String item = offset == 0 ? "{\"__typename\":\"Folder\"}"
                    : "{\"uri\":\"spotify:user:owner:playlist:" + ID + "\",\"name\":\"Nested\"}";
            return "{\"data\":{\"me\":{\"libraryV3\":{\"totalCount\":2,\"items\":[{\"item\":{\"data\":" + item + "}}]}}}}";
        });
        assertEquals("Nested", SpotifyDesktopCatalog.userPlaylists(10).get(1).name);
        assertEquals(3, requests.size());
    }
    @Test void missingLibraryIsFailureRatherThanSuccessfulEmptySnapshot() {
        respond(query -> "{\"data\":{}}");
        SpotifyMusicService.refreshUserPlaylists();
        assertFalse(SpotifyMusicService.areUserPlaylistsLoaded());
        assertFalse(SpotifyMusicService.getUserPlaylistsError().isEmpty());
    }
    @Test void desktopUnauthorizedRefreshUsesIssuingClientAndRetriesSameQuery() throws Exception {
        SpotifyWebApiClient.setTransportForTest(request -> {
            requests.add(request);
            if (requests.size() == 1) return new SpotifyWebApiClient.Response(401, -1, "{}");
            if (requests.size() == 2) {
                assertTrue(request.formBody.contains("client_id=" + SpotifyConfigStore.BROWSER_CLIENT_ID));
                return new SpotifyWebApiClient.Response(200, -1, "{\"access_token\":\"fresh\",\"expires_in\":3600}");
            }
            assertEquals("Bearer fresh", request.headers.get("Authorization"));
            assertEquals(requests.get(0).jsonBody, request.jsonBody);
            return new SpotifyWebApiClient.Response(200, -1, "{\"data\":{\"searchV2\":{\"tracksV2\":{\"items\":[]}}}}");
        });
        assertTrue(SpotifyDesktopCatalog.search("test", 10, 0).isEmpty());
        assertEquals(3, requests.size());
    }
    @Test void albumTracksInheritTheirAlbumAndCover() throws Exception {
        respond(query -> {
            assertEquals("getAlbum", query.get("operationName").getAsString());
            return "{\"data\":{\"albumUnion\":{\"name\":\"Parent\",\"coverArt\":{\"sources\":[{\"url\":\"parent-cover\"}]},\"tracksV2\":{\"totalCount\":1,\"items\":[{\"track\":" + track() + "}]}}}}";
        });
        SpotifyMusicService.Track song = SpotifyDesktopCatalog.playlistTracks("album:" + ID, 10).get(0);
        assertEquals(ID, song.albumId);
        assertEquals("Parent", song.album);
        assertEquals("parent-cover", song.coverUrl);
    }
    @Test void developerSessionKeepsWebApiEvenWhenBrowserIsNextLoginMode() {
        SpotifyAuthStore.saveAuthorization(JsonParser.parseString("{\"access_token\":\"developer\",\"expires_in\":3600}").getAsJsonObject(), "own-client");
        SpotifyConfigStore.saveLoginMode(SpotifyConfigStore.LoginMode.BROWSER);
        SpotifyWebApiClient.setTransportForTest(request -> {
            requests.add(request);
            assertTrue(request.url.startsWith("https://api.spotify.com/v1/search?"));
            assertEquals("Bearer developer", request.headers.get("Authorization"));
            return new SpotifyWebApiClient.Response(200, -1, "{\"tracks\":{\"items\":[]}}");
        });
        assertTrue(SpotifyMusicService.search("test", 10).isEmpty());
        assertEquals(1, requests.size());
    }
    @Test void staleAuthorizationFailureCannotLogOutNewAccount() {
        SpotifyWebApiClient.setTransportForTest(request -> {
            SpotifyAuthStore.saveAuthorization(JsonParser.parseString("{\"access_token\":\"new-account\",\"expires_in\":3600}").getAsJsonObject(), SpotifyConfigStore.BROWSER_CLIENT_ID);
            throw new SpotifyWebApiClient.SpotifyApiException(401, "REAUTH_REQUIRED", true);
        });
        assertTrue(SpotifyMusicService.search("test", 10).isEmpty());
        assertEquals("new-account", SpotifyAuthStore.snapshot().accessToken);
    }
    @Test void oldUnauthorizedResponseCannotInvalidateNewAccount() {
        SpotifyWebApiClient.setTransportForTest(request -> {
            SpotifyAuthStore.saveAuthorization(JsonParser.parseString("{\"access_token\":\"new-account\",\"expires_in\":3600}").getAsJsonObject(), SpotifyConfigStore.BROWSER_CLIENT_ID);
            return new SpotifyWebApiClient.Response(401, -1, "{}");
        });
        assertTrue(SpotifyMusicService.search("test", 10).isEmpty());
        assertEquals("new-account", SpotifyAuthStore.snapshot().accessToken);
    }
    @Test void oldRefreshResponseCannotReplaceNewAccount() {
        SpotifyAuthStore.putForTest("expired", "refresh", 0);
        SpotifyWebApiClient.setTransportForTest(request -> {
            assertTrue(request.url.endsWith("/api/token"));
            SpotifyAuthStore.saveAuthorization(JsonParser.parseString("{\"access_token\":\"new-account\",\"expires_in\":3600}").getAsJsonObject(), SpotifyConfigStore.BROWSER_CLIENT_ID);
            return new SpotifyWebApiClient.Response(200, -1, "{\"access_token\":\"old-refreshed\",\"expires_in\":3600}");
        });
        assertThrows(java.io.IOException.class, () -> SpotifyWebApiClient.getJson("/me", null));
        assertEquals("new-account", SpotifyAuthStore.snapshot().accessToken);
    }
}
