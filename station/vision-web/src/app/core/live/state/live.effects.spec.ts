import { TestBed } from '@angular/core/testing';
import { provideMockActions } from '@ngrx/effects/testing';
import { Store, provideState, provideStore } from '@ngrx/store';
import type { Action } from '@ngrx/store';
import { Observable, ReplaySubject } from 'rxjs';
import type { Subscriber } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { MeResponse } from '../../api/models';
import { VisionApi } from '../../api/vision-api';
import { AuthApiActions } from '../../auth/state/auth.actions';
import { SSE_RETRY_INTERVAL_MS, telemetryTopic } from '../live-fallback-logic';
import { LiveGateway, type LiveGatewayEvent } from '../live-gateway';
import { LiveApiActions, LivePageActions, LiveSocketActions } from './live.actions';
import { connection$, patchOnTrack$, patchOnUntrack$, reconnectOnSession$, stopOnLogout$ } from './live.effects';
import { liveFeature } from './live.reducer';

/** Lets a fire-and-forget promise chain inside an effect (`patchTopics`'s own `from(api...)`) settle. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

/** One `gateway.open(topics)` call, with direct access to its `Subscriber` so a test can push
 *  `LiveGatewayEvent`s and observe teardown (`unsubscribed`) exactly like `switchMap`'s cancellation
 *  of a superseded attempt would trigger on the real `EventSource.close()`. */
interface OpenCall {
  readonly topics: string;
  readonly subscriber: Subscriber<LiveGatewayEvent>;
  unsubscribed: boolean;
}

/** A hand-written fake standing in for `LiveGateway` — no real `EventSource` (jsdom has none, see
 *  that class's own doc comment), full control over when each lifecycle event fires. */
function fakeGateway(available = true) {
  const calls: OpenCall[] = [];
  const open = vi.fn((topics: string) =>
    new Observable<LiveGatewayEvent>((subscriber) => {
      const call: OpenCall = { topics, subscriber, unsubscribed: false };
      calls.push(call);
      return () => {
        call.unsubscribed = true;
      };
    }),
  );
  return { isAvailable: () => available, open, calls };
}

function setupConnection(gateway: ReturnType<typeof fakeGateway>) {
  const actions = new ReplaySubject<Action>(1);
  TestBed.configureTestingModule({
    providers: [provideMockActions(() => actions), provideStore(), provideState(liveFeature), { provide: LiveGateway, useValue: gateway }],
  });
  const store = TestBed.inject(Store);
  const seen: Action[] = [];
  TestBed.runInInjectionContext(() => connection$()).subscribe((a) => seen.push(a));
  return { actions, store, seen };
}

describe('live effects — connection$', () => {
  it('degrades straight to Closed, without ever opening, when the gateway reports unavailable', () => {
    const gateway = fakeGateway(false);
    const { actions, seen } = setupConnection(gateway);

    actions.next(LivePageActions.reconnectRequested());

    expect(seen).toEqual([LiveSocketActions.closed()]);
    expect(gateway.open).not.toHaveBeenCalled();
  });

  it('opens with the topics query built fresh from the live ref-counts at the moment it connects', () => {
    const gateway = fakeGateway();
    const { actions, store } = setupConnection(gateway);

    store.dispatch(LivePageActions.telemetryTracked({ assetId: 'a-1' }));
    actions.next(LivePageActions.reconnectRequested());

    expect(gateway.open).toHaveBeenCalledExactlyOnceWith(telemetryTopic('a-1'));
  });

  it('maps every LiveGatewayEvent kind to its matching Live Socket action', () => {
    const gateway = fakeGateway();
    const { actions, seen } = setupConnection(gateway);

    actions.next(LivePageActions.reconnectRequested());
    const call = gateway.calls[0];
    call.subscriber.next({ kind: 'open' });
    call.subscriber.next({ kind: 'connected', connectionId: 'c-1', topics: [] });
    call.subscriber.next({ kind: 'message', envelope: { seq: 1, type: 'fleet', payload: [] } });
    call.subscriber.next({ kind: 'retrying' });

    expect(seen).toEqual([
      LiveSocketActions.opened(),
      LiveSocketActions.handshakeReceived({ connectionId: 'c-1', topics: [] }),
      LiveSocketActions.envelopeReceived({ envelope: { seq: 1, type: 'fleet', payload: [] } }),
      LiveSocketActions.retrying(),
    ]);
  });

  it('retries SSE_RETRY_INTERVAL_MS after a fatal close, rebuilding the topic list fresh for the new attempt', async () => {
    vi.useFakeTimers();
    try {
      const gateway = fakeGateway();
      const { actions, seen, store } = setupConnection(gateway);

      actions.next(LivePageActions.reconnectRequested());
      gateway.calls[0].subscriber.next({ kind: 'fatal' });
      gateway.calls[0].subscriber.complete();
      expect(seen).toEqual([LiveSocketActions.closed()]);

      // A topic changes while genuinely closed (this function's own retry path) — the known,
      // accepted gap only applies to a *native* auto-retry ('retrying'), never this one.
      store.dispatch(LivePageActions.telemetryTracked({ assetId: 'a-1' }));

      await vi.advanceTimersByTimeAsync(SSE_RETRY_INTERVAL_MS);

      expect(gateway.open).toHaveBeenCalledTimes(2);
      expect(gateway.open).toHaveBeenLastCalledWith(telemetryTopic('a-1'));
    } finally {
      vi.useRealTimers();
    }
  });

  it('a fresh Reconnect Requested tears down whatever attempt was in flight (switchMap cancellation)', () => {
    const gateway = fakeGateway();
    const { actions } = setupConnection(gateway);

    actions.next(LivePageActions.reconnectRequested());
    const first = gateway.calls[0];
    actions.next(LivePageActions.reconnectRequested());

    expect(first.unsubscribed).toBe(true);
    expect(gateway.calls).toHaveLength(2);
  });

  it('Stop Requested tears down any in-flight attempt and emits nothing at all', () => {
    const gateway = fakeGateway();
    const { actions, seen } = setupConnection(gateway);

    actions.next(LivePageActions.reconnectRequested());
    const first = gateway.calls[0];
    actions.next(LivePageActions.stopRequested());

    expect(first.unsubscribed).toBe(true);
    expect(seen).toEqual([]);
  });
});

