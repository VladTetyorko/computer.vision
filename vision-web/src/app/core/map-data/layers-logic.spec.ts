import { describe, expect, it } from 'vitest';
import type { LayerGrant, MapEventPayload, MapLayer } from '../api/models';
import {
  accessLevelLabel,
  accessRank,
  accessTo,
  applyLayerEvents,
  atLeast,
  canContribute,
  canManage,
  canView,
  contributableLayers,
  copLayer,
  defaultContributeLayerId,
  grantKey,
  hasGrant,
  layerKindLabel,
  layerName,
  manageableLayers,
  removeGrant,
  sortLayers,
  upsertGrant,
} from './layers-logic';

function layer(overrides: Partial<MapLayer> = {}): MapLayer {
  return {
    layerId: 'l1',
    name: 'Alpha',
    kind: 'TEAM',
    myAccess: 'VIEW',
    markCount: 0,
    drawingCount: 0,
    createdAt: '2026-08-05T10:00:00Z',
    ...overrides,
  };
}

describe('access-level ranking', () => {
  it('ranks VIEW < CONTRIBUTE < MANAGE, matching the domain declaration order', () => {
    expect(accessRank('VIEW')).toBeLessThan(accessRank('CONTRIBUTE'));
    expect(accessRank('CONTRIBUTE')).toBeLessThan(accessRank('MANAGE'));
  });

  it('atLeast compares by rank, and no-access is below every requirement', () => {
    expect(atLeast('MANAGE', 'CONTRIBUTE')).toBe(true);
    expect(atLeast('CONTRIBUTE', 'CONTRIBUTE')).toBe(true);
    expect(atLeast('VIEW', 'CONTRIBUTE')).toBe(false);
    expect(atLeast(undefined, 'VIEW')).toBe(false);
  });

  it('canView/canContribute/canManage read one layers resolved myAccess', () => {
    expect(canView(layer({ myAccess: 'VIEW' }))).toBe(true);
    expect(canContribute(layer({ myAccess: 'VIEW' }))).toBe(false);
    expect(canContribute(layer({ myAccess: 'CONTRIBUTE' }))).toBe(true);
    expect(canManage(layer({ myAccess: 'CONTRIBUTE' }))).toBe(false);
    expect(canManage(layer({ myAccess: 'MANAGE' }))).toBe(true);
    expect(canView(undefined)).toBe(false);
  });

  it('labels every level and kind for the UI', () => {
    expect(accessLevelLabel('CONTRIBUTE')).toBe('Contribute');
    expect(layerKindLabel('COP')).toBe('Common picture');
    expect(layerKindLabel('PERSONAL')).toBe('Personal');
  });
});

describe('ordering and lookups', () => {
  it('sorts COP first, then case-insensitively by name', () => {
    const sorted = sortLayers([
      layer({ layerId: 'b', name: 'beta' }),
      layer({ layerId: 'c', name: 'Common picture', kind: 'COP' }),
      layer({ layerId: 'a', name: 'Alpha' }),
    ]);
    expect(sorted.map((l) => l.layerId)).toEqual(['c', 'a', 'b']);
  });

  it('resolves access, name and the COP layer, degrading to undefined for an unknown id', () => {
    const layers = [layer({ layerId: 'cop', kind: 'COP', name: 'Common picture', myAccess: 'VIEW' }), layer({ layerId: 'l1', myAccess: 'MANAGE' })];
    expect(accessTo(layers, 'l1')).toBe('MANAGE');
    expect(accessTo(layers, 'nope')).toBeUndefined();
    expect(accessTo(layers, undefined)).toBeUndefined();
    expect(layerName(layers, 'cop')).toBe('Common picture');
    expect(layerName(layers, 'nope')).toBeUndefined();
    expect(copLayer(layers)?.layerId).toBe('cop');
    expect(copLayer([layer()])).toBeUndefined();
  });

  it('filters the picker and manager option sets by level', () => {
    const layers = [
      layer({ layerId: 'v', myAccess: 'VIEW' }),
      layer({ layerId: 'c', myAccess: 'CONTRIBUTE' }),
      layer({ layerId: 'm', myAccess: 'MANAGE' }),
    ];
    expect(contributableLayers(layers).map((l) => l.layerId)).toEqual(['c', 'm']);
    expect(manageableLayers(layers).map((l) => l.layerId)).toEqual(['m']);
  });
});

