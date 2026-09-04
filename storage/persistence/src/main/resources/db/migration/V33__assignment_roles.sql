-- docs/plans/active/AUTH-ROLES-PLAN.md §3.4/D13, wave B3: seats on the pilot->asset assignment
-- roster, and the forced-password-change latch on a user account. Purely additive on top of
-- V1-V32 -- two new columns, nothing else changed.
--
-- pilot_assignments.role (AssignmentRepositoryPort#assign/#roleFor/#assignmentsForAsset,
-- AssignmentEntity#role): the seat ("PILOT" or "CREW", AssignmentRole#name()) an assignment link
-- grants -- CREW-CONTROL-PLAN.md's IC-2 answer. Defaulted to 'PILOT' at the column level, not just
-- in application code, so every row created before this column existed reads back as the seat it
-- always implicitly granted (the wide seat, subsuming the camera) with no backfill migration
-- needed. NOT NULL: every link has exactly one seat, never "assigned with an unknown role".
--
-- users.must_change_password (User#mustChangePassword, UserEntity#mustChangePassword): forces a
-- password change at next login after an admin sets/resets a password
-- (UserService#create/#setPassword). Defaulted to FALSE so every existing account -- created before
-- this column existed, including the dev-seed accounts -- is unaffected; only a password an admin
-- newly sets, from this wave onward, requires the change.
--
-- No trigger changes: V21__db_audit_log.sql's audit_row_change() takes every column of an audited
-- table via to_jsonb(NEW)/to_jsonb(OLD) with no fixed column list, so both new columns are captured
-- automatically. Neither is a secret (see that migration's redaction-list note) -- only
-- users.password_hash is ever redacted -- so no redaction-list change is needed either.
ALTER TABLE pilot_assignments ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'PILOT';

ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
