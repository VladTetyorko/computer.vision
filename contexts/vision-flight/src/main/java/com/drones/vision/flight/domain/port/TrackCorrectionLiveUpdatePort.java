package com.drones.vision.flight.domain.port;

import com.drones.vision.flight.domain.model.TrackCorrection;
import com.drones.vision.kernel.AssetId;

/**
 * Driven port: announce that a {@link TrackCorrection} was produced, so a driving adapter can push it
 * to connected viewers on the {@code geo:<assetId>} live topic (docs/plans/done/VISUAL-GEO-V2-PLAN.md
 * §3.4, D11). Mirrors {@link TelemetryLiveUpdatePort}'s contract exactly — this context's application
 * layer knows nothing about SSE, connections, topics, or resume/replay; that is entirely a driving
 * adapter's concern.
 *
 * <h2>Contract</h2>
 * Must return quickly and must not throw for an ordinary delivery failure — a disconnected viewer, a
 * full connection registry, or the feature being disabled entirely (a no-op implementation) must
 * never surface as an exception on the caller's own hot path.
 *
 * <h2>Threading</h2>
 * Called from {@code DefaultTrackCorrectionService#submit}, once per submitted {@code VisualFix} — a
 * hot path — so implementations must be cheap and effectively fire-and-forget: hand off to a
 * background dispatcher for any real I/O rather than doing it on the calling thread. Safe for
 * concurrent use from many assets at once.
 */
public interface TrackCorrectionLiveUpdatePort {

    /**
     * Announces that one {@link TrackCorrection} was produced for {@code assetId}, {@link
     * com.drones.vision.flight.domain.model.CorrectionStatus#NO_FIX} included.
     *
     * @param assetId    the asset the correction belongs to
     * @param correction the newly produced correction
     */
    void publishCorrection(AssetId assetId, TrackCorrection correction);
}
