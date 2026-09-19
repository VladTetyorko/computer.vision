import { DestroyRef, Injectable, type Signal, computed, inject } from '@angular/core';
import { Store } from '@ngrx/store';
import type {
  CorrectionResponse,
  DetectionResult,
  FrameLedger,
  LinkGroupResponse,
  StreamTracksResponse,
  TelemetrySample,
  WorldObject,
} from '../api/models';
import { LivePageActions } from './state/live.actions';
import { liveFeature } from './state/live.reducer';

export type { LiveConnectionState } from './live-fallback-logic';

/**
 * Owns the app's **one** `GET /api/live` connection (docs/plans/active/NGRX-MIGRATION-PLAN.md §8,
 * replacing `LiveStore`). Signals and methods keep that class's exact names — `connectionState`,
 * `fleet`, `liveEvents`, `devices`, `detectionEvents`, `mapEvents`, `discoveryEvents`, `zoneEvents`,
 * `systemStatus`, `telemetryFor`/`detectionsFor`/`geoFor`/`worldObjectsFor`/`tracksFor`/`cvTraceFor`/
 * `linksFor`, every `trackX`/`untrackX` pair, `reconnect`, `stop` — so every one of this class's 25
 * consumer files changes only `inject(LiveStore)` → `inject(LiveFacade)` and nothing else.
 *
 * The socket itself lives in `./live-gateway.ts#LiveGateway`; the ref-counts and accumulated topic
 * payloads live in the `live` NgRx slice (`./state/live.{model,actions,reducer,effects}.ts`); this
 * class is only the read/dispatch boundary over that slice, plus the same per-asset `Signal` identity
 * caching the old class kept (a plain `Map<assetId, Signal<T>>`, entirely local bookkeeping — never
 * itself state, since a cached derived `Signal` is not a value NgRx's serializability check inspects).
 *
 * <h2>Fourteen topics now — read before wiring a new consumer</h2>
 * The backend started with four topics (`fleet`, `event`, `telemetry:<assetId>`,
 * `detections:<assetId>`) and grew ten more over several plans — `devices`, `detection-events`,
 * `map`, `geo:<assetId>`, `discovery`, `zones`, `system`, `tracks:<assetId>`, `cv-trace:<assetId>`,
 * `links:<assetId>` — see `../api/models.ts#LiveEnvelope`'s own doc comment for the full history and
 * per-topic framing (always-on vs. opt-in, snapshot-on-connect vs. delta-only, FIFO log vs.
 * latest-wins) that this class's doc comment used to carry directly. Thirteen distinct store classes
 * project at least one of them today (`TelemetryStore`, `DetectionsStore` — also `tracks`/`geo`'s own
 * `worldObjectsFor`/`geoFor` — `FleetStore`, `EventsStore`, `LayersFacade`/`MarksFacade`/`DrawingsFacade`
 * sharing `map`, `GeoStore`, `DiscoveryInboxStore`, `GeofenceFacade`, `SystemStatusStore`,
 * `CvTraceStore`, `LinksStore`); `fleet`'s own {@link AssetSummary} list and the generic `event`
 * topic (`liveEvents` below) still have no dedicated store — see `FleetFacade`/`FleetStore`'s own doc
 * comment for the former, and `liveEvents`'s own doc comment for the latter.
 *
 * <h2>Connection lifecycle</h2>
 * `EventSource` is a browser built-in with its own native reconnect for a transient network drop —
 * `LiveGateway.open`'s `'retrying'` event reports that without this class doing anything; a topic
 * ref-counted before that drop is automatically included once the browser's own reconnect completes,
 * since it re-fetches the exact URL `live.effects.ts#connectWithRetry` built at the *previous*
 * successful open. It does **not** auto-retry when the server rejects the request outright (non-2xx
 * status, or the wrong content type) — that is `LiveGateway`'s `'fatal'` event, which
 * `connectWithRetry` turns into its own {@link SSE_RETRY_INTERVAL_MS}-spaced manual retry loop. See
 * that function's own doc comment for the accepted "ref-count change during a native auto-retry"
 * gap, preserved unchanged from `LiveStore`.
 *
 * <h2>Not tested at the component level</h2>
 * jsdom has no `EventSource` at all, so `LiveGateway` itself is verified only by inspection — see its
 * own class doc. This facade and the `live` slice's reducer/effects, though, **are** fully spec'd
 * (`live.reducer.spec.ts`, `live.effects.spec.ts`, `live-facade.spec.ts`), against a hand-written fake
 * `LiveGateway`: the split this wave made is exactly what makes that possible, where the old
 * `LiveStore` class itself could only be exercised by inspection end to end.
 */
