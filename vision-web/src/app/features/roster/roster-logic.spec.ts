import { describe, expect, it } from 'vitest';
import type { AssetSummary, AssignedPilot, UserSummary } from '../../core/api/models';
import { buildRosterRows, searchRosterRows, sortAssetsByName } from './roster-logic';

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
    const pilotsByAsset = new Map<string, readonly AssignedPilot[]>([['a-1', [{ userId: 'u-1' }]]]);
    const users = [user({ userId: 'u-1', displayName: 'Jane Pilot' })];
    const rows = buildRosterRows(assets, pilotsByAsset, users);
    expect(rows).toEqual([{ asset: assets[0], pilotNames: ['Jane Pilot'] }]);
  });

  it('falls back to a short id fragment for an unresolvable pilot', () => {
    const assets = [asset({ assetId: 'a-1' })];
    const pilotsByAsset = new Map<string, readonly AssignedPilot[]>([['a-1', [{ userId: 'unknown-user-id' }]]]);
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
    new Map<string, readonly AssignedPilot[]>([['a-1', [{ userId: 'u-1' }]]]),
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
