import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type {
  ActiveStream,
  AssetDeletionResponse,
  AssetDetails,
  AssetEdit,
  AssetStats,
  AssetSummary,
  AssignedPilot,
  Assignment,
  AuditEntry,
  CreateAssetRequest,
  CreateGroupRequest,
  CreateUserRequest,
  DetectionEvent,
  DetectionResult,
  Device,
  DeviceEdit,
  FleetSummary,
  GeofenceZone,
  GeofenceZoneRequest,
  GroupSummary,
  LiveSubscription,
  MeResponse,
  ProbeDeviceRequest,
  ProbeDeviceResult,
  RegisterDeviceRequest,
  ReturnHomeResponse,
  ScanRequest,
  ScanResult,
  SettableLifecycleState,
  SimulationResponse,
  StartSimulationRequest,
  StartStreamRequest,
  StartStreamResult,
  SystemNetworkResponse,
  TelemetrySample,
  UpdateLiveTopicsRequest,
  UsageRecording,
  UsageTimeline,
  UserSummary,
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
   * query string when `true` — the warehouse page's "show archived" toggle (docs/CYCLES-PLAN.md
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

  // --- Devices — warehouse lifecycle (docs/CYCLES-PLAN.md §8's pinned contract) --------------
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
   * Recent detection results for a stream, newest first (docs/MVP1-PLAN.md §C8 bullet 3) — backs
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

  // --- Discovery -----------------------------------------------------------

  scan(request: ScanRequest = {}): Promise<ScanResult> {
    return firstValueFrom(this.http.post<ScanResult>('/api/discovery/scan', request));
  }

  // --- Device probe (docs/UX-REWORK-PLAN.md §U-d — the onboarding wizard's Test step) ---------
  // "Test before save" (UX-DESIGN §5.1): connects to a candidate connection and decodes one frame
  // without registering anything. A probe that can't produce a frame is a 422 with a specific
  // message (`describeHttpError` already surfaces it) — the wizard never lets Register/Discover
  // paths advance past a failed probe (`features/onboarding/onboarding-logic.ts#canAdvanceFromTest`).

  probeDevice(request: ProbeDeviceRequest): Promise<ProbeDeviceResult> {
    return firstValueFrom(this.http.post<ProbeDeviceResult>('/api/devices/probe', request));
  }

  // --- Assets ----------------------------------------------------------------
  // Backs the live telemetry OSD/map (docs/CYCLES-PLAN.md §2): a device's asset — and
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
   * The manager page's KPI tile row (docs/ASSET-MANAGER-PAGE-PLAN.md, Wave B item 3) — lifetime
   * flight-utilization aggregates, distinct from {@link getAsset}'s `recentUsages` (a capped
   * recent list). 404 for an unknown asset, same as {@link getAsset}; the caller degrades its own
   * KPI row to "—" rather than blocking the page on failure (this page's existing enrichment-read
   * resilience — see `AssetDetailPage#loadStats`).
   */
  assetStats(assetId: string): Promise<AssetStats> {
    return firstValueFrom(this.http.get<AssetStats>(`/api/assets/${encodeURIComponent(assetId)}/stats`));
  }

  /**
   * Creates a new asset together with its device(s) in one call (docs/UX-QUICKWINS-PLAN.md QF-2 —
   * the Devices page's "Create asset from this device" quick action, the first UI call site for an
   * endpoint that already existed server-side). Returns the full detail view (201), same shape as
   * {@link getAsset}.
   */
  createAsset(request: CreateAssetRequest): Promise<AssetDetails> {
    return firstValueFrom(this.http.post<AssetDetails>('/api/assets', request));
  }

  usageTelemetry(usageId: string, limit = 200): Promise<TelemetrySample[]> {
    return firstValueFrom(
      this.http.get<TelemetrySample[]>(`/api/usages/${encodeURIComponent(usageId)}/telemetry`, {
        params: { limit },
      }),
    );
  }

  /**
   * The flight-replay window for one usage (docs/MVP2-PLAN.md §R, R-a/R-b — `UsageTimelineController`,
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

  // --- Assets — warehouse lifecycle (docs/CYCLES-PLAN.md §8's pinned contract) ---------------

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

  // --- Asset image (docs/UX-REWORK-PLAN.md §U-d) ----------------------------------------------
  // A small binary sidecar on an asset, not a field in `AssetDetails`/`AssetSummary` itself — only
  // `hasImage` lives on those DTOs (see that field's own doc comment in `models.ts`). Upload is
  // always a client-downscaled JPEG (`features/onboarding/image-downscale.ts`), always ≤2MB per the
  // pinned contract — this class does no downscaling itself, it only moves already-prepared bytes.

  /**
   * The path for an asset's photo (docs/UX-REWORK-PLAN.md §U-d) — not promise-returning, like
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
  // Backs the "Simulate a source" wizard (docs/CYCLES-PLAN.md §4): a video file path in, a
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

  // --- Detection events (docs/MVP2-PLAN.md §E, E-a/E-b) ----------------------------------------
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

  // --- Fleet summary + stream snapshots (docs/MVP3-PLAN.md C-a/C-c) --------------------------
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
   * The path for a running stream's latest-frame JPEG thumbnail (docs/MVP3-PLAN.md C-a/C-c) — not
   * promise-returning like every other method here: meant to be bound straight to an `<img src>`,
   * which fetches it itself via the browser's own image loading, cache-busted with a query param on
   * each poll — there is nothing this class could usefully `await` on the caller's behalf.
   *
   * **Currently unused** (docs/UX-REWORK-PLAN.md §U-c): its one consumer,
   * `features/command/live-strip-tile.ts` (the Command dashboard's live-strip snapshot tiles), was
   * deleted when that section was removed from Command per the plan's own user-amendments
   * blockquote ("live strip: removed"). Left in place rather than deleted — a small, self-contained,
   * still-correct method, and out of `vision-web/MODULE.md`'s own "core/api/** mirrors the wire
   * contract 1:1" scope to prune opportunistically; a future cycle that finds no plausible use for
   * it is free to remove it then. Still lives here (not inlined at a call site, if one reappears) so
   * `VisionApi` stays "the only place the frontend knows REST URLs" (this file's own top doc
   * comment) even for a path that's never actually passed through `HttpClient`.
   */
  snapshotUrl(streamId: string): string {
    return `/api/streams/${encodeURIComponent(streamId)}/snapshot`;
  }

  // --- Live updates (docs/REALTIME-PLAN.md §4, Phase R-c) ------------------------------------
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

  // --- Geofencing (docs/OPS-CORE-PLAN.md §G's frozen wire contract) ---------------------------

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

  // --- Recording + clip export (docs/OPS-CORE-PLAN.md §R's frozen wire contract) ---------------

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

  // --- Guarded command TX (docs/DRONE-INFRA-PLAN.md I-e Stage 1's frozen contract) -------------
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

  // --- Guided drone onboarding (docs/DRONE-INFRA-PLAN.md I-g's frozen wire contract) -----------

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

  // --- Auth (docs/U-AUTH-PLAN.md wave 3's frozen contract) --------------------------------------
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

  /** `401` (bad credentials) rejects the promise — `core/auth/auth-store.ts#login` turns that into an inline form error, never a toast (a login failure is squarely the login form's own business). */
  authLogin(username: string, password: string): Promise<MeResponse> {
    return firstValueFrom(this.http.post<MeResponse>('/api/auth/login', { username, password }));
  }

  /** `204` on success. `core/auth/auth-store.ts#logout` clears its local session regardless of whether this call itself succeeds — there is no state left to reconcile either way. */
  authLogout(): Promise<void> {
    return firstValueFrom(this.http.post<void>('/api/auth/logout', {}));
  }

  // --- Org settings: users, groups (docs/U-SCOPE-PLAN.md, U-e slice 2's frozen contract) --------
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

  // --- Pilot assignment (docs/U-SCOPE-PLAN.md feature 2) ----------------------------------------
  // `PUT`/`DELETE` are idempotent and answer `204`; a `403` means the asset is outside the acting
  // manager's scope, a `404` an unknown asset. `features/asset-detail/pilots-card.ts` handles both.

  /** The pilots assigned to an asset. `404`s (rejects) for an unknown or out-of-scope asset — existence isn't revealed, the same rule the scoped asset read follows. */
  listAssetPilots(assetId: string): Promise<AssignedPilot[]> {
    return firstValueFrom(
      this.http.get<AssignedPilot[]>(`/api/assets/${encodeURIComponent(assetId)}/pilots`),
    );
  }

  /** Assign a pilot to an asset (idempotent, `204`). `403` = the asset is outside the caller's scope. */
  assignPilot(assetId: string, userId: string): Promise<void> {
    return firstValueFrom(
      this.http.put<void>(
        `/api/assets/${encodeURIComponent(assetId)}/pilots/${encodeURIComponent(userId)}`,
        {},
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
   * The acting user's own recent activity, newest first (`GET /api/me/activity`, docs/U-SCOPE-PLAN.md
   * feature 7). `limit` is only added to the query string when given (the backend defaults to 50,
   * caps at 500, floors at 1) — `features/activity/activity.ts` is the only caller.
   */
  myActivity(limit?: number): Promise<AuditEntry[]> {
    return firstValueFrom(
      this.http.get<AuditEntry[]>('/api/me/activity', limit === undefined ? {} : { params: { limit } }),
    );
  }
}
