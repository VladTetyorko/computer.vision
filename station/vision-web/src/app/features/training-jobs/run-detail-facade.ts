import { Injectable, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { ToastService } from '../../core/toast.service';
import type { TrainingRun } from '../../core/api/models';

/** Stable per-file console tag, mirroring every other store/facade in this app. */
const LOG_PREFIX = '[training-jobs]';

/**
 * `RunDetailPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — `/manage/training/runs/:runId`,
 * one persisted training run's own record (docs/plans/active/CV-SETTINGS-PLAN.md §3.3/§5.2, wave W8),
 * the dataset → run → produced model walk's middle link (Provenance column on `ModelsPage` links
 * here from the model side; this page links back to the dataset and forward to the registry).
 *
 * **Not a live poll.** Unlike `TrainingJobFacade` (`/manage/training/jobs/:jobId`, the in-flight
 * progress view), this reads `GET /api/cv/training/runs/{runId}` once per navigation — a persisted
 * run row's own numbers are the record of what happened, not something to chase every few seconds;
 * an operator watching a run still in progress uses the live job page instead (both can point at the
 * same underlying run once it's running, but they answer different questions — "how's it doing
 * right now" vs "what happened").
 *
 * Route-guarded by `orgGuard` (`training-jobs.routes.ts`), same as `RunHistoryFacade`'s own doc
 * comment explains — reachable only once the caller may already manage the org.
 *
 * **Degrades honestly (§3.5)**: an unknown `runId` or `vision.training.enabled=false` both surface
 * as `404` server-side (this endpoint's own doc comment) and render {@link notFound} — a
 * `vision-empty`, never a blocked page. Any other failure toasts and leaves the run unset.
 */
@Injectable()
export class RunDetailFacade {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);

  readonly run = signal<TrainingRun | null>(null);
  readonly loading = signal(false);
  /** `true` once a load has confirmed the run id is unknown, or the feature is disabled. */
  readonly notFound = signal(false);

  async load(runId: string): Promise<void> {
    this.run.set(null);
    this.notFound.set(false);
    this.loading.set(true);
    try {
      const run = await this.api.getTrainingRun(runId);
      this.run.set(run);
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 404) {
        this.notFound.set(true);
      } else {
        console.warn(`${LOG_PREFIX} failed to load training run ${runId}`, { error });
        this.toasts.error(describeHttpError(error));
      }
    } finally {
      this.loading.set(false);
    }
  }
}
