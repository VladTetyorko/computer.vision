package com.drones.vision.perception.application.pipeline;

import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.TrackRef;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Smooths burned-in detection boxes between completed inferences by
 * extrapolating forward to the timestamp of the frame currently being
 * published, instead of freezing the latest result's raw boxes until the
 * next one lands (docs/main/CYCLES-PLAN.md &sect;12, CP-c).
 *
 * <p>Keeps only the two most recently {@linkplain #accept accepted} results
 * — the previous one ({@code P}) and the latest ({@code L}). {@link
 * #at(Instant)} matches {@code L}'s detections to {@code P}'s (see "Matching"
 * below) and extrapolates every matched detection's center at the resulting
 * velocity, capping the horizon at {@value #MAX_EXTRAPOLATION_MILLIS}ms past
 * {@code L}'s capture time so a stalled/outaged detector freezes rather than
 * running boxes off the frame forever. Unmatched {@code L} detections are
 * returned unchanged; {@code P}-only detections (nothing in {@code L} matched
 * them) are dropped. A matched detection keeps its label, confidence, model
 * and {@link Detection#track() track facts} — only the box's origin moves.
 *
 * <h2>Matching (docs/plans/done/TRACKING-PLAN.md &sect;5.F)</h2>
 * Two passes, in this order:
 * <ol>
 *   <li><b>By track id, exactly, with no gate.</b> When a detection on each
 *       side carries a {@link com.drones.vision.perception.domain.model.TrackRef} and the
 *       two ids are equal, they are the same object — cv-service's tracker
 *       already decided that, and no distance heuristic can improve on an
 *       identity. This is what makes velocity correct through an occlusion
 *       and across two same-label objects that cross, where the distance gate
 *       below would confidently swap them.</li>
 *   <li><b>By nearest normalized box-center, gated</b>, over whatever the
 *       first pass left unmatched: same {@code label}, within {@value
 *       #MATCH_GATE_DISTANCE} center distance, closest pairs assigned first,
 *       each detection used at most once. Unchanged from before tracking
 *       existed — with tracking off, {@code track()} is {@code null}
 *       everywhere, pass 1 matches nothing and the behavior is byte-identical
 *       to what it always was.
 *       <p>One pair is deliberately <b>never</b> considered here: two
 *       detections that both carry ids, when those ids differ. The tracker has
 *       already stated they are different objects, so matching them on
 *       proximity would reintroduce exactly the swap pass 1 exists to
 *       prevent.</li>
 * </ol>
 *
 * <p><b>Threading:</b> {@link #accept} and {@link #at} are both {@code
 * synchronized} — both are called at most once per sampled/published frame
 * by {@link StreamPipeline}, never on a hot loop.
 */
final class DetectionExtrapolator {

    /**
     * How far past {@code L}'s capture time extrapolation runs before the output freezes, and the
     * default max normalized box-center distance for a {@code P}&rarr;{@code L} match of the same
     * label — the {@link StreamPipelineSettings#defaults()} values, exposed here purely so
     * same-package tests can assert against them by name (docs/plans/active/LAYERING-REFACTOR-PLAN.md &sect;1.3
     * config extraction); this instance's own working values always come from its constructor, not
     * these constants.
     */
    static final long MAX_EXTRAPOLATION_MILLIS = StreamPipelineSettings.defaults().extrapolationMaxMillis();

    /** @see #MAX_EXTRAPOLATION_MILLIS */
    private static final double MATCH_GATE_DISTANCE = StreamPipelineSettings.defaults().extrapolationMatchGate();

    private final long maxExtrapolationMillis;
    private final double matchGateDistance;

    /** Uses {@link StreamPipelineSettings#defaults()}'s extrapolation tuning. */
    DetectionExtrapolator() {
        this(MAX_EXTRAPOLATION_MILLIS, MATCH_GATE_DISTANCE);
    }

    /**
     * @param maxExtrapolationMillis how far past {@code L}'s capture time extrapolation runs before
     *                               the output freezes
     * @param matchGateDistance      max normalized box-center distance for a {@code P}&rarr;{@code
     *                               L} match of the same label
     */
    DetectionExtrapolator(long maxExtrapolationMillis, double matchGateDistance) {
        this.maxExtrapolationMillis = maxExtrapolationMillis;
        this.matchGateDistance = matchGateDistance;
    }

    private DetectionResult previous;
    private DetectionResult latest;

    /**
     * Records a newly completed detection result as the latest one, demoting
     * the current latest to previous.
     *
     * @param result the completed result; a {@code frameSequence} that is
     *               &le; the current latest's is ignored — inference
     *               completions can land out of order relative to when the
     *               frames were sampled
     */
    synchronized void accept(DetectionResult result) {
        Objects.requireNonNull(result, "result must not be null");
        if (latest != null && result.frameSequence() <= latest.frameSequence()) {
            return;
        }
        previous = latest;
        latest = result;
    }

    /**
     * Clears both remembered results (docs/plans/done/CV-CONTROL-PLAN.md &sect;A) — called by {@link
     * StreamPipeline#updateConfig} on a model-id change, since a box matched/extrapolated across a
     * model swap would blend two different models' outputs. After this call, {@link #at} behaves
     * exactly as it does before any result has ever been {@linkplain #accept accepted}.
     */
    synchronized void reset() {
        previous = null;
        latest = null;
    }

    /**
     * @param t the timestamp to extrapolate boxes to, typically the
     *          currently-publishing frame's {@code capturedAt}
     * @return no result accepted yet: an empty list. Only one result
     *         accepted: its detections, unchanged. Two results accepted:
     *         {@code L}'s detections with matched ones' boxes extrapolated
     *         toward {@code t} (capped at {@value #MAX_EXTRAPOLATION_MILLIS}ms
     *         past {@code L.capturedAt()}) and unmatched ones returned as-is.
     *         Never {@code null}, never throws.
     */
    synchronized List<Detection> at(Instant t) {
        Objects.requireNonNull(t, "t must not be null");
        if (latest == null) {
            return List.of();
        }
        List<Detection> latestDetections = latest.detections();
        if (previous == null || latestDetections.isEmpty()) {
            return latestDetections;
        }

        double deltaSeconds = secondsBetween(previous.capturedAt(), latest.capturedAt());
        if (deltaSeconds <= 0) {
            // Degenerate/duplicate timestamps: no meaningful velocity, so nothing to extrapolate.
            return latestDetections;
        }

        Instant cap = latest.capturedAt().plusMillis(maxExtrapolationMillis);
        Instant target = t.isAfter(cap) ? cap : t;
        double extrapolateSeconds = secondsBetween(latest.capturedAt(), target);

        List<Detection> previousDetections = previous.detections();
        int[] matchedPreviousIndex = match(latestDetections, previousDetections, matchGateDistance);

        List<Detection> result = new ArrayList<>(latestDetections.size());
        for (int i = 0; i < latestDetections.size(); i++) {
            Detection l = latestDetections.get(i);
            int pIndex = matchedPreviousIndex[i];
            result.add(pIndex < 0
                    ? l
                    : extrapolate(l, previousDetections.get(pIndex), deltaSeconds, extrapolateSeconds));
        }
        return List.copyOf(result);
    }

    /**
     * Matches {@code latestDetections} to {@code previousDetections} — first by track id, then by
     * gated nearest center over the remainder. See the class javadoc's "Matching" section for the
     * two passes and why the second one skips id-vs-id pairs.
     *
     * @return one entry per {@code latestDetections} index, holding the
     *         matched {@code previousDetections} index or {@code -1}
     */
    private static int[] match(List<Detection> latestDetections, List<Detection> previousDetections,
                                double matchGateDistance) {
        int[] matchedPreviousIndex = new int[latestDetections.size()];
        Arrays.fill(matchedPreviousIndex, -1);
        boolean[] previousUsed = new boolean[previousDetections.size()];

        matchByTrackId(latestDetections, previousDetections, matchedPreviousIndex, previousUsed);
        matchByNearestCenter(latestDetections, previousDetections, matchGateDistance, matchedPreviousIndex,
                previousUsed);
        return matchedPreviousIndex;
    }

    /** Pass 1: equal, non-{@code null} track ids on both sides are the same object — exact, ungated. */
    private static void matchByTrackId(List<Detection> latestDetections, List<Detection> previousDetections,
                                        int[] matchedPreviousIndex, boolean[] previousUsed) {
        Map<Long, Integer> previousIndexByTrackId = new HashMap<>();
        for (int pi = 0; pi < previousDetections.size(); pi++) {
            TrackRef track = previousDetections.get(pi).track();
            if (track != null) {
                previousIndexByTrackId.putIfAbsent(track.trackId(), pi);
            }
        }
        if (previousIndexByTrackId.isEmpty()) {
            return;
        }
        for (int li = 0; li < latestDetections.size(); li++) {
            TrackRef track = latestDetections.get(li).track();
            if (track == null) {
                continue;
            }
            Integer pi = previousIndexByTrackId.get(track.trackId());
            if (pi != null && !previousUsed[pi]) {
                matchedPreviousIndex[li] = pi;
                previousUsed[pi] = true;
            }
        }
    }

    /**
     * Pass 2: greedy nearest-center bipartite matching over what pass 1 left unmatched. Every
     * same-label candidate pair within {@code matchGateDistance} normalized center distance is
     * considered — <b>except</b> a pair whose two sides both carry a track id, which the tracker has
     * already declared to be different objects — closest pairs are assigned first, and an
     * already-assigned detection on either side is never reused.
     */
    private static void matchByNearestCenter(List<Detection> latestDetections, List<Detection> previousDetections,
                                              double matchGateDistance, int[] matchedPreviousIndex,
                                              boolean[] previousUsed) {
        record Candidate(int latestIndex, int previousIndex, double distance) { }

        List<Candidate> candidates = new ArrayList<>();
        for (int li = 0; li < latestDetections.size(); li++) {
            if (matchedPreviousIndex[li] >= 0) {
                continue;
            }
            Detection l = latestDetections.get(li);
            double[] lCenter = center(l.box());
            for (int pi = 0; pi < previousDetections.size(); pi++) {
                Detection p = previousDetections.get(pi);
                if (previousUsed[pi] || !p.label().equals(l.label())
                        || (l.track() != null && p.track() != null)) {
                    continue;
                }
                double distance = distance(lCenter, center(p.box()));
                if (distance <= matchGateDistance) {
                    candidates.add(new Candidate(li, pi, distance));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::distance));

        for (Candidate c : candidates) {
            if (matchedPreviousIndex[c.latestIndex()] < 0 && !previousUsed[c.previousIndex()]) {
                matchedPreviousIndex[c.latestIndex()] = c.previousIndex();
                previousUsed[c.previousIndex()] = true;
            }
        }
    }

    /**
     * Extrapolates {@code l}'s box center along the velocity implied by
     * {@code p}&rarr;{@code l} over {@code deltaSeconds}, {@code
     * extrapolateSeconds} further ahead (already capped by the caller).
     * {@code l}'s width/height, label, confidence, model and {@link
     * Detection#track() track facts} are kept as-is; only the box's {@code
     * x}/{@code y} move, clamped back into {@code BoundingBox}'s {@code [0,1]}
     * per-component invariant. Carrying {@code track} through matters: the
     * burned-in overlay reads the id and state off exactly this detection, so
     * dropping it here would make every extrapolated box lose its {@code #id}
     * and its {@code COASTING} dashes between completed inferences.
     */
    private static Detection extrapolate(Detection l, Detection p, double deltaSeconds, double extrapolateSeconds) {
        double[] lCenter = center(l.box());
        double[] pCenter = center(p.box());
        double velocityX = (lCenter[0] - pCenter[0]) / deltaSeconds;
        double velocityY = (lCenter[1] - pCenter[1]) / deltaSeconds;

        double newCenterX = lCenter[0] + velocityX * extrapolateSeconds;
        double newCenterY = lCenter[1] + velocityY * extrapolateSeconds;

        double newX = clampUnit(newCenterX - l.box().width() / 2.0);
        double newY = clampUnit(newCenterY - l.box().height() / 2.0);

        BoundingBox extrapolatedBox = new BoundingBox(newX, newY, l.box().width(), l.box().height());
        return new Detection(l.label(), l.confidence(), extrapolatedBox, l.model(), l.track());
    }

    private static double[] center(BoundingBox box) {
        return new double[] {box.x() + box.width() / 2.0, box.y() + box.height() / 2.0};
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static double clampUnit(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double secondsBetween(Instant from, Instant to) {
        return Duration.between(from, to).toNanos() / 1_000_000_000.0;
    }
}
