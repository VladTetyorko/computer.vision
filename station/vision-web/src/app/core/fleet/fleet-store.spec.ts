import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { FleetStore } from './fleet-store';
import { VisionApi } from '../api/vision-api';
import { ToastService } from '../toast.service';
import { PollScheduler } from '../poll-scheduler';
import { LiveStore, type LiveConnectionState } from '../live/live-store';
import type { ActiveStream, Device, DevicesSnapshot } from '../api/models';

/**
 * Targeted specs for `FleetStore`'s new `LiveStore` projection (docs/plans/done/REALTIME-PLAN.md §4's backend
 * follow-up batch) — this class had no dedicated spec file before (its pre-existing surface is a
 * thin, `run()`-wrapped pass-through over `VisionApi`, exercised indirectly by every page/store
 * that consumes it), but the poll-vs-live transport switch introduced here is exactly the kind of
 * pure-decision logic this codebase always covers directly, mirroring
 * `telemetry-store.spec.ts`/`detections-store.spec.ts`'s own "LiveStore projection" section.
 */

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
  return {
    streamId: 'stream-0',
    deviceId: 'dev-0',
    startedAt: '2026-07-24T00:00:00Z',
    ...partial,
  };
}

/**
 * A minimal `LiveStore` test double, mirroring `telemetry-store.spec.ts#stubLiveStore` — real
 * Angular `signal`s so `FleetStore`'s own `effect`s react to it exactly as they would to the real
 * class, without a real `EventSource` (jsdom has none). Defaults to `'closed'` — the same state the
 * *real* `LiveStore` reports under jsdom.
 */
function stubLiveStore(initialState: LiveConnectionState = 'closed') {
  const stateSignal = signal<LiveConnectionState>(initialState);
  const devicesSignal = signal<DevicesSnapshot | undefined>(undefined);
  return {
    connectionState: stateSignal.asReadonly(),
    devices: devicesSignal.asReadonly(),
    setState: (state: LiveConnectionState) => stateSignal.set(state),
    pushDevicesSnapshot: (snapshot: DevicesSnapshot) => devicesSignal.set(snapshot),
  };
}

/** Captures every `PollScheduler.schedule` registration so a test can assert on/off without real timers. */
function stubScheduler() {
  const calls: { periodMs: number; stop: ReturnType<typeof vi.fn> }[] = [];
  const schedule = vi.fn((periodMs: number) => {
    const stop = vi.fn();
    calls.push({ periodMs, stop });
    return stop;
  });
  const lastFor = (periodMs: number) => [...calls].reverse().find((call) => call.periodMs === periodMs);
  return { schedule, calls, lastFor };
}

function stubApi(
  overrides: Partial<Record<'listDevices' | 'listStreams' | 'getStreamTracks', ReturnType<typeof vi.fn>>> = {},
) {
  return {
    listDevices: vi.fn().mockResolvedValue([]),
    listStreams: vi.fn().mockResolvedValue([]),
    ...overrides,
  };
}

function stubToasts() {
  return { ok: vi.fn(), info: vi.fn(), error: vi.fn(), notify: vi.fn() };
}

function create(
  api: ReturnType<typeof stubApi>,
  options: { live?: ReturnType<typeof stubLiveStore>; scheduler?: ReturnType<typeof stubScheduler> } = {},
): FleetStore {
  const providers: unknown[] = [
    FleetStore,
    { provide: VisionApi, useValue: api },
    { provide: ToastService, useValue: stubToasts() },
  ];
  if (options.live) {
    providers.push({ provide: LiveStore, useValue: options.live });
  }
  if (options.scheduler) {
    providers.push({ provide: PollScheduler, useValue: options.scheduler });
  }
  TestBed.configureTestingModule({ providers });
  return TestBed.inject(FleetStore);
}

