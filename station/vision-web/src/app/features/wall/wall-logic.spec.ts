import { describe, expect, it } from 'vitest';
import type { ActiveStream, AssetAttention, DetectionEvent, Device } from '../../core/api/models';
import type { GeofenceBreach } from '../../core/geofence/geofence-logic';
import {
  ACTIVITY_WINDOW_MS,
  buildWallTiles,
  DENSITY_STOPS,
  PULSE_WINDOW_MS,
  tileMinPx,
  wallActivityRows,
  type BuildWallTilesInput,
  type WallTileModel,
} from './wall-logic';

const NOW = Date.parse('2026-09-03T12:00:00Z');

function stream(partial: Partial<ActiveStream> = {}): ActiveStream {
  return {
    streamId: 's-1',
    deviceId: 'd-1',
    startedAt: '2026-09-03T11:00:00Z',
    viewUrl: 'https://example/view.m3u8',
    state: 'LIVE',
    ...partial,
  };
}

function device(partial: Partial<Device> = {}): Device {
  return {
    id: 'd-1',
    name: 'Device One',
    capabilities: [],
    protocol: 'rtsp',
    uri: 'rtsp://example',
    options: {},
    state: 'ACTIVE',
    ...partial,
  };
}

function asset(partial: Partial<AssetAttention> = {}): AssetAttention {
  return {
    assetId: 'a-1',
    displayName: 'Skyfall One',
    categoryId: 'drone',
    categoryName: 'Drone',
    lifecycle: 'ACTIVE',
    streaming: true,
    streamId: 's-1',
    openEventCount: 0,
    ...partial,
  };
}

function event(partial: Partial<DetectionEvent> = {}): DetectionEvent {
  return {
    id: 'e-1',
    streamId: 's-1',
    label: 'person',
    peakConfidence: 0.9,
    firstSeen: '2026-09-03T11:59:00Z',
    lastSeen: '2026-09-03T11:59:50Z',
    state: 'OPEN',
    ...partial,
  };
}

function baseInput(overrides: Partial<BuildWallTilesInput> = {}): BuildWallTilesInput {
  return {
    streams: [stream()],
    devices: [device()],
    assets: [asset()],
    events: [],
    pipelineErrors: new Map(),
    breaches: [],
    nowMs: NOW,
    // Every existing test in this file predates `summaryLoaded` and assumes the fleet-summary poll
    // has already resolved at least once — default it `true` so those cases (including the
    // deliberately-empty-`assets` "degraded summary" ones) keep meaning "loaded and confirmed," not
    // "hasn't answered yet." Tests for the cold-mount window override this explicitly.
    summaryLoaded: true,
    ...overrides,
  };
}

function tileFor(tiles: readonly WallTileModel[], streamId = 's-1'): WallTileModel {
  const found = tiles.find((tile) => tile.streamId === streamId);
  if (!found) {
    throw new Error(`no tile for ${streamId}`);
  }
  return found;
}

describe('tileMinPx / DENSITY_STOPS', () => {
  it('maps each frozen stop to its own tileMinPx', () => {
    expect(tileMinPx(2)).toBe(DENSITY_STOPS[0].tileMinPx);
    expect(tileMinPx(3)).toBe(DENSITY_STOPS[1].tileMinPx);
    expect(tileMinPx(4)).toBe(DENSITY_STOPS[2].tileMinPx);
  });

  it('clamps below 2 to Comfortable and at/above 4 to Dense — a stale pre-wave value never resolves undefined', () => {
    expect(tileMinPx(1)).toBe(DENSITY_STOPS[0].tileMinPx);
    expect(tileMinPx(5)).toBe(DENSITY_STOPS[2].tileMinPx);
    expect(tileMinPx(6)).toBe(DENSITY_STOPS[2].tileMinPx);
  });
});

