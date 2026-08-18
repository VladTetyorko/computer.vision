import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { SystemStatusStore } from './system-status-store';
import { VisionApi } from '../api/vision-api';
import { PollScheduler } from '../poll-scheduler';
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
): { store: SystemStatusStore; scheduler: ReturnType<typeof stubScheduler> } {
  TestBed.configureTestingModule({
    providers: [
      SystemStatusStore,
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: scheduler },
    ],
  });
  return { store: TestBed.inject(SystemStatusStore), scheduler };
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
});
