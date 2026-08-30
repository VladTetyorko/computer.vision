-- docs/plans/active/CV-SETTINGS-PLAN.md §5.3, §3.2, §3.3 (CV-SETTINGS wave W3) -- the durable CV
-- model catalogue (fixes H4: two model rosters that need not agree) and training run history
-- (fixes H7: training metrics evaporate). No seed rows -- the config-seeded model roster is merged
-- with the worker's live ListModels response at the application layer (CvModelRepositoryPort's own
-- javadoc), not baked into this schema.
--
-- No FKs (storage/persistence MODULE.md's standing convention): dataset_id/training_run_id below
-- are plain UUID columns, not REFERENCES, same as every other cross-entity pointer in this schema.
CREATE TABLE cv_models (
    model_id                VARCHAR(120)      NOT NULL,
    version                 VARCHAR(60)       NOT NULL,
    display_name            VARCHAR(120)      NOT NULL,
    kind                    VARCHAR(60)       NOT NULL,
    open_vocab              BOOLEAN           NOT NULL DEFAULT FALSE,
    -- Ordered lists, read back whole -- same jsonb-list convention as datasets.classes.
    default_label_filter    JSONB             NOT NULL DEFAULT '[]'::jsonb,
    task_type                VARCHAR(16)      NOT NULL,
    runtime                  VARCHAR(16)      NOT NULL,
    classes                  JSONB            NOT NULL DEFAULT '[]'::jsonb,
    status                   VARCHAR(16)      NOT NULL,
    -- The whole ModelMetrics record (map50 + kind) as one nullable jsonb column -- CvModelRecord's
    -- own domain deviation 1: unlike provenance below, "no metrics reported yet" is a genuinely
    -- absent object, not five independently-nullable columns, so it is not decomposed.
    metrics                  JSONB,
    -- ModelProvenance decomposes into five flat columns rather than staying one nested jsonb
    -- object (CV-SETTINGS-CONTEXT.md's W4-domain handoff, "For W3 ... field-to-column mapping"):
    -- every field is independently nullable at the domain level, and a hand-registered model can
    -- have some but not all of them, which five plain columns represent exactly and a single jsonb
    -- blob would not add any real query capability over.
    dataset_id                UUID,
    training_run_id           UUID,
    base_model                VARCHAR(120),
    epochs                    INTEGER,
    trained_at                TIMESTAMPTZ,
    promoted_by               UUID,
    promoted_at                TIMESTAMPTZ,
    created_at                TIMESTAMPTZ      NOT NULL,
    PRIMARY KEY (model_id, version)
);

-- One row per training job (docs/plans/active/CV-SETTINGS-PLAN.md §3.3) -- written when a job
-- starts, updated in place as TrainingProgress messages arrive, left as a permanent record once
-- the job reaches a terminal JobState. epoch/total_epochs/loss/map50 default to 0 -- "before any
-- progress arrives", matching TrainingRunRecord's own javadoc and TrainingJobView's existing
-- precedent for the same fields.
CREATE TABLE cv_training_runs (
    run_id            UUID              PRIMARY KEY,
    dataset_id        UUID              NOT NULL,
    base_model        VARCHAR(120)      NOT NULL,
    epochs            INTEGER           NOT NULL,
    state             VARCHAR(16)       NOT NULL,
    epoch             INTEGER           NOT NULL DEFAULT 0,
    total_epochs      INTEGER           NOT NULL DEFAULT 0,
    loss              DOUBLE PRECISION  NOT NULL DEFAULT 0,
    map50             DOUBLE PRECISION  NOT NULL DEFAULT 0,
    output_model_id   VARCHAR(120),
    started_by        UUID              NOT NULL,
    started_at        TIMESTAMPTZ       NOT NULL,
    finished_at       TIMESTAMPTZ,
    message           VARCHAR(2000)     NOT NULL DEFAULT ''
);

-- findAll(limit) (TrainingRunRepositoryPort) reads newest-first by started_at.
CREATE INDEX idx_cv_training_runs_started_at ON cv_training_runs (started_at DESC);

-- CV-SETTINGS-PLAN.md §3.2/§8 OQ9 canManageOrg/canAdminister-gated catalogue governance (who
-- promoted/retired which model, which run produced it) -- the same "control-plane accountability"
-- character as datasets/control_profiles, so both tables join the audited set rather than the
-- excluded one (storage/persistence MODULE.md "Database change audit"). cv_training_runs updates
-- more often than most audited tables (once per TrainingProgress message, roughly once per epoch),
-- but its write volume is bounded by a training job's epoch count -- tens of rows over a run's
-- life, not the per-frame/per-sample character of the excluded set (telemetry_samples,
-- detection_results, detection_events) -- and H7 exists specifically so a promoted model's
-- provenance is answerable later, which is exactly what this audit trail is for.
CREATE TRIGGER trg_audit_cv_models AFTER INSERT OR UPDATE OR DELETE ON cv_models
    FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER trg_audit_cv_training_runs AFTER INSERT OR UPDATE OR DELETE ON cv_training_runs
    FOR EACH ROW EXECUTE FUNCTION audit_row_change();
