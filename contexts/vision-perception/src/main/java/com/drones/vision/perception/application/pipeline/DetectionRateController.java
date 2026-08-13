package com.drones.vision.perception.application.pipeline;

import com.drones.vision.perception.domain.model.CameraAttitude;
import com.drones.vision.perception.domain.model.Detection;

import java.util.List;
import java.util.Objects;

/**
 * Chooses the rate {@link StreamPipeline} samples at, from how fast the tracked target is about to
 * leave its own association budget (docs/plans/active/CV-RATE-CONTROL-PLAN.md &sect;2, closing gap 1 of
 * docs/conclusions/CV-RATE-BUDGET.md).
 *
 * <h2>The inequality this exists to keep true</h2>
 * Two observations of the same object associate while the box moves less than its IoU budget. For a
 * box of width {@code w} and a re-anchor threshold {@code t}, that budget is
 * <pre>    d = w (1 - t) / (1 + t)</pre>
 * so the rate that keeps displacement inside it is {@code displacement per second / d}. That is the
 * whole of "spend resources not to lose it", written as an inequality rather than as a constant —
 * and it is why a fixed 10 fps is both wasteful when nothing is moving and far too slow during a
 * 30&deg;/s yaw, which is the failure an operator actually reports.
 *
 * <h2>Everything is in frame widths, never pixels</h2>
 * The natural unit here is the normalized frame, and choosing it removes an entire dependency: the
 * detector's pixel width never enters the arithmetic. Box widths and {@code TrackRef} velocities
 * are already normalized, and a yaw rate converts by dividing by the horizontal field of view,
 * because the frame spans exactly that many degrees. A pixel formulation would have needed
 * {@code detectWidth} — an adapter's concern the application layer has no business knowing.
 *
 * <h2>Degrading rather than guessing</h2>
 * The two demand terms have different prerequisites, deliberately:
 * <ul>
 *   <li><b>Target motion</b> needs nothing but the tracks themselves, so it works on any
 *       deployment.</li>
 *   <li><b>Ego-motion</b> needs {@code cameraHfovDegrees}, which defaults to "unknown" and stays
 *       that way until a deployment describes its optics. With no field of view this term is
 *       simply absent — never estimated from a plausible-looking default, for the same reason
 *       {@code CameraAttitude#known()} refuses to invent one: a wrong field of view produces a
 *       confidently wrong rate.</li>
 * </ul>
 * With no tracks at all the demand is zero and the configured rate stands unchanged — correct
 * rather than merely safe, since a stream holding nothing has nothing to lose.
 *
 * <h2>Bounds</h2>
 * The demand only ever <b>raises</b> the rate: the operator's configured rate is the floor, so
 * asking for a rate is still honoured. Above it the ceiling is the smallest of the configured
 * maximum, the source's own frame rate (deadlines no frame can serve are waste), and measured
 * detector capacity ({@code maxInFlightInferences / round trip}), which makes the loop
 * self-limiting: a cv-service that slows down lowers the ceiling that is feeding it.
 *
 * <h2>Threading</h2>
 * {@link #targetFps} is read from the source's delivery thread on every frame and must stay cheap —
 * it is a handful of comparisons over {@code volatile} fields, never a percentile or a scan. The
 * two {@code observe} methods and {@link #recordRoundTrip} run on inference-completion threads;
 * the yaw differentiation they share is guarded by {@link #yawLock} because it is genuinely
 * multi-field state, exactly as {@code StreamPipeline}'s outage state is.
 */
final class DetectionRateController {

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    private static final double MILLIS_PER_SECOND = 1_000.0;
    private static final double NANOS_PER_MILLI = 1_000_000.0;
    private static final double DEGREES_PER_TURN = 360.0;
    private static final double HALF_TURN_DEGREES = 180.0;

    /**
     * Ignore a yaw delta measured across a gap longer than this: telemetry that stopped and resumed
     * would otherwise be differentiated as one enormous instantaneous slew. Two seconds is well
     * beyond any real sample interval and well inside any real telemetry outage.
     */
    private static final double MAX_YAW_DELTA_SECONDS = 2.0;

