import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { DrawingsStore } from '../map-data/drawings-store';
import { LayersStore } from '../map-data/layers-store';
import { MarksStore } from '../map-data/marks-store';
import { TracksStore } from '../map-data/tracks-store';
import { GeofenceStore } from '../geofence/geofence-store';
import { SystemStatusStore } from '../system-status/system-status-store';
import { FleetMapStore } from '../map/map-store';
import { LiveFacade } from './live-facade';
import { PollScheduler } from '../poll-scheduler';
import { VisionApi } from '../api/vision-api';
import { ToastService } from '../toast.service';
import { UndoToastService } from '../../shared/ui/undo-toast.service';
import type { LiveConnectionState } from './live-fallback-logic';
import type { AssetSummary } from '../api/models';

/**
 * The plan's own acceptance criterion 2 (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md &sect;7) —
 * "a clean re-measurement, compared against &sect;2's cadence arithmetic", **as a test instead of a
 * browser session**.
 *
 * <h2>Why this file exists rather than a screenshot of a DevTools network tab</h2>
 * The 2026-09-06 browser attempt was voided by exactly two things, and this harness is immune to
 * both by construction:
 * <ol>
 *   <li><b>The backend died mid-window</b>, which correctly *resumed* the very polls being measured
 *       and inflated the count. Here the transport is an input (`connectionState`), not a hazard.</li>
 *   <li><b>The tab was Chrome-throttled.</b> `PollScheduler` pauses every task while
 *       `document.hidden`, so a background tab measures a flattering zero regardless of the code.
 *       {@link measure} asserts `document.hidden === false` before counting, so this harness can
 *       never repeat that mistake silently.</li>
 * </ol>
 * It also measures something a browser cannot: the *counterfactual*. The `poll` column is today's
 * behaviour on the same commit — the real "before" — rather than a number recovered from a
 * different build.
 *
 * <h2>What is and is not covered</h2>
 * The seven root stores &sect;2's table names. The eighth row, `/command`'s own 5s
 * `GET /api/fleet/summary` (wave L8a), is **not** here: `CommandFacade` has no TestBed harness in
 * this repo (only `command-logic.spec.ts`, a pure-logic file) and standing one up would mean faking
 * `Router`/`ActivatedRoute`/`AuthFacade`/`WeatherStore`/`RouteStore` for one number. Its retirement
 * is pinned instead by `features/fleet/summary-refresh-logic.spec.ts` and stated as arithmetic in
 * &sect;9 of the plan, labelled as such.
 */
const WINDOW_MS = 60_000;

type Counts = Record<string, number>;

function streamingAsset(): AssetSummary {
  return {
    assetId: 's-1',
    displayName: 'Drone One',
    category: 'drone',
    categoryName: 'Drone',
    owner: 'owner-0',
    status: 'STREAMING',
    lifecycle: 'ACTIVE',
    attributes: {},
    lastKnownPosition: { latitude: 50.45, longitude: 30.52 },
  };
}

function countingApi(counts: Counts, assets: readonly AssetSummary[]) {
  const record = (endpoint: string, result: unknown) =>
    vi.fn(async () => {
      counts[endpoint] = (counts[endpoint] ?? 0) + 1;
      return result;
    });
  return {
    listMapMarks: record('GET /api/map/marks', []),
    listMapLayers: record('GET /api/map/layers', []),
    listMapDrawings: record('GET /api/map/drawings', []),
    listMapTracks: record('GET /api/map/tracks', []),
    listGeofences: record('GET /api/geofences', []),
    systemStatus: record('GET /api/system/status', {
      overall: 'HEALTHY',
      subsystems: [],
      generatedAt: '2026-09-07T00:00:00Z',
    }),
    listAssets: record('GET /api/assets', assets),
    getAsset: record('GET /api/assets/{id}', {
      ...streamingAsset(),
      devices: [],
      recentUsages: [{ usageId: 'u-1', startedAt: 't0', sampleCount: 1 }],
    }),
    usageTelemetry: record('GET /api/usages/{id}/telemetry', []),
  };
}

/** Real Angular signals so every store's own `effect()`/`computed` reacts as it would in the app. */
function stubLiveFacade(state: LiveConnectionState) {
  const telemetry = signal<readonly never[]>([]);
  return {
    connectionState: signal<LiveConnectionState>(state),
    mapEvents: signal([]).asReadonly(),
    zoneEvents: signal([]).asReadonly(),
    systemStatus: signal(undefined).asReadonly(),
    fleet: signal(undefined).asReadonly(),
    telemetryFor: () => telemetry.asReadonly(),
    trackTelemetry: vi.fn(),
    untrackTelemetry: vi.fn(),
  };
}

