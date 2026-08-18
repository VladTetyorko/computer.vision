-- POSTGRES-ONLY-CONTEXT.md upgrade-path fix, defect 2: V13__identity_baseline.sql pins the root
-- group at the fixed id 00000000-0000-0000-0000-000000000001 (DevPrincipal.GROUP_ID), but on a
-- database that already ran the now-deleted AuthSeedRunner, V13's ON CONFLICT (id) DO NOTHING only
-- adds the fixed group ALONGSIDE the pre-existing random-id "Root" group -- it does not merge them.
-- Both are parentless. A pre-existing MANAGER's membership still points at the OLD random root, and
-- DefaultScopeResolver builds their VisibilityScope as the subtree of THAT group -- which the fixed
-- group (00000000-0000-0000-0000-000000000001), the group every DevPrincipal-owned asset is stamped
-- with, is not a member of. So an operator upgrades, the migration succeeds, and the manager still
-- gets an empty fleet: V13 fixed the id going forward but did nothing for a database that already
-- diverged.
--
-- Fix: adopt the fixed group AS A CHILD of the pre-existing root, when there is exactly one. That
-- puts 00000000-0000-0000-0000-000000000001 inside the existing root's subtree, which is exactly
-- the set DefaultScopeResolver walks for a manager whose membership points at that existing root --
-- so the manager's VisibilityScope.GROUPS now covers it, and DevPrincipal-owned assets become
-- visible again. This is verified end-to-end (not just by reading the id) by
-- UpgradePathMigrationTest's upgrade scenario, which drives DefaultScopeResolver's own
-- subtree walk against a real Postgres.
--
-- Direction matters and is NOT interchangeable with the reverse. Reparenting the OLD root under the
-- FIXED group would leave the manager's membership (still pointing at the old root) scoped to a
-- subtree that does not include the fixed group's own id -- the old root is not a descendant of
-- itself, and the manager's scope walk starts from the old root, not from the fixed group. Only
-- adopting the fixed group *under* the existing root puts the fixed group inside the tree the
-- manager's existing membership already reaches. So this migration reparents the FIXED group, never
-- the pre-existing one.
--
-- Renamed from "Root" to "Dev-Mode Assets" when adopted: once nested under a real org root, a child
-- node still called "Root" reads as a bug in any org chart. The new name says what the group
-- actually holds after adoption -- every asset/mark/layer DevPrincipal stamped while
-- vision.auth.enabled=false, i.e. everything created before this database had real identity -- which
-- is an honest, stable label regardless of how deep it ends up nested.
--
-- Three cases, decided by counting OTHER parentless groups (parent_id IS NULL AND id <> the fixed
-- group's own id):
--   0  -- fresh install (V13 just created the only root there is). NO-OP: the fixed group stays the
--        root, keeps V13's "Root" name. Required, not incidental -- every fresh install must come out
--        of this migration unchanged, or plain V13 installs would get a mangled name for no reason.
--   1  -- exactly the upgrade case this migration exists for. Adopt: reparent + rename as above.
--   2+ -- ambiguous. More than one candidate "existing root" and no principled way for a migration to
--        pick between them. NO-OP, flagged here rather than guessed at: an operator with more than one
--        pre-existing parentless group needs to reconcile their org chart by hand (which root should
--        own the dev-principal group, or whether the two roots should merge at all is a judgment call
--        no migration should make silently).
--
-- Non-destructive by construction: this migration only ever UPDATEs groups.parent_id/groups.name for
-- the single row whose id is the fixed group's id. It touches no users row, no memberships jsonb, no
-- asset/mark/layer ownership column anywhere, and deletes nothing -- every pre-existing group, user,
-- and asset stays exactly as it was, byte for byte, except that one row's parent_id and name.

DO $$
DECLARE
    fixed_root_id CONSTANT uuid := '00000000-0000-0000-0000-000000000001';
    other_root_count integer;
    other_root_id uuid;
BEGIN
    SELECT count(*) INTO other_root_count
    FROM groups
    WHERE parent_id IS NULL AND id <> fixed_root_id;

    IF other_root_count = 1 THEN
        -- uuid has no default aggregate ordering, so the "which one" lookup is a plain SELECT
        -- (guaranteed exactly one row by the count check above), not max(id)/min(id) -- Postgres
        -- has no built-in max/min aggregate for uuid, confirmed against a real instance
        -- ("function max(uuid) does not exist") rather than assumed.
        SELECT id INTO other_root_id
        FROM groups
        WHERE parent_id IS NULL AND id <> fixed_root_id;

        UPDATE groups
        SET parent_id = other_root_id,
            name = 'Dev-Mode Assets'
        WHERE id = fixed_root_id;
    END IF;
    -- other_root_count = 0 (fresh install) or >= 2 (ambiguous, needs manual attention): no-op.
END $$;
