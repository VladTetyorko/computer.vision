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

## Section index

| Area | Where |
|---|---|
| Package layout (who owns what) | API surface → Package layout |
| Authorization tags (`scope`/`manage`/`manageOrg`/`administer`/`open`/`unscoped`/…) | API surface → Authorization tags |
| Full endpoint table (every controller, method, path, DTO, access tag) | API surface → Endpoints |
| `/ws/manual-control` (engage/mayFly/seat/denial-code protocol) | API surface → `/ws/manual-control` |
| Exception → HTTP status/code mapping | API surface → Error mapping |
| SSE topics, payload shapes, scoping, buffering | API surface → Live updates |
| Rate limiting | API surface → Rate limiting |
| DTO shapes not fully spelled out in the endpoint table (assets/inventory/discovery/seats/CV/tracks) | API surface → DTO conventions |
| Authorization, DI, validation, testing idioms | Conventions |
| Hard-won quirks and deliberate deviations | Gotchas |
| Feature flags, what's real/stubbed, known limitations | Status |

## API surface

### Package layout

`controller/` (every `@RestController`, including `AssetInventoryController`/
`InventoryExportController`, `CvProfileController`, `DiscoveryInboxController`,
`DiscoveryStatusController`, `SeatController`) · `dto/` (wire records only, ~190 — house rule "zero
DTO leakage": no domain type is ever serialized directly) · `security/` (`CurrentUser`/
`PrincipalResolver`/`StreamAccess`/`OpenByDesign`/`AssetAuthority`/`CapabilityAssetAuthority`/
`SeatAccess`/`SeatAccessSettings` — the authorization seam, see Conventions) · `live/` (SSE
connection registry, per-topic ring buffers, per-connection visibility filtering,
`SystemStatusSampler` — the server-side change-detecting sampler that owns the `system` topic's
schedule, `LiveAndPollTraceDemand implements TraceDemandPort` — the SSE half of trace demand, see
"Live updates" below) · `ws/` (`/ws/manual-control` raw `WebSocketHandler`) · `proxy/`
(`HlsProxyController` — a pass-through edge owning no application service) · `ratelimit/`
(`RateLimitFilter`/`TokenBucket`, per-principal `/api/**` token bucket) · `support/` (edge-local
helpers: `SnapshotJpegEncoder`, `CapabilityParsing`, `DeviceOriginParsing`, `RemediationOrchestrator`,
`VisionApiProperties`, `SystemStatusReader` — `safeStatus`/`overall`/`worstHealth` extracted from
`SystemStatusController` so `SystemStatusSampler` can reuse the exact same rollup logic —
`DiscoveryStatusFacts` — the plain (non-DTO) crossing-seam payload behind `GET /api/discovery/status`,
`InventoryExportService` — the hand-rolled CSV behind `GET /api/inventory/export`, `AssetRowFacts` —
WAREHOUSE-UX W8 + INVENTORY-REWORK W1, bundles the `firmware`/`totalFlightSeconds`/
`custody.custodianName` cross-context joins `AssetController` needs, see Conventions, `SeatSupport` —
bundles `SeatAccess`'s device/stream→asset resolution, asset ownership,
display-name lookup, and `FORCE`/`DENIED:SEAT_HELD` audit writes, see Conventions) · `demo/`
(property-gated demo-data seeding, deletable as one unit) · `exception/` (`ApiExceptionHandler` +
api-local exceptions) · `config/` (MVC/WebSocket/SPA `@Configuration`).

### Authorization tags used in the table below

Every handler is either scoped, carries `@OpenByDesign`, or is named in the `TEMPORARY_UNSCOPED`
ledger (`EndpointAuthorizationTest`, vision-app) — there is no fourth state. See Conventions for
the full mechanism.

| Tag | Means |
|---|---|
| `scope` | `CurrentUser#scope()`, a `VisibilityScope`. Single-resource read/write → **404** if out of scope (existence hidden). List read → silently filtered, never 403. |
| `manage` | `authority.mayManageFleet(ownership)` (`CurrentUser#authority()`) — visible but not manageable → **403**. |
| `manageOrg` | `authority.mayManageOrg()` (ADMIN or MANAGER). A `VIEWER` also resolves a `GROUPS`-shaped scope (contexts/vision-identity) but still fails this gate — `mayManageOrg()` additionally requires `Capability.MANAGE_ORG`, which `RoleAuthority` never grants `VIEWER`) → **403** otherwise. |
| `administer` | `authority.mayAdminister()` (deployment-global, no group boundary) → **403** otherwise. |
| `self` | Filtered to the caller's own id; takes no target-user/asset parameter, so there is nothing to authorize against. |
| `viewer:view`/`:contribute`/`:manage` | `MapAccessPolicy` gates via `CurrentUser#viewer()` — a model kept deliberately separate from `VisibilityScope` (see `contexts/vision-map/MODULE.md`). |
| `own profile` | Gated by profile ownership inside the application service, not `VisibilityScope` — see `ControlProfileController` note in Gotchas. |
| `open` | `@OpenByDesign` — reachable by any authenticated caller by design. |
| `unscoped` | **No check at all.** Named in `TEMPORARY_UNSCOPED` — a real, currently-open gap, not a decision. |

### Endpoints

