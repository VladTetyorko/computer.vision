package com.drones.vision.app.devsupport;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.perception.domain.model.DetectionEvent;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.port.DetectionLiveUpdatePort;
import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventLiveUpdatePort;
import com.drones.vision.map.domain.model.MapEvent;
import com.drones.vision.map.domain.port.MapLiveUpdatePort;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.flight.domain.model.TrackCorrection;
import com.drones.vision.flight.domain.port.TelemetryLiveUpdatePort;
import com.drones.vision.flight.domain.port.TrackCorrectionLiveUpdatePort;
import com.drones.vision.warehouse.domain.port.FleetLiveUpdatePort;

/**
 * No-op implementation of all six live-update ports the former god-port {@code
 * LiveUpdatePublisherPort} split into (docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6b), plus
 * {@link TrackCorrectionLiveUpdatePort} added for visual geolocation's {@code geo:<assetId>} topic
 * (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.4/D11/H5) — every method is a no-op. Wired when
 * {@code vision.live.enabled=false} (docs/plans/done/REALTIME-PLAN.md
 * §4, item 4) — {@code vision-api}'s {@code /api/live} endpoint itself 404s in that case (its
 * controller/registry beans are conditionally absent), but the application layer ({@code
 * StreamPipeline}/{@code UsageTracker}/{@code DefaultStreamService}/{@code DefaultTrackCorrectionService})
 * still needs <em>some</em> implementation of whichever one of the six ports it depends on to
 * satisfy its constructor, exactly as {@link NoopDetectionPort}/{@link NoopStreamPublisher} do for
 * their own ports when their feature is disabled. Implementing all six on one class (rather than six
 * separate no-op classes) mirrors {@code LiveUpdateRegistry}'s own shape — the one class that would
 * otherwise announce this fact for real.
 */
public final class NoopLiveUpdatePublisher implements FleetLiveUpdatePort, TelemetryLiveUpdatePort,
        DetectionLiveUpdatePort, MapLiveUpdatePort, EventLiveUpdatePort, TrackCorrectionLiveUpdatePort {

    @Override
    public void publishFleetChanged() {
        // no-op
    }

    @Override
    public void publishTelemetryAppended(AssetId assetId, Telemetry sample) {
        // no-op
    }

    @Override
    public void publishDetections(AssetId assetId, DetectionResult result) {
        // no-op
    }

    @Override
    public void publishEvent(Event event) {
        // no-op
    }

    @Override
    public void publishDetectionEvent(DetectionEvent event) {
        // no-op
    }

    @Override
    public void publishMapEvent(MapEvent event) {
        // no-op
    }

    @Override
    public void publishCorrection(AssetId assetId, TrackCorrection correction) {
        // no-op
    }
}