    /**
     * Below this the demand is snapped to exactly zero. Without it the EWMA decays toward zero
     * geometrically and never arrives, so a stream that has simply stopped tracking anything reports
     * a denormal like {@code 9.29e-38} on its API — arithmetically harmless, and indistinguishable
     * from a bug to whoever reads it. One frame per thousand seconds is not a rate.
     */
    private static final double NEGLIGIBLE_DEMAND_FPS = 1e-3;

    private final boolean enabled;
    private final double maxFps;
    private final double ewmaAlpha;
    private final double cameraHfovDegrees;

    /** Smoothed demand in fps; {@code 0} means "nothing tracked", which leaves the floor standing. */
    private volatile double demandFps = 0.0;

    /** Smoothed round trip in milliseconds; {@code 0} means "not measured yet", so no capacity cap. */
    private volatile double roundTripMillis = 0.0;

    private final Object yawLock = new Object();
    private double previousYawDegrees = Double.NaN;
    private long previousYawAtNanos;
    private double egoWidthsPerSecond = 0.0;

    DetectionRateController(AdaptiveRateSettings settings, double cameraHfovDegrees) {
        Objects.requireNonNull(settings, "settings must not be null");
        this.enabled = settings.enabled();
        this.maxFps = settings.maxFps();
        this.ewmaAlpha = settings.ewmaAlpha();
        this.cameraHfovDegrees = cameraHfovDegrees;
    }

    /**
     * The rate to sample at right now.
     *
     * @param floorFps    the operator's configured rate — never undercut, whatever the demand says
     * @param sourceFps   the source's measured arrival rate; a deadline no frame can serve is waste
     * @param maxInFlight {@link com.drones.vision.perception.domain.model.PipelineConfig#maxInFlightInferences()},
     *                    which with the measured round trip gives the detector's actual throughput
     * @return the rate, at least {@code floorFps} and never above the smallest applicable ceiling
     */
    double targetFps(double floorFps, double sourceFps, int maxInFlight) {
        double demand = demandFps;
        if (!enabled || demand <= floorFps) {
            return floorFps;
        }
        double ceiling = maxFps;
        if (sourceFps > 0.0) {
            ceiling = Math.min(ceiling, sourceFps);
        }
        double roundTrip = roundTripMillis;
        if (roundTrip > 0.0) {
            ceiling = Math.min(ceiling, maxInFlight * MILLIS_PER_SECOND / roundTrip);
        }
        return Math.max(floorFps, Math.min(demand, ceiling));
    }

    /** What the physics asked for before any ceiling applied — the {@code why} behind a chosen rate. */
    double demandFps() {
        return demandFps;
    }

    /**
     * Folds one completed round trip into the capacity estimate. The <i>mean</i> rather than a
     * percentile, deliberately: this figure bounds a rate the in-flight limiter already protects
     * against overshoot on, so the cheap smoothed average is the right tool and a sorted window
     * would be a per-frame cost for a decision that tolerates being approximately right.
     */
    void recordRoundTrip(long nanos) {
        if (nanos <= 0L) {
            return;
        }
        double millis = nanos / NANOS_PER_MILLI;
        double previous = roundTripMillis;
        roundTripMillis = previous <= 0.0 ? millis : ewmaAlpha * millis + (1 - ewmaAlpha) * previous;
    }

