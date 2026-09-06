package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.EventHistoryEntity;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.platform.Event;

/**
 * {@link Event} &harr; {@link EventHistoryEntity} mapping, following the extraction convention
 * every other aggregate's mapper in this package already uses (docs/plans/active/LAYERING-REFACTOR-PLAN.md
 * §3/§7 row C).
 */
public final class EventHistoryMapper {

    private EventHistoryMapper() {
    }

    public static EventHistoryEntity toEntity(Event event) {
        return new EventHistoryEntity(event.id(), event.streamId() == null ? null : event.streamId().value(),
                event.at(), event.type(), event.message(), event.attributes());
    }

    public static Event toDomain(EventHistoryEntity entity) {
        return new Event(entity.id(), entity.streamId() == null ? null : new StreamId(entity.streamId()),
                entity.occurredAt(), entity.type(), entity.message(), entity.attributes());
    }
}
