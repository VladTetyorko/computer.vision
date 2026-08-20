-- Visual geolocation's corrected track (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.5/§3.7/D12,
-- H5): contexts/vision-flight's DefaultTrackCorrectionService.submit persists one row per gated
-- VisualFix -- the aircraft's own position, a second opinion beside its raw reported telemetry,
-- CorrectionStatus.NO_FIX rows included (a refusal is a measurement, not an error).
--
-- track_corrections (excluded -- no trigger) -- append-only, ~1 Hz per flying asset while
-- vision.geo.visual.enabled=true, machine output. The identical reasoning V21 wrote for
-- detection_results/telemetry_samples and V22 for projected_track_points: a trigger here would
-- flood db_audit_log with output nobody reads, not a record of anyone's intent. Pruned by
-- VisualGeoRunner on its own cadence (vision.geo.visual.retention / max-rows-per-usage).
--
-- No foreign keys -- the schema's own "no cross-entity FK" convention (a real constraint would
-- reject writes the in-memory devsupport repositories accept).
--
-- altitude_meters is present for the same reason V22 added it to projected_track_points:
-- GeoPosition has a real nullable third component and omitting the column silently drops it on
-- every round trip -- TrackCorrection.position().altitudeMeters() is always null in practice (a
-- 2-D homography fix), but the column stays honest about the domain type's own shape.
--
-- This table is added to PostgresDockerIntegrationTest's EXCLUDED_TABLES (the live source of
-- truth DbAuditLogCoverageTests reads) in the same change as this migration.

CREATE TABLE track_corrections (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_id               UUID NOT NULL,
    usage_id               UUID NOT NULL,
    frame_at               TIMESTAMPTZ NOT NULL,
    computed_at            TIMESTAMPTZ NOT NULL,
    status                 VARCHAR(12) NOT NULL,
    source                 VARCHAR(16) NOT NULL,
    latitude               DOUBLE PRECISION,
    longitude              DOUBLE PRECISION,
    altitude_meters        DOUBLE PRECISION,          -- GeoPosition's own nullable third component
    yaw_degrees            DOUBLE PRECISION,
    radius_meters          DOUBLE PRECISION,
    implied_agl_meters     DOUBLE PRECISION,
    raw_latitude           DOUBLE PRECISION,
    raw_longitude          DOUBLE PRECISION,
    separation_meters      DOUBLE PRECISION,
    sigma_meters           DOUBLE PRECISION,
    divergent              BOOLEAN NOT NULL,
    divergent_since        TIMESTAMPTZ,
    region_id              TEXT NOT NULL,
    tile_id                TEXT NOT NULL,
    refusal                TEXT NOT NULL,
    match_count            INTEGER NOT NULL,
    inlier_count           INTEGER NOT NULL,
    inlier_ratio           DOUBLE PRECISION NOT NULL,
    rerank_margin          DOUBLE PRECISION NOT NULL,
    reprojection_rms_px    DOUBLE PRECISION NOT NULL,
    rectified              BOOLEAN NOT NULL,
    -- H8, VISUAL-GEO-V2-PLAN.md 9.11 defect 4: these two booleans are what DefaultTrackCorrection-
    -- Service reads to decide PROBABLE vs CONFIRMED (pythonEligible = sequenceConverged &&
    -- cellCalibrated). They existed on the wire and in VisualFixEvidence from H1 but had no column,
    -- so every replayed correction read back false and an operator could never see WHY a fix was
    -- only PROBABLE -- exactly the "why is always one click away" rule D5 states. Added to this
    -- migration in place rather than as a V24: V23 is not yet shipped anywhere public (introduced on
    -- this same unmerged branch), so no deployed database has run it.
    cell_calibrated        BOOLEAN NOT NULL,
    sequence_converged     BOOLEAN NOT NULL,
    sequence_spread_meters DOUBLE PRECISION NOT NULL,
    sequence_updates       INTEGER NOT NULL
);
CREATE INDEX idx_track_corrections_usage_frame ON track_corrections (usage_id, frame_at);
CREATE INDEX idx_track_corrections_asset_frame ON track_corrections (asset_id, frame_at DESC);
CREATE INDEX idx_track_corrections_frame_at    ON track_corrections (frame_at);
