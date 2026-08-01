import type { RegisteredModel } from '../../core/api/models';

/**
 * Pure logic behind `ModelsPage` (`/manage/training/models`, docs/CV-TRAINING-PLAN.md Phase 2 T10)
 * — which model is live, whether "Promote" is actionable right now, the version string a promote
 * request actually sends, and the list's display order. Split out per this app's own convention of
 * keeping component/facade logic thin and unit-testing the framework-free parts directly.
 */

/**
 * The "Promote" button's enabled predicate for one row: `false` for a non-manager (mirroring the
 * backend's own 403 — `ModelsPage`/`ModelsFacade`'s own doc comments hide the button entirely for
 * that case, this predicate only covers the remaining "button is visible" cases), `false` for the
 * already-active model (nothing to promote it to), and `false` while *any* row's own promotion is
 * in flight (`promotingId`) — one promotion at a time app-wide, so a second click can't race the
 * first.
 */
export function canPromoteModel(
  model: Pick<RegisteredModel, 'active'>,
  canManage: boolean,
  promotingId: string | null,
): boolean {
  return canManage && !model.active && promotingId === null;
}

/**
 * The `version` sent with `POST /api/cv/registry/models/{id}/promote` (docs/CV-TRAINING-PLAN.md
 * §8's frozen `PromoteModelRequest`).
 *
 * **A real backend quirk, worked around deliberately, not silently** — `GET
 * /api/cv/registry/models` reports every model's `version` as `""` today: cv-service's own
 * `ListModels` RPC tracks no per-model version data yet (`cv_service/server.py#ListModels`'s own
 * doc comment, "the registry tracks no per-model version today"). But `ModelRegistryController`
 * (vision-api) builds a domain `ModelRef(id, version)` from the promote request server-side, and
 * `ModelRef`'s compact constructor rejects a **blank** version with an `IllegalArgumentException`
 * → 400 (`vision-domain/.../ModelRef.java`) — so echoing the registry's own blank `version`
 * straight back on every promote call would 400 unconditionally, for every model, every time.
 * cv-service's own `PromoteModel` handler never actually validates this string (it's only persisted
 * as the restart-survival marker, `training.py#write_active_model`), so substituting a real,
 * already-established sentinel is safe: `'latest'` — the exact placeholder
 * `PipelineConfig.defaults()`'s own model reference already uses elsewhere in this app
 * (vision-api/MODULE.md's `StartAssetStreamRequest#model` note). A model that *does* carry a real,
 * non-blank version (a future cv-service revision) is sent verbatim, untouched.
 */
export function resolvePromoteVersion(model: Pick<RegisteredModel, 'version'>): string {
  const trimmed = model.version.trim();
  return trimmed.length > 0 ? trimmed : 'latest';
}

/**
 * Display order for the registry table: the live model first — it's the one fact an operator
 * scanning the page wants to confirm before anything else — then the rest alphabetically by id, so
 * the order stays stable regardless of whatever order cv-service's own roster scan happens to
 * return them in. Never mutates its input.
 */
export function sortModelsForDisplay(models: readonly RegisteredModel[]): readonly RegisteredModel[] {
  return [...models].sort((a, b) => {
    if (a.active !== b.active) {
      return a.active ? -1 : 1;
    }
    return a.id.localeCompare(b.id);
  });
}
