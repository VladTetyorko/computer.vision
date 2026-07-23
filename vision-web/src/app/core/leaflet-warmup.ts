import { Injectable } from '@angular/core';

interface IdleWindow {
  requestIdleCallback?: (callback: () => void, options?: { timeout: number }) => number;
}

/** Give first paint, the initial fleet fetch, and idle route preloading a clear run first. */
const IDLE_TIMEOUT_MS = 4_000;

/**
 * Warms the Leaflet chunk on browser idle (docs/CYCLES-PLAN.md §9, CU-b item 2), so the first
 * `/map` visit or telemetry-capable `/live/:deviceId` view doesn't pay Leaflet's ~38 kB gz
 * fetch+parse cost on click. `App` calls `schedule()` once, from `afterNextRender`.
 *
 * A plain **dynamic** `import('leaflet')` — exactly what every host component's own `initMap()`
 * already does via `ui/leaflet-loader.ts#importLeaflet` — so this changes only *when* the chunk is
 * fetched, never *whether*: it stays out of the initial bundle either way, and a browser that
 * never visits `/map` or a telemetry-capable device still only pays for it once idle time exists.
 *
 * Deliberately its own idle callback rather than reusing `IdlePreload` (`core/idle-preload.ts`):
 * that class implements Angular's `PreloadingStrategy` and only knows how to warm *route* chunks.
 * Leaflet isn't a route — it's a shared dependency straddling two of them (`live`, `map`) — so
 * warming it needs a plain dynamic import, not a router API.
 */
@Injectable({ providedIn: 'root' })
export class LeafletWarmup {
  private scheduled = false;

  /** Idempotent — safe to call more than once; only the first call schedules anything. */
  schedule(): void {
    if (this.scheduled) {
      return;
    }
    this.scheduled = true;

    const run = () => void import('leaflet');
    const idle = window as unknown as IdleWindow;
    if (idle.requestIdleCallback) {
      idle.requestIdleCallback(run, { timeout: IDLE_TIMEOUT_MS });
    } else {
      setTimeout(run, IDLE_TIMEOUT_MS);
    }
  }
}
