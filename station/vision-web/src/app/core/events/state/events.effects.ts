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
  filter,
  from,
  map,
  merge,
  mergeMap,
  of,
  switchMap,
  tap,
} from 'rxjs';
import type { DetectionEvent } from '../../api/models';
import { isLiveAvailable } from '../../live/live-fallback-logic';
import { LiveSocketActions } from '../../live/state/live.actions';
import { liveFeature } from '../../live/state/live.reducer';
import { PollScheduler } from '../../poll-scheduler';
import { SettingsFacade } from '../../settings/settings-facade';
import { VisionApi } from '../../api/vision-api';
import { eventNotificationText, shouldNotify } from '../events-logic';
import { EventsApiActions, EventsPageActions } from './events.actions';
import { eventsFeature } from './events.reducer';

/** `EventController.DEFAULT_LIMIT` — see `events-store.ts`'s identical doc comment this file inherits. */
const EVENTS_LIMIT = 50;

/** Matches `EventsStore`'s own `POLL_INTERVAL_MS` — a 5s cadence, kept unchanged. */
const POLL_INTERVAL_MS = 5_000;

/** Wraps `PollScheduler.schedule` as a cold `Observable` tick source, with `ignoreHidden: true` —
 *  see `events-store.ts`'s own doc comment on why this store is the one deliberate exception to
 *  every other poller pausing in a backgrounded tab: the point of the notification feature is to
 *  alert the user *while they are not looking*. Duplicated (module-private) per this wave's own
 *  established convention (`discovery.effects.ts#ticks$` and siblings each keep their own copy). */
function ticks$(scheduler: PollScheduler, periodMs: number): Observable<void> {
  return new Observable<void>((subscriber) => {
    const stop = scheduler.schedule(periodMs, () => subscriber.next(), { ignoreHidden: true });
    return () => stop();
  });
}

/**
 * The one shared GET — reads the *current* `sinceMs` cursor straight off the store via
 * `selectSignal`'s synchronous snapshot (this function is invoked fresh at every poll tick, so
 * there is no staleness risk the way a memoized closure variable would have), mirroring
 * `EventsStore#pollOnce`'s own `this.sinceMs` read. `EventController`'s own contract is
 * newest-first; reversed once here, matching that method's own `[...incoming].reverse()` before
 * ever reaching `applyIncoming`/the reducer. Silent-degrade on failure — the original method's bare
 * `catch {}`.
 */
function pollOnce$(store: Store, api: VisionApi): Observable<Action> {
  const sinceMs = store.selectSignal(eventsFeature.selectSinceMs)();
  return from(api.events(sinceMs, EVENTS_LIMIT)).pipe(
    map((events) => EventsApiActions.pollSucceeded({ events: [...events].reverse() })),
    catchError(() => of(EventsApiActions.pollFailed())),
  );
}

type Phase = 'idle' | 'live' | 'poll';

/**
 * The same root-singleton demand-gate pattern `discovery.effects.ts#poll$` established (this
 * wave's own trap #4) — `activeConsumers` (this slice's own state) crossed with `core/live`'s
 * connection state, read via `store.select(liveFeature.selectConnectionState)`, **never** by
 * injecting `LiveFacade` itself.
 *
 * The one deliberate difference from Discovery's identical-looking `poll$`: Discovery's `'live'`
 * phase still issues one reconcile GET (its `sources` list has no SSE-fed replacement). Events has
 * no such sidecar — `detection-events` is a genuine, complete replacement for the poll while live,
 * so `'live'` here is `EMPTY`, exactly like `'idle'`: `EventsStore#applyTransport`'s own
 * `if (liveAvailable) { this.stopPolling(); return; }` branch never re-polled either. The merged
 * live arrivals are folded straight into `state.events` by `events.reducer.ts`'s own
 * `LiveSocketActions.envelopeReceived` handler — nothing this effect needs to do.
 */
export const poll$ = createEffect(
  (store = inject(Store), api = inject(VisionApi), scheduler = inject(PollScheduler)) =>
    combineLatest([
      store.select(eventsFeature.selectActiveConsumers),
      store.select(liveFeature.selectConnectionState).pipe(map(isLiveAvailable)),
    ]).pipe(
      map(([activeConsumers, liveAvailable]): Phase => (activeConsumers === 0 ? 'idle' : liveAvailable ? 'live' : 'poll')),
      distinctUntilChanged(),
      switchMap((phase) => {
        if (phase !== 'poll') {
          return EMPTY;
        }
        return concat(pollOnce$(store, api), ticks$(scheduler, POLL_INTERVAL_MS).pipe(mergeMap(() => pollOnce$(store, api))));
      }),
    ),
  { functional: true },
);

/**
 * The side-effecting half of `EventsStore#applyIncoming` (`#maybeNotify`/`#fireNotification`) —
 * deliberately kept out of the reducer (`events.reducer.ts`'s own doc comment): `shouldNotify` reads
 * `Notification.permission` and `document.hidden`, both impure environment globals a reducer must
 * never touch. `seenIds` moves from a store instance field to this effect factory's own closure
 * `Set` — created once, alive for the app's lifetime, exactly mirroring the original singleton's own
 * field lifetime, since a functional effect's factory body runs exactly once at effects-registration
 * time (the returned `Observable` is what stays subscribed).
 *
 * Listens to both transports' own success action, mirroring `EventsStore`'s own single
 * `applyIncoming` call site reached from either `pollOnce()` or the live-arrival `effect()`: the
 * poll's `pollSucceeded` (already oldest-first) and the live topic's own
 * `LiveSocketActions.envelopeReceived` (one `DetectionEvent` per envelope, wrapped as a
 * single-element oldest-first array — see `events.reducer.ts`'s identical extraction).
 */
export const notify$ = createEffect(
  (actions$ = inject(Actions), settings = inject(SettingsFacade)) => {
    const seenIds = new Set<string>();

    const fromPoll$ = actions$.pipe(
      ofType(EventsApiActions.pollSucceeded),
      map((action) => action.events),
    );
    const fromLive$ = actions$.pipe(
      ofType(LiveSocketActions.envelopeReceived),
      map((action): readonly DetectionEvent[] => (action.envelope.type === 'detection-events' ? [action.envelope.payload] : [])),
      filter((incoming) => incoming.length > 0),
    );

    return merge(fromPoll$, fromLive$).pipe(
      tap((incoming) => {
        const permission: NotificationPermission = typeof Notification === 'undefined' ? 'denied' : Notification.permission;
        for (const event of incoming) {
          const alreadySeen = seenIds.has(event.id);
          seenIds.add(event.id);
          const notify = shouldNotify({
            event,
            alreadySeen,
            notificationsEnabled: settings.eventNotifications(),
            permission,
            documentHidden: document.hidden,
          });
          if (notify) {
            fireNotification(event);
          }
        }
      }),
    );
  },
  { functional: true, dispatch: false },
);

/** `EventsStore#fireNotification`'s own best-effort construction — some environments accept the
 *  permission grant but still throw on construction; never let this break anything else. */
function fireNotification(event: DetectionEvent): void {
  const text = eventNotificationText(event);
  try {
    new Notification(text.title, { body: text.body, tag: event.id });
  } catch {
    // Best-effort — see this function's own doc comment.
  }
}

export const eventsEffects = { poll$, notify$ };
