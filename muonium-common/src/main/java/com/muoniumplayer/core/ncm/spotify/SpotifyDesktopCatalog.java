package com.muoniumplayer.core.ncm.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.spotify.SpotifyMusicService.Playlist;
import com.muoniumplayer.core.ncm.spotify.SpotifyMusicService.Track;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Desktop account catalog, using the persisted queries also used by Folia. */
final class SpotifyDesktopCatalog {
    private static final Map<String, String> HASHES = Map.of(
            "libraryV3", "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455",
            "fetchLibraryTracks", "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240",
            "fetchPlaylist", "cd2275433b29f7316176e7b5b5e098ae7744724e1a52d63549c76636b3257749",
            "getAlbum", "b9bfabef66ed756e5e13f68a942deb60bd4125ec1f1be8cc42769dc0259b4b10",
            "searchTracks", "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428",
            "profileAttributes", "53bcb064f6cd18c23f752bc324a791194d20df612d8e1239c735144ab0399ced");

    private SpotifyDesktopCatalog() { }

    static boolean usesDesktopSession() {
        return SpotifyConfigStore.BROWSER_CLIENT_ID.equals(SpotifyAuthStore.snapshot().clientId);
    }

    private static JsonObject query(String operation, JsonObject variables) throws IOException {
        JsonObject persisted = new JsonObject();
        persisted.addProperty("version", 1);
        persisted.addProperty("sha256Hash", HASHES.get(operation));
        JsonObject extensions = new JsonObject();
        extensions.add("persistedQuery", persisted);
        JsonObject body = new JsonObject();
        body.addProperty("operationName", operation);
        body.add("variables", variables);
        body.add("extensions", extensions);
        return SpotifyWebApiClient.desktopQuery(body);
    }

    static List<Playlist> userPlaylists(int max) throws IOException {
        List<Playlist> result = new ArrayList<>();
        JsonObject liked = object(query("fetchLibraryTracks", page(0, 1)), "me", "library", "tracks");
        JsonArray likedRows = rows(liked);
        Playlist card = new Playlist();
        card.id = SpotifyMusicService.LIKED_PLAYLIST_ID;
        card.name = "喜欢的歌曲";
        card.owner = "我";
        card.trackCount = (int) number(liked, "totalCount");
        if (!likedRows.isEmpty()) {
            JsonObject beside = object(asObject(likedRows.get(0)), "track");
            Track first = track(object(beside, "data"), text(beside, "_uri"));
            if (first != null) card.coverUrl = first.coverUrl;
        }
        result.add(card);
        int bound = Math.max(1, Math.min(max, 20000));
        int offset = 0;
        while (offset < 20000 && result.size() <= bound) {
            JsonObject variables = page(offset, Math.min(50, bound));
            JsonArray filters = new JsonArray();
            filters.add("Playlists");
            variables.add("filters", filters);
            variables.add("order", JsonNull.INSTANCE);
            variables.addProperty("textFilter", "");
            variables.add("features", new JsonArray());
            variables.addProperty("flatten", true);
            variables.add("expandedFolders", new JsonArray());
            variables.add("folderUri", JsonNull.INSTANCE);
            variables.addProperty("includeFoldersWhenFlattening", true);
            JsonObject section = object(query("libraryV3", variables), "me", "libraryV3");
            JsonArray entries = rows(section);
            for (JsonElement row : entries) {
                Playlist playlist = playlist(object(asObject(row), "item", "data"));
                if (playlist != null && result.size() <= bound) result.add(playlist);
            }
            offset += entries.size();
            if (finished(section, entries, offset)) break;
        }
        return result;
    }

