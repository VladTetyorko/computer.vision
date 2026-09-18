import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { SystemStatusStore } from './system-status-store';
import { VisionApi } from '../api/vision-api';
import { PollScheduler } from '../poll-scheduler';
import { LiveFacade } from '../live/live-facade';
import type { LiveConnectionState } from '../live/live-fallback-logic';
import type { SystemStatus } from '../api/models';

/** Mirrors `fleet-store.spec.ts#stubScheduler` — captures registrations, no real timers. */
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

/** `connectionState` seeded `'closed'` — reproduces today's (pre-D1) behaviour exactly, see
 *  `marks-store.spec.ts`'s identical `stubLiveFacade` doc comment. `systemStatus` starts `undefined`;
 *  `push` drives the always-on `system` topic this store now projects directly. */
function stubLiveFacade() {
  const systemStatus = signal<SystemStatus | undefined>(undefined);
  const connectionState = signal<LiveConnectionState>('closed');
  return {
    systemStatus: systemStatus.asReadonly(),
    push: (value: SystemStatus) => systemStatus.set(value),
    connectionState,
  };
}

function status(partial: Partial<SystemStatus> = {}): SystemStatus {
  return {
    overall: 'OK',
    checkedAt: '2026-08-15T00:00:00Z',
    subsystems: [],
    ...partial,
  };
}

function create(
  api: { systemStatus: ReturnType<typeof vi.fn> },
  scheduler: ReturnType<typeof stubScheduler> = stubScheduler(),
  live: ReturnType<typeof stubLiveFacade> = stubLiveFacade(),
): { store: SystemStatusStore; scheduler: ReturnType<typeof stubScheduler>; live: ReturnType<typeof stubLiveFacade> } {
  TestBed.configureTestingModule({
    providers: [
      SystemStatusStore,
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: scheduler },
      { provide: LiveFacade, useValue: live },
    ],
  });
  const store = TestBed.inject(SystemStatusStore);
  // This store has no `activate()` (see class doc) — construction's own reconnect-driven `effect()`
  // is the one and only "first activation" trigger, so it must be flushed here for every test,
  // mirroring how `MarksStore`/`DrawingsStore` specs `TestBed.tick()` after their own `activate()`.
  TestBed.tick();
  return { store, scheduler, live };
}

/** Lets the fire-and-forget promise chain inside `refresh()` settle before asserting. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('SystemStatusStore', () => {
  it('fetches immediately at construction and registers a 15s recurring poll', async () => {
    const api = { systemStatus: vi.fn().mockResolvedValue(status()) };
    const { scheduler } = create(api);
    await flush();

    expect(api.systemStatus).toHaveBeenCalledOnce();
    expect(scheduler.lastFor(15_000)).toBeDefined();
  });

  it('exposes the fetched status and its overall health', async () => {
    const api = { systemStatus: vi.fn().mockResolvedValue(status({ overall: 'DEGRADED' })) };
    const { store } = create(api);
    await flush();

    expect(store.status()?.overall).toBe('DEGRADED');
    expect(store.overall()).toBe('DEGRADED');
    expect(store.error()).toBeUndefined();
  });

  it('overall is undefined before the first fetch ever succeeds', () => {
    const api = { systemStatus: vi.fn(() => new Promise<SystemStatus>(() => {})) };
    const { store } = create(api);

    expect(store.status()).toBeUndefined();
    expect(store.overall()).toBeUndefined();
  });

  it('degrades to stale-but-present data on a later poll failure, never wiping the page', async () => {
    const api = { systemStatus: vi.fn().mockResolvedValue(status({ overall: 'OK' })) };
    const { store, scheduler } = create(api);
    await flush();
    expect(store.status()?.overall).toBe('OK');

    api.systemStatus.mockRejectedValueOnce(new Error('network down'));
    await scheduler.lastFor(15_000)?.callback();
    await flush();

    expect(store.status()?.overall).toBe('OK'); // stale value retained, not cleared
    expect(store.error()).toBeDefined(); // but the failure is still surfaced
  });

  it('clears a previous error once a later poll succeeds again', async () => {
    const api = { systemStatus: vi.fn().mockRejectedValueOnce(new Error('down')) };
    const { store, scheduler } = create(api);
    await flush();
    expect(store.error()).toBeDefined();

    api.systemStatus.mockResolvedValueOnce(status());
    await scheduler.lastFor(15_000)?.callback();
    await flush();

    expect(store.error()).toBeUndefined();
    expect(store.status()).toBeDefined();
  });

  describe('the `system` SSE topic', () => {
    it('projects a live arrival directly onto status/overall, and clears a previous error', async () => {
      const api = { systemStatus: vi.fn().mockRejectedValueOnce(new Error('down')) };
      const { store, live } = create(api);
      await flush();
      expect(store.error()).toBeDefined();

      live.push(status({ overall: 'DEGRADED' }));
      TestBed.tick();

      expect(store.status()?.overall).toBe('DEGRADED');
      expect(store.overall()).toBe('DEGRADED');
      expect(store.error()).toBeUndefined();
    });
  });

  describe('live gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1) — live axis only, no `activeConsumers` term', () => {
    /**
     * The plan's own frozen acceptance criterion (§5): a poll that stops must still reconcile on
     * reconnect. With the store constructed (this store has no separate "active" state — see class
     * doc) and live already `'open'`, driving `connectionState` through `open → closed → open` must
     * issue **exactly one** REST refresh on (re-)entering `open`, and **zero** REST requests for as
     * long as `open` persists.
     */
    it('reconciles exactly once on reconnect, and stays silent for as long as live holds', async () => {
      const api = { systemStatus: vi.fn().mockResolvedValue(status()) };
      const { live } = create(api);
      await flush();
      // construction itself already fetched once (connectionState seeded 'closed', pre-D1 shape).

      live.connectionState.set('open');
      TestBed.tick();
      await flush();
      // (the first entry into live already reconciled once here — not the segment under test)

      live.connectionState.set('closed');
      TestBed.tick();
      await flush();
      // Falling back to polling refreshes immediately too (the table's own
      // `false | true (was live) → refresh once, then start poll` row) — a separate, legitimate
      // call, also not the segment under test. Only now do we isolate "entering open".
      api.systemStatus.mockClear();

      live.connectionState.set('open');
      TestBed.tick();
      await flush();

      expect(api.systemStatus).toHaveBeenCalledTimes(1); // exactly one refresh, entering 'open'

      // Signals don't re-notify on an equal write — driving 'open' again is a no-op, not a second call.
      live.connectionState.set('open');
      TestBed.tick();
      await flush();
      expect(api.systemStatus).toHaveBeenCalledTimes(1);
    });

    it('constructing while already live does one initial GET (the reconcile), not zero, and never schedules the poll', async () => {
      const api = { systemStatus: vi.fn().mockResolvedValue(status()) };
      const live = stubLiveFacade();
      live.connectionState.set('open');
      const { scheduler } = create(api, stubScheduler(), live);
      await flush();

      expect(api.systemStatus).toHaveBeenCalledTimes(1);
      expect(scheduler.schedule).not.toHaveBeenCalled();
    });
  });
});
