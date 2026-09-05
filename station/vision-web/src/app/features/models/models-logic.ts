import type { CvModel, CvModelMetrics, CvModelProvenance, ScopeKind } from '../../core/api/models';
import { canAdminister } from '../../core/auth/auth-logic';

/**
 * Pure logic behind `ModelsPage` (`/manage/training/models`) — the CV model registry table: which
 * model is live, whether "Promote"/"Roll back" are actionable right now, how the wire's raw status/
 * runtime/availability/metrics/provenance fields read as plain language, and the list's display
 * order. Split out per this app's own convention of keeping component/facade logic thin and
 * unit-testing the framework-free parts directly.
 *
 * **Rewritten wave W8** (docs/plans/active/CV-SETTINGS-PLAN.md §6 row W8) — this file used to operate
 * on the now-deleted `RegisteredModel` (`{id, version, active}, GET /api/cv/registry/models`); every
 * function here now reads the widened `CvModel` (`GET /api/cv/models`, `CvModelResponse` joined
 * registry+config roster) instead, keyed by `status` rather than a bare `active` boolean.
 */

/** Display/sort priority per status — `LIVE` first (the one fact a manager scanning the page wants
 *  to confirm), then the states a model can still move through, `RETIRED` last. A roster row with no
 *  `status` at all (never happens on a real response — every `CvModelRecord` carries one — but the
 *  wire field is optional per `CvModel`'s own doc comment) reads as the lowest priority, alongside
 *  `RETIRED`, rather than being silently dropped from the sort. */
const STATUS_RANK: Readonly<Record<string, number>> = {
  LIVE: 0,
  CANDIDATE: 1,
  DRAFT: 2,
  RETIRED: 3,
};
const UNKNOWN_STATUS_RANK = 4;

/** Composite identity for a registry row — `(modelId, version)` is the real primary key
 *  (`cv_models`'s own `PRIMARY KEY(model_id, version)`, docs/plans/active/CV-SETTINGS-PLAN.md §5.3), so
 *  two rows can legitimately share an `id` (e.g. one `LIVE`, one `RETIRED` version of the same
 *  checkpoint family). Used for `@for` tracking and for "which row is this promotion in flight for". */
export function modelKey(model: Pick<CvModel, 'id' | 'version'>): string {
  return `${model.id}@${model.version ?? ''}`;
}

/**
 * May this session reach the registry's mutation surface (Promote/Roll back)? **`UNBOUNDED` scope
 * only** — `ModelRegistryController`'s own javadoc: both operations require
 * `VisibilityScope#canAdminister()`, which is strictly narrower than `canManageOrg()`
 * (`core/org/org-logic.ts`) — a MANAGER's `GROUPS` scope administers their own subtree, not "swap
 * the model every stream in the deployment uses" (`VisibilityScope#canAdminister`'s own javadoc). A
 * thin wrapper over `core/auth/auth-logic.ts#canAdminister` (docs/plans/active/AUTH-ROLES-PLAN.md
 * §3.2, wave W2 — moved off `topRole === 'ADMIN'` for the same reason `canManageOrg` moved off
 * `topRole` comparisons: it was never a reliable stand-in once `VIEWER` existed). **Dev parity**:
 * `vision.auth.enabled=false`'s dev principal resolves to `scopeKind: 'UNBOUNDED'`, so this reads
 * `true` exactly as it does for a real admin.
 */
export function canAdministerRegistry(scopeKind: ScopeKind | null | undefined): boolean {
  return canAdminister(scopeKind);
}

/**
 * The "Promote" button's enabled predicate for one row: `false` for a non-administrator, `false`
 * for the already-`LIVE` row (nothing to promote it to), and `false` while *any* row's own
 * promotion is in flight (`promotingKey`, `modelKey`-shaped) — one promotion at a time app-wide, so
 * a second click can't race the first. A `status`-less row (never real, see this file's own
 * `STATUS_RANK` doc comment) is treated as promotable, the safe default.
 */
export function canPromoteModel(
  model: Pick<CvModel, 'status'>,
  canAdminister: boolean,
  promotingKey: string | null,
): boolean {
  return canAdminister && model.status !== 'LIVE' && promotingKey === null;
}

/**
 * The `version` sent with `POST /api/cv/registry/models/{id}/promote`.
 *
 * **A real backend quirk, worked around deliberately, not silently** — a config-fallback roster row
 * (or a worker-registry row cv-service tracks no per-model version data for yet) reports `version`
 * as absent or `""`. But `ModelRegistryController` builds a domain `ModelRef(id, version)` server-side,
 * and `ModelRef`'s compact constructor rejects a **blank** version with a `400` — so echoing a blank
 * `version` straight back would 400 unconditionally. `'latest'` is the same sentinel
 * `PipelineConfig.defaults()`'s own model reference already uses elsewhere in this app. A model that
 * *does* carry a real, non-blank version is sent verbatim, trimmed.
 */
export function resolvePromoteVersion(model: Pick<CvModel, 'version'>): string {
  const trimmed = (model.version ?? '').trim();
  return trimmed.length > 0 ? trimmed : 'latest';
}

/**
 * Display order for the registry table: `LIVE` first, then the states a model can still move
 * through, `RETIRED` last (`STATUS_RANK`); ties break by id, then by version, so the order stays
 * stable regardless of whatever order the backend's own roster scan happens to return rows in.
 * Never mutates its input.
 */
