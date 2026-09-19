import { TestBed } from '@angular/core/testing';
import { provideMockActions } from '@ngrx/effects/testing';
import type { Action } from '@ngrx/store';
import { Store, provideState, provideStore } from '@ngrx/store';
import { ReplaySubject } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { DetectionEvent } from '../../api/models';
import { VisionApi } from '../../api/vision-api';
import { LiveSocketActions } from '../../live/state/live.actions';
import { liveFeature } from '../../live/state/live.reducer';
import { PollScheduler } from '../../poll-scheduler';
import { SettingsFacade } from '../../settings/settings-facade';
import { EventsApiActions, EventsPageActions } from './events.actions';
import { notify$, poll$ } from './events.effects';
import { eventsFeature } from './events.reducer';

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function event(overrides: Partial<DetectionEvent> = {}): DetectionEvent {
  return {
    id: 'e-1',
    streamId: 's-1',
    label: 'person',
    peakConfidence: 0.8,
    firstSeen: '2026-07-23T10:00:00.000Z',
    lastSeen: '2026-07-23T10:00:05.000Z',
    state: 'OPEN',
    ...overrides,
  };
}

function stubScheduler() {
  const calls: { periodMs: number; callback: () => void | Promise<void>; stop: ReturnType<typeof vi.fn> }[] = [];
  const schedule = vi.fn((periodMs: number, callback: () => void | Promise<void>) => {
    const stop = vi.fn();
    calls.push({ periodMs, callback, stop });
    return stop;
  });
  const lastFor = (periodMs: number) => [...calls].reverse().find((call) => call.periodMs === periodMs);
  return { schedule, calls, lastFor };
}

function setup(apiOverrides: Record<string, ReturnType<typeof vi.fn>> = {}, scheduler = stubScheduler()) {
  const actions = new ReplaySubject<Action>(1);
  const api = { events: vi.fn().mockResolvedValue([]), ...apiOverrides };
  TestBed.configureTestingModule({
    providers: [
      provideMockActions(() => actions),
      provideStore(),
      provideState(eventsFeature),
      provideState(liveFeature),
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: scheduler },
    ],
  });
  const store = TestBed.inject(Store);
  return { actions, api, scheduler, store };
}

