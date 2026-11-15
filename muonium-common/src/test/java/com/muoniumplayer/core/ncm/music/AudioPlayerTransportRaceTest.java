package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.Test;
import repackage.processing.sound.SoundFile;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the wrapper with a controlled sample and no audio output device. */
class AudioPlayerTransportRaceTest {
    @Test
    void pauseAndSeekCannotLeaveAPlayingSampleBehindAStoppedIntent() throws Exception {
        FakeSoundFile sample = allocate(FakeSoundFile.class);
        sample.running = true;
        sample.seekEntered = new CountDownLatch(1);
        sample.releaseSeek = new CountDownLatch(1);
        AudioPlayer player = player(sample, true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch pauseEntered = new CountDownLatch(1);
        CountDownLatch pauseFinished = new CountDownLatch(1);
        Thread seek = new Thread(() -> {
            try { player.setPlaybackTime(30_000f); }
            catch (Throwable thrown) { failure.set(thrown); }
        });
        Thread pause = new Thread(() -> {
            pauseEntered.countDown();
            try { player.pause(); }
            catch (Throwable thrown) { failure.set(thrown); }
            finally { pauseFinished.countDown(); }
        });
        seek.start();
        try {
            assertTrue(sample.seekEntered.await(2, TimeUnit.SECONDS));
            pause.start();
            assertTrue(pauseEntered.await(2, TimeUnit.SECONDS));
            assertFalse(pauseFinished.await(100, TimeUnit.MILLISECONDS),
                    "pause must serialize with the seek's decision to keep playing");
        } finally {
            sample.releaseSeek.countDown();
            seek.join(2_000L);
            if (pause.getState() != Thread.State.NEW) pause.join(2_000L);
        }
        assertFalse(seek.isAlive());
        assertFalse(pause.isAlive());
        assertNull(failure.get());
        assertFalse(sample.running);
        assertFalse(player.isPlayRequested());
    }

    @Test
    void pausedSeekOnlyCuesTheSample() throws Exception {
        FakeSoundFile sample = allocate(FakeSoundFile.class);
        AudioPlayer player = player(sample, false);
        player.setPlaybackTime(45_000f);
        assertEquals(1, sample.cues);
        assertEquals(0, sample.jumps);
        assertFalse(sample.running);
        assertFalse(player.isPlayRequested());
    }

    @Test
    void completionFromAReplacedSampleCannotFinishTheCurrentDeck() throws Exception {
        FakeSoundFile previous = allocate(FakeSoundFile.class);
        FakeSoundFile current = allocate(FakeSoundFile.class);
        AudioPlayer player = player(previous, true);
        AtomicInteger completions = new AtomicInteger();
        player.setAfterPlayed(completions::incrementAndGet);
        player.player = current;
        player.setListeners();

        previous.completion.run();
        assertFalse(player.isFinished());
        assertTrue(player.isPlayRequested());
        assertEquals(0, completions.get());
        current.completion.run();
        current.completion.run();
        assertTrue(player.isFinished());
        assertFalse(player.isPlayRequested());
        assertEquals(1, completions.get());
    }

    @Test
    void completionQueuedBeforePauseCannotMarkThePausedDeckFinished() throws Exception {
        FakeSoundFile sample = allocate(FakeSoundFile.class);
        sample.running = true;
        AudioPlayer player = player(sample, true);
        AtomicInteger completions = new AtomicInteger();
        player.setAfterPlayed(completions::incrementAndGet);
        player.pause();
        sample.completion.run();
        assertFalse(player.isFinished());
        assertFalse(player.isPlayRequested());
        assertEquals(0, completions.get());
    }

    private static AudioPlayer player(FakeSoundFile sample, boolean requested) throws Exception {
        AudioPlayer player = allocate(AudioPlayer.class);
        player.player = sample;
        Field field = AudioPlayer.class.getDeclaredField("playRequested");
        field.setAccessible(true);
        field.setBoolean(player, requested);
        return player;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }

    private static final class FakeSoundFile extends SoundFile {
        volatile boolean running;
        Runnable completion;
        CountDownLatch seekEntered;
        CountDownLatch releaseSeek;
        int jumps;
        int cues;

        private FakeSoundFile() { super((String) null); }
        @Override public int frames() { return 180 * 44_100; }
        @Override public int sampleRate() { return 44_100; }
        @Override public float duration() { return 180f; }
        @Override public boolean isPlaying() { return running; }
        @Override public void amp(float amp) { }
        @Override public void resyncTimeStretch() { }
        @Override public void setOnFinished(Runnable completion) { this.completion = completion; }
        @Override public void pause() { running = false; }
        @Override public void cue(float seconds) { cues++; running = false; }
        @Override public void jump(float seconds) {
            jumps++;
            if (seekEntered != null) seekEntered.countDown();
            if (releaseSeek != null) {
                try {
                    if (!releaseSeek.await(2, TimeUnit.SECONDS)) throw new AssertionError("seek was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            running = true;
        }
    }
}
