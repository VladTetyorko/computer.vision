import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EmptyState } from '../../shared/ui/empty-state';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { SectionHeader } from '../../shared/ui/section-header';
import { Stat } from '../../shared/ui/stat';
import { humanAge } from '../../core/telemetry/telemetry-logic';
import { MAINTENANCE_KIND_LABELS, hoursSinceClose, type AssetMaintenanceRow } from '../../core/maintenance/maintenance-logic';
import type { MaintenanceKind } from '../../core/api/models';
import { MaintenanceFacade } from './maintenance-facade';

/**
 * `/fleet/maintenance` — **Maintenance** (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3/§4 wave W7): fleet-wide
 * grounded/inspection-due/in-repair/retired triage, a "Ground a vehicle" action, and a recently-closed
 * history — the page `/manage/health`'s old `ComingSoon` stub was standing in for (this task's own
 * exit criterion; see `roster.routes.ts`'s own doc comment for the `hubs.routes.ts` redirect this
 * still needs from wave W4, out of this task's file scope).
 *
 * Route-guarded (`core/org/org-guard.ts`, the same guard `/manage/roster` and `/org` use) —
 * grounding/closing/releasing an asset is a manager-level action; dev-parity
 * (`vision.auth.enabled=false`) leaves the dev admin ADMIN/unbounded, so this page behaves exactly as
 * before there.
 *
 * **KPI tiles** (`MaintenanceFacade.kpis`, `core/maintenance/maintenance-logic.ts#maintenanceKpis`):
 * Grounded / Inspection due / In repair read every `MAINTENANCE`-state asset's own
 * {@link primaryOpenRecord}; Retired reads `inventoryState==='RETIRED'` directly (a stored fact, no
 * record lookup — WAREHOUSE-UX-PLAN.md §3.2 D1).
 *
 * **The open-records table** is fleet-wide despite there being no fleet-wide maintenance endpoint —
 * `MaintenanceFacade.load()` fetches per-asset records only for `MAINTENANCE`-state assets (see that
 * class's own doc comment; also flagged as a follow-up for W3 in
 * `docs/plans/active/WAREHOUSE-UX-CONTEXT.md`'s W7 row). Two independent actions per row, trusting the
 * backend's own inventory state machine rather than re-deriving it client-side: **Close** (closes just
 * this one record, `POST .../maintenance/{id}/close`) and **Release** (returns the whole asset to
 * `IN_STOCK`, `POST .../inventory {action:RELEASE}`) — mirrors `pilots-card.ts`'s precedent of no
 * confirm dialog on either.
 *
 * Empty state copy is pinned exactly by the plan: "Nothing grounded · every vehicle is in stock or in
 * the field" — shown only once assets have loaded and none are open (`MaintenanceFacade.nothingGrounded`),
 * never confused with the loading/error/no-assets-at-all states below it.
 */
@Component({
  selector: 'vision-maintenance',
  imports: [FormsModule, EmptyState, PageBar, SectionHeader, Stat],
  templateUrl: './maintenance.page.html',
  styleUrl: './maintenance.page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [MaintenanceFacade],
})
export class MaintenancePage {
  protected readonly facade = inject(MaintenanceFacade);
  protected readonly kindLabels = MAINTENANCE_KIND_LABELS;
  protected readonly groundableKinds: readonly MaintenanceKind[] = ['GROUNDING', 'INSPECTION_DUE', 'REPAIR', 'NOTE'];

  /** "Opened" column — age since `openedAt`, this app's one age vocabulary (`core/telemetry/telemetry-logic.ts`). */
  protected openedAge(row: AssetMaintenanceRow): string {
    return humanAge((Date.now() - Date.parse(row.record.openedAt)) / 1000);
  }

  /** "Recently closed" section's own age-since-close column — "—" for an unparseable timestamp, never `NaN`. */
  protected closedHoursAgo(row: AssetMaintenanceRow): string {
    const hours = hoursSinceClose(row.record, Date.now());
    return hours === undefined ? '—' : humanAge(hours * 3600);
  }
}
