import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { ToastService } from '../../core/toast.service';
import type { TrainingRun } from '../../core/api/models';
import { sortRunsNewestFirst } from './run-history-logic';

/** Stable per-file console tag, mirroring every other store/facade in this app. */
const LOG_PREFIX = '[training-jobs]';

/**
 * `RunHistoryPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — `/manage/training/runs`, the
 * persisted training-run history behind `VisionApi#getTrainingRuns` (docs/plans/active/CV-SETTINGS-PLAN.md
 * §3.3/§5.2, wave W8). Injects `VisionApi` directly rather than growing a shared store — page-local,
 * single-consumer state, the same `ModelsFacade`/`DatasetDetailFacade` shape.
 *
 * **Route-guarded, not client-gated**: `manage/training/runs` carries `orgGuard` at the route level
 * (`training-jobs.routes.ts`, matching `TrainingJobService#runs`'s own `canManageOrg` server gate),
 * so this facade never has to hide a 403 behind its own logic — only reachable at all once the guard
 * has already confirmed the caller may manage the org. **Dev parity**: `vision.auth.enabled=false`'s
 * dev principal is `ADMIN`/unbounded, so `orgGuard` passes exactly as it does for a real manager.
 *
 * **Degrades honestly (§3.5)**: a `404` means `vision.training.enabled=false` on this deployment —
 * the whole training-run route tree is absent server-side, same as `TrainingJobController`'s own
 * `GET /api/training/jobs` — and renders {@link disabled} as one honest `vision-empty`, never a
 * blocked page or a fabricated empty list. Any other failure (a genuine transport/server problem)
 * toasts via `describeHttpError` and leaves the list empty, the same posture `ModelsFacade.refresh`
 * takes for its own roster read.
 */
@Injectable()
export class RunHistoryFacade {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);

  private readonly runsSignal = signal<readonly TrainingRun[]>([]);
  private readonly loadingSignal = signal(false);
  private readonly loadedSignal = signal(false);
  private readonly disabledSignal = signal(false);

  /** Newest-first (`sortRunsNewestFirst`) — the list page's own display order. */
  readonly runs = computed(() => sortRunsNewestFirst(this.runsSignal()));
  readonly loading = this.loadingSignal.asReadonly();
  /** `false` until the first `refresh()` settles — lets the page tell "still loading" from "genuinely no runs". */
  readonly loaded = this.loadedSignal.asReadonly();
  /** `true` once a load has confirmed `vision.training.enabled=false` on this deployment. */
  readonly disabled = this.disabledSignal.asReadonly();

  constructor() {
    void this.refresh();
  }

  async refresh(): Promise<void> {
    this.loadingSignal.set(true);
    try {
      const response = await this.api.getTrainingRuns();
      this.runsSignal.set(response.runs);
      this.disabledSignal.set(false);
      this.loadedSignal.set(true);
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 404) {
        this.runsSignal.set([]);
        this.disabledSignal.set(true);
        this.loadedSignal.set(true);
      } else {
        console.warn(`${LOG_PREFIX} failed to load training run history`, { error });
        this.toasts.error(describeHttpError(error));
      }
    } finally {
      this.loadingSignal.set(false);
    }
  }
}