describe('buildWallTiles — health precedence (§3.2 A1)', () => {
  it('no-publisher outranks every other condition, including an active pipeline error', () => {
    const tiles = buildWallTiles(
      baseInput({
        streams: [stream({ viewUrl: undefined, state: 'STALLED' })],
        pipelineErrors: new Map([['s-1', 'boom']]),
      }),
    );
    expect(tileFor(tiles).health).toBe('no-publisher');
    expect(tileFor(tiles).healthLabel).toContain('No publisher');
  });

  it('an active pipeline error outranks stalled/reconnecting/starting', () => {
    const tiles = buildWallTiles(baseInput({ streams: [stream({ state: 'STALLED' })], pipelineErrors: new Map([['s-1', 'cv-service down']]) }));
    expect(tileFor(tiles).health).toBe('pipeline-error');
    expect(tileFor(tiles).healthLabel).toBe('Pipeline error — cv-service down');
  });

  it('renders the pipeline error message verbatim — never paraphrased', () => {
    const tiles = buildWallTiles(baseInput({ pipelineErrors: new Map([['s-1', 'NoSuchElementException: model missing']]) }));
    expect(tileFor(tiles).healthLabel).toBe('Pipeline error — NoSuchElementException: model missing');
  });

  it.each([
    ['STALLED', 'stalled'],
    ['RECONNECTING', 'reconnecting'],
    ['STARTING', 'starting'],
  ] as const)('%s state maps to %s health with a non-null label', (state, health) => {
    const tiles = buildWallTiles(baseInput({ streams: [stream({ state })] }));
    expect(tileFor(tiles).health).toBe(health);
    expect(tileFor(tiles).healthLabel).not.toBeNull();
  });

  it('LIVE renders health "live" with a null label — the picture is the message', () => {
    const tiles = buildWallTiles(baseInput({ streams: [stream({ state: 'LIVE' })] }));
    expect(tileFor(tiles).health).toBe('live');
    expect(tileFor(tiles).healthLabel).toBeNull();
  });

  it.each([undefined, 'UNOBSERVED'] as const)('state=%s renders "unknown" with a null label — never a fabricated fault', (state) => {
    const tiles = buildWallTiles(baseInput({ streams: [stream({ state })] }));
    expect(tileFor(tiles).health).toBe('unknown');
    expect(tileFor(tiles).healthLabel).toBeNull();
  });
});

describe('buildWallTiles — title fallback ladder + unlinked (D3/D4)', () => {
  it('prefers the asset display name when linked', () => {
    const tiles = buildWallTiles(baseInput());
    expect(tileFor(tiles).title).toBe('Skyfall One');
    expect(tileFor(tiles).unlinked).toBe(false);
    expect(tileFor(tiles).assetId).toBe('a-1');
  });

  it('falls back to the device name when no asset resolves, and marks the tile unlinked', () => {
    const tiles = buildWallTiles(baseInput({ assets: [] }));
    expect(tileFor(tiles).title).toBe('Device One');
    expect(tileFor(tiles).unlinked).toBe(true);
    expect(tileFor(tiles).assetId).toBeUndefined();
  });

  it('falls back to an 8-char device-id fragment when neither asset nor device resolves', () => {
    const tiles = buildWallTiles(baseInput({ assets: [], devices: [], streams: [stream({ deviceId: 'abcdefghijklmnop' })] }));
    expect(tileFor(tiles).title).toBe('abcdefgh');
  });

  it('joins a live stream and summary row that share a streamId — regression for a live-verification defect where a matching pair still rendered unlinked', () => {
    // Exact shape from the live repro: /api/streams and /api/fleet/summary both carry
    // streamId "a7fcb809-bb57-493f-9518-a24a6fcb03c4" for the same device/asset, yet the tile
    // rendered "Skyfall Vampire 2 · telemetry" (the device name) and "Not linked to an asset".
    const streamId = 'a7fcb809-bb57-493f-9518-a24a6fcb03c4';
    const deviceId = '14707108-8d65-4346-a6c0-484f477832d1';
    const tiles = buildWallTiles(
      baseInput({
        streams: [stream({ streamId, deviceId, state: 'LIVE' })],
        devices: [device({ id: deviceId, name: 'Skyfall Vampire 2 · telemetry' })],
        assets: [
          asset({
            assetId: 'asset-vampire-2',
            displayName: 'Skyfall Vampire 2',
            streaming: true,
            streamId,
            batteryPercent: 98.6,
            telemetryAgeMs: 836,
            flightMode: 'Loiter',
            armed: true,
          }),
        ],
      }),
    );
    const tile = tileFor(tiles, streamId);
    // The join hit: linked, and the title is the asset name ALONE — never "· telemetry" or any
    // other device-plumbing suffix, even though the device's own name carries one.
    expect(tile.unlinked).toBe(false);
    expect(tile.assetId).toBe('asset-vampire-2');
    expect(tile.title).toBe('Skyfall Vampire 2');
    // The summary facts a linked tile is supposed to surface are all present, not "—" — battery
    // rounded to a whole percent (98.6 -> "99%"), never the raw float.
    expect(tile.batteryLabel).toBe('99%');
    expect(tile.telemetryAgeLabel).not.toBeNull();
    expect(tile.flightMode).toBe('Loiter');
    expect(tile.armed).toBe(true);
  });
});

