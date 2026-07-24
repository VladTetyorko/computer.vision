-- docs/UX-REWORK-PLAN.md §U-d item 3: one stored image per asset, the onboarding wizard's
-- "Profile" step photo (CONTRACT 2 -- PUT/GET/DELETE /api/assets/{id}/image).
--
-- asset_id is the primary key rather than a synthetic one, since there is at most one image per
-- asset and a PUT is always an upsert -- mirrors AssetImageRepositoryPort's own "save replaces
-- whatever was stored" contract directly in the schema, needing no separate unique constraint.
-- No FK to assets.id, same "no referential integrity between repositories" convention every other
-- table in this schema already follows (see MODULE.md's Conventions) -- an image attached to an
-- id nothing else knows about is harmless, it simply never surfaces.
CREATE TABLE asset_images (
    asset_id     UUID PRIMARY KEY,
    content_type VARCHAR(255) NOT NULL,
    data         BYTEA NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
