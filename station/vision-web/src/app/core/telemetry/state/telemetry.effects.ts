import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import type { Action } from '@ngrx/store';
import { Store } from '@ngrx/store';
import {
  EMPTY,
  Observable,
  catchError,
  concat,
  defer,
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
  takeUntil,
} from 'rxjs';
import type { AssetDetails, TelemetrySample } from '../../api/models';
import { VisionApi } from '../../api/vision-api';
import { LivePageActions } from '../../live/state/live.actions';
import { PollScheduler } from '../../poll-scheduler';
import { findOwningAsset, selectOpenUsage } from '../telemetry-logic';
import { TelemetryApiActions, TelemetryPageActions } from './telemetry.actions';
import { TELEMETRY_BACKFILL_LIMIT, TELEMETRY_POLL_INTERVAL_MS } from './telemetry.model';
import { transportSelectorFor } from './telemetry.reducer';

/** Wraps `PollScheduler.schedule` (an imperative, callback-based API) as a cold `Observable` tick
 *  source — duplicated from `seat.effects.ts`'s identical helper rather than shared, per
 *  NGRX-MIGRATION-PLAN.md §8's own "keeps wave file scopes disjoint" precedent. */
function ticks$(scheduler: PollScheduler, periodMs: number): Observable<void> {
  return new Observable<void>((subscriber) => {
    const stop = scheduler.schedule(periodMs, () => subscriber.next());
    return () => stop();
  });
}

/**
 * The live half of one `track()` session, expressed as an RxJS resource: subscribing dispatches
 * `LivePageActions.telemetryTracked`, unsubscribing dispatches `telemetryUntracked` — so the
 * ref-counted opt-in ties precisely to this pipeline's own subscription lifecycle (started only once
 * the async backfill/usage lookup below resolves, torn down by `takeUntil` on `reset()` or by the
 * outer `switchMap` on a superseded `track()`), rather than a second, separately-timed effect.
 * Dispatching an action here — never injecting `LiveFacade` directly — is what keeps this file clear
 * of the `EffectsRootModule` boot-order hazard NGRX-MIGRATION-PLAN.md §9 documents (established by
 * wave N3): an eagerly-injected cross-slice facade as a `createEffect` factory default would
 * construct that facade, running its constructor-time side effects, before its own effects group is
 * even registered.
 */
function liveTelemetrySession(store: Store, assetId: string | undefined): Observable<never> {
  if (assetId === undefined) {
    return EMPTY;
  }
  return new Observable<never>(() => {
    store.dispatch(LivePageActions.telemetryTracked({ assetId }));
    return () => store.dispatch(LivePageActions.telemetryUntracked({ assetId }));
  });
}

/**
 * The poll half — reactive to {@link transportSelectorFor}, never to `LiveFacade` directly. Skips
 * the very first immediate fetch (`index === 0`, the transport decision made right after this same
 * session's own backfill already populated `pollSamples`) but always fetches immediately on every
 * later transition — mirrors `TelemetryStore#applyTransport`'s own `immediatePoll` parameter
 * (`false` only from `startTracking`, default `true` everywhere else, e.g. a live→poll fallback
 * mid-session).
 */
function telemetryPollGate(
  store: Store,
  api: VisionApi,
  scheduler: PollScheduler,
  deviceId: string,
  usageId: string,
): Observable<Action> {
  return store.select(transportSelectorFor(deviceId)).pipe(
    distinctUntilChanged(),
    switchMap((transport, index) => {
      if (transport === 'live') {
        return EMPTY;
      }
      const immediate$ = index === 0 ? EMPTY : of(undefined);
      return merge(immediate$, ticks$(scheduler, TELEMETRY_POLL_INTERVAL_MS)).pipe(
        exhaustMap(() => pollTelemetry(api, deviceId, usageId)),
      );
    }),
  );
}

function pollTelemetry(api: VisionApi, deviceId: string, usageId: string): Observable<Action> {
  return from(api.usageTelemetry(usageId, TELEMETRY_BACKFILL_LIMIT)).pipe(
    map((samples) => TelemetryApiActions.pollReceived({ deviceId, samples })),
    catchError(() => of(TelemetryApiActions.pollFailed({ deviceId }))),
  );
}

