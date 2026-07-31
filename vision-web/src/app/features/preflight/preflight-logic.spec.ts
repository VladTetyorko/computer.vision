import { describe, expect, it } from 'vitest';
import type { AssetSummary } from '../../core/api/models';
import { defaultPreflightAssetId, sortAssetsByName } from './preflight-logic';

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

describe('sortAssetsByName', () => {
  it('sorts alphabetically, case-insensitive', () => {
    const rows = sortAssetsByName([asset({ displayName: 'bravo' }), asset({ displayName: 'Alpha' })]);
    expect(rows.map((a) => a.displayName)).toEqual(['Alpha', 'bravo']);
  });
});

describe('defaultPreflightAssetId', () => {
  it('prefers the remembered asset id when it is still in the fleet', () => {
    const assets = [asset({ assetId: 'a' }), asset({ assetId: 'b' })];
    expect(defaultPreflightAssetId(assets, 'b')).toBe('b');
  });

  it('falls back to the first asset alphabetically when nothing is remembered', () => {
    const assets = [asset({ assetId: 'z', displayName: 'Zulu' }), asset({ assetId: 'a', displayName: 'Alpha' })];
    expect(defaultPreflightAssetId(assets, null)).toBe('a');
  });

  it('falls back to the first asset when the remembered id no longer exists (archived/deleted)', () => {
    const assets = [asset({ assetId: 'a', displayName: 'Alpha' })];
    expect(defaultPreflightAssetId(assets, 'stale-id')).toBe('a');
  });

  it('returns undefined for an empty fleet', () => {
    expect(defaultPreflightAssetId([], null)).toBeUndefined();
    expect(defaultPreflightAssetId([], 'anything')).toBeUndefined();
  });
});
