import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icon } from '../../shared/ui/icon';
import { EmptyState } from '../../shared/ui/empty-state';
import { Notice } from '../../shared/ui/notice';
import { SectionHeader } from '../../shared/ui/section-header';
import { ModelsFacade } from './models-facade';

/**
 * `/manage/training/models` — the CV model registry (docs/plans/done/CV-TRAINING-PLAN.md Phase 2 T10): every
 * model cv-service currently knows about, a clear "Live" badge on the one actually serving
 * detections, and a one-click Promote for the rest. The final step of the loop this whole plan
 * builds toward — dataset export (`/manage/training`, Phase 1 T5) → fine-tune offline → rsync the
 * produced `.pt` into cv-service's model directory → **this page lists it and promotes it live**. No
 * training/start-training UI here — training itself runs offline (docs/plans/done/CV-TRAINING-PLAN.md's own
 * "the constraint that shapes everything"), this page only ingests the result.
 *
 * Degrades honestly: `vision.training.enabled=false` renders the same `vision-empty` "not enabled
 * here" state `DatasetsPage` uses (`ModelsFacade.disabled`); Promote is hidden entirely for a
 * non-manager (`ModelsFacade.canManage`, matching the backend's own 403); a 409 on promote (the
 * model isn't on the server yet) surfaces as one plain-language `vision-notice`, never a raw error.
 *
 * **Disabled-state cleanup (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.2, docs/extracts/design/16-training.md, wave 2)** —
 * same treatment as `DatasetsPage`: the `eyebrow="Manage"` duplicate of the sidebar's own active-group
 * label is gone, and the **Datasets** action is hidden while `disabled()` (it would only lead to that
 * page's own identical disabled state).
 */
@Component({
  selector: 'vision-models-page',
  imports: [RouterLink, Icon, EmptyState, Notice, SectionHeader],
  templateUrl: './models.html',
  styleUrl: './models.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ModelsFacade],
})
export class ModelsPage {
  protected readonly facade = inject(ModelsFacade);
}
