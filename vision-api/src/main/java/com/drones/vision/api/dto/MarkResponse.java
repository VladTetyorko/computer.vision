package com.drones.vision.api.dto;

import com.drones.vision.domain.model.Mark;
import com.drones.vision.domain.model.Verification;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Wire representation of a {@link Mark} — the response body for every {@code /api/map/marks}
 * endpoint (docs/plans/done/MAP-REWORK-PLAN.md §4.2's frozen wire contract) and the {@code mark} field inside
 * {@link MapEventPayload} on the {@code map} SSE topic (§4.3), so one shape is parsed regardless of
 * whether it arrived via REST or live push.
 *
 * <p>Reworked from the docs/plans/done/TACTICAL-MARKS-PLAN.md shape it replaces. Three deliberate, breaking
 * differences (the SPA migrates in Wave E; no compatibility fields are kept):
 * <ul>
 *   <li>The identity field is {@code markId}, not {@code id} — matching {@code layerId}/{@code
 *       drawingId} elsewhere in the same contract.</li>
 *   <li>{@code position} is flattened to {@code latitude}/{@code longitude}/{@code altitudeMeters}
 *       instead of a nested {@link GeoPositionResponse}.</li>
 *   <li>{@code affiliation} ("whose it is") is now first-class and separate from {@code kind}
 *       ("what it is"); {@code verification}/{@code verifiedByUserId}/{@code verifiedAt} carry the
 *       review state; {@code createdBy} became {@code createdByUserId} and gained {@code groupId}.</li>
 * </ul>
 *
 * @param markId           the mark id, as a canonical UUID string
 * @param layerId          the layer it lives on, as a canonical UUID string
 * @param altitudeMeters   metres, or absent if the mark carries no altitude
 * @param kind             {@code UNIT}/{@code EQUIPMENT}/{@code HAZARD}/{@code POI}/{@code TARGET}
 * @param affiliation      {@code FRIENDLY}/{@code HOSTILE}/{@code NEUTRAL}/{@code UNKNOWN}
 * @param note             optional free-text detail, or absent
 * @param status           {@code ACTIVE} or {@code CLEARED}
 * @param source           {@code MANUAL} or {@code DETECTION}
 * @param verification     {@code UNVERIFIED}, {@code CONFIRMED} or {@code REJECTED}
 * @param verifiedByUserId who reviewed it, absent while {@code UNVERIFIED}
 * @param verifiedAt       when it was reviewed, absent while {@code UNVERIFIED}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MarkResponse(String markId, String layerId, double latitude, double longitude,
                            Double altitudeMeters, String kind, String affiliation, String label, String note,
                            String createdByUserId, String groupId, Instant createdAt, String status,
                            String source, String verification, String verifiedByUserId, Instant verifiedAt) {

    /**
     * Maps a domain {@link Mark} to its wire representation.
     *
     * @param mark the mark to map
     * @return the response body for {@code mark}
     */
    public static MarkResponse from(Mark mark) {
        Verification verification = mark.verification();
        return new MarkResponse(
                mark.id().value().toString(),
                mark.layerId().value().toString(),
                mark.position().latitude(),
                mark.position().longitude(),
                mark.position().altitudeMeters(),
                mark.kind().name(),
                mark.affiliation().name(),
                mark.label(),
                mark.note(),
                mark.ownership().ownerId().value().toString(),
                mark.ownership().groupId().value().toString(),
                mark.createdAt(),
                mark.status().name(),
                mark.source().name(),
                verification.state().name(),
                verification.verifiedBy() == null ? null : verification.verifiedBy().value().toString(),
                verification.verifiedAt());
    }
}
