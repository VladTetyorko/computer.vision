package com.drones.vision.app.events;

import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventType;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.platform.EventLiveUpdatePort;
import com.drones.vision.warehouse.domain.port.FleetLiveUpdatePort;

import java.util.Objects;
import java.util.Set;

/**
 * {@link EventPublisherPort} decorator that additionally announces every event as a live update
 * (docs/plans/done/REALTIME-PLAN.md §4) — and, for the subset of event types that represent fleet-level
 * lifecycle ({@link EventType#DEVICE_ONLINE}/{@link EventType#DEVICE_OFFLINE}/{@link
 * EventType#STREAM_STARTED}/{@link EventType#STREAM_STOPPED}), also announces a "fleet changed"
 * update — the {@code streams lifecycle} third of docs/plans/done/REALTIME-PLAN.md §4 item 1's "assets/
 * devices/streams lifecycle" (asset/device CRUD itself flows through {@link
 * LiveUpdateAuditTrail}, which never touches either port here).
 *
 * <p>Every event is still delegated to the real {@link EventPublisherPort} (today {@code
 * com.drones.vision.app.devsupport.LoggingEventPublisher}, itself possibly already wrapped by
 * {@link DetectionSessionCleanupEventPublisher} — see {@link WiringConfiguration#eventPublisherPort}
 * for the composition order) unchanged, exactly like that decorator's own precedent.
 *
 * <p>Takes two of the five ports the former god-port {@code LiveUpdatePublisherPort} split into
 * (docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6b) — {@link EventLiveUpdatePort} for every
 * event, {@link FleetLiveUpdatePort} for the fleet-lifecycle subset — since this is the one class
 * that needs both.
 *
 * <p>Only wired ({@link WiringConfiguration#eventPublisherPort}) when {@code vision.live.enabled}
 * is {@code true}; with it disabled, this class is never constructed.
 */
public final class LiveUpdateEventPublisher implements EventPublisherPort {

    /** Event types that also represent a fleet-level lifecycle change, not just a notable occurrence. */
    private static final Set<EventType> FLEET_LIFECYCLE_EVENTS =
            Set.of(EventType.DEVICE_ONLINE, EventType.DEVICE_OFFLINE, EventType.STREAM_STARTED,
                    EventType.STREAM_STOPPED);

    private final EventPublisherPort delegate;
    private final EventLiveUpdatePort eventLiveUpdatePort;
    private final FleetLiveUpdatePort fleetLiveUpdatePort;

    public LiveUpdateEventPublisher(EventPublisherPort delegate, EventLiveUpdatePort eventLiveUpdatePort,
                                     FleetLiveUpdatePort fleetLiveUpdatePort) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.eventLiveUpdatePort = Objects.requireNonNull(eventLiveUpdatePort, "eventLiveUpdatePort must not be null");
        this.fleetLiveUpdatePort = Objects.requireNonNull(fleetLiveUpdatePort, "fleetLiveUpdatePort must not be null");
    }

    @Override
    public void publish(Event event) {
        delegate.publish(event);
        eventLiveUpdatePort.publishEvent(event);
        if (FLEET_LIFECYCLE_EVENTS.contains(event.type())) {
            fleetLiveUpdatePort.publishFleetChanged();
        }
    }
}
