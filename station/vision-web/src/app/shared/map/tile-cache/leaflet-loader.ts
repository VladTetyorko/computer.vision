import { signal } from '@angular/core';
import type * as Leaflet from 'leaflet';
import type { MapLayerId } from '../../../core/settings/settings-store';
import type { Theme } from '../../../core/shell/theme-store';
import { readPersistedFlag, writePersistedFlag } from '../../../core/panel-state';
import { getCachedTile, putCachedTile } from './tile-cache-db';
import { tileCacheKey, tileHost } from './tile-cache-logic';

/**
 * Leaflet bootstrap bits shared by every map in this app (`shared/map/tactical-map/`, the one map
 * component since docs/plans/done/MAP-REWORK-PLAN.md §5.1; plus `features/replay/replay-map.ts`,
 * `shared/map/fleet-plan-dialog/`, `features/command/geofence-zone-dialog.ts`): the dynamic import,
 * the runtime stylesheet injection, and the switchable base-layer tile factory
 * (docs/main/CYCLES-PLAN.md §9, CU-b item 6 — `MAP_LAYERS`/`mapLayerTileLayer`). Pulled out of
 * the original single-asset map when the `/map` tab needed the identical setup — intra-app DRY (unlike the
 * cross-adapter rule in the Java side of this repo, nothing here stops two Angular pages sharing
 * a plain module).
 *
 * Each *host component* still owns its own dynamic `import('leaflet')` call site conceptually —
 * calling `importLeaflet()` from `initMap()` — so this module changes nothing about where the
 * Leaflet chunk is fetched from (still only on first map creation, still its own lazy chunk, see
 * `allowedCommonJsDependencies` in `angular.json` and the `leaflet-src` chunk both host pages'
 * MODULE.md sections describe).
 */

/** Injected once per document, only when a map is actually created — see `ensureLeafletStylesheet`. */
const LEAFLET_STYLESHEET_ID = 'vision-leaflet-css';
const LEAFLET_STYLESHEET_HREF = '/leaflet/leaflet.css';

/**
 * One definition per switchable base layer (docs/main/CYCLES-PLAN.md §9, CU-b item 6): **Standard**
 * (plain OSM raster), **Night** (also OSM raster, run through {@link MapLayerDef.tileFilter} — see
 * that field's own doc comment for why: CARTO Dark Matter, the real-dark-tile source this used to
 * be, started returning "API KEY REQUIRED" tiles, per docs/plans/active/OPERATOR-UX-6-PLAN.md M1),
 * **Relief** (OpenTopoMap, contour shading), and **Satellite** (Esri World Imagery). Each carries
 * its own attribution text, shown by Leaflet's attribution control automatically whenever that
 * layer is the one added to the map. Selection is `SettingsStore.mapLayer` — persisted, one choice
 * shared by every map in the app.
 */
export interface MapLayerDef {
  readonly id: MapLayerId;
  readonly label: string;
  readonly url: string;
  readonly attribution: string;
  readonly maxZoom: number;
  /**
   * A CSS `filter` value the host applies to `.leaflet-tile-pane` only — never markers, drawings,
   * or popups, which live in their own Leaflet panes (docs/plans/active/OPERATOR-UX-6-PLAN.md M1).
   * `undefined` for every layer except `night`: Standard/Relief/Satellite render their source tiles
   * as-is. `tactical-map.ts#activeBasemapTileFilter` reads this and exposes it as the
   * `--basemap-tile-filter` custom property on the host, gated behind a `basemap-filtered` class so
   * the CSS rule (`tactical-map.css`) only ever matches while the active basemap actually has one.
   */
  readonly tileFilter?: string;
}

const OSM_ATTRIBUTION =
  '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';

/**
 * The standard Leaflet dark-basemap trick: invert OSM's light tiles, then rotate/tune them back to
 * a plausible dark palette rather than an inverted rainbow (docs/plans/active/OPERATOR-UX-6-PLAN.md
 * M1). Lives here, next to the layer it belongs to, rather than inline in `MAP_LAYERS` below, so
 * its own "why these five numbers" reasoning doesn't have to interrupt that table's scan.
 */
const NIGHT_TILE_FILTER = 'invert(1) hue-rotate(180deg) brightness(0.85) contrast(0.9) saturate(0.6)';

const OPENTOPOMAP_ATTRIBUTION =
  '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors, ' +
  '<a href="https://opentopomap.org">OpenTopoMap</a> (CC-BY-SA)';
