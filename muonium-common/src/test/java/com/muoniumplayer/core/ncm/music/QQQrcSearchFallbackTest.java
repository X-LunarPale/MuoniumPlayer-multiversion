package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class QQQrcSearchFallbackTest {
    private static JsonArray row(String title, String artist, String album, int seconds) {
        return JsonParser.parseString("[{\"songid\":1,\"songname\":\"" + title + "\",\"albumname\":\"" + album
                + "\",\"interval\":" + seconds + ",\"singer\":[{\"name\":\"" + artist + "\"}]}]").getAsJsonArray();
    }

    @Test void albumSearchWithUnrelatedResultsFallsBackToTitleAndArtistWithoutRelaxingScore() {
        var target = new LyricMatchScore.Target("消愁", "毛不易", "平凡的一天", 261346);
        List<String> queries = new ArrayList<>();
        var selected = QQQrcLyricService.findCandidate(target, query -> {
            queries.add(query);
            return query.equals("消愁 毛不易") ? row("消愁", "毛不易", "巨星不易工作室", 261)
                    : row("像我这样的人", "毛不易", "平凡的一天", 263);
        });
        assertNotNull(selected);
        assertEquals("消愁", selected.get("songname").getAsString());
        assertTrue(queries.contains("消愁 毛不易"));
    }

    @Test void matchingStructuredQueryDoesNotIssueFallbackRequests() {
        List<String> queries = new ArrayList<>();
        assertNotNull(QQQrcLyricService.findCandidate(new LyricMatchScore.Target("當你", "林俊傑", "她說", 249248),
                query -> { queries.add(query); return row("当你", "林俊杰", "她说", 249); }));
        assertEquals(1, queries.size());
    }

    @Test void fallbackStillRejectsDifferentSongAndMismatchedDuration() {
        assertNull(QQQrcLyricService.findCandidate(new LyricMatchScore.Target("消愁", "毛不易", "平凡的一天", 261346),
                query -> row("消愁", "毛不易", "平凡的一天", 190)));
    }

    @Test void titleOnlyRetryRejectsShortLiveVersionUsingOriginalDuration() {
        var target = new LyricMatchScore.Target("消愁", "毛不易", "平凡的一天", 261346);
        var selected = QQQrcLyricService.findCandidate(target, query -> query.equals("消愁")
                ? row("消愁", "毛不易", "平凡的一天", 261)
                : row("消愁", "毛不易", "明日之子 第7期", 179));
        assertNotNull(selected, "A short live version must not prevent the title-only retry");
        assertEquals(261, selected.get("interval").getAsInt());
    }

    @Test void mobileSearchKeepsGroupedStudioVersionAndRemovesHighlightMarkup() throws Exception {
        // QQ groups the 261-second studio recording under a shorter live version.
        var response = JsonParser.parseString("""
                {"code":0,"music.search.SearchCgiService.DoSearchForQQMusicMobile":{"code":0,"data":{
                  "code":0,"body":{"item_song":[{"id":203451421,"mid":"live",
                    "title":"<em>消愁</em>","interval":179,"album":{"name":"明日之子 第7期"},
                    "singer":[{"name":"<em>毛不易</em>"}],"grp":[
                      {"id":214039297,"mid":"studio","title":"<em>消愁</em>","interval":261,
                       "album":{"title":"平凡的一天","mid":"album-mid"},"singer":[{"name":"<em>毛不易</em>"}]}]}]}}}}
                """).getAsJsonObject();
        var parser = QQQrcLyricService.class.getDeclaredMethod("parseSearchResponse", com.google.gson.JsonObject.class);
        parser.setAccessible(true);
        var candidates = (JsonArray) parser.invoke(null, response);
        var selected = QQQrcLyricService.findCandidate(
                new LyricMatchScore.Target("消愁", "毛不易", "平凡的一天", 261346), query -> candidates);
        assertNotNull(selected);
        assertEquals(214039297L, selected.get("songid").getAsLong());
        assertEquals("消愁", selected.get("songname").getAsString());
        assertEquals("album-mid", selected.get("albummid").getAsString());
        assertEquals("毛不易", selected.getAsJsonArray("singer").get(0).getAsJsonObject().get("name").getAsString());
    }
}