    static List<Track> playlistTracks(String id, int max) throws IOException {
        boolean liked = SpotifyMusicService.LIKED_PLAYLIST_ID.equals(id);
        boolean album = id.startsWith(SpotifyMusicService.ALBUM_PREFIX);
        String albumId = album ? id.substring(SpotifyMusicService.ALBUM_PREFIX.length()) : "";
        if (!liked && !(album ? albumId : id).matches("[0-9A-Za-z]{22}")) throw new IOException("Spotify 歌单 ID 无效");
        List<Track> result = new ArrayList<>();
        int offset = 0;
        int bound = Math.max(1, Math.min(max, 20000));
        while (offset < 20000 && result.size() < bound) {
            JsonObject variables = page(offset, Math.min(100, bound - result.size()));
            String operation = liked ? "fetchLibraryTracks" : album ? "getAlbum" : "fetchPlaylist";
            if (!liked) variables.addProperty("uri", "spotify:" + (album ? "album:" + albumId : "playlist:" + id));
            if (album) variables.addProperty("locale", "");
            else if (!liked) variables.addProperty("enableWatchFeedEntrypoint", false);
            JsonObject answer = query(operation, variables);
            JsonObject section = liked ? object(answer, "me", "library", "tracks")
                    : album ? object(answer, "albumUnion", "tracksV2") : object(answer, "playlistV2", "content");
            JsonArray entries = rows(section);
            for (JsonElement entry : entries) {
                JsonObject row = asObject(entry);
                JsonObject wrapped = object(row, liked ? "track" : "itemV2");
                Track song = album ? track(object(row, "track"), "") : track(object(wrapped, "data"), text(wrapped, "_uri"));
                if (song == null) continue;
                if (album) {
                    JsonObject parent = object(answer, "albumUnion");
                    song.albumId = albumId;
                    song.album = text(parent, "name");
                    song.coverUrl = image(object(parent, "coverArt"));
                }
                if (result.size() < bound) result.add(song);
            }
            offset += entries.size();
            if (finished(section, entries, offset)) break;
        }
        return result;
    }

    static List<Track> search(String keyword, int limit, int offset) throws IOException {
        Map<String, Track> result = new LinkedHashMap<>();
        int cursor = Math.max(0, offset);
        int maxRows = cursor + 200;
        int bound = Math.max(1, Math.min(limit, SpotifyMusicService.SEARCH_LIMIT_MAX));
        while (result.size() < bound && cursor < maxRows) {
            JsonObject variables = page(cursor, Math.min(50, bound - result.size()));
            variables.addProperty("searchTerm", keyword.trim());
            variables.addProperty("includePreReleases", false);
            variables.addProperty("numberOfTopResults", 20);
            variables.addProperty("includeAudiobooks", true);
            variables.addProperty("includeAuthors", false);
            JsonObject section = object(query("searchTracks", variables), "searchV2", "tracksV2");
            JsonArray entries = rows(section);
            for (JsonElement entry : entries) {
                Track song = track(object(asObject(entry), "item", "data"), "");
                if (song != null) result.putIfAbsent(song.id, song);
            }
            cursor += entries.size();
            if (finished(section, entries, cursor)) break;
        }
        return new ArrayList<>(result.values());
    }

    static Playlist albumCard(String id) throws IOException {
        if (!id.matches("[0-9A-Za-z]{22}")) throw new IOException("Spotify 专辑 ID 无效");
        JsonObject variables = page(0, 1);
        variables.addProperty("uri", "spotify:album:" + id);
        variables.addProperty("locale", "");
        JsonObject album = object(query("getAlbum", variables), "albumUnion");
        if (album == null || text(album, "name").isEmpty()) throw new IOException("Spotify 专辑暂不可用");
        Playlist card = new Playlist();
        card.id = SpotifyMusicService.ALBUM_PREFIX + id;
        card.name = text(album, "name");
        card.coverUrl = image(object(album, "coverArt"));
        card.trackCount = (int) number(object(album, "tracksV2"), "totalCount");
        card.owner = "Spotify 专辑";
        return card;
    }

    /** Adapt profile to the existing Web API profile contract. */
    static JsonObject profile() throws IOException {
        JsonObject profile = object(query("profileAttributes", new JsonObject()), "me", "profile");
        if (profile == null) throw new IOException("Spotify 账号资料暂不可用");
        JsonObject me = new JsonObject();
        me.addProperty("id", text(profile, "username"));
        me.addProperty("display_name", text(profile, "name"));
        JsonArray images = new JsonArray();
        JsonObject picture = new JsonObject();
        picture.addProperty("url", image(object(profile, "avatar")));
        images.add(picture);
        me.add("images", images);
        return me;
    }

    private static boolean finished(JsonObject section, JsonArray rows, int offset) {
        return rows.isEmpty() || (section.has("totalCount") && !section.get("totalCount").isJsonNull()
                && offset >= number(section, "totalCount"));
    }