@Injectable({ providedIn: 'root' })
export class LiveFacade {
  private readonly store = inject(Store);

  readonly connectionState = this.store.selectSignal(liveFeature.selectConnectionState);
  /** The latest `fleet` snapshot (always-on) — see class doc for why nothing consumes this yet. */
  readonly fleet = this.store.selectSignal(liveFeature.selectFleet);
  /** Generic domain events (always-on) — **not** `DetectionEvent`s; see `../api/models.ts#LiveEvent`'s own doc comment. */
  readonly liveEvents = this.store.selectSignal(liveFeature.selectLiveEvents);
  /** The latest `devices` snapshot (always-on) — `core/fleet/fleet-store.ts#FleetStore`'s own projection source. */
  readonly devices = this.store.selectSignal(liveFeature.selectDevices);
  /** `core/events/events-store.ts#EventsStore`'s own projection source — chronological (oldest-first). */
  readonly detectionEvents = this.store.selectSignal(liveFeature.selectDetectionEvents);
  /** The projection source shared by `LayersFacade`/`MarksFacade`/`DrawingsFacade` — chronological (oldest-first). */
  readonly mapEvents = this.store.selectSignal(liveFeature.selectMapEvents);
  /** `core/discovery/discovery-inbox-store.ts#DiscoveryInboxStore`'s own projection source — chronological (oldest-first). */
  readonly discoveryEvents = this.store.selectSignal(liveFeature.selectDiscoveryEvents);
  /** `core/geofence/geofence-facade.ts#GeofenceFacade`'s own projection source — chronological (oldest-first). */
  readonly zoneEvents = this.store.selectSignal(liveFeature.selectZoneEvents);
  /** The latest `system` sample (always-on) — a full snapshot, never a diff. */
  readonly systemStatus = this.store.selectSignal(liveFeature.selectSystemStatus);

  private readonly telemetryByAssetId = this.store.selectSignal(liveFeature.selectTelemetryByAssetId);
  private readonly detectionsByAssetId = this.store.selectSignal(liveFeature.selectDetectionsByAssetId);
  private readonly geoByAssetId = this.store.selectSignal(liveFeature.selectGeoByAssetId);
  private readonly tracksByAssetId = this.store.selectSignal(liveFeature.selectTracksByAssetId);
  private readonly cvTraceByAssetId = this.store.selectSignal(liveFeature.selectCvTraceByAssetId);
  private readonly linksByAssetId = this.store.selectSignal(liveFeature.selectLinksByAssetId);

  private readonly telemetrySignals = new Map<string, Signal<readonly TelemetrySample[]>>();
  private readonly detectionsSignals = new Map<string, Signal<DetectionResult | undefined>>();
  private readonly geoSignals = new Map<string, Signal<CorrectionResponse | undefined>>();
  private readonly tracksSignals = new Map<string, Signal<StreamTracksResponse | null>>();
  /** `worldObjectsFor`'s own cached derivation, one per asset — kept separate so repeated calls
   *  return a stable `Signal` identity rather than a fresh `computed` every time. */
  private readonly worldObjectsSignals = new Map<string, Signal<readonly WorldObject[]>>();
  private readonly cvTraceSignals = new Map<string, Signal<FrameLedger | undefined>>();
  private readonly linksSignals = new Map<string, Signal<LinkGroupResponse | undefined>>();

