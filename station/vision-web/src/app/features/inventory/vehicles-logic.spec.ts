import { describe, expect, it } from 'vitest';
import type { AssetDetails, AssetSummary, ReadinessRow, UserSummary } from '../../core/api/models';
import {
  buildVehicleRows,
  custodianFilterOptions,
  custodianLabel,
  filterVehicleRowsByArchived,
  filterVehicleRowsByCategory,
  filterVehicleRowsByConnected,
  filterVehicleRowsByCustodian,
  filterVehicleRowsByInventoryState,
  filterVehicleRowsByReadiness,
  filterVehicleRowsByRetired,
  findVehicleRowById,
  firmwareLabel,
  linksLabel,
  searchVehicleRowsByName,
  sortVehicleRowsByTriage,
  vehicleLastFlownLabel,
  type BuildVehicleRowsInput,
  type VehicleRow,
} from './vehicles-logic';

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

function details(partial: Partial<AssetDetails> = {}): AssetDetails {
  return { ...asset(), devices: [], recentUsages: [], ...partial };
}

function readinessRow(partial: Partial<ReadinessRow> = {}): ReadinessRow {
  return { assetId: 'a-1', displayName: 'Asset', verdict: 'GO', features: {}, ...partial };
}

/** `buildVehicleRows` takes one input record — this fills in the joins a given test doesn't care about. */
function rowsFor(assets: readonly AssetSummary[], partial: Partial<BuildVehicleRowsInput> = {}): readonly VehicleRow[] {
  return buildVehicleRows({
    assets,
    users: [],
    readinessByAssetId: new Map(),
    detailsByAssetId: new Map(),
    nowMs: Date.now(),
    ...partial,
  });
}

function user(partial: Partial<UserSummary> = {}): UserSummary {
  return {
    userId: 'u-0',
    username: 'pilot',
    displayName: 'Pilot One',
    email: 'p@example.com',
    enabled: true,
    memberships: [],
    ...partial,
  };
}

function row(partial: Partial<VehicleRow> = {}): VehicleRow {
  return {
    asset: asset(),
    lifecycle: 'ACTIVE',
    archived: false,
    streaming: false,
    links: '—',
    stateChip: { kind: 'unknown', label: '—', tone: 'muted', live: false },
    firmware: '—',
    hours: '—',
    lastFlownLabel: 'Never flown',
    ...partial,
  };
}

describe('vehicleLastFlownLabel', () => {
  it('is "Never flown" with no lastUsedAt', () => {
    expect(vehicleLastFlownLabel(undefined, Date.now())).toBe('Never flown');
  });

  it('renders "<age> ago" otherwise', () => {
    const now = Date.parse('2026-08-29T12:00:00Z');
    const lastUsedAt = new Date(now - 3_600_000).toISOString();
    expect(vehicleLastFlownLabel(lastUsedAt, now)).toMatch(/ago$/);
  });
});

describe('firmwareLabel', () => {
  it('is "—" when firmware is entirely absent (never probed)', () => {
    expect(firmwareLabel(undefined)).toBe('—');
  });

  it('is "—" when the probe answered but identified neither field', () => {
    expect(firmwareLabel({})).toBe('—');
  });

  it('maps a known name code to its display label and joins the version', () => {
    expect(firmwareLabel({ name: 'ardupilot', version: '4.7.0' })).toBe('ArduPilot 4.7.0');
    expect(firmwareLabel({ name: 'px4', version: '1.14' })).toBe('PX4 1.14');
    expect(firmwareLabel({ name: 'generic', version: '1.0' })).toBe('Generic 1.0');
  });

  it('renders an unrecognized name code verbatim rather than hiding it', () => {
    expect(firmwareLabel({ name: 'betaflight', version: '4.5' })).toBe('betaflight 4.5');
  });

  it('renders whichever one field is known when only one is', () => {
    expect(firmwareLabel({ name: 'ardupilot' })).toBe('ArduPilot');
    expect(firmwareLabel({ version: '4.7.0' })).toBe('4.7.0');
  });
});

describe('linksLabel', () => {
  it('prefers the wire count', () => {
    expect(linksLabel(3, undefined)).toBe('3');
    expect(linksLabel(0, undefined)).toBe('0');
  });

  it('falls back to an already-fetched detail record (a pre-W1 backend, row open)', () => {
    expect(linksLabel(undefined, details({ devices: [{ id: 'd-1' } as never] }))).toBe('1');
  });

  it('is "—" when neither source can answer — never a fabricated 0', () => {
    expect(linksLabel(undefined, undefined)).toBe('—');
  });
});

