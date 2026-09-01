import { describe, expect, it } from 'vitest';
import type { AssetSummary, GeoPosition, TelemetrySample } from '../api/models';
import {
  bucketAssets,
  bucketForAsset,
  buildMarker,
  buildMarkers,
  fingerprintMarkers,
  nextAutoFitEnabled,
  snapshotFromSamples,
  windowTrail,
  type AssetTelemetrySnapshot,
  type FleetMarker,
} from './map-logic';

const POSITION: GeoPosition = { latitude: 10, longitude: 20 };

function asset(partial: Partial<AssetSummary> = {}): AssetSummary {
  return {
    assetId: 'a-1',
    displayName: 'Drone One',
    category: 'drone',
    categoryName: 'Drone',
    owner: 'owner-0',
    status: 'OFFLINE',
    lifecycle: 'ACTIVE',
    attributes: {},
    ...partial,
  };
}

function sample(partial: Partial<TelemetrySample> = {}): TelemetrySample {
  return { deviceId: 'dev-0', at: '2026-07-22T00:00:00Z', ...partial };
}

describe('bucketForAsset', () => {
  it('buckets a streaming asset with a position as streaming', () => {
    expect(bucketForAsset(asset({ status: 'STREAMING', lastKnownPosition: POSITION }))).toBe('streaming');
  });

  it('buckets an offline asset with a position as offline', () => {
    expect(bucketForAsset(asset({ status: 'OFFLINE', lastKnownPosition: POSITION }))).toBe('offline');
  });

  it('buckets a streaming asset with no position as noPosition (e.g. just after stream start)', () => {
    expect(bucketForAsset(asset({ status: 'STREAMING' }))).toBe('noPosition');
  });

  it('buckets an offline asset with no position as noPosition', () => {
    expect(bucketForAsset(asset({ status: 'OFFLINE' }))).toBe('noPosition');
  });

  // docs/plans/active/OPERATOR-UX-4-PLAN.md finding N1 — Null Island is a no-fix report, not a place.
  it('buckets a streaming asset whose lastKnownPosition is exactly (0, 0) as noPosition', () => {
    expect(bucketForAsset(asset({ status: 'STREAMING', lastKnownPosition: { latitude: 0, longitude: 0 } }))).toBe(
      'noPosition',
    );
  });

  it('buckets an offline asset whose lastKnownPosition is exactly (0, 0) as noPosition', () => {
    expect(bucketForAsset(asset({ status: 'OFFLINE', lastKnownPosition: { latitude: 0, longitude: 0 } }))).toBe(
      'noPosition',
    );
  });
});

describe('bucketAssets', () => {
  it('partitions a mixed list, preserving order within each bucket', () => {
    const streamingA = asset({ assetId: 's-1', status: 'STREAMING', lastKnownPosition: POSITION });
    const streamingB = asset({ assetId: 's-2', status: 'STREAMING', lastKnownPosition: POSITION });
    const offlineA = asset({ assetId: 'o-1', status: 'OFFLINE', lastKnownPosition: POSITION });
    const noPos = asset({ assetId: 'n-1', status: 'OFFLINE' });

    const buckets = bucketAssets([streamingA, offlineA, streamingB, noPos]);

    expect(buckets.streaming).toEqual([streamingA, streamingB]);
    expect(buckets.offline).toEqual([offlineA]);
    expect(buckets.noPosition).toEqual([noPos]);
  });

  it('returns empty buckets for an empty asset list', () => {
    expect(bucketAssets([])).toEqual({ streaming: [], offline: [], noPosition: [] });
  });
});

describe('windowTrail', () => {
  function trailOf(length: number): GeoPosition[] {
    return Array.from({ length }, (_, i) => ({ latitude: i, longitude: i }));
  }

  it('returns the whole trail when at or under the max', () => {
    const trail = trailOf(60);
    expect(windowTrail(trail, 60)).toEqual(trail);
  });

  it('keeps only the most recent max points when over the limit', () => {
    const trail = trailOf(65);
    const windowed = windowTrail(trail, 60);
    expect(windowed).toHaveLength(60);
    expect(windowed[0]).toEqual({ latitude: 5, longitude: 5 });
    expect(windowed[59]).toEqual({ latitude: 64, longitude: 64 });
  });

  it('defaults to the TRAIL_WINDOW constant (60)', () => {
    expect(windowTrail(trailOf(61))).toHaveLength(60);
  });

  it('returns an empty array unchanged', () => {
    expect(windowTrail([])).toEqual([]);
  });
});

