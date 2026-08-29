/**
 * Pure logic behind the map-tile cache (docs/plans/done/MVP3-PLAN.md's Build rules — "map tiles are cached
 * after first load", with the IndexedDB-over-service-worker constraint spelled out there: a
 * service worker requires a secure context, and LAN viewers use plain `http://<ip>:8080`, so
 * ngsw/SW-based caching would silently not work for exactly the field-ops users who need it most).
 * Key derivation and LRU eviction planning live here, unit-tested without touching IndexedDB
 * itself — `shared/map/tile-cache-db.ts` is the thin, documented-but-untested glue around this.
 */

/**
 * One cache key per layer+zoom/x/y (docs/plans/done/MVP3-PLAN.md: "per-layer keying incl. zoom") — the same
 * `{z}/{x}/{y}` a tile is fetched at is what the layer's `MapLayerId` (`core/settings/settings-store.ts`)
 * distinguishes: two layers can tile the same `z/x/y` slot with completely different imagery
 * (Standard vs. Satellite over the same coordinates), so the layer must be part of the key, not
 * an afterthought. The tile host is part of the key too: when a layer's source changes (Night
 * moved from Carto to OSM when Carto started returning "API KEY REQUIRED" tiles,
 * docs/plans/active/OPERATOR-UX-6-PLAN.md M1), tiles cached from the old host must never be
 * served for the new one — a stale dead tile in the cache looked exactly like the live defect.
 */
export function tileCacheKey(layerId: string, z: number, x: number, y: number, host = ''): string {
  return host ? `${layerId}@${host}/${z}/${x}/${y}` : `${layerId}/${z}/${x}/${y}`;
}

/** The host of a tile URL template — `{s}` subdomains collapse to one host so keys stay stable across shards. */
export function tileHost(urlTemplate: string): string {
  return urlTemplate.replace(/^https?:\/\//, '').replace(/^\{s\}\./, '').split('/')[0] ?? '';
}

/**
 * Byte-size cap for the whole tile cache — a size cap rather than an entry-count cap (the plan's
 * own "size cap or entry cap — pick, justify, document"): tile byte size varies a lot by layer
 * (a dark-mode PNG vs. a detailed satellite JPEG can differ by 5-10×), so a fixed entry count would
 * let one layer's cache balloon in actual disk usage while another's stayed tiny for the same
 * "number of tiles" — a byte cap bounds what field-ops devices (the whole reason this cache exists)
 * actually pay in storage, directly. **300 MB**: comfortably covers a multi-layer, multi-zoom demo
 * session (at a rough 20-40 kB/tile average across the four layers, that's ~8,000-15,000 tiles —
 * many multiples of what a single flight's viewport/zoom range ever touches) without approaching
 * browser-imposed IndexedDB origin quotas (typically a percentage of free disk, far above 300 MB on
 * any device this app targets).
 */
export const TILE_CACHE_MAX_BYTES = 300 * 1024 * 1024;

export interface TileCacheEntryMeta {
  readonly key: string;
  readonly size: number;
  readonly lastAccessedAt: number;
}

/**
 * Which cached tiles to evict — oldest-accessed first — so the cache stays at or under `capBytes`
 * once `incomingSize` bytes are added for `incomingKey`. Pure: `existing` is whatever
 * `shared/map/tile-cache-db.ts` read from its lightweight metadata store (never the blob bytes themselves —
 * eviction planning must never need to touch tile pixels to decide what to keep).
 *
 * An existing entry sharing `incomingKey` is excluded from the accounting first (a cache miss that
 * races itself into two writes for the same tile is an overwrite, not two entries) — in practice
 * `putCachedTile` is only ever called after a confirmed miss, so this is defensive, not load-bearing.
 */
export function planEviction(
  existing: readonly TileCacheEntryMeta[],
  incomingKey: string,
  incomingSize: number,
  capBytes: number = TILE_CACHE_MAX_BYTES,
): readonly string[] {
  const oldestFirst = existing
    .filter((entry) => entry.key !== incomingKey)
    .slice()
    .sort((a, b) => a.lastAccessedAt - b.lastAccessedAt);

  let total = oldestFirst.reduce((sum, entry) => sum + entry.size, 0) + incomingSize;
  const evict: string[] = [];
  for (const entry of oldestFirst) {
    if (total <= capBytes) {
      break;
    }
    evict.push(entry.key);
    total -= entry.size;
  }
  return evict;
}
