import { describe, expect, it } from 'vitest';
import type { LiveEvent } from '../api/models';
import {
  activeGeofenceBreaches,
  assetsOutsideZoneCount,
  canSaveZone,
  geofenceBreachReasonText,
  geofenceBreachToastMessage,
  groupBreachesByAsset,
  parseGeofenceBreach,
  polygonContains,
  zoneKindLabel,
  zoneLayerStyle,
  zoneVertexCountReason,
  type ZoneVertex,
} from './geofence-logic';

const SQUARE: readonly ZoneVertex[] = [
  { latitude: 0, longitude: 0 },
  { latitude: 0, longitude: 10 },
  { latitude: 10, longitude: 10 },
  { latitude: 10, longitude: 0 },
];

function liveEvent(partial: Partial<LiveEvent> = {}): LiveEvent {
  return {
    id: 'e-0',
    at: '2026-07-28T00:00:00Z',
    type: 'GEOFENCE_BREACH',
    message: 'breach',
    attributes: {},
    ...partial,
  };
}

function breachEvent(
  id: string,
  assetId: string,
  zoneId: string,
  direction: 'enter' | 'exit',
  overrides: Partial<Record<string, string>> = {},
): LiveEvent {
  return liveEvent({
    id,
    attributes: { assetId, zoneId, zoneName: 'North perimeter', kind: 'KEEP_OUT', direction, ...overrides },
  });
}

describe('canSaveZone / zoneVertexCountReason', () => {
  it('needs at least 3 vertices', () => {
    expect(canSaveZone([])).toBe(false);
    expect(canSaveZone([SQUARE[0], SQUARE[1]])).toBe(false);
    expect(canSaveZone([SQUARE[0], SQUARE[1], SQUARE[2]])).toBe(true);
  });

  it('names exactly how many more points are needed', () => {
    expect(zoneVertexCountReason([])).toBe('Add 3 more points (need at least 3).');
    expect(zoneVertexCountReason([SQUARE[0]])).toBe('Add 2 more points (need at least 3).');
    expect(zoneVertexCountReason([SQUARE[0], SQUARE[1]])).toBe('Add 1 more point (need at least 3).');
  });

  it('is null once the minimum is met, regardless of how many more vertices exist', () => {
    expect(zoneVertexCountReason([SQUARE[0], SQUARE[1], SQUARE[2]])).toBeNull();
    expect(zoneVertexCountReason(SQUARE)).toBeNull();
  });
});

describe('polygonContains', () => {
  it('is true for a point well inside the square', () => {
    expect(polygonContains(SQUARE, { latitude: 5, longitude: 5 })).toBe(true);
  });

  it('is false for a point well outside the square', () => {
    expect(polygonContains(SQUARE, { latitude: 20, longitude: 20 })).toBe(false);
    expect(polygonContains(SQUARE, { latitude: -5, longitude: 5 })).toBe(false);
  });
});

describe('assetsOutsideZoneCount', () => {
  it('counts only positions outside the polygon', () => {
    const positions = [
      { latitude: 5, longitude: 5 }, // inside
      { latitude: 20, longitude: 20 }, // outside
      { latitude: -1, longitude: -1 }, // outside
    ];
    expect(assetsOutsideZoneCount(SQUARE, positions)).toBe(2);
  });

  it('is zero when every asset is inside', () => {
    expect(assetsOutsideZoneCount(SQUARE, [{ latitude: 1, longitude: 1 }])).toBe(0);
  });

  it('is zero with no positions at all', () => {
    expect(assetsOutsideZoneCount(SQUARE, [])).toBe(0);
  });

  it('is zero for a polygon below the minimum vertex count (nothing meaningful to test against)', () => {
    expect(assetsOutsideZoneCount([SQUARE[0], SQUARE[1]], [{ latitude: 100, longitude: 100 }])).toBe(0);
  });
});

describe('zoneLayerStyle', () => {
  it('KEEP_OUT is red with a light fill and a dashed border', () => {
    const style = zoneLayerStyle('KEEP_OUT');
    expect(style.color).toBe('#ff5d5d');
    expect(style.fill).toBe(true);
    expect(style.fillColor).toBe('#ff5d5d');
    expect(style.fillOpacity).toBeCloseTo(0.12);
    expect(style.dashArray).toBe('6 6');
  });

  it('KEEP_IN is accent-colored with a dashed border and no fill', () => {
    const style = zoneLayerStyle('KEEP_IN');
    expect(style.color).toBe('#4f8cff');
    expect(style.fill).toBe(false);
    expect(style.fillColor).toBeUndefined();
    expect(style.dashArray).toBe('6 6');
  });

  it('a disabled zone dims rather than changing color', () => {
    const enabled = zoneLayerStyle('KEEP_OUT', true);
    const disabled = zoneLayerStyle('KEEP_OUT', false);
    expect(disabled.color).toBe(enabled.color);
    expect(disabled.opacity).toBeLessThan(enabled.opacity);
    expect(disabled.fillOpacity!).toBeLessThan(enabled.fillOpacity!);
  });
});

describe('zoneKindLabel', () => {
  it('hyphenates both kinds', () => {
    expect(zoneKindLabel('KEEP_OUT')).toBe('KEEP-OUT');
    expect(zoneKindLabel('KEEP_IN')).toBe('KEEP-IN');
  });
});

