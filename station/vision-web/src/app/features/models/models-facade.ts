import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { ToastService } from '../../core/toast.service';
import { AuthStore } from '../../core/auth/auth-store';
import { UiStore } from '../../core/ui/ui-store';
import type { CvModel } from '../../core/api/models';
import {
  canAdministerRegistry,
  canPromoteModel,
  catalogSource,
  findLiveModel,
  modelKey,
  resolvePromoteVersion,
  rollbackConfirmMessage,
  sortModelsForDisplay,
} from './models-logic';

/** Stable per-file console tag, mirroring every other store/facade in this app. */
const LOG_PREFIX = '[models]';

/** `UiStore` id for the (single, page-wide, not per-row) rollback confirm. */
const ROLLBACK_DIALOG_ID = 'rollback';

/**
 * `ModelsPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — `/manage/training/models`, the
 * registry step of the CV-TRAINING-PLAN loop, widened by docs/plans/active/CV-SETTINGS-PLAN.md §3.2/§5.2
 * (wave W8) into a joined read model: worker truth (`ModelInfo`) merged with platform governance
 * (`CvModelRecord` — status/runtime/metrics/provenance). Injects `VisionApi` directly rather than
 * growing a shared store — the registry list is page-local, single-consumer state, the same
 * `DatasetDetailFacade`/`CategoriesFacade` shape ("a routed page's facade may talk to a service
 * directly, not only a store").
 *
 * **Role-gating is narrower than most manager surfaces**: `ModelRegistryController#promote`/
 * `#rollback` both require `canAdminister()` (ADMIN only), not `canManageOrg()` — a MANAGER can see
 * this page (route-guarded `orgGuard`) but never promote or roll back (`canAdministerRegistry`,
 * `models-logic.ts`'s own doc comment explains why this is narrower than the usual manager gate).
 * The roster read itself (`GET /api/cv/models`) is open to any signed-in caller and never errors
 * server-side, so unlike this file's pre-W8 shape there is no "feature disabled" empty state for the
 * *page* any more — only the mutation buttons can 404 (`vision.cv.registry.enabled=false`).
 * **Dev parity**: `vision.auth.enabled=false`'s dev principal resolves to `ADMIN`/unbounded scope,
 * so `canAdminister` is `true` and Promote/Roll back behave exactly as they do for a real admin.
 *
 * **Degrades honestly (CLAUDE.md, §3.5)**: a genuine transport failure on the roster read toasts and
 * leaves the list empty (`GET /api/cv/models` itself never 4xx/5xxs by contract, so a caught error
 * here really is a network/server problem, not an expected outcome); the 409 on promote/rollback
 * (cv-service refuses / "nothing to roll back to") is folded into {@link promoteError}/
 * {@link rollbackError} as one plain sentence, never a raw server-message dump; a 404 on either
 * mutation (the registry flag is off) reads as its own honest notice via {@link registryDisabled}
 * rather than a generic toast.
 */
@Injectable()
export class ModelsFacade {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly auth = inject(AuthStore);

  private readonly modelsSignal = signal<readonly CvModel[]>([]);
  private readonly loadingSignal = signal(false);
  private readonly loadedSignal = signal(false);
  private readonly promotingKeySignal = signal<string | null>(null);
  private readonly promoteErrorSignal = signal<string | null>(null);
  private readonly rollbackBusySignal = signal(false);
  private readonly rollbackErrorSignal = signal<string | null>(null);
  private readonly registryDisabledSignal = signal(false);

  /** Live model first, then by registry-lifecycle priority (`sortModelsForDisplay`) — see that function's own doc comment. */
  readonly models = computed(() => sortModelsForDisplay(this.modelsSignal()));
  readonly loading = this.loadingSignal.asReadonly();
  /** `false` until the first `refresh()` settles — lets the page tell "still loading" from "genuinely no models". */
  readonly loaded = this.loadedSignal.asReadonly();
  /** Which of `ModelRegistryService#models()`'s two paths produced the current roster — drives the "registry unreachable" banner (§3.5 rule 2). */
  readonly source = computed(() => catalogSource(this.modelsSignal()));
  /** The row currently `LIVE`, if any — named in the rollback confirm dialog. */
  readonly liveModel = computed(() => findLiveModel(this.modelsSignal()));
  /** `modelKey`-shaped id of the row currently being promoted, or `null` — drives that row's own "Promoting…" label and disables every *other* row's button. */
  readonly promotingKey = this.promotingKeySignal.asReadonly();
  /** The honest 409/404 message from the last promote attempt, or `null`. Cleared at the start of the next attempt. */
  readonly promoteError = this.promoteErrorSignal.asReadonly();
  readonly rollbackBusy = this.rollbackBusySignal.asReadonly();
  /** The honest 409/404 message from the last rollback attempt, or `null`. Cleared at the start of the next attempt. */
  readonly rollbackError = this.rollbackErrorSignal.asReadonly();
  /** `true` once a mutation call has confirmed `vision.cv.registry.enabled=false` on this deployment. The roster read itself never sets this — it never 404s. */
  readonly registryDisabled = this.registryDisabledSignal.asReadonly();

