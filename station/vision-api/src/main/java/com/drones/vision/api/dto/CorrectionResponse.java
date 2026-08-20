package com.drones.vision.api.dto;

import com.drones.vision.flight.domain.model.TrackCorrection;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.VisualFixEvidence;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Wire shape for {@code TrackCorrection} (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.3) — the
 * {@code GET /api/geo/corrections/live} and {@code GET /api/geo/corrections} response element, and
 * the {@code geo:<assetId>} SSE envelope payload (§3.4), byte-identical between the two.
 *
 * <p>One record serves both a full {@code CONFIRMED} row and a {@code NO_FIX} row carrying only
 * {@link #refusal()} — {@code regionId}/{@code tileId}/{@code refusal} are {@code null} on the wire
 * whenever the domain value is {@code ""} (no value), not serialized as an empty string, so a
 * {@code NO_FIX} response shows {@code "regionId":null} rather than {@code "regionId":""}.
 *
 * @param assetId                aircraft this correction is about, as a canonical UUID string
 * @param usageId                the flight session this correction belongs to, as a canonical UUID string
 * @param frameAt                capture time of the underlying keyframe
 * @param computedAt             when this correction was produced
 * @param status                 {@code CONFIRMED}, {@code PROBABLE}, or {@code NO_FIX} — the Java enum name verbatim
 * @param source                 {@code VISUAL_HEAVY} — the Java enum name verbatim
 * @param latitude               corrected position latitude; {@code null} iff {@code status == NO_FIX}
 * @param longitude              corrected position longitude; {@code null} iff {@code status == NO_FIX}
 * @param yawDegrees             camera image-up bearing, nullable
 * @param radiusMeters           1-sigma horizontal uncertainty of the corrected position, meters, nullable
 * @param impliedAglMeters       AGL implied by the homography scale, meters, nullable
 * @param rawLatitude            the aircraft's own reported latitude at {@code frameAt}, nullable (a telemetry gap)
 * @param rawLongitude           the aircraft's own reported longitude at {@code frameAt}, nullable
 * @param separationMeters       great-circle distance between the raw and corrected positions, nullable
 * @param sigmaMeters            the combined 1-sigma the divergence gate compares against, nullable
 * @param divergent              whether the divergence alarm is currently latched for this asset
 * @param divergentSince         when the currently-latched alarm first rose; {@code null} iff {@code !divergent}
 * @param regionId               the region actually searched; {@code null} when none was
 * @param tileId                 {@code "<z>/<x>/<y>"} of the winning cell; {@code null} when none
 * @param matchCount             correspondences found on the winning candidate
 * @param inlierCount            MAGSAC inliers on the winning candidate
 * @param inlierRatio            {@code inlierCount / matchCount}
 * @param rerankMargin           the re-rank margin over the second-best candidate
 * @param reprojectionRmsPixels  homography residual, pixels
 * @param rectified              whether IPM rectification was actually applied
 * @param sequenceSpreadMeters   the sequence filter's posterior 1-sigma spread, meters
 * @param sequenceUpdates        how many filter updates contributed
 * @param refusal                names the first gate that refused this correction; {@code null} iff a position is present
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CorrectionResponse(
        String assetId, String usageId,
        Instant frameAt, Instant computedAt,
        String status, String source,
        Double latitude, Double longitude,
        Double yawDegrees, Double radiusMeters, Double impliedAglMeters,
        Double rawLatitude, Double rawLongitude,
        Double separationMeters, Double sigmaMeters,
        boolean divergent, Instant divergentSince,
        String regionId, String tileId,
        int matchCount, int inlierCount, double inlierRatio,
        double rerankMargin, double reprojectionRmsPixels,
        boolean rectified, double sequenceSpreadMeters, int sequenceUpdates,
        String refusal) {

    /**
     * Maps a {@link TrackCorrection} read model to its wire representation.
     *
     * @param correction the correction to map
     * @return the response body element for {@code correction}
     */
    public static CorrectionResponse from(TrackCorrection correction) {
        GeoPosition position = correction.position();
        GeoPosition rawPosition = correction.rawPosition();
        VisualFixEvidence evidence = correction.evidence();
        return new CorrectionResponse(
                correction.assetId().value().toString(),
                correction.usageId().value().toString(),
                correction.frameAt(),
                correction.computedAt(),
                correction.status().name(),
                correction.source().name(),
                position == null ? null : position.latitude(),
                position == null ? null : position.longitude(),
                correction.yawDegrees(),
                correction.radiusMeters(),
                correction.impliedAglMeters(),
                rawPosition == null ? null : rawPosition.latitude(),
                rawPosition == null ? null : rawPosition.longitude(),
                correction.separationMeters(),
                correction.sigmaMeters(),
                correction.divergent(),
                correction.divergentSince(),
                emptyToNull(correction.regionId()),
                emptyToNull(correction.tileId()),
                evidence.matchCount(),
                evidence.inlierCount(),
                evidence.inlierRatio(),
                evidence.rerankMargin(),
                evidence.reprojectionRmsPixels(),
                evidence.rectified(),
                evidence.sequenceSpreadMeters(),
                evidence.sequenceUpdates(),
                emptyToNull(correction.refusal()));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
