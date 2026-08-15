import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { ToastService } from '../../core/toast.service';
import { PollScheduler } from '../../core/poll-scheduler';
import type { TrainingJobResponse } from '../../core/api/models';
import { isTerminalJobState, jobProgressPercent } from './training-job-logic';

/** Fast enough that "watching progress" feels live, cheap enough not to hammer the backend — between
 *  this app's 2s "fast-changing telemetry" cadence and its 5s "status" cadence. */
const JOB_POLL_INTERVAL_MS = 3_000;

/** Stable per-file console tag, mirroring every other store/facade in this app. */
const LOG_PREFIX = '[training-jobs]';

/**
 * `TrainingJobPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — `/manage/training/jobs/:jobId`, the
 * live progress view for one fine-tune run started from `DatasetDetailPage`'s own "Train a model"
 * card. Injects `VisionApi` directly rather than growing a shared store — one job's own polled
 * state is page-local, single-consumer data, the same reasoning `DatasetDetailFacade`/
 * `SampleEditorFacade`/`ModelsFacade` already document for their own page-local state.
 *
 * **Polling stops itself, not just on destroy.** `PollScheduler.schedule` is registered once, for
 * the page's whole lifetime (mirroring `AssetDetailFacade`'s own multi-poll shape), but
 * {@link pollJob}'s own callback no-ops the moment the last known job is terminal
 * (`isTerminalJobState`) or unknown ({@link notFound}) — a `SUCCEEDED`/`FAILED` job (or a 404'd one)
 * never keeps generating requests, without tearing down the scheduler registration itself. That
 * means navigating to a *different* job id on the same page instance (via {@link load}, called from
 * the page's own route-input `effect` on every `jobId` change — the same `DatasetDetailPage`
 * precedent) resumes polling for free if the new job is still running.
 */
@Injectable()
export class TrainingJobFacade {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);

  readonly job = signal<TrainingJobResponse | null>(null);
  readonly loading = signal(true);
  /** `true` once a load/poll has confirmed the job id is unknown — never started, evicted under the
   *  backend's own finished-job retention policy, or reachable only because `vision.training.enabled`
   *  was flipped off after the job started. A `vision-empty`, never a blocked page. */
  readonly notFound = signal(false);

  readonly progressPercent = computed(() => jobProgressPercent(this.job()));

  private currentJobId = '';

  constructor() {
    const stopPoll = inject(PollScheduler).schedule(JOB_POLL_INTERVAL_MS, () => this.pollJob());
    inject(DestroyRef).onDestroy(stopPoll);
  }

  /** Called once by the page's own constructor `effect()` on every `jobId` route-input change. */
  load(jobId: string): void {
    this.currentJobId = jobId;
    this.job.set(null);
    this.notFound.set(false);
    void this.fetchJob(jobId, false);
  }

  private async pollJob(): Promise<void> {
    const jobId = this.currentJobId;
    if (!jobId || this.notFound()) {
      return;
    }
    const current = this.job();
    if (current && isTerminalJobState(current.state)) {
      return; // settled — see this class's own doc comment
    }
    await this.fetchJob(jobId, true);
  }

  private async fetchJob(jobId: string, silent: boolean): Promise<void> {
    if (!silent) {
      this.loading.set(true);
    }
    try {
      const job = await this.api.trainingJob(jobId);
      this.job.set(job);
      this.notFound.set(false);
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 404) {
        this.notFound.set(true);
      } else {
        console.warn(`${LOG_PREFIX} failed to poll training job ${jobId}`, { error });
        if (!silent) {
          this.toasts.error(describeHttpError(error));
        }
        // A background poll failure otherwise stays silent — the page keeps showing the last known
        // state rather than toast-spamming on every failed tick (this app's own silent-degrade
        // convention for polls, e.g. `AssetDetailFacade`'s own stream-events poll).
      }
    } finally {
      if (!silent) {
        this.loading.set(false);
      }
    }
  }
}