/** Dispatches to both the real `Store` (what `concatLatestFrom` reads back, post-reducer) and the
 *  mocked `Actions` stream (what the effect actually listens on) — the two are separate channels
 *  under `provideMockActions`, mirroring `seat.effects.spec.ts#receive`. */
function dispatchBoth(store: Store, actions: ReplaySubject<Action>, action: Action) {
  store.dispatch(action);
  actions.next(action);
}

function setupPatch(api: Partial<VisionApi>) {
  const actions = new ReplaySubject<Action>(1);
  TestBed.configureTestingModule({
    providers: [provideMockActions(() => actions), provideStore(), provideState(liveFeature), { provide: VisionApi, useValue: api }],
  });
  const store = TestBed.inject(Store);
  return { actions, store };
}

/** Puts the slice into "connected and open with connectionId c-1" — the only state `patchOnTrack$`/
 *  `patchOnUntrack$` will ever PATCH under, per their own doc comments. */
function openWithConnectionId(store: Store, connectionId = 'c-1') {
  store.dispatch(LiveSocketActions.handshakeReceived({ connectionId, topics: [] }));
  store.dispatch(LiveSocketActions.opened());
}

describe('live effects — patchOnTrack$', () => {
  it('PATCHes add on the first tracker of a topic, while the connection is open', async () => {
    const api = { updateLiveTopics: vi.fn().mockResolvedValue({ connectionId: 'c-1', topics: [telemetryTopic('a-1')] }) };
    const { actions, store } = setupPatch(api);
    openWithConnectionId(store);
    TestBed.runInInjectionContext(() => patchOnTrack$()).subscribe();

    dispatchBoth(store, actions, LivePageActions.telemetryTracked({ assetId: 'a-1' }));
    await flush();

    expect(api.updateLiveTopics).toHaveBeenCalledExactlyOnceWith('c-1', { add: [telemetryTopic('a-1')], remove: [] });
  });

  it('does not PATCH for a second tracker of an already-subscribed topic', async () => {
    const api = { updateLiveTopics: vi.fn().mockResolvedValue({}) };
    const { actions, store } = setupPatch(api);
    openWithConnectionId(store);
    TestBed.runInInjectionContext(() => patchOnTrack$()).subscribe();

    dispatchBoth(store, actions, LivePageActions.telemetryTracked({ assetId: 'a-1' }));
    await flush();
    api.updateLiveTopics.mockClear();

    dispatchBoth(store, actions, LivePageActions.telemetryTracked({ assetId: 'a-1' }));
    await flush();

    expect(api.updateLiveTopics).not.toHaveBeenCalled();
  });

  it('does not PATCH while the connection is not open, even for a first subscriber', async () => {
    const api = { updateLiveTopics: vi.fn().mockResolvedValue({}) };
    const { actions, store } = setupPatch(api); // default state: 'connecting', no connectionId
    TestBed.runInInjectionContext(() => patchOnTrack$()).subscribe();

    dispatchBoth(store, actions, LivePageActions.telemetryTracked({ assetId: 'a-1' }));
    await flush();

    expect(api.updateLiveTopics).not.toHaveBeenCalled();
  });

  it('dispatches Topics Patch Failed on a rejected PATCH, rather than throwing', async () => {
    const api = { updateLiveTopics: vi.fn().mockRejectedValue(new Error('network down')) };
    const { actions, store } = setupPatch(api);
    openWithConnectionId(store);
    const seen: Action[] = [];
    TestBed.runInInjectionContext(() => patchOnTrack$()).subscribe((a) => seen.push(a));

    dispatchBoth(store, actions, LivePageActions.telemetryTracked({ assetId: 'a-1' }));
    await flush();

    expect(seen).toEqual([LiveApiActions.topicsPatchFailed({ topics: [telemetryTopic('a-1')] })]);
  });
});

