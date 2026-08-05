package com.drones.vision.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The wire envelope every {@code GET /api/live} (docs/REALTIME-PLAN.md §4) SSE {@code data:} line
 * carries, one per event.
 *
 * @param seq      a globally increasing sequence number (shared across every topic on the
 *                  connection, since SSE's own {@code Last-Event-ID} resume mechanism is one value
 *                  per connection, not per topic) — also sent as the SSE event's own {@code id:}
 *                  field, so {@code EventSource}'s automatic reconnect resumes from it for free
 * @param assetId  the asset this update is about, as a canonical UUID string, or absent for a
 *                  fleet-wide/asset-less update ({@code type=fleet} or {@code type=event})
 * @param type     one of {@code fleet}, {@code telemetry}, {@code detections}, {@code event},
 *                  {@code devices}, {@code detection-events}, {@code map}
 * @param payload  already-mapped response DTO(s): {@code List<AssetSummaryResponse>} for {@code
 *                  fleet}, {@code List<TelemetrySampleResponse>} for {@code telemetry} (a
 *                  coalesced batch of appended samples), a single {@code DetectionResultResponse}
 *                  for {@code detections} (latest-frame-only — no backlog), a single {@code
 *                  EventResponse} for {@code event}, a single {@code DevicesSnapshotResponse} for
 *                  {@code devices}, a single {@code DetectionEventResponse} for {@code
 *                  detection-events}, and a single {@link MapEventPayload} for {@code map}
 *                  (docs/MAP-REWORK-PLAN.md §4.3 — the only payload whose delivery is filtered per
 *                  connection, by its own {@code layerId})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LiveEnvelopeResponse(long seq, String assetId, String type, Object payload) {
}
