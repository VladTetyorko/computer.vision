import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import type { AssignedPilot, MaintenanceKind } from '../../core/api/models';
import { primaryVehicleVerb, type VehicleVerb } from '../../core/fleet/inventory-logic';
import { assignedPilotName, custodianCandidateName } from '../../core/org/pilot-logic';
import { verdictLabel, verdictTone } from '../../core/readiness/readiness-logic';
import { assignmentRoleLabel } from '../../core/roster/roster-pivot-logic';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { EmptyState } from '../../shared/ui/empty-state';
import { KebabMenu } from '../../shared/ui/kebab-menu';
import { TwoPane } from '../../shared/ui/two-pane/two-pane';
import { pluralize } from '../../shared/ui/text-logic';
import { InventoryFacade } from './inventory-facade';
import type { VehicleRow } from './vehicles-logic';

/**
 * The Vehicles/Equipment tab's dense table + two-pane detail (docs/plans/active/WAREHOUSE-UX-PLAN.md
 * §3.3, wave W4) — a presentational component: `rows`/`connected` are inputs, every read/write goes
 * through the parent-provided {@link InventoryFacade} (injected here, *not* re-provided — the
 * facade's own doc comment: "one fetch, two views"). Non-routed child component, so `core/ui/architecture.spec.ts`'s
 * facade/`UiStore` discipline doesn't apply here (same carve-out `pilots-card.ts` already has) —
 * this file's own dialog-open flags are deliberately plain `signal()`s, mutually exclusive by
 * construction (only one dialog renders at a time, gated by which target signal is set).
 *
 * **Columns** (§3.3, extended by INVENTORY-REWORK-PLAN.md §5.1): Name · Category · Serial ·
 * Readiness (dot + verdict + **first blocker**, `VehicleRow#readinessCause`) · Custodian (a *name*,
 * from the wire — never a raw UUID) · **Location** · Inventory state chip ·
 * Firmware · Hours · Last flown · Links(n) — Readiness, Firmware, Hours, Last flown and Links all
 * render only for `connected` (Vehicles); Equipment categories are never flown or evaluated for
 * flight readiness (`GET /api/fleet/readiness` only ever carries a row for a connected-category
 * asset, per `docs/plans/active/WAREHOUSE-UX-CONTEXT.md`'s W10 finding E1 — a battery's row still
 * came back a literal `'UNKNOWN'` verdict, which is a real wire value distinct from "never
 * evaluated" and rendered as the word "Unknown" rather than the honest `'—'` an absent join gets),
 * and Equipment has no "flown" concept and its own device-link count is rarely interesting either —
 * every one of these five is hidden there rather than always showing `'—'`/a nonsensical verdict for
 * a whole tab (frontend-style's "never a column of pure dashes"). Firmware/Hours (wave W9,
 * docs/plans/active/WAREHOUSE-UX-CONTEXT.md "W8 → W9 handoff") render real values off
 * `AssetSummary#firmware`/`#totalFlightSeconds` now (`vehicles-logic.ts#firmwareLabel`/
 * `formatFlightTime`) — `'—'` only for a genuinely never-probed/never-flown asset, never a
 * whole-column absence.
 *
 * **The drawer is a triage sheet** (INVENTORY-REWORK-PLAN.md §5.3, wave W4), read top to bottom:
 * *what is this* (name, category, simulated origin) → *what state is it in* (one state chip + the
 * readiness verdict) → *the facts* (one fact grid, fixed-width muted labels, `—` for anything
 * absent) → *why not ready* (the full blocker list, `VehicleRow#readinessBlockers`, first one bold)
 * → *maintenance* (the one open record, named) → *pilots* (who may fly it) → *act* (**exactly one**
 * primary button — `core/fleet/inventory-logic.ts#primaryVehicleVerb` picks it, the rest are ghosts,
 * `Open full ›` last). It replaced two unlabelled fact groups, a raw ISO `since` timestamp, a
 * full maintenance-history list and a second "open a record" form that duplicated Ground.
 *
 * **Kebab verbs** are gated by `core/fleet/inventory-logic.ts#vehicleRowActions` through
 * `InventoryFacade#actionsFor` (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.2, wave W3) — the
 * matrix, not this template, decides. A verb this session's capabilities would have the server
 * refuse is **not rendered at all** (a pilot's/viewer's kebab is Open — plus Watch live and Fly when
 * their own capability allows — where it used to offer Issue/Ground/Retire and a 403 on click,
 * context §3 defect A); a verb that exists for this session but is momentarily impossible renders
 * disabled with its reason underneath (the shared `.kebab-item`/`.kebab-reason` primitive in
 * `styles.css`), e.g. Retire on an issued vehicle. "Issue to…"/"Ground" need form fields
 * `vision-confirm-dialog` can't hold (a pilot picker, a kind picker + summary) — both use a
 * page-local custom modal, the exact `.backdrop`/`.dialog` shape
 * `features/command/geofence-zone-dialog.ts` set (Angular's emulated view encapsulation means
 * there's no shared `.dialog` class to reuse, only the convention).
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
  protected readonly assignmentRoleLabel = assignmentRoleLabel;
  /** The one authority-aware verb matrix (`core/fleet/inventory-logic.ts#vehicleRowActions`, via the facade's own `actor`) — this template never decides for itself what may be rendered. */
  protected readonly rowActions = (row: VehicleRow) => this.facade.actionsFor(row);

  /** `displayName ?? username ?? user-list join ?? short id` — the drawer's Pilots list never prints a bare UUID. */
  protected pilotName(pilot: AssignedPilot): string {
    return assignedPilotName(pilot, this.facade.userNameById());
  }

  /**
   * Which of the drawer's action buttons is the loud one (frontend-style §6: max one primary `.btn`
   * per surface). The choice is `core/fleet/inventory-logic.ts#primaryVehicleVerb`'s, so the drawer
   * and W5's card cannot disagree; every other shown verb renders `.btn.secondary`.
   */
  protected verbClass(row: VehicleRow, verb: VehicleVerb, extra = ''): string {
    const primary = primaryVehicleVerb(this.rowActions(row)) === verb;
    return `${primary ? 'btn' : 'btn secondary'}${extra ? ` ${extra}` : ''}`;
  }

  protected readonly noun = computed(() => (this.connected() ? 'vehicle' : 'item'));

  // --- Issue to… dialog (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.5, context §3 defect E) ----

  protected readonly issueTarget = signal<VehicleRow | undefined>(undefined);
  protected readonly issueCustodianId = signal('');
  protected readonly issueLocation = signal('');
  /** The "Show everyone" disclosure — off by default, so the picker opens on the two short lists that answer the question. */
  protected readonly everyoneShown = signal(false);

  /** Assigned pilots → other pilots → everyone else, from `core/org/pilot-logic.ts#custodianPickerGroups`. */
  protected readonly issueGroups = computed(() => this.facade.custodianGroupsFor(this.issueTarget()?.asset.assetId));

  /** The name on the primary button — the dialog says who it is about to hand the aircraft to, by name. */
  protected readonly issueCustodianName = computed(() => {
    const id = this.issueCustodianId();
    return id ? custodianCandidateName(this.issueGroups(), id) : undefined;
  });

  protected openIssue(row: VehicleRow): void {
    this.issueTarget.set(row);
    this.issueCustodianId.set('');
    this.issueLocation.set('');
    this.everyoneShown.set(false);
    // The "Assigned pilots" group needs this asset's own roster; it is cached, so re-opening the
    // dialog for a row the drawer already visited costs nothing.
    void this.facade.ensurePilots(row.asset.assetId);
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
    this.issueTarget.set(undefined);
    await this.facade.issueTo(row.asset.assetId, custodianId, this.issueLocation());
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
}
