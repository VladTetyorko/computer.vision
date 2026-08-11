-- docs/plans/done/U-AUTH-PLAN.md wave 3: identity (users + org-chart groups). Purely additive on top of
-- V1-V7 -- two brand-new tables, nothing else changed.
--
-- No cross-entity foreign keys (not groups.parent_id, not any users->groups link), same convention
-- as every other table in this schema (see MODULE.md's Conventions): the in-memory reference
-- repositories perform zero referential checks, and a real constraint here would break parity for
-- the "round-trip every port method the same way the in-memory impl does" contract this module is
-- judged against. Memberships ride on the user row as jsonb (the whole List<Membership>, each a
-- {groupId, role}) -- Jackson serializes the record tree natively, same mechanism as
-- detection_results.detections / geofence_zones.polygon; they are only ever read back whole with
-- the User aggregate, so a normalized join table would add schema without adding any query the
-- ports need.

CREATE TABLE groups (
    id        UUID PRIMARY KEY,
    name      VARCHAR(255) NOT NULL,
    parent_id UUID
);

-- username is stored already-lower-cased by the domain (User normalizes it), so a plain UNIQUE
-- constraint gives the case-insensitive uniqueness findByUsername relies on -- the repositories
-- lower-case the lookup key before matching.
CREATE TABLE users (
    id            UUID PRIMARY KEY,
    username      VARCHAR(255) NOT NULL UNIQUE,
    display_name  VARCHAR(255) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    enabled       BOOLEAN NOT NULL,
    memberships   JSONB NOT NULL
);