  readonly canAdminister = computed(() => canAdministerRegistry(this.auth.scopeKind()));
  readonly rollbackDialogOpen = computed(() => this.dialogs.isOpen(ROLLBACK_DIALOG_ID));
  readonly rollbackMessage = computed(() => rollbackConfirmMessage(this.liveModel()));

  /** Single-overlay group of one — mirrors `DatasetsFacade.dialogs`'s own "a confirm is its own tiny `UiStore` group" precedent rather than a bare boolean. */
  private readonly dialogs = new UiStore();

  constructor() {
    void this.refresh();
  }

  /** Re-reads the roster. Never expects a 404 (`GET /api/cv/models` doesn't 4xx by contract) — any failure here is a genuine transport/server problem and toasts once, explained. */
  async refresh(): Promise<void> {
    this.loadingSignal.set(true);
    try {
      const response = await this.api.getCvModels();
      this.modelsSignal.set(response.models);
      this.loadedSignal.set(true);
    } catch (error) {
      console.warn(`${LOG_PREFIX} failed to load the model roster`, { error });
      this.toasts.error(describeHttpError(error));
    } finally {
      this.loadingSignal.set(false);
    }
  }

  /** Single source for the row-level "Promote" enabled predicate — see `canPromoteModel`'s own doc comment. */
  canPromote(model: CvModel): boolean {
    return canPromoteModel(model, this.canAdminister(), this.promotingKey());
  }

  /** Promotes `model`, then re-reads the roster so the Live chip moves onto it — a plain refetch rather than a hand-rolled optimistic flip, mirroring `TrainingStore`'s own mutate-then-`refresh()` shape. */
  async promote(model: CvModel): Promise<void> {
    if (!this.canPromote(model)) {
      return;
    }
    this.promoteErrorSignal.set(null);
    const key = modelKey(model);
    this.promotingKeySignal.set(key);
    try {
      await this.api.promoteModel(model.id, resolvePromoteVersion(model));
      await this.refresh();
      this.toasts.ok(`"${model.id}" is now the live model.`);
    } catch (error) {
      this.handleMutationError(error, this.promoteErrorSignal, `"${model.id}" isn't on the server yet — copy its file into cv-service first, then try again.`);
    } finally {
      this.promotingKeySignal.set(null);
    }
  }

  requestRollback(): void {
    if (!this.canAdminister()) {
      return;
    }
    this.rollbackErrorSignal.set(null);
    this.dialogs.open(ROLLBACK_DIALOG_ID);
  }

  cancelRollback(): void {
    this.dialogs.close(ROLLBACK_DIALOG_ID);
  }

  /** Rolls back the live model, then re-reads the roster. A **409** ("no previous model to roll back to") is the one expected, first-class outcome this method itself never toasts for — see {@link rollbackError}. */
  async confirmRollback(): Promise<void> {
    this.rollbackBusySignal.set(true);
    try {
      const result = await this.api.rollbackModel();
      await this.refresh();
      this.dialogs.close(ROLLBACK_DIALOG_ID);
      this.toasts.ok(`"${result.id}" is now the live model.`);
    } catch (error) {
      this.handleMutationError(error, this.rollbackErrorSignal, 'Nothing to roll back to — no model has been promoted over yet.');
    } finally {
      this.rollbackBusySignal.set(false);
    }
  }

  /**
   * Shared 409/404/other-failure handling for both mutations: a **409** is expected-and-honest
   * (`conflictMessage`, e.g. cv-service refusing an unrsynced promote, or rollback's "nothing to
   * restore") and is written to `errorSignal`, not toasted; a **404** means
   * `vision.cv.registry.enabled=false` on this deployment (`registryDisabled`, read by the page to
   * hide both buttons behind one honest notice instead of a dead-end 404 loop); anything else is a
   * genuine failure and toasts via `describeHttpError`, same as everywhere else in this app.
   */
  private handleMutationError(
    error: unknown,
    errorSignal: { set(value: string | null): void },
    conflictMessage: string,
  ): void {
    if (error instanceof HttpErrorResponse && error.status === 409) {
      errorSignal.set(conflictMessage);
      return;
    }
    if (error instanceof HttpErrorResponse && error.status === 404) {
      this.registryDisabledSignal.set(true);
      errorSignal.set("This deployment hasn't turned on model promotion yet.");
      return;
    }
    console.warn(`${LOG_PREFIX} registry mutation failed`, { error });
    this.toasts.error(describeHttpError(error));
  }
}
