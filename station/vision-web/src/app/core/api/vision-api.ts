import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type {
  ActiveStream,
  AfterActionManifest,
  AssetDeletionResponse,
  AssetDetails,
  AssetEdit,
  AssetStats,
  AssetSummary,
  AssetUsage,
  AssignedPilot,
  Assignment,
  AssignmentRole,
  AuditEntry,
  AdminSetPasswordRequest,
  BindingScope,
  BootstrapRequest,
  BootstrapStatusResponse,
  CalibrateCameraPoseRequest,
  ChangePasswordRequest,
  CalibrationResult,
  CameraPoseRequest,
  CameraPoseResponse,
  Category,
  CorrectionsResponse,
  CreateAssetRequest,
  CreateCategoryRequest,
  CreateDatasetRequest,
  CreateGroupRequest,
  CreateDrawingRequest,
  CreateLayerRequest,
  CreateMaintenanceRecordRequest,
  CreateMarkRequest,
  CreateUserRequest,
  CustodyActionRequest,
  CvCoverageResponse,
  CvModelsResponse,
  CvProfile,
  CvProfileBinding,
  CvProfileBindingRequest,
  CvProfileRequest,
  CvProfilesResponse,
  CvTrackersResponse,
  Dataset,
  DatasetsResponse,
  DetectionEvent,
  DetectionResult,
  AttachDiscoveryCandidateRequest,
  Device,
  DeviceEdit,
  DiscoveryCandidate,
  DiscoveryInboxResponse,
  DiscoveryStatusResponse,
  EffectiveCvProfile,
  FleetMaintenanceRecord,
  FleetReadiness,
  FleetSummary,
  AuxFunctionRequest,
  ControlCatalog,
  ControlProfile,
  CreateControlProfileRequest,
  FlightCapability,
  FlightCommandResponse,
  UpdateControlProfileRequest,
  GeofenceZone,
  GeofenceZoneRequest,
  GeolocateMarkRequest,
  GroupSummary,
  InventoryActionRequest,
  LabelAnnotationsRequest,
  LiveSubscription,
  MaintenanceListState,
  MaintenanceRecord,
  MapDrawingResponse,
  MapLayer,
  MapMark,
  MapTracksResponse,
  MeResponse,
  ParameterWriteRequest,
  ParameterWriteResponse,
  PatchDrawingRequest,
  PatchMarkRequest,
  PromoteMarkRequest,
  PatchStreamConfigResponse,
  ProbeCandidateRequest,
  ProbeDeviceRequest,
  ProbeDeviceResult,
  PromoteModelRequest,
  PromotionResultResponse,
  ReadinessReport,
  RegionIngestRequest,
  RegionProgressResponse,
  RegionResponse,
  RegionsResponse,
  RegisterDeviceRequest,
  RegisterDiscoveryCandidateRequest,
  RegisterDiscoveryCandidateResponse,
  RemediationRequest,
  RemediationResult,
  OpsThresholdsResponse,
  RenameLayerRequest,
  ReturnHomeResponse,
  SampleStatus,
  SamplesResponse,
  ScanRequest,
  ScanResult,
  SeatKind,
  SeatsResponse,
  SetLayerGrantsRequest,
  SetMembershipsRequest,
  SettableLifecycleState,
  SimulationResponse,
  StartSimulationRequest,
  StartStreamRequest,
  StartStreamResult,
  StartTrainingJobRequest,
  StreamConfigResponse,
  StreamTracksResponse,
  SystemNetworkResponse,
  SystemStatus,
  TakeSeatRequest,
  TelemetrySample,
  TrainingJobResponse,
  TrainingJobsResponse,
  TrainingRun,
  TrainingRunsResponse,
  TrainingSample,
  UpdateCategoryRequest,
  UpdateLiveTopicsRequest,
  UpdateStreamConfigRequest,
  UsageRecording,
  UsageSummary,
  UsageTimeline,
  UserSummary,
  VehicleProfile,
  VerifyMarkRequest,
} from './models';

/**
 * The only place the frontend knows REST URLs.
 *
 * Every method returns a promise: components hold signals, not subscriptions, so
 * awaiting and assigning is both simpler and zoneless-friendly.
 */
@Injectable({ providedIn: 'root' })
export class VisionApi {
  private readonly http = inject(HttpClient);

  // --- Devices -------------------------------------------------------------

  /**
   * `includeDeleted` defaults to `false` (today's behavior, unchanged) and is only added to the
   * query string when `true` — the warehouse page's "show archived" toggle (docs/main/CYCLES-PLAN.md
   * §8's pinned contract).
   */
  listDevices(includeDeleted = false): Promise<Device[]> {
    return firstValueFrom(
      this.http.get<Device[]>('/api/devices', includeDeleted ? { params: { includeDeleted: true } } : {}),
    );
  }

  registerDevice(request: RegisterDeviceRequest): Promise<Device> {
    return firstValueFrom(this.http.post<Device>('/api/devices', request));
  }

  // --- Devices — warehouse lifecycle (docs/main/CYCLES-PLAN.md §8's pinned contract) --------------
  // CW-a builds the server side of these in parallel with this UI; every call here degrades to
  // one `FleetStore.run()` toast (via its thin wrappers) if it 404s before CW-a ships.

  updateDevice(id: string, edit: DeviceEdit): Promise<Device> {
    return firstValueFrom(this.http.patch<Device>(`/api/devices/${encodeURIComponent(id)}`, edit));
  }

  /** `DEACTIVATED` on a `DELETED` device is a restore; `DELETED` is reached only via `deleteDevice`. */
  setDeviceState(id: string, state: SettableLifecycleState): Promise<Device> {
    return firstValueFrom(
      this.http.post<Device>(`/api/devices/${encodeURIComponent(id)}/state`, { state }),
    );
  }

  /** Soft delete (archive) — idempotent. */
  deleteDevice(id: string): Promise<Device> {
    return firstValueFrom(this.http.delete<Device>(`/api/devices/${encodeURIComponent(id)}`));
  }

  // --- Streams -------------------------------------------------------------

  listStreams(): Promise<ActiveStream[]> {
    return firstValueFrom(this.http.get<ActiveStream[]>('/api/streams'));
  }

  startStream(deviceId: string, request: StartStreamRequest = {}): Promise<StartStreamResult> {
    return firstValueFrom(
      this.http.post<StartStreamResult>(
        `/api/devices/${encodeURIComponent(deviceId)}/stream`,
        request,
      ),
    );
  }

  stopStream(streamId: string): Promise<void> {
    return firstValueFrom(
      this.http.delete<void>(`/api/streams/${encodeURIComponent(streamId)}`),
    );
  }

  /**
   * Recent detection results for a stream, newest first (docs/plans/done/MVP1-PLAN.md §C8 bullet 3) — backs
   * the Live page's chip strip + CV status dot. An unknown/never-detected stream returns an empty
   * array rather than 404ing, mirroring `usageTelemetry`'s precedent.
   */
  streamDetections(streamId: string, limit = 50): Promise<DetectionResult[]> {
    return firstValueFrom(
      this.http.get<DetectionResult[]>(`/api/streams/${encodeURIComponent(streamId)}/detections`, {
        params: { limit },
      }),
    );
  }

  // --- Live per-stream CV control (docs/plans/done/CV-CONTROL-PLAN.md §3-4's frozen contract) -----------
  // `features/fly/cv-control-panel.ts` is the one UI caller (via `FleetStore`'s thin wrappers,
  // mirroring every other mutation in this class); no page talks to either URL directly.

  /**
   * Live-patches a *running* stream's detection config (`200 {streamId, modelReArmed}` — see that
   * response type's own doc comment for what `modelReArmed` means and when it's `true`). `404`
   * unknown/not-running stream, `400` a value fails `PipelineConfig`'s own validation, `409`
   * reserved for a not-yet-named "cannot apply" state — all three reject the returned promise,
   * decoded by the caller via `describeHttpError`. No client-side precondition check — every field
   * the CV panel sends is already clamped to a valid range by its own slider/control bounds.
   */
  patchStreamConfig(streamId: string, patch: UpdateStreamConfigRequest): Promise<PatchStreamConfigResponse> {
    return firstValueFrom(
      this.http.patch<PatchStreamConfigResponse>(
        `/api/streams/${encodeURIComponent(streamId)}/config`,
        patch,
      ),
    );
  }

  /**
   * A running stream's effective configuration (`200`, see {@link StreamConfigResponse}'s own doc
   * comment) — docs/plans/active/CV-SETTINGS-PLAN.md wave W7's H6 fix: every hot knob this app
   * exposes now has a real readback instead of assuming from what was last sent. `404` for an
   * unknown/not-running stream — the caller (`CockpitFacade`) degrades to `undefined`, never a
   * fabricated default (see that type's own doc comment).
   */
  getStreamConfig(streamId: string): Promise<StreamConfigResponse> {
    return firstValueFrom(this.http.get<StreamConfigResponse>(`/api/streams/${encodeURIComponent(streamId)}/config`));
  }

  /**
   * The detection-model picker's roster (`200 {models}`, never errors server-side — see
   * `CvModelsResponse`'s own doc comment). Replaces the old hardcoded `DETECTION_MODEL_OPTIONS`
   * array; `FleetStore.models` fetches this once and caches it, degrading to an empty list on any
   * transport failure rather than blocking whichever page asked.
   */
  getCvModels(): Promise<CvModelsResponse> {
    return firstValueFrom(this.http.get<CvModelsResponse>('/api/cv/models'));
  }

  // --- Tracking engine (docs/plans/done/TRACKING-PLAN.md §4's frozen wire contract, wave T7) -------------
  // `features/fly/cv-control-panel.ts` is the one UI caller of both (via `FleetStore`'s own thin
  // wrappers — see that class's doc comments for why neither is `run()`-wrapped). **Neither
  // endpoint had shipped server-side when this wave landed** — both simply reject until docs/
  // TRACKING-PLAN.md wave T6 builds them, and every caller here degrades to "hidden"/"—", never a
  // blocked page or a fabricated value.

  /**
   * The tracker-engine roster for the Tracking section's engine picker (`200 {trackers}` — see
   * `CvTrackersResponse`'s own doc comment). Config-backed and static like `getCvModels()`'s own
   * roster — fetched once by `FleetStore`, never re-polled.
   */
  getCvTrackers(): Promise<CvTrackersResponse> {
    return firstValueFrom(this.http.get<CvTrackersResponse>('/api/cv/trackers'));
  }

  /**
   * One stream's live track book + duty-cycle stats (docs/plans/done/TRACKING-PLAN.md §4.E) — feeds the Fly
   * cockpit's flow strip and its "Following #N" lock-confirmation chip (see
   * `StreamTracksResponse`'s own doc comment). Never errors server-side once T6 ships (an
   * unknown/stopped stream returns an empty track list) — see `FleetStore.getStreamTracks`'s own
   * doc comment for how *this app* degrades a transport failure (an old/absent server, or a genuine
   * network error) into "nothing to show" rather than a crash.
   */
  getStreamTracks(streamId: string): Promise<StreamTracksResponse> {
    return firstValueFrom(
      this.http.get<StreamTracksResponse>(`/api/streams/${encodeURIComponent(streamId)}/tracks`),
    );
  }

