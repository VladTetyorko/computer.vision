import { describe, expect, it } from 'vitest';
import type { AssetDetails, AssetSummary, Device } from '../../core/api/models';
import {
  RESTORE_TARGET_STATE,
  availableAssetActions,
  availableDeviceActions,
  buildAssetEdit,
  buildAssetRows,
  buildDeviceRenameEdit,
  buildWarehouseRows,
  filterAssetRowsByArchived,
  filterRowsByArchived,
  mapDeviceOwners,
  type AssetEditForm,
} from './warehouse-logic';

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

describe('RESTORE_TARGET_STATE', () => {
  it('is DEACTIVATED — the contract has no direct DELETED to ACTIVE transition', () => {
    expect(RESTORE_TARGET_STATE).toBe('DEACTIVATED');
  });
});

describe('availableDeviceActions', () => {
  it('offers rename/deactivate/archive/assign for an unowned ACTIVE device', () => {
    expect(availableDeviceActions('ACTIVE', false)).toEqual([
      'rename',
      'deactivate',
      'archive',
      'assign',
    ]);
  });

  it('offers unassign instead of assign for an owned ACTIVE device', () => {
    expect(availableDeviceActions('ACTIVE', true)).toEqual([
      'rename',
      'deactivate',
      'archive',
      'unassign',
    ]);
  });

  it('offers rename/activate/archive for a DEACTIVATED device, regardless of ownership', () => {
    expect(availableDeviceActions('DEACTIVATED', false)).toEqual(['rename', 'activate', 'archive']);
    expect(availableDeviceActions('DEACTIVATED', true)).toEqual(['rename', 'activate', 'archive']);
  });

  it('offers only restore for a DELETED device', () => {
    expect(availableDeviceActions('DELETED', false)).toEqual(['restore']);
    expect(availableDeviceActions('DELETED', true)).toEqual(['restore']);
  });
});

describe('availableAssetActions', () => {
  it('offers rename/deactivate/archive for ACTIVE', () => {
    expect(availableAssetActions('ACTIVE')).toEqual(['rename', 'deactivate', 'archive']);
  });

  it('offers rename/activate/archive for DEACTIVATED', () => {
    expect(availableAssetActions('DEACTIVATED')).toEqual(['rename', 'activate', 'archive']);
  });

  it('offers only restore for DELETED', () => {
    expect(availableAssetActions('DELETED')).toEqual(['restore']);
  });
});

describe('buildDeviceRenameEdit', () => {
  it('includes the trimmed name when it differs from the original', () => {
    expect(buildDeviceRenameEdit('  front gate  ', { name: 'old-name' })).toEqual({
      name: 'front gate',
    });
  });

  it('omits name when blank', () => {
    expect(buildDeviceRenameEdit('   ', { name: 'old-name' })).toEqual({});
  });

  it('omits name when unchanged from the original', () => {
    expect(buildDeviceRenameEdit('old-name', { name: 'old-name' })).toEqual({});
  });

  it('never sends null', () => {
    const edit = buildDeviceRenameEdit('', { name: 'old-name' });
    expect(edit.name).toBeUndefined();
    expect('name' in edit).toBe(false);
  });
});

describe('buildAssetEdit', () => {
  const original = { displayName: 'My Drone', category: 'drone' };

  function form(partial: Partial<AssetEditForm> = {}): AssetEditForm {
    return { displayName: original.displayName, category: original.category, ...partial };
  }

  it('omits both fields when nothing changed', () => {
    expect(buildAssetEdit(form(), original)).toEqual({});
  });

  it('includes only the changed displayName', () => {
    expect(buildAssetEdit(form({ displayName: '  Renamed Drone  ' }), original)).toEqual({
      displayName: 'Renamed Drone',
    });
  });

  it('includes only the changed category', () => {
    expect(buildAssetEdit(form({ category: 'camera' }), original)).toEqual({ category: 'camera' });
  });

  it('includes both when both changed', () => {
    expect(buildAssetEdit(form({ displayName: 'Renamed', category: 'camera' }), original)).toEqual({
      displayName: 'Renamed',
      category: 'camera',
    });
  });

  it('omits a field blanked out to whitespace rather than sending an empty string', () => {
    expect(buildAssetEdit(form({ displayName: '   ' }), original)).toEqual({});
  });
});

describe('mapDeviceOwners', () => {
  it('maps each device id to its owning asset', () => {
    const a = assetDetails({
      assetId: 'a-1',
      displayName: 'Asset 1',
      devices: [device({ id: 'dev-1' }), device({ id: 'dev-2' })],
    });
    const b = assetDetails({ assetId: 'a-2', displayName: 'Asset 2', devices: [device({ id: 'dev-3' })] });

    const owners = mapDeviceOwners([a, b]);

    expect(owners.get('dev-1')).toEqual({ assetId: 'a-1', assetName: 'Asset 1' });
    expect(owners.get('dev-2')).toEqual({ assetId: 'a-1', assetName: 'Asset 1' });
    expect(owners.get('dev-3')).toEqual({ assetId: 'a-2', assetName: 'Asset 2' });
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
    expect(rows[0].owner).toEqual({ assetId: 'a-1', assetName: 'Asset 1' });
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
