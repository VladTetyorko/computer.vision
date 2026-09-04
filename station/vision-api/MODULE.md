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
`DiscoveryInboxController` — ZERO-CONFIG-ONBOARDING Z2c) · `dto/` (wire
records only, ~187 — house rule "zero DTO leakage": no domain type is ever serialized directly) ·
`security/` (`CurrentUser`/
`PrincipalResolver`/`StreamAccess`/`OpenByDesign` — the authorization seam, see Conventions) ·
`live/` (SSE connection registry, per-topic ring buffers, per-connection visibility filtering) ·
`ws/` (`/ws/manual-control` raw `WebSocketHandler`) · `proxy/` (`HlsProxyController` — a pass-through
edge owning no application service) · `ratelimit/` (`RateLimitFilter`/`TokenBucket`, per-principal
`/api/**` token bucket) · `support/` (edge-local helpers: `SnapshotJpegEncoder`, `CapabilityParsing`,
`DeviceOriginParsing`, `RemediationOrchestrator`, `VisionApiProperties`, `InventoryExportService` —
WAREHOUSE-UX W3, the hand-rolled CSV behind `GET /api/inventory/export`; `AssetRowFacts` — WAREHOUSE-UX
W8, bundles the `firmware`/`totalFlightSeconds` cross-context joins `AssetController` needs, see
Conventions) · `demo/` (property-gated
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
| `manage` | `scope.canManage(ownership)` — visible but not manageable → **403**. |
| `manageOrg` | `scope.canManageOrg()` (ADMIN or MANAGER) → **403** otherwise. |
| `administer` | `scope.canAdminister()` (deployment-global, no group boundary) → **403** otherwise. |
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
| AssetSessionController | DELETE | `/api/assets/{id}/session` | Operator "disengage" (idempotent; demotes rather than closes if a stream is still running) | scope |
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
| StreamController | GET | `/api/streams/{streamId}/tracks` | Track book + duty-cycle stats; never errors on unknown stream (empty `tracks`) | scope |
| HlsProxyController | GET | `/hls/{streamId}/**` | Reverse-proxy this asset's live HLS bytes to the mediamtx sidecar | scope (`StreamAccess`, checked **before** the upstream is ever contacted) |
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
| FlightCommandController | POST | `/api/assets/{id}/return-home` | RTL | scope |
| FlightCommandController | POST | `/api/assets/{id}/mode` | Flight-mode change | scope |
| FlightCommandController | POST | `/api/assets/{id}/arm` | Arm (optional `force`) | scope |
| FlightCommandController | POST | `/api/assets/{id}/disarm` | Disarm (optional `force`) | scope |
| FlightCommandController | POST | `/api/assets/{id}/emergency-stop` | Forced disarm, kept separate from `disarm{force}` for audit-trail clarity | scope |
| FlightCommandController | POST | `/api/assets/{id}/aux-function` | `MAV_CMD_DO_AUX_FUNCTION` | scope |
| FlightCommandController | GET | `/api/assets/{id}/flight-capabilities` | What this asset supports commanding | scope |
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
| SystemStatusController | GET | `/api/system/status` | Subsystem health rollup; never errors | **unscoped** (ledger — deliberately: no secrets exposed) |
| SystemNetworkController | GET | `/api/system/network` | Host's site-local IPv4 addresses | **unscoped** (ledger) |
| DemoController | GET | `/api/demo` | Demo-button availability probe | **unscoped** (ledger); gated by `vision.demo.enabled` (default on) |
| DemoController | POST | `/api/demo/seed` | Seed demo assets/users/streams/zones/marks; fault-tolerant (failures land in `problems`, never an error status) | scope (`DemoScenario` resolves the acting user itself) |

`/ws/manual-control` (WebSocket, `ws/ManualControlWebSocketHandler`) is the streaming RC-control
transport, outside the table above since it isn't a `@RestController` route. Its handshake resolves
`CurrentUser` the same way every REST call does (`ManualControlHandshakeInterceptor`, 401 if it
can't) and every `engage` frame re-derives scope from that handshake — see the class javadoc for the
full frame protocol. **FLEET-RADIO R2** added one additive `denied` reason code, `VEHICLE_UNIDENTIFIED`
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
| `ProbeFailedException` | 422 | `UNPROCESSABLE_ENTITY` |
| `PayloadTooLargeException` | 413 | `PAYLOAD_TOO_LARGE` |
| `GeoServiceUnavailableException` | 503 | `SERVICE_UNAVAILABLE` — request was well-formed/authorized, the collaborator it proxies to (cv-service) is down |

### Live updates (`com.drones.vision.api.live`)

One `LiveUpdateRegistry` implements all five per-context live-update ports (`FleetLiveUpdatePort`,
`TelemetryLiveUpdatePort`, `DetectionLiveUpdatePort`, `MapLiveUpdatePort`, `EventLiveUpdatePort`) and
owns every SSE connection, process-local/single-instance only. Topics: `fleet`, `event`, `devices`,
`detection-events` (always-on, no auth needed beyond the connection itself), `map` and per-asset
`telemetry:<id>`/`detections:<id>`/`geo:<id>` (individually authorized — see below). Delivery is
coalesced (leading+trailing, ~150ms default) per topic, not per connection, so exactly one resumable
`seq` exists per topic; `Last-Event-ID` resumes from a per-topic ring buffer (FIFO or latest-only
depending on topic). Tunables live in `VisionApiProperties.Live` (coalesce/heartbeat/buffer
sizes/send-timeout/buffer-eviction), bound from `vision.api.live.*`.

**Discovery inbox is poll-only, not SSE (ZERO-CONFIG-ONBOARDING Z2c, deliberate v1 scope call).**
`GET /api/discovery/inbox` is a cheap, indexed (`identity_key`/`last_seen`), idempotent read a client
can poll on its own cadence — the background sweep that populates it already runs on a fixed period
(`vision.discovery.inbox.sweep-seconds`, default 30s in `vision-app`), so there is no sub-second event
to push and a poll interval matched to the sweep period loses nothing a live topic would have delivered
sooner. No `DiscoveryLiveUpdatePort`/topic was added to `LiveUpdateRegistry` this wave: doing so would
mean a sixth `@Qualifier("liveUpdateRegistry")` selector bean in `ApplicationServiceWiring`
(`vision-app`, out of this wave's write scope) and a consuming panel in `vision-web` (owned by a
concurrent agent in the same task), neither of which exists yet to justify the wiring. Revisit if/when
a UI wave wants sub-30s latency on new-candidate appearance.

**Scoped delivery**: `MapVisibility` gates the `map` topic by `MapAccessPolicy.canView`; `LiveAssetAccess`
gates per-asset `telemetry`/`detections`/`geo` by `StreamAccess.visibleAsset`. Both filter at
subscribe time (a caller never even subscribes to something out of scope) **and** re-check on every
delivery (a scope change mid-connection, e.g. a revoked assignment, takes effect within the cache
TTL — 5s for assets, 10s for map — with no `PATCH` needed to trigger it). Both checks are called
**directly** in `LiveController`'s handler body, not only stored as a `Predicate` — a check hidden
behind a `Predicate` field is invisible to `EndpointAuthorizationTest`'s static call-graph guard,
which only recognizes a direct call to `CurrentUser.scope()`/`.viewer()` or a class named `*Access`.

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

## Conventions

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
  explicitly in-controller (`currentUser.scope().canManageOrg()`, throwing `AccessDeniedException`
  itself) because `DiscoveryInboxService#candidates()`/`#dismiss(id, userId)` carry no scope parameter
  to check against — same shape as `AuditController#list`, which has no application-service layer of
  its own to put the check in either. `register` instead passes `currentUser.scope()` and
  `currentUser.ownership()` straight through to `DiscoveryInboxService#register(...)`, which performs
  its own `canManageOrg()`+`includesGroup` checks internally — same shape as `GroupAdminController#create`
  delegating to `GroupService`. All three still reach `CurrentUser.scope()` directly inside the
  controller method body, so `EndpointAuthorizationTest`'s call-graph walk is satisfied without an
  `@OpenByDesign`/ledger entry either way.
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
  the same `LiveUpdateRegistry` singleton under five more bean names (one per `*LiveUpdatePort` it
  implements), so a plain by-type autowire finds six candidates and fails at context startup.
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
  A future "pipeline errors in the events feed" ask needs a new `EventRepositoryPort`-shaped read side.
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
