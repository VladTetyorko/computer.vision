import { describe, expect, it } from 'vitest';
import {
  DEFAULT_ALTITUDE_METERS,
  DEFAULT_ROUTE_MODE,
  DEFAULT_SPEED_MPS,
  FALLBACK_HOME_POINT,
  addWaypoint,
  buildTelemetryRequest,
  canSavePlan,
  formatManualWaypoints,
  moveWaypoint,
  parseManualWaypoints,
  removeWaypoint,
  seedTriangle,
  updateWaypointAltitude,
  type EditorWaypoint,
  type FlightPlanForm,
} from './flight-plan-logic';

function waypoint(partial: Partial<EditorWaypoint> = {}): EditorWaypoint {
  return { latitude: 10, longitude: 20, altitudeMeters: 60, ...partial };
}

describe('seedTriangle', () => {
  it('seeds 3 waypoints near the given home point', () => {
    const home = { latitude: 40, longitude: -70 };
    const waypoints = seedTriangle(home);
    expect(waypoints).toHaveLength(3);
    for (const wp of waypoints) {
      expect(wp.latitude).toBeGreaterThan(39.9);
      expect(wp.latitude).toBeLessThan(40.1);
      expect(wp.longitude).toBeGreaterThan(-70.1);
      expect(wp.longitude).toBeLessThan(-69.9);
      expect(wp.altitudeMeters).not.toBeNull();
    }
  });

  it('falls back to a generic home point when none is given', () => {
    expect(seedTriangle(null)).toEqual(seedTriangle(FALLBACK_HOME_POINT));
    expect(seedTriangle(undefined)).toEqual(seedTriangle(FALLBACK_HOME_POINT));
  });

  it('is deterministic — the same home point always seeds the same triangle', () => {
    const home = { latitude: 1, longitude: 2 };
    expect(seedTriangle(home)).toEqual(seedTriangle(home));
  });
});

describe('addWaypoint / moveWaypoint / updateWaypointAltitude / removeWaypoint', () => {
  it('addWaypoint appends with the default altitude', () => {
    const result = addWaypoint([], { latitude: 1, longitude: 2 });
    expect(result).toEqual([{ latitude: 1, longitude: 2, altitudeMeters: DEFAULT_ALTITUDE_METERS }]);
  });

  it('addWaypoint accepts an explicit altitude', () => {
    const result = addWaypoint([], { latitude: 1, longitude: 2 }, 120);
    expect(result[0].altitudeMeters).toBe(120);
  });

  it('addWaypoint does not mutate the input array', () => {
    const original: EditorWaypoint[] = [];
    addWaypoint(original, { latitude: 1, longitude: 2 });
    expect(original).toHaveLength(0);
  });

  it('moveWaypoint updates only the targeted index, leaving altitude untouched', () => {
    const waypoints = [waypoint({ latitude: 1, longitude: 1 }), waypoint({ latitude: 2, longitude: 2 })];
    const moved = moveWaypoint(waypoints, 1, { latitude: 9, longitude: 9 });
    expect(moved[0]).toEqual(waypoints[0]);
    expect(moved[1]).toEqual({ latitude: 9, longitude: 9, altitudeMeters: 60 });
  });

  it('updateWaypointAltitude sets and clears (null) an altitude by index', () => {
    const waypoints = [waypoint(), waypoint()];
    const updated = updateWaypointAltitude(waypoints, 0, 200);
    expect(updated[0].altitudeMeters).toBe(200);
    expect(updated[1].altitudeMeters).toBe(60);

    const cleared = updateWaypointAltitude(updated, 0, null);
    expect(cleared[0].altitudeMeters).toBeNull();
  });

  it('removeWaypoint drops exactly the targeted index', () => {
    const waypoints = [waypoint({ latitude: 1 }), waypoint({ latitude: 2 }), waypoint({ latitude: 3 })];
    const result = removeWaypoint(waypoints, 1);
    expect(result.map((w) => w.latitude)).toEqual([1, 3]);
  });
});

