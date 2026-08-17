-- The root group now lives at a fixed id -- the same UUID(0,1) DevPrincipal (vision-app devsupport)
-- already stamps onto every asset created while vision.auth.enabled=false, the compiled default.
-- Before this migration the root group only ever existed via AuthSeedRunner minting
-- GroupId.random() at boot (docs/plans/active/POSTGRES-ONLY-CONTEXT.md §2.1), so a MANAGER whose
-- VisibilityScope.GROUPS covered that random id's subtree matched none of the assets DevPrincipal
-- owns -- an empty fleet the moment auth was turned on. ADMIN never noticed, since an unbounded
-- scope short-circuits the subtree test. Pinning the id here makes "the group dev-mode assets are
-- owned by" and "the group a manager is scoped to" the same value, by construction -- fixing the bug
-- without touching DevPrincipal itself.
--
-- ON CONFLICT (id) DO NOTHING, same idiom as V2__seed_categories.sql: safe to apply to a database
-- that already holds a randomly-seeded root group from an earlier boot -- this adds the fixed-id row
-- alongside it, it does not delete or rewrite anything. (A database left with two "Root" groups from
-- before this fix is a one-time cleanup an operator does by hand; this migration's job is only to
-- stop the mismatch from recurring.)

INSERT INTO groups (id, name, parent_id) VALUES
    ('00000000-0000-0000-0000-000000000001', 'Root', NULL)
ON CONFLICT (id) DO NOTHING;