| Controller | Method | Path | Does | Access |
|---|---|---|---|---|
| AssetController | POST | `/api/assets` | Create asset | manageOrg |
| AssetController | GET | `/api/assets?includeDeleted=` | List assets — each row now carries `firmware`/`totalFlightSeconds` (see Conventions) | scope |
| AssetController | GET | `/api/assets/{id}` | Asset detail — same `firmware`/`totalFlightSeconds` join as the list | scope |
| AssetController | PATCH | `/api/assets/{id}` | Update asset | scope for `displayName`/`attributes` only; `category` (or any managed field) needs manage — see "Authority split" in Conventions |
| AssetController | POST | `/api/assets/{id}/state` | Lifecycle transition (active/deactivated/deleted) | manage |
| AssetController | DELETE | `/api/assets/{id}` | Soft delete (archive) | manage |
| AssetController | POST | `/api/assets/{id}/devices` | Attach a device | manage |
| AssetController | DELETE | `/api/assets/{id}/devices/{deviceId}` | Detach a device | manage |
| AssetController | GET | `/api/usages/{usageId}/telemetry?limit=` | Raw (unwindowed) telemetry trail — the **latest** `limit` samples, ascending (reads `TelemetryRepositoryPort#findLatestByUsage`, not `#findByUsage` — the `/command` fleet map polls this treating the last element as "current position") | **unscoped** (ledger: `AssetController#telemetry`) |
| AssetInventoryController | POST | `/api/assets/{id}/custody` | Issue to a custodian / return to stock (`{action:ISSUE\|RETURN,custodianId?,location?}`). **INVENTORY-REWORK W1: ISSUE now also grants the custodian the `PILOT` seat** (idempotent — an existing `PILOT` or `CREW` seat is left exactly as it is); RETURN clears custody and **leaves the assignment in place**. Routed through `HandoverService`, see Conventions | manage (via `HandoverService` → `AssetCustodyService` + `AssignmentService`) |
| AssetInventoryController | POST | `/api/assets/{id}/inventory` | Ground / release / retire (`{action:GROUND\|RELEASE\|RETIRE,kind?,summary?}`) | manage (via `AssetCustodyService`) |
| AssetInventoryController | GET | `/api/assets/{id}/maintenance` | List an asset's maintenance history, open and closed | scope (via `MaintenanceService`) |
| AssetInventoryController | POST | `/api/assets/{id}/maintenance` | Open a maintenance record directly, without also grounding | manage (via `MaintenanceService`) |
| AssetInventoryController | POST | `/api/assets/{id}/maintenance/{recordId}/close` | Close an open record | manage (via `MaintenanceService`) |
| AssetInventoryController | GET | `/api/maintenance?state=open\|closed\|all&limit=` | Fleet-wide maintenance read across every in-scope asset, each row carrying `assetId`/`assetName`/`categoryId` | scope (via `MaintenanceService#fleetWide`) |
| InventoryExportController | GET | `/api/inventory/export?format=csv` | Hand-rolled CSV, one row per visible asset (id/name/category/serial/make/model/registration/inventoryState/custodian/location/lifecycle/createdAt/lastFlownAt) | scope |
| AssetStreamController | POST | `/api/assets/{id}/stream` | Start the asset's video stream | scope |
| AssetStreamController | DELETE | `/api/assets/{id}/stream` | Stop it (idempotent) | scope |
| AssetSessionController | POST | `/api/assets/{id}/session` | Operator "engage" — opens/promotes a usage, no video, no device traffic; the response's `pilotId` is `CurrentUser#userId()`, passed straight through to `UsageTracker#engage` — the caller's own identity, never a request body field | scope |
| AssetSessionController | DELETE | `/api/assets/{id}/session` | Operator "disengage" (idempotent; demotes rather than closes if a stream is still running); records an `AuditTrailPort` entry (`AuditAction.UPDATED`/`AuditTargetType.ASSET`) naming the calling `CurrentUser#userId()` when something was actually engaged — no entry when nothing was (attribution only, no seat/arbitration logic — that's `SeatAccess`'s job, below) | scope |
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
| StreamController | GET | `/api/streams/{streamId}/detections?limit=` | Recent per-frame detections, each carrying `objects` (`List<ObjectStateResponse>`, the per-identity mirror, never omitted, empty when no mirror was produced) — deliberately still the flat mirror, not `WorldObjectResponse`: the operator/event/render relations are a platform concern this durable/detections path must never carry, see `WorldObjectResponse` in DTO conventions | scope |
| StreamController | GET | `/api/streams/{streamId}/snapshot` | Latest frame as downscaled JPEG (only binary, non-JSON response besides the HLS proxy) | scope |
| StreamController | PATCH | `/api/streams/{streamId}/config` | Hot-patch confidence/fps/labelFilter/model/tracking/`intent` — never interrupts video; response reports per-knob `sources` (`CvProfileResponse.Sources`, see DTO conventions); `intent:"CUSTOM"` with no `labelFilter` is `400 BAD_REQUEST` | scope |
| StreamController | GET | `/api/streams/{streamId}/tracks` | Track book + duty-cycle stats + the held `FOLLOW` lock's own lifecycle (`follow`, omitted until a lock is issued) + `objects` (`List<WorldObjectResponse>`, `{state, operator, event, render}`, always a JSON array, never omitted); never errors on unknown stream (empty `tracks`/`objects`) — the handler itself is a 3-line collapse (`streamAccess.requireVisible` → `streamService.tracksSnapshot(id).orElse(TracksSnapshot.empty(id))` → `StreamTracksResponse.from(snapshot, Instant.now())`); every gating rule (stats/latency/rate/detectionState omission, `lockedTrackId` hoisting from `follow`) lives in `StreamTracksResponse.from` itself, not here — the same assembly `LiveUpdateRegistry`'s `tracks:<assetId>` SSE push also calls ("one assembly, two transports"), so this poll is a fallback, not the only way to learn these fields live | scope |
| StreamController | GET | `/api/streams/{streamId}/cv/trace?last=N` | The warm trace tier's three ledgers side by side (`CvTraceResponse{streamId, gate, frame, world}`, see DTO conventions for each nested shape); `last` defaults to `DEFAULT_TRACE_LAST=50` when absent. **This read is itself trace demand** — polling this endpoint counts toward `TraceDemandPort#traceWanted` exactly like an open `cv-trace:<assetId>` SSE subscription. Never errors — unknown/stopped stream reads every list empty | scope |
| HlsProxyController | GET | `/hls/{streamId}/**` | Reverse-proxy this asset's live HLS bytes to the mediamtx sidecar | scope (`StreamAccess#requireVisibleForHlsProxy`, checked **before** the upstream is ever contacted; fails closed on an unknown/stopped id — unlike every other `StreamAccess` caller in this module, which keeps `requireVisible`'s no-op) — also behind `SecurityConfig`'s secured chain's `authenticated()` rule (`/hls/**` joined `/api/**`/`/ws/**`) |
| CvModelsController | GET | `/api/cv/models` | Detection-model roster — widened (`docs/plans/active/CV-SETTINGS-PLAN.md` §5.2) to serve the registry's live roster (`registrySource: true`) when `vision.cv.registry.enabled`, else the static config catalogue; never errors | open |
| CvTrackersController | GET | `/api/cv/trackers` | Static tracker-engine roster | open |
| OpsThresholdsController | GET | `/api/ops/thresholds` | Battery urgency thresholds + RC neutral-stick tolerance — `{"battery":{"warningPercent":25,"criticalPercent":10},"rc":{"neutralTolerancePercent":5}}`, frozen wire shape, values from `vision.ops.battery.*`/`vision.ops.rc.*` | open |
| CvProfileController | GET | `/api/cv/profiles` | List profiles the caller may see (every built-in + the caller's own group's) | scope |
| CvProfileController | GET | `/api/cv/profiles/{id}` | Read one profile | scope |
| CvProfileController | POST | `/api/cv/profiles` | Create a profile owned by the caller's own group (201) | manageOrg |
| CvProfileController | PUT | `/api/cv/profiles/{id}` | Replace a non-built-in profile wholesale | manageOrg |
| CvProfileController | DELETE | `/api/cv/profiles/{id}` | Delete a non-built-in, unbound profile (204) | manageOrg |
| CvProfileController | PUT | `/api/cv/bindings` | Bind a profile to a scope (`ASSET`/`CATEGORY`/`ORGANIZATION`), replacing any prior binding at that exact scope | manageOrg |
| CvProfileController | DELETE | `/api/cv/bindings` | Clear a scope's binding, idempotent (204) — body-carrying DELETE, the binding's natural key has no single path id | manageOrg |
| CvProfileController | GET | `/api/cv/profiles/effective?assetId=` | The one profile `assetId` would start with right now, plus per-knob provenance (`CvKnobSourcesResponse`, 8 fields, one per bound tier's knob) and which `intent` (if any) the matched tier persisted — see DTO conventions | scope |
| CvProfileController | GET | `/api/cv/coverage` | Fleet-wide "what CV will do on each asset" table, one row per visible asset | scope |
| EventController | GET | `/api/events?sinceMs&limit` | Cross-stream debounced detection events, newest first | scope (filtered) |
| EventController | GET | `/api/streams/{streamId}/events?limit` | One stream's events | scope |
| FleetController | GET | `/api/fleet/summary?includeArchived=` | Per-category counts + attention list | scope |
| ReadinessController | GET | `/api/assets/{assetId}/readiness` | One asset's onboarding-readiness report | scope |
| ReadinessController | GET | `/api/fleet/readiness` | Readiness row per visible asset | scope |
| FlightCommandController | POST | `/api/assets/{id}/return-home` | RTL | scope + `AssetAuthority#mayFly` |
| FlightCommandController | POST | `/api/assets/{id}/mode` | Flight-mode change | scope + `mayFly` |
| FlightCommandController | POST | `/api/assets/{id}/arm` | Arm (optional `force`) | scope + `mayFly` |
| FlightCommandController | POST | `/api/assets/{id}/disarm` | Disarm (optional `force`) | scope + `mayFly` |
| FlightCommandController | POST | `/api/assets/{id}/emergency-stop` | Forced disarm, kept separate from `disarm{force}` for audit-trail clarity | scope + `mayFly` |
| FlightCommandController | POST | `/api/assets/{id}/aux-function` | `MAV_CMD_DO_AUX_FUNCTION` | scope + `mayFly` |
| FlightCommandController | GET | `/api/assets/{id}/flight-capabilities` | What this asset supports commanding | scope |
| SeatController | GET | `/api/assets/{id}/seats` | Both seats' current holder/expiry + the caller's own `mayTakeFlight`/`mayTakeCamera`/`mayForceSeat` | scope |
| SeatController | POST | `/api/assets/{id}/seats/{kind}` | Take-or-renew (`kind` = `flight`\|`camera`); optional `{"force":true}` body, honoured only for a caller whose `mayForceSeat` is true and only against a *different* current holder — otherwise silently ignored, not rejected | scope + `SeatAccess` (409 if held by another and not forced, 403 if the caller has no standing for that seat) |
| SeatController | DELETE | `/api/assets/{id}/seats/{kind}` | Release (idempotent for a free seat or the caller's own); a manager may evict another holder (204), firing the same RC-release hook a forced `POST` does | scope + `SeatAccess` (403 if held by another and the caller may not force) |
| ControlProfileController | GET | `/api/control-profiles` | Caller's saved layouts + built-ins; every row carries `stickMode`/`forwardIsUp`, a built-in reporting `TransmitterView.DEFAULT` | own profile |
| ControlProfileController | GET | `/api/control-profiles/catalog` | Every enumerable setup choice (vehicle kinds, input kinds, functions, …) | own profile |
| ControlProfileController | POST | `/api/control-profiles` | Create (copy of the built-in for that vehicle kind) | own profile |
| ControlProfileController | PUT | `/api/control-profiles/{id}` | Replace the whole layout; body may also carry `stickMode?`/`forwardIsUp?` (both optional in both directions — an older client sends neither and gets the platform default; a `stickMode` outside 1-4 is a 400) | own profile |
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
| TrainingJobController | GET | `/api/cv/training/runs?limit=` | Most recently started **persisted** training runs, newest-first (default limit 50); `datasetName` resolved via a `DatasetRepositoryPort` injected directly (read-only driven port, see Conventions) | manageOrg |
| TrainingJobController | GET | `/api/cv/training/runs/{runId}` | One persisted run's latest state | manageOrg |
| OnboardingController | POST | `/api/onboarding/probe` | Pre-registration vehicle probe | **unscoped** (ledger — nothing yet exists to scope against) |
| OnboardingController | GET | `/api/assets/{assetId}/profile` | Latest vehicle profile | scope |
| OnboardingController | POST | `/api/assets/{assetId}/probe` | Probe a registered asset's link | manage (audited) |
| OnboardingController | POST | `/api/assets/{assetId}/remediate` | Dispatch remediation actions | manage (audited) |
| OnboardingController | GET | `/api/assets/{assetId}/usages/{usageId}/passport` | Flight passport | scope |
| OnboardingController | GET | `/api/assets/{assetId}/usages/{usageId}/drift` | Parameter drift vs. previous flight | scope |
| AssetParameterController | POST | `/api/assets/{id}/parameters` | Write one Tier-A/B vehicle parameter, explicit value + consent required for **every** write regardless of tier (see Gotchas) | manage (Tier A)/administer (Tier B), audited |
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
| AssetLinksController | GET | `/api/assets/{id}/links` | One asset's paired-link election snapshot (LINK-PAIRING-PLAN.md §3.4) | in-scope (404 out-of-scope/unknown, via `requireInScope`) |
| AssetLinksController | PUT | `/api/assets/{id}/links/{linkId}/pin` | Operator override: pin the ACTIVE link, empty `{}` body | in-scope |
| AssetLinksController | DELETE | `/api/assets/{id}/links/pin` | Release an operator pin, hand back to automatic election; idempotent; returns `LinkGroupResponse` (200, not 204 — the caller reads the resulting snapshot) | in-scope |
| CarriersController | GET | `/api/carriers` | Every carrier registered on the station, station-wide reference data (§3.4/§7 ruling 5) | `@OpenByDesign` — no `AssetId` to filter by |
| DiscoveryController | POST | `/api/discovery/scan` | ONVIF/mDNS/V4L2 device scan | **unscoped** (ledger) |
| DiscoveryInboxController | GET | `/api/discovery/inbox` | `{candidates, sources}` envelope — every reported discovery candidate (newest-reported first) plus one health row per discovery mechanism | manageOrg |
| DiscoveryInboxController | POST | `/api/discovery/inbox/{id}/register` | Register a candidate as a new asset | manageOrg (checked inside `DiscoveryInboxService#register`, ownership from `CurrentUser`, never the body — see Conventions) |
| DiscoveryInboxController | POST | `/api/discovery/inbox/{id}/dismiss` | Dismiss a candidate (idempotent-in-effect: dismissing an already-dismissed candidate just re-stamps status) | manageOrg |
| DiscoveryInboxController | POST | `/api/discovery/inbox/{id}/attach` | Atomically attach a candidate's suggested stream to an **existing** asset (`{"assetId":"..."}`) — 404 unknown candidate/out-of-scope asset, 409 no suggested stream or a duplicate stream, 422 candidate already `REGISTERED` to a different asset | manageOrg |
| DiscoveryInboxController | POST | `/api/discovery/inbox/{id}/restore` | Undo a dismiss — status back to `NEW`, `registeredAssetId` cleared | manageOrg |
| DiscoveryStatusController | GET | `/api/discovery/status` | Sweep cadence + telemetry/video intake facts + per-source health, for a live onboarding status panel | manageOrg |
| PairingController | POST | `/api/devices/{id}/pairing` | Pair a manually registered device — heard sysid/hardware uid in the body (both optional); response carries `sysidPushRequired` when the assigned sysid differs from what was heard (LINK-PAIRING-PLAN.md §3.3) | device-keyed (`StreamAccess#requireVisible`) |
| PairingController | DELETE | `/api/devices/{id}/pairing` | Forget a pairing — ⚠ hard delete, its sysid/key stop being valid immediately | device-keyed |
| PairingController | POST | `/api/devices/{id}/pairing/replace-hardware` | Record a hardware swap — clears `hardwareUid`, bumps `replacedAt`, keeps sysid/key | device-keyed |
| PairingController | GET | `/api/pairings/unpaired-devices` | Devices that have never been paired — the picklist a manual pair starts from | manageOrg |
| SimulationController | POST | `/api/simulations` | Start a synthetic (or video-fed) simulated asset — controller bean absent (404) unless `vision.simulation.enabled=true` | manageOrg |
| SimulationController | DELETE | `/api/simulations/{assetId}` | Stop it (idempotent) — same gate as above | scope |
| AuthController | POST | `/api/auth/login` | Session login (always-200 dev admin when auth disabled); body `{username,password,kiosk?}` — a non-`VIEWER` requesting `kiosk:true` is refused `400 KIOSK_NOT_PERMITTED` *after* a real successful login, and the just-established session is torn down | open |
| AuthController | POST | `/api/auth/logout` | Invalidate session (idempotent) | open |
| AuthController | GET | `/api/auth/me` | Caller's own identity; `MeResponse` carries `capabilities[]`/`scopeKind`/`mustChangePassword` | open (Spring Security's chain itself 401s when auth is enabled and unauthenticated) |
| BootstrapController | GET | `/api/auth/bootstrap` | `{"required": true\|false}` — `true` iff `vision.auth.enabled` and no enabled user holds an `ADMIN` membership | open, anonymous, `@OpenByDesign`, `permitAll` |
| BootstrapController | POST | `/api/auth/bootstrap` | Body `{username,displayName,email,password}` → `201 MeResponse`, session established. Refused once any admin exists (`409 ALREADY_INITIALIZED`) or the password fails policy (`400 WEAK_PASSWORD`) | open only while `required`, anonymous, `@OpenByDesign` |
| AuthPasswordController | POST | `/api/auth/password` | Self-service password change, `{currentPassword,newPassword}` → `204`, clears `mustChangePassword`; `401` wrong current, `400 WEAK_PASSWORD`, `409 AUTH_DISABLED` when `vision.auth.enabled=false` | self |
| AssignmentController | PUT | `/api/assets/{assetId}/pilots/{userId}` | Assign a pilot (idempotent); body `{"role"?: "PILOT"\|"CREW"}` — absent body/field defaults to `PILOT` | manage |
| AssignmentController | DELETE | `/api/assets/{assetId}/pilots/{userId}` | Unassign (idempotent) | manage |
| AssignmentController | GET | `/api/assets/{assetId}/pilots` | List an asset's pilots; `PilotResponse` carries `role` and, **INVENTORY-REWORK W1**, `username`/`displayName` resolved server-side by id (`AuthService#find`) — both omitted if the user record no longer resolves | scope |
| AssignmentController | GET | `/api/me/assignments` | Caller's own assigned assets; `AssignmentResponse` carries `role` (defaults to `PILOT` if `roleFor` finds no link — see Gotchas) | self |
| ActivityController | GET | `/api/me/activity?limit=` | Caller's own audit entries | self |
| AuditController | GET | `/api/audit?targetType=&targetId=&limit=` | Fleet-wide audit trail | manageOrg |
| UserAdminController | GET | `/api/users` | List visible users | scope |
| UserAdminController | POST | `/api/users` | Create/invite a user | manageOrg (via `UserService`) |
| UserAdminController | POST | `/api/users/{id}/enabled` | Enable/disable a user | manageOrg |
| UserAdminController | POST | `/api/users/{userId}/password` | Admin-resets a user's password, `{"newPassword"}` → `204`, sets `mustChangePassword=true`; `400 WEAK_PASSWORD` | manageOrg |
| UserAdminController | PUT | `/api/users/{userId}/memberships` | Wholesale-replaces a user's group memberships, `{"memberships":[{"groupId","role"}]}` → `200 UserResponse` | manageOrg |
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
| SystemStatusController | GET | `/api/system/status` | Subsystem health rollup; never errors — rollup logic lives in `support/SystemStatusReader#read`, shared with the `system` SSE topic's server-side sampler (see "Live updates" below) | **unscoped** (ledger — deliberately: no secrets exposed) |
| SystemNetworkController | GET | `/api/system/network` | Host's site-local IPv4 addresses (each carrying a `kind` — `LAN`/`VIRTUAL`/`UNKNOWN`, sorted kind-first) plus `mavlinkPort`, `simulationEnabled` (mirrors `vision.simulation.enabled`, the flag the web reads to decide whether to offer the Playground) and, when mediamtx publish is configured, `videoPushPort`/`videoPushPathPrefix` | **unscoped** (ledger) |
| SystemEventsController | GET | `/api/system/events?sinceMs&limit` | Durable platform-`Event` history, newest-first — the notification bell/`/manage/system`'s reconnect backfill; empty unless `vision.events.history.enabled` | `@OpenByDesign` (durably replays exactly what the already-unscoped `event` SSE topic broadcasts) |
| DemoController | GET | `/api/demo` | Demo-button availability probe | **unscoped** (ledger); gated by `vision.demo.enabled` (default on) |
| DemoController | POST | `/api/demo/seed` | Seed demo assets/users/streams/zones/marks; fault-tolerant (failures land in `problems`, never an error status) | scope (`DemoScenario` resolves the acting user itself) |

### `/ws/manual-control`

`/ws/manual-control` (WebSocket, `ws/ManualControlWebSocketHandler`) is the streaming RC-control
transport, outside the table above since it isn't a `@RestController` route. Its handshake resolves
`CurrentUser` the same way every REST call does (`ManualControlHandshakeInterceptor`, 401 if it
can't) and every `engage` frame re-derives scope from that handshake — see the class javadoc for the
full frame protocol.

**The per-asset `mayFly` gate on `engage`.** `ManualControlHandshakeInterceptor` stashes `Authority`
(`ATTR_AUTHORITY`) into the session's attribute map at handshake time, alongside `userId`/`scope` —
the one point where the HTTP thread's `SecurityContext` is actually valid; a WebSocket message frame
(`engage` included) is dispatched later on the container's own message thread, which carries none.
`handleEngage` checks `CapabilityAssetAuthority#mayFly(Authority, UserId, AssetId)` — a second,
explicit-actor public method on the concrete class, not part of the frozen 3-method `AssetAuthority`
interface (that interface's own `mayFly(AssetId)` overload reads the ambient `CurrentUser`, which
would throw once auth is enabled) — exactly once, at engage, denying `OUT_OF_SCOPE` before
`ManualControlService#engage` is ever called; a revocation mid-session is never re-checked (the
mid-flight rule). The handler's constructor depends on the *concrete* `CapabilityAssetAuthority`
class, not the `AssetAuthority` interface, specifically to reach this explicit-actor overload.

**`FlightCommandController`'s six REST commands use the same `mayFly` gate, differently.** Unlike the
WebSocket handler, this controller *can* reach the ambient `CurrentUser`, so it injects the plain
`AssetAuthority` interface and calls the interface's `mayFly(AssetId)` overload. A private
`requireMayFly(AssetId)` throws `AccessDeniedException` (→403) before any of
`returnHome`/`setMode`/`arm`/`disarm`/`emergencyStop`/`auxFunction` ever calls
`FlightCommandService` — deliberately redundant with that service's own pre-existing
`scope().includes(...)` check in `contexts/vision-flight` (out of this module's reach): the edge gate
is strictly narrower (adds the `COMMAND_FLIGHT` capability and, for an `ASSIGNED_ASSETS` caller, the
`PILOT`-not-`CREW` seat narrowing), so the service's own check never actually fires once this one has
denied — it stays as the scope-only backstop for any future caller that reaches the service directly.
`GET /api/assets/{id}/flight-capabilities` (a read) keeps its existing 404-on-out-of-scope
convention, not this 403 gate.

**The flight-seat gate on `engage` (docs/plans/active/CREW-CONTROL-PLAN.md §3.3).** The handler's
constructor carries `(ManualControlService, CapabilityAssetAuthority, SeatAccess,
watchdogTimeoutMillis, engageSlowThresholdMillis)`. `handleEngage` calls
`SeatAccess#requireFlightSeat(UserId, AssetId)` — the explicit-actor overload, since the message
thread carries no ambient `CurrentUser` — immediately after the `mayFly` check and before
`ManualControlService#engage` is ever called; on `IllegalStateException` (seat held by another) the
handler answers a `denied` frame with `code:"SEAT_HELD"` rather than letting the exception propagate
(there is no HTTP layer here to map it), mirroring the REST controllers' 409 with a WS-native shape.
Disabled by default (`vision.crew.enabled=false`) via `SeatAccess`'s own settings-driven no-op, not a
second code path in this handler.

**The seat gate on five REST controllers, layered after `mayFly`/`mayOperateCamera`.** `SeatAccess`
(`security/SeatAccess.java`) is the one collaborator every guard calls — constructed from
`SeatService` (contexts/vision-flight), `AssetAuthority`, `CurrentUser`, `SeatSupport`, and
`SeatAccessSettings` (5 params, at the constructor ceiling, documented in its own javadoc).
`requireFlightSeat(AssetId)`/`requireCameraSeat(AssetId|DeviceId|StreamId)` are pass-through no-ops
when `vision.crew.enabled=false` (default config is byte-identical to pre-seat-gate behaviour) and
otherwise take-or-renew the caller's own seat, throwing `IllegalStateException` (→409, "Asset
`<uuid>` `<kind>` seat is held by `<displayName>`") only when a *different* user holds it — a
flight-seat holder never conflicts on the camera seat and instead silently preempts any prior camera
holder. Call sites: `FlightCommandController#returnHome/setMode/arm/disarm/emergencyStop/auxFunction`
each call `seatAccess.requireFlightSeat(assetId)` immediately after the `mayFly` check;
`AssetStreamController#startStream/stopStream` and `StreamController#start/stop/updateConfig`-family
call `seatAccess.requireCameraSeat(...)`; `AssetSessionController#engage/disengage` call
`seatAccess.requireFlightSeat(assetId)`. `AssetStreamController`'s constructor carries 6 args,
`StreamController`'s 7 (both past the 5-arg ceiling, documented in their own javadoc — `StreamController`
was already at 6 for `StreamAccess` before the seat gate, so 7 continues an existing precedent).
`SeatController` (`controller/SeatController.java`) is a separate controller exposing
`GET/POST/DELETE /api/assets/{id}/seats[/{kind}]` directly over `SeatAccess`'s
`seats`/`takeSeat`/`releaseSeat` — see the Endpoints table and "DTO conventions" below.

**Denial codes.** `ManualControlDeniedFrame.code` is a plain `String`, not a closed enum. Beyond the
three originally-documented causes (`AccessDeniedException`, `VehicleUnidentifiedException` →
`VEHICLE_UNIDENTIFIED`, `IllegalStateException` including `SEAT_HELD`), `handleEngage` has a fourth,
final `catch (RuntimeException e)` — it logs WARNING with the full stack trace and answers `denied`
with the additive code `INTERNAL_ERROR`, a generic operator-facing sentence plus the exception's
simple class name **only** (never its message, which could leak internals). This guarantees
`handleEngage` never returns without a reply on an open socket; the `engaged` response DTO is built
*before* `state.session` is assigned, so a failure composing it cannot leave local state claiming a
session the client was told was denied. Every `engage` attempt's wall time is measured and logged —
WARNING once it reaches `vision.rc.engage-slow-threshold-ms` (default 2000, `@Value`, vision-app's
`application.yaml` documents the same default), DEBUG otherwise. `engage()`'s own path
(`DefaultManualControlService` → `MavlinkManualControlSender` → `mavlink-core`'s
`ManualControlService`) is local and ack-less by design — there is no vehicle round-trip to be slow,
so a firing client-side timeout is always a station fault (see MODULE-HISTORY.md's H1 entry for the
trace that established this).

### Error mapping (`ApiExceptionHandler`, body `{"error","message"}`)

| Exception | Status | code |
|---|---|---|
| `IllegalArgumentException`, `UnsupportedProtocolException` | 400 | `BAD_REQUEST` |
| `NoSuchElementException` | 404 | `NOT_FOUND` |
| `AccessDeniedException` (platform) | 403 | `FORBIDDEN` — a scoped **command** against something the caller cannot see; never used for a scoped *read*, which 404s instead (see Conventions) |
| `IllegalStateException` | 409 | `CONFLICT` |
| `HlsUpstreamUnavailableException` | 502 | `BAD_GATEWAY` — upstream unreachable; a normal non-2xx *received* from upstream passes through verbatim instead |
| `ProbeFailedException`, `DiscoveryCandidateAlreadyRegisteredException` | 422 | `UNPROCESSABLE_ENTITY` — the latter shares this status for `POST /api/discovery/inbox/{id}/attach` against a candidate already `REGISTERED` to a different asset |
| `PayloadTooLargeException` | 413 | `PAYLOAD_TOO_LARGE` |
| `GeoServiceUnavailableException` | 503 | `SERVICE_UNAVAILABLE` — request was well-formed/authorized, the collaborator it proxies to (cv-service) is down |

Per-endpoint business-rule codes that don't fit this cross-cutting table (`KIOSK_NOT_PERMITTED`,
`WEAK_PASSWORD`, `ALREADY_INITIALIZED`, `AUTH_DISABLED`, the discovery-inbox 404/409/422 trio, `intent`
validation 400s, …) are documented inline in the endpoint table's "Does"/"Access" columns instead —
each has exactly one caller, so a second cross-cutting table would only add indirection.

### Live updates (`com.drones.vision.api.live`)

One `LiveUpdateRegistry` implements all eight per-context live-update ports (`FleetLiveUpdatePort`,
`TelemetryLiveUpdatePort`, `DetectionLiveUpdatePort`, `MapLiveUpdatePort`, `EventLiveUpdatePort`,
`TrackCorrectionLiveUpdatePort`, `GeofenceLiveUpdatePort`, `LinkStateLiveUpdatePort` —
`contexts/vision-flight`'s ports) and owns every SSE connection, process-local/single-instance only.
Topics: `fleet`, `event`, `devices`, `detection-events`, `discovery`, `zones` (all always-on, no auth
needed beyond the connection itself — see below for `discovery`'s own delta-only semantics), plus a
tenth always-on topic `system` that carries no per-context port at all (see below), `map` and
per-asset `telemetry:<id>`/`detections:<id>`/`geo:<id>`/`tracks:<id>`/`cv-trace:<id>`/`links:<id>`
(individually authorized — see below; `tracks`/`cv-trace` and `links` are each documented in their
own paragraph further down).
Delivery is coalesced (leading+trailing, ~150ms default) per topic, not per connection, so exactly one
resumable `seq` exists per topic; `Last-Event-ID` resumes from a per-topic ring buffer (FIFO or
latest-only depending on topic). Tunables live in `VisionApiProperties.Live` (coalesce/heartbeat/buffer
sizes/send-timeout/buffer-eviction/`systemSample`), bound from `vision.api.live.*`.

**`zones` — geofence create/update/delete, never riding `map`.** Zones deliberately do not ride the
pre-existing `map` topic: no `map -> flight` architecture edge exists (`ContextArchitectureTest`,
vision-app), and zones are `contexts/vision-flight`'s own concept, not `vision-map`'s. Follows the
one-port-per-context idiom exactly like `MapLiveUpdatePort`/`MapEvent`: `contexts/vision-flight`
carries `GeofenceZoneEvent` (`Action{CREATED,UPDATED,DELETED}` + `GeofenceZone`) and
`GeofenceLiveUpdatePort` (`publishZoneEvent(GeofenceZoneEvent)`), and `DefaultGeofenceService`
publishes on every create/update/delete, immediately after `GeofenceMonitor#refresh()`. The envelope
is `GeofenceZoneEventPayload{action, zone}` — `action` one of `"CREATED"`/`"UPDATED"`/`"DELETED"`,
`zone` the same `GeofenceZoneResponse` shape `GET /api/geofences` already returns. **`DELETED` carries
the last-known zone in full** — `DefaultGeofenceService#delete` captures `require(id)`'s return value
before removing it, rather than discarding it, specifically so a subscriber can render "zone X was
deleted" without a separate lookup. Buffer capacity mirrors `discoveryBuffer` (shares
`eventBufferCapacity`, FIFO, not latest-only — a `DELETED` a resuming viewer missed must still be
delivered, not collapsed away by a later `UPDATED` to a different zone).

**`links:<assetId>` — one asset's paired-link election snapshot, opt-in per asset like `cv-trace`,
not always-on like `zones`.** `contexts/vision-flight`'s `DefaultLinkStateService` calls
`LinkStateLiveUpdatePort#publishLinks(AssetId, LinkGroupView)` at most once per
`linksFor`/`pin`/`release` call — dispatched directly via the executor (`publishZoneEvent`'s
pattern), not coalesced, since there is no hot per-sample cadence to batch here the way `telemetry`/
`detections` have. The envelope's payload is `LinkGroupResponse` (`dto/`) — the exact same shape
`GET /api/assets/{id}/links` returns, "one topic, one full-state snapshot, never a delta". Ring buffer
is per-asset, capacity-1 latest-wins (`LiveRingBuffer(1, true)`), same shape as `cv-trace`'s own
per-asset buffers — `bufferFor`'s `LINKS` case computes into `linksBuffers` (a
`ConcurrentHashMap<AssetId, LiveRingBuffer>`), swept by `evictUnusedAssetBuffers` exactly like every
other per-asset topic. **`LINKS` is removable** from `updateTopics` (a client may unsubscribe), the
same posture as `TELEMETRY`/`DETECTIONS`/`CV_TRACE` — confirmed against `station/vision-web`'s
`live-store.ts`, whose `trackLinks`/`untrackLinks` already call the identical generic `track`/`untrack`
helpers `trackCvTrace`/`untrackCvTrace` use, not the sticky `GEO`/`TRACKS` pair's own helpers.

**`tracks:<assetId>`/`cv-trace:<assetId>` — the live halves of `GET /api/streams/{id}/tracks` and
`GET /api/streams/{id}/cv/trace`'s `frame`, piggybacked onto the existing `pendingDetections` drain
loop rather than a new publish call site.** `pendingDetections` holds a `PendingDetection(DetectionResult
result, TracksSnapshot tracksSnapshot)` per asset — `publishDetections`'s own 3rd parameter
(`contexts/vision-perception`'s `DetectionLiveUpdatePort`), carried verbatim so `detections`/`tracks`/
`cv-trace` all come from one coherent snapshot per result instead of three independent reads. Every
time one is drained for the `detections:<assetId>` topic, `flushPending` also (a) **unconditionally**
publishes the whole `StreamTracksResponse` snapshot (`StreamTracksResponse.from(pending.tracksSnapshot(),
Instant.now())`) onto `tracks:<assetId>` — the exact same assembly `StreamController#tracks` itself
calls ("one assembly, two transports"). `TRACKS` rides the exact same cadence as `DETECTIONS`, every
drain, since the snapshot is computed for free alongside `detections[]` whenever tracking is on; and
(b) publishes one `FrameLedgerResponse` (from `result.ledger()`) onto `cv-trace:<assetId>` **only when
`ledger()` is present** — most ticks carry no ledger at all (tracing must have been separately
demanded via `TraceDemandPort`), so most ticks publish nothing on this topic. Both ring buffers are
capacity-1 latest-wins (`LiveRingBuffer(1, true)`), same as `DETECTIONS` — a newly (re)subscribing
connection replays the last full `StreamTracksResponse` snapshot on `tracks:<assetId>`, not just an
object list. **Payload size**: the `tracks:` envelope rides at frame cadence carrying the whole
snapshot (~1.6KB), not the old ~243-byte bare object list — a fact worth weighing before any
SSE-fan-out/scale decision.
`LiveUpdateRegistry#watchingTrace(AssetId)` — `true` iff at least one live connection currently
subscribes to that asset's `cv-trace:<assetId>` topic — is the SSE half of `LiveAndPollTraceDemand`'s
OR (the poll half is `StreamDetectionSupport#touchedTrace`, a recent `GET .../cv/trace` timestamp).
`CV_TRACE` is deliberately removable from a stale-connection sweep the same way `DETECTIONS`/
`DETECTION_EVENTS`/… are (an inspector's connection dying should stop counting as trace demand); `TRACKS`
is deliberately left at this method's broader scope, matching `GEO` — do not "clean up" the asymmetry,
it is intentional (see the sweep method's own inline comment for the full reasoning).

**`system` — a server-side sampler, not a port.** Unlike every other topic, nothing calls
`LiveUpdateRegistry` through a per-context port to publish `system`; `live/SystemStatusSampler` (a
plain `@Component`, gated the same way `LiveUpdateRegistry` itself is —
`@ConditionalOnProperty(prefix="vision.live", name="enabled", matchIfMissing=true)`) owns its own
`ScheduledExecutorService` and calls `LiveUpdateRegistry#publishSystemStatus(SystemStatusResponse)` —
one public method, no port interface, no new constructor collaborator on the registry itself
(`systemBuffer` is inline-field-initialized `new LiveRingBuffer(1, true)`, exactly like `fleetBuffer`).
The envelope's payload is the verbatim `SystemStatusResponse` `GET /api/system/status` already
returns — same shape `support/SystemStatusReader#read` builds for both callers. The sampler ticks on
its own schedule (`vision.api.live.systemSample`, default 5s, first tick at delay `0` so the buffer is
populated before any connection can possibly arrive — `LiveUpdateRegistry` needs no `seedIfEmpty` case
for `system` because of this) and broadcasts **only on change** — a genuine poll-to-push conversion,
not a fixed-cadence relay.

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
unfixed defect this mitigation routes around**: `fleetBuffer` is `new LiveRingBuffer(1, true)`
(latest-only), and `LiveRingBuffer#everDropped()` is set by collapse-to-latest replacement, so
`live-updates` already reports `DEGRADED` from the second fleet change onward with a misleading
detail — exactly why the exclusion above is necessary, not merely a change-detection convenience. See
`LiveRingBuffer`'s own javadoc.

**`discovery` topic, on top of the still-pollable inbox.** `LiveTopicKind.DISCOVERY`/`LiveTopic.DISCOVERY`
is a ninth always-on topic (no per-connection authorization beyond the connection itself, same posture
as `fleet`/`event`/`devices`/`detection-events`). The envelope is `DiscoveryEventPayload{action,
candidate}` — `action` one of `"REPORTED"`/`"REGISTERED"`/`"DISMISSED"`/`"RESTORED"`, `candidate` the
same `DiscoveryCandidateResponse` shape `GET /api/discovery/inbox` already returns. **Delta-only,
never per-sweep**: `vision-app`'s `LiveUpdateDiscoveryInboxService` decorator only calls
`LiveUpdateRegistry#publishDiscoveryEvent` when a sweep's `ReportOutcome#changed()` is `true` (a
genuinely new candidate, or a status/discovered-vs-not change — not a routine last-seen refresh) or
on an operator verb (`register`/`dismiss`/`attach`/`restore`), so a client watching this topic sees
exactly the events an operator would call "something happened," never the sweep's own cadence.
Gated by `vision.discovery.live.enabled` (default **true** — see `vision-app`'s MODULE.md for the
wiring). `GET /api/discovery/inbox` itself stays pollable, not SSE-only: a client without an open SSE
connection still has a correct, if latent, view from polling; the two are complementary, not a
replacement.

**`devices` also fires on a real stream-state transition.** `LiveUpdateRegistry#publishDevicesSnapshot()`
(a plain, no-arg re-broadcast of the current device list) is additionally invoked by a real
`StreamStateObserver` — wired in `vision-app`'s `ApplicationServiceWiring` — every time
`DefaultStreamService` computes a stream-state transition (start/stop/error), not only on the triggers
that already called it. Gated by `vision.live.stream-state-push.enabled` (default **true**); the
observer itself is edge-triggered and fires synchronously, isolated from a throwing implementation by
`DefaultStreamService` — see `vision-app`'s MODULE.md for the wiring.

**`event` has a durable counterpart.** The `event` topic itself is live-only, lost on disconnect — but
`GET /api/system/events` (own row in the endpoint table above, `SystemEventsController`) durably
replays the same `platform.Event`s this topic fans out, for a caller that missed them across a page
load or a reconnect. Same open-to-any-connected-caller posture as the topic it backfills
(`@OpenByDesign`, not scope-filtered), empty unless `vision-app`'s `vision.events.history.enabled` is
on. `DETECTION` is never durably recorded there (excluded at the publisher, not the controller).

**Scoped delivery**: `MapVisibility` gates the `map` topic by `MapAccessPolicy.canView`; `LiveAssetAccess`
gates per-asset `telemetry`/`detections`/`geo` by `StreamAccess.visibleAsset`. Both filter at
subscribe time (a caller never even subscribes to something out of scope) **and** re-check on every
delivery (a scope change mid-connection, e.g. a revoked assignment, takes effect within the cache
TTL — 5s for assets, 10s for map — with no `PATCH` needed to trigger it). Both checks are called
**directly** in `LiveController`'s handler body, not only stored as a `Predicate` — a check hidden
behind a `Predicate` field is invisible to `EndpointAuthorizationTest`'s static call-graph guard,
which only recognizes a direct call to `CurrentUser.scope()`/`.viewer()` or a class named `*Access`.

**`fleet` is filtered per connection too, same mechanism as `map`.** `LiveUpdateRegistry#freshFleetEnvelope`
builds its snapshot from the unscoped `AssetService#assets()` overload and `fleetBuffer` stays
deliberately unfiltered (so a `Last-Event-ID` resume can re-filter against whatever the resuming
viewer may see *now*, exactly like `map`) — narrowing happens only at delivery time, in
`LiveConnection#project`, which replaced the old `mayReceive(envelope): boolean`. `project` returns a
`LiveEnvelopeResponse` (nullable), not a `boolean`: a `map` event still resolves to either the same
envelope or `null` (outright dropped for a connection that may not see its `layerId`), but a `fleet`
envelope (identified by `type.equals(LiveTopicKind.FLEET.wire())` — never `instanceof List`, since the
envelope's `payload` is an erased `Object`) is never dropped; its asset list is narrowed to
`assetVisibility.test(assetId)`, and a viewer whose scope includes nothing still gets an envelope
carrying an empty list, not silence. `project` returns the identical envelope instance when nothing
needed filtering — load-bearing for `broadcast`'s serialize-once optimization: `broadcast` still
serializes an envelope exactly once and reuses that one `String` for every connection whose `project`
returned that same instance back, paying a second, per-connection re-serialize only for a connection
that actually got a narrower `map`/`fleet` view. The same projection also runs on `connect()`'s
snapshot/resume burst and `updateTopics()`'s newly-added-topic burst.

### Rate limiting (`ratelimit/`)

`RateLimitFilter` (`OncePerRequestFilter`, not a controller) is a **blast-radius bound**, not
security hardening: one token bucket per `CurrentUser#userId()`, 600/min default, `/api/**` except
`/api/live/**` and `/hls/**`. Registered behind `vision.api.rate-limit.enabled`, **default off** —
with auth disabled every caller shares one dev-principal bucket, so turning this on without auth
would rate-limit the whole deployment as one user. 429 body reuses `ErrorResponse` (hand-serialized;
`ApiExceptionHandler` is unreachable from a plain servlet filter).

### DTO conventions

`ErrorResponse(error, message)` is the uniform error body every `ApiExceptionHandler` mapping
returns (see "Error mapping" above). `AssetUsageResponse` carries `origin` (`STREAM` or `OPERATOR` —
the only two `UsageOrigin` values today; a `TELEMETRY` value was considered and deliberately left out
since nothing produces it, see `com.drones.vision.kernel.UsageOrigin`'s own javadoc) and `pilotId` —
the raw `UUID` string of `AssetUsage#pilotId()`, `null`/omitted when genuinely unknown, **no
display-name resolution** at this layer (mirrors `MaintenanceRecordResponse#openedBy`'s own
precedent: id only, a caller resolves a name if it needs one). `UsageSummaryResponse` (the
`GET /api/usages`/`GET /api/usages/by-stream/{id}` row shape) carries the same `pilotId` field, same
nullability rule.

**Warehouse inventory DTOs** — `IdentityResponse(serialNumber, make, model, registration)` /
`CustodyResponse(custodianId, location, since)`, both `@JsonInclude(NON_NULL)` with a static
`from(Identity)`/`from(Custody)`; `AssetSummaryResponse`/`AssetDetailsResponse` each carry trailing
`identity`, `custody`, `inventoryState` (effective value, already a `String` name), `createdAt`,
`updatedAt` fields. `IdentityRequest(serialNumber, make, model, registration)` is a shared top-level
record (not nested per-DTO) so `CreateAssetRequest`/`UpdateAssetRequest` both reuse it via
`toIdentity()`; `CreateAssetRequest` additionally carries a nested `CustodySpec(custodianId, location)`
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
`CategoryEdit` 1:1; `CategoryResponse` carries `connected`; `CategoryCountsResponse` carries
`inStock`/`issued`/`inField`/`maintenance`/`retired`.

`FirmwareResponse(name, version)` (`@JsonInclude(NON_NULL)`, static `from(VehicleProfile)`) and
`FleetMaintenanceRecordResponse(id, assetId, assetName, categoryId, kind, openedAt, closedAt,
openedBy, summary, flightSecondsAt)` (`@JsonInclude(NON_NULL)`, static `from(MaintenanceRecordSummary)`)
— the `GET /api/maintenance` row shape, `MaintenanceRecordResponse`'s fleet-wide sibling with
`assetName`/`categoryId` joined in. `AssetSummaryResponse`/`AssetDetailsResponse` each carry trailing
`firmware` (`FirmwareResponse`, nullable) and `totalFlightSeconds` (`Long`) fields — `firmware` absent
when never probed or when the caller has no join to offer; `totalFlightSeconds` absent **only** when
the caller has no join to offer, never for a genuine zero (a never-flown asset reports `0`). See
`AssetRowFacts` in Conventions for who supplies real values and who passes `null`.

**Discovery DTOs** — `DiscoveryCandidateResponse(id, method, name, address, suggestedCategory,
suggestedStreamProtocol, suggestedStreamUri, suggestedStreamOptions, details, firstSeen, lastSeen,
status, registeredAssetId)` (`@JsonInclude(NON_NULL)`, static `from(DiscoveryCandidate)`) carries
`suggestedStreamOptions` as the **full** `Map<String,String>` — this is the one field this DTO exists
to get right that its sibling `DiscoveredDeviceResponse` (`DiscoveryController`) documented-defect
drops, so a reader must not copy that shape here. `RegisterDiscoveryCandidateRequest(displayName,
category, attributes, identity)` builds a `RegisterFromCandidateCommand` via `toCommand(Ownership)` —
the `Ownership` argument always comes from `CurrentUser#ownership()` in the controller, never a
request field (see Conventions). `RegisterDiscoveryCandidateResponse(assetId, displayName, category)`
is deliberately **not** the full `AssetDetailsResponse` shape — `register` only has the
freshly-created `Asset` in hand, and building the full details response would need extra
collaborators (`AssetRowFacts`, image lookup) this endpoint has no call to pull in; a caller wanting
the full asset shape follows up with `GET /api/assets/{id}`.

**INVENTORY-REWORK W1 additions** (docs/plans/active/INVENTORY-REWORK-PLAN.md §6 frozen wire
contract, D3) — three fields, all additive, no field renamed or removed:
- `CustodyResponse(custodianId, custodianName, location, since)` — `custodianName` is the custodian's
  `User#displayName()`, resolved **server-side by id**. Omitted (not `null`-valued, `NON_NULL`) for an
  in-stock asset, for a custodian whose user record no longer resolves, and on the endpoints with no
  name lookup to offer (below).
- `PilotResponse(userId, role, username, displayName)` — the `GET /api/assets/{assetId}/pilots` row.
  Both names omitted together when the user record no longer resolves; the `userId` is always there,
  so a client always has something to show.
- `AssetSummaryResponse`/`AssetDetailsResponse` gained a trailing **`deviceCount`** (`int`, `Asset#devices().size()`,
  never absent — it is read straight off the same `AssetSummary` the row is built from, so it is
  accurate on **every** producer including the SSE `fleet` snapshot). It exists so a Links column
  renders from the list alone; the web used to `GET /api/assets/{id}` once per row just to count
  devices, turning a 20-asset page into 25 requests (CONTEXT §3, defect D). On
  `AssetDetailsResponse` it is redundant with `devices.size()` and carried anyway, so one row shape
  reads the same from either endpoint.

**Why the names are resolved server-side, and why this is not a scope widening**: the client-side
join this replaces read `GET /api/users`, which answers an **empty list** for a `PILOT`'s
`ASSIGNED_ASSETS` scope — so exactly the caller who most needs to know who holds the aircraft was
the one guaranteed to render a raw UUID (CONTEXT §3, defect C). The server-side resolution is a
**label lookup by id** (`AuthService#find(UserId)`, the same unscoped by-id read `SeatSupport#displayNameOrId`
already makes), on a person the row already names — it cannot be used to enumerate anybody, and
`UserService#list(scope)`'s own filtering is untouched. `AssetSummaryResponse.from`/
`AssetDetailsResponse.from` each widened by one trailing `custodianName` parameter (no overload —
CLAUDE.md rule 10); `PilotResponse.from` widened to take the resolved `User` (nullable = unresolved).

**`DiscoveryCandidateResponse`/`RegisterDiscoveryCandidateResponse` sysid fields (LINK-PAIRING-PLAN.md
§3.3/§7).** Both gained a trailing `Integer assignedSysid` (`@JsonInclude(NON_NULL)`, widened
`from(...)` overload to `from(candidate, sysidPushRequired, assignedSysid)`; a 1-arg/3-field
`from(candidate)` remains for `LiveUpdateRegistry`'s own SSE-envelope call site, delegating straight
to the 3-arg form with both new fields `null`). Both fields come from
`DiscoveryInboxController#register`/`#attach`'s own private `adopt(...)` helper, which returns a
private `AdoptOutcome(boolean sysidPushRequired, int assignedSysid)` record (was a bare `Boolean`) so
the sysid a collision actually assigned reaches the response, not just the fact that one was pushed —
matches `vision-web`'s `core/api/models.ts` `assignedSysid?: number` field verbatim.
`PairingController`'s own DTOs (`PairDeviceRequest(heardSysid, hardwareUid)`, `PairingResponse
(pairingId, deviceId, sysid, hardwareUid, createdAt, replacedAt, sysidPushRequired)`,
`UnpairedDeviceResponse(deviceId, name, capabilities)`) never carry `Pairing#vehicleKey()` — key
material never reaches the wire, same reasoning `VehicleKey#toString()`'s own redaction already
applies at the domain layer. `hardwareUid` is a decimal string both directions (`BigInteger` has no
safe round-trip through JSON `number`); `PairDeviceRequest#parsedHardwareUid()` throws
`IllegalArgumentException` (400) for a non-decimal value, checked before `StreamAccess#requireVisible`
so a malformed request never leaks whether a device exists.

`GET /api/discovery/inbox` answers `DiscoveryInboxResponse(candidates, sources)` — an envelope,
`candidates` carrying the same `DiscoveryCandidateResponse[]` array it always did, under its own key.
`sources` is `List<DiscoverySourceResponse>`, `DiscoverySourceResponse(id, status, lastScanAt)`
(`status` the raw enum name, `"OK"`/`"UNREACHABLE"`, static `from(SourceHealth)`; `lastScanAt`
`@JsonInclude(NON_NULL)`, `Instant`, absent for `SourceStatus.NEVER_SCANNED`) — one row per registered
`vision-warehouse` `DeviceDiscoveryPort`, from that context's `DiscoveryService#health()`; this is
what lets a caller distinguish "mediamtx push-registry scanner unreachable" from "reachable but
empty" — both used to collapse to the same empty `List.of()`. `register`/`dismiss` are unchanged
(still bare `DiscoveryCandidateResponse`/`RegisterDiscoveryCandidateResponse`) — only `list` moved to
the envelope; this is an intentional asymmetry, not an oversight. `DiscoveryInboxController`'s
constructor carries `(DiscoveryInboxService, CurrentUser, DiscoveryService)` — the third parameter
needs no `vision-app` wiring change since this controller has no explicit `@Bean` method (pure
component-scan auto-wiring by type).

`AttachDiscoveryCandidateRequest(String assetId)` backs `POST /api/discovery/inbox/{id}/attach`;
`DiscoveryEventPayload(String action, DiscoveryCandidateResponse candidate)` is the `discovery` SSE
topic's envelope (see "Live updates" above). Three records back `GET /api/discovery/status`
(`@JsonInclude(NON_NULL)` throughout): `TelemetryIntakeResponse(bound, bindAddress, lobbyHeld,
datagramsReceived, bytesReceived, lastDatagramAt, framesDecoded, unclaimedSysids, claimedSysids)`
mirrors `adapter-mavlink`'s `MavlinkIntakeStatus` field-for-field; `VideoIntakeResponse(pushPort,
pathPrefix, readyPaths)` (no `@JsonInclude` on itself — the whole object is either present or the
enclosing `videoIntake` field is absent, not a per-field omission); `DiscoveryStatusResponse(int
sweepSeconds, Instant lastSweepAt, TelemetryIntakeResponse telemetryIntake, VideoIntakeResponse
videoIntake, List<DiscoverySourceResponse> sources)` is the endpoint's top-level shape —
`lastSweepAt`/`videoIntake` both omitted (not `null`) before the first sweep completes / when
mediamtx publish is unconfigured, respectively. `support/DiscoveryStatusFacts` is a **plain, non-DTO**
record (no Jackson annotations) — the crossing-seam payload a `vision-app`-supplied
`Supplier<DiscoveryStatusFacts>` bean hands `DiscoveryStatusController`, kept deliberately separate
from the wire DTOs above so this module's own `dto` package still contains only what actually
serializes (`vision-api` has zero dependency on `adapter-mavlink`/`adapter-discovery`, and one
implementation/one caller doesn't earn a new interface per `.claude/skills/java-clean-code/SKILL.md`
§1). `NetworkAddressResponse` carries a trailing `kind` (`"LAN"`/`"VIRTUAL"`/`"UNKNOWN"`);
`SystemNetworkResponse` carries trailing `videoPushPort`/`videoPushPathPrefix` (`@JsonInclude(NON_NULL)`,
both absent together when mediamtx publish is unconfigured).

**Seat DTOs** — `SeatHolderResponse(holderUserId, holderDisplayName, expiresAt)`, deliberately **not**
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

**Object/track DTOs (CV-ORCHESTRATION).** `dto.ObjectStateResponse` (`@JsonInclude(NON_NULL)`) mirrors
the domain `ObjectState` (`contexts/vision-perception`) field-for-field, JSON keys matching
`station/vision-web/src/app/core/api/models.ts`'s `ObjectState` exactly. Nested static records — one
per facet, each with its own `static from(...)` — mirror the domain's own nested records one-for-one:
`Identity`, `Kinematics`, `Belief`, `Provenance`, `MemoryFacts`, `LockFacts`, `Timing`,
`LabelCandidate`. `Kinematics` reuses `BoundingBoxResponse` for all four box fields (`box`/
`detectorBox`/`trackerBox`/`predictedBox` — no second box DTO) and is itself `@JsonInclude(NON_NULL)` —
an individual source box is omitted, not zeroed, when that source produced nothing this frame. A
`null` domain group (`identity`/`kinematics`/…) maps to a `null` DTO component, which `NON_NULL` then
omits from the JSON entirely — this is the intended "absent is honest" behavior, not a bug.
`DetectionResultResponse` carries `objects` (`List<ObjectStateResponse>`, never `null`, sourced from
`DetectionResult#objects()`) — deliberately still the flat per-identity mirror (see the
`/streams/{id}/detections` endpoint row above); this is unrelated to `WorldObjectResponse` below,
which only `StreamTracksResponse`/`CvTraceResponse` use.

`dto.WorldObjectResponse` is `{state, operator, event, render}` — `state` the same
`ObjectStateResponse` verbatim, `operator`/`event`/`render` the platform-relation facets.
`StreamTracksResponse#objects` and `CvTraceResponse#world` are both `List<WorldObjectResponse>`,
sourced from `StreamService#worldObjects(StreamId)` — one `WorldModel` fold per result feeds both the
REST reads and the `tracks:`/`cv-trace:` SSE topics (see "Live updates" above), never re-folded per
surface.

**Trace DTOs.** `CvTraceResponse{streamId, gate, frame, world}`: `gate` is `GateDecisionResponse[]`
(`contexts/vision-perception`'s `FrameGateLedger`, coalesced — `GateReason` has seven declared values,
including `SENT` and `PROBE`); `frame` is `FrameLedgerResponse[]` (`FrameLedgerRing`, empty unless
tracing was ever requested for this stream); `world` is `List<WorldObjectResponse>`, not windowed by
`last`. `FrameLedgerResponse` carries a `RAN`/`SKIPPED`/`FAILED` `LedgerEntryResponse` triad, an
`ObjectEvidenceResponse` claim shape mirroring cv-service's real `predict.cv` evidence (`predicted`/
`held`/`velocity` keys, `cv/cv-service/cv_service/orchestration/contributors/predict.py`), and
`detections: [{label, confidence, box{x,y,width,height}}]` + `frameWidth`/`frameHeight` — a nested
`FrameLedgerResponse.DetectorBoxResponse(label, confidence, box)` reuses `BoundingBoxResponse` (the
same shape `DetectionResponse` uses) rather than a second box DTO, even though the domain sources
(`DetectorBox`/`Detection`) are deliberately unrelated types; both fields are `detections: []`/
`frameWidth`/`frameHeight: 0` on an untraced frame. `CvTraceResponse` never errors — `gate`/`frame`/
`world` are all empty lists for a never-traced stream. Wire contracts for both `CvTraceResponse` and
`StreamTracksResponse` are pinned by committed JSON fixtures shared with vision-web
(`station/vision-web/src/app/core/api/__fixtures__/cv-trace.wire.json`,
`.../stream-tracks.wire.json`) — see Conventions.

**CV profile DTOs (`CvProfileController`'s family).** Every knob on `CvProfileRequest`/
`CvProfileResponse`/`CvProfileTrackingResponse` is nullable, mirroring the domain `CvProfile`/
`TrackingKnobPatch` field-for-field — a per-knob inheritance model where `null` means "inherit from
the next-lower tier" (asset → category → organization → platform). `CvProfileRequest#toSpec()` is a
straight, unresolved pass-through onto `CvProfileSpec` — every knob carried through byte-identical to
what the request said; `intent` is never consulted here to seed `model`/`labelFilter` — that
resolution happens in `CvProfileResolver`, at fold time, not at request time. `CvProfileTrackingResponse`
has two separately named factories: `from(TrackingKnobPatch)` (null-tolerant, for a raw profile read)
and `fromResolved(TrackingConfig)` (an EFFECTIVE fold's own tracking, always present).
`CvProfileEventRuleResponse#from(EventRuleConfig)` is null-tolerant the same way, serving both call
sites with one method. `CvProfileResponse` has three static factories: `from(CvProfile)` (every plain
`GET`/`list`/`create`/`update`), `platformDefault(PipelineConfig)`, and `fromEffective(CvProfile
matchedProfile, PipelineConfig config)` for `GET .../effective`'s nested `profile` — identity/
bookkeeping fields come from `matchedProfile`, but **every knob comes from `config`** (the fold's own
result), never from `matchedProfile`'s own possibly-partial fields, since under per-knob inheritance
the matched tier may leave several knobs unset.

`CvProfileResponse#sources()` is typed `CvProfileResponse.FieldSources` (4 fields: `model`/
`confidenceThreshold`/`inferenceFps`/`labelFilter`, `of(CvProfile)`/`none()`), computed fresh from the
profile on every read. `EffectiveCvProfileResponse` additionally carries `CvKnobSourcesResponse
sources` (8 fields, one `String` per `KnobSources` component, always present, no `@JsonInclude`) — the
fold's real per-knob provenance across every bound tier — and `intent` (the matched tier's own
persisted pick). See Gotchas for why a second, older `CvProfileResponse.Sources` (2 fields) still
exists alongside `FieldSources`.

**Live PATCH-path provenance (`StreamController#updateConfig`).** `dto.UpdateStreamConfigRequest` is
an 8-component partial-patch record (…`labelDenyFilter`, `intent`, the last two components); a `null`
field means "not sent", distinct from `CvProfileRequest`'s blank/empty-as-sentinel convention (see
Gotchas — do not unify the two). `toPatch()` resolves `intent` (when non-`null`) via
`IntentPolicyResolver.resolve(intent, labelFilter)` and seeds `model`/`labelFilter` only when this
request's own fields were `null`. `fieldSources()` returns `CvProfileResponse.Sources` (the older
2-field record, reused here rather than `FieldSources`). `dto.UpdateStreamConfigResponse` is a
4-component record ending in `CvProfileResponse.Sources sources`; its 2-arg convenience constructor
defaults `sources` to `CvProfileResponse.Sources.none()` — never a bare `null` (CLAUDE.md rule 10).
See Gotchas for the measured `sources: {}` (present-but-empty) JSON shape when no `intent` is sent.

- **Out-of-scope single-resource reads answer 404, not 403.** A caller must never be able to prove a
  resource exists by the status code alone. `AccessDeniedException`→403 is reserved for a scoped
  **command** against something the caller can already see but may not act on (see the error table).
  `DatasetController#get` is a known, deliberate exception (403 for out-of-scope, per
  `DatasetService#get`'s own frozen contract) — the one place in this module that breaks the rule on
  purpose.
- **`@OpenByDesign(reason=…)`** (`security/OpenByDesign.java`) marks a handler that deliberately
  performs no authority check. `EndpointAuthorizationTest` (vision-app, ArchUnit) walks every
  `@RestController` handler's own call graph looking for a call to `CurrentUser.scope()`/`.viewer()`
  or into a class whose name ends `Access`; a handler that reaches neither and isn't annotated (or
  isn't named in its `TEMPORARY_UNSCOPED` ledger) fails the build. Currently annotated:
  `ActivityController#myActivity`, `AssignmentController#myAssignments` (both self-scoped — no target
  parameter to check), `AuthController`'s three handlers (login/logout/me — must work with no
  session), `CategoryController#list`/`GeofenceController#list`/`CvModelsController#models`/
  `CvTrackersController#trackers` (deployment-wide reference data), `DeviceProbeController#probe` (a
  caller-supplied protocol+uri names no existing asset), and `ControlProfileController` at the
  **class** level (gated by profile ownership inside `ControlProfileService`, not by
  `VisibilityScope` — a layout describes one person's transmitter, not an asset, so an operator with
  zero visible assets must still be able to configure it).
- **The `TEMPORARY_UNSCOPED` ledger is a real, currently-open gap, not a design choice** — every
  endpoint tagged `unscoped` in the table above has no authority check at all today. It shrinks with
  each scoping wave; the test's own second assertion fails the build if an entry names a handler that
  no longer exists, so it can't quietly go stale.
- Controllers are constructor-injected with application-service ports only, never an adapter
  (ArchUnit-enforced). Documented exceptions: `HlsProxyController` (a raw `URI`, since it is a
  byte-level proxy with no domain concept to depend on), `AssetImageController` (calls
  `AssetImageRepositoryPort` directly for reads *and* writes — storing/fetching bytes by asset id has
  no business rule beyond what the controller itself already enforces), and a **read-only driven port
  injected directly for a simple resolve-by-id join** — `DatasetController`'s own documented
  precedent, followed by `TrainingJobController`'s `DatasetRepositoryPort` (resolving `datasetName`
  for `GET /api/cv/training/runs`).
