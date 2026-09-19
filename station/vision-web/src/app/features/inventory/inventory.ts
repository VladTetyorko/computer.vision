import { ChangeDetectionStrategy, Component, effect, inject, input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CategoriesPage } from '../categories/categories';
import { DevicesPage } from '../devices/devices';
import { FoundDevices } from './found-devices';
import { Notice } from '../../shared/ui/notice';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { Stat } from '../../shared/ui/stat';
import { pluralize } from '../../shared/ui/text-logic';
import { DiscoveryInboxFacade } from '../../core/discovery/discovery-inbox-facade';
import { InventoryFacade } from './inventory-facade';
import { MyVehicles } from './my-vehicles';
import { VehiclesTable } from './vehicles-table';
import type { InventoryTab } from '../../core/fleet/inventory-logic';
import type { VehicleInventoryStateFilter, VehicleReadinessFilter } from './vehicles-logic';

/**
 * The Inventory page (`/assets`, docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 rule 3/§3.3, wave W4) —
 * one page, four tabs (`?tab=vehicles|equipment|links|categories`, default `vehicles`). Supersedes
 * the old `AssetsPage` (deleted this wave — its own table/filters/two-pane became the Vehicles tab,
 * `VehiclesTable`, filtered to `Category#connected` categories) and mounts `DevicesPage`/
 * `CategoriesPage` verbatim as the Links/Categories tabs' content — neither is copied, both are
 * imported and reused exactly as they already exist (each keeps its own facade; this page injects
 * only its own {@link InventoryFacade}, never reaching into either). Both take `[embedded]="true"`
 * (docs/plans/active/WAREHOUSE-UX-PLAN.md §4 wave W9), the same `CrewPage`-precedent swap of their
 * own sticky `<vision-page-bar>` for a plain inline toolbar row — this page's own bar already carries
 * the title/tab bar, so the pre-W9 double sticky header (WAREHOUSE-UX-CONTEXT.md's W4 status section)
 * is gone; see `devices.ts`/`categories.ts`'s own `embedded` doc comments.
 *
 * **The stats are the view switcher** (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.1, decision D7,
 * wave W4): the five tiles above the tab bar — Needs attention · In field · Issued · In stock ·
 * Maintenance — are buttons, and the selected one filters the table beneath them. They replaced the
 * old Reports-page "Fleet at a glance" strip (Total/Streaming/Active/Deactivated/Needs attention),
 * which occupied the widest band on the page to answer a question nobody in a hangar asks and could
 * not be acted on at all. Counts come from `InventoryFacade#viewTiles`, computed over the same
 * filtered rows the table renders. The pick survives a reload (`InventoryViewStore`).
 *
 * **Export** — Reports' one other surviving idea — is a page-bar action, a plain `<a [href]>`
 * download following the after-action-archive pattern (`VisionApi.inventoryExportUrl`/
 * `afterActionArchiveUrl`'s own doc comment). It is labelled *Export all (CSV)* because the endpoint
 * takes no filter: see `InventoryFacade#exportUrl`.
 *
 * **Manager view vs. "My vehicles"**: everything from the view row down is wrapped in
 * `InventoryFacade#showsManagerView`. A genuinely `ASSIGNED_ASSETS`-scoped pilot/crew session gets
 * the `@else` branch — `<vision-my-vehicles>` (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.4, wave
 * W5), never the manager's dense table. The page bar itself narrows for that branch too: Category,
 * More filters, Export all (CSV) and the view/stat row are manager tools and stay behind
 * `showsManagerView()`; search and Refresh are the two controls every persona gets.
 *
 * **Role gate lives in the tab bar, not the route** (`/assets` itself is ungated, always has been):
 * `InventoryFacade#visibleTabs` hides Links/Categories from a pilot outright — the tab button never
 * renders, so `setTabFromQueryParam`'s own clamp is what a stale `?tab=links` bookmark meets, not a
 * route bounce to `/fly`.
 *
 * **`<vision-found-devices>`** (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §3 P2, §11,
 * wave Z2d) — the discovery inbox's "Found devices" section, mounted with no inputs right below the
 * page bar, visible on every tab (it's a fleet-wide inbox, not vehicle/equipment-scoped) and
 * rendering nothing at all while the inbox is empty. It owns its own store injection
 * (`DiscoveryInboxStore`) and poll lifecycle entirely independently of `InventoryFacade` — see that
 * component's own class doc for why (the `architecture.spec.ts` non-routed-child carve-out).
 */
@Component({
  selector: 'vision-inventory',
  imports: [FormsModule, PageBar, Stat, Notice, VehiclesTable, MyVehicles, DevicesPage, CategoriesPage, FoundDevices],
  templateUrl: './inventory.html',
  styleUrl: './inventory.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // `DiscoveryInboxFacade` is page-provided since wave N-split — see its own doc comment; the
  // `discoveryInbox` slice `<vision-found-devices>` reads is registered by
  // `inventory.page-routes.ts`.
  providers: [InventoryFacade, DiscoveryInboxFacade],
})
export class InventoryPage {
  /** `?tab=` — bound the same way `AssetsPage`'s old `category`/`sel` inputs were
   *  (`withComponentInputBinding()`); forwarded into the facade by the constructor `effect()` below. */
  readonly tab = input<string | undefined>(undefined);

  /** `?category=<slug>` — the Categories tab's own "View" link forwards here (docs/plans/done/UX-QUICKWINS-PLAN.md
   *  QF-2/QF-3's original drill-down convention, carried over from the old `AssetsPage`). */
  readonly category = input<string | undefined>(undefined);

  /** `?sel=<assetId>` — the Vehicles/Equipment two-pane selection, mirrors `AssetsPage`'s identical input. */
  readonly sel = input<string | undefined>(undefined);

  protected readonly facade = inject(InventoryFacade);
  protected readonly pluralize = pluralize;

  constructor() {
    effect(() => {
      this.facade.setTabFromQueryParam(this.tab());
    });

    effect(() => {
      const slug = this.category();
      if (slug !== undefined) {
        this.facade.categoryFilter.set(slug);
      }
    });

    effect(() => {
      this.facade.selectedId.set(this.sel());
    });
  }

  protected selectTab(tab: InventoryTab): void {
    this.facade.selectTab(tab);
  }

  protected onInventoryStateFilterChange(value: string): void {
    this.facade.inventoryStateFilter.set(value as VehicleInventoryStateFilter);
  }

  protected onReadinessFilterChange(value: string): void {
    this.facade.readinessFilter.set(value as VehicleReadinessFilter);
  }
}
