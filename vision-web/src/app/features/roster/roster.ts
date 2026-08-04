import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Icon } from '../../shared/ui/icon';
import { EmptyState } from '../../shared/ui/empty-state';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { PilotsCard } from '../asset-detail/pilots-card';
import { RosterFacade } from './roster-facade';
import type { RosterRow } from './roster-logic';

/**
 * `/manage/roster` — the fleet-wide pilot roster (docs/UI-REDESIGN-PLAN.md Wave 4, Manage's
 * **FUNCTIONAL-NOW** "Pilots / roster" tile — `AssignmentController`/`PilotResponse`/`OrgStore` were
 * all already live, this is the first dedicated frontend surface for them; previously the tile's
 * only route was the `ComingSoon` scaffold pointing at each asset's own Pilots card).
 *
 * Route-guarded (`core/org/org-guard.ts`, the same guard `/org` uses) — only ADMIN/MANAGER reach
 * this page; a PILOT following the Manage hub's tile is redirected to `/fly` before this component
 * ever mounts, mirroring `OrgSettingsPage`'s own belt (route guard) with no second in-component
 * suspenders needed (`pilots-card.ts`'s own `canManage()` check exists because *it* is mounted on a
 * page pilots themselves can reach — this page isn't).
 *
 * Every asset, alphabetical, each row naming its currently-assigned pilots as chips (collapsed) and
 * expanding (a native `<details>`, no extra JS state beyond `RosterFacade.isExpanded`) to the exact
 * same `<vision-pilots-card>` the asset detail page's own Pilots drawer uses — add/remove is
 * byte-for-byte that component's existing logic, not reimplemented here. Honest states: distinct
 * loading / "couldn't load" (with retry) / no assets yet / no assets match the search.
 *
 * **`page-head` → `vision-page-bar`** (docs/NAV-IA-REDESIGN-PLAN.md §2.2, docs/design/13-roster.md):
 * the subtitle carries real instruction (the accordion's expand-to-edit interaction isn't obvious
 * from the title), so it moves to the bar's `hint` rather than being deleted. The search input moves
 * into `[pageBarFilters]`. The count chip is new — this page previously showed no number anywhere —
 * and reads the same `rows()` the accordion renders, not a fleet-level pilot/gap summary
 * (docs/design/13-roster.md's `By asset | By pilot` pivot and "N asset(s) with no pilot" indicator
 * are the Wave 3 rewrite this task explicitly leaves alone).
 */
@Component({
  selector: 'vision-roster',
  imports: [FormsModule, RouterLink, PageBar, Icon, EmptyState, PilotsCard],
  templateUrl: './roster.html',
  styleUrl: './roster.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [RosterFacade],
})
export class RosterPage {
  protected readonly facade = inject(RosterFacade);

  protected onToggle(row: RosterRow, event: Event): void {
    this.facade.setExpanded(row.asset.assetId, (event.target as HTMLDetailsElement).open);
  }
}
