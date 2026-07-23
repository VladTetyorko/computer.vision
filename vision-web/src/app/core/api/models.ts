/**
 * Wire types, mirroring `com.drones.vision.api.dto` one-for-one.
 *
 * Keep this file in lockstep with the Java DTOs — it is the single place the
 * frontend encodes the REST contract.
 */

/**
 * Mirrors `domain.model.Capability`. Drives which panels a device renders.
 *
 * Sent case-insensitively; `CapabilityParsing` on the backend accepts any casing, but
 * responses always echo the enum's `name()`, hence the fixed casing here.
 */
export type Capability = 'VIDEO' | 'TELEMETRY' | 'PTZ' | 'AUDIO';

/**
 * Mirrors `domain.model.LifecycleState` as surfaced by `dto.DeviceResponse#state` /
 * `dto.AssetSummaryResponse#lifecycle` (docs/CYCLES-PLAN.md §8) — one axis shared by both
 * devices and assets. A deactivated device/asset refuses to stream; `DELETED` is a soft
 * delete/archive (hidden from default listings, recoverable via restore — see
 * `SettableLifecycleState`).
 */
export type LifecycleState = 'ACTIVE' | 'DEACTIVATED' | 'DELETED';

/**
 * The only two states a client may request via `POST .../state` (docs/CYCLES-PLAN.md §8's
 * pinned contract) — `DELETED` is reached only via the `DELETE` (archive) endpoints, and
 * `DEACTIVATED` on an already-`DELETED` thing is how the contract spells "restore" (there is no
 * direct `DELETED` → `ACTIVE` transition).
 */
export type SettableLifecycleState = Exclude<LifecycleState, 'DELETED'>;

/** Mirrors the `{state}` body every `POST .../state` endpoint takes (docs/CYCLES-PLAN.md §8). */
export interface SetLifecycleStateRequest {
  readonly state: SettableLifecycleState;
}

/**
 * Mirrors `dto.DeviceResponse`.
 *
 * There is no `type` field: the `DeviceType` enum was removed server-side in favor of the
 * data-driven category model, which applies to `Asset`s, not raw devices (see `Category`,
 * `AssetSummary`). `state` can now be `DELETED` too (docs/CYCLES-PLAN.md §8 — a device can be
 * archived, e.g. as the last source of an asset, without the asset itself going away).
 */
export interface Device {
  readonly id: string;
  readonly name: string;
  readonly capabilities: readonly Capability[];
  readonly protocol: string;
  readonly uri: string;
  readonly options: Record<string, string>;
  readonly state: LifecycleState;
}

/**
 * Mirrors `PATCH /api/devices/{id}`'s body (docs/CYCLES-PLAN.md §8's pinned contract): every
 * field optional, `@JsonInclude(NON_NULL)`-style — send only what actually changed. The backend
 * requires `protocol`+`uri` together whenever either (or `options`) is present; the request
 * builder in `pages/devices/warehouse-logic.ts` only ever populates `name` today (the UI's
 * "Rename" action), but the type mirrors the full pinned shape for API-layer completeness.
 */
export interface DeviceEdit {
  readonly name?: string;
  readonly protocol?: string;
  readonly uri?: string;
  readonly options?: Record<string, string>;
  readonly capabilities?: readonly Capability[];
}

/**
 * Mirrors `dto.RegisterDeviceRequest`. `capabilities` is optional; a missing/empty value
 * defaults server-side to `[VIDEO]`. No `type` field — see `Device`.
 */
export interface RegisterDeviceRequest {
  readonly name: string;
  readonly protocol: string;
  readonly uri: string;
  readonly options?: Record<string, string>;
  readonly capabilities?: readonly Capability[];
}

/**
 * Mirrors `dto.ActiveStreamResponse`. `viewUrl`/`whepUrl` are each independently absent when the
 * active publisher has no such viewing endpoint wired.
 *
 * `whepUrl` (docs/MVP2-PLAN.md §L, L-a) is mediamtx's own **absolute origin URL** for the WebRTC
 * (WHEP) viewing endpoint — unlike `viewUrl`, which can be app-relative (`HlsProxyController`
 * reverse-proxies HLS byte fetches), WHEP is a POST/SDP + ICE exchange a stateless proxy cannot
 * forward, so this is never app-relative and must never be proxied — POST straight to it.
 */
export interface ActiveStream {
  readonly streamId: string;
  readonly deviceId: string;
  readonly startedAt: string;
  readonly viewUrl?: string;
  readonly whepUrl?: string;
}

/** Mirrors `dto.StartStreamRequest` — both fields fall back to `PipelineConfig.defaults()`. */
export interface StartStreamRequest {
  readonly confidenceThreshold?: number;
  readonly inferenceFps?: number;
}

