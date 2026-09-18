import type { BoxesMode } from '../../../shared/player/player';
import { DEFAULT_DECLUTTER_LEVEL } from '../../../shared/player/detection-overlay-logic';

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

const MAP_LAYER_IDS: readonly MapLayerId[] = ['standard', 'night', 'relief', 'satellite'];

export function isMapLayerId(value: unknown): value is MapLayerId {
  return typeof value === 'string' && (MAP_LAYER_IDS as readonly string[]).includes(value);
}

/**
 * View/session preferences — genuinely personal, client-only choices with no server-side counterpart
 * (docs/plans/active/CV-SETTINGS-PLAN.md wave W7, H2; migrated off `SettingsStore` per
 * docs/plans/active/NGRX-MIGRATION-PLAN.md wave N2). See the deleted `SettingsStore`'s own doc
 * comment (git history) for why no CV pipeline defaults live here.
 */
export interface SettingsState {
  /** Account-level, not per-page: an expert flips this once and stays expert
   *  (docs/main/UX-DESIGN.md §4, rule 4). */
  readonly advancedMode: boolean;
  /** Wall tiles per row. */
  readonly wallDensity: number;
  /** The active base map layer — persisted, shared by the fleet map and the live map inset. */
  readonly mapLayer: MapLayerId;
  /** Opt-in browser `Notification`s for newly-opened detection events (docs/plans/done/MVP2-PLAN.md
   *  §E, E-b) — off by default. `core/events/events-store.ts` still independently checks
   *  `Notification.permission === 'granted'` before ever firing one. */
  readonly eventNotifications: boolean;
  /** The operator's last-chosen drone for the Fly cockpit (docs/plans/done/MVP3-PLAN.md §C-b) —
   *  `null` until a first pick is made. */
  readonly flyAssetId: string | null;
  /** The detection-boxes declutter level — one shared, persisted preference (docs/plans/active/
   *  CV-SETTINGS-PLAN.md wave W7, H12). */
  readonly declutterLevel: BoxesMode;
  /** F2 digital crop-follow's own per-viewer preference (docs/plans/active/TRACK-FOLLOW-PLAN.md
   *  §3.1 item 4, wave W6) — off by default. */
  readonly cropFollowEnabled: boolean;
}

export const initialSettingsState: SettingsState = {
  advancedMode: false,
  wallDensity: 3,
  mapLayer: DEFAULT_MAP_LAYER,
  eventNotifications: false,
  flyAssetId: null,
  declutterLevel: DEFAULT_DECLUTTER_LEVEL,
  cropFollowEnabled: false,
};

/** One JSON blob under this key — every field optional, so a partially-corrupt or pre-wave value
 *  degrades field by field rather than all-or-nothing (see `settings.hydration.ts`). */
export const SETTINGS_STORAGE_KEY = 'vision.settings.v1';
