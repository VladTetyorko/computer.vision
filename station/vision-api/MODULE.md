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
`InventoryExportController` — WAREHOUSE-UX W3) · `dto/` (wire records only, ~170 — house rule "zero
DTO leakage": no domain type is ever serialized directly) · `security/` (`CurrentUser`/
`PrincipalResolver`/`StreamAccess`/`OpenByDesign` — the authorization seam, see Conventions) ·
`live/` (SSE connection registry, per-topic ring buffers, per-connection visibility filtering) ·
`ws/` (`/ws/manual-control` raw `WebSocketHandler`) · `proxy/` (`HlsProxyController` — a pass-through
edge owning no application service) · `ratelimit/` (`RateLimitFilter`/`TokenBucket`, per-principal
`/api/**` token bucket) · `support/` (edge-local helpers: `SnapshotJpegEncoder`, `CapabilityParsing`,
`DeviceOriginParsing`, `RemediationOrchestrator`, `VisionApiProperties`, `InventoryExportService` —
WAREHOUSE-UX W3, the hand-rolled CSV behind `GET /api/inventory/export`) · `demo/` (property-gated
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
| AssetController | GET | `/api/assets?includeDeleted=` | List assets | scope |
| AssetController | GET | `/api/assets/{id}` | Asset detail | scope |
| AssetController | PATCH | `/api/assets/{id}` | Update asset | scope for `displayName`/`attributes` only; `category` (or any managed field) needs manage — see "Authority split" in Conventions |
| AssetController | POST | `/api/assets/{id}/state` | Lifecycle transition (active/deactivated/deleted) | manage |
| AssetController | DELETE | `/api/assets/{id}` | Soft delete (archive) | manage |
| AssetController | POST | `/api/assets/{id}/devices` | Attach a device | manage |
| AssetController | DELETE | `/api/assets/{id}/devices/{deviceId}` | Detach a device | manage |
| AssetController | GET | `/api/usages/{usageId}/telemetry?limit=` | Raw (unwindowed) telemetry trail | **unscoped** (ledger: `AssetController#telemetry`) |
| AssetInventoryController | POST | `/api/assets/{id}/custody` | Issue to a custodian / return to stock (`{action:ISSUE\|RETURN,custodianId?,location?}`) | manage (via `AssetCustodyService`) |
| AssetInventoryController | POST | `/api/assets/{id}/inventory` | Ground / release / retire (`{action:GROUND\|RELEASE\|RETIRE,kind?,summary?}`) | manage (via `AssetCustodyService`) |
| AssetInventoryController | GET | `/api/assets/{id}/maintenance` | List an asset's maintenance history, open and closed | scope (via `MaintenanceService`) |
| AssetInventoryController | POST | `/api/assets/{id}/maintenance` | Open a maintenance record directly, without also grounding | manage (via `MaintenanceService`) |
| AssetInventoryController | POST | `/api/assets/{id}/maintenance/{recordId}/close` | Close an open record | manage (via `MaintenanceService`) |
| InventoryExportController | GET | `/api/inventory/export?format=csv` | Hand-rolled CSV, one row per visible asset (id/name/category/serial/make/model/registration/inventoryState/custodian/location/lifecycle/createdAt/lastFlownAt) | scope |
| AssetStreamController | POST | `/api/assets/{id}/stream` | Start the asset's video stream | scope |
| AssetStreamController | DELETE | `/api/assets/{id}/stream` | Stop it (idempotent) | scope |
| AssetSessionController | POST | `/api/assets/{id}/session` | Operator "engage" — opens/promotes a usage, no video, no device traffic | scope |
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
| CvModelsController | GET | `/api/cv/models` | Static, config-backed detection-model roster | open |
| CvTrackersController | GET | `/api/cv/trackers` | Static tracker-engine roster | open |
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
| ControlProfileController | GET | `/api/control-profiles` | Caller's saved layouts + built-ins | own profile |
| ControlProfileController | GET | `/api/control-profiles/catalog` | Every enumerable setup choice (vehicle kinds, input kinds, functions, …) | own profile |
| ControlProfileController | POST | `/api/control-profiles` | Create (copy of the built-in for that vehicle kind) | own profile |
| ControlProfileController | PUT | `/api/control-profiles/{id}` | Replace the whole layout | own profile |
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
| ModelRegistryController | GET | `/api/cv/registry/models` | The dynamic, gRPC-sourced model registry (distinct from the static `/api/cv/models` picker) | **unscoped** (ledger: `ModelRegistryController#models`) |
| ModelRegistryController | POST | `/api/cv/registry/models/{id}/promote` | Promote a model version | manageOrg |
| TrainingJobController | POST | `/api/datasets/{id}/train` | Start a training job (uploads the dataset to cv-service over gRPC) | manageOrg + dataset scope |
| TrainingJobController | GET | `/api/training/jobs/{jobId}` | Poll one job | **unscoped** (ledger) |
| TrainingJobController | GET | `/api/training/jobs` | List every tracked job | **unscoped** (ledger) |
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
| SimulationController | POST | `/api/simulations` | Start a synthetic (or video-fed) simulated asset | manageOrg |
| SimulationController | DELETE | `/api/simulations/{assetId}` | Stop it (idempotent) | scope |
| AuthController | POST | `/api/auth/login` | Session login (always-200 dev admin when auth disabled) | open |
| AuthController | POST | `/api/auth/logout` | Invalidate session (idempotent) | open |
| AuthController | GET | `/api/auth/me` | Caller's own identity | open (Spring Security's chain itself 401s when auth is enabled and unauthenticated) |
| AssignmentController | PUT | `/api/assets/{assetId}/pilots/{userId}` | Assign a pilot (idempotent) | manage |
| AssignmentController | DELETE | `/api/assets/{assetId}/pilots/{userId}` | Unassign (idempotent) | manage |
| AssignmentController | GET | `/api/assets/{assetId}/pilots` | List an asset's pilots | scope |
| AssignmentController | GET | `/api/me/assignments` | Caller's own assigned assets | self |
| ActivityController | GET | `/api/me/activity?limit=` | Caller's own audit entries | self |
| AuditController | GET | `/api/audit?targetType=&targetId=&limit=` | Fleet-wide audit trail | manageOrg |
| UserAdminController | GET | `/api/users` | List visible users | scope |
| UserAdminController | POST | `/api/users` | Create/invite a user | manageOrg (via `UserService`) |
| UserAdminController | POST | `/api/users/{id}/enabled` | Enable/disable a user | manageOrg |
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
`com.drones.vision.kernel.UsageOrigin`'s own javadoc). `ErrorResponse(error, message)` is the
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
- Rationale for any of the above beyond what's stated here lives in the plan doc cited inline, under
  `docs/plans/`.

