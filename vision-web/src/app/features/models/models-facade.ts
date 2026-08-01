import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { ToastService } from '../../core/toast.service';
import { AuthStore } from '../../core/auth/auth-store';
import { canManageOrg } from '../../core/org/org-logic';
import type { RegisteredModel } from '../../core/api/models';
import { canPromoteModel, resolvePromoteVersion, sortModelsForDisplay } from './models-logic';

/** Stable per-file console tag, mirroring every other store/facade in this app. */
const LOG_PREFIX = '[models]';

/**
 * `ModelsPage`'s facade (docs/UI-ARCHITECTURE-PLAN.md) — `/manage/training/models`, the last step
 * of the CV-TRAINING-PLAN loop this plan builds toward (dataset export → train offline → rsync the
 * produced `.pt` into cv-service's model directory → **this page lists it and promotes it live**,
 * docs/CV-TRAINING-PLAN.md Phase 2 T10). Injects `VisionApi` directly rather than growing a shared
 * store — the registry list is page-local, single-consumer state, the same
 * `DatasetDetailFacade`/`CategoriesFacade` shape ("a routed page's facade may talk to a service
 * directly, not only a store").
 *
 * **Role-gating mirrors the backend exactly**: `ModelRegistryController#promote` requires "may
 * manage the organization" (403 otherwise, vision-api/MODULE.md's own `ModelRegistryController`
 * subsection), so {@link canManage} (`canManageOrg`, `core/org/org-logic.ts` — the same predicate
 * `DatasetsFacade`/`org-guard.ts`/`identity-chip.ts` already use) hides every row's Promote button
 * for a non-manager client-side too, rather than showing a button that would only ever 403. The
 * list itself (`models()`) is unscoped/unaudited server-side — any signed-in caller may read it, so
 * `ModelsPage` carries no route guard. **Dev parity**: `vision.auth.enabled=false`'s dev principal
 * resolves to `ADMIN`/unbounded scope, so `canManageOrg` is `true` and Promote behaves exactly as it
 * does for a real admin.
 *
 * **Feature-off degrade, with no dedicated "is it enabled" endpoint** (mirrors `TrainingStore`'s own
 * doc comment): `vision.training.enabled=false` removes `ModelRegistryController` from the app
 * entirely, so `GET /api/cv/registry/models` 404s exactly like any unmapped path — the *only* call
 * this facade makes that can mean that, so {@link refresh} turns that specific 404 into
 * {@link disabled}, read by `ModelsPage` to render the same honest `vision-empty` "not enabled here"
 * state `DatasetsPage` uses, never a blocked page or a fabricated model list.
 *
 * **The 409 on promote** (cv-service's own "unknown model id … not in the registry roster … rsync
 * the model artifact into the cv-service model directory first" message, `ModelRegistryController`'s
 * javadoc) is folded into {@link promoteError} as one plain sentence in the interface's own voice,
 * not a raw server-message dump — every other failure (network down, a genuine 5xx, a malformed-
 * version 400) still toasts via `describeHttpError`, same as everywhere else in this app.
 */
@Injectable()
export class ModelsFacade {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly auth = inject(AuthStore);

  private readonly modelsSignal = signal<readonly RegisteredModel[]>([]);
  private readonly loadingSignal = signal(false);
  private readonly loadedSignal = signal(false);
  private readonly disabledSignal = signal(false);
  private readonly promotingIdSignal = signal<string | null>(null);
  private readonly promoteErrorSignal = signal<string | null>(null);

  /** Live model first, then alphabetical (`sortModelsForDisplay`) — see that function's own doc comment. */
  readonly models = computed(() => sortModelsForDisplay(this.modelsSignal()));
  readonly loading = this.loadingSignal.asReadonly();
  /** `false` until the first `refresh()` settles — lets the page tell "still loading" from "genuinely no models". */
  readonly loaded = this.loadedSignal.asReadonly();
  /** `true` once a `refresh()` has confirmed `vision.training.enabled=false` on this deployment — see class doc. */
  readonly disabled = this.disabledSignal.asReadonly();
  /** The id currently being promoted, or `null` — drives every row's own "Promoting…" label and disables every *other* row's button. */
  readonly promotingId = this.promotingIdSignal.asReadonly();
  /** The honest 409 message from the last promote attempt, or `null`. Cleared at the start of the next attempt. */
  readonly promoteError = this.promoteErrorSignal.asReadonly();

  readonly canManage = computed(() => canManageOrg(this.auth.user()?.topRole));

  constructor() {
    void this.refresh();
  }

  /** Re-reads the registry. A 404 sets {@link disabled} instead of toasting (an expected, first-class outcome here, not an error); every other failure toasts once, explained. */
  async refresh(): Promise<void> {
    this.loadingSignal.set(true);
    try {
      const response = await this.api.registryModels();
      this.modelsSignal.set(response.models);
      this.disabledSignal.set(false);
      this.loadedSignal.set(true);
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 404) {
        this.modelsSignal.set([]);
        this.disabledSignal.set(true);
        this.loadedSignal.set(true);
      } else {
        console.warn(`${LOG_PREFIX} failed to load the model registry`, { error });
        this.toasts.error(describeHttpError(error));
      }
    } finally {
      this.loadingSignal.set(false);
    }
  }

  /** Single source for the row-level "Promote" enabled predicate — see `canPromoteModel`'s own doc comment. */
  canPromote(model: RegisteredModel): boolean {
    return canPromoteModel(model, this.canManage(), this.promotingId());
  }

  /** Promotes `model`, then re-reads the registry so the Live badge moves onto it — a plain refetch rather than a hand-rolled optimistic flip, mirroring `TrainingStore`'s own mutate-then-`refresh()` shape. */
  async promote(model: RegisteredModel): Promise<void> {
    if (!this.canPromote(model)) {
      return;
    }
    this.promoteErrorSignal.set(null);
    this.promotingIdSignal.set(model.id);
    try {
      await this.api.promoteModel(model.id, { version: resolvePromoteVersion(model) });
      await this.refresh();
      this.toasts.ok(`"${model.id}" is now the live model.`);
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 409) {
        this.promoteErrorSignal.set(
          `"${model.id}" isn't on the server yet — copy its file into cv-service first, then try again.`,
        );
      } else {
        console.warn(`${LOG_PREFIX} failed to promote ${model.id}`, { error });
        this.toasts.error(describeHttpError(error));
      }
    } finally {
      this.promotingIdSignal.set(null);
    }
  }
}