  // --- Discovery -----------------------------------------------------------

  scan(request: ScanRequest = {}): Promise<ScanResult> {
    return firstValueFrom(this.http.post<ScanResult>('/api/discovery/scan', request));
  }

  // --- Discovery inbox (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §11, wave Z2d) -------
  // manageOrg-gated server-side (`DiscoveryInboxController`); poll-only, no SSE topic yet — see
  // `DiscoveryCandidate`'s own doc comment in `models.ts`.

  /** Since A3 (docs/plans/active/ASSET-FLOWS-PLAN.md §2) this answers the `{candidates, sources}`
   *  envelope, not a bare array — see `DiscoveryInboxResponse`'s own doc comment in `models.ts`. */
  listDiscoveryInboxCandidates(): Promise<DiscoveryInboxResponse> {
    return firstValueFrom(this.http.get<DiscoveryInboxResponse>('/api/discovery/inbox'));
  }

  registerDiscoveryCandidate(
    id: string,
    request: RegisterDiscoveryCandidateRequest,
  ): Promise<RegisterDiscoveryCandidateResponse> {
    return firstValueFrom(
      this.http.post<RegisterDiscoveryCandidateResponse>(
        `/api/discovery/inbox/${encodeURIComponent(id)}/register`,
        request,
      ),
    );
  }

  /** "Not now" — a later scan hit for the same identity also revives it server-side, but an
   *  operator can undo the dismissal directly too, via {@link restoreDiscoveryCandidate} (W3). */
  dismissDiscoveryCandidate(id: string): Promise<DiscoveryCandidate> {
    return firstValueFrom(
      this.http.post<DiscoveryCandidate>(`/api/discovery/inbox/${encodeURIComponent(id)}/dismiss`, {}),
    );
  }

  /** Undoes a dismiss — puts a `DISMISSED` candidate back to `NEW` (`DiscoveryInboxController`'s
   *  `/restore`). Not offered for a candidate that's already `REGISTERED`; the store/UI enforce
   *  that, the endpoint itself only ever touches status + clears `registeredAssetId`. */
  restoreDiscoveryCandidate(id: string): Promise<DiscoveryCandidate> {
    return firstValueFrom(
      this.http.post<DiscoveryCandidate>(`/api/discovery/inbox/${encodeURIComponent(id)}/restore`, {}),
    );
  }

  /** Atomic "this candidate *is* that asset" — attaches a discovered device straight onto an
   *  existing asset in one call (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md C1), replacing the
   *  pre-existing two-step register-then-move-device dance for the common "it's my rover, I
   *  already registered it, this is just its camera" case. */
  attachDiscoveryCandidate(id: string, request: AttachDiscoveryCandidateRequest): Promise<DiscoveryCandidate> {
    return firstValueFrom(
      this.http.post<DiscoveryCandidate>(
        `/api/discovery/inbox/${encodeURIComponent(id)}/attach`,
        request,
      ),
    );
  }

  /** `GET /api/discovery/status` (C2) — the standing MAVLink lobby's + mediamtx push's own live
   *  facts, manageOrg-gated server-side same as the rest of discovery. Powers the `source`/`prove`
   *  steps' waiting room (`core/onboarding/intake-logic.ts#intakeState`) — polled, not pushed; the
   *  `discovery` SSE topic carries only inbox candidate deltas, not this summary. */
  discoveryStatus(): Promise<DiscoveryStatusResponse> {
    return firstValueFrom(this.http.get<DiscoveryStatusResponse>('/api/discovery/status'));
  }

  // --- Device probe (docs/plans/done/UX-REWORK-PLAN.md §U-d — the onboarding wizard's Test step) ---------
  // "Test before save" (UX-DESIGN §5.1): connects to a candidate connection and decodes one frame
  // without registering anything. A probe that can't produce a frame is a 422 with a specific
  // message (`describeHttpError` already surfaces it) — the wizard never lets Register/Discover
  // paths advance past a failed probe (`features/onboarding/onboarding-logic.ts#canAdvanceFromTest`).

  probeDevice(request: ProbeDeviceRequest): Promise<ProbeDeviceResult> {
    return firstValueFrom(this.http.post<ProbeDeviceResult>('/api/devices/probe', request));
  }

  // --- Assets ----------------------------------------------------------------
  // Backs the live telemetry OSD/map (docs/main/CYCLES-PLAN.md §2): a device's asset — and
  // that asset's open usage — is looked up on demand, not polled by a fleet-wide store.

  /** `includeDeleted` defaults to `false` (today's behavior, unchanged) — see `listDevices`. */
  listAssets(includeDeleted = false): Promise<AssetSummary[]> {
    return firstValueFrom(
      this.http.get<AssetSummary[]>(
        '/api/assets',
        includeDeleted ? { params: { includeDeleted: true } } : {},
      ),
    );
  }

  getAsset(assetId: string): Promise<AssetDetails> {
    return firstValueFrom(this.http.get<AssetDetails>(`/api/assets/${encodeURIComponent(assetId)}`));
  }

  /**
   * The defined category reference list (`CategoryController`, docs/plans/done/UI-REDESIGN-PLAN.md Wave 4) —
   * every category the asset-creation UI's picker can offer, **including one with zero assets
   * currently in it** (unlike deriving categories from whatever's loaded, `core/fleet/category-logic.ts#deriveCategoryOptions`'s
   * pre-existing fallback approach). Never errors server-side; an empty install still returns the
   * backend's own seed list. First real call site: `features/categories/**`'s grouped view, joining
   * this against `fleetSummary().categories`' per-category counts by `slug`/`categoryId`.
   */
  listCategories(): Promise<Category[]> {
    return firstValueFrom(this.http.get<Category[]>('/api/categories'));
  }

  /**
   * Creates a new category (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3's Categories table write
   * half, wave W4) — `CategoryController#create`, `canManageOrg`-gated server-side (`403` for a
   * pilot; the Categories tab's own create form is hidden from one first, via `orgGuard`'s same
   * `canManageOrg` check, so this is belt-and-suspenders, not the primary gate).
   */
  createCategory(request: CreateCategoryRequest): Promise<Category> {
    return firstValueFrom(this.http.post<Category>('/api/categories', request));
  }

  /**
   * Replaces an existing category's mutable fields — a whole-record `PUT`, not a patch (see
   * {@link UpdateCategoryRequest}'s own doc comment). `canManageOrg`-gated server-side.
   */
  updateCategory(id: string, request: UpdateCategoryRequest): Promise<Category> {
    return firstValueFrom(this.http.put<Category>(`/api/categories/${encodeURIComponent(id)}`, request));
  }

  /**
   * The manager page's KPI tile row (docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md, Wave B item 3) — lifetime
   * flight-utilization aggregates, distinct from {@link getAsset}'s `recentUsages` (a capped
   * recent list). 404 for an unknown asset, same as {@link getAsset}; the caller degrades its own
   * KPI row to "—" rather than blocking the page on failure (this page's existing enrichment-read
   * resilience — see `AssetDetailPage#loadStats`).
   */
  assetStats(assetId: string): Promise<AssetStats> {
    return firstValueFrom(this.http.get<AssetStats>(`/api/assets/${encodeURIComponent(assetId)}/stats`));
  }

  /**
   * Creates a new asset together with its device(s) in one call (docs/plans/done/UX-QUICKWINS-PLAN.md QF-2 —
   * the Devices page's "Create asset from this device" quick action, the first UI call site for an
   * endpoint that already existed server-side). Returns the full detail view (201), same shape as
   * {@link getAsset}.
   */
  createAsset(request: CreateAssetRequest): Promise<AssetDetails> {
    return firstValueFrom(this.http.post<AssetDetails>('/api/assets', request));
  }

  // --- Asset session (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §3 P4) -----------------
  // `AssetSessionController`'s operator `engage`/`disengage` pair — opens or closes an `AssetUsage`
  // directly, no video stream involved, so a telemetry-only asset (or one whose camera isn't up
  // yet) has a way to say "this asset is in use" at all. Fire-and-confirm, not fire-and-forget:
  // there is no `GET .../session` read endpoint, so neither method's *return* is treated as the
  // engaged/disengaged state to render. `features/fly/cockpit-facade.ts#operatorEngaged` instead
  // rereads the fact honestly off `AssetDetails#recentUsages` (already polled every 5s) via
  // `selectOpenUsage` + `origin === 'OPERATOR'` — the same "poll is the truth" rule every other
  // cockpit fact already follows, rather than a local "I clicked it" flag that could drift from
  // what the server actually did (a 200 here doesn't guarantee the poll lands before the operator
  // looks — see that computed's own doc comment).

  /** `200` — the usage now open for this asset (newly opened, promoted from a stream-origin usage,
   * or unchanged if already operator-engaged). The response is logged, not rendered; see this
   * section's own comment for why. */
  engageAssetSession(assetId: string): Promise<AssetUsage> {
    return firstValueFrom(
      this.http.post<AssetUsage>(`/api/assets/${encodeURIComponent(assetId)}/session`, {}),
    );
  }

  /** `204` — idempotent: a no-op if the asset isn't operator-engaged, a demote-not-close if a video
   * stream is still running (`AssetSessionController#disengage`'s own javadoc). */
  disengageAssetSession(assetId: string): Promise<void> {
    return firstValueFrom(
      this.http.delete<void>(`/api/assets/${encodeURIComponent(assetId)}/session`),
    );
  }

  // --- Seats (docs/plans/active/CREW-CONTROL-PLAN.md §3.6, frozen — byte-exact) ------------------
  // Both seats are always present in the response, holder fields explicit `null` when free. With
  // `vision.crew.enabled=false` (default) the endpoint reports both seats free and every `may*` true
  // — `core/seat/seat-store.ts` treats a `404` (feature not deployed at all) the same way, never as
  // an error to surface.

  /** `200` `SeatsResponse` — `404` unknown or out-of-visibility asset (a read hides existence);
   * `400` bad UUID. */
  getAssetSeats(assetId: string): Promise<SeatsResponse> {
    return firstValueFrom(
      this.http.get<SeatsResponse>(`/api/assets/${encodeURIComponent(assetId)}/seats`),
    );
  }

  /** Take-or-renew, idempotent for the current holder — this is the seat heartbeat
   * (`SeatStore`'s renewal cadence is `ttlMs / 3`, never hard-coded). `409` held by another; `403`
   * caller lacks authority for this `kind`; `404` unknown asset; `400` bad UUID or unknown kind. The
   * body may be omitted entirely — `force` is honoured only for a caller with `mayForceSeat`, which
   * this wave never sets. */
  takeAssetSeat(assetId: string, kind: SeatKind, request: TakeSeatRequest = {}): Promise<SeatsResponse> {
    return firstValueFrom(
      this.http.post<SeatsResponse>(
        `/api/assets/${encodeURIComponent(assetId)}/seats/${kind.toLowerCase()}`,
        request,
      ),
    );
  }

  /** `204` — idempotent: a no-op if free or already released. `403` held by another and caller lacks
   * `mayForceSeat`; `404` unknown; `400` bad UUID or unknown kind. */
  releaseAssetSeat(assetId: string, kind: SeatKind): Promise<void> {
    return firstValueFrom(
      this.http.delete<void>(`/api/assets/${encodeURIComponent(assetId)}/seats/${kind.toLowerCase()}`),
    );
  }