  constructor() {
    this.reconnect();
    inject(DestroyRef).onDestroy(() => this.store.dispatch(LivePageActions.stopRequested()));
  }

  /**
   * Force-closes the current connection and immediately opens a fresh one (docs/plans/active/
   * AUTH-ROLES-PLAN.md §3.7) — `core/auth/state/auth.effects.ts#reconnectLiveOnSession$` calls this
   * on every successful sign-in, so a connection opened under a stale/anonymous/different-user
   * session never lingers into the new one. Also what this facade's own constructor calls once at
   * boot, mirroring `AuthFacade`'s own constructor-dispatch precedent.
   */
  reconnect(): void {
    this.store.dispatch(LivePageActions.reconnectRequested());
  }

  /**
   * Force-closes the current connection without reopening one —
   * `core/auth/state/auth.effects.ts#logoutSideEffects$` calls this once a real session ends, so a
   * signed-out browser stops holding an authenticated SSE connection open while the login screen is
   * up. `login()`'s own subsequent `reconnect()` is what opens the next one.
   */
  stop(): void {
    this.store.dispatch(LivePageActions.stopRequested());
  }

  /** The accumulated live samples for `assetId` — empty until `trackTelemetry(assetId)` is called and data arrives. */
  telemetryFor(assetId: string): Signal<readonly TelemetrySample[]> {
    return this.cached(this.telemetrySignals, assetId, () => computed(() => this.telemetryByAssetId()[assetId] ?? []));
  }

  /** The latest live detection result for `assetId` — `undefined` until one arrives. */
  detectionsFor(assetId: string): Signal<DetectionResult | undefined> {
    return this.cached(this.detectionsSignals, assetId, () => computed(() => this.detectionsByAssetId()[assetId]));
  }

  /** The latest live visual-geolocation correction for `assetId` (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.4) — `undefined` until one arrives. */
  geoFor(assetId: string): Signal<CorrectionResponse | undefined> {
    return this.cached(this.geoSignals, assetId, () => computed(() => this.geoByAssetId()[assetId]));
  }

  /** The world model's latest per-asset object snapshot from `tracks:<assetId>` (frame cadence), or
   *  `[]` before the first arrival / while not subscribed — derived from {@link tracksFor}'s own
   *  `objects` field, so this keeps working unchanged regardless of that snapshot's wider shape.
   *  **Derived, not independently writable**: there is no separate `trackTracks`/`untrackTracks` —
   *  {@link trackWorldObjects} ref-counts the one underlying `tracks:<assetId>` subscription both
   *  this and {@link tracksFor} project. */
  worldObjectsFor(assetId: string): Signal<readonly WorldObject[]> {
    return this.cached(this.worldObjectsSignals, assetId, () => computed(() => this.tracksFor(assetId)()?.objects ?? []));
  }

  /** The whole latest per-asset `tracks:<assetId>` snapshot (frame cadence), or `null` before the
   *  first arrival / while not subscribed — the same ref-counted opt-in as {@link trackWorldObjects}/
   *  {@link untrackWorldObjects}; there is no separate track/untrack pair for this accessor. */
  tracksFor(assetId: string): Signal<StreamTracksResponse | null> {
    return this.cached(this.tracksSignals, assetId, () => computed(() => this.tracksByAssetId()[assetId] ?? null));
  }

  /** The latest live frame ledger for `assetId` (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4) — `undefined` until one arrives. */
  cvTraceFor(assetId: string): Signal<FrameLedger | undefined> {
    return this.cached(this.cvTraceSignals, assetId, () => computed(() => this.cvTraceByAssetId()[assetId]));
  }

  /** The latest live link-group snapshot for `assetId` (docs/plans/active/LINK-PAIRING-PLAN.md §3.4) — `undefined` until one arrives. */
  linksFor(assetId: string): Signal<LinkGroupResponse | undefined> {
    return this.cached(this.linksSignals, assetId, () => computed(() => this.linksByAssetId()[assetId]));
  }

