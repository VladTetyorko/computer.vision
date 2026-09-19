import { DestroyRef, Injectable, computed, inject } from '@angular/core';
import { Store } from '@ngrx/store';
import type { GeoPosition } from '../api/models';
import { shouldRefetchWeather } from './weather-logic';
import { WeatherPageActions } from './state/weather.actions';
import { weatherFeature } from './state/weather.reducer';

/** Module-level, monotonically increasing — guarantees two live `WeatherFacade` instances can never
 *  mint the same `hostId`, regardless of what either host is looking at (see the class doc below). */
let nextHostSequence = 0;

/**
 * Replaces `WeatherStore` (docs/plans/done/NGRX-MIGRATION-PLAN.md wave N7) — Open-Meteo wind/
 * precipitation for the go/no-go chip. `@Injectable()`, **not** `providedIn: 'root'` — unchanged
 * from the old store: `CommandPage`/`FlyPage` (`cockpit.ts`) each list this in their own `providers`,
 * so a fresh instance starts/stops with the route, exactly like `SeatFacade`, and
 * `<vision-weather-chip>` (a child of either host, injecting this facade directly) resolves to that
 * same page-scoped instance.
 *
 * <h2>Keyed by a synthetic `hostId`, not `assetId` — the trap this wave was explicitly warned about</h2>
 * Command's chip centers on the fleet centroid (no asset selected at all, often); Fly's centers on
 * the currently-flown asset. There is no key the two hosts naturally share, and reusing `assetId`
 * for one while inventing something else for the other would still risk two same-kind hosts (two
 * Command tabs, or a future split view) colliding on an accidental shared key. Instead, every
 * `WeatherFacade` instance mints its own `hostId` here, in its constructor, off a module-level
 * counter — two live instances cannot collide, by construction, regardless of what either host is
 * looking at. The underlying `weather` slice keys all of its state by that `hostId`
 * (`WeatherState#byHostId`, mirroring `SeatState#byAssetId`'s own per-host isolation) — see
 * `weather-facade.spec.ts`'s "two hosts never clobber each other" case, the spec that proves opening
 * Fly can never silently rewrite Command's own reading.
 *
 * `DestroyRef.onDestroy` releases this instance's `byHostId` entry — unlike the old component-
 * provided store's private signals (which simply vanished when Angular destroyed the injector), the
 * `weather` slice is one app-wide-registered NgRx feature, so a fresh instance minting a new `hostId`
 * on every page visit without ever releasing an old one would leak one entry per visit for the rest
 * of the SPA session.
 */
@Injectable()
export class WeatherFacade {
  private readonly store = inject(Store);
  private readonly hostId = `weather-host-${++nextHostSequence}`;
  private readonly byHostId = this.store.selectSignal(weatherFeature.selectByHostId);

  /** `undefined` means "no chip" — see class doc's "never a stale fake". */
  readonly reading = computed(() => this.byHostId()[this.hostId]?.reading);

  constructor() {
    inject(DestroyRef).onDestroy(() => this.store.dispatch(WeatherPageActions.hostReleased({ hostId: this.hostId })));
  }

  /**
   * Ensures the reading is fresh for `position` — a no-op (no dispatch at all) whenever `position`
   * is `undefined` (nothing known to center the forecast on yet), a fetch is already in flight, or
   * the existing reading is still within its cache window for essentially this same place. Safe to
   * call from an `effect()` on every tick of a fast-changing position signal.
   */
  track(position: GeoPosition | undefined): void {
    if (!position) {
      return;
    }
    const host = this.byHostId()[this.hostId];
    if (host?.inFlight) {
      return;
    }
    const now = Date.now();
    if (!shouldRefetchWeather(position, host?.lastAttemptedAtMs, host?.lastPosition, now)) {
      return;
    }
    this.store.dispatch(WeatherPageActions.trackRequested({ hostId: this.hostId, position, nowMs: now }));
  }
}
