import { DestroyRef, Injectable, type Signal, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import type {
  AssetSummary,
  DetectionEvent,
  DetectionResult,
  DevicesSnapshot,
  LiveConnected,
  LiveEnvelope,
  LiveEvent,
  MarkEvent,
  TelemetrySample,
} from '../api/models';
import {
  type LiveConnectionState,
  SSE_RETRY_INTERVAL_MS,
  buildTopicsParam,
  decrementTopicRef,
  detectionsTopic,
  incrementTopicRef,
  mergeTelemetrySamples,
  telemetryTopic,
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
 * How many `marks` arrivals `markEvents` retains — matches `LiveUpdateRegistry`'s own
 * `MARKS_BUFFER_CAPACITY` (vision-api), though unlike `detection-events` that server-side buffer is
 * never replayed to a new connection (see `MarkEvent`'s own doc comment) — this cap just bounds this
 * store's own in-memory arrival log for `core/marks/marks-store.ts` to fold in.
 */
const MAX_LIVE_MARK_EVENTS = 300;

/**
 * Owns the app's **one** `GET /api/live` connection (docs/REALTIME-PLAN.md §4, Phase R-c) — the
 * server-push replacement for steady-state polling. `TelemetryStore`/`DetectionsStore` project this
 * store's per-asset signals when live, falling back to their own polling otherwise (see their own
 * doc comments and `live-fallback-logic.ts#resolveAssetScopedTransport`).
 *
 * <h2>Seven topics now, five projected stores — read before wiring a new consumer</h2>
 * The backend started with four topics (`fleet`, `event`, `telemetry:<assetId>`,
 * `detections:<assetId>`) and grew three more, always-on like `fleet`/`event`: `devices` and
 * `detection-events` (docs/REALTIME-PLAN.md §4's backend follow-up batch), then `marks`
 * (docs/TACTICAL-MARKS-PLAN.md §5). All four of the original plan's own stores have a matching topic:
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
 * - `marks` ↔ `core/marks/marks-store.ts#MarksStore` (docs/TACTICAL-MARKS-PLAN.md §5) — the shared
 *   tactical-marks operational picture. Carries {@link MarkEvent}s (`{action, mark}`, FIFO,
 *   **not** snapshot-on-connect — see that type's own doc comment) for `MarksStore` to fold into its
 *   own `GET /api/marks`-seeded list; unlike every other topic here this one has no poll fallback at
 *   all — `MarksStore` always does the initial GET regardless of live availability and treats this
 *   feed purely as incremental deltas on top, plus its own slow safety-net poll.
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
 * like any unmapped route — docs/REALTIME-PLAN.md §4, item 4's own note). `onerror` distinguishes
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
 * Topics only survive server-side on the *same* connection (docs/REALTIME-PLAN.md §4's own
 * framing) — a brand new connection (this store's own manual retry, or a fresh page load) carries
 * no memory of what the *previous* connection was subscribed to. `connect()` always rebuilds the
 * `topics` query parameter from `topicRefs`' current keys (this store's own in-memory ref-count
 * map, untouched by a reconnect), so every asset any consumer is still tracking at the moment of a
 * reconnect is re-requested from scratch — no page-level code needs to notice a reconnect at all.
 *
 * <h2>Ref-counting (docs/REALTIME-PLAN.md §4, item 2)</h2>
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
   * Every `marks` arrival this connection has seen, chronological (oldest-first, true FIFO append —
   * same reasoning as `detectionEventsSignal` above: `core/marks/marks-store.ts#MarksStore` processes
   * new arrivals in order and must never see a later `cleared` clobbered by an earlier `created` for
   * the same mark id). Always-on, like `detection-events`, but **not** snapshot-on-connect — a fresh
   * connection starts this array empty and only accumulates deltas going forward (see `MarkEvent`'s
   * own doc comment in `core/api/models.ts` for why).
   */
  private readonly markEventsSignal = signal<readonly MarkEvent[]>([]);
  /** `core/marks/marks-store.ts#MarksStore`'s own projection source — see this field's own doc comment above. */
  readonly markEvents = this.markEventsSignal.asReadonly();

  private readonly telemetrySignals = new Map<string, ReturnType<typeof signal<readonly TelemetrySample[]>>>();
  private readonly detectionsSignals = new Map<string, ReturnType<typeof signal<DetectionResult | undefined>>>();
  /** Per-topic subscriber counts (docs/REALTIME-PLAN.md §4, item 2) — see class doc's "Ref-counting". */
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

  /** The accumulated live samples for `assetId` — empty until `trackTelemetry(assetId)` is called and data arrives. */
  telemetryFor(assetId: string): Signal<readonly TelemetrySample[]> {
    return this.telemetrySignalFor(assetId);
  }

  /** The latest live detection result for `assetId` — `undefined` until one arrives. */
  detectionsFor(assetId: string): Signal<DetectionResult | undefined> {
    return this.detectionsSignalFor(assetId);
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
      case 'marks':
        // Chronological append — identical reasoning to `detection-events` above.
        this.markEventsSignal.update((events) => [...events, envelope.payload].slice(-MAX_LIVE_MARK_EVENTS));
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
