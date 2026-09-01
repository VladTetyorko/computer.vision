package com.drones.vision.flight.application.alerting;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventLiveUpdatePort;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.platform.EventType;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Raises exactly one {@link EventType#LINK_LOST} event per link-failure edge
 * (docs/plans/active/ASSET-FLOWS-PLAN.md §2, wave S4) — the notification half of the already-merged
 * FLEET-RADIO R4/F7 typed link-failure signal (docs/plans/done/FLEET-RADIO-PLAN.md, {@code
 * mavlink-core}'s {@code MavlinkSession#onLinkFailure}), which today reaches project code only as
 * the {@code IOException} a device's supervised {@link
 * com.drones.vision.flight.domain.port.TelemetrySourcePort} closes exceptionally with — and, at
 * that boundary, is already resolved from the wire-level {@code PeerId} to a project {@code
 * DeviceId}/{@link AssetId} by the adapter's own claim policy, so this class needs no cross-context
 * resolution port of its own: a caller that already has the affected {@code AssetId} (perception's
 * {@code UsageTracker}, which resolves it once per subscription via {@code AssetDirectoryService}
 * before ever opening the port) calls {@link #reportLinkLost} directly.
 *
 * <p>Deliberately stateless — unlike {@link BatteryMonitor}'s latch, no edge-detection is done
 * here, because the upstream signal already guarantees "exactly once per outage": {@code
 * SupervisedPublisher#onOutageBegan} (perception, {@code vision-perception}'s own generic
 * source-retry decorator) fires its callback once per outage by construction, not once per failed
 * retry and never per heartbeat miss, so every call this class receives is already the edge.
 *
 * <h2>Wiring gap, flagged for the caller</h2>
 * As of this wave, nothing calls {@link #reportLinkLost} yet. The one production call site is
 * perception's {@code UsageTracker#subscribeTelemetry}
 * (contexts/vision-perception/.../pipeline/UsageTracker.java), which today passes a hardcoded
 * no-op {@code cause -> { }} as {@code SupervisedPublisher}'s {@code onOutageBegan} callback for the
 * telemetry path — the exact seam this class exists to fill. That file is out of this wave's scope
 * (owned by a concurrent wave for an unrelated gate change); wiring
 * {@code cause -> linkLossNotifier.reportLinkLost(asset.id(), "MAVLink link to " + asset.name() + " lost")}
 * in place of that no-op is the one remaining integration step — see this module's MODULE.md for
 * the full trail.
 */
public final class LinkLossNotifier {

    private final EventPublisherPort eventPublisher;
    private final EventLiveUpdatePort liveUpdatePublisherPort;

    /**
     * @param eventPublisher          nullable: {@code null} means link-loss events are never
     *                                published through this port, following the same
     *                                nullable-collaborator convention as {@code GeofenceMonitor}'s
     *                                own constructor
     * @param liveUpdatePublisherPort nullable, same convention
     */
    public LinkLossNotifier(EventPublisherPort eventPublisher, EventLiveUpdatePort liveUpdatePublisherPort) {
        this.eventPublisher = eventPublisher; // nullable: no EventPublisherPort announcements when absent
        this.liveUpdatePublisherPort = liveUpdatePublisherPort; // nullable: no live-update announcements when absent
    }

    /**
     * Raises one {@link EventType#LINK_LOST} event for {@code assetId}. The caller is responsible
     * for calling this at most once per failure edge — see the class javadoc for why that is already
     * guaranteed at this class's one intended call site.
     *
     * @param assetId the asset whose telemetry link just failed
     * @param summary a human-readable summary (e.g. {@code "MAVLink link to <asset> lost"}) —
     *                becomes the event's {@link Event#message()} verbatim
     */
    public void reportLinkLost(AssetId assetId, String summary) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        Map<String, String> attributes = Map.of("assetId", assetId.value().toString());
        Event event = new Event(UUID.randomUUID().toString(), null, Instant.now(), EventType.LINK_LOST, summary,
                attributes);
        if (eventPublisher != null) {
            eventPublisher.publish(event);
        }
        if (liveUpdatePublisherPort != null) {
            liveUpdatePublisherPort.publishEvent(event);
        }
    }
}