describe('events effects — poll$ (the root-singleton demand gate)', () => {
  it('never fetches or schedules until the first activated()', async () => {
    const { api, scheduler, store } = setup();
    TestBed.runInInjectionContext(() => poll$()).subscribe((action) => store.dispatch(action));
    await flush();

    expect(api.events).not.toHaveBeenCalled();
    expect(scheduler.schedule).not.toHaveBeenCalled();
  });

  it('activated() fetches once (sinceMs undefined) and schedules the poll; a second concurrent activated() does neither again', async () => {
    const { api, scheduler, store } = setup({ events: vi.fn().mockResolvedValue([event()]) });
    TestBed.runInInjectionContext(() => poll$()).subscribe((action) => store.dispatch(action));

    store.dispatch(EventsPageActions.activated());
    await flush();
    expect(api.events).toHaveBeenCalledExactlyOnceWith(undefined, 50);
    expect(scheduler.schedule).toHaveBeenCalledTimes(1);
    expect(scheduler.schedule).toHaveBeenCalledWith(5_000, expect.any(Function), { ignoreHidden: true });

    store.dispatch(EventsPageActions.activated());
    await flush();
    expect(api.events).toHaveBeenCalledTimes(1);
    expect(scheduler.schedule).toHaveBeenCalledTimes(1);
  });

  it('advances the sinceMs cursor between polls', async () => {
    const { api, scheduler, store } = setup({
      events: vi.fn().mockResolvedValue([event({ id: 'e-1', lastSeen: '2026-07-23T10:00:05.000Z' })]),
    });
    TestBed.runInInjectionContext(() => poll$()).subscribe((action) => store.dispatch(action));

    store.dispatch(EventsPageActions.activated());
    await flush();
    await scheduler.lastFor(5_000)?.callback();
    await flush();

    expect(api.events).toHaveBeenLastCalledWith(Date.parse('2026-07-23T10:00:05.000Z'), 50);
  });

  it('stops the poll only once every consumer has released, then refetches on reactivation', async () => {
    const { api, scheduler, store } = setup();
    TestBed.runInInjectionContext(() => poll$()).subscribe((action) => store.dispatch(action));

    store.dispatch(EventsPageActions.activated());
    store.dispatch(EventsPageActions.activated());
    await flush();
    const firstPoll = scheduler.lastFor(5_000);

    store.dispatch(EventsPageActions.released());
    expect(firstPoll?.stop).not.toHaveBeenCalled();
    store.dispatch(EventsPageActions.released());
    expect(firstPoll?.stop).toHaveBeenCalledOnce();

    store.dispatch(EventsPageActions.activated());
    await flush();
    expect(api.events).toHaveBeenCalledTimes(2);
  });

  it('activating while LiveFacade is already open does zero fetches and never schedules', async () => {
    const { api, scheduler, store } = setup();
    TestBed.runInInjectionContext(() => poll$()).subscribe((action) => store.dispatch(action));

    store.dispatch(LiveSocketActions.opened());
    store.dispatch(EventsPageActions.activated());
    await flush();

    expect(api.events).not.toHaveBeenCalled();
    expect(scheduler.schedule).not.toHaveBeenCalled();
  });

  it('switches from poll to live, stopping the poll with no extra fetch, when live opens mid-session', async () => {
    const { api, scheduler, store } = setup();
    TestBed.runInInjectionContext(() => poll$()).subscribe((action) => store.dispatch(action));

    store.dispatch(EventsPageActions.activated());
    await flush();
    const firstPoll = scheduler.lastFor(5_000);
    expect(firstPoll?.stop).not.toHaveBeenCalled();
    api.events.mockClear();

    store.dispatch(LiveSocketActions.opened());
    await flush();

    expect(firstPoll?.stop).toHaveBeenCalledOnce();
    expect(api.events).not.toHaveBeenCalled(); // no reconcile GET — live is a full replacement here
  });

  it('falls back to polling again, fetching fresh data immediately, when live drops mid-session', async () => {
    const { api, store } = setup({ events: vi.fn().mockResolvedValue([event({ id: 'e-fallback' })]) });
    TestBed.runInInjectionContext(() => poll$()).subscribe((action) => store.dispatch(action));

    store.dispatch(LiveSocketActions.opened());
    store.dispatch(EventsPageActions.activated());
    await flush();
    expect(api.events).not.toHaveBeenCalled();

    store.dispatch(LiveSocketActions.closed());
    await flush();

    expect(api.events).toHaveBeenCalledOnce();
    expect(store.selectSignal(eventsFeature.selectEvents)().map((e) => e.id)).toEqual(['e-fallback']);
  });

  it('silently degrades when the poll fails, rather than throwing', async () => {
    const { store } = setup({ events: vi.fn().mockRejectedValue(new Error('network down')) });
    TestBed.runInInjectionContext(() => poll$()).subscribe((action) => store.dispatch(action));

    store.dispatch(EventsPageActions.activated());
    await flush();

    expect(store.selectSignal(eventsFeature.selectEvents)()).toEqual([]);
  });
});