/** Lets the fire-and-forget promise chain inside `refresh()` settle before asserting. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('FleetStore — LiveStore projection (docs/plans/done/REALTIME-PLAN.md §4 backend follow-up batch)', () => {
  it('polls devices/streams immediately at construction, regardless of LiveStore', async () => {
    const api = stubApi({ listDevices: vi.fn().mockResolvedValue([device()]), listStreams: vi.fn().mockResolvedValue([]) });
    create(api);
    await flush();

    expect(api.listDevices).toHaveBeenCalled();
    expect(api.listStreams).toHaveBeenCalled();
  });

  it('applies a devices snapshot atomically when LiveStore delivers one, live', async () => {
    const api = stubApi();
    const live = stubLiveStore('open');
    const scheduler = stubScheduler();
    const store = create(api, { live, scheduler });
    await flush();

    live.pushDevicesSnapshot({ devices: [device({ id: 'dev-9' })], streams: [stream({ deviceId: 'dev-9' })] });
    TestBed.tick(); // flushes the devices-snapshot effect

    expect(store.devices().map((d) => d.id)).toEqual(['dev-9']);
    expect(store.streams().map((s) => s.deviceId)).toEqual(['dev-9']);
    expect(store.reachable()).toBe(true);
  });

  it('stops the poll once LiveStore opens', async () => {
    const api = stubApi();
    const live = stubLiveStore('closed');
    const scheduler = stubScheduler();
    create(api, { live, scheduler });
    await flush();
    const poll = scheduler.lastFor(5_000);
    expect(poll?.stop).not.toHaveBeenCalled();

    live.setState('open');
    TestBed.tick(); // flushes the transport-switch effect

    expect(poll?.stop).toHaveBeenCalledOnce(); // the poll is stopped, not left running alongside live
  });

  it('resumes polling immediately, fetching fresh data, when LiveStore drops mid-session', async () => {
    const api = stubApi({ listDevices: vi.fn().mockResolvedValue([device({ id: 'dev-7' })]) });
    const live = stubLiveStore('open');
    const scheduler = stubScheduler();
    create(api, { live, scheduler });
    await flush();
    api.listDevices.mockClear();

    live.setState('closed');
    TestBed.tick(); // flushes the transport-switch effect
    await flush();

    expect(api.listDevices).toHaveBeenCalledOnce(); // immediate re-fetch on falling back to polling
    expect(scheduler.lastFor(5_000)).toBeDefined(); // and the recurring poll is registered again
  });

  it('never double-registers the poll across repeated not-live transport evaluations', async () => {
    const api = stubApi();
    const live = stubLiveStore('closed');
    const scheduler = stubScheduler();
    create(api, { live, scheduler });
    await flush();

    TestBed.tick(); // the connectionState effect's first run — already not-live, must not re-poll

    expect(scheduler.schedule).toHaveBeenCalledTimes(1); // the constructor's own unconditional registration, only
  });
});

describe('FleetStore — tracking engine passthroughs (docs/plans/done/TRACKING-PLAN.md §4, wave T7)', () => {
  it('getStreamTracks delegates straight to VisionApi, with no run()-wrapped toast on failure', async () => {
    const getStreamTracks = vi.fn().mockResolvedValue({ streamId: 's-1', lockedTrackId: 7, tracks: [] });
    const api = stubApi({ getStreamTracks });
    const store = create(api);
    await flush();

    await expect(store.getStreamTracks('s-1')).resolves.toEqual({ streamId: 's-1', lockedTrackId: 7, tracks: [] });
    expect(getStreamTracks).toHaveBeenCalledWith('s-1');
  });

  it('getStreamTracks rethrows on failure — the caller decides what "hidden" means, not this store', async () => {
    const getStreamTracks = vi.fn().mockRejectedValue(new Error('offline'));
    const api = stubApi({ getStreamTracks });
    const store = create(api);
    await flush();

    await expect(store.getStreamTracks('s-1')).rejects.toThrow('offline');
  });
});
