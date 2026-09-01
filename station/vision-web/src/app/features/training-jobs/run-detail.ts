import { ChangeDetectionStrategy, Component, effect, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EmptyState } from '../../shared/ui/empty-state';
import { Notice } from '../../shared/ui/notice';
import { SectionHeader } from '../../shared/ui/section-header';
import { RunDetailFacade } from './run-detail-facade';
import { formatRunMetric, hasProducedModel, runDatasetLabel, runProgressLabel, runStateLabel } from './run-history-logic';

/**
 * `/manage/training/runs/:runId` — one persisted training run's own record
 * (docs/plans/active/CV-SETTINGS-PLAN.md §3.3/§6, wave W8): the dataset → run → produced model walk's
 * middle link — a "Dataset" link back to `DatasetDetailPage`, and (once the run has produced one) an
 * "open the model registry" link forward into `ModelsPage`, whose own Provenance column links back
 * here from the model side. Reached from `RunHistoryPage`'s row links, or from a model's Provenance
 * cell.
 *
 * Deliberately carries no `<vision-cv-subnav>` — a drill-in page, not a tree root; its own "Back to
 * run history"/"Dataset" actions already cover the lateral moves that matter here (`CvSubnav`'s own
 * doc comment explains the split).
 *
 * `canManageOrg`-gated at the route level (`orgGuard`, `training-jobs.routes.ts`), same footing as
 * `RunHistoryPage`.
 *
 * Degrades honestly (§3.5): an unknown `runId` or a disabled deployment both read as `404`
 * server-side and render `RunDetailFacade.notFound` — a `vision-empty`, never a blocked page. A run
 * that finished without a produced model (no `outputModelId` despite not being `FAILED` — an
 * unexpected but possible backend state) reads as its own honest warn notice rather than silently
 * omitting the "produced model" section.
 */
@Component({
  selector: 'vision-run-detail-page',
  imports: [RouterLink, EmptyState, Notice, SectionHeader],
  templateUrl: './run-detail.html',
  styleUrl: './run-detail.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [RunDetailFacade],
})
export class RunDetailPage {
  /** Bound from the route by `withComponentInputBinding()`. */
  readonly runId = input.required<string>();

  protected readonly facade = inject(RunDetailFacade);

  protected readonly runStateLabel = runStateLabel;
  protected readonly runProgressLabel = runProgressLabel;
  protected readonly formatRunMetric = formatRunMetric;
  protected readonly runDatasetLabel = runDatasetLabel;
  protected readonly hasProducedModel = hasProducedModel;

  constructor() {
    effect(() => {
      void this.facade.load(this.runId());
    });
  }

  protected formatWhen(iso: string): string {
    return new Date(iso).toLocaleString();
  }
}
