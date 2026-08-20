package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.TrackCorrectionEntity;
import com.drones.vision.flight.domain.model.CorrectionSource;
import com.drones.vision.flight.domain.model.CorrectionStatus;
import com.drones.vision.flight.domain.model.TrackCorrection;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.VisualFixEvidence;

/**
 * {@link TrackCorrection} ⟷ {@link TrackCorrectionEntity} (docs/plans/active/VISUAL-GEO-V2-PLAN.md
 * §3.5/§3.7).
 *
 * <p>{@code toEntity} never carries an {@code id} — {@link TrackCorrection} has none of its own,
 * and {@link TrackCorrectionEntity#id()} is assigned by the database on insert, the same {@code
 * TrackPointMapper} precedent. Positions are flattened rather than nested.
 *
 * <h2>{@code toDomain}'s placeholders — the frozen-schema lossy round trip</h2>
 * See {@link TrackCorrectionEntity}'s own javadoc for why the V23 DDL persists 10 of {@link
 * VisualFixEvidence}'s 14 fields, and no {@code rawPosition} altitude. {@code toDomain}
 * reconstructs the missing fields with inert placeholders rather than fail {@link
 * TrackCorrection}/{@link VisualFixEvidence}'s own compact-constructor validation on read-back:
 * {@code candidateCount=0}, {@code supportingFrames=0}, {@code baselineMeters=0.0}, {@code
 * osmPrior=1.0} ({@code VisualFixEvidence#osmPrior}'s own javadoc: {@code 1.0} is inert) — and
 * {@code rawPosition}'s {@code altitudeMeters} always reads back {@code null}. A row read back is
 * therefore not byte-identical to the one written; every field the wire contract (§3.3 {@code
 * CorrectionResponse}) actually serializes round-trips exactly.
 *
 * <p>{@code cellCalibrated}/{@code sequenceConverged} left this placeholder list in H8 — they are
 * the two booleans that decide PROBABLE vs CONFIRMED, so substituting {@code false} on read-back
 * was not inert at all (docs/plans/active/VISUAL-GEO-V2-PLAN.md §9.11 defect 4). They now round-trip
 * through real columns.
 */
public final class TrackCorrectionMapper {

    /** {@link VisualFixEvidence#osmPrior()}'s own inert value — see this class's javadoc. */
    private static final double INERT_OSM_PRIOR = 1.0;

    private TrackCorrectionMapper() {
    }

    public static TrackCorrectionEntity toEntity(TrackCorrection correction) {
        GeoPosition position = correction.position();
        GeoPosition rawPosition = correction.rawPosition();
        VisualFixEvidence evidence = correction.evidence();
        return new TrackCorrectionEntity(
                correction.assetId().value(),
                correction.usageId().value(),
                correction.frameAt(),
                correction.computedAt(),
                correction.status().name(),
                correction.source().name(),
                position == null ? null : position.latitude(),
                position == null ? null : position.longitude(),
                position == null ? null : position.altitudeMeters(),
                correction.yawDegrees(),
                correction.radiusMeters(),
                correction.impliedAglMeters(),
                rawPosition == null ? null : rawPosition.latitude(),
                rawPosition == null ? null : rawPosition.longitude(),
                correction.separationMeters(),
                correction.sigmaMeters(),
                correction.divergent(),
                correction.divergentSince(),
                correction.regionId(),
                correction.tileId(),
                correction.refusal(),
                evidence.matchCount(),
                evidence.inlierCount(),
                evidence.inlierRatio(),
                evidence.rerankMargin(),
                evidence.reprojectionRmsPixels(),
                evidence.rectified(),
                evidence.cellCalibrated(),
                evidence.sequenceConverged(),
                evidence.sequenceSpreadMeters(),
                evidence.sequenceUpdates());
    }

    public static TrackCorrection toDomain(TrackCorrectionEntity entity) {
        GeoPosition position = entity.latitude() == null ? null
                : new GeoPosition(entity.latitude(), entity.longitude(), entity.altitudeMeters());
        GeoPosition rawPosition = entity.rawLatitude() == null ? null
                : new GeoPosition(entity.rawLatitude(), entity.rawLongitude(), null);
        VisualFixEvidence evidence = new VisualFixEvidence(
                0, // candidateCount -- not persisted, see class javadoc
                entity.matchCount(),
                entity.inlierCount(),
                entity.inlierRatio(),
                entity.rerankMargin(),
                entity.reprojectionRmsPixels(),
                entity.rectified(),
                entity.cellCalibrated(),
                0, // supportingFrames -- not persisted
                0.0, // baselineMeters -- not persisted
                entity.sequenceConverged(),
                entity.sequenceSpreadMeters(),
                entity.sequenceUpdates(),
                INERT_OSM_PRIOR);
        return new TrackCorrection(
                new AssetId(entity.assetId()),
                new UsageId(entity.usageId()),
                entity.frameAt(),
                entity.computedAt(),
                CorrectionStatus.valueOf(entity.status()),
                CorrectionSource.valueOf(entity.source()),
                position,
                entity.yawDegrees(),
                entity.radiusMeters(),
                entity.impliedAglMeters(),
                rawPosition,
                entity.separationMeters(),
                entity.sigmaMeters(),
                entity.divergent(),
                entity.divergentSince(),
                entity.regionId(),
                entity.tileId(),
                entity.refusal(),
                evidence);
    }
}
