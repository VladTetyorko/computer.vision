package com.drones.vision.api.dto;

import com.drones.vision.domain.model.Mark;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Wire representation of a {@link Mark} — the response body for every {@code /api/marks} endpoint
 * (docs/TACTICAL-MARKS-PLAN.md §4's frozen wire contract) and the {@code mark} field inside {@link
 * MarkPayload} on the {@code marks} SSE topic (§5), so {@code MarksStore} (vision-web) parses one
 * shape regardless of whether it arrived via REST or live push.
 *
 * @param id        the mark id, as a canonical UUID string
 * @param kind       {@code "TARGET"}, {@code "HAZARD"}, {@code "POI"}, or {@code "FRIENDLY"} (the
 *                   enum name)
 * @param label      short human-readable label
 * @param note       optional free-text detail, or absent
 * @param position   where the mark is
 * @param createdBy  the creator's user id, as a canonical UUID string
 * @param createdAt  when the mark was created
 * @param status     {@code "ACTIVE"} or {@code "CLEARED"} (the enum name)
 * @param source     {@code "MANUAL"} or {@code "DETECTION"} (the enum name)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MarkResponse(String id, String kind, String label, String note, GeoPositionResponse position,
                            String createdBy, Instant createdAt, String status, String source) {

    /**
     * Maps a domain {@link Mark} to its wire representation.
     *
     * @param mark the mark to map
     * @return the response body for {@code mark}
     */
    public static MarkResponse from(Mark mark) {
        return new MarkResponse(
                mark.id().value().toString(),
                mark.kind().name(),
                mark.label(),
                mark.note(),
                GeoPositionResponse.from(mark.position()),
                mark.createdBy().value().toString(),
                mark.createdAt(),
                mark.status().name(),
                mark.source().name());
    }
}
