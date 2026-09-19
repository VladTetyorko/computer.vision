import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { concatLatestFrom } from '@ngrx/operators';
import type { Action } from '@ngrx/store';
import { Store } from '@ngrx/store';
import {
  EMPTY,
  Observable,
  catchError,
  combineLatest,
  concat,
  distinctUntilChanged,
  exhaustMap,
  filter,
  from,
  groupBy,
  map,
  merge,
  mergeMap,
  of,
  switchMap,
} from 'rxjs';
import type { AssetSummary, TelemetrySample } from '../../api/models';
import { VisionApi } from '../../api/vision-api';
import { isLiveAvailable } from '../../live/live-fallback-logic';
import { LivePageActions, LiveSocketActions } from '../../live/state/live.actions';
import { liveFeature } from '../../live/state/live.reducer';
import { PollScheduler } from '../../poll-scheduler';
import { selectOpenUsage } from '../../telemetry/telemetry-logic';
import { bucketAssets } from '../map-logic';
import { MapApiActions, MapPageActions } from './map.actions';
import { ASSET_POLL_INTERVAL_MS, TELEMETRY_LIMIT, TELEMETRY_POLL_INTERVAL_MS } from './map.model';
import { mapFeature } from './map.reducer';

/** Wraps `PollScheduler.schedule` as a cold tick `Observable` — copied per effects file, matching
 *  `seat.effects.ts#ticks$`'s own established precedent. */
function ticks$(scheduler: PollScheduler, periodMs: number): Observable<void> {
  return new Observable<void>((subscriber) => {
    const stop = scheduler.schedule(periodMs, () => subscriber.next());
    return () => stop();
  });
}

function fetchAssets(api: VisionApi): Observable<Action> {
  return from(api.listAssets()).pipe(
    map((assets) => MapApiActions.assetsLoaded({ assets })),
    catchError(() => of(MapApiActions.assetsLoadFailed())),
  );
}

type TransportMode = 'idle' | 'live' | 'poll';

/**
 * The asset-list demand/live gate — same continuous mode derivation as every other slice's `gate$`
 * this wave (`marks.effects.ts`/`drawings.effects.ts`/`tracks.effects.ts`/`layers.effects.ts`/
 * `geofence.effects.ts`), reproducing `map-store.ts#applyTransport`'s frozen D1 table exactly:
 * entering `'live'` always fires one reconcile fetch and nothing further (subsequent updates arrive
 * off {@link fleetEnvelope$} instead); entering `'poll'` fires one fetch, then repeats every
 * {@link ASSET_POLL_INTERVAL_MS}; a same-mode re-evaluation (an equal `connectionState` write, or a
 * second concurrent `activate()`) is suppressed by `distinctUntilChanged` before it ever reaches the
 * outer `switchMap`, exactly matching the old table's two "nothing" rows.
 *
 * **This slice's `activeConsumers` ref-count is new** — `FleetMapStore` itself never had one (its own
 * class doc: "this store's demand axis is satisfied by construction, not by a ref-count", since it
 * was page-provided and a fresh instance's whole lifetime *was* the demand signal). That invariant
 * does not survive the move to NgRx: `provideEffects()`-registered effects subscribe once, at the
 * root injector, for the app's entire lifetime (`ROOT_EFFECTS_INIT`), regardless of whether
 * `MapFacade` is currently constructed. Without this ref-count, `assetGate$`/`fleetEnvelope$`/
 * `trackerLifecycle$` would run forever from boot — polling assets and opening telemetry
 * subscriptions — even if `/command` (the sole host) is never visited once. `MapFacade`'s constructor
 * dispatches {@link MapPageActions.activated} and its `DestroyRef` dispatches
 * {@link MapPageActions.released}, restoring the exact "runs only while the page is mounted"
 * behavior the old class got for free — a genuine plan-translation gap this wave found (the plan's
 * file list says only "page-provided, `@Injectable()`", without anticipating this consequence).
 */
export const assetGate$ = createEffect(
  (store = inject(Store), api = inject(VisionApi), scheduler = inject(PollScheduler)) => {
    const hasDemand$ = store.select(mapFeature.selectActiveConsumers).pipe(
      map((count) => count > 0),
      distinctUntilChanged(),
    );
    const liveAvailable$ = store.select(liveFeature.selectConnectionState).pipe(map(isLiveAvailable), distinctUntilChanged());
    const mode$ = combineLatest([hasDemand$, liveAvailable$]).pipe(
      map(([hasDemand, liveAvailable]): TransportMode => (!hasDemand ? 'idle' : liveAvailable ? 'live' : 'poll')),
      distinctUntilChanged(),
    );
    return mode$.pipe(
      switchMap((mode): Observable<Action> => {
        if (mode === 'idle') {
          return EMPTY;
        }
        if (mode === 'live') {
          return fetchAssets(api);
        }
        return concat(fetchAssets(api), ticks$(scheduler, ASSET_POLL_INTERVAL_MS).pipe(exhaustMap(() => fetchAssets(api))));
      }),
    );
  },
  { functional: true },
);