describe('live effects — patchOnUntrack$', () => {
  it('PATCHes remove on the last untracker of a topic, while the connection is open', async () => {
    const api = { updateLiveTopics: vi.fn().mockResolvedValue({}) };
    const { actions, store } = setupPatch(api);
    openWithConnectionId(store);
    store.dispatch(LivePageActions.telemetryTracked({ assetId: 'a-1' })); // seed ref count = 1
    TestBed.runInInjectionContext(() => patchOnUntrack$()).subscribe();

    dispatchBoth(store, actions, LivePageActions.telemetryUntracked({ assetId: 'a-1' }));
    await flush();

    expect(api.updateLiveTopics).toHaveBeenCalledExactlyOnceWith('c-1', { add: [], remove: [telemetryTopic('a-1')] });
  });

  it('does not PATCH remove while another subscriber of the same topic remains', async () => {
    const api = { updateLiveTopics: vi.fn().mockResolvedValue({}) };
    const { actions, store } = setupPatch(api);
    openWithConnectionId(store);
    store.dispatch(LivePageActions.telemetryTracked({ assetId: 'a-1' }));
    store.dispatch(LivePageActions.telemetryTracked({ assetId: 'a-1' })); // ref count = 2
    TestBed.runInInjectionContext(() => patchOnUntrack$()).subscribe();

    dispatchBoth(store, actions, LivePageActions.telemetryUntracked({ assetId: 'a-1' })); // -> 1, not last
    await flush();

    expect(api.updateLiveTopics).not.toHaveBeenCalled();
  });

  it('does not PATCH while the connection is not open, even for a last subscriber', async () => {
    const api = { updateLiveTopics: vi.fn().mockResolvedValue({}) };
    const { actions, store } = setupPatch(api); // never opened
    store.dispatch(LivePageActions.telemetryTracked({ assetId: 'a-1' }));
    TestBed.runInInjectionContext(() => patchOnUntrack$()).subscribe();

    dispatchBoth(store, actions, LivePageActions.telemetryUntracked({ assetId: 'a-1' }));
    await flush();

    expect(api.updateLiveTopics).not.toHaveBeenCalled();
  });
});

/** The two session-bridge effects (wave N9) need nothing but the action stream — no store, no
 *  gateway, no `VisionApi`. That is the whole point of the inversion: `live` reacts to auth's
 *  published facts instead of auth resolving a `LiveFacade` out of the injector. These cases moved
 *  here verbatim from `core/auth/state/auth.effects.spec.ts`. */
function setupBridge() {
  const actions = new ReplaySubject<Action>(1);
  TestBed.configureTestingModule({ providers: [provideMockActions(() => actions)] });
  const seen: Action[] = [];
  return { actions, seen };
}

function me(overrides: Partial<MeResponse> = {}): MeResponse {
  return {
    userId: 'u-1',
    username: 'pilot',
    displayName: 'Pat Pilot',
    email: 'pilot@example.com',
    memberships: [],
    topRole: 'PILOT',
    authEnabled: true,
    capabilities: [],
    scopeKind: 'ASSIGNED_ASSETS',
    mustChangePassword: false,
    ...overrides,
  };
}

describe('live effects — reconnectOnSession$', () => {
  it('reconnects once a login resolves a session', () => {
    const { actions, seen } = setupBridge();
    TestBed.runInInjectionContext(() => reconnectOnSession$()).subscribe((a) => seen.push(a));

    actions.next(AuthApiActions.loginSucceeded({ me: me() }));

    expect(seen).toEqual([LivePageActions.reconnectRequested()]);
  });

  it('reconnects for the first-admin bootstrap too — it mints a session exactly like a login', () => {
    const { actions, seen } = setupBridge();
    TestBed.runInInjectionContext(() => reconnectOnSession$()).subscribe((a) => seen.push(a));

    actions.next(AuthApiActions.bootstrapSucceeded({ me: me({ topRole: 'ADMIN' }) }));

    expect(seen).toEqual([LivePageActions.reconnectRequested()]);
  });

  it('does not reconnect on a failed login — no session was minted', () => {
    const { actions, seen } = setupBridge();
    TestBed.runInInjectionContext(() => reconnectOnSession$()).subscribe((a) => seen.push(a));

    actions.next(AuthApiActions.loginFailed({ message: 'Incorrect username or password.' }));

    expect(seen).toEqual([]);
  });
});

describe('live effects — stopOnLogout$', () => {
  it('stops the connection when a real session was signed out of', () => {
    const { actions, seen } = setupBridge();
    TestBed.runInInjectionContext(() => stopOnLogout$()).subscribe((a) => seen.push(a));

    actions.next(AuthApiActions.logoutCompleted({ wasAuthEnabled: true }));

    expect(seen).toEqual([LivePageActions.stopRequested()]);
  });

  it('leaves the connection alone in dev parity — there was no session to invalidate', () => {
    const { actions, seen } = setupBridge();
    TestBed.runInInjectionContext(() => stopOnLogout$()).subscribe((a) => seen.push(a));

    actions.next(AuthApiActions.logoutCompleted({ wasAuthEnabled: false }));

    expect(seen).toEqual([]);
  });
});
