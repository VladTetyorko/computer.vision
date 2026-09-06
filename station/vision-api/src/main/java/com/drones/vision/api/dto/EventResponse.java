package com.drones.vision.api.dto;

import com.drones.vision.platform.Event;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Response body element for a domain {@link Event} (docs/plans/done/REALTIME-PLAN.md §4) — the {@code
 * "event"} envelope payload on {@code GET /api/live}, reused unchanged (docs/plans/active/
 * ALWAYS-ON-FLOW-PLAN.md wave B3) as the body of {@link
 * com.drones.vision.api.controller.SystemEventsController#recent}, {@code GET
 * /api/system/events}'s durable-history counterpart to the live feed — the same wire shape either
 * way, live or replayed.
 *
 * @param id         the event's own id (a plain string, not a typed id — see {@code Event}'s own
 *                    javadoc for why)
 * @param streamId   the stream this event relates to, as a canonical UUID string, or absent for a
 *                    device-level event
 * @param at         when the event occurred
 * @param type       the kind of event, as its enum name (e.g. {@code "STREAM_STARTED"})
 * @param message    human-readable description
 * @param attributes free-form structured detail
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventResponse(String id, String streamId, Instant at, String type, String message,
                             Map<String, String> attributes) {

    /**
     * Maps a domain {@link Event} to its wire representation.
     *
     * @param event the event to map
     * @return the response body for {@code event}
     */
    public static EventResponse from(Event event) {
        return new EventResponse(event.id(), event.streamId() == null ? null : event.streamId().value().toString(),
                event.at(), event.type().name(), event.message(), event.attributes());
    }
}
