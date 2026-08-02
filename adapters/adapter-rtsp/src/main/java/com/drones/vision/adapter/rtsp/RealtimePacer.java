package com.drones.vision.adapter.rtsp;

import java.util.concurrent.TimeUnit;

/**
 * Paces a grab loop to a media source's own timeline instead of letting native decode run flat out.
 * Tracks the delta between successive media timestamps (microseconds, e.g. {@code
 * FFmpegFrameGrabber#getTimestamp()}) and sleeps the difference between that delta and the
 * wall-clock time actually spent since the previous paced frame, clamped to zero (never a negative
 * sleep) — no drift compensation beyond this one monotonic baseline, deliberately kept simple per
 * docs/CYCLES-PLAN.md §1a.
 *
 * <p>Extracted out of {@code FfmpegVideoSource}'s grab loop (docs/LAYERING-REFACTOR-PLAN.md §5.1,
 * "Share a {@code RealtimePacer} with {@code RtspFeedTransmitter}") where this exact logic was
 * duplicated verbatim in both {@link FfmpegGrabLoop} ({@code file}-scheme sources) and {@link
 * RtspFeedTransmitter}'s own transmit loop. Sharing it here is intra-module, not cross-adapter — both
 * call sites live in this one {@code adapter-rtsp} module, so this does not violate the "adapters
 * never depend on each other" rule.
 *
 * <p><b>Not thread-safe and stateful</b>: one instance per grab/transmit loop, driven only from that
 * loop's own dedicated thread; never share an instance across two loops.
 */
final class RealtimePacer {

    /** {@code -1} means "no previous frame yet" — the next call only establishes the baseline. */
    private long baselineTimestampMicros = -1;
    private long baselineWallNanos;

    /**
     * Sleeps as needed so that {@code timestampMicros} — the media's own monotonic clock — advances
     * no faster than wall-clock time. The first call after construction, or after {@link #reset()},
     * never sleeps: it only records the baseline, so the first frame of a pass through the media is
     * never delayed.
     *
     * @param timestampMicros the current frame's media timestamp, e.g. {@code
     *                        FFmpegFrameGrabber#getTimestamp()}
     */
    void paceTo(long timestampMicros) {
        if (baselineTimestampMicros >= 0) {
            long targetDeltaMicros = timestampMicros - baselineTimestampMicros;
            long elapsedMicros = (System.nanoTime() - baselineWallNanos) / 1_000L;
            sleepMicros(targetDeltaMicros - elapsedMicros);
        }
        baselineTimestampMicros = timestampMicros;
        baselineWallNanos = System.nanoTime();
    }

    /**
     * Resets the pacing baseline so the next {@link #paceTo(long)} call behaves like the very first
     * one (no sleep) — call this after a loop restart (e.g. {@code loop=true} on graceful EOF), since
     * the restarted media's timeline starts over from its own beginning too.
     */
    void reset() {
        baselineTimestampMicros = -1;
    }

    /** Sleeps the given microsecond duration; clamps negative/zero to a no-op. */
    private static void sleepMicros(long micros) {
        if (micros <= 0) {
            return;
        }
        try {
            TimeUnit.MICROSECONDS.sleep(micros);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