/** Mirrors `dto.StartStreamResponse`. `whepUrl` follows the same absolute-origin rule as `ActiveStream#whepUrl`. */
export interface StartStreamResult {
  readonly streamId: string;
  readonly viewUrl?: string;
  readonly whepUrl?: string;
}

/**
 * Mirrors `dto.DiscoveredDeviceResponse`. A candidate, not yet a device.
 *
 * `suggestedCategory` is a category slug (e.g. `"drone"`), not a `DeviceType` — categories
 * apply to `Asset`s (see `Device`'s doc comment), but discovery still offers a best-guess one
 * as a hint for whichever asset the user eventually files this device under.
 */
export interface DiscoveredDevice {
  readonly method: string;
  readonly name: string;
  readonly address: string;
  readonly suggestedCategory?: string;
  readonly protocol?: string;
  readonly uri?: string;
  readonly details: Record<string, string>;
}

/** Mirrors `dto.ScanRequestDto`. */
export interface ScanRequest {
  readonly timeoutMs?: number;
  readonly methods?: string[];
}

/** Mirrors `dto.ScanResultResponse`. `failedMethods` is surfaced, never swallowed. */
export interface ScanResult {
  readonly devices: readonly DiscoveredDevice[];
  readonly failedMethods: readonly string[];
}

/** Mirrors `dto.ErrorResponse`, produced by `ApiExceptionHandler`. */
export interface ApiErrorBody {
  readonly error: string;
  readonly message: string;
}

/** Mirrors `application.AssetStatus`, as surfaced by `dto.AssetSummaryResponse#status`. */
export type AssetStatus = 'OFFLINE' | 'STREAMING';

/**
 * Mirrors `dto.GeoPositionResponse`. `altitudeMeters` is absent when the position carries no
 * altitude reading.
 */
export interface GeoPosition {
  readonly latitude: number;
  readonly longitude: number;
  readonly altitudeMeters?: number;
}

/**
 * Mirrors `dto.CategoryResponse`. `parent` is absent for a top-level category.
 * `attributeHints` are UI suggestions, not a rigid schema.
 */
export interface Category {
  readonly slug: string;
  readonly name: string;
  readonly parent?: string;
  readonly attributeHints: readonly string[];
}

/**
 * Mirrors `dto.TelemetrySampleResponse`. Every field except `deviceId`/`at` is absent when the
 * underlying sample did not carry that reading — not every device reports every field.
 *
 * `deviceId` (docs/CYCLES-PLAN.md §11, CD-a) is never absent — every `Telemetry` sample carries
 * the telemetry device it came from, which is what makes multi-telemetry grouping possible (an
 * asset's usage can mix samples from more than one TELEMETRY-capable device; see
 * `pages/asset-detail/asset-detail-logic.ts#groupTelemetryByDevice`).
 */
export interface TelemetrySample {
  readonly deviceId: string;
  readonly at: string;
  readonly latitude?: number;
  readonly longitude?: number;
  readonly altitudeMeters?: number;
  readonly headingDegrees?: number;
  readonly batteryPercent?: number;
}

/**
 * Mirrors `dto.AssetUsageResponse`, embedded in `AssetDetails#recentUsages`. `endedAt` absent
 * means the usage is still open — this is how the telemetry store finds the usage to poll.
 */
export interface AssetUsage {
  readonly usageId: string;
  readonly startedAt: string;
  readonly endedAt?: string;
  readonly startPosition?: GeoPosition;
  readonly lastPosition?: GeoPosition;
  readonly sampleCount: number;
}

/**
 * Mirrors `dto.AssetSummaryResponse`, the shared field set `AssetDetails` extends. `lastUsedAt`
 * and `lastKnownPosition` are absent for an asset that has never been used.
 *
 * `lifecycle` mirrors `AssetSummaryResponse#lifecycle` (docs/CYCLES-PLAN.md §8's pinned contract
 * — CW-a exposes the asset's lifecycle state under this name, alongside the derived streaming
 * `status`). It is typed optional rather than required: CW-a lands this field server-side in
 * parallel with this UI, so a backend this app talks to before that ships simply omits it —
 * every reader here treats an absent `lifecycle` as `'ACTIVE'` (see
 * `pages/devices/warehouse-logic.ts`), never as a crash.
 */
export interface AssetSummary {
  readonly assetId: string;
  readonly displayName: string;
  readonly category: string;
  readonly categoryName: string;
  readonly owner: string;
  readonly status: AssetStatus;
  readonly lifecycle?: LifecycleState;
  readonly lastUsedAt?: string;
  readonly lastKnownPosition?: GeoPosition;
  readonly attributes: Record<string, string>;
}

