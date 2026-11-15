package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Album;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.utils.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Runs transport handlers without a decoder, network request or audio device. */
class PlaybackTransportStateTest {
    private Class<?> ownerType;
    private Object owner;
    private PlaybackSession session;
    private Music song;
    private List<Music> oldQueue;
    private Thread oldThread;
    private Music oldSong;
    private AudioPlayer oldPlayer;
    private long oldGeneration;
    private int oldIndex;
    private boolean oldPlaying;
    private boolean oldBreak;
    private boolean oldAwaiting;
    private boolean oldFm;
    private long oldResumeOffset;

    @BeforeEach
    void setUp() throws Exception {
        oldQueue = CloudMusic.playList;
        oldThread = CloudMusic.playThread;
        oldSong = CloudMusic.currentlyPlaying;
        oldPlayer = CloudMusic.player;
        oldGeneration = CloudMusic.generation.get();
        oldIndex = CloudMusic.curIdx;
        oldPlaying = CloudMusic.playing.get();
        oldBreak = (boolean) get(CloudMusic.class, "doBreak");
        oldAwaiting = (boolean) get(CloudMusic.class, "awaitingPlaybackAction");
        oldFm = (boolean) get(CloudMusic.class, "personalFmActive");
        oldResumeOffset = (long) get(CloudMusic.class, "pendingResumeOffsetMillis");
        set(CloudMusic.class, "doBreak", false);
        set(CloudMusic.class, "awaitingPlaybackAction", false);
        set(CloudMusic.class, "pendingResumeOffsetMillis", -1L);
        // Playback tests must not write the user's persisted listening queue.
        set(CloudMusic.class, "personalFmActive", true);
        song = new StubSong();
        List<Music> queue = new ArrayList<>(Collections.singletonList(song));
        CloudMusic.playList = queue;
        CloudMusic.curIdx = 0;
        CloudMusic.currentlyPlaying = song;
        CloudMusic.player = null;
        CloudMusic.playing.set(false);
        ownerType = Class.forName(CloudMusic.class.getName() + "$PlayThread");
        Constructor<?> constructor = ownerType.getDeclaredConstructor(List.class, int.class);
        constructor.setAccessible(true);
        owner = constructor.newInstance(queue, 0);
        session = new PlaybackSession(CloudMusic.generation.incrementAndGet(), song.getStableKey(), song.getId());
        set(owner, "session", session);
        CloudMusic.playThread = (Thread) owner;
    }

    @AfterEach
    void tearDown() throws Exception {
        set(owner, "activeFade", null);
        set(owner, "ownedPlayer", null);
        CloudMusic.playList = oldQueue;
        CloudMusic.playThread = oldThread;
        CloudMusic.currentlyPlaying = oldSong;
        CloudMusic.player = oldPlayer;
        CloudMusic.generation.set(oldGeneration);
        CloudMusic.curIdx = oldIndex;
        CloudMusic.playing.set(oldPlaying);
        set(CloudMusic.class, "doBreak", oldBreak);
        set(CloudMusic.class, "awaitingPlaybackAction", oldAwaiting);
        set(CloudMusic.class, "personalFmActive", oldFm);
        set(CloudMusic.class, "pendingResumeOffsetMillis", oldResumeOffset);
    }

    @Test
    void pausingDuringInitialLoadKeepsTheReadyDeckSilentUntilResume() throws Exception {
        assertTrue(CloudMusic.transportPlaying());
        assertTrue(CloudMusic.playbackPresentation().preparing);
        assertSame(song, CloudMusic.playbackPresentation().displayTrack);
        assertEquals(CloudMusic.PlayPauseResult.PAUSED, CloudMusic.togglePlayPause());
        assertFalse(CloudMusic.transportPlaying());

        TestDeck deck = deck(false);
        install(deck);
        assertTrue(start());
        assertEquals(1, deck.preparations);
        assertEquals(0, deck.starts);
        assertTrue(deck.isPausing());
        assertFalse(CloudMusic.playbackPresentation().preparing);

        assertEquals(CloudMusic.PlayPauseResult.RESUMED, CloudMusic.togglePlayPause());
        assertEquals(1, deck.starts);
        assertFalse(deck.isPausing());
        assertTrue(CloudMusic.transportPlaying());
    }

    @Test
    void resumeBeforeDecodingCompletesStartsTheSongExactlyOnce() throws Exception {
        assertEquals(CloudMusic.PlayPauseResult.PAUSED, CloudMusic.togglePlayPause());
        assertEquals(CloudMusic.PlayPauseResult.RESUMED, CloudMusic.togglePlayPause());
        TestDeck deck = deck(false);
        install(deck);
        assertTrue(start());
        assertEquals(1, deck.starts);
        assertEquals(0, deck.preparations);
        assertTrue(CloudMusic.transportPlaying());
    }

