import { Injectable } from '@angular/core';
import {
  parseInventoryView,
  serializeInventoryView,
  type InventoryView,
  type InventoryViewSelection,
} from './inventory-page-logic';

/** One key, one value — the last view row this browser had selected on `/assets`. */
const INVENTORY_VIEW_KEY = 'vision.inventory.view';

/**
 * The Inventory view row's per-browser memory (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.1,
 * wave W4) — "last view persists per browser", the same single-string-preference shape
 * `core/shell/state/theme.effects.ts` uses, kept beside the page it belongs to rather than in
 * `core/` because nothing outside `features/inventory/**` has any use for it.
 *
 * **Reads and writes are wrapped, not assumed.** The theme slice goes through
 * `core/panel-state.ts#readPersistedString`, which touches `localStorage` bare; that is safe enough
 * for a value the app re-derives on every boot, but `localStorage` genuinely throws — a Safari
 * private window, a browser configured to block site data, a page opened from a `file://` origin —
 * and a throw here would take the whole Inventory page down with it. A blocked browser simply gets
 * no memory: {@link read} answers `undefined` ("never chosen"), and the page falls back to §5.1's
 * own default view exactly as a first-time visitor does.
 *
 * The stored value is three-valued on purpose — see `InventoryViewSelection`: an explicit "All" is
 * written as a real value, so deselecting a view survives a reload instead of being undone by the
 * Needs-attention default on the next boot.
 *
 * <h2>Why this is not an NgRx slice (wave N8 judgement call)</h2>
 * It was named `InventoryViewStore` until docs/plans/active/NGRX-MIGRATION-PLAN.md wave N8, which is
 * what made it look like the migration had missed one. **It owns no state at all** — two methods, no
 * signals, no fetch, no timer, no toast. The state it guards (`InventoryFacade#viewSelection`) is one
 * enum among roughly twenty plain page signals on that facade, none of which N8 converts; promoting
 * exactly one of them to a feature slice would be arbitrary, and the class that remained would still
 * be this `localStorage` wrapper, because `core/state/hydration.ts#StateHydrator.read()` would have
 * to call something exactly like it. So the *rename* is the migration: this is the storage adapter a
 * hydrator would use, not a store, and it is now named for what it is. Same reasoning that left
 * `core/system-events/` without a slice in wave N4b, and the same rule as `core/ui/ui-store.ts`
 * (NGRX-MIGRATION-PLAN.md §8: deliberately plain, must not become a slice).
 *
 * If the Inventory page ever does grow a real slice, this is the piece that becomes its
 * `StateHydrator` — and note that `hydrationMetaReducer`'s `UPDATE` branch already supports a
 * page-scoped slice hydrating, a path nothing exercises yet.
 */
@Injectable({ providedIn: 'root' })
export class InventoryViewStorage {
  /** The remembered view, `null` for an explicit All, `undefined` when nothing was ever stored (or storage is unreadable). */
  read(): InventoryViewSelection {
    try {
      return parseInventoryView(localStorage.getItem(INVENTORY_VIEW_KEY));
    } catch {
      return undefined;
    }
  }

  /** Remembers an operator's own pick. A storage failure is silently nothing-remembered, never an error the page shows. */
  write(view: InventoryView | null): void {
    try {
      localStorage.setItem(INVENTORY_VIEW_KEY, serializeInventoryView(view));
    } catch {
      // No memory this session. The view itself already changed in the signal; persistence is the
      // convenience half, and a browser that refuses site data is not something to report at all.
    }
  }
}
