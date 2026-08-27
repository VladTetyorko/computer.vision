-- docs/plans/active/FLEET-RADIO-PLAN.md R6 -- two ArduPilot settings each silently discard every
-- MAVLink RC override this platform sends: no error, no rejection, no wire response at all, the
-- sticks simply do nothing. Both are readiness-checkable from a vehicle's already-probed
-- VehicleProfile, so this migration gives the existing `rc-relay` feature key (V18) two real,
-- value-aware requirement rows instead of the placeholder "neither message nor parameter, trivially
-- satisfied" row V18 seeded for it -- `rc-relay`'s label was always "RC relay"; this is the first
-- wave that gives it something to actually check.
--
-- Facts verified against ArduPilot master (not memory), 2026-08-27:
--
--   * RC_OPTIONS bit 1 is IGNORE_OVERRIDES = (1U << 1), from libraries/RC_Channel/RC_Channel.h's
--     `enum class Option`. Bit SET means the vehicle IGNORES MAVLink overrides -- the requirement is
--     therefore that the bit be CLEAR. Getting this backwards inverts the verdict for every vehicle
--     (DefaultReadinessServiceTest asserts the polarity by value, both directions, not by reading the
--     constant's name).
--   * This platform transmits as MAVLink sysid 255 (mavlink-core's MavlinkNode.groundStation(),
--     255/190, the conventional GCS identity). ArduPilot accepts RC overrides only from the GCS
--     sysid named by SYSID_MYGCS / MAV_GCS_SYSID (renamed in 4.7 -- the same rename F0 already
--     handled for SYSID_THISMAV/MAV_SYSID); a mismatch discards this platform's overrides silently.
--     ParameterAliases (contexts/vision-flight, R0) already knows the SYSID_MYGCS/MAV_GCS_SYSID
--     pair, so DefaultReadinessService compares alias-aware, matching whichever spelling the vehicle
--     actually answered under.
--   * CLEAR_OVERRIDES_BY_RC (bit 14) is adjacent -- with it set, an operator touching the physical
--     sticks silently ends manual control. Not modeled as a readiness blocker here: unlike the two
--     rows below, it does not misconfigure the *link*, it is the vehicle's designed pilot-override
--     behaviour, and surfacing it usefully needs live-stick telemetry this configuration-only table
--     has no way to check. Left for whoever next extends this table with a telemetry-derived half
--     (ReadinessService's own documented scope gap).
--
-- Two new nullable columns generalize `required_parameter_name`'s previous presence-only check to a
-- value-aware one (FeatureRequirement's own compact constructor requires requiredParameterName
-- whenever either is set): `required_parameter_value` -- the parameter must equal exactly this
-- (compared with a small float tolerance); `forbidden_parameter_bits` -- the parameter's value
-- (rounded to a bitmask) must have none of these bits set. Both stay data, not a Java constant, so
-- the sysid this platform transmits as and the RC_OPTIONS bit stay seeded facts a future rename or
-- reclassification only ever needs a migration for.
--
-- Two rows, one key, not two new frozen keys: `FeatureRequirement.FEATURE_KEYS` stays the frozen
-- eleven (contexts/vision-flight/.../domain/model/FeatureRequirement.java, docs). Both new facts are
-- independent ways a MAVLink RC override fails to reach the servos -- exactly what `rc-relay`'s own
-- label already means -- and `ReadinessRowResponse` (vision-api)'s fleet-board wire shape is a
-- `{featureKey: status}` map that cannot hold two statuses under one key regardless (this table's
-- own V18 comment already hit this limit once, for preflight-checks/GPS_RAW_INT+STATUSTEXT).
-- DefaultReadinessService folds every row sharing a key into one FeatureReadiness (worst status,
-- joined detail, first remedy at that status) before it ever reaches the wire, so two rows here never
-- produce two entries on it. See DefaultReadinessService's own "Value/bit-aware parameter checks"
-- javadoc and this wave's report/MODULE.md entry for the full reasoning.
--
-- The placeholder row V18 seeded for `rc-relay` (neither message nor parameter, therefore always
-- trivially READY once a profile exists) is retired below rather than left alongside the two real
-- rows -- three rows under one key for no reason would only confuse the next reader; the two new
-- rows below are the row's whole meaning now.
ALTER TABLE feature_requirements
    ADD COLUMN required_parameter_value    DOUBLE PRECISION,
    ADD COLUMN forbidden_parameter_bits    BIGINT;

DELETE FROM feature_requirements WHERE id = 'ardupilot:rc-relay';

INSERT INTO feature_requirements
    (id, feature_key, label, firmware, required_message_id, required_message_name, minimum_hz,
     required_parameter_name, required_parameter_value, forbidden_parameter_bits)
VALUES
    ('ardupilot:rc-relay:gcs-sysid', 'rc-relay', 'RC relay (GCS sysid)', 'ardupilot',
        NULL, NULL, NULL, 'SYSID_MYGCS', 255.0, NULL),
    ('ardupilot:rc-relay:rc-options', 'rc-relay', 'RC relay (RC_OPTIONS)', 'ardupilot',
        NULL, NULL, NULL, 'RC_OPTIONS', NULL, 2)
ON CONFLICT (id) DO NOTHING;
