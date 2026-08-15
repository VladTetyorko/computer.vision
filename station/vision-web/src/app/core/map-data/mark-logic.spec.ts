import { describe, expect, it } from 'vitest';
import type { MapEventPayload, MapMark } from '../api/models';
import {
  DEFAULT_MARK_PALETTE,
  applyMarkEvents,
  bearingDistance,
  bearingDistanceLabel,
  compassPoint,
  countUnverified,
  createMarkRequest,
  editMarkRequest,
  filterMarks,
  formatDistanceMeters,
  isUnverified,
  markPosition,
  paletteFromMark,
  reconcilePaletteLayer,
  toTacticalMark,
  verificationChipClass,
  verificationLabel,
  withPaletteAffiliation,
  withPaletteKind,
  withPaletteLayer,
} from './mark-logic';

function mark(overrides: Partial<MapMark> = {}): MapMark {
  return {
    markId: 'm1',
    layerId: 'layer-a',
    latitude: 50.45,
    longitude: 30.52,
    kind: 'TARGET',
    affiliation: 'HOSTILE',
    label: 'Bunker',
    createdByUserId: 'u1',
    createdAt: '2026-08-05T10:00:00Z',
    status: 'ACTIVE',
    source: 'MANUAL',
    verification: 'UNVERIFIED',
    ...overrides,
  };
}

describe('wire → display projection', () => {
  it('reassembles the flat wire position into a GeoPosition', () => {
    expect(markPosition(mark({ altitudeMeters: 120 }))).toEqual({ latitude: 50.45, longitude: 30.52, altitudeMeters: 120 });
    expect(markPosition(mark()).altitudeMeters).toBeUndefined();
  });

  it('projects only what the map renders, keyed by `id`', () => {
    expect(toTacticalMark(mark({ note: 'seen twice' }))).toEqual({
      id: 'm1',
      layerId: 'layer-a',
      position: { latitude: 50.45, longitude: 30.52, altitudeMeters: undefined },
      kind: 'TARGET',
      affiliation: 'HOSTILE',
      label: 'Bunker',
      note: 'seen twice',
      verification: 'UNVERIFIED',
    });
  });
});

describe('applyMarkEvents', () => {
  function markEvent(overrides: Partial<MapEventPayload> = {}): MapEventPayload {
    return { entity: 'mark', action: 'created', layerId: 'layer-a', mark: mark(), ...overrides };
  }

  it('upserts created/updated and drops cleared/deleted', () => {
    const created = applyMarkEvents([], [markEvent()]);
    expect(created.map((m) => m.markId)).toEqual(['m1']);
    expect(applyMarkEvents(created, [markEvent({ action: 'cleared' })])).toEqual([]);
    expect(applyMarkEvents(created, [markEvent({ action: 'deleted' })])).toEqual([]);
  });

  it('ignores drawing and layer deltas riding the same arrival log', () => {
    const before = [mark()];
    expect(applyMarkEvents(before, [{ entity: 'drawing', action: 'created', layerId: 'layer-a' }])).toBe(before);
  });

  it('drops every mark on a layer that was deleted, even without per-mark events', () => {
    const before = [mark({ markId: 'a', layerId: 'gone' }), mark({ markId: 'b', layerId: 'kept' })];
    const after = applyMarkEvents(before, [{ entity: 'layer', action: 'deleted', layerId: 'gone' }]);
    expect(after.map((m) => m.markId)).toEqual(['b']);
  });
});