describe('snapshotFromSamples', () => {
  it('keeps the latest sample even when it carries no position', () => {
    const samples = [sample({ at: 't1', latitude: 1, longitude: 2 }), sample({ at: 't2', batteryPercent: 50 })];
    const snapshot = snapshotFromSamples(samples);
    expect(snapshot.latest).toEqual(samples[1]);
    expect(snapshot.trail).toEqual([{ latitude: 1, longitude: 2, altitudeMeters: undefined }]);
  });

  it('is empty for no samples', () => {
    expect(snapshotFromSamples([])).toEqual({ latest: undefined, trail: [] });
  });
});

describe('buildMarker', () => {
  it('returns undefined for the noPosition bucket', () => {
    expect(buildMarker(asset({ status: 'OFFLINE' }), undefined, 0)).toBeUndefined();
  });

  it('builds a dimmed marker for an offline asset at its lastKnownPosition, with no live enrichment', () => {
    const marker = buildMarker(asset({ status: 'OFFLINE', lastKnownPosition: POSITION }), undefined, 0);
    expect(marker).toEqual({
      assetId: 'a-1',
      displayName: 'Drone One',
      category: 'drone',
      categoryName: 'Drone',
      status: 'OFFLINE',
      live: false,
      position: POSITION,
      trail: [],
    });
  });

  it('builds a live marker from telemetry when a fix is present', () => {
    const telemetry: AssetTelemetrySnapshot = {
      latest: sample({ at: '2026-07-22T00:00:05Z', latitude: 5, longitude: 6, headingDegrees: 90, batteryPercent: 80 }),
      trail: [{ latitude: 5, longitude: 6 }],
    };
    const nowMs = Date.parse('2026-07-22T00:00:08Z');
    const marker = buildMarker(
      asset({ status: 'STREAMING', lastKnownPosition: POSITION }),
      telemetry,
      nowMs,
    );
    expect(marker?.live).toBe(true);
    expect(marker?.position).toEqual({ latitude: 5, longitude: 6, altitudeMeters: undefined });
    expect(marker?.headingDegrees).toBe(90);
    expect(marker?.batteryPercent).toBe(80);
    expect(marker?.trail).toEqual(telemetry.trail);
    expect(marker?.sampleAgeSeconds).toBeCloseTo(3, 5);
  });

  it('falls back to lastKnownPosition for a streaming asset with no telemetry yet', () => {
    const marker = buildMarker(asset({ status: 'STREAMING', lastKnownPosition: POSITION }), undefined, 0);
    expect(marker?.position).toEqual(POSITION);
    expect(marker?.trail).toEqual([]);
    expect(marker?.sampleAgeSeconds).toBeUndefined();
  });

  it('falls back to lastKnownPosition when the latest sample has no fix, but still surfaces battery/heading', () => {
    const telemetry: AssetTelemetrySnapshot = {
      latest: sample({ at: '2026-07-22T00:00:00Z', batteryPercent: 40 }),
      trail: [],
    };
    const marker = buildMarker(asset({ status: 'STREAMING', lastKnownPosition: POSITION }), telemetry, 0);
    expect(marker?.position).toEqual(POSITION);
    expect(marker?.batteryPercent).toBe(40);
  });

  // docs/plans/active/OPERATOR-UX-4-PLAN.md finding N1 — a (0, 0) sample is a no-fix report, treated
  // exactly like a sample carrying no lat/lon at all.
  it('falls back to lastKnownPosition when the latest sample is exactly (0, 0), never plots Null Island', () => {
    const telemetry: AssetTelemetrySnapshot = {
      latest: sample({ at: '2026-07-22T00:00:00Z', latitude: 0, longitude: 0, batteryPercent: 40 }),
      trail: [],
    };
    const marker = buildMarker(asset({ status: 'STREAMING', lastKnownPosition: POSITION }), telemetry, 0);
    expect(marker?.position).toEqual(POSITION);
    expect(marker?.batteryPercent).toBe(40);
  });

  it('returns undefined when both lastKnownPosition and the latest sample are (0, 0) — genuinely no fix', () => {
    const marker = buildMarker(asset({ status: 'STREAMING', lastKnownPosition: { latitude: 0, longitude: 0 } }), undefined, 0);
    expect(marker).toBeUndefined();
  });

  describe('flightMode/armed/failsafe/gpsFixType (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-d)', () => {
    it('sources all four from the latest sample\'s own flightState', () => {
      const telemetry: AssetTelemetrySnapshot = {
        latest: sample({
          latitude: 5,
          longitude: 6,
          flightState: { mode: 'RTL', armed: true, failsafe: true, gpsFixType: 3 },
        }),
        trail: [],
      };
      const marker = buildMarker(asset({ status: 'STREAMING', lastKnownPosition: POSITION }), telemetry, 0);
      expect(marker?.flightMode).toBe('RTL');
      expect(marker?.armed).toBe(true);
      expect(marker?.failsafe).toBe(true);
      expect(marker?.gpsFixType).toBe(3);
    });

    it('is undefined for a streaming asset with no telemetry snapshot yet — never fabricated', () => {
      const marker = buildMarker(asset({ status: 'STREAMING', lastKnownPosition: POSITION }), undefined, 0);
      expect(marker?.flightMode).toBeUndefined();
      expect(marker?.armed).toBeUndefined();
      expect(marker?.failsafe).toBeUndefined();
      expect(marker?.gpsFixType).toBeUndefined();
    });

    it('is undefined when the latest sample carries no flightState at all', () => {
      const telemetry: AssetTelemetrySnapshot = { latest: sample({ batteryPercent: 40 }), trail: [] };
      const marker = buildMarker(asset({ status: 'STREAMING', lastKnownPosition: POSITION }), telemetry, 0);
      expect(marker?.flightMode).toBeUndefined();
      expect(marker?.armed).toBeUndefined();
    });

    it('is absent entirely for the offline bucket (no live telemetry poller)', () => {
      const marker = buildMarker(asset({ status: 'OFFLINE', lastKnownPosition: POSITION }), undefined, 0);
      expect(marker).not.toHaveProperty('flightMode');
      expect(marker).not.toHaveProperty('gpsFixType');
    });
  });

  describe('firmware (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1 — feeds asset-panel.ts\'s "Bring home" gate)', () => {
    it('sources it from the latest sample\'s own flightState, same place as flightMode/armed/etc.', () => {
      const telemetry: AssetTelemetrySnapshot = {
        latest: sample({ latitude: 5, longitude: 6, flightState: { firmware: 'ardupilot' } }),
        trail: [],
      };
      const marker = buildMarker(asset({ status: 'STREAMING', lastKnownPosition: POSITION }), telemetry, 0);
      expect(marker?.firmware).toBe('ardupilot');
    });

    it('is undefined when the latest sample carries no flightState at all', () => {
      const telemetry: AssetTelemetrySnapshot = { latest: sample({ batteryPercent: 40 }), trail: [] };
      const marker = buildMarker(asset({ status: 'STREAMING', lastKnownPosition: POSITION }), telemetry, 0);
      expect(marker?.firmware).toBeUndefined();
    });

    it('is absent entirely for the offline bucket (no live telemetry poller)', () => {
      const marker = buildMarker(asset({ status: 'OFFLINE', lastKnownPosition: POSITION }), undefined, 0);
      expect(marker).not.toHaveProperty('firmware');
    });
  });

  describe('extra (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-e — feeds asset-panel.ts\'s Status-tab diagnostics)', () => {
    it('passes the latest sample\'s extra map through verbatim', () => {
      const telemetry: AssetTelemetrySnapshot = { latest: sample({ extra: { windSpeedMps: 4.2 } }), trail: [] };
      const marker = buildMarker(asset({ status: 'STREAMING', lastKnownPosition: POSITION }), telemetry, 0);
      expect(marker?.extra).toEqual({ windSpeedMps: 4.2 });
    });

    it('is undefined when the latest sample carries no extra', () => {
      const telemetry: AssetTelemetrySnapshot = { latest: sample(), trail: [] };
      const marker = buildMarker(asset({ status: 'STREAMING', lastKnownPosition: POSITION }), telemetry, 0);
      expect(marker?.extra).toBeUndefined();
    });

    it('is absent entirely for the offline bucket', () => {
      const marker = buildMarker(asset({ status: 'OFFLINE', lastKnownPosition: POSITION }), undefined, 0);
      expect(marker).not.toHaveProperty('extra');
    });
  });
});

