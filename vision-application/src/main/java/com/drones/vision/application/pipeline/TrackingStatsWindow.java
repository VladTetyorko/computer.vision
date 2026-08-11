package com.drones.vision.application.pipeline;

import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.DetectorReason;
import com.drones.vision.perception.domain.model.TrackRef;
import com.drones.vision.perception.domain.model.TrackState;
import com.drones.vision.perception.domain.model.TrackingMode;
import com.drones.vision.perception.domain.model.TrackingTelemetry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Rolling counters over one stream's response stream (TRACKING-ORCHESTRATION.md &sect;2.3,
 * &sect;5.4): detector passes, tracker frames, duty ratio, tracker-latency p50/p95, the last
 * detector reason and a track-state histogram, all over a configurable window (default
 * {@value #DEFAULT_WINDOW_SECONDS}s). {@link #snapshot(TrackingMode)} renders them as a {@link
 * TrackingStats}, the read model behind {@code GET /api/streams/{id}/tracks}'s {@code stats} object.
 *
 * <h2>Why this is a peer of {@link TrackBook}, not a field on it</h2>
 * The two are separate entries on {@link StreamPipeline}'s existing fan-out — a deliberate deviation
 * from docs/plans/done/TRACKING-PLAN.md &sect;5.E's "one line", recorded in TRACKING-ORCHESTRATION.md &sect;2.3.
 * Folding these counters into {@link TrackBook} would give that class two responsibilities in order
 * to save one line in a list that already has five entries. They genuinely differ: the book is a
 * read model of the tracks that <i>exist right now</i>, retained by track lifetime and carrying each
 * track's full latest {@link Detection}; this is an <i>aggregate over time</i>, retained by a fixed
 * window and carrying nothing but counts and timings. A {@code LOST} track leaves the book at once
 * yet keeps counting here until it ages out of the window — the same fact, correctly answered
 * differently by the two questions.
 *
 * <h2>What it never does</h2>
 * Counters only: no formatting, no logging, no consumer-shaped field (invariant P3 — a stat is a
 * pipeline fact, a count or a timing, never a UI shape). {@link TrackingStats#mode()} is the one
 * value this class does not own and therefore does not cache: the mode is a hot knob that can change
 * within a window, so it is passed in at {@link #snapshot(TrackingMode) snapshot} time.
 *
 * <h2>Time</h2>
 * Samples are stamped with {@link DetectionResult#capturedAt()} and the window is measured against
 * the newest stamp accepted so far — the frame's own timebase, the same one {@link TrackBook} and
 * {@link DetectionEventEngine} use, never wall-clock time. No clock is injected because none is
 * needed, which makes tests exactly deterministic. The honest consequence: on a stream that has gone
 * quiet, a snapshot keeps describing the last {@code window} of <i>traffic</i> rather than decaying
 * to zero — which is what the flow strip wants to show anyway ("this is what it was doing"), and
 * what the {@code tracks} list beside it already contradicts if the stream really is dead.
 *
 * <h2>Threading</h2>
 * Every method is {@code synchronized}, for the same reason {@link TrackBook}'s are: {@link #accept}
 * runs on an inference-completion thread while {@link #snapshot} is read from an HTTP thread.
 */
final class TrackingStatsWindow {

    /** @see StreamPipelineSettings#trackingStatsWindow() */
    static final int DEFAULT_WINDOW_SECONDS = 30;

    /**
     * Hard cap on retained samples, a safety net rather than a tuning knob: the window is normally
     * bounded by time ({@value #DEFAULT_WINDOW_SECONDS}s &times; at most a few tens of frames per
     * second), and this bounds memory anyway should a source ever report a degenerate {@code
     * capturedAt} that defeats time-based eviction.
     */
    private static final int MAX_SAMPLES = 4_000;

    private final Duration window;

    private final Deque<Sample> samples = new ArrayDeque<>();

    /** The newest {@link DetectionResult#capturedAt()} accepted so far — this window's notion of "now". */
    private Instant latestObservedAt;

    /** One accepted result's tracking facts; {@code tracks} holds this frame's (id, state) pairs, in wire order. */
    private record Sample(Instant at, boolean detectorRan, DetectorReason reason, long trackerNanos,
                           String engineId, long lockedTrackId, List<TrackObservation> tracks) {
    }

    private record TrackObservation(long trackId, TrackState state) {
    }

    /** Uses {@link StreamPipelineSettings#defaults()}'s stats window. */
    TrackingStatsWindow() {
        this(StreamPipelineSettings.defaults().trackingStatsWindow());
    }

    /**
     * @param window how far back the counters reach; must be positive. Supplied by {@link
     *               StreamPipeline} from {@link StreamPipelineSettings#trackingStatsWindow()}, which
     *               {@code vision-app} binds to {@code vision.tracking.stats-window-seconds}
     */
    TrackingStatsWindow(Duration window) {
        Objects.requireNonNull(window, "window must not be null");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive, was " + window);
        }
        this.window = window;
    }

    /**
     * Records one completed result's tracking facts and evicts whatever has aged out.
     *
     * <p>A result whose {@link DetectionResult#tracking()} is {@code null} — tracking was off for
     * that frame — is ignored entirely rather than counted as a zero-latency detector pass: an
     * untracked stream must report an empty window, not a 100% duty ratio.
     *
     * @param result the completed result to count; never {@code null}
     */
    synchronized void accept(DetectionResult result) {
        Objects.requireNonNull(result, "result must not be null");
        TrackingTelemetry telemetry = result.tracking();
        if (telemetry == null) {
            return;
        }
        Instant at = result.capturedAt();
        if (latestObservedAt == null || at.isAfter(latestObservedAt)) {
            latestObservedAt = at;
        }
        samples.addLast(new Sample(at, telemetry.detectorRan(), telemetry.reason(),
                telemetry.trackerLatency().toNanos(), telemetry.engineId(), telemetry.lockedTrackId(),
                observationsIn(result)));
        evict();
    }

    private static List<TrackObservation> observationsIn(DetectionResult result) {
        List<TrackObservation> observations = new ArrayList<>(result.detections().size());
        for (Detection detection : result.detections()) {
            TrackRef track = detection.track();
            if (track != null) {
                observations.add(new TrackObservation(track.trackId(), track.state()));
            }
        }
        return List.copyOf(observations);
    }

    private void evict() {
        Instant staleBefore = latestObservedAt.minus(window);
        while (!samples.isEmpty()
                && (samples.size() > MAX_SAMPLES || !samples.peekFirst().at().isAfter(staleBefore))) {
            samples.removeFirst();
        }
    }

    /** Empties the window; called by {@link StreamPipeline#updateConfig} on a model re-arm. */
    synchronized void clear() {
        samples.clear();
        latestObservedAt = null;
    }

    /**
     * @param mode the stream's currently configured tracking mode — see {@link TrackingStats#mode()}
     *             for why it is a parameter rather than a field
     * @return the counters over the current window; {@link TrackingStats#empty} when nothing has
     *         been sampled. Never {@code null}, never throws.
     */
    synchronized TrackingStats snapshot(TrackingMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        if (samples.isEmpty()) {
            return TrackingStats.empty(mode, window);
        }
        long detectorPasses = 0;
        long trackerFrames = 0;
        long[] trackerNanos = new long[samples.size()];
        int index = 0;
        for (Sample sample : samples) {
            if (sample.detectorRan()) {
                detectorPasses++;
            } else {
                trackerFrames++;
            }
            trackerNanos[index++] = sample.trackerNanos();
        }
        Arrays.sort(trackerNanos);
        Sample newest = samples.peekLast();
        long total = detectorPasses + trackerFrames;
        return new TrackingStats(mode, newest.engineId(), window, detectorPasses, trackerFrames,
                total == 0 ? 0.0 : (double) detectorPasses / total,
                percentileMillis(trackerNanos, 50), percentileMillis(trackerNanos, 95),
                lastDetectorReason(), newest.lockedTrackId(), byState());
    }

    /** The newest sample in the window that actually spent a detector pass, or {@code null} if none did. */
    private DetectorReason lastDetectorReason() {
        for (var it = samples.descendingIterator(); it.hasNext(); ) {
            Sample sample = it.next();
            if (sample.detectorRan()) {
                return sample.reason();
            }
        }
        return null;
    }

    /**
     * Counts distinct track ids in the window by their <b>newest</b> observed state: walking samples
     * newest-first and taking the first state seen per id is what makes a track that was {@code
     * COASTING} and is now {@code CONFIRMED} count once, as {@code CONFIRMED}.
     */
    private Map<TrackState, Integer> byState() {
        Map<TrackState, Integer> counts = TrackingStats.zeroedStates();
        Set<Long> counted = new HashSet<>();
        for (var it = samples.descendingIterator(); it.hasNext(); ) {
            for (TrackObservation observation : it.next().tracks()) {
                if (counted.add(observation.trackId())) {
                    counts.merge(observation.state(), 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    /**
     * Nearest-rank percentile over an <b>already-sorted</b> {@code nanos}, converted to fractional
     * milliseconds. {@code 0.0} for an empty sample set.
     */
    private static double percentileMillis(long[] sortedNanos, int percentile) {
        if (sortedNanos.length == 0) {
            return 0.0;
        }
        int rank = (int) Math.ceil(percentile / 100.0 * sortedNanos.length);
        int index = Math.min(sortedNanos.length - 1, Math.max(0, rank - 1));
        return sortedNanos[index] / 1_000_000.0;
    }
}
