import { describe, expect, it } from 'vitest';
import type { AssetDetails, Device } from '../../core/api/models';
import {
  buildAssetListRows,
  describeAssetState,
  filterAssetListRowsByArchived,
  filterAssetListRowsByCategory,
  filterAssetListRowsByStatus,
  filterAssetListRowsByStreaming,
  findAssetRowById,
  lastSeenLabel,
  parseAssetViewMode,
  searchAssetListRowsByName,
  sortAssetListRowsByTriage,
} from './assets-logic';

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

function assetDetails(partial: Partial<AssetDetails> = {}): AssetDetails {
  return {
    assetId: 'a-0',
    displayName: 'asset',
    category: 'drone',
    categoryName: 'Drone',
    owner: 'owner-0',
    status: 'OFFLINE',
    attributes: {},
    devices: [],
    recentUsages: [],
    ...partial,
  };
}

describe('buildAssetListRows', () => {
  it('derives deviceCount from the resolved device list', () => {
    const asset = assetDetails({ devices: [device({ id: 'd1' }), device({ id: 'd2' })] });
    const rows = buildAssetListRows([asset], new Set());
    expect(rows[0].deviceCount).toBe(2);
  });

  it('marks streaming true when any of the asset devices is live', () => {
    const asset = assetDetails({ devices: [device({ id: 'd1' }), device({ id: 'd2' })] });
    const rows = buildAssetListRows([asset], new Set(['d2']));
    expect(rows[0].streaming).toBe(true);
  });

  it('marks streaming false when none of the asset devices is live', () => {
    const asset = assetDetails({ devices: [device({ id: 'd1' })] });
    const rows = buildAssetListRows([asset], new Set(['other-device']));
    expect(rows[0].streaming).toBe(false);
  });

  it('resolves watchDeviceId to the first VIDEO-capable device', () => {
    const telemetryOnly = device({ id: 'd1', capabilities: ['TELEMETRY'] });
    const video = device({ id: 'd2', capabilities: ['VIDEO'] });
    const rows = buildAssetListRows([assetDetails({ devices: [telemetryOnly, video] })], new Set());
    expect(rows[0].watchDeviceId).toBe('d2');
  });

  it('leaves watchDeviceId undefined when the asset has no VIDEO-capable device', () => {
    const rows = buildAssetListRows(
      [assetDetails({ devices: [device({ id: 'd1', capabilities: ['TELEMETRY'] })] })],
      new Set(),
    );
    expect(rows[0].watchDeviceId).toBeUndefined();
  });

  it('defaults a missing lifecycle to ACTIVE', () => {
    const rows = buildAssetListRows([assetDetails({ lifecycle: undefined })], new Set());
    expect(rows[0].lifecycle).toBe('ACTIVE');
    expect(rows[0].archived).toBe(false);
  });

  it('marks a DELETED asset archived', () => {
    const rows = buildAssetListRows([assetDetails({ lifecycle: 'DELETED' })], new Set());
    expect(rows[0].archived).toBe(true);
  });
});

// --- sortAssetListRowsByTriage (docs/plans/active/OPERATOR-UX-5-PLAN.md finding U5, §2 U5 — the
// grid's default order, via `core/fleet/triage-logic.ts#triageOrder`) --------------------------

const NOW = Date.parse('2026-08-29T12:00:00Z');

describe('sortAssetListRowsByTriage', () => {
  it('sorts streaming assets ahead of offline ones', () => {
    const rows = buildAssetListRows(
      [
        assetDetails({ assetId: 'offline-1', displayName: 'Zulu', status: 'OFFLINE' }),
        assetDetails({ assetId: 'streaming-1', displayName: 'Alpha', status: 'STREAMING' }),
      ],
      new Set(),
    );
    expect(sortAssetListRowsByTriage(rows, NOW).map((r) => r.asset.assetId)).toEqual(['streaming-1', 'offline-1']);
  });

  it('sorts by lastUsedAt descending (most recently seen first), never-seen last', () => {
    const rows = buildAssetListRows(
      [
        assetDetails({ assetId: 'never' }),
        assetDetails({ assetId: 'older', lastUsedAt: '2026-08-29T08:00:00Z' }),
        assetDetails({ assetId: 'newer', lastUsedAt: '2026-08-29T11:00:00Z' }),
      ],
      new Set(),
    );
    expect(sortAssetListRowsByTriage(rows, NOW).map((r) => r.asset.assetId)).toEqual(['newer', 'older', 'never']);
  });

  it('sorts real vehicles ahead of simulated ones, even a streaming simulated asset', () => {
    const rows = buildAssetListRows(
      [
        assetDetails({ assetId: 'sim-1', category: 'simulated', status: 'STREAMING' }),
        assetDetails({ assetId: 'rover-1', category: 'rover', status: 'OFFLINE' }),
      ],
      new Set(),
    );
    expect(sortAssetListRowsByTriage(rows, NOW).map((r) => r.asset.assetId)).toEqual(['rover-1', 'sim-1']);
  });

  it('does not mutate the input array', () => {
    const rows = buildAssetListRows(
      [assetDetails({ assetId: 'b', displayName: 'B' }), assetDetails({ assetId: 'a', displayName: 'A' })],
      new Set(),
    );
    const original = [...rows];
    sortAssetListRowsByTriage(rows, NOW);
    expect(rows).toEqual(original);
  });
});