- Ids in path variables are canonical UUID strings, parsed via `XId.of(String)`; its
  `IllegalArgumentException` on a malformed UUID surfaces as 400 through the same mapping as domain
  validation — no controller-side translation needed.
- **Authority split on `PATCH /api/assets/{id}`**: the one write whose gate depends on the request
  body, not just the caller. `AssetEdit#changesManagedFields()` decides — a body touching only
  `displayName`/`attributes` needs `scope` alone (so a PILOT may rename their own assigned aircraft);
  a body touching `category` needs `manage`. Every other asset mutation always requires `manage`. A
  per-asset `DetectionPolicy` opt-in (`contexts/vision-perception`'s
  `DetectionPolicy.ATTRIBUTE_KEY = "cv.detection-policy"`, values `"on-view"`/`"always"`) is stored
  under this same free-form `attributes` map — this generic PATCH already round-trips it, so setting
  an asset's policy is just `PATCH {"attributes":{"cv.detection-policy":"always"}}` under the
  `scope`-only authority level above, no separate endpoint/DTO/wire contract.
- Logging: `System.Logger`, not SLF4J — matches every other adapter/domain class in this codebase;
  SLF4J appears only in `vision-app`'s Spring-only devsupport beans.
- **`AssetRowFacts` (`support/`, WAREHOUSE-UX W8; a third join added by INVENTORY-REWORK W1) bundles
  the cross-context reads behind one collaborator** — `firmwareOf(Asset)` (iterates the asset's
  devices, returns the first `VehicleProfileRepositoryPort#findLatest` hit), `totalFlightSecondsByAsset()`
  (delegates to `AssetUsageRepositoryPort`'s aggregate), and `custodianNameOf(Custody)` (W1 —
  `AuthService#find(UserId)`'s `displayName`, `null` for an in-stock asset or an unresolvable user;
  identity is joined here for the same reason flight is, warehouse's `Custody` holds a bare `UserId`
  and must not learn to read identity). `AssetController` already sat at four constructor params
  (`AssetService`, `CurrentUser`, `TelemetryRepositoryPort`, `AssetImageRepositoryPort`); adding
  `VehicleProfileRepositoryPort`, `AssetUsageRepositoryPort` and `AuthService` directly would have
  meant seven, past the five-parameter ceiling (`.claude/skills/java-clean-code/SKILL.md` §3) — so
  all three are bundled into one fifth parameter instead, the same "bundle into a collaborator"
  resolution this file's own `AssetInventoryController`/`AssetStreamController` split documents for
  the same ceiling. `AssetSummary`/domain records were **not** widened for this — see
  `contexts/vision-warehouse/MODULE.md`'s W8 note for why. Two other call sites of
  `AssetSummaryResponse.from`/`AssetDetailsResponse.from` — `AssetInventoryController#detailsResponse`
  and `LiveUpdateRegistry#freshFleetEnvelope` — have no room for `AssetRowFacts` either, and pass
  `null, null, null` explicitly (each documented in place) rather than silently omitting the
  parameters; a caller wanting an accurate `firmware`/`totalFlightSeconds`/`custody.custodianName`
  after those endpoints should follow up with `GET /api/assets/{id}`. `deviceCount` is **not** in
  that company — it comes off the `AssetSummary` itself, so every producer reports it accurately.
  `AssetRowFacts` carries no stereotype annotation and depends on ports from two different contexts —
  `ContextArchitectureTest`'s `contextOf(...)` does not flag this since it only recognizes
  `com.drones.vision.<context>` packages, not `vision-api`/`vision-app`/adapter code.
