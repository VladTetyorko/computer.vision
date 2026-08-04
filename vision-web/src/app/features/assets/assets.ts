import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { operatorAssetActions, type ActionAvailability } from '../../core/fleet/warehouse-logic';
import { readPersistedString, writePersistedString } from '../../core/panel-state';
import { Icon } from '../../shared/ui/icon';
import { KebabMenu } from '../../shared/ui/kebab-menu';
import { EmptyState } from '../../shared/ui/empty-state';
import { PageBar, pluralize } from '../../shared/ui/page-bar/page-bar';
import { TwoPane } from '../../shared/ui/two-pane/two-pane';
import { AssetsFacade } from './assets-facade';
import { parseAssetViewMode, type AssetListRow, type AssetViewMode } from './assets-logic';

/** `localStorage` key for the `▤ ▦` view toggle (docs/design/04-assets.md) — one page's own key,
 *  same `vision.<page>.<field>` shape as `vision.command.railOpen`/`vision.fly.mapVisible`. */
const VIEW_MODE_KEY = 'vision.assets.viewMode';

/**
 * The Assets page (`/assets`) — asset-first list: search by name, filter by category/lifecycle/
 * streaming, one row per asset with a two-pane detail panel for triage. Split out of the old combined
 * Devices page (docs/CYCLES-PLAN.md §11's "asset-first list") once inventory got its own dedicated
 * pages for Assets and Devices. **Assets is the one home for "what I own/fly"**
 * (docs/UX-SIMPLIFY-REVIEW.md F2) — Warehouse, a two-tile launcher that briefly sat between this page
 * and `features/devices/**`, was deleted outright (`/warehouse` now redirects here); see
 * `features/hubs/nav-entries.ts`'s own class doc comment for the full before/after and
 * `features/devices/devices.ts`'s own class doc comment for what stayed behind.
 * Every asset-lifecycle action here (rename lives on the asset detail page, not this list — see
 * `operatorAssetActions`'s own doc comment for why only Archive/Restore reach this surface) is
 * unchanged from the page this was split out of, byte-for-byte in behavior.
 *
 * **Layered per docs/UI-ARCHITECTURE-PLAN.md**: every store/service injection, search/filter
 * read-model, and HTTP-backed command lives in {@link AssetsFacade}. This component is left holding
 * only the route-bound `category`/`sel` inputs (only a component can receive one), the two
 * constructor `effect()`s that forward them into the facade, the `viewMode` toggle (pure
 * template-branch state, doesn't feed any facade computed), and a couple of pure, stateless
 * label/action-list helpers with no injected dependency of their own.
 *
 * **`page-head` → `vision-page-bar`** (docs/NAV-IA-REDESIGN-PLAN.md §2.2, docs/design/04-assets.md):
 * the old subtitle — two lines ending in a prose link to `/devices` — is deleted outright rather than
 * moved to a `hint`, both because "Assets" needs no explanation and because that link was a second
 * door to a page the sidebar already lists under `⌄ Advanced` (F7). The search/category/status/
 * streaming filters and the archived toggle, previously boxed in their own `.asset-toolbar` card,
 * now project into the bar's `[pageBarFilters]` slot; `+ Add source`/`Refresh` project into
 * `[pageBarActions]`.
 *
 * **Wave 3 — two-pane (docs/NAV-IA-REDESIGN-PLAN.md §2.4, docs/design/04-assets.md)**: a dense list
 * is now the default view (F5 — a card spent 260×120 on what a row shows in 32px); the card grid
 * stays as an opt-in `▤ ▦` view, both reading `facade.assetRows()` — one projection, two renderings,
 * per the design doc's own "don't duplicate the row/card mapping" instruction. Clicking a row/card no
 * longer navigates (F6): it opens `vision-two-pane`'s detail panel, bound to `?sel=<assetId>` via the
 * facade (see `AssetsFacade`'s own doc comment). `/assets/:id` survives as the panel's own "Open
 * full ›" link, for the deep work — rename, KPIs, recent flights, pilots — a triage panel has no room
 * for.
 */
@Component({
  selector: 'vision-assets',
  imports: [FormsModule, RouterLink, Icon, PageBar, KebabMenu, EmptyState, TwoPane],
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

  /**
   * `?sel=<assetId>` (docs/NAV-IA-REDESIGN-PLAN.md §2.4) — bound the same way `category` above is
   * (`withComponentInputBinding()`, `app.config.ts`): only a component can receive a route input, so
   * the constructor `effect()` below is what forwards it into the facade, mirroring `category`'s own
   * forwarding exactly.
   */
  readonly sel = input<string | undefined>(undefined);

  protected readonly facade = inject(AssetsFacade);

  /** Bound for the template — see `pluralize`'s own doc comment (`shared/ui/page-bar/page-bar.ts`)
   *  for why this is the one place `asset(s)`/`device(s)`-style pluralisation gets fixed from. */
  protected readonly pluralize = pluralize;

  /**
   * The `▤ ▦` list/card view toggle (docs/design/04-assets.md) — pure template-branch view state,
   * same "host-owned, not the facade's" reasoning `features/devices/devices.ts`'s own (now-removed)
   * `viewMode` doc comment gave: both views read the identical `facade.assetRows()`, so this signal
   * decides nothing any computed in the facade needs to know about. Seeded from `localStorage`
   * (`readPersistedString`/`parseAssetViewMode` — the codebase's `core/panel-state.ts` idiom, not a
   * bespoke persistence scheme) so the choice survives a reload.
   */
  protected readonly viewMode = signal<AssetViewMode>(parseAssetViewMode(readPersistedString(VIEW_MODE_KEY, null)));

  protected setViewMode(mode: AssetViewMode): void {
    this.viewMode.set(mode);
    writePersistedString(VIEW_MODE_KEY, mode);
  }

  constructor() {
    // Seeds/updates the facade's category filter from the query param — re-navigating here with a
    // different ?category= (e.g. a second drill-down click) while already mounted keeps working.
    effect(() => {
      const slug = this.category();
      if (slug !== undefined) {
        this.facade.categoryFilter.set(slug);
      }
    });

    // ?sel= round-trips through the facade's own selectedId signal — unlike category above, this one
    // forwards unconditionally (including `undefined`), since "no ?sel=" is itself a meaningful state
    // (nothing selected), not "leave whatever was there before" the way an absent ?category= is.
    effect(() => {
      this.facade.selectedId.set(this.sel());
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