describe('buildMarkers', () => {
  it('excludes noPosition assets and preserves order', () => {
    const streaming = asset({ assetId: 's-1', status: 'STREAMING', lastKnownPosition: POSITION });
    const offline = asset({ assetId: 'o-1', status: 'OFFLINE', lastKnownPosition: POSITION });
    const noPos = asset({ assetId: 'n-1', status: 'OFFLINE' });

    const markers = buildMarkers([streaming, noPos, offline], new Map(), 0);

    expect(markers.map((m) => m.assetId)).toEqual(['s-1', 'o-1']);
  });

  it('returns an empty array for no assets', () => {
    expect(buildMarkers([], new Map(), 0)).toEqual([]);
  });
});

describe('fingerprintMarkers', () => {
  function marker(partial: Partial<FleetMarker> = {}): FleetMarker {
    return {
      assetId: 'a-1',
      displayName: 'Drone One',
      category: 'drone',
      categoryName: 'Drone',
      status: 'STREAMING',
      live: true,
      position: POSITION,
      trail: [],
      ...partial,
    };
  }

  it('is stable for the same asset ids and positions', () => {
    const a = fingerprintMarkers([marker(), marker({ assetId: 'a-2', position: { latitude: 1, longitude: 1 } })]);
    const b = fingerprintMarkers([marker(), marker({ assetId: 'a-2', position: { latitude: 1, longitude: 1 } })]);
    expect(a).toBe(b);
  });

  it('changes when a position moves', () => {
    const a = fingerprintMarkers([marker()]);
    const b = fingerprintMarkers([marker({ position: { latitude: 10.0001, longitude: 20 } })]);
    expect(a).not.toBe(b);
  });

  it('changes when the marker set size changes', () => {
    const a = fingerprintMarkers([marker()]);
    const b = fingerprintMarkers([marker(), marker({ assetId: 'a-2' })]);
    expect(a).not.toBe(b);
  });

  it('is empty for no markers', () => {
    expect(fingerprintMarkers([])).toBe('');
  });
});

