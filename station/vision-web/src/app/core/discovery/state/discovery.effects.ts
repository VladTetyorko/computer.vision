import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import type { Action } from '@ngrx/store';
import { Store } from '@ngrx/store';
import {
  EMPTY,
  Observable,
  catchError,
  combineLatest,
  concat,
  distinctUntilChanged,
  from,
  map,
  mergeMap,
  of,
  switchMap,
  tap,
} from 'rxjs';
import { VisionApi } from '../../api/vision-api';
import { describeHttpError } from '../../api-error';
import { isLiveAvailable } from '../../live/live-fallback-logic';
import { liveFeature } from '../../live/state/live.reducer';
import { PollScheduler } from '../../poll-scheduler';
import { ToastService } from '../../toast.service';
import { DiscoveryApiActions, DiscoveryPageActions } from './discovery.actions';
import { discoveryInboxFeature } from './discovery.reducer';

/** Matches the backend sweep's own cadence — see `discovery-inbox-store.ts`'s identical
 *  `POLL_INTERVAL_MS` doc comment for the full D1/L2b reasoning this file inherits unchanged. */
const POLL_INTERVAL_MS = 30_000;

/** Wraps `PollScheduler.schedule` as a cold `Observable` tick source — see `geo.effects.ts#ticks$`'s identical helper. */
function ticks$(scheduler: PollScheduler, periodMs: number): Observable<void> {
  return new Observable<void>((subscriber) => {
    const stop = scheduler.schedule(periodMs, () => subscriber.next());
    return () => stop();
  });
}

/** The one shared GET, reused by the phase-driven poll below and by a successful `register()`'s own
 *  "await this.refresh()" (`DiscoveryInboxStore#register`'s own doc comment) — silent-degrade on
 *  failure, mirroring that class's `refresh()` exactly. */
function pollOnce$(api: VisionApi): Observable<Action> {
  return concat(
    of(DiscoveryApiActions.pollStarted()),
    from(api.listDiscoveryInboxCandidates()).pipe(
      map((inbox) => DiscoveryApiActions.pollSucceeded({ candidates: inbox.candidates, sources: inbox.sources })),
      catchError(() => of(DiscoveryApiActions.pollFailed())),
    ),
  );
}

type Phase = 'idle' | 'live' | 'poll';

/**
 * Root-singleton demand gate (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N7's own trap #3/#4) —
 * replaces `DiscoveryInboxStore`'s `activate()`/`release()` + reconnect-driven `applyTransport`
 * pair with one derived "phase" stream: `activeConsumers` (this slice's own state, incremented by
 * `DiscoveryPageActions.activated`/`released`) crossed with `core/live`'s own connection state, read
 * here via `store.select(liveFeature.selectConnectionState)` — **never** by injecting `LiveFacade`
 * itself (the exact thing this wave's own trap #4 warns against: a facade may inject another
 * facade, but an `@ngrx/effects` class must only ever cross-slice-select).
 *
 * `distinctUntilChanged` on the derived phase, then `switchMap`, gives the identical state-machine
 * `DiscoveryInboxStore#applyTransport`'s own doc comment tables out, for free:
 *  - `'idle'` (`activeConsumers === 0`) → `EMPTY`, i.e. nothing — `switchMap` tears down whichever
 *    inner observable (a ticking poll, or nothing) was running before, exactly as `stopPolling()` did.
 *  - `'live'` → one reconcile GET, no recurring timer — the same one-shot refresh
 *    `DiscoveryInboxStore#applyTransport`'s `if (!this.liveGated) { … void this.refresh(); }` branch
 *    performed, now simply "entering this branch of the switchMap" (no `liveGated` flag needed: a
 *    stream that only re-enters `'live'` on a genuine transition, thanks to `distinctUntilChanged`,
 *    needs no memory of its own).
 *  - `'poll'` → the same one-shot GET, plus a recurring `ticks$` tail — `switchMap` cancels this
 *    tail immediately on the next phase change, which is exactly `stopPollingFn?.()`.
 *
 * `sources` is refreshed by this effect alone — the `discovery` SSE topic carries only candidate
 * deltas (`discovery.reducer.ts`'s own doc comment) — so the one-shot GET on every `'poll'`/`'live'`
 * entry (not just `'poll'`'s recurring tail) is what keeps `sources` from going stale forever once
 * live holds, matching `discovery-inbox-store.spec.ts`'s frozen "reconciles exactly once on
 * reconnect" acceptance criterion.
 */
export const poll$ = createEffect(
  (store = inject(Store), api = inject(VisionApi), scheduler = inject(PollScheduler)) =>
    combineLatest([
      store.select(discoveryInboxFeature.selectActiveConsumers),
      store.select(liveFeature.selectConnectionState).pipe(map(isLiveAvailable)),
    ]).pipe(
      map(([activeConsumers, liveAvailable]): Phase => (activeConsumers === 0 ? 'idle' : liveAvailable ? 'live' : 'poll')),
      distinctUntilChanged(),
      switchMap((phase) => {
        if (phase === 'idle') {
          return EMPTY;
        }
        if (phase === 'live') {
          return pollOnce$(api);
        }
        return concat(pollOnce$(api), ticks$(scheduler, POLL_INTERVAL_MS).pipe(mergeMap(() => pollOnce$(api))));
      }),
    ),
  { functional: true },
);

