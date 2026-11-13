package com.muoniumplayer.core.ncm.spotify;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.muoniumplayer.core.ncm.spotify.SpotifySoloistTrackWatch.Outcome.*;

class SpotifySoloistTrackWatchTest {
    private static SpotifySoloistTrackWatch started() {
        SpotifySoloistTrackWatch watch = new SpotifySoloistTrackWatch("A", 100000);
        watch.dispatched(0);
        watch.observe("A", "playing", 5000, 100000, 0);
        return watch;
    }

    @Test void oldTrackBeforeConfirmationCannotAdvanceTheNewSelection() {
        var watch = new SpotifySoloistTrackWatch("B", 100000);
        watch.dispatched(0);
        watch.observe("A", "idle", 100000, 100000, 100);
        assertEquals(WAITING, watch.outcome(100));
        watch.observe("B", "buffering", 0, 100000, 200);
        assertEquals(PLAYING, watch.outcome(200));
    }

    @Test void unconfirmedPlayTimesOutInsteadOfSilentlyAdoptingAnUnrelatedSong() {
        var watch = new SpotifySoloistTrackWatch("B", 100000);
        watch.dispatched(100);
        assertEquals(WAITING, watch.outcome(10099));
        assertEquals(FAILED, watch.outcome(10100));
    }

    @Test void recommendationAfterEndIsLatchedWithoutAdoptingTheRecommendation() {
        var watch = started();
        watch.rememberPosition("A", 99000);
        watch.observe("recommendation", "playing", 0, 200000, 1000);
        assertEquals(ENDED, watch.outcome(1000));
        watch.observe("recommendation", "playing", 50000, 200000, 51000);
        assertEquals(ENDED, watch.outcome(51000));
    }

    @Test void manualPauseAndSeekNearEndDoNotFinishTheSong() {
        var watch = started();
        watch.pause(true);
        watch.rememberPosition("A", 99500);
        watch.observe("A", "paused", 99500, 100000, 100);
        assertEquals(PLAYING, watch.outcome(100));
        watch.seek(20000, 200);
        watch.rememberPosition("A", 99500);
        watch.observe("A", "paused", 99500, 100000, 210);
        assertEquals(PLAYING, watch.outcome(210));
        watch.observe("A", "paused", 20000, 100000, 220);
        watch.pause(false);
        watch.observe("A", "playing", 20000, 100000, 230);
        assertEquals(PLAYING, watch.outcome(230));
    }

    @Test void sameTrackAtItsEndThenRewoundIsANewQueueSelection() {
        var watch = started();
        watch.rememberPosition("A", 99500);
        watch.observe("A", "playing", 0, 100000, 1000);
        assertEquals(ENDED, watch.outcome(1000));
        var replay = started();
        assertEquals(PLAYING, replay.outcome(1000));
    }

    @Test void changingTrackWellBeforeEndReturnsControlToExternalPlayer() {
        var watch = started();
        watch.rememberPosition("A", 10000);
        watch.observe("external", "playing", 0, 100000, 1000);
        assertEquals(REPLACED, watch.outcome(1000));
    }

    @Test void eventLayerCatchesEndAndNewTrackBetweenTwoMirrorPolls() {
        var state = new SpotifySoloistState();
        state.accept(json("{\"type\":\"auth_state\",\"logged_in\":true}"), 0);
        var watch = new SpotifySoloistTrackWatch("2JRo0gjbX4GrCqBYdRohoo", 100000);
        state.watch(watch, 0, false);
        state.accept(json("{\"type\":\"playback_state\",\"status\":\"playing\",\"item\":{\"uri\":\"spotify:track:2JRo0gjbX4GrCqBYdRohoo\",\"duration_ms\":100000},\"position\":{\"position_ms\":99000}}"), 1);
        state.accept(json("{\"type\":\"track_changed\",\"item\":{\"uri\":\"spotify:track:56UzFYxgkvxU188BsCKBOc\"}}"), 2);
        assertEquals(ENDED, watch.outcome(250));
    }

    private static com.google.gson.JsonObject json(String raw) {
        return com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
    }
}
