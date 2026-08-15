-- docs/plans/done/OPS-CORE-PLAN.md §G: geofence zones (global reference data -- every asset is evaluated
-- against every enabled zone; per-group scoping is a later cycle, docs/plans/done/OPS-CORE-PLAN.md §G, U-e).
--
-- polygon is jsonb (the whole List<GeoPosition>, at least 3 vertices, enforced application-side
-- by GeofenceZone/GeofenceZoneSpec's own compact constructors -- not a database CHECK constraint,
-- same "validate in the domain/spec, not the schema" convention as every other jsonb column here).
-- No FK to any other table, same "no cross-entity foreign keys" convention as the rest of this
-- schema (see MODULE.md's Conventions) -- zones have no relationship to assets/devices.
CREATE TABLE geofence_zones (
    id                  UUID PRIMARY KEY,
    name                VARCHAR(255) NOT NULL,
    kind                VARCHAR(16) NOT NULL,
    polygon             JSONB NOT NULL,
    max_altitude_meters DOUBLE PRECISION,
    enabled             BOOLEAN NOT NULL
);