/**
 * A device belongs to at most one asset; that asset's open usage is what's polled/subscribed to —
 * ported verbatim from `TelemetryStore#findOpenUsageId`'s own doc comment (O(1) path via
 * `getAsset(assetId)` when the caller already knows it, else the O(fleet) list-then-find). Impure
 * (issues the `VisionApi` calls itself), so it lives here rather than `telemetry-logic.ts` — the pure
 * *selection* it defers to (`selectOpenUsage`/`findOwningAsset`) stays unchanged in that file.
 */
async function findOpenUsageId(api: VisionApi, deviceId: string, assetId: string | undefined): Promise<string | undefined> {
  try {
    if (assetId !== undefined) {
      const details = await api.getAsset(assetId);
      return selectOpenUsage(details.recentUsages)?.usageId;
    }
    const summaries = await api.listAssets();
    const details = await Promise.all(summaries.map((summary) => api.getAsset(summary.assetId).catch(() => undefined)));
    const resolved = details.filter((detail): detail is AssetDetails => detail !== undefined);
    const owner = findOwningAsset(resolved, deviceId);
    return owner ? selectOpenUsage(owner.recentUsages)?.usageId : undefined;
  } catch {
    return undefined; // best-effort lookup — see `TelemetryFacade`'s class doc on silent-degrade
  }
}

/** The one-time backfill fetch — on failure, returns `[]`, the same starting state as a fresh reset. */
async function fetchBackfill(api: VisionApi, usageId: string): Promise<readonly TelemetrySample[]> {
  try {
    return await api.usageTelemetry(usageId, TELEMETRY_BACKFILL_LIMIT);
  } catch {
    return [];
  }
}

interface ResolvedSession {
  readonly usageId: string | undefined;
  readonly samples: readonly TelemetrySample[];
}

async function resolveSession(api: VisionApi, deviceId: string, assetId: string | undefined): Promise<ResolvedSession> {
  const usageId = await findOpenUsageId(api, deviceId, assetId);
  if (usageId === undefined) {
    return { usageId: undefined, samples: [] };
  }
  return { usageId, samples: await fetchBackfill(api, usageId) };
}

/**
 * One `track()` session end to end, grouped by `deviceId` so two devices' sessions never affect each
 * other (`seat.effects.ts#poll$`'s own doc comment explains why a top-level `switchMap` would be
 * wrong here). Resolves the owning asset's open usage, backfills once, then — only once that
 * resolution settles, exactly like the old store's own `startTracking` — starts the live
 * subscription (if `assetId` is given) and the transport-reactive poll gate, `concat`-ed after the
 * backfill action so the poll gate's very first `transportSelectorFor` read always observes this
 * session's own just-applied `assetId`/`backfill`, never a value from before this session existed.
 *
 * No `generation` counter, unlike the old store: a superseded `track()` (same or a different
 * `deviceId`) or a `reset()` for this device unsubscribes this whole chain via `switchMap`/
 * `takeUntil`, and RxJS drops an in-flight Promise's resolution once its subscriber is already torn
 * down — the identical "a stale async lookup is silently discarded" guarantee the old store built
 * with a manual counter, for free from the subscription lifecycle itself.
 */
export const session$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store), api = inject(VisionApi), scheduler = inject(PollScheduler)) =>
    actions$.pipe(
      ofType(TelemetryPageActions.tracked),
      groupBy((action) => action.deviceId),
      mergeMap((group$) =>
        group$.pipe(
          switchMap(({ deviceId, assetId }) =>
            defer(() => from(resolveSession(api, deviceId, assetId))).pipe(
              switchMap(({ usageId, samples }) => {
                if (usageId === undefined) {
                  return of(TelemetryApiActions.usageNotFound({ deviceId }));
                }
                return concat(
                  of(TelemetryApiActions.backfillReceived({ deviceId, usageId, samples })),
                  merge(liveTelemetrySession(store, assetId), telemetryPollGate(store, api, scheduler, deviceId, usageId)),
                );
              }),
            ),
          ),
          takeUntil(actions$.pipe(ofType(TelemetryPageActions.reset), filter((r) => r.deviceId === group$.key))),
        ),
      ),
    ),
  { functional: true },
);

export const telemetryEffects = { session$ };