export function sortModelsForDisplay(models: readonly CvModel[]): readonly CvModel[] {
  return [...models].sort((a, b) => {
    const rankA = STATUS_RANK[a.status ?? ''] ?? UNKNOWN_STATUS_RANK;
    const rankB = STATUS_RANK[b.status ?? ''] ?? UNKNOWN_STATUS_RANK;
    if (rankA !== rankB) {
      return rankA - rankB;
    }
    const byId = a.id.localeCompare(b.id);
    return byId !== 0 ? byId : (a.version ?? '').localeCompare(b.version ?? '');
  });
}

/** One human word for the status chip. `undefined` (never real, see `STATUS_RANK`'s doc comment) reads as "Unknown" rather than blank. */
export function statusLabel(status: CvModel['status']): string {
  switch (status) {
    case 'LIVE':
      return 'Live';
    case 'CANDIDATE':
      return 'Candidate';
    case 'DRAFT':
      return 'Draft';
    case 'RETIRED':
      return 'Retired';
    default:
      return 'Unknown';
  }
}

/** The status chip's colour variant (`.chip.ok`/`.chip.accent`/plain `.chip`) — `LIVE` is the one
 *  healthy/confirmed state (green); `CANDIDATE` is "worth a look" (blue/accent, matching this app's
 *  one accent hue, never a second meaning for it); `DRAFT`/`RETIRED` are both plainly neutral —
 *  neither is a problem, so neither gets `.chip.warn`/`.chip.danger` (frontend-style §3: status
 *  colour means state, and "retired"/"not yet promoted" are not failures). */
export function statusChipVariant(status: CvModel['status']): 'ok' | 'accent' | '' {
  switch (status) {
    case 'LIVE':
      return 'ok';
    case 'CANDIDATE':
      return 'accent';
    default:
      return '';
  }
}

/** One human word for the runtime — the honest speed signal (§3.2). `undefined`/an unrecognized
 *  wire value both read as `'—'`, never a guessed engine. */
export function runtimeLabel(runtime: CvModel['runtime']): string {
  switch (runtime) {
    case 'PYTORCH':
      return 'PyTorch';
    case 'OPENVINO':
      return 'OpenVINO';
    case 'ONNX':
      return 'ONNX';
    case 'TENSORRT':
      return 'TensorRT';
    default:
      return '—';
  }
}

/** §3.5 honesty rule 4 — "a model the worker does not have reads Missing on worker, never a silent
 *  fallback." Drives the row's explicit warning text; deliberately not folded into the status chip
 *  (frontend-style §5 "one chip per row max" — status is the row's one chip, availability is plain
 *  warning-toned text alongside it, the same secondary-line idiom `devices.html`'s endpoint-conflict
 *  note already uses). */
export function isMissingOnWorker(model: Pick<CvModel, 'availability'>): boolean {
  return model.availability === 'MISSING';
}

/** The metrics column's structural label — states which kind of number it is (§3.5 rule 5: "training
 *  mAP is not called an evaluation"). `'HELDOUT'` has no real producer yet (docs/plans/active/CV-SETTINGS-CONTEXT.md's
 *  W4-app handoff) but is labelled honestly if it ever appears; absent metrics render no label at
 *  all (the template gates this behind the metrics object's own presence). */
export function metricsKindLabel(kind: CvModelMetrics['kind'] | undefined): string {
  switch (kind) {
    case 'TRAINING':
      return 'Training mAP50';
    case 'HELDOUT':
      return 'Held-out mAP50';
    default:
      return 'mAP50 (unlabelled)';
  }
}

/** Fixed 3-decimal reading for `map50`, mirroring `training-job-logic.ts#formatMetric`'s own
 *  register — `'—'` for a missing/unreported figure, never a fabricated `0.000`. */
export function formatMap50(value: number | null | undefined): string {
  return typeof value === 'number' ? value.toFixed(3) : '—';
}

/** `true` once at least one provenance field actually names something (a config-seeded row with no
 *  training run behind it reports every field `null`, per `CvModelProvenance`'s own doc comment —
 *  reads as "nothing to report" here, not a fabricated dash-per-field row). */
export function hasProvenance(provenance: CvModelProvenance | undefined): boolean {
  return !!provenance && (provenance.datasetId !== null || provenance.trainingRunId !== null);
}

/** Which of `ModelRegistryService#models()`'s two paths produced this roster — `'registry'` if any
 *  row is tagged so, `'config'` otherwise (every row in one response shares the same source, per
 *  `CvModelResponse.from`'s own uniform mapping — checking "any" is equivalent to checking "all" and
 *  degrades safely for an empty list). Drives the page's own "registry unreachable" banner. */
export function catalogSource(models: readonly CvModel[]): 'config' | 'registry' {
  return models.some((model) => model.source === 'registry') ? 'registry' : 'config';
}

/** The row currently `LIVE`, if any — the model a rollback would retire. */
export function findLiveModel(models: readonly CvModel[]): CvModel | undefined {
  return models.find((model) => model.status === 'LIVE');
}

/**
 * The rollback confirm dialog's own message — names what the operation retires (the current live
 * model, which this app *does* know) rather than what it restores (which it genuinely cannot know
 * ahead of time: the frozen wire's `CvModel` carries no `promotedAt`/ordering field a client could
 * use to predict which `RETIRED` row the server will pick — only the live call itself resolves
 * that, server-side, off `CvModelRecord#promotedAt`). Honest about that limit rather than guessing.
 */
export function rollbackConfirmMessage(liveModel: CvModel | undefined): string {
  if (!liveModel) {
    return 'Roll back the live model? This restores whichever model was most recently promoted over.';
  }
  const version = liveModel.version && liveModel.version.trim().length > 0 ? ` (${liveModel.version})` : '';
  return (
    `Roll back the live model? "${liveModel.id}"${version} will be retired, and whichever model it ` +
    'most recently replaced will become live again.'
  );
}
