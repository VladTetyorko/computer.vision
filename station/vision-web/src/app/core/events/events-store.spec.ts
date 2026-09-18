import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EventsStore } from './events-store';
import { VisionApi } from '../api/vision-api';
import { PollScheduler } from '../poll-scheduler';
import { SettingsFacade } from '../settings/settings-facade';
import { LiveFacade, type LiveConnectionState } from '../live/live-facade';
import type { DetectionEvent } from '../api/models';

/** Lets the fire-and-forget promise chain inside `pollOnce()` settle before asserting. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function event(partial: Partial<DetectionEvent> = {}): DetectionEvent {
  return {
    id: 'e-1',
    streamId: 's-1',
    label: 'person',
    peakConfidence: 0.8,
    firstSeen: '2026-07-23T10:00:00.000Z',
    lastSeen: '2026-07-23T10:00:05.000Z',
    state: 'OPEN',
    ...partial,
  };
}

function stubSettings(eventNotifications = false) {
  return { eventNotifications: () => eventNotifications };
}

/**
 * A minimal `LiveFacade` test double (docs/plans/done/REALTIME-PLAN.md §4's backend follow-up batch), mirroring
 * `telemetry-store.spec.ts#stubLiveFacade` — real Angular `signal`s so `EventsStore`'s own
 * `computed`/`effect` react to it exactly as they would to the real class, without a real
 * `EventSource` (jsdom has none). Defaults to `'closed'` — the same state the *real* `LiveFacade`
 * reports under jsdom — so every pre-existing test above, which never provides this stub at all,
 * keeps exercising the poll-only path unmodified.
 */
function stubLiveFacade(initialState: LiveConnectionState = 'closed') {
  const stateSignal = signal<LiveConnectionState>(initialState);
  const detectionEventsSignal = signal<readonly DetectionEvent[]>([]);
  return {
    connectionState: stateSignal.asReadonly(),
    detectionEvents: detectionEventsSignal.asReadonly(),
    setState: (state: LiveConnectionState) => stateSignal.set(state),
    /** Appends, oldest-first — mirrors the real `LiveFacade.detectionEvents`'s own accumulation contract. */
    pushDetectionEvents: (events: readonly DetectionEvent[]) =>
      detectionEventsSignal.update((existing) => [...existing, ...events]),
  };
}

function create(options: {
  events?: ReturnType<typeof vi.fn>;
  scheduler?: unknown;
  eventNotifications?: boolean;
  live?: ReturnType<typeof stubLiveFacade>;
} = {}): { store: EventsStore; api: { events: ReturnType<typeof vi.fn> } } {
  const api = { events: options.events ?? vi.fn().mockResolvedValue([]) };
  const providers: unknown[] = [
    { provide: VisionApi, useValue: api },
    { provide: SettingsFacade, useValue: stubSettings(options.eventNotifications ?? false) },
  ];
  if (options.scheduler) {
    providers.push({ provide: PollScheduler, useValue: options.scheduler });
  }
  providers.push({ provide: LiveFacade, useValue: options.live ?? stubLiveFacade() });
  TestBed.configureTestingModule({ providers });
  return { store: TestBed.inject(EventsStore), api };
}

/**
 * Installs a fake global `Notification` constructor with a fixed `.permission`, mirroring the
 * shape `events-store.ts` reads (`Notification.permission`, `new Notification(title, options)`).
 * `Object.defineProperty` rather than plain assignment sidesteps needing `ctor`'s inferred mock
 * type to already declare a `permission` property.
 */
function installNotificationStub(permission: NotificationPermission): ReturnType<typeof vi.fn> {
  const ctor = vi.fn();
  Object.defineProperty(ctor, 'permission', { value: permission, configurable: true });
  (globalThis as { Notification?: unknown }).Notification = ctor;
  return ctor;
}

/**
 * A scheduler stub that hands back the registered callback so a test can trigger a second poll
 * on demand (`poll()`) rather than reaching into the store's own private `pollOnce` or waiting on
 * real/fake timers — the store only ever registers one task, so capturing the last one is enough.
 */
function createWithCapturedPoll(options: {
  events?: ReturnType<typeof vi.fn>;
  eventNotifications?: boolean;
  live?: ReturnType<typeof stubLiveFacade>;
} = {}): { store: EventsStore; api: { events: ReturnType<typeof vi.fn> }; poll: () => void } {
  let captured: (() => void) | undefined;
  const schedule = vi.fn((_periodMs: number, callback: () => void) => {
    captured = callback;
    return () => {};
  });
  const { store, api } = create({ ...options, scheduler: { schedule } });
  return { store, api, poll: () => captured?.() };
}

