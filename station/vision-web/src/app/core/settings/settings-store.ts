import { Injectable, effect, signal } from '@angular/core';
import type { BoxesMode } from '../../shared/player/player';
import { DEFAULT_DECLUTTER_LEVEL, isBoxesMode } from '../../shared/player/detection-overlay-logic';

/**
 * The four base map layers (docs/main/CYCLES-PLAN.md §9, CU-b item 6) — shared by the fleet map
 * (`shared/map/fleet-map.ts`) and the live cockpit's map inset (`shared/map/live-map.ts`) via this
 * one persisted choice, rather than each map remembering its own. Definitions (tile URL,
 * attribution, max zoom) live in `shared/map/leaflet-loader.ts#MAP_LAYERS`, keyed by this id — this file
 * only owns *which one is selected*, not the tile-provider details.
 */
export type MapLayerId = 'standard' | 'night' | 'relief' | 'satellite';

/**
 * `night` (OSM tiles through a dark tile-pane filter, OPERATOR-UX-6 M1) rather than `standard` (plain
 * OSM) — this console is dark by default (docs/main/UX-DESIGN.md §7.7), so it is the closer match to
 * the app's existing look out of the box.
 */
const DEFAULT_MAP_LAYER: MapLayerId = 'night';

interface PersistedSettings {
  advancedMode: boolean;
  wallDensity: number;
  mapLayer: MapLayerId;
  eventNotifications: boolean;
  flyAssetId: string | null;
  declutterLevel: BoxesMode;
}

const STORAGE_KEY = 'vision.settings.v1';

/**
 * View/session preferences — genuinely personal, client-only choices with no server-side counterpart
 * (docs/plans/active/CV-SETTINGS-PLAN.md wave W7, H2). **No CV pipeline defaults live here any more.**
 *
 * Before this wave, this store also carried a `PipelineSettings` draft/preset system
 * (`activeProfileId`/`customProfiles`/`draft`/`effective()`) — a second, unaudited path to "what a
 * stream starts with" that duplicated the server-side profile hierarchy (PLATFORM → ORGANIZATION →
 * CATEGORY → ASSET → SESSION, docs/plans/active/CV-SETTINGS-PLAN.md §3.1) with a browser-local one no
 * operator asked for and no admin could see. Every CV knob (confidence/fps/model/label filters/
 * detection on-off/tracking) is now either **read back from the wire** (`GET /api/streams/{id}/config`
 * while a stream is running, `GET /api/cv/profiles/effective?assetId=` otherwise —
 * `features/fly/cv-control-panel-logic.ts#resolveCvConfig`) or **written explicitly** to a profile via
 * `/vision/profiles`' own editor — never silently drafted into `localStorage` in between. See
 * `CockpitFacade#resolvedCvConfig`'s own doc comment for where the wire read-back now lives.
 *
 * What's left is exactly the state with no honest home anywhere else: a rendering preference
 * ({@link declutterLevel}), UI chrome ({@link advancedMode}/{@link wallDensity}), a browser
 * permission gate ({@link eventNotifications}), the last basemap choice ({@link mapLayer}), and the
 * last-flown drone for `/fly`'s own redirect ({@link flyAssetId}) — none of these have (or need) a
 * server-side counterpart; a second browser or a fresh profile simply starts with the defaults below.
 */
@Injectable({ providedIn: 'root' })
export class SettingsStore {
  /**
   * Account-level, not per-page: an expert flips this once and stays expert
   * (docs/main/UX-DESIGN.md §4, rule 4).
   */
  readonly advancedMode = signal(false);

  /** Wall tiles per row. */
  readonly wallDensity = signal(3);

  /** The active base map layer — persisted, shared by the fleet map and the live map inset. */
  readonly mapLayer = signal<MapLayerId>(DEFAULT_MAP_LAYER);