  /** Ref-counted opt-in to `telemetry:<assetId>` — call once per consumer; pair with `untrackTelemetry`. */
  trackTelemetry(assetId: string): void {
    this.store.dispatch(LivePageActions.telemetryTracked({ assetId }));
  }

  /** The matching teardown for `trackTelemetry` — call from the consumer's own `reset()`/destroy. */
  untrackTelemetry(assetId: string): void {
    this.store.dispatch(LivePageActions.telemetryUntracked({ assetId }));
    this.telemetrySignals.delete(assetId);
  }

  /** Ref-counted opt-in to `detections:<assetId>` — call once per consumer; pair with `untrackDetections`. */
  trackDetections(assetId: string): void {
    this.store.dispatch(LivePageActions.detectionsTracked({ assetId }));
  }

  /** The matching teardown for `trackDetections` — call from the consumer's own `reset()`/destroy. */
  untrackDetections(assetId: string): void {
    this.store.dispatch(LivePageActions.detectionsUntracked({ assetId }));
    this.detectionsSignals.delete(assetId);
  }

  /** Ref-counted opt-in to `geo:<assetId>` — call once per consumer; pair with `untrackGeo`. */
  trackGeo(assetId: string): void {
    this.store.dispatch(LivePageActions.geoTracked({ assetId }));
  }

  /** The matching teardown for `trackGeo` — call from the consumer's own `reset()`/destroy. */
  untrackGeo(assetId: string): void {
    this.store.dispatch(LivePageActions.geoUntracked({ assetId }));
    this.geoSignals.delete(assetId);
  }

  /** Ref-counted opt-in to `tracks:<assetId>` — call once per consumer; pair with `untrackWorldObjects`.
   *  Backs both {@link worldObjectsFor} and {@link tracksFor} — piggybacked on `DetectionsStore`'s
   *  detections-feed lifecycle (`track()`/`teardownTracking()`), **not** `DetectionsStore`'s own
   *  separate, demand-gated tracks lifecycle (`trackTracks`/`followTracks`). */
  trackWorldObjects(assetId: string): void {
    this.store.dispatch(LivePageActions.worldObjectsTracked({ assetId }));
  }

  /** The matching teardown for `trackWorldObjects` — call from the consumer's own `reset()`/destroy. */
  untrackWorldObjects(assetId: string): void {
    this.store.dispatch(LivePageActions.worldObjectsUntracked({ assetId }));
    this.tracksSignals.delete(assetId);
    // Not ref-counted itself (a pure derivation, safe to drop and lazily recreate) — cleared here
    // only so a retired asset id doesn't linger in this cache forever.
    this.worldObjectsSignals.delete(assetId);
  }

  /** Ref-counted opt-in to `cv-trace:<assetId>` — call once per consumer; pair with `untrackCvTrace`. */
  trackCvTrace(assetId: string): void {
    this.store.dispatch(LivePageActions.cvTraceTracked({ assetId }));
  }

  /** The matching teardown for `trackCvTrace` — call from the consumer's own `reset()`/destroy. */
  untrackCvTrace(assetId: string): void {
    this.store.dispatch(LivePageActions.cvTraceUntracked({ assetId }));
    this.cvTraceSignals.delete(assetId);
  }

  /** Ref-counted opt-in to `links:<assetId>` — call once per consumer; pair with `untrackLinks`. */
  trackLinks(assetId: string): void {
    this.store.dispatch(LivePageActions.linksTracked({ assetId }));
  }

  /** The matching teardown for `trackLinks` — call from the consumer's own `reset()`/destroy. */
  untrackLinks(assetId: string): void {
    this.store.dispatch(LivePageActions.linksUntracked({ assetId }));
    this.linksSignals.delete(assetId);
  }

  private cached<T>(cache: Map<string, Signal<T>>, key: string, create: () => Signal<T>): Signal<T> {
    let existing = cache.get(key);
    if (existing === undefined) {
      existing = create();
      cache.set(key, existing);
    }
    return existing;
  }
}
