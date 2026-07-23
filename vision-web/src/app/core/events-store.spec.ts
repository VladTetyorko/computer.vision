import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EventsStore } from './events-store';
import { VisionApi } from './api/vision-api';
import { PollScheduler } from './poll-scheduler';
import { SettingsStore } from './settings-store';
import type { DetectionEvent } from './api/models';

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

function create(options: {
  events?: ReturnType<typeof vi.fn>;
  scheduler?: unknown;
  eventNotifications?: boolean;
} = {}): { store: EventsStore; api: { events: ReturnType<typeof vi.fn> } } {
  const api = { events: options.events ?? vi.fn().mockResolvedValue([]) };
  const providers: unknown[] = [
    { provide: VisionApi, useValue: api },
    { provide: SettingsStore, useValue: stubSettings(options.eventNotifications ?? false) },
  ];
  if (options.scheduler) {
    providers.push({ provide: PollScheduler, useValue: options.scheduler });
  }
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
});
