import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Icon } from '../../shared/ui/icon';
import { IconButton } from '../../shared/ui/icon-button';
import { EmptyState } from '../../shared/ui/empty-state';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { SectionHeader } from '../../shared/ui/section-header';
import { CvSubnav } from '../../shared/ui/cv-subnav';
import { TrainingFacade } from '../../core/training/training-facade';
import { DatasetsFacade } from './datasets-facade';

/**
 * `/manage/training` — the CV training dataset list (docs/plans/done/CV-TRAINING-PLAN.md Wave T5): browse
 * datasets, create/delete one (manage-org gated, see `DatasetsFacade`'s own doc comment), open one
 * to capture/label samples. No route guard — unlike `/manage/roster`, capture/label are open to any
 * user who can see a dataset, so the page itself is reachable by everyone; only create/delete hide.
 *
 * Degrades honestly when `vision.training.enabled=false`: `TrainingStore.disabled()` (set the moment
 * the dataset-list call 404s — see that store's own doc comment) renders a `vision-empty`
 * "not enabled here" state instead of a blocked page or a fabricated empty dataset list.
 *
 * **Disabled-state cleanup (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.2, docs/extracts/design/16-training.md, wave 2).**
 * The header's own `eyebrow="Manage"` is gone — the sidebar's active group already answers "where am
 * I" (docs/extracts/design/00-shell.md), so repeating the mode name in every page's own header was pure
 * duplication.
 *
 * **Shared CV sub-nav (docs/plans/active/CV-SETTINGS-PLAN.md §3.3/§6 row W8)** — `<vision-cv-subnav
 * active="labeling">` replaces the old header-actions "Models" link outright; it covers that same
 * jump plus the new Training (run history) tree, so this page never repeats it as a bespoke action —
 * unlike that old link, the sub-nav is always visible here (not hidden behind `!disabled()`) since
 * jumping to another CV-settings tree is meaningful regardless of whether datasets are enabled here.
 */
@Component({
  selector: 'vision-datasets-page',
  imports: [FormsModule, RouterLink, Icon, IconButton, EmptyState, ConfirmDialog, SectionHeader, CvSubnav],
  templateUrl: './datasets.html',
  styleUrl: './datasets.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // `TrainingFacade` is page-provided since wave N-split — see its own doc comment; the `training`
  // slice it reads is registered by `labeling.page-routes.ts`.
  providers: [DatasetsFacade, TrainingFacade],
})
export class DatasetsPage {
  protected readonly facade = inject(DatasetsFacade);
}