  // --- Maintenance / inventory (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.2/§4 W7/W9) ---------------

  /** One asset's maintenance history, open and closed, oldest-first (server order) — `404` unknown or out-of-scope asset. */
  listAssetMaintenance(assetId: string): Promise<MaintenanceRecord[]> {
    return firstValueFrom(
      this.http.get<MaintenanceRecord[]>(`/api/assets/${encodeURIComponent(assetId)}/maintenance`),
    );
  }

  /**
   * Maintenance records across every asset the caller's scope includes, one call rather than one
   * {@link listAssetMaintenance} per grounded asset (docs/plans/active/WAREHOUSE-UX-CONTEXT.md
   * "W8 → W9 handoff") — `AssetInventoryController#fleetMaintenance`, `GET /api/maintenance`.
   * `limit` bounds the `closed`/`all` portion's fleet-wide scan; open records are never
   * limit-truncated server-side.
   */
  fleetMaintenance(state: MaintenanceListState = 'open', limit?: number): Promise<FleetMaintenanceRecord[]> {
    return firstValueFrom(
      this.http.get<FleetMaintenanceRecord[]>('/api/maintenance', {
        params: limit === undefined ? { state } : { state, limit },
      }),
    );
  }

  /** Opens a record *without* grounding the asset — see {@link setAssetInventory}'s `GROUND` action for that. `canManage`-gated, `403` audited. */
  createMaintenanceRecord(assetId: string, request: CreateMaintenanceRecordRequest): Promise<MaintenanceRecord> {
    return firstValueFrom(
      this.http.post<MaintenanceRecord>(`/api/assets/${encodeURIComponent(assetId)}/maintenance`, request),
    );
  }

  /** Closes one open record (idempotent on an already-closed one is not guaranteed — callers only ever close a record they can see is still open). `canManage`-gated. */
  closeMaintenanceRecord(assetId: string, recordId: string): Promise<MaintenanceRecord> {
    return firstValueFrom(
      this.http.post<MaintenanceRecord>(
        `/api/assets/${encodeURIComponent(assetId)}/maintenance/${encodeURIComponent(recordId)}/close`,
        {},
      ),
    );
  }

  /**
   * `GROUND` (opens a blocking-capable record and sets `inventoryState` to `MAINTENANCE` in one
   * call — `kind`/`summary` required), `RELEASE` (returns to `IN_STOCK`), or `RETIRE`. Returns the
   * asset's full `AssetDetails`. `canManage`-gated, `403` audited.
   */
  setAssetInventory(assetId: string, request: InventoryActionRequest): Promise<AssetDetails> {
    return firstValueFrom(
      this.http.post<AssetDetails>(`/api/assets/${encodeURIComponent(assetId)}/inventory`, request),
    );
  }

  /**
   * `ISSUE` (hands an in-stock asset to a custodian) or `RETURN` (brings an issued one back to
   * stock) — `AssetInventoryController#custody` (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4, wave
   * W3/W4). Returns the asset's full `AssetDetails`, the same response shape {@link
   * setAssetInventory} returns. `canManage`-gated, `403` audited.
   *
   * The onboarding wizard's Hand-over step's "Issue to" action calls this, then separately calls
   * {@link assignPilot} — `issue` alone does not create a pilot assignment (verified by reading
   * `DefaultAssetCustodyService#issue`, which only touches `Custody`/`InventoryState`).
   */
  setAssetCustody(assetId: string, request: CustodyActionRequest): Promise<AssetDetails> {
    return firstValueFrom(
      this.http.post<AssetDetails>(`/api/assets/${encodeURIComponent(assetId)}/custody`, request),
    );
  }

  /**
   * The fleet-wide CSV export's own URL (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3's Export
   * action, wave W4) — `InventoryExportController#export`, `GET /api/inventory/export?format=csv`.
   * A plain file download (`Content-Disposition: attachment; filename="inventory.csv"`), not
   * promise-returning — same pattern as {@link afterActionArchiveUrl}: the Inventory page's page-bar
   * action binds this straight to `<a [href]="…" download>`, never through `HttpClient`. `format`
   * is the only query parameter the endpoint accepts (`csv` is the only supported value today).
   */
  inventoryExportUrl(format = 'csv'): string {
    return `/api/inventory/export?format=${encodeURIComponent(format)}`;
  }

  usageTelemetry(usageId: string, limit = 200): Promise<TelemetrySample[]> {
    return firstValueFrom(
      this.http.get<TelemetrySample[]>(`/api/usages/${encodeURIComponent(usageId)}/telemetry`, {
        params: { limit },
      }),
    );
  }

  /**
   * The flight-replay window for one usage (docs/plans/done/MVP2-PLAN.md §R, R-a/R-b — `UsageTimelineController`,
   * windowed + downsampled, unlike `usageTelemetry` above). `fromMs`/`toMs`/`maxPoints` are each
   * only added to the query string when given — omitting all three (`features/replay/replay.ts`'s
   * only call site) lets the server default the window to the usage's own bounds and thin to its
   * own `DEFAULT_MAX_POINTS`; the replay cockpit instead always asks for `maxPoints: 2000` (the
   * server's clamp ceiling) once, up front, then scrubs entirely against the in-memory result —
   * no re-fetch per drag frame.
   */
  usageTimeline(
    usageId: string,
    options: { readonly fromMs?: number; readonly toMs?: number; readonly maxPoints?: number } = {},
  ): Promise<UsageTimeline> {
    const params: Record<string, number> = {};
    if (options.fromMs !== undefined) {
      params['fromMs'] = options.fromMs;
    }
    if (options.toMs !== undefined) {
      params['toMs'] = options.toMs;
    }
    if (options.maxPoints !== undefined) {
      params['maxPoints'] = options.maxPoints;
    }
    return firstValueFrom(
      this.http.get<UsageTimeline>(`/api/usages/${encodeURIComponent(usageId)}/timeline`, { params }),
    );
  }

  /**
   * The fleet-wide flight list behind the replay library (docs/extracts/design/10-replay.md's frozen wire
   * contract, Wave 4) — `GET /api/usages`, newest first. `limit`/`assetId` are each only added to
   * the query string when given, the same convention as every other optional filter in this
   * class. `features/replay/replay-library-facade.ts` re-fetches with `assetId` set whenever the
   * asset filter changes, rather than filtering a fleet-wide page client-side — narrowing to one
   * asset then surfaces *that asset's* newest flights, not whatever happened to survive inside
   * the fleet-wide `limit`.
   */
  listUsages(options: { readonly limit?: number; readonly assetId?: string } = {}): Promise<UsageSummary[]> {
    const params: Record<string, string | number> = {};
    if (options.limit !== undefined) {
      params['limit'] = options.limit;
    }
    if (options.assetId !== undefined) {
      params['assetId'] = options.assetId;
    }
    return firstValueFrom(this.http.get<UsageSummary[]>('/api/usages', { params }));
  }

  // --- Assets — warehouse lifecycle (docs/main/CYCLES-PLAN.md §8's pinned contract) ---------------

  updateAsset(id: string, edit: AssetEdit): Promise<AssetDetails> {
    return firstValueFrom(this.http.patch<AssetDetails>(`/api/assets/${encodeURIComponent(id)}`, edit));
  }

  /** `DEACTIVATED` on a `DELETED` asset is a restore; `DELETED` is reached only via `deleteAsset`. */
  setAssetState(id: string, state: SettableLifecycleState): Promise<AssetDetails> {
    return firstValueFrom(
      this.http.post<AssetDetails>(`/api/assets/${encodeURIComponent(id)}/state`, { state }),
    );
  }

  /** Soft delete (archive) — idempotent. Reports what it retained, not just that it succeeded. */
  deleteAsset(id: string): Promise<AssetDeletionResponse> {
    return firstValueFrom(
      this.http.delete<AssetDeletionResponse>(`/api/assets/${encodeURIComponent(id)}`),
    );
  }

  /** Assigns an unowned device to an asset; 409 when the device is already owned elsewhere. */
  assignDevice(assetId: string, deviceId: string): Promise<AssetDetails> {
    return firstValueFrom(
      this.http.post<AssetDetails>(`/api/assets/${encodeURIComponent(assetId)}/devices`, { deviceId }),
    );
  }

  /** 409 when this is the asset's last device — every asset needs at least one. */
  unassignDevice(assetId: string, deviceId: string): Promise<AssetDetails> {
    return firstValueFrom(
      this.http.delete<AssetDetails>(
        `/api/assets/${encodeURIComponent(assetId)}/devices/${encodeURIComponent(deviceId)}`,
      ),
    );
  }

  // --- Asset image (docs/plans/done/UX-REWORK-PLAN.md §U-d) ----------------------------------------------
  // A small binary sidecar on an asset, not a field in `AssetDetails`/`AssetSummary` itself — only
  // `hasImage` lives on those DTOs (see that field's own doc comment in `models.ts`). Upload is
  // always a client-downscaled JPEG (`features/onboarding/image-downscale.ts`), always ≤2MB per the
  // pinned contract — this class does no downscaling itself, it only moves already-prepared bytes.

  /**
   * The path for an asset's photo (docs/plans/done/UX-REWORK-PLAN.md §U-d) — not promise-returning, like
   * `snapshotUrl` above: meant to be bound straight to an `<img src>`, which fetches it itself and
   * degrades gracefully to its own `error` handler on a 404 (an asset with `hasImage` false/absent,
   * or one whose photo was never uploaded on a pre-§U-d backend).
   */
  assetImageUrl(assetId: string): string {
    return `/api/assets/${encodeURIComponent(assetId)}/image`;
  }

  /** Replaces the asset's photo — raw JPEG/PNG bytes, ≤2MB, `204` on success. */
  uploadAssetImage(assetId: string, image: Blob): Promise<void> {
    return firstValueFrom(
      this.http.put<void>(`/api/assets/${encodeURIComponent(assetId)}/image`, image),
    );
  }

  /** Removes the asset's photo — idempotent, `204` on success. */
  deleteAssetImage(assetId: string): Promise<void> {
    return firstValueFrom(
      this.http.delete<void>(`/api/assets/${encodeURIComponent(assetId)}/image`),
    );
  }

  // --- Simulation ------------------------------------------------------------
  // Backs the "Simulate a source" wizard (docs/main/CYCLES-PLAN.md §4): a video file path in, a
  // registered, categorized, optionally already-streaming asset out.

  startSimulation(request: StartSimulationRequest): Promise<SimulationResponse> {
    return firstValueFrom(this.http.post<SimulationResponse>('/api/simulations', request));
  }

  /** Idempotent — stops the asset's stream and, for `transport=rtsp`, its transmitted feed too. */
  stopSimulation(assetId: string): Promise<void> {
    return firstValueFrom(
      this.http.delete<void>(`/api/simulations/${encodeURIComponent(assetId)}`),
    );
  }

  // --- Detection events (docs/plans/done/MVP2-PLAN.md §E, E-a/E-b) ----------------------------------------
  // `EventController`'s polling contract: newest-first by `lastSeen`, `sinceMs` a nullable cursor
  // (see `core/events/events-store.ts` for how the poll loop advances it).