describe('defaultContributeLayerId', () => {
  it('prefers a contributable non-COP layer — the COP is a promotion target, not a scratchpad', () => {
    const layers = [
      layer({ layerId: 'cop', kind: 'COP', myAccess: 'MANAGE' }),
      layer({ layerId: 'team', kind: 'TEAM', myAccess: 'CONTRIBUTE' }),
    ];
    expect(defaultContributeLayerId(layers)).toBe('team');
  });

  it('falls back to a contributable COP when that is genuinely all there is', () => {
    expect(defaultContributeLayerId([layer({ layerId: 'cop', kind: 'COP', myAccess: 'MANAGE' })])).toBe('cop');
  });

  it('is undefined when nothing is writable — the request then omits layerId and the server defaults it', () => {
    expect(defaultContributeLayerId([layer({ myAccess: 'VIEW' })])).toBeUndefined();
    expect(defaultContributeLayerId([])).toBeUndefined();
  });
});

describe('applyLayerEvents', () => {
  function layerEvent(overrides: Partial<MapEventPayload> = {}): MapEventPayload {
    return { entity: 'layer', action: 'created', layerId: 'l2', layer: layer({ layerId: 'l2', name: 'Bravo' }), ...overrides };
  }

  it('upserts a created layer and keeps the COP-first ordering', () => {
    const before = [layer({ layerId: 'cop', kind: 'COP', name: 'Common picture' })];
    const after = applyLayerEvents(before, [layerEvent()]);
    expect(after.map((l) => l.layerId)).toEqual(['cop', 'l2']);
  });

  it('removes a deleted layer', () => {
    const before = [layer({ layerId: 'l2' })];
    expect(applyLayerEvents(before, [layerEvent({ action: 'deleted' })])).toEqual([]);
  });

  it('keeps the grants a REST read already loaded — SSE never carries them (§4.3)', () => {
    const grants: LayerGrant[] = [{ subjectType: 'USER', subjectId: 'u1', level: 'VIEW' }];
    const before = [layer({ layerId: 'l2', myAccess: 'MANAGE', grants })];
    const after = applyLayerEvents(before, [
      layerEvent({ action: 'updated', layer: layer({ layerId: 'l2', name: 'Renamed', myAccess: 'MANAGE' }) }),
    ]);
    expect(after[0].name).toBe('Renamed');
    expect(after[0].grants).toEqual(grants);
  });

  it('leaves the list untouched (same reference) when a run carries nothing for layers', () => {
    const before = [layer()];
    expect(applyLayerEvents(before, [{ entity: 'mark', action: 'created', layerId: 'l1' }])).toBe(before);
  });
});

describe('grants editing (wholesale PUT, so list reducers)', () => {
  const userGrant: LayerGrant = { subjectType: 'USER', subjectId: 'u1', level: 'VIEW' };
  const groupGrant: LayerGrant = { subjectType: 'GROUP', subjectId: 'g1', level: 'CONTRIBUTE' };

  it('keys a grant by subject type + id, so a user and a group with the same uuid never collide', () => {
    expect(grantKey(userGrant)).toBe('USER:u1');
    expect(grantKey({ subjectType: 'GROUP', subjectId: 'u1' })).toBe('GROUP:u1');
  });

  it('upsert adds a new subject and replaces an existing ones level in place', () => {
    const added = upsertGrant([userGrant], groupGrant);
    expect(added).toHaveLength(2);

    const raised = upsertGrant(added, { ...userGrant, level: 'MANAGE' });
    expect(raised).toHaveLength(2);
    expect(raised[0].level).toBe('MANAGE');
  });

  it('remove drops exactly one subject; hasGrant reports membership', () => {
    const grants = [userGrant, groupGrant];
    expect(removeGrant(grants, userGrant).map(grantKey)).toEqual(['GROUP:g1']);
    expect(hasGrant(grants, { subjectType: 'USER', subjectId: 'u1' })).toBe(true);
    expect(hasGrant(grants, { subjectType: 'USER', subjectId: 'u2' })).toBe(false);
  });
});
