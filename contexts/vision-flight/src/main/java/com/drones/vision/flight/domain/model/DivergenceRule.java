package com.drones.vision.flight.domain.model;

import com.drones.vision.kernel.AssetId;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The per-asset divergence alarm state machine (docs/plans/done/VISUAL-GEO-V2-PLAN.md §4.5) — the
 * {@code FlightPhaseRule}/{@code GeofenceMonitor} shape: a pure, hand-fake-testable rule holding only
 * its own configured thresholds plus per-asset in-heap state, safe to share across every asset and
 * thread.
 *
 * <h2>The state machine (§4.5, frozen)</h2>
 * <pre>
 * Quiet   --qualifying CONFIRMED fix-->        Arming
 * Arming  --another qualifying CONFIRMED fix--> Arming
 * Arming  --non-qualifying CONFIRMED fix-->     Quiet
 * Arming  --consecutive-fixes reached-->        Alarm   (rising edge -- reported once)
 * Alarm   --qualifying CONFIRMED fix-->         Alarm   (divergentSince unchanged)
 * Alarm   --clear-after elapses, no qualifying fix--> Quiet
 * </pre>
 * Only {@link CorrectionStatus#CONFIRMED} corrections participate at all — a {@link
 * CorrectionStatus#PROBABLE} or {@link CorrectionStatus#NO_FIX} correction, or one with no {@code
 * separationMeters}/{@code sigmaMeters} (a telemetry gap), neither arms nor clears: {@link
 * #evaluate} simply reports the asset's current state back unchanged, with {@code risingEdge=false}.
 * "Qualifying" means {@code separationMeters > sigma * sigmaMeters} (§4.5).
 *
 * <p>The clear-after transition is <b>lazily evaluated</b>, not scheduler-driven — this class owns no
 * thread. It is only re-checked when a new {@code CONFIRMED} correction (qualifying or not) arrives
 * for that asset; an asset that stops reporting entirely stays latched in memory until one more
 * {@code CONFIRMED} correction arrives (the same honestly-documented limitation {@code
 * GeofenceMonitor}'s in-heap breach state already carries).
 *
 * <h2>State is in-heap only and resets on restart</h2>
 * Exactly the {@code GeofenceMonitor} precedent, documented rather than fixed: an alarm already
 * latched at boot is simply forgotten, and a fresh run of {@code consecutiveFixes} qualifying fixes is
 * needed to re-latch it.
 *
 * <h2>Threading</h2>
 * Per-asset state lives in a {@link ConcurrentHashMap}, updated via {@link ConcurrentHashMap#compute}
 * so a whole state transition is atomic; concurrent {@link #evaluate} calls for different assets never
 * contend, matching {@code GeofenceMonitor}'s own per-key concurrency model.
 */
public final class DivergenceRule {

    private final double sigma;
    private final int consecutiveFixes;
    private final Duration clearAfter;

    private final ConcurrentHashMap<AssetId, AssetState> stateByAsset = new ConcurrentHashMap<>();

    /**
     * @param sigma            how many combined 1-sigma widths a separation must exceed to qualify
     *                         ({@code vision.geo.visual.divergence.sigma}); must be positive and
     *                         finite
     * @param consecutiveFixes how many consecutive qualifying {@code CONFIRMED} fixes latch the alarm
     *                         ({@code vision.geo.visual.divergence.consecutive-fixes}); must be at
     *                         least 1
     * @param clearAfter       how long a latched alarm may go without a qualifying fix before it
     *                         clears ({@code vision.geo.visual.divergence.clear-after}); must be
     *                         positive
     */
    public DivergenceRule(double sigma, int consecutiveFixes, Duration clearAfter) {
        if (Double.isNaN(sigma) || Double.isInfinite(sigma) || sigma <= 0) {
            throw new IllegalArgumentException("DivergenceRule sigma must be finite and positive: " + sigma);
        }
        if (consecutiveFixes < 1) {
            throw new IllegalArgumentException("DivergenceRule consecutiveFixes must be at least 1: "
                    + consecutiveFixes);
        }
        if (clearAfter == null || clearAfter.isNegative() || clearAfter.isZero()) {
            throw new IllegalArgumentException("DivergenceRule clearAfter must be positive: " + clearAfter);
        }
        this.sigma = sigma;
        this.consecutiveFixes = consecutiveFixes;
        this.clearAfter = clearAfter;
    }

    /**
     * Feeds one correction's outcome into {@code assetId}'s alarm state machine.
     *
     * @param assetId          the aircraft this correction is about
     * @param status           the correction's Java-side status; only {@link
     *                         CorrectionStatus#CONFIRMED} can arm or clear
     * @param separationMeters the correction's separation from the raw fix, or {@code null} when
     *                         absent (a telemetry gap) — a {@code null} here never arms or clears
     * @param sigmaMeters      the combined 1-sigma the separation is compared against, or {@code
     *                         null} to match {@code separationMeters}
     * @param now              the wall-clock instant this correction was computed
     * @return the asset's alarm state after this correction, and whether this call was the rising
     *         edge that should raise exactly one event
     */
    public Outcome evaluate(AssetId assetId, CorrectionStatus status, Double separationMeters, Double sigmaMeters,
                             Instant now) {
        if (assetId == null) {
            throw new IllegalArgumentException("DivergenceRule assetId must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("DivergenceRule status must not be null");
        }
        if (now == null) {
            throw new IllegalArgumentException("DivergenceRule now must not be null");
        }

        boolean participates = status == CorrectionStatus.CONFIRMED && separationMeters != null && sigmaMeters != null;
        if (!participates) {
            AssetState current = stateByAsset.getOrDefault(assetId, AssetState.INITIAL);
            return new Outcome(current.alarmed, current.divergentSince, false);
        }

        boolean qualifies = separationMeters > sigma * sigmaMeters;
        boolean[] risingEdge = {false};
        AssetState next = stateByAsset.compute(assetId, (id, prev) -> {
            AssetState state = prev == null ? AssetState.INITIAL : prev;
            if (state.alarmed) {
                if (qualifies) {
                    return new AssetState(state.consecutiveQualifying, true, state.divergentSince, now);
                }
                Duration sinceLastQualifying = Duration.between(state.lastQualifyingAt, now);
                if (sinceLastQualifying.compareTo(clearAfter) >= 0) {
                    return AssetState.INITIAL;
                }
                return state;
            }
            if (!qualifies) {
                return AssetState.INITIAL;
            }
            int count = state.consecutiveQualifying + 1;
            if (count >= consecutiveFixes) {
                risingEdge[0] = true;
                return new AssetState(count, true, now, now);
            }
            return new AssetState(count, false, null, now);
        });
        return new Outcome(next.alarmed, next.divergentSince, risingEdge[0]);
    }

    /**
     * @param divergent      whether the alarm is latched after the evaluated correction
     * @param divergentSince when the currently-latched alarm first rose; non-null iff {@code
     *                       divergent}
     * @param risingEdge     {@code true} exactly on the one call that latched the alarm — re-arming
     *                       while already latched reports {@code false}
     */
    public record Outcome(boolean divergent, Instant divergentSince, boolean risingEdge) {
        public Outcome {
            if (divergent != (divergentSince != null)) {
                throw new IllegalArgumentException(
                        "DivergenceRule.Outcome divergentSince must be non-null iff divergent: divergent="
                                + divergent + ", divergentSince=" + divergentSince);
            }
            if (risingEdge && !divergent) {
                throw new IllegalArgumentException("DivergenceRule.Outcome risingEdge requires divergent=true");
            }
        }
    }

    private record AssetState(int consecutiveQualifying, boolean alarmed, Instant divergentSince,
                               Instant lastQualifyingAt) {
        static final AssetState INITIAL = new AssetState(0, false, null, null);
    }
}
