import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Icon } from '../../shared/ui/icon';
import { EmptyState } from '../../shared/ui/empty-state';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { IconButton } from '../../shared/ui/icon-button';
import { SectionHeader } from '../../shared/ui/section-header';
import { regionPhaseLabel, regionProgressPercent, regionStatusLabel, regionStatusTone } from '../../core/geo/geo-logic';
import { RegionManagerFacade } from './region-manager-facade';

/**
 * `/manage/geo/regions` (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.8, wave H6) — the reference-imagery
 * region list: an ingest form (bounds + zoom), live indexing progress for anything `BUILDING`, and
 * every settled region's own status/tile-count/holdout stats. Route-guarded (`geo.routes.ts`'s
 * `orgGuard`) rather than gated client-side only — unlike `/manage/training`, there is nothing here
 * for a non-manager to see, so the whole page redirects instead of rendering a form full of disabled
 * buttons.
 *
 * Degrades honestly when `vision.geo.visual.enabled=false`: `RegionManagerFacade.disabled()` (set the
 * moment the region-list call returns D9's 409 — see that facade's own doc comment) renders a
 * `vision-empty` "not enabled here" state instead of a blocked page or a fabricated empty list —
 * consistent with how the cockpit's `GeoChip` and `TacticalMap`'s corrections layer both go
 * fully absent for the same flag, elsewhere in this wave.
 *
 * `NEVER_ACCEPT` renders exactly like every other status (`regionStatusLabel`/`regionStatusTone`),
 * never hidden or folded into `FAILED` — §3.8's own explicit rule, see `geo-logic.ts#regionStatusTone`'s
 * doc comment for why the two are deliberately distinct tones.
 */
@Component({
  selector: 'vision-region-manager-page',
  imports: [FormsModule, Icon, EmptyState, ConfirmDialog, IconButton, SectionHeader],
  templateUrl: './region-manager.html',
  styleUrl: './region-manager.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [RegionManagerFacade],
})
export class RegionManagerPage {
  protected readonly facade = inject(RegionManagerFacade);

  protected readonly regionStatusLabel = regionStatusLabel;
  protected readonly regionStatusTone = regionStatusTone;
  protected readonly regionPhaseLabel = regionPhaseLabel;
  protected readonly regionProgressPercent = regionProgressPercent;
}
