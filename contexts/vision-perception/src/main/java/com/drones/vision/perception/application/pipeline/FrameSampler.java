package com.drones.vision.perception.application.pipeline;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Frame-arrival cadence measurement and the deadline-based sample schedule (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md &sect;4.9/K3, wave W8.0 extraction) — one collaborator {@link
 * StreamPipeline} holds, built directly inside its constructor from {@link StreamPipelineSettings}
 * and its own {@link LongSupplier} clock, exactly like {@link WorldModel}/{@link PipelineTrace} are
 * (java-clean-code &sect;3: no new constructor overload on {@link StreamPipeline}, nothing here
 * changes that class's public signature).
 *
 * <h2>Why a deadline and not {@code sequence % everyNth}</h2>
 * The stride this replaced was {@code max(1, round(sourceFps / targetFps))} — an <b>integer</b>, so
 * a 24 fps source asked for 10 fps could only ever be sampled at 12 or 8, never 10, and the stride
 * was recomputed from a drifting EWMA on every frame, which moved the phase of {@code sequence % N}
 * as well as its period. A deadline has neither problem: every deadline is served by exactly one
 * frame, so the achieved rate equals the target for any source faster than it
 * (docs/plans/done/CV-RATE-CONTROL-PLAN.md &sect;1, losses L1/L2).
 *
 * <h2>The late clamp</h2>
 * When a frame arrives more than a whole interval after the deadline it serves, {@link #sampleDue}
 * restarts the schedule from {@code now} rather than advancing by one interval. Without that clamp a
 * stalled source builds up deadline debt that fires as a catch-up burst on recovery — spending the
 * scarcest resource in the system on frames whose moment has passed. The skipped deadlines are
 * counted instead (via the caller-supplied {@link DetectionRateWindow}), because "the source cannot
 * feed this rate" is a distinct diagnosis from "the detector cannot keep up" and has a different fix.
 *
 * <p><b>The rate controller's target stays where the docs say it lives</b> ({@link
 * DetectionRateController}, a {@link StreamPipeline}-owned peer): this class <i>asks</i> it (a method
 * parameter on {@link #sampleDue}/{@link #targetFps}) rather than owning it, so a rate-controller
 * change is never a constructor change here.
 */
final class FrameSampler {

    /** Unit conversion, not a tunable — a second in nanoseconds. */
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final LongSupplier nanoTimeSource;
    private final int assumedSourceFps;
    private final double measuredFpsEwmaAlpha;
    private final int warmupFrames;
    private final double minMeasuredFps;
    private final double maxMeasuredFps;

    // Frame-arrival cadence measurement state. Only ever touched from StreamPipeline#onNext's
    // thread (Flow.Subscriber's contract serializes signals), volatile purely for cross-thread
    // *visibility* between successive calls, not for mutual exclusion.
    private volatile long lastFrameArrivalNanos = -1L;
    private volatile long framesObserved = 0L;
    private volatile double measuredFps = -1.0;

    // Deadline-based sampling state (docs/plans/done/CV-RATE-CONTROL-PLAN.md wave R1). Same
    // threading note as the cadence fields above. `sampleScheduleArmed` distinguishes "no deadline
    // yet" from a legitimate deadline value, which a sentinel nanotime could not do -- nanoTime's
    // origin is arbitrary and may be negative.
    private volatile boolean sampleScheduleArmed = false;
    private volatile long nextSampleAtNanos;
    private volatile long lastSampleAtNanos;

    /**
     * @param settings       supplies {@code assumedSourceFps}/{@code measuredFpsEwmaAlpha}/{@code
     *                       warmupFrames}/{@code minMeasuredFps}/{@code maxMeasuredFps} — deployment
     *                       tunables, never a literal here
     * @param nanoTimeSource the pipeline's own cadence clock (see {@link #recordArrival()}'s own
     *                       javadoc for why it must be read at most once per frame)
     */
    FrameSampler(StreamPipelineSettings settings, LongSupplier nanoTimeSource) {
        Objects.requireNonNull(settings, "settings must not be null");
        this.nanoTimeSource = Objects.requireNonNull(nanoTimeSource, "nanoTimeSource must not be null");
        this.assumedSourceFps = settings.assumedSourceFps();
        this.measuredFpsEwmaAlpha = settings.measuredFpsEwmaAlpha();
        this.warmupFrames = settings.warmupFrames();
        this.minMeasuredFps = settings.minMeasuredFps();
        this.maxMeasuredFps = settings.maxMeasuredFps();
    }

    /**
     * Folds this frame's arrival into the source frame-rate measurement and returns the clock
     * reading it used.
     *
     * <p><b>This is the only place {@link #nanoTimeSource} is read per frame, and the value must be
     * threaded onward rather than re-read</b> (by {@link StreamPipeline#onNext}, into {@link
     * StreamPipeline#maybeDetect}). That seam is a <i>cadence</i> clock: a test's fake advances one
     * frame interval on every read, so it encodes "the pipeline reads me once per frame" — a second
     * read anywhere in the frame path would silently double every fake source's rate. The same trap
     * cost {@link StreamPipeline#pipelineLatency} a separate clock (its own {@code
     * latencyNanoSource}).
     *
     * @return the arrival timestamp of this frame, for the caller's own deadline arithmetic
     */
    long recordArrival() {
        long now = nanoTimeSource.getAsLong();
        framesObserved++;
        if (lastFrameArrivalNanos >= 0) {
            long deltaNanos = now - lastFrameArrivalNanos;
            if (deltaNanos > 0) {
                double instantaneousFps = NANOS_PER_SECOND / deltaNanos;
                double blended = measuredFps < 0
                        ? instantaneousFps // seed directly from the first sample rather than blending toward an
                                            // arbitrary starting value, so a constant-cadence source converges
                                            // immediately instead of drifting in slowly over many EWMA updates
                        : measuredFpsEwmaAlpha * instantaneousFps + (1 - measuredFpsEwmaAlpha) * measuredFps;
                measuredFps = clamp(blended, minMeasuredFps, maxMeasuredFps);
            }
        }
        lastFrameArrivalNanos = now;
        return now;
    }

    /**
     * @return how many frames {@link #recordArrival()} has observed (docs/plans/done/STREAM-STATE-PLAN.md
     *         &sect;2.2) — {@code 0} distinguishes "started, nothing has arrived yet" from "frames
     *         arrived and stopped," which {@code StreamState} needs and no other read model exposes
     */
    long framesObserved() {
        return framesObserved;
    }

    /**
     * @return nanoseconds since the most recent frame arrived, measured against this sampler's own
     *         clock — or {@link Long#MAX_VALUE} when no frame has ever arrived (the "infinitely
     *         stale" answer, which keeps every caller's comparison total without a separate
     *         emptiness check)
     */
    long nanosSinceLastFrame() {
        long lastArrival = lastFrameArrivalNanos;
        return lastArrival < 0L ? Long.MAX_VALUE : nanoTimeSource.getAsLong() - lastArrival;
    }

    /**
     * @return the source frame rate to report and to bound the sample rate by: the measurement once
     *         it is trusted ({@code warmupFrames} arrivals observed), the configured assumption
     *         until then
     */
    double sourceFps() {
        boolean trustMeasurement = framesObserved >= warmupFrames && measuredFps >= 0;
        return trustMeasurement ? measuredFps : assumedSourceFps;
    }

    /**
     * The rate being sampled at right now: {@code effectiveInferenceFps} raised by {@code
     * rateController} when the tracked target is about to leave its association budget, and exactly
     * {@code effectiveInferenceFps} whenever it is not (or the loop is off). Read on every frame, so
     * this is deliberately a few comparisons over already-computed values rather than any kind of
     * scan.
     *
     * @param rateController        asked, not owned — see this class's own javadoc
     * @param effectiveInferenceFps {@link StreamPipeline#effectiveInferenceFps()}'s current value —
     *                               tracking-mode-derived, so it stays a caller-supplied parameter
     *                               rather than a field here
     * @param maxInFlightInferences {@code PipelineConfig#maxInFlightInferences()}'s current value
     */
    double targetFps(DetectionRateController rateController, int effectiveInferenceFps, int maxInFlightInferences) {
        return rateController.targetFps(effectiveInferenceFps, sourceFps(), maxInFlightInferences);
    }

    /**
     * Decides whether {@code now} serves a sample deadline, advancing the schedule when it does —
     * see this class's own javadoc for why a deadline and not a frame-count stride, and for the late
     * clamp.
     *
     * <p>On a missed deadline (the source fell behind by a whole interval or more), the count of
     * skipped deadlines is folded into {@code detectionRate} before the schedule catches up to
     * {@code now} — "the source cannot feed this rate" is a distinct diagnosis from "the detector
     * cannot keep up," recorded here because this is the one place that knows a deadline was missed
     * versus merely not yet due.
     *
     * @param now                    this frame's arrival timestamp, from {@link #recordArrival()}
     * @param rateController         asked, not owned — see this class's own javadoc
     * @param effectiveInferenceFps  see {@link #targetFps}
     * @param maxInFlightInferences  see {@link #targetFps}
     * @param detectionRate          asked, not owned — the one window a missed deadline is recorded
     *                               into; same reasoning as {@code rateController} above
     * @return whether this frame serves the current deadline
     */
    boolean sampleDue(long now, DetectionRateController rateController, int effectiveInferenceFps,
                       int maxInFlightInferences, DetectionRateWindow detectionRate) {
        long intervalNanos = sampleIntervalNanos(rateController, effectiveInferenceFps, maxInFlightInferences);
        if (!sampleScheduleArmed) {
            sampleScheduleArmed = true;
            armScheduleAt(now, now + intervalNanos);
            return true;
        }
        if (now > lastSampleAtNanos && now < nextSampleAtNanos) {
            return false;
        }
        if (now <= lastSampleAtNanos) {
            // Degenerate clock (frozen, or stepped backwards): with no usable time base there is no
            // rate to limit, so fail OPEN and sample. Failing closed would silently stop detection
            // altogether the moment a clock skewed -- the loudest possible failure for the quietest
            // possible cause. The old frame-count stride was immune to this by construction; a
            // time-based schedule has to say what it does instead.
            armScheduleAt(now, now + intervalNanos);
            return true;
        }
        long advanced = nextSampleAtNanos + intervalNanos;
        if (advanced <= now) {
            detectionRate.recordMissedDeadlines((now - nextSampleAtNanos) / intervalNanos);
            advanced = now + intervalNanos;
        }
        armScheduleAt(now, advanced);
        return true;
    }

    /** Records that {@code now} served a deadline and that the next one falls at {@code nextAtNanos}. */
    private void armScheduleAt(long now, long nextAtNanos) {
        lastSampleAtNanos = now;
        nextSampleAtNanos = nextAtNanos;
    }

    /** The gap between sample deadlines for the currently targeted rate; at least one nanosecond. */
    private long sampleIntervalNanos(DetectionRateController rateController, int effectiveInferenceFps,
                                      int maxInFlightInferences) {
        return Math.max(1L, Math.round(
                NANOS_PER_SECOND / targetFps(rateController, effectiveInferenceFps, maxInFlightInferences)));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
