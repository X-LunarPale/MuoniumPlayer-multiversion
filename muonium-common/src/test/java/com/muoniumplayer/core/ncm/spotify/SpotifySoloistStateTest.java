package com.muoniumplayer.core.ncm.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpotifySoloistStateTest {
    private static final String ID = "2JRo0gjbX4GrCqBYdRohoo";

    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    private static String item(String id) {
        return "{\"uri\":\"spotify:track:" + id + "\",\"entity_type\":\"track\",\"decorations\":{"
                + "\"identity\":{\"name\":\"My Song\"},\"playback\":{\"duration_ms\":210000},"
                + "\"parent\":{\"entity\":{\"decorations\":{\"identity\":{\"name\":\"Album\"}}}},"
                + "\"creators\":[{\"entity\":{\"decorations\":{\"identity\":{\"name\":\"Artist\"}}}}],"
                + "\"visual_identity\":{\"cover\":[{\"url\":\"https://i.scdn.co/image/test\"}]}}}";
    }

    @Test
    void pairingIsIndependentOfOAuthAndRequiresAuthEvent() {
        SpotifySoloistState state = new SpotifySoloistState();
        assertFalse(state.paired());
        state.accept(json("{\"type\":\"auth_state\",\"logged_in\":true}"), 1000);
        assertTrue(state.paired());
        assertNull(state.snapshot(1000).track);
        state.accept(json("{\"type\":\"auth_state\",\"logged_in\":false}"), 2000);
        assertFalse(state.paired());
    }

    @Test
    void officialEntityEnvelopeProvidesMetadataAndClockWithoutWebApi() {
        SpotifySoloistState state = playing();
        SpotifyConnectService.PlaybackState snapshot = state.snapshot(4000);
        assertEquals(ID, snapshot.track.id);
        assertEquals("My Song", snapshot.track.name);
        assertEquals("Artist", snapshot.track.artist);
        assertEquals("Album", snapshot.track.album);
        assertEquals("https://i.scdn.co/image/test", snapshot.track.coverUrl);
        assertEquals(210000, snapshot.durationMs);
        assertEquals(48000, snapshot.progressMs);
        assertEquals(65, snapshot.volumePercent);
    }

    @Test
    void pauseFreezesPositionAndResumeContinuesFromFrozenAnchor() {
        SpotifySoloistState state = playing();
        state.accept(json("{\"type\":\"playback_changed\",\"status\":\"paused\"}"), 4000);
        assertEquals(48000, state.snapshot(9000).progressMs);
        assertFalse(state.snapshot(9000).playing);
        state.accept(json("{\"type\":\"playback_changed\",\"status\":\"playing\"}"), 9000);
        assertEquals(50000, state.snapshot(11000).progressMs);
    }

    @Test
    void changingTrackDropsPreviousPositionAndSupportsVolumeEvents() {
        SpotifySoloistState state = playing();
        state.accept(json("{\"type\":\"track_changed\",\"item\":"
                + item("7qiZfU4dY1lWllzX7mPBI3") + "}"), 4000);
        state.accept(json("{\"type\":\"volume_changed\",\"volume\":120}"), 4000);
        assertEquals(0, state.snapshot(4000).progressMs);
        assertEquals(100, state.snapshot(4000).volumePercent);
    }

    @Test
    void nonMusicAndSignedOutStatesNeverReusePreviousLyrics() {
        SpotifySoloistState state = playing();
        state.accept(json("{\"type\":\"track_changed\",\"item\":{\"uri\":\"spotify:episode:abc\"}}"), 4000);
        assertNull(state.snapshot(4000).track);
        state.accept(json("{\"type\":\"auth_state\",\"logged_in\":false}"), 5000);
        assertNull(state.snapshot(5000));
    }

    @Test
    void positionSyncHonorsSpeedAndIgnoresUnrelatedServerClocks() {
        SpotifySoloistState state = playing();
        state.accept(json("{\"type\":\"position_sync\",\"position\":{\"position_ms\":100,"
                + "\"timestamp_ms\":1700000000000,\"speed\":0}}"), 4000);
        assertEquals(100, state.snapshot(9000).progressMs);
    }

    @Test
    void replayingSameTrackCannotInheritPreviousEndPosition() {
        SpotifySoloistState state = playing();
        state.accept(json("{\"type\":\"position_sync\",\"position\":{\"position_ms\":209000}}"), 2000);
        SpotifySoloistTrackWatch replay = new SpotifySoloistTrackWatch(ID, 210000);
        state.watch(replay, 2000, false);
        state.accept(json("{\"type\":\"playback_changed\",\"status\":\"buffering\"}"), 2001);
        state.accept(json("{\"type\":\"position_sync\",\"position\":{\"position_ms\":0}}"), 2002);
        state.accept(json("{\"type\":\"playback_changed\",\"status\":\"playing\"}"), 2003);
        assertEquals(SpotifySoloistTrackWatch.Outcome.PLAYING, replay.outcome(2003));
        assertEquals(0, state.snapshot(2003).progressMs);
    }

    @Test
    void playableEpisodeEventsProvideIdentityAndClockForTheSameQueue() {
        SpotifySoloistState state = playing();
        state.accept(json("{\"type\":\"playback_state\",\"status\":\"playing\",\"item\":{"
                + "\"uri\":\"spotify:episode:" + ID + "\",\"decorations\":{\"identity\":{\"name\":\"我們倆\"},"
                + "\"playback\":{\"duration_ms\":193908}}},\"position\":{\"position_ms\":12000}}"), 2000);
        assertNotNull(state.snapshot(2000).track);
        assertEquals("episode:" + ID, state.snapshot(2000).track.id);
        assertEquals(12000, state.snapshot(2000).progressMs);
    }

    @Test
    void commandsUseDocumentedSoloistNamesAndFields() {
        JsonObject seek = SpotifySoloistService.command("seek", "position_ms", 30000);
        assertEquals("command", seek.get("type").getAsString());
        assertEquals("seek", seek.get("command").getAsString());
        assertEquals(30000, seek.get("position_ms").getAsInt());
        assertFalse(seek.has("Authorization"));
    }

    @Test
    void resumeAfterPausedSpeedZeroRestartsClockBeforeNextPositionSync() {
        SpotifySoloistState state = playing();
        state.accept(json("{\"type\":\"playback_changed\",\"status\":\"paused\"}"), 2000);
        state.accept(json("{\"type\":\"position_sync\",\"position\":{\"position_ms\":46000,\"speed\":0}}"), 2000);
        state.accept(json("{\"type\":\"playback_changed\",\"status\":\"playing\"}"), 4000);
        assertEquals(47000, state.snapshot(5000).progressMs);
    }

    private static SpotifySoloistState playing() {
        SpotifySoloistState state = new SpotifySoloistState();
        state.accept(json("{\"type\":\"auth_state\",\"logged_in\":true}"), 1000);
        state.accept(json("{\"type\":\"playback_state\",\"status\":\"playing\",\"volume\":65,\"item\":"
                + item(ID) + ",\"position\":{\"position_ms\":45000,\"timestamp_ms\":1000,\"speed\":1}}"), 1000);
        return state;
    }
}
