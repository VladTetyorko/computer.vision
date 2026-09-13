import type { TelemetrySample } from '../api/models';

/**
 * Pure logic behind `core/live-store.ts` and the stores that project it (`TelemetryStore`/
 * `DetectionsStore` — docs/plans/done/REALTIME-PLAN.md §4, Phase R-c frontend) — split out so the
 * switchover/ref-counting/merge decisions are unit-testable without a real `EventSource` (jsdom has
 * none, confirmed — see `live-store.ts`'s own doc comment), mirroring this app's standing
 * `*-logic.ts`/store split (`telemetry-logic.ts`, `events-logic.ts`, ...).
 */

// --- Connection lifecycle / poll-vs-live decision ----------------------------------------------

/**
 * `LiveStore`'s own connection lifecycle, three states only:
 * - `'connecting'` — never yet reached `'open'` this attempt (initial connect, or a browser-native
 *   auto-retry in flight after a transient drop).
 * - `'open'` — the SSE `connection` handshake has been received; live data is flowing.
 * - `'closed'` — the browser gave up retrying on its own (a non-2xx status or wrong content-type —
 *   e.g. `/api/live` 404ing because `vision.live.enabled=false` or an older backend predating this
 *   route entirely); `LiveStore` schedules its own manual retry (see `SSE_RETRY_INTERVAL_MS`).
 *
 * Every consumer (`TelemetryStore`/`DetectionsStore`) falls back to polling in **both**
 * `'connecting'` and `'closed'` — there is no data to project in either case, only the *reason*
 * differs (and only matters for `LiveStore`'s own retry bookkeeping, never to a consumer).
 */
export type LiveConnectionState = 'connecting' | 'open' | 'closed';

/** Whether `LiveStore` currently has an open connection a consumer could read live data from. */
export function isLiveAvailable(state: LiveConnectionState): boolean {
  return state === 'open';
}

/**
 * How often `LiveStore` retries `GET /api/live` on its own, after the browser gave up (`'closed'`)
 * — e.g. the route 404s because `vision.live.enabled=false`, or a pre-R-c backend (docs/
 * REALTIME-PLAN.md §4's own task note: "404 → stay on polling, retry SSE occasionally"). A
 * transient network drop never reaches this path at all — the browser's own native `EventSource`
 * retry (typically ~3s) handles that without `LiveStore` intervening; see that class's own doc
 * comment for exactly which `readyState` distinguishes the two.
 */
export const SSE_RETRY_INTERVAL_MS = 60_000;

/**
 * Which transport a per-asset-scoped consumer (`TelemetryStore`/`DetectionsStore`) should use right
 * now. Live requires **both** an open connection **and** an asset id — the `telemetry:<assetId>`/
 * `detections:<assetId>` topics are asset-scoped only (docs/plans/done/REALTIME-PLAN.md §4, item 2); a caller
 * with no asset id (`LivePage`/`WallTile`, a bare `deviceId` with no asset context — see
 * `telemetry-store.ts`'s own R-a doc comment) has nothing to subscribe to regardless of whether
 * live is otherwise available, and must always poll.
 */
export type AssetScopedTransport = 'live' | 'poll';

export function resolveAssetScopedTransport(
  connectionState: LiveConnectionState,
  assetId: string | undefined,
): AssetScopedTransport {
  return assetId !== undefined && isLiveAvailable(connectionState) ? 'live' : 'poll';
}

/**
 * A stable key for "the session `track(primaryId, assetId?)` would start" — `TelemetryStore`/
 * `DetectionsStore` compare this against the key their *current* (or in-flight) session was opened
 * with, and no-op a `track()` call whose key is unchanged (docs/plans/done/REALTIME-PLAN.md §4 Phase R-c
 * follow-up — see either store's own `lastTrackKey` doc comment for why this matters beyond
 * avoiding a wasted re-fetch: re-entering `track()` re-runs its internal teardown, which reads that
 * store's own `currentAssetIdSignal` while a *caller's* effect may still be the active reactive
 * consumer, letting the later write re-notify that caller's effect for no reason it actually reads
 * changed — a tight, self-sustaining loop, not just redundant work). A plain space can't appear in
 * a `deviceId`/`streamId`/`assetId` (all server-issued UUIDs or route params), so it's a safe separator.
 */
export function trackSessionKey(primaryId: string, assetId: string | undefined): string {
  return `${primaryId} ${assetId ?? ''}`;
}

// --- Per-asset topic ref-counting (docs/plans/done/REALTIME-PLAN.md §4, item 2) --------------------------
// Several consumers can track the same asset's telemetry/detections at once (e.g. a Fly cockpit
// and a Wall tile both watching the same drone) — the server only needs one subscription per topic
// per connection, so `LiveStore` ref-counts locally and only PATCHes on the first subscriber in /
// last unsubscriber out. Pure functions over a plain `ReadonlyMap<topic, count>` snapshot; the
// class applies the returned `count` back onto its own mutable `Map` (see its own doc comment for
// why the map itself isn't reactive state).

