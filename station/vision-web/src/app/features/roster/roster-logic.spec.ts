import { describe, expect, it } from 'vitest';
import type { AssetSummary, AssignedPilot, UserSummary } from '../../core/api/models';
import {
  buildRosterRows,
  countAssetsWithoutPilot,
  custodyStatusFor,
  custodyStatusLabel,
  custodyStatusTitle,
  searchRosterRows,
  sortAssetsByName,
} from './roster-logic';

function asset(partial: Partial<AssetSummary> = {}): AssetSummary {
  return {
    assetId: 'a-0',
    displayName: 'Asset',
    category: 'drone',
    categoryName: 'Drone',
    owner: 'org',
    status: 'OFFLINE',
    attributes: {},
    ...partial,
  };
}

function user(partial: Partial<UserSummary> = {}): UserSummary {
  return {
    userId: 'u-0',
    username: 'user',
    displayName: 'User',
    email: 'user@example.com',
    enabled: true,
    memberships: [],
    ...partial,
  };
}

describe('sortAssetsByName', () => {
  it('sorts alphabetically, case-insensitive', () => {
    const rows = sortAssetsByName([asset({ displayName: 'bravo' }), asset({ displayName: 'Alpha' })]);
    expect(rows.map((a) => a.displayName)).toEqual(['Alpha', 'bravo']);
  });

  it('does not mutate the input', () => {
    const list = [asset({ displayName: 'z' }), asset({ displayName: 'a' })];
    const original = [...list];
    sortAssetsByName(list);
    expect(list).toEqual(original);
  });
});

describe('buildRosterRows', () => {
  it('resolves each assigned pilot to a display name', () => {
    const assets = [asset({ assetId: 'a-1', displayName: 'Falcon' })];
    const pilotsByAsset = new Map<string, readonly AssignedPilot[]>([['a-1', [{ userId: 'u-1', role: 'PILOT' }]]]);
    const users = [user({ userId: 'u-1', displayName: 'Jane Pilot' })];
    const rows = buildRosterRows(assets, pilotsByAsset, users);
    expect(rows).toEqual([{ asset: assets[0], pilotNames: ['Jane Pilot'] }]);
  });

  it('falls back to a short id fragment for an unresolvable pilot', () => {
    const assets = [asset({ assetId: 'a-1' })];
    const pilotsByAsset = new Map<string, readonly AssignedPilot[]>([['a-1', [{ userId: 'unknown-user-id', role: 'PILOT' }]]]);
    const rows = buildRosterRows(assets, pilotsByAsset, []);
    expect(rows[0].pilotNames).toEqual(['unknown-']);
  });

  it('gives an asset with no pilots-map entry an empty pilotNames array', () => {
    const assets = [asset({ assetId: 'a-1' })];
    const rows = buildRosterRows(assets, new Map(), []);
    expect(rows[0].pilotNames).toEqual([]);
  });

  it('sorts assets by name', () => {
    const assets = [asset({ assetId: 'b', displayName: 'Bravo' }), asset({ assetId: 'a', displayName: 'Alpha' })];
    const rows = buildRosterRows(assets, new Map(), []);
    expect(rows.map((r) => r.asset.assetId)).toEqual(['a', 'b']);
  });
});

describe('searchRosterRows', () => {
  const rows = buildRosterRows(
    [asset({ assetId: 'a-1', displayName: 'Falcon' }), asset({ assetId: 'a-2', displayName: 'Hawk' })],
    new Map<string, readonly AssignedPilot[]>([['a-1', [{ userId: 'u-1', role: 'PILOT' }]]]),
    [user({ userId: 'u-1', displayName: 'Jane Pilot' })],
  );

  it('returns every row for a blank query', () => {
    expect(searchRosterRows(rows, '')).toEqual(rows);
    expect(searchRosterRows(rows, '   ')).toEqual(rows);
  });

  it('matches by asset name, case-insensitive', () => {
    expect(searchRosterRows(rows, 'hawk').map((r) => r.asset.assetId)).toEqual(['a-2']);
  });

  it('matches by an assigned pilot\'s name, case-insensitive', () => {
    expect(searchRosterRows(rows, 'jane').map((r) => r.asset.assetId)).toEqual(['a-1']);
  });

  it('matches nothing for an unrelated query', () => {
    expect(searchRosterRows(rows, 'nope')).toEqual([]);
  });
});