describe('buildWallTiles — cold-mount window before the first summary response (fix)', () => {
  it('never claims "unlinked" before summaryLoaded is true, even with no asset data yet', () => {
    // WallFacade is request-scoped: on every fresh navigation to /wall, `assets` legitimately starts
    // empty for the brief window before its own first `fleetSummary()` fetch resolves, even though
    // FleetStore's streams/devices may already be warm. That window must render as "we don't know
    // yet", never as the confirmed-negative "Not linked to an asset".
    const tiles = buildWallTiles(baseInput({ assets: [], summaryLoaded: false }));
    const tile = tileFor(tiles);
    expect(tile.unlinked).toBe(false);
    expect(tile.assetId).toBeUndefined();
    expect(tile.title).toBe('Device One'); // still a reasonable placeholder, just not asserted as final
  });

  it('still claims "unlinked" once summaryLoaded is true and no asset resolves — the pre-existing degrade path is unchanged', () => {
    const tiles = buildWallTiles(baseInput({ assets: [], summaryLoaded: true }));
    expect(tileFor(tiles).unlinked).toBe(true);
  });

  it('links immediately once both summaryLoaded and a matching asset are present, regardless of prior loading state', () => {
    const tiles = buildWallTiles(baseInput({ summaryLoaded: true }));
    expect(tileFor(tiles).unlinked).toBe(false);
    expect(tileFor(tiles).title).toBe('Skyfall One');
  });
});

describe('buildWallTiles — battery label rounding (fix)', () => {
  it('rounds a raw float battery reading to a whole percent — regression for a live tile that rendered "58.349999999999994%"', () => {
    const tiles = buildWallTiles(baseInput({ assets: [asset({ batteryPercent: 58.349999999999994 })] }));
    expect(tileFor(tiles).batteryLabel).toBe('58%');
  });

  it('rounds .5-and-up up — 98.6 -> "99%"', () => {
    const tiles = buildWallTiles(baseInput({ assets: [asset({ batteryPercent: 98.6 })] }));
    expect(tileFor(tiles).batteryLabel).toBe('99%');
  });

  it('is null, not "—" or "NaN%", when the asset has no battery reading at all', () => {
    const tiles = buildWallTiles(baseInput({ assets: [asset({ batteryPercent: undefined })] }));
    expect(tileFor(tiles).batteryLabel).toBeNull();
  });

  it('never rounds the value severity is classified from — a value just above the warning threshold still reads "ok"', () => {
    // Default warning threshold is 25 (inclusive, <=). Raw 25.4 rounds to "25%" for display, but is
    // itself > 25, so severity must read "ok" — if rounding happened before classification, the
    // rounded 25 would incorrectly trip the <=25 warning boundary.
    const tiles = buildWallTiles(baseInput({ assets: [asset({ batteryPercent: 25.4 })] }));
    const tile = tileFor(tiles);
    expect(tile.batteryLabel).toBe('25%');
    expect(tile.batterySeverity).toBe('ok');
  });
});

