-- docs/plans/active/CONTROLLER-SETUP-CONTEXT.md C6: an operator's saved controller layouts -- which
-- stick, button and switch of *their* transmitter does what. Owned per user rather than per asset:
-- two pilots may share every aircraft on the field and still have different hardware in their hands.
--
-- The platform's own built-in profiles are deliberately NOT rows here. ControlProfile.forKind() must
-- answer on a server where this table is empty, so the fallback cannot depend on a migration having
-- seeded anything (C7); a built-in's id is derived from its vehicle kind instead.
--
-- channel_map/action_map are jsonb -- a profile is only ever read back whole (findById/findActive
-- return one ControlProfile), never queried into by individual binding, the same "jsonb over a
-- normalized child table" convention vehicle_profiles/detection_results/geofence_zones already use.
CREATE TABLE control_profiles (
    id            UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    vehicle_kind  VARCHAR(32) NOT NULL,
    code          VARCHAR(16) NOT NULL,
    display_name  VARCHAR(120) NOT NULL,
    active        BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at    TIMESTAMPTZ NOT NULL,
    channel_map   JSONB NOT NULL DEFAULT '[]'::jsonb,
    action_map    JSONB NOT NULL DEFAULT '[]'::jsonb
);

-- findAllByOwner is "this operator's profiles, newest edit first" -- index the owner together with
-- the ordering column, same shape as vehicle_profiles' own (device, observed_at) index.
CREATE INDEX idx_control_profiles_owner ON control_profiles (owner_user_id, updated_at DESC);

-- ControlProfileRepositoryPort promises AT MOST ONE active profile per (owner, vehicle kind), and
-- promises it atomically. A partial unique index makes the database itself the enforcer: two active
-- rows would otherwise leave findActive picking by read order, which surfaces much later as "my
-- sticks were different today" and is untraceable when it does.
CREATE UNIQUE INDEX uq_control_profiles_active
    ON control_profiles (owner_user_id, vehicle_kind) WHERE active;

-- Control-plane configuration, so it joins V21's audited set rather than its excluded one: a saved
-- layout decides what a switch does to an aircraft, and "which binding was in force at the time" is
-- exactly the question an investigation asks. Volume is per-operator and edit-driven -- nothing like
-- the telemetry-character tables V21 excludes.
CREATE TRIGGER trg_audit_control_profiles AFTER INSERT OR UPDATE OR DELETE ON control_profiles
    FOR EACH ROW EXECUTE FUNCTION audit_row_change();