describe('countAssetsWithoutPilot', () => {
  const drone = new Set(['drone']);

  it('counts rows with zero assigned pilots', () => {
    const rows = buildRosterRows(
      [asset({ assetId: 'a-1' }), asset({ assetId: 'a-2' })],
      new Map<string, readonly AssignedPilot[]>([['a-1', [{ userId: 'u-1', role: 'PILOT' }]]]),
      [user({ userId: 'u-1' })],
    );
    expect(countAssetsWithoutPilot(rows, drone)).toBe(1);
  });

  it('counts against every row, not a filtered subset — the caller is responsible for passing the unfiltered list', () => {
    const rows = buildRosterRows([asset({ assetId: 'a-1' }), asset({ assetId: 'a-2' })], new Map(), []);
    expect(countAssetsWithoutPilot(rows, drone)).toBe(2);
  });

  it('is zero when every asset has ≥1 pilot', () => {
    const rows = buildRosterRows(
      [asset({ assetId: 'a-1' })],
      new Map<string, readonly AssignedPilot[]>([['a-1', [{ userId: 'u-1', role: 'PILOT' }]]]),
      [user({ userId: 'u-1' })],
    );
    expect(countAssetsWithoutPilot(rows, drone)).toBe(0);
  });

  it('excludes a non-connected category (e.g. a battery) even with zero pilots — C1(b)', () => {
    const rows = buildRosterRows(
      [asset({ assetId: 'a-1', category: 'drone' }), asset({ assetId: 'a-2', category: 'battery' })],
      new Map(),
      [],
    );
    expect(countAssetsWithoutPilot(rows, drone)).toBe(1);
  });

  it('counts nothing while categories have not resolved yet (empty slug set)', () => {
    const rows = buildRosterRows([asset({ assetId: 'a-1' })], new Map(), []);
    expect(countAssetsWithoutPilot(rows, new Set())).toBe(0);
  });
});

describe('custodyStatusFor', () => {
  const nameById = new Map([['u-1', 'Jane Pilot']]);

  it('is "in-stock" when nobody has it and it is not grounded/retired', () => {
    expect(custodyStatusFor(asset(), nameById)).toEqual({ kind: 'in-stock' });
  });

  it('resolves an active custodian to a display name', () => {
    const held = asset({ inventoryState: 'ISSUED', custody: { custodianId: 'u-1' } });
    expect(custodyStatusFor(held, nameById)).toEqual({ kind: 'held', custodianName: 'Jane Pilot' });
  });

  it('falls back to a short id fragment for an unresolvable custodian', () => {
    const held = asset({ inventoryState: 'IN_FIELD', custody: { custodianId: 'unknown-user-id' } });
    expect(custodyStatusFor(held, nameById)).toEqual({ kind: 'held', custodianName: 'unknown-' });
  });

  it('MAINTENANCE beats a leftover custodian — C1(c)', () => {
    const grounded = asset({ inventoryState: 'MAINTENANCE', custody: { custodianId: 'u-1' } });
    expect(custodyStatusFor(grounded, nameById)).toEqual({ kind: 'maintenance' });
  });

  it('is "maintenance" for a grounded asset with no custodian at all — C1(c)', () => {
    const grounded = asset({ inventoryState: 'MAINTENANCE' });
    expect(custodyStatusFor(grounded, nameById)).toEqual({ kind: 'maintenance' });
  });

  it('RETIRED also beats a leftover custodian', () => {
    const retired = asset({ inventoryState: 'RETIRED', custody: { custodianId: 'u-1' } });
    expect(custodyStatusFor(retired, nameById)).toEqual({ kind: 'retired' });
  });
});

describe('custodyStatusLabel / custodyStatusTitle', () => {
  it('renders "Has: <name>" for an active custodian', () => {
    const status = { kind: 'held' as const, custodianName: 'Jane Pilot' };
    expect(custodyStatusLabel(status)).toBe('Has: Jane Pilot');
    expect(custodyStatusTitle(status)).toBe('Has: Jane Pilot');
  });

  it('renders "In maintenance" for a grounded asset, never "In stock"', () => {
    expect(custodyStatusLabel({ kind: 'maintenance' })).toBe('In maintenance');
    expect(custodyStatusTitle({ kind: 'maintenance' })).toContain('Grounded for maintenance');
  });

  it('renders "Retired"', () => {
    expect(custodyStatusLabel({ kind: 'retired' })).toBe('Retired');
    expect(custodyStatusTitle({ kind: 'retired' })).toContain('Retired');
  });

  it('renders "In stock" for the genuine in-stock case', () => {
    expect(custodyStatusLabel({ kind: 'in-stock' })).toBe('In stock');
    expect(custodyStatusTitle({ kind: 'in-stock' })).toContain('In stock');
  });
});
