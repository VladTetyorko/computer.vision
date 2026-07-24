/**
 * Minimal IndexedDB glue behind the stable WHEP DTLS certificate (docs/REALTIME-PLAN.md Phase R-b
 * item 3). Deliberately thin and undocumented-by-spec (this codebase's own standing precedent for
 * imperative browser-API wiring — see `shared/map/tile-cache-db.ts`'s identical doc comment; jsdom, this
 * project's test environment, has no IndexedDB *or* `RTCPeerConnection.generateCertificate`
 * implementation to test against regardless). `core/webrtc-certificate.ts` is the only caller;
 * every actual *decision* (is the stored certificate still usable) lives in the pure, unit-tested
 * `core/webrtc-certificate-logic.ts` instead.
 *
 * **One record, one store** — unlike the tile cache's two-store meta/blob split (which exists to
 * avoid deserializing pixel data just to plan eviction), there is exactly one thing ever stored
 * here: the browser's single `RTCCertificate`. `RTCCertificate` objects are structured-cloneable
 * per the WebRTC spec — the spec's own intended persistence mechanism is exactly "store it via
 * IndexedDB" — so the certificate object itself is stored directly, not re-derived from raw DER
 * bytes.
 */

const DB_NAME = 'vision-webrtc-certificate';
const DB_VERSION = 1;
const STORE = 'certificate';
const RECORD_KEY = 'default';

interface StoredCertificate {
  readonly key: string;
  readonly certificate: RTCCertificate;
}

let dbPromise: Promise<IDBDatabase | null> | null = null;

/** `null` when IndexedDB isn't available at all (very old browsers, some private-mode configurations) — the caller degrades to an in-memory, per-tab certificate. */
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
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE, { keyPath: 'key' });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => resolve(null); // a blocked/broken IndexedDB degrades to a per-tab certificate, never a broken WHEP attach
  });
  return dbPromise;
}

/** The persisted certificate, if any and if IndexedDB is available at all — `undefined` otherwise. */
export async function getStoredCertificate(): Promise<RTCCertificate | undefined> {
  const db = await openDb();
  if (!db) {
    return undefined;
  }
  return new Promise((resolve) => {
    const request = db.transaction(STORE, 'readonly').objectStore(STORE).get(RECORD_KEY);
    request.onsuccess = () =>
      resolve((request.result as StoredCertificate | undefined)?.certificate);
    request.onerror = () => resolve(undefined);
  });
}

/** Persists a freshly-generated certificate, replacing whatever was stored before. Best-effort — a failed write just means the next page load regenerates again, same as a private-mode session. */
export async function putStoredCertificate(certificate: RTCCertificate): Promise<void> {
  const db = await openDb();
  if (!db) {
    return;
  }
  await new Promise<void>((resolve) => {
    const tx = db.transaction(STORE, 'readwrite');
    const record: StoredCertificate = { key: RECORD_KEY, certificate };
    tx.objectStore(STORE).put(record);
    tx.oncomplete = () => resolve();
    tx.onerror = () => resolve();
  });
}
