package com.muoniumplayer.core.ncm.spotify;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Soloist WebSocket events → the existing Spotify mirror contract. No Web API token is needed. */
final class SpotifySoloistState {
    private boolean paired;
    private String status = "idle";
    private SpotifyMusicService.Track track;
    private long position;
    private long anchorAt;
    private double speed = 1;
    private int volume = -1;
    private SpotifySoloistTrackWatch watch;

    synchronized void watch(SpotifySoloistTrackWatch requested, long now, boolean followCurrent) {
        watch = requested;
        if (requested != null) {
            requested.dispatched(now);
            if (followCurrent) {
                requested.observe(track == null ? "" : track.id, status,
                        positionAt(now), track == null ? 0 : track.durationMillis, now);
            } else {
                // A new play URI starts a new clock even when the URI repeats. Buffering can arrive
                // before position_sync, so the previous tail must never confirm this selection.
                position = 0;
                anchorAt = now;
                status = "idle";
                speed = 0;
            }
        }
    }

    synchronized boolean paired() {
        return paired;
    }

    synchronized void accept(JsonObject event, long now) {
        String type = text(event, "type");
        if (watch != null) watch.rememberPosition(track == null ? "" : track.id, positionAt(now));
        switch (type) {
            case "auth_state":
                paired = event.has("logged_in") && event.get("logged_in").getAsBoolean();
                if (!paired) { track = null; status = "idle"; position = 0; }
                break;
            case "playback_state":
                status = text(event, "status");
                track = track(object(event, "item"));
                position = 0;
                anchorAt = now;
                sync(object(event, "position"), now);
                if (event.has("volume")) volume = percent(event.get("volume"));
                break;
            case "track_changed":
                SpotifyMusicService.Track next = track(object(event, "item"));
                if (track == null || next == null || !track.id.equals(next.id)) {
                    position = 0;
                    anchorAt = now;
                }
                track = next;
                break;
            case "playback_changed":
                position = positionAt(now);
                anchorAt = now;
                status = text(event, "status");
                speed = "playing".equals(status) ? 1 : 0;
                break;
            case "position_sync":
                sync(object(event, "position"), now);
                break;
            case "volume_changed":
                volume = percent(event.get("volume"));
                break;
            default:
                break;
        }
        if (watch != null && paired && ("playback_state".equals(type) || "track_changed".equals(type)
                || "playback_changed".equals(type) || "position_sync".equals(type))) {
            watch.observe(track == null ? "" : track.id, status, positionAt(now),
                    track == null ? 0 : track.durationMillis, now);
        }
    }

    private void sync(JsonObject anchor, long now) {
        position = Math.max(0, number(anchor, "position_ms", 0));
        long timestamp = number(anchor, "timestamp_ms", now);
        anchorAt = Math.abs(now - timestamp) < 3_600_000L ? timestamp : now;
        speed = anchor.has("speed") ? anchor.get("speed").getAsDouble() : 1;
        if (!Double.isFinite(speed) || speed < 0) speed = 0;
    }

    private long positionAt(long now) {
        long at = position + ("playing".equals(status)
                ? (long) (Math.max(0, now - anchorAt) * speed) : 0);
        return track != null && track.durationMillis > 0 ? Math.min(at, track.durationMillis) : at;
    }

    synchronized SpotifyConnectService.PlaybackState snapshot(long now) {
        if (!paired) return null;
        return new SpotifyConnectService.PlaybackState("soloist", "MuoniumPlayer Soloist", "Computer",
                volume, volume >= 0, "playing".equals(status), positionAt(now), now, track,
                track == null ? 0 : track.durationMillis);
    }

    private static SpotifyMusicService.Track track(JsonObject entity) {
        String uri = text(entity, "uri");
        String id = SpotifyMusicService.sourceIdFromUri(uri);
        if (id.isEmpty()) return null;
        SpotifyMusicService.Track result = new SpotifyMusicService.Track();
        result.id = id;
        JsonObject decorations = object(entity, "decorations");
        result.name = text(object(decorations, "identity"), "name");
        result.durationMillis = number(object(decorations, "playback"), "duration_ms",
                number(entity, "duration_ms", 0));
        JsonObject album = object(object(decorations, "parent"), "entity");
        result.album = text(object(object(album, "decorations"), "identity"), "name");
        String albumUri = text(album, "uri");
        if (albumUri.startsWith("spotify:album:")) result.albumId = albumUri.substring(14);
        else if (albumUri.startsWith("spotify:show:")) result.albumId = albumUri.substring(13);
        JsonElement creators = decorations.get("creators");
        if (creators != null && creators.isJsonArray()) {
            for (JsonElement creator : creators.getAsJsonArray()) {
                if (!creator.isJsonObject()) continue;
                String name = text(object(object(object(creator.getAsJsonObject(), "entity"),
                        "decorations"), "identity"), "name");
                if (!name.isEmpty()) result.artist += (result.artist.isEmpty() ? "" : " / ") + name;
            }
        }
        JsonElement covers = object(decorations, "visual_identity").get("cover");
        if (covers != null && covers.isJsonArray()) {
            for (JsonElement cover : covers.getAsJsonArray()) {
                if (!cover.isJsonObject()) continue;
                String url = text(cover.getAsJsonObject(), "url");
                if (url.startsWith("https://")) { result.coverUrl = url; break; }
            }
        }
        if (result.name.isEmpty()) result.name = "Spotify · " + result.id;
        return result;
    }

    static JsonObject object(JsonObject value, String key) {
        JsonElement child = value.get(key);
        return child != null && child.isJsonObject() ? child.getAsJsonObject() : new JsonObject();
    }

    static String text(JsonObject value, String key) {
        JsonElement child = value.get(key);
        return child != null && child.isJsonPrimitive() ? child.getAsString() : "";
    }

    private static long number(JsonObject value, String key, long fallback) {
        JsonElement child = value.get(key);
        return child != null && child.isJsonPrimitive() ? child.getAsLong() : fallback;
    }

    private static int percent(JsonElement value) {
        return value != null && value.isJsonPrimitive() ? Math.max(0, Math.min(100, value.getAsInt())) : -1;
    }
}