- **`HandoverService` (vision-identity) owns the two-write hand-over, not `AssetInventoryController`**
  (INVENTORY-REWORK W1, plan D1/D2). `POST /api/assets/{id}/custody` ISSUE must write custody *and*
  grant the custodian the `PILOT` seat, with a compensating `returnToStock` if the grant fails — a
  controller sequencing two writes with an undo between them would be business logic in an adapter.
  The controller stays a thin translator: it calls one service method and renders the result.
  Ground/release/retire still go straight to `AssetCustodyService`; hand-over owns possession, not
  serviceability. **Constructor cost, disclosed**: this puts `AssetInventoryController` at **six**
  collaborators, one past the ceiling — the same documented exception `AssetStreamController` (6)
  and `StreamController` (7) already carry. `HandoverService` joined `AssetCustodyService` rather
  than replacing it, because re-homing ground/release/retire in identity to save a parameter would
  be a far worse trade. The honest fix is to split the four `/maintenance` endpoints onto their own
  controller (five here, two there); that is larger than W1's additive scope and is recorded here as
  debt rather than done quietly.
- **`DiscoveryInboxController`'s three original handlers split authorization the same way
  `AuditController`/`GroupAdminController` already do, for the same reason each does it that way**:
  `list`/`dismiss` gate explicitly in-controller (`currentUser.authority().mayManageOrg()`, throwing
  `AccessDeniedException` itself) because `DiscoveryInboxService#candidates()`/`#dismiss(id, userId)`
  carry no scope parameter to check against — same shape as `AuditController#list`, which has no
  application-service layer of its own to put the check in either. `register` instead passes
  `currentUser.authority()` and `currentUser.ownership()` straight through to
  `DiscoveryInboxService#register(...)`, which performs its own `mayManageOrg()`+`includesGroup`
  checks internally (`vision-warehouse`) — same shape as `GroupAdminController#create` delegating to
  `GroupService`. All three still reach `CurrentUser.scope()`/`.authority()` directly inside the
  controller method body, so `EndpointAuthorizationTest`'s call-graph walk is satisfied without an
  `@OpenByDesign`/ledger entry either way. `attach`/`restore` gate the same way `list`/`dismiss` do —
  explicit in-controller `currentUser.scope().canManageOrg()`-equivalent check — since
  `DiscoveryInboxService#attach`/`#restore` carry no scope parameter of their own either.
  `DiscoveryStatusController#status` gates the same way as its own single handler.
