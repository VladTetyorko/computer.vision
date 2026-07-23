-- Baseline schema for the fleet-side repository ports (docs/MVP2-PLAN.md P-a):
-- categories, devices, assets, and the asset<->device membership join. History
-- (usages, telemetry, detections) is P-b's schema, not this one's.
--
-- UUID primary keys match the domain id wrappers 1:1 (AssetId/DeviceId wrap
-- java.util.UUID). CategoryId is the one exception in the domain itself: a
-- human-authored kebab-case slug, not a generated UUID, so `categories.id`
-- follows suit. No foreign keys between categories/devices/assets: the
-- in-memory reference repositories this adapter must stay behavior-compatible
-- with perform no referential checks either (see each Jpa*Repository's
-- javadoc), so adding them here would reject operations the in-memory ports
-- happily allow.

CREATE TABLE categories (
    id              VARCHAR(64)   PRIMARY KEY,
    name            VARCHAR(255)  NOT NULL,
    parent_id       VARCHAR(64)   REFERENCES categories (id),
    attribute_hints JSONB         NOT NULL DEFAULT '[]'::jsonb
);

CREATE TABLE devices (
    id              UUID          PRIMARY KEY,
    name            VARCHAR(255)  NOT NULL,
    stream_protocol VARCHAR(64)   NOT NULL,
    stream_uri      VARCHAR(2048) NOT NULL,
    stream_options  JSONB         NOT NULL DEFAULT '{}'::jsonb,
    state           VARCHAR(32)   NOT NULL
);

CREATE TABLE device_capabilities (
    device_id  UUID        NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    capability VARCHAR(32) NOT NULL,
    PRIMARY KEY (device_id, capability)
);

CREATE TABLE assets (
    id           UUID         PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    category_id  VARCHAR(64)  NOT NULL,
    owner_id     UUID         NOT NULL,
    group_id     UUID         NOT NULL,
    attributes   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    state        VARCHAR(32)  NOT NULL
);

CREATE TABLE asset_devices (
    asset_id  UUID NOT NULL REFERENCES assets (id) ON DELETE CASCADE,
    device_id UUID NOT NULL,
    PRIMARY KEY (asset_id, device_id)
);

CREATE INDEX idx_asset_devices_device_id ON asset_devices (device_id);
