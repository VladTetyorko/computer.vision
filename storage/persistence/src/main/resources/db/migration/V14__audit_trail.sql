-- docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3: the audit trail, durable. AuditTrailPort was
-- wired unconditionally to InMemoryAuditTrail with no Postgres option at all -- this is the first
-- migration that gives it one.
--
-- Entries are immutable historical facts (never updated, never deleted -- see AuditTrailPort's own
-- javadoc), so id is the domain's own AuditId rather than a synthetic one: every entry has real
-- identity, and nothing ever needs to merge/upsert a row here.
--
-- details is jsonb (the whole free-form Map<String,String>), same mechanism as every other
-- "read back whole, never queried into by individual key" column in this schema (see
-- categories.attribute_hints).
--
-- No FK to users or to any target table (assets/devices/datasets/models) -- same "no cross-entity
-- foreign keys" convention as the rest of this schema (see MODULE.md's Conventions), and here it
-- is more than convention: an entry must stay resolvable even after its actor's account or its
-- target row is gone, so a referential constraint would be actively wrong, not merely
-- inconsistent with the in-memory reference repository.
--
-- Three read access paths, three indexes: findRecent (occurred_at alone), findByTarget
-- (target_type, target_id, occurred_at), findByActor (actor_id, occurred_at) -- each a composite
-- index with occurred_at trailing so the newest-first ORDER BY can use it directly.
CREATE TABLE audit_entries (
    id          UUID        PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    actor_id    UUID        NOT NULL,
    action      VARCHAR(16) NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    target_id   VARCHAR(255) NOT NULL,
    summary     TEXT        NOT NULL,
    details     JSONB       NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_audit_entries_occurred_at ON audit_entries (occurred_at);
CREATE INDEX idx_audit_entries_target ON audit_entries (target_type, target_id, occurred_at);
CREATE INDEX idx_audit_entries_actor ON audit_entries (actor_id, occurred_at);
