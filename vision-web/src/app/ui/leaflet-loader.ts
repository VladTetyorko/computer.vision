import type * as Leaflet from 'leaflet';
import type { MapLayerId } from '../core/settings-store';

/**
 * Leaflet bootstrap bits shared by every map in this app (`ui/live-map.ts`,
 * docs/CYCLES-PLAN.md §2; `pages/map/fleet-map.ts`, docs/CYCLES-PLAN.md §6): the dynamic import,
 * the runtime stylesheet injection, and the switchable base-layer tile factory
 * (docs/CYCLES-PLAN.md §9, CU-b item 6 — `MAP_LAYERS`/`mapLayerTileLayer`). Pulled out of
 * `live-map.ts` when the `/map` tab needed the identical setup — intra-app DRY (unlike the
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
 * One definition per switchable base layer (docs/CYCLES-PLAN.md §9, CU-b item 6): **Standard**
 * (plain OSM raster), **Night** (CARTO Dark Matter — real dark tiles, replacing the CSS `invert()`
 * filter every map used to apply unconditionally), **Relief** (OpenTopoMap, contour shading), and
 * **Satellite** (Esri World Imagery). Each carries its own attribution text, shown by Leaflet's
 * attribution control automatically whenever that layer is the one added to the map. Selection is
 * `SettingsStore.mapLayer` — persisted, one choice shared by `LiveMap` and `FleetMap` alike.
 */
export interface MapLayerDef {
  readonly id: MapLayerId;
  readonly label: string;
  readonly url: string;
  readonly attribution: string;
  readonly maxZoom: number;
}

const OSM_ATTRIBUTION =
  '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';
const CARTO_ATTRIBUTION =
  '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors ' +
  '&copy; <a href="https://carto.com/attributions">CARTO</a>';
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
    url: 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',
    attribution: CARTO_ATTRIBUTION,
    maxZoom: 20,
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
 * Builds the tile layer for `layerId` (docs/CYCLES-PLAN.md §9, CU-b item 6) — offline-safe (a
 * tile fetch failure just leaves the host's own dark background showing through; `onStatus`
 * reports which, so the host can show a small "tiles unavailable" badge, as `live-map.ts` already
 * does). Callers create a fresh instance per layer switch — swapping which `TileLayer` is
 * `addTo(map)` is how `LiveMap`/`FleetMap` change the active base layer, and Leaflet's own
 * attribution control follows whichever instance is currently added.
 */
export function mapLayerTileLayer(
  L: typeof Leaflet,
  layerId: MapLayerId,
  onStatus: (ok: boolean) => void,
): Leaflet.TileLayer {
  const def = mapLayerDef(layerId);
  const tiles = L.tileLayer(def.url, { maxZoom: def.maxZoom, attribution: def.attribution });
  tiles.on('tileerror', () => onStatus(false));
  tiles.on('load', () => onStatus(true));
  return tiles;
}

/**
 * A divIcon drone marker rotated to `headingDegrees`, shared between the single-drone `LiveMap`
 * inset and the fleet-wide `FleetMap`. `className` lets each host scope its own CSS to the
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