describe('nextAutoFitEnabled', () => {
  it('user interaction disables auto-fit', () => {
    expect(nextAutoFitEnabled(true, 'userInteraction')).toBe(false);
  });

  // Focusing one asset has to stop the fleet-wide fit, or auto-fit's own effect re-fits to every
  // marker on the next telemetry tick and yanks the camera straight back off the chosen asset.
  it('focusing an asset disables auto-fit, and is idempotent when already off', () => {
    expect(nextAutoFitEnabled(true, 'assetFocused')).toBe(false);
    expect(nextAutoFitEnabled(false, 'assetFocused')).toBe(false);
  });

  it('recenter still re-enables auto-fit after an asset focus', () => {
    expect(nextAutoFitEnabled(nextAutoFitEnabled(true, 'assetFocused'), 'recenterClicked')).toBe(true);
  });

  it('recenter re-enables auto-fit', () => {
    expect(nextAutoFitEnabled(false, 'recenterClicked')).toBe(true);
  });

  it('is idempotent: user interaction while already off stays off', () => {
    expect(nextAutoFitEnabled(false, 'userInteraction')).toBe(false);
  });

  it('is idempotent: recenter while already on stays on', () => {
    expect(nextAutoFitEnabled(true, 'recenterClicked')).toBe(true);
  });
});
