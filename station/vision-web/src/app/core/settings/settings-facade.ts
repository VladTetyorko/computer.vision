import { Injectable, inject, type WritableSignal } from '@angular/core';
import { Store } from '@ngrx/store';
import type { BoxesMode } from '../../shared/player/player';
import { SettingsPageActions } from './state/settings.actions';
import type { MapLayerId } from './state/settings.model';
import { settingsFeature } from './state/settings.reducer';

/** Re-exported so every consumer that used to import `MapLayerId` alongside the store class keeps
 *  importing it from the same module — only the class name changes. */
export type { MapLayerId };

/**
 * Wraps a store-backed value as something that reads exactly like Angular's own `WritableSignal`
 * (callable, `.set()`, `.update()`) but writes by dispatching — so the ~30 call sites across the app
 * that do `settings.mapLayer.set(id)` or alias the field straight onto their own signal (e.g.
 * `WallFacade.boxesMode = this.settings.declutterLevel`) need no redesign, per this wave's "one-line
 * consumer swap" mandate. The getter delegates to the real `Signal` from `store.selectSignal(...)`,
 * so reads stay fully reactive; only the identity is a facade-owned wrapper, never the `Store` itself
 * (docs/plans/done/NGRX-MIGRATION-PLAN.md §3 rule 8's actual concern).
 */
function dispatchingSignal<T>(read: () => T, write: (value: T) => void): WritableSignal<T> {
  const sig = ((): T => read()) as unknown as WritableSignal<T>;
  sig.set = write;
  sig.update = (updateFn: (value: T) => T) => write(updateFn(read()));
  sig.asReadonly = () => sig;
  return sig;
}

/**
 * The settings slice's read/dispatch boundary (docs/plans/done/NGRX-MIGRATION-PLAN.md §2). Every
 * field keeps the exact name and the exact `WritableSignal`-shaped call surface the old
 * `SettingsStore` exposed — see {@link dispatchingSignal}.
 */
@Injectable({ providedIn: 'root' })
export class SettingsFacade {
  private readonly store = inject(Store);

  readonly advancedMode = dispatchingSignal(
    this.store.selectSignal(settingsFeature.selectAdvancedMode),
    (advancedMode: boolean) => this.store.dispatch(SettingsPageActions.advancedModeSet({ advancedMode })),
  );

  readonly wallDensity = dispatchingSignal(
    this.store.selectSignal(settingsFeature.selectWallDensity),
    (wallDensity: number) => this.store.dispatch(SettingsPageActions.wallDensitySet({ wallDensity })),
  );

  readonly mapLayer = dispatchingSignal(
    this.store.selectSignal(settingsFeature.selectMapLayer),
    (mapLayer: MapLayerId) => this.store.dispatch(SettingsPageActions.mapLayerSet({ mapLayer })),
  );

  readonly eventNotifications = dispatchingSignal(
    this.store.selectSignal(settingsFeature.selectEventNotifications),
    (eventNotifications: boolean) =>
      this.store.dispatch(SettingsPageActions.eventNotificationsSet({ eventNotifications })),
  );

  readonly flyAssetId = dispatchingSignal(
    this.store.selectSignal(settingsFeature.selectFlyAssetId),
    (flyAssetId: string | null) => this.store.dispatch(SettingsPageActions.flyAssetIdSet({ flyAssetId })),
  );

  readonly declutterLevel = dispatchingSignal(
    this.store.selectSignal(settingsFeature.selectDeclutterLevel),
    (declutterLevel: BoxesMode) => this.store.dispatch(SettingsPageActions.declutterLevelSet({ declutterLevel })),
  );

  readonly cropFollowEnabled = dispatchingSignal(
    this.store.selectSignal(settingsFeature.selectCropFollowEnabled),
    (cropFollowEnabled: boolean) =>
      this.store.dispatch(SettingsPageActions.cropFollowEnabledSet({ cropFollowEnabled })),
  );
}
