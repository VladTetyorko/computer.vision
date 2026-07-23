package com.drones.vision.api.dto;

import com.drones.vision.domain.model.DetectionEvent;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Response body element for {@code GET /api/events}/{@code GET /api/streams/{streamId}/events}
 * (docs/MVP2-PLAN.md §E, E-a) — one debounced detection event.
 *
 * @param id             the event's id, as a canonical UUID string
 * @param streamId       the stream this event was observed on, as a canonical UUID string
 * @param assetId        the owning asset's id, as a canonical UUID string, or absent if unresolvable
 * @param label          the tracked detection label (e.g. {@code "person"})
 * @param peakConfidence highest confidence observed for this label while the event has been open, range [0,1]
 * @param firstSeen      when the qualifying streak that opened this event began
 * @param lastSeen       the most recent time this label was actually seen at/above threshold
 * @param state          {@code "OPEN"} or {@code "CLOSED"}
 * @param position       best-effort position at open time, or absent if unavailable
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DetectionEventResponse(String id, String streamId, String assetId, String label,
                                      double peakConfidence, Instant firstSeen, Instant lastSeen, String state,
                                      GeoPositionResponse position) {

    /**
     * Maps a domain {@link DetectionEvent} to its wire representation.
     *
     * @param event the event to map
     * @return the response body for {@code event}
     */
    public static DetectionEventResponse from(DetectionEvent event) {
        return new DetectionEventResponse(
                event.id().value().toString(),
                event.streamId().value().toString(),
                event.assetId() == null ? null : event.assetId().value().toString(),
                event.label(),
                event.peakConfidence(),
                event.firstSeen(),
                event.lastSeen(),
                event.state().name(),
                GeoPositionResponse.from(event.position()));
    }
}