    private static JsonObject page(int offset, int limit) {
        JsonObject variables = new JsonObject();
        variables.addProperty("offset", offset);
        variables.addProperty("limit", limit);
        return variables;
    }

    private static JsonArray rows(JsonObject section) throws IOException {
        if (section == null || !section.has("items") || !section.get("items").isJsonArray()) {
            throw new IOException("Spotify 曲库响应缺少列表，请稍后刷新");
        }
        return section.getAsJsonArray("items");
    }

    private static Playlist playlist(JsonObject source) {
        String id = id(text(source, "uri"), "playlist");
        if (id.isEmpty() || !type(source, "Playlist")) return null;
        Playlist card = new Playlist();
        card.id = id;
        card.name = text(source, "name");
        card.trackCount = (int) number(object(source, "content"), "totalCount");
        card.owner = text(object(source, "ownerV2", "data"), "name");
        JsonObject images = object(source, "images");
        if (images != null && images.has("items") && images.get("items").isJsonArray()) {
            JsonArray items = images.getAsJsonArray("items");
            if (!items.isEmpty()) card.coverUrl = image(asObject(items.get(0)));
        }
        return card;
    }

    private static Track track(JsonObject source, String besideUri) {
        String uri = text(source, "uri");
        if (type(source, "Episode") && uri.startsWith("spotify:episode:")) {
            String episodeId = id(uri, "episode");
            if (episodeId.isEmpty() || text(source, "name").isEmpty()) return null;
            Track episode = new Track();
            episode.id = "episode:" + episodeId;
            episode.name = text(source, "name");
            JsonObject show = object(source, "podcastV2", "data");
            episode.album = text(show, "name");
            episode.albumId = id(text(show, "uri"), "show");
            episode.artist = SpotifyMusicService.episodeArtist(episode.name, text(source, "description"),
                    text(object(show, "publisher"), "name"));
            episode.coverUrl = image(object(source, "coverArt"));
            episode.durationMillis = number(object(source, "episodeDuration"), "totalMilliseconds");
            return episode;
        }
        String id = id(uri.isEmpty() ? besideUri : uri, "track");
        if (source == null || id.isEmpty() || text(source, "name").isEmpty() || !type(source, "Track")) return null;
        Track song = new Track();
        song.id = id;
        song.name = text(source, "name");
        JsonObject artists = object(source, "artists");
        List<String> names = new ArrayList<>();
        if (artists != null && artists.has("items") && artists.get("items").isJsonArray()) {
            for (JsonElement entry : artists.getAsJsonArray("items")) {
                String name = text(object(asObject(entry), "profile"), "name");
                if (!name.isEmpty()) names.add(name);
            }
        }
        song.artist = String.join(" / ", names);
        JsonObject album = object(source, "albumOfTrack");
        song.album = text(album, "name");
        song.albumId = id(text(album, "uri"), "album");
        song.coverUrl = image(object(album, "coverArt"));
        song.durationMillis = Math.max(0, source.has("duration")
                ? number(object(source, "duration"), "totalMilliseconds")
                : number(object(source, "trackDuration"), "totalMilliseconds"));
        return song;
    }

    private static boolean type(JsonObject source, String expected) {
        String kind = text(source, "__typename");
        return kind.isEmpty() || kind.equals(expected);
    }
    private static String id(String uri, String kind) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^spotify:(?:user:[^:]+:)?" + kind + ":([0-9A-Za-z]{22})$").matcher(uri);
        return matcher.matches() ? matcher.group(1) : "";
    }
    private static String image(JsonObject picture) {
        if (picture == null || !picture.has("sources") || !picture.get("sources").isJsonArray()) return "";
        long width = -1;
        String best = "";
        for (JsonElement entry : picture.getAsJsonArray("sources")) {
            JsonObject source = asObject(entry);
            String url = text(source, "url");
            long size = number(source, "width");
            if (!url.isEmpty() && size > width) { best = url; width = size; }
        }
        return best;
    }
    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }
    private static JsonObject object(JsonObject root, String... path) {
        for (String key : path) root = root == null ? null : asObject(root.get(key));
        return root;
    }
    private static String text(JsonObject root, String key) {
        JsonElement value = root == null ? null : root.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString().trim() : "";
    }
    private static long number(JsonObject root, String key) {
        try { return Math.max(0, Long.parseLong(text(root, key))); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