describe('custodianLabel', () => {
  it('prefers the name resolved on the wire (D3) over any client-side join', () => {
    const nameById = new Map([['u-1', 'Stale Join']]);
    expect(custodianLabel({ custodianId: 'u-1', custodianName: 'Jane Pilot' }, nameById)).toEqual({ name: 'Jane Pilot' });
  });

  it('falls back to the org user-list join for a pre-W1 backend', () => {
    expect(custodianLabel({ custodianId: 'u-1' }, new Map([['u-1', 'Jane Pilot']]))).toEqual({ name: 'Jane Pilot' });
  });

  it('falls back to a truncated id (never a bare 36-char UUID) with the whole id as a title', () => {
    const uuid = '3f2a91c4-1d2e-4b7a-9c8d-0e1f2a3b4c5d';
    expect(custodianLabel({ custodianId: uuid }, new Map())).toEqual({ name: '3f2a91c4…', title: uuid });
  });

  it('names nobody for an asset in stock', () => {
    expect(custodianLabel(undefined, new Map())).toEqual({});
    expect(custodianLabel({}, new Map())).toEqual({});
  });
});

describe('buildVehicleRows', () => {
  it('resolves the custodian id to a display name when found', () => {
    const rows = rowsFor([asset({ assetId: 'a-1', custody: { custodianId: 'u-1', since: '2026-08-01T00:00:00Z' } })], {
      users: [user({ userId: 'u-1', displayName: 'Jane Pilot' })],
    });
    expect(rows[0].custodianName).toBe('Jane Pilot');
    expect(rows[0].custodianTitle).toBeUndefined();
    expect(rows[0].custodianId).toBe('u-1');
  });

  it('prefers the custodian name the wire already resolved, with no user list at all (a pilot session)', () => {
    const rows = rowsFor([asset({ assetId: 'a-1', custody: { custodianId: 'u-1', custodianName: 'Jane Pilot' } })]);
    expect(rows[0].custodianName).toBe('Jane Pilot');
  });

  it('falls back to a truncated custodian id when nothing can name it', () => {
    const uuid = '3f2a91c4-1d2e-4b7a-9c8d-0e1f2a3b4c5d';
    const rows = rowsFor([asset({ assetId: 'a-1', custody: { custodianId: uuid } })]);
    expect(rows[0].custodianName).toBe('3f2a91c4…');
    expect(rows[0].custodianTitle).toBe(uuid);
  });

  it('leaves custodianName undefined for an in-stock asset', () => {
    expect(rowsFor([asset({ assetId: 'a-1' })])[0].custodianName).toBeUndefined();
  });

  it('carries the custody location as its own column value', () => {
    const rows = rowsFor([asset({ custody: { custodianId: 'u-1', location: 'Shelf B' } })]);
    expect(rows[0].location).toBe('Shelf B');
    expect(rowsFor([asset()])[0].location).toBeUndefined();
  });

  it('joins the readiness verdict by assetId, and names the first blocker as the cause', () => {
    const rows = rowsFor([asset({ assetId: 'a-1' })], {
      readinessByAssetId: new Map([
        ['a-1', readinessRow({ verdict: 'NO_GO', features: { battery: 'MISSING', 'link-quality': 'DEGRADED' } })],
      ]),
    });
    expect(rows[0].readinessVerdict).toBe('NO_GO');
    expect(rows[0].readinessCause).toBe('Link quality +1 more');
  });

  it('leaves the cause undefined for a fully-ready row — a verdict with no blocker has none to name', () => {
    const rows = rowsFor([asset({ assetId: 'a-1' })], {
      readinessByAssetId: new Map([['a-1', readinessRow({ verdict: 'GO', features: { battery: 'READY' } })]]),
    });
    expect(rows[0].readinessCause).toBeUndefined();
  });

  it('leaves readinessVerdict undefined for an asset never evaluated', () => {
    expect(rowsFor([asset({ assetId: 'a-1' })])[0].readinessVerdict).toBeUndefined();
  });

  it('reads streaming off the summary status — the Watch live gate', () => {
    expect(rowsFor([asset({ status: 'STREAMING' })])[0].streaming).toBe(true);
    expect(rowsFor([asset({ status: 'OFFLINE' })])[0].streaming).toBe(false);
  });

  it('renders Links from the wire count, from a cached detail record, or as "—"', () => {
    expect(rowsFor([asset({ assetId: 'a-1', deviceCount: 2 })])[0].links).toBe('2');
    expect(
      rowsFor([asset({ assetId: 'a-1' })], {
        detailsByAssetId: new Map([['a-1', details({ devices: [{ id: 'd-1' } as never] })]]),
      })[0].links,
    ).toBe('1');
    expect(rowsFor([asset({ assetId: 'a-1' })])[0].links).toBe('—');
  });

  it('renders firmware/hours as "—" when the asset carries neither field (never probed/flown)', () => {
    const rows = rowsFor([asset()]);
    expect(rows[0].firmware).toBe('—');
    expect(rows[0].hours).toBe('—');
  });

  it('renders firmware as "<name> <version>" and hours via formatFlightTime once the asset carries both', () => {
    const rows = rowsFor([asset({ firmware: { name: 'ardupilot', version: '4.7.0' }, totalFlightSeconds: 3_720 })]);
    expect(rows[0].firmware).toBe('ArduPilot 4.7.0');
    expect(rows[0].hours).toBe('1h 02m');
  });

  it('renders a genuine zero totalFlightSeconds honestly, not as "—"', () => {
    expect(rowsFor([asset({ totalFlightSeconds: 0 })])[0].hours).toBe('0m');
  });

  it('builds an honest stateChip merging lifecycle/archived/inventoryState', () => {
    expect(rowsFor([asset({ lifecycle: 'DELETED', inventoryState: 'IN_FIELD' })])[0].stateChip.kind).toBe('archived');
  });

  it('reads registration from identity, falling back to the legacy attributes key (core/fleet/asset-attributes.ts#effectiveRegistration)', () => {
    expect(rowsFor([asset({ identity: { registration: 'N12345' } })])[0].registration).toBe('N12345');
    expect(rowsFor([asset({ attributes: { registrationNumber: 'N-OLD' } })])[0].registration).toBe('N-OLD');
    expect(rowsFor([asset()])[0].registration).toBeUndefined();
  });
});

