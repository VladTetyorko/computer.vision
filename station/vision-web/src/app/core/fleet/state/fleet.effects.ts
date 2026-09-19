import { inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import type { Action } from '@ngrx/store';
import { Store } from '@ngrx/store';
import { EMPTY, Observable, catchError, concat, distinctUntilChanged, exhaustMap, from, map, merge, of, switchMap, tap } from 'rxjs';
import { describeHttpError } from '../../api-error';
import { VisionApi } from '../../api/vision-api';
import { isLiveAvailable } from '../../live/live-fallback-logic';
import { liveFeature } from '../../live/state/live.reducer';
import { PollScheduler } from '../../poll-scheduler';
import { ToastService } from '../../toast.service';
import { FLEET_POLL_INTERVAL_MS } from './fleet.model';
import { FleetApiActions, FleetPageActions } from './fleet.actions';

/** Console prefix — `FleetStore`'s own `LOG_PREFIX`, kept verbatim (this codebase has no logging
 *  service/convention, grep-verified there). */
const LOG_PREFIX = '[fleet]';

/** Wraps `PollScheduler.schedule` as a cold tick `Observable` — copied per effects file, matching
 *  `layers.effects.ts#ticks$`'s own established precedent. */
function ticks$(scheduler: PollScheduler, periodMs: number): Observable<void> {
  return new Observable<void>((subscriber) => {
    const stop = scheduler.schedule(periodMs, () => subscriber.next());
    return () => stop();
  });
}

/**
 * The one shared `GET /api/devices` + `GET /api/streams` round trip — `FleetStore.refresh`'s own
 * `Promise.all`, reused by every automatic trigger (`gate$`'s poll, a post-mutation reconcile) *and*
 * by `manualRefresh$` for the explicit "Refresh" button. `quiet` only decides whether
 * `notifyFailure$` toasts a failure; the reducer folds `refreshSucceeded`/`refreshFailed` identically
 * regardless of origin.
 */
function refreshDevicesAndStreams$(api: VisionApi, quiet: boolean): Observable<Action> {
  return from(Promise.all([api.listDevices(), api.listStreams()])).pipe(
    map(([devices, streams]) => FleetApiActions.refreshSucceeded({ devices, streams })),
    catchError((error: unknown) => of(FleetApiActions.refreshFailed({ quiet, error: describeHttpError(error) }))),
  );
}

/**
 * The devices/streams poll-vs-live gate — live axis only, no `activeConsumers` term, same posture
 * as `system-status.effects.ts#gate$`: every page reads the same fleet data (the Wall, the Devices
 * tab, Live all disagree if there were more than one poller), so there is no "nobody needs this"
 * state to ref-count, unlike `marks`/`layers`/`geofence`'s ref-counted gates.
 *
 * **Deliberately does not replicate `FleetStore`'s literal constructor order** ("unconditional
 * `refresh()`, *then* register the poll, *then* the transport effect's own first — always
 * not-live-at-boot — run, which the old `stopPollingFn !== null` guard turned into a no-op"). That
 * three-step dance existed only to get exactly one `GET` at boot before a real `EventSource` could
 * possibly have opened; the equivalent one-`GET`-at-boot behaviour falls out for free here as this
 * effect's very first `distinctUntilChanged` emission (subscribing to a `Store` selector always
 * replays the current value), with no separate "run once at construction" step to keep in sync.
 * `firstEntry` below reproduces the one behavioural nuance that ordering bought: the very first
 * fetch (whatever the connection state happens to be at boot) is **not** quiet, exactly like
 * `FleetStore`'s own unguarded `void this.refresh()` — every later re-entry into `'poll'` (a
 * live→closed fallback) is quiet, matching `applyTransport`'s own `refresh({quiet:true})`.
 *
 * `exhaustMap` on the recurring ticks is the same in-flight guard `layers.effects.ts#gate$`/
 * `discovery.effects.ts#poll$` already use in place of `PollScheduler`'s own promise-tracking
 * (bypassed here because `ticks$` hands back a plain, `void`-returning tick, not the fetch itself).
 */
export const gate$ = createEffect(
  (store = inject(Store), api = inject(VisionApi), scheduler = inject(PollScheduler)) => {
    let firstEntry = true;
    return store.select(liveFeature.selectConnectionState).pipe(
      map(isLiveAvailable),
      distinctUntilChanged(),
      switchMap((liveAvailable): Observable<Action> => {
        if (liveAvailable) {
          // Stops the poll; no reconcile fetch here — the `devices` topic (folded directly by
          // `fleet.reducer.ts`) is what actually delivers fresh data once live is open, exactly
          // like `FleetStore.applyTransport`'s own live branch never called `refresh()` either.
          return EMPTY;
        }
        const quiet = !firstEntry;
        firstEntry = false;
        return concat(refreshDevicesAndStreams$(api, quiet), ticks$(scheduler, FLEET_POLL_INTERVAL_MS).pipe(exhaustMap(() => refreshDevicesAndStreams$(api, true))));
      }),
    );
  },
  { functional: true },
);

/** `FleetStore.refresh()`'s own explicit, non-automatic path — the Devices page's "Refresh" button
 *  (`devices-facade.ts#refreshAll`), the one caller that awaits a `quiet: false` outcome. */
export const manualRefresh$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(FleetPageActions.refreshRequested),
      switchMap(({ quiet }) => refreshDevicesAndStreams$(api, quiet)),
    ),
  { functional: true },
);

