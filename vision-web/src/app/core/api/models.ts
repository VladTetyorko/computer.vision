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
 * Mirrors `domain.model.LifecycleState` as surfaced by `dto.DeviceResponse#state`.
 * A deactivated device refuses to stream.
 */
export type DeviceState = 'ACTIVE' | 'DEACTIVATED';

/**
 * Mirrors `dto.DeviceResponse`.
 *
 * There is no `type` field: the `DeviceType` enum was removed server-side in favor of the
 * data-driven category model, which applies to `Asset`s, not raw devices (see `Category`,
 * `AssetSummary`).
 */
export interface Device {
  readonly id: string;
  readonly name: string;
  readonly capabilities: readonly Capability[];
  readonly protocol: string;
  readonly uri: string;
  readonly options: Record<string, string>;
  readonly state: DeviceState;
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

/** Mirrors `dto.ActiveStreamResponse`. `viewUrl` is absent when no publisher is wired. */
export interface ActiveStream {
  readonly streamId: string;
  readonly deviceId: string;
  readonly startedAt: string;
  readonly viewUrl?: string;
}

/** Mirrors `dto.StartStreamRequest` — both fields fall back to `PipelineConfig.defaults()`. */
export interface StartStreamRequest {
  readonly confidenceThreshold?: number;
  readonly inferenceFps?: number;
}

/** Mirrors `dto.StartStreamResponse`. */
export interface StartStreamResult {
  readonly streamId: string;
  readonly viewUrl?: string;
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

/**
 * Mirrors `domain.model.LifecycleState` as surfaced by `dto.AssetSummaryResponse#state` /
 * `dto.AssetDetailsResponse#state`. A separate axis from `AssetStatus`: "idle right now" and
 * "withdrawn from service" are different facts. Unlike `DeviceState`, assets can also be
 * `DELETED` (a soft delete — `GET /api/assets` excludes these unless queried otherwise, which
 * this app never does).
 */
export type LifecycleState = 'ACTIVE' | 'DEACTIVATED' | 'DELETED';

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
 * Mirrors `dto.TelemetrySampleResponse`. Every field except `at` is absent when the underlying
 * sample did not carry that reading — not every device reports every field.
 */
export interface TelemetrySample {
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
 */
export interface AssetSummary {
  readonly assetId: string;
  readonly displayName: string;
  readonly category: string;
  readonly categoryName: string;
  readonly owner: string;
  readonly status: AssetStatus;
  readonly state: LifecycleState;
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
 * Mirrors `dto.StartSimulationRequest` (docs/CYCLES-PLAN.md §1c, §3) — the one-call,
 * zero-hardware "simulate a source" entry point. Optional fields are omitted, never sent as
 * `null`, matching every other request DTO here; the backend's own defaults then apply
 * (`autoStart` → `true`, `transport` → `"direct"`).
 *
 * `transport` is matched case-insensitively server-side, but this app always sends the fixed
 * lowercase values: `"direct"` (in-process playback) or `"rtsp"` (pushed over the wire and
 * ingested back, docs/CYCLES-PLAN.md §3 — rehearses the full protocol path).
 */
export interface StartSimulationRequest {
  readonly displayName?: string;
  readonly videoPath: string;
  readonly latitude?: number;
  readonly longitude?: number;
  readonly autoStart?: boolean;
  readonly transport?: 'direct' | 'rtsp';
}

/**
 * Mirrors `dto.SimulationResponse`. `streamId`/`viewUrl` are absent when the simulation was not
 * auto-started, or — for `viewUrl` — when the active publisher has no viewing endpoint.
 */
export interface SimulationResponse {
  readonly assetId: string;
  readonly streamId?: string;
  readonly viewUrl?: string;
}
