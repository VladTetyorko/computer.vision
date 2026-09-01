package com.drones.vision.flight.application.alerting;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventLiveUpdatePort;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.platform.EventType;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates live telemetry against {@link BatteryAlertSettings} and raises {@link
 * EventType#BATTERY_LOW} on the rising edge only (docs/plans/active/ASSET-FLOWS-PLAN.md §2, wave
 * S4) — the {@link com.drones.vision.flight.application.geofence.GeofenceMonitor} idiom, applied to
 * a single per-asset scalar instead of a polygon set.
 *
 * <p>Called from perception's {@code UsageTracker#applySample} once per telemetry sample, the same
 * hot path {@code GeofenceMonitor} and the live-update/persist calls already run on — {@link
 * #evaluate} must stay cheap: no repository I/O, one in-heap map lookup, and, on an actual crossing,
 * one {@link EventPublisherPort#publish}/{@link EventLiveUpdatePort#publishEvent} call.
 *
 * <h2>Hysteresis, not a level check</h2>
 * {@link #lowByAsset} holds one latched boolean per asset ever evaluated, in-heap only (resets on
 * restart, same honest limitation {@code GeofenceMonitor}'s own breach state carries). A sample
 * crossing at or below {@link BatteryAlertSettings#criticalPercent()} while not already latched
 * raises exactly one event and latches; every further sample at or below the critical line is
 * silent. The asset re-arms — silently, no event — only once a sample reports at or above {@link
 * BatteryAlertSettings#warningPercent()}. A reading that dips just under critical and immediately
 * bounces back above it but stays below warning therefore reports nothing further and does not
 * re-arm — exactly the "no spam" behavior the D6 contract asks for; a battery genuinely recovering
 * needs to actually clear the warning line, not merely leave the critical one.
 *
 * <p>A sample with no {@link Telemetry#batteryPercent()} is ignored entirely — no latch state
 * changes, since "unknown battery" must never be silently treated as either safe or critical (the
 * same honest-unknown discipline {@code GeofenceMonitor} applies to a positionless sample).
 *
 * <h2>Threading</h2>
 * {@link #lowByAsset} is a {@link ConcurrentHashMap} — concurrent {@link #evaluate} calls for
 * different assets never contend, matching {@code UsageTracker}'s own per-asset concurrency model
 * and {@code GeofenceMonitor}'s identical choice.
 */
public final class BatteryMonitor {

    private final EventPublisherPort eventPublisher;
    private final EventLiveUpdatePort liveUpdatePublisherPort;
    private final BatteryAlertSettings settings;

    private final ConcurrentHashMap<AssetId, Boolean> lowByAsset = new ConcurrentHashMap<>();

    /**
     * @param eventPublisher          nullable: {@code null} means low-battery transitions are never
     *                                published through this port, following the same
     *                                nullable-collaborator convention as {@code GeofenceMonitor}'s
     *                                own constructor
     * @param liveUpdatePublisherPort nullable, same convention
     * @param settings                required — the two D6 thresholds this monitor evaluates against
     */
    public BatteryMonitor(EventPublisherPort eventPublisher, EventLiveUpdatePort liveUpdatePublisherPort,
                           BatteryAlertSettings settings) {
        this.eventPublisher = eventPublisher; // nullable: no EventPublisherPort announcements when absent
        this.liveUpdatePublisherPort = liveUpdatePublisherPort; // nullable: no live-update announcements when absent
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    /**
     * Evaluates one telemetry sample for {@code assetId}, publishing one {@link
     * EventType#BATTERY_LOW} event iff this sample is the rising edge of a critical-battery
     * condition for that asset.
     *
     * @param assetId the asset the sample belongs to
     * @param sample  the telemetry sample; ignored entirely (no state change) if it carries no
     *                {@link Telemetry#batteryPercent()}
     */
    public void evaluate(AssetId assetId, Telemetry sample) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(sample, "sample must not be null");
        Double batteryPercent = sample.batteryPercent();
        if (batteryPercent == null) {
            return; // honest unknown: never a transition, never a spurious re-arm
        }
        boolean currentlyLow = lowByAsset.getOrDefault(assetId, Boolean.FALSE);
        if (!currentlyLow && batteryPercent <= settings.criticalPercent()) {
            lowByAsset.put(assetId, Boolean.TRUE);
            publishLowBatteryEvent(assetId, batteryPercent);
        } else if (currentlyLow && batteryPercent >= settings.warningPercent()) {
            lowByAsset.put(assetId, Boolean.FALSE); // re-armed: silent, no event on the way back up
        }
    }

    private void publishLowBatteryEvent(AssetId assetId, double batteryPercent) {
        Map<String, String> attributes = Map.of(
                "assetId", assetId.value().toString(),
                "batteryPercent", String.valueOf(batteryPercent));
        String message = "Battery low on asset " + assetId.value() + ": " + batteryPercent + "%";
        Event event = new Event(UUID.randomUUID().toString(), null, Instant.now(), EventType.BATTERY_LOW, message,
                attributes);
        if (eventPublisher != null) {
            eventPublisher.publish(event);
        }
        if (liveUpdatePublisherPort != null) {
            liveUpdatePublisherPort.publishEvent(event);
        }
    }
}
