-- docs/CV-TRAINING-PLAN.md §1/§3, Wave T1/T3: the CV model-improvement loop's data pipeline --
-- datasets (a named accumulation of labeled frames), training_samples (one captured frame plus
-- its evolving annotations), sample_images (the frame's raw bytes, keyed by sample id -- the
-- asset_images.data BYTEA precedent applied to training frames, docs/CV-TRAINING-PLAN.md §C).
--
-- No FK between any of the three tables, or to assets/streams/users/groups -- same "no
-- cross-entity foreign keys / stay parity-compatible with the in-memory reference repos"
-- convention as every other table in this schema (see MODULE.md's Conventions).

-- id is the dataset's own DatasetId, not synthetic -- a dataset has real identity, same as
-- geofence_zones/marks. classes is jsonb (the whole ordered List<String>), same mechanism as
-- geofence_zones.polygon. ownership is flattened to owner_id/group_id, same choice assets/marks
-- make for Ownership.
CREATE TABLE datasets (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    target_category VARCHAR(255),
    classes         JSONB NOT NULL,
    owner_id        UUID NOT NULL,
    group_id        UUID NOT NULL,
    status          VARCHAR(16) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL
);

-- id is the sample's own TrainingSampleId, not synthetic -- a sample mutates over its own review
-- lifecycle (capture seeds it PENDING, labeling replaces its annotations and moves it to
-- LABELED/DISCARDED), the same "upsert not append" shape as detection_events, unlike
-- detection_results' immutable append-only rows. annotations is jsonb (the whole
-- List<Annotation>), same mechanism as detection_results.detections. asset_id/labeled_by/
-- labeled_at are nullable (asset unresolved at capture time, or not yet reviewed).
CREATE TABLE training_samples (
    id           UUID PRIMARY KEY,
    dataset_id   UUID NOT NULL,
    stream_id    UUID NOT NULL,
    asset_id     UUID,
    captured_at  TIMESTAMPTZ NOT NULL,
    width        INTEGER NOT NULL,
    height       INTEGER NOT NULL,
    annotations  JSONB NOT NULL,
    status       VARCHAR(16) NOT NULL,
    labeled_by   UUID,
    labeled_at   TIMESTAMPTZ
);

-- Serves TrainingSampleRepositoryPort#findByDataset/#countByDataset's (dataset_id, status)
-- filter -- a dataset's PENDING labeling queue and its per-status sampleCounts summary both hit
-- this index instead of scanning the whole table.
CREATE INDEX idx_training_samples_dataset_status ON training_samples (dataset_id, status);

-- Mirrors asset_images (V5__asset_images.sql) exactly: sample_id is the primary key rather than
-- a synthetic one, since there is at most one image per sample and a save is always an upsert.
CREATE TABLE sample_images (
    sample_id    UUID PRIMARY KEY,
    content_type VARCHAR(255) NOT NULL,
    data         BYTEA NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
