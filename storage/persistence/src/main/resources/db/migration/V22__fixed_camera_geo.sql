-- Fixed-camera geolocation (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md decisions D3/D4, §7): a
-- camera whose position/orientation is known turns a tracked object's bounding-box bottom-centre
-- into a ground coordinate on the shared map. Two tables, opposite sides of the V21 audit
-- classification:
--
--   camera_poses (audited -- trg_audit_camera_poses attached below, reusing V21's own
--   audit_row_change() function, not redefining it) -- control-plane configuration, one row per
--   asset (upsert), human/solver-written. "Who re-aimed the camera" is exactly the kind of
--   question db_audit_log exists to answer, and write volume is tiny (one row per calibration or
--   manual edit, never per tick).
--
--   projected_track_points (excluded -- no trigger) -- append-only, high-volume, telemetry-
--   character output of the projection runner (nominally ~1 Hz per tracked object, decimated
--   further by vision.geo.fixed-camera.trail.min-distance-meters before a row is even written).
--   Same character V21 already gave detection_results/telemetry_samples: a trigger here would
--   flood db_audit_log with machine output nobody reads, not a record of anyone's intent.
--
-- Both tables are added to PostgresDockerIntegrationTest's AUDITED_TABLES/EXCLUDED_TABLES (the
-- live source of truth DbAuditLogCoverageTests reads -- see that class's own comment) in the same
-- change as this migration.
--
-- Gap this migration's own plan (§7) left unaddressed: its table sketches list only
-- latitude/longitude for each table's position, not altitude. Both CameraPose#position() and
-- TrackPoint#position() are kernel GeoPosition values, whose altitudeMeters is a real (if
-- currently projection-unused, per FixedCameraGeo's javadoc) nullable component -- omitting the
-- column would silently drop it on every save/load round trip. Both tables therefore carry a
-- nullable altitude_meters column, the same "flatten a small value type into columns" choice
-- V10 (marks) and V15 (detection_events) already make for their own single-point GeoPositions.
--
-- No FK on either table -- same "no cross-entity foreign keys" convention as the rest of this
-- schema (see MODULE.md's Conventions): a real constraint would reject writes the in-memory
-- devsupport repositories happily accept, breaking round-trip parity.

CREATE TABLE camera_poses (
    asset_id         UUID PRIMARY KEY,
    latitude         DOUBLE PRECISION NOT NULL,
    longitude        DOUBLE PRECISION NOT NULL,
    altitude_meters  DOUBLE PRECISION,
    agl_meters       DOUBLE PRECISION NOT NULL,
    yaw_degrees      DOUBLE PRECISION NOT NULL,
    pitch_degrees    DOUBLE PRECISION NOT NULL,
    hfov_degrees     DOUBLE PRECISION NOT NULL,
    target_layer_id  UUID,
    source           VARCHAR(12) NOT NULL,
    rms_error_pixels DOUBLE PRECISION,
    updated_at       TIMESTAMPTZ NOT NULL,
    updated_by       UUID NOT NULL
);

CREATE TABLE projected_track_points (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_id            UUID NOT NULL,
    track_id            BIGINT NOT NULL,
    label               TEXT NOT NULL,
    layer_id            UUID NOT NULL,
    latitude            DOUBLE PRECISION NOT NULL,
    longitude           DOUBLE PRECISION NOT NULL,
    altitude_meters     DOUBLE PRECISION,
    error_radius_meters DOUBLE PRECISION NOT NULL,
    captured_at         TIMESTAMPTZ NOT NULL
);

-- Two real access paths: a track's own trail (findByTrack/findLatest/trimToMostRecent, oldest- or
-- newest-first within one (asset_id, track_id)), and the retention prune (deleteOlderThan, across
-- every track).
CREATE INDEX idx_projected_track_points_asset_track_captured
    ON projected_track_points (asset_id, track_id, captured_at);
CREATE INDEX idx_projected_track_points_captured_at ON projected_track_points (captured_at);

CREATE TRIGGER trg_audit_camera_poses AFTER INSERT OR UPDATE OR DELETE ON camera_poses
    FOR EACH ROW EXECUTE FUNCTION audit_row_change();
