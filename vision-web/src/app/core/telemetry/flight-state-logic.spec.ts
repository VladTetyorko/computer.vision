import { describe, expect, it } from 'vitest';
import type { FlightState, TelemetrySample } from '../api/models';
import {
  derivePreflight,
  deriveDiagnostics,
  ekfSeverity,
  flightBanner,
  gpsFixLabel,
  gpsSeverity,
  vibeSeverity,
} from './flight-state-logic';

function flightState(partial: Partial<FlightState> = {}): FlightState {
  return { ...partial };
}

function sample(partial: Partial<TelemetrySample> = {}): TelemetrySample {
  return { deviceId: 'dev-0', at: '2026-07-28T00:00:00.000Z', ...partial };
}

describe('gpsFixLabel', () => {
  it('renders a dash with no reading yet', () => {
    expect(gpsFixLabel(undefined)).toBe('—');
  });

  it('labels every recognized GPS_FIX_TYPE ordinal', () => {
    expect(gpsFixLabel(0)).toBe('No GPS');
    expect(gpsFixLabel(1)).toBe('No fix');
    expect(gpsFixLabel(2)).toBe('2D');
    expect(gpsFixLabel(3)).toBe('3D');
    expect(gpsFixLabel(4)).toBe('DGPS');
    expect(gpsFixLabel(5)).toBe('RTK float');
    expect(gpsFixLabel(6)).toBe('RTK fixed');
  });

  it('renders a dash for an out-of-range value rather than guessing', () => {
    expect(gpsFixLabel(9)).toBe('—');
  });
});

describe('gpsSeverity', () => {
  it('is critical with no reading at all', () => {
    expect(gpsSeverity(undefined)).toBe('critical');
  });

  it('is critical for no-GPS/no-fix', () => {
    expect(gpsSeverity(0)).toBe('critical');
    expect(gpsSeverity(1)).toBe('critical');
  });

  it('is warn for a 2D fix', () => {
    expect(gpsSeverity(2)).toBe('warn');
  });

  it('is ok at and above a 3D fix', () => {
    expect(gpsSeverity(3)).toBe('ok');
    expect(gpsSeverity(6)).toBe('ok');
  });
});

describe('flightBanner', () => {
  it('is null with no sample at all', () => {
    expect(flightBanner(undefined)).toBeNull();
  });

  it('is null when the sample carries no flightState yet', () => {
    expect(flightBanner(sample())).toBeNull();
  });

  it('is null for ordinary flight (no failsafe, no RTL/landing mode)', () => {
    expect(flightBanner(sample({ flightState: flightState({ mode: 'Loiter' }) }))).toBeNull();
  });

  it('is the failsafe RTH banner when failsafe is set and the mode is an RTL-family one', () => {
    for (const mode of ['RTL', 'SmartRTL', 'QRTL', 'AutoRTL']) {
      const banner = flightBanner(sample({ flightState: flightState({ mode, failsafe: true }) }));
      expect(banner).toEqual({ kind: 'failsafe', text: 'FAILSAFE — RETURNING TO HOME' });
    }
  });

  it('is a plainer failsafe banner when failsafe is set but the mode is not an RTL variant', () => {
    expect(flightBanner(sample({ flightState: flightState({ mode: 'Stabilize', failsafe: true }) }))).toEqual({
      kind: 'failsafe',
      text: 'FAILSAFE ACTIVE',
    });
  });

  it('is a failsafe banner even with no mode reported at all', () => {
    expect(flightBanner(sample({ flightState: flightState({ failsafe: true }) }))).toEqual({
      kind: 'failsafe',
      text: 'FAILSAFE ACTIVE',
    });
  });

  it('failsafe wins over an RTL-family mode regardless of order — never downgraded to rth', () => {
    const banner = flightBanner(sample({ flightState: flightState({ mode: 'RTL', failsafe: true }) }));
    expect(banner?.kind).toBe('failsafe');
  });

  it('is the rth banner for a pilot-commanded RTL-family mode without failsafe', () => {
    for (const mode of ['RTL', 'SmartRTL', 'QRTL', 'AutoRTL']) {
      expect(flightBanner(sample({ flightState: flightState({ mode, failsafe: false }) }))).toEqual({
        kind: 'rth',
        text: 'Return to home active',
      });
    }
  });

  it('treats an absent failsafe field the same as false for the rth banner', () => {
    expect(flightBanner(sample({ flightState: flightState({ mode: 'RTL' }) }))).toEqual({
      kind: 'rth',
      text: 'Return to home active',
    });
  });

  it('is the landing banner for a landing-family mode', () => {
    for (const mode of ['Land', 'QLand', 'AutoLand']) {
      expect(flightBanner(sample({ flightState: flightState({ mode }) }))).toEqual({
        kind: 'landing',
        text: 'Landing',
      });
    }
  });
});