const ESRI_ATTRIBUTION =
  'Tiles &copy; Esri &mdash; Esri, DigitalGlobe, GeoEye, Earthstar Geographics, CNES/Airbus DS, USDA, USGS, AeroGRID, IGN, and the GIS User Community';

export const MAP_LAYERS: readonly MapLayerDef[] = [
  {
    id: 'standard',
    label: 'Standard',
    url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
    attribution: OSM_ATTRIBUTION,
    maxZoom: 19,
  },
  {
    id: 'night',
    label: 'Night',
    // Same source as `standard` (OSM raster) — deliberately: the dark look comes entirely from
    // `tileFilter` below, not a different tile provider. See that field's doc comment for why
    // (CARTO Dark Matter needs an API key this app doesn't have). The tile cache keys by layer id
    // as well as z/x/y (`tile-cache-logic.ts#tileCacheKey`), so `standard` and `night` panning over
    // the same area cache the identical bytes twice under two different keys — extra storage, never
    // a correctness issue (no key collision, nothing to invalidate).
    url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
    attribution: OSM_ATTRIBUTION,
    maxZoom: 19,
    tileFilter: NIGHT_TILE_FILTER,
  },
  {
    id: 'relief',
    label: 'Relief',
    url: 'https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png',
    attribution: OPENTOPOMAP_ATTRIBUTION,
    maxZoom: 17,
  },
  {
    id: 'satellite',
    label: 'Satellite',
    url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
    attribution: ESRI_ATTRIBUTION,
    maxZoom: 19,
  },
];

/** Looks up a layer definition by id, falling back to `standard` for an unrecognized/stale one. */
export function mapLayerDef(id: MapLayerId): MapLayerDef {
  return MAP_LAYERS.find((layer) => layer.id === id) ?? MAP_LAYERS[0];
}

// --- Theme-aware default layer (docs/plans/done/VISUAL-REFRESH-PLAN.md F7, Wave 3) ------------------------
//
// `SettingsStore.mapLayer` (docs/main/CYCLES-PLAN.md §9) is a single persisted signal shared by every
// map — it always holds a concrete `MapLayerId` (its own hardcoded `DEFAULT_MAP_LAYER = 'night'`
// until something changes it), so a consumer reading it alone cannot tell "the operator has never
// touched the layer picker" apart from "the operator explicitly chose Night". That distinction is
// exactly what F7 needs ("the default follows the theme … an explicit user pick always wins"), so
// this module tracks it separately, in its own tiny persisted flag, rather than needing a change to
// `core/settings/settings-store.ts` (out of this wave's own file scope — see `vision-web/MODULE.md`'s
// dated Wave 3 entry for the full accounting, including why a returning user's *already*-persisted
// `mapLayer` value cannot retroactively be told apart from the store's own unconditional default).

const MAP_LAYER_EXPLICIT_KEY = 'vision.map.layerExplicit';

/** `standard` (plain OSM raster) is already a genuinely light basemap — no new layer needed. `night`
 * (same OSM raster, run dark through its own `tileFilter`) is the existing dark one. Every other
 * layer (`relief`/`satellite`) is neutral imagery, never a *default* either theme picks on its own
 * — only ever reached by an explicit pick. */
export function defaultMapLayerIdForTheme(theme: Theme): MapLayerId {
  return theme === 'dark' ? 'night' : 'standard';
}

/**
 * The reactive source of truth behind {@link isMapLayerExplicit}/{@link markMapLayerExplicit}.
 * **`localStorage` is this signal's persistence, not its source of truth** — see `vision-web/MODULE.md`
 * Gotchas for the bug this replaces: `isMapLayerExplicit()` used to be a bare `localStorage.getItem`
 * read, invisible to Angular's dependency graph, so a `computed()` calling it inside `TacticalMap`
 * never re-ran when the flag flipped false→true in the exact same click that also called
 * `settings.mapLayer.set(id)` with an *already-equal* value (the theme-implied default happened to
 * match the operator's pick) — that `.set()` is an `Object.is` no-op, so nothing else invalidated the
 * computed and the map/button silently didn't update until a full reload. Seeded once per module
 * load from whatever was already persisted; every later flip goes through `markMapLayerExplicit()`,
 * which updates this signal *and* persists it in the same call, so every reader (`TacticalMap`,
 * `GeofenceZoneDialog`, `FlightPlanDialog`, `ReplayMap` — see each one's own `activeBasemapId`/
 * `activeLayerId` computed) picks up the flip on the very same tick it happens.
 */
const explicitMapLayer = signal(readPersistedFlag(MAP_LAYER_EXPLICIT_KEY, false));