describe('palette reducers', () => {
  it('defaults to TARGET + HOSTILE, matching the servers own geolocate default', () => {
    expect(DEFAULT_MARK_PALETTE).toEqual({ kind: 'TARGET', affiliation: 'HOSTILE' });
  });

  it('changes one axis at a time and leaves the rest alone', () => {
    const base = { kind: 'TARGET', affiliation: 'HOSTILE', layerId: 'l1' } as const;
    expect(withPaletteKind(base, 'HAZARD')).toEqual({ kind: 'HAZARD', affiliation: 'HOSTILE', layerId: 'l1' });
    expect(withPaletteAffiliation(base, 'FRIENDLY')).toEqual({ kind: 'TARGET', affiliation: 'FRIENDLY', layerId: 'l1' });
    expect(withPaletteLayer(base, 'l2').layerId).toBe('l2');
    expect(withPaletteLayer(base, undefined).layerId).toBeUndefined();
  });

  it('seeds from an existing mark for the edit flow', () => {
    expect(paletteFromMark(mark({ kind: 'POI', affiliation: 'NEUTRAL', layerId: 'l9' }))).toEqual({
      kind: 'POI',
      affiliation: 'NEUTRAL',
      layerId: 'l9',
    });
  });

  describe('reconcilePaletteLayer', () => {
    it('keeps a layer the viewer may still contribute to — same object, so an effect never loops', () => {
      const palette = { kind: 'TARGET', affiliation: 'HOSTILE', layerId: 'l1' } as const;
      expect(reconcilePaletteLayer(palette, ['l1', 'l2'], 'l2')).toBe(palette);
    });

    it('falls back when the picked layer was deleted or access was revoked', () => {
      const palette = { kind: 'TARGET', affiliation: 'HOSTILE', layerId: 'gone' } as const;
      expect(reconcilePaletteLayer(palette, ['l1'], 'l1').layerId).toBe('l1');
    });

    it('is a no-op when there is nothing to fall back to and nothing was picked', () => {
      const palette = DEFAULT_MARK_PALETTE;
      expect(reconcilePaletteLayer(palette, [], undefined)).toBe(palette);
    });
  });
});

describe('request builders', () => {
  it('flattens a confirmed draft into the wire shape, omitting what is genuinely absent', () => {
    const request = createMarkRequest(
      { palette: { kind: 'HAZARD', affiliation: 'UNKNOWN', layerId: 'l1' }, position: { latitude: 10, longitude: 20 } },
      'Downed line',
    );
    expect(request).toEqual({
      layerId: 'l1',
      latitude: 10,
      longitude: 20,
      altitudeMeters: undefined,
      kind: 'HAZARD',
      affiliation: 'UNKNOWN',
      label: 'Downed line',
      note: undefined,
    });
  });

  it('the edit patch never touches position or status', () => {
    expect(editMarkRequest({ kind: 'POI', affiliation: 'NEUTRAL', layerId: 'l1' }, 'Bridge', 'note')).toEqual({
      kind: 'POI',
      affiliation: 'NEUTRAL',
      label: 'Bridge',
      note: 'note',
    });
  });
});

describe('verification', () => {
  it('labels every state and maps it to one chip modifier', () => {
    expect(verificationLabel('UNVERIFIED')).toBe('Unverified');
    expect(verificationChipClass('UNVERIFIED')).toBe('warn');
    expect(verificationChipClass('CONFIRMED')).toBe('ok');
    expect(verificationChipClass('REJECTED')).toBe('danger');
  });

  it('filters and counts unverified marks for the panels own view chip', () => {
    const marks = [mark({ markId: 'a' }), mark({ markId: 'b', verification: 'CONFIRMED' })];
    expect(isUnverified(marks[0])).toBe(true);
    expect(countUnverified(marks)).toBe(1);
    expect(filterMarks(marks, { unverifiedOnly: true }).map((m) => m.markId)).toEqual(['a']);
    expect(filterMarks(marks)).toBe(marks);
  });
});

describe('bearing/distance (mirrors GeoProjection.bearingDistance)', () => {
  it('due north is 0°, due east is 90°', () => {
    expect(bearingDistance({ latitude: 0, longitude: 0 }, { latitude: 1, longitude: 0 }).bearingDegrees).toBeCloseTo(0, 3);
    expect(bearingDistance({ latitude: 0, longitude: 0 }, { latitude: 0, longitude: 1 }).bearingDegrees).toBeCloseTo(90, 3);
  });

  it('one degree of latitude is ~111.2 km', () => {
    const { distanceMeters } = bearingDistance({ latitude: 0, longitude: 0 }, { latitude: 1, longitude: 0 });
    expect(distanceMeters).toBeCloseTo(111_195, 0);
  });

  it('formats the readout without false precision', () => {
    expect(compassPoint(0)).toBe('N');
    expect(compassPoint(135)).toBe('SE');
    expect(formatDistanceMeters(420)).toBe('420 m');
    expect(formatDistanceMeters(1234)).toBe('1.2 km');
    expect(bearingDistanceLabel({ bearingDegrees: 142.4, distanceMeters: 1234 })).toBe('142° SE · 1.2 km');
  });
});
