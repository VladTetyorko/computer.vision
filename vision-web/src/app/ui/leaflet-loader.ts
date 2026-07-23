import type * as Leaflet from 'leaflet';

/**
 * Leaflet bootstrap bits shared by every map in this app (`pages/live/live-map.ts`,
 * docs/CYCLES-PLAN.md §2; `pages/map/fleet-map.ts`, docs/CYCLES-PLAN.md §6): the dynamic import,
 * the runtime stylesheet injection, and the dark tile layer factory. Pulled out of `live-map.ts`
 * when the `/map` tab needed the identical setup — intra-app DRY (unlike the cross-adapter rule
 * in the Java side of this repo, nothing here stops two Angular pages sharing a plain module).
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

/** Class applied to the OSM tile layer's own DOM so a component's `::ng-deep` CSS filter can re-tint it dark without touching overlay panes. */
export const DARK_TILE_CLASS = 'vision-tiles';

const OSM_TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
const OSM_ATTRIBUTION =
  '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';

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
 * The dark-tinted OSM raster tile layer every map in this app uses — offline-safe (a tile fetch
 * failure just leaves the host's own dark background showing through; `onStatus` reports which
 * so the host can show a small "tiles unavailable" badge, as `live-map.ts` already does).
 */
export function darkTileLayer(L: typeof Leaflet, onStatus: (ok: boolean) => void): Leaflet.TileLayer {
  const tiles = L.tileLayer(OSM_TILE_URL, {
    maxZoom: 19,
    className: DARK_TILE_CLASS,
    attribution: OSM_ATTRIBUTION,
  });
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
