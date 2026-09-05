import { describe, expect, it } from 'vitest';
import type { AssetDetails, Device } from '../../core/api/models';
import { mapSimulatedDevices } from './simulate-logic';

/**
 * `buildSimulationRequest`/`buildSyntheticRegisterRequest`'s own tests moved to
 * `core/fleet/simulation-logic.spec.ts` alongside the functions themselves (docs/plans/done/UX-REWORK-PLAN.md
 * §U-d) — this file keeps only the Warehouse-page-specific display logic.
 *
 * `isSimulatedAsset`'s own tests are gone with the function (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md
 * D6) — see `simulate-logic.ts`'s own doc comment.
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

function asset(partial: Partial<AssetDetails> = {}): AssetDetails {
  return {
    assetId: 'a-0',
    displayName: 'asset',
    category: 'drone',
    categoryName: 'Drone',
    owner: 'owner-0',
    status: 'OFFLINE',
    lifecycle: 'ACTIVE',
    attributes: {},
    devices: [],
    recentUsages: [],
    ...partial,
  };
}

describe('mapSimulatedDevices', () => {
  it('maps a SIMULATED-origin device id to its owning asset', () => {
    const a = asset({
      assetId: 'a-1',
      displayName: 'Sim 1',
      devices: [device({ id: 'dev-1', origin: 'SIMULATED' }), device({ id: 'dev-2', origin: 'LIVE' })],
    });

    const map = mapSimulatedDevices([a]);

    expect(map.get('dev-1')).toEqual({ assetId: 'a-1', displayName: 'Sim 1' });
    expect(map.has('dev-2')).toBe(false);
  });

  it('finds a simulated device on an otherwise real (mixed) asset — the whole point of D6', () => {
    const a = asset({
      assetId: 'a-2',
      displayName: 'Mixed rig',
      category: 'drone',
      devices: [device({ id: 'dev-3', origin: 'LIVE' }), device({ id: 'dev-4', origin: 'SIMULATED' })],
    });

    const map = mapSimulatedDevices([a]);

    expect(map.get('dev-4')).toEqual({ assetId: 'a-2', displayName: 'Mixed rig' });
    expect(map.has('dev-3')).toBe(false);
  });

  it('treats an absent origin as not simulated — the wire always populates it, but never assume', () => {
    const a = asset({ devices: [device({ id: 'dev-5', origin: undefined })] });

    expect(mapSimulatedDevices([a]).size).toBe(0);
  });

  it('returns an empty map for no assets', () => {
    expect(mapSimulatedDevices([]).size).toBe(0);
  });

  it('returns an empty map when no asset has a simulated device', () => {
    expect(mapSimulatedDevices([asset({ devices: [device({ origin: 'LIVE' })] })]).size).toBe(0);
  });
});
