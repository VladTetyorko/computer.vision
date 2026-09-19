import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideMockActions } from '@ngrx/effects/testing';
import type { Action } from '@ngrx/store';
import { Store, provideState, provideStore } from '@ngrx/store';
import { ReplaySubject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { ActiveStream, Device } from '../../api/models';
import { VisionApi } from '../../api/vision-api';
import { LiveSocketActions } from '../../live/state/live.actions';
import { liveFeature } from '../../live/state/live.reducer';
import { PollScheduler } from '../../poll-scheduler';
import { ToastService } from '../../toast.service';
import { FleetApiActions, FleetPageActions } from './fleet.actions';
import { assignDevice$, gate$, loadRosters$, notifyFailure$, notifyRefreshFailure$, notifySuccess$, register$ } from './fleet.effects';
import { fleetFeature } from './fleet.reducer';

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function device(partial: Partial<Device> = {}): Device {
  return {
    id: 'dev-0',
    name: 'device',
    capabilities: ['VIDEO'],
    protocol: 'sim',
    uri: 'sim://demo',
    options: {},
    state: 'ACTIVE',
    ...partial,
  };
}

function stream(partial: Partial<ActiveStream> = {}): ActiveStream {
  return { streamId: 'stream-0', deviceId: 'dev-0', startedAt: '2026-07-24T00:00:00Z', ...partial };
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
  const api = {
    listDevices: vi.fn().mockResolvedValue([]),
    listStreams: vi.fn().mockResolvedValue([]),
    getCvModels: vi.fn().mockResolvedValue({ models: [] }),
    getCvTrackers: vi.fn().mockResolvedValue({ trackers: [] }),
    ...apiOverrides,
  };
  TestBed.configureTestingModule({
    providers: [
      provideMockActions(() => actions),
      provideStore(),
      provideState(fleetFeature),
      provideState(liveFeature),
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: scheduler },
      { provide: ToastService, useValue: { ok: vi.fn(), info: vi.fn(), error: vi.fn(), notify: vi.fn() } },
    ],
  });
  const store = TestBed.inject(Store);
  const toasts = TestBed.inject(ToastService);
  return { actions, api, scheduler, store, toasts };
}

describe('fleet effects — gate$ (live-axis-only poll gate)', () => {
  it('fetches immediately at subscription (connectionState starts closed) with quiet:false, and schedules the recurring poll', async () => {
    const { api, scheduler, store } = setup({ listDevices: vi.fn().mockResolvedValue([device()]) });
    TestBed.runInInjectionContext(() => gate$()).subscribe((action) => store.dispatch(action));
    await flush();

    expect(api.listDevices).toHaveBeenCalledOnce();
    expect(scheduler.lastFor(5_000)).toBeDefined();
    expect(store.selectSignal(fleetFeature.selectAllDevices)().map((d) => d.id)).toEqual(['dev-0']);
  });

  it('the very first boot fetch is not quiet; a later reconnect-driven re-entry into poll is quiet', async () => {
    const { api, store } = setup({ listDevices: vi.fn().mockRejectedValueOnce(new Error('down')) });
    TestBed.runInInjectionContext(() => gate$()).subscribe((action) => store.dispatch(action));
    await flush();
    expect(store.selectSignal(fleetFeature.selectReachable)()).toBe(false);

    // Go live, then fall back — this second poll-entry must be quiet.
    store.dispatch(LiveSocketActions.opened());
    api.listDevices.mockRejectedValueOnce(new Error('down again'));
    store.dispatch(LiveSocketActions.closed());
    await flush();

    // Both failures set reachable:false; distinguishing quiet is covered by notifyRefreshFailure$ below.
    expect(store.selectSignal(fleetFeature.selectReachable)()).toBe(false);
  });

  it('stops the poll once live opens, with no reconcile fetch — the `devices` topic supersedes it', async () => {
    const { api, scheduler, store } = setup();
    TestBed.runInInjectionContext(() => gate$()).subscribe((action) => store.dispatch(action));
    await flush();
    const poll = scheduler.lastFor(5_000);
    api.listDevices.mockClear();

    store.dispatch(LiveSocketActions.opened());
    await flush();

    expect(poll?.stop).toHaveBeenCalledOnce();
    expect(api.listDevices).not.toHaveBeenCalled();
  });

  it('resumes polling immediately, quietly, when live drops mid-session', async () => {
    const { api, store } = setup({ listDevices: vi.fn().mockResolvedValue([device({ id: 'dev-7' })]) });
    TestBed.runInInjectionContext(() => gate$()).subscribe((action) => store.dispatch(action));
    await flush();
    api.listDevices.mockClear();

    store.dispatch(LiveSocketActions.opened());
    store.dispatch(LiveSocketActions.closed());
    await flush();

    expect(api.listDevices).toHaveBeenCalledOnce();
    expect(store.selectSignal(fleetFeature.selectAllDevices)().map((d) => d.id)).toEqual(['dev-7']);
  });

  it('skips an overlapping recurring tick while the previous fetch is still pending (exhaustMap)', async () => {
    let resolveFirst!: (value: [Device[], ActiveStream[]]) => void;
    const listDevices = vi
      .fn()
      .mockImplementationOnce(() => new Promise<Device[]>((resolve) => (resolveFirst = () => resolve([]))))
      .mockResolvedValue([]);
    const scheduler = stubScheduler();
    const { store } = setup({ listDevices }, scheduler);
    TestBed.runInInjectionContext(() => gate$()).subscribe((action) => store.dispatch(action));
    await flush();

    // Fires two ticks back to back while the first fetch's devices call is still unresolved.
    scheduler.lastFor(5_000)?.callback();
    scheduler.lastFor(5_000)?.callback();
    await flush();
    expect(listDevices).toHaveBeenCalledTimes(1); // the second tick was dropped, not queued

    resolveFirst([[], []] as never);
    await flush();
  });
});

