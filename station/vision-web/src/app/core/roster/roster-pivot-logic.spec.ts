import { describe, expect, it } from 'vitest';
import type { AssetSummary, AssignedPilot, UserSummary } from '../api/models';
import { buildPilotRows, countPilotsWithoutAssets, isPilot, parseRosterPivot, searchPilotRows } from './roster-pivot-logic';

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

describe('parseRosterPivot', () => {
  it('reads "pilot" literally', () => {
    expect(parseRosterPivot('pilot')).toBe('pilot');
  });

  it('defaults everything else (missing, null, garbage) to "asset"', () => {
    expect(parseRosterPivot(null)).toBe('asset');
    expect(parseRosterPivot(undefined)).toBe('asset');
    expect(parseRosterPivot('')).toBe('asset');
    expect(parseRosterPivot('bogus')).toBe('asset');
  });
});

describe('buildPilotRows', () => {
  it('includes every user, even one with zero assignments', () => {
    const users = [user({ userId: 'u-1', displayName: 'Jane' }), user({ userId: 'u-2', displayName: 'Bo' })];
    const rows = buildPilotRows([asset({ assetId: 'a-1' })], new Map(), users);
    expect(rows.map((r) => r.userId)).toEqual(['u-2', 'u-1']); // alphabetical: Bo, Jane
    expect(rows.every((r) => r.assignments.length === 0)).toBe(true);
  });

  it('resolves each pilot\'s assigned assets to display names, sorted', () => {
    const assets = [asset({ assetId: 'a-1', displayName: 'Hawk' }), asset({ assetId: 'a-2', displayName: 'Falcon' })];
    const pilotsByAsset = new Map<string, readonly AssignedPilot[]>([
      ['a-1', [{ userId: 'u-1' }]],
      ['a-2', [{ userId: 'u-1' }]],
    ]);
    const rows = buildPilotRows(assets, pilotsByAsset, [user({ userId: 'u-1', displayName: 'Jane' })]);
    expect(rows).toHaveLength(1);
    expect(rows[0].assignments.map((a) => a.displayName)).toEqual(['Falcon', 'Hawk']);
  });

  it('drops an assignment referencing an asset not in the fetched list, rather than showing an unresolved name', () => {
    const pilotsByAsset = new Map<string, readonly AssignedPilot[]>([['gone', [{ userId: 'u-1' }]]]);
    const rows = buildPilotRows([], pilotsByAsset, [user({ userId: 'u-1' })]);
    expect(rows[0].assignments).toEqual([]);
  });

  it('a pilot assigned to two assets appears once, with both listed', () => {
    const assets = [asset({ assetId: 'a-1', displayName: 'Hawk' }), asset({ assetId: 'a-2', displayName: 'Falcon' })];
    const pilotsByAsset = new Map<string, readonly AssignedPilot[]>([
      ['a-1', [{ userId: 'u-1' }, { userId: 'u-2' }]],
      ['a-2', [{ userId: 'u-1' }]],
    ]);
    const users = [user({ userId: 'u-1', displayName: 'Jane' }), user({ userId: 'u-2', displayName: 'Bo' })];
    const rows = buildPilotRows(assets, pilotsByAsset, users);
    expect(rows.find((r) => r.userId === 'u-1')?.assignments).toHaveLength(2);
    expect(rows.find((r) => r.userId === 'u-2')?.assignments).toHaveLength(1);
  });
});

describe('isPilot', () => {
  it('true for a user with a PILOT membership', () => {
    expect(isPilot(user({ memberships: [{ groupId: 'g-1', role: 'PILOT' }] }))).toBe(true);
  });

  it('false for a user with only a MANAGER/ADMIN membership', () => {
    expect(isPilot(user({ memberships: [{ groupId: 'g-1', role: 'MANAGER' }] }))).toBe(false);
    expect(isPilot(user({ memberships: [{ groupId: 'g-1', role: 'ADMIN' }] }))).toBe(false);
  });

  it('false for a user with no memberships at all', () => {
    expect(isPilot(user({ memberships: [] }))).toBe(false);
  });

  it('true for a user who is PILOT in one group and MANAGER in another — topRole would miss this', () => {
    const mixed = user({
      memberships: [
        { groupId: 'g-1', role: 'PILOT' },
        { groupId: 'g-2', role: 'MANAGER' },
      ],
      topRole: 'MANAGER',
    });
    expect(isPilot(mixed)).toBe(true);
  });
});

describe('countPilotsWithoutAssets', () => {
  it('counts a pilot with zero assignments', () => {
    const users = [user({ userId: 'u-1', memberships: [{ groupId: 'g-1', role: 'PILOT' }] })];
    const rows = buildPilotRows([], new Map(), users);
    expect(countPilotsWithoutAssets(rows, users)).toBe(1);
  });

  it('does not count a pilot who has at least one assignment', () => {
    const assets = [asset({ assetId: 'a-1', displayName: 'Hawk' })];
    const pilotsByAsset = new Map<string, readonly AssignedPilot[]>([['a-1', [{ userId: 'u-1' }]]]);
    const users = [user({ userId: 'u-1', memberships: [{ groupId: 'g-1', role: 'PILOT' }] })];
    const rows = buildPilotRows(assets, pilotsByAsset, users);
    expect(countPilotsWithoutAssets(rows, users)).toBe(0);
  });

  it('does not count a MANAGER/ADMIN with zero assignments — they are not pilots', () => {
    const users = [
      user({ userId: 'u-1', memberships: [{ groupId: 'g-1', role: 'MANAGER' }] }),
      user({ userId: 'u-2', memberships: [{ groupId: 'g-1', role: 'ADMIN' }] }),
    ];
    const rows = buildPilotRows([], new Map(), users);
    expect(countPilotsWithoutAssets(rows, users)).toBe(0);
  });

  it('counts each unassigned pilot once, across several', () => {
    const users = [
      user({ userId: 'u-1', displayName: 'A', memberships: [{ groupId: 'g-1', role: 'PILOT' }] }),
      user({ userId: 'u-2', displayName: 'B', memberships: [{ groupId: 'g-1', role: 'PILOT' }] }),
      user({ userId: 'u-3', displayName: 'C', memberships: [{ groupId: 'g-1', role: 'MANAGER' }] }),
    ];
    const rows = buildPilotRows([], new Map(), users);
    expect(countPilotsWithoutAssets(rows, users)).toBe(2);
  });
});

describe('searchPilotRows', () => {
  const assets = [asset({ assetId: 'a-1', displayName: 'Falcon' })];
  const pilotsByAsset = new Map<string, readonly AssignedPilot[]>([['a-1', [{ userId: 'u-1' }]]]);
  const users = [user({ userId: 'u-1', displayName: 'Jane Pilot', username: 'jpilot' }), user({ userId: 'u-2', displayName: 'Bo' })];
  const rows = buildPilotRows(assets, pilotsByAsset, users);

  it('returns every row for a blank query', () => {
    expect(searchPilotRows(rows, '')).toEqual(rows);
  });

  it('matches by pilot display name, case-insensitive', () => {
    expect(searchPilotRows(rows, 'jane').map((r) => r.userId)).toEqual(['u-1']);
  });

  it('matches by username', () => {
    expect(searchPilotRows(rows, 'jpilot').map((r) => r.userId)).toEqual(['u-1']);
  });

  it('matches by an assigned asset\'s name', () => {
    expect(searchPilotRows(rows, 'falcon').map((r) => r.userId)).toEqual(['u-1']);
  });

  it('matches nothing for an unrelated query', () => {
    expect(searchPilotRows(rows, 'nope')).toEqual([]);
  });
});
