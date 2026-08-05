import { describe, expect, it } from 'vitest';
import type { MapEventPayload, MapMark } from '../api/models';
import {
  applyMapEvent,
  applyMapEvents,
  deletedLayerIds,
  dropByLayer,
  isRemoval,
  removeById,
  upsertById,
  type MapEntitySpec,
} from './map-event-logic';

function mark(overrides: Partial<MapMark> = {}): MapMark {
  return {
    markId: 'm1',
    layerId: 'layer-a',
    latitude: 50,
    longitude: 30,
    kind: 'TARGET',
    affiliation: 'HOSTILE',
    label: 'Contact',
    createdByUserId: 'u1',
    createdAt: '2026-08-05T10:00:00Z',
    status: 'ACTIVE',
    source: 'MANUAL',
    verification: 'UNVERIFIED',
    ...overrides,
  };
}

const MARK_SPEC: MapEntitySpec<MapMark> = {
  entity: 'mark',
  idOf: (m) => m.markId,
  payloadOf: (event) => event.mark,
};

function event(overrides: Partial<MapEventPayload> = {}): MapEventPayload {
  return { entity: 'mark', action: 'created', layerId: 'layer-a', mark: mark(), ...overrides };
}

describe('isRemoval', () => {
  it('treats cleared and deleted as removals, created/updated as upserts', () => {
    expect(isRemoval('cleared')).toBe(true);
    expect(isRemoval('deleted')).toBe(true);
    expect(isRemoval('created')).toBe(false);
    expect(isRemoval('updated')).toBe(false);
  });
});

describe('upsertById / removeById', () => {
  const idOf = (m: MapMark) => m.markId;

  it('prepends a genuinely new item (lists are newest-first)', () => {
    const result = upsertById([mark({ markId: 'm1' })], mark({ markId: 'm2' }), idOf);
    expect(result.map(idOf)).toEqual(['m2', 'm1']);
  });

  it('replaces an existing item in place, preserving order', () => {
    const before = [mark({ markId: 'm1' }), mark({ markId: 'm2' }), mark({ markId: 'm3' })];
    const result = upsertById(before, mark({ markId: 'm2', label: 'Renamed' }), idOf);
    expect(result.map(idOf)).toEqual(['m1', 'm2', 'm3']);
    expect(result[1].label).toBe('Renamed');
  });

  it('removeById drops the match and always returns a fresh array', () => {
    const before = [mark({ markId: 'm1' })];
    expect(removeById(before, 'm1', idOf)).toEqual([]);
    expect(removeById(before, 'nope', idOf)).not.toBe(before);
  });
});

describe('applyMapEvent', () => {
  it('ignores an event for another entity — all three stores read the same arrival log', () => {
    const before = [mark()];
    expect(applyMapEvent(before, event({ entity: 'drawing', mark: undefined }), MARK_SPEC)).toBe(before);
  });

  it('ignores an event whose own object is missing rather than throwing', () => {
    const before = [mark()];
    expect(applyMapEvent(before, event({ mark: undefined }), MARK_SPEC)).toBe(before);
  });

  it('upserts on created and updated', () => {
    const created = applyMapEvent([], event({ action: 'created' }), MARK_SPEC);
    expect(created.map((m) => m.markId)).toEqual(['m1']);

    const updated = applyMapEvent(created, event({ action: 'updated', mark: mark({ label: 'Moved' }) }), MARK_SPEC);
    expect(updated).toHaveLength(1);
    expect(updated[0].label).toBe('Moved');
  });

  it('removes on cleared and on deleted', () => {
    expect(applyMapEvent([mark()], event({ action: 'cleared' }), MARK_SPEC)).toEqual([]);
    expect(applyMapEvent([mark()], event({ action: 'deleted' }), MARK_SPEC)).toEqual([]);
  });

  it('applyMapEvents folds a run in arrival order — a later removal wins over an earlier create', () => {
    const result = applyMapEvents(
      [],
      [event({ action: 'created' }), event({ action: 'updated', mark: mark({ label: 'B' }) }), event({ action: 'deleted' })],
      MARK_SPEC,
    );
    expect(result).toEqual([]);
  });
});

describe('layer-deletion cascade', () => {
  it('collects the distinct layer ids a run deleted, ignoring other entities/actions', () => {
    const events: MapEventPayload[] = [
      { entity: 'layer', action: 'deleted', layerId: 'l1' },
      { entity: 'layer', action: 'deleted', layerId: 'l1' },
      { entity: 'layer', action: 'updated', layerId: 'l2' },
      { entity: 'mark', action: 'deleted', layerId: 'l3', mark: mark() },
    ];
    expect(deletedLayerIds(events)).toEqual(['l1']);
  });

  it('dropByLayer removes every item on a deleted layer and is a no-op for none', () => {
    const items = [mark({ markId: 'a', layerId: 'l1' }), mark({ markId: 'b', layerId: 'l2' })];
    expect(dropByLayer(items, ['l1']).map((m) => m.markId)).toEqual(['b']);
    expect(dropByLayer(items, [])).toBe(items);
  });
});
