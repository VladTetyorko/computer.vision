import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icon } from '../../shared/ui/icon';
import { EmptyState } from '../../shared/ui/empty-state';
import { Notice } from '../../shared/ui/notice';
import { SectionHeader } from '../../shared/ui/section-header';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { CvSubnav } from '../../shared/ui/cv-subnav';
import { ModelsFacade } from './models-facade';
import {
  formatMap50,
  hasProvenance,
  isMissingOnWorker,
  metricsKindLabel,
  modelKey,
  runtimeLabel,
  statusChipVariant,
  statusLabel,
} from './models-logic';

/**
 * `/manage/training/models` — the CV model registry (docs/plans/active/CV-SETTINGS-PLAN.md §3.2/§5.2,
 * wave W8): every model this deployment knows about — worker truth (id/version/live-or-not) joined
 * with platform governance (status/runtime/metrics/provenance/availability) — a clear **Live** chip
 * on the one actually serving detections, one-click **Promote** for the rest, and a page-level
 * **Roll back** that restores whichever model the last promotion demoted. Rewritten this wave from
 * its original, worker-only shape (docs/plans/done/CV-TRAINING-PLAN.md Phase 2 T10) — see
 * `ModelsFacade`'s own doc comment for the read-model change and `models-logic.ts` for the
 * status/runtime/availability/metrics/provenance rendering rules.
 *
 * Degrades honestly (§3.5): `GET /api/cv/models` never errors server-side, so the page is never a
 * blocked "not enabled here" state any more — a `source:'config'` roster (registry off, or its
 * worker unreachable) instead renders every row plus one `vision-notice` saying so
 * (`ModelsFacade.source`). Availability's `MISSING_ON_WORKER` reads as an explicit warning line next
 * to the row, never hidden or silently substituted (rule 4); metrics are labelled **training mAP50**,
 * never presented as a held-out evaluation (rule 5). Promote/Roll back are both hidden entirely for
 * anyone but an administrator (`ModelsFacade.canAdminister`, matching `ModelRegistryController`'s own
 * `canAdminister()` gate — narrower than the usual manager gate, see that facade's own doc comment);
 * a 409 on either (cv-service refuses a promote, or "nothing to roll back to") surfaces as one
 * plain-language notice, never a raw error; a 404 (the registry flag is off) reads the same way.
 * Roll back requires an explicit confirm naming what it retires (`ModelsFacade.rollbackMessage`) —
 * never a single-click destructive action.
 *
 * Carries the shared `<vision-cv-subnav active="models">` (§3.3/§6 row W8) so a dataset → its run →
 * its produced model is walkable without returning to the rail — this page's own Provenance column
 * is the other half of that path, linking straight to the run/dataset that produced a registry row.
 */
@Component({
  selector: 'vision-models-page',
  imports: [RouterLink, Icon, EmptyState, Notice, SectionHeader, ConfirmDialog, CvSubnav],
  templateUrl: './models.html',
  styleUrl: './models.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ModelsFacade],
})
export class ModelsPage {
  protected readonly facade = inject(ModelsFacade);

  protected readonly modelKey = modelKey;
  protected readonly statusLabel = statusLabel;
  protected readonly statusChipVariant = statusChipVariant;
  protected readonly runtimeLabel = runtimeLabel;
  protected readonly isMissingOnWorker = isMissingOnWorker;
  protected readonly metricsKindLabel = metricsKindLabel;
  protected readonly formatMap50 = formatMap50;
  protected readonly hasProvenance = hasProvenance;
}
