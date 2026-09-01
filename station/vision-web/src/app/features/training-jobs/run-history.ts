import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EmptyState } from '../../shared/ui/empty-state';
import { SectionHeader } from '../../shared/ui/section-header';
import { CvSubnav } from '../../shared/ui/cv-subnav';
import { RunHistoryFacade } from './run-history-facade';
import { formatRunMetric, runDatasetLabel, runProgressLabel, runStateLabel } from './run-history-logic';

/**
 * `/manage/training/runs` — the persisted training-run history (docs/plans/active/CV-SETTINGS-PLAN.md
 * §3.3/§6, wave W8): every fine-tune run this deployment has ever recorded, newest first, distinct
 * from `TrainingJobPage`'s in-flight live-poll view (`RunHistoryFacade`'s own doc comment explains
 * the split). Each row is a full-row link into `RunDetailPage`, which carries this row's own
 * dataset → run → produced model walk (this list intentionally keeps "Dataset" as plain text, not a
 * nested link, since the whole row is already one).
 *
 * `canManageOrg`-gated at the route level (`orgGuard`, `training-jobs.routes.ts`) — reading past
 * training runs is control-plane history, the same footing as starting one.
 *
 * Carries the shared `<vision-cv-subnav active="training">` (§3.3/§6 row W8) — the Training tree's
 * own root, alongside Models and Labeling.
 *
 * Degrades honestly (§3.5): `RunHistoryFacade.disabled` (set the moment the list call 404s) renders
 * a `vision-empty` "not enabled here" state instead of a blocked page or a fabricated empty list.
 */
@Component({
  selector: 'vision-run-history-page',
  imports: [RouterLink, EmptyState, SectionHeader, CvSubnav],
  templateUrl: './run-history.html',
  styleUrl: './run-history.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [RunHistoryFacade],
})
export class RunHistoryPage {
  protected readonly facade = inject(RunHistoryFacade);

  protected readonly runStateLabel = runStateLabel;
  protected readonly runProgressLabel = runProgressLabel;
  protected readonly formatRunMetric = formatRunMetric;
  protected readonly runDatasetLabel = runDatasetLabel;

  protected formatWhen(iso: string): string {
    return new Date(iso).toLocaleString();
  }
}
