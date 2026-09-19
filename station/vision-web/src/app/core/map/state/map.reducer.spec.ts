import { describe, expect, it } from 'vitest';
import type { AssetSummary, TelemetrySample } from '../../api/models';
import { MapApiActions, MapPageActions } from './map.actions';
import { initialMapState } from './map.model';
import { mapFeature } from './map.reducer';

const { reducer } = mapFeature;

function summary(overrides: Partial<AssetSummary> = {}): AssetSummary {
  return {
    assetId: 'a-1',
    displayName: 'Drone One',
    category: 'drone',
    categoryName: 'Drone',
    owner: 'owner-0',
    status: 'STREAMING',
    lifecycle: 'ACTIVE',
    attributes: {},
    ...overrides,
  };
}

const sample: TelemetrySample = { deviceId: 'dev-0', at: '2026-09-01T00:00:00Z', latitude: 1, longitude: 2 };

describe('map reducer', () => {
  it('starts with no assets, no trackers, no active consumers', () => {
    expect(initialMapState).toEqual({ assets: [], activeConsumers: 0, trackers: {} });
  });

  it('activated resets assets/trackers to initial while bumping the ref-count — a fresh mount drops stale global state', () => {
    const seeded = reducer(initialMapState, MapApiActions.assetsLoaded({ assets: [summary()] }));
    const reactivated = reducer(seeded, MapPageActions.activated());
    expect(reactivated).toEqual({ assets: [], activeConsumers: 1, trackers: {} });

    const second = reducer(reactivated, MapPageActions.activated());
    expect(second.activeConsumers).toBe(2);
  });

  it('released decrements the ref-count, floored at zero, without touching assets/trackers', () => {
    const active = reducer(initialMapState, MapPageActions.activated());
    const withAssets = reducer(active, MapApiActions.assetsLoaded({ assets: [summary()] }));
    const released = reducer(withAssets, MapPageActions.released());
    expect(released.activeConsumers).toBe(0);
    expect(released.assets).toEqual([summary()]);

    expect(reducer(released, MapPageActions.released()).activeConsumers).toBe(0); // never negative
  });

  it('assetsLoaded replaces the asset list; assetsLoadFailed is a no-op, leaving the last-known value', () => {
    const loaded = reducer(initialMapState, MapApiActions.assetsLoaded({ assets: [summary()] }));
    expect(loaded.assets).toEqual([summary()]);

    const failed = reducer(loaded, MapApiActions.assetsLoadFailed());
    expect(failed).toBe(loaded);
  });

  it('trackersReconciled only adds a hollow entry per started id — stopped ids are left for a later Tracker Removed', () => {
    const state = reducer(initialMapState, MapPageActions.trackersReconciled({ started: ['a-1', 'a-2'], stopped: ['a-9'] }));
    expect(state.trackers).toEqual({
      'a-1': { backfill: [], usageId: undefined },
      'a-2': { backfill: [], usageId: undefined },
    });
  });

  it('trackersReconciled with no started ids is a no-op', () => {
    const state = reducer(initialMapState, MapPageActions.trackersReconciled({ started: [], stopped: ['a-9'] }));
    expect(state).toBe(initialMapState);
  });

  it('trackerRemoved deletes exactly one tracker, and is a no-op for an id that is not tracked', () => {
    const seeded = reducer(initialMapState, MapPageActions.trackersReconciled({ started: ['a-1', 'a-2'], stopped: [] }));
    const removed = reducer(seeded, MapPageActions.trackerRemoved({ assetId: 'a-1' }));
    expect(Object.keys(removed.trackers)).toEqual(['a-2']);

    const noop = reducer(removed, MapPageActions.trackerRemoved({ assetId: 'a-1' }));
    expect(noop).toBe(removed);
  });

  it('trackerBackfillLoaded seeds the backfill and usageId for a tracker that exists, and no-ops otherwise', () => {
    const seeded = reducer(initialMapState, MapPageActions.trackersReconciled({ started: ['a-1'], stopped: [] }));
    const loaded = reducer(seeded, MapApiActions.trackerBackfillLoaded({ assetId: 'a-1', samples: [sample], usageId: 'u-1' }));
    expect(loaded.trackers['a-1']).toEqual({ backfill: [sample], usageId: 'u-1' });

    const orphan = reducer(initialMapState, MapApiActions.trackerBackfillLoaded({ assetId: 'a-9', samples: [sample], usageId: 'u-1' }));
    expect(orphan).toBe(initialMapState); // defense-in-depth — the lifecycle effect should never let this arrive
  });

  it('trackerPollLoaded replaces the backfill without touching usageId, for a tracker that exists', () => {
    const seeded = reducer(initialMapState, MapPageActions.trackersReconciled({ started: ['a-1'], stopped: [] }));
    const withBackfill = reducer(seeded, MapApiActions.trackerBackfillLoaded({ assetId: 'a-1', samples: [sample], usageId: 'u-1' }));
    const moved: TelemetrySample = { ...sample, latitude: 9 };
    const polled = reducer(withBackfill, MapApiActions.trackerPollLoaded({ assetId: 'a-1', samples: [sample, moved] }));
    expect(polled.trackers['a-1']).toEqual({ backfill: [sample, moved], usageId: 'u-1' });

    const orphan = reducer(initialMapState, MapApiActions.trackerPollLoaded({ assetId: 'a-9', samples: [sample] }));
    expect(orphan).toBe(initialMapState);
  });

  it('selectTrackerIds derives the tracked asset ids', () => {
    const state = reducer(initialMapState, MapPageActions.trackersReconciled({ started: ['a-1', 'a-2'], stopped: [] }));
    expect(mapFeature.selectTrackerIds.projector(state.trackers)).toEqual(['a-1', 'a-2']);
  });
});
