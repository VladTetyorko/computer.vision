import { describe, expect, it } from 'vitest';
import type { ActiveStream, Device } from '../../api/models';
import { LiveSocketActions } from '../../live/state/live.actions';
import { FleetApiActions, FleetPageActions } from './fleet.actions';
import { initialFleetState } from './fleet.model';
import { fleetFeature } from './fleet.reducer';

const { reducer } = fleetFeature;

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

describe('fleetFeature reducer', () => {
  it('starts with an empty, not-yet-reachable state', () => {
    expect(fleetFeature.selectAllDevices.projector(initialFleetState.devices)).toEqual([]);
    expect(fleetFeature.selectAllStreams.projector(initialFleetState.streams)).toEqual([]);
    expect(initialFleetState.reachable).toBeNull();
    expect(initialFleetState.loading).toBe(false);
  });

  it('refreshRequested sets loading true — the explicit "Refresh" button only', () => {
    const state = reducer(initialFleetState, FleetPageActions.refreshRequested({ quiet: false }));
    expect(state.loading).toBe(true);
  });

  it('refreshSucceeded folds both lists atomically, marks reachable, and clears loading', () => {
    const loading = reducer(initialFleetState, FleetPageActions.refreshRequested({ quiet: false }));
    const state = reducer(loading, FleetApiActions.refreshSucceeded({ devices: [device()], streams: [stream()] }));

    expect(fleetFeature.selectAllDevices.projector(state.devices).map((d) => d.id)).toEqual(['dev-0']);
    expect(fleetFeature.selectAllStreams.projector(state.streams).map((s) => s.streamId)).toEqual(['stream-0']);
    expect(state.reachable).toBe(true);
    expect(state.loading).toBe(false);
  });

  it('refreshFailed degrades to stale-but-present data — devices/streams untouched, reachable false', () => {
    const withData = reducer(initialFleetState, FleetApiActions.refreshSucceeded({ devices: [device()], streams: [] }));
    const failed = reducer(withData, FleetApiActions.refreshFailed({ quiet: true, error: 'offline' }));

    expect(fleetFeature.selectAllDevices.projector(failed.devices)).toHaveLength(1);
    expect(failed.reachable).toBe(false);
    expect(failed.loading).toBe(false);
  });

  it('modelsLoaded/trackersLoaded set their roster; the matching *LoadFailed needs no handler (identity no-op)', () => {
    const withModels = reducer(initialFleetState, FleetApiActions.modelsLoaded({ models: [{ id: 'm-1', label: 'YOLO', kind: 'DETECTION' } as never] }));
    expect(withModels.models).toHaveLength(1);

    const afterFailure = reducer(withModels, FleetApiActions.modelsLoadFailed());
    expect(afterFailure).toBe(withModels); // true identity no-op — no handler registered

    const withTrackers = reducer(initialFleetState, FleetApiActions.trackersLoaded({ trackers: [{ id: 't-1' } as never] }));
    expect(withTrackers.trackers).toHaveLength(1);
    expect(reducer(withTrackers, FleetApiActions.trackersLoadFailed())).toBe(withTrackers);
  });

  describe('the always-on `devices` SSE topic', () => {
    it('folds a devices envelope atomically and marks reachable', () => {
      const state = reducer(
        initialFleetState,
        LiveSocketActions.envelopeReceived({
          envelope: { seq: 1, type: 'devices', payload: { devices: [device({ id: 'dev-9' })], streams: [stream({ deviceId: 'dev-9' })] } },
        }),
      );

      expect(fleetFeature.selectAllDevices.projector(state.devices).map((d) => d.id)).toEqual(['dev-9']);
      expect(fleetFeature.selectAllStreams.projector(state.streams).map((s) => s.deviceId)).toEqual(['dev-9']);
      expect(state.reachable).toBe(true);
    });

    it('ignores every other envelope type — a true identity no-op', () => {
      const state = reducer(
        initialFleetState,
        LiveSocketActions.envelopeReceived({ envelope: { seq: 1, type: 'fleet', payload: [] } }),
      );
      expect(state).toBe(initialFleetState);
    });
  });

  describe('selectLiveDeviceIds', () => {
    it('derives the set of device ids with a running stream', () => {
      const state = reducer(
        initialFleetState,
        FleetApiActions.refreshSucceeded({ devices: [device({ id: 'a' }), device({ id: 'b' })], streams: [stream({ deviceId: 'a' })] }),
      );
      expect(fleetFeature.selectLiveDeviceIds.projector(fleetFeature.selectAllStreams.projector(state.streams))).toEqual(new Set(['a']));
    });
  });

  it('every mutation Succeeded action needs no reducer handler — a full refresh always follows (see fleet.effects.ts)', () => {
    const state = reducer(initialFleetState, FleetApiActions.registerSucceeded({ device: device() }));
    expect(state).toBe(initialFleetState);
  });
});