/**
 * Mirrors `dto.AssetDetailsResponse`: `AssetSummary`'s fields plus the asset's resolved devices
 * and recent usage history (newest first).
 */
export interface AssetDetails extends AssetSummary {
  readonly devices: readonly Device[];
  readonly recentUsages: readonly AssetUsage[];
}

/**
 * Mirrors `PATCH /api/assets/{id}`'s body (docs/CYCLES-PLAN.md §8's pinned contract): every field
 * optional — send only what actually changed. Built by
 * `pages/devices/warehouse-logic.ts#buildAssetEdit`.
 */
export interface AssetEdit {
  readonly displayName?: string;
  readonly category?: string;
  readonly attributes?: Record<string, string>;
}

/** Mirrors `POST /api/assets/{id}/devices`'s body (docs/CYCLES-PLAN.md §8's pinned contract). */
export interface AssignDeviceRequest {
  readonly deviceId: string;
}

/**
 * Mirrors `dto.AssetDeletionResponse` (docs/CYCLES-PLAN.md §8's pinned contract), the body of
 * `DELETE /api/assets/{id}` — from `application.AssetDeletion`. Told to the user verbatim so an
 * archive confirmation says what survived, not just that the asset is gone.
 */
export interface AssetDeletionResponse {
  readonly assetId: string;
  readonly displayName: string;
  readonly devicesDeleted: number;
  readonly usagesRetained: number;
  readonly streamsStopped: number;
}

/**
 * Mirrors `dto.StartSimulationRequest.RouteMode` values (docs/CYCLES-PLAN.md §7, CT-a's
 * `application.RouteMode`), matched case-insensitively server-side but always sent lowercase here.
 * `loop` (default, end→start closing leg) / `bounce` (retrace backwards) / `once` (hold at the end
 * waypoint, still emitting).
 */
export type RouteMode = 'loop' | 'bounce' | 'once';

/**
 * Mirrors `dto.StartSimulationRequest.WaypointRequest` — one checkpoint on a flight plan's route.
 * `altitudeMeters` omitted (not `null`) when this checkpoint carries none.
 */
export interface WaypointRequest {
  readonly latitude: number;
  readonly longitude: number;
  readonly altitudeMeters?: number;
}

/**
 * Mirrors `dto.StartSimulationRequest.TelemetryRequest` (docs/CYCLES-PLAN.md §7, CT-a's pinned
 * contract) — a configurable flight plan replacing the bare circular home-point track. `route`
 * must carry at least 2 waypoints (`ui/flight-plan-logic.ts#canSavePlan`/`buildTelemetryRequest`
 * enforce this client-side before a request is ever built); `speedMps`/`routeMode` omitted defer
 * to the adapter's own defaults (`12.0`/`"loop"`).
 */
export interface TelemetryPlanRequest {
  readonly speedMps?: number;
  readonly routeMode?: RouteMode;
  readonly route: readonly WaypointRequest[];
}

/**
 * Mirrors `dto.StartSimulationRequest` (docs/CYCLES-PLAN.md §1c, §3, §7, §9). Optional fields are
 * omitted, never sent as `null`, matching every other request DTO here; the backend's own
 * defaults then apply (`autoStart` → `true`, `transport` → `"direct"`).
 *
 * `videoPath` is optional (docs/CYCLES-PLAN.md §9, CU-a): omitting it entirely registers a fully
 * synthetic VIDEO+TELEMETRY device instead of a `file`-backed one — a moving test drone with no
 * video file at all (`pages/devices/simulate-logic.ts#buildTestDroneRequest`). A `null`/absent
 * `videoPath` requires `transport` to stay `"direct"` (the default) — the backend 400s otherwise,
 * since `"rtsp"`/`"mjpeg"` have no in-process renderer output to push over the wire.
 *
 * `transport` is matched case-insensitively server-side, but this app always sends the fixed
 * lowercase values: `"direct"` (in-process playback) or `"rtsp"` (pushed over the wire and
 * ingested back, docs/CYCLES-PLAN.md §3 — rehearses the full protocol path).
 *
 * `telemetry` (docs/CYCLES-PLAN.md §7, CT-a/CT-b) is an optional flight plan replacing the bare
 * `latitude`/`longitude` circular home-point track; when present, `latitude`/`longitude` are
 * still accepted but ignored server-side (`StartSimulationRequest`'s own Javadoc) —
 * `ui/flight-plan-dialog.ts` is the UI that builds this field.
 */
