package com.drones.vision.app;

import com.drones.vision.domain.model.Event;
import com.drones.vision.domain.model.EventType;
import com.drones.vision.domain.port.out.EventPublisherPort;
import com.drones.vision.domain.port.out.LiveUpdatePublisherPort;

import java.util.Objects;
import java.util.Set;

/**
 * {@link EventPublisherPort} decorator that additionally announces every event as a live update
 * (docs/REALTIME-PLAN.md §4) — and, for the subset of event types that represent fleet-level
 * lifecycle ({@link EventType#DEVICE_ONLINE}/{@link EventType#DEVICE_OFFLINE}/{@link
 * EventType#STREAM_STARTED}/{@link EventType#STREAM_STOPPED}), also announces a "fleet changed"
 * update — the {@code streams lifecycle} third of docs/REALTIME-PLAN.md §4 item 1's "assets/
 * devices/streams lifecycle" (asset/device CRUD itself flows through {@link
 * LiveUpdateAuditTrail}, which never touches this port).
 *
 * <p>Every event is still delegated to the real {@link EventPublisherPort} (today {@code
 * com.drones.vision.app.devsupport.LoggingEventPublisher}, itself possibly already wrapped by
 * {@link DetectionSessionCleanupEventPublisher} — see {@link WiringConfiguration#eventPublisherPort}
 * for the composition order) unchanged, exactly like that decorator's own precedent.
 *
 * <p>Only wired ({@link WiringConfiguration#eventPublisherPort}) when {@code vision.live.enabled}
 * is {@code true}; with it disabled, this class is never constructed.
 */
final class LiveUpdateEventPublisher implements EventPublisherPort {

    /** Event types that also represent a fleet-level lifecycle change, not just a notable occurrence. */
    private static final Set<EventType> FLEET_LIFECYCLE_EVENTS =
            Set.of(EventType.DEVICE_ONLINE, EventType.DEVICE_OFFLINE, EventType.STREAM_STARTED,
                    EventType.STREAM_STOPPED);

    private final EventPublisherPort delegate;
    private final LiveUpdatePublisherPort liveUpdatePublisherPort;

    LiveUpdateEventPublisher(EventPublisherPort delegate, LiveUpdatePublisherPort liveUpdatePublisherPort) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.liveUpdatePublisherPort =
                Objects.requireNonNull(liveUpdatePublisherPort, "liveUpdatePublisherPort must not be null");
    }

    @Override
    public void publish(Event event) {
        delegate.publish(event);
        liveUpdatePublisherPort.publishEvent(event);
        if (FLEET_LIFECYCLE_EVENTS.contains(event.type())) {
            liveUpdatePublisherPort.publishFleetChanged();
        }
    }
}
