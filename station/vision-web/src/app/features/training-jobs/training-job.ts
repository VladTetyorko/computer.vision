import { ChangeDetectionStrategy, Component, effect, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EmptyState } from '../../shared/ui/empty-state';
import { Notice } from '../../shared/ui/notice';
import { SectionHeader } from '../../shared/ui/section-header';
import { TrainingJobFacade } from './training-job-facade';
import { failureMessage, formatMetric, hasReportedProgress, jobStateLabel, producedModelId } from './training-job-logic';

/**
 * `/manage/training/jobs/:jobId` — the live progress view for one CV fine-tune run
 * (docs/plans/done/CV-TRAINING-PLAN.md Phase 2's last web wave). Reached by starting a run from
 * `DatasetDetailPage`'s own "Train a model" card (an immediate navigation here, so the operator
 * lands straight on the run they just started) or from that same page's "Training jobs" list for a
 * job started earlier.
 *
 * Polls `GET /api/training/jobs/{jobId}` via `TrainingJobFacade` and renders a progress bar
 * (`epoch/totalEpochs`), the latest `loss`/`map50` in the mono/telemetry register, and the job's
 * state as a chip. **`SUCCEEDED`** links straight to the model registry
 * (`/manage/training/models`) — "promote it live" is the very next thing an operator does once a
 * run finishes. **`FAILED`** renders the backend's own plain-language reason
 * (`TrainingJobResponse#message`) in a `vision-notice danger`, never a raw error — a training
 * failure is a normal, polled outcome here, not an exception this page has to guess at.
 *
 * Degrades honestly: an unknown/evicted job (`TrainingJobFacade.notFound`) renders a `vision-empty`,
 * never a blocked page.
 */
@Component({
  selector: 'vision-training-job-page',
  imports: [RouterLink, EmptyState, Notice, SectionHeader],
  templateUrl: './training-job.html',
  styleUrl: './training-job.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [TrainingJobFacade],
})
export class TrainingJobPage {
  /** Bound from the route by `withComponentInputBinding()`. */
  readonly jobId = input.required<string>();

  protected readonly facade = inject(TrainingJobFacade);

  protected readonly jobStateLabel = jobStateLabel;
  protected readonly formatMetric = formatMetric;
  protected readonly hasReportedProgress = hasReportedProgress;
  protected readonly producedModelId = producedModelId;
  protected readonly failureMessage = failureMessage;

  constructor() {
    effect(() => {
      this.facade.load(this.jobId());
    });
  }

  protected formatWhen(iso: string): string {
    return new Date(iso).toLocaleString();
  }
}
