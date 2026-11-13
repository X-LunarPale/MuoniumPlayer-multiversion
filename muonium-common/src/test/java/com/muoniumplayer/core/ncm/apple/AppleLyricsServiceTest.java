package com.muoniumplayer.core.ncm.apple;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.screens.ncm.LyricParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppleLyricsServiceTest {
    private static final String LINE_TTML = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div>"
            + "<p begin=\"1s\" end=\"3s\">第一句</p>"
            + "<p begin=\"4s\" end=\"6s\">第二句</p></div></body></tt>";
    private static final String WORD_TTML = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div>"
            + "<p begin=\"1s\" end=\"3s\"><span begin=\"1s\" end=\"2s\">第</span>"
            + "<span begin=\"2s\" end=\"3s\">一句</span></p></div></body></tt>";

    private final List<AppleApiClient.Request> requests = new ArrayList<>();
    private JsonObject relationships;
    private int status;

    @BeforeEach void setUp() {
        relationships = new JsonObject();
        status = 200;
        AppleConfigStore.putForTest("test-media-token", "cn", false, false);
        AppleTokenManager.putForTest("test-web-token", 0L);
        AppleApiClient.markAccountValid();
        AppleApiClient.setTransportForTest(request -> {
            requests.add(request);
            JsonObject song = new JsonObject();
            song.add("relationships", relationships);
            JsonArray data = new JsonArray();
            data.add(song);
            JsonObject payload = new JsonObject();
            payload.add("data", data);
            return new AppleApiClient.Response(status, -1, payload.toString());
        });
    }

    @AfterEach void tearDown() {
        AppleApiClient.resetTransportForTest();
        AppleApiClient.markAccountValid();
        AppleTokenManager.clearForTest();
        AppleConfigStore.clearForTest();
    }

    private void relation(String type, String field, String ttml) {
        JsonObject attrs = new JsonObject();
        attrs.addProperty(field, ttml);
        JsonObject lyric = new JsonObject();
        lyric.add("attributes", attrs);
        JsonArray data = new JsonArray();
        data.add(lyric);
        JsonObject container = new JsonObject();
        container.add("data", data);
        relationships.add(type, container);
    }

    @Test void authenticatedSongRequestsSyllablesAndSelectsLocalizedTiming() {
        relation("syllable-lyrics", "ttmlLocalizations", WORD_TTML);
        relation("lyrics", "ttml", LINE_TTML);
        assertEquals(WORD_TTML, AppleMusicService.ttml("1071506932"));
        assertEquals(1, requests.size());
        AppleApiClient.Request request = requests.get(0);
        assertEquals("test-media-token", request.headers.get("Media-User-Token"));
        String url = URLDecoder.decode(request.url, StandardCharsets.UTF_8);
        assertTrue(url.contains("/v1/catalog/cn/songs/1071506932?"));
        assertTrue(url.contains("include[songs]=syllable-lyrics,lyrics"));
        assertTrue(url.contains("extend=ttmlLocalizations"));
    }

    @Test void lineOnlyOfficialLyricsReachSharedTimeline() throws Exception {
        relation("lyrics", "ttml", LINE_TTML);
        AppleMusicService.Track track = new AppleMusicService.Track();
        track.id = "1071506932";
        track.name = "测试歌曲";
        Music music = Music.fromAppleTrack(track);
        Class<?> loader = Class.forName("com.muoniumplayer.core.ncm.music.LyricLoadService");
        Method method = loader.getDeclaredMethod("appleTtmlLyrics", Music.class);
        method.setAccessible(true);
        Object result = method.invoke(null, music);
        assertNotNull(result, "有效逐行 TTML 不能因为没有逐字时间戳而被丢弃");
        Field linesField = result.getClass().getDeclaredField("lines");
        linesField.setAccessible(true);
        @SuppressWarnings("unchecked") List<LyricLine> lines = (List<LyricLine>) linesField.get(result);
        assertEquals(2, lines.size());
        assertEquals(1000L, lines.get(0).getTimestamp());
        assertEquals(4000L, lines.get(1).getTimestamp());
        assertFalse(LyricParser.hasWordByWordTiming(lines));
    }

    @Test void guestRequestsNeverSendAccountCredentials() {
        AppleConfigStore.putForTest("", "cn", false, false);
        relation("lyrics", "ttml", LINE_TTML);
        assertEquals(LINE_TTML, AppleMusicService.ttml("123"));
        assertFalse(requests.get(0).headers.containsKey("Media-User-Token"));
    }

    @Test void blankLocalizedLyricsFallBackToOrdinaryTtml() {
        relation("syllable-lyrics", "ttmlLocalizations", "  ");
        relationships.getAsJsonObject("syllable-lyrics").getAsJsonArray("data").get(0)
                .getAsJsonObject().getAsJsonObject("attributes").addProperty("ttml", WORD_TTML);
        assertEquals(WORD_TTML, AppleMusicService.ttml("123"));
    }

    @Test void emptySyllableRelationshipDoesNotHideLineLyrics() {
        JsonObject empty = new JsonObject();
        empty.add("data", new JsonArray());
        relationships.add("syllable-lyrics", empty);
        relation("lyrics", "ttml", LINE_TTML);
        assertEquals(LINE_TTML, AppleMusicService.ttml("123"));
    }

    @Test void noLyricOrMalformedRelationshipReturnsNoLyrics() {
        relationships.addProperty("lyrics", "unavailable");
        String result = AppleMusicService.ttml("123");
        assertTrue(result == null || result.isEmpty());
    }

    @Test void emptyIdDoesNotMakeRequests() {
        assertTrue(AppleMusicService.ttml("  ").isEmpty());
        assertTrue(requests.isEmpty());
    }

    @Test void rejectedAccountIsReportedAsExpired() {
        status = 401;
        assertNull(AppleMusicService.ttml("123"));
        assertTrue(AppleApiClient.isAccountInvalid());
        assertEquals(1, requests.size());
    }
}