  /**
   * Recent detection events across every stream, newest-first by `lastSeen`. `sinceMs` is only
   * added to the query string when given — `EventController#recent`'s own `sinceMs` param is
   * nullable, `null` meaning no lower bound (the first poll of a session).
   */
  events(sinceMs?: number, limit = 50): Promise<DetectionEvent[]> {
    const params: Record<string, number> = { limit };
    if (sinceMs !== undefined) {
      params['sinceMs'] = sinceMs;
    }
    return firstValueFrom(this.http.get<DetectionEvent[]>('/api/events', { params }));
  }

  /**
   * One stream's detection events, newest-first by `lastSeen`. An unknown/never-alerted stream
   * returns an empty array rather than 404ing, mirroring `streamDetections`'s own precedent.
   */
  streamEvents(streamId: string, limit = 50): Promise<DetectionEvent[]> {
    return firstValueFrom(
      this.http.get<DetectionEvent[]>(`/api/streams/${encodeURIComponent(streamId)}/events`, {
        params: { limit },
      }),
    );
  }

  // --- Fleet summary + stream snapshots (docs/plans/done/MVP3-PLAN.md C-a/C-c) --------------------------
  // The Command dashboard's one aggregated poll: per-category counts + a capped per-asset
  // attention list, server-joined so the browser never assembles this from several endpoints.

  /**
   * `includeArchived` (deliberately **not** `includeDeleted` like every other list endpoint here —
   * see vision-api/MODULE.md's Conventions for why the fleet-summary endpoint alone uses this
   * name) defaults to `false` and is only added to the query string when `true`, mirroring
   * `listDevices`/`listAssets`'s own `includeDeleted` convention.
   */
  fleetSummary(includeArchived = false): Promise<FleetSummary> {
    return firstValueFrom(
      this.http.get<FleetSummary>(
        '/api/fleet/summary',
        includeArchived ? { params: { includeArchived: true } } : {},
      ),
    );
  }

  /**
   * The path for a running stream's latest-frame JPEG thumbnail (docs/plans/done/MVP3-PLAN.md C-a/C-c) — not
   * promise-returning like every other method here: meant to be bound straight to an `<img src>`,
   * which fetches it itself via the browser's own image loading, cache-busted with a query param on
   * each poll — there is nothing this class could usefully `await` on the caller's behalf.
   *
   * Was unused for a while (docs/plans/done/UX-REWORK-PLAN.md §U-c): its one consumer,
   * `features/command/live-strip-tile.ts` (the Command dashboard's live-strip snapshot tiles), was
   * deleted when that section was removed from Command per the plan's own user-amendments
   * blockquote ("live strip: removed") — left in place rather than deleted at the time, a small,
   * self-contained, still-correct method kept on the bet that a future consumer would want it again.
   * That bet paid off: `features/camera-geo/camera-calibration-wizard.ts`
   * (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md wave G5) now binds this straight to the calibration
   * frame `<img>` an operator clicks landmarks on. Still lives here (not inlined at the call site) so
   * `VisionApi` stays "the only place the frontend knows REST URLs" (this file's own top doc
   * comment) even for a path that's never actually passed through `HttpClient`.
   */
  snapshotUrl(streamId: string): string {
    return `/api/streams/${encodeURIComponent(streamId)}/snapshot`;
  }

  // --- Live updates (docs/plans/done/REALTIME-PLAN.md §4, Phase R-c) ------------------------------------
  // `GET /api/live` itself is not here — `core/live/live-store.ts` opens it as a raw `EventSource`
  // (not `HttpClient`, which cannot stream SSE), so this class's own "the only place the frontend
  // knows REST URLs" rule bends for exactly that one endpoint (see that file's own doc comment).
  // This PATCH is a plain request/response, so it follows the usual path.

  /** Adds/removes topics on an already-open live connection; returns the full topic set afterward. */
  updateLiveTopics(connectionId: string, request: UpdateLiveTopicsRequest): Promise<LiveSubscription> {
    return firstValueFrom(
      this.http.patch<LiveSubscription>(
        `/api/live/${encodeURIComponent(connectionId)}/topics`,
        request,
      ),
    );
  }

  // --- Geofencing (docs/plans/done/OPS-CORE-PLAN.md §G's frozen wire contract) ---------------------------

  /** Every known zone, server-sorted by name. */
  listGeofences(): Promise<GeofenceZone[]> {
    return firstValueFrom(this.http.get<GeofenceZone[]>('/api/geofences'));
  }

  /** 400 for a polygon with fewer than 3 vertices — `core/geofence/geofence-logic.ts#canSaveZone` checks first. */
  createGeofence(request: GeofenceZoneRequest): Promise<GeofenceZone> {
    return firstValueFrom(this.http.post<GeofenceZone>('/api/geofences', request));
  }

  /** Wholesale replace — the wire contract has no partial-patch geofence endpoint; 404 unknown id. */
  updateGeofence(id: string, request: GeofenceZoneRequest): Promise<GeofenceZone> {
    return firstValueFrom(
      this.http.put<GeofenceZone>(`/api/geofences/${encodeURIComponent(id)}`, request),
    );
  }