    /**
     * Differentiates the airframe heading into an ego-motion rate in frame widths per second.
     *
     * <p>A {@code null} attitude, an unknown field of view, or a first observation all leave the
     * ego term at zero rather than substituting anything. The delta is taken the short way around
     * the circle, so a heading crossing north reads as the few degrees it actually turned instead
     * of the 350 it appears to have.
     */
    void observeAttitude(CameraAttitude attitude, long atNanos) {
        if (attitude == null || !attitude.known() || cameraHfovDegrees <= 0.0) {
            return;
        }
        synchronized (yawLock) {
            double yaw = attitude.yawDegrees();
            if (!Double.isNaN(previousYawDegrees)) {
                double elapsedSeconds = (atNanos - previousYawAtNanos) / NANOS_PER_SECOND;
                if (elapsedSeconds > 0.0 && elapsedSeconds <= MAX_YAW_DELTA_SECONDS) {
                    double degreesPerSecond = Math.abs(shortestAngleDelta(previousYawDegrees, yaw)) / elapsedSeconds;
                    egoWidthsPerSecond = degreesPerSecond / cameraHfovDegrees;
                }
            }
            previousYawDegrees = yaw;
            previousYawAtNanos = atNanos;
        }
    }

    /**
     * Recomputes the demand from one completed result's tracked boxes.
     *
     * <p>The <b>smallest</b> tracked box sets the budget and the <b>fastest</b> sets the target
     * term, so the rate serves whichever object is hardest to hold rather than the average one —
     * the average is exactly the object that was never in danger. Untracked detections are ignored:
     * without an id there is no association to preserve, so they cannot be lost in the sense this
     * rate is defending against.
     *
     * @param detections        the completed result's detections, already label-filtered
     * @param redetectIouPercent the tracker's re-anchor IoU threshold as a percentage — the
     *                           {@code t} of the budget formula, and the reason a deployment that
     *                           loosens its tracker automatically needs a lower rate
     */
    void observeDetections(List<Detection> detections, int redetectIouPercent) {
        double narrowestWidth = Double.MAX_VALUE;
        double fastestWidthsPerSecond = 0.0;
        for (Detection detection : detections) {
            if (detection.track() == null) {
                continue;
            }
            narrowestWidth = Math.min(narrowestWidth, detection.box().width());
            double velocityX = detection.track().velocityX();
            double velocityY = detection.track().velocityY();
            fastestWidthsPerSecond =
                    Math.max(fastestWidthsPerSecond, Math.hypot(velocityX, velocityY));
        }
        if (narrowestWidth == Double.MAX_VALUE || narrowestWidth <= 0.0) {
            blendDemand(0.0);
            return;
        }
        double threshold = redetectIouPercent / 100.0;
        double budgetWidths = narrowestWidth * (1.0 - threshold) / (1.0 + threshold);
        if (budgetWidths <= 0.0) {
            blendDemand(0.0);
            return;
        }
        // Worst case rather than vector sum: a target moving with the camera pan is the lucky case,
        // and a rate chosen for the lucky case is the one that loses the unlucky one.
        double displacementWidthsPerSecond = egoWidthsPerSecond + fastestWidthsPerSecond;
        blendDemand(displacementWidthsPerSecond / budgetWidths);
    }

    /** Empties the estimate; called on a model re-arm, exactly as the windows are cleared. */
    void clear() {
        demandFps = 0.0;
        roundTripMillis = 0.0;
        synchronized (yawLock) {
            previousYawDegrees = Double.NaN;
            egoWidthsPerSecond = 0.0;
        }
    }

    private void blendDemand(double instantaneous) {
        double bounded = Double.isFinite(instantaneous) ? Math.max(0.0, instantaneous) : 0.0;
        double previous = demandFps;
        double blended = previous <= 0.0 ? bounded : ewmaAlpha * bounded + (1 - ewmaAlpha) * previous;
        demandFps = blended < NEGLIGIBLE_DEMAND_FPS ? 0.0 : blended;
    }

    /** Signed difference {@code to - from} folded into {@code (-180, 180]}. */
    private static double shortestAngleDelta(double from, double to) {
        double delta = (to - from) % DEGREES_PER_TURN;
        if (delta > HALF_TURN_DEGREES) {
            delta -= DEGREES_PER_TURN;
        } else if (delta <= -HALF_TURN_DEGREES) {
            delta += DEGREES_PER_TURN;
        }
        return delta;
    }
}
