import { TestBed } from '@angular/core/testing';
import { provideMockActions } from '@ngrx/effects/testing';
import type { Action } from '@ngrx/store';
import { Store, provideState, provideStore } from '@ngrx/store';
import { ReplaySubject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { SystemStatus } from '../../api/models';
import { VisionApi } from '../../api/vision-api';
import { LiveSocketActions } from '../../live/state/live.actions';
import { liveFeature } from '../../live/state/live.reducer';
import { PollScheduler } from '../../poll-scheduler';
import { SystemStatusPageActions } from './system-status.actions';
import { gate$, manualRefresh$ } from './system-status.effects';
import { systemStatusFeature } from './system-status.reducer';

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function status(partial: Partial<SystemStatus> = {}): SystemStatus {
  return { overall: 'OK', checkedAt: '2026-08-15T00:00:00Z', subsystems: [], ...partial };
}

function stubScheduler() {
  const calls: { periodMs: number; callback: () => void; stop: ReturnType<typeof vi.fn> }[] = [];
  const schedule = vi.fn((periodMs: number, callback: () => void) => {
    const stop = vi.fn();
    calls.push({ periodMs, callback, stop });
    return stop;
  });
  const lastFor = (periodMs: number) => [...calls].reverse().find((call) => call.periodMs === periodMs);
  return { schedule, calls, lastFor };
}

function setup(apiOverrides: Record<string, ReturnType<typeof vi.fn>> = {}, scheduler = stubScheduler()) {
  const actions = new ReplaySubject<Action>(1);
  const api = { systemStatus: vi.fn().mockResolvedValue(status()), ...apiOverrides };
  TestBed.configureTestingModule({
    providers: [
      provideMockActions(() => actions),
      provideStore(),
      provideState(systemStatusFeature),
      provideState(liveFeature),
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: scheduler },
    ],
  });
  const store = TestBed.inject(Store);
  return { actions, api, scheduler, store };
}

describe('system-status effects — gate$ (live-axis-only poll gate)', () => {
  it('fetches immediately at subscription and schedules the recurring 15s poll', async () => {
    const { api, scheduler, store } = setup({ systemStatus: vi.fn().mockResolvedValue(status({ overall: 'DEGRADED' })) });
    TestBed.runInInjectionContext(() => gate$()).subscribe((action) => store.dispatch(action));
    await flush();

    expect(api.systemStatus).toHaveBeenCalledOnce();
    expect(scheduler.lastFor(15_000)).toBeDefined();
    expect(store.selectSignal(systemStatusFeature.selectOverall)()).toBe('DEGRADED');
  });

  it('stops the poll once live opens, with no reconcile fetch — the `system` topic supersedes it', async () => {
    const { api, scheduler, store } = setup();
    TestBed.runInInjectionContext(() => gate$()).subscribe((action) => store.dispatch(action));
    await flush();
    const poll = scheduler.lastFor(15_000);
    api.systemStatus.mockClear();

    store.dispatch(LiveSocketActions.opened());
    await flush();

    expect(poll?.stop).toHaveBeenCalledOnce();
    expect(api.systemStatus).not.toHaveBeenCalled();
  });

  it('resumes polling immediately when live drops mid-session', async () => {
    const { api, store } = setup({ systemStatus: vi.fn().mockResolvedValue(status({ overall: 'DOWN' })) });
    TestBed.runInInjectionContext(() => gate$()).subscribe((action) => store.dispatch(action));
    await flush();
    api.systemStatus.mockClear();

    store.dispatch(LiveSocketActions.opened());
    store.dispatch(LiveSocketActions.closed());
    await flush();

    expect(api.systemStatus).toHaveBeenCalledOnce();
    expect(store.selectSignal(systemStatusFeature.selectOverall)()).toBe('DOWN');
  });

  it('degrades to stale-but-present data on a later poll failure, never wiping status', async () => {
    const { api, scheduler, store } = setup();
    TestBed.runInInjectionContext(() => gate$()).subscribe((action) => store.dispatch(action));
    await flush();
    expect(store.selectSignal(systemStatusFeature.selectStatus)()?.overall).toBe('OK');

    api.systemStatus.mockRejectedValueOnce(new Error('network down'));
    scheduler.lastFor(15_000)?.callback();
    await flush();

    expect(store.selectSignal(systemStatusFeature.selectStatus)()?.overall).toBe('OK');
    expect(store.selectSignal(systemStatusFeature.selectError)()).toBeDefined();
  });

  it('skips an overlapping recurring tick while the previous fetch is still pending (exhaustMap)', async () => {
    let resolveFirst!: (value: SystemStatus) => void;
    const systemStatus = vi
      .fn()
      .mockImplementationOnce(() => new Promise<SystemStatus>((resolve) => (resolveFirst = resolve)))
      .mockResolvedValue(status());
    const scheduler = stubScheduler();
    const { store } = setup({ systemStatus }, scheduler);
    TestBed.runInInjectionContext(() => gate$()).subscribe((action) => store.dispatch(action));
    await flush();

    scheduler.lastFor(15_000)?.callback();
    scheduler.lastFor(15_000)?.callback();
    await flush();
    expect(systemStatus).toHaveBeenCalledTimes(1);

    resolveFirst(status());
    await flush();
  });
});

describe('system-status effects — manualRefresh$', () => {
  it('re-fetches on an explicit Refresh Requested', async () => {
    const { actions, api, store } = setup();
    TestBed.runInInjectionContext(() => manualRefresh$()).subscribe((action) => store.dispatch(action));

    actions.next(SystemStatusPageActions.refreshRequested());
    await flush();

    expect(api.systemStatus).toHaveBeenCalledOnce();
  });
});
