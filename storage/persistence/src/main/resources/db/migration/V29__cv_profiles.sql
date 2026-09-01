-- docs/plans/active/CV-SETTINGS-PLAN.md §5.3, §3.4 (CV-SETTINGS wave W3) -- persisted CV profiles
-- and the scope bindings that attach them, resolved asset -> category -> organization -> platform
-- at stream start (§3.1). No feature flag (§3.1 rule 3): this migration seeds the four built-in
-- profiles and ZERO bindings, so every existing stream keeps resolving to the byte-identical
-- PipelineConfig.defaults() it always has -- CvProfileTest/CvProfileBindingTest's own domain
-- invariants are the only thing enforcing the shape here; storage adds no invariant of its own.
--
-- No FKs anywhere (storage/persistence MODULE.md's standing convention) -- group_id and
-- cv_profile_bindings.profile_id are plain UUID columns, the same shape as every other
-- cross-entity reference in this schema.
CREATE TABLE cv_profiles (
    id                     UUID              PRIMARY KEY,
    name                   VARCHAR(120)      NOT NULL,
    description            VARCHAR(500)      NOT NULL DEFAULT '',
    built_in               BOOLEAN           NOT NULL DEFAULT FALSE,
    group_id               UUID,
    -- CvProfile#model is a ModelRef(id, version), not a single string -- split into two columns
    -- (model_id/model_version) rather than the plan's single "model" column, mirroring
    -- cv_models.model_id/version below. A single-column mapping would either drop the version
    -- component (ModelRef's compact constructor requires it non-blank, so it cannot round-trip
    -- from one string alone) or overload one column with a composite "id@version" encoding this
    -- schema does not use anywhere else. Deviation from §5.3's literal column list, flagged here
    -- and in CV-SETTINGS-CONTEXT.md's W3 handoff.
    model_id               VARCHAR(120)      NOT NULL,
    model_version          VARCHAR(60)       NOT NULL DEFAULT 'latest',
    confidence_threshold   DOUBLE PRECISION  NOT NULL,
    inference_fps          INTEGER           NOT NULL,
    -- Ordered lists, read back whole -- same jsonb-list convention as datasets.classes/
    -- categories.attribute_hints, matching CvProfile#labelFilter/#labelDenyFilter staying
    -- List<String> rather than PipelineConfig's Set<String> (W1 deviation 1).
    label_filter           JSONB             NOT NULL DEFAULT '[]'::jsonb,
    label_deny_filter      JSONB             NOT NULL DEFAULT '[]'::jsonb,
    detection_enabled      BOOLEAN           NOT NULL,
    -- Whole TrackingConfig record as jsonb -- same mechanism as control_profiles.channel_map:
    -- read back whole, never queried into by individual field.
    tracking               JSONB             NOT NULL,
    -- Not in §5.3's column list -- the domain CvProfile carries an EventRuleConfig (§5.1's
    -- "eventRule" field; H9's fix), and the plan's own persistence table omitted it. Added here as
    -- a deliberate deviation rather than silently dropping the field: a profile with no event_rule
    -- column could not round-trip through CvProfileRepositoryPort#save/#findById at all.
    event_rule             JSONB             NOT NULL,
    created_at             TIMESTAMPTZ       NOT NULL,
    updated_at             TIMESTAMPTZ       NOT NULL
);

-- CvProfileBinding: at most one profile bound per (scopeKind, scopeId) -- the composite primary
-- key both enforces that and makes saveBinding a plain upsert. scope_id is VARCHAR, not UUID:
-- CvProfileBinding's own javadoc requires it (a CategoryId is a kebab-case slug, not a UUID, so a
-- single typed column could not hold every BindingScope's id shape).
CREATE TABLE cv_profile_bindings (
    scope_kind   VARCHAR(16)  NOT NULL,
    scope_id     VARCHAR(120) NOT NULL,
    profile_id   UUID         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (scope_kind, scope_id)
);

-- The four built-in profiles (§3.4), fixed ids so every deployment seeds the identical rows,
-- built_in=true / group_id=NULL (CvProfile's own bidirectional invariant: builtIn iff groupId is
-- null -- W1 deviation 2). tracking/event_rule are byte-identical to TrackingConfig.defaults()/
-- EventRuleConfig.defaults() for all four rows -- §3.4 only calls out ASSOCIATE tracking for
-- people-vehicles by name, but ASSOCIATE-with-every-cadence-at-its-default IS
-- TrackingConfig.defaults(), and nothing in §3.4 asks for a different tracking mode on the other
-- three, so every built-in ships the same platform-default tracking/event-rule pair a stream would
-- get anyway. labelFilter/labelDenyFilter are empty for all four (§3.4 does not specify either),
-- matching PipelineConfig.defaults()'s own "no label filtering" starting point.
--
-- video-only's model/confidence/fps are unused by construction (detection_enabled=false means
-- StreamPipeline never calls detect() -- CvProfile#toPipelineConfig still requires a non-null
-- model/valid confidence/positive fps, so it carries the same platform-default values
-- PipelineConfig.defaults() does, matching "video-only" having nothing else to say about them.
INSERT INTO cv_profiles (id, name, description, built_in, group_id, model_id, model_version,
                          confidence_threshold, inference_fps, label_filter, label_deny_filter,
                          detection_enabled, tracking, event_rule, created_at, updated_at)
VALUES
    ('f8fb1ff5-2dd8-4cb6-b0f1-5a5f4c5c056f', 'people-vehicles',
     'People and vehicles at a moderate rate -- the platform''s own default coverage.',
     TRUE, NULL, 'yolo26n.pt', 'latest', 0.40, 10, '[]'::jsonb, '[]'::jsonb, TRUE,
     '{"mode":"ASSOCIATE","engineId":"","verifyEveryMillis":2000,"followFps":15,"redetectIouPercent":30,"maxAgeFrames":30,"minHits":3,"capabilityLevel":0,"reupdateMaxGapMillis":0,"lock":null}'::jsonb,
     '{"labels":["person","car"],"confidenceThreshold":0.5,"consecutiveToOpen":3,"absenceToClose":"PT5S"}'::jsonb,
     now(), now()),
    ('a42e5d7c-b977-4099-980c-b14e94518e6a', 'wide-search',
     'Open-vocabulary wide search at a low rate -- broad coverage over precision.',
     TRUE, NULL, 'yoloe-26s-seg-pf.pt', 'latest', 0.30, 4, '[]'::jsonb, '[]'::jsonb, TRUE,
     '{"mode":"ASSOCIATE","engineId":"","verifyEveryMillis":2000,"followFps":15,"redetectIouPercent":30,"maxAgeFrames":30,"minHits":3,"capabilityLevel":0,"reupdateMaxGapMillis":0,"lock":null}'::jsonb,
     '{"labels":["person","car"],"confidenceThreshold":0.5,"consecutiveToOpen":3,"absenceToClose":"PT5S"}'::jsonb,
     now(), now()),
    ('0ca952cf-284a-4a33-b04c-0c9da6a64602', 'military-vehicles',
     'Military vehicle detection at a low rate -- the slower, larger model.',
     TRUE, NULL, 'orion12l.pt', 'latest', 0.45, 5, '[]'::jsonb, '[]'::jsonb, TRUE,
     '{"mode":"ASSOCIATE","engineId":"","verifyEveryMillis":2000,"followFps":15,"redetectIouPercent":30,"maxAgeFrames":30,"minHits":3,"capabilityLevel":0,"reupdateMaxGapMillis":0,"lock":null}'::jsonb,
     '{"labels":["person","car"],"confidenceThreshold":0.5,"consecutiveToOpen":3,"absenceToClose":"PT5S"}'::jsonb,
     now(), now()),
    ('5e0cd997-743f-4867-82c7-e2be176c23ad', 'video-only',
     'Video only -- detection stays off; the asset streams video and nothing else.',
     TRUE, NULL, 'yolo26n.pt', 'latest', 0.40, 10, '[]'::jsonb, '[]'::jsonb, FALSE,
     '{"mode":"ASSOCIATE","engineId":"","verifyEveryMillis":2000,"followFps":15,"redetectIouPercent":30,"maxAgeFrames":30,"minHits":3,"capabilityLevel":0,"reupdateMaxGapMillis":0,"lock":null}'::jsonb,
     '{"labels":["person","car"],"confidenceThreshold":0.5,"consecutiveToOpen":3,"absenceToClose":"PT5S"}'::jsonb,
     now(), now());

-- Zero bindings seeded (§3.1 rule 3) -- deliberately no INSERT into cv_profile_bindings here.

-- CV-SETTINGS-PLAN.md §3.5/§5.2 canManageOrg-gated admin config, same "who changed X" character as
-- control_profiles/datasets/maintenance_records -- control-plane accountability, not machine-output
-- telemetry, so both tables join the audited set rather than the excluded one (storage/persistence
-- MODULE.md "Database change audit"). cv_profile_bindings is a security/routing decision over a
-- join row, the same reasoning that already put pilot_assignments and map_layer_grants in the
-- audited set despite both being plain join tables.
CREATE TRIGGER trg_audit_cv_profiles AFTER INSERT OR UPDATE OR DELETE ON cv_profiles
    FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER trg_audit_cv_profile_bindings AFTER INSERT OR UPDATE OR DELETE ON cv_profile_bindings
    FOR EACH ROW EXECUTE FUNCTION audit_row_change();
