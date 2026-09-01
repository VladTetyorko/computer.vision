-- How the owner's transmitter is arranged (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md wave C15).
--
-- Neither column changes a microsecond on the wire: they say which hand holds which stick and which
-- way a vertical axis reads, so the platform can draw the operator's radio the way it actually is.
-- They lived in one browser's local storage, which meant setting a layout up on a laptop and flying
-- it from the ground-station box answered the same question twice.
--
-- Defaults are the arrangement most operators fly (mode 2, forward reads positive), so every row
-- saved before this migration keeps drawing exactly as it did.
--
-- IF NOT EXISTS / DROP-then-ADD because this script shipped as V25 on the pre-merge
-- feat/controller-setup branch (renumbered to V32 in the 34e298d0 reconciliation): a dev database
-- that ran that branch already carries both columns and the check, with no matching history row.
ALTER TABLE control_profiles
    ADD COLUMN IF NOT EXISTS stick_mode SMALLINT NOT NULL DEFAULT 2;
ALTER TABLE control_profiles
    ADD COLUMN IF NOT EXISTS forward_is_up BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE control_profiles
    DROP CONSTRAINT IF EXISTS ck_control_profiles_stick_mode;
ALTER TABLE control_profiles
    ADD CONSTRAINT ck_control_profiles_stick_mode CHECK (stick_mode BETWEEN 1 AND 4);
