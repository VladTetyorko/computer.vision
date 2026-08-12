package com.drones.vision.application.pipeline;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Objects;

/**
 * Rolling submit&rarr;available timings over one stream, rendered as a {@link PipelineLatency}.
 *
 * <h2>Why a peer of {@link TrackingStatsWindow} rather than fields on it</h2>
 * The same reasoning that made {@code TrackingStatsWindow} a peer of {@link TrackBook} applies once
 * more, and here it is load-bearing rather than stylistic: {@code TrackingStatsWindow#accept}
 * <b>returns early when tracking is off</b>, because an untracked stream must report an empty duty
 * ratio rather than a fake 100% one. Latency has no such exemption — a stream with tracking
 * {@code OFF} still has a round trip, and an operator complaining that boxes lag is very often
 * running exactly that stream. Folding these samples into the tracking window would have silently
 * produced "no latency data" for the case most likely to be under investigation.
 *
 * <h2>Time</h2>
 * Unlike {@code TrackingStatsWindow}, which stamps samples with the frame's own {@code capturedAt},
 * this window measures with {@link StreamPipeline}'s injected {@code nanoTimeSource}. That is
 * deliberate on both counts: a duration between two events on one host wants a <b>monotonic</b>
 * clock (immune to NTP steps), while a frame's position in a sequence wants the frame's timebase.
 * Tests drive the same fake supplier they already use for the sampler, so these figures are exactly
 * deterministic.
 *
 * <h2>What it does not claim</h2>
 * {@code roundTrip} starts when the pipeline <i>submits</i> a frame, not when a sensor captured it.
 * Every {@code VideoSource} in this repo stamps {@code VideoFrame.capturedAt} with {@code
 * Instant.now()} at receipt, so true sensor-to-receipt latency is not observable here and is not
 * pretended to be. What is measured is what this pipeline is responsible for.
 *
 * <h2>Threading</h2>
 * Every method is {@code synchronized}: {@link #record} runs on an inference-completion thread while
 * {@link #snapshot} is read from an HTTP thread.
 */
final class PipelineLatencyWindow {

    /**
     * Hard cap on retained samples — a memory safety net, not a knob, exactly as in {@link
     * TrackingStatsWindow}. The window is normally bounded by time.
     */
    private static final int MAX_SAMPLES = 4_000;

    private static final double NANOS_PER_MILLI = 1_000_000.0;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final long windowNanos;
    private final Duration window;

    /** Completion timestamps and their round trips, oldest first. */
    private final Deque<Sample> samples = new ArrayDeque<>();

    private record Sample(long completedAtNanos, long roundTripNanos) {
    }

    /**
     * @param window how far back the figures reach; must be positive. {@link StreamPipeline} passes
     *               the same {@code StreamPipelineSettings#trackingStatsWindow()} the tracking
     *               counters use — one observability window for the stream, deliberately not a
     *               second knob that could drift out of step with the first.
     */
    PipelineLatencyWindow(Duration window) {
        Objects.requireNonNull(window, "window must not be null");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive, was " + window);
        }
        this.window = window;
        this.windowNanos = window.toNanos();
    }

    /**
     * Records one completed detection and evicts whatever has aged out.
     *
     * <p>Called for <b>every</b> completion, successful or not: a failing stream's round trips are
     * exactly the ones worth seeing. A negative or zero elapsed time (a fake clock that does not
     * advance, in tests) is clamped to zero rather than rejected — a degenerate sample must not be
     * able to make a percentile negative.
     *
     * @param submittedAtNanos the reading of the pipeline's nano source when the frame was submitted
     * @param completedAtNanos the reading when its result arrived
     */
    synchronized void record(long submittedAtNanos, long completedAtNanos) {
        long elapsed = Math.max(0L, completedAtNanos - submittedAtNanos);
        samples.addLast(new Sample(completedAtNanos, elapsed));
        evict(completedAtNanos);
    }

    private void evict(long nowNanos) {
        long staleBefore = nowNanos - windowNanos;
        while (!samples.isEmpty()
                && (samples.size() > MAX_SAMPLES || samples.peekFirst().completedAtNanos() <= staleBefore)) {
            samples.removeFirst();
        }
    }

    /** Empties the window; called by {@link StreamPipeline#updateConfig} on a model re-arm. */
    synchronized void clear() {
        samples.clear();
    }

    /**
     * @return the figures over the current window; {@link PipelineLatency#empty} when nothing has
     *         completed. Never {@code null}, never throws.
     */
    synchronized PipelineLatency snapshot() {
        if (samples.isEmpty()) {
            return PipelineLatency.empty(window);
        }
        long[] roundTrips = new long[samples.size()];
        long[] intervals = new long[Math.max(0, samples.size() - 1)];
        int index = 0;
        long previousCompletedAt = 0L;
        for (Sample sample : samples) {
            if (index > 0) {
                intervals[index - 1] = sample.completedAtNanos() - previousCompletedAt;
            }
            previousCompletedAt = sample.completedAtNanos();
            roundTrips[index++] = sample.roundTripNanos();
        }
        Arrays.sort(roundTrips);
        Arrays.sort(intervals);
        return new PipelineLatency(window, samples.size(),
                percentileMillis(roundTrips, 50), percentileMillis(roundTrips, 95),
                roundTrips[roundTrips.length - 1] / NANOS_PER_MILLI,
                percentileMillis(intervals, 50), effectiveFps());
    }

    /**
     * Completions per second measured across the window's own span, not across {@link #window}: a
     * stream that started two seconds ago must report its actual rate rather than one diluted by
     * twenty-eight seconds it was not running for. Falls back to zero for a single sample, where no
     * span exists to divide by.
     */
    private double effectiveFps() {
        if (samples.size() < 2) {
            return 0.0;
        }
        long span = samples.peekLast().completedAtNanos() - samples.peekFirst().completedAtNanos();
        if (span <= 0L) {
            return 0.0;
        }
        return (samples.size() - 1) * NANOS_PER_SECOND / span;
    }

    /** Nearest-rank percentile over an already-sorted array, in milliseconds; 0 for an empty array. */
    private static double percentileMillis(long[] sortedNanos, int percentile) {
        if (sortedNanos.length == 0) {
            return 0.0;
        }
        int rank = (int) Math.ceil(percentile / 100.0 * sortedNanos.length) - 1;
        return sortedNanos[Math.clamp(rank, 0, sortedNanos.length - 1)] / NANOS_PER_MILLI;
    }
}
