import { Injectable, computed, inject } from '@angular/core';
import { LiveFacade } from '../live/live-facade';
import { systemEventRows, type SystemEventRow } from './system-events-logic';

/**
 * The system-event log (docs/plans/done/SYSTEM-STATUS-PLAN.md §3.2) — a thin, `providedIn: 'root'`
 * projection of `LiveFacade.liveEvents()` through `system-events-logic.ts#systemEventRows`. Unlike
 * `core/events/events-store.ts#EventsStore` (which polls `GET /api/events` and needs its own
 * activate/release refcount + dedupe/notification state), this store owns no poll, no HTTP call, and
 * no mutable state of its own — `liveEvents()` is already a signal `LiveFacade` keeps warm for the
 * whole session (it's an always-on SSE topic, arriving whether or not anything reads it), so this is
 * a pure `computed()` with nothing to start or stop. Consumers (`shared/ui/notification-bell.ts`) can
 * simply `inject()` it, like `EventsStore`'s own already-established "root singleton, trivial
 * anywhere" precedent (see `shared/map/fleet-map.ts`'s doc comment for that phrase).
 *
 * Follows the Component → Facade → Store → Service layering (`MODULE.md`'s own Conventions section) —
 * this is the Store layer for the system-events feature; the bell is a non-routed shared component
 * (the same carve-out `shared/ui/events-rail.ts` already uses to inject `EventsStore` directly rather
 * than through a facade), and there is no Service layer here at all since nothing makes an HTTP call.
 */
@Injectable({ providedIn: 'root' })
export class SystemEventsStore {
  private readonly liveStore = inject(LiveFacade);

  /** Newest-first, `DETECTION` excluded, capped — see `systemEventRows`'s own doc comment. */
  readonly rows = computed<readonly SystemEventRow[]>(() => systemEventRows(this.liveStore.liveEvents()));
}
