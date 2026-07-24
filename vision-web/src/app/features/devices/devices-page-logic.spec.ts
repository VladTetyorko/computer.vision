import { describe, expect, it } from 'vitest';
import type { AssetDetails, AssetSummary, Device } from '../../core/api/models';
import {
  DEFAULT_CATEGORY_OPTIONS,
  buildAssetListRows,
  buildAssetRows,
  buildCreateAssetRequestForDevice,
  buildWarehouseRows,
  deriveCategoryOptions,
  filterAssetListRowsByArchived,
  filterAssetListRowsByCategory,
  filterAssetRowsByArchived,
  filterRowsByArchived,
  mapDeviceOwners,
} from './devices-page-logic';

/**
 * The lifecycle-action-menu/edit-builder tests (`availableDeviceActions`, `availableAssetActions`,
 * `buildDeviceRenameEdit`, `buildAssetEdit`, `RESTORE_TARGET_STATE`) moved to
 * `core/fleet/warehouse-logic.spec.ts` alongside the functions themselves (docs/CYCLES-PLAN.md §11,
 * CD-b) — this file keeps only the Devices-page-specific view-model builders.
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

function assetSummary(partial: Partial<AssetSummary> = {}): AssetSummary {
  return {
    assetId: 'a-0',
    displayName: 'asset',
    category: 'drone',
    categoryName: 'Drone',
    owner: 'owner-0',
    status: 'OFFLINE',
    attributes: {},
    ...partial,
  };
}

function assetDetails(partial: Partial<AssetDetails> = {}): AssetDetails {
  return {
    ...assetSummary(),
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

describe('buildAssetRows', () => {
  it('defaults a missing lifecycle to ACTIVE', () => {
    const rows = buildAssetRows([assetSummary({ lifecycle: undefined })]);
    expect(rows[0].lifecycle).toBe('ACTIVE');
    expect(rows[0].archived).toBe(false);
  });

  it('carries an explicit lifecycle through', () => {
    const rows = buildAssetRows([assetSummary({ lifecycle: 'DEACTIVATED' })]);
    expect(rows[0].lifecycle).toBe('DEACTIVATED');
  });

  it('marks a DELETED asset archived', () => {
    const rows = buildAssetRows([assetSummary({ lifecycle: 'DELETED' })]);
    expect(rows[0].archived).toBe(true);
  });
});

describe('filterAssetRowsByArchived', () => {
  const rows = buildAssetRows([
    assetSummary({ assetId: 'active', lifecycle: 'ACTIVE' }),
    assetSummary({ assetId: 'archived', lifecycle: 'DELETED' }),
  ]);

  it('hides archived cards when showArchived is false', () => {
    expect(filterAssetRowsByArchived(rows, false).map((r) => r.asset.assetId)).toEqual(['active']);
  });

  it('keeps archived cards when showArchived is true', () => {
    expect(filterAssetRowsByArchived(rows, true).map((r) => r.asset.assetId)).toEqual([
      'active',
      'archived',
    ]);
  });
});

describe('buildAssetListRows (docs/CYCLES-PLAN.md §11, CD-b item 1 — the asset-first primary list)', () => {
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

  it('defaults a missing lifecycle to ACTIVE, same as buildAssetRows', () => {
    const rows = buildAssetListRows([assetDetails({ lifecycle: undefined })], new Set());
    expect(rows[0].lifecycle).toBe('ACTIVE');
    expect(rows[0].archived).toBe(false);
  });

  it('marks a DELETED asset archived', () => {
    const rows = buildAssetListRows([assetDetails({ lifecycle: 'DELETED' })], new Set());
    expect(rows[0].archived).toBe(true);
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

describe('filterAssetListRowsByCategory (docs/UX-QUICKWINS-PLAN.md QF-2 — /devices?category= support)', () => {
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

describe('deriveCategoryOptions (docs/UX-QUICKWINS-PLAN.md QF-2 — the create-asset category picker)', () => {
  it('derives options from the categories already in use, deduped and sorted by name', () => {
    const assets = [
      assetSummary({ category: 'drone', categoryName: 'Drone' }),
      assetSummary({ category: 'ip-camera', categoryName: 'IP Camera' }),
      assetSummary({ category: 'drone', categoryName: 'Drone' }),
    ];
    expect(deriveCategoryOptions(assets)).toEqual([
      { slug: 'drone', name: 'Drone' },
      { slug: 'ip-camera', name: 'IP Camera' },
    ]);
  });

  it('falls back to the default set when no asset exists yet', () => {
    expect(deriveCategoryOptions([])).toEqual(DEFAULT_CATEGORY_OPTIONS);
  });
});

describe('buildCreateAssetRequestForDevice (docs/UX-QUICKWINS-PLAN.md QF-2 — the orphaned-device quick fix)', () => {
  it('wraps the device\'s own connection details as the new asset\'s one device', () => {
    const request = buildCreateAssetRequestForDevice(
      device({ id: 'dev-1', name: 'front-gate', protocol: 'rtsp', uri: 'rtsp://192.168.1.50:554/stream' }),
      'Front gate camera',
      'ip-camera',
    );
    expect(request).toEqual({
      displayName: 'Front gate camera',
      category: 'ip-camera',
      devices: [
        {
          name: 'front-gate',
          protocol: 'rtsp',
          uri: 'rtsp://192.168.1.50:554/stream',
          capabilities: ['VIDEO'],
        },
      ],
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

  it('includes options only when the device carries any', () => {
    const withOptions = buildCreateAssetRequestForDevice(
      device({ options: { rtsp_transport: 'tcp' } }),
      'name',
      'drone',
    );
    expect(withOptions.devices[0].options).toEqual({ rtsp_transport: 'tcp' });

    const withoutOptions = buildCreateAssetRequestForDevice(device({ options: {} }), 'name', 'drone');
    expect(withoutOptions.devices[0]).not.toHaveProperty('options');
  });
});
