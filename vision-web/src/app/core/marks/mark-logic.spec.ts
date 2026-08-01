import { describe, expect, it } from 'vitest';
import {
  EARTH_RADIUS_METERS,
  MARK_KINDS,
  applyMarkEvent,
  applyMarkEvents,
  bearingDistance,
  bearingDistanceLabel,
  compassPoint,
  formatDistanceMeters,
  markColor,
  markKindIcon,
  markKindLabel,
  markStyle,
  removeMark,
  upsertMark,
} from './mark-logic';
import type { Mark, MarkEvent, MarkKind } from '../api/models';

function mark(partial: Partial<Mark> = {}): Mark {
  return {
    id: 'm-1',
    kind: 'TARGET',
    label: 'Bunker',
    position: { latitude: 50.45, longitude: 30.52 },
    createdBy: 'u-1',
    createdAt: '2026-07-31T10:00:00Z',
    status: 'ACTIVE',
    source: 'MANUAL',
    ...partial,
  };
}

describe('upsertMark', () => {
  it('prepends a genuinely new mark', () => {
    expect(upsertMark([], mark())).toEqual([mark()]);
  });

  it('replaces an existing mark in place, preserving array position', () => {
    const first = mark({ id: 'm-1', label: 'First' });
    const second = mark({ id: 'm-2', label: 'Second' });
    const updated = mark({ id: 'm-1', label: 'First (renamed)' });

    const result = upsertMark([first, second], updated);

    expect(result).toEqual([updated, second]);
  });
});

describe('removeMark', () => {
  it('drops the matching mark', () => {
    const a = mark({ id: 'm-1' });
    const b = mark({ id: 'm-2' });
    expect(removeMark([a, b], 'm-1')).toEqual([b]);
  });

  it('is a no-op (content-wise) when the id is absent', () => {
    const a = mark({ id: 'm-1' });
    expect(removeMark([a], 'does-not-exist')).toEqual([a]);
  });
});

describe('applyMarkEvent', () => {
  it('created upserts (adds) the mark', () => {
    const event: MarkEvent = { action: 'created', mark: mark({ id: 'm-1' }) };
    expect(applyMarkEvent([], event)).toEqual([mark({ id: 'm-1' })]);
  });

  it('updated upserts (replaces) the mark', () => {
    const original = mark({ id: 'm-1', label: 'Original' });
    const event: MarkEvent = { action: 'updated', mark: mark({ id: 'm-1', label: 'Renamed' }) };
    expect(applyMarkEvent([original], event)).toEqual([mark({ id: 'm-1', label: 'Renamed' })]);
  });

  it('cleared removes the mark — clients drop the pin', () => {
    const original = mark({ id: 'm-1' });
    const event: MarkEvent = { action: 'cleared', mark: mark({ id: 'm-1', status: 'CLEARED' }) };
    expect(applyMarkEvent([original], event)).toEqual([]);
  });

  it('cleared is a no-op when the mark was never in the list', () => {
    const event: MarkEvent = { action: 'cleared', mark: mark({ id: 'unknown' }) };
    expect(applyMarkEvent([], event)).toEqual([]);
  });
});

describe('applyMarkEvents', () => {
  it('folds a run of deltas onto an initial-GET snapshot, in arrival order', () => {
    // The real scenario: MarksStore does GET /api/marks (one mark already active), then merges
    // deltas that arrived over the live topic afterward.
    const initial = [mark({ id: 'm-1', label: 'Existing' })];
    const events: MarkEvent[] = [
      { action: 'created', mark: mark({ id: 'm-2', label: 'New target' }) },
      { action: 'updated', mark: mark({ id: 'm-1', label: 'Existing (renamed)' }) },
      { action: 'cleared', mark: mark({ id: 'm-2', status: 'CLEARED' }) },
    ];

    const result = applyMarkEvents(initial, events);

    expect(result).toEqual([mark({ id: 'm-1', label: 'Existing (renamed)' })]);
  });

  it('is the identity function for an empty event list', () => {
    const initial = [mark({ id: 'm-1' })];
    expect(applyMarkEvents(initial, [])).toEqual(initial);
  });

  it('a later event for the same id always wins over an earlier one', () => {
    const events: MarkEvent[] = [
      { action: 'created', mark: mark({ id: 'm-1', label: 'v1' }) },
      { action: 'updated', mark: mark({ id: 'm-1', label: 'v2' }) },
      { action: 'updated', mark: mark({ id: 'm-1', label: 'v3' }) },
    ];
    expect(applyMarkEvents([], events)).toEqual([mark({ id: 'm-1', label: 'v3' })]);
  });
});

