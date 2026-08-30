import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icon } from './icon';

/** Which of the three CV-settings route trees the host page belongs to. */
export type CvSubnavTree = 'models' | 'labeling' | 'training';

/**
 * `vision-cv-subnav` — the shared lateral navigator across the CV settings/training trees
 * (docs/plans/active/CV-SETTINGS-PLAN.md §3.3/§6 row W8: "the three route trees gain one shared
 * sub-nav so a dataset → its run → its model is walkable"). Sits atop each tree's own list page —
 * `ModelsPage` (`/manage/training/models`), `DatasetsPage` (`/manage/training`, "Labeling" — capture/
 * correct/export), and `RunHistoryPage` (`/manage/training/runs`, "Training" — persisted run
 * history). Deliberately **not** added to every drill-in page (`DatasetDetailPage`, `SampleEditorPage`,
 * `TrainingJobPage`, `RunDetailPage`) — those already carry their own "Back to …" breadcrumbs and
 * cross-links (e.g. `RunDetailPage`'s dataset/produced-model links), so a second, always-visible
 * nav row there would just repeat what a breadcrumb already says. This is a lateral jump between the
 * three *trees themselves*, not a replacement for either.
 *
 * The **Vision rail entries stay exactly as they are** (docs/plans/active/CV-SETTINGS-CONTEXT.md's own
 * task brief) — this is a page-level convenience for staying inside the CV-training loop without a
 * round trip through the sidebar, not a rail change. `active` is an explicit input, not
 * `routerLinkActive`: `/manage/training` (Labeling) and `/manage/training/models`/`/manage/training/runs`
 * all share the literal string prefix `/manage/training`, so a non-exact `routerLinkActive` would
 * highlight Labeling on every one of these pages — an explicit "which tree is this" input from each
 * host page sidesteps that string-prefix ambiguity entirely rather than fighting it with `exact`
 * options that would themselves have to special-case `:datasetId` sub-routes.
 */
@Component({
  selector: 'vision-cv-subnav',
  imports: [RouterLink, Icon],
  templateUrl: './cv-subnav.html',
  styleUrl: './cv-subnav.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CvSubnav {
  readonly active = input.required<CvSubnavTree>();
}
