import { ChangeDetectionStrategy, Component, effect, inject, input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CategoriesPage } from '../categories/categories';
import { DevicesPage } from '../devices/devices';
import { Notice } from '../../shared/ui/notice';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { Stat } from '../../shared/ui/stat';
import { pluralize } from '../../shared/ui/text-logic';
import { InventoryFacade } from './inventory-facade';
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
 * **KPI strip + Export**: the old Reports page's "Fleet at a glance" KPI tiles sit above the tab bar
 * (only functional section that page had — see `features/reports/reports.routes.ts`'s own doc
 * comment for what wasn't carried forward), and Export CSV — Reports' one other surviving idea —
 * is a page-bar action, a plain `<a [href]>` download following the after-action-archive pattern
 * (`VisionApi.inventoryExportUrl`/`afterActionArchiveUrl`'s own doc comment).
 *
 * **Role gate lives in the tab bar, not the route** (`/assets` itself is ungated, always has been):
 * `InventoryFacade#visibleTabs` hides Links/Categories from a pilot outright — the tab button never
 * renders, so `setTabFromQueryParam`'s own clamp is what a stale `?tab=links` bookmark meets, not a
 * route bounce to `/fly`.
 */
@Component({
  selector: 'vision-inventory',
  imports: [FormsModule, PageBar, Stat, Notice, VehiclesTable, DevicesPage, CategoriesPage],
  templateUrl: './inventory.html',
  styleUrl: './inventory.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [InventoryFacade],
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
