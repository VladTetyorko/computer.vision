import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import type { VehicleRowActions, VehicleVerb } from '../../core/fleet/inventory-logic';
import { verdictLabel, verdictTone } from '../../core/readiness/readiness-logic';
import { EmptyState } from '../../shared/ui/empty-state';
import { InventoryFacade } from './inventory-facade';
import {
  MY_VEHICLE_VERB_LABELS,
  myVehicleActions,
  myVehicleCustodyLine,
  myVehicleMetaLine,
  myVehiclePrimaryVerb,
  myVehicleVerbList,
} from './my-vehicles-logic';
import type { VehicleRow } from './vehicles-logic';

/**
 * "My vehicles" — the Inventory page's pilot/crew view (docs/plans/active/INVENTORY-REWORK-PLAN.md
 * §5.4, wave W5). `InventoryPage` mounts this with no inputs, in the `@else` of
 * `facade.showsManagerView()` (`inventory.html`), for exactly the persona that view guards: a
 * session without `MANAGE_FLEET` whose visibility scope is `ASSIGNED_ASSETS` — the server has
 * already narrowed `GET /api/assets` to their own assigned vehicles, so every row this component
 * ever sees is one they may act on.
 *
 * **A non-routed presentational child** (the same `core/ui/architecture.spec.ts` carve-out
 * `vehicles-table.ts`/`found-devices.ts`/`pilots-card.ts` already use) — it injects `InventoryFacade`
 * directly rather than an `*Store`/`VisionApi`, sharing the instance `InventoryPage` provides; it
 * makes no API call of its own.
 *
 * **Vehicles, then Equipment** — one stacked card list, not a tab switch (unlike the manager's
 * table): a pilot's whole fleet is small enough that a second click to see the handful of batteries
 * they're also holding is friction the manager's dense table doesn't have to justify. Rows come from
 * {@link InventoryFacade.myVehicleRows}/{@link InventoryFacade.myEquipmentRows} — the *pre-view* row
 * sets (see that getter's own doc comment for why never `vehicleRows()`/`equipmentRows()`).
 *
 * **Card verbs** read `facade.actionsFor(row)` (the one authority matrix, §5.2) through
 * {@link myVehicleActions}, which layers exactly one more rule on top: a `CREW`-assigned row never
 * offers Fly (§5.4). Rendering is generic — {@link myVehicleVerbList} + {@link MY_VEHICLE_VERB_LABELS}
 * — so W2 (D4/D5) extends the fleet by adding one verb to the shared matrix and one label here, never
 * by touching this template.
 */
@Component({
  selector: 'vision-my-vehicles',
  imports: [RouterLink, EmptyState],
  templateUrl: './my-vehicles.html',
  styleUrl: './my-vehicles.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MyVehicles {
  protected readonly facade = inject(InventoryFacade);
  protected readonly verdictLabel = verdictLabel;
  protected readonly verdictTone = verdictTone;
  protected readonly verbLabels = MY_VEHICLE_VERB_LABELS;

  protected readonly vehicleRows = computed(() => this.facade.myVehicleRows());
  protected readonly equipmentRows = computed(() => this.facade.myEquipmentRows());
  protected readonly hasAny = computed(() => this.vehicleRows().length > 0 || this.equipmentRows().length > 0);

  protected custodyLine(row: VehicleRow): string {
    return myVehicleCustodyLine(row, this.facade.actor().userId);
  }

  protected metaLine(row: VehicleRow): string {
    return myVehicleMetaLine(row);
  }

  /** The card's own verbs — {@link InventoryFacade.actionsFor}'s answer, minus Fly for a CREW-assigned row. */
  protected actionsFor(row: VehicleRow): VehicleRowActions {
    return myVehicleActions(this.facade.actionsFor(row), this.facade.myRoleFor(row.asset.assetId));
  }

  protected primaryVerb(row: VehicleRow): VehicleVerb | undefined {
    return myVehiclePrimaryVerb(this.actionsFor(row));
  }

  protected verbList(row: VehicleRow): readonly VehicleVerb[] {
    return myVehicleVerbList(this.actionsFor(row));
  }

  /** Every verb this card can actually run — `open` is the title link, never reached from here. */
  protected runVerb(row: VehicleRow, verb: VehicleVerb): void {
    switch (verb) {
      case 'fly':
        this.facade.openCockpitFor(row.asset.assetId);
        return;
      case 'watchLive':
        void this.facade.watchLive(row.asset.assetId);
        return;
      case 'return':
        void this.facade.returnAsset(row.asset.assetId);
        return;
      default:
        // Nothing else is ever in `MY_VEHICLE_VERB_ORDER` yet — see that array's own doc comment for
        // where W2's `report` lands once the matrix grants it.
        return;
    }
  }
}