/**
 * The CV model + tracker-engine rosters — both config-backed, one-shot fetches for the app's
 * lifetime, unrelated to the devices/streams poll (`FleetStore`'s own `loadModels`/`loadTrackers`
 * doc comments). Runs once, triggered by `FleetPageActions.booted()` from `FleetFacade`'s
 * constructor — see that action's own doc comment for why the devices/streams half needs no such
 * trigger while this half does (nothing else would ever re-fire these two `GET`s).
 */
export const loadRosters$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(FleetPageActions.booted),
      switchMap(() =>
        merge(
          from(api.getCvModels()).pipe(
            map((response) => FleetApiActions.modelsLoaded({ models: response.models })),
            catchError(() => of(FleetApiActions.modelsLoadFailed())),
          ),
          from(api.getCvTrackers()).pipe(
            map((response) => FleetApiActions.trackersLoaded({ trackers: response.trackers })),
            catchError(() => of(FleetApiActions.trackersLoadFailed())),
          ),
        ),
      ),
    ),
  { functional: true },
);

// --- Warehouse: device lifecycle (docs/main/CYCLES-PLAN.md §8's pinned contract) -------------------
// Every one of these mirrors `FleetStore`'s own `run()`-wrapped mutation: the mutation's own
// success/failure, immediately followed (on success) by the same `refreshDevicesAndStreams$(api,
// true)` reconcile every mutation triggered before — see `fleet.reducer.ts`'s own doc comment for
// why that stays a full re-fetch rather than an entity upsert.

