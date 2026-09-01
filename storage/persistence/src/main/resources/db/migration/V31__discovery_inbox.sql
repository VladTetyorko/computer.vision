-- docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §11 (Z2c): the discovery inbox's persistence.
-- Z2a (contexts/vision-warehouse) built DiscoveryCandidate/DiscoveryCandidateId/CandidateStatus and
-- DiscoveryInboxService/DefaultDiscoveryInboxService against a DiscoveryCandidateRepositoryPort with
-- no adapter yet -- this migration is that adapter's table.
--
-- The whole point of this table (§3 P2): discovery stops being "a scan button feeding a form" and
-- becomes a persisted, deduplicated "found devices" inbox that survives a station restart. A
-- DiscoveryCandidate is the transient, in-memory DiscoveredDevice a scan produced, upserted by
-- identityKey (method + "|" + address, plus "|sysid=" when the device carries one -- see
-- DiscoveryCandidate.identityKeyFor's own javadoc) and carrying the operator's own decision about it
-- (NEW / DISMISSED / REGISTERED).
--
-- Column shapes below mirror this schema's own existing precedent field-for-field rather than
-- inventing new conventions: category_id/suggested_category are VARCHAR(64) like categories.id
-- (CategoryId is a human slug, not a UUID); stream_protocol/stream_uri/stream_options on devices
-- (V1__baseline.sql) are the exact template for suggested_stream_protocol/suggested_stream_uri/
-- suggested_stream_options below -- StreamDescriptor's shape does not change just because this one
-- is nullable (a candidate with no ready-to-use stream, e.g. ONVIF before GetStreamUri, has
-- suggested_stream_protocol/uri/options all NULL together). details is JSONB NOT NULL DEFAULT '{}'
-- like attributes/attribute_hints elsewhere -- DiscoveredDevice.details is never null at the domain
-- level (its own compact constructor rejects a null map), only ever empty.
--
-- Nothing about DiscoveredDevice is dropped: method, name, address, suggestedCategory,
-- suggestedStream (protocol + uri + every option), and details all round-trip whole -- the
-- documented "suggestedStream.options() dropped" defect on the existing POST /api/discovery/scan
-- wire response (DiscoveredDeviceResponse) is a vision-api DTO problem, not a persistence gap, and
-- is out of this migration's/this wave's scope (deferred to a later Z4 fix per the frozen contract).
--
-- No FK to assets (registered_asset_id) -- this schema's standing "no cross-entity foreign keys"
-- convention (storage/persistence MODULE.md Gotchas); a candidate outlives the asset lookup that
-- created it and this table must never fail to insert because of a foreign asset row's state.
CREATE TABLE discovery_candidates (
    id                         UUID          PRIMARY KEY,
    identity_key               VARCHAR(1024) NOT NULL,
    method                     VARCHAR(64)   NOT NULL,
    name                       VARCHAR(255)  NOT NULL,
    address                    VARCHAR(2048) NOT NULL,
    suggested_category         VARCHAR(64),
    suggested_stream_protocol  VARCHAR(64),
    suggested_stream_uri       VARCHAR(2048),
    suggested_stream_options   JSONB,
    details                    JSONB         NOT NULL DEFAULT '{}'::jsonb,
    first_seen                 TIMESTAMPTZ   NOT NULL,
    last_seen                  TIMESTAMPTZ   NOT NULL,
    status                     VARCHAR(16)   NOT NULL,
    registered_asset_id        UUID
);

-- The dedup key DiscoveryInboxService#report upserts by (findByIdentityKey then save). The service
-- itself serializes every mutating call against one coarse lock (DefaultDiscoveryInboxService's own
-- javadoc explains why this port need not itself guarantee an atomic upsert), so this index is not
-- load-bearing for that in-process race -- but it IS what makes the same guarantee hold across a
-- restart/redeploy (a fresh JVM's lock is a fresh lock; the identity key must still resolve to at
-- most one row) and across any future second app instance sharing this database. A unique index,
-- not merely a unique constraint check in application code, is the actual enforcement.
CREATE UNIQUE INDEX uq_discovery_candidates_identity_key ON discovery_candidates (identity_key);

-- candidates() (DiscoveryInboxService) and the inbox list endpoint read "every candidate, newest
-- report first" -- same (ordering-column) index shape as vehicle_profiles'/control_profiles' own.
CREATE INDEX idx_discovery_candidates_last_seen ON discovery_candidates (last_seen DESC);

-- Control-plane accountability, so this table joins V21's audited set rather than its excluded one:
-- "who dismissed/registered this candidate, and when" is exactly the question db_audit_log exists to
-- answer, the same reasoning already applied to control_profiles (V24)/maintenance_records (V28).
-- Write volume is sweep-driven (a periodic scan every tens of seconds, at most a few dozen rows) --
-- nothing like the per-frame/per-sample character of the excluded telemetry-shaped tables.
CREATE TRIGGER trg_audit_discovery_candidates AFTER INSERT OR UPDATE OR DELETE ON discovery_candidates
    FOR EACH ROW EXECUTE FUNCTION audit_row_change();