    @Test
    void pausingABlendStopsBothDecksAndTicksCannotResumeThem() throws Exception {
        TestDeck incoming = deck(true);
        TestDeck outgoing = deck(true);
        install(incoming);
        session.audioActive = true;
        Method planMethod = ownerType.getDeclaredMethod("manualSelectionPlan");
        planMethod.setAccessible(true);
        AutomixPlan plan = (AutomixPlan) planMethod.invoke(null);
        Class<?> fadeType = Class.forName(ownerType.getName() + "$AutomixFade");
        Constructor<?> constructor = fadeType.getDeclaredConstructor(AudioPlayer.class, AudioPlayer.class,
                AutomixPlan.class, long.class, long.class);
        constructor.setAccessible(true);
        Object fade = constructor.newInstance(outgoing, incoming, plan, 10_000L, System.currentTimeMillis());
        set(owner, "activeFade", fade);

        assertEquals(CloudMusic.PlayPauseResult.PAUSED, CloudMusic.togglePlayPause());
        assertEquals(1, incoming.pauses);
        assertEquals(1, outgoing.pauses);
        call("driveAutomixFade");
        call("driveAutomixFade");
        assertEquals(0, incoming.starts);
        assertEquals(0, outgoing.starts);
        assertTrue((double) get(fade, "pausedProgress") >= 0.0);

        assertEquals(CloudMusic.PlayPauseResult.RESUMED, CloudMusic.togglePlayPause());
        assertEquals(1, incoming.starts);
        assertEquals(1, outgoing.starts);
        assertEquals(-1.0, (double) get(fade, "pausedProgress"));
    }

    @Test
    void cancelledSessionCannotAdvertiseOrTogglePlaybackIntent() throws Exception {
        session.invalidate();
        assertFalse(CloudMusic.transportPlaying());
        assertFalse(CloudMusic.playbackPresentation().preparing);
        assertEquals(CloudMusic.PlayPauseResult.UNAVAILABLE, CloudMusic.togglePlayPause());
    }

    @Test
    void roomTransportPauseIsReflectedAfterTheDeckHasStarted() throws Exception {
        TestDeck deck = deck(true);
        install(deck);
        session.audioActive = true;
        assertTrue(CloudMusic.transportPlaying());
        deck.pause();
        assertFalse(CloudMusic.transportPlaying());
        deck.unpause();
        assertTrue(CloudMusic.transportPlaying());
    }

    private void install(TestDeck deck) throws Exception {
        set(owner, "ownedPlayer", deck);
        CloudMusic.player = deck;
        session.player = deck;
    }

    private boolean start() throws Exception {
        Method method = ownerType.getDeclaredMethod("startPlayback", Music.class, Tuple.class,
                File.class, PlaybackSession.class);
        method.setAccessible(true);
        return (boolean) method.invoke(owner, song, null, null, session);
    }

    private void call(String name) throws Exception {
        Method method = ownerType.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(owner);
    }

    private static TestDeck deck(boolean running) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        TestDeck deck = (TestDeck) ((sun.misc.Unsafe) field.get(null)).allocateInstance(TestDeck.class);
        deck.running = running;
        return deck;
    }

    private static Object get(Object target, String name) throws Exception {
        Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target instanceof Class<?> ? null : target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target instanceof Class<?> ? null : target, value);
    }

    private static final class TestDeck extends AudioPlayer {
        boolean running;
        int starts;
        int pauses;
        int preparations;

        private TestDeck() { super(null); }
        @Override public boolean isUsable() { return true; }
        @Override public boolean isFinished() { return false; }
        @Override public boolean isPausing() { return !running; }
        @Override public float getTotalTimeMillis() { return 180_000f; }
        @Override public float getCurrentTimeMillis() { return 0f; }
        @Override public void play() { starts++; running = true; }
        @Override public void unpause() { play(); }
        @Override public void pause() { pauses++; running = false; }
        @Override public void prepareStart(float millis) { preparations++; running = false; }
        @Override public void setAfterPlayed(Runnable callback) { afterPlayed = callback; }
        @Override public void applyAutomixFrame(float gain, float bass, float vocal, float mid, float high) { }
        @Override public void applyOutgoingAutomixFrame(float gain, float bass, float vocal, float mid,
                                                       float high, float tempo, float pitch) { }
    }

    private static final class StubSong extends Music {
        StubSong() {
            super("transport-test", "transport-test", "", 992L, Collections.emptyList(), Collections.emptyList(),
                    new Album(992L, "test", "", Collections.emptyList()),
                    180_000L, 0L, 0L, Collections.emptyList());
        }
        @Override public boolean isNetease() { return false; }
        @Override public Tuple<String, String> getPlayUrl() { throw new AssertionError("unexpected resolution"); }
    }
}
