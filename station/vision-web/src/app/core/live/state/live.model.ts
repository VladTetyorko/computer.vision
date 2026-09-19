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
  LiveEvent,
  MapEventPayload,
  StreamTracksResponse,
  SystemStatus,
  TelemetrySample,
} from '../../api/models';
import type { LiveConnectionState } from '../live-fallback-logic';

/** The six ref-counted per-asset topic families (docs/plans/done/NGRX-MIGRATION-PLAN.md §8) — one
 *  fewer than the seven reader accessors `LiveFacade` exposes, because `worldObjectsFor` piggybacks
 *  on the same `tracks:<assetId>` subscription `tracksFor` does (see `live-facade.ts`'s own doc
 *  comment on `worldObjectsFor` for why that one reader has no independent track/untrack pair). */
export type LiveTopicFamily = 'telemetry' | 'detections' | 'geo' | 'worldObjects' | 'cvTrace' | 'links';

/**
 * The app's one `GET /api/live` connection (docs/plans/done/NGRX-MIGRATION-PLAN.md §8), as plain
 * state — the non-serializable `EventSource` itself lives in `../live-gateway.ts#LiveGateway`
 * instead, exactly like `overlay.model.ts` keeps `HTMLElement` refs out of the `overlay` slice. Every
 * top-level field here mirrors one of the old `LiveStore`'s own signals 1:1 — see that class's
 * (deleted) doc comment, preserved in spirit by `live.effects.ts`'s own "Connection lifecycle"
 * section and `live-facade.ts`'s class doc.
 */
export interface LiveState {
  readonly connectionState: LiveConnectionState;
  /** `undefined` until the SSE `connection` handshake event names one — cleared on every close. */
  readonly connectionId: string | undefined;
  /** Per-topic subscriber counts (docs/plans/done/REALTIME-PLAN.md §4, item 2) — a topic's key is
   *  absent entirely once its count reaches zero (never a `0` entry left lying around), so
   *  `Object.keys(topicRefs)` is always exactly "every topic PATCHed onto this connection". */
  readonly topicRefs: Readonly<Record<string, number>>;

  // --- Always-on topics — one shared signal apiece, no ref-counting ------------------------------
  readonly fleet: readonly AssetSummary[] | undefined;
  readonly liveEvents: readonly LiveEvent[];
  readonly devices: DevicesSnapshot | undefined;
  readonly detectionEvents: readonly DetectionEvent[];
  readonly mapEvents: readonly MapEventPayload[];
  readonly discoveryEvents: readonly DiscoveryEventPayload[];
  readonly zoneEvents: readonly GeofenceZoneEventPayload[];
  readonly systemStatus: SystemStatus | undefined;

  // --- Ref-counted per-asset topics — keyed by assetId, entry removed on the last untrack ---------
  readonly telemetryByAssetId: Readonly<Record<string, readonly TelemetrySample[]>>;
  readonly detectionsByAssetId: Readonly<Record<string, DetectionResult | undefined>>;
  readonly geoByAssetId: Readonly<Record<string, CorrectionResponse | undefined>>;
  /** Backs both `tracksFor` (raw) and `worldObjectsFor` (derived `.objects` view) — see
   *  {@link LiveTopicFamily}'s own doc comment. */
  readonly tracksByAssetId: Readonly<Record<string, StreamTracksResponse | null>>;
  readonly cvTraceByAssetId: Readonly<Record<string, FrameLedger | undefined>>;
  readonly linksByAssetId: Readonly<Record<string, LinkGroupResponse | undefined>>;
}

export const initialLiveState: LiveState = {
  connectionState: 'connecting',
  connectionId: undefined,
  topicRefs: {},
  fleet: undefined,
  liveEvents: [],
  devices: undefined,
  detectionEvents: [],
  mapEvents: [],
  discoveryEvents: [],
  zoneEvents: [],
  systemStatus: undefined,
  telemetryByAssetId: {},
  detectionsByAssetId: {},
  geoByAssetId: {},
  tracksByAssetId: {},
  cvTraceByAssetId: {},
  linksByAssetId: {},
};

/** How many generic domain events (`LiveEvent`) `liveEvents` retains — mirrors `events-logic.ts#MAX_RETAINED_EVENTS`'s reasoning. */
export const MAX_LIVE_EVENTS = 200;

/**
 * How many `detection-events` arrivals `detectionEvents` retains — matches `LiveUpdateRegistry`'s
 * own `DETECTION_EVENT_BUFFER_CAPACITY` (vision-api), so a fresh connection's full snapshot burst
 * always fits without this slice trimming anything the server itself still considers current.
 */
export const MAX_LIVE_DETECTION_EVENTS = 300;

/**
 * How many `map` arrivals `mapEvents` retains — matches `LiveUpdateRegistry`'s own map-event buffer
 * capacity (vision-api), though unlike `detection-events` that server-side buffer is never replayed
 * to a new connection (see `MapEventPayload`'s own doc comment) — this cap just bounds this slice's
 * own in-memory arrival log for the three `core/map-data/**` stores to fold in.
 */
export const MAX_LIVE_MAP_EVENTS = 300;

/**
 * How many `discovery` arrivals `discoveryEvents` retains — a candidate inbox is a small, bursty
 * list (a handful of vehicles at once, not hundreds), so this is generous headroom rather than a
 * tuned capacity like the other topics' own buffer-matched caps.
 */
export const MAX_LIVE_DISCOVERY_EVENTS = 200;

/**
 * How many `zones` arrivals `zoneEvents` retains — mirrors `MAX_LIVE_DISCOVERY_EVENTS`'s own
 * reasoning, not a server-buffer-matched cap: an operator edits a geofence zone about as rarely as a
 * discovery candidate changes state, so this is generous headroom rather than a tuned size.
 */
export const MAX_LIVE_ZONE_EVENTS = 200;