/** `CommandFacade.addTestDrone`'s escape hatch (`map-facade.ts#refresh`) — the one place this slice
 *  needs a fetch *not* driven by a demand/live transition. Reuses {@link fetchAssets}, so a caller
 *  that awaits `assetsLoaded`/`assetsLoadFailed` sees the identical settled state either transport
 *  would have produced. */
export const refresh$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(MapPageActions.refreshRequested),
      switchMap(() => fetchAssets(api)),
    ),
  { functional: true },
);

/**
 * L7a's live half: `LiveFacade.fleet()` (the `fleet` topic) carries the same `AssetSummary[]` shape
 * `GET /api/assets` does — this just re-expresses `map-store.ts`'s own "one signal, either transport
 * writes it" `effect()` as an action translation, so the reducer keeps exactly one clause
 * (`map.reducer.ts`'s `assetsLoaded`) regardless of which transport produced it.
 *
 * Gated on `activeConsumers > 0`, unlike `tracks.reducer.ts`'s identical-looking `map` envelope fold
 * (which runs unconditionally): that fold only ever *stores* a delta nothing else reacts to, whereas
 * a `fleet` envelope reaching this slice also drives {@link reconcileTrackers$} — which opens/closes
 * real `LiveFacade.trackTelemetry` subscriptions. Folding it while no one is viewing the map would
 * start those subscriptions invisibly for the app's whole lifetime, the same regression the
 * `activeConsumers` ref-count above exists to prevent.
 */
export const fleetEnvelope$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store)) =>
    actions$.pipe(
      ofType(LiveSocketActions.envelopeReceived),
      concatLatestFrom(() => store.select(mapFeature.selectActiveConsumers)),
      filter(([{ envelope }, activeConsumers]) => envelope.type === 'fleet' && activeConsumers > 0),
      map(([{ envelope }]) => MapApiActions.assetsLoaded({ assets: (envelope.payload as readonly AssetSummary[]) })),
    ),
  { functional: true },
);

/**
 * Diffs the just-loaded asset list's `streaming` bucket against the trackers this slice already
 * knows about (`map-store.ts#reconcileTrackers`, ported verbatim) — runs off *every* `assetsLoaded`
 * regardless of source (poll, one-time live-transition reconcile, `fleetEnvelope$`, or `refresh$`),
 * so it is never missed no matter which transport is current.
 */
export const reconcileTrackers$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store)) =>
    actions$.pipe(
      ofType(MapApiActions.assetsLoaded),
      concatLatestFrom(() => store.select(mapFeature.selectTrackerIds)),
      map(([{ assets }, trackerIds]) => {
        const streamingIds = new Set(bucketAssets(assets).streaming.map((asset) => asset.assetId));
        const started = [...streamingIds].filter((id) => !trackerIds.includes(id));
        const stopped = trackerIds.filter((id) => !streamingIds.has(id));
        return MapPageActions.trackersReconciled({ started, stopped });
      }),
    ),
  { functional: true },
);

type TrackerCommand = { readonly assetId: string; readonly kind: 'start' | 'stop' };

/** One `getAsset` → `selectOpenUsage` → one backfill fetch — `map-store.ts#initTracker`'s async half,
 *  ported verbatim including its own latent quirk: a streaming asset whose usage lookup finds nothing
 *  open is never retried while it keeps streaming (the tracker entry already exists, so the next
 *  reconcile never calls `startTracker` for it again) — faithfully preserved, not fixed, since this
 *  is a mechanical port, not a bug hunt (see this wave's own exit report). */
async function findOpenUsageBackfill(
  api: VisionApi,
  assetId: string,
): Promise<{ usageId: string; samples: readonly TelemetrySample[] } | undefined> {
  try {
    const details = await api.getAsset(assetId);
    const usageId = selectOpenUsage(details.recentUsages)?.usageId;
    if (usageId === undefined) {
      return undefined;
    }
    return { usageId, samples: await fetchBackfill(api, usageId) };
  } catch {
    return undefined; // best-effort — this asset's marker just falls back to lastKnownPosition
  }
}

/** The one-time backfill fetch (L7c) — on failure, leaves the trail at `[]` (live deltas alone still merge in fine). */
async function fetchBackfill(api: VisionApi, usageId: string): Promise<readonly TelemetrySample[]> {
  try {
    return await api.usageTelemetry(usageId, TELEMETRY_LIMIT);
  } catch {
    return [];
  }
}

/**
 * D1 for one tracker's telemetry, re-expressed reactively: switches to `EMPTY` the instant live
 * becomes available, or to a {@link TELEMETRY_POLL_INTERVAL_MS} tick loop the instant it isn't —
 * `map-store.ts#applyTrackerTransport`'s idempotent-either-direction table, but driven by
 * `distinctUntilChanged` + `switchMap` over the `live` slice's own connection state instead of an
 * imperative re-apply call. No leading `concat(fetchOnce, …)` — `PollScheduler.schedule` itself never
 * fires immediately (see that class's own doc comment), and the old code never did an immediate
 * fetch on *entering* poll mode here either (only the asset-level gate does that).
 */
