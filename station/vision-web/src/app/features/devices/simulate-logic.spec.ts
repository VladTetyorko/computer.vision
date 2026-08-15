import { describe, expect, it } from 'vitest';
import type { AssetDetails, Device } from '../../core/api/models';
import { isSimulatedAsset, mapSimulatedDevices } from './simulate-logic';

/**
 * `buildSimulationRequest`/`buildSyntheticRegisterRequest`'s own tests moved to
 * `core/fleet/simulation-logic.spec.ts` alongside the functions themselves (docs/plans/done/UX-REWORK-PLAN.md
 * §U-d) — this file keeps only the Warehouse-page-specific display logic.
 */

function device(partial: Partial<Device>): Device {
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

function asset(partial: Partial<AssetDetails>): AssetDetails {
  return {
    assetId: 'a-0',
    displayName: 'asset',
    category: 'simulated',
    categoryName: 'Simulated',
    owner: 'owner-0',
    status: 'OFFLINE',
    lifecycle: 'ACTIVE',
    attributes: {},
    devices: [],
    recentUsages: [],
    ...partial,
  };
}

describe('isSimulatedAsset', () => {
  it('is true for the simulated category', () => {
    expect(isSimulatedAsset({ category: 'simulated' })).toBe(true);
  });

  it('is false for any other category', () => {
    expect(isSimulatedAsset({ category: 'drone' })).toBe(false);
  });
});

describe('mapSimulatedDevices', () => {
  it('maps each device id to its owning asset', () => {
    const a = asset({ assetId: 'a-1', displayName: 'Sim 1', devices: [device({ id: 'dev-1' }), device({ id: 'dev-2' })] });
    const b = asset({ assetId: 'a-2', displayName: 'Sim 2', devices: [device({ id: 'dev-3' })] });

    const map = mapSimulatedDevices([a, b]);

    expect(map.get('dev-1')).toEqual({ assetId: 'a-1', displayName: 'Sim 1' });
    expect(map.get('dev-2')).toEqual({ assetId: 'a-1', displayName: 'Sim 1' });
    expect(map.get('dev-3')).toEqual({ assetId: 'a-2', displayName: 'Sim 2' });
  });

  it('returns an empty map for no simulated assets', () => {
    expect(mapSimulatedDevices([]).size).toBe(0);
  });

  it('returns an empty map when simulated assets have no devices', () => {
    expect(mapSimulatedDevices([asset({ devices: [] })]).size).toBe(0);
  });
});
