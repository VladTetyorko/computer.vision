-- How the owner's transmitter is arranged (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md wave C15).
--
-- Neither column changes a microsecond on the wire: they say which hand holds which stick and which
-- way a vertical axis reads, so the platform can draw the operator's radio the way it actually is.
-- They lived in one browser's local storage, which meant setting a layout up on a laptop and flying
-- it from the ground-station box answered the same question twice.
--
-- Defaults are the arrangement most operators fly (mode 2, forward reads positive), so every row
-- saved before this migration keeps drawing exactly as it did.
ALTER TABLE control_profiles
    ADD COLUMN stick_mode SMALLINT NOT NULL DEFAULT 2,
    ADD COLUMN forward_is_up BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE control_profiles
    ADD CONSTRAINT ck_control_profiles_stick_mode CHECK (stick_mode BETWEEN 1 AND 4);