describe('events effects — notify$ (the impure side-effect half of applyIncoming)', () => {
  const originalNotification = (globalThis as { Notification?: unknown }).Notification;
  const originalHidden = Object.getOwnPropertyDescriptor(document, 'hidden');

  afterEach(() => {
    (globalThis as { Notification?: unknown }).Notification = originalNotification;
    if (originalHidden) {
      Object.defineProperty(document, 'hidden', originalHidden);
    }
  });

  function installNotificationStub(permission: NotificationPermission): ReturnType<typeof vi.fn> {
    const ctor = vi.fn();
    Object.defineProperty(ctor, 'permission', { value: permission, configurable: true });
    (globalThis as { Notification?: unknown }).Notification = ctor;
    return ctor;
  }

  function setHidden(hidden: boolean): void {
    Object.defineProperty(document, 'hidden', { value: hidden, configurable: true });
  }

  function setupNotify(eventNotifications: boolean) {
    const actions = new ReplaySubject<Action>(1);
    const settings = { eventNotifications: () => eventNotifications };
    TestBed.configureTestingModule({
      providers: [provideMockActions(() => actions), { provide: SettingsFacade, useValue: settings }],
    });
    return { actions };
  }

  it('fires a Notification for a new OPEN event, from a poll batch, once permission/opt-in/hidden all line up', () => {
    const NotificationCtor = installNotificationStub('granted');
    setHidden(true);
    const { actions } = setupNotify(true);
    TestBed.runInInjectionContext(() => notify$()).subscribe();

    actions.next(EventsApiActions.pollSucceeded({ events: [event({ id: 'e-open', label: 'person', peakConfidence: 0.87 })] }));

    expect(NotificationCtor).toHaveBeenCalledWith('Person detected', { body: '87% confidence', tag: 'e-open' });
  });

  it('never fires twice for the same id across two poll batches', () => {
    const NotificationCtor = installNotificationStub('granted');
    setHidden(true);
    const { actions } = setupNotify(true);
    TestBed.runInInjectionContext(() => notify$()).subscribe();

    actions.next(EventsApiActions.pollSucceeded({ events: [event({ id: 'e-open', lastSeen: '2026-07-23T10:00:00.000Z' })] }));
    actions.next(EventsApiActions.pollSucceeded({ events: [event({ id: 'e-open', lastSeen: '2026-07-23T10:00:05.000Z' })] }));

    expect(NotificationCtor).toHaveBeenCalledTimes(1);
  });

  it('does not notify while the document is visible', () => {
    const NotificationCtor = installNotificationStub('granted');
    setHidden(false);
    const { actions } = setupNotify(true);
    TestBed.runInInjectionContext(() => notify$()).subscribe();

    actions.next(EventsApiActions.pollSucceeded({ events: [event({ id: 'e-visible' })] }));

    expect(NotificationCtor).not.toHaveBeenCalled();
  });

  it('does not notify when the user has not opted in, even if permission is granted', () => {
    const NotificationCtor = installNotificationStub('granted');
    setHidden(true);
    const { actions } = setupNotify(false);
    TestBed.runInInjectionContext(() => notify$()).subscribe();

    actions.next(EventsApiActions.pollSucceeded({ events: [event({ id: 'e-opt-out' })] }));

    expect(NotificationCtor).not.toHaveBeenCalled();
  });

  it('does not notify when the browser has not granted permission', () => {
    const NotificationCtor = installNotificationStub('default');
    setHidden(true);
    const { actions } = setupNotify(true);
    TestBed.runInInjectionContext(() => notify$()).subscribe();

    actions.next(EventsApiActions.pollSucceeded({ events: [event({ id: 'e-no-permission' })] }));

    expect(NotificationCtor).not.toHaveBeenCalled();
  });

  it('also fires from the live detection-events topic, one envelope at a time', () => {
    const NotificationCtor = installNotificationStub('granted');
    setHidden(true);
    const { actions } = setupNotify(true);
    TestBed.runInInjectionContext(() => notify$()).subscribe();

    actions.next(LiveSocketActions.envelopeReceived({ envelope: { seq: 1, type: 'detection-events', payload: event({ id: 'e-live' }) } }));

    expect(NotificationCtor).toHaveBeenCalledOnce();
  });

  it('ignores envelopes of any other type', () => {
    const NotificationCtor = installNotificationStub('granted');
    setHidden(true);
    const { actions } = setupNotify(true);
    TestBed.runInInjectionContext(() => notify$()).subscribe();

    actions.next(LiveSocketActions.envelopeReceived({ envelope: { seq: 1, type: 'fleet', payload: [] } }));

    expect(NotificationCtor).not.toHaveBeenCalled();
  });

  it('a live OPEN then a later CLOSED for the same id notifies exactly once, for the OPEN', () => {
    const NotificationCtor = installNotificationStub('granted');
    setHidden(true);
    const { actions } = setupNotify(true);
    TestBed.runInInjectionContext(() => notify$()).subscribe();

    actions.next(
      LiveSocketActions.envelopeReceived({
        envelope: { seq: 1, type: 'detection-events', payload: event({ id: 'e-lifecycle', state: 'OPEN', lastSeen: '2026-07-23T10:00:00.000Z' }) },
      }),
    );
    actions.next(
      LiveSocketActions.envelopeReceived({
        envelope: { seq: 2, type: 'detection-events', payload: event({ id: 'e-lifecycle', state: 'CLOSED', lastSeen: '2026-07-23T10:00:05.000Z' }) },
      }),
    );

    expect(NotificationCtor).toHaveBeenCalledTimes(1);
  });
});