describe('lastSeenLabel', () => {
  it('reads "Never seen" for no lastUsedAt', () => {
    expect(lastSeenLabel(undefined, NOW)).toBe('Never seen');
  });

  it('reads "<age> ago" for a real lastUsedAt', () => {
    const twoHoursTwentyNineMinAgo = NOW - (2 * 3600 + 29 * 60) * 1000;
    expect(lastSeenLabel(new Date(twoHoursTwentyNineMinAgo).toISOString(), NOW)).toBe('2h 29m ago');
  });

  it('drops a zero remainder — "6d ago", not "6d 0h ago"', () => {
    const sixDaysAgo = NOW - 6 * 86_400 * 1000;
    expect(lastSeenLabel(new Date(sixDaysAgo).toISOString(), NOW)).toBe('6d ago');
  });
});

describe('filterAssetListRowsByArchived', () => {
  const rows = buildAssetListRows(
    [
      assetDetails({ assetId: 'active', lifecycle: 'ACTIVE' }),
      assetDetails({ assetId: 'archived', lifecycle: 'DELETED' }),
    ],
    new Set(),
  );

  it('hides archived rows when showArchived is false', () => {
    expect(filterAssetListRowsByArchived(rows, false).map((r) => r.asset.assetId)).toEqual(['active']);
  });

  it('keeps archived rows when showArchived is true', () => {
    expect(filterAssetListRowsByArchived(rows, true).map((r) => r.asset.assetId)).toEqual([
      'active',
      'archived',
    ]);
  });
});

describe('filterAssetListRowsByCategory', () => {
  const rows = buildAssetListRows(
    [
      assetDetails({ assetId: 'drone-1', category: 'drone' }),
      assetDetails({ assetId: 'cam-1', category: 'ip-camera' }),
    ],
    new Set(),
  );

  it('leaves every row when category is undefined', () => {
    expect(filterAssetListRowsByCategory(rows, undefined).map((r) => r.asset.assetId)).toEqual([
      'drone-1',
      'cam-1',
    ]);
  });

  it('leaves every row when category is blank', () => {
    expect(filterAssetListRowsByCategory(rows, '   ').map((r) => r.asset.assetId)).toEqual([
      'drone-1',
      'cam-1',
    ]);
  });

  it('narrows to rows matching the given category slug', () => {
    expect(filterAssetListRowsByCategory(rows, 'drone').map((r) => r.asset.assetId)).toEqual(['drone-1']);
  });

  it('narrows to zero rows for a category with no current assets, rather than showing everything', () => {
    expect(filterAssetListRowsByCategory(rows, 'robot')).toEqual([]);
  });
});

describe('filterAssetListRowsByStatus', () => {
  const rows = buildAssetListRows(
    [
      assetDetails({ assetId: 'active', lifecycle: 'ACTIVE' }),
      assetDetails({ assetId: 'deactivated', lifecycle: 'DEACTIVATED' }),
      assetDetails({ assetId: 'archived', lifecycle: 'DELETED' }),
    ],
    new Set(),
  );

  it("'all' leaves every row untouched", () => {
    expect(filterAssetListRowsByStatus(rows, 'all')).toHaveLength(3);
  });

  it("'active' narrows to ACTIVE rows only", () => {
    expect(filterAssetListRowsByStatus(rows, 'active').map((r) => r.asset.assetId)).toEqual(['active']);
  });

  it("'deactivated' narrows to DEACTIVATED rows only", () => {
    expect(filterAssetListRowsByStatus(rows, 'deactivated').map((r) => r.asset.assetId)).toEqual([
      'deactivated',
    ]);
  });

  it("never matches a DELETED row, even though it isn't 'all' filtered out — that's the archived toggle's job", () => {
    expect(filterAssetListRowsByStatus(rows, 'active').some((r) => r.lifecycle === 'DELETED')).toBe(false);
    expect(filterAssetListRowsByStatus(rows, 'deactivated').some((r) => r.lifecycle === 'DELETED')).toBe(
      false,
    );
  });
});

