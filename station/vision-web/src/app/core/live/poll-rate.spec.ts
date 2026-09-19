import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Store } from '@ngrx/store';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { GeofenceFacade } from '../geofence/geofence-facade';
import { DrawingsFacade } from '../map-data/drawings-facade';
import { LayersFacade } from '../map-data/layers-facade';
import { MarksFacade } from '../map-data/marks-facade';
import { MapFacade } from '../map/map-facade';
import { SystemStatusStore } from '../system-status/system-status-store';
import { provideAppState } from '../state/app-state';
import { provideDrawingsState } from '../map-data/state/drawings.providers';
import { provideGeofenceState } from '../geofence/state/geofence.providers';
import { provideLayersState } from '../map-data/state/layers.providers';
import { provideMarksState } from '../map-data/state/marks.providers';
import { provideMapState } from '../map/state/map.providers';
import { provideRouteState } from '../map-data/state/route.providers';
import { provideWeatherState } from '../weather/state/weather.providers';
import { provideTelemetryState } from '../telemetry/state/telemetry.providers';
import { provideDetectionsState } from '../detections/state/detections.providers';
import { LiveFacade } from './live-facade';
import { LiveSocketActions } from './state/live.actions';
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
 * `Router`/`ActivatedRoute`/`AuthFacade`/`WeatherFacade`/`RouteFacade` for one number. Its retirement
 * is pinned instead by `features/fleet/summary-refresh-logic.spec.ts` and stated as arithmetic in
 * &sect;9 of the plan, labelled as such.
 *
 * <h2>docs/plans/active/NGRX-MIGRATION-PLAN.md wave N6</h2>
 * Every root store this file drives moved to NgRx this wave (`MarksStore`→`MarksFacade`,
 * `LayersStore`→`LayersFacade`, `DrawingsStore`→`DrawingsFacade`, `GeofenceStore`→`GeofenceFacade`,
 * `FleetMapStore`→`MapFacade`; `TracksStore`→`TracksFacade` is imported nowhere here, unchanged from
 * before — see the "deliberately absent" note below). `provideAppState()` plus each page-scoped slice's own `provide<Domain>State()` replaces the old hand-rolled
 * `{ provide: LiveFacade, useValue: stubLiveFacade(transport) }`: `LiveFacade` itself is untouched
 * `core/live/**` territory (out of this wave's scope) and every migrated slice's own gate effect now
 * reads the real `live` feature state through its own selectors (NGRX-MIGRATION-PLAN.md §9's "never
 * inject `LiveFacade` into an effect" rule) — so the only way left to drive `transport` here is to
 * dispatch the same `LiveSocketActions.opened`/`closed` the real gateway would, straight onto the real
 * `Store`. `MapFacade.markers` still composes the real `LiveFacade` directly (facade-to-facade, not
 * effect-level — the rule's own distinction), so this file no longer needs to fake it at all.
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
      provideAppState(), provideMapState(), provideRouteState(), provideWeatherState(), provideTelemetryState(), provideDetectionsState(),
      provideMarksState(), provideLayersState(), provideDrawingsState(), provideGeofenceState(),
      MarksFacade, LayersFacade, DrawingsFacade, GeofenceFacade,
      SystemStatusStore,
      MapFacade,
      PollScheduler,
      { provide: VisionApi, useValue: api },
      { provide: ToastService, useValue: toasts },
      { provide: UndoToastService, useValue: { show: vi.fn() } },
      provideRouter([]),
    ],
  });

  // `LiveFacade`'s own constructor unconditionally dispatches `Reconnect Requested` (mirrors the old
  // `LiveStore`'s own constructor `connect()` call) — and `MapFacade` (below) injects `LiveFacade`
  // directly for `markers`' telemetry merge, so constructing it is unavoidable here just like in the
  // real app. Under jsdom, `LiveGateway.isAvailable()` is always `false` (no `EventSource`), so that
  // first attempt always settles to `'closed'` — exactly the real app's own honest behavior the very
  // first time it ever runs in this environment. Forcing that settle *before* setting the desired
  // `transport`, and before any demand exists, keeps it from firing as a spurious *second* mode
  // transition once marks/layers/drawings/geofence/map activate below.
  const store = TestBed.inject(Store);
  TestBed.inject(LiveFacade);
  await drain();
  store.dispatch(transport === 'open' ? LiveSocketActions.opened() : LiveSocketActions.closed());

  // `/command`'s activation set. `TracksFacade` is deliberately absent — §2: it costs 0 here,
  // because only `features/asset-detail/asset-detail-facade.ts` ever activates it.
  TestBed.inject(MarksFacade).activate();
  TestBed.inject(LayersFacade).activate();
  TestBed.inject(DrawingsFacade).activate();
  TestBed.inject(GeofenceFacade).activate();
  TestBed.inject(SystemStatusStore); // root singleton, polls from construction on every page
  TestBed.inject(MapFacade); // page-provided, dispatches `activated()` from its own constructor
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