export const register$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(FleetPageActions.registerRequested),
      switchMap(({ request }) =>
        from(api.registerDevice(request)).pipe(
          switchMap((device) => concat(of(FleetApiActions.registerSucceeded({ device })), refreshDevicesAndStreams$(api, true))),
          catchError((error: unknown) => of(FleetApiActions.registerFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const updateDevice$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(FleetPageActions.updateDeviceRequested),
      switchMap(({ id, edit }) =>
        from(api.updateDevice(id, edit)).pipe(
          switchMap((device) => concat(of(FleetApiActions.updateDeviceSucceeded({ device })), refreshDevicesAndStreams$(api, true))),
          catchError((error: unknown) => of(FleetApiActions.updateDeviceFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** `DEACTIVATED` on a `DELETED` device is how the pinned contract spells "restore". */
export const setDeviceState$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(FleetPageActions.setDeviceStateRequested),
      switchMap(({ id, state }) =>
        from(api.setDeviceState(id, state)).pipe(
          switchMap((device) => concat(of(FleetApiActions.setDeviceStateSucceeded({ device, state })), refreshDevicesAndStreams$(api, true))),
          catchError((error: unknown) => of(FleetApiActions.setDeviceStateFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** Soft delete (archive) — the device drops out of the default device listing afterward. */
export const deleteDevice$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(FleetPageActions.deleteDeviceRequested),
      switchMap(({ id }) =>
        from(api.deleteDevice(id)).pipe(
          switchMap((device) => concat(of(FleetApiActions.deleteDeviceSucceeded({ device })), refreshDevicesAndStreams$(api, true))),
          catchError((error: unknown) => of(FleetApiActions.deleteDeviceFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** Read-only — deliberately never folds into `devices`/`streams` (see `fleet.actions.ts`'s own doc
 *  comment); still funnelled through the same toast-on-failure rule as every mutation above. */
export const listDevicesIncludingArchived$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(FleetPageActions.listDevicesIncludingArchivedRequested),
      switchMap(() =>
        from(api.listDevices(true)).pipe(
          map((devices) => FleetApiActions.listDevicesIncludingArchivedSucceeded({ devices })),
          catchError((error: unknown) => of(FleetApiActions.listDevicesIncludingArchivedFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const start$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(FleetPageActions.startRequested),
      tap(({ deviceId, request }) => console.info(`${LOG_PREFIX} POST /api/devices/${deviceId}/stream`, { request })),
      switchMap(({ deviceId, request }) =>
        from(api.startStream(deviceId, request)).pipe(
          switchMap((result) => concat(of(FleetApiActions.startSucceeded({ result })), refreshDevicesAndStreams$(api, true))),
          catchError((error: unknown) => of(FleetApiActions.startFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** The diagnostic-only half of `FleetStore.start`'s own success branch — never a toast failure path,
 *  see `FleetApiActions.startFailed`'s own `notifyFailure$` handling for that. */
export const notifyStart$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(FleetApiActions.startSucceeded),
      tap(({ result }) => {
        console.info(`${LOG_PREFIX} stream start response`, { streamId: result.streamId, viewUrl: result.viewUrl, whepUrl: result.whepUrl });
        if (!result.viewUrl) {
          console.warn(`${LOG_PREFIX} stream ${result.streamId} started with no viewUrl — nothing to watch yet`);
          toasts.info('Stream started, but no publisher is wired — there is nothing to watch yet.');
        }
        if (!result.whepUrl) {
          console.info(`${LOG_PREFIX} stream ${result.streamId} has no whepUrl — the player will use HLS only`);
        }
      }),
    ),
  { functional: true, dispatch: false },
);

/** Live-patches a *running* stream's detection config — `features/fly/cv-control-panel.ts`'s one
 *  write path. No reconcile refresh (unlike every other mutation here): this never changes the
 *  device/stream lists, only a running stream's own config. */
export const patchStreamConfig$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(FleetPageActions.patchStreamConfigRequested),
      switchMap(({ streamId, patch }) =>
        from(api.patchStreamConfig(streamId, patch)).pipe(
          map((response) => FleetApiActions.patchStreamConfigSucceeded({ response })),
          catchError((error: unknown) => of(FleetApiActions.patchStreamConfigFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const stop$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(FleetPageActions.stopRequested),
      tap(({ streamId }) => console.info(`${LOG_PREFIX} DELETE /api/streams/${streamId}`)),
      switchMap(({ streamId }) =>
        from(api.stopStream(streamId)).pipe(
          switchMap(() => concat(of(FleetApiActions.stopSucceeded()), refreshDevicesAndStreams$(api, true))),
          catchError((error: unknown) => of(FleetApiActions.stopFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** `AssetSessionController#engage` — no reconcile refresh, matching `FleetStore.engageAsset`'s own
 *  doc comment: engaging touches neither the device nor stream list this slice tracks. */
export const engageAsset$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(FleetPageActions.engageAssetRequested),
      tap(({ assetId }) => console.info(`${LOG_PREFIX} POST /api/assets/${assetId}/session`)),
      switchMap(({ assetId }) =>
        from(api.engageAssetSession(assetId)).pipe(
          map(() => FleetApiActions.engageAssetSucceeded()),
          catchError((error: unknown) => of(FleetApiActions.engageAssetFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** `AssetSessionController#disengage` — same "no refresh" reasoning as {@link engageAsset$}. */
export const disengageAsset$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(FleetPageActions.disengageAssetRequested),
      tap(({ assetId }) => console.info(`${LOG_PREFIX} DELETE /api/assets/${assetId}/session`)),
      switchMap(({ assetId }) =>
        from(api.disengageAssetSession(assetId)).pipe(
          map(() => FleetApiActions.disengageAssetSucceeded()),
          catchError((error: unknown) => of(FleetApiActions.disengageAssetFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const simulate$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(FleetPageActions.simulateRequested),
      switchMap(({ request }) =>
        from(api.startSimulation(request)).pipe(
          switchMap((result) => concat(of(FleetApiActions.simulateSucceeded({ result })), refreshDevicesAndStreams$(api, true))),
          catchError((error: unknown) => of(FleetApiActions.simulateFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** Idempotent — stops a simulated asset's stream (and, for `transport=rtsp`, its feed). */
export const stopSimulation$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(FleetPageActions.stopSimulationRequested),
      switchMap(({ assetId }) =>
        from(api.stopSimulation(assetId)).pipe(
          switchMap(() => concat(of(FleetApiActions.stopSimulationSucceeded()), refreshDevicesAndStreams$(api, true))),
          catchError((error: unknown) => of(FleetApiActions.stopSimulationFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

// --- Warehouse: asset lifecycle (docs/main/CYCLES-PLAN.md §8's pinned contract) ---------------------

export const updateAsset$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(FleetPageActions.updateAssetRequested),
      switchMap(({ id, edit }) =>
        from(api.updateAsset(id, edit)).pipe(
          switchMap((asset) => concat(of(FleetApiActions.updateAssetSucceeded({ asset })), refreshDevicesAndStreams$(api, true))),
          catchError((error: unknown) => of(FleetApiActions.updateAssetFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** `DEACTIVATED` on a `DELETED` asset is how the pinned contract spells "restore". */
export const setAssetState$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(FleetPageActions.setAssetStateRequested),
      switchMap(({ id, state }) =>
        from(api.setAssetState(id, state)).pipe(
          switchMap((asset) => concat(of(FleetApiActions.setAssetStateSucceeded({ asset, state })), refreshDevicesAndStreams$(api, true))),
          catchError((error: unknown) => of(FleetApiActions.setAssetStateFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** Soft delete (archive) — the success toast names what survived (docs/main/CYCLES-PLAN.md §8). */
export const deleteAsset$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(FleetPageActions.deleteAssetRequested),
      switchMap(({ id }) =>
        from(api.deleteAsset(id)).pipe(
          switchMap((result) => concat(of(FleetApiActions.deleteAssetSucceeded({ result })), refreshDevicesAndStreams$(api, true))),
          catchError((error: unknown) => of(FleetApiActions.deleteAssetFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** Read-only — mirrors `listDevicesIncludingArchived$`: backs the warehouse asset section's "show
 *  archived" toggle without touching any signal Wall/Live read from. */
export const listAssetsIncludingArchived$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(FleetPageActions.listAssetsIncludingArchivedRequested),
      switchMap(() =>
        from(api.listAssets(true)).pipe(
          map((assets) => FleetApiActions.listAssetsIncludingArchivedSucceeded({ assets })),
          catchError((error: unknown) => of(FleetApiActions.listAssetsIncludingArchivedFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** Assigns an unowned device to an asset. A device-already-owned 409 gets a specific message —
 *  `FleetStore.assignDevice`'s own direct `HttpErrorResponse` check, ported into the effect's
 *  `catchError` since that is where every other mutation's error classification already lives. */
export const assignDevice$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(FleetPageActions.assignDeviceRequested),
      switchMap(({ assetId, deviceId }) =>
        from(api.assignDevice(assetId, deviceId)).pipe(
          switchMap((asset) => concat(of(FleetApiActions.assignDeviceSucceeded({ asset })), refreshDevicesAndStreams$(api, true))),
          catchError((error: unknown) =>
            of(
              FleetApiActions.assignDeviceFailed({
                error:
                  error instanceof HttpErrorResponse && error.status === 409
                    ? 'That device already belongs to another asset — unassign it there first.'
                    : describeHttpError(error),
              }),
            ),
          ),
        ),
      ),
    ),
  { functional: true },
);

/** Unassigns a device from an asset. The ≥1-device invariant's 409 gets its own explanation —
 *  `describeHttpError`'s generic conflict sentence doesn't know *why* (docs/main/CYCLES-PLAN.md §8). */
export const unassignDevice$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(FleetPageActions.unassignDeviceRequested),
      switchMap(({ assetId, deviceId }) =>
        from(api.unassignDevice(assetId, deviceId)).pipe(
          switchMap((asset) => concat(of(FleetApiActions.unassignDeviceSucceeded({ asset })), refreshDevicesAndStreams$(api, true))),
          catchError((error: unknown) =>
            of(
              FleetApiActions.unassignDeviceFailed({
                error:
                  error instanceof HttpErrorResponse && error.status === 409
                    ? "Can't unassign — every asset needs at least one device. Assign a replacement first."
                    : describeHttpError(error),
              }),
            ),
          ),
        ),
      ),
    ),
  { functional: true },
);

/** `refresh({quiet:true})`'s own silent-degrade rule — a background poll/live-fallback/reconcile
 *  failure never toasts, only the explicit "Refresh" button (and the very first boot fetch,
 *  `fleet.effects.ts#gate$`'s own `firstEntry`) does. */
export const notifyRefreshFailure$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(FleetApiActions.refreshFailed),
      tap(({ quiet, error }) => {
        console.warn(`${LOG_PREFIX} could not read devices/streams`, { error });
        if (!quiet) {
          toasts.error(error);
        }
      }),
    ),
  { functional: true, dispatch: false },
);

/**
 * One toast per genuine mutation failure — `FleetStore.run`'s own centralised catch, generalised
 * across every mutation above. `modelsLoadFailed`/`trackersLoadFailed` are deliberately excluded —
 * background roster enrichment never toasts, matching their old `console.warn`-only degrade.
 */
export const notifyFailure$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(
        FleetApiActions.registerFailed,
        FleetApiActions.updateDeviceFailed,
        FleetApiActions.setDeviceStateFailed,
        FleetApiActions.deleteDeviceFailed,
        FleetApiActions.listDevicesIncludingArchivedFailed,
        FleetApiActions.startFailed,
        FleetApiActions.patchStreamConfigFailed,
        FleetApiActions.stopFailed,
        FleetApiActions.engageAssetFailed,
        FleetApiActions.disengageAssetFailed,
        FleetApiActions.simulateFailed,
        FleetApiActions.stopSimulationFailed,
        FleetApiActions.updateAssetFailed,
        FleetApiActions.setAssetStateFailed,
        FleetApiActions.deleteAssetFailed,
        FleetApiActions.listAssetsIncludingArchivedFailed,
        FleetApiActions.assignDeviceFailed,
        FleetApiActions.unassignDeviceFailed,
      ),
      tap((action) => {
        console.warn(`${LOG_PREFIX} action failed`, { error: action.error });
        toasts.error(action.error);
      }),
    ),
  { functional: true, dispatch: false },
);

/** One toast per genuine success whose text depends on the mutation's own result — the rest of
 *  `FleetStore.run`'s callers (`register`/`updateDevice`/etc.) each fired their own `toasts.ok(...)`
 *  inline; centralised here since only effects may own a side effect (NGRX-MIGRATION-PLAN.md §3 rule 6). */
export const notifySuccess$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    merge(
      actions$.pipe(ofType(FleetApiActions.registerSucceeded), tap(({ device }) => toasts.ok(`Registered ${device.name}.`))),
      actions$.pipe(ofType(FleetApiActions.updateDeviceSucceeded), tap(({ device }) => toasts.ok(`Updated ${device.name}.`))),
      actions$.pipe(
        ofType(FleetApiActions.setDeviceStateSucceeded),
        tap(({ device, state }) => toasts.ok(`${device.name} is now ${state.toLowerCase()}.`)),
      ),
      actions$.pipe(ofType(FleetApiActions.deleteDeviceSucceeded), tap(({ device }) => toasts.ok(`Archived ${device.name}.`))),
      actions$.pipe(ofType(FleetApiActions.updateAssetSucceeded), tap(({ asset }) => toasts.ok(`Updated ${asset.displayName}.`))),
      actions$.pipe(
        ofType(FleetApiActions.setAssetStateSucceeded),
        tap(({ asset, state }) => toasts.ok(`${asset.displayName} is now ${state.toLowerCase()}.`)),
      ),
      actions$.pipe(
        ofType(FleetApiActions.deleteAssetSucceeded),
        tap(({ result }) =>
          toasts.ok(
            `Archived ${result.displayName} — ${result.devicesDeleted} device(s) archived, ` +
              `${result.usagesRetained} usage(s) retained, ${result.streamsStopped} stream(s) stopped.`,
          ),
        ),
      ),
      actions$.pipe(ofType(FleetApiActions.assignDeviceSucceeded), tap(({ asset }) => toasts.ok(`Assigned the device to ${asset.displayName}.`))),
      actions$.pipe(ofType(FleetApiActions.unassignDeviceSucceeded), tap(({ asset }) => toasts.ok(`Unassigned the device from ${asset.displayName}.`))),
    ),
  { functional: true, dispatch: false },
);

export const fleetEffects = {
  gate$,
  manualRefresh$,
  loadRosters$,
  register$,
  updateDevice$,
  setDeviceState$,
  deleteDevice$,
  listDevicesIncludingArchived$,
  start$,
  notifyStart$,
  patchStreamConfig$,
  stop$,
  engageAsset$,
  disengageAsset$,
  simulate$,
  stopSimulation$,
  updateAsset$,
  setAssetState$,
  deleteAsset$,
  listAssetsIncludingArchived$,
  assignDevice$,
  unassignDevice$,
  notifyRefreshFailure$,
  notifyFailure$,
  notifySuccess$,
};