function pollTelemetry$(assetId: string, usageId: string, store: Store, api: VisionApi, scheduler: PollScheduler): Observable<Action> {
  return store.select(liveFeature.selectConnectionState).pipe(
    map(isLiveAvailable),
    distinctUntilChanged(),
    switchMap((liveAvailable) =>
      liveAvailable
        ? EMPTY
        : ticks$(scheduler, TELEMETRY_POLL_INTERVAL_MS).pipe(
            exhaustMap(() => from(fetchBackfill(api, usageId)).pipe(map((samples) => MapApiActions.trackerPollLoaded({ assetId, samples })))),
          ),
    ),
  );
}

/**
 * One tracker's whole lifecycle (`map-store.ts#startTracker`+`initTracker`, ported): subscribes
 * (`telemetryTracked`) *before* the async usage lookup — dispatched directly, not via `LiveFacade`
 * (NGRX-MIGRATION-PLAN.md §9's own rule: never inject `LiveFacade` into an effect; `live-facade.ts`
 * confirms `trackTelemetry`/`untrackTelemetry` are themselves plain dispatch wrappers around these
 * same two actions, so this achieves the identical ref-count side effect) — then resolves the open
 * usage + backfill, then hands off to {@link pollTelemetry$} for as long as this pipeline stays
 * subscribed. A superseded 'start' (this same assetId's group replacing this whole observable via
 * `switchMap` in {@link trackerLifecycle$}) cancels everything below mid-flight, structurally — the
 * `route.effects.ts#show$` precedent this wave already used to retire `RouteStore`'s own generation
 * counter, reused here for `FleetMapStore`'s equivalent counter.
 */
function runTracker$(assetId: string, store: Store, api: VisionApi, scheduler: PollScheduler): Observable<Action> {
  const start$: Observable<Action> = of(LivePageActions.telemetryTracked({ assetId }));
  const init$: Observable<Action> = from(findOpenUsageBackfill(api, assetId)).pipe(
    switchMap((found) =>
      found === undefined
        ? EMPTY
        : concat(
            of<Action>(MapApiActions.trackerBackfillLoaded({ assetId, samples: found.samples, usageId: found.usageId })),
            pollTelemetry$(assetId, found.usageId, store, api, scheduler),
          ),
    ),
  );
  return concat(start$, init$);
}

/**
 * Every per-asset start/stop command, from two sources merged into one stream: `reconcileTrackers$`'s
 * own diff (`Trackers Reconciled`), and this slice's own idle teardown (dropping to zero consumers
 * must force-stop every still-tracked asset — `map-store.ts#teardown`'s own final loop). `groupBy
 * (assetId)` + `switchMap` per group gives exactly the two guarantees `initTracker`'s hand-rolled
 * `generation` counter existed for: a 'start' naturally cancels/replaces any still-running previous
 * pipeline for that same assetId, correctly handling both a simple stop and a stop-then-restart race
 * — structurally, not by hand-counted bookkeeping.
 *
 * The reducer deliberately does **not** eagerly clear `trackers` in its `released` clause (see
 * `map.reducer.ts`'s `activate`) — doing so would erase the very ids {@link idleStop$} below reads
 * via `concatLatestFrom`, since reducers run strictly before effects observe the same dispatch. Every
 * removal — reconcile-driven or idle-driven — instead goes through the same `'stop'` branch here,
 * which dispatches `Tracker Removed` only after the matching `untrackTelemetry`, keeping deletion
 * uniform and reducer-owned.
 */
export const trackerLifecycle$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store), api = inject(VisionApi), scheduler = inject(PollScheduler)) => {
    const reconciled$: Observable<TrackerCommand> = actions$.pipe(
      ofType(MapPageActions.trackersReconciled),
      mergeMap(({ started, stopped }) =>
        from([...started.map((assetId) => ({ assetId, kind: 'start' as const })), ...stopped.map((assetId) => ({ assetId, kind: 'stop' as const }))]),
      ),
    );
    const idleStop$: Observable<TrackerCommand> = store.select(mapFeature.selectActiveConsumers).pipe(
      map((count) => count > 0),
      distinctUntilChanged(),
      filter((hasDemand) => !hasDemand),
      concatLatestFrom(() => store.select(mapFeature.selectTrackerIds)),
      mergeMap(([, ids]) => from(ids.map((assetId) => ({ assetId, kind: 'stop' as const })))),
    );
    return merge(reconciled$, idleStop$).pipe(
      groupBy((command) => command.assetId),
      mergeMap((group$) =>
        group$.pipe(
          switchMap((command): Observable<Action> =>
            command.kind === 'stop'
              ? from([LivePageActions.telemetryUntracked({ assetId: command.assetId }), MapPageActions.trackerRemoved({ assetId: command.assetId })])
              : runTracker$(command.assetId, store, api, scheduler),
          ),
        ),
      ),
    );
  },
  { functional: true },
);

export const mapEffects = { assetGate$, refresh$, fleetEnvelope$, reconcileTrackers$, trackerLifecycle$ };
