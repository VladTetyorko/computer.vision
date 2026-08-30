import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import type { MaintenanceKind } from '../../core/api/models';
import { verdictLabel, verdictTone } from '../../core/readiness/readiness-logic';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { EmptyState } from '../../shared/ui/empty-state';
import { KebabMenu } from '../../shared/ui/kebab-menu';
import { TwoPane } from '../../shared/ui/two-pane/two-pane';
import { pluralize } from '../../shared/ui/text-logic';
import { InventoryFacade } from './inventory-facade';
import { vehicleRowActions, type VehicleRow } from './vehicles-logic';

/**
 * The Vehicles/Equipment tab's dense table + two-pane detail (docs/plans/active/WAREHOUSE-UX-PLAN.md
 * §3.3, wave W4) — a presentational component: `rows`/`connected` are inputs, every read/write goes
 * through the parent-provided {@link InventoryFacade} (injected here, *not* re-provided — the
 * facade's own doc comment: "one fetch, two views"). Non-routed child component, so `core/ui/architecture.spec.ts`'s
 * facade/`UiStore` discipline doesn't apply here (same carve-out `pilots-card.ts` already has) —
 * this file's own dialog-open flags are deliberately plain `signal()`s, mutually exclusive by
 * construction (only one dialog renders at a time, gated by which target signal is set).
 *
 * **Columns** (§3.3): Name · Category · Serial · Readiness · Custodian · Inventory state chip ·
 * Firmware · Hours · Last flown · Links(n) — the last two only for `connected` (Vehicles); Equipment
 * has no "flown" concept and its own device-link count is rarely interesting, so both columns are
 * hidden there rather than always showing `'—'` for a whole tab (frontend-style's "never a column of
 * pure dashes"). Firmware/Hours (wave W9, docs/plans/active/WAREHOUSE-UX-CONTEXT.md "W8 → W9
 * handoff") render real values off `AssetSummary#firmware`/`#totalFlightSeconds` now
 * (`vehicles-logic.ts#firmwareLabel`/`formatFlightTime`) — `'—'` only for a genuinely never-probed/
 * never-flown asset, never a whole-column absence.
 *
 * **Kebab verbs** are gated by {@link vehicleRowActions} (a plain boolean set — see that function's
 * own doc comment for why this wave didn't reuse `warehouse-logic.ts`'s reasoned
 * `ActionAvailability<T>` shape): unavailable verbs are hidden, not shown-disabled, keeping the menu
 * short for the common case (an in-stock vehicle sees only Issue/Ground/Retire/Open/Fly, not six
 * rows half of them dimmed). "Issue to…"/"Ground" need form fields `vision-confirm-dialog` can't
 * hold (a pilot picker, a kind picker + summary) — both use a page-local custom modal, the exact
 * `.backdrop`/`.dialog` shape `features/command/geofence-zone-dialog.ts` set (Angular's emulated
 * view encapsulation means there's no shared `.dialog` class to reuse, only the convention).
 */
@Component({
  selector: 'vision-vehicles-table',
  imports: [FormsModule, RouterLink, KebabMenu, EmptyState, TwoPane, ConfirmDialog],
  templateUrl: './vehicles-table.html',
  styleUrl: './vehicles-table.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VehiclesTable {
  readonly rows = input.required<readonly VehicleRow[]>();
  readonly connected = input.required<boolean>();

  protected readonly facade = inject(InventoryFacade);
  protected readonly pluralize = pluralize;
  protected readonly verdictLabel = verdictLabel;
  protected readonly verdictTone = verdictTone;
  protected readonly rowActions = vehicleRowActions;

  protected readonly noun = computed(() => (this.connected() ? 'vehicle' : 'item'));

  // --- Issue to… dialog --------------------------------------------------------------------------

  protected readonly issueTarget = signal<VehicleRow | undefined>(undefined);
  protected readonly issueCustodianId = signal('');
  protected readonly issueLocation = signal('');

  protected openIssue(row: VehicleRow): void {
    this.issueTarget.set(row);
    this.issueCustodianId.set('');
    this.issueLocation.set('');
  }

  protected cancelIssue(): void {
    this.issueTarget.set(undefined);
  }

  protected async confirmIssue(): Promise<void> {
    const row = this.issueTarget();
    const custodianId = this.issueCustodianId();
    if (!row || !custodianId) {
      return;
    }
    await this.facade.issueTo(row.asset.assetId, custodianId, this.issueLocation());
    this.issueTarget.set(undefined);
  }

  // --- Ground dialog -------------------------------------------------------------------------------

  protected readonly groundTarget = signal<VehicleRow | undefined>(undefined);
  protected readonly groundKind = signal<MaintenanceKind>('GROUNDING');
  protected readonly groundSummary = signal('');

  protected openGround(row: VehicleRow): void {
    this.groundTarget.set(row);
    this.groundKind.set('GROUNDING');
    this.groundSummary.set('');
  }

  protected cancelGround(): void {
    this.groundTarget.set(undefined);
  }

  protected async confirmGround(): Promise<void> {
    const row = this.groundTarget();
    const summary = this.groundSummary().trim();
    if (!row || !summary) {
      return;
    }
    await this.facade.ground(row.asset.assetId, this.groundKind(), summary);
    this.groundTarget.set(undefined);
  }

  // --- Retire confirm --------------------------------------------------------------------------

  protected readonly retireTarget = signal<VehicleRow | undefined>(undefined);

  protected openRetire(row: VehicleRow): void {
    this.retireTarget.set(row);
  }

  protected async confirmRetire(): Promise<void> {
    const row = this.retireTarget();
    if (!row) {
      return;
    }
    await this.facade.retire(row.asset.assetId);
    this.retireTarget.set(undefined);
  }

  // --- Maintenance drawer (detail pane) — new-record form -------------------------------------

  protected readonly newRecordKind = signal<MaintenanceKind>('NOTE');
  protected readonly newRecordSummary = signal('');

  protected async openNewRecord(assetId: string): Promise<void> {
    const summary = this.newRecordSummary().trim();
    if (!summary) {
      return;
    }
    await this.facade.openMaintenanceRecord(assetId, this.newRecordKind(), summary);
    this.newRecordSummary.set('');
  }
}