/** Whether the operator has ever explicitly used the layer picker (`markMapLayerExplicit`) — once
 * true, `effectiveMapLayerId` stops substituting the theme default and always returns their choice.
 * Reads {@link explicitMapLayer}, not `localStorage` directly — see that signal's own doc comment. */
export function isMapLayerExplicit(): boolean {
  return explicitMapLayer();
}

/** Records an explicit layer-picker click — every host's own `setBasemap`/`setLayer` calls this
 * alongside `SettingsStore.mapLayer.set(id)`, so the two persisted values always change together.
 * Writes {@link explicitMapLayer} first (the reactive notification) and `localStorage` second (the
 * persistence) — see that signal's own doc comment for why the signal write is load-bearing, not
 * redundant with the `localStorage` one. */
export function markMapLayerExplicit(): void {
  explicitMapLayer.set(true);
  writePersistedFlag(MAP_LAYER_EXPLICIT_KEY, true);
}

/**
 * The layer id a map should actually render: `chosen` (`SettingsStore.mapLayer()`) once the
 * operator has made an explicit pick, otherwise the theme's own default — "an explicit user pick
 * always wins" (docs/plans/done/VISUAL-REFRESH-PLAN.md F7). Pure and unit-tested (`leaflet-loader.spec.ts`);
 * `TacticalMap` wraps it in a `computed()` reading `ThemeStore.theme()` +
 * `SettingsStore.mapLayer()`, so both re-render the instant either changes.
 */
export function effectiveMapLayerId(theme: Theme, chosen: MapLayerId, explicit: boolean): MapLayerId {
  return explicit ? chosen : defaultMapLayerIdForTheme(theme);
}

/**
 * Dynamically imports Leaflet, normalizing the UMD default/namespace split so callers don't
 * repeat the unwrap dance. **Must be called from a dynamic `import()` call site inside the host
 * component itself for the chunking benefit to apply** — this function is just the shared
 * unwrap logic, not a substitute for each host doing its own lazy `import('leaflet')`.
 */
export async function importLeaflet(): Promise<typeof Leaflet> {
  const imported = await import('leaflet');
  const namespace = imported as unknown as { default?: typeof Leaflet } & typeof Leaflet;
  return namespace.default ?? namespace;
}

/**
 * Injects Leaflet's own stylesheet as a runtime `<link>` rather than a component `styleUrl` —
 * see vision-web/MODULE.md Gotchas for why (the raw 14.8 kB file would trip the
 * `anyComponentStyle` budget, or grow the initial bundle if imported globally instead).
 * Idempotent: safe to call from every map component's `initMap()`.
 */
export function ensureLeafletStylesheet(): void {
  if (document.getElementById(LEAFLET_STYLESHEET_ID)) {
    return;
  }
  const link = document.createElement('link');
  link.id = LEAFLET_STYLESHEET_ID;
  link.rel = 'stylesheet';
  link.href = LEAFLET_STYLESHEET_HREF;
  document.head.appendChild(link);
}

/**
 * Builds the tile layer for `layerId` (docs/main/CYCLES-PLAN.md §9, CU-b item 6) — offline-safe (a
 * tile fetch failure just leaves the host's own dark background showing through; `onStatus`
 * reports which, so the host can show a small "tiles unavailable" badge, as `tactical-map.ts` already
 * does). Callers create a fresh instance per layer switch — swapping which `TileLayer` is
 * `addTo(map)` is how `TacticalMap` changes the active base layer, and Leaflet's own
 * attribution control follows whichever instance is currently added.
 *
 * **IndexedDB tile cache** (docs/plans/done/MVP3-PLAN.md's Build rules): every tile this layer requests goes
 * through `resolveTileSrc` below instead of a bare `img.src = url` — cache hit serves a stored
 * blob, cache miss fetches, renders, and stores it for next time. Landing here (rather than in
 * each host component) is what makes it shared infra: `TacticalMap`, `ReplayMap`, and
 * `FlightPlanDialog` all build their tile layer through this one function, so every map in the app
 * — including the Fly cockpit's map inset, which is `TacticalMap` in follow mode — inherits the cache
 * for free, exactly the plan's own "lands in C-b, so Fly/Command/replay/detail maps all inherit
 * it" requirement.
 */