  /**
   * Opt-in browser `Notification`s for newly-opened detection events (docs/plans/done/MVP2-PLAN.md §E, E-b)
   * — off by default, both because it's a permission-gated browser feature a user should
   * deliberately turn on, and because `Notification.requestPermission()` must be called from a
   * direct user gesture in most browsers, which only the Settings page's toggle can provide.
   * `core/events/events-store.ts` still independently checks `Notification.permission === 'granted'`
   * before ever firing one — flipping this signal alone (e.g. a stale/tampered persisted value)
   * can never bypass the browser's own permission gate.
   */
  readonly eventNotifications = signal(false);

  /**
   * The operator's last-chosen drone for the Fly cockpit (docs/plans/done/MVP3-PLAN.md §C-b) — `null` until a
   * first pick is made. `null` is what tells `FlyPage` to show the asset picker instead of jumping
   * straight into a cockpit; every later visit (and every use of the in-cockpit switcher, which
   * writes here too) skips the picker. Not validated against the live fleet here — an id for an
   * asset that was since archived/deleted is a normal, expected staleness this store has no way to
   * detect on its own; `FlyPage` is the layer that checks the id still resolves and falls back to
   * the picker if not (same "store persists, page validates" split `mapLayer` already follows for
   * its own stale-value case).
   */
  readonly flyAssetId = signal<string | null>(null);

  /**
   * The detection-boxes declutter level — **one shared, persisted preference** (docs/plans/active/
   * CV-SETTINGS-PLAN.md wave W7, H12), replacing three unshared in-memory signals that used to live
   * one each on `CockpitFacade`, `LiveFacade` and `WallTile`: an operator who picked "Locked only" in
   * the Fly cockpit saw "All" again the moment they opened a Wall tile, with no reason either surface
   * could name. This is purely a client-side rendering preference — how already-computed detections
   * are *drawn* — never part of the wire contract or the CV profile hierarchy (§3.5's own boundary:
   * this knob has no backend effect, so it is legitimately a `localStorage` pref, not a dishonest
   * draft of something the server also decides). Every consumer (`CockpitFacade.boxesMode`,
   * `LiveFacade.boxesMode`, `WallTile`) now aliases this exact signal instance rather than keeping its
   * own copy.
   */
  readonly declutterLevel = signal<BoxesMode>(DEFAULT_DECLUTTER_LEVEL);

  constructor() {
    this.restore();
    effect(() => this.persist());
  }

  private restore(): void {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return;
    }
    try {
      const parsed = JSON.parse(raw) as Partial<PersistedSettings>;
      if (typeof parsed.advancedMode === 'boolean') {
        this.advancedMode.set(parsed.advancedMode);
      }
      if (typeof parsed.wallDensity === 'number') {
        this.wallDensity.set(parsed.wallDensity);
      }
      if (isMapLayerId(parsed.mapLayer)) {
        this.mapLayer.set(parsed.mapLayer);
      }
      if (typeof parsed.eventNotifications === 'boolean') {
        this.eventNotifications.set(parsed.eventNotifications);
      }
      if (typeof parsed.flyAssetId === 'string') {
        this.flyAssetId.set(parsed.flyAssetId);
      }
      if (isBoxesMode(parsed.declutterLevel)) {
        this.declutterLevel.set(parsed.declutterLevel);
      }
    } catch {
      // Corrupt or stale settings must never keep the app from starting.
      localStorage.removeItem(STORAGE_KEY);
    }
  }

  private persist(): void {
    const snapshot: PersistedSettings = {
      advancedMode: this.advancedMode(),
      wallDensity: this.wallDensity(),
      mapLayer: this.mapLayer(),
      eventNotifications: this.eventNotifications(),
      flyAssetId: this.flyAssetId(),
      declutterLevel: this.declutterLevel(),
    };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(snapshot));
  }
}

const MAP_LAYER_IDS: readonly MapLayerId[] = ['standard', 'night', 'relief', 'satellite'];

function isMapLayerId(value: unknown): value is MapLayerId {
  return typeof value === 'string' && (MAP_LAYER_IDS as readonly string[]).includes(value);
}