describe('sortVehicleRowsByTriage', () => {
  it('delegates to triageOrder — a streaming/live asset sorts before an offline one', () => {
    const now = Date.now();
    const offline = row({ asset: asset({ assetId: 'a-offline', lastUsedAt: new Date(now - 10_000).toISOString() }) });
    const neverSeen = row({ asset: asset({ assetId: 'a-never' }) });
    const sorted = sortVehicleRowsByTriage([neverSeen, offline], now);
    expect(sorted.map((r) => r.asset.assetId)).toEqual(['a-offline', 'a-never']);
  });
});

describe('filterVehicleRowsByConnected', () => {
  it('splits Vehicles (connected) from Equipment (not connected)', () => {
    const rows = [row({ asset: asset({ assetId: 'a-1', category: 'drone' }) }), row({ asset: asset({ assetId: 'a-2', category: 'battery' }) })];
    const connected = new Set(['drone']);
    expect(filterVehicleRowsByConnected(rows, connected, true).map((r) => r.asset.assetId)).toEqual(['a-1']);
    expect(filterVehicleRowsByConnected(rows, connected, false).map((r) => r.asset.assetId)).toEqual(['a-2']);
  });
});

describe('filterVehicleRowsByArchived', () => {
  it('hides archived rows unless shown', () => {
    const rows = [row({ archived: true }), row({ archived: false })];
    expect(filterVehicleRowsByArchived(rows, false)).toHaveLength(1);
    expect(filterVehicleRowsByArchived(rows, true)).toHaveLength(2);
  });
});

describe('filterVehicleRowsByRetired', () => {
  it('hides retired rows by default', () => {
    const rows = [row({ inventoryState: 'RETIRED' }), row({ inventoryState: 'IN_STOCK' })];
    expect(filterVehicleRowsByRetired(rows, false, 'all')).toHaveLength(1);
  });

  it('shows retired rows when the toggle is on', () => {
    const rows = [row({ inventoryState: 'RETIRED' }), row({ inventoryState: 'IN_STOCK' })];
    expect(filterVehicleRowsByRetired(rows, true, 'all')).toHaveLength(2);
  });

  it('shows retired rows when the state filter explicitly asks for RETIRED, even with the toggle off', () => {
    const rows = [row({ inventoryState: 'RETIRED' }), row({ inventoryState: 'IN_STOCK' })];
    expect(filterVehicleRowsByRetired(rows, false, 'RETIRED')).toHaveLength(2);
  });
});

