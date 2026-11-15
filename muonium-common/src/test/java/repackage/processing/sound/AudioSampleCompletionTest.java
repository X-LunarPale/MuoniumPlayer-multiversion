package repackage.processing.sound;

import org.junit.jupiter.api.Test;
import repackage.com.jsyn.ports.UnitDataQueueCallback;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Delivers queued completion events without opening an output device. */
class AudioSampleCompletionTest {
    @Test
    void completionFromTheSegmentBeforeASeekCannotStopTheNewSegment() throws Exception {
        TrackingSample sample = sample();
        AtomicInteger completions = new AtomicInteger();
        sample.setOnFinished(completions::incrementAndGet);
        setGeneration(sample, 12L);
        UnitDataQueueCallback oldSegment = callback(sample, 12L);
        setGeneration(sample, 13L);
        oldSegment.finished(null);
        assertEquals(0, sample.stops);
        assertEquals(0, completions.get());
        callback(sample, 13L).finished(null);
        assertEquals(1, sample.stops);
        assertEquals(1, completions.get());
    }

    @Test
    void completionQueuedBeforePauseRemainsInvalidAfterResume() throws Exception {
        TrackingSample sample = sample();
        AtomicInteger completions = new AtomicInteger();
        sample.setOnFinished(completions::incrementAndGet);
        setGeneration(sample, 20L);
        UnitDataQueueCallback beforePause = callback(sample, 20L);
        setGeneration(sample, 21L);
        beforePause.finished(null);
        setGeneration(sample, 22L);
        beforePause.finished(null);
        assertEquals(0, sample.stops);
        assertEquals(0, completions.get());
    }

    private static TrackingSample sample() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (TrackingSample) ((sun.misc.Unsafe) field.get(null)).allocateInstance(TrackingSample.class);
    }

    private static void setGeneration(AudioSample sample, long generation) throws Exception {
        Field field = AudioSample.class.getDeclaredField("playbackGeneration");
        field.setAccessible(true);
        field.setLong(sample, generation);
    }

    private static UnitDataQueueCallback callback(AudioSample sample, long generation) throws Exception {
        Class<?> type = Class.forName(AudioSample.class.getName() + "$PlaybackFinishedCallback");
        Constructor<?> constructor = type.getDeclaredConstructor(AudioSample.class, long.class);
        constructor.setAccessible(true);
        return (UnitDataQueueCallback) constructor.newInstance(sample, generation);
    }

    private static final class TrackingSample extends AudioSample {
        int stops;
        private TrackingSample() { super(); }
        @Override public synchronized void stop() { stops++; }
    }
}