export function mapLayerTileLayer(
  L: typeof Leaflet,
  layerId: MapLayerId,
  onStatus: (ok: boolean) => void,
): Leaflet.TileLayer {
  const def = mapLayerDef(layerId);
  const CachedTileLayer = cachedTileLayerClass(L);
  const tiles = new CachedTileLayer(def.url, {
    maxZoom: def.maxZoom,
    attribution: def.attribution,
    cacheLayerId: layerId,
    cacheHost: tileHost(def.url),
    tileFilter: def.tileFilter ?? '',
  } as Leaflet.TileLayerOptions);
  tiles.on('tileerror', () => onStatus(false));
  tiles.on('load', () => onStatus(true));
  return tiles;
}

/** Built once per `L` module instance (every host shares the one dynamically-imported Leaflet). */
let cachedTileLayerCtor: (new (url: string, options: Leaflet.TileLayerOptions) => Leaflet.TileLayer) | undefined;

/**
 * A `Leaflet.TileLayer` subclass whose only change is `createTile` — everything else (URL
 * templating incl. `{s}`/`{r}` substitution, zoom/bounds handling, the `load`/`tileerror` events
 * `mapLayerTileLayer` wires above) stays the stock `L.TileLayer` behavior, since only `createTile`
 * is overridden. Built via `L.TileLayer.extend(...)` (Leaflet's own subclassing helper — this
 * codebase has no other Leaflet subclass, but this is the documented way to override one method)
 * rather than a TypeScript `class extends`, since `@types/leaflet` marks `createTile` `protected`;
 * `.extend()` sidesteps that by construction (a plain object literal, not a subclass declaration).
 */
function cachedTileLayerClass(
  L: typeof Leaflet,
): new (url: string, options: Leaflet.TileLayerOptions) => Leaflet.TileLayer {
  if (!cachedTileLayerCtor) {
    cachedTileLayerCtor = (
      L.TileLayer as unknown as {
        extend(props: unknown): new (url: string, options: Leaflet.TileLayerOptions) => Leaflet.TileLayer;
      }
    ).extend({
      /**
       * The basemap's `tileFilter` lives on the map's own tile pane, not on each tile — one style
       * write per layer swap, and markers/drawings/popups (sibling panes) stay unfiltered. Set here,
       * on the one class every map host (tactical map, replay map, geofence dialog) instantiates,
       * so no host has to know a basemap can be filtered (docs/plans/active/OPERATOR-UX-6-PLAN.md M1).
       */
      onAdd(this: Leaflet.TileLayer, map: Leaflet.Map): Leaflet.TileLayer {
        const pane = map.getPane('tilePane');
        if (pane) {
          pane.style.filter = String((this.options as { tileFilter?: string }).tileFilter ?? '');
        }
        return (L.TileLayer.prototype.onAdd as (this: Leaflet.TileLayer, m: Leaflet.Map) => Leaflet.TileLayer).call(this, map);
      },
      onRemove(this: Leaflet.TileLayer, map: Leaflet.Map): Leaflet.TileLayer {
        const pane = map.getPane('tilePane');
        if (pane) {
          pane.style.filter = '';
        }
        return (L.TileLayer.prototype.onRemove as (this: Leaflet.TileLayer, m: Leaflet.Map) => Leaflet.TileLayer).call(this, map);
      },
      createTile(this: Leaflet.TileLayer, coords: Leaflet.Coords, done: Leaflet.DoneCallback): HTMLElement {
        const img = document.createElement('img');
        const url: string = (this as unknown as { getTileUrl(c: Leaflet.Coords): string }).getTileUrl(coords);
        const layerId = String((this.options as { cacheLayerId?: string }).cacheLayerId ?? '');
        const host = String((this.options as { cacheHost?: string }).cacheHost ?? '');
        const key = tileCacheKey(layerId, coords.z, coords.x, coords.y, host);
        void resolveTileSrc(key, url).then(({ src, isObjectUrl }) => {
          img.onload = () => {
            if (isObjectUrl) {
              URL.revokeObjectURL(src);
            }
            done(undefined, img);
          };
          img.onerror = () => {
            if (isObjectUrl) {
              URL.revokeObjectURL(src);
            }
            done(new Error('Tile failed to load'), img);
          };
          img.src = src;
        });
        return img;
      },
    });
  }
  return cachedTileLayerCtor;
}

interface TileSrc {
  readonly src: string;
  /** Whether `src` is a `blob:` URL this caller must `URL.revokeObjectURL` once the image has loaded. */
  readonly isObjectUrl: boolean;
}

