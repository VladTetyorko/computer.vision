import { describe, expect, it } from 'vitest';
import {
  ASSET_ACTION_LABELS,
  DEVICE_ACTION_LABELS,
  RESTORE_TARGET_STATE,
  availableAssetActions,
  availableDeviceActions,
  buildAssetEdit,
  buildDeviceRenameEdit,
  operatorAssetActions,
  reasonedAssetActions,
  reasonedDeviceActions,
  type ActionAvailability,
  type AssetEditForm,
  type AssetLifecycleAction,
  type DeviceLifecycleAction,
} from './warehouse-logic';

/** Every entry an `ActionAvailability[]` carries, reduced to `{action, available, reason}` triples
 *  a spec can assert against with a single `toEqual` — reads closer to the reasons matrix itself
 *  than repeating `.find(...)` per action under test. */
function summarize<TAction extends string>(
  entries: readonly ActionAvailability<TAction>[],
): readonly { action: TAction; available: boolean; reason?: string }[] {
  return entries.map(({ action, available, reason }) =>
    reason === undefined ? { action, available } : { action, available, reason },
  );
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

describe('DEVICE_ACTION_LABELS / ASSET_ACTION_LABELS (docs/UX-REWORK-PLAN.md §U-a2 §1 — verb+object)', () => {
  it('labels every device action verb+object, ellipsis only for the two that open a form first', () => {
    expect(DEVICE_ACTION_LABELS).toEqual({
      rename: 'Rename device…',
      activate: 'Activate device',
      deactivate: 'Deactivate device',
      archive: 'Archive device',
      restore: 'Restore device',
      assign: 'Assign to asset…',
      unassign: 'Unassign from asset',
    });
  });

  it('labels every asset action verb+object', () => {
    expect(ASSET_ACTION_LABELS).toEqual({
      rename: 'Rename asset…',
      activate: 'Activate asset',
      deactivate: 'Deactivate asset',
      archive: 'Archive asset',
      restore: 'Restore asset',
    });
  });
});

describe('reasonedDeviceActions (docs/UX-REWORK-PLAN.md §U-a2 item 3a — poka-yoke prevention)', () => {
  it('an unowned ACTIVE device: only activate/restore are blocked, both for lifecycle reasons', () => {
    expect(summarize(reasonedDeviceActions('ACTIVE', false))).toEqual([
      { action: 'rename', available: true },
      { action: 'activate', available: false, reason: 'Already active.' },
      { action: 'deactivate', available: true },
      { action: 'assign', available: true },
      { action: 'archive', available: true },
      { action: 'restore', available: false, reason: 'Not archived — nothing to restore.' },
    ]);
  });

  it('an owned ACTIVE device with >1 sibling device: unassign is available', () => {
    const entries = reasonedDeviceActions('ACTIVE', true, 2);
    expect(entries.find((e) => e.action === 'unassign')).toEqual({
      action: 'unassign',
      available: true,
    });
  });

  it("an owned ACTIVE device that is its asset's only device: unassign is blocked, not a 409 surprise", () => {
    const entries = reasonedDeviceActions('ACTIVE', true, 1);
    expect(entries.find((e) => e.action === 'unassign')).toEqual({
      action: 'unassign',
      available: false,
      reason: 'This asset has only one device — assign another before unassigning this one.',
    });
  });

  it('an owned ACTIVE device with ownerDeviceCount omitted is not blocked (unknown ≠ "only device")', () => {
    const entries = reasonedDeviceActions('ACTIVE', true);
    expect(entries.find((e) => e.action === 'unassign')?.available).toBe(true);
  });

  it('never offers the opposite assign/unassign slot at all, whatever the state', () => {
    expect(reasonedDeviceActions('ACTIVE', true).some((e) => e.action === 'assign')).toBe(false);
    expect(reasonedDeviceActions('ACTIVE', false).some((e) => e.action === 'unassign')).toBe(false);
  });

  it('a DEACTIVATED device: deactivate/restore/the assign-or-unassign slot are all blocked', () => {
    expect(summarize(reasonedDeviceActions('DEACTIVATED', false))).toEqual([
      { action: 'rename', available: true },
      { action: 'activate', available: true },
      { action: 'deactivate', available: false, reason: 'Already deactivated.' },
      { action: 'assign', available: false, reason: 'Reactivate the device first.' },
      { action: 'archive', available: true },
      { action: 'restore', available: false, reason: 'Not archived — nothing to restore.' },
    ]);

    expect(
      reasonedDeviceActions('DEACTIVATED', true).find((e) => e.action === 'unassign'),
    ).toEqual({
      action: 'unassign',
      available: false,
      reason: 'Reactivate the device first.',
    });
  });

  it('a DELETED (archived) device: only restore is available — everything else says so', () => {
    expect(summarize(reasonedDeviceActions('DELETED', false))).toEqual([
      { action: 'rename', available: false, reason: 'Archived — restore it first.' },
      { action: 'activate', available: false, reason: 'Archived — restore it first.' },
      { action: 'deactivate', available: false, reason: 'Archived — restore it first.' },
      { action: 'assign', available: false, reason: 'Archived — restore it first.' },
      { action: 'archive', available: false, reason: 'Already archived.' },
      { action: 'restore', available: true },
    ]);
  });

  it('does not disable Archive for a streaming device — archiving stops the stream itself, never rejected for it', () => {
    // No `streaming`/`isLive` parameter exists on this function at all: verified by reading
    // DefaultAssetService#setState/DefaultDeviceService#setState (vision-application), both of
    // which stop the running stream themselves as part of the very same transition. "Stop the
    // stream first" is not a real precondition in this domain — see this module's own doc comment.
    const archive = reasonedDeviceActions('ACTIVE', false).find((e) => e.action === 'archive');
    expect(archive).toEqual({ action: 'archive', available: true });
  });
});

describe('reasonedAssetActions (docs/UX-REWORK-PLAN.md §U-a2 item 3a)', () => {
  it('ACTIVE: only activate/restore are blocked', () => {
    expect(summarize(reasonedAssetActions('ACTIVE'))).toEqual([
      { action: 'rename', available: true },
      { action: 'activate', available: false, reason: 'Already active.' },
      { action: 'deactivate', available: true },
      { action: 'archive', available: true },
      { action: 'restore', available: false, reason: 'Not archived — nothing to restore.' },
    ]);
  });

  it('DEACTIVATED: only deactivate/restore are blocked', () => {
    expect(summarize(reasonedAssetActions('DEACTIVATED'))).toEqual([
      { action: 'rename', available: true },
      { action: 'activate', available: true },
      { action: 'deactivate', available: false, reason: 'Already deactivated.' },
      { action: 'archive', available: true },
      { action: 'restore', available: false, reason: 'Not archived — nothing to restore.' },
    ]);
  });

  it('DELETED: only restore is available', () => {
    expect(summarize(reasonedAssetActions('DELETED'))).toEqual([
      { action: 'rename', available: false, reason: 'Archived — restore it first.' },
      { action: 'activate', available: false, reason: 'Archived — restore it first.' },
      { action: 'deactivate', available: false, reason: 'Archived — restore it first.' },
      { action: 'archive', available: false, reason: 'Already archived.' },
      { action: 'restore', available: true },
    ]);
  });
});

describe('operatorAssetActions (docs/UX-REWORK-PLAN.md §U-a2 item 2 — Archive+Restore only)', () => {
  it('narrows to exactly archive/restore, dropping rename/activate/deactivate entirely', () => {
    for (const state of ['ACTIVE', 'DEACTIVATED', 'DELETED'] as const) {
      expect(operatorAssetActions(state).map((e) => e.action)).toEqual(['archive', 'restore']);
    }
  });

  it('an in-service asset (ACTIVE or DEACTIVATED): archive is the one available action', () => {
    for (const state of ['ACTIVE', 'DEACTIVATED'] as const) {
      expect(summarize(operatorAssetActions(state))).toEqual([
        { action: 'archive', available: true },
        { action: 'restore', available: false, reason: 'Not archived — nothing to restore.' },
      ]);
    }
  });

  it('an archived asset: restore is the one available action', () => {
    expect(summarize(operatorAssetActions('DELETED'))).toEqual([
      { action: 'archive', available: false, reason: 'Already archived.' },
      { action: 'restore', available: true },
    ]);
  });
});

// Compile-time check that the exported label maps stay total over their action union — a future
// `DeviceLifecycleAction`/`AssetLifecycleAction` member with no label would fail `tsc`, not just a
// spec, but this keeps the two types honest about being label-map keys too.
const _deviceLabelsTotal: Record<DeviceLifecycleAction, string> = DEVICE_ACTION_LABELS;
const _assetLabelsTotal: Record<AssetLifecycleAction, string> = ASSET_ACTION_LABELS;
void _deviceLabelsTotal;
void _assetLabelsTotal;
