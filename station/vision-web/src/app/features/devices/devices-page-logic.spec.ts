import { describe, expect, it } from 'vitest';
import type { AssetDetails, Device } from '../../core/api/models';
import {
  buildCreateAssetRequestForDevice,
  buildWarehouseRows,
  describeDeviceState,
  endpointConflicts,
  filterRowsByArchived,
  findWarehouseRowById,
  mapDeviceOwners,
  searchWarehouseRowsByQuery,
} from './devices-page-logic';

/**
 * The lifecycle-action-menu/edit-builder tests (`availableDeviceActions`, `buildDeviceRenameEdit`,
 * `RESTORE_TARGET_STATE`) live in `core/fleet/warehouse-logic.spec.ts` alongside the functions
 * themselves (docs/main/CYCLES-PLAN.md §11, CD-b); the category-picker tests (`deriveCategoryOptions`)
 * live in `core/fleet/category-logic.spec.ts` the same way (docs/plans/done/UX-REWORK-PLAN.md §U-d). The
 * asset-first list's own tests
 * (`buildAssetListRows`/`filterAssetListRowsByArchived`/`filterAssetListRowsByCategory` and friends)
 * moved to `features/assets/assets-logic.spec.ts` once the Assets page split out of this one — this
 * file keeps only the Devices-page-specific (raw device table) view-model builders.
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

describe('mapDeviceOwners', () => {
  it('maps each device id to its owning asset', () => {
    const a = assetDetails({
      assetId: 'a-1',
      displayName: 'Asset 1',
      devices: [device({ id: 'dev-1' }), device({ id: 'dev-2' })],
    });
    const b = assetDetails({ assetId: 'a-2', displayName: 'Asset 2', devices: [device({ id: 'dev-3' })] });

    const owners = mapDeviceOwners([a, b]);

    expect(owners.get('dev-1')).toEqual({ assetId: 'a-1', assetName: 'Asset 1', deviceCount: 2 });
    expect(owners.get('dev-2')).toEqual({ assetId: 'a-1', assetName: 'Asset 1', deviceCount: 2 });
    expect(owners.get('dev-3')).toEqual({ assetId: 'a-2', assetName: 'Asset 2', deviceCount: 1 });
  });

  it("carries the owning asset's device count, for the unassign-would-empty-it poka-yoke check", () => {
    const onlyDevice = assetDetails({
      assetId: 'a-only',
      displayName: 'Only',
      devices: [device({ id: 'solo' })],
    });
    expect(mapDeviceOwners([onlyDevice]).get('solo')?.deviceCount).toBe(1);
  });

  it('returns an empty map for no assets', () => {
    expect(mapDeviceOwners([]).size).toBe(0);
  });

  it('returns an empty map when assets have no devices', () => {
    expect(mapDeviceOwners([assetDetails({ devices: [] })]).size).toBe(0);
  });
});

describe('buildWarehouseRows', () => {
  it('marks a device owned when it appears in the owners map', () => {
    const owners = mapDeviceOwners([
      assetDetails({ assetId: 'a-1', displayName: 'Asset 1', devices: [device({ id: 'dev-1' })] }),
    ]);
    const rows = buildWarehouseRows([device({ id: 'dev-1' })], owners, new Set());
    expect(rows[0].owner).toEqual({ assetId: 'a-1', assetName: 'Asset 1', deviceCount: 1 });
  });

  it('leaves owner absent for an unowned device', () => {
    const rows = buildWarehouseRows([device({ id: 'dev-1' })], new Map(), new Set());
    expect(rows[0].owner).toBeUndefined();
  });

  it('marks streaming true only for devices in liveDeviceIds', () => {
    const rows = buildWarehouseRows(
      [device({ id: 'dev-1' }), device({ id: 'dev-2' })],
      new Map(),
      new Set(['dev-1']),
    );
    expect(rows.find((r) => r.device.id === 'dev-1')?.streaming).toBe(true);
    expect(rows.find((r) => r.device.id === 'dev-2')?.streaming).toBe(false);
  });

  it('marks archived true only for DELETED devices', () => {
    const rows = buildWarehouseRows(
      [device({ id: 'dev-1', state: 'DELETED' }), device({ id: 'dev-2', state: 'ACTIVE' })],
      new Map(),
      new Set(),
    );
    expect(rows.find((r) => r.device.id === 'dev-1')?.archived).toBe(true);
    expect(rows.find((r) => r.device.id === 'dev-2')?.archived).toBe(false);
  });

  it('carries the device state through as lifecycle', () => {
    const rows = buildWarehouseRows([device({ state: 'DEACTIVATED' })], new Map(), new Set());
    expect(rows[0].lifecycle).toBe('DEACTIVATED');
  });
});

describe('filterRowsByArchived', () => {
  const rows = buildWarehouseRows(
    [device({ id: 'active', state: 'ACTIVE' }), device({ id: 'archived', state: 'DELETED' })],
    new Map(),
    new Set(),
  );

  it('hides archived rows when showArchived is false', () => {
    const visible = filterRowsByArchived(rows, false);
    expect(visible.map((r) => r.device.id)).toEqual(['active']);
  });

  it('keeps archived rows when showArchived is true', () => {
    const visible = filterRowsByArchived(rows, true);
    expect(visible.map((r) => r.device.id)).toEqual(['active', 'archived']);
  });
});

describe('searchWarehouseRowsByQuery', () => {
  const owners = mapDeviceOwners([
    assetDetails({ assetId: 'a-1', displayName: 'Front Gate', devices: [device({ id: 'dev-1' })] }),
  ]);
  const rows = buildWarehouseRows(
    [
      device({ id: 'dev-1', name: 'Gate camera', protocol: 'rtsp', uri: 'rtsp://192.168.1.50:554/stream' }),
      device({ id: 'dev-2', name: 'Back yard', protocol: 'mjpeg', uri: 'http://192.168.1.60:8080' }),
    ],
    owners,
    new Set(),
  );

  it('leaves every row for a blank query', () => {
    expect(searchWarehouseRowsByQuery(rows, '   ').map((r) => r.device.id)).toEqual(['dev-1', 'dev-2']);
  });

  it('matches case-insensitively on the device name', () => {
    expect(searchWarehouseRowsByQuery(rows, 'GATE camera').map((r) => r.device.id)).toEqual(['dev-1']);
  });

  it('matches on protocol', () => {
    expect(searchWarehouseRowsByQuery(rows, 'mjpeg').map((r) => r.device.id)).toEqual(['dev-2']);
  });

  it('matches on the URI, e.g. searching by IP address', () => {
    expect(searchWarehouseRowsByQuery(rows, '192.168.1.50').map((r) => r.device.id)).toEqual(['dev-1']);
  });

  it("matches on the owning asset's name", () => {
    expect(searchWarehouseRowsByQuery(rows, 'front gate').map((r) => r.device.id)).toEqual(['dev-1']);
  });

  it('narrows to zero rows for a query matching nothing', () => {
    expect(searchWarehouseRowsByQuery(rows, 'nope')).toEqual([]);
  });
});

describe('findWarehouseRowById', () => {
  const rows = buildWarehouseRows([device({ id: 'dev-1' }), device({ id: 'dev-2' })], new Map(), new Set());

  it('finds the row matching the given id', () => {
    expect(findWarehouseRowById(rows, 'dev-2')?.device.id).toBe('dev-2');
  });

  it('degrades to undefined for an id matching no loaded row (stale ?sel=, docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4)', () => {
    expect(findWarehouseRowById(rows, 'not-a-real-id')).toBeUndefined();
  });

  it('degrades to undefined for an undefined id (no selection)', () => {
    expect(findWarehouseRowById(rows, undefined)).toBeUndefined();
  });

  it('degrades to undefined for an empty-string id', () => {
    expect(findWarehouseRowById(rows, '')).toBeUndefined();
  });
});

describe('describeDeviceState', () => {
  it('is "archived" whenever archived is true, regardless of lifecycle/streaming', () => {
    expect(describeDeviceState({ lifecycle: 'ACTIVE', archived: true, streaming: true })).toEqual({
      kind: 'archived',
      label: 'Archived',
    });
  });

  it('is "deactivated" for a non-archived DEACTIVATED row, even while streaming', () => {
    expect(describeDeviceState({ lifecycle: 'DEACTIVATED', archived: false, streaming: true })).toEqual({
      kind: 'deactivated',
      label: 'Deactivated',
    });
  });

  it('is "live" for an active, streaming row', () => {
    expect(describeDeviceState({ lifecycle: 'ACTIVE', archived: false, streaming: true })).toEqual({
      kind: 'live',
      label: 'Live',
    });
  });

  it('is "stopped" for the default steady state — active, not streaming', () => {
    expect(describeDeviceState({ lifecycle: 'ACTIVE', archived: false, streaming: false })).toEqual({
      kind: 'stopped',
      label: 'Stopped',
    });
  });

  it('archived outranks every other state', () => {
    expect(describeDeviceState({ lifecycle: 'DEACTIVATED', archived: true, streaming: false }).kind).toBe('archived');
  });
});

describe('endpointConflicts (docs/plans/active/OPERATOR-UX-7-PLAN.md finding D1)', () => {
  it('returns an empty map when no two devices share a protocol+uri', () => {
    const conflicts = endpointConflicts([
      device({ id: 'dev-1', protocol: 'mavlink', uri: 'udp://0.0.0.0:14550' }),
      device({ id: 'dev-2', protocol: 'rtsp', uri: 'rtsp://192.168.1.50:554/stream' }),
    ]);
    expect(conflicts.size).toBe(0);
  });

  it('maps each device in a shared-endpoint group to the names of the others', () => {
    const conflicts = endpointConflicts([
      device({ id: 'dev-1', name: 'ESP32 Rover', protocol: 'mavlink', uri: 'udp://0.0.0.0:14550' }),
      device({ id: 'dev-2', name: 'ESP32 telemetry', protocol: 'mavlink', uri: 'udp://0.0.0.0:14550' }),
      device({ id: 'dev-3', name: 'ESP32 Rover · telemetry', protocol: 'mavlink', uri: 'udp://0.0.0.0:14550' }),
    ]);
    expect(conflicts.get('dev-1')).toEqual(['ESP32 telemetry', 'ESP32 Rover · telemetry']);
    expect(conflicts.get('dev-2')).toEqual(['ESP32 Rover', 'ESP32 Rover · telemetry']);
    expect(conflicts.get('dev-3')).toEqual(['ESP32 Rover', 'ESP32 telemetry']);
  });

  it('requires both protocol and uri to match — same uri, different protocol is not a conflict', () => {
    const conflicts = endpointConflicts([
      device({ id: 'dev-1', protocol: 'rtsp', uri: '192.168.1.50:554' }),
      device({ id: 'dev-2', protocol: 'mjpeg', uri: '192.168.1.50:554' }),
    ]);
    expect(conflicts.size).toBe(0);
  });

  it('excludes archived (DELETED) devices from consideration entirely', () => {
    const conflicts = endpointConflicts([
      device({ id: 'dev-1', name: 'Live one', protocol: 'mavlink', uri: 'udp://0.0.0.0:14550', state: 'ACTIVE' }),
      device({ id: 'dev-2', name: 'Archived one', protocol: 'mavlink', uri: 'udp://0.0.0.0:14550', state: 'DELETED' }),
    ]);
    expect(conflicts.size).toBe(0);
  });

  it('treats DEACTIVATED as still-active (non-archived) for conflict purposes', () => {
    const conflicts = endpointConflicts([
      device({ id: 'dev-1', name: 'A', protocol: 'mavlink', uri: 'udp://0.0.0.0:14550', state: 'ACTIVE' }),
      device({ id: 'dev-2', name: 'B', protocol: 'mavlink', uri: 'udp://0.0.0.0:14550', state: 'DEACTIVATED' }),
    ]);
    expect(conflicts.get('dev-1')).toEqual(['B']);
    expect(conflicts.get('dev-2')).toEqual(['A']);
  });

  it('normalises trailing whitespace on the uri before comparing', () => {
    const conflicts = endpointConflicts([
      device({ id: 'dev-1', name: 'A', protocol: 'mavlink', uri: 'udp://0.0.0.0:14550' }),
      device({ id: 'dev-2', name: 'B', protocol: 'mavlink', uri: 'udp://0.0.0.0:14550  ' }),
    ]);
    expect(conflicts.get('dev-1')).toEqual(['B']);
    expect(conflicts.get('dev-2')).toEqual(['A']);
  });

  it('does not trim leading whitespace or fold case — only trailing whitespace is normalised', () => {
    const conflicts = endpointConflicts([
      device({ id: 'dev-1', protocol: 'mavlink', uri: 'udp://0.0.0.0:14550' }),
      device({ id: 'dev-2', protocol: 'mavlink', uri: ' udp://0.0.0.0:14550' }),
      device({ id: 'dev-3', protocol: 'MAVLINK', uri: 'udp://0.0.0.0:14550' }),
    ]);
    expect(conflicts.size).toBe(0);
  });

  it('does not let a protocol/uri split ambiguity collide two unrelated pairs', () => {
    const conflicts = endpointConflicts([
      device({ id: 'dev-1', protocol: 'a b', uri: 'c' }),
      device({ id: 'dev-2', protocol: 'a', uri: 'b c' }),
    ]);
    expect(conflicts.size).toBe(0);
  });

  it('a solitary device on its own endpoint has no entry at all', () => {
    const conflicts = endpointConflicts([device({ id: 'dev-1', protocol: 'mavlink', uri: 'udp://0.0.0.0:14550' })]);
    expect(conflicts.has('dev-1')).toBe(false);
  });

  it('returns an empty map for no devices', () => {
    expect(endpointConflicts([]).size).toBe(0);
  });
});

describe('buildCreateAssetRequestForDevice (docs/plans/done/UX-QUICKWINS-PLAN.md QF-2 — "Promote to asset…", now via deviceIds per docs/plans/done/REALTIME-PLAN.md §4)', () => {
  it("assigns the existing device by id — no devices array, no duplicate registration", () => {
    const request = buildCreateAssetRequestForDevice(
      device({ id: 'dev-1', name: 'front-gate', protocol: 'rtsp', uri: 'rtsp://192.168.1.50:554/stream' }),
      'Front gate camera',
      'ip-camera',
    );
    expect(request).toEqual({
      displayName: 'Front gate camera',
      category: 'ip-camera',
      deviceIds: ['dev-1'],
    });
  });

  it('falls back to the device name when displayName is blank', () => {
    const request = buildCreateAssetRequestForDevice(device({ name: 'front-gate' }), '   ', 'ip-camera');
    expect(request.displayName).toBe('front-gate');
  });

  it('trims a non-blank displayName', () => {
    const request = buildCreateAssetRequestForDevice(device(), '  My Drone  ', 'drone');
    expect(request.displayName).toBe('My Drone');
  });

  it("carries the device's own id regardless of its other fields (options/capabilities stay untouched, on the device itself)", () => {
    const request = buildCreateAssetRequestForDevice(
      device({ id: 'dev-2', options: { rtsp_transport: 'tcp' }, capabilities: ['VIDEO', 'TELEMETRY'] }),
      'name',
      'drone',
    );
    expect(request.deviceIds).toEqual(['dev-2']);
    expect(request).not.toHaveProperty('devices');
  });
});
