package com.drones.vision.adapter.rtsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level checks of {@link RealtimePacer}, the shared pacing logic extracted out of {@code
 * FfmpegVideoSource}'s grab loop and {@code RtspFeedTransmitter}'s transmit loop
 * (docs/plans/active/LAYERING-REFACTOR-PLAN.md §5.1), previously only exercised indirectly through those two
 * classes' own real-decode tests (e.g. {@code FfmpegVideoSourceTest#fileSourceIsPacedToItsNativeFrameRate}).
 * Deliberately generous lower-bound-only assertions, same idiom as that test — CI scheduling
 * jitter must never flake a wall-clock-timing test, so no upper bound is ever asserted.
 */
class RealtimePacerTest {

    @Test
    void firstCallNeverSleeps() {
        RealtimePacer pacer = new RealtimePacer();

        long startNanos = System.nanoTime();
        pacer.paceTo(1_000_000L); // an arbitrary baseline timestamp; nothing to pace against yet
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        assertTrue(elapsedMillis < 200, "the very first paceTo() call must only establish a baseline, never sleep");
    }

    @Test
    void secondCallSleepsAtLeastTheTimestampDelta() {
        RealtimePacer pacer = new RealtimePacer();
        pacer.paceTo(0L);

        long startNanos = System.nanoTime();
        pacer.paceTo(100_000L); // 100ms of media-timeline advance since the previous call
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        assertTrue(elapsedMillis >= 50, "expected a sleep of roughly 100ms (generous lower bound), got " + elapsedMillis + "ms");
    }

    @Test
    void resetMakesTheNextCallBehaveLikeTheFirstOne() {
        RealtimePacer pacer = new RealtimePacer();
        pacer.paceTo(0L);
        pacer.paceTo(500_000L); // establishes a baseline far in the "media timeline" future

        pacer.reset();

        long startNanos = System.nanoTime();
        pacer.paceTo(0L); // a restarted loop's first frame is timestamp 0 again
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        assertTrue(elapsedMillis < 200, "reset() must make the next paceTo() call establish a fresh baseline, never sleep");
    }
}
