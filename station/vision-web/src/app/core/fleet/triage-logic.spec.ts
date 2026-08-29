import { describe, expect, it } from 'vitest';
import type { AssetSummary } from '../api/models';
import { groupAndSort, isSimulated, offlineLabel } from './triage-logic';

/**
 * Moved from `features/fly/drone-picker-logic.spec.ts` (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N3,
 * §2 N3, wave W2) alongside the rules themselves — see `triage-logic.ts`'s own doc comment.
 * `features/fly/drone-picker-logic.ts` re-exports these same functions verbatim, so `/fly`'s own
 * behavior stays covered by `drone-picker.spec.ts`/`drone-picker-card.ts`'s own callers; nothing
 * `/fly`-specific was left behind to keep a spec file here for.
 */
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

  it('is structurally typed on just `category` — a caller with only that field works too', () => {
    expect(isSimulated({ category: 'simulated' })).toBe(true);
    expect(isSimulated({ category: 'robot' })).toBe(false);
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

  // --- attentionRank (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N3, §2 N3 — new this wave, the
  // Command rail's own "streaming → needs attention (severity desc) → last seen desc" order) -------

  it('omitting attentionRank reproduces the pre-N3 order exactly (no /fly behavior change)', () => {
    const older = asset({ assetId: 'older', lastUsedAt: '2026-08-28T08:00:00Z' });
    const newer = asset({ assetId: 'newer', lastUsedAt: '2026-08-28T11:00:00Z' });
    const groups = groupAndSort([older, newer], NOW);
    expect(groups.yours.map((a) => a.assetId)).toEqual(['newer', 'older']);
  });

  it('with attentionRank, ranks higher-urgency rows first within a status tier, ahead of last-seen', () => {
    // 'stale' is far more recently seen than 'critical', but critical outranks it.
    const critical = asset({ assetId: 'critical', lastUsedAt: '2026-01-01T00:00:00Z' });
    const stale = asset({ assetId: 'stale', lastUsedAt: '2026-08-28T11:59:00Z' });
    const quiet = asset({ assetId: 'quiet', lastUsedAt: '2026-08-28T11:00:00Z' });
    const rankById: Record<string, number> = { critical: 6, stale: 5 };
    const groups = groupAndSort([quiet, stale, critical], NOW, (a) => rankById[a.assetId] ?? 0);
    expect(groups.yours.map((a) => a.assetId)).toEqual(['critical', 'stale', 'quiet']);
  });

  it('attentionRank never promotes an offline row ahead of a streaming one — status is still the first tier', () => {
    const streamingQuiet = asset({ assetId: 'streaming', status: 'STREAMING' });
    const offlineCritical = asset({ assetId: 'offline-critical', status: 'OFFLINE' });
    const groups = groupAndSort([offlineCritical, streamingQuiet], NOW, (a) => (a.assetId === 'offline-critical' ? 9 : 0));
    expect(groups.yours.map((a) => a.assetId)).toEqual(['streaming', 'offline-critical']);
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

  it('reads "Offline · 6d" for an asset offline six days sharp — a zero remainder is dropped', () => {
    const sixDaysAgo = NOW - 6 * 86_400 * 1000;
    const a = asset({ lastUsedAt: new Date(sixDaysAgo).toISOString() });
    expect(offlineLabel(a, NOW)).toBe('Offline · 6d');
  });
});
