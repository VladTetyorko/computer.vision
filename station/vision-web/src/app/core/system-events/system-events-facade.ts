import { Injectable, computed, inject } from '@angular/core';
import { LiveFacade } from '../live/live-facade';
import { systemEventRows, type SystemEventRow } from './system-events-logic';

/**
 * The system-event log (docs/plans/done/SYSTEM-STATUS-PLAN.md §3.2) — a thin, `providedIn: 'root'`
 * projection of `LiveFacade.liveEvents()` through `system-events-logic.ts#systemEventRows`.
 *
 * <h2>Why this carries no NgRx slice of its own (wave N4b judgement call)</h2>
 * NGRX-MIGRATION-PLAN.md §4's table lists `core/system-events/` alongside `core/fleet/` and
 * `core/system-status/` as "3 slices" for wave N4 — that row is corrected here. This class owns
 * **no state, no HTTP call, and dispatches no action**: `liveEvents()` is already a `Signal` the
 * `live` NgRx slice keeps warm for the whole session (an always-on SSE topic, arriving whether or
 * not anything reads it — see `live.reducer.ts`'s own `event` case), so this is a pure `computed()`
 * with nothing for a second slice to hold. Giving it its own feature would mean a reducer with no
 * reachable action of its own (nothing this class does is ever dispatched) and a facade that just
 * re-derives what `LiveFacade` already computed — ceremony, not architecture. This mirrors
 * `FleetFacade.streamFor`/`.device()` and `core/map/map-facade.ts#resolveWatchDevice`: a facade may
 * freely derive from, or pass through to, another already-NgRx-backed facade without owning a slice
 * itself; only a *reducer* needs one. Renamed `SystemEventsStore` → `SystemEventsFacade` for the
 * plan's own naming convention (every `*Store` becomes a `*Facade`) even though, unusually among
 * this wave's three, its constructor body is unchanged — the rename is the only migration this class
 * needed.
 *
 * Consumers (`shared/ui/notification-bell.ts`, `features/system-status/system-status-facade.ts`) can
 * simply `inject()` it, like `EventsFacade`'s own already-established "root singleton, trivial
 * anywhere" precedent.
 */
@Injectable({ providedIn: 'root' })
export class SystemEventsFacade {
  private readonly live = inject(LiveFacade);

  /** Newest-first, `DETECTION` excluded, capped — see `systemEventRows`'s own doc comment. */
  readonly rows = computed<readonly SystemEventRow[]>(() => systemEventRows(this.live.liveEvents()));
}