describe('markColor / markStyle', () => {
  it('gives every kind a distinct hsl colour', () => {
    const colors = new Set(MARK_KINDS.map((kind) => markColor(kind)));
    expect(colors.size).toBe(MARK_KINDS.length);
  });

  it('every colour is well-formed hsl()', () => {
    for (const kind of MARK_KINDS) {
      expect(markColor(kind)).toMatch(/^hsl\(\d+(\.\d+)? \d+% \d+%\)$/);
    }
  });

  it('none of the four hues fall inside this app\'s reserved status bands (danger/warn/success/info/live)', () => {
    const reservedBands: ReadonlyArray<readonly [number, number]> = [
      [340, 380], // danger (~0°, wrapped)
      [-20, 20], // danger (~0°)
      [16, 56], // warn (~36°)
      [126, 166], // success (~146°)
      [199, 239], // info/accent (~219°)
      [322, 362], // live (~342°)
    ];
    for (const kind of MARK_KINDS) {
      const match = /^hsl\((\d+(?:\.\d+)?)/.exec(markColor(kind));
      const hue = Number(match?.[1]);
      const collides = reservedBands.some(([lo, hi]) => hue >= lo && hue <= hi);
      expect(collides, `${kind}'s hue ${hue}° collides with a reserved status band`).toBe(false);
    }
  });

  it('selected renders a larger dot than unselected, same colour', () => {
    const kind: MarkKind = 'HAZARD';
    const plain = markStyle(kind, false);
    const selected = markStyle(kind, true);
    expect(selected.diameterPx).toBeGreaterThan(plain.diameterPx);
    expect(selected.color).toBe(plain.color);
  });
});

describe('markKindLabel / markKindIcon', () => {
  it('every kind has a non-empty label and a resolvable icon', () => {
    for (const kind of MARK_KINDS) {
      expect(markKindLabel(kind).length).toBeGreaterThan(0);
      expect(markKindIcon(kind).length).toBeGreaterThan(0);
    }
  });

  it('TARGET reads "Target"', () => {
    expect(markKindLabel('TARGET')).toBe('Target');
  });
});

// --- bearingDistance — mirrors vision-domain's GeoProjectionTest golden values -------------------

describe('bearingDistance', () => {
  it('is zero distance for identical points', () => {
    const p = { latitude: 12, longitude: 34 };
    expect(bearingDistance(p, p).distanceMeters).toBeCloseTo(0, 6);
  });

  it('bearing is 0° due north', () => {
    const bd = bearingDistance({ latitude: 0, longitude: 0 }, { latitude: 10, longitude: 0 });
    expect(bd.bearingDegrees).toBeCloseTo(0, 6);
    expect(bd.distanceMeters).toBeGreaterThan(0);
  });

  it('bearing is 90° due east at the equator', () => {
    const bd = bearingDistance({ latitude: 0, longitude: 0 }, { latitude: 0, longitude: 10 });
    expect(bd.bearingDegrees).toBeCloseTo(90, 6);
  });

  it('bearing is 180° due south', () => {
    const bd = bearingDistance({ latitude: 0, longitude: 0 }, { latitude: -10, longitude: 0 });
    expect(bd.bearingDegrees).toBeCloseTo(180, 6);
  });

  it('bearing is 270° due west at the equator', () => {
    const bd = bearingDistance({ latitude: 0, longitude: 0 }, { latitude: 0, longitude: -10 });
    expect(bd.bearingDegrees).toBeCloseTo(270, 6);
  });

  it('one degree of latitude matches the known great-circle baseline', () => {
    const bd = bearingDistance({ latitude: 0, longitude: 0 }, { latitude: 1, longitude: 0 });
    const expected = EARTH_RADIUS_METERS * (Math.PI / 180);
    expect(bd.distanceMeters).toBeCloseTo(expected, 0);
    expect(bd.distanceMeters).toBeCloseTo(111_194.9, -1);
  });

  it('distance is symmetric', () => {
    const a = { latitude: 50.45, longitude: 30.52 };
    const b = { latitude: 48.85, longitude: 2.35 };
    expect(bearingDistance(a, b).distanceMeters).toBeCloseTo(bearingDistance(b, a).distanceMeters, 6);
  });

  it('handles points across the antimeridian without NaN, taking the short way', () => {
    const bd = bearingDistance({ latitude: 0, longitude: 179.5 }, { latitude: 0, longitude: -179.5 });
    expect(Number.isNaN(bd.distanceMeters)).toBe(false);
    expect(Number.isNaN(bd.bearingDegrees)).toBe(false);
    expect(bd.distanceMeters).toBeLessThan(EARTH_RADIUS_METERS * (Math.PI / 36)); // < ~5 degrees of arc
  });
});

describe('compassPoint', () => {
  it.each<[number, string]>([
    [0, 'N'],
    [44, 'NE'],
    [90, 'E'],
    [135, 'SE'],
    [180, 'S'],
    [225, 'SW'],
    [270, 'W'],
    [315, 'NW'],
    [360, 'N'],
  ])('%s° -> %s', (degrees, expected) => {
    expect(compassPoint(degrees)).toBe(expected);
  });
});

describe('formatDistanceMeters', () => {
  it('renders sub-km distances in whole meters', () => {
    expect(formatDistanceMeters(420)).toBe('420 m');
  });

  it('renders >= 1km distances in km, one decimal', () => {
    expect(formatDistanceMeters(1234)).toBe('1.2 km');
  });
});

describe('bearingDistanceLabel', () => {
  it('combines rounded bearing, compass point, and formatted distance', () => {
    expect(bearingDistanceLabel({ bearingDegrees: 141.6, distanceMeters: 1234 })).toBe('142° SE · 1.2 km');
  });
});