/** `DiscoveryInboxFacade#refresh`'s own forced immediate re-read — independent of `poll$`'s own
 *  activeConsumers/live gating, mirrors `DiscoveryInboxStore#refresh`'s identical callable-anytime shape. */
export const refresh$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(DiscoveryPageActions.refreshRequested),
      mergeMap(() => pollOnce$(api)),
    ),
  { functional: true },
);

export const register$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(DiscoveryPageActions.registerRequested),
      mergeMap(({ id, request }) =>
        from(api.registerDiscoveryCandidate(id, request)).pipe(
          switchMap((result) => concat(of(DiscoveryApiActions.registerSucceeded({ id, result })), pollOnce$(api))),
          catchError((error: unknown) => of(DiscoveryApiActions.registerFailed({ id, error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** The legacy two-step path — see `discovery.actions.ts#attachRequested`'s own doc comment on why
 *  this is kept despite having no current caller. */
export const attach$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(DiscoveryPageActions.attachRequested),
      mergeMap(({ id, deviceSpec, assetId }) =>
        from(api.registerDevice(deviceSpec)).pipe(
          switchMap((device) => from(api.assignDevice(assetId, device.id))),
          map((asset) =>
            DiscoveryApiActions.attachSucceeded({ id, deviceName: deviceSpec.name, displayName: asset.displayName }),
          ),
          catchError((error: unknown) => of(DiscoveryApiActions.attachFailed({ id, error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const attachCandidate$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(DiscoveryPageActions.attachCandidateRequested),
      mergeMap(({ id, assetId }) =>
        from(api.attachDiscoveryCandidate(id, { assetId })).pipe(
          map((result) => DiscoveryApiActions.attachCandidateSucceeded({ id, result })),
          catchError((error: unknown) =>
            of(DiscoveryApiActions.attachCandidateFailed({ id, error: describeHttpError(error) })),
          ),
        ),
      ),
    ),
  { functional: true },
);

export const dismiss$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(DiscoveryPageActions.dismissRequested),
      mergeMap(({ id }) =>
        from(api.dismissDiscoveryCandidate(id)).pipe(
          map((result) => DiscoveryApiActions.dismissSucceeded({ id, result })),
          catchError((error: unknown) => of(DiscoveryApiActions.dismissFailed({ id, error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const restore$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(DiscoveryPageActions.restoreRequested),
      mergeMap(({ id }) =>
        from(api.restoreDiscoveryCandidate(id)).pipe(
          map((result) => DiscoveryApiActions.restoreSucceeded({ id, result })),
          catchError((error: unknown) => of(DiscoveryApiActions.restoreFailed({ id, error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** Every mutation is operator-initiated, unlike the background poll's own silent-degrade — mirrors
 *  `DiscoveryInboxStore#run`'s own direct `this.toasts.error(...)` call, moved to the one shared seam
 *  (`links.effects.ts#notifyFailure$`'s established `tap`-based pattern). */
export const notifyFailure$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(
        DiscoveryApiActions.registerFailed,
        DiscoveryApiActions.attachFailed,
        DiscoveryApiActions.attachCandidateFailed,
        DiscoveryApiActions.dismissFailed,
        DiscoveryApiActions.restoreFailed,
      ),
      tap((action) => toasts.error(action.error)),
    ),
  { functional: true, dispatch: false },
);

/** `register`/`attach`/`attachCandidate` each surface exactly one success toast — mirrors
 *  `DiscoveryInboxStore#register`/`#attach`/`#attachCandidate`'s own direct `this.toasts.ok(...)`
 *  calls. `dismiss`/`restore` never toasted in the original class (no confirm — "reversible in
 *  spirit", per that class's own doc comment) and still don't here. */
export const notifySuccess$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(DiscoveryApiActions.registerSucceeded),
      tap((action) => toasts.ok(`Added "${action.result.displayName}" to inventory.`)),
    ),
  { functional: true, dispatch: false },
);

export const notifyAttachSuccess$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(DiscoveryApiActions.attachSucceeded),
      tap((action) => toasts.ok(`Attached "${action.deviceName}" to "${action.displayName}".`)),
    ),
  { functional: true, dispatch: false },
);

export const notifyAttachCandidateSuccess$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(DiscoveryApiActions.attachCandidateSucceeded),
      tap((action) => toasts.ok(`Attached "${action.result.name}" to the asset.`)),
    ),
  { functional: true, dispatch: false },
);

export const discoveryEffects = {
  poll$,
  refresh$,
  register$,
  attach$,
  attachCandidate$,
  dismiss$,
  restore$,
  notifyFailure$,
  notifySuccess$,
  notifyAttachSuccess$,
  notifyAttachCandidateSuccess$,
};
