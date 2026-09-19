import { Injectable, inject } from '@angular/core';
import { Actions } from '@ngrx/effects';
import { Store } from '@ngrx/store';
import type { GeoPosition, GeofenceZone, GeofenceZoneRequest, ZoneKind } from '../api/models';
import { dispatchAndAwait } from '../state/dispatch-bridge';
import { GeofenceApiActions, GeofencePageActions } from './state/geofence.actions';
import { geofenceFeature } from './state/geofence.reducer';

/**
 * `GeofenceStore`'s read/dispatch boundary (docs/plans/done/NGRX-MIGRATION-PLAN.md wave N6).
 * `providedIn: 'root'`, exactly like the store it replaces — zones back both Command's Zones panel
 * and Fly's read-only layer, and both should see the same list without standing up their own poller.
 *
 * **Page-provided since wave N4, not `providedIn: 'root'`** (NGRX-MIGRATION-PLAN.md §9). Every class
 * that injects this sits behind a lazy route — the four map-surface page facades and the controls
 * inside `<vision-map-tools>` (`shared/map/map-controls/**`) — so the `geofence` slice is registered by
 * each of those five routes instead of shipping in every visitor's initial bundle. **Behaviour change
 * this carries:** the slice no longer survives navigating between map surfaces, so entering
 * `/command` from `/fly` reconciles from the server rather than inheriting the previous page's copy.
 * The demand ref-count (`activate()`/`release()`) is unaffected — it always protected *concurrent*
 * consumers within one page, and one page is all that is ever mounted.
 */
@Injectable()
export class GeofenceFacade {
  private readonly store = inject(Store);
  private readonly actions$ = inject(Actions);

  readonly zones = this.store.selectSignal(geofenceFeature.selectZones);
  readonly loaded = this.store.selectSignal(geofenceFeature.selectLoaded);

  /** Registers demand — see `GeofenceStore.activate`'s original doc comment. */
  activate(): void {
    this.store.dispatch(GeofencePageActions.activated());
  }

  /** The matching teardown — call from the consumer's own `DestroyRef.onDestroy`. */
  release(): void {
    this.store.dispatch(GeofencePageActions.released());
  }

  /** Creates a zone; `null` on failure (a toast already explains why). */
  async create(request: GeofenceZoneRequest): Promise<GeofenceZone | null> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      GeofencePageActions.createRequested({ request }),
      GeofenceApiActions.createSucceeded,
      GeofenceApiActions.createFailed,
      (action) => action.zone,
      () => null,
    );
  }

  /** Inline rename — resends the zone's full body with only `name` changed. */
  async rename(zone: GeofenceZone, name: string): Promise<boolean> {
    return this.replace(zone, { name });
  }

  /** The list row's enable/disable toggle — resends the zone's full body with only `enabled` changed. */
  async setEnabled(zone: GeofenceZone, enabled: boolean): Promise<boolean> {
    return this.replace(zone, { enabled });
  }

  /** Redraws a zone's polygon/kind/altitude ceiling — see `GeofenceStore.redraw`'s original doc comment. */
  async redraw(
    zone: GeofenceZone,
    edit: { readonly kind?: ZoneKind; readonly polygon?: readonly GeoPosition[]; readonly maxAltitudeMeters?: number },
  ): Promise<boolean> {
    return this.replace(zone, edit);
  }

  private async replace(zone: GeofenceZone, edit: Partial<GeofenceZoneRequest>): Promise<boolean> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      GeofencePageActions.replaceRequested({ zone, edit }),
      GeofenceApiActions.replaceSucceeded,
      GeofenceApiActions.replaceFailed,
      () => true,
      () => false,
    );
  }

  /** Deletes `zone` immediately, then offers a 10s `Undo` that re-creates an equivalent zone (a new id). */
  async remove(zone: GeofenceZone): Promise<void> {
    await dispatchAndAwait(
      this.store,
      this.actions$,
      GeofencePageActions.removeRequested({ zone }),
      GeofenceApiActions.removeSucceeded,
      GeofenceApiActions.removeFailed,
      () => undefined,
      () => undefined,
    );
  }
}