/**
 * Cache-first tile resolution: an IndexedDB hit serves a stored blob straight away; a miss fetches
 * over the network, renders it, and stores it for next time (`putCachedTile` runs in the
 * background — the tile is already on screen by the time the write settles). Any failure along
 * that path (offline, a tile host that doesn't allow a CORS-readable `fetch`, IndexedDB unavailable)
 * falls back to the plain `<img src>` request every map used before this cache existed — preserving
 * the pre-existing offline-grid fallback (`onStatus(false)` still fires via the image's own error
 * event) for a cache-miss-while-offline, per the plan's own requirement. Tiles are only ever
 * fetched here in response to Leaflet's own `createTile` calls — panning/zooming the map the user
 * is already looking at — never a deliberate bulk prefetch, respecting the tile-server usage
 * policies the plan calls out.
 */
async function resolveTileSrc(key: string, url: string): Promise<TileSrc> {
  const cached = await getCachedTile(key);
  if (cached) {
    return { src: URL.createObjectURL(cached), isObjectUrl: true };
  }
  try {
    const response = await fetch(url, { mode: 'cors' });
    if (!response.ok) {
      throw new Error(`tile fetch failed: ${response.status}`);
    }
    const blob = await response.blob();
    void putCachedTile(key, blob);
    return { src: URL.createObjectURL(blob), isObjectUrl: true };
  } catch {
    return { src: url, isObjectUrl: false };
  }
}

/**
 * A divIcon drone marker rotated to `headingDegrees`, used by `TacticalMap` in both of its modes
 * (the single-drone follow inset and the fleet overview). `className` lets each host scope its CSS to the
 * marker root (the inner `.drone-arrow` element's geometry/color still needs a `::ng-deep` rule
 * per host component — view encapsulation can't reach DOM Leaflet renders itself, see either
 * host's `.css` file — this only shares the JS that builds the icon, not that CSS).
 */
export function droneDivIcon(
  L: typeof Leaflet,
  headingDegrees: number,
  className = 'drone-marker',
): Leaflet.DivIcon {
  return L.divIcon({
    className,
    html: `<div class="drone-arrow" style="transform: rotate(${headingDegrees}deg)"></div>`,
    iconSize: [22, 22],
    iconAnchor: [11, 11],
  });
}

/**
 * A divIcon for an asset whose heading is genuinely unknown (docs/plans/active/COMMAND-MAP-FLOW-PLAN.md
 * §3.3/W1) — a hollow, non-directional ring, same footprint as {@link droneDivIcon}'s arrow so the
 * glyph doesn't jump size when heading arrives or is lost. Deliberately a separate function rather
 * than teaching `droneDivIcon` to accept `undefined`: its two other call sites
 * (`features/replay/replay-map.ts`) always have a definite heading and are out of this wave's
 * scope, so widening that signature would touch a file this plan doesn't own. Colour/opacity are
 * resolved entirely by the host's own `::ng-deep` rule (`TacticalMap`'s freshness/attention
 * classes) — this only builds the DOM, same division of labour as `droneDivIcon`/`correctionDivIcon`.
 */
export function droneHollowDivIcon(L: typeof Leaflet, className = 'drone-marker'): Leaflet.DivIcon {
  return L.divIcon({
    className,
    html: '<div class="drone-hollow"></div>',
    iconSize: [22, 22],
    iconAnchor: [11, 11],
  });
}

/**
 * A divIcon for a visual-geolocation correction (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.8, wave
 * H6) — a hollow ring with a small heading tick, deliberately **not** {@link droneDivIcon}'s filled
 * arrow: §3.8 requires the corrected marker to read as "visually secondary to the raw one", never a
 * second "real" aircraft. `divergent` only toggles a CSS class (`nominal`/`divergent`) — the actual
 * colour is resolved by the host's own `::ng-deep` rule from `--color-info`/`--color-warn`, never
 * `--color-danger` (VISUAL-GEO-V2-PLAN.md §3.8's own "never red for divergent" — the same anti-alarm
 * discipline the divergence chip follows).
 */
export function correctionDivIcon(
  L: typeof Leaflet,
  yawDegrees: number | undefined,
  divergent: boolean,
  className = 'geo-correction-marker',
): Leaflet.DivIcon {
  const rotation = yawDegrees ?? 0;
  const tone = divergent ? 'divergent' : 'nominal';
  return L.divIcon({
    className: `${className} ${tone}`,
    // Rotating the ring div (not the tick alone) rotates both together with no per-glyph transform
    // math: a circle rotated is visually identical, so this only moves the tick — around the ring's
    // own centre, which is the CSS transform default (`transform-origin: 50% 50%`).
    html: `<div class="geo-correction-ring" style="transform: rotate(${rotation}deg)"><div class="geo-correction-tick"></div></div>`,
    iconSize: [16, 16],
    iconAnchor: [8, 8],
  });
}