describe('filterAssetListRowsByStreaming', () => {
  const rows = buildAssetListRows(
    [
      assetDetails({ assetId: 'live', devices: [device({ id: 'd1' })] }),
      assetDetails({ assetId: 'quiet', devices: [device({ id: 'd2' })] }),
    ],
    new Set(['d1']),
  );

  it("'all' leaves every row", () => {
    expect(filterAssetListRowsByStreaming(rows, 'all')).toHaveLength(2);
  });

  it("'streaming' narrows to streaming rows only", () => {
    expect(filterAssetListRowsByStreaming(rows, 'streaming').map((r) => r.asset.assetId)).toEqual(['live']);
  });

  it("'offline' narrows to non-streaming rows only", () => {
    expect(filterAssetListRowsByStreaming(rows, 'offline').map((r) => r.asset.assetId)).toEqual(['quiet']);
  });
});

describe('searchAssetListRowsByName', () => {
  const rows = buildAssetListRows(
    [assetDetails({ assetId: 'a-1', displayName: 'Front Gate Drone' }), assetDetails({ assetId: 'a-2', displayName: 'Back Yard Camera' })],
    new Set(),
  );

  it('leaves every row for a blank query', () => {
    expect(searchAssetListRowsByName(rows, '   ').map((r) => r.asset.assetId)).toEqual(['a-1', 'a-2']);
  });

  it('matches case-insensitively on a substring of the display name', () => {
    expect(searchAssetListRowsByName(rows, 'drone').map((r) => r.asset.assetId)).toEqual(['a-1']);
    expect(searchAssetListRowsByName(rows, 'GATE').map((r) => r.asset.assetId)).toEqual(['a-1']);
  });

  it('narrows to zero rows for a query matching nothing', () => {
    expect(searchAssetListRowsByName(rows, 'nope')).toEqual([]);
  });
});

describe('findAssetRowById', () => {
  const rows = buildAssetListRows(
    [assetDetails({ assetId: 'a-1' }), assetDetails({ assetId: 'a-2' })],
    new Set(),
  );

  it('finds the row matching the given id', () => {
    expect(findAssetRowById(rows, 'a-2')?.asset.assetId).toBe('a-2');
  });

  it('degrades to undefined for an id matching no loaded row (stale ?sel=, docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4)', () => {
    expect(findAssetRowById(rows, 'not-a-real-id')).toBeUndefined();
  });

  it('degrades to undefined for an undefined id (no selection)', () => {
    expect(findAssetRowById(rows, undefined)).toBeUndefined();
  });

  it('degrades to undefined for an empty-string id', () => {
    expect(findAssetRowById(rows, '')).toBeUndefined();
  });
});

describe('describeAssetState', () => {
  it('is "archived" whenever archived is true, regardless of lifecycle/streaming', () => {
    expect(describeAssetState({ lifecycle: 'ACTIVE', archived: true, streaming: true })).toEqual({
      kind: 'archived',
      label: 'Archived',
    });
  });

  it('is "deactivated" for a non-archived DEACTIVATED row, even while streaming', () => {
    expect(describeAssetState({ lifecycle: 'DEACTIVATED', archived: false, streaming: true })).toEqual({
      kind: 'deactivated',
      label: 'Deactivated',
    });
  });

  it('is "live" for an active, streaming row', () => {
    expect(describeAssetState({ lifecycle: 'ACTIVE', archived: false, streaming: true })).toEqual({
      kind: 'live',
      label: 'Live',
    });
  });

  it('is "offline" for the default steady state — active, not streaming', () => {
    expect(describeAssetState({ lifecycle: 'ACTIVE', archived: false, streaming: false })).toEqual({
      kind: 'offline',
      label: 'Offline',
    });
  });

  it('archived outranks every other state', () => {
    expect(describeAssetState({ lifecycle: 'DEACTIVATED', archived: true, streaming: false }).kind).toBe('archived');
  });
});

describe('parseAssetViewMode', () => {
  it("reads a persisted 'card' back as 'card'", () => {
    expect(parseAssetViewMode('card')).toBe('card');
  });

  it("reads a persisted 'list' back as 'list'", () => {
    expect(parseAssetViewMode('list')).toBe('list');
  });

  it('falls back to the dense list default for nothing ever persisted (null)', () => {
    expect(parseAssetViewMode(null)).toBe('list');
  });

  it('falls back to the dense list default for a corrupted/unrecognised value', () => {
    expect(parseAssetViewMode('grid')).toBe('list');
    expect(parseAssetViewMode('true')).toBe('list');
  });
});
