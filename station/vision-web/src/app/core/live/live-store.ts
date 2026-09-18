import { DestroyRef, Injectable, type Signal, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import type {
  AssetSummary,
  CorrectionResponse,
  DetectionEvent,
  DetectionResult,
  DevicesSnapshot,
  DiscoveryEventPayload,
  FrameLedger,
  GeofenceZoneEventPayload,
  LinkGroupResponse,
  LiveConnected,
  LiveEnvelope,
  LiveEvent,
  MapEventPayload,
  StreamTracksResponse,
  SystemStatus,
  TelemetrySample,
  WorldObject,
} from '../api/models';
import {
  type LiveConnectionState,
  SSE_RETRY_INTERVAL_MS,
  buildTopicsParam,
  cvTraceTopic,
  decrementTopicRef,
  detectionsTopic,
  geoTopic,
  incrementTopicRef,
  linksTopic,
  mergeTelemetrySamples,
  telemetryTopic,
  tracksTopic,
} from './live-fallback-logic';

export type { LiveConnectionState } from './live-fallback-logic';

/** Console prefix for this store's lifecycle logging — mirrors `shared/player/player.ts`'s `[player]`/`features/fly/fly.ts`'s `[fly]`. */
const LOG_PREFIX = '[live]';

/** How many generic domain events (`LiveEvent`) `liveEvents` retains — mirrors `MAX_RETAINED_EVENTS`'s reasoning. */
const MAX_LIVE_EVENTS = 200;

/**
 * How many `detection-events` arrivals `detectionEvents` retains — matches `LiveUpdateRegistry`'s
 * own `DETECTION_EVENT_BUFFER_CAPACITY` (vision-api), so a fresh connection's full snapshot burst
 * always fits without this store trimming anything the server itself still considers current.
 */
const MAX_LIVE_DETECTION_EVENTS = 300;

/**
 * How many `map` arrivals `mapEvents` retains — matches `LiveUpdateRegistry`'s own map-event buffer
 * capacity (vision-api), though unlike `detection-events` that server-side buffer is never replayed
 * to a new connection (see `MapEventPayload`'s own doc comment) — this cap just bounds this store's
 * own in-memory arrival log for the three `core/map-data/**` stores to fold in.
 */
const MAX_LIVE_MAP_EVENTS = 300;

/**
 * How many `discovery` arrivals `discoveryEvents` retains — a candidate inbox is a small, bursty
 * list (a handful of vehicles at once, not hundreds), so this is generous headroom rather than a
 * tuned capacity like the other topics' own buffer-matched caps.
 */
const MAX_LIVE_DISCOVERY_EVENTS = 200;

/**
 * How many `zones` arrivals `zoneEvents` retains — mirrors `MAX_LIVE_DISCOVERY_EVENTS`'s own
 * reasoning, not a server-buffer-matched cap: an operator edits a geofence zone about as rarely as a
 * discovery candidate changes state (a handful of creates/renames/toggles in a session, never a
 * volume feed like `detection-events`/`map`), so this is generous headroom rather than a tuned size.
 */
const MAX_LIVE_ZONE_EVENTS = 200;

/**
 * Owns the app's **one** `GET /api/live` connection (docs/plans/done/REALTIME-PLAN.md §4, Phase R-c) — the
 * server-push replacement for steady-state polling. `TelemetryStore`/`DetectionsStore` project this
 * store's per-asset signals when live, falling back to their own polling otherwise (see their own
 * doc comments and `live-fallback-logic.ts#resolveAssetScopedTransport`).
 *
 * <h2>Twelve topics now, twelve projected stores — read before wiring a new consumer</h2>
 * The backend started with four topics (`fleet`, `event`, `telemetry:<assetId>`,
 * `detections:<assetId>`) and grew three more, always-on like `fleet`/`event`: `devices` and
 * `detection-events` (docs/plans/done/REALTIME-PLAN.md §4's backend follow-up batch), then `map`
 * (docs/plans/done/MAP-REWORK-PLAN.md §4.3, which replaced the TACTICAL-MARKS wave's own `marks` topic — same
 * always-on posture, but scoped per connection and carrying layers/drawings as well as marks). All
 * four of the original plan's own stores have a matching topic:
 * - `telemetry:<assetId>` ↔ `TelemetryStore` (same domain — {@link TelemetrySample}s for one asset).
 * - `detections:<assetId>` ↔ `DetectionsStore` (same domain — the latest {@link DetectionResult}),
 *   **but keyed differently**: `DetectionsStore.track(streamId, assetId?)` still takes a
 *   `streamId` (the poll fallback's own key), with `assetId` now optional and required only to use
 *   live at all — mirrors `TelemetryStore.track(deviceId, assetId?)`'s own R-a-established split.
 * - `devices` ↔ `core/fleet/fleet-store.ts#FleetStore` — a **new**, separate topic from `fleet`, not an
 *   extension of it: `fleet`'s payload is `List<AssetSummaryResponse>` (→ {@link AssetSummary}, this
 *   file's own `fleet` signal below), asset-centric and unrelated to `FleetStore`'s own domain
 *   (`Device`/`ActiveStream` from `GET /api/devices`+`GET /api/streams`). `devices` was added
 *   specifically to give `FleetStore` something to project — see its own doc comment for the
 *   poll-vs-live toggle (no per-asset subscription needed; this topic is always-on, like `fleet`).
 * - `detection-events` ↔ `core/events/events-store.ts#EventsStore` — also a **new**, separate topic from
 *   `event`: `event`'s payload is `EventResponse` (→ {@link LiveEvent}), the domain's generic `Event`
 *   (`STREAM_STARTED`/`DEVICE_ONLINE`/`PIPELINE_ERROR`/...), genuinely different from the debounced,
 *   `OPEN`/`CLOSED`, `peakConfidence`-carrying `DetectionEvent` `GET /api/events` serves — see
 *   {@link LiveEvent}'s own doc comment for the full distinction. `detection-events` carries
 *   `DetectionEvent`s (FIFO, snapshot-on-connect oldest-first, same underlying source `GET
 *   /api/events` reads) for `EventsStore` to project, exactly like `event`/`LiveEvent` remains
 *   unconsumed (no store's domain matches it — `liveEvents` below is exposed anyway, arriving for
 *   free, for a future consumer that doesn't exist yet).
 * - `map` ↔ all three `core/map-data/**` stores (`LayersStore`/`MarksStore`/`DrawingsStore`,
 *   docs/plans/done/MAP-REWORK-PLAN.md §4.3) — the Common Operational Picture. **One topic, three consumers**:
 *   each folds in only the arrivals whose `entity` is its own, so `mapEvents()` is read by three
 *   independent `effect()`s over the same append-only log (each keeping its own processed-count
 *   cursor), rather than this store fanning it out into three signals it would then have to keep in
 *   sync. Carries {@link MapEventPayload}s (FIFO, **not** snapshot-on-connect — see that type's own
 *   doc comment); unlike every other topic here it has no poll fallback at all — every map-data
 *   store always does its own initial `GET` regardless of live availability, treats this feed purely
 *   as incremental deltas on top, and keeps a slow safety-net poll. It is also the only
 *   **per-connection-filtered** topic: the server drops events for layers this viewer may not see
 *   (§4.3), so nothing here is a client-side visibility filter.
 * - `geo:<assetId>` ↔ `core/geo/geo-store.ts#GeoStore` (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.4, wave H6) —
 *   the 8th, opt-in per-asset topic, same shape/scoping as `telemetry:<assetId>` (D11 — deliberately
 *   *not* viewer-filtered like `map`, since its raw twin `telemetry:<assetId>` isn't either).
 *   Coalescing latest-wins with ring capacity 1 server-side, so `geoSignalFor` below only ever holds
 *   the single most recent {@link CorrectionResponse} — `GeoStore`'s own poll fallback (`GET
 *   /api/geo/corrections/live`, filtered client-side to the tracked asset) is what replays history
 *   after a reconnect, exactly like `detections:<assetId>`'s "latest only, no backlog" contract.
 * - `discovery` ↔ `core/discovery/discovery-inbox-store.ts#DiscoveryInboxStore`
 *   (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.2 C4, wave W1) — the 9th topic, always-on like
 *   `map`/`devices`/`detection-events`, carrying {@link DiscoveryEventPayload}s (FIFO,
 *   **not** snapshot-on-connect — same reasoning as `map`: the poll fallback already gives a full
 *   picture, this feed is deltas only). `DiscoveryInboxStore` keeps its existing poll as the safety
 *   net and folds this log on top via the same `processedLiveEventCount` cursor idiom `MarksStore`
 *   established for `map`.
 * - `zones` ↔ `core/geofence/geofence-store.ts#GeofenceStore` (docs/plans/active/
 *   LIVE-POLL-RETIREMENT-PLAN.md §3 D2/§4.1, waves L3/L5) — the 10th topic, always-on like
 *   `discovery`, carrying {@link GeofenceZoneEventPayload}s (FIFO, **not** snapshot-on-connect —
 *   `GeofenceStore`'s own `GET /api/geofences` on `activate()` is the snapshot). `action` is
 *   `CREATED`/`UPDATED`/`DELETED`; a `DELETED` payload's `zone` is the last-known body in full, which
 *   is what lets that store's existing 10s Undo re-`POST` it. `GeofenceStore` folds this log on top
 *   of its own kept 30s poll via the identical cursor idiom, and now also gates that poll on
 *   `isLiveAvailable()` for the first time (D1) — see that store's own class doc for why its former
 *   "deliberately not gated" stance is obsolete rather than reversed.
 * - `system` ↔ `core/system-status/system-status-store.ts#SystemStatusStore` (docs/plans/active/
 *   LIVE-POLL-RETIREMENT-PLAN.md §3 D3/§4.2, waves L4/L5) — the 11th topic, always-on, but
 *   **latest-value-only** like `detections`/`geo` rather than a FIFO log: a server-side sampler
 *   broadcasts the verbatim `GET /api/system/status` body only when it actually changes, never on a
 *   fixed cadence regardless of content. Payload is {@link SystemStatus}, reused as-is — no new type
 *   needed. Unlike every other topic in this list, `system` *does* effectively arrive on connect: the
 *   sampler's first tick runs at server startup, delay 0, so its ring buffer is already populated
 *   before any connection can exist (see `systemStatus`'s own doc comment below). `SystemStatusStore`
 *   has no `activate()`/`release()` (the shell health dot needs `overall` on every page), so its own
 *   D1 gate is the live axis only — no demand axis to compose it with.
 * - `tracks:<assetId>` ↔ `core/detections/detections-store.ts#DetectionsStore` (docs/plans/active/
 *   CV-ORCHESTRATION-PLAN.md §4.6/§4.9, wave W3.1, widened wave W9 decision E25) — the 12th topic,
 *   opt-in per-asset like `telemetry:<assetId>`/`detections:<assetId>`/`geo:<assetId>` above,
 *   latest-wins with ring capacity 1 server-side (same pattern as `detections`/`geo` — no new
 *   semantics). Payload is the **whole {@link StreamTracksResponse} snapshot** — the same shape and
 *   gating rules `GET /api/streams/{id}/tracks` itself returns ("one assembly, two transports").
 *   Ref-counted via `trackWorldObjects`/`untrackWorldObjects` (names kept from wave W3.1 — still
 *   piggybacked on `DetectionsStore`'s own detections-feed subscription lifecycle, `track()`/
 *   `teardownTracking()`), projected two ways: `tracksFor` exposes the raw snapshot (read by
 *   `DetectionsStore.tracks` **only** while its own separate, demand-gated tracks lifecycle
 *   (`trackTracks`/`followTracks`) resolves to live — see that class's own doc comment for why the
 *   *subscription* here is unconditional but the *read* is gated), `worldObjectsFor` derives just
 *   the `objects` field for the box-overlay pipeline (wave W3.2), unconditionally. Before wave W9
 *   the payload was a raw `readonly {@link WorldObject}[]`, and `DetectionsStore`'s tracks poll was
 *   the *only* way to learn `stats`/`latency`/`rate`/`follow`/`lockedTrackId` — that poll is now the
 *   fallback it should always have been.
 * - `cv-trace:<assetId>` ↔ `core/cv-trace/cv-trace-store.ts#CvTraceStore` (docs/plans/active/
 *   CV-ORCHESTRATION-PLAN.md §4.4/§4.8, wave W5.1/W5.2) — the 13th topic, opt-in per-asset like
 *   `telemetry:<assetId>`/`detections:<assetId>`/`geo:<assetId>`, **not** always-on. Carries
 *   {@link FrameLedger}; this store is latest-wins for it (`cvTraceSignalFor`, same posture as
 *   `detections`/`geo`) — the capped client-side ring the engineer inspector (`/manage/cv`) reads
 *   from is `CvTraceStore`'s own job, matching the server's own `last` cap rather than this store's.
 *
 * `fleet`'s own {@link AssetSummary} polling is still done ad hoc by several pages (`fly.ts`'s own
 * picker refresh, `core/map/map-store.ts`, `asset-detail.ts`), with no single existing store class —
 * a future cycle could point one of those at this store's `fleet` signal, but none is rewired here.
 *
 * <h2>Connection lifecycle</h2>
 * `EventSource` is a browser built-in with its own native reconnect for a transient network drop
 * (`readyState` cycles `CONNECTING`→`OPEN`→...→`CONNECTING` again, re-fetching the *same* URL this
 * store constructed at `connect()` time — so any topic ref-counted **before** that drop is
 * automatically included in the reconnect for free, no action needed here). It does **not**
 * auto-retry when the server rejects the request outright (non-2xx status, or the wrong content
 * type) — the spec has the browser set `readyState` to `CLOSED` and give up permanently. That is
 * exactly what happens against a pre-R-c backend, or `vision.live.enabled=false` (`/api/live` 404s
 * like any unmapped route — docs/plans/done/REALTIME-PLAN.md §4, item 4's own note). `onerror` distinguishes
 * the two by reading `readyState` at the moment it fires: `CLOSED` means fatal — this store falls
 * back to `'closed'` and schedules its **own** retry every {@link SSE_RETRY_INTERVAL_MS} (60s,
 * since a repeatedly-404ing endpoint is not worth hammering); anything else means the browser is
 * already retrying on its own, so this store just reports `'connecting'` (degraded to polling
 * meanwhile) and otherwise stays out of the way.
 *
 * **Known, accepted gap**: a topic tracked/untracked during the few seconds of a *native* auto-retry
 * (browser-driven, transient drop) won't be reflected until the *next* full reconnect — the native
 * retry reuses the URL this store built at the *previous* successful `connect()` call, and this
 * store deliberately does not fight the browser's own backoff by forcing an immediate manual
 * reconnect for that narrow window. A topic change while `'closed'` (this store's own manual retry
 * path) **is** always picked up correctly, since `connect()` rebuilds the URL fresh from the
 * current ref-counted topics every time it runs.
 *
 * <h2>Reconnect topic restoration</h2>
 * Topics only survive server-side on the *same* connection (docs/plans/done/REALTIME-PLAN.md §4's own
 * framing) — a brand new connection (this store's own manual retry, or a fresh page load) carries
 * no memory of what the *previous* connection was subscribed to. `connect()` always rebuilds the
 * `topics` query parameter from `topicRefs`' current keys (this store's own in-memory ref-count
 * map, untouched by a reconnect), so every asset any consumer is still tracking at the moment of a
 * reconnect is re-requested from scratch — no page-level code needs to notice a reconnect at all.
 *
 * <h2>Ref-counting (docs/plans/done/REALTIME-PLAN.md §4, item 2)</h2>
 * `trackTelemetry`/`trackDetections` (and their `untrack*` pairs) are called once per *consumer*
 * (a `TelemetryStore`/`DetectionsStore` instance) — several consumers can track the same asset at
 * once (e.g. a Fly cockpit and a Wall tile both watching the same drone), and the server only needs
 * one subscription per topic per connection, so this store ref-counts locally
 * (`live-fallback-logic.ts#incrementTopicRef`/`decrementTopicRef`) and only issues a `PATCH` on the
 * first subscriber in / last unsubscriber out — an already-subscribed topic's second/third tracker
 * costs nothing server-side, and only reads the same shared per-asset signal.
 *
 * <h2>Not tested at the component level</h2>
 * jsdom has no `EventSource` at all (confirmed by grepping the installed `jsdom` package — no
 * matches), so this class is exercised only by code inspection plus `live-fallback-logic.spec.ts`'s
 * pure-logic coverage of every decision it delegates — the same "browser-API-heavy class, pure
 * logic extracted and tested, the class itself verified by inspection" precedent `shared/player/player.ts`/
 * `shared/player/webrtc-certificate.ts` already established (see their own MODULE.md Status entries). Feature
 * detection (`typeof EventSource === 'undefined'`) degrades straight to `'closed'` at construction —
 * this is also exactly what happens under jsdom, so every existing `TelemetryStore`/`DetectionsStore`
 * spec keeps exercising the polling path unmodified, with no test-side stubbing of this class needed.
 */
@Injectable({ providedIn: 'root' })
export class LiveStore {
  private readonly api = inject(VisionApi);
  private readonly available = typeof EventSource !== 'undefined';

  private readonly stateSignal = signal<LiveConnectionState>('connecting');
  readonly connectionState = this.stateSignal.asReadonly();

  private readonly fleetSignal = signal<readonly AssetSummary[] | undefined>(undefined);
  /** The latest `fleet` snapshot (always-on) — see class doc for why nothing consumes this yet. */
  readonly fleet = this.fleetSignal.asReadonly();

  private readonly liveEventsSignal = signal<readonly LiveEvent[]>([]);
  /** Generic domain events (always-on) — **not** `DetectionEvent`s; see class doc. */
  readonly liveEvents = this.liveEventsSignal.asReadonly();

  private readonly devicesSignal = signal<DevicesSnapshot | undefined>(undefined);
  /** The latest `devices` snapshot (always-on) — `core/fleet/fleet-store.ts#FleetStore`'s own projection source. */
  readonly devices = this.devicesSignal.asReadonly();

  /**
   * Every `detection-events` arrival this connection has seen, **chronological (oldest-first,
   * true FIFO append)** — deliberately not newest-first like `liveEvents` above, because the same
   * `DetectionEvent` id can arrive more than once as its `OPEN`→`CLOSED` lifecycle advances,
   * and `core/events/events-store.ts#EventsStore`'s own `mergeEvents` upserts by id, letting a
   * *later* array entry win over an *earlier* one for the same id (its own doc comment: "incoming
   * always wins"). Appending in true arrival order — and trimming overflow from the *front* — is
   * what keeps that "later wins" guarantee correct regardless of how many times this array has
   * been reprocessed; reversing the order here would silently let a stale `OPEN` snapshot entry
   * clobber a genuinely newer `CLOSED` one.
   */
  private readonly detectionEventsSignal = signal<readonly DetectionEvent[]>([]);
  /** `core/events/events-store.ts#EventsStore`'s own projection source — see this field's own doc comment above. */
  readonly detectionEvents = this.detectionEventsSignal.asReadonly();

  /**
   * Every `map` arrival this connection has seen, chronological (oldest-first, true FIFO append —
   * same reasoning as `detectionEventsSignal` above: the `core/map-data/**` stores process new
   * arrivals in order and must never see a later `deleted` clobbered by an earlier `created` for the
   * same id). Always-on, like `detection-events`, but **not** snapshot-on-connect — a fresh
   * connection starts this array empty and only accumulates deltas going forward (see
   * {@link MapEventPayload}'s own doc comment in `core/api/models.ts` for why).
   */
  private readonly mapEventsSignal = signal<readonly MapEventPayload[]>([]);
  /** The projection source shared by `LayersStore`/`MarksStore`/`DrawingsStore` — see this field's own doc comment above. */
  readonly mapEvents = this.mapEventsSignal.asReadonly();

  /**
   * Every `discovery` arrival this connection has seen, chronological (oldest-first, true FIFO
   * append — same "later must not be clobbered by earlier" reasoning as `mapEventsSignal` above).
   * Always-on (`LiveTopicKind.DISCOVERY`, docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.2 C4),
   * delta-only like `map` — no snapshot-on-connect, since the poll-fallback `GET
   * /api/discovery/inbox` already gives `DiscoveryInboxStore` a full picture on its own; this feed
   * is purely incremental candidate-lifecycle deltas (`REPORTED`/`REGISTERED`/`DISMISSED`/`RESTORED`)
   * layered on top of that store's existing poll (mirrors `MarksStore`'s own
   * `processedLiveEventCount` cursor idiom over `mapEvents`).
   */
  private readonly discoveryEventsSignal = signal<readonly DiscoveryEventPayload[]>([]);
  /** `core/discovery/discovery-inbox-store.ts#DiscoveryInboxStore`'s own projection source — see this field's own doc comment above. */
  readonly discoveryEvents = this.discoveryEventsSignal.asReadonly();

  /**
   * Every `zones` arrival this connection has seen, chronological (oldest-first, true FIFO append —
   * same "later must not be clobbered by earlier" reasoning as `discoveryEventsSignal` above).
   * Always-on (`LiveTopicKind.ZONES`, docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §4.1, wave L3),
   * delta-only like `map`/`discovery` — no snapshot-on-connect, since `GeofenceStore`'s own `GET
   * /api/geofences` (at `activate()`, and on its own D1 reconcile) already gives a full picture;
   * this feed is purely incremental `CREATED`/`UPDATED`/`DELETED` deltas layered on top.
   */
  private readonly zoneEventsSignal = signal<readonly GeofenceZoneEventPayload[]>([]);
  /** `core/geofence/geofence-store.ts#GeofenceStore`'s own projection source — see this field's own doc comment above. */
  readonly zoneEvents = this.zoneEventsSignal.asReadonly();

  /**
   * The latest `system` sample (always-on) — mirrors `devicesSignal` above: a full snapshot, never a
   * diff. `undefined` only until the very first envelope of this type ever arrives on this
   * connection, which in practice is close to immediately — the server-side sampler's ring buffer is
   * seeded at startup (delay 0), before any connection can exist, so a fresh connect's replay burst
   * carries a `system` envelope essentially right away (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md
   * §4.2, wave L4). `core/system-status/system-status-store.ts#SystemStatusStore` projects this
   * directly onto its own `status` signal rather than folding a log, since there is nothing to fold —
   * latest always wins (CLAUDE.md rule 9).
   */
  private readonly systemStatusSignal = signal<SystemStatus | undefined>(undefined);
  readonly systemStatus = this.systemStatusSignal.asReadonly();

  private readonly telemetrySignals = new Map<string, ReturnType<typeof signal<readonly TelemetrySample[]>>>();
  private readonly detectionsSignals = new Map<string, ReturnType<typeof signal<DetectionResult | undefined>>>();
  private readonly geoSignals = new Map<string, ReturnType<typeof signal<CorrectionResponse | undefined>>>();
  /** Per-asset `tracks:<assetId>` snapshots (wave W3.1, widened to the whole {@link StreamTracksResponse}
   *  wave W9, decision E25) — this store's own SSE-fed map, distinct from `DetectionsStore`'s own
   *  separate, demand-gated tracks lifecycle (see class doc). `null` before the first arrival, same
   *  "nothing measured yet, never a fabricated default" idiom as `detectionsSignals`. */
  private readonly tracksSignals = new Map<string, ReturnType<typeof signal<StreamTracksResponse | null>>>();
  /** `worldObjectsFor`'s own cached derivation of `tracksSignals`' `objects` field, one per asset —
   *  kept separate so `worldObjectsFor` returns a stable `Signal` identity across calls (mirrors the
   *  other per-asset caches in this class) rather than constructing a fresh `computed` every read. */
  private readonly worldObjectsSignals = new Map<string, Signal<readonly WorldObject[]>>();
  /** Latest-wins, like `detectionsSignals` — the capped ring an inspector reads from is
   *  `core/cv-trace/cv-trace-store.ts`'s own job (wave W5.2), not this store's. */
  private readonly cvTraceSignals = new Map<string, ReturnType<typeof signal<FrameLedger | undefined>>>();
  /** Latest-wins, like `geoSignals` — §3.4's own "payload is the whole list snapshot" rule (docs/plans/active/LINK-PAIRING-PLAN.md, wave L4). */
  private readonly linksSignals = new Map<string, ReturnType<typeof signal<LinkGroupResponse | undefined>>>();
  /** Per-topic subscriber counts (docs/plans/done/REALTIME-PLAN.md §4, item 2) — see class doc's "Ref-counting". */
  private readonly topicRefs = new Map<string, number>();

  private eventSource: EventSource | null = null;
  private connectionId: string | undefined;
  private retryHandle: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    if (this.available) {
      this.connect();
    } else {
      console.info(`${LOG_PREFIX} EventSource unavailable in this environment — staying on polling`);
      this.stateSignal.set('closed');
    }
    inject(DestroyRef).onDestroy(() => this.teardown());
  }

  /**
   * Force-closes the current connection and immediately opens a fresh one (docs/plans/active/AUTH-ROLES-PLAN.md
   * §3.7, wave W1) — `core/auth/auth-store.ts#login` calls this on every successful sign-in, so a
   * connection opened under a stale/anonymous/different-user session never lingers into the new one.
   * A no-op-then-reconnect if `EventSource` was never available in this environment at all (`available`
   * stays `false` regardless — `connect()` itself doesn't guard on it, matching the constructor's own
   * one-time check; a caller only ever reaches this from a real browser).
   */
  reconnect(): void {
    this.teardown();
    this.connect();
  }

  /**
   * Force-closes the current connection without reopening one (wave W1) — `core/auth/auth-store.ts#logout`
   * calls this once a real session ends, so a signed-out browser stops holding an authenticated SSE
   * connection open while the login screen is up. `login()`'s own subsequent `reconnect()` is what
   * opens the next one; nothing else in this store calls `connect()` again on its own after this.
   */
  stop(): void {
    this.teardown();
    this.stateSignal.set('closed');
  }

  /** The accumulated live samples for `assetId` — empty until `trackTelemetry(assetId)` is called and data arrives. */
  telemetryFor(assetId: string): Signal<readonly TelemetrySample[]> {
    return this.telemetrySignalFor(assetId);
  }

  /** The latest live detection result for `assetId` — `undefined` until one arrives. */
  detectionsFor(assetId: string): Signal<DetectionResult | undefined> {
    return this.detectionsSignalFor(assetId);
  }

  /** The latest live visual-geolocation correction for `assetId` (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.4) — `undefined` until one arrives. */
  geoFor(assetId: string): Signal<CorrectionResponse | undefined> {
    return this.geoSignalFor(assetId);
  }

  /** The world model's latest per-asset object snapshot from `tracks:<assetId>` (frame cadence),
   *  or `[]` before the first arrival / while not subscribed (wave W3.1) — derived from
   *  {@link tracksFor}'s own `objects` field (wave W9), so this keeps working unchanged regardless
   *  of that snapshot's now-wider shape. */
  worldObjectsFor(assetId: string): Signal<readonly WorldObject[]> {
    let existing = this.worldObjectsSignals.get(assetId);
    if (existing === undefined) {
      const tracks = this.tracksSignalFor(assetId);
      existing = computed(() => tracks()?.objects ?? []);
      this.worldObjectsSignals.set(assetId, existing);
    }
    return existing;
  }

  /** The whole latest per-asset `tracks:<assetId>` snapshot (frame cadence), or `null` before the
   *  first arrival / while not subscribed (wave W9, decision E25) — the same ref-counted opt-in as
   *  {@link trackWorldObjects}/{@link untrackWorldObjects} below; there is no separate track/untrack
   *  pair for this accessor, since it reads the identical subscription `worldObjectsFor` already
   *  projects a narrower view of. `DetectionsStore.tracks` reads this only while its own demand-gated
   *  tracks lifecycle resolves to the live transport — see that class's own doc comment. */
  tracksFor(assetId: string): Signal<StreamTracksResponse | null> {
    return this.tracksSignalFor(assetId);
  }

  /** The latest live frame ledger for `assetId` (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4, wave W5.1) — `undefined` until one arrives. */
  cvTraceFor(assetId: string): Signal<FrameLedger | undefined> {
    return this.cvTraceSignalFor(assetId);
  }

  /** The latest live link-group snapshot for `assetId` (docs/plans/active/LINK-PAIRING-PLAN.md §3.4, wave L4) — `undefined` until one arrives. */
  linksFor(assetId: string): Signal<LinkGroupResponse | undefined> {
    return this.linksSignalFor(assetId);
  }

  /** Ref-counted opt-in to `telemetry:<assetId>` — call once per consumer; pair with `untrackTelemetry`. */
  trackTelemetry(assetId: string): void {
    this.track(telemetryTopic(assetId));
  }

  /** The matching teardown for `trackTelemetry` — call from the consumer's own `reset()`/destroy. */
  untrackTelemetry(assetId: string): void {
    this.untrack(telemetryTopic(assetId), assetId, this.telemetrySignals);
  }

  /** Ref-counted opt-in to `detections:<assetId>` — call once per consumer; pair with `untrackDetections`. */
  trackDetections(assetId: string): void {
    this.track(detectionsTopic(assetId));
  }

  /** The matching teardown for `trackDetections` — call from the consumer's own `reset()`/destroy. */
  untrackDetections(assetId: string): void {
    this.untrack(detectionsTopic(assetId), assetId, this.detectionsSignals);
  }

  /** Ref-counted opt-in to `geo:<assetId>` — call once per consumer; pair with `untrackGeo`. */
  trackGeo(assetId: string): void {
    this.track(geoTopic(assetId));
  }

  /** The matching teardown for `trackGeo` — call from the consumer's own `reset()`/destroy. */
  untrackGeo(assetId: string): void {
    this.untrack(geoTopic(assetId), assetId, this.geoSignals);
  }

  /** Ref-counted opt-in to `tracks:<assetId>` (wave W3.1; the topic itself widened wave W9) — call
   *  once per consumer; pair with `untrackWorldObjects`. Names kept from wave W3.1 even though the
   *  subscription now backs both {@link worldObjectsFor} and {@link tracksFor} — piggybacked on
   *  `DetectionsStore`'s detections-feed lifecycle (`track()`/`teardownTracking()`), **not**
   *  `DetectionsStore`'s own separate, demand-gated tracks lifecycle (`trackTracks`/`followTracks`)
   *  — see this class's own doc comment. */
  trackWorldObjects(assetId: string): void {
    this.track(tracksTopic(assetId));
  }

  /** The matching teardown for `trackWorldObjects` — call from the consumer's own `reset()`/destroy. */
  untrackWorldObjects(assetId: string): void {
    this.untrack(tracksTopic(assetId), assetId, this.tracksSignals);
    // Not ref-counted itself (`worldObjectsFor`'s own cache is a pure derivation, safe to drop and
    // lazily recreate) — cleared here only so a retired asset id doesn't linger in this map forever.
    this.worldObjectsSignals.delete(assetId);
  }

  /** Ref-counted opt-in to `cv-trace:<assetId>` — call once per consumer; pair with `untrackCvTrace`. */
  trackCvTrace(assetId: string): void {
    this.track(cvTraceTopic(assetId));
  }

  /** The matching teardown for `trackCvTrace` — call from the consumer's own `reset()`/destroy. */
  untrackCvTrace(assetId: string): void {
    this.untrack(cvTraceTopic(assetId), assetId, this.cvTraceSignals);
  }

  /** Ref-counted opt-in to `links:<assetId>` — call once per consumer; pair with `untrackLinks`. */
  trackLinks(assetId: string): void {
    this.track(linksTopic(assetId));
  }

  /** The matching teardown for `trackLinks` — call from the consumer's own `reset()`/destroy. */
  untrackLinks(assetId: string): void {
    this.untrack(linksTopic(assetId), assetId, this.linksSignals);
  }

  private telemetrySignalFor(assetId: string): ReturnType<typeof signal<readonly TelemetrySample[]>> {
    let existing = this.telemetrySignals.get(assetId);
    if (existing === undefined) {
      existing = signal<readonly TelemetrySample[]>([]);
      this.telemetrySignals.set(assetId, existing);
    }
    return existing;
  }

  private detectionsSignalFor(assetId: string): ReturnType<typeof signal<DetectionResult | undefined>> {
    let existing = this.detectionsSignals.get(assetId);
    if (existing === undefined) {
      existing = signal<DetectionResult | undefined>(undefined);
      this.detectionsSignals.set(assetId, existing);
    }
    return existing;
  }

  private geoSignalFor(assetId: string): ReturnType<typeof signal<CorrectionResponse | undefined>> {
    let existing = this.geoSignals.get(assetId);
    if (existing === undefined) {
      existing = signal<CorrectionResponse | undefined>(undefined);
      this.geoSignals.set(assetId, existing);
    }
    return existing;
  }

  private tracksSignalFor(assetId: string): ReturnType<typeof signal<StreamTracksResponse | null>> {
    let existing = this.tracksSignals.get(assetId);
    if (existing === undefined) {
      existing = signal<StreamTracksResponse | null>(null);
      this.tracksSignals.set(assetId, existing);
    }
    return existing;
  }

  private cvTraceSignalFor(assetId: string): ReturnType<typeof signal<FrameLedger | undefined>> {
    let existing = this.cvTraceSignals.get(assetId);
    if (existing === undefined) {
      existing = signal<FrameLedger | undefined>(undefined);
      this.cvTraceSignals.set(assetId, existing);
    }
    return existing;
  }

  private linksSignalFor(assetId: string): ReturnType<typeof signal<LinkGroupResponse | undefined>> {
    let existing = this.linksSignals.get(assetId);
    if (existing === undefined) {
      existing = signal<LinkGroupResponse | undefined>(undefined);
      this.linksSignals.set(assetId, existing);
    }
    return existing;
  }

  private track(topic: string): void {
    const { count, firstSubscriber } = incrementTopicRef(this.topicRefs, topic);
    this.topicRefs.set(topic, count);
    if (!firstSubscriber) {
      return; // an existing subscriber already covers this topic server-side
    }
    console.info(`${LOG_PREFIX} track ${topic}`);
    if (this.connectionId !== undefined && this.stateSignal() === 'open') {
      void this.patchTopics({ add: [topic], remove: [] });
    }
    // Not connected right now: the next `connect()` (this store's own manual retry, or a fresh
    // page load) rebuilds its URL from `topicRefs`' current keys — see class doc.
  }

  private untrack(
    topic: string,
    assetId: string,
    signals: Map<string, ReturnType<typeof signal<unknown>>>,
  ): void {
    const { count, lastSubscriber } = decrementTopicRef(this.topicRefs, topic);
    if (count === 0) {
      this.topicRefs.delete(topic);
    } else {
      this.topicRefs.set(topic, count);
    }
    if (!lastSubscriber) {
      return; // another consumer is still tracking this asset's topic
    }
    console.info(`${LOG_PREFIX} untrack ${topic}`);
    signals.delete(assetId);
    if (this.connectionId !== undefined && this.stateSignal() === 'open') {
      void this.patchTopics({ add: [], remove: [topic] });
    }
  }

  private async patchTopics(request: { add: readonly string[]; remove: readonly string[] }): Promise<void> {
    const connectionId = this.connectionId;
    if (connectionId === undefined) {
      return;
    }
    try {
      await this.api.updateLiveTopics(connectionId, request);
    } catch (error) {
      console.warn(`${LOG_PREFIX} PATCH topics failed`, { error });
    }
  }

  private connect(): void {
    if (this.retryHandle !== null) {
      clearTimeout(this.retryHandle);
      this.retryHandle = null;
    }
    this.stateSignal.set('connecting');
    this.connectionId = undefined;
    const topicsParam = buildTopicsParam(this.topicRefs.keys());
    const url = topicsParam.length > 0 ? `/api/live?topics=${encodeURIComponent(topicsParam)}` : '/api/live';
    console.info(`${LOG_PREFIX} connecting`, { topics: topicsParam || '(none)' });

    const source = new EventSource(url);
    this.eventSource = source;
    source.addEventListener('connection', (event) => this.handleConnected(event as MessageEvent<string>));
    source.onmessage = (event) => this.handleMessage(event);
    source.onopen = () => this.handleOpen();
    source.onerror = () => this.handleError(source);
  }

  private handleOpen(): void {
    this.stateSignal.set('open');
    console.info(`${LOG_PREFIX} connected`);
  }

  private handleConnected(event: MessageEvent<string>): void {
    try {
      const payload = JSON.parse(event.data) as LiveConnected;
      this.connectionId = payload.connectionId;
      console.info(`${LOG_PREFIX} connection handshake`, { connectionId: payload.connectionId, topics: payload.topics });
    } catch (error) {
      console.warn(`${LOG_PREFIX} malformed connection event`, { error });
    }
  }

  private handleMessage(event: MessageEvent<string>): void {
    try {
      const envelope = JSON.parse(event.data) as LiveEnvelope;
      this.applyEnvelope(envelope);
    } catch (error) {
      console.warn(`${LOG_PREFIX} malformed envelope`, { error, raw: event.data });
    }
  }

  private applyEnvelope(envelope: LiveEnvelope): void {
    switch (envelope.type) {
      case 'fleet':
        this.fleetSignal.set(envelope.payload);
        return;
      case 'telemetry': {
        const target = this.telemetrySignalFor(envelope.assetId);
        target.set(mergeTelemetrySamples(target(), envelope.payload));
        return;
      }
      case 'detections':
        this.detectionsSignalFor(envelope.assetId).set(envelope.payload);
        return;
      case 'event':
        this.liveEventsSignal.update((events) => [envelope.payload, ...events].slice(0, MAX_LIVE_EVENTS));
        return;
      case 'devices':
        this.devicesSignal.set(envelope.payload);
        return;
      case 'detection-events':
        // Chronological append, trimmed from the front on overflow — see `detectionEventsSignal`'s
        // own doc comment for why this must stay oldest-first, unlike `liveEvents` above.
        this.detectionEventsSignal.update((events) =>
          [...events, envelope.payload].slice(-MAX_LIVE_DETECTION_EVENTS),
        );
        return;
      case 'map':
        // Chronological append — identical reasoning to `detection-events` above.
        this.mapEventsSignal.update((events) => [...events, envelope.payload].slice(-MAX_LIVE_MAP_EVENTS));
        return;
      case 'geo':
        // Latest-wins, like `detections` above — the server's own ring capacity 1 means this is
        // never a batch to merge, just the freshest correction replacing the last one.
        this.geoSignalFor(envelope.assetId).set(envelope.payload);
        return;
      case 'discovery':
        // Chronological append — identical reasoning to `map`/`detection-events` above.
        this.discoveryEventsSignal.update((events) =>
          [...events, envelope.payload].slice(-MAX_LIVE_DISCOVERY_EVENTS),
        );
        return;
      case 'zones':
        // Chronological append — identical reasoning to `map`/`discovery` above.
        this.zoneEventsSignal.update((events) => [...events, envelope.payload].slice(-MAX_LIVE_ZONE_EVENTS));
        return;
      case 'system':
        // Latest-wins, like `devices`/`detections` above — the server's own ring capacity 1 means
        // this is never a batch to merge, just the freshest sample replacing the last one.
        this.systemStatusSignal.set(envelope.payload);
        return;
      case 'tracks':
        // Latest-wins snapshot, like `detections`/`geo` above — server ring capacity 1, never a batch
        // to merge. Wave W9 (decision E25) widened this from a bare WorldObject[] array to the whole
        // StreamTracksResponse; `worldObjectsFor` derives its own narrower view from `.objects`.
        this.tracksSignalFor(envelope.assetId).set(envelope.payload);
        return;
      case 'cv-trace':
        // Latest-wins, like `detections`/`geo` above — `core/cv-trace/cv-trace-store.ts` (wave
        // W5.2) is what accumulates the capped ring an inspector actually reads from.
        this.cvTraceSignalFor(envelope.assetId).set(envelope.payload);
        return;
      case 'links':
        // Latest-wins snapshot — §3.4's own "payload is the whole list snapshot" rule, same posture
        // as `geo`/`cv-trace` above.
        this.linksSignalFor(envelope.assetId).set(envelope.payload);
        return;
    }
  }

  private handleError(source: EventSource): void {
    if (source.readyState === EventSource.CLOSED) {
      console.warn(
        `${LOG_PREFIX} connection unavailable — falling back to polling, retrying in ${SSE_RETRY_INTERVAL_MS / 1000}s`,
      );
      this.stateSignal.set('closed');
      this.connectionId = undefined;
      this.scheduleRetry();
    } else {
      console.info(`${LOG_PREFIX} connection dropped — browser is retrying automatically, polling meanwhile`);
      this.stateSignal.set('connecting');
    }
  }

  private scheduleRetry(): void {
    if (this.retryHandle !== null) {
      return;
    }
    this.retryHandle = setTimeout(() => {
      this.retryHandle = null;
      this.connect();
    }, SSE_RETRY_INTERVAL_MS);
  }

  private teardown(): void {
    if (this.retryHandle !== null) {
      clearTimeout(this.retryHandle);
      this.retryHandle = null;
    }
    this.eventSource?.close();
    this.eventSource = null;
  }
}