  /** Idempotent. */
  deleteGeofence(id: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`/api/geofences/${encodeURIComponent(id)}`));
  }

  // --- The map COP: layers, marks, drawings (docs/plans/done/MAP-REWORK-PLAN.md §4.1's frozen contract) ---
  // One base path, `/api/map`, replacing the whole `/api/marks` surface. Every list is already
  // scoped server-side (§3) — an out-of-scope id 404s rather than 403s, deliberately, so existence
  // is never revealed; a forbidden action on an object the caller *can* see is the only 403.

  /** Every layer the caller may see, COP first then by name. `grants` is populated only on layers they MANAGE. */
  listMapLayers(): Promise<MapLayer[]> {
    return firstValueFrom(this.http.get<MapLayer[]>('/api/map/layers'));
  }

  /** Creates a TEAM (managers of that group; `groupId` required) or PERSONAL (anyone) layer. */
  createMapLayer(request: CreateLayerRequest): Promise<MapLayer> {
    return firstValueFrom(this.http.post<MapLayer>('/api/map/layers', request));
  }

  /** Rename only. 403 unless the caller MANAGEs the layer; the COP layer can never be renamed. */
  renameMapLayer(id: string, request: RenameLayerRequest): Promise<MapLayer> {
    return firstValueFrom(this.http.patch<MapLayer>(`/api/map/layers/${encodeURIComponent(id)}`, request));
  }

  /** Cascades: the layer's marks and drawings go with it. 403 unless MANAGE; the COP layer can never be deleted. */
  deleteMapLayer(id: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`/api/map/layers/${encodeURIComponent(id)}`));
  }

  /** **Wholesale** replacement of a layer's access list (§4.1) — send every grant that should survive, not a delta. 403 unless MANAGE. */
  setMapLayerGrants(id: string, request: SetLayerGrantsRequest): Promise<MapLayer> {
    return firstValueFrom(this.http.put<MapLayer>(`/api/map/layers/${encodeURIComponent(id)}/grants`, request));
  }

  /** Every ACTIVE mark on a layer the caller may view, newest first. */
  listMapMarks(): Promise<MapMark[]> {
    return firstValueFrom(this.http.get<MapMark[]>('/api/map/marks'));
  }

  /** Drops a manual mark (an armed map click). 403 unless the caller may CONTRIBUTE to `layerId`; an omitted `layerId` defaults server-side. */
  createMapMark(request: CreateMarkRequest): Promise<MapMark> {
    return firstValueFrom(this.http.post<MapMark>('/api/map/marks', request));
  }

  /** Drops a mark projected from an asset's freshest telemetry (the cockpit "Mark target"). 400 if the asset has no/incomplete telemetry. */
  geolocateMapMark(request: GeolocateMarkRequest): Promise<MapMark> {
    return firstValueFrom(this.http.post<MapMark>('/api/map/marks/geolocate', request));
  }

  /** Partial edit — annotation, drag-to-correct, or a status transition. 403 once CONFIRMED unless the caller MANAGEs the layer; 404 unknown/out-of-scope id. */
  patchMapMark(id: string, request: PatchMarkRequest): Promise<MapMark> {
    return firstValueFrom(this.http.patch<MapMark>(`/api/map/marks/${encodeURIComponent(id)}`, request));
  }

  /** A manager's CONFIRM/REJECT decision. 403 unless the caller MANAGEs the mark's layer. */
  verifyMapMark(id: string, request: VerifyMarkRequest): Promise<MapMark> {
    return firstValueFrom(this.http.post<MapMark>(`/api/map/marks/${encodeURIComponent(id)}/verify`, request));
  }

  /** Moves the mark onto the shared common picture (default target: the COP layer) and stamps it CONFIRMED. 403 unless MANAGE on the source layer + CONTRIBUTE on the target. */
  promoteMapMark(id: string, request: PromoteMarkRequest = {}): Promise<MapMark> {
    return firstValueFrom(this.http.post<MapMark>(`/api/map/marks/${encodeURIComponent(id)}/promote`, request));
  }

  /** 403 unless the caller is the still-unverified mark's creator or MANAGEs its layer; 404 unknown/out-of-scope id. */
  deleteMapMark(id: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`/api/map/marks/${encodeURIComponent(id)}`));
  }

  /** Every drawing on a layer the caller may view. */
  listMapDrawings(): Promise<MapDrawingResponse[]> {
    return firstValueFrom(this.http.get<MapDrawingResponse[]>('/api/map/drawings'));
  }

  /** 400 on a degenerate shape (LINE/ARROW need ≥2 points, POLYGON ≥3, TEXT exactly 1 + a label); 403 without CONTRIBUTE. */
  createMapDrawing(request: CreateDrawingRequest): Promise<MapDrawingResponse> {
    return firstValueFrom(this.http.post<MapDrawingResponse>('/api/map/drawings', request));
  }

  /** Geometry and/or details; only present fields change. */
  patchMapDrawing(id: string, request: PatchDrawingRequest): Promise<MapDrawingResponse> {
    return firstValueFrom(this.http.patch<MapDrawingResponse>(`/api/map/drawings/${encodeURIComponent(id)}`, request));
  }

  /** 403 unless the caller is the drawing's creator or MANAGEs its layer; 404 unknown/out-of-scope id. */
  deleteMapDrawing(id: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`/api/map/drawings/${encodeURIComponent(id)}`));
  }

  // --- Fixed-camera geolocation (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md §5's frozen wire contract) -----
  // Asset-scoped like every other `/api/assets/{id}/…` endpoint (D10): out-of-scope reads 404, a
  // write on a visible-but-unmanageable asset 403. While `vision.geo.fixed-camera.enabled=false`
  // (the default) every endpoint here 409s — `core/camera-geo/camera-geo-logic.ts#isFixedCameraGeoDisabledError`
  // is what a caller checks for that case, mirroring `isProbeDisabledError`'s own precedent.

  /** The asset's stored pose, or `404` if none has ever been saved (or the asset is out of scope) — `core/camera-geo/camera-pose-panel.ts`'s own "no pose yet" empty state reads that 404, not an error toast. */
  getCameraPose(assetId: string): Promise<CameraPoseResponse> {
    return firstValueFrom(this.http.get<CameraPoseResponse>(`/api/assets/${encodeURIComponent(assetId)}/camera-pose`));
  }

  /** Whole-resource replace — manual entry, or confirming a calibration solve (`source` says which). `400` on a range violation (§5); `403`/`404` per this section's own doc comment. */
  putCameraPose(assetId: string, request: CameraPoseRequest): Promise<CameraPoseResponse> {
    return firstValueFrom(
      this.http.put<CameraPoseResponse>(`/api/assets/${encodeURIComponent(assetId)}/camera-pose`, request),
    );
  }

  /** Idempotent — `204` even if no pose was stored. Removing a pose stops that asset's tracks from projecting (D9: no pose ⇒ no projection). */
  deleteCameraPose(assetId: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`/api/assets/${encodeURIComponent(assetId)}/camera-pose`));
  }

  /** Solves yaw/pitch/hfov from 2–8 landmark correspondences — **never persists** (D5); the caller reviews the result and calls {@link putCameraPose} separately to save it. `400` for fewer than 2 or more than 8 points, or a `u`/`v` outside `[0,1]`. */
  calibrateCameraPose(assetId: string, request: CalibrateCameraPoseRequest): Promise<CalibrationResult> {
    return firstValueFrom(
      this.http.post<CalibrationResult>(`/api/assets/${encodeURIComponent(assetId)}/camera-pose/calibration`, request),
    );
  }

  /** Every projected track on a layer the caller may view (D10) — the map track layer's initial load, folded forward afterward by the live `TRACK`-entity `map` topic (`core/map-data/tracks-store.ts`). */
  listMapTracks(): Promise<MapTracksResponse> {
    return firstValueFrom(this.http.get<MapTracksResponse>('/api/map/tracks'));
  }

  // --- Visual geolocation v2 (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.3's frozen wire contract, wave H6) ---
  // All six routes 409 with D9's exact envelope while `vision.geo.visual.enabled=false` —
  // `core/geo/geo-logic.ts#isVisualGeoDisabledError` is what a caller checks for that case, the
  // same "check for the frozen message, degrade to the generic sentence otherwise" idiom
  // `isFixedCameraGeoDisabledError`/`isProbeDisabledError` already established.

  /** Every region cv-service knows about, plus any still-`BUILDING` in-flight job (D10 — proxied, not a Postgres read). `503` when cv-service itself is unreachable. */
  listGeoRegions(): Promise<RegionsResponse> {
    return firstValueFrom(this.http.get<RegionsResponse>('/api/geo/regions'));
  }

  /** Starts an async ingest for a new region — `202` with the fresh `RegionResponse` in `status: 'BUILDING'`; poll {@link geoRegionProgress} for its phase. `403` unless `canAdminister` (an external imagery fetch); `400` invalid bounds/zoom/tile-count. */
  createGeoRegion(request: RegionIngestRequest): Promise<RegionResponse> {
    return firstValueFrom(this.http.post<RegionResponse>('/api/geo/regions', request));
  }

  /** Idempotent — `204` even if `regionId` is already gone. */
  deleteGeoRegion(regionId: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`/api/geo/regions/${encodeURIComponent(regionId)}`));
  }

  /** One in-flight (or just-finished) ingest job's phase/state — `404` once neither an in-flight job nor a `READY` region exists for `regionId`. */
  geoRegionProgress(regionId: string): Promise<RegionProgressResponse> {
    return firstValueFrom(
      this.http.get<RegionProgressResponse>(`/api/geo/regions/${encodeURIComponent(regionId)}/progress`),
    );
  }

  /** The latest correction per asset the caller may see — `core/geo/geo-store.ts`'s own poll-fallback source, filtered client-side to the one tracked asset (§3.3 has no single-asset "latest" route). */
  liveGeoCorrections(): Promise<CorrectionsResponse> {
    return firstValueFrom(this.http.get<CorrectionsResponse>('/api/geo/corrections/live'));
  }

  /** One usage's full correction history, oldest→newest — the replay page's corrected track + divergence band. `limit` defaults server-side; `400` outside `[1,10000]`; `404` unknown usage or out of scope. */
  geoCorrections(usageId: string, limit?: number): Promise<CorrectionsResponse> {
    const params: Record<string, string | number> = { usageId };
    if (limit !== undefined) {
      params['limit'] = limit;
    }
    return firstValueFrom(this.http.get<CorrectionsResponse>('/api/geo/corrections', { params }));
  }

  // --- Recording + clip export (docs/plans/done/OPS-CORE-PLAN.md §R's frozen wire contract) ---------------

  /**
   * The recording/clip-export URL for one usage's flight window, if one is available. Always a
   * `200` — `{available: false}` for a usage with nothing to play back is not an error, see
   * `UsageRecording`'s own doc comment.
   */
  usageRecording(usageId: string): Promise<UsageRecording> {
    return firstValueFrom(
      this.http.get<UsageRecording>(`/api/usages/${encodeURIComponent(usageId)}/recording`),
    );
  }

  // --- After-action evidence package (docs/plans/done/AFTER-ACTION-PLAN.md §3's frozen wire contract, wave W2) ---
  // One request against one finished (or still-open) flight, everything the platform knows about
  // it: this manifest, plus the ZIP archive's own URL below. `features/replay/**`'s after-action
  // panel is the one consumer.

  /**
   * The evidence manifest for one usage — every one of the six parts, always, each with its own
   * honest `state`/`count`/`note` (see `AfterActionManifest`'s own doc comment). `404` for an
   * unknown usage, a usage that doesn't belong to `assetId`, or an asset not visible to the caller
   * (the same shape either way — existence itself is scoped, §3.3); `403` when the caller may see
   * the asset but not export it. A still-open usage (`endedAt: null`) is a valid, non-404 response
   * — never redirect/hide this panel the way `ReplayFacade`'s own timeline load does for `usageOpen`.
   */
  afterAction(assetId: string, usageId: string): Promise<AfterActionManifest> {
    return firstValueFrom(
      this.http.get<AfterActionManifest>(
        `/api/assets/${encodeURIComponent(assetId)}/usages/${encodeURIComponent(usageId)}/after-action`,
      ),
    );
  }

  /**
   * The ZIP archive's own URL (§3.2) — not promise-returning, like `snapshotUrl`/`assetImageUrl`
   * above: this is a plain file download (`Content-Disposition: attachment`), and the browser
   * handles that far better than fetching the whole archive into memory and re-offering it as a
   * blob would. `features/replay/**`'s "Download package" control binds this straight to a plain
   * `<a [href]="…" download>`, never through `HttpClient`.
   */
  afterActionArchiveUrl(assetId: string, usageId: string): string {
    return `/api/assets/${encodeURIComponent(assetId)}/usages/${encodeURIComponent(usageId)}/after-action/archive`;
  }

  // --- Guarded command TX (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1's frozen contract) -------------
  // The RX-only doctrine's one deliberate exception, staged and guarded: a single command, sent
  // only after the caller's own mandatory confirm dialog (`shared/ui/return-home-button.ts`).

  /**
   * Commands the asset's active mavlink device to RTL (`202 {result}` either way it was sent — see
   * `ReturnHomeResponse`'s own doc comment). `404` (unknown asset) and `409 {message}` (not
   * commandable: no active mavlink device, a firmware without RTL capability, or a vehicle never
   * heard on the socket) both reject the returned promise as an `HttpErrorResponse` — `409`'s
   * `message` is surfaced verbatim by `describeHttpError` (`core/api-error.ts`'s own switch already
   * handles it), not decoded here. No client-side dry-run/precondition check before sending — the
   * caller's own confirm dialog is what makes this safe to call directly.
   */
  returnHome(assetId: string): Promise<ReturnHomeResponse> {
    return firstValueFrom(
      this.http.post<ReturnHomeResponse>(`/api/assets/${encodeURIComponent(assetId)}/return-home`, {}),
    );
  }

  // --- Guarded command TX — arm/disarm/mode select (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 2's frozen
  // contract, extends Stage 1 above) -------------------------------------------------------------

  /**
   * The vehicle's own capability matrix (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 2's frozen contract) —
   * drives what `features/fly/flight-command-panel.ts` renders for this asset: `commandable=false`
   * for a Betaflight/never-heard vehicle hides the whole panel, the identical refusal cases Stage
   * 1's `returnHome` already surfaces as a `409`, just told ahead of time here as data instead of
   * waiting for a rejected command. `404` unknown asset, `403` out of scope — both reject the
   * returned promise as an `HttpErrorResponse`; the caller (`FlyPage`) degrades to "panel stays
   * hidden" on either, no toast — a background capability read, not a user-initiated action.
   */
  flightCapabilities(assetId: string): Promise<FlightCapability> {
    return firstValueFrom(
      this.http.get<FlightCapability>(`/api/assets/${encodeURIComponent(assetId)}/flight-capabilities`),
    );
  }

  /**
   * Sets the asset's active mavlink device's flight mode (`202 {result}` either way it was sent —
   * `FlightCommandResponse`'s own doc comment, the identical shape `arm`/`disarm` below share).
   * `404` unknown asset, `409 {message}` not commandable, `400` unknown/unsupported mode, `403` out
   * of scope — all reject the returned promise, decoded by the caller via `describeHttpError`
   * (`core/api-error.ts`'s existing switch already surfaces a `400`/`409`'s `message` verbatim and a
   * `403` as an access sentence — no new error decoding needed for this trio). No client-side
   * precondition check — the caller's own confirm dialog (`features/fly/flight-command-panel.ts`)
   * is what makes this safe to call directly.
   */
  setMode(assetId: string, mode: string): Promise<FlightCommandResponse> {
    return firstValueFrom(
      this.http.post<FlightCommandResponse>(`/api/assets/${encodeURIComponent(assetId)}/mode`, { mode }),
    );
  }

  /**
   * Arms the asset's active mavlink device — the app's single highest-danger action (see
   * `features/fly/arm-confirm-dialog.ts`'s own doc comment for the deliberately higher-friction
   * confirm this sits behind). `force` (omitted by default, the server treats an absent value as
   * `false`) mirrors the domain's own `MAV_CMD_COMPONENT_ARM_DISARM` force-arm param; no control in
   * this app sends `force: true` today — the confirm modal is the safety gate, not a force override
   * — but the parameter is wired through for the frozen contract's own completeness. Same error
   * shape as `setMode`.
   */
  arm(assetId: string, force?: boolean): Promise<FlightCommandResponse> {
    return firstValueFrom(
      this.http.post<FlightCommandResponse>(
        `/api/assets/${encodeURIComponent(assetId)}/arm`,
        force ? { force } : {},
      ),
    );
  }

  /** Disarms — same shape, same error handling, same `force` convention as {@link arm}. */
  disarm(assetId: string, force?: boolean): Promise<FlightCommandResponse> {
    return firstValueFrom(
      this.http.post<FlightCommandResponse>(
        `/api/assets/${encodeURIComponent(assetId)}/disarm`,
        force ? { force } : {},
      ),
    );
  }

  /**
   * Force-disarms — QGroundControl's own Emergency Stop, and the same `21196` magic parameter
   * underneath. **This stops the motors wherever the vehicle is**; a multirotor in the air falls.
   * A separate call from {@link disarm} rather than `disarm(force: true)` on purpose: the two are
   * different commands with different audit entries, and a UI that blurs them eventually fires the
   * wrong one. Same error shape as {@link setMode}.
   */
  emergencyStop(assetId: string): Promise<FlightCommandResponse> {
    return firstValueFrom(
      this.http.post<FlightCommandResponse>(`/api/assets/${encodeURIComponent(assetId)}/emergency-stop`, {}),
    );
  }

  /**
   * Fires one ArduPilot auxiliary function at one switch level — the generic escape hatch a bound
   * 2- or 3-position switch uses to reach a feature this platform has no dedicated command for
   * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md §2.3). `level` is ArduPilot's own 0 LOW / 1
   * MIDDLE / 2 HIGH, read off `GET /api/control-profiles/catalog` rather than assumed. `400` for an
   * out-of-range function/level, otherwise the same error shape as {@link setMode}.
   */
  auxFunction(assetId: string, request: AuxFunctionRequest): Promise<FlightCommandResponse> {
    return firstValueFrom(
      this.http.post<FlightCommandResponse>(`/api/assets/${encodeURIComponent(assetId)}/aux-function`, request),
    );
  }

  // --- Controller setup (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md §4.4's frozen contract) ------
  // Gated by profile *ownership*, not by visibility scope: a layout describes one person's
  // transmitter, so these answer for the signed-in operator and nobody else.

  /**
   * Every layout available to the operator — their saved profiles newest-edit-first, then the
   * platform's built-ins. A built-in comes back `active: true` exactly when they have activated no
   * saved profile for its vehicle kind, i.e. when it is what a session would really engage with.
   */
  controlProfiles(): Promise<readonly ControlProfile[]> {
    return firstValueFrom(this.http.get<ControlProfile[]>('/api/control-profiles'));
  }

  /** Everything the setup page may offer — see {@link ControlCatalog} for why this is a server read. */
  controlCatalog(): Promise<ControlCatalog> {
    return firstValueFrom(this.http.get<ControlCatalog>('/api/control-profiles/catalog'));
  }

  /** Starts a new layout as a copy of the built-in for `kind` (`201`); not activated. */
  createControlProfile(request: CreateControlProfileRequest): Promise<ControlProfile> {
    return firstValueFrom(this.http.post<ControlProfile>('/api/control-profiles', request));
  }

  /**
   * Replaces one saved layout's name and both binding maps. `400` when the layout violates its own
   * invariants — one control bound twice, one RC channel driven twice, a 3-position switch on a
   * button — surfaced verbatim by `describeHttpError`; `403` for another operator's profile; `404`
   * for one nobody saved.
   */
  updateControlProfile(id: string, request: UpdateControlProfileRequest): Promise<ControlProfile> {
    return firstValueFrom(
      this.http.put<ControlProfile>(`/api/control-profiles/${encodeURIComponent(id)}`, request),
    );
  }

  /** Makes one layout the operator's active one for its vehicle kind (`204`). */
  activateControlProfile(id: string): Promise<void> {
    return firstValueFrom(
      this.http.post<void>(`/api/control-profiles/${encodeURIComponent(id)}/activate`, {}),
    );
  }

  /** Deletes one saved layout (`204`); its vehicle kind falls back to the built-in. */
  deleteControlProfile(id: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`/api/control-profiles/${encodeURIComponent(id)}`));
  }

  // --- Guided drone onboarding (docs/plans/active/DRONE-INFRA-PLAN.md I-g's frozen wire contract) -----------

  /**
   * This platform's own reachable LAN address(es) + the MAVLink heartbeat scanner's listen port —
   * backs the onboarding wizard's "Add a real drone" Connect method: every generated config
   * snippet is parameterized by these so the operator never types an address
   * (`features/onboarding/drone-config-logic.ts#configSnippets`). Always `200`, even with an empty
   * `addresses` list (`SystemNetworkResponse`'s own doc comment) — no try/catch special-casing
   * needed here, the wizard's own store degrades on either an empty list or a rejected promise.
   */
  systemNetwork(): Promise<SystemNetworkResponse> {
    return firstValueFrom(this.http.get<SystemNetworkResponse>('/api/system/network'));
  }

  // --- Drone onboarding: vehicle profile & fleet readiness (docs/plans/active/DRONE-ONBOARDING-PLAN.md
  // §8.1's frozen wire contract, O6) --------------------------------------------------------------

  /**
   * The pre-registration probe (`OnboardingController#probeCandidate`) — observes a candidate keyed
   * only by `(bind address, sysid)`, before any asset/device exists, from the onboarding wizard's
   * new Verify step. `409 {message}` when `vision.onboarding.probe.enabled` is `false` (the default,
   * D17) or the candidate is unreachable — both share the identical status code; only the message
   * text distinguishes them (`core/readiness/readiness-logic.ts#isProbeDisabledError` matches the
   * flag-off message exactly). No 403/404 — this call is not scoped, nothing yet exists to scope
   * against.
   */
  probeVehicleCandidate(request: ProbeCandidateRequest): Promise<VehicleProfile> {
    return firstValueFrom(this.http.post<VehicleProfile>('/api/onboarding/probe', request));
  }

  /**
   * The most recently observed profile for a registered asset (`OnboardingController#profile`). A
   * read: unknown, out-of-scope, and never-probed all `404` identically — the caller degrades to
   * "no profile yet" on any rejection, never distinguishing the three.
   */
  assetProfile(assetId: string): Promise<VehicleProfile> {
    return firstValueFrom(this.http.get<VehicleProfile>(`/api/assets/${encodeURIComponent(assetId)}/profile`));
  }

  /**
   * Actively probes a registered asset's device and persists the resulting snapshot
   * (`OnboardingController#probe`). An authority action (D8): puts traffic on the aircraft's own
   * link, so it requires `canManage` on the asset — `403`, audited server-side. `404` unknown asset;
   * `409 {message}` no probeable device, or probing disabled (same shared-status-code caveat as
   * {@link probeVehicleCandidate}).
   */
  probeAsset(assetId: string): Promise<VehicleProfile> {
    return firstValueFrom(this.http.post<VehicleProfile>(`/api/assets/${encodeURIComponent(assetId)}/probe`, {}));
  }

  /**
   * One asset's full readiness report (`ReadinessController#readiness`) — never gated by
   * `vision.onboarding.probe.enabled` (verified against source: no flag check in that controller);
   * a never-probed asset still answers `200` with every feature `UNKNOWN`. `404` unknown or
   * out-of-scope asset.
   */
  assetReadiness(assetId: string): Promise<ReadinessReport> {
    return firstValueFrom(this.http.get<ReadinessReport>(`/api/assets/${encodeURIComponent(assetId)}/readiness`));
  }

  /**
   * The fleet board (`ReadinessController#fleetReadiness`): one compact row per asset the caller's
   * scope includes, never `404` (it only asks about assets already scope-filtered). Also never
   * gated by `vision.onboarding.probe.enabled` — the board renders identically whether or not
   * probing is enabled.
   */
  fleetReadiness(): Promise<FleetReadiness> {
    return firstValueFrom(this.http.get<FleetReadiness>('/api/fleet/readiness'));
  }

  /**
   * Attempts to remediate one or more readiness features against a registered asset, then re-probes
   * if anything was actually dispatched (`OnboardingController#remediate`). `403` audited (D8);
   * `404` unknown asset; `409 {message}` armed, arming unknown, or probing disabled. A request that
   * dispatches nothing (e.g. the asset was never probed) still answers `200`, every action
   * `UNSUPPORTED` — not a rejected promise.
   */
  remediateAsset(assetId: string, request: RemediationRequest): Promise<RemediationResult> {
    return firstValueFrom(
      this.http.post<RemediationResult>(`/api/assets/${encodeURIComponent(assetId)}/remediate`, request),
    );
  }

  /**
   * Writes one Tier-A/B vehicle parameter (`AssetParameterController#writeParameter`,
   * docs/plans/active/FLEET-RADIO-PLAN.md R5) — the explicit, operator-initiated act
   * `RemediationOrchestrator` deliberately refuses to synthesise (D6). `consent: true` must come only
   * from the operator's own explicit click on this call's triggering action — never defaulted, never
   * sent from an automatic/background call. `400` blank name / missing value / `consent` not exactly
   * `true` / not on the writable allowlist; `403` out of the caller's management scope, audited;
   * `404` unknown asset; `409` armed, arming unknown, or no configurable device on this deployment
   * (the same shared-status-code case every onboarding endpoint gives when
   * `vision.onboarding.probe.enabled` is at its default `false`).
   */
  writeAssetParameter(assetId: string, request: ParameterWriteRequest): Promise<ParameterWriteResponse> {
    return firstValueFrom(
      this.http.post<ParameterWriteResponse>(`/api/assets/${encodeURIComponent(assetId)}/parameters`, request),
    );
  }

  // --- System status (docs/plans/done/SYSTEM-STATUS-PLAN.md §4.3's frozen wire contract, S3) --------------

  /**
   * The platform's own live self-check — CV inference, MAVLink telemetry, video publish, live
   * updates, and whatever else is wired conditionally (the subsystem list is not fixed, see
   * `SystemStatus`'s own doc comment). Never 403s — readable by any authenticated user (§4.3's own
   * deliberate call, `models.ts#SystemStatus`'s own doc comment) — and never partially fails: one
   * misbehaving provider is reported as that one subsystem's own `'UNKNOWN'` row, not a rejected
   * promise, so `core/system-status/system-status-store.ts` only ever needs to handle "the whole
   * endpoint is unreachable" (network down, `vision.live` context gone) as its own failure case.
   */
  systemStatus(): Promise<SystemStatus> {
    return firstValueFrom(this.http.get<SystemStatus>('/api/system/status'));
  }

  // --- Ops thresholds (docs/plans/active/ASSET-FLOWS-PLAN.md §2 D6, wave S3) -------------------------------

  /** The one served severity source (`core/ops/thresholds-store.ts`) — `@OpenByDesign`, readable by
   *  any signed-in caller, never 403s. */
  opsThresholds(): Promise<OpsThresholdsResponse> {
    return firstValueFrom(this.http.get<OpsThresholdsResponse>('/api/ops/thresholds'));
  }

  // --- Auth (docs/plans/done/U-AUTH-PLAN.md wave 3's frozen contract) --------------------------------------
  // Same-origin session cookie, not a bearer token — no `withCredentials` needed on any of the
  // three calls below (or anywhere else in this class): this app is always served same-origin with
  // vision-api, either the built SPA served off vision-app's own classpath in production, or
  // `ng serve`'s dev proxy (`proxy.conf.json`) forwarding `/api` to `:8080` in dev — the browser
  // sends/receives the session cookie automatically either way, exactly like every other `HttpClient`
  // call in this file. `withCredentials` only matters for genuinely cross-origin requests.

  /**
   * The current session, or `null` when auth is enabled and there is no session (a clean `401`) —
   * folded into data here rather than left to reject, since "not logged in" is an expected,
   * first-class outcome for this one call, unlike every other 401 in this app (`describeHttpError`'s
   * generic "credentials rejected" sentence is about a *device's* auth, e.g. a bad RTSP password —
   * unrelated to this app's own session). Any other failure (network down, 5xx) still rejects the
   * returned promise; `core/auth/auth-store.ts#loadMe` is the only caller and degrades that case to
   * `'anon'` without ever claiming to know whether auth is even enabled.
   */
  async authMe(): Promise<MeResponse | null> {
    try {
      return await firstValueFrom(this.http.get<MeResponse>('/api/auth/me'));
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        return null;
      }
      throw error;
    }
  }

  /**
   * `401` (bad credentials) rejects the promise — `core/auth/auth-store.ts#login` turns that into an
   * inline form error, never a toast (a login failure is squarely the login form's own business).
   * `kiosk` (docs/plans/active/AUTH-ROLES-PLAN.md §3.5/§3.7, wave B3) requests the long-lived session an
   * always-on wall display needs — omitted callers get the ordinary session, unchanged.
   */
  authLogin(username: string, password: string, kiosk?: boolean): Promise<MeResponse> {
    return firstValueFrom(
      this.http.post<MeResponse>('/api/auth/login', { username, password, kiosk: kiosk ?? false }),
    );
  }

  /** `204` on success. `core/auth/auth-store.ts#logout` clears its local session regardless of whether this call itself succeeds — there is no state left to reconcile either way. */
  authLogout(): Promise<void> {
    return firstValueFrom(this.http.post<void>('/api/auth/logout', {}));
  }

  // --- Bootstrap + password management (docs/plans/active/AUTH-ROLES-PLAN.md §3.5, wave B3) -----------------

  /** Whether a first-admin bootstrap is still needed (`GET /api/auth/bootstrap`) — a one-way latch, see `BootstrapStatusResponse`'s own doc comment. `core/auth/auth-store.ts` is the only caller. */
  bootstrapStatus(): Promise<BootstrapStatusResponse> {
    return firstValueFrom(this.http.get<BootstrapStatusResponse>('/api/auth/bootstrap'));
  }

  /** Creates the first administrator (`POST /api/auth/bootstrap`) and returns their session, same shape as `authLogin`. `409` (`ALREADY_INITIALIZED`) if an ADMIN already exists; `400` (`WEAK_PASSWORD`) if the chosen password fails `PasswordPolicy`. */
  bootstrap(request: BootstrapRequest): Promise<MeResponse> {
    return firstValueFrom(this.http.post<MeResponse>('/api/auth/bootstrap', request));
  }

  /** Self-service password change (`POST /api/auth/password`) — re-confirms the current password. `401` if `currentPassword` is wrong; `400` (`WEAK_PASSWORD`) if the new one fails policy. */
  changePassword(request: ChangePasswordRequest): Promise<void> {
    return firstValueFrom(this.http.post<void>('/api/auth/password', request));
  }

  /** An admin/manager sets another user's password on their behalf (`POST /api/users/{id}/password`) — always forces `mustChangePassword` on the target. `core/org/org-store.ts` is the only caller. */
  adminSetPassword(id: string, request: AdminSetPasswordRequest): Promise<void> {
    return firstValueFrom(
      this.http.post<void>(`/api/users/${encodeURIComponent(id)}/password`, request),
    );
  }

  /** Wholesale-replaces a user's group memberships (`PUT /api/users/{id}/memberships`) — not a delta. `403` if any grant exceeds the caller's own scope. */
  setMemberships(id: string, request: SetMembershipsRequest): Promise<UserSummary> {
    return firstValueFrom(
      this.http.put<UserSummary>(`/api/users/${encodeURIComponent(id)}/memberships`, request),
    );
  }

  // --- Org settings: users, groups (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2's frozen contract) --------
  // ADMIN/MANAGER-only surfaces server-side; the UI additionally role-gates the route + nav link so
  // a pilot never reaches them (`core/org/org-guard.ts`). `core/org/org-store.ts` is the only caller.

  listUsers(): Promise<UserSummary[]> {
    return firstValueFrom(this.http.get<UserSummary[]>('/api/users'));
  }

  /** Invite/create a user. A `403` (grant above the inviter's own scope) or `409` (username taken) rejects — `core/org/org-store.ts` turns each into one explained toast. */
  createUser(request: CreateUserRequest): Promise<UserSummary> {
    return firstValueFrom(this.http.post<UserSummary>('/api/users', request));
  }

  /** Enable/disable a user (a disabled user can't log in). Returns the updated user. */
  setUserEnabled(id: string, enabled: boolean): Promise<UserSummary> {
    return firstValueFrom(
      this.http.post<UserSummary>(`/api/users/${encodeURIComponent(id)}/enabled`, { enabled }),
    );
  }

  listGroups(): Promise<GroupSummary[]> {
    return firstValueFrom(this.http.get<GroupSummary[]>('/api/groups'));
  }

  createGroup(request: CreateGroupRequest): Promise<GroupSummary> {
    return firstValueFrom(this.http.post<GroupSummary>('/api/groups', request));
  }

  // --- Pilot assignment (docs/plans/done/U-SCOPE-PLAN.md feature 2) ----------------------------------------
  // `PUT`/`DELETE` are idempotent and answer `204`; a `403` means the asset is outside the acting
  // manager's scope, a `404` an unknown asset. `features/asset-detail/pilots-card.ts` handles both.

  /** The pilots assigned to an asset. `404`s (rejects) for an unknown or out-of-scope asset — existence isn't revealed, the same rule the scoped asset read follows. */
  listAssetPilots(assetId: string): Promise<AssignedPilot[]> {
    return firstValueFrom(
      this.http.get<AssignedPilot[]>(`/api/assets/${encodeURIComponent(assetId)}/pilots`),
    );
  }

  /**
   * Assign a pilot to an asset (idempotent, `204`). `403` = the asset is outside the caller's scope.
   * `role` (docs/plans/active/AUTH-ROLES-PLAN.md §3.4, wave B3) is the seat granted — omitted defaults to
   * `'PILOT'` server-side, same as before this wave existed.
   */
  assignPilot(assetId: string, userId: string, role?: AssignmentRole): Promise<void> {
    return firstValueFrom(
      this.http.put<void>(
        `/api/assets/${encodeURIComponent(assetId)}/pilots/${encodeURIComponent(userId)}`,
        role ? { role } : {},
      ),
    );
  }

  /** Unassign a pilot from an asset (idempotent, `204`). */
  unassignPilot(assetId: string, userId: string): Promise<void> {
    return firstValueFrom(
      this.http.delete<void>(
        `/api/assets/${encodeURIComponent(assetId)}/pilots/${encodeURIComponent(userId)}`,
      ),
    );
  }

  /** The acting user's own asset assignments — the assets they may fly (`GET /api/me/assignments`). The "who" is the session, never a path param, so a user only ever reads their own. */
  myAssignments(): Promise<Assignment[]> {
    return firstValueFrom(this.http.get<Assignment[]>('/api/me/assignments'));
  }

  /**
   * The acting user's own recent activity, newest first (`GET /api/me/activity`, docs/plans/done/U-SCOPE-PLAN.md
   * feature 7). `limit` is only added to the query string when given (the backend defaults to 50,
   * caps at 500, floors at 1) — `features/activity/activity.ts` is the only caller.
   */
  myActivity(limit?: number): Promise<AuditEntry[]> {
    return firstValueFrom(
      this.http.get<AuditEntry[]>('/api/me/activity', limit === undefined ? {} : { params: { limit } }),
    );
  }

  /**
   * The fleet-wide audit trail, newest first (`GET /api/audit`, docs/plans/done/OPS-UX-PLAN.md §3 B1) —
   * unlike {@link myActivity} above, not scoped to the caller's own actions. Gated server-side on
   * `VisibilityScope#canManageOrg()` (`AuditController`'s own class javadoc); a PILOT session gets a
   * `403` with a real message (`ErrorResponse.message`), which `features/audit/audit-facade.ts`
   * surfaces via `describeHttpError` exactly like any other rejected call, rather than rendering an
   * empty table that would read as "nothing ever happened". `targetType`/`targetId` mirror the
   * backend's own optional target-scoping pair (must be supplied together, or not at all — enforced
   * server-side); `features/audit/**` never uses them today (its own actor/action filters are
   * client-side over one already-fetched page), but they're wired through here rather than a
   * narrower single-purpose method, since this is `AuditController#list`'s one full surface.
   */
  listAudit(params?: { readonly targetType?: string; readonly targetId?: string; readonly limit?: number }): Promise<AuditEntry[]> {
    const query: Record<string, string | number> = {};
    if (params?.targetType !== undefined) {
      query['targetType'] = params.targetType;
    }
    if (params?.targetId !== undefined) {
      query['targetId'] = params.targetId;
    }
    if (params?.limit !== undefined) {
      query['limit'] = params.limit;
    }
    return firstValueFrom(
      this.http.get<AuditEntry[]>('/api/audit', Object.keys(query).length > 0 ? { params: query } : {}),
    );
  }

  // --- CV training / dataset improvement loop (docs/plans/done/CV-TRAINING-PLAN.md §3-4's frozen wire
  // contract, Wave T5) — capture a live frame + its detections into a dataset, correct the boxes,
  // export a YOLO dataset. Every method below is gated server-side by `vision.training.enabled`
  // (default `false`) and 404s as a whole when it's off — `core/training/training-store.ts` is the
  // one place that's turned into an honest "not enabled here" state; every other caller here just
  // lets the rejected promise propagate like any other endpoint in this class.

  /** Every dataset in the caller's scope (`DatasetsResponse#datasets`, not a bare array — mirrors `CvModelsResponse`'s own wrapped-list shape). */
  listDatasets(): Promise<DatasetsResponse> {
    return firstValueFrom(this.http.get<DatasetsResponse>('/api/datasets'));
  }

  /** `403` when the caller may not manage the organization (create is a manage-org action, unlike capture/label below) — `core/training/training-store.ts` surfaces it as a toast. */
  createDataset(request: CreateDatasetRequest): Promise<Dataset> {
    return firstValueFrom(this.http.post<Dataset>('/api/datasets', request));
  }

  /** `403` is the dataset's own **non-hiding** scope check (`DatasetService#get`'s deliberate deviation from the usual "out of scope reads 404" rule — see vision-api/MODULE.md); `404` unknown id. */
  getDataset(id: string): Promise<Dataset> {
    return firstValueFrom(this.http.get<Dataset>(`/api/datasets/${encodeURIComponent(id)}`));
  }

  /** Does not cascade to the dataset's own samples/images (`DatasetService#delete`'s own contract). `204` on success, idempotent is **not** guaranteed (a repeat call 404s once actually gone). */
  deleteDataset(id: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`/api/datasets/${encodeURIComponent(id)}`));
  }

  /**
   * Captures the stream's current **raw**, full-resolution frame + its latest detections into a
   * new `PENDING` sample (annotations pre-filled `source: 'MODEL'`) — the operator-tap "Add to
   * dataset" gesture (docs/plans/done/CV-TRAINING-PLAN.md §B). `404` when the dataset is unknown or the stream
   * has no frame published yet; `403` dataset/source asset outside scope.
   */
  captureSample(streamId: string, datasetId: string): Promise<TrainingSample> {
    return firstValueFrom(
      this.http.post<TrainingSample>(`/api/streams/${encodeURIComponent(streamId)}/samples`, {
        datasetId,
      }),
    );
  }

  /** One dataset's samples, optionally filtered by status (`SamplesResponse#samples`). `limit` defaults server-side to 50 when omitted. */
  datasetSamples(datasetId: string, status?: SampleStatus, limit?: number): Promise<SamplesResponse> {
    const params: Record<string, string | number> = {};
    if (status !== undefined) {
      params['status'] = status;
    }
    if (limit !== undefined) {
      params['limit'] = limit;
    }
    return firstValueFrom(
      this.http.get<SamplesResponse>(`/api/datasets/${encodeURIComponent(datasetId)}/samples`, { params }),
    );
  }

  /**
   * The path for a sample's captured frame (docs/plans/done/CV-TRAINING-PLAN.md §3/§D — the raw, pre-overlay,
   * full-resolution JPEG, not the dashboard's downscaled `snapshotUrl`) — not promise-returning, like
   * `assetImageUrl`/`snapshotUrl` above: meant to be bound straight to an `<img src>`, which fetches
   * it itself and degrades to its own `error` handler on a `404` (unknown sample or no image stored).
   */
  sampleImageUrl(sampleId: string): string {
    return `/api/samples/${encodeURIComponent(sampleId)}/image`;
  }

  /**
   * Confirm/correct: replaces a sample's annotations and sets its terminal status (`LABELED`/
   * `DISCARDED`). `400` when an annotation's label isn't a member of the dataset's own `classes` —
   * `features/labeling/sample-editor-logic.ts#validateAnnotations` checks this client-side first so
   * the confirm button is disabled before the request ever goes out, but the server is the one real
   * authority (a dataset's `classes` can't be edited from this UI once samples exist).
   */
  putSampleAnnotations(sampleId: string, request: LabelAnnotationsRequest): Promise<TrainingSample> {
    return firstValueFrom(
      this.http.put<TrainingSample>(`/api/samples/${encodeURIComponent(sampleId)}/annotations`, request),
    );
  }

  /** Captures a frame from a finished usage's recording at `atSeconds` past its start, with the
   *  nearest stored detections pre-filled as MODEL annotations. 404 = unknown usage / no recorded
   *  stream / nothing recorded at that instant; 403 = dataset or asset out of scope. */
  captureReplaySample(usageId: string, datasetId: string, atSeconds: number): Promise<TrainingSample> {
    return firstValueFrom(
      this.http.post<TrainingSample>(`/api/usages/${encodeURIComponent(usageId)}/samples`, {
        datasetId,
        atSeconds,
      }),
    );
  }

  // --- CV profiles (docs/plans/active/CV-SETTINGS-PLAN.md §5's frozen wire contract, wave W6) -------------
  // `features/vision-profiles/**`'s `/vision/profiles` page is the one caller. Every method here
  // codes against §5.2's endpoint table exactly — the backend (`contexts/vision-perception` +
  // `vision-learning` + `storage/persistence`, waves W2-W4) had not shipped when this wave landed, so
  // every read degrades to a visible notice on 404/transport failure rather than a fabricated row
  // (§3.5 rule 2), and the page states once that a profile applies only at stream start.

  /** Every profile in the caller's scope, built-in ones included (`CvProfilesResponse#profiles`). */
  getCvProfiles(): Promise<CvProfilesResponse> {
    return firstValueFrom(this.http.get<CvProfilesResponse>('/api/cv/profiles'));
  }

  /** Creates a non-built-in profile for the caller's own org. `403` when the caller may not manage
   * the organization (`canManageOrg`-gated on this page); `400` a value fails the domain record's own
   * compact-constructor validation. */
  createCvProfile(request: CvProfileRequest): Promise<CvProfile> {
    return firstValueFrom(this.http.post<CvProfile>('/api/cv/profiles', request));
  }

  /** Updates an existing, non-built-in profile in place. `404` unknown id; `403` a built-in profile
   * (not editable — "Fork" creates an editable copy instead, never an in-place edit) or out-of-scope
   * org; `400` a value fails validation. */
  updateCvProfile(id: string, request: CvProfileRequest): Promise<CvProfile> {
    return firstValueFrom(this.http.put<CvProfile>(`/api/cv/profiles/${encodeURIComponent(id)}`, request));
  }

  /** `404` unknown id; `409` reserved for "still bound somewhere" (the page still attempts the call
   * and surfaces whatever the server says via `describeHttpError` — no client-side precondition
   * check invented ahead of the real one). */
  deleteCvProfile(id: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`/api/cv/profiles/${encodeURIComponent(id)}`));
  }

  /** Sets (or replaces) the profile bound at one scope. `404` unknown `profileId`; `400` a
   * `scopeId` that doesn't resolve (unknown group/category slug/asset id). */
  setCvProfileBinding(request: CvProfileBindingRequest): Promise<CvProfileBinding> {
    return firstValueFrom(this.http.put<CvProfileBinding>('/api/cv/bindings', request));
  }

  /** Clears whatever profile is bound at one scope, falling back to the next level up
   * (§3.1's hierarchy) — never sends `profileId` (see `CvProfileBindingRequest`'s own doc comment). */
  deleteCvProfileBinding(scopeKind: BindingScope, scopeId: string): Promise<void> {
    return firstValueFrom(
      this.http.delete<void>('/api/cv/bindings', { body: { scopeKind, scopeId } satisfies CvProfileBindingRequest }),
    );
  }

  /** The profile that would actually apply to `assetId`'s next stream start, and which binding
   * produced it (`EffectiveCvProfile#source`). `404` unknown asset. */
  getEffectiveCvProfile(assetId: string): Promise<EffectiveCvProfile> {
    return firstValueFrom(
      this.http.get<EffectiveCvProfile>('/api/cv/profiles/effective', { params: { assetId } }),
    );
  }

  /** The coverage table's one fleet-wide read (`CvCoverageResponse#rows` — §4's UI sketch: "asset ·
   * category · profile · source · detection · model · filters"). Never errors server-side for an
   * empty fleet (an empty `rows` array reads as the honest "no assets yet" empty state, not a
   * failure); a transport failure still degrades to the page's own notice. */
  getCvCoverage(): Promise<CvCoverageResponse> {
    return firstValueFrom(this.http.get<CvCoverageResponse>('/api/cv/coverage'));
  }

  // --- CV model registry (docs/plans/active/CV-SETTINGS-PLAN.md §3.2/§5.2, wave W8) — promote/rollback,
  // behind `features/models/**`'s registry page. Gated by `vision.cv.registry.enabled` (default
  // `vision.cv.enabled`), **not** `vision.training.enabled` — `ModelRegistryController`'s own
  // javadoc. The roster read itself lives above (`getCvModels()`); `GET /api/cv/registry/models` no
  // longer exists.

  /**
   * Promotes `id`@`version` to `LIVE`, demoting whichever model was previously `LIVE` to `RETIRED`.
   * `403` when the caller may not administer the organization (`canAdminister` — ADMIN only, a
   * narrower gate than `canManageOrg`); `409` when cv-service refuses (an unknown id — the artifact
   * hasn't been rsync'd into its model directory yet); `400` a malformed `version`
   * (`ModelRef`'s own compact-constructor check — see `features/models/models-logic.ts#resolvePromoteVersion`
   * for why the caller never sends a roster row's own often-blank `version` verbatim). `404` when
   * `vision.cv.registry.enabled` is off (the whole controller is absent from the context).
   */
  promoteModel(id: string, version: string): Promise<PromotionResultResponse> {
    return firstValueFrom(
      this.http.post<PromotionResultResponse>(
        `/api/cv/registry/models/${encodeURIComponent(id)}/promote`,
        { version } satisfies PromoteModelRequest,
      ),
    );
  }

  /**
   * Restores whichever model the most recent {@link promoteModel} call demoted to `RETIRED` back to
   * `LIVE`, retiring the current live model in its place. `403` — same `canAdminister` gate as
   * {@link promoteModel}; `409` when there is nothing to roll back to (no promotion has happened
   * yet on this deployment); `404` when `vision.cv.registry.enabled` is off.
   */
  rollbackModel(): Promise<PromotionResultResponse> {
    return firstValueFrom(this.http.post<PromotionResultResponse>('/api/cv/registry/rollback', {}));
  }

  // --- CV training-job flow (docs/plans/done/CV-TRAINING-PLAN.md §7-8, Phase 2's last web wave) — starting a
  // fine-tune run against a dataset and polling its progress. Gated by the same
  // `vision.training.enabled` flag as every method above; `features/training-jobs/**` is the one
  // consumer.

  /**
   * Starts a fine-tune job against `datasetId`. `403` when the caller may not manage the
   * organization (starting a training run is a privileged control-plane action, the same footing as
   * promoting a model); `400` a blank `baseModel` or non-positive `epochs`
   * (`TrainingJobSpec`'s own compact-constructor checks). The response is the freshly started job's
   * initial state — present immediately, poll it via {@link trainingJob}.
   */
  startTrainingJob(datasetId: string, request: StartTrainingJobRequest): Promise<TrainingJobResponse> {
    return firstValueFrom(
      this.http.post<TrainingJobResponse>(`/api/datasets/${encodeURIComponent(datasetId)}/train`, request),
    );
  }

  /**
   * Polls one job's latest known state. `404` when `jobId` is unknown — never started, or evicted
   * under the backend's own finished-job retention policy. A training *failure* is reported here as
   * `state: 'FAILED'`, never as a rejected promise — only a genuine transport/server failure rejects.
   */
  trainingJob(jobId: string): Promise<TrainingJobResponse> {
    return firstValueFrom(this.http.get<TrainingJobResponse>(`/api/training/jobs/${encodeURIComponent(jobId)}`));
  }

  /** Every tracked job, newest-first by `startedAt` (`TrainingJobsResponse#jobs`). Unscoped/unaudited — any signed-in caller may poll progress. */
  trainingJobs(): Promise<TrainingJobsResponse> {
    return firstValueFrom(this.http.get<TrainingJobsResponse>('/api/training/jobs'));
  }

  // --- CV training runs (docs/plans/active/CV-SETTINGS-PLAN.md §3.3/§5.2, wave W8) — the **persisted**
  // run history behind `features/training-jobs/run-history*` (`TrainingRun`, distinct from the
  // in-memory `TrainingJobResponse` above — see that interface's own doc comment). `canManageOrg`
  // gated server-side (`TrainingJobService#runs`/`#run`), so a non-manager gets a `403`; the route
  // itself is additionally `orgGuard`-gated (`run-history.routes` — actually `training-jobs.routes.ts`),
  // so this app never issues either call for a non-manager in the first place.

  /**
   * Every persisted training run, newest-first (`TrainingRunRepositoryPort#findAll`'s own
   * documented order — `run-history-logic.ts#sortRunsNewestFirst` re-sorts defensively rather than
   * trusting that ordering blindly). `403` for a non-manager; `404` when `vision.training.enabled`
   * is off (this controller's whole route tree is absent from the context, mirroring
   * `TrainingJobController`'s existing `GET /api/training/jobs`).
   */
  getTrainingRuns(): Promise<TrainingRunsResponse> {
    return firstValueFrom(this.http.get<TrainingRunsResponse>('/api/cv/training/runs'));
  }

  /** One persisted run by id, for the run-detail view. `403` for a non-manager; `404` unknown `runId` or the feature disabled. */
  getTrainingRun(runId: string): Promise<TrainingRun> {
    return firstValueFrom(this.http.get<TrainingRun>(`/api/cv/training/runs/${encodeURIComponent(runId)}`));
  }
}