describe('buildWallTiles — anti-double-signal rules (§3.2 A2)', () => {
  it('excludes open-events from the reasons list and severity — the pulse says it instead', () => {
    const tiles = buildWallTiles(baseInput({ assets: [asset({ openEventCount: 3 })] }));
    const tile = tileFor(tiles);
    expect(tile.reasons.some((r) => r.kind === 'open-events')).toBe(false);
    expect(tile.openEventCount).toBe(3);
  });

  it('excludes pipeline-error from the reasons list once it is already the health line', () => {
    const tiles = buildWallTiles(baseInput({ pipelineErrors: new Map([['s-1', 'boom']]) }));
    const tile = tileFor(tiles);
    expect(tile.health).toBe('pipeline-error');
    expect(tile.reasons.some((r) => r.kind === 'pipeline-error')).toBe(false);
  });

  it('a quiet asset with no other reasons reads severity "ok" even with open events', () => {
    const tiles = buildWallTiles(baseInput({ assets: [asset({ openEventCount: 5 })] }));
    expect(tileFor(tiles).severity).toBe('ok');
  });

  it('a genuine failsafe still ranks severity critical (unaffected by the exclusion rules)', () => {
    const tiles = buildWallTiles(baseInput({ assets: [asset({ failsafe: true })] }));
    expect(tileFor(tiles).severity).toBe('critical');
    expect(tileFor(tiles).reasons[0].kind).toBe('failsafe');
  });

  it('feeds this asset\'s own geofence breaches into its reasons, by assetId', () => {
    const breach: GeofenceBreach = { assetId: 'a-1', zoneId: 'z-1', zoneName: 'North perimeter', kind: 'KEEP_OUT', direction: 'enter' };
    const tiles = buildWallTiles(baseInput({ breaches: [breach] }));
    expect(tileFor(tiles).severity).toBe('critical');
    expect(tileFor(tiles).reasons[0].kind).toBe('geofence-breach');
  });
});

describe('buildWallTiles — pulse windowing (§3.2 A3)', () => {
  it('an OPEN event within the window pulses the tile with a count of same-label OPEN events', () => {
    const tiles = buildWallTiles(
      baseInput({
        events: [
          event({ id: 'e-1', lastSeen: new Date(NOW - 1_000).toISOString() }),
          event({ id: 'e-2', lastSeen: new Date(NOW - 2_000).toISOString() }),
          event({ id: 'e-3', label: 'car', lastSeen: new Date(NOW - 500).toISOString() }),
        ],
      }),
    );
    const pulse = tileFor(tiles).pulse;
    expect(pulse).not.toBeNull();
    expect(pulse!.label).toBe('person');
    expect(pulse!.count).toBe(2);
  });

  it('an event older than PULSE_WINDOW_MS never pulses the tile', () => {
    const tiles = buildWallTiles(
      baseInput({ events: [event({ lastSeen: new Date(NOW - PULSE_WINDOW_MS - 1_000).toISOString() })] }),
    );
    expect(tileFor(tiles).pulse).toBeNull();
  });

  it('a CLOSED event never pulses the tile', () => {
    const tiles = buildWallTiles(baseInput({ events: [event({ state: 'CLOSED', lastSeen: new Date(NOW - 1_000).toISOString() })] }));
    expect(tileFor(tiles).pulse).toBeNull();
  });

  it('an event for a different stream never pulses this tile', () => {
    const tiles = buildWallTiles(baseInput({ events: [event({ streamId: 's-other', lastSeen: new Date(NOW - 1_000).toISOString() })] }));
    expect(tileFor(tiles).pulse).toBeNull();
  });
});

