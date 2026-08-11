import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Icon } from '../../shared/ui/icon';
import { IconButton } from '../../shared/ui/icon-button';
import { EmptyState } from '../../shared/ui/empty-state';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { SectionHeader } from '../../shared/ui/section-header';
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
 * duplication. The **Models** action is hidden while `training.disabled()` — offering a door into a
 * second disabled page is not a primary action, it's dead weight above the one honest message this
 * state has to give (`vision-empty`, already used here, already produces one centered stack).
 */
@Component({
  selector: 'vision-datasets-page',
  imports: [FormsModule, RouterLink, Icon, IconButton, EmptyState, ConfirmDialog, SectionHeader],
  templateUrl: './datasets.html',
  styleUrl: './datasets.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [DatasetsFacade],
})
export class DatasetsPage {
  protected readonly facade = inject(DatasetsFacade);
}