describe('parseManualWaypoints / formatManualWaypoints', () => {
  it('parses lat,lon,alt lines', () => {
    const parsed = parseManualWaypoints('37.77,-122.48,60\n37.78,-122.49,80');
    expect(parsed).toEqual([
      { latitude: 37.77, longitude: -122.48, altitudeMeters: 60 },
      { latitude: 37.78, longitude: -122.49, altitudeMeters: 80 },
    ]);
  });

  it('parses lat,lon lines with no altitude as null', () => {
    expect(parseManualWaypoints('1,2')).toEqual([{ latitude: 1, longitude: 2, altitudeMeters: null }]);
  });

  it('accepts whitespace-separated values too', () => {
    expect(parseManualWaypoints('1 2 3')).toEqual([{ latitude: 1, longitude: 2, altitudeMeters: 3 }]);
  });

  it('skips blank lines', () => {
    expect(parseManualWaypoints('1,2\n\n  \n3,4')).toEqual([
      { latitude: 1, longitude: 2, altitudeMeters: null },
      { latitude: 3, longitude: 4, altitudeMeters: null },
    ]);
  });

  it('skips lines with unparsable numbers, out-of-range coordinates, or too few values', () => {
    expect(parseManualWaypoints('not,numbers\n95,0\n0,200\nonly-one\n1,2')).toEqual([
      { latitude: 1, longitude: 2, altitudeMeters: null },
    ]);
  });

  it('skips a line whose altitude does not parse, keeping lat/lon valid lines around it', () => {
    expect(parseManualWaypoints('1,2,bad\n3,4,50')).toEqual([{ latitude: 3, longitude: 4, altitudeMeters: 50 }]);
  });

  it('formatManualWaypoints is the inverse of parseManualWaypoints for the common case', () => {
    const waypoints: EditorWaypoint[] = [
      { latitude: 1, longitude: 2, altitudeMeters: 60 },
      { latitude: 3, longitude: 4, altitudeMeters: null },
    ];
    expect(parseManualWaypoints(formatManualWaypoints(waypoints))).toEqual(waypoints);
  });
});

describe('canSavePlan', () => {
  it('requires at least 2 waypoints', () => {
    expect(canSavePlan([])).toBe(false);
    expect(canSavePlan([waypoint()])).toBe(false);
    expect(canSavePlan([waypoint(), waypoint()])).toBe(true);
  });
});

describe('buildTelemetryRequest', () => {
  function form(partial: Partial<FlightPlanForm> = {}): FlightPlanForm {
    return {
      waypoints: [waypoint({ latitude: 1, longitude: 2 }), waypoint({ latitude: 3, longitude: 4 })],
      speedMps: DEFAULT_SPEED_MPS,
      routeMode: DEFAULT_ROUTE_MODE,
      ...partial,
    };
  }

  it('returns undefined when there are fewer than 2 waypoints', () => {
    expect(buildTelemetryRequest(form({ waypoints: [] }))).toBeUndefined();
    expect(buildTelemetryRequest(form({ waypoints: [waypoint()] }))).toBeUndefined();
  });

  it('serializes waypoints/speed/routeMode into the wire shape', () => {
    expect(buildTelemetryRequest(form())).toEqual({
      routeMode: 'loop',
      speedMps: 12,
      route: [
        { latitude: 1, longitude: 2, altitudeMeters: 60 },
        { latitude: 3, longitude: 4, altitudeMeters: 60 },
      ],
    });
  });

  it('omits speedMps when null rather than sending it', () => {
    const request = buildTelemetryRequest(form({ speedMps: null }));
    expect(request).toBeDefined();
    expect('speedMps' in (request as object)).toBe(false);
  });

  it('omits a waypoint altitudeMeters when null rather than sending it', () => {
    const request = buildTelemetryRequest(
      form({ waypoints: [waypoint({ altitudeMeters: null }), waypoint({ latitude: 5 })] }),
    );
    expect(request?.route[0]).toEqual({ latitude: 10, longitude: 20 });
    expect('altitudeMeters' in request!.route[0]).toBe(false);
  });

  it('sends every routeMode value (loop/bounce/once — the actual TelemetryPlan contract)', () => {
    expect(buildTelemetryRequest(form({ routeMode: 'bounce' }))?.routeMode).toBe('bounce');
    expect(buildTelemetryRequest(form({ routeMode: 'once' }))?.routeMode).toBe('once');
  });
});
