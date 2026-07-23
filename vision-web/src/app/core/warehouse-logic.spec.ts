import { describe, expect, it } from 'vitest';
import {
  RESTORE_TARGET_STATE,
  availableAssetActions,
  availableDeviceActions,
  buildAssetEdit,
  buildDeviceRenameEdit,
  type AssetEditForm,
} from './warehouse-logic';

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
