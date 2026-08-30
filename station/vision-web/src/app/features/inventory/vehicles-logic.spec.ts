import { describe, expect, it } from 'vitest';
import type { AssetDetails, UserSummary } from '../../core/api/models';
import {
  buildVehicleRows,
  custodianFilterOptions,
  filterVehicleRowsByArchived,
  filterVehicleRowsByCategory,
  filterVehicleRowsByConnected,
  filterVehicleRowsByCustodian,
  filterVehicleRowsByInventoryState,
  filterVehicleRowsByReadiness,
  filterVehicleRowsByRetired,
  findVehicleRowById,
  firmwareLabel,
  searchVehicleRowsByName,
  sortVehicleRowsByTriage,
  vehicleLastFlownLabel,
  vehicleRowActions,
  type VehicleRow,
} from './vehicles-logic';

function asset(partial: Partial<AssetDetails> = {}): AssetDetails {
  return {
    assetId: 'a-0',
    displayName: 'Asset',
    category: 'drone',
    categoryName: 'Drone',
    owner: 'org',
    status: 'OFFLINE',
    attributes: {},
    devices: [],
    recentUsages: [],
    ...partial,
  };
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
    deviceCount: 0,
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

describe('buildVehicleRows', () => {
  it('resolves the custodian id to a display name when found', () => {
    const rows = buildVehicleRows(
      [asset({ assetId: 'a-1', custody: { custodianId: 'u-1', since: '2026-08-01T00:00:00Z' } })],
      [user({ userId: 'u-1', displayName: 'Jane Pilot' })],
      new Map(),
      Date.now(),
    );
    expect(rows[0].custodianName).toBe('Jane Pilot');
  });

  it('falls back to the raw custodian id when no matching user is found', () => {
    const rows = buildVehicleRows(
      [asset({ assetId: 'a-1', custody: { custodianId: 'u-missing' } })],
      [],
      new Map(),
      Date.now(),
    );
    expect(rows[0].custodianName).toBe('u-missing');
  });

  it('leaves custodianName undefined for an in-stock asset', () => {
    const rows = buildVehicleRows([asset({ assetId: 'a-1' })], [], new Map(), Date.now());
    expect(rows[0].custodianName).toBeUndefined();
  });

  it('joins the readiness verdict by assetId', () => {
    const rows = buildVehicleRows(
      [asset({ assetId: 'a-1' })],
      [],
      new Map([['a-1', 'GO']]),
      Date.now(),
    );
    expect(rows[0].readinessVerdict).toBe('GO');
  });

  it('leaves readinessVerdict undefined for an asset never evaluated', () => {
    const rows = buildVehicleRows([asset({ assetId: 'a-1' })], [], new Map(), Date.now());
    expect(rows[0].readinessVerdict).toBeUndefined();
  });

  it('renders firmware/hours as "—" when the asset carries neither field (never probed/flown)', () => {
    const rows = buildVehicleRows([asset()], [], new Map(), Date.now());
    expect(rows[0].firmware).toBe('—');
    expect(rows[0].hours).toBe('—');
  });

  it('renders firmware as "<name> <version>" and hours via formatFlightTime once the asset carries both', () => {
    const rows = buildVehicleRows(
      [asset({ firmware: { name: 'ardupilot', version: '4.7.0' }, totalFlightSeconds: 3_720 })],
      [],
      new Map(),
      Date.now(),
    );
    expect(rows[0].firmware).toBe('ArduPilot 4.7.0');
    expect(rows[0].hours).toBe('1h 02m');
  });

  it('renders a genuine zero totalFlightSeconds honestly, not as "—"', () => {
    const rows = buildVehicleRows([asset({ totalFlightSeconds: 0 })], [], new Map(), Date.now());
    expect(rows[0].hours).toBe('0m');
  });

  it('builds an honest stateChip merging lifecycle/archived/inventoryState', () => {
    const rows = buildVehicleRows([asset({ lifecycle: 'DELETED', inventoryState: 'IN_FIELD' })], [], new Map(), Date.now());
    expect(rows[0].stateChip.kind).toBe('archived');
  });

  it('reads registration from identity, falling back to the legacy attributes key (core/fleet/asset-attributes.ts#effectiveRegistration)', () => {
    const viaIdentity = buildVehicleRows([asset({ identity: { registration: 'N12345' } })], [], new Map(), Date.now());
    expect(viaIdentity[0].registration).toBe('N12345');

    const viaLegacyAttribute = buildVehicleRows(
      [asset({ attributes: { registrationNumber: 'N-OLD' } })],
      [],
      new Map(),
      Date.now(),
    );
    expect(viaLegacyAttribute[0].registration).toBe('N-OLD');

    const neither = buildVehicleRows([asset()], [], new Map(), Date.now());
    expect(neither[0].registration).toBeUndefined();
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
      row({ asset: asset({ assetId: 'a-1', custody: { custodianId: 'u-1' } }) }),
      row({ asset: asset({ assetId: 'a-2', custody: { custodianId: 'u-2' } }) }),
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
      row({ asset: asset({ assetId: 'a-1', custody: { custodianId: 'u-2' } }), custodianName: 'Zed' }),
      row({ asset: asset({ assetId: 'a-2', custody: { custodianId: 'u-1' } }), custodianName: 'Amy' }),
      row({ asset: asset({ assetId: 'a-3', custody: { custodianId: 'u-1' } }), custodianName: 'Amy' }),
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

describe('vehicleRowActions', () => {
  it('an in-stock asset may be issued, grounded, retired, or flown — not returned or released', () => {
    expect(vehicleRowActions({ archived: false, lifecycle: 'ACTIVE', inventoryState: 'IN_STOCK' })).toEqual({
      issue: true,
      return: false,
      ground: true,
      release: false,
      retire: true,
      fly: true,
    });
  });

  it('an issued/in-field asset may be returned, grounded, retired, or flown — not issued again', () => {
    for (const state of ['ISSUED', 'IN_FIELD'] as const) {
      expect(vehicleRowActions({ archived: false, lifecycle: 'ACTIVE', inventoryState: state })).toEqual({
        issue: false,
        return: true,
        ground: true,
        release: false,
        retire: true,
        fly: true,
      });
    }
  });

  it('a MAINTENANCE asset may only be released, retired, or (still) flown', () => {
    expect(vehicleRowActions({ archived: false, lifecycle: 'ACTIVE', inventoryState: 'MAINTENANCE' })).toEqual({
      issue: false,
      return: false,
      ground: false,
      release: true,
      retire: true,
      fly: true,
    });
  });

  it('a RETIRED asset offers nothing inventory-mutating, but Open/Fly stay available', () => {
    expect(vehicleRowActions({ archived: false, lifecycle: 'ACTIVE', inventoryState: 'RETIRED' })).toEqual({
      issue: false,
      return: false,
      ground: false,
      release: false,
      retire: false,
      fly: true,
    });
  });

  it('an archived/deactivated asset offers none of the six verbs', () => {
    expect(vehicleRowActions({ archived: true, lifecycle: 'DELETED', inventoryState: 'IN_STOCK' })).toEqual({
      issue: false,
      return: false,
      ground: false,
      release: false,
      retire: false,
      fly: false,
    });
    expect(vehicleRowActions({ archived: false, lifecycle: 'DEACTIVATED', inventoryState: 'IN_STOCK' })).toEqual({
      issue: false,
      return: false,
      ground: false,
      release: false,
      retire: false,
      fly: false,
    });
  });

  it('an unknown (never-fetched) inventoryState hides every inventory-mutating verb, honestly', () => {
    expect(vehicleRowActions({ archived: false, lifecycle: 'ACTIVE', inventoryState: undefined })).toEqual({
      issue: false,
      return: false,
      ground: false,
      release: false,
      retire: false,
      fly: true,
    });
  });
});
