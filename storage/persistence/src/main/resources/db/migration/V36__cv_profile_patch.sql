-- docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7, decision E22, wave W7.2 -- "a profile is a
-- patch": W7.0/W7.1 (contexts/vision-perception) already made CvProfile/CvProfileResolver treat
-- every knob but built-ins as nullable = "inherit from the tier below", and made CvProfile's own
-- Intent a persisted field instead of one resolved once at request time and discarded. This
-- migration is the storage half: every column that domain rewrite widened to nullable must be able
-- to actually store NULL, and a new column must exist to hold the persisted intent.
--
-- Existing rows untouched: every column this migration relaxes already holds a non-null value for
-- all four built-ins (V29__cv_profiles.sql) and for any operator-created profile since -- before
-- this wave every CvProfile fully specified every knob, so there is nothing to backfill or reshape.
-- A NULL knob only appears once a future PATCH through the new station/vision-api contract (wave
-- W7.3) actually leaves one unset.

ALTER TABLE cv_profiles
    ALTER COLUMN model_id             DROP NOT NULL,
    ALTER COLUMN model_version        DROP NOT NULL,
    ALTER COLUMN confidence_threshold DROP NOT NULL,
    ALTER COLUMN inference_fps        DROP NOT NULL,
    ALTER COLUMN label_filter         DROP NOT NULL,
    ALTER COLUMN label_deny_filter    DROP NOT NULL,
    ALTER COLUMN detection_enabled    DROP NOT NULL,
    ALTER COLUMN tracking             DROP NOT NULL,
    ALTER COLUMN event_rule           DROP NOT NULL;

-- CvProfile#model is one unit (ModelRef(id, version)) -- a row must set both columns or neither,
-- the same "both present or both absent" invariant ModelRef's own compact constructor would enforce
-- if this table stored a single Java value instead of two columns (the migration's own W1-era
-- header on this table already explains why it is two columns, not one). Enforced here as a
-- database CHECK constraint, not inside CvProfileEntity's Java constructor: Hibernate hydrates a row
-- into an entity by field reflection, bypassing that constructor entirely on every read, so a
-- Java-side check there could only ever catch a row this build itself just wrote through
-- CvProfileMapper -- never a row written any other way. A CHECK constraint is this schema's already
-- -established way to enforce a same-row, no-lookup-needed invariant at the one place every writer
-- must pass through (ck_control_profiles_stick_mode, V32__control_profile_transmitter_view.sql;
-- the operation enum-in-a-CHECK in V21__db_audit_log.sql).
ALTER TABLE cv_profiles
    ADD CONSTRAINT ck_cv_profiles_model_pair CHECK ((model_id IS NULL) = (model_version IS NULL));

-- CvProfile#intent (contexts/vision-perception's domain.model.Intent -- PEOPLE | VEHICLES |
-- EVERYTHING | CUSTOM), persisted with the profile for the first time -- decision E22's other half:
-- an intent must survive a reload, not vanish the moment the save-time response that reported it is
-- gone. NULL = no intent picked, the same meaning CvProfile#intent() already gives a null value.
-- Stored as its enum name in plain TEXT and parsed back with Intent#valueOf, not a Postgres ENUM
-- type or a CHECK-constrained VARCHAR -- this schema's own established convention for an
-- enum-shaped column already in use one table up in this very file (cv_profile_bindings.scope_kind,
-- parsed back via BindingScope#valueOf in CvProfileMapper, no CHECK constraint of its own either).
ALTER TABLE cv_profiles
    ADD COLUMN intent TEXT NULL;
