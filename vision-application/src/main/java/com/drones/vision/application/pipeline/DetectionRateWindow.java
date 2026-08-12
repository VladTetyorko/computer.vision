package com.drones.vision.application.pipeline;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Rolling record of what the sampler decided, per deadline, rendered as a {@link DetectionRate}.
 *
 * <h2>Why a peer of {@link PipelineLatencyWindow} rather than fields on it</h2>
 * They measure opposite halves of the same loop and are written at different moments by different
 * threads: a sampler decision happens on the source's {@code onNext} thread <i>before</i> a request
 * leaves, while a latency sample happens on an inference-completion thread <i>after</i> a response
 * lands. Folding them together would mean one lock serializing the video path against the
 * completion path for no reason, and would make the counters undefined for exactly the case they
 * exist to explain — the samples that were <b>never sent</b>, and so have no round trip at all.
 *
 * <h2>Time</h2>
 * Stamped with {@link StreamPipeline}'s {@code nanoTimeSource} — the frame-cadence seam, read once
 * per {@code onNext} and passed in, never read again here. See {@link StreamPipeline}'s
 * {@code latencyNanoSource} javadoc for why that distinction is load-bearing.
 *
 * <h2>Threading</h2>
 * Every method is {@code synchronized}: {@link #record} runs on the source's delivery thread while
 * {@link #snapshot} is read from an HTTP thread.
 */
final class DetectionRateWindow {

    /** Memory safety net, not a knob — the window is normally bounded by time. @see PipelineLatencyWindow */
    private static final int MAX_SAMPLES = 4_000;

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    /** What the sampler did with one served deadline. */
    enum Outcome {
        /** Handed to the detection port. */
        SUBMITTED,
        /** Discarded at the {@code maxInFlightInferences} bound. */
        DROPPED_IN_FLIGHT,
        /** Withheld during a detection outage's backoff. */
        DROPPED_OUTAGE
    }

    private final Duration window;
    private final long windowNanos;

    private final Deque<Sample> samples = new ArrayDeque<>();

    private record Sample(long atNanos, Outcome outcome) {
    }

    /** Deadlines no frame served, counted separately: they have no sample to hang on the deque. */
    private long missedDeadlines;

    /**
     * @param window how far back the counters reach; must be positive. {@link StreamPipeline} passes
     *               the same window the latency and tracking counters use — one observability window
     *               per stream, deliberately not a third knob that could drift out of step.
     */
    DetectionRateWindow(Duration window) {
        Objects.requireNonNull(window, "window must not be null");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive, was " + window);
        }
        this.window = window;
        this.windowNanos = window.toNanos();
    }

    /** Records one served deadline and evicts whatever has aged out. */
    synchronized void record(Outcome outcome, long atNanos) {
        samples.addLast(new Sample(atNanos, Objects.requireNonNull(outcome, "outcome must not be null")));
        evict(atNanos);
    }

    /**
     * Records deadlines that came and went with no frame to serve them. Unlike {@link #record} this
     * is a running total rather than a windowed one: it is a starvation signal whose absolute count
     * over the stream's life is what matters, and a starving source produces no events to evict by.
     */
    synchronized void recordMissedDeadlines(long count) {
        if (count > 0L) {
            missedDeadlines += count;
        }
    }

    private void evict(long nowNanos) {
        long staleBefore = nowNanos - windowNanos;
        while (!samples.isEmpty()
                && (samples.size() > MAX_SAMPLES || samples.peekFirst().atNanos() <= staleBefore)) {
            samples.removeFirst();
        }
    }

    /** Empties the window; called by {@link StreamPipeline#updateConfig} on a model re-arm. */
    synchronized void clear() {
        samples.clear();
        missedDeadlines = 0L;
    }

    /**
     * @return the counters over the current window. Never {@code null}, never throws.
     */
    synchronized DetectionRate snapshot(double sourceFps, double targetFps, double demandFps) {
        if (samples.isEmpty()) {
            return new DetectionRate(window, sourceFps, targetFps, demandFps, 0.0, 0L, 0L, 0L, missedDeadlines);
        }
        long submitted = 0L;
        long droppedInFlight = 0L;
        long droppedOutage = 0L;
        for (Sample sample : samples) {
            switch (sample.outcome()) {
                case SUBMITTED -> submitted++;
                case DROPPED_IN_FLIGHT -> droppedInFlight++;
                case DROPPED_OUTAGE -> droppedOutage++;
            }
        }
        return new DetectionRate(window, sourceFps, targetFps, demandFps, submittedFps(submitted),
                submitted, droppedInFlight, droppedOutage, missedDeadlines);
    }

    /**
     * Submissions per second across the deque's own span rather than across {@link #window}, for the
     * same reason {@link PipelineLatencyWindow}'s does: a stream two seconds old must report its
     * actual rate, not one diluted by a window it has not filled yet.
     */
    private double submittedFps(long submitted) {
        if (samples.size() < 2 || submitted == 0L) {
            return 0.0;
        }
        long span = samples.peekLast().atNanos() - samples.peekFirst().atNanos();
        return span <= 0L ? 0.0 : submitted * NANOS_PER_SECOND / span;
    }
}