describe('derivePreflight', () => {
  const NOW = Date.parse('2026-07-28T00:00:10.000Z');

  it('always returns exactly 5 rows, in a fixed order', () => {
    const rows = derivePreflight(undefined, false, false, NOW);
    expect(rows.map((row) => row.label)).toEqual([
      'Video feed',
      'Telemetry link',
      'GPS fix',
      'Battery',
      'Armable',
    ]);
  });

  describe('Video feed', () => {
    it('fails when the drone has no camera device at all', () => {
      const [video] = derivePreflight(undefined, false, false, NOW);
      expect(video).toEqual({ label: 'Video feed', state: 'fail', detail: 'No camera device on this drone.' });
    });

    it('is unknown (not yet verified) when a camera exists but nothing is streaming', () => {
      const [video] = derivePreflight(undefined, true, false, NOW);
      expect(video.state).toBe('unknown');
    });

    it('is ok while actually streaming', () => {
      const [video] = derivePreflight(undefined, true, true, NOW);
      expect(video.state).toBe('ok');
    });
  });

  describe('Telemetry link', () => {
    it('is unknown with no sample at all', () => {
      const [, telemetry] = derivePreflight(undefined, false, false, NOW);
      expect(telemetry.state).toBe('unknown');
    });

    it('is ok for a fresh sample', () => {
      const fresh = sample({ at: new Date(NOW - 2_000).toISOString() });
      const [, telemetry] = derivePreflight(fresh, false, false, NOW);
      expect(telemetry.state).toBe('ok');
    });

    it('fails once the sample is stale past the shared threshold', () => {
      const stale = sample({ at: new Date(NOW - 11_000).toISOString() });
      const [, telemetry] = derivePreflight(stale, false, false, NOW);
      expect(telemetry.state).toBe('fail');
      expect(telemetry.detail).toContain('Stale');
    });
  });

  describe('GPS fix', () => {
    it('is unknown with no GPS reading yet', () => {
      const [, , gps] = derivePreflight(sample(), false, false, NOW);
      expect(gps.state).toBe('unknown');
    });

    it('fails below a 3D fix', () => {
      const [, , gps] = derivePreflight(sample({ flightState: flightState({ gpsFixType: 2 }) }), false, false, NOW);
      expect(gps).toEqual({ label: 'GPS fix', state: 'fail', detail: '2D' });
    });

    it('is ok at and above a 3D fix', () => {
      const [, , gps] = derivePreflight(sample({ flightState: flightState({ gpsFixType: 3 }) }), false, false, NOW);
      expect(gps).toEqual({ label: 'GPS fix', state: 'ok', detail: '3D' });
    });
  });

  describe('Battery', () => {
    it('is unknown with no battery reading yet', () => {
      const [, , , battery] = derivePreflight(sample(), false, false, NOW);
      expect(battery.state).toBe('unknown');
    });

    it('fails below the 45% minimum', () => {
      const [, , , battery] = derivePreflight(sample({ batteryPercent: 44 }), false, false, NOW);
      expect(battery.state).toBe('fail');
    });

    it('is ok at and above the 45% minimum', () => {
      const [, , , battery] = derivePreflight(sample({ batteryPercent: 45 }), false, false, NOW);
      expect(battery.state).toBe('ok');
    });
  });

  describe('Armable', () => {
    it('is unknown with no flightState at all', () => {
      const [, , , , armable] = derivePreflight(sample(), false, false, NOW);
      expect(armable.state).toBe('unknown');
    });

    it('fails with the blockers joined verbatim when armingBlockers is non-empty', () => {
      const [, , , , armable] = derivePreflight(
        sample({ flightState: flightState({ armingBlockers: ['PreArm: Compass not calibrated', 'PreArm: GPS Glitch'] }) }),
        false,
        false,
        NOW,
      );
      expect(armable).toEqual({
        label: 'Armable',
        state: 'fail',
        detail: 'PreArm: Compass not calibrated; PreArm: GPS Glitch',
      });
    });

    it('is ok when armed, even with a flightState that reports no blockers', () => {
      const [, , , , armable] = derivePreflight(
        sample({ flightState: flightState({ armed: true, armingBlockers: [] }) }),
        false,
        false,
        NOW,
      );
      expect(armable.state).toBe('ok');
    });

    it('is ok when disarmed but blockers are empty (flightState present)', () => {
      const [, , , , armable] = derivePreflight(sample({ flightState: flightState({ armed: false }) }), false, false, NOW);
      expect(armable.state).toBe('ok');
    });
  });
});

describe('ekfSeverity', () => {
  it('is ok below 0.5', () => {
    expect(ekfSeverity(0)).toBe('ok');
    expect(ekfSeverity(0.49)).toBe('ok');
  });

  it('is warn from 0.5 up to and including 1.0', () => {
    expect(ekfSeverity(0.5)).toBe('warn');
    expect(ekfSeverity(1.0)).toBe('warn');
  });

  it('is bad above 1.0', () => {
    expect(ekfSeverity(1.01)).toBe('bad');
  });
});

