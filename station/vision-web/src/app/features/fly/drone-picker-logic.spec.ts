import { describe, expect, it } from 'vitest';
import type { AssetSummary } from '../../core/api/models';
import { groupAndSort, humanAge, isSimulated, offlineLabel } from './drone-picker-logic';

function asset(partial: Partial<AssetSummary>): AssetSummary {
  return {
    assetId: 'a-0',
    displayName: 'Asset',
    category: 'drone',
    categoryName: 'Drone',
    owner: 'owner-0',
    status: 'OFFLINE',
    attributes: {},
    ...partial,
  };
}

const NOW = Date.parse('2026-08-28T12:00:00Z');

describe('isSimulated', () => {
  it('is true for the simulated category', () => {
    expect(isSimulated(asset({ category: 'simulated' }))).toBe(true);
  });

  it('is false for any real category', () => {
    expect(isSimulated(asset({ category: 'rover' }))).toBe(false);
    expect(isSimulated(asset({ category: 'drone' }))).toBe(false);
  });
});

describe('groupAndSort', () => {
  it('splits real vehicles from simulated ones by category', () => {
    const rover = asset({ assetId: 'rover-1', category: 'rover' });
    const sim = asset({ assetId: 'sim-1', category: 'simulated' });
    const groups = groupAndSort([sim, rover], NOW);
    expect(groups.yours.map((a) => a.assetId)).toEqual(['rover-1']);
    expect(groups.simulated.map((a) => a.assetId)).toEqual(['sim-1']);
  });

  it('puts streaming assets before offline ones within a group', () => {
    const offlineZ = asset({ assetId: 'z', displayName: 'Zulu', status: 'OFFLINE', lastUsedAt: '2026-08-28T11:59:00Z' });
    const streamingA = asset({ assetId: 'a', displayName: 'Alpha', status: 'STREAMING', lastUsedAt: '2026-08-28T11:00:00Z' });
    const groups = groupAndSort([offlineZ, streamingA], NOW);
    expect(groups.yours.map((a) => a.assetId)).toEqual(['a', 'z']);
  });

  it('sorts by lastUsedAt descending (most recently seen first) within a status', () => {
    const older = asset({ assetId: 'older', lastUsedAt: '2026-08-28T08:00:00Z' });
    const newer = asset({ assetId: 'newer', lastUsedAt: '2026-08-28T11:00:00Z' });
    const groups = groupAndSort([older, newer], NOW);
    expect(groups.yours.map((a) => a.assetId)).toEqual(['newer', 'older']);
  });

  it('sorts a never-seen asset last, after every asset with a real lastUsedAt', () => {
    const neverSeen = asset({ assetId: 'never' });
    const seenLongAgo = asset({ assetId: 'seen-long-ago', lastUsedAt: '2026-01-01T00:00:00Z' });
    const groups = groupAndSort([neverSeen, seenLongAgo], NOW);
    expect(groups.yours.map((a) => a.assetId)).toEqual(['seen-long-ago', 'never']);
  });

  it('breaks ties (equal age, including two never-seen assets) alphabetically, case-insensitively', () => {
    const bravo = asset({ assetId: 'b', displayName: 'bravo' });
    const alpha = asset({ assetId: 'a', displayName: 'Alpha' });
    const groups = groupAndSort([bravo, alpha], NOW);
    expect(groups.yours.map((a) => a.assetId)).toEqual(['a', 'b']);
  });

  it('does not mutate the input array', () => {
    const list = [asset({ assetId: 'b', displayName: 'B' }), asset({ assetId: 'a', displayName: 'A' })];
    const original = [...list];
    groupAndSort(list, NOW);
    expect(list).toEqual(original);
  });

  it('returns empty groups for an empty fleet', () => {
    expect(groupAndSort([], NOW)).toEqual({ yours: [], simulated: [] });
  });

  it('clamps a future-dated lastUsedAt to "just now" rather than sorting it as the freshest', () => {
    const future = asset({ assetId: 'future', lastUsedAt: '2026-08-28T13:00:00Z' }); // 1h after NOW
    const justNow = asset({ assetId: 'just-now', lastUsedAt: '2026-08-28T12:00:00Z' }); // exactly NOW
    const groups = groupAndSort([future, justNow], NOW);
    // Both clamp to age 0 — tie broken alphabetically ('future' < 'just-now'), never by raw timestamp.
    expect(groups.yours.map((a) => a.assetId)).toEqual(['future', 'just-now']);
  });
});

describe('humanAge', () => {
  it('renders seconds under a minute', () => {
    expect(humanAge(45)).toBe('45s');
  });

  it('renders whole minutes under an hour', () => {
    expect(humanAge(12 * 60)).toBe('12m');
  });

  it('renders hours and minutes under a day', () => {
    expect(humanAge(2 * 3600 + 29 * 60)).toBe('2h 29m');
  });

  it('omits a zero minutes remainder', () => {
    expect(humanAge(4 * 3600)).toBe('4h');
  });

  it('renders whole days with no hours remainder', () => {
    expect(humanAge(6 * 86_400 + 4 * 3600)).toBe('6d');
  });

  it('clamps a negative age to 0s rather than going negative', () => {
    expect(humanAge(-5)).toBe('0s');
  });
});

describe('offlineLabel', () => {
  it('reads "Never seen" for an asset with no lastUsedAt', () => {
    expect(offlineLabel(asset({ lastUsedAt: undefined }), NOW)).toBe('Never seen');
  });

  it('reads "Offline · <age>" for an asset with a lastUsedAt', () => {
    const twoHoursTwentyNineMinAgo = NOW - (2 * 3600 + 29 * 60) * 1000;
    const a = asset({ lastUsedAt: new Date(twoHoursTwentyNineMinAgo).toISOString() });
    expect(offlineLabel(a, NOW)).toBe('Offline · 2h 29m');
  });

  it('reads "Offline · 6d" for an asset offline six days', () => {
    const sixDaysAgo = NOW - 6 * 86_400 * 1000;
    const a = asset({ lastUsedAt: new Date(sixDaysAgo).toISOString() });
    expect(offlineLabel(a, NOW)).toBe('Offline · 6d');
  });
});
