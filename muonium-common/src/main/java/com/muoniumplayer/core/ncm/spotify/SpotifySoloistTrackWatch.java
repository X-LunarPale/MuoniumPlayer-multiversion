package com.muoniumplayer.core.ncm.spotify;

/** Event-owned end detection for one requested track. Each selection has its own identity. */
final class SpotifySoloistTrackWatch {
    enum Outcome { WAITING, PLAYING, ENDED, REPLACED, FAILED }
    private static final long END_SLACK_MS = 2500;
    private static final long START_TIMEOUT_MS = 10000;
    final String trackId;
    private long duration;
    private long lastPosition;
    private long dispatchedAt = -1;
    private long seekAt = -1;
    private long seekPosition;
    private boolean seen;
    private boolean pausedByUs;
    private Outcome outcome = Outcome.WAITING;

    SpotifySoloistTrackWatch(String trackId, long duration) {
        this.trackId = trackId;
        this.duration = Math.max(0, duration);
    }

    synchronized void dispatched(long now) { dispatchedAt = now; }
    synchronized long duration() { return duration; }

    synchronized void rememberPosition(String id, long position) {
        if (seen && trackId.equals(id) && seekAt < 0 && !terminal()) lastPosition = position;
    }

    synchronized void observe(String id, String status, long position, long toldDuration, long now) {
        if (dispatchedAt < 0 || terminal()) return;
        boolean matching = trackId.equals(id);
        if (!seen) {
            if (!matching || !("playing".equals(status) || "buffering".equals(status))) return;
            seen = true;
            outcome = Outcome.PLAYING;
        }
        if (matching && toldDuration > 0) duration = toldDuration;
        if (seekAt >= 0) {
            // Ignore the old position anchor until the seek is acknowledged (or its finite grace expires).
            if (matching && (Math.abs(position - seekPosition) <= 2500 || now - seekAt > 5000)) seekAt = -1;
            else return;
            lastPosition = position;
        }
        if (!matching) {
            outcome = nearEnd(lastPosition) ? Outcome.ENDED : Outcome.REPLACED;
        } else if (!pausedByUs && ("idle".equals(status) || "paused".equals(status))
                && nearEnd(Math.max(position, lastPosition))) {
            outcome = Outcome.ENDED;
        } else if (!pausedByUs && nearEnd(lastPosition) && position + END_SLACK_MS < lastPosition
                && ("playing".equals(status) || "buffering".equals(status))) {
            outcome = Outcome.ENDED;
        }
        if (matching) lastPosition = position;
    }

    synchronized void pause(boolean paused) { pausedByUs = paused; }
    synchronized void seek(long position, long now) {
        seekPosition = Math.max(0, position);
        lastPosition = seekPosition;
        seekAt = now;
    }

    synchronized Outcome outcome(long now) {
        if (!seen && dispatchedAt >= 0 && now - dispatchedAt >= START_TIMEOUT_MS) outcome = Outcome.FAILED;
        return outcome;
    }

    private boolean nearEnd(long position) { return duration > 0 && position >= duration - END_SLACK_MS; }
    private boolean terminal() { return outcome == Outcome.ENDED || outcome == Outcome.REPLACED || outcome == Outcome.FAILED; }
}