describe('EventsStore', () => {
  const originalNotification = (globalThis as { Notification?: unknown }).Notification;

  afterEach(() => {
    (globalThis as { Notification?: unknown }).Notification = originalNotification;
  });

  it('does nothing until activated — no poll at all', () => {
    const events = vi.fn().mockResolvedValue([]);
    create({ events });
    expect(events).not.toHaveBeenCalled();
  });

  it('activate() polls immediately and populates events', async () => {
    const events = vi.fn().mockResolvedValue([event({ id: 'e-1' })]);
    const { store } = create({ events });

    store.activate();
    await flush();

    expect(events).toHaveBeenCalledWith(undefined, 50);
    expect(store.events().map((e) => e.id)).toEqual(['e-1']);
    store.release();
  });

  it('advances the sinceMs cursor between polls', async () => {
    const events = vi.fn().mockResolvedValue([event({ id: 'e-1', lastSeen: '2026-07-23T10:00:05.000Z' })]);
    const { store, poll } = createWithCapturedPoll({ events });

    store.activate();
    await flush();
    poll(); // second poll, exercising the cursor this store now holds
    await flush();

    expect(events).toHaveBeenLastCalledWith(Date.parse('2026-07-23T10:00:05.000Z'), 50);
    store.release();
  });

  it('merges rather than duplicates a repeated id across polls', async () => {
    const events = vi
      .fn()
      .mockResolvedValueOnce([event({ id: 'e-1', peakConfidence: 0.5 })])
      .mockResolvedValueOnce([event({ id: 'e-1', peakConfidence: 0.9 })]);
    const { store, poll } = createWithCapturedPoll({ events });

    store.activate();
    await flush();
    poll();
    await flush();

    expect(store.events()).toHaveLength(1);
    expect(store.events()[0].peakConfidence).toBe(0.9);
    store.release();
  });

  it('silently degrades when the poll fails, rather than throwing', async () => {
    const events = vi.fn().mockRejectedValue(new Error('network down'));
    const { store } = create({ events });

    store.activate();
    await flush();

    expect(store.events()).toEqual([]);
    store.release();
  });

  it('release() stops the scheduled poll once the last consumer leaves', () => {
    const stop = vi.fn();
    const schedule = vi.fn().mockReturnValue(stop);
    const events = vi.fn().mockResolvedValue([]);
    const { store } = create({ events, scheduler: { schedule } });

    store.activate();
    store.release();

    expect(stop).toHaveBeenCalledTimes(1);
  });

  it('does not stop polling while a second consumer is still active', () => {
    const stop = vi.fn();
    const schedule = vi.fn().mockReturnValue(stop);
    const events = vi.fn().mockResolvedValue([]);
    const { store } = create({ events, scheduler: { schedule } });

    store.activate();
    store.activate();
    store.release();

    expect(stop).not.toHaveBeenCalled();
    expect(schedule).toHaveBeenCalledTimes(1); // only the first activate() registers a poll
  });

  it('re-activating after a full release polls immediately again', async () => {
    const events = vi.fn().mockResolvedValue([]);
    const { store } = create({ events });

    store.activate();
    await flush();
    store.release();
    store.activate();
    await flush();

    expect(events).toHaveBeenCalledTimes(2);
    store.release();
  });

  it('registers its poll to keep running while the tab is hidden', () => {
    const schedule = vi.fn().mockReturnValue(() => {});
    const events = vi.fn().mockResolvedValue([]);
    const { store } = create({ events, scheduler: { schedule } });

    store.activate();

    expect(schedule).toHaveBeenCalledWith(expect.any(Number), expect.any(Function), { ignoreHidden: true });
    store.release();
  });

  it('fires a Notification for a new OPEN event once permission/opt-in/hidden all line up', async () => {
    const NotificationCtor = installNotificationStub('granted');
    Object.defineProperty(document, 'hidden', { value: true, configurable: true });

    const events = vi.fn().mockResolvedValue([event({ id: 'e-open', label: 'person', peakConfidence: 0.87 })]);
    const { store } = create({ events, eventNotifications: true });

    store.activate();
    await flush();

    expect(NotificationCtor).toHaveBeenCalledWith('Person detected', {
      body: '87% confidence',
      tag: 'e-open',
    });
    store.release();
    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
  });

  it('never fires twice for the same event id across polls', async () => {
    const NotificationCtor = installNotificationStub('granted');
    Object.defineProperty(document, 'hidden', { value: true, configurable: true });

    const events = vi.fn().mockResolvedValue([event({ id: 'e-open', lastSeen: '2026-07-23T10:00:05.000Z' })]);
    const { store, poll } = createWithCapturedPoll({ events, eventNotifications: true });

    store.activate();
    await flush();
    poll(); // the still-OPEN event reappears, lastSeen advanced
    await flush();

    expect(NotificationCtor).toHaveBeenCalledTimes(1);
    store.release();
    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
  });

  it('does not notify while the document is visible', async () => {
    const NotificationCtor = installNotificationStub('granted');
    Object.defineProperty(document, 'hidden', { value: false, configurable: true });

    const events = vi.fn().mockResolvedValue([event({ id: 'e-visible' })]);
    const { store } = create({ events, eventNotifications: true });

    store.activate();
    await flush();

    expect(NotificationCtor).not.toHaveBeenCalled();
    store.release();
  });

  it('does not notify when the user has not opted in, even if permission is granted', async () => {
    const NotificationCtor = installNotificationStub('granted');
    Object.defineProperty(document, 'hidden', { value: true, configurable: true });

    const events = vi.fn().mockResolvedValue([event({ id: 'e-opt-out' })]);
    const { store } = create({ events, eventNotifications: false });

    store.activate();
    await flush();

    expect(NotificationCtor).not.toHaveBeenCalled();
    store.release();
    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
  });

  it('does not notify when the browser has not granted permission', async () => {
    const NotificationCtor = installNotificationStub('default');
    Object.defineProperty(document, 'hidden', { value: true, configurable: true });

    const events = vi.fn().mockResolvedValue([event({ id: 'e-no-permission' })]);
    const { store } = create({ events, eventNotifications: true });

    store.activate();
    await flush();

    expect(NotificationCtor).not.toHaveBeenCalled();
    store.release();
    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
  });

  // --- LiveFacade projection (docs/plans/done/REALTIME-PLAN.md §4's backend follow-up batch) ---------------

  it('does not poll at all when activated while LiveFacade is already open', () => {
    const events = vi.fn().mockResolvedValue([]);
    const live = stubLiveFacade('open');
    const { store } = create({ events, live });

    store.activate();

    expect(events).not.toHaveBeenCalled();
    store.release();
  });

  it('folds a detection-events arrival into events(), live, with no poll involved', () => {
    const events = vi.fn().mockResolvedValue([]);
    const live = stubLiveFacade('open');
    const { store } = create({ events, live });

    store.activate();
    live.pushDetectionEvents([event({ id: 'e-live', peakConfidence: 0.6 })]);
    TestBed.tick(); // flushes the live-arrival effect

    expect(store.events().map((e) => e.id)).toEqual(['e-live']);
    expect(events).not.toHaveBeenCalled();
    store.release();
  });

  it('switches from poll to live, stopping the poll, when LiveFacade opens mid-session', async () => {
    const events = vi.fn().mockResolvedValue([]);
    const stop = vi.fn();
    const schedule = vi.fn().mockReturnValue(stop);
    const live = stubLiveFacade('closed');
    const { store } = create({ events, live, scheduler: { schedule } });

    store.activate();
    await flush();
    expect(stop).not.toHaveBeenCalled();

    live.setState('open');
    TestBed.tick(); // flushes the transport-switch effect

    expect(stop).toHaveBeenCalledOnce(); // the poll is stopped, not left running alongside live
    store.release();
  });

  it('falls back to polling again, fetching fresh data immediately, when LiveFacade drops mid-session', async () => {
    const events = vi.fn().mockResolvedValue([event({ id: 'e-fallback' })]);
    const live = stubLiveFacade('open');
    const { store } = create({ events, live });

    store.activate();
    expect(events).not.toHaveBeenCalled(); // live from the start — no poll yet

    live.setState('closed');
    TestBed.tick(); // flushes the transport-switch effect
    await flush();

    expect(events).toHaveBeenCalledOnce(); // immediate re-fetch on falling back to polling
    expect(store.events().map((e) => e.id)).toEqual(['e-fallback']);
    store.release();
  });

  it('upserts a same-id OPEN→CLOSED pair from a single live batch in chronological order, notifying only for the OPEN', () => {
    const NotificationCtor = installNotificationStub('granted');
    Object.defineProperty(document, 'hidden', { value: true, configurable: true });

    const events = vi.fn().mockResolvedValue([]);
    const live = stubLiveFacade('open');
    const { store } = create({ events, live, eventNotifications: true });

    store.activate();
    // A burst containing the OPEN, then (later) the CLOSED state of the *same* id — mirrors a
    // connect-time snapshot replay or several arrivals coalesced into one effect run.
    live.pushDetectionEvents([
      event({ id: 'e-lifecycle', state: 'OPEN', lastSeen: '2026-07-23T10:00:00.000Z' }),
      event({ id: 'e-lifecycle', state: 'CLOSED', lastSeen: '2026-07-23T10:00:05.000Z' }),
    ]);
    TestBed.tick();

    expect(store.events()).toHaveLength(1);
    expect(store.events()[0].state).toBe('CLOSED'); // the later state wins, not the earlier OPEN
    expect(NotificationCtor).toHaveBeenCalledTimes(1); // fired once, for the genuine OPEN
    store.release();
    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
  });
});