describe('buildWallTiles — stable ordering (accepted decision #2)', () => {
  it('sorts by title case-insensitively, never by attention/severity', () => {
    const tiles = buildWallTiles(
      baseInput({
        streams: [stream({ streamId: 's-1', deviceId: 'd-1' }), stream({ streamId: 's-2', deviceId: 'd-2' })],
        devices: [device({ id: 'd-1' }), device({ id: 'd-2', name: 'zeta' })],
        assets: [asset({ assetId: 'a-1', streamId: 's-1', displayName: 'alpha', failsafe: true })],
      }),
    );
    expect(tiles.map((t) => t.streamId)).toEqual(['s-1', 's-2']); // 'alpha' < 'zeta' despite s-1 being critical
  });

  it('breaks a title tie by streamId', () => {
    const tiles = buildWallTiles(
      baseInput({
        streams: [stream({ streamId: 's-b' }), stream({ streamId: 's-a' })],
        devices: [device({ id: 'd-1', name: 'Same' })],
        assets: [],
      }),
    );
    expect(tiles.map((t) => t.streamId)).toEqual(['s-a', 's-b']);
  });
});

describe('buildWallTiles — degraded summary (WALL-FLOW-PLAN.md W1 scope)', () => {
  it('an empty assets list (a failed/forbidden fleet-summary poll) degrades every tile honestly', () => {
    const tiles = buildWallTiles(baseInput({ assets: [] }));
    const tile = tileFor(tiles);
    expect(tile.unlinked).toBe(true);
    expect(tile.severity).toBe('ok');
    expect(tile.batterySeverity).toBe('unknown');
    expect(tile.telemetryAgeLabel).toBeNull();
    expect(tile.title).toBe('Device One');
  });
});

describe('wallActivityRows', () => {
  it('matches by assetId when both the event and a tile carry one, even across a stream restart', () => {
    const tiles = buildWallTiles(baseInput());
    const restarted = event({ id: 'e-old', streamId: 's-old-gone', assetId: 'a-1', lastSeen: new Date(NOW - 60_000).toISOString() });
    const rows = wallActivityRows([restarted], tiles, NOW);
    expect(rows).toHaveLength(1);
    expect(rows[0].tile.streamId).toBe('s-1');
    expect(rows[0].sourceLabel).toBe('Skyfall One');
  });

  it('falls back to streamId when the event has no assetId', () => {
    const tiles = buildWallTiles(baseInput({ assets: [] }));
    const row = event({ assetId: undefined, streamId: 's-1', lastSeen: new Date(NOW - 1_000).toISOString() });
    const rows = wallActivityRows([row], tiles, NOW);
    expect(rows).toHaveLength(1);
    expect(rows[0].tile.streamId).toBe('s-1');
  });

  it('drops an event matching neither an assetId nor a streamId on this wall', () => {
    const tiles = buildWallTiles(baseInput());
    const orphan = event({ id: 'e-orphan', streamId: 's-elsewhere', assetId: 'a-elsewhere', lastSeen: new Date(NOW - 1_000).toISOString() });
    expect(wallActivityRows([orphan], tiles, NOW)).toEqual([]);
  });

  it('drops an event older than ACTIVITY_WINDOW_MS', () => {
    const tiles = buildWallTiles(baseInput());
    const stale = event({ lastSeen: new Date(NOW - ACTIVITY_WINDOW_MS - 1_000).toISOString() });
    expect(wallActivityRows([stale], tiles, NOW)).toEqual([]);
  });

  it('sorts newest-first regardless of input order', () => {
    const tiles = buildWallTiles(baseInput());
    const older = event({ id: 'e-older', lastSeen: new Date(NOW - 30_000).toISOString() });
    const newer = event({ id: 'e-newer', lastSeen: new Date(NOW - 5_000).toISOString() });
    const rows = wallActivityRows([older, newer], tiles, NOW);
    expect(rows.map((r) => r.event.id)).toEqual(['e-newer', 'e-older']);
  });
});
