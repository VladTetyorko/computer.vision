import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { EmptyState } from '../../shared/ui/empty-state';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { Stat } from '../../shared/ui/stat';
import { TwoPane } from '../../shared/ui/two-pane/two-pane';
import type { PilotAssetAssignment } from '../../core/roster/roster-pivot-logic';
import { PilotsCard } from '../asset-detail/pilots-card';
import { PilotAssignmentsPanel } from './pilot-assignments-panel';
import { RosterFacade } from './roster-facade';

/**
 * `/manage/roster` — the fleet-wide pilot roster (docs/plans/done/UI-REDESIGN-PLAN.md Wave 4's original
 * accordion build; reworked into a two-pane matrix by docs/plans/done/NAV-IA-REDESIGN-PLAN.md Wave 3, §2.4,
 * docs/extracts/design/13-roster.md).
 *
 * Route-guarded (`core/org/org-guard.ts`, the same guard `/org` uses) — only ADMIN/MANAGER reach
 * this page; a PILOT following the Manage sidebar's entry is redirected to `/fly` before this
 * component ever mounts.
 *
 * **The accordion is gone.** Assignments render inline in the row — `RosterFacade.rows`/`pilotRows`
 * already carry the pilot names/asset names each row needs, so the disclosure that used to hide them
 * behind a click (docs/extracts/design/13-roster.md's own named problem: "40 assets is 40 clicks to read")
 * had nothing left to earn its place. A `By asset | By pilot` pivot (`?by=`) reads the identical
 * source data either direction — "By pilot" is what answers "what does Pilot A fly", which the
 * accordion could not answer without expanding every row. Both pivots wrap in `vision-two-pane`
 * (`?sel=`); the detail pane does the actual assign/unassign editing — `<vision-pilots-card>`
 * (unchanged, reused wholesale) for `By asset`, the new `<vision-pilot-assignments-panel>` for
 * `By pilot` — see `RosterFacade`'s own class doc comment for why there are still only two REST call
 * sites behind both.
 *
 * **`page-head` → `vision-page-bar`** (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.2, Wave 2, committed) — the
 * subtitle carries real instruction, so it stays a `hint`; the count chip and search live in the bar
 * as before, joined this wave by the pivot toggle (`[pageBarFilters]`) and a fleet-level gap
 * indicator next to it.
 *
 * **`embedded` (docs/plans/active/WAREHOUSE-UX-PLAN.md §4 wave W7).** `/manage/roster` now routes to
 * `CrewPage` (`features/roster/crew.ts`), which mounts this component as its "Roster" tab —
 * `embedded` true swaps the sticky `<vision-page-bar>` (title "Pilots / roster") for a plain
 * `.embedded-toolbar` row carrying the identical pivot toggle + search, since `Crew`'s own bar
 * already owns the page title/tab switcher directly above it. See `OrgSettingsPage`'s identical
 * `embedded` doc comment for why a second stacked sticky bar would be redundant chrome.
 */
@Component({
  selector: 'vision-roster',
  imports: [FormsModule, RouterLink, NgTemplateOutlet, PageBar, EmptyState, Stat, TwoPane, PilotsCard, PilotAssignmentsPanel],
  templateUrl: './roster.html',
  styleUrl: './roster.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [RosterFacade],
})
export class RosterPage {
  protected readonly facade = inject(RosterFacade);

  /** `true` when mounted inside `CrewPage` — see this class's own doc comment. */
  readonly embedded = input(false);

  /**
   * The "By pilot" pivot's own row state text (docs/plans/done/VISUAL-REFRESH-PLAN.md F5 — dot + plain text,
   * not a chip per assigned asset) — mirrors the "By asset" pivot's `row.pilotNames.join(', ')`,
   * which needs no helper since `RosterRow#pilotNames` is already `readonly string[]`;
   * `RosterPilotRow#assignments` carries the display name one level deeper.
   */
  protected assetNames(assignments: readonly PilotAssetAssignment[]): string {
    return assignments.map((assignment) => assignment.displayName).join(', ');
  }
}
