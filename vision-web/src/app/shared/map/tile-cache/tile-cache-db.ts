import { TILE_CACHE_MAX_BYTES, planEviction, type TileCacheEntryMeta } from './tile-cache-logic';

/**
 * Minimal IndexedDB glue behind the map-tile cache (docs/plans/done/MVP3-PLAN.md's Build rules). Deliberately
 * thin and undocumented-by-spec (this codebase's own standing precedent for imperative browser-API
 * wiring — see `shared/map/live-map.ts`'s Leaflet bootstrap, `shared/player/player.ts`'s WHEP path — none of those have
 * dedicated component specs either; jsdom, this project's test environment, has no IndexedDB
 * implementation to test against regardless). The two functions below are the entire public
 * surface `shared/map/leaflet-loader.ts`'s cached tile layer calls; every actual *decision* (the cache key,
 * which entries to evict) lives in the pure, unit-tested `shared/map/tile-cache-logic.ts` instead.
 *
 * **Two object stores, not one** — `tileMeta` (key, size, lastAccessedAt — no blob) and
 * `tileBlobs` (key, blob). Eviction planning (`putCachedTile` below) only ever cursors over
 * `tileMeta`, so deciding what to evict never deserializes tile pixel data; only a confirmed
 * get/put of one specific tile ever touches `tileBlobs`.
 *
 * **No service worker, works over plain `http://`** — IndexedDB and `fetch` are both available
 * without a secure context (unlike a service worker), which is the entire reason this cache is
 * built this way instead of the "obvious" `ngsw`/SW-cache answer — see the plan's own Build rule
 * for the LAN-viewer scenario this exists for.
 */

const DB_NAME = 'vision-tile-cache';
const DB_VERSION = 1;
const META_STORE = 'tileMeta';
const BLOB_STORE = 'tileBlobs';
const LAST_ACCESSED_INDEX = 'lastAccessedAt';

interface StoredBlob {
  readonly key: string;
  readonly blob: Blob;
}

let dbPromise: Promise<IDBDatabase | null> | null = null;

/** `null` when IndexedDB isn't available at all (very old browsers) — every caller degrades to network-only. */
function openDb(): Promise<IDBDatabase | null> {
  if (dbPromise) {
    return dbPromise;
  }
  dbPromise = new Promise((resolve) => {
    if (typeof indexedDB === 'undefined') {
      resolve(null);
      return;
    }
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(META_STORE)) {
        db.createObjectStore(META_STORE, { keyPath: 'key' }).createIndex(
          LAST_ACCESSED_INDEX,
          LAST_ACCESSED_INDEX,
        );
      }
      if (!db.objectStoreNames.contains(BLOB_STORE)) {
        db.createObjectStore(BLOB_STORE, { keyPath: 'key' });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => resolve(null); // a blocked/broken IndexedDB degrades to network-only, never a broken map
  });
  return dbPromise;
}

/** Serves a cached tile's bytes and touches its LRU timestamp. `undefined` on a cache miss. */
export async function getCachedTile(key: string): Promise<Blob | undefined> {
  const db = await openDb();
  if (!db) {
    return undefined;
  }
  return new Promise((resolve) => {
    const tx = db.transaction([BLOB_STORE, META_STORE], 'readwrite');
    const blobRequest = tx.objectStore(BLOB_STORE).get(key);
    blobRequest.onsuccess = () => {
      const record = blobRequest.result as StoredBlob | undefined;
      if (!record) {
        resolve(undefined);
        return;
      }
      const meta: TileCacheEntryMeta = { key, size: record.blob.size, lastAccessedAt: Date.now() };
      tx.objectStore(META_STORE).put(meta);
      resolve(record.blob);
    };
    blobRequest.onerror = () => resolve(undefined);
  });
}

/** Stores a freshly-fetched tile, evicting the least-recently-accessed entries first if now over the cap. */
export async function putCachedTile(
  key: string,
  blob: Blob,
  capBytes: number = TILE_CACHE_MAX_BYTES,
): Promise<void> {
  const db = await openDb();
  if (!db) {
    return;
  }
  const existing = await readAllMeta(db);
  const evictKeys = planEviction(existing, key, blob.size, capBytes);
  await new Promise<void>((resolve) => {
    const tx = db.transaction([BLOB_STORE, META_STORE], 'readwrite');
    const metaStore = tx.objectStore(META_STORE);
    const blobStore = tx.objectStore(BLOB_STORE);
    for (const evictKey of evictKeys) {
      metaStore.delete(evictKey);
      blobStore.delete(evictKey);
    }
    const record: StoredBlob = { key, blob };
    const meta: TileCacheEntryMeta = { key, size: blob.size, lastAccessedAt: Date.now() };
    blobStore.put(record);
    metaStore.put(meta);
    tx.oncomplete = () => resolve();
    tx.onerror = () => resolve(); // best-effort — a failed write just means this tile isn't cached yet
  });
}

function readAllMeta(db: IDBDatabase): Promise<TileCacheEntryMeta[]> {
  return new Promise((resolve) => {
    const entries: TileCacheEntryMeta[] = [];
    const tx = db.transaction(META_STORE, 'readonly');
    const request = tx.objectStore(META_STORE).openCursor();
    request.onsuccess = () => {
      const cursor = request.result;
      if (cursor) {
        entries.push(cursor.value as TileCacheEntryMeta);
        cursor.continue();
      } else {
        resolve(entries);
      }
    };
    request.onerror = () => resolve(entries);
  });
}