describe('filterVehicleRowsByCategory', () => {
  it('narrows to one category slug', () => {
    const rows = [row({ asset: asset({ assetId: 'a-1', category: 'drone' }) }), row({ asset: asset({ assetId: 'a-2', category: 'rover' }) })];
    expect(filterVehicleRowsByCategory(rows, 'rover').map((r) => r.asset.assetId)).toEqual(['a-2']);
  });

  it('leaves every row when blank/absent', () => {
    const rows = [row(), row()];
    expect(filterVehicleRowsByCategory(rows, undefined)).toHaveLength(2);
    expect(filterVehicleRowsByCategory(rows, '  ')).toHaveLength(2);
  });
});

describe('filterVehicleRowsByInventoryState', () => {
  it('narrows to one state', () => {
    const rows = [row({ inventoryState: 'ISSUED' }), row({ inventoryState: 'IN_STOCK' })];
    expect(filterVehicleRowsByInventoryState(rows, 'ISSUED')).toHaveLength(1);
  });

  it('"all" leaves every row', () => {
    const rows = [row({ inventoryState: 'ISSUED' }), row({ inventoryState: 'IN_STOCK' })];
    expect(filterVehicleRowsByInventoryState(rows, 'all')).toHaveLength(2);
  });
});

describe('filterVehicleRowsByCustodian', () => {
  it('narrows to one custodian id', () => {
    const rows = [
      row({ asset: asset({ assetId: 'a-1' }), custodianId: 'u-1' }),
      row({ asset: asset({ assetId: 'a-2' }), custodianId: 'u-2' }),
    ];
    expect(filterVehicleRowsByCustodian(rows, 'u-1').map((r) => r.asset.assetId)).toEqual(['a-1']);
  });
});

describe('filterVehicleRowsByReadiness', () => {
  it('narrows to a verdict', () => {
    const rows = [row({ readinessVerdict: 'GO' }), row({ readinessVerdict: 'NO_GO' })];
    expect(filterVehicleRowsByReadiness(rows, 'GO')).toHaveLength(1);
  });

  it('"UNEVALUATED" matches a row with no readiness at all', () => {
    const rows = [row({ readinessVerdict: undefined }), row({ readinessVerdict: 'GO' })];
    expect(filterVehicleRowsByReadiness(rows, 'UNEVALUATED')).toHaveLength(1);
  });
});

describe('searchVehicleRowsByName', () => {
  it('matches case-insensitively', () => {
    const rows = [row({ asset: asset({ displayName: 'Falcon One' }) }), row({ asset: asset({ displayName: 'Rover' }) })];
    expect(searchVehicleRowsByName(rows, 'falcon')).toHaveLength(1);
  });
});

describe('findVehicleRowById', () => {
  it('finds against the given set, undefined for a missing/blank id', () => {
    const rows = [row({ asset: asset({ assetId: 'a-1' }) })];
    expect(findVehicleRowById(rows, 'a-1')?.asset.assetId).toBe('a-1');
    expect(findVehicleRowById(rows, undefined)).toBeUndefined();
    expect(findVehicleRowById(rows, 'a-missing')).toBeUndefined();
  });
});

describe('custodianFilterOptions', () => {
  it('dedupes and sorts by name', () => {
    const rows = [
      row({ asset: asset({ assetId: 'a-1' }), custodianId: 'u-2', custodianName: 'Zed' }),
      row({ asset: asset({ assetId: 'a-2' }), custodianId: 'u-1', custodianName: 'Amy' }),
      row({ asset: asset({ assetId: 'a-3' }), custodianId: 'u-1', custodianName: 'Amy' }),
    ];
    expect(custodianFilterOptions(rows)).toEqual([
      { id: 'u-1', name: 'Amy' },
      { id: 'u-2', name: 'Zed' },
    ]);
  });

  it('is empty when nobody currently holds an asset', () => {
    expect(custodianFilterOptions([row()])).toEqual([]);
  });
});

// `vehicleRowActions` moved to `core/fleet/inventory-logic.ts` in wave W3 of
// docs/plans/active/INVENTORY-REWORK-PLAN.md (it now takes an `InventoryActor` and is shared with
// the pilot cards) — its per-cell matrix tests live in that module's own spec.
