-- Database-level change audit: a durable, unbypassable record of what actually changed in
-- Postgres, row by row.
--
-- This is deliberately NOT the same thing as audit_entries (V14__audit_trail.sql,
-- AuditTrailPort/AuditEntryEntity). That table answers "which user did what to the fleet" --
-- a domain-intent record, written only where an application service remembers to call
-- AuditTrailPort#record. This table answers "which rows in this database changed, when, and
-- how" -- including a change made by a DBA typing SQL into psql, by a migration, or by any code
-- path that forgot to call AuditTrailPort. It is infrastructure, not domain; storage/persistence/
-- MODULE.md's "Database change audit" section spells out the distinction in full. Do not merge
-- the two tables or the two mechanisms.
--
-- Mechanism, and why it is a trigger and not a Java/Hibernate interceptor: an interceptor can
-- always be bypassed -- manual SQL, a psql session, a future non-Hibernate writer -- and an audit
-- that can be bypassed is not an audit. PersistenceUnit also bootstraps Hibernate natively with
-- no Spring in front of it, so there is no framework-level hook here that would be any cleaner
-- than a trigger anyway. One generic PL/pgSQL function (audit_row_change) is attached to every
-- audited table below, rather than one function per table: it resolves each table's own
-- primary-key column(s) from the catalog (pg_index/pg_attribute) at trigger time, so a
-- composite-key join table (device_capabilities, asset_devices, pilot_assignments,
-- map_layer_grants) and a single-UUID-PK table (assets, users, ...) are both handled by the same
-- function -- row_id is always text, built by ':'-joining the primary-key column values in their
-- declared key order, even though every id in this schema is in fact a UUID (CLAUDE.md rule 1:
-- no per-table logic duplicated seventeen times where one generic rule already covers it).
--
-- Included -- the control-plane / configuration tables, enumerated by reading every migration
-- V1 through V20, not guessed:
--   categories, devices, device_capabilities, assets, asset_devices (V1__baseline.sql)
--   asset_usages (V3__history.sql -- a flight session record, not a per-sample event stream)
--   geofence_zones (V7__geofence_zones.sql)
--   groups, users (V8__users_groups.sql)
--   pilot_assignments (V9__pilot_assignments.sql)
--   marks (V10__marks.sql)
--   datasets (V11__training_datasets.sql -- see "training_samples"/"sample_images" below for
--     the other two tables that same migration created, deliberately NOT included)
--   map_layers, map_layer_grants, map_drawings (V12__map_layers.sql -- map_layer_grants is a
--     security decision, not a bulk collection: storage/persistence/MODULE.md already calls it
--     out as "the one collection whose individual rows are ... auditable in SQL", worth its own
--     trigger even though it is an @ElementCollection join table)
--   vehicle_profiles (V17__vehicle_profiles.sql)
--   feature_requirements (V18__feature_requirements.sql -- seed-only today, but exactly the
--     "someone edited it by hand" case this feature exists to catch)
--
-- Excluded -- the high-volume append-only event tables, plus tables where a trigger would be
-- actively wrong: a trigger on any of these would double the hottest write paths in the system
-- and drown the log in rows nobody will ever read.
--   telemetry_samples, detection_results (V3__history.sql)
--   detection_events (V15__detection_events.sql)
--   training_samples, sample_images (V11__training_datasets.sql -- captured-frame pipeline
--     output, one row per training frame, the same append-heavy character as detection_results)
--   asset_images (V5__asset_images.sql -- grouped with sample_images rather than with the
--     control-plane set it might otherwise resemble: to_jsonb() on a row with a bytea column
--     duplicates the whole image into every audit row it writes, and a fleet photo is content,
--     not a configuration value this feature needs to answer "who changed X" for)
--   audit_entries (V14__audit_trail.sql -- the existing domain audit trail; auditing an audit
--     trail is not useful, and its own write volume/character already matches this excluded set)
--   db_audit_log (this table -- a trigger on itself would recurse)
--   flyway_schema_history -- Flyway's own bookkeeping table, not application data
--
-- Redaction: to_jsonb() takes every column verbatim, so an audited table holding a secret would
-- copy it here. users.password_hash is replaced with "[redacted]" (the key kept, the value gone)
-- before the row image is written, and changed_columns is computed first so a password change is
-- still reported without either hash being stored. Any future migration adding a secret-bearing
-- column to an audited table must add its name to the trigger's redaction list.
--
-- Retention: deliberately not addressed by this migration. This table grows unbounded, the same
-- accepted tradeoff audit_entries already makes (see that table's own MODULE.md entry) -- a
-- purge/rollup job is a known open item, not built here.
--
-- Trigger installation is itself schema, governed by this migration -- there is no
-- vision.persistence.* flag gating it, and none should be added: a flag could not make a
-- Postgres trigger conditional on a JVM-side property anyway, and pretending otherwise would be
-- worse than having no flag at all.
CREATE TABLE db_audit_log (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    table_name      TEXT        NOT NULL,
    row_id          TEXT        NOT NULL,
    operation       VARCHAR(6)  NOT NULL CHECK (operation IN ('INSERT', 'UPDATE', 'DELETE')),
    db_user         TEXT        NOT NULL,
    old_row         JSONB,
    new_row         JSONB,
    changed_columns JSONB
);

-- Two real access paths: newest-first overall, and newest-first for one (table_name, row_id).
CREATE INDEX idx_db_audit_log_occurred_at ON db_audit_log (occurred_at);
CREATE INDEX idx_db_audit_log_table_row ON db_audit_log (table_name, row_id, occurred_at);

CREATE OR REPLACE FUNCTION audit_row_change() RETURNS trigger AS $$
DECLARE
    old_data      jsonb;
    new_data      jsonb;
    pk_cols       text[];
    row_ident     text;
    changed       jsonb;
    sensitive_col text;
BEGIN
    IF TG_OP = 'DELETE' THEN
        old_data := to_jsonb(OLD);
    ELSIF TG_OP = 'UPDATE' THEN
        old_data := to_jsonb(OLD);
        new_data := to_jsonb(NEW);
    ELSE
        new_data := to_jsonb(NEW);
    END IF;

    -- This table's own primary-key column(s), in declared key order, resolved from the catalog
    -- so this one function serves every audited table regardless of whether its key is a single
    -- UUID column or a composite join-table key.
    SELECT array_agg(a.attname ORDER BY k.ord)
    INTO pk_cols
    FROM pg_index i
             JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord) ON true
             JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
    WHERE i.indrelid = TG_RELID
      AND i.indisprimary;

    SELECT string_agg(COALESCE(new_data, old_data) ->> pk_col, ':' ORDER BY ord)
    INTO row_ident
    FROM unnest(pk_cols) WITH ORDINALITY AS u(pk_col, ord);

    IF TG_OP = 'UPDATE' THEN
        -- Every column whose value in new_data differs from old_data -- answers "what changed?"
        -- without the caller diffing two JSON blobs itself. Computed BEFORE redaction below, so a
        -- password change still reports "password_hash" as changed even though neither hash is kept.
        SELECT jsonb_agg(n.key ORDER BY n.key)
        INTO changed
        FROM jsonb_each(new_data) AS n(key, value)
        WHERE n.value IS DISTINCT FROM (old_data -> n.key);
    END IF;

    -- Secrets are never copied into this table. to_jsonb(NEW) takes every column verbatim, which
    -- for users would mean storing password_hash -- and on a password change, BOTH the old and the
    -- new hash -- in a table with a longer retention and a wider read audience than the row it came
    -- from. The key is replaced rather than dropped so the shape of the row stays honest: the reader
    -- can see the column exists and (via changed_columns) that it changed, without the value.
    FOREACH sensitive_col IN ARRAY ARRAY['password_hash'] LOOP
        IF old_data ? sensitive_col THEN
            old_data := jsonb_set(old_data, ARRAY[sensitive_col], '"[redacted]"'::jsonb);
        END IF;
        IF new_data ? sensitive_col THEN
            new_data := jsonb_set(new_data, ARRAY[sensitive_col], '"[redacted]"'::jsonb);
        END IF;
    END LOOP;

    INSERT INTO db_audit_log (table_name, row_id, operation, db_user, old_row, new_row, changed_columns)
    VALUES (TG_TABLE_NAME::text, row_ident, TG_OP, session_user::text, old_data, new_data, changed);

    -- AFTER triggers ignore their return value; RETURN NULL is the PL/pgSQL convention for one.
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_categories AFTER INSERT OR UPDATE OR DELETE ON categories FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER trg_audit_devices AFTER INSERT OR UPDATE OR DELETE ON devices FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER trg_audit_device_capabilities AFTER INSERT OR UPDATE OR DELETE ON device_capabilities FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER trg_audit_assets AFTER INSERT OR UPDATE OR DELETE ON assets FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER trg_audit_asset_devices AFTER INSERT OR UPDATE OR DELETE ON asset_devices FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER trg_audit_asset_usages AFTER INSERT OR UPDATE OR DELETE ON asset_usages FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER trg_audit_geofence_zones AFTER INSERT OR UPDATE OR DELETE ON geofence_zones FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER trg_audit_groups AFTER INSERT OR UPDATE OR DELETE ON groups FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER trg_audit_users AFTER INSERT OR UPDATE OR DELETE ON users FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER trg_audit_pilot_assignments AFTER INSERT OR UPDATE OR DELETE ON pilot_assignments FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER trg_audit_marks AFTER INSERT OR UPDATE OR DELETE ON marks FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER trg_audit_datasets AFTER INSERT OR UPDATE OR DELETE ON datasets FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER trg_audit_map_layers AFTER INSERT OR UPDATE OR DELETE ON map_layers FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER trg_audit_map_layer_grants AFTER INSERT OR UPDATE OR DELETE ON map_layer_grants FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER trg_audit_map_drawings AFTER INSERT OR UPDATE OR DELETE ON map_drawings FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER trg_audit_vehicle_profiles AFTER INSERT OR UPDATE OR DELETE ON vehicle_profiles FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER trg_audit_feature_requirements AFTER INSERT OR UPDATE OR DELETE ON feature_requirements FOR EACH ROW EXECUTE FUNCTION audit_row_change();
