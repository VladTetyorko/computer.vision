package com.drones.vision.perception.application.pipeline;

import com.drones.vision.perception.domain.model.PullTelemetry;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
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
 * <h2>Pull mode (docs/plans/done/MEDIA-SOT-PLAN.md &sect;7, wave M5)</h2>
 * A window is either {@link Transport#PUSH} or {@link Transport#PULL} for its whole life, decided once
 * at construction — a running stream is one or the other, never both. {@link #record}/{@link
 * #recordMissedDeadlines} are the <b>push</b>-mode sampler's own counters and are never called by a
 * pull-mode pipeline (there is no local sampler in pull mode — the worker runs its own, ported,
 * deadline sampler); {@link #recordPull} is the pull-mode counterpart, folding the worker's
 * self-reported figures straight in rather than re-deriving them from local sampler decisions — except
 * {@code submitted}, which {@link #recordPull} counts itself (docs/conclusions/MEDIA-SOT-RESULTS.md
 * &sect;6): pull mode delivers exactly one {@code DetectionResult} per inferred frame, so every call is
 * one submission, with nothing to read off the wire. {@link #snapshot} reads whichever set applies.
 *
 * <h2>Threading</h2>
 * Every method is {@code synchronized}: {@link #record}/{@link #recordPull} run on the source's
 * delivery thread while {@link #snapshot} is read from an HTTP thread.
 */
final class DetectionRateWindow {

    /** Memory safety net, not a knob — the window is normally bounded by time. @see PipelineLatencyWindow */
    private static final int MAX_SAMPLES = 4_000;

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    /** Which loop counted these figures — decided once, at construction, never mixed. */
    enum Transport {
        /** The JVM's own sampler decides, and counts what it decided. */
        PUSH,
        /** The worker's (ported) sampler decides; this window mirrors its self-reported counters. */
        PULL
    }

    /** What the sampler did with one served deadline (push mode only). */
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
    private final Transport transport;

    private final Deque<Sample> samples = new ArrayDeque<>();

    private record Sample(long atNanos, Outcome outcome) {
    }

    /** Deadlines no frame served, counted separately: they have no sample to hang on the deque. Push mode only. */
    private long missedDeadlines;

    // Pull-mode state (Transport.PULL only): the worker restates these on every response, so the
    // latest report is simply kept rather than derived from anything local (§7 -- there is no JVM
    // sampler to count in pull mode).
    private volatile double pullSourceFps = 0.0;
    private volatile double pullAchievedFps = 0.0;
    private volatile long pullDroppedFrames = 0L;
    private volatile long pullMissedDeadlines = 0L;

    /**
     * {@code submitted}, pull mode's own way (docs/conclusions/MEDIA-SOT-RESULTS.md &sect;6): unlike
     * {@code pullDroppedFrames}/{@code pullMissedDeadlines}, this is never a wire field — pull mode
     * delivers exactly one {@code DetectionResult} per inferred frame, so every {@link #recordPull}
     * call already <b>is</b> one submission. Cumulative since the stream started, not windowed, for
     * the same reason {@code pullDroppedFrames} is not: it is compared directly against that
     * worker-reported cumulative counter in {@link DetectionRate#due()}, and mixing a windowed
     * numerator against a lifetime denominator would make {@link DetectionRate#dropRatio()} wrong in
     * the other direction. Before this field existed, {@link #snapshotPull} hard-coded {@code 0L}
     * here, which pinned {@code dropRatio()} at {@code 1.0} for any pull stream with even one
     * legitimate worker-side drop — the defect this field fixes.
     */
    private volatile long pullSubmitted = 0L;

    /** Rolling {@code decode_millis} samples, windowed exactly like {@link #samples} (pull mode only). */
    private final Deque<DecodeSample> decodeMillisSamples = new ArrayDeque<>();

    private record DecodeSample(long atNanos, long decodeMillis) {
    }

    /**
     * @param window how far back the counters reach; must be positive. {@link StreamPipeline} passes
     *               the same window the latency and tracking counters use — one observability window
     *               per stream, deliberately not a third knob that could drift out of step.
     */
    DetectionRateWindow(Duration window) {
        this(window, Transport.PUSH);
    }

    /**
     * @param window    as the 1-argument constructor
     * @param transport which loop's counters this window reports (docs/plans/done/MEDIA-SOT-PLAN.md wave M5);
     *                  fixed for the window's whole life
     */
    DetectionRateWindow(Duration window, Transport transport) {
        Objects.requireNonNull(window, "window must not be null");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive, was " + window);
        }
        this.window = window;
        this.windowNanos = window.toNanos();
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
    }

    /** Records one served deadline and evicts whatever has aged out. Push mode only. */
    synchronized void record(Outcome outcome, long atNanos) {
        samples.addLast(new Sample(atNanos, Objects.requireNonNull(outcome, "outcome must not be null")));
        evict(atNanos);
    }

    /**
     * Records deadlines that came and went with no frame to serve them. Unlike {@link #record} this
     * is a running total rather than a windowed one: it is a starvation signal whose absolute count
     * over the stream's life is what matters, and a starving source produces no events to evict by.
     * Push mode only.
     */
    synchronized void recordMissedDeadlines(long count) {
        if (count > 0L) {
            missedDeadlines += count;
        }
    }

    /**
     * Folds one pull result's worker-reported diagnostics in (docs/plans/done/MEDIA-SOT-PLAN.md &sect;7, D12):
     * {@code source_fps}/{@code achieved_fps}/{@code missed_deadlines}/{@code dropped_frames} are kept
     * as the worker's latest restatement (cumulative counters, not deltas — nothing to sum), while
     * {@code decode_millis} joins a rolling window for {@link DetectionRate#decodeMillisP50()}.
     *
     * <p>One call to this method <b>is</b> one submission (docs/conclusions/MEDIA-SOT-RESULTS.md
     * &sect;6): pull mode delivers exactly one {@code DetectionResult} per inferred frame, and this is
     * that result arriving, so {@code pullSubmitted} is counted here directly rather than derived from
     * anything on the wire.
     */
    synchronized void recordPull(PullTelemetry telemetry, long atNanos) {
        Objects.requireNonNull(telemetry, "telemetry must not be null");
        pullSubmitted++;
        pullSourceFps = telemetry.sourceFps();
        pullAchievedFps = telemetry.achievedFps();
        pullDroppedFrames = telemetry.droppedFrames();
        pullMissedDeadlines = telemetry.missedDeadlines();
        decodeMillisSamples.addLast(new DecodeSample(atNanos, telemetry.decodeMillis()));
        evictDecode(atNanos);
    }

    private void evict(long nowNanos) {
        long staleBefore = nowNanos - windowNanos;
        while (!samples.isEmpty()
                && (samples.size() > MAX_SAMPLES || samples.peekFirst().atNanos() <= staleBefore)) {
            samples.removeFirst();
        }
    }

    private void evictDecode(long nowNanos) {
        long staleBefore = nowNanos - windowNanos;
        while (!decodeMillisSamples.isEmpty()
                && (decodeMillisSamples.size() > MAX_SAMPLES
                        || decodeMillisSamples.peekFirst().atNanos() <= staleBefore)) {
            decodeMillisSamples.removeFirst();
        }
    }

    /** Empties the window; called by {@link StreamPipeline#updateConfig} on a model re-arm. */
    synchronized void clear() {
        samples.clear();
        missedDeadlines = 0L;
        decodeMillisSamples.clear();
        pullSourceFps = 0.0;
        pullAchievedFps = 0.0;
        pullDroppedFrames = 0L;
        pullMissedDeadlines = 0L;
        pullSubmitted = 0L;
    }

    /**
     * @return the counters over the current window. Never {@code null}, never throws. In {@link
     *         Transport#PULL}, {@code sourceFps} is the worker's own self-report, not {@code
     *         sourceFps} (the JVM-measured/assumed video-source rate passed in — meaningless for a
     *         proxied stream that never flows through this JVM at all); {@code targetFps}/{@code
     *         demandFps} still come from the caller, since the Java rate controller runs unchanged in
     *         pull mode (docs/plans/done/MEDIA-SOT-PLAN.md &sect;7) and its output still travels on the wire.
     */
    synchronized DetectionRate snapshot(double sourceFps, double targetFps, double demandFps) {
        return transport == Transport.PULL
                ? snapshotPull(targetFps, demandFps)
                : snapshotPush(sourceFps, targetFps, demandFps);
    }

    private DetectionRate snapshotPush(double sourceFps, double targetFps, double demandFps) {
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

    private DetectionRate snapshotPull(double targetFps, double demandFps) {
        return new DetectionRate(window, pullSourceFps, targetFps, demandFps, pullAchievedFps, pullSubmitted,
                pullDroppedFrames, 0L, pullMissedDeadlines, DetectionRate.TRANSPORT_PULL, decodeMillisP50());
    }

    /** Median {@code decode_millis} over the window; {@code 0} when nothing has been reported yet. */
    private double decodeMillisP50() {
        if (decodeMillisSamples.isEmpty()) {
            return 0.0;
        }
        long[] sorted = new long[decodeMillisSamples.size()];
        int index = 0;
        for (DecodeSample sample : decodeMillisSamples) {
            sorted[index++] = sample.decodeMillis();
        }
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(0.5 * sorted.length) - 1;
        return sorted[Math.clamp(rank, 0, sorted.length - 1)];
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
