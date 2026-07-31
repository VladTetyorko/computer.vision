import { ChangeDetectionStrategy, Component, effect, inject, input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { operatorAssetActions, type ActionAvailability } from '../../core/fleet/warehouse-logic';
import { KebabMenu } from '../../shared/ui/kebab-menu';
import { EmptyState } from '../../shared/ui/empty-state';
import { AssetsFacade } from './assets-facade';
import type { AssetListRow } from './assets-logic';

/**
 * The Assets page (`/assets`) — asset-first grid: search by name, filter by category/lifecycle/
 * streaming, one card per asset with exactly Watch · Open · Archive. Split out of the old combined
 * Devices page (docs/CYCLES-PLAN.md §11's "asset-first list") once inventory got its own dedicated
 * pages for Assets and Devices, Warehouse becoming a two-tile launcher between them — see
 * `features/warehouse/warehouse.ts` and `features/devices/devices.ts`'s own class doc comments.
 * Every asset-lifecycle action here (rename lives on the asset detail page, not this list — see
 * `operatorAssetActions`'s own doc comment for why only Archive/Restore reach this surface) is
 * unchanged from the page this was split out of, byte-for-byte in behavior.
 *
 * **Layered per docs/UI-ARCHITECTURE-PLAN.md**: every store/service injection, search/filter
 * read-model, and HTTP-backed command lives in {@link AssetsFacade}. This component is left holding
 * only the route-bound `category` input (only a component can receive one) and the single
 * constructor `effect()` that forwards it into the facade's `categoryFilter`, plus a couple of pure,
 * stateless label/action-list helpers with no injected dependency of their own.
 */
@Component({
  selector: 'vision-assets',
  imports: [FormsModule, RouterLink, KebabMenu, EmptyState],
  templateUrl: './assets.html',
  styleUrl: './assets.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [AssetsFacade],
})
export class AssetsPage {
  /**
   * `?category=<slug>` — pre-filters the grid to one category (docs/UX-QUICKWINS-PLAN.md QF-2/QF-3):
   * the drill-down target for the Command dashboard's readiness tiles.
   */
  readonly category = input<string | undefined>(undefined);

  protected readonly facade = inject(AssetsFacade);

  constructor() {
    // Seeds/updates the facade's category filter from the query param — re-navigating here with a
    // different ?category= (e.g. a second drill-down click) while already mounted keeps working.
    effect(() => {
      const slug = this.category();
      if (slug !== undefined) {
        this.facade.categoryFilter.set(slug);
      }
    });
  }

  protected clearFilters(): void {
    this.facade.clearFilters(this.category());
  }

  protected lifecycleLabel(state: AssetListRow['lifecycle']): string {
    switch (state) {
      case 'ACTIVE':
        return 'Active';
      case 'DEACTIVATED':
        return 'Deactivated';
      case 'DELETED':
        return 'Archived';
    }
  }

  /** The card's per-row kebab menu — just Archive/Restore, reasoned (docs/UX-REWORK-PLAN.md §U-a2 item 2). */
  protected assetActionsFor(row: AssetListRow): readonly ActionAvailability<'archive' | 'restore'>[] {
    return operatorAssetActions(row.lifecycle);
  }

  protected assetActionLabel(action: 'archive' | 'restore'): string {
    return action === 'archive' ? 'Archive asset' : 'Restore asset';
  }
}
