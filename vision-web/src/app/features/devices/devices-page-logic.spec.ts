import { describe, expect, it } from 'vitest';
import type { AssetDetails, Device } from '../../core/api/models';
import {
  buildCreateAssetRequestForDevice,
  buildWarehouseRows,
  filterRowsByArchived,
  findWarehouseRowById,
  mapDeviceOwners,
  searchWarehouseRowsByQuery,
} from './devices-page-logic';

/**
 * The lifecycle-action-menu/edit-builder tests (`availableDeviceActions`, `buildDeviceRenameEdit`,
 * `RESTORE_TARGET_STATE`) live in `core/fleet/warehouse-logic.spec.ts` alongside the functions
 * themselves (docs/CYCLES-PLAN.md §11, CD-b); the category-picker tests
 * (`deriveCategoryOptions`/`DEFAULT_CATEGORY_OPTIONS`) live in `core/fleet/category-logic.spec.ts`
 * the same way (docs/UX-REWORK-PLAN.md §U-d). The asset-first list's own tests
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

  it('degrades to undefined for an id matching no loaded row (stale ?sel=, docs/NAV-IA-REDESIGN-PLAN.md §2.4)', () => {
    expect(findWarehouseRowById(rows, 'not-a-real-id')).toBeUndefined();
  });

  it('degrades to undefined for an undefined id (no selection)', () => {
    expect(findWarehouseRowById(rows, undefined)).toBeUndefined();
  });

  it('degrades to undefined for an empty-string id', () => {
    expect(findWarehouseRowById(rows, '')).toBeUndefined();
  });
});

describe('buildCreateAssetRequestForDevice (docs/UX-QUICKWINS-PLAN.md QF-2 — "Promote to asset…", now via deviceIds per docs/REALTIME-PLAN.md §4)', () => {
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
