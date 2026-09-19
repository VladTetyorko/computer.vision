import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import type { ActiveStream, Device } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { PollScheduler } from '../poll-scheduler';
import { ToastService } from '../toast.service';
import { provideAppState } from '../state/app-state';
import { FleetFacade } from './fleet-facade';

/**
 * `FleetFacade`'s own read/dispatch boundary spec (docs/plans/active/NGRX-MIGRATION-PLAN.md wave
 * N4b) — `provideAppState()` registers the real `fleet` slice + its effects (fleet is root, per
 * `core/state/app-state.ts`'s own doc comment), with `VisionApi`/`PollScheduler`/`ToastService`
 * overridden by plain stubs, mirroring `core/ops/thresholds-facade.spec.ts`'s own established
 * pattern for a root facade whose constructor kicks off real dispatch work. `fleet.reducer.spec.ts`/
 * `fleet.effects.spec.ts` already cover the slice's own folding/gating logic in detail; this file
 * only proves the facade's own signals/methods read and dispatch correctly end to end.
 */
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

function create(apiOverrides: Record<string, ReturnType<typeof vi.fn>> = {}) {
  const api = {
    listDevices: vi.fn().mockResolvedValue([]),
    listStreams: vi.fn().mockResolvedValue([]),
    getCvModels: vi.fn().mockResolvedValue({ models: [] }),
    getCvTrackers: vi.fn().mockResolvedValue({ trackers: [] }),
    ...apiOverrides,
  };
  TestBed.configureTestingModule({
    providers: [
      provideAppState(),
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: { schedule: vi.fn().mockReturnValue(vi.fn()) } },
      { provide: ToastService, useValue: { ok: vi.fn(), info: vi.fn(), error: vi.fn(), notify: vi.fn() } },
    ],
  });
  return { facade: TestBed.inject(FleetFacade), api };
}

describe('FleetFacade', () => {
  it('boots by fetching devices/streams (gate$) and the CV rosters (loadRosters$, via its own Booted dispatch)', async () => {
    const { facade, api } = create({
      listDevices: vi.fn().mockResolvedValue([device()]),
      listStreams: vi.fn().mockResolvedValue([stream()]),
      getCvModels: vi.fn().mockResolvedValue({ models: [{ id: 'm-1', label: 'YOLO', kind: 'DETECTION' } as never] }),
    });
    await flush();

    expect(api.listDevices).toHaveBeenCalledOnce();
    expect(facade.devices().map((d) => d.id)).toEqual(['dev-0']);
    expect(facade.streams().map((s) => s.streamId)).toEqual(['stream-0']);
    expect(facade.reachable()).toBe(true);
    expect(facade.models()).toEqual([{ id: 'm-1', label: 'YOLO', kind: 'DETECTION' }]);
    expect(facade.liveDeviceIds()).toEqual(new Set(['dev-0']));
  });

  it('streamFor/device resolve from the already-fetched signals', async () => {
    const { facade } = create({
      listDevices: vi.fn().mockResolvedValue([device({ id: 'dev-9' })]),
      listStreams: vi.fn().mockResolvedValue([stream({ streamId: 's-9', deviceId: 'dev-9' })]),
    });
    await flush();

    expect(facade.device('dev-9')?.id).toBe('dev-9');
    expect(facade.streamFor('dev-9')?.streamId).toBe('s-9');
    expect(facade.device('nope')).toBeUndefined();
  });

  it('refresh({quiet}) dispatches and resolves once the reconcile settles', async () => {
    const listDevices = vi.fn().mockResolvedValue([]).mockResolvedValueOnce([]).mockResolvedValueOnce([device({ id: 'later' })]);
    const { facade } = create({ listDevices });
    await flush();

    await facade.refresh({ quiet: true });
    expect(facade.devices().map((d) => d.id)).toEqual(['later']);
  });

  it('register() resolves the created device and reconciles the roster', async () => {
    const registerDevice = vi.fn().mockResolvedValue(device({ id: 'dev-new' }));
    const { facade } = create({ registerDevice, listDevices: vi.fn().mockResolvedValue([device({ id: 'dev-new' })]) });
    await flush();

    const result = await facade.register({ deviceId: 'x' } as never);

    expect(result?.id).toBe('dev-new');
    expect(registerDevice).toHaveBeenCalledWith({ deviceId: 'x' });
    expect(facade.devices().map((d) => d.id)).toEqual(['dev-new']);
  });

  it('a failed mutation resolves the documented fallback value, never throwing', async () => {
    const registerDevice = vi.fn().mockRejectedValue(new Error('down'));
    const { facade } = create({ registerDevice });
    await flush();

    await expect(facade.register({ deviceId: 'x' } as never)).resolves.toBeNull();
  });

  it('getStreamTracks() is a direct VisionApi passthrough — rethrows on failure, never toasts (see class doc)', async () => {
    const getStreamTracks = vi.fn().mockRejectedValue(new Error('stream gone'));
    const { facade } = create({ getStreamTracks });
    await flush();

    await expect(facade.getStreamTracks('s-1')).rejects.toThrow('stream gone');
  });
});