export interface StartSimulationRequest {
  readonly displayName?: string;
  readonly videoPath?: string;
  readonly latitude?: number;
  readonly longitude?: number;
  readonly autoStart?: boolean;
  readonly transport?: 'direct' | 'rtsp';
  readonly telemetry?: TelemetryPlanRequest;
}

/**
 * Mirrors `dto.SimulationResponse`. `streamId`/`viewUrl`/`whepUrl` are absent when the simulation
 * was not auto-started, or — for `viewUrl`/`whepUrl`, each independently — when the active
 * publisher has no such viewing endpoint (see `ActiveStream#whepUrl`'s doc comment for the
 * absolute-origin/never-proxied rule `whepUrl` follows here too).
 */
export interface SimulationResponse {
  readonly assetId: string;
  readonly streamId?: string;
  readonly viewUrl?: string;
  readonly whepUrl?: string;
}

/** Mirrors `dto.BoundingBoxResponse`. Each component is normalized [0,1] against frame dimensions. */
export interface BoundingBox {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

/** Mirrors `dto.DetectionResponse`, embedded in `DetectionResult#detections`. */
export interface Detection {
  readonly label: string;
  readonly confidence: number;
  readonly box: BoundingBox;
  readonly modelId: string;
  readonly modelVersion: string;
}

/**
 * Mirrors `dto.DetectionResultResponse`, the body element of `GET
 * /api/streams/{streamId}/detections` (docs/MVP1-PLAN.md §C8 bullet 3) — one completed inference
 * result. Backs the Live page's detections strip (`core/detections-store.ts`).
 */
export interface DetectionResult {
  readonly streamId: string;
  readonly frameSequence: number;
  readonly capturedAt: string;
  readonly inferenceMillis: number;
  readonly detections: readonly Detection[];
}

/**
 * Mirrors `dto.UsageTimelineResponse`, the body of `GET /api/usages/{usageId}/timeline`
 * (docs/MVP2-PLAN.md §R, R-a/R-a2 — flight replay). No `NON_NULL`-style optionality: `from`/`to`
 * are always resolved server-side (defaulted from the usage's own `startedAt`/`endedAt`, or "now"
 * for an open usage's `to`), and both list fields are always present, possibly empty.
 *
 * `telemetry` is ascending by `at`, downsampled to the request's `maxPoints` (equidistant
 * thinning that always keeps the first/last sample — `ReplayService`, vision-application).
 * `detections` is ascending by `capturedAt`; real for usages opened after R-a2's
 * `AssetUsage.streamId` link, honestly `[]` for a legacy or streamless usage — see
 * `pages/replay/replay.ts`'s empty-state handling.
 */
export interface UsageTimeline {
  readonly usage: AssetUsage;
  readonly from: string;
  readonly to: string;
  readonly telemetry: readonly TelemetrySample[];
  readonly detections: readonly DetectionResult[];
}

/** Mirrors `domain.model.DetectionEventState` as surfaced by `dto.DetectionEventResponse#state`. */
export type DetectionEventState = 'OPEN' | 'CLOSED';

/**
 * Mirrors `dto.DetectionEventResponse`, the body element of `GET /api/events` /
 * `GET /api/streams/{streamId}/events` (docs/MVP2-PLAN.md §E, E-a/E-b) — one debounced detection
 * event ("a person was seen for a while"), distinct from the raw per-frame `DetectionResult` the
 * Live page's chip strip already reads.
 *
 * `assetId` is absent when the owning asset couldn't be resolved (a device with no asset, or one
 * whose asset had no open usage yet at open time — see the Java doc comment this mirrors).
 * `position` is absent when no telemetry was available at open time; it is stamped once, at open,
 * and never updated afterward even if the event stays open for a while (again mirroring the
 * backend's own documented behavior) — a marker plotted from it is "where it started", not "where
 * it is now".
 *
 * **No pipeline-error events here.** `EventPublisherPort`'s generic `Event`s (`PIPELINE_ERROR`
 * etc., the docs/MVP2-PLAN.md §U-info ask) are not exposed by this API — E-a's own MODULE.md
 * documents that port as fire-and-forget/write-only with no read side at all. `core/events-store.ts`
 * and every page reading it are detection events only, labeled as such; the player state chip
 * (docs/CYCLES-PLAN.md §11 item 5 / docs/MVP2-PLAN.md §V, V-b) stays the connectivity surface.
 */
export interface DetectionEvent {
  readonly id: string;
  readonly streamId: string;
  readonly assetId?: string;
  readonly label: string;
  readonly peakConfidence: number;
  readonly firstSeen: string;
  readonly lastSeen: string;
  readonly state: DetectionEventState;
  readonly position?: GeoPosition;
}
