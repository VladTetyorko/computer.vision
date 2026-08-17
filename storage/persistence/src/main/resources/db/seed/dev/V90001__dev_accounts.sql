-- DEV-ONLY credentials -- password equals username for all three accounts:
-- admin/admin (ADMIN), manager/manager (MANAGER), pilot/pilot (PILOT).
-- ANY DATABASE CARRYING THESE ROWS IS UNSECURED. A real deployment must create users through
-- UserService (BCrypt-hashed via PasswordHasherPort) and never rely on these -- this preserves, word
-- for word in spirit, the warning the deleted AuthSeedRunner used to carry as a javadoc
-- (docs/plans/active/POSTGRES-ONLY-CONTEXT.md W1).
--
-- This migration only ever runs when vision.persistence.seed-dev-users=true
-- (VisionPersistenceProperties / PersistenceUnit#start) -- docker-compose.yml sets it for the
-- friends-demo stack; nothing else should. It lives in a SEPARATE Flyway location
-- (classpath:db/seed/dev, not classpath:db/migration) precisely so it is not part of every
-- database's baseline history: PersistenceUnit#start only adds this location to Flyway's
-- `locations` when the flag is on, so flag-off databases never see this file at all, and flipping
-- the flag off again after it ran leaves these rows in place (this migration is additive, nothing
-- ever un-seeds them) while Flyway is told to ignore the now-unresolvable history entry for this
-- location via `ignoreMigrationPatterns("*:future", "*:missing")` -- see PersistenceUnit's own
-- javadoc for why the pattern that actually fires is "*:future", not "*:missing": once this
-- location drops out, Flyway compares this migration's orphaned version against the highest version
-- classpath:db/migration can still resolve, and (per the reserved-high-band choice explained below)
-- 90001 always sorts above it, which Flyway classifies as FUTURE_SUCCESS. Proven against a real
-- Postgres by DevAccountSeedMigrationTest -- a first-reading guess of "*:missing" alone compiled and
-- looked reasonable but silently matched nothing until measured against a live database.
--
-- Version 90001, deliberately far out of band: the two locations are combined into one
-- version-ordered history by Flyway, so this must both (a) never collide with db/migration's own
-- sequential numbering and (b) stay ahead of it forever, not just today. A "next free slot" scheme
-- (e.g. V13.1, sorting right after V13__identity_baseline.sql) was tried first and measured to
-- fail: by the time an operator actually flips vision.persistence.seed-dev-users on, db/migration
-- has usually moved on to V14/V15/... (docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3 landed
-- alongside this wave), so applying this migration afterward means resolving a *lower*-versioned
-- migration than the highest already-applied one -- Flyway refuses that by default ("Detected
-- resolved migration not applied to database ... set -outOfOrder=true"), and outOfOrder=true is a
-- global setting that would also let a genuinely-misordered db/migration change slip through
-- silently. A reserved high band sidesteps the whole problem: this migration's version will always
-- be the highest resolved one, so it always applies next regardless of how far db/migration has
-- moved, with no out-of-order behavior needed anywhere. Proven against a real Postgres, not assumed
-- -- see DevAccountSeedMigrationTest's false-then-true scenario.
--
-- Password hashes below are real BCrypt output from the app's own BcryptPasswordHasher
-- (BCryptPasswordEncoder, default strength) -- generated once, pasted in literally, not invented; each
-- is asserted to verify against its plaintext in DevAccountSeedMigrationTest, so a hash that silently
-- doesn't match cannot hide here. All three join the fixed root group from V13__identity_baseline.sql
-- (00000000-0000-0000-0000-000000000001). admin reuses DevPrincipal.USER_ID (UUID(0,0)) for the same
-- "asset owner id == seeded admin id" continuity reason V13 pinned the root group's id. manager/pilot
-- get equally fixed, readable ids -- UUID(0,2)/UUID(0,3) -- chosen only for readability; a primary
-- key is scoped to its own table, so these do not collide with any other table's use of a
-- similar-looking id (e.g. map_layers' own UUID(0,2) COP layer from V12__map_layers.sql).
--
-- memberships is a single-element jsonb array shaped {"groupId":{"value":"<uuid>"},"role":"<ROLE>"}
-- -- exactly what Hibernate's Jackson-backed FormatMapper produces for a List<Membership> (GroupId is
-- itself a one-component record, so it nests as {"value":...} rather than a bare string) and what
-- JpaUserRepository/UserMapper read back on the other side; DevAccountSeedMigrationTest round-trips
-- this literal through JpaUserRepository to prove the shape, rather than trusting it by inspection.
--
-- ON CONFLICT (id) DO NOTHING, same idiom as V2__seed_categories.sql/V13 -- idempotent re-apply.
--
-- POSTGRES-ONLY-CONTEXT.md upgrade-path fix, defect 1: the ON CONFLICT (id) DO NOTHING above is
-- only half a guard. users.username carries its OWN UNIQUE constraint (V8__users_groups.sql), and
-- a conflict on that index is not covered by an ON CONFLICT (id) target -- Postgres has exactly one
-- conflict target per INSERT, and id is it. Any database that already ran the now-deleted
-- AuthSeedRunner (every pre-W1 docker-compose.yml deployment: VISION_PERSISTENCE_ENABLED=true +
-- VISION_AUTH_ENABLED=true against a persistent volume) has admin/manager/pilot rows at RANDOM ids
-- already. Against such a database this migration's INSERT used to hit "duplicate key value
-- violates unique constraint users_username_key" and abort -- Flyway fails the whole migrate() call,
-- PersistenceUnit.start() never returns, and the application does not boot. Reproduced directly
-- against a real postgres:16 with V8's exact DDL and one pre-existing admin row at a random id
-- before this fix landed.
--
-- Fixed by replacing the single multi-row INSERT ... VALUES ... ON CONFLICT (id) with three
-- INSERT ... SELECT ... WHERE NOT EXISTS statements, one per account -- ON CONFLICT accepts only
-- one target and username has no separate ON CONFLICT-able clause here, so a NOT EXISTS guard that
-- checks BOTH id and username is the shape that actually skips either kind of collision. Each
-- statement is still a no-op re-apply once its account exists (id already taken -> NOT EXISTS is
-- false), and still skips cleanly when only the id differs but the username is already someone
-- else's account (the upgrade case) -- proven against a real Postgres by
-- DevAccountSeedMigrationTest's upgrade scenario, not just reasoned about. Ids, hashes, and
-- memberships jsonb below are unchanged from the original INSERT.

INSERT INTO users (id, username, display_name, email, password_hash, enabled, memberships)
SELECT '00000000-0000-0000-0000-000000000000', 'admin', 'Administrator', 'admin@vision.local',
       '$2a$10$MoD/vL7PH.KklHCeogwzFeJ174OKwNaEaVlQ17qa2ohjXItcBfzqa', true,
       '[{"groupId":{"value":"00000000-0000-0000-0000-000000000001"},"role":"ADMIN"}]'::jsonb
WHERE NOT EXISTS (
    SELECT 1 FROM users
    WHERE id = '00000000-0000-0000-0000-000000000000' OR username = 'admin'
);

INSERT INTO users (id, username, display_name, email, password_hash, enabled, memberships)
SELECT '00000000-0000-0000-0000-000000000002', 'manager', 'Manager', 'manager@vision.local',
       '$2a$10$uBakOli./vaE/.Yd43ftAuOGtNaHG3UjKtQL3xuzYu71kMVkBHb/m', true,
       '[{"groupId":{"value":"00000000-0000-0000-0000-000000000001"},"role":"MANAGER"}]'::jsonb
WHERE NOT EXISTS (
    SELECT 1 FROM users
    WHERE id = '00000000-0000-0000-0000-000000000002' OR username = 'manager'
);

INSERT INTO users (id, username, display_name, email, password_hash, enabled, memberships)
SELECT '00000000-0000-0000-0000-000000000003', 'pilot', 'Pilot', 'pilot@vision.local',
       '$2a$10$Pw27YE4P5C5FphYEZOrDMOdwX8DAVwX4V4FBb1GQWX9V34ilR6iQi', true,
       '[{"groupId":{"value":"00000000-0000-0000-0000-000000000001"},"role":"PILOT"}]'::jsonb
WHERE NOT EXISTS (
    SELECT 1 FROM users
    WHERE id = '00000000-0000-0000-0000-000000000003' OR username = 'pilot'
);