- **A conditionally-absent plain-value bean crossing the `vision-app`→`vision-api` boundary is taken
  through `ObjectProvider<T>`, never a plain, possibly-`null`-valued `T`.** `SystemNetworkController`'s
  `videoPushPort`/`videoPushPathPrefix` constructor parameters are `ObjectProvider<Integer>`/
  `ObjectProvider<String>` — Spring's `@Bean` "null-bean" mechanism (a factory method returning
  `null`) only satisfies `Optional`/`ObjectProvider`/explicitly-`@Nullable` injection points, not a
  plain required constructor parameter; the corresponding `vision-app` bean methods are instead
  genuinely conditionally-registered (`@ConditionalOnProperty`), never present-with-a-null-value —
  see that module's MODULE.md for the wiring side. `mavlinkPort` stays a plain `int` (unconditional,
  never absent), unaffected.
- **`vision-api` never imports `org.springframework.security`.** `CurrentUser`/`PrincipalResolver`
  (`security/`) is the seam through which the `SecurityContext` crosses — both `PrincipalResolver`
  implementations live in `vision-app` (see that module's MODULE.md).
- **`OpsThresholdsController` follows the `CvTrackersController`/`CvModelsController` precedent** — a
  plain config-backed DTO bean built once in `vision-app`'s wiring and injected into a controller that
  does nothing but return it — rather than the `OnboardingProperties` bridge-properties pattern
  (`support/`), since there is exactly one caller and no per-request branching (`java-clean-code` §1:
  a bridge type must earn its place).
- **Wire contracts for the newer response DTOs are pinned by committed JSON fixtures shared with
  vision-web**, not only by prose — `*WireContractTest` classes (e.g. `CvTraceResponseWireContractTest`,
  `StreamTracksResponseWireContractTest`, `ObjectStateResponseTest`/`WorldObjectResponseWireContractTest`)
  build a `full` and a `minimal` example and diff them against
  `station/vision-web/src/app/core/api/__fixtures__/*.wire.json`; the corresponding
  `*.wire.contract.spec.ts` in vision-web loads the same fixture to pin the TypeScript side. Regenerate
  a fixture from the test's own mismatch artifact (`cp target/*.wire.actual.json
  ../vision-web/src/app/core/api/__fixtures__/*.wire.json`), never hand-edit it.
- Rationale for any of the above beyond what's stated here lives in the plan doc cited inline, under
  `docs/plans/`.

## Conventions

`@JsonInclude(Include.NON_NULL)` sits on every response DTO with an optional field, so an absent value
is omitted from the JSON rather than serialized `null` — except where absent-vs-null is itself
meaningful and documented per-DTO (e.g. `AfterActionManifestResponse`'s `endedAt:null` distinguishes
"still flying" from "nothing to report", so it carries no `NON_NULL` annotation; `SeatHolderResponse`
is another documented exception — see DTO conventions). Mapping/validation lives on the DTO records
themselves (`toSpec()`, `toRegistration()`, …) — no mapper library.

Every authorization decision reaches `CurrentUser.scope()`/`.authority()`/`.viewer()` or a class whose
name ends `Access`, directly inside the handler method body — never hidden behind a stored
`Predicate` field — so `EndpointAuthorizationTest`'s static call-graph guard can see it. See the
"Authorization tags" table above for the five gate shapes (`scope`/`manage`/`manageOrg`/`administer`/
`viewer:*`) and the DTO-conventions bullet list above for the exception→404-vs-403 rule and the
`@OpenByDesign`/`TEMPORARY_UNSCOPED` mechanism.

Ids in path variables parse via `XId.of(String)`, never a controller-side regex/format check — see
the DTO-conventions bullet list for the full validation-idiom rules (constructor-injection-only,
five-parameter ceiling + "bundle into a collaborator" resolution, the `org.springframework.security`
import ban, `ObjectProvider<T>` for a conditionally-absent bean).

**LINK-PAIRING-PLAN.md §3.4/§4 row L3 additions** — four new records back `GET /api/assets/{id}/links`,
`POST .../links/pin`, `DELETE .../links/pin` and `GET /api/carriers` (all `dto/`, each with a static
`from(...)` mapping its domain view 1:1, none reusing an existing DTO): `LinkGroupResponse(assetId,
links, activeLinkId, pinned, lastFailoverAt)` (`@JsonInclude(NON_NULL)`) is the top-level snapshot —
also the `links:<assetId>` SSE envelope payload, see "Live updates" above — `activeLinkId` genuinely
`null` (not omitted-as-absent semantics; the field always serializes) when no link is ACTIVE yet,
matching `LinkGroupView#activeLinkId()`. `LinkViewResponse(id, carrier, serialRole, label, active,
receiving, heartbeatAgeSeconds, quality, deviceId)` — `heartbeatAgeSeconds` is a plain whole-second
`long`, not an ISO-8601 duration string (the frozen web contract's own field shape); `deviceId` is
**additive beyond the frozen contract** (LINK-PAIRING-PLAN.md §4 row L3 task brief, not §3.4) so a
multi-device asset's links can be told apart on the wire — flagged here because it is the one field
in this wave a reader must not assume `station/vision-web`'s frozen TS interface already declares.
`LinkQualityResponse(lastRadioStatusAt, rssi, remoteRssi, noise, rxErrors, fixed)` — every field
`Integer`/`Boolean` (not primitive) so `NON_NULL` can omit any one individually, and `from(null)`
returns `null` (the whole object, not just its fields, is absent until the first `RADIO_STATUS` frame).
`CarrierSummaryResponse(id, carrier, serialRole, label, priority)` — no `@JsonInclude`, every field
always populated for a registered carrier, backs the station-wide (not per-asset) `GET /api/carriers`
list.

## Gotchas

- **Two test-fixture bugs LINK-PAIRING L2 found by actually running the required build, not by
  inspection:** `DiscoveryInboxControllerTest` didn't compile at all after `DiscoveryInboxController`
  grew `PairingService`/`AssetService` constructor parameters for "adopt is one motion" (§7 ruling
  3) — its `mockMvcFor` helper now has a 5-arg overload the 3-arg one delegates to (fresh no-op
  mocks), plus two new tests (`registerOfAMavlinkCandidatePairsTheNewDeviceAndReportsThePushRequired
  Sysid`, `attachOfAMavlinkCandidatePairsTheDeviceAndOmitsAssignedSysidWhenNoPushIsNeeded`) that
  actually exercise the composition, since none of the pre-existing register/attach tests happened to
  stub `discoveryInboxService.candidates()` and so never reached it. Separately,
  `PairingControllerTest`'s own `pairing(DeviceId, int)` helper hardcoded `hardwareUid =
  BigInteger.valueOf(42L)` on every call regardless of what each test actually needed, which two
  tests' own assertions contradicted (one expected `hardwareUid` absent, one expected the value it
  had just "passed through" a mocked call) — fixed by defaulting the helper's `hardwareUid` to
  `null` and having the one test that needs a real value construct its own `Pairing` inline.
- **`AssetController#telemetry`, `TrainingJobController#job`/`#jobs`,
  `GeoRegionController#list`/`#progress`, `DiscoveryController#scan`, `SystemNetworkController#network`,
  `SystemStatusController#status`, `DemoController#status`, `OnboardingController#probeCandidate`, and
  `UsageTimelineController#timeline`/`#recording` are genuinely unauthorized today** — no `403`/`404`
  from a caller who shouldn't see them, just a plain `200`. This is the `TEMPORARY_UNSCOPED` ledger
  (`EndpointAuthorizationTest`, vision-app), not an oversight in this doc. `EndpointAuthorizationTest#theTemporaryLedgerHasNoStaleEntries`
  fails on a ledger entry naming a handler that no longer exists, so removing an entry when its
  handler disappears is mandatory, not tidiness.
- **`hasImage` is always `false` on the SSE `fleet` topic's live snapshot** — `AssetImageRepositoryPort`
  has no change-notification port of its own to announce an upload/delete through, and
  `LiveUpdateRegistry`'s constructor is already at the five-parameter ceiling
  (`.claude/skills/java-clean-code/SKILL.md` §3). A viewer that needs an accurate `hasImage` must read
  `GET /api/assets`/`GET /api/assets/{id}` instead.
- **`LiveController`'s constructor needs `@Qualifier("liveUpdateRegistry")`** — `vision-app` exposes
  the same `LiveUpdateRegistry` singleton under seven more bean names (one per `*LiveUpdatePort` it
  implements), so a plain by-type autowire finds eight candidates and fails at context startup.
  `SystemStatusSampler`'s own constructor needs the same qualifier for the same reason — it depends on
  the concrete `LiveUpdateRegistry` type directly (there is no port for `system`, see "Live updates"
  above).
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
  `EventController` cannot cheaply carry generic `Event`s (e.g. `PIPELINE_ERROR`) — `EventPublisherPort`
  is fire-and-forget with no read side at all, and `Event`'s shape shares nothing with `DetectionEvent`'s;
  `SystemEventsController` (`GET /api/system/events`) is the separate, `EventHistoryPort`-shaped read
  side built for exactly this gap — see its own endpoint-table row and the "Live updates" `event`
  paragraph. `DETECTION` is still never durably recorded there either.
- **Usage-scoped reads are split across two controllers on purpose**: `AssetController` still owns the
  original, unwindowed `.../telemetry` (unscoped, see ledger above), reading
  `TelemetryRepositoryPort#findLatestByUsage` (the `/command` fleet map polls this endpoint and was
  freezing once a flight passed `limit` earliest-first samples) — `UsageTimelineController` owns the
  windowed/downsampled `.../timeline`, unaffected, which still reads `#findByUsage` via
  `DefaultReplayService` in `vision-events`. Neither depends on the other's presence.
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
  header**: `VisionApiProperties.HlsProxy` carries `authUsername`/`authPassword` — `null`/blank
  `authUsername` (the `defaults()` factory's value, matching every test that doesn't opt in) sends no
  header at all, which is the correct behaviour for a `vision.publish.enabled=false`/no-mediamtx-auth
  setup. `vision-app`'s `PublishWiring` is the only real caller that supplies a non-null value,
  sourced from `vision.media.auth.*` `VisionMediaProperties` (defaults `vision-viewer`/`change-me` —
  see that module's own MODULE.md). The header is built once in the constructor (`Base64` of
  `username:password`, empty string for a `null` password) and resent on **every** hand-followed
  redirect hop, alongside the existing Cookie/Range forwarding — mediamtx's own read-auth check runs on
  the redirect target, not the first hop, so a header that only rode the initial request would
  silently 401 after the very first request established the pinning cookie.
- **`HlsProxyController` is the one `StreamAccess` caller that fails closed on an unknown/stopped
  stream id** (`requireVisibleForHlsProxy`) — every other caller (`StreamController#stop`/`tracks`/
  `detections`, `EventController`, both above) deliberately keeps `requireVisible`'s no-op, a
  documented "forgiving idiom" for a polling client. Changing the *shared* method instead of adding
  this one would have silently broken those tests; the two methods read identically for a
  *present-but-invisible* device (both throw) and differ only for an *absent* one.
  `requireVisibleStream`'s own malformed-`streamId` short-circuit (a non-UUID path segment) is
  unchanged and runs first — it never reaches `StreamAccess` at all.
- **`ControlProfileController`'s catalogue is served, not hardcoded in the SPA** (CLAUDE.md rule 1).
  `ControlCatalogResponse.of(...)` is derived from the domain enums themselves —
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
  `RcChannels.RELAYED_CHANNELS` (8) — "the client invents nothing the server can state" applied to the
  one number the setup page was still inventing: it used to offer CH1–18 while the link relays only
  CH1–8, so ten of those choices were stored, displayed, and never sent.
- **`AssetParameterController` requires `consent` on every write, regardless of tier** — stricter than
  `RemediationService#writeParameter`'s own `explicitConsent` parameter, which only actually gates
  Tier B internally. Tier A (including `SYSID_THISMAV`/`MAV_SYSID`) would otherwise need no consent at
  all, and this endpoint's whole reason to exist is carrying an explicit operator act.
- **Parameter-name aliases are resolved, not assumed** — ArduPilot 4.7 renamed `SYSID_THISMAV` to
  `MAV_SYSID`, and MAVLink has no "no such parameter" reply, so a wrong spelling would otherwise
  silently no-op. `AssetParameterController#resolveSpelling` consults
  `VehicleProfileService#latestProfile` only for names with more than one known alias
  (`ParameterAliases#spellingsOf`), falling back to the requested spelling on any lookup failure.
- **`security.StreamAccess`'s unowned-device/asset fallback checks `scope.isUnbounded()` directly, not
  an `Authority` capability** — deliberately scope-only, unlike every other admin-shaped gate in this
  module. Widening it to `Authority#mayAdminister()` would additionally require
  `Capability.MANAGE_ORG`, which this fallback never needed; the two call sites
  (`visible(DeviceId)`, `visibleAsset(AssetId, VisibilityScope)`) predate `Authority` entirely and
  their behavior is intentionally unchanged.
- **`LiveUpdateRegistry` has no `DiscoveryLiveUpdatePort`** — `vision-app`'s
  `LiveUpdateDiscoveryInboxService` depends on the concrete `LiveUpdateRegistry` class directly instead
  of a narrow per-context port, unlike every other topic's decorator. See that class's own javadoc in
  `vision-app`'s MODULE.md for why.
- **`SystemStatusResponse`'s cv-service row has no structured capacity field** — cv-service
  capacity/queue facts fold into the existing free-text `detail` string instead (`cv/grpc`'s
  `CvStatusProvider`), not a new structured field.
- **`GateDecision`/`GateDecisionResponse` has no `count` field**, even though
  `docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.4's prose describes coalesced `SKIPPED` gate entries
  as carrying one. Reading `contexts/vision-perception`'s `FrameGateLedger` shows coalescing instead
  *replaces* the previous entry's timestamp/frameSequence/demand snapshot outright — a coalesced run
  is indistinguishable on the wire from a single decision at the same reason; only the refreshed
  `atMillis`/`frameSequence` say time passed. Trust the code, not that paragraph of the plan.
- **`UpdateStreamConfigRequest` and `CvProfileRequest` deliberately use *different* "was this explicit"
  tests for the same-looking `model`/`labelFilter` fields — do not unify them.** `CvProfileRequest`
  treats blank-string/empty-list as "left to the platform" (that wire shape has no `null`).
  `UpdateStreamConfigRequest` is a true partial-patch DTO where `null` means "not sent" and an explicit
  empty `labelFilter` means "keep all labels." Using the blank/empty test on
  `UpdateStreamConfigRequest` would let `intent` silently overwrite an operator's explicit "keep all
  labels," which is exactly backwards for that DTO's contract.
- **`UpdateStreamConfigResponse#sources` serializes as `{}` (present, empty object), never an absent
  key, when no `intent` was sent** — `CvProfileResponse.Sources`'s own `@JsonInclude(NON_NULL)` only
  omits `null` *fields inside* `Sources`; `Sources.none()` itself is never `null` (rule 10 — an
  explicit sentinel, not a `null`), and `UpdateStreamConfigResponse` carries no `@JsonInclude` of its
  own to omit a non-null `sources` field. This is at odds with `CvProfileResponse.Sources.none()`'s own
  javadoc claim of full omission — that javadoc describes `CvProfileResponse` specifically and does not
  hold for `UpdateStreamConfigResponse`.
- **`CvProfileResponse.Sources` (2 fields: `model`/`labelFilter`) and `CvProfileResponse.FieldSources`
  (4 fields) are deliberately separate, near-identical types — do not merge them.** `Sources` exists
  only so `UpdateStreamConfigRequest`/`UpdateStreamConfigResponse`'s live PATCH-path provenance keeps
  compiling against its original two-argument shape; `FieldSources` is what `CvProfileResponse#sources()`
  is actually typed as. Widening `Sources` in place was tried once and reverted because it broke that
  unrelated, frozen call site.
- **`TrainingRunResponse#loss`/`#map50` are boxed `Double`s that are never actually `null`**, even
  though `vision-web`'s `models.ts` declares them `number | null` — the domain has no way to represent
  "no progress reported yet" distinctly from a genuine `0.0`. `TrainingRunResponse` also drops
  `TrainingRunRecord#message` entirely (`models.ts`'s `TrainingRun` has no such field). Both are
  disclosed, permanent wire deviations, not bugs to fix.
- **`RemediationOrchestrator` living in `support/` rather than as a fourth `vision-flight` application
  service is a known layering gap, not a decision** (see its own javadoc). `AssetParameterController`
  is the only `vision-api` caller of `vision-flight`'s `RemediationService#writeParameter`;
  `RemediationOrchestrator`'s own `PARAM_WRITE` refusal path is untouched by that endpoint.

## Status

Feature flags gating whole controllers/packages off by default: `vision.training.enabled` (false —
`DatasetController`/`LabelingController`/`TrainingJobController` all 404 like unmapped routes when
off), `vision.cv.registry.enabled` (defaults to `vision.cv.enabled`'s own value via `application.yaml`'s
`${vision.cv.enabled:false}` placeholder, deliberately decoupled from `vision.training.enabled`
per `docs/plans/active/CV-SETTINGS-PLAN.md` §5 — `ModelRegistryController` 404s like an unmapped route
when off), `vision.onboarding.probe.enabled` (false — `POST /api/onboarding/probe` answers 409, and so does
`AssetParameterController#writeParameter`), `vision.geo.fixed-camera.enabled` (false —
`CameraPoseController`/`MapTracksController` answer 409), `vision.crew.enabled` (false — the seat gate
on `FlightCommandController`/`AssetStreamController`/`AssetSessionController`/`StreamController`/the
WS handler is a pass-through no-op; `SeatController`'s own endpoints are unaffected by this flag),
`vision.api.rate-limit.enabled` (false, see "Rate limiting" above), `vision.auth.enabled` (false —
every `CurrentUser` call resolves a fixed unbounded dev principal), `vision.simulation.enabled` (false
— `SimulationController` 404s like an unmapped route when off; unlike every other flag in this list, a
caller can also read its live value directly — `GET /api/system/network`'s `simulationEnabled` field,
a plain unconditional `boolean` bean wired in `vision-app`, not another `ObjectProvider` case). On by
default: `vision.live.enabled`, `vision.demo.enabled`. `CvProfileController` carries no flag at all —
profiles ship unconditionally, built-ins exist regardless of `vision.cv.enabled`/`vision.cv.registry.enabled`.
SSE-specific flags (`vision.discovery.live.enabled`, `vision.live.stream-state-push.enabled`,
`vision.events.history.enabled`) are covered per-topic in "Live updates" above.

Multi-instance SSE fan-out is out of scope — `LiveUpdateRegistry` is explicitly process-local,
single-instance.

Wave-by-wave history: [`MODULE-HISTORY.md`](MODULE-HISTORY.md).
