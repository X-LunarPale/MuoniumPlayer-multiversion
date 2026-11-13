package com.muoniumplayer.core.ncm.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.screens.ncm.LyricLine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Spotify Web Player's color-lyrics endpoint adapter. Missing lyrics are an empty result. */
public final class SpotifyLyricsService {
    private static final Pattern TRACK_ID = Pattern.compile("[0-9A-Za-z]{22}");

    private SpotifyLyricsService() {
    }

    /** Returns empty when Spotify has no lyrics or its private endpoint is unavailable. */
    public static List<LyricLine> resolve(String trackId) {
        if (trackId == null || !TRACK_ID.matcher(trackId.trim()).matches()) {
            return Collections.emptyList();
        }
        try {
            return parse(SpotifyWebApiClient.lyrics(trackId.trim()).toString());
        } catch (IOException | RuntimeException failure) {
            System.err.println("[Music/Spotify] 歌词暂不可用 " + trackId + ": " + failure.getMessage());
            return Collections.emptyList();
        }
    }

    static List<LyricLine> parse(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonElement lyricsElement = root.get("lyrics");
            if (lyricsElement == null || !lyricsElement.isJsonObject()) return Collections.emptyList();
            JsonObject lyrics = lyricsElement.getAsJsonObject();
            if (!"LINE_SYNCED".equals(string(lyrics, "syncType"))) return Collections.emptyList();
            JsonElement linesElement = lyrics.get("lines");
            if (linesElement == null || !linesElement.isJsonArray()) return Collections.emptyList();
            JsonArray sourceLines = linesElement.getAsJsonArray();
            List<LyricLine> output = new ArrayList<>();
            for (int lineIndex = 0; lineIndex < sourceLines.size(); lineIndex++) {
                JsonElement element = sourceLines.get(lineIndex);
                if (!element.isJsonObject()) continue;
                JsonObject source = element.getAsJsonObject();
                String text = string(source, "words").trim();
                if (text.isEmpty()) continue;
                long start = number(source, "startTimeMs");
                long nextStart = -1L;
                for (int i = lineIndex + 1; i < sourceLines.size(); i++) {
                    JsonElement next = sourceLines.get(i);
                    if (next.isJsonObject()) {
                        nextStart = number(next.getAsJsonObject(), "startTimeMs");
                        break;
                    }
                }
                long duration = nextStart > start ? nextStart - start : 0L;
                LyricLine parsed = new LyricLine(start, text);
                parsed.duration = duration;
                output.add(parsed);
            }
            return output;
        } catch (RuntimeException malformed) {
            return Collections.emptyList();
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static long number(JsonObject object, String key) {
        try {
            return Long.parseLong(string(object, key));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
