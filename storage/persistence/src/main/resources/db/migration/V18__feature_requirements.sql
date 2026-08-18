-- docs/plans/active/DRONE-ONBOARDING-PLAN.md O5: the feature x requirement matrix, expressed as
-- data (D6) -- adding a requirement is meant to be a migration, never a five-layer code edit
-- (FeatureRequirement's own javadoc). Seeded once per (firmware, feature_key); `id` is a synthetic
-- natural key ("<firmware>:<feature_key>") this adapter invents purely so the row has a JPA
-- primary key -- FeatureRequirement itself (contexts/vision-flight) carries no id field, the same
-- "domain record has no identity of its own" situation DetectionResultEntity/TelemetrySampleEntity
-- solve with a random UUID; here the natural composite key is stable and human-readable, so it is
-- used directly instead, matching CategoryEntity's own natural-string-id precedent.
--
-- Only `firmware = 'ardupilot'` is seeded for v1. D13 ("PX4 is 'generic MAVLink, unverified' until
-- a PX4 SITL run proves otherwise") means inventing PX4-specific thresholds here would be exactly
-- the fabricated-capability D13 forbids; a firmware with zero rows is not an error --
-- ReadinessService reports every feature UNKNOWN for it, "a correct answer, not a failure" (O3
-- MODULE.md).
--
-- Message ids verified against pymavlink's own common.xml dictionary (not memory): HEARTBEAT=0,
-- SYS_STATUS=1, GPS_RAW_INT=24, ATTITUDE=30, GLOBAL_POSITION_INT=33, RC_CHANNELS=65, VFR_HUD=74.
--
-- Thresholds: two of the eleven rows carry a plan-given number (docs/conclusions/ANY-DRONE-PLAN.md
-- SS1.2) -- map-position's GLOBAL_POSITION_INT >= 2 Hz, visual-geolocation's ATTITUDE >= 5 Hz.
-- Every other message-bearing row's minimum-Hz (preflight-checks, ground-speed, link-quality,
-- failsafe-banners, battery) has NO plan-given number -- ANY-DRONE SS1.2 names which message each
-- needs but never a rate for it, and FeatureRequirement's own compact constructor requires a
-- non-null, non-negative minimumHz whenever a message id is present, so a value had to be chosen.
-- Seeded at a conservative 1.0 Hz floor (the MAVLink stream-rate convention a healthy link clears
-- easily; ArduPilot's own SRx_* defaults sit at or above it) -- a **judgment call**, not a
-- plan-sourced number, flagged here and in storage/persistence/MODULE.md for whoever tunes it.
--
-- preflight-checks: ANY-DRONE SS1.2 lists BOTH GPS_RAW_INT and STATUSTEXT as required, but
-- FeatureRequirement models exactly one message per row (and ReadinessRow's own wire shape --
-- a {featureKey: status} map -- cannot hold two rows under the same key either). GPS_RAW_INT was
-- kept as the modeled signal (the row's own "arming blockers" symptom is GPS-shaped); STATUSTEXT
-- is not independently checked by this table. Flagged, not silently resolved.
--
-- fleet-identity is parameter-only (SYSID_THISMAV, ANY-DRONE's own literal example). command-tx,
-- rc-relay and video-ingest are "neither" rows -- FeatureRequirement's own javadoc names command-tx
-- as exactly this case: a feature whose readiness turns on the vehicle's capability bitmask (or,
-- for video-ingest, a non-MAVLink signal entirely), not a message or parameter this table can check;
-- such a row is trivially satisfied once it exists, per ReadinessService's documented rule.
--
-- The battery row only expresses "is SYS_STATUS arriving at all", not the 45%-low-battery bar
-- SS8.1 also names as a "runtime-variable ... row in feature_requirement" -- FeatureRequirement has
-- no field to carry a percentage threshold (only minimumHz/requiredParameterName), so that number
-- cannot be represented in this table as it stands. Left out rather than smuggled into minimumHz,
-- and flagged in MODULE.md/the wave report for whoever extends the domain type.
--
-- No FK to any other table -- same "reference/global data, no cross-entity foreign keys"
-- convention as geofence_zones/categories. `firmware` deliberately stays a plain varchar, not an
-- enum/lookup table -- a new firmware string is meant to be a migration adding rows, never a schema
-- change (D6).
CREATE TABLE feature_requirements (
    id                       VARCHAR(160) PRIMARY KEY,
    feature_key              VARCHAR(64) NOT NULL,
    label                    VARCHAR(255) NOT NULL,
    firmware                 VARCHAR(32) NOT NULL,
    required_message_id     INTEGER,
    required_message_name   VARCHAR(64),
    minimum_hz               DOUBLE PRECISION,
    required_parameter_name VARCHAR(64)
);

CREATE INDEX idx_feature_requirements_firmware ON feature_requirements (firmware);

INSERT INTO feature_requirements
    (id, feature_key, label, firmware, required_message_id, required_message_name, minimum_hz, required_parameter_name)
VALUES
    ('ardupilot:map-position', 'map-position', 'Map position', 'ardupilot', 33, 'GLOBAL_POSITION_INT', 2.0, NULL),
    ('ardupilot:preflight-checks', 'preflight-checks', 'Preflight checklist', 'ardupilot', 24, 'GPS_RAW_INT', 1.0, NULL),
    ('ardupilot:ground-speed', 'ground-speed', 'Ground speed', 'ardupilot', 74, 'VFR_HUD', 1.0, NULL),
    ('ardupilot:link-quality', 'link-quality', 'Link quality', 'ardupilot', 65, 'RC_CHANNELS', 1.0, NULL),
    ('ardupilot:failsafe-banners', 'failsafe-banners', 'Failsafe banners', 'ardupilot', 0, 'HEARTBEAT', 1.0, NULL),
    ('ardupilot:battery', 'battery', 'Battery', 'ardupilot', 1, 'SYS_STATUS', 1.0, NULL),
    ('ardupilot:visual-geolocation', 'visual-geolocation', 'Visual geolocation', 'ardupilot', 30, 'ATTITUDE', 5.0, NULL),
    ('ardupilot:fleet-identity', 'fleet-identity', 'Fleet identity', 'ardupilot', NULL, NULL, NULL, 'SYSID_THISMAV'),
    ('ardupilot:command-tx', 'command-tx', 'Command TX', 'ardupilot', NULL, NULL, NULL, NULL),
    ('ardupilot:rc-relay', 'rc-relay', 'RC relay', 'ardupilot', NULL, NULL, NULL, NULL),
    ('ardupilot:video-ingest', 'video-ingest', 'Video ingest', 'ardupilot', NULL, NULL, NULL, NULL)
ON CONFLICT (id) DO NOTHING;
