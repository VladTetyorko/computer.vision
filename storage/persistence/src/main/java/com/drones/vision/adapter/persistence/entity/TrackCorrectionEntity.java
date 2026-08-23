package com.drones.vision.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA row for {@code track_corrections} — mirrors {@link com.drones.vision.flight.domain.model.TrackCorrection}
 * (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.5/§3.7, {@code V23__track_corrections.sql});
 * {@code TrackCorrectionMapper} owns the mapping in both directions.
 *
 * <p>{@code id} is a synthetic {@code BIGINT GENERATED ALWAYS AS IDENTITY} column the schema
 * itself generates — the same {@link TrackPointEntity} precedent: append-only machine output
 * never looked up by id, only by {@code (assetId, usageId)} or pruned by {@code frameAt}.
 * {@code position}/{@code rawPosition} are each flattened to latitude/longitude columns, the
 * same choice {@link CameraPoseEntity}/{@link TrackPointEntity} make for their own positions.
 *
 * <h2>Frozen-schema lossy round trip (V23, not a bug)</h2>
 * The DDL is frozen by docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.7 and persists a deliberate
 * subset of two richer domain shapes:
 * <ul>
 *   <li>{@code rawPosition}'s {@code altitudeMeters} has no column — only {@code raw_latitude}/
 *       {@code raw_longitude} are stored, so {@code TrackCorrectionMapper#toDomain} always
 *       reconstructs a raw position with a {@code null} altitude, whatever the original
 *       telemetry sample carried.</li>
 *   <li>{@link com.drones.vision.kernel.VisualFixEvidence} carries 14 fields; 10 have a column
 *       ({@code match_count}, {@code inlier_count}, {@code inlier_ratio}, {@code rerank_margin},
 *       {@code reprojection_rms_px}, {@code rectified}, {@code cell_calibrated}, {@code
 *       sequence_converged}, {@code sequence_spread_meters}, {@code sequence_updates}). {@code
 *       candidateCount}, {@code supportingFrames}, {@code baselineMeters} and {@code osmPrior} are
 *       not persisted; {@code TrackCorrectionMapper#toDomain} synthesizes inert placeholders for
 *       them (see that class's own javadoc) rather than failing {@link
 *       com.drones.vision.kernel.VisualFixEvidence}'s compact-constructor validation on read-back.
 *       <p>{@code cellCalibrated}/{@code sequenceConverged} were placeholders too until H8. They
 *       are the two booleans {@code DefaultTrackCorrectionService} reads to decide PROBABLE vs
 *       CONFIRMED, so reading them back as {@code false} left a replayed correction unable to say
 *       why it was only PROBABLE — against D5's "why is always one click away"
 *       (docs/plans/done/VISUAL-GEO-V2-PLAN.md §9.11 defect 4). They now have columns.</li>
 * </ul>
 * The remaining losses are consequences of the plan's own frozen contract, not omissions to fix here.
 *
 * <p>No FK to any other table — same "no cross-entity foreign keys" convention as the rest of
 * this schema. <strong>Excluded</strong> from {@code db_audit_log} — see {@code
 * V23__track_corrections.sql}'s own header for why.
 */
@Entity
@Table(name = "track_corrections")
public class TrackCorrectionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "usage_id", nullable = false)
    private UUID usageId;

    @Column(name = "frame_at", nullable = false)
    private Instant frameAt;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "altitude_meters")
    private Double altitudeMeters;

    @Column(name = "yaw_degrees")
    private Double yawDegrees;

    @Column(name = "radius_meters")
    private Double radiusMeters;

    @Column(name = "implied_agl_meters")
    private Double impliedAglMeters;

    @Column(name = "raw_latitude")
    private Double rawLatitude;

    @Column(name = "raw_longitude")
    private Double rawLongitude;

    @Column(name = "separation_meters")
    private Double separationMeters;

    @Column(name = "sigma_meters")
    private Double sigmaMeters;

    @Column(name = "divergent", nullable = false)
    private boolean divergent;

    @Column(name = "divergent_since")
    private Instant divergentSince;

    @Column(name = "region_id", nullable = false)
    private String regionId;

    @Column(name = "tile_id", nullable = false)
    private String tileId;

    @Column(name = "refusal", nullable = false)
    private String refusal;

    @Column(name = "match_count", nullable = false)
    private int matchCount;

    @Column(name = "inlier_count", nullable = false)
    private int inlierCount;

    @Column(name = "inlier_ratio", nullable = false)
    private double inlierRatio;

    @Column(name = "rerank_margin", nullable = false)
    private double rerankMargin;

    @Column(name = "reprojection_rms_px", nullable = false)
    private double reprojectionRmsPixels;

    @Column(name = "rectified", nullable = false)
    private boolean rectified;

    @Column(name = "cell_calibrated", nullable = false)
    private boolean cellCalibrated;

    @Column(name = "sequence_converged", nullable = false)
    private boolean sequenceConverged;

    @Column(name = "sequence_spread_meters", nullable = false)
    private double sequenceSpreadMeters;

    @Column(name = "sequence_updates", nullable = false)
    private int sequenceUpdates;

    /** JPA only. */
    protected TrackCorrectionEntity() {
    }

    /** A new, not-yet-persisted row — {@code id} is assigned by the database on insert. */
    public TrackCorrectionEntity(UUID assetId, UUID usageId, Instant frameAt, Instant computedAt, String status,
                                  String source, Double latitude, Double longitude, Double altitudeMeters,
                                  Double yawDegrees, Double radiusMeters, Double impliedAglMeters,
                                  Double rawLatitude, Double rawLongitude, Double separationMeters,
                                  Double sigmaMeters, boolean divergent, Instant divergentSince, String regionId,
                                  String tileId, String refusal, int matchCount, int inlierCount,
                                  double inlierRatio, double rerankMargin, double reprojectionRmsPixels,
                                  boolean rectified, boolean cellCalibrated, boolean sequenceConverged,
                                  double sequenceSpreadMeters, int sequenceUpdates) {
        this.assetId = assetId;
        this.usageId = usageId;
        this.frameAt = frameAt;
        this.computedAt = computedAt;
        this.status = status;
        this.source = source;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeMeters = altitudeMeters;
        this.yawDegrees = yawDegrees;
        this.radiusMeters = radiusMeters;
        this.impliedAglMeters = impliedAglMeters;
        this.rawLatitude = rawLatitude;
        this.rawLongitude = rawLongitude;
        this.separationMeters = separationMeters;
        this.sigmaMeters = sigmaMeters;
        this.divergent = divergent;
        this.divergentSince = divergentSince;
        this.regionId = regionId;
        this.tileId = tileId;
        this.refusal = refusal;
        this.matchCount = matchCount;
        this.inlierCount = inlierCount;
        this.inlierRatio = inlierRatio;
        this.rerankMargin = rerankMargin;
        this.reprojectionRmsPixels = reprojectionRmsPixels;
        this.rectified = rectified;
        this.cellCalibrated = cellCalibrated;
        this.sequenceConverged = sequenceConverged;
        this.sequenceSpreadMeters = sequenceSpreadMeters;
        this.sequenceUpdates = sequenceUpdates;
    }

    public Long id() {
        return id;
    }

    public UUID assetId() {
        return assetId;
    }

    public UUID usageId() {
        return usageId;
    }

    public Instant frameAt() {
        return frameAt;
    }

    public Instant computedAt() {
        return computedAt;
    }

    public String status() {
        return status;
    }

    public String source() {
        return source;
    }

    public Double latitude() {
        return latitude;
    }

    public Double longitude() {
        return longitude;
    }

    public Double altitudeMeters() {
        return altitudeMeters;
    }

    public Double yawDegrees() {
        return yawDegrees;
    }

    public Double radiusMeters() {
        return radiusMeters;
    }

    public Double impliedAglMeters() {
        return impliedAglMeters;
    }

    public Double rawLatitude() {
        return rawLatitude;
    }

    public Double rawLongitude() {
        return rawLongitude;
    }

    public Double separationMeters() {
        return separationMeters;
    }

    public Double sigmaMeters() {
        return sigmaMeters;
    }

    public boolean divergent() {
        return divergent;
    }

    public Instant divergentSince() {
        return divergentSince;
    }

    public String regionId() {
        return regionId;
    }

    public String tileId() {
        return tileId;
    }

    public String refusal() {
        return refusal;
    }

    public int matchCount() {
        return matchCount;
    }

    public int inlierCount() {
        return inlierCount;
    }

    public double inlierRatio() {
        return inlierRatio;
    }

    public double rerankMargin() {
        return rerankMargin;
    }

    public double reprojectionRmsPixels() {
        return reprojectionRmsPixels;
    }

    public boolean rectified() {
        return rectified;
    }

    public boolean cellCalibrated() {
        return cellCalibrated;
    }

    public boolean sequenceConverged() {
        return sequenceConverged;
    }

    public double sequenceSpreadMeters() {
        return sequenceSpreadMeters;
    }

    public int sequenceUpdates() {
        return sequenceUpdates;
    }
}
