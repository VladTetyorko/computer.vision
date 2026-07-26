package com.drones.vision.app.devsupport;

import com.drones.vision.domain.model.Event;
import com.drones.vision.domain.port.out.EventPublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link EventPublisherPort} that logs events via SLF4J: dev/Phase-0
 * fallback with no external delivery or persistence.
 *
 * <p>Replaced by a message-broker-backed implementation (MQTT/Kafka),
 * planned for Phase 7 (multi-instance event bus).
 */
public final class LoggingEventPublisher implements EventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(Event event) {
        log.debug("event id={} streamId={} type={} message={} attributes={}",
                event.id(), event.streamId(), event.type(), event.message(), event.attributes());
    }
}