describe('fleet effects — loadRosters$ (one-shot, triggered by FleetPageActions.booted)', () => {
  it('does nothing until booted() fires', async () => {
    const { api, store } = setup();
    TestBed.runInInjectionContext(() => loadRosters$()).subscribe((action) => store.dispatch(action));
    await flush();
    expect(api.getCvModels).not.toHaveBeenCalled();
  });

  it('fetches both rosters once booted() fires, and folds them independently', async () => {
    const { actions, api, store } = setup({
      getCvModels: vi.fn().mockResolvedValue({ models: [{ id: 'm-1' }] }),
      getCvTrackers: vi.fn().mockRejectedValue(new Error('no tracking yet')),
    });
    TestBed.runInInjectionContext(() => loadRosters$()).subscribe((action) => store.dispatch(action));

    // `loadRosters$` reads the mocked `Actions` stream (`provideMockActions`), not the real `Store`'s
    // own dispatch pipeline — `actions.next(...)` is what triggers it; `store.dispatch(...)` only
    // folds whatever the effect itself already emitted back onto real state (see the `.subscribe`
    // above), and would never reach this effect's own `ofType(FleetPageActions.booted)` filter.
    actions.next(FleetPageActions.booted());
    await flush();

    expect(store.selectSignal(fleetFeature.selectModels)()).toEqual([{ id: 'm-1' }]);
    expect(store.selectSignal(fleetFeature.selectTrackers)()).toEqual([]); // silent degrade, no throw
  });
});

describe('fleet effects — a mutation effect (register$), the refresh-after-success shape every mutation shares', () => {
  it('dispatches registerSucceeded then reconciles devices/streams — never a targeted upsert', async () => {
    const registerDevice = vi.fn().mockResolvedValue(device({ id: 'dev-new' }));
    const listDevices = vi.fn().mockResolvedValue([device({ id: 'dev-new' })]);
    const { actions, store } = setup({ registerDevice, listDevices });
    const seen: Action[] = [];
    TestBed.runInInjectionContext(() => register$()).subscribe((action) => {
      seen.push(action);
      store.dispatch(action);
    });

    // See the identical `actions.next(...)` note in the `loadRosters$` test above.
    actions.next(FleetPageActions.registerRequested({ request: { deviceId: 'x' } as never }));
    await flush();

    expect(seen.map((a) => a.type)).toEqual([FleetApiActions.registerSucceeded.type, FleetApiActions.refreshSucceeded.type]);
    expect(store.selectSignal(fleetFeature.selectAllDevices)().map((d) => d.id)).toEqual(['dev-new']);
  });
});

describe('fleet effects — assignDevice$ (the one mutation with its own 409 classification)', () => {
  it('turns a 409 into the specific "already belongs to another asset" message', async () => {
    const assignDevice = vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 409 }));
    const { actions, store } = setup({ assignDevice });
    const seen: Action[] = [];
    TestBed.runInInjectionContext(() => assignDevice$()).subscribe((action) => seen.push(action));

    actions.next(FleetPageActions.assignDeviceRequested({ assetId: 'a-1', deviceId: 'd-1' }));
    await flush();

    expect(seen).toEqual([
      FleetApiActions.assignDeviceFailed({ error: 'That device already belongs to another asset — unassign it there first.' }),
    ]);
  });
});

describe('fleet effects — toasts', () => {
  it('notifyRefreshFailure$ toasts only when the failure is not quiet', () => {
    const { actions, toasts } = setup();
    TestBed.runInInjectionContext(() => notifyRefreshFailure$()).subscribe();

    actions.next(FleetApiActions.refreshFailed({ quiet: true, error: 'boom' }));
    expect(toasts.error).not.toHaveBeenCalled();

    actions.next(FleetApiActions.refreshFailed({ quiet: false, error: 'boom' }));
    expect(toasts.error).toHaveBeenCalledWith('boom');
  });

  it('notifyFailure$ toasts every mutation failure, generically', () => {
    const { actions, toasts } = setup();
    TestBed.runInInjectionContext(() => notifyFailure$()).subscribe();

    actions.next(FleetApiActions.registerFailed({ error: 'network down' }));
    expect(toasts.error).toHaveBeenCalledWith('network down');
  });

  it('notifySuccess$ renders the exact per-mutation text', () => {
    const { actions, toasts } = setup();
    TestBed.runInInjectionContext(() => notifySuccess$()).subscribe();

    actions.next(FleetApiActions.registerSucceeded({ device: device({ name: 'Camera 1' }) }));
    expect(toasts.ok).toHaveBeenCalledWith('Registered Camera 1.');

    actions.next(FleetApiActions.setDeviceStateSucceeded({ device: device({ name: 'Camera 1' }), state: 'DEACTIVATED' }));
    expect(toasts.ok).toHaveBeenCalledWith('Camera 1 is now deactivated.');

    actions.next(
      FleetApiActions.deleteAssetSucceeded({
        result: { displayName: 'Rover', devicesDeleted: 2, usagesRetained: 3, streamsStopped: 1 } as never,
      }),
    );
    expect(toasts.ok).toHaveBeenCalledWith('Archived Rover — 2 device(s) archived, 3 usage(s) retained, 1 stream(s) stopped.');
  });
});
