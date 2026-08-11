package com.drones.vision.app.devsupport;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.events.domain.model.DetectionEvent;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.platform.Event;
import com.drones.vision.map.domain.model.MapEvent;
import com.drones.vision.flight.domain.model.Telemetry;
import com.drones.vision.events.domain.port.LiveUpdatePublisherPort;

/**
 * No-op {@link LiveUpdatePublisherPort}: every method is a no-op. Wired when {@code
 * vision.live.enabled=false} (docs/plans/done/REALTIME-PLAN.md §4, item 4) — {@code vision-api}'s {@code
 * /api/live} endpoint itself 404s in that case (its controller/registry beans are conditionally
 * absent), but the application layer ({@code StreamPipeline}/{@code UsageTracker}/{@code
 * DefaultStreamService}) still needs <em>some</em> {@link LiveUpdatePublisherPort} bean to satisfy
 * their constructors, exactly as {@link NoopDetectionPort}/{@link NoopStreamPublisher} do for their
 * own ports when their feature is disabled.
 */
public final class NoopLiveUpdatePublisher implements LiveUpdatePublisherPort {

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
}