## Gotchas

- **`AssetController#telemetry`, `TrainingJobController#job`/`#jobs`, `ModelRegistryController#models`,
  `GeoRegionController#list`/`#progress`, `DiscoveryController#scan`, `SystemNetworkController#network`,
  `SystemStatusController#status`, `DemoController#status`, `OnboardingController#probeCandidate`, and
  `UsageTimelineController#timeline`/`#recording` are genuinely unauthorized today** — no `403`/`404`
  from a caller who shouldn't see them, just a plain `200`. This is the `TEMPORARY_UNSCOPED` ledger
  (`EndpointAuthorizationTest`, vision-app), not an oversight in this doc.
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
  original, unwindowed `.../telemetry` (unscoped, see ledger above); `UsageTimelineController` owns
  the windowed/downsampled `.../timeline`. Neither depends on the other's presence.
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

## Status

Feature flags gating whole controllers/packages off by default: `vision.training.enabled` (false —
`DatasetController`/`LabelingController`/`ModelRegistryController`/`TrainingJobController` all 404
like unmapped routes when off), `vision.onboarding.probe.enabled` (false — `POST /api/onboarding/probe`
answers 409, and so does `AssetParameterController#writeParameter` — see FLEET-RADIO R5 note below),
`vision.geo.fixed-camera.enabled` (false — `CameraPoseController`/`MapTracksController`
answer 409), `vision.api.rate-limit.enabled` (false, see "Rate limiting" above), `vision.auth.enabled`
(false — every `CurrentUser` call resolves a fixed unbounded dev principal). On by default:
`vision.live.enabled`, `vision.demo.enabled`.

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
