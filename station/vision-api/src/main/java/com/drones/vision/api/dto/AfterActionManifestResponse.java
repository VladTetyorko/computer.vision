package com.drones.vision.api.dto;

import com.drones.vision.api.support.afteraction.AfterActionPackage;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code GET /api/assets/{assetId}/usages/{usageId}/after-action} (docs/plans/
 * active/AFTER-ACTION-PLAN.md &sect;3.1's frozen wire contract), and — serialized byte-for-byte
 * identically — the {@code manifest.json} entry of the archive endpoint's ZIP (&sect;3.2).
 *
 * <p>Deliberately carries <b>no</b> {@code @JsonInclude(NON_NULL)}: {@code endedAt: null} (a
 * still-open usage) and every part's {@code note: null} are meaningful facts that must appear on
 * the wire, the opposite convention from {@link FlightPassportResponse}'s own — this record follows
 * {@link com.drones.vision.api.dto.UsageTimelineResponse}'s "always present" idiom instead.
 *
 * @param assetId     the asset this package describes, as a canonical UUID string
 * @param assetName   the asset's display name
 * @param usageId     the usage (flight) this package describes, as a canonical UUID string
 * @param startedAt   when the flight started
 * @param endedAt     when the flight ended, or {@code null} for a still-open usage
 * @param open        {@code true} iff {@code endedAt} is {@code null}
 * @param generatedAt when this package was assembled
 * @param scopedTo    who this package was assembled for (D6)
 * @param parts       all six part rows, always in the order {@code telemetry, detections, marks,
 *                    recording, passport, audit}
 * @param complete    {@code true} iff every part is {@code PRESENT}
 * @param caveats     every non-{@code null} note, verbatim, in part order, regardless of
 *                    {@code state} — may be non-empty even when {@code complete} is {@code true}
 *                    (a {@code PRESENT} part may still carry a standing qualifier, e.g. marks/D5)
 */
public record AfterActionManifestResponse(String assetId, String assetName, String usageId, Instant startedAt,
                                           Instant endedAt, boolean open, Instant generatedAt, String scopedTo,
                                           List<AfterActionPartResponse> parts, boolean complete,
                                           List<String> caveats) {

    /**
     * Maps an assembled {@link AfterActionPackage} to its wire representation.
     *
     * @param pkg the package to map
     * @return the response body for {@code pkg}
     */
    public static AfterActionManifestResponse from(AfterActionPackage pkg) {
        return new AfterActionManifestResponse(
                pkg.assetId().value().toString(),
                pkg.assetName(),
                pkg.usageId().value().toString(),
                pkg.startedAt(),
                pkg.endedAt(),
                pkg.open(),
                pkg.generatedAt(),
                pkg.scopedTo(),
                pkg.parts().stream().map(AfterActionPartResponse::from).toList(),
                pkg.complete(),
                pkg.caveats());
    }
}
