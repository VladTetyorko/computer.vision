# vision-api

REST driving adapter: asset-first HTTP API, an SSE/WebSocket live-update transport, and an HLS
video reverse proxy, all sitting over the contexts' application-service ports. `vision-web` (the
Angular SPA, packaged in by `vision-app`) is its only client today.

**Depends on:** every context's `*.application`/`*.domain` module (compile only — never an adapter,
ArchUnit-enforced) · spring-boot-starter-web · spring-boot-starter-websocket (`/ws/manual-control`)
· test: spring-boot-starter-test (JUnit 5, Mockito, MockMvc, Hamcrest)
**Used by:** vision-app (wires beans in, packages the jar; `vision-web` is a sibling module only
vision-app depends on — see Gotchas)
**Build/test:** `./mvnw -B -pl station/vision-api test`

## Package layout

`controller/` (every `@RestController`, now including `AssetInventoryController`/
`InventoryExportController` — WAREHOUSE-UX W3, `CvProfileController` — CV-SETTINGS W5,
`DiscoveryInboxController` — ZERO-CONFIG-ONBOARDING Z2c, `DiscoveryStatusController` —
SOURCE-ONBOARDING-2 wave C, `SeatController` — CREW-CONTROL W2) · `dto/` (wire
records only, ~190 — house rule "zero DTO leakage": no domain type is ever serialized directly;
CREW-CONTROL W2 added `SeatsResponse`/`SeatHolderResponse`/`TakeSeatRequest`) ·
`security/` (`CurrentUser`/
`PrincipalResolver`/`StreamAccess`/`OpenByDesign`/`AssetAuthority`/`CapabilityAssetAuthority`/
`SeatAccess`/`SeatAccessSettings` — the
authorization seam, see Conventions) ·
`live/` (SSE connection registry, per-topic ring buffers, per-connection visibility filtering,
`SystemStatusSampler` — LIVE-POLL-RETIREMENT-PLAN wave L4, the server-side change-detecting sampler
that owns the `system` topic's schedule, see "Live updates" below) ·
`ws/` (`/ws/manual-control` raw `WebSocketHandler`) · `proxy/` (`HlsProxyController` — a pass-through
edge owning no application service) · `ratelimit/` (`RateLimitFilter`/`TokenBucket`, per-principal
`/api/**` token bucket) · `support/` (edge-local helpers: `SnapshotJpegEncoder`, `CapabilityParsing`,
`DeviceOriginParsing`, `RemediationOrchestrator`, `VisionApiProperties`, `SystemStatusReader` — LIVE-POLL-RETIREMENT
wave L4a, `safeStatus`/`overall`/`worstHealth` extracted from `SystemStatusController` so
`SystemStatusSampler` can reuse the exact same rollup logic, see "Live updates" below —
`DiscoveryStatusFacts` — the
plain (non-DTO) crossing-seam payload behind `GET /api/discovery/status`, `InventoryExportService` —
WAREHOUSE-UX W3, the hand-rolled CSV behind `GET /api/inventory/export`; `AssetRowFacts` — WAREHOUSE-UX
W8, bundles the `firmware`/`totalFlightSeconds` cross-context joins `AssetController` needs, see
Conventions; `SeatSupport` — CREW-CONTROL W2, bundles `SeatAccess`'s device/stream→asset resolution,
asset ownership, display-name lookup, and `FORCE`/`DENIED:SEAT_HELD` audit writes, see Conventions) ·
`demo/` (property-gated
demo-data seeding, deletable as one unit) · `exception/` (`ApiExceptionHandler` + api-local
exceptions) · `config/` (MVC/WebSocket/SPA `@Configuration`).

## API surface

### Authorization tags used in the table below

Every handler is either scoped, carries `@OpenByDesign`, or is named in the `TEMPORARY_UNSCOPED`
ledger (`EndpointAuthorizationTest`, vision-app) — there is no fourth state. See Conventions for
the full mechanism.

| Tag | Means |
|---|---|
| `scope` | `CurrentUser#scope()`, a `VisibilityScope`. Single-resource read/write → **404** if out of scope (existence hidden). List read → silently filtered, never 403. |
| `manage` | `authority.mayManageFleet(ownership)` (`CurrentUser#authority()`, wave B6 — was the now-deleted `scope.canManage(ownership)`) — visible but not manageable → **403**. |
| `manageOrg` | `authority.mayManageOrg()` (ADMIN or MANAGER; wave B6 — was `scope.canManageOrg()`. A `VIEWER` now also resolves a `GROUPS`-shaped scope, contexts/vision-identity's own wave B6, but still fails this gate — `mayManageOrg()` additionally requires `Capability.MANAGE_ORG`, which `RoleAuthority` never grants `VIEWER`) → **403** otherwise. |
| `administer` | `authority.mayAdminister()` (deployment-global, no group boundary; wave B6 — was `scope.canAdminister()`) → **403** otherwise. |
| `self` | Filtered to the caller's own id; takes no target-user/asset parameter, so there is nothing to authorize against. |
| `viewer:view`/`:contribute`/`:manage` | `MapAccessPolicy` gates via `CurrentUser#viewer()` — a model kept deliberately separate from `VisibilityScope` (see `contexts/vision-map/MODULE.md`). |
| `own profile` | Gated by profile ownership inside the application service, not `VisibilityScope` — see `ControlProfileController` note below the table. |
| `open` | `@OpenByDesign` — reachable by any authenticated caller by design. |
| `unscoped` | **No check at all.** Named in `TEMPORARY_UNSCOPED` — a real, currently-open gap, not a decision. |

### Endpoints

| Controller | Method | Path | Does | Access |
|---|---|---|---|---|
| AssetController | POST | `/api/assets` | Create asset | manageOrg |
| AssetController | GET | `/api/assets?includeDeleted=` | List assets — each row now carries `firmware`/`totalFlightSeconds` (WAREHOUSE-UX W8, see Conventions) | scope |
| AssetController | GET | `/api/assets/{id}` | Asset detail — same `firmware`/`totalFlightSeconds` join as the list | scope |
| AssetController | PATCH | `/api/assets/{id}` | Update asset | scope for `displayName`/`attributes` only; `category` (or any managed field) needs manage — see "Authority split" in Conventions |
| AssetController | POST | `/api/assets/{id}/state` | Lifecycle transition (active/deactivated/deleted) | manage |
| AssetController | DELETE | `/api/assets/{id}` | Soft delete (archive) | manage |
| AssetController | POST | `/api/assets/{id}/devices` | Attach a device | manage |
| AssetController | DELETE | `/api/assets/{id}/devices/{deviceId}` | Detach a device | manage |
| AssetController | GET | `/api/usages/{usageId}/telemetry?limit=` | Raw (unwindowed) telemetry trail — the **latest** `limit` samples, ascending (COMMAND-MAP-FLOW-PLAN.md B1; was earliest-first) | **unscoped** (ledger: `AssetController#telemetry`) |
| AssetInventoryController | POST | `/api/assets/{id}/custody` | Issue to a custodian / return to stock (`{action:ISSUE\|RETURN,custodianId?,location?}`) | manage (via `AssetCustodyService`) |
| AssetInventoryController | POST | `/api/assets/{id}/inventory` | Ground / release / retire (`{action:GROUND\|RELEASE\|RETIRE,kind?,summary?}`) | manage (via `AssetCustodyService`) |
| AssetInventoryController | GET | `/api/assets/{id}/maintenance` | List an asset's maintenance history, open and closed | scope (via `MaintenanceService`) |
| AssetInventoryController | POST | `/api/assets/{id}/maintenance` | Open a maintenance record directly, without also grounding | manage (via `MaintenanceService`) |
| AssetInventoryController | POST | `/api/assets/{id}/maintenance/{recordId}/close` | Close an open record | manage (via `MaintenanceService`) |
| AssetInventoryController | GET | `/api/maintenance?state=open\|closed\|all&limit=` | Fleet-wide maintenance read across every in-scope asset, each row carrying `assetId`/`assetName`/`categoryId` (WAREHOUSE-UX W8) | scope (via `MaintenanceService#fleetWide`) |
| InventoryExportController | GET | `/api/inventory/export?format=csv` | Hand-rolled CSV, one row per visible asset (id/name/category/serial/make/model/registration/inventoryState/custodian/location/lifecycle/createdAt/lastFlownAt) | scope |
| AssetStreamController | POST | `/api/assets/{id}/stream` | Start the asset's video stream | scope |
| AssetStreamController | DELETE | `/api/assets/{id}/stream` | Stop it (idempotent) | scope |
| AssetSessionController | POST | `/api/assets/{id}/session` | Operator "engage" — opens/promotes a usage, no video, no device traffic; the response's `pilotId` is now `CurrentUser#userId()` (ASSET-FLOWS-PLAN §2 D1p, wave BK4 — passed straight through to `UsageTracker#engage`, the caller's own identity, never a request body field) | scope |
| AssetSessionController | DELETE | `/api/assets/{id}/session` | Operator "disengage" (idempotent; demotes rather than closes if a stream is still running); records an `AuditTrailPort` entry (`AuditAction.UPDATED`/`AuditTargetType.ASSET`) naming the calling `CurrentUser#userId()` when something was actually engaged — no entry when nothing was (AUTH-ROLES-PLAN.md D17, wave B4; attribution only, no seat/arbitration logic — CREW-CONTROL's concern) | scope |
| AssetStatsController | GET | `/api/assets/{id}/stats` | KPI tile row (flight time/count, last battery, …) | scope |
| AssetImageController | PUT | `/api/assets/{id}/image` | Upload cover image (≤2 MB, `AssetImageRepositoryPort` called directly) | scope |
| AssetImageController | GET | `/api/assets/{id}/image` | Fetch it | scope |
| AssetImageController | DELETE | `/api/assets/{id}/image` | Remove it (idempotent) | scope |
| CategoryController | GET | `/api/categories` | Category taxonomy | open |
| CategoryController | POST | `/api/categories` | Create a category | manageOrg |
| CategoryController | PUT | `/api/categories/{id}` | Replace a category's mutable fields (whole-record, not a patch) | manageOrg |
| DeviceController | POST | `/api/devices` | Register a device | manageOrg |
| DeviceController | GET | `/api/devices?includeDeleted=` | List devices | scope (filtered) |
| DeviceController | PATCH | `/api/devices/{id}` | Update a device | scope (deliberately not `manage` — a PILOT may edit their own assigned camera) |
| DeviceController | POST | `/api/devices/{id}/state` | Lifecycle transition | scope |
| DeviceController | DELETE | `/api/devices/{id}` | Soft delete | manageOrg |
| DeviceProbeController | POST | `/api/devices/probe` | Test-connect a caller-supplied protocol+uri; never registers anything | open |
| StreamController | POST | `/api/devices/{deviceId}/stream` | Start a stream on a device | scope |
| StreamController | GET | `/api/streams` | List active streams | scope (filtered) |
| StreamController | DELETE | `/api/streams/{streamId}` | Stop (idempotent no-op if unknown/stopped) | scope |
| StreamController | GET | `/api/streams/{streamId}/detections?limit=` | Recent per-frame detections | scope |
| StreamController | GET | `/api/streams/{streamId}/snapshot` | Latest frame as downscaled JPEG (only binary, non-JSON response besides the HLS proxy) | scope |
| StreamController | PATCH | `/api/streams/{streamId}/config` | Hot-patch confidence/fps/labelFilter/model/tracking — never interrupts video | scope |
| StreamController | GET | `/api/streams/{streamId}/tracks` | Track book + duty-cycle stats + the held `FOLLOW` lock's own lifecycle (`follow`, TRACK-FOLLOW-PLAN §3.1 — omitted until a lock is issued); never errors on unknown stream (empty `tracks`) | scope |
| HlsProxyController | GET | `/hls/{streamId}/**` | Reverse-proxy this asset's live HLS bytes to the mediamtx sidecar | scope (`StreamAccess#requireVisibleForHlsProxy`, checked **before** the upstream is ever contacted; fails closed on an unknown/stopped id — AUTH-ROLES-PLAN.md D10, wave B4 — unlike the other `StreamAccess`-gated rows above, which keep `requireVisible`'s no-op) — also now behind `SecurityConfig`'s secured chain's `authenticated()` rule (`/hls/**` joined `/api/**`/`/ws/**`) |
| CvModelsController | GET | `/api/cv/models` | Detection-model roster — widened (CV-SETTINGS-PLAN §5.2) to serve the registry's live roster (`registrySource: true`) when `vision.cv.registry.enabled`, else the static config catalogue; never errors | open |
| CvTrackersController | GET | `/api/cv/trackers` | Static tracker-engine roster | open |
| OpsThresholdsController | GET | `/api/ops/thresholds` | Battery urgency thresholds (ASSET-FLOWS-PLAN §2 D6) + RC neutral-stick tolerance (FLY-CONTROL-UX-PLAN §2/BK1) — `{"battery":{"warningPercent":25,"criticalPercent":10},"rc":{"neutralTolerancePercent":5}}`, frozen wire shape, values from `vision.ops.battery.*`/`vision.ops.rc.*` | open |
| CvProfileController | GET | `/api/cv/profiles` | List profiles the caller may see (every built-in + the caller's own group's) | scope |
| CvProfileController | GET | `/api/cv/profiles/{id}` | Read one profile | scope |
| CvProfileController | POST | `/api/cv/profiles` | Create a profile owned by the caller's own group (201) | manageOrg |
| CvProfileController | PUT | `/api/cv/profiles/{id}` | Replace a non-built-in profile wholesale | manageOrg |
| CvProfileController | DELETE | `/api/cv/profiles/{id}` | Delete a non-built-in, unbound profile (204) | manageOrg |
| CvProfileController | PUT | `/api/cv/bindings` | Bind a profile to a scope (`ASSET`/`CATEGORY`/`ORGANIZATION`), replacing any prior binding at that exact scope | manageOrg |
| CvProfileController | DELETE | `/api/cv/bindings` | Clear a scope's binding, idempotent (204) — body-carrying DELETE, the binding's natural key has no single path id | manageOrg |
| CvProfileController | GET | `/api/cv/profiles/effective?assetId=` | The one profile `assetId` would start with right now, plus which layer of the asset→category→organization→platform fold supplied it (`source`) | scope |
| CvProfileController | GET | `/api/cv/coverage` | Fleet-wide "what CV will do on each asset" table, one row per visible asset | scope |
| EventController | GET | `/api/events?sinceMs&limit` | Cross-stream debounced detection events, newest first | scope (filtered) |
| EventController | GET | `/api/streams/{streamId}/events?limit` | One stream's events | scope |
| FleetController | GET | `/api/fleet/summary?includeArchived=` | Per-category counts + attention list | scope |
| ReadinessController | GET | `/api/assets/{assetId}/readiness` | One asset's onboarding-readiness report | scope |
| ReadinessController | GET | `/api/fleet/readiness` | Readiness row per visible asset | scope |
| FlightCommandController | POST | `/api/assets/{id}/return-home` | RTL | scope + `AssetAuthority#mayFly` (AUTH-ROLES-PLAN.md §3.8, wave B4) |
| FlightCommandController | POST | `/api/assets/{id}/mode` | Flight-mode change | scope + `mayFly` |
| FlightCommandController | POST | `/api/assets/{id}/arm` | Arm (optional `force`) | scope + `mayFly` |
| FlightCommandController | POST | `/api/assets/{id}/disarm` | Disarm (optional `force`) | scope + `mayFly` |
| FlightCommandController | POST | `/api/assets/{id}/emergency-stop` | Forced disarm, kept separate from `disarm{force}` for audit-trail clarity | scope + `mayFly` |
| FlightCommandController | POST | `/api/assets/{id}/aux-function` | `MAV_CMD_DO_AUX_FUNCTION` | scope + `mayFly` |
| FlightCommandController | GET | `/api/assets/{id}/flight-capabilities` | What this asset supports commanding | scope |
| SeatController | GET | `/api/assets/{id}/seats` | Both seats' current holder/expiry + the caller's own `mayTakeFlight`/`mayTakeCamera`/`mayForceSeat` (CREW-CONTROL-PLAN.md §3.6, wave W2) | scope |
| SeatController | POST | `/api/assets/{id}/seats/{kind}` | Take-or-renew (`kind` = `flight`\|`camera`); optional `{"force":true}` body, honoured only for a caller whose `mayForceSeat` is true and only against a *different* current holder — otherwise silently ignored, not rejected | scope + `SeatAccess` (§3.2 rules 2-4; 409 if held by another and not forced, 403 if the caller has no standing for that seat) |
| SeatController | DELETE | `/api/assets/{id}/seats/{kind}` | Release (idempotent for a free seat or the caller's own); a manager may evict another holder (204), firing the same RC-release hook a forced `POST` does | scope + `SeatAccess` (403 if held by another and the caller may not force) |
| ControlProfileController | GET | `/api/control-profiles` | Caller's saved layouts + built-ins; every row now also carries `stickMode`/`forwardIsUp` (C15), a built-in reporting `TransmitterView.DEFAULT` | own profile |
| ControlProfileController | GET | `/api/control-profiles/catalog` | Every enumerable setup choice (vehicle kinds, input kinds, functions, …) | own profile |
| ControlProfileController | POST | `/api/control-profiles` | Create (copy of the built-in for that vehicle kind) | own profile |
| ControlProfileController | PUT | `/api/control-profiles/{id}` | Replace the whole layout; body may also carry `stickMode?`/`forwardIsUp?` (C15's `TransmitterView`, both optional in both directions — an older client sends neither and gets the platform default; a `stickMode` outside 1-4 is a 400) | own profile |
| ControlProfileController | POST | `/api/control-profiles/{id}/activate` | Activate (≤1 active per owner+vehicle-kind) | own profile |
| ControlProfileController | DELETE | `/api/control-profiles/{id}` | Delete (not a built-in) | own profile |
| DatasetController | POST | `/api/datasets` | Create dataset | scope (`DatasetService`, gated `vision.training.enabled`) |
| DatasetController | GET | `/api/datasets` | List visible datasets | scope |
| DatasetController | GET | `/api/datasets/{id}` | Read one | scope — **403, not 404, when out of scope** (see Gotchas) |
| DatasetController | DELETE | `/api/datasets/{id}` | Delete (does not cascade to samples) | scope |
| LabelingController | POST | `/api/streams/{streamId}/samples` | Capture a training sample from a live stream's current frame | scope |
| LabelingController | POST | `/api/usages/{usageId}/samples` | Capture a training sample from a recorded flight at `atSeconds` | scope |
| LabelingController | GET | `/api/datasets/{id}/samples?status=&limit=` | List a dataset's samples | scope |
| LabelingController | GET | `/api/samples/{id}/image` | Fetch one sample's image | scope |
| LabelingController | PUT | `/api/samples/{id}/annotations` | Confirm/correct a sample's annotations | scope |
| ModelRegistryController | POST | `/api/cv/registry/models/{id}/promote` | Promote a model version to `LIVE`, demoting whatever was live to `RETIRED` | administer (ADMIN only) |
| ModelRegistryController | POST | `/api/cv/registry/rollback` | Restore whichever model the last promotion demoted | administer (ADMIN only) |
| TrainingJobController | POST | `/api/datasets/{id}/train` | Start a training job (uploads the dataset to cv-service over gRPC) | manageOrg + dataset scope |
| TrainingJobController | GET | `/api/training/jobs/{jobId}` | Poll one in-flight job's live state | **unscoped** (ledger) |
| TrainingJobController | GET | `/api/training/jobs` | List every tracked in-flight job | **unscoped** (ledger) |
| TrainingJobController | GET | `/api/cv/training/runs?limit=` | Most recently started **persisted** training runs, newest-first (default limit 50) | manageOrg |
| TrainingJobController | GET | `/api/cv/training/runs/{runId}` | One persisted run's latest state | manageOrg |
| OnboardingController | POST | `/api/onboarding/probe` | Pre-registration vehicle probe | **unscoped** (ledger — nothing yet exists to scope against) |
| OnboardingController | GET | `/api/assets/{assetId}/profile` | Latest vehicle profile | scope |
| OnboardingController | POST | `/api/assets/{assetId}/probe` | Probe a registered asset's link | manage (audited) |
| OnboardingController | POST | `/api/assets/{assetId}/remediate` | Dispatch remediation actions | manage (audited) |
| OnboardingController | GET | `/api/assets/{assetId}/usages/{usageId}/passport` | Flight passport | scope |
| OnboardingController | GET | `/api/assets/{assetId}/usages/{usageId}/drift` | Parameter drift vs. previous flight | scope |
| AssetParameterController | POST | `/api/assets/{id}/parameters` | Write one Tier-A/B vehicle parameter, explicit value + consent (FLEET-RADIO R5) | manage (Tier A)/administer (Tier B), audited |
| CameraPoseController | GET | `/api/assets/{assetId}/camera-pose` | Stored fixed-camera pose | scope |
| CameraPoseController | PUT | `/api/assets/{assetId}/camera-pose` | Set (create/replace) pose | manage |
| CameraPoseController | DELETE | `/api/assets/{assetId}/camera-pose` | Remove pose (idempotent) | manage |
| CameraPoseController | POST | `/api/assets/{assetId}/camera-pose/calibration` | Solve yaw/pitch/hfov from clicked landmarks; never persists | manage |
| GeoCorrectionController | GET | `/api/geo/corrections/live` | Latest visual-geo correction per visible asset | scope |
| GeoCorrectionController | GET | `/api/geo/corrections?usageId=&limit=` | One usage's corrections | scope |
| GeoRegionController | GET | `/api/geo/regions` | Every known reference region | **unscoped** (ledger) |
| GeoRegionController | POST | `/api/geo/regions` | Start an async ingest (202) | administer |
| GeoRegionController | DELETE | `/api/geo/regions/{regionId}` | Delete a region (idempotent) | administer |
| GeoRegionController | GET | `/api/geo/regions/{regionId}/progress` | Ingest progress | **unscoped** (ledger) |
| MapTracksController | GET | `/api/map/tracks` | Live projected fixed-camera tracks, with trail | viewer:view |
| MapLayersController | GET | `/api/map/layers` | Visible layers, COP first | viewer:view |
| MapLayersController | POST | `/api/map/layers` | Create a layer (not `COP`, which is infrastructure) | viewer:manage of the target group |
| MapLayersController | PATCH | `/api/map/layers/{id}` | Rename (COP cannot be renamed → 409) | viewer:manage |
| MapLayersController | DELETE | `/api/map/layers/{id}` | Delete, cascading to its marks/drawings (COP cannot be deleted → 409) | viewer:manage |
| MapLayersController | PUT | `/api/map/layers/{id}/grants` | Replace the grant list wholesale | viewer:manage |
| MapMarksController | GET | `/api/map/marks` | Active marks on visible layers | viewer:view |
| MapMarksController | POST | `/api/map/marks` | Create a mark (absent `layerId` = caller's default layer) | viewer:contribute |
| MapMarksController | POST | `/api/map/marks/geolocate` | Create a mark from an asset's current telemetry/gimbal pose | viewer:contribute |
| MapMarksController | PATCH | `/api/map/marks/{id}` | Edit a mark | creator-while-`UNVERIFIED` or viewer:manage of its layer |
| MapMarksController | POST | `/api/map/marks/{id}/verify` | Verify/reject | viewer:manage of the mark's layer |
| MapMarksController | POST | `/api/map/marks/{id}/promote` | Promote to another layer (absent target = COP) | viewer:manage source + viewer:contribute target |
| MapMarksController | DELETE | `/api/map/marks/{id}` | Delete | creator-while-`UNVERIFIED` or viewer:manage |
| MapDrawingsController | GET | `/api/map/drawings` | Drawings on visible layers | viewer:view |
| MapDrawingsController | POST | `/api/map/drawings` | Create | viewer:contribute |
| MapDrawingsController | PATCH | `/api/map/drawings/{id}` | Edit (points, when present, replace geometry wholesale) | creator or viewer:manage |
| MapDrawingsController | DELETE | `/api/map/drawings/{id}` | Delete | creator or viewer:manage |
| GeofenceController | GET | `/api/geofences` | List zones | open (safety data — hiding a no-fly zone is the hole, not the fix) |
| GeofenceController | POST | `/api/geofences` | Create a zone | administer |
| GeofenceController | PUT | `/api/geofences/{id}` | Replace a zone wholesale | administer |
| GeofenceController | DELETE | `/api/geofences/{id}` | Delete a zone | administer |
| DiscoveryController | POST | `/api/discovery/scan` | ONVIF/mDNS/V4L2 device scan | **unscoped** (ledger) |
| DiscoveryInboxController | GET | `/api/discovery/inbox` | `{candidates, sources}` envelope — every reported discovery candidate (newest-reported first) plus one health row per discovery mechanism (BK6/A3) | manageOrg |
| DiscoveryInboxController | POST | `/api/discovery/inbox/{id}/register` | Register a candidate as a new asset | manageOrg (checked inside `DiscoveryInboxService#register`, ownership from `CurrentUser`, never the body — see Conventions) |
| DiscoveryInboxController | POST | `/api/discovery/inbox/{id}/dismiss` | Dismiss a candidate (idempotent-in-effect: dismissing an already-dismissed candidate just re-stamps status) | manageOrg |
| DiscoveryInboxController | POST | `/api/discovery/inbox/{id}/attach` | Atomically attach a candidate's suggested stream to an **existing** asset (`{"assetId":"..."}`) — 404 unknown candidate/out-of-scope asset, 409 no suggested stream or a duplicate stream, 422 candidate already `REGISTERED` to a different asset (SOURCE-ONBOARDING-2-PLAN.md §3.2 C1) | manageOrg |
| DiscoveryInboxController | POST | `/api/discovery/inbox/{id}/restore` | Undo a dismiss — status back to `NEW`, `registeredAssetId` cleared (SOURCE-ONBOARDING-2-PLAN.md §3.2 C5) | manageOrg |
| DiscoveryStatusController | GET | `/api/discovery/status` | Sweep cadence + telemetry/video intake facts + per-source health, for a live onboarding status panel (SOURCE-ONBOARDING-2-PLAN.md §3.2 C2 — "the most important endpoint in the plan") | manageOrg |
| SimulationController | POST | `/api/simulations` | Start a synthetic (or video-fed) simulated asset | manageOrg |
| SimulationController | DELETE | `/api/simulations/{assetId}` | Stop it (idempotent) | scope |
| AuthController | POST | `/api/auth/login` | Session login (always-200 dev admin when auth disabled); body `{username,password,kiosk?}` — a non-`VIEWER` requesting `kiosk:true` is refused `400 KIOSK_NOT_PERMITTED` *after* a real successful login, and the just-established session is torn down (AUTH-ROLES-PLAN.md §3.5/§3.7, wave B3) | open |
| AuthController | POST | `/api/auth/logout` | Invalidate session (idempotent) | open |
| AuthController | GET | `/api/auth/me` | Caller's own identity; `MeResponse` now carries `capabilities[]`/`scopeKind`/`mustChangePassword` (wave B3) | open (Spring Security's chain itself 401s when auth is enabled and unauthenticated) |
| BootstrapController | GET | `/api/auth/bootstrap` | `{"required": true|false}` — `true` iff `vision.auth.enabled` and no enabled user holds an `ADMIN` membership (AUTH-ROLES-PLAN.md §3.5, wave B3) | open, anonymous, `@OpenByDesign`, `permitAll` |
| BootstrapController | POST | `/api/auth/bootstrap` | Body `{username,displayName,email,password}` → `201 MeResponse`, session established. Refused once any admin exists (`409 ALREADY_INITIALIZED`) or the password fails policy (`400 WEAK_PASSWORD`) | open only while `required`, anonymous, `@OpenByDesign` |
| AuthPasswordController | POST | `/api/auth/password` | Self-service password change, `{currentPassword,newPassword}` → `204`, clears `mustChangePassword`; `401` wrong current, `400 WEAK_PASSWORD`, `409 AUTH_DISABLED` when `vision.auth.enabled=false` | self |
| AssignmentController | PUT | `/api/assets/{assetId}/pilots/{userId}` | Assign a pilot (idempotent); body `{"role"?: "PILOT"\|"CREW"}` — absent body/field defaults to `PILOT` (byte-identical to pre-B3 callers), AUTH-ROLES-PLAN.md §3.4, wave B3 | manage |
| AssignmentController | DELETE | `/api/assets/{assetId}/pilots/{userId}` | Unassign (idempotent) | manage |
| AssignmentController | GET | `/api/assets/{assetId}/pilots` | List an asset's pilots; `PilotResponse` now carries `role` | scope |
| AssignmentController | GET | `/api/me/assignments` | Caller's own assigned assets; `AssignmentResponse` now carries `role` (defaults to `PILOT` if `roleFor` finds no link — see Gotchas) | self |
| ActivityController | GET | `/api/me/activity?limit=` | Caller's own audit entries | self |
| AuditController | GET | `/api/audit?targetType=&targetId=&limit=` | Fleet-wide audit trail | manageOrg |
| UserAdminController | GET | `/api/users` | List visible users | scope |
| UserAdminController | POST | `/api/users` | Create/invite a user | manageOrg (via `UserService`) |
| UserAdminController | POST | `/api/users/{id}/enabled` | Enable/disable a user | manageOrg |
| UserAdminController | POST | `/api/users/{userId}/password` | Admin-resets a user's password, `{"newPassword"}` → `204`, sets `mustChangePassword=true`; `400 WEAK_PASSWORD` (AUTH-ROLES-PLAN.md D13, wave B3) | manageOrg |
| UserAdminController | PUT | `/api/users/{userId}/memberships` | Wholesale-replaces a user's group memberships, `{"memberships":[{"groupId","role"}]}` → `200 UserResponse` (AUTH-ROLES-PLAN.md D14, wave B3) | manageOrg |
| GroupAdminController | GET | `/api/groups` | List visible groups | scope |
| GroupAdminController | POST | `/api/groups` | Create a group | manageOrg (via `GroupService`) |
| LiveController | GET | `/api/live?topics=` | Open an SSE connection (`text/event-stream`) | scope, per-topic (see "Live updates" below); gated by `vision.live.enabled` (default on) |
| LiveController | PATCH | `/api/live/{connectionId}/topics` | Add/remove topics on an open connection | scope + connection ownership |
| UsageTimelineController | GET | `/api/usages?limit&assetId` | Replay library list, newest first | scope |
| UsageTimelineController | GET | `/api/usages/{usageId}/timeline?fromMs&toMs&maxPoints` | Windowed, downsampled replay data | **unscoped** (ledger) |
| UsageTimelineController | GET | `/api/usages/{usageId}/recording` | Recording playback URL, if any | **unscoped** (ledger) |
| UsageTimelineController | GET | `/api/usages/by-stream/{streamId}` | "What happened to stream X" | scope |
| AfterActionController | GET | `/api/assets/{assetId}/usages/{usageId}/after-action` | JSON manifest of the evidence package | scope + export authority (`AccessDeniedException`→403 if visible but not exportable) |
| AfterActionController | GET | `/api/assets/{assetId}/usages/{usageId}/after-action/archive` | The ZIP archive, streamed (never buffered whole) | scope + export authority |
| SystemStatusController | GET | `/api/system/status` | Subsystem health rollup; never errors — rollup logic now lives in `support/SystemStatusReader#read` (LIVE-POLL-RETIREMENT wave L4a), this controller's wire output unchanged; the same reader backs the `system` SSE topic's server-side sampler (see "Live updates" below) | **unscoped** (ledger — deliberately: no secrets exposed) |
| SystemNetworkController | GET | `/api/system/network` | Host's site-local IPv4 addresses (each now carrying a `kind` — `LAN`/`VIRTUAL`/`UNKNOWN`, sorted kind-first) plus `mavlinkPort` and, when mediamtx publish is configured, `videoPushPort`/`videoPushPathPrefix` (SOURCE-ONBOARDING-2-PLAN.md §3.2 C3) | **unscoped** (ledger) |
| SystemEventsController | GET | `/api/system/events?sinceMs&limit` | Durable platform-`Event` history, newest-first (ALWAYS-ON-FLOW-PLAN wave B3) — the notification bell/`/manage/system`'s reconnect backfill; empty unless `vision.events.history.enabled` | `@OpenByDesign` (see class javadoc — durably replays exactly what the already-unscoped `event` SSE topic broadcasts) |
| DemoController | GET | `/api/demo` | Demo-button availability probe | **unscoped** (ledger); gated by `vision.demo.enabled` (default on) |
| DemoController | POST | `/api/demo/seed` | Seed demo assets/users/streams/zones/marks; fault-tolerant (failures land in `problems`, never an error status) | scope (`DemoScenario` resolves the acting user itself) |

`/ws/manual-control` (WebSocket, `ws/ManualControlWebSocketHandler`) is the streaming RC-control
transport, outside the table above since it isn't a `@RestController` route. Its handshake resolves
`CurrentUser` the same way every REST call does (`ManualControlHandshakeInterceptor`, 401 if it
can't) and every `engage` frame re-derives scope from that handshake — see the class javadoc for the
full frame protocol.

**AUTH-ROLES-PLAN wave B4 — the per-asset `mayFly` gate on `engage`.** `ManualControlHandshakeInterceptor`
now also stashes `Authority` (`ATTR_AUTHORITY`) into the session's attribute map at handshake time,
alongside the pre-existing `userId`/`scope` — the one point where the HTTP thread's `SecurityContext`
is actually valid; a WebSocket message frame (`engage` included) is dispatched later on the
container's own message thread, which carries none. `handleEngage` now checks
`CapabilityAssetAuthority#mayFly(Authority, UserId, AssetId)` — the interface's `mayFly(AssetId)`
overload cannot be used here since it reads the ambient `CurrentUser`, which would throw once auth is
enabled — **exactly once, at engage**, denying `OUT_OF_SCOPE` before `ManualControlService#engage` is
ever called; a revocation mid-session is never re-checked (the mid-flight rule, §3.7 clause 1). The
handler's constructor was 4-arg at the time: `(ManualControlService, CapabilityAssetAuthority,
watchdogTimeoutMillis, engageSlowThresholdMillis)` — depends on the *concrete* class, not the
`AssetAuthority` interface, specifically to reach this explicit-actor overload (a second public method
on `CapabilityAssetAuthority`, not part of the frozen 3-method `AssetAuthority` interface).

**CREW-CONTROL wave W2 — the flight-seat gate on `engage` (docs/plans/active/CREW-CONTROL-PLAN.md
§3.3).** The constructor gained a third parameter, `SeatAccess`, ahead of the two `@Value` longs —
`(ManualControlService, CapabilityAssetAuthority, SeatAccess, watchdogTimeoutMillis,
engageSlowThresholdMillis)`. `handleEngage` calls `SeatAccess#requireFlightSeat(UserId, AssetId)` —
the explicit-actor overload, since (as above) the message thread carries no ambient `CurrentUser` —
immediately after the `mayFly` check and before `ManualControlService#engage` is ever called; on
`IllegalStateException` (seat held by another) the handler answers a `denied` frame with
`code:"SEAT_HELD"` rather than letting the exception propagate (there is no HTTP layer here to map it),
mirroring the REST controllers' 409 with a WS-native shape. Disabled by default
(`vision.crew.enabled=false`) via `SeatAccess`'s own settings-driven no-op, not a second code path in
this handler.

**AUTH-ROLES-PLAN wave B4 — the same `mayFly` gate on `FlightCommandController`'s six REST commands.**
Unlike the WebSocket handler, this controller *can* reach the ambient `CurrentUser`, so it injects
the plain `AssetAuthority` interface (not the concrete class) and calls the interface's
`mayFly(AssetId)` overload. A new private `requireMayFly(AssetId)` throws `AccessDeniedException`
(→403 via `ApiExceptionHandler`, unchanged mapping) before any of `returnHome`/`setMode`/`arm`/
`disarm`/`emergencyStop`/`auxFunction` ever calls `FlightCommandService` — deliberately redundant
with that service's own pre-existing `scope().includes(...)` check in `contexts/vision-flight`
(`DefaultFlightCommandService`, out of this module's reach): the edge gate is strictly narrower (adds
the `COMMAND_FLIGHT` capability and, for an `ASSIGNED_ASSETS` caller, the `PILOT`-not-`CREW` seat
narrowing), so the service's own check never actually fires once this one has denied — it stays as
the scope-only backstop for any future caller that reaches the service directly. `GET
/api/assets/{id}/flight-capabilities` (a read) is untouched — it keeps its existing 404-on-out-of-scope
convention, not this 403 gate.

**CREW-CONTROL wave W2 — the seat gate, layered after `mayFly`/`mayOperateCamera`, on five REST
controllers and the WS handler above.** `SeatAccess` (`security/SeatAccess.java`) is the one
collaborator every guard calls — constructed from `SeatService` (contexts/vision-flight),
`AssetAuthority`, `CurrentUser`, `SeatSupport`, and `SeatAccessSettings` (5 params, at the
constructor ceiling, documented in its own javadoc). `requireFlightSeat(AssetId)`/`requireCameraSeat
(AssetId|DeviceId|StreamId)` are pass-through no-ops when `vision.crew.enabled=false` (the opt-in
guardrail: default config is byte-identical to pre-W2 behaviour) and otherwise take-or-renew the
caller's own seat, throwing `IllegalStateException` (→409, "Asset `<uuid>` `<kind>` seat is held by
`<displayName>`") only when a *different* user holds it — per §3.3 rule 3, a flight-seat holder never
conflicts on the camera seat and instead silently preempts any prior camera holder. Insertion points:
`FlightCommandController#returnHome/setMode/arm/disarm/emergencyStop/auxFunction` (lines 119, 137,
157, 178, 202, 225) each call `seatAccess.requireFlightSeat(assetId)` immediately after the
pre-existing `mayFly` check; `AssetStreamController#startStream/stopStream` (lines 135, 157) and
`StreamController#start/stop/updateConfig`-family (lines 193, 242, 291) call
`seatAccess.requireCameraSeat(...)`; `AssetSessionController#engage/disengage` (lines 125, 147) call
`seatAccess.requireFlightSeat(assetId)`. Each of these four controllers' constructors gained a
trailing `SeatAccess` parameter — `AssetStreamController` to 6 args, `StreamController` to 7 (both
past the 5-arg ceiling, documented in their own javadoc following the pre-existing `StreamAccess`
precedent at 6). `SeatController` (`controller/SeatController.java`) is a new, separate controller
exposing `GET/POST/DELETE /api/assets/{id}/seats[/{kind}]` directly over `SeatAccess`'s
`seats`/`takeSeat`/`releaseSeat` — see the Endpoints table and "DTO conventions" above. **FLEET-RADIO
R2** added one additive `denied` reason code, `VEHICLE_UNIDENTIFIED`
— no frame added/removed, no field renamed: `engage` now catches `vision-flight`'s
`VehicleUnidentifiedException` (a subtype of, and ahead of, the existing `IllegalStateException`
clause) and maps it straight to `new ManualControlDeniedFrame(CODE_VEHICLE_UNIDENTIFIED, e.getMessage())`
instead of the generic `IllegalStateException` clause's own code — the message is one of three
distinct, operator-facing sentences (`vision-flight`'s `UnidentifiedReason`-keyed text), never
sniffed or rewritten here. `ManualControlDeniedFrame.code` is a plain `String`, not a closed enum, so
this needed no wire-contract/DTO change at all.

**FLY-CONTROL-UX H1 — catch-all denial + engage-duration instrumentation.** Traced from a real
report ("Control denied — the station never confirmed control"): `docs/plans/active/fly-control-ux/R3-handshake-denial.md`
proved this is a client-local 4s abandon (`ManualControlClient`'s `ENGAGE_TIMEOUT_MS`) that fires
only when *neither* an `engaged` nor a `denied` frame arrives, and that `engage()`'s whole path
(`DefaultManualControlService` → `MavlinkManualControlSender` → `mavlink-core`'s
`ManualControlService`) is local and ack-less — no vehicle round-trip exists to be slow, so a
firing timeout is always a station fault. `handleEngage` now has a fourth, final
`catch (RuntimeException e)` after the three documented types (`AccessDeniedException`/
`VehicleUnidentifiedException`/`IllegalStateException`) — it logs WARNING with the full stack trace
and still answers `denied` with the additive code `INTERNAL_ERROR`, a generic operator-facing
sentence plus the exception's simple class name **only** (never its message, which could leak
internals). This closes the one gap R3's trace could not rule out (an uncaught type — e.g. a plain
`NoSuchElementException` from an unknown `assetId`, which `AssetService#details` throws and none of
the three documented types cover) and guarantees `handleEngage` never returns without a reply on an
open socket; the `engaged` response DTO is now built *before* `state.session` is assigned, so a
failure composing it cannot leave local state claiming a session the client was told was denied.
Every `engage` attempt's wall time is also measured and logged — WARNING once it reaches
`vision.rc.engage-slow-threshold-ms` (default 2000, read via `@Value`, vision-app's `application.yaml`
documents the same default), DEBUG otherwise — so a real "never confirmed" report is diagnosable from
this station's own log instead of a bisect. Blocking audit (read-only, no bound added): every step
under `engage()` — scope/readiness/maintenance checks (DB-backed, no live link, per
`DefaultManualControlService`'s own javadoc), `MavlinkManualControlSender.engage` (a map read plus a
non-blocking `ManualControlService(mavlink-core).engage`, which only checks `PeerDirectory` and calls
`TxScheduler.repeat` — itself a non-blocking `ScheduledExecutorService.scheduleAtFixedRate`) — was
traced and found to contain no wait, sleep, or socket read that could approach 4s; no bound was added
because none was needed.

### Error mapping (`ApiExceptionHandler`, body `{"error","message"}`)

| Exception | Status | code |
|---|---|---|
| `IllegalArgumentException`, `UnsupportedProtocolException` | 400 | `BAD_REQUEST` |
| `NoSuchElementException` | 404 | `NOT_FOUND` |
| `AccessDeniedException` (platform) | 403 | `FORBIDDEN` — a scoped **command** against something the caller cannot see; never used for a scoped *read*, which 404s instead (see Conventions) |
| `IllegalStateException` | 409 | `CONFLICT` |
| `HlsUpstreamUnavailableException` | 502 | `BAD_GATEWAY` — upstream unreachable; a normal non-2xx *received* from upstream passes through verbatim instead |
| `ProbeFailedException`, `DiscoveryCandidateAlreadyRegisteredException` | 422 | `UNPROCESSABLE_ENTITY` — the latter shares this status for `POST /api/discovery/inbox/{id}/attach` against a candidate already `REGISTERED` to a different asset (SOURCE-ONBOARDING-2-PLAN.md §3.2 C1) |
| `PayloadTooLargeException` | 413 | `PAYLOAD_TOO_LARGE` |
| `GeoServiceUnavailableException` | 503 | `SERVICE_UNAVAILABLE` — request was well-formed/authorized, the collaborator it proxies to (cv-service) is down |

### Live updates (`com.drones.vision.api.live`)

One `LiveUpdateRegistry` implements all seven per-context live-update ports (`FleetLiveUpdatePort`,
`TelemetryLiveUpdatePort`, `DetectionLiveUpdatePort`, `MapLiveUpdatePort`, `EventLiveUpdatePort`,
`TrackCorrectionLiveUpdatePort`, and — LIVE-POLL-RETIREMENT-PLAN wave L3 — `GeofenceLiveUpdatePort`,
`contexts/vision-flight`'s new port) and owns every SSE connection, process-local/single-instance
only. Topics: `fleet`, `event`, `devices`, `detection-events`, `discovery`, `zones` (all always-on, no
auth needed beyond the connection itself — see below for `discovery`'s own delta-only semantics), plus
a tenth always-on topic `system` that carries no per-context port at all (see below), `map` and
per-asset `telemetry:<id>`/`detections:<id>`/`geo:<id>` (individually authorized — see below).
Delivery is coalesced (leading+trailing, ~150ms default) per topic, not per connection, so exactly one
resumable `seq` exists per topic; `Last-Event-ID` resumes from a per-topic ring buffer (FIFO or
latest-only depending on topic). Tunables live in `VisionApiProperties.Live` (coalesce/heartbeat/buffer
sizes/send-timeout/buffer-eviction/`systemSample`), bound from `vision.api.live.*`.

**`zones` (LIVE-POLL-RETIREMENT-PLAN §3 D2/§4.1, wave L3) — geofence create/update/delete, never
riding `map`.** Zones deliberately do not ride the pre-existing `map` topic: no `map -> flight`
architecture edge exists (`ContextArchitectureTest`, vision-app), and zones are
`contexts/vision-flight`'s own concept, not `vision-map`'s. Follows the one-port-per-context idiom
exactly like `MapLiveUpdatePort`/`MapEvent`: `contexts/vision-flight` gained `GeofenceZoneEvent`
(`Action{CREATED,UPDATED,DELETED}` + `GeofenceZone`) and `GeofenceLiveUpdatePort`
(`publishZoneEvent(GeofenceZoneEvent)`), and `DefaultGeofenceService` publishes on every
create/update/delete, immediately after `GeofenceMonitor#refresh()`. The envelope is
`GeofenceZoneEventPayload{action, zone}` — `action` one of `"CREATED"`/`"UPDATED"`/`"DELETED"`, `zone`
the same `GeofenceZoneResponse` shape `GET /api/geofences` already returns. **`DELETED` carries the
last-known zone in full** — `DefaultGeofenceService#delete` captures `require(id)`'s return value
before removing it, rather than discarding it, specifically so a subscriber can render "zone X was
deleted" without a separate lookup. Buffer capacity mirrors `discoveryBuffer` (shares
`eventBufferCapacity`, FIFO, not latest-only — a `DELETED` a resuming viewer missed must still be
delivered, not collapsed away by a later `UPDATED` to a different zone).

**`system` (LIVE-POLL-RETIREMENT-PLAN §3 D3/§4.2, wave L4) — a server-side sampler, not a port.**
Unlike every other topic, nothing calls `LiveUpdateRegistry` through a per-context port to publish
`system`; `live/SystemStatusSampler` (a plain `@Component`, gated the same way `LiveUpdateRegistry`
itself is — `@ConditionalOnProperty(prefix="vision.live", name="enabled", matchIfMissing=true)`) owns
its own `ScheduledExecutorService` and calls `LiveUpdateRegistry#publishSystemStatus(SystemStatusResponse)`
— one new public method, no new port interface, no new constructor collaborator on the registry
itself (`systemBuffer` is inline-field-initialized `new LiveRingBuffer(1, true)`, exactly like
`fleetBuffer`). The envelope's payload is the verbatim `SystemStatusResponse` `GET /api/system/status`
already returns — same shape `support/SystemStatusReader#read` builds for both callers (wave L4a
extracted `safeStatus`/`overall`/`worstHealth` out of `SystemStatusController` for this reuse; that
controller's own wire output is unchanged, guarded by its pre-existing, untouched test suite). The
sampler ticks on its own schedule (`vision.api.live.systemSample`, default 5s, first tick at delay
`0` so the buffer is populated before any connection can possibly arrive — `LiveUpdateRegistry` needs
no `seedIfEmpty` case for `system` because of this) and broadcasts **only on change** — a genuine
poll-to-push conversion, not a fixed-cadence relay.

**Self-feedback hazard and its frozen mitigation.** `live/LiveUpdateStatusProvider` reports on the
`live-updates` subsystem (connection count, `LiveRingBuffer#everDropped()` across every buffer) — the
very registry the sampler broadcasts through. Broadcasting on *every* `live-updates` change would
create feedback (a broadcast changes connection/delivery state, which the next sample would see as a
change, triggering another broadcast). The sampler's change detection (`SystemStatusSampler.Fingerprint`)
compares `overall` plus each subsystem's `(id, health, detail, hint)`, **ignoring `checkedAt` entirely**
and **excluding the `live-updates` subsystem from the comparison entirely** (`LiveUpdateStatusProvider.SUBSYSTEM_ID`,
a shared constant so the exclusion can't drift from the id it excludes) — `live-updates`'s own value
still rides in the broadcast payload (a subscriber still sees it), it just never *triggers* one on its
own. Critically, `Fingerprint.overall` is **recomputed** via `SystemStatusReader#worstHealth` over the
filtered (live-updates-excluded) list, never copied from `SystemStatusResponse#overall()` — so a
`live-updates`-only health flip can't move the comparison's overall either. **Known, deliberately
unfixed defect this mitigation route around**: `fleetBuffer` is `new LiveRingBuffer(1, true)`
(latest-only), and `LiveRingBuffer#everDropped()` is set by collapse-to-latest replacement, so
`live-updates` already reports `DEGRADED` from the second fleet change onward with a misleading
detail — exactly why the exclusion above is necessary, not merely a change-detection convenience. Out
of scope for this wave to fix; see `LiveRingBuffer`'s own javadoc.

**Discovery now also has a `discovery` SSE topic, added on top of the still-pollable inbox
(SOURCE-ONBOARDING-2-PLAN.md §3.2 C4 — supersedes the Z2c "poll-only" call below for the delta
case).** `LiveTopicKind.DISCOVERY`/`LiveTopic.DISCOVERY` is a ninth always-on topic (no
per-connection authorization beyond the connection itself, same posture as `fleet`/`event`/
`devices`/`detection-events`). The envelope is `DiscoveryEventPayload{action, candidate}` —
`action` one of `"REPORTED"`/`"REGISTERED"`/`"DISMISSED"`/`"RESTORED"`, `candidate` the same
`DiscoveryCandidateResponse` shape `GET /api/discovery/inbox` already returns. **Delta-only, never
per-sweep**: `vision-app`'s `LiveUpdateDiscoveryInboxService` decorator only calls
`LiveUpdateRegistry#publishDiscoveryEvent` when a sweep's `ReportOutcome#changed()` is `true` (a
genuinely new candidate, or a status/discovered-vs-not change — not a routine last-seen refresh) or
on an operator verb (`register`/`dismiss`/`attach`/`restore`), so a client watching this topic sees
exactly the events an operator would call "something happened," never the sweep's own cadence.
Gated by `vision.discovery.live.enabled` (default **true** — see `vision-app`'s MODULE.md for the
wiring). The original Z2c reasoning for keeping `GET /api/discovery/inbox` itself pollable, not SSE,
is unchanged: a client without an open SSE connection still has a correct, if latent, view from
polling; the two are complementary, not a replacement.

**`devices` now also fires on a real stream-state transition (SOURCE-ONBOARDING-2-PLAN.md §3.2 C6).**
`LiveUpdateRegistry#publishDevicesSnapshot()` (a plain, no-arg re-broadcast of the current device
list, pre-existing) is now additionally invoked by a real `StreamStateObserver` — wired in
`vision-app`'s `ApplicationServiceWiring` — every time `DefaultStreamService` computes a stream-state
transition (start/stop/error), not only on the triggers that already called it. Gated by
`vision.live.stream-state-push.enabled` (default **true**); the observer itself is edge-triggered and
fires synchronously, isolated from a throwing implementation by `DefaultStreamService` — see
`vision-app`'s MODULE.md for the wiring.

**`event` now has a durable counterpart (ALWAYS-ON-FLOW-PLAN wave B3).** The `event` topic itself is
unchanged — still live-only, still lost on disconnect — but `GET /api/system/events` (own row in the
endpoint table above, `SystemEventsController`) now durably replays the same `platform.Event`s this
topic fans out, for a caller that missed them across a page load or a reconnect. Same
open-to-any-connected-caller posture as the topic it backfills (`@OpenByDesign`, not scope-filtered —
see that controller's own class javadoc for the full reasoning), empty unless `vision-app`'s
`vision.events.history.enabled` is on.

**Scoped delivery**: `MapVisibility` gates the `map` topic by `MapAccessPolicy.canView`; `LiveAssetAccess`
gates per-asset `telemetry`/`detections`/`geo` by `StreamAccess.visibleAsset`. Both filter at
subscribe time (a caller never even subscribes to something out of scope) **and** re-check on every
delivery (a scope change mid-connection, e.g. a revoked assignment, takes effect within the cache
TTL — 5s for assets, 10s for map — with no `PATCH` needed to trigger it). Both checks are called
**directly** in `LiveController`'s handler body, not only stored as a `Predicate` — a check hidden
behind a `Predicate` field is invisible to `EndpointAuthorizationTest`'s static call-graph guard,
which only recognizes a direct call to `CurrentUser.scope()`/`.viewer()` or a class named `*Access`.

**`fleet` is filtered per connection too (fix/fleet-topic-scope), same mechanism as `map`.**
`LiveUpdateRegistry#freshFleetEnvelope` builds its snapshot from the unscoped `AssetService#assets()`
overload and `fleetBuffer` stays deliberately unfiltered (so a `Last-Event-ID` resume can re-filter
against whatever the resuming viewer may see *now*, exactly like `map`) — narrowing happens only at
delivery time, in `LiveConnection#project`, which replaced the old `mayReceive(envelope): boolean`.
`project` returns a `LiveEnvelopeResponse` (nullable), not a `boolean`: a `map` event still resolves
to either the same envelope or `null` (outright dropped for a connection that may not see its
`layerId`), but a `fleet` envelope (identified by `type.equals(LiveTopicKind.FLEET.wire())` — never
`instanceof List`, since the envelope's `payload` is an erased `Object`) is never dropped; its asset
list is narrowed to `assetVisibility.test(assetId)`, and a viewer whose scope includes nothing still
gets an envelope carrying an empty list, not silence. `project` returns the identical envelope
instance when nothing needed filtering — load-bearing for `broadcast`'s serialize-once optimization
(SCALE-100-PLAN §5 S2): `broadcast` still serializes an envelope exactly once and reuses that one
`String` for every connection whose `project` returned that same instance back, paying a second,
per-connection re-serialize only for a connection that actually got a narrower `map`/`fleet` view.
The same projection now also runs on `connect()`'s snapshot/resume burst and `updateTopics()`'s
newly-added-topic burst — both used to serialize an unfiltered envelope straight from the buffer,
which was the actual leak (a fresh connection's seeded `fleet` snapshot, and any later resume, both
carried every asset regardless of the caller's scope). Covered end-to-end by
`LiveFleetScopingTest` (connect-time snapshot, broadcast delta, `Last-Event-ID` resume — proving the
buffer stays unfiltered and is re-filtered per resuming viewer, UNBOUNDED-admin no-regression,
empty-scope-viewer-gets-empty-list) and by three pure-unit cases in `LiveUpdateRegistryTest`
(broadcast narrowing + UNBOUNDED no-regression, empty-list-not-dropped, and a same-instance
assertion on a non-fleet/non-map topic guarding the serialize-once path).

### Rate limiting (`ratelimit/`)

`RateLimitFilter` (`OncePerRequestFilter`, not a controller) is a **blast-radius bound**, not
security hardening: one token bucket per `CurrentUser#userId()`, 600/min default, `/api/**` except
`/api/live/**` and `/hls/**`. Registered behind `vision.api.rate-limit.enabled`, **default off** —
with auth disabled every caller shares one dev-principal bucket, so turning this on without auth
would rate-limit the whole deployment as one user. 429 body reuses `ErrorResponse` (hand-serialized;
`ApiExceptionHandler` is unreachable from a plain servlet filter).

### DTO conventions

`@JsonInclude(Include.NON_NULL)` on every response DTO with an optional field, so an absent value is
omitted from the JSON rather than serialized `null` — except where absent-vs-null is itself
meaningful (`AfterActionManifestResponse`'s `endedAt:null` distinguishes "still flying" from
"nothing to report", so it carries no `NON_NULL` annotation). Mapping/validation lives on the DTO
records themselves (`toSpec()`, `toRegistration()`, …) — no mapper library. `AssetUsageResponse`
carries `origin` (`STREAM` or `OPERATOR` — the only two `UsageOrigin` values today; a `TELEMETRY`
value was considered and deliberately left out since nothing produces it, see
`com.drones.vision.kernel.UsageOrigin`'s own javadoc) and (ASSET-FLOWS-PLAN §2 D1p, wave BK4)
`pilotId` — the raw `UUID` string of `AssetUsage#pilotId()`, `null`/omitted when genuinely unknown,
**no display-name resolution** at this layer (mirrors `MaintenanceRecordResponse#openedBy`'s own
precedent: id only, a caller resolves a name if it needs one). `UsageSummaryResponse` (the
`GET /api/usages`/`GET /api/usages/by-stream/{id}` row shape) carries the same `pilotId` field, same
nullability rule. `ErrorResponse(error, message)` is the
uniform error body every `ApiExceptionHandler` mapping returns.

**WAREHOUSE-UX W3 additions** — `IdentityResponse(serialNumber, make, model, registration)` /
`CustodyResponse(custodianId, location, since)`, both `@JsonInclude(NON_NULL)` with a static
`from(Identity)`/`from(Custody)`; `AssetSummaryResponse`/`AssetDetailsResponse` each gained trailing
`identity`, `custody`, `inventoryState` (effective value, already a `String` name), `createdAt`,
`updatedAt` fields. `IdentityRequest(serialNumber, make, model, registration)` is a shared top-level
record (not nested per-DTO) so `CreateAssetRequest`/`UpdateAssetRequest` both reuse it via
`toIdentity()`; `CreateAssetRequest` additionally gained a nested `CustodySpec(custodianId, location)`
record (`toCustody()` returns `Custody.NONE` when `custodianId` is blank/absent, else stamps
`Instant.now()` for `since`) — custody is create-time-only, there is no "custody" field on
`UpdateAssetRequest` (custody changes go through `AssetInventoryController`'s dedicated endpoint, not
a general asset PATCH). `CustodyActionRequest(action, custodianId, location)` /
`InventoryActionRequest(action, kind, summary)` each carry a nested `Action` enum and
`toAction()`/`requireXxx()` parse helpers (case-insensitive, throwing `IllegalArgumentException` on
an unknown value — 400 via `ApiExceptionHandler`). `CreateMaintenanceRecordRequest(kind, summary)` /
`MaintenanceRecordResponse(id, assetId, kind, openedAt, closedAt, openedBy, summary,
flightSecondsAt)` (`@JsonInclude(NON_NULL)` — `closedAt`/`flightSecondsAt` omitted, not `null`, when
absent). `CreateCategoryRequest(id, name, parentId, connected, attributeHints)` /
`UpdateCategoryRequest(name, parentId, connected, attributeHints)` mirror `CategorySpec`/
`CategoryEdit` 1:1; `CategoryResponse` gained `connected`; `CategoryCountsResponse` gained
`inStock`/`issued`/`inField`/`maintenance`/`retired`.

**WAREHOUSE-UX W8 additions** — `FirmwareResponse(name, version)` (`@JsonInclude(NON_NULL)`, static
`from(VehicleProfile)`) and `FleetMaintenanceRecordResponse(id, assetId, assetName, categoryId, kind,
openedAt, closedAt, openedBy, summary, flightSecondsAt)` (`@JsonInclude(NON_NULL)`, static
`from(MaintenanceRecordSummary)`) — the `GET /api/maintenance` row shape, `MaintenanceRecordResponse`'s
fleet-wide sibling with `assetName`/`categoryId` joined in. `AssetSummaryResponse`/
`AssetDetailsResponse` each gained trailing `firmware` (`FirmwareResponse`, nullable) and
`totalFlightSeconds` (`Long`) fields — `firmware` absent when never probed or when the caller has no
join to offer; `totalFlightSeconds` absent **only** when the caller has no join to offer, never for a
genuine zero (a never-flown asset reports `0`). Both `from(...)` factories widened to take `firmware`/
`totalFlightSeconds` as explicit parameters rather than gaining a second overload — see `AssetRowFacts`
in Conventions for who supplies real values and who passes `null`.

**ZERO-CONFIG-ONBOARDING Z2c additions** — `DiscoveryCandidateResponse(id, method, name, address,
suggestedCategory, suggestedStreamProtocol, suggestedStreamUri, suggestedStreamOptions, details,
firstSeen, lastSeen, status, registeredAssetId)` (`@JsonInclude(NON_NULL)`, static
`from(DiscoveryCandidate)`) carries `suggestedStreamOptions` as the **full** `Map<String,String>` —
this is the one field this DTO exists to get right that its sibling `DiscoveredDeviceResponse`
(`DiscoveryController`) documented-defect drops, so a reader must not copy that shape here.
`RegisterDiscoveryCandidateRequest(displayName, category, attributes, identity)` builds a
`RegisterFromCandidateCommand` via `toCommand(Ownership)` — the `Ownership` argument always comes from
`CurrentUser#ownership()` in the controller, never a request field (see Conventions).
`RegisterDiscoveryCandidateResponse(assetId, displayName, category)` is deliberately **not** the full
`AssetDetailsResponse` shape — `register` only has the freshly-created `Asset` in hand (the service
returns that, not the mutated `DiscoveryCandidate`), and building the full details response would need
extra collaborators (`AssetRowFacts`, image lookup) this endpoint has no call to pull in; a caller
wanting the full asset shape follows up with `GET /api/assets/{id}`, same posture as `AssetInventoryController`'s
own after-mutation responses.

**ASSET-FLOWS wave BK6 (A3) — `GET /api/discovery/inbox` wire-contract change (docs/plans/active/ASSET-FLOWS-PLAN.md
§2, frozen).** This endpoint used to answer a bare `DiscoveryCandidateResponse[]`; it now answers
`DiscoveryInboxResponse(candidates, sources)` — an envelope, `candidates` carrying exactly that same
array under its own key. `sources` is `List<DiscoverySourceResponse>`, `DiscoverySourceResponse(id,
status)` (`status` the raw enum name, `"OK"`/`"UNREACHABLE"`, static `from(SourceHealth)`) — one row
per registered `vision-warehouse` `DeviceDiscoveryPort`, from that context's new
`DiscoveryService#health()`. This is the fix for the mediamtx push-registry scanner reporting
"unreachable" identically to "reachable but empty" (both were `List.of()` from `scan()`, WARN-log
only); a caller can now tell them apart without reading logs. **Breaking change, intentional per
plan**: `register`/`dismiss` are unchanged (still bare `DiscoveryCandidateResponse`/
`RegisterDiscoveryCandidateResponse`) — only `list` moved to the envelope. `vision-web`'s frontend
still expects the old bare-array shape as of this wave; fixing that is wave WB2's job, out of this
module's scope. `DiscoveryInboxController`'s constructor gained a third parameter,
`DiscoveryService` (alongside the existing `DiscoveryInboxService`/`CurrentUser`) — no `vision-app`
wiring change needed, since this controller has no explicit `@Bean` method (pure component-scan
auto-wiring by type).

**SOURCE-ONBOARDING-2 wave C additions** — `AttachDiscoveryCandidateRequest(String assetId)` backs
`POST /api/discovery/inbox/{id}/attach`; `DiscoveryEventPayload(String action,
DiscoveryCandidateResponse candidate)` is the `discovery` SSE topic's envelope (see "Live updates"
above). `DiscoverySourceResponse` gained a trailing `lastScanAt` (`@JsonInclude(NON_NULL)`,
`Instant`, absent for `SourceStatus.NEVER_SCANNED`). Three new records back `GET
/api/discovery/status` (`@JsonInclude(NON_NULL)` throughout): `TelemetryIntakeResponse(bound,
bindAddress, lobbyHeld, datagramsReceived, bytesReceived, lastDatagramAt, framesDecoded,
unclaimedSysids, claimedSysids)` mirrors `adapter-mavlink`'s `MavlinkIntakeStatus` field-for-field;
`VideoIntakeResponse(pushPort, pathPrefix, readyPaths)` (no `@JsonInclude` on itself — the whole
object is either present or the enclosing `videoIntake` field is absent, per
`DiscoveryStatusResponse`'s own contract, not a per-field omission); `DiscoveryStatusResponse(int
sweepSeconds, Instant lastSweepAt, TelemetryIntakeResponse telemetryIntake, VideoIntakeResponse
videoIntake, List<DiscoverySourceResponse> sources)` is the endpoint's top-level shape —
`lastSweepAt`/`videoIntake` both omitted (not `null`) before the first sweep completes / when
mediamtx publish is unconfigured, respectively. `support/DiscoveryStatusFacts` is a **plain, non-DTO**
record (no Jackson annotations) — the crossing-seam payload a `vision-app`-supplied
`Supplier<DiscoveryStatusFacts>` bean hands `DiscoveryStatusController`, kept deliberately separate
from the wire DTOs above so this module's own `dto` package still contains only what actually
serializes; see that record's own javadoc for why it crosses as a plain `Supplier` rather than a new
port interface (`vision-api` has zero dependency on `adapter-mavlink`/`adapter-discovery`, and one
implementation/one caller doesn't earn a new interface per `.claude/skills/java-clean-code/SKILL.md`
§1). `NetworkAddressResponse` gained a trailing `kind` (`"LAN"`/`"VIRTUAL"`/`"UNKNOWN"` — see
`LocalNetworkAddresses`'s own classification); `SystemNetworkResponse` gained trailing
`videoPushPort`/`videoPushPathPrefix` (`@JsonInclude(NON_NULL)`, both absent together when mediamtx
publish is unconfigured).

**CREW-CONTROL wave W2 additions (docs/plans/active/CREW-CONTROL-PLAN.md §3.6, frozen wire
contract)** — `SeatHolderResponse(holderUserId, holderDisplayName, expiresAt)`, deliberately **not**
`@JsonInclude(NON_NULL)`: a free seat serializes all three fields as explicit JSON `null` rather than
omitting them, because the frontend contract distinguishes "no holder" from "still loading" by key
presence, not just falsiness — static `free()` factory returns the all-null instance. `SeatsResponse
(assetId, ttlMs, flight, camera, mayTakeFlight, mayTakeCamera, mayForceSeat)` is `GET
/api/assets/{id}/seats`'s shape; the three `may*` flags are the caller's *own* standing, computed by
`SeatAccess#seats` against `AssetAuthority` and never require a client-side capability lookup.
`TakeSeatRequest(force)` is `POST .../seats/{kind}`'s optional body (bare `{}` or an absent body both
mean `force=false` — Jackson 3 defaults a missing `boolean` field to `false`, no explicit handling
needed). None of the three names a `SeatKind` field: `kind` is a path variable
(`SeatController#parseKind`, case-insensitive `"flight"`/`"camera"` → 400 `IllegalArgumentException`
on anything else), never a body field, so there is exactly one place a client can get it wrong.

- **Out-of-scope single-resource reads answer 404, not 403.** A caller must never be able to prove a
  resource exists by the status code alone. `AccessDeniedException`→403 is reserved for a scoped
  **command** against something the caller can already see but may not act on (see the error table).
  `DatasetController#get` is a known, deliberate exception (403 for out-of-scope, per
  `DatasetService#get`'s own frozen contract) — flagged here because it is the one place in this
  module that breaks the rule on purpose.
- **`@OpenByDesign(reason=…)`** (`security/OpenByDesign.java`) marks a handler that deliberately
  performs no authority check. `EndpointAuthorizationTest` (vision-app, ArchUnit) walks every
  `@RestController` handler's own call graph looking for a call to `CurrentUser.scope()`/`.viewer()`
  or into a class whose name ends `Access`; a handler that reaches neither and isn't annotated (or
  isn't named in its `TEMPORARY_UNSCOPED` ledger) fails the build. Currently annotated: `ActivityController#myActivity`,
  `AssignmentController#myAssignments` (both self-scoped — no target parameter to check), `AuthController`'s
  three handlers (login/logout/me — must work with no session), `CategoryController#list`/`GeofenceController#list`/
  `CvModelsController#models`/`CvTrackersController#trackers` (deployment-wide reference data), `DeviceProbeController#probe`
  (a caller-supplied protocol+uri names no existing asset), and `ControlProfileController` at the **class** level
  (gated by profile ownership inside `ControlProfileService`, not by `VisibilityScope` — a layout describes one
  person's transmitter, not an asset, so an operator with zero visible assets must still be able to configure it).
- **The `TEMPORARY_UNSCOPED` ledger is a real, currently-open gap, not a design choice** — every
  endpoint tagged `unscoped` in the table above has no authority check at all today. It shrinks with
  each LIVE-SCOPE-style wave; the test's own second assertion fails the build if an entry names a
  handler that no longer exists, so it can't quietly go stale.
- Controllers are constructor-injected with application-service ports only, never an adapter
  (ArchUnit-enforced) — the two documented exceptions are `HlsProxyController` (a raw `URI`, since it
  is a byte-level proxy with no domain concept to depend on) and `AssetImageController` (calls
  `AssetImageRepositoryPort` directly for reads *and* writes — storing/fetching bytes by asset id has
  no business rule beyond what the controller itself already enforces).
- Ids in path variables are canonical UUID strings, parsed via `XId.of(String)`; its
  `IllegalArgumentException` on a malformed UUID surfaces as 400 through the same mapping as domain
  validation — no controller-side translation needed.
- **Authority split on `PATCH /api/assets/{id}`**: the one write whose gate depends on the request
  body, not just the caller. `AssetEdit#changesManagedFields()` decides — a body touching only
  `displayName`/`attributes` needs `scope` alone (so a PILOT may rename their own assigned aircraft);
  a body touching `category` needs `manage`. Every other asset mutation always requires `manage`.
  **ALWAYS-ON-FLOW-PLAN.md wave D1 (2026-09-06) confirmed this endpoint needed no change**: a
  per-asset `DetectionPolicy` opt-in (`contexts/vision-perception`'s new
  `DetectionPolicy.ATTRIBUTE_KEY = "cv.detection-policy"`, values `"on-view"`/`"always"`) is stored
  under this same free-form `attributes` map — this generic PATCH already round-trips it, so setting
  an asset's policy is just `PATCH {"attributes":{"cv.detection-policy":"always"}}` under the
  `scope`-only authority level above, no new endpoint/DTO/wire contract added.
- Logging: `System.Logger`, not SLF4J — matches every other adapter/domain class in this codebase;
  SLF4J appears only in `vision-app`'s Spring-only devsupport beans.
- **`AssetRowFacts` (`support/`, WAREHOUSE-UX W8) bundles two cross-context reads behind one
  collaborator** — `firmwareOf(Asset)` (iterates the asset's devices, returns the first
  `VehicleProfileRepositoryPort#findLatest` hit) and `totalFlightSecondsByAsset()` (delegates to
  `AssetUsageRepositoryPort`'s new aggregate). `AssetController` already sat at four constructor
  params (`AssetService`, `CurrentUser`, `TelemetryRepositoryPort`, `AssetImageRepositoryPort`);
  adding both `VehicleProfileRepositoryPort` and `AssetUsageRepositoryPort` directly would have meant
  six, past the five-parameter ceiling (`.claude/skills/java-clean-code/SKILL.md` §3) — so both ports
  are bundled into one new fifth parameter instead, the same "bundle into a collaborator" resolution
  this file's own `AssetInventoryController`/`AssetStreamController` split documents for the same
  ceiling. `AssetSummary`/domain records were **not** widened for this — see
  `contexts/vision-warehouse/MODULE.md`'s W8 note for why. Two other call sites of
  `AssetSummaryResponse.from`/`AssetDetailsResponse.from` — `AssetInventoryController#detailsResponse`
  and `LiveUpdateRegistry#freshFleetEnvelope` — are already at their own five-parameter ceiling with no
  room for `AssetRowFacts` either, and pass `null, null` explicitly (each documented in place) rather
  than silently omitting the parameters; a caller wanting an accurate join after those endpoints
  should follow up with `GET /api/assets/{id}`.
- **`DiscoveryInboxController`'s three handlers split authorization the same way `AuditController`/
  `GroupAdminController` already do, for the same reason each does it that way**: `list`/`dismiss` gate
  explicitly in-controller (`currentUser.authority().mayManageOrg()`, wave B6 — was
  `currentUser.scope().canManageOrg()` — throwing `AccessDeniedException` itself) because
  `DiscoveryInboxService#candidates()`/`#dismiss(id, userId)` carry no scope parameter to check against
  — same shape as `AuditController#list`, which has no application-service layer of its own to put the
  check in either. `register` instead passes `currentUser.authority()` (wave B6, was `.scope()`) and
  `currentUser.ownership()` straight through to `DiscoveryInboxService#register(...)`, which performs
  its own `mayManageOrg()`+`includesGroup` checks internally (`vision-warehouse`, own wave B6 migration
  — see that module's MODULE.md) — same shape as `GroupAdminController#create` delegating to
  `GroupService`. All three still reach `CurrentUser.scope()`/`.authority()` directly inside the
  controller method body, so `EndpointAuthorizationTest`'s call-graph walk is satisfied without an
  `@OpenByDesign`/ledger entry either way.
- **`DiscoveryInboxController#attach`/`#restore` (SOURCE-ONBOARDING-2 wave C) gate the same way
  `list`/`dismiss` already do** — explicit in-controller `currentUser.scope().canManageOrg()`
  (`AccessDeniedException` on failure), since `DiscoveryInboxService#attach`/`#restore` carry no
  scope parameter of their own to check against (same reasoning as the note above for `list`/
  `dismiss`). `DiscoveryStatusController#status` gates the same way as its own single handler.
- **A conditionally-absent plain-value bean crossing the `vision-app`→`vision-api` boundary is taken
  through `ObjectProvider<T>`, never a plain, possibly-`null`-valued `T`.** `SystemNetworkController`'s
  `videoPushPort`/`videoPushPathPrefix` constructor parameters are `ObjectProvider<Integer>`/
  `ObjectProvider<String>` — Spring's `@Bean` "null-bean" mechanism (a factory method returning
  `null`) only satisfies `Optional`/`ObjectProvider`/explicitly-`@Nullable` injection points, not a
  plain required constructor parameter; the corresponding `vision-app` bean methods are instead
  genuinely conditionally-registered (`@ConditionalOnProperty`), never present-with-a-null-value —
  see that module's MODULE.md for the wiring side. `mavlinkPort` stays a plain `int` (unconditional,
  never absent), unaffected.
- Rationale for any of the above beyond what's stated here lives in the plan doc cited inline, under
  `docs/plans/`.

## Gotchas

- **`AssetController#telemetry`, `TrainingJobController#job`/`#jobs`,
  `GeoRegionController#list`/`#progress`, `DiscoveryController#scan`, `SystemNetworkController#network`,
  `SystemStatusController#status`, `DemoController#status`, `OnboardingController#probeCandidate`, and
  `UsageTimelineController#timeline`/`#recording` are genuinely unauthorized today** — no `403`/`404`
  from a caller who shouldn't see them, just a plain `200`. This is the `TEMPORARY_UNSCOPED` ledger
  (`EndpointAuthorizationTest`, vision-app), not an oversight in this doc. `ModelRegistryController#models`
  left this ledger when `GET /api/cv/registry/models` was deleted (CV-SETTINGS-PLAN §8 OQ5, folded into
  `CvModelsController`'s widened `GET /api/cv/models`, which carries `@OpenByDesign` instead) —
  `EndpointAuthorizationTest#theTemporaryLedgerHasNoStaleEntries` fails on a ledger entry naming a
  handler that no longer exists, so removing it was mandatory, not tidiness.
- **`hasImage` is always `false` on the SSE `fleet` topic's live snapshot** — `AssetImageRepositoryPort`
  has no change-notification port of its own to announce an upload/delete through, and
  `LiveUpdateRegistry`'s constructor is already at the five-parameter ceiling
  (`.claude/skills/java-clean-code/SKILL.md` §3). A viewer that needs an accurate `hasImage` must read
  `GET /api/assets`/`GET /api/assets/{id}` instead.
- **`LiveController`'s constructor needs `@Qualifier("liveUpdateRegistry")`** — `vision-app` exposes
  the same `LiveUpdateRegistry` singleton under seven more bean names (one per `*LiveUpdatePort` it
  implements, `GeofenceLiveUpdatePort` now the seventh, LIVE-POLL-RETIREMENT wave L3), so a plain
  by-type autowire finds eight candidates and fails at context startup. `SystemStatusSampler`'s own
  constructor needs the same qualifier for the same reason — it depends on the concrete
  `LiveUpdateRegistry` type directly (there is no port for `system`, see "Live updates" above).
- **`LiveUpdateRegistry`'s constructor takes `ObjectProvider<AssetService>`/`ObjectProvider<DeviceService>`/
  `ObjectProvider<StreamService>`/`ObjectProvider<DetectionEventRepositoryPort>`, not the plain types**
  — a genuine circular bean dependency (each of those services' `AuditTrailPort`/live-update port
  eventually resolves back to this same class). Deferring the `.getObject()` call until after context
  startup breaks the cycle.
- **Testing an `SseEmitter` controller with MockMvc relies on `ResponseBodyEmitter`'s own early-send
  buffering**, not a documented MockMvc feature — `connect()`'s handshake+snapshot burst is sent
  synchronously before the framework attaches its handler, and `ResponseBodyEmitter` buffers those
  calls until attachment completes. A delta published *after* connect goes through the registry's
  real background scheduler, so a test polling for it must actually poll, not assert immediately.
- **`EventController` is polling, not SSE**, and neither of its endpoints 404s for an unknown/stopped
  stream (empty list instead) — `StreamAccess.requireVisible` is a no-op for a stream that isn't
  currently running, so a *currently-running* stream out of scope is the only 404 case.
- **`EventController` cannot cheaply carry generic `Event`s** (e.g. `PIPELINE_ERROR`) — `EventPublisherPort`
  is fire-and-forget with no read side at all, and `Event`'s shape shares nothing with `DetectionEvent`'s.
  **ALWAYS-ON-FLOW-PLAN wave B3** built exactly the `EventHistoryPort`-shaped read side this gotcha
  predicted would be needed, behind a new, separate controller (`SystemEventsController`, `GET
  /api/system/events`) rather than a third `EventController` endpoint — see that controller's own
  Live-updates-adjacent section below and `core/vision-platform`/`storage/persistence`/`vision-app`'s
  MODULE.mds for the port/adapter/decorator. `DETECTION` is still never durably recorded there either
  (excluded at the publisher, not the controller) — this gotcha's underlying observation about
  `EventController` itself remains true, only the "nothing else can carry `Event`s" half is now stale.
- **Usage-scoped reads are split across two controllers on purpose**: `AssetController` still owns the
  original, unwindowed `.../telemetry` (unscoped, see ledger above) — now reading
  `TelemetryRepositoryPort#findLatestByUsage` rather than `#findByUsage`
  (COMMAND-MAP-FLOW-PLAN.md B1/D1: the `/command` fleet map polls this endpoint and was freezing once
  a flight passed `limit` earliest-first samples; same wire contract, different window) —
  `UsageTimelineController` owns the windowed/downsampled `.../timeline`, unaffected, which still
  reads `#findByUsage` via `DefaultReplayService` in `vision-events`. Neither depends on the other's
  presence.
- **`AfterActionController`'s two endpoints resolve the whole package synchronously before returning**,
  so a 404/403 always lands on the response before any streaming (ZIP or otherwise) starts — the
  archive endpoint's body is a `StreamingResponseBody` whose failure could not otherwise change an
  already-committed status.
- **No static resources ship from this module** — `station/vision-api/src/main/resources` does not
  exist. `SpaResourceConfiguration` only configures the resource-serving *paths*; `vision-web` is a
  sibling module only `vision-app` packages alongside this jar. Running this module's jar standalone
  serves no UI.
- **MockMvc tests use `standaloneSetup`**, mocking application-service ports directly with no Spring
  context — they do not catch DI wiring gaps; those only surface in `vision-app`'s context tests.
- **Jackson 3** (Spring Boot 4, `tools.jackson.*`): DTOs still import
  `com.fasterxml.jackson.annotation.JsonInclude` — only the databind package moved, annotations did not.
- **`HlsProxyController` has no `cookieHandler` on its shared `HttpClient`** — mediamtx issues
  per-viewer pinning cookies over a redirect; a shared cookie jar would leak viewer A's cookie to
  viewer B. Redirects are followed by hand (`Redirect.NEVER` + a bounded manual loop) specifically to
  keep each hop's `Set-Cookie` scoped to that one servlet request.
- **`HlsProxyController` sends a mediamtx read credential as an outbound `Authorization: Basic`
  header** (docs/plans/active/ASSET-FLOWS-PLAN.md &sect;2 S6): `VisionApiProperties.HlsProxy` grew two
  components, `authUsername`/`authPassword` — `null`/blank `authUsername` (the `defaults()` factory's
  value, matching every test that doesn't opt in) sends no header at all, which is the correct
  behaviour for a `vision.publish.enabled=false`/no-mediamtx-auth setup. `vision-app`'s `PublishWiring`
  is the only real caller that supplies a non-null value, sourced from the new `vision.media.auth.*`
  `VisionMediaProperties` (defaults `vision-viewer`/`change-me` — see that module's own MODULE.md).
  The header is built once in the constructor (`Base64` of `username:password`, empty string for a
  `null` password) and resent on **every** hand-followed redirect hop, alongside the existing
  Cookie/Range forwarding — mediamtx's own read-auth check runs on the redirect target, not the first
  hop, so a header that only rode the initial request would silently 401 after the very first request
  established the pinning cookie.
- **`HlsProxyController` is the one `StreamAccess` caller that fails closed on an unknown/stopped
  stream id** (`requireVisibleForHlsProxy`, AUTH-ROLES-PLAN.md D10, wave B4) — every other caller
  (`StreamController#stop`/`tracks`/`detections`, `EventController`, both above) deliberately keeps
  `requireVisible`'s no-op, a documented "forgiving idiom" for a polling client. Changing the *shared*
  method instead of adding this one would have silently broken those tests; the two methods read
  identically for a *present-but-invisible* device (both throw) and differ only for an *absent* one.
  `requireVisibleStream`'s own malformed-`streamId` short-circuit (a non-UUID path segment, e.g. a
  test's placeholder `"stream-1"`) is unchanged and runs first — it never reaches `StreamAccess` at
  all, so none of `HlsProxyControllerTest`'s wire-mechanics fixtures needed updating for this wave.
- **`ControlProfileController`'s catalogue is served, not hardcoded in the SPA** (decision C8, and
  CLAUDE.md rule 1). `ControlCatalogResponse.of(...)` is derived from the domain enums themselves —
  `ControlInputKind#allows` decides which sources each kind lists, `SwitchPosition#auxFunctionLevel()`
  supplies each position's level, `ControlAction#dangerous()` supplies the danger flag. A client that
  invented its own copy of any of these would eventually offer a binding the server refuses, or —
  worse, for `dangerous` — skip a confirmation.
- **`AuxFunctionCatalog` is a labelled seed list, not the firmware's own table.** Thirteen common
  `RCx_OPTION` numbers (RTL 4, camera trigger 9, gripper 19, parachute 22, motor e-stop 31, …) exist
  to save an operator a trip to the docs. **Any number in `[0,400]` is accepted** whether it is listed
  or not; the list is a convenience, and this module deliberately keeps no claim to be complete — a
  stale copy of the firmware's list is worse than none. It is a `@ConfigurationProperties`-shaped
  record so a deployment can extend it (`vision.control.aux-functions[*]`, `station/vision-app`).
- **No `VisibilityScope` anywhere in `ControlProfileController`.** Every other controller here threads
  `currentUser.scope()`; this one threads only `currentUser.userId()`, because a controller layout is
  personal equipment configuration and the authority question is ownership. `ControlProfileService`
  throws `AccessDeniedException` for another operator's profile, which the existing handler maps to
  403 — no new exception type and no `ApiExceptionHandler` change.
- **Control-layout validation is the domain's, not the DTO's.** `UpdateControlProfileRequest` parses
  strings to enums (`ControlEnumParsing`, the same case-insensitive-with-listed-alternatives idiom as
  `CapabilityParsing`) and then builds real `ChannelMap`/`ActionMap` records — so "the same stick both
  drives CH3 and arms the vehicle" is rejected by `ControlProfile`'s own compact constructor and
  surfaces as a 400 with the domain's own message. Nothing re-validates it here.
- **`ControlCatalogResponse` carries a trailing `maxRcChannel`**, populated from
  `RcChannels.RELAYED_CHANNELS` (8) — decision C8's rule ("the client invents nothing the server can
  state") applied to the one number the setup page was still inventing: it used to offer CH1–18 while
  the link relays only CH1–8, so ten of those choices were stored, displayed, and never sent.

## Status

**docs/plans/active/CONTROLLER-SETUP-CONTEXT.md Wave C15 done** (the operator's transmitter, not
just their bindings — reconciled here as part of merging `feat/controller-setup-c15` onto master).
`GET`/`PUT /api/control-profiles[/{id}]` now carry `stickMode`/`forwardIsUp` on every row
(`ControlProfileResponse`), and `PUT` accepts them as optional fields on `UpdateControlProfileRequest`
— absent means the platform default (`TransmitterView.DEFAULT`, mode 2/forward-up), a `stickMode`
outside 1-4 is a 400. See "API surface" above for the full endpoint contract and the Gotchas block
above for the `ControlProfileController`-specific gotchas C1-C8 already established (catalogue served
not hardcoded, `AuxFunctionCatalog` seed list, ownership not `VisibilityScope`, domain-owned
validation, `maxRcChannel`) — none of which this wave changed.

Feature flags gating whole controllers/packages off by default: `vision.training.enabled` (false —
`DatasetController`/`LabelingController`/`TrainingJobController` all 404
like unmapped routes when off), `vision.cv.registry.enabled` (defaults to `vision.cv.enabled`'s own
value via `application.yaml`'s `${vision.cv.enabled:false}` placeholder, **no longer** tied to
`vision.training.enabled` since docs/plans/active/CV-SETTINGS-PLAN.md §5 — `ModelRegistryController`
404s like an unmapped route when off), `vision.onboarding.probe.enabled` (false — `POST /api/onboarding/probe`
answers 409, and so does `AssetParameterController#writeParameter` — see FLEET-RADIO R5 note below),
`vision.geo.fixed-camera.enabled` (false — `CameraPoseController`/`MapTracksController`
answer 409), `vision.api.rate-limit.enabled` (false, see "Rate limiting" above), `vision.auth.enabled`
(false — every `CurrentUser` call resolves a fixed unbounded dev principal). On by default:
`vision.live.enabled`, `vision.demo.enabled`. `CvProfileController` carries no flag at all —
profiles ship unconditionally, built-ins exist regardless of `vision.cv.enabled`/`vision.cv.registry.enabled`.

Multi-instance SSE fan-out is out of scope — `LiveUpdateRegistry` is explicitly process-local,
single-instance. `RemediationOrchestrator` living in `support/` rather than as a fourth
`vision-flight` application service is a known layering gap, not a decision (see its own javadoc).

**`docs/plans/active/FLEET-RADIO-PLAN.md` R2 done.** `ws/ManualControlWebSocketHandler` gained one
`catch (VehicleUnidentifiedException e)` clause ahead of its existing `IllegalStateException` clause,
mapping to a new, additive `denied` code `VEHICLE_UNIDENTIFIED` — see "Live updates"/`/ws/manual-control`
above. `./mvnw -B -pl station/vision-api test` — **861 tests**, all green (2026-08-27; +2 from before
this wave: `ManualControlWebSocketHandlerTest`'s new cases asserting the code is distinct and not
message-sniffed into one of the other `denied` causes).

**`docs/plans/active/FLEET-RADIO-PLAN.md` R5 done (2026-08-27).** New `AssetParameterController`
(`POST /api/assets/{id}/parameters`, `dto.ParameterWriteRequest`/`ParameterWriteResponse`) — the one
caller of `vision-flight`'s already-built `RemediationService#writeParameter` anywhere in
`vision-api`; `RemediationOrchestrator`'s own `PARAM_WRITE` refusal is untouched (`git diff` empty).
Adds no service/adapter/wiring — `RemediationService`/`VehicleProfileService` are already wired
unconditionally (`OnboardingWiringConfiguration`), so this endpoint inherits the existing
`VehicleConfigPort` flag-swap for free: with `vision.onboarding.probe.enabled` at its default
(`false`) every write 409s from the same `NoopVehicleConfigPort`-caused "no active device this
platform can configure" refusal every other onboarding endpoint already gives, proven by
`AssetParameterFlagGatingTest` (`station/vision-app`) against a real, known-disarmed sim asset (not
merely an asset with no telemetry at all — see that test's own javadoc for why the distinction
matters). `consent` is enforced by `dto.ParameterWriteRequest#requireConsent` as a hard requirement
for **every** write this controller dispatches — stricter than `RemediationService#writeParameter`'s
own `explicitConsent` parameter, which only actually gates Tier B internally; Tier A (including
`SYSID_THISMAV`/`MAV_SYSID`) would otherwise need no consent at all, and this endpoint's whole reason
to exist is carrying an explicit operator act. Spelling resolution (F0 — ArduPilot 4.7 renamed
`SYSID_THISMAV` to `MAV_SYSID`, and MAVLink has no "no such parameter" reply) is a private controller
method, `resolveSpelling`, consulting `VehicleProfileService#latestProfile` only for names with more
than one known alias (`ParameterAliases#spellingsOf`), falling back to the requested spelling on any
lookup failure. `./mvnw -B -o -pl contexts/vision-flight,station/vision-api,station/vision-app test`
— `vision-flight` **351** (unchanged — no source touched), `vision-api` **874** (+13,
`AssetParameterControllerTest`), `vision-app` **271** (+1, `AssetParameterFlagGatingTest`), all
green.

**WAREHOUSE-UX wave W8 done.** New `GET /api/maintenance` (`AssetInventoryController#fleetMaintenance`)
+ `firmware`/`totalFlightSeconds` joined onto `AssetController#list`/`#details`'s response rows via the
new `AssetRowFacts` collaborator (see Conventions). `AssetControllerTest` **62** (+4), `station/vision-api`
**901** (+8) total, all green; ArchUnit (`ArchitectureTest`/`ContextArchitectureTest`/
`EndpointAuthorizationTest`) unaffected — `AssetRowFacts` carries no stereotype annotation and depends
on ports from two different contexts, which `ContextArchitectureTest`'s `contextOf(...)` does not flag
since it only recognizes `com.drones.vision.<context>` packages, not `vision-api`/`vision-app`/adapter
code; `fleetMaintenance` reaches `currentUser.scope()` directly so needed no `TEMPORARY_UNSCOPED`
ledger entry.

**CV-SETTINGS wave W5 done (2026-08-30, uncommitted).** New `CvProfileController` (8 handlers: profile
CRUD, `PUT`/`DELETE /api/cv/bindings`, `GET /api/cv/profiles/effective`, `GET /api/cv/coverage`) and
11 new `dto/` records backing it (`CvProfileResponse`/`CvProfileTrackingResponse`/
`CvProfileEventRuleResponse`/`CvProfilesResponse`/`CvProfileRequest`/`CvProfileBindingResponse`/
`CvProfileBindingRequest`/`EffectiveCvProfileResponse`/`CvCoverageRowResponse`/`CvCoverageResponse`)
plus `TrainingRunResponse`/`TrainingRunsResponse` — see the endpoint table above and Gotchas/Status for
the wiring/exception-mapping decisions. Also: `GET /api/cv/models` widened to serve the registry's live
roster when on; `GET /api/cv/registry/models` deleted (folded into the widened `/api/cv/models`);
`POST /api/cv/registry/rollback` added; `TrainingJobController` gained `GET /api/cv/training/runs`
(+`/{runId}`) reading `TrainingJobService`'s newly-persisted run history, resolving `datasetName` via a
new `DatasetRepositoryPort` collaborator injected directly into the controller (the same
"controllers call a driving-port service, driven ports only read-only" precedent `DatasetController`'s
own javadoc already documents). Full write scope was `station/vision-api`/`station/vision-app` only —
`contexts/vision-perception`/`contexts/vision-learning`/`contexts/vision-warehouse`/`storage/persistence`
were read-only for this wave (already built by earlier CV-SETTINGS waves).

Two deliberate DTO/wire deviations, both informational (frozen `models.ts` leaves no room to do
otherwise without a `contexts/vision-learning` change, out of this wave's write scope):
`TrainingRunResponse` drops `TrainingRunRecord#message` entirely (`models.ts`'s `TrainingRun` has no
such field); `TrainingRunResponse#loss`/`#map50` are boxed `Double`s that are **always** populated from
the domain's primitive `double` fields, never actually `null`, even though `models.ts` declares
`number | null` — the domain has no way to represent "no progress reported yet" distinctly from a
genuine `0.0`.

`./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app test -DskipWeb` (run as three
separate synchronous foreground commands after an `-am` install — see `station/vision-app/MODULE.md`'s
own W5 entry for why) — `storage/persistence` **260** (unchanged, read-only this wave; docker ran, not
skipped), `station/vision-api` **932** (+31 from 901: `CvProfileControllerTest` ~20 new cases +
`TrainingJobControllerTest`'s 9 new `runs`/`run` cases, net of the deleted
`ModelRegistryControllerTest#models` case and its removed `GET /api/cv/registry/models` coverage),
`station/vision-app` **278** (see that module's own MODULE.md entry) — all green, default-config bar
held throughout.

**ZERO-CONFIG-ONBOARDING wave Z2c done.** New `DiscoveryInboxController` (3 handlers: `list`,
`register`, `dismiss` — see the endpoint table, DTO conventions, and the Conventions note above for the
auth split and the deliberate poll-only-not-SSE decision) + 3 new `dto/` records
(`DiscoveryCandidateResponse`/`RegisterDiscoveryCandidateRequest`/`RegisterDiscoveryCandidateResponse`).
Also fixed a pre-existing compile break in `AfterActionAssemblerTest`'s `FakeAssetService` (missing
`findDuplicateDevice` override, added trivially returning `Optional.empty()`) found while wiring this
wave's own test — unrelated to discovery, but blocking the module's test compile either way.
`./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app test -DskipWeb` (after `-am
install -Dmaven.test.skip=true` on the upstream context modules, then a separate `-am install
-DskipTests` on `drone-link/mavlink-core,drone-link/mavlink` specifically — see
`station/vision-app/MODULE.md`'s own Z2c entry for why that second install was needed) —
`station/vision-api` **941** (+9 over 932: `DiscoveryInboxControllerTest`'s 9 cases), `storage/persistence`
**267** (+7, see that module's own MODULE.md entry), `station/vision-app` **293** (+15, see that
module's own MODULE.md entry) — all green, Docker ran for real, default-config bar held throughout.

**ASSET-FLOWS wave BK6 (A3) done.** `DiscoveryInboxController#list` now returns the
`DiscoveryInboxResponse` envelope (`candidates` + `sources`) instead of a bare
`DiscoveryCandidateResponse[]` — see the Conventions note above for the full wire-contract
writeup and the reason it is an intentional breaking change. Two new `dto/` records
(`DiscoveryInboxResponse`, `DiscoverySourceResponse`); the controller's constructor gained a third
param, `DiscoveryService` (`vision-warehouse`'s `application.discovery` package). 1 new test,
`DiscoveryInboxControllerTest#listCarriesSourceHealthAlongsideTheCandidateList`; every pre-existing
`list` test's JSON-path assertions moved from `$[...]` to `$.candidates[...]`.
`./mvnw -B -pl contexts/vision-warehouse -am install -DskipTests` then `./mvnw -B -pl
station/vision-api -o test` (offline, no `-am` — a concurrent agent's in-progress, uncommitted
`contexts/vision-flight` edit was mid-break on this shared branch at the time; resolving `vision-flight`
from its last-known-good installed jar instead of rebuilding its currently-broken source avoided
blocking on unrelated work, per CLAUDE.md's "never run reactor-wide builds while another agent's
task holds modules red") — `station/vision-api` **944** tests, 0 failures (the exact delta from 941
is not attributable to this wave alone: `git status` shows a sibling agent's concurrent, unrelated
`OpsThresholdsController`/`BatteryThresholdsResponse` additions already present in this shared
working tree).

**ASSET-FLOWS wave BK4 (D1p) done.** `AssetSessionController#engage` now passes `CurrentUser#userId()`
through to `UsageTracker#engage(AssetId, UserId)` (widened, CLAUDE.md rule 10 — every call site
updated, no new overload); `AssetUsageResponse`/`UsageSummaryResponse` each gained a `pilotId` field
(raw UUID string, `null`/omitted when unknown — see DTO conventions above and the endpoint table row
above). No new Flyway migration (`storage/persistence`'s `V28` already carried the column
schema-only). `./mvnw -B -pl station/vision-api -am test -DskipWeb` — **949** tests, 0 failures (+5
over BK3's 944: `AssetSessionControllerTest#engagePassesTheCurrentUsersIdThroughToUsageTrackerEngage`
plus new/strengthened pilot assertions in `AssetUsageResponseTest`/`UsageTimelineControllerTest`).
Docker not needed for this module.

**ASSET-FLOWS wave BK3 (D6/S3 backend) done.** New `OpsThresholdsController` (1 handler, `GET
/api/ops/thresholds`, `@OpenByDesign` — display config, not fleet or per-user data, so any signed-in
caller may read it) + 2 new `dto/` records, `BatteryThresholdsResponse(int warningPercent, int
criticalPercent)` nested inside `OpsThresholdsResponse(BatteryThresholdsResponse battery)`, wire shape
frozen exactly per docs/plans/active/ASSET-FLOWS-PLAN.md §2:
`{"battery":{"warningPercent":25,"criticalPercent":10}}`. Followed the `CvTrackersController`/
`CvModelsController` precedent (a plain config-backed DTO bean built once in `vision-app`'s wiring and
injected into a controller that does nothing but return it) rather than the `OnboardingProperties`
bridge-properties pattern (`support/`) — there is exactly one caller and no per-request branching, so a
second bridge type would only add indirection (`java-clean-code` §1: an interface/bridge needs to earn
its place). The controller throws nothing, so `ApiExceptionHandler` gained no new mapping. `station/
vision-app`'s wiring is `OpsWiringConfiguration#opsThresholds(VisionOpsProperties)` — see that module's
own MODULE.md entry for the properties record and its note on `ApplicationServiceWiring#batteryMonitor`
(BK2, this same cycle), which reads the same two `vision.ops.battery.*` keys via raw `@Value` by
deliberate design (converges on this wave's property keys/defaults, not a bug). New `OpsThresholdsControllerTest` (2 cases, MockMvc `standaloneSetup`, mirrors
`CvTrackersControllerTest`): asserts the exact frozen JSON shape, and that the controller reflects
whatever `OpsThresholdsResponse` it was built from (proving the values are `vision-app`'s wiring concern,
not hardcoded here). `./mvnw -B -pl station/vision-api -am test` — **944** tests, 0 failures (net +2 over
BK6's own 944 baseline is misleading by coincidence — see that wave's note above: this wave's 2 new
`OpsThresholdsControllerTest` cases were already counted inside BK6's reported 944 since both waves'
changes were concurrently present in this shared working tree at either wave's gate time). Also
independently verified green inside the full `-am` reactor build gating BK3's own `station/vision-app`
run (`station/vision-api` section of that log: 944, 0 failures). Docker not needed for this module
(`vision-api` has no Testcontainers-backed test). Nothing deferred on the vision-api side.

**FLY-CONTROL-UX-PLAN wave BK1 done.** New `RcThresholdsResponse(int neutralTolerancePercent)` `dto/`
record — same one-field, no-validation wire-record shape `BatteryThresholdsResponse` already
establishes (validation lives server-side in `vision-app`'s `VisionOpsProperties.Rc`, not on the wire
DTO). `OpsThresholdsResponse` widened to `OpsThresholdsResponse(BatteryThresholdsResponse battery,
RcThresholdsResponse rc)` — a second constructor parameter at the one record, not a new overload
(CLAUDE.md rule 10); the only call site is `vision-app`'s `OpsWiringConfiguration#opsThresholds` (see
that module's own MODULE.md entry), updated in the same wave. `OpsThresholdsController` gained no new
endpoint and no new exception mapping — `GET /api/ops/thresholds` now serves `{"battery":{...},
"rc":{"neutralTolerancePercent":5}}`; its Javadoc return line was updated to match. No new `@OpenByDesign`
decision needed — the existing "display config, any signed-in caller may read it" reasoning already
covers the RC tolerance. `OpsThresholdsControllerTest` gained no new test methods; its 2 existing cases
were widened with `jsonPath("$.rc.neutralTolerancePercent")` assertions alongside the pre-existing
`battery` ones, exercising both the frozen-default fixture and the "reflects whatever config it was
built from" fixture.

`./mvnw -B -pl station/vision-api -am test` — **951** tests, 0 failures, 0 errors (0 net new test
*methods* from this wave — the 2 `OpsThresholdsControllerTest` cases were widened in place, not
duplicated; the module's total moved from the 949 documented at BK4 to 951 from other concurrently-landed
waves on this shared branch, not from this one). Docker not needed for this module. Also green in the
same session: `station/vision-app` **326** (see that module's own MODULE.md entry). Nothing deferred.

**FLY-CONTROL-UX-PLAN wave H1 done.** See "Live updates"/`/ws/manual-control` above for the catch-all
denial + engage-duration instrumentation this wave added, and `docs/plans/active/fly-control-ux/R3-handshake-denial.md`
for the trace that motivated it plus its own appended "Firmware note" (read-only rover-sim/firmware
finding: F4's learned-peer authority gate also silently drops `RC_CHANNELS_OVERRIDE` from a second
source, confirmed by reading the real sketch at `~/Arduino/ardupoilot-start/MavlinkUdpLink.cpp`
outside this repo — no firmware file touched). Blocking audit found no wait/sleep/socket-read
anywhere under `engage()` that could approach the web client's 4s abandon, so **no bound was added**
in `drone-link/mavlink` or `contexts/vision-flight` — neither module was touched this wave.
`ManualControlWebSocketHandlerTest` gained 3 new cases (14 → 17): unexpected-exception →
`INTERNAL_ERROR` denied frame + WARNING log with stack trace (and that the exception's own message
never reaches the client), a `NoSuchElementException` (unknown asset) instance of the same gap, and a
0ms-threshold smoke test proving the duration log actually escalates to WARNING.
`./mvnw -B -pl station/vision-api -am test` — **954** tests, 0 failures, 0 errors (951 → 954, +3, all
new; nothing else changed). Docker not needed. `drone-link/mavlink`/`contexts/vision-flight` gates not
run — this wave changed no file in either module.

**COMMAND-MAP-FLOW-PLAN wave B1 done (D1 fix).** `AssetController#telemetry`
(`GET /api/usages/{usageId}/telemetry`) now reads `TelemetryRepositoryPort#findLatestByUsage`
instead of `#findByUsage` — the `/command` fleet map polls this endpoint treating the last array
element as "current position", and `findByUsage`'s earliest-first window meant that element never
changed again past `limit` samples (`docs/plans/active/COMMAND-MAP-FLOW-PLAN.md` D1). Wire contract
unchanged: same path, same `limit` param, same `TelemetrySampleResponse[]` shape, same ascending
order, same `200 []` on an unknown usage — only which window of the flight comes back. `AssetController`'s
constructor is unchanged (still the same `TelemetryRepositoryPort` field, just a different method
called on it). `AssetControllerTest`'s existing telemetry-block tests (`888`–`1018`) were repointed
to stub/verify `findLatestByUsage` in place — no test methods added or removed here, since the
port-level "latest window" / "ascending order" / "empty case" proofs live in
`storage/persistence`'s `PostgresDockerIntegrationTest$TelemetryRepositoryTests` instead (271 → 274
tests there). `./mvnw -B -pl station/vision-api test` — **954** tests, 0 failures, 0 errors (unchanged
from before this wave — every edit here was a rename of an existing stub/verify call plus one
javadoc rewrite, not a new test). Docker not needed for this module. Also green in the same session:
`storage/persistence` **274** and `station/vision-app` **326** (both docker-ran, not skipped — see
those modules' own MODULE.md entries). Nothing deferred; `vision-events`/`DefaultReplayService` and
`UsageTimelineController` untouched by design (they own the earliest-first replay window, a
different question — see plan §3.7/§5).

**AUTH-ROLES-PLAN wave B3 done.** New `BootstrapController` (`GET`/`POST /api/auth/bootstrap`,
`@OpenByDesign`, anonymous, `permitAll`), `AuthPasswordController` (`POST /api/auth/password`, self-
service), and two new `UserAdminController` handlers (`POST /api/users/{userId}/password`,
`PUT /api/users/{userId}/memberships`) — see "API surface" above for every new/widened endpoint
shape. `AuthController#login` gained an optional `kiosk` body field; a non-`VIEWER` requesting a
kiosk session is refused `400 KIOSK_NOT_PERMITTED` *after* a real credential check (so the
already-established session is explicitly torn down via `SessionAuthenticator#logout`, not merely
left to expire). `AssignmentController#assign`'s body gained an optional `role` field (`AssignAssetRequest#toRole()`,
absent body/field → `PILOT`, byte-identical to every pre-B3 caller); `pilots()`/`myAssignments()` now
enrich each entry with its `AssignmentRole` seat (`PilotResponse`/`AssignmentResponse`), the latter
defaulting to `PILOT` when `AssignmentRepositoryPort#roleFor` finds no link (an assignment the
`assignmentsFor` index still lists but whose seat lookup races an unassign — the same
"index says yes, detail lookup says no → assume the safer/older answer" shape as other scope-adjacent
reads in this module, not a new pattern). `MeResponse` gained `capabilities[]`/`scopeKind`/
`mustChangePassword` — every existing field byte-identical. `PrincipalResolver` (`security/`) gained
`role()`/`authority()` — both implementations live in `vision-app` (see that module's own MODULE.md);
this module never imports `org.springframework.security` to use them. `DemoPeople#seed`/`DemoScenario`'s
private `assign`/`grant` helpers were threaded with an explicit `UserId actor` parameter (the demo has
no CREW story — every demo grant is the wide `AssignmentRole#PILOT` seat, documented in place).
Error field naming: every new `ApiExceptionHandler` mapping this wave added
(`KIOSK_NOT_PERMITTED`/`WEAK_PASSWORD`/`ALREADY_INITIALIZED`/`AUTH_DISABLED`) uses the pre-existing
`ErrorResponse(String error, String message)` shape — the wire field is `error`, matching every
mapping that predates this wave (`NOT_FOUND`/`FORBIDDEN`/etc.); nothing introduced a `code` field.
`./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app test -DskipWeb` — vision-api
**961** tests (954 → 961, +7: 2 kiosk-login cases, 3 `UserAdminController` cases, 2 `AssignmentController`
seat-enrichment cases), 0 failures. Also green in the same run: `storage/persistence` **276**
(274 → 276, +2, `AssignmentRepositoryTests`) and `station/vision-app` **326** (unchanged test count —
this wave's `vision-app` edits were mechanical call-site fixes for widened application-service
signatures, not new tests). Docker ran (not skipped — Testcontainers started a real `postgres:16`,
Flyway migrated through `V33`). Waves B4 (per-asset command authority)/B5 (Spring Session JDBC)/B6
(migrate the ~34 `canManageOrg`/`canManage`/`canAdminister` call sites + the VIEWER-precedence flip)/
B0b (flip `vision.auth.enabled`'s default) are open — see `docs/plans/active/AUTH-ROLES-PLAN.md`.

**AUTH-ROLES-PLAN wave B4 done.** New `security.AssetAuthority` (interface, frozen 3-method shape
per §3.9 — a join point shared with CREW-CONTROL-PLAN §3.7) + `CapabilityAssetAuthority` (the one
implementation) — see "API surface"/"Live updates" above for every gated call site
(`ManualControlWebSocketHandler#handleEngage`, `FlightCommandController`'s six commands,
`HlsProxyController#proxy` via the new `StreamAccess#requireVisibleForHlsProxy`) and "Gotchas" for why
the HLS gate is a second `StreamAccess` method rather than a change to the shared
`requireVisible(StreamId)`. `AssetSessionController#disengage` now records an `AuditTrailPort` entry
naming the calling user (D17) — attribution only; no seat/arbitration logic, which stays
CREW-CONTROL's to add. `SecurityConfig`'s secured-chain matcher gained `/hls/**` (D10, `vision-app`).
Deviations, each one-line: (1) `CapabilityAssetAuthority` gained a second public method,
`mayFly(Authority, UserId, AssetId)`, not on the frozen interface — the WebSocket message thread has
no `SecurityContext`, so the interface's ambient-`CurrentUser` `mayFly(AssetId)` cannot be called
from `ManualControlWebSocketHandler` at all once auth is enabled; (2) D17 (actor attribution on
disengage) was solved via the already-vision-api-reachable `AuditTrailPort` rather than widening
`UsageTracker#disengage`'s signature or adding a field to `AssetUsage` — both of those modules were
reserved for a concurrent agent's own wave; (3) `contexts/vision-flight`/`contexts/vision-identity`
were not touched despite being named in the plan's literal B4 file list — every call site this wave
actually needed lives in `vision-api`. `./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app
test -DskipWeb` — vision-api **979** (961 → 979, +18: 12 `CapabilityAssetAuthorityTest` + 2
`ManualControlWebSocketHandlerTest` + 2 `AssetSessionControllerTest` + 2 `FlightCommandControllerTest`),
`storage/persistence` unaffected at **276**, `station/vision-app` **328** (326 → 328, +2 — see that
module's own MODULE.md). Docker ran for real (Testcontainers `postgres:16`, Flyway unchanged at
`V33` — this wave added no migration). `vision.auth.enabled` stays `false` by default, unchanged;
the default-config auth-off suites stayed green throughout. Waves B5/B6/B0b open.

**AUTH-ROLES-PLAN wave B6 done.** The ~34 remaining `canManageOrg`/`canManage`/`canAdminister` call
sites this wave's own plan text named are now migrated onto `Authority` everywhere (see "Authorization
tags" table above, updated to `authority.mayManageOrg()`/`mayManageFleet(ownership)`/`mayAdminister()`)
— by the time this wave reached vision-api, most controller production call sites had already migrated
in an earlier pass of the same wave; what remained here was fixing the test fixtures and fixing one
missed production call site:

- **9 controller test fixtures** (`GeoRegionControllerTest`, `CameraPoseControllerTest`,
  `GeofenceControllerTest`, `DiscoveryInboxControllerTest`, `CategoryControllerTest`,
  `EventControllerTest`, `SimulationControllerTest`, `AssetControllerTest`, `DeviceControllerTest`) had
  a `currentUserWithScope(VisibilityScope scope)`-style `CurrentUser` fake whose `authority()` override
  threw `UnsupportedOperationException("<Controller> never calls authority()")` — written before this
  wave's controllers actually started calling `authority()`. Once they did, every one of these 9 broke
  (40 errors on the first honest, non-`-q` run). Fixed uniformly: `return new Authority(scope,
  EnumSet.allOf(Capability.class))` — **full capabilities, deliberately**, so a PILOT/MANAGER-scope
  test case still proves the *scope* gate, not a missing capability. `AssetControllerTest`/
  `DeviceControllerTest` reference `com.drones.vision.platform.Capability` fully-qualified (no import)
  since both already import `com.drones.vision.kernel.Capability` — a different type, unrelated device
  capabilities (`VIDEO`, …) — under the same simple name.
- **`AfterActionAssemblerTest`/`AssetParameterControllerTest`** — same "full capabilities, restricted
  scope" pattern used deliberately where a test needs to prove a *scope* boundary causes a denial,
  e.g. `AfterActionAssemblerTest#assembleThrowsAccessDeniedWhenTheViewerMaySeeButNotExportTheAsset`
  builds `new Authority(VisibilityScope.assignedAssets(Set.of(assetId)), EnumSet.allOf(Capability.class))`
  rather than a scope-only fixture, so the assertion is unambiguous about which axis failed.
- **`security.StreamAccess`** — a genuine production call site this wave's earlier grep-based "zero
  remaining callers" pass missed, surfaced only by running the plan's full multi-module `-am test`
  build after `core/vision-platform` deleted the three methods outright (`NoSuchMethodError` at
  runtime, 42+ cascading errors in `StreamControllerTest`/`HlsProxyControllerTest`/
  `LiveAssetAccessDevPrincipalTest`). Two call sites, `visible(DeviceId)` and `visibleAsset(AssetId,
  VisibilityScope)` — both an "unowned device/asset falls back to a caller whose scope is deployment-
  wide" check. Fixed by replacing `scope.canAdminister()` with `scope.isUnbounded()` directly, **not**
  by widening `StreamAccess` to carry an `Authority` — the deleted `canAdminister()`'s own body was
  always exactly `kind == UNBOUNDED` (confirmed from `git log`), so `isUnbounded()` is a byte-identical
  replacement; widening instead would have rippled into `StreamAccess`'s eight `StreamController`
  callers for no behavior change, and would have made this fallback capability-gated
  (`Authority#mayAdminister()` also requires `Capability.MANAGE_ORG`) when it never was before. Class
  javadoc updated in three places to stop citing the now-deleted methods and to state plainly why this
  fallback is deliberately scope-only.

`./mvnw -B -pl station/vision-api test -DskipWeb` — **985/985** green, 0 failures/errors — the same
count before and after this wave's fixes (every change here repaired an existing test's fixture or a
production call site that would otherwise `NoSuchMethodError`; no test method was added or removed).
The doc's last-recorded vision-api count (**979**, wave B4 entry above) predates an undocumented wave
B5 (Spring Session JDBC — the `V34` migration this module's tests already exercise) that isn't this
wave's to reconstruct; 985 is this wave's own before-and-after baseline, not a delta from 979. Plan's
full green line (`core/vision-platform,contexts/vision-warehouse,contexts/vision-flight,
contexts/vision-perception,contexts/vision-learning,contexts/vision-map,contexts/vision-identity,
station/vision-api,station/vision-app -am test`) — **BUILD SUCCESS** end-to-end in the foreground (a
first attempt was backgrounded and lost when the agent turn ended — backgrounded builds do not
survive the turn that started them); see `core/vision-platform/MODULE.md`'s own B6 entry for the full
per-module tally. Docker ran for real (Testcontainers `postgres:16`). `vision.auth.enabled` stays
`false` by default, unchanged — the default-config bar held throughout. The plan's B1 text also asked
for an ArchUnit rule banning new `canManageOrg`/`canManage`/`canAdminister` call sites (deferred there
to this wave); it was never added and is now moot — the three methods are deleted outright, a stronger
guarantee than any reflection-based check over a method that no longer exists to call (see
`core/vision-platform/MODULE.md`'s B6 entry for the full reasoning). Wave B0b (flip
`vision.auth.enabled`'s default) is the only item this plan still has open.
**SOURCE-ONBOARDING-2-PLAN.md §3.2 wave C done (2026-09-05, uncommitted at time of writing).** C1
(`POST /api/discovery/inbox/{id}/attach`), C2 (new `DiscoveryStatusController`, `GET
/api/discovery/status`), C3 (`SystemNetworkController`/`NetworkAddressResponse`/
`SystemNetworkResponse` widened for `kind` + mediamtx push facts), C4 (new `discovery` SSE topic),
C5 (`POST /api/discovery/inbox/{id}/restore`) — see the endpoint table, exception-mapping table, DTO
conventions, and "Live updates" section above for the full shapes; C6 (a real `StreamStateObserver`
wired to `devices`) is wholly a `vision-app` change, noted in "Live updates" above and detailed in
that module's own MODULE.md. Also fixed two pre-existing test compile breaks found while wiring this
wave, unrelated to discovery/network but blocking this module's test compile either way: `git
blame`-confirmed leftovers from an earlier, already-merged wave that widened `SourceHealth` to a
3-arg canonical constructor (`id, status, lastScanAt`) without updating
`DiscoveryInboxControllerTest`'s two `new SourceHealth("mediamtx", SourceStatus.UNREACHABLE)`
2-arg call sites (fixed by adding `Instant.now()` as the third argument).

**Deliberate deviation, documented in place**: `LiveUpdateDiscoveryInboxService` (the decorator that
turns a `ReportOutcome#changed()` into a `discovery` SSE publish) lives in `vision-app` and depends
directly on the **concrete** `LiveUpdateRegistry` class, not a new per-context `*LiveUpdatePort`
interface — every other such decorator in this codebase depends on a narrow port instead. Adding a
`DiscoveryLiveUpdatePort` to `contexts/vision-warehouse` would have meant writing outside this
wave's file scope (that context belonged to earlier waves A/B); see that class's own javadoc in
`vision-app` for the full reasoning.

`./mvnw -B -pl core/vision-kernel,core/vision-platform,contexts/vision-warehouse,contexts/vision-identity,contexts/vision-flight,contexts/vision-perception,contexts/vision-map,contexts/vision-events,contexts/vision-learning,contexts/vision-simulation
install -DskipTests` (this worktree's own source — the shared `~/.m2` local repo had been
concurrently overwritten by another agent's build of the main checkout's identity module mid-task,
surfacing as `VisibilityScope`-vs-`Authority` and `AssignmentService`/`UserService` signature
mismatches with no relation to this wave's own edits; reinstalling from this worktree's source
resolved it — see `station/vision-app/MODULE.md`'s own entry for the parallel adapter-module
reinstall this same contamination required) then `./mvnw -B -pl
storage/persistence,station/vision-api,station/vision-app test -DskipWeb` — `storage/persistence`
**274** (unchanged, read-only this wave; docker ran, not skipped), `station/vision-api` **965**
(+11 over 954: 7 new `DiscoveryInboxControllerTest` cases for `attach`/`restore`, 2 new
`SystemNetworkControllerTest` cases for the video-push-facts present/absent cases, 2 new
`LocalNetworkAddressesTest` cases for `VIRTUAL` classification + LAN-before-VIRTUAL sort order),
`station/vision-app` **326** (unchanged in count from the prior B1 entry above — see that module's
own MODULE.md for wave C's actual test-count delta there) — all green, default-config bar held
throughout (`vision.discovery.live.enabled`/`vision.live.stream-state-push.enabled` both default
**true**, proven by this same green run rather than a separate flag-off suite). Nothing deferred.

**CREW-CONTROL-PLAN.md wave W2 done (2026-09-05, uncommitted at time of writing, branch
`feat/crew-control`).** New `security.SeatAccess` (the one collaborator every guard calls — 5 params,
at the constructor ceiling) + `security.SeatAccessSettings` (plain, framework-free settings record
bridged from `vision-app`'s `VisionCrewProperties`) + `support.SeatSupport` (device/stream→asset
resolution, display-name lookup, `FORCE`/`DENIED:SEAT_HELD` audit writes) + new `SeatController`
(`GET`/`POST`/`DELETE /api/assets/{id}/seats[/{kind}]`, §3.6 frozen wire contract) + 3 new DTOs
(`SeatsResponse`/`SeatHolderResponse`/`TakeSeatRequest`, see "DTO conventions" above) + the seat guard
threaded into `FlightCommandController` (6 command handlers), `AssetStreamController`
(start/stop), `AssetSessionController` (engage/disengage), `StreamController` (start/stop/
updateConfig-family), and `ManualControlWebSocketHandler#handleEngage` — see the two "CREW-CONTROL
wave W2" convention notes above (REST + WS) for exact insertion points and rule composition.
`AssetAuthority`/`CapabilityAssetAuthority` already existed (AUTH-ROLES-PLAN wave B4) and needed no
change; this wave only adds `SeatAccess` as a second, later-consulted gate.

Deviations, each one-line: (1) fixed a genuine **pre-existing** compile defect unrelated to
CREW-CONTROL, in `vision-app`'s `LiveFrameFallbackStreamService` (missing `StreamService#followStatus`
override — `git blame`-confirmed leftover from an already-merged, unrelated commit,
`29536635 feat(track-follow W2)`, that widened the interface without updating this one decorator);
fixed minimally, matching the class's own "every other method delegates unchanged" pattern — see that
module's own MODULE.md. (2) Four constructors pushed past the 5-arg ceiling — `SeatAccess` and
`SeatSupport` land exactly at 5, `AssetStreamController` to 6, `StreamController` to 7 — each
documented in its own javadoc; `StreamController` was already at 6 for `StreamAccess` before this
wave, so 7 continues an existing precedent rather than opening a new one. (3) §3.6 worked one example
(`force` on the flight seat only); this implementation generalizes `force`/`mayForceSeat` to both seat
kinds symmetrically, since the plan's own rule table (§3.2) states the force rule kind-agnostically
and a flight-only implementation would have been an arbitrary, undocumented asymmetry. (4) The
explicit-actor overload `requireFlightSeat(UserId, AssetId)` trusts `mayFly=true` unconditionally
(mirrors `CapabilityAssetAuthority`'s own explicit-actor precedent, AUTH-ROLES wave B4) since the WS
handler already ran its own `mayFly` check immediately before calling it — documented in `SeatAccess`'s
own javadoc, not re-derived here.

`./mvnw -B -pl contexts/vision-flight,station/vision-api,station/vision-app test -DskipWeb` —
`contexts/vision-flight` **447** (unchanged — W2's file scope excludes this module, which W1 already
shipped), `station/vision-api` **1046** (+37 over 1009: 22 `SeatAccessTest` + 12 `SeatControllerTest`
+ 1 `StreamControllerTest` case proving rule 3 — flight-seat holder never conflicts on the camera
seat and preempts any prior camera holder — + 1 `ManualControlWebSocketHandlerTest` case proving the
WS `SEAT_HELD` denial fires before `ManualControlService#engage` is ever called + 1
`FlightCommandControllerTest` case), `station/vision-app` **334** (unchanged — no test file in this
module's scope touched). All green, 0 failures/errors. Docker ran for real (Testcontainers
`postgres:16`, Flyway migrated through `V34`). Default-config guardrail held: `vision.crew.enabled`
defaults `false`, and every pre-existing test in all three modules is unmodified and still green
under that default — the four pre-existing controller/WS-handler test files needed only a
disabled/pass-through `SeatAccess` threaded into their existing construction call sites to keep
compiling, never a behavioral change. Nothing deferred to a later wave from this module's own scope;
W3 (crew UI, vision-web) is a separate, concurrently-running agent's file scope, not this one's.

**fix/fleet-topic-scope: closed the `fleet`-topic visibility-scope leak.** `freshFleetEnvelope`'s
unscoped `AssetService#assets()` snapshot used to reach every connection unfiltered — `mayReceive`
only ever checked `map` events and per-asset envelopes, so a `fleet` envelope (neither) always
passed. Replaced `LiveConnection#mayReceive(envelope): boolean` with `project(envelope):
LiveEnvelopeResponse` (nullable) reusing the connection's existing `assetVisibility` predicate (the
same one `LiveAssetAccess#deliveryPredicate` builds from `StreamAccess#visibleAsset`, which
`AssetService#assets(scope, includeDeleted)` — i.e. `GET /api/assets` — already applies): `map`
unchanged (envelope-or-null by `layerId`), per-asset unchanged (envelope-or-null by `assetId`),
`fleet` now narrows its `List<AssetSummaryResponse>` to visible entries and **never** returns `null`
(an empty list is correct for a viewer with nothing visible), everything else passes through as the
identical instance (load-bearing for `broadcast`'s serialize-once reuse). Fixed all three call sites
that used to hand a connection an unfiltered envelope: `broadcast` (rewritten to project first, reuse
the one shared serialized `String` only when `project` returned the same instance back), `connect()`'s
snapshot/resume burst, and `updateTopics()`'s newly-added-topic burst — the last two are what actually
leaked in production, since a fresh connection's seeded `fleet` snapshot and any `Last-Event-ID`
resume both replayed straight from the buffer with no per-viewer narrowing at all. `fleetBuffer`
itself stays unfiltered by design, matching `map`'s buffer, so resume re-filters against the
resuming viewer's *current* scope rather than the scope of whoever happened to seed the buffer.
Fixed every now-false "only `map` is filtered" javadoc claim found by grep (`LiveUpdateRegistry`
class doc, `broadcast`, the old `mayReceive`, `LiveTopicKind.FLEET`/`MAP`, `LiveTopic.MAP`,
`LiveAssetAccess`, `LiveEnvelopeResponse`'s `@param payload`) — four more locations than the four
named going in, since the claim had spread past them.

No new collaborator, query, DTO field, or exception mapping — this is a pure delivery-filtering fix
using a predicate the connection already carried; `ApiExceptionHandler`'s table is unchanged. No new
endpoint or wire-shape change either: `fleet`'s envelope shape (`List<AssetSummaryResponse>`) is
exactly what it always was, only which elements a given connection receives changed — nothing for a
UI wave to react to beyond "you may now legitimately see fewer/zero assets in a `fleet` envelope,
which was always the intended scope."

Six required proofs, three end-to-end (`LiveFleetScopingTest`, MockMvc over the real
`LiveController`/`LiveUpdateRegistry`/`LiveAssetAccess`/`StreamAccess` chain, mirroring
`LiveAssetScopingTest`'s established pattern) plus three pure-unit (`LiveUpdateRegistryTest`,
package-private `register()`/`publishFleetChanged()` seam): (1) a GROUPS-scoped viewer's fleet
broadcast only carries its own visible assets — `aFleetBroadcastDeltaIsAlsoNarrowedToAGroupsScopedViewersScope`
+ the pure-unit `broadcastNarrowsTheFleetEnvelopesAssetListPerConnectionWhileAnUnboundedViewerKeepsEverything`;
(2) the connect-time seeded snapshot — the actual leak path — is already narrowed —
`connectsSeededFleetSnapshotIsAlreadyNarrowedToAGroupsScopedViewersScope`; (3) a `Last-Event-ID`
resume re-filters the buffer per resuming viewer, proving the buffer itself was never filtered —
`aLastEventIdResumeReplaysTheUnfilteredBufferReFilteredPerViewer` (one admin connect seeds the shared
buffer with both assets; a GROUPS-scoped resume sees only its own, an UNBOUNDED resume still sees
both); (4) UNBOUNDED (admin) sees every asset, no regression — covered in both the end-to-end and
pure-unit tests above; (5) a viewer whose scope includes nothing gets an envelope with an empty list,
never a dropped one — `aViewerWithNothingVisibleReceivesAnEmptyFleetListNotADroppedEnvelope` +
`aFleetBroadcastToAConnectionWithNothingVisibleStillDeliversAnEmptyListEnvelopeNotADroppedOne`; (6)
non-fleet topics are byte-identical to before, guarding serialize-once —
`nonFleetTopicsReuseTheIdenticalSerializedStringAcrossConnectionsGuardingSerializeOnce` asserts
`assertSame` on the delivered `String` across two connections with divergent predicates.

`./mvnw -B -pl station/vision-api -am test -DskipWeb` — `station/vision-api` **1060** (before this
task's 8 new tests: **1052**), 0 failures/errors/skipped; full reactor summary (`kernel` through
`vision-simulation` plus `vision-api` itself) all `SUCCESS`, `BUILD SUCCESS`, exit 0. No feature flag
gates this change (it is a straight correctness fix, not opt-in behavior), so there is no
default-config-off suite to separately hold green — every pre-existing test in this module's scope is
unmodified and still passing under whatever config it always ran under. Docker not needed/not run —
this module's tests are pure-unit/MockMvc, no Testcontainers dependency in the touched files.
Nothing deferred; the fix, its three call sites, its javadoc corrections, and its six required proofs
are complete in this task's scope (`station/vision-api` only — `vision-web`'s
`drone-picker-facade.ts` was read for context, per instruction, but not modified, and self-corrects
once the server stops over-sending).

**LIVE-POLL-RETIREMENT-PLAN.md waves L3+L4 done (2026-09-06, branch `feat/live-topics-zones-system`,
uncommitted at time of writing).** L3: new `zones` SSE topic — `GeofenceLiveUpdatePort`/
`GeofenceZoneEvent` (`contexts/vision-flight`), `GeofenceZoneEventPayload` (`dto/`), `LiveTopicKind.ZONES`/
`LiveTopic.ZONES`, a `zonesBuffer` (FIFO, shares `eventBufferCapacity`) and `publishZoneEvent` on
`LiveUpdateRegistry` (now implementing seven ports). L4: new `system` SSE topic backed by
`live/SystemStatusSampler` (a fellow vision-api class, not a per-context port) — `LiveUpdateRegistry`
gained one public method (`publishSystemStatus`) and one inline-initialized buffer (`systemBuffer`,
capacity-1 latest-only), **no new constructor collaborator**. L4a: `support/SystemStatusReader`
extracted `safeStatus`/`overall`(now `worstHealth`, made public for reuse) out of
`SystemStatusController` — that controller's wire output is unchanged, its own pre-existing test
suite (`SystemStatusControllerTest`, untouched) is the guardrail. See "Live updates" above for the
full topic/self-feedback-mitigation writeup, "Package layout" for the new
`live/SystemStatusSampler`/`support/SystemStatusReader` classes, and "Gotchas" for the widened
`@Qualifier("liveUpdateRegistry")` note.

No new `ApiExceptionHandler` mapping — neither wave introduces a new HTTP-facing failure mode
(`GeofenceLiveUpdatePort`/`SystemStatusSampler` are both fire-and-forget from the caller's point of
view). No new REST endpoint — both waves are pure SSE-topic additions; `GET /api/geofences`'s existing
CRUD endpoints are unchanged (see the endpoint table above), and `system`'s payload is the same
`SystemStatusResponse` `GET /api/system/status` already returns.

Tests added: `LiveUpdateRegistryTest` gained 3 (`publishZoneEventAppendsAZonesEnvelopeForEachAction`,
`deletedZoneEventCarriesTheLastKnownZoneInFullOnTheWire`,
`publishZoneEventReachesASubscribedConnection` — the last an end-to-end SSE-delivery proof via
`register`); `LiveTopicTest` gained 1 (`parsesZonesAndSystemAsTheSharedConstants`); new
`SystemStatusSamplerTest` (5 cases) proves the three L4d requirements plus one extra: (1)
`unchangedStatusSampledRepeatedlyBroadcastsExactlyOnce`, (2)
`aLiveUpdatesOnlyChangeNeverTriggersAnAdditionalBroadcast` (the self-feedback proof — `live-updates`
moves its connection count then flips `OK`→`DEGRADED`, zero additional broadcasts), (3)
`aRealSubsystemHealthChangeTriggersOneAdditionalBroadcast` (contrast case), plus
`checkedAtAloneNeverTriggersABroadcast` and `constructorRejectsNullCollaborators`.
`contexts/vision-flight`'s `DefaultGeofenceServiceTest` was widened in place (not new test methods) to
assert publish-on-create/update/delete and the `DELETED`-carries-last-known-zone contract — see that
module's own MODULE.md entry.

`./mvnw -B -pl station/vision-api -am test -DskipWeb` — **1069/1069** green (1060 → 1069, +9: 3+1+5
above). `./mvnw -B -pl core/vision-kernel,core/vision-platform,contexts/vision-warehouse,contexts/vision-identity,
contexts/vision-flight,contexts/vision-perception,contexts/vision-map,contexts/vision-events,
contexts/vision-learning,contexts/vision-simulation install -DskipTests` then `./mvnw -B -pl
storage/persistence,station/vision-api,station/vision-app test -DskipWeb` (the `-am`-on-`vision-app`
form was tried first and pulled in `adapter-rtsp` as a reactor dependency, whose
`MediamtxDockerIntegrationTest` hit a genuine, pre-existing, unrelated Docker/network flake — RX side
never connected to a real mediamtx container within its 1-minute bound; switched to the
install-then-`-pl`-without-`-am` recipe instead, which reuses `adapter-rtsp`'s already-installed
`~/.m2` jar untouched, since this task's file scope never touched that module) — `storage/persistence`
**283/283** (unchanged, read-only this wave; Postgres Testcontainers ran for real), `station/vision-api`
**1069/1069**, `station/vision-app` **354/354** (`LiveWiringTest` 5→6, `+systemStatusSamplerBeanExists`;
`LiveDisabledWiringTest` renamed one test in place to also assert `SystemStatusSampler`'s bean absence
— see that module's own MODULE.md entry) — all green, `BUILD SUCCESS`, default-config bar held
throughout both `vision.live.enabled=true` (default) and `=false` wiring tests. Docker ran for real
(not skipped). Nothing deferred except the pre-existing `everDropped`/`live-updates` `DEGRADED` defect,
explicitly out of scope per this wave's own task spec (see "Live updates" above for why the L4
mitigation routes around it rather than fixing it).