describe('parseGeofenceBreach', () => {
  it('decodes a well-formed breach event', () => {
    const event = breachEvent('e-1', 'asset-1', 'zone-1', 'enter');
    expect(parseGeofenceBreach(event)).toEqual({
      assetId: 'asset-1',
      zoneId: 'zone-1',
      zoneName: 'North perimeter',
      kind: 'KEEP_OUT',
      direction: 'enter',
    });
  });

  it('is undefined for a non-breach event type', () => {
    expect(parseGeofenceBreach(liveEvent({ type: 'STREAM_STARTED' }))).toBeUndefined();
  });

  it('is undefined when a required attribute is missing', () => {
    expect(parseGeofenceBreach(breachEvent('e-1', '', 'zone-1', 'enter'))).toBeUndefined();
    expect(parseGeofenceBreach(liveEvent({ type: 'GEOFENCE_BREACH', attributes: { assetId: 'a', zoneId: 'z' } }))).toBeUndefined();
  });

  it('is undefined for an unrecognized kind/direction (never fabricated)', () => {
    expect(parseGeofenceBreach(breachEvent('e-1', 'a', 'z', 'enter', { kind: 'SIDEWAYS' }))).toBeUndefined();
    expect(
      parseGeofenceBreach(liveEvent({ type: 'GEOFENCE_BREACH', attributes: { assetId: 'a', zoneId: 'z', zoneName: 'Z', kind: 'KEEP_OUT', direction: 'sideways' } })),
    ).toBeUndefined();
  });
});

describe('activeGeofenceBreaches', () => {
  it('an enter with no matching exit stays active', () => {
    const events = [breachEvent('e-1', 'asset-1', 'zone-1', 'enter')];
    expect(activeGeofenceBreaches(events)).toEqual([
      { assetId: 'asset-1', zoneId: 'zone-1', zoneName: 'North perimeter', kind: 'KEEP_OUT', direction: 'enter' },
    ]);
  });

  it('a later exit (newest-first: appears earlier in the array) clears an earlier enter', () => {
    // newest-first: the exit is more recent than the enter, so it should win.
    const events = [breachEvent('e-2', 'asset-1', 'zone-1', 'exit'), breachEvent('e-1', 'asset-1', 'zone-1', 'enter')];
    expect(activeGeofenceBreaches(events)).toEqual([]);
  });

  it('only the most recent event per (assetId, zoneId) counts, regardless of how many toggles preceded it', () => {
    const events = [
      breachEvent('e-4', 'asset-1', 'zone-1', 'enter'), // newest — wins
      breachEvent('e-3', 'asset-1', 'zone-1', 'exit'),
      breachEvent('e-2', 'asset-1', 'zone-1', 'enter'),
      breachEvent('e-1', 'asset-1', 'zone-1', 'exit'),
    ];
    expect(activeGeofenceBreaches(events)).toHaveLength(1);
    expect(activeGeofenceBreaches(events)[0].direction).toBe('enter');
  });

  it('tracks distinct (assetId, zoneId) pairs independently', () => {
    const events = [
      breachEvent('e-1', 'asset-1', 'zone-1', 'enter'),
      breachEvent('e-2', 'asset-1', 'zone-2', 'enter'),
      breachEvent('e-3', 'asset-2', 'zone-1', 'enter'),
    ];
    expect(activeGeofenceBreaches(events)).toHaveLength(3);
  });

  it('ignores non-breach events mixed into the feed', () => {
    const events = [liveEvent({ id: 'e-0', type: 'DEVICE_ONLINE' }), breachEvent('e-1', 'asset-1', 'zone-1', 'enter')];
    expect(activeGeofenceBreaches(events)).toHaveLength(1);
  });

  it('is empty for an empty feed', () => {
    expect(activeGeofenceBreaches([])).toEqual([]);
  });
});

describe('groupBreachesByAsset', () => {
  it('groups multiple breaches for the same asset together', () => {
    const breaches = activeGeofenceBreaches([
      breachEvent('e-1', 'asset-1', 'zone-1', 'enter'),
      breachEvent('e-2', 'asset-1', 'zone-2', 'enter', { zoneName: 'Charging pad', kind: 'KEEP_IN' }),
      breachEvent('e-3', 'asset-2', 'zone-1', 'enter'),
    ]);
    const grouped = groupBreachesByAsset(breaches);
    expect(grouped.get('asset-1')).toHaveLength(2);
    expect(grouped.get('asset-2')).toHaveLength(1);
    expect(grouped.get('asset-3')).toBeUndefined();
  });
});

describe('geofenceBreachReasonText', () => {
  it('joins one clause per active zone', () => {
    const breaches = activeGeofenceBreaches([
      breachEvent('e-1', 'asset-1', 'zone-1', 'enter'),
      breachEvent('e-2', 'asset-1', 'zone-2', 'enter', { zoneName: 'Charging pad', kind: 'KEEP_IN' }),
    ]);
    expect(geofenceBreachReasonText(breaches)).toBe('KEEP-OUT breach — North perimeter; KEEP-IN breach — Charging pad.');
  });
});

describe('geofenceBreachToastMessage', () => {
  it('renders a message for an enter event', () => {
    expect(geofenceBreachToastMessage(breachEvent('e-1', 'a', 'z', 'enter'))).toBe('KEEP-OUT breach — North perimeter');
  });

  it('is undefined for an exit event — clearing a breach is not toast-worthy', () => {
    expect(geofenceBreachToastMessage(breachEvent('e-1', 'a', 'z', 'exit'))).toBeUndefined();
  });

  it('is undefined for a non-breach event', () => {
    expect(geofenceBreachToastMessage(liveEvent({ type: 'STREAM_STARTED' }))).toBeUndefined();
  });
});