export function telemetryTopic(assetId: string): string {
  return `telemetry:${assetId}`;
}

export function detectionsTopic(assetId: string): string {
  return `detections:${assetId}`;
}

/** `geo:<assetId>` (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.4) — opt-in, ref-counted exactly like `telemetryTopic`/`detectionsTopic` above. */
export function geoTopic(assetId: string): string {
  return `geo:${assetId}`;
}

/** `tracks:<assetId>` (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.6, wave W3.1) — opt-in, ref-counted exactly like `telemetryTopic`/`detectionsTopic`/`geoTopic` above; the world model's per-asset object mirror at frame cadence, additive to (not a replacement for) `DetectionsStore`'s existing `GET .../tracks` poll for stats/latency/rate/follow. */
export function tracksTopic(assetId: string): string {
  return `tracks:${assetId}`;
}

/**
 * `cv-trace:<assetId>` (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4/§4.8, wave W5.1) — opt-in,
 * ref-counted exactly like `telemetryTopic`/`detectionsTopic`/`geoTopic` above. Carries {@link
 * FrameLedger} arrivals; unlike `geoTopic` (server-side ring capacity 1) the server does **not**
 * cap this topic's history — `LiveStore` itself is latest-wins for this topic (mirroring
 * `detectionsTopic`), and the client-side ring the engineer inspector actually reads from is
 * `core/cv-trace/cv-trace-store.ts`'s own job (wave W5.2), not this store's.
 */
export function cvTraceTopic(assetId: string): string {
  return `cv-trace:${assetId}`;
}

export interface RefCountResult {
  /** The topic's new subscriber count. */
  readonly count: number;
  /** `true` when this call transitioned the topic from 0 → 1 subscribers — only then PATCH `add`. */
  readonly firstSubscriber: boolean;
}

export function incrementTopicRef(counts: ReadonlyMap<string, number>, topic: string): RefCountResult {
  const current = counts.get(topic) ?? 0;
  return { count: current + 1, firstSubscriber: current === 0 };
}

export interface UnrefCountResult {
  /** The topic's new subscriber count — `0` means the caller should delete the map entry entirely. */
  readonly count: number;
  /** `true` when this call transitioned the topic from 1 → 0 subscribers — only then PATCH `remove`. */
  readonly lastSubscriber: boolean;
}

export function decrementTopicRef(counts: ReadonlyMap<string, number>, topic: string): UnrefCountResult {
  const current = counts.get(topic) ?? 0;
  const next = Math.max(0, current - 1);
  return { count: next, lastSubscriber: current === 1 };
}

/** The `topics` query parameter value for `GET /api/live` — every currently ref-counted topic, comma-joined. */
export function buildTopicsParam(topics: Iterable<string>): string {
  return [...topics].join(',');
}

// --- Telemetry merge semantics (docs/plans/done/REALTIME-PLAN.md §4, item 3) -----------------------------

/**
 * How many samples `LiveStore` retains per tracked asset (bounded, generous — mirrors
 * `events-logic.ts#MAX_RETAINED_EVENTS`'s "small shared singleton, still bounded" reasoning). A
 * live session can run far longer than the 200-sample `TELEMETRY_LIMIT` a single poll ever fetched,
 * so this is deliberately larger, not copied from that constant.
 */
export const LIVE_TELEMETRY_MAX_SAMPLES = 500;

/**
 * Merges a coalesced SSE telemetry batch (`docs/plans/done/REALTIME-PLAN.md §4, item 3`: "telemetry emits
 * appended samples as deltas") into the samples already held, deduplicating by `(deviceId, at)` —
 * a resumed/re-subscribed connection can replay a `LiveRingBuffer`'s currently-buffered entries
 * (vision-api's own snapshot-on-subscribe behavior), which may overlap with samples this store
 * already has from an earlier live batch or the one-time HTTP backfill (`TelemetryStore.track`'s
 * own initial `usageTelemetry` GET) — without dedup those would render as duplicate trail points.
 * Re-sorts by `at` (ascending, chronological — matches `deriveTrail`'s own expectation) rather than
 * trusting append order, since a resume/snapshot burst is not guaranteed to arrive in the same
 * order as this store's own prior state. Trims to `maxRetained`, dropping the *oldest* first.
 */
export function mergeTelemetrySamples(
  existing: readonly TelemetrySample[],
  incoming: readonly TelemetrySample[],
  maxRetained: number = LIVE_TELEMETRY_MAX_SAMPLES,
): readonly TelemetrySample[] {
  if (incoming.length === 0) {
    return existing;
  }
  const byKey = new Map<string, TelemetrySample>();
  for (const sample of existing) {
    byKey.set(`${sample.deviceId}@${sample.at}`, sample);
  }
  for (const sample of incoming) {
    byKey.set(`${sample.deviceId}@${sample.at}`, sample);
  }
  const merged = [...byKey.values()].sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
  return merged.length > maxRetained ? merged.slice(merged.length - maxRetained) : merged;
}