/** Drains the fire-and-forget promise chains without moving the fake clock. */
async function drain(): Promise<void> {
  for (let i = 0; i < 5; i += 1) {
    await vi.advanceTimersByTimeAsync(0);
  }
}

interface Measurement {
  readonly steadyState: Counts;
  readonly onceAtStartup: Counts;
  readonly total: number;
}

/**
 * Builds `/command`'s store set under `transport`, drains the one-time startup fetches, then counts
 * every request the app makes over {@link WINDOW_MS} of wall clock.
 */
async function measure(transport: LiveConnectionState, assets: readonly AssetSummary[] = []): Promise<Measurement> {
  expect(document.hidden, 'PollScheduler pauses while hidden — a hidden document measures a false 0').toBe(false);

  const counts: Counts = {};
  const api = countingApi(counts, assets);
  const toasts = { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn(), warn: vi.fn() };

  TestBed.configureTestingModule({
    providers: [
      MarksStore,
      LayersStore,
      DrawingsStore,
      TracksStore,
      GeofenceStore,
      SystemStatusStore,
      FleetMapStore,
      PollScheduler,
      { provide: VisionApi, useValue: api },
      { provide: ToastService, useValue: toasts },
      { provide: UndoToastService, useValue: { show: vi.fn() } },
      { provide: LiveFacade, useValue: stubLiveFacade(transport) },
      provideRouter([]),
    ],
  });

  // `/command`'s activation set. `TracksStore` is deliberately absent — §2: it costs 0 here,
  // because only `features/asset-detail/asset-detail-facade.ts` ever activates it.
  TestBed.inject(MarksStore).activate();
  TestBed.inject(LayersStore).activate();
  TestBed.inject(DrawingsStore).activate();
  TestBed.inject(GeofenceStore).activate();
  TestBed.inject(SystemStatusStore); // root singleton, polls from construction on every page
  TestBed.inject(FleetMapStore); // page-provided, polls from construction
  TestBed.tick();
  await drain();

  const onceAtStartup = { ...counts };
  for (const key of Object.keys(counts)) {
    delete counts[key];
  }

  await vi.advanceTimersByTimeAsync(WINDOW_MS);

  const steadyState = { ...counts };
  const total = Object.values(steadyState).reduce((sum, n) => sum + n, 0);
  return { steadyState, onceAtStartup, total };
}

describe('request rate on /command (LIVE-POLL-RETIREMENT-PLAN.md §7 acceptance 2)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.useRealTimers();
  });

  it('reproduces §2’s cadence arithmetic exactly while live is unavailable', async () => {
    const { steadyState, total } = await measure('closed');

    expect(steadyState).toEqual({
      'GET /api/map/marks': 2,
      'GET /api/map/layers': 2,
      'GET /api/map/drawings': 2,
      'GET /api/geofences': 2,
      'GET /api/system/status': 4,
      'GET /api/assets': 12,
    });
    expect(total).toBe(24);
  });

  it('makes no request at all over a full minute while live is open', async () => {
    const { steadyState, total } = await measure('open');

    expect(steadyState).toEqual({});
    expect(total).toBe(0);
  });

  it('pays each endpoint exactly once at startup when live is open, then nothing', async () => {
    const { onceAtStartup } = await measure('open');

    expect(onceAtStartup).toEqual({
      'GET /api/map/marks': 1,
      'GET /api/map/layers': 1,
      'GET /api/map/drawings': 1,
      'GET /api/geofences': 1,
      'GET /api/system/status': 1,
      'GET /api/assets': 1,
    });
  });

  it('costs nothing per streaming asset — the 2s telemetry poll is gone, not re-homed', async () => {
    const { steadyState, onceAtStartup } = await measure('open', [streamingAsset()]);

    expect(onceAtStartup['GET /api/usages/{id}/telemetry']).toBe(1); // the one-time L7c backfill
    expect(steadyState['GET /api/usages/{id}/telemetry']).toBeUndefined();
    expect(steadyState).toEqual({});
  });

  it('still costs 30 req/min per streaming asset when live is unavailable — the honest fallback', async () => {
    const { steadyState } = await measure('closed', [streamingAsset()]);

    // D1 in full: the fallback poll exists and runs only while live does not. This row is the one
    // this measurement found missing — L7b had deleted the poll outright rather than gating it, so
    // with SSE down the marker froze at its backfill position (see `TELEMETRY_POLL_INTERVAL_MS`).
    expect(steadyState['GET /api/usages/{id}/telemetry']).toBe(30);
    expect(steadyState['GET /api/assets']).toBe(12);
  });
});