describe('vibeSeverity', () => {
  it('is ok below 30', () => {
    expect(vibeSeverity(0)).toBe('ok');
    expect(vibeSeverity(29.9)).toBe('ok');
  });

  it('is warn from 30 up to and including 60', () => {
    expect(vibeSeverity(30)).toBe('warn');
    expect(vibeSeverity(60)).toBe('warn');
  });

  it('is bad above 60', () => {
    expect(vibeSeverity(60.1)).toBe('bad');
  });
});

describe('deriveDiagnostics', () => {
  it('is empty with no extra at all', () => {
    expect(deriveDiagnostics(undefined)).toEqual([]);
  });

  it('is empty with an empty extra map', () => {
    expect(deriveDiagnostics({})).toEqual([]);
  });

  describe('wind', () => {
    it('is omitted when windSpeedMps is absent', () => {
      expect(deriveDiagnostics({ windDirectionDegrees: 90 })).toEqual([]);
    });

    it('shows speed alone when direction is absent', () => {
      const [wind] = deriveDiagnostics({ windSpeedMps: 4.2 });
      expect(wind).toEqual({ key: 'wind', label: 'Wind', value: '4.2 m/s' });
    });

    it('shows speed and direction together when both are present', () => {
      const [wind] = deriveDiagnostics({ windSpeedMps: 4.2, windDirectionDegrees: 127.6 });
      expect(wind).toEqual({ key: 'wind', label: 'Wind', value: '4.2 m/s @ 128°' });
    });
  });

  describe('vibration', () => {
    it('is omitted when no vibe axis is present', () => {
      expect(deriveDiagnostics({ windSpeedMps: 1 }).find((row) => row.key === 'vibration')).toBeUndefined();
    });

    it('shows only the axes present and is governed by the worst one', () => {
      const rows = deriveDiagnostics({ vibeXMs2: 12.3, vibeZMs2: 65.2 });
      const vibration = rows.find((row) => row.key === 'vibration');
      expect(vibration).toEqual({ key: 'vibration', label: 'Vibration', value: 'X 12.3 · Z 65.2 m/s²', severity: 'bad' });
    });

    it('is ok when every present axis is under the ok threshold', () => {
      const rows = deriveDiagnostics({ vibeXMs2: 5, vibeYMs2: 8, vibeZMs2: 10 });
      expect(rows.find((row) => row.key === 'vibration')?.severity).toBe('ok');
    });
  });

  describe('EKF', () => {
    it('is omitted when no EKF variance is present', () => {
      expect(deriveDiagnostics({ windSpeedMps: 1 }).find((row) => row.key === 'ekf')).toBeUndefined();
    });

    it('names whichever variance reads worst', () => {
      const rows = deriveDiagnostics({ ekfVelocityVariance: 0.2, ekfCompassVariance: 1.4 });
      const ekf = rows.find((row) => row.key === 'ekf');
      expect(ekf).toEqual({ key: 'ekf', label: 'EKF', value: 'compass 1.40', severity: 'bad' });
    });

    it('is ok when every present variance is under the ok threshold', () => {
      const rows = deriveDiagnostics({ ekfPosHorizVariance: 0.1, ekfPosVertVariance: 0.2 });
      expect(rows.find((row) => row.key === 'ekf')?.severity).toBe('ok');
    });
  });

  describe('rangefinder', () => {
    it('is omitted when absent', () => {
      expect(deriveDiagnostics({ windSpeedMps: 1 }).find((row) => row.key === 'rangefinder')).toBeUndefined();
    });

    it('has no severity — F-e defines no threshold for it', () => {
      const rows = deriveDiagnostics({ rangefinderDistanceM: 12.44 });
      expect(rows.find((row) => row.key === 'rangefinder')).toEqual({
        key: 'rangefinder',
        label: 'Rangefinder',
        value: '12.4 m',
      });
    });
  });

  describe('mission', () => {
    it('is omitted when absent', () => {
      expect(deriveDiagnostics({ windSpeedMps: 1 }).find((row) => row.key === 'mission')).toBeUndefined();
    });

    it('has no severity — F-e defines no threshold for it', () => {
      const rows = deriveDiagnostics({ missionSeq: 3 });
      expect(rows.find((row) => row.key === 'mission')).toEqual({ key: 'mission', label: 'Mission', value: 'Waypoint 3' });
    });
  });

  it('returns every row in fixed order when every key is present', () => {
    const rows = deriveDiagnostics({
      windSpeedMps: 1,
      windDirectionDegrees: 10,
      vibeXMs2: 1,
      ekfVelocityVariance: 0.1,
      rangefinderDistanceM: 2,
      missionSeq: 1,
    });
    expect(rows.map((row) => row.key)).toEqual(['wind', 'vibration', 'ekf', 'rangefinder', 'mission']);
  });
});
