# vision-api

REST driving adapter: asset-first + device/stream/discovery endpoints over the domain's use-case ports.

**Depends on:** vision-domain, vision-application (compile) · spring-boot-starter-web · spring-boot-starter-websocket (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md R4 — the `/ws/manual-control` raw `WebSocketHandler` endpoint; the only new dependency this wave added) · test: spring-boot-starter-test (JUnit 5, Mockito, MockMvc, Hamcrest)
**Used by:** vision-app (wires beans in and packages the jar; the frontend, vision-web, is a separate sibling dependency of vision-app — see Gotchas)
**Build/test:** `./mvnw -B -pl station/vision-api test`

## Package layout (docs/plans/active/LAYERING-REFACTOR-PLAN.md §3/§7 row B)

Everything below `com.drones.vision.api` lives in one of these subpackages — nothing sits directly
at the module root anymore: `controller/` (every `@RestController`, +1 for `AssetStreamController` —
docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6e, split off `AssetController` to stay under the
five-constructor-parameter ceiling once `AssetStreamService` joined the picture, see the API surface
table below; `MarksController` was
deleted and `MapLayersController`/`MapMarksController`/`MapDrawingsController` added by
docs/plans/done/MAP-REWORK-PLAN.md Wave C; `DemoController` is the property-gated one, see the `demo/` note
below), `dto/` (wire records only,
~110 — house rule "zero DTO leakage"), `demo/` (the additive, property-gated demo data package — its
own section under API surface; the one subpackage added since this layout was frozen, kept separate
precisely so it can be deleted in one `rm -r` plus two lines), `security/` (`CurrentUser`/`PrincipalResolver`/
`SessionAuthenticator` — the token→`UserId` edge, no `org.springframework.security` dependency),
`ws/` (`ManualControlWebSocketHandler`/`ManualControlHandshakeInterceptor`), `proxy/`
(`HlsProxyController` — a pass-through edge owning no application service), `support/` (edge-local
helpers: `SnapshotJpegEncoder`, `LocalNetworkAddresses`, `CapabilityParsing`, `DeviceOriginParsing`, and the new
`VisionApiProperties` settings record — see its own paragraph below), `live/` (the SSE registry +
ring buffers, unchanged), `ratelimit/` (`RateLimitFilter`/`TokenBucket` — the per-principal
`/api/**` request budget, docs/plans/done/SCALE-100-PLAN.md §5 S6 item 3, added this wave — see its
own API surface section below), `exception/` (renamed from `exceptions/` — `@RestControllerAdvice` +
api-local exceptions), `config/` (MVC/WS/SPA `@Configuration`). This was a pure repackaging: no
route, JSON shape, or status code changed, and every extracted default is byte-identical to the
literal it replaced (see `VisionApiProperties` below).

## API surface

### `com.drones.vision.api.controller` — controllers

| Controller | Method | Path | Success | Failure |
|---|---|---|---|---|
| AssetController | POST | `/api/assets` | 201 `AssetDetailsResponse` | 400 validation (incl. 0-device asset), 403 `!scope.canManageOrg()` (docs/plans/done/OPS-UX-PLAN.md §1/C2) |
| AssetController | GET | `/api/assets?includeDeleted=` | 200 `List<AssetSummaryResponse>` | — (`includeDeleted` defaults `false`) |
| AssetController | GET | `/api/assets/{id}` | 200 `AssetDetailsResponse` | 404 unknown id, 400 bad UUID |
| AssetController | PATCH | `/api/assets/{id}` | 200 `AssetDetailsResponse` | 404 unknown/out-of-visibility id, 400 bad UUID/unknown category (docs/main/CYCLES-PLAN.md §8's pinned contract), 403 visible but `!scope.canManage(ownership)` (docs/plans/done/OPS-UX-PLAN.md §1/C2) |
| AssetController | POST | `/api/assets/{id}/state` | 200 `AssetDetailsResponse` | 404 unknown/out-of-visibility id, 400 unrecognized state, 409 `DELETED`→`ACTIVE` (docs/main/CYCLES-PLAN.md §8; idempotent; `DEACTIVATED` on a `DELETED` asset restores it), 403 visible but `!scope.canManage(ownership)` |
| AssetController | DELETE | `/api/assets/{id}` | 200 `AssetDeletionResponse` | 404 unknown/out-of-visibility id (soft delete/archive, idempotent; docs/main/CYCLES-PLAN.md §8), 403 visible but `!scope.canManage(ownership)` |
| AssetController | POST | `/api/assets/{id}/devices` | 200 `AssetDetailsResponse` | 404 unknown/out-of-visibility asset, unknown device, 400 blank deviceId, 409 device already owned (docs/main/CYCLES-PLAN.md §8), 403 asset visible but `!scope.canManage(ownership)` |
| AssetController | DELETE | `/api/assets/{id}/devices/{deviceId}` | 200 `AssetDetailsResponse` | 404 unknown/out-of-visibility asset, 400 device not on this asset, 409 last remaining device (docs/main/CYCLES-PLAN.md §8), 403 asset visible but `!scope.canManage(ownership)` |
| AssetStreamController | POST | `/api/assets/{id}/stream` | 201 `StartStreamResponse` | 404 unknown asset, 400 ambiguous device/bad UUID |
| AssetStreamController | DELETE | `/api/assets/{id}/stream` | 204 | idempotent no-op for a visible asset with no active stream; 404 unknown-or-out-of-scope asset (LIVE-SCOPE W2, was an unconditional no-op before), 400 bad UUID |
| AssetSessionController | POST | `/api/assets/{id}/session` | 200 `AssetUsageResponse` | 404 unknown-or-out-of-scope asset, 409 asset not in service, 400 bad UUID (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D2, wave R2 — the operator `engage` verb: opens/promotes/no-ops a usage directly, no video stream, no device traffic; `200` not `201` since promoting/no-opping are not creation; see `UsageTracker#engage`'s own javadoc for the three stream/operator collision rules) |
| AssetSessionController | DELETE | `/api/assets/{id}/session` | 204 | idempotent no-op if not operator-engaged; 404 unknown-or-out-of-scope asset, 400 bad UUID (wave R2 — the operator `disengage` verb; demotes to `STREAM` origin rather than closing if a device is still active, mirroring `AssetStreamController#stopStream`'s idempotent-DELETE contract) |
| AssetController | GET | `/api/usages/{usageId}/telemetry?limit=` | 200 `List<TelemetrySampleResponse>` | — (unknown usage → empty list) |
| AssetStatsController | GET | `/api/assets/{id}/stats` | 200 `AssetStatsResponse` | 404 unknown id, 400 bad UUID (docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md Wave A; the manager page's KPI tile row — total flight time, flight count, first/last flown, average flight length, last-known battery, in-progress flag) |
| AssetImageController | PUT | `/api/assets/{id}/image` | 204 | 400 missing/unsupported `Content-Type`, empty body; 413 body over `AssetImageController.MAX_IMAGE_BYTES` (2MB) (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3, CONTRACT 2) |
| AssetImageController | GET | `/api/assets/{id}/image` | 200 raw image bytes, `Content-Type` from storage | 404 no image stored (same response whether the asset itself is unknown or simply has none), 400 bad UUID |
| AssetImageController | DELETE | `/api/assets/{id}/image` | 204 | idempotent no-op; 400 bad UUID |
| CategoryController | GET | `/api/categories` | 200 `List<CategoryResponse>` | — |
| FlightCommandController | POST | `/api/assets/{id}/return-home` | 202 `ReturnHomeResponse` (`result: "ACCEPTED"\|"NO_ACK"`) | 404 unknown asset, 403 out of scope, 409 `{message}` not commandable / aircraft refused (docs/plans/active/DRONE-INFRA-PLAN.md I-e, Stage 1's frozen wire contract — no body; both 409 cases are `IllegalStateException` from `FlightCommandService`, mapped by `ApiExceptionHandler`'s pre-existing, unmodified `IllegalStateException`→409 rule, see Status) |
| FlightCommandController | POST | `/api/assets/{id}/mode` | 202 `ReturnHomeResponse` (`{result}`) | 404 unknown asset, 403 out of scope, 409 not commandable / aircraft refused, **400 unknown or blank mode** (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 2's frozen wire contract — body `{mode}` required; the 400-vs-409 split is deliberate, see the note below the table) |
| FlightCommandController | POST | `/api/assets/{id}/arm` | 202 `ReturnHomeResponse` (`{result}`) | 404 unknown asset, 403 out of scope, 409 not commandable / aircraft refused (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 2; body `{force?:boolean}` optional — the whole body may be absent, `force` defaults `false`; the highest-danger command) |
| FlightCommandController | POST | `/api/assets/{id}/disarm` | 202 `ReturnHomeResponse` (`{result}`) | 404 unknown asset, 403 out of scope, 409 not commandable / aircraft refused (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 2; body `{force?:boolean}` optional, `force` defaults `false`) |
| FlightCommandController | POST | `/api/assets/{id}/emergency-stop` | 202 `ReturnHomeResponse` (`{result}`) | 404 unknown asset, 403 out of scope, 409 not commandable / aircraft refused (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md §2.4 — no body; a **forced disarm**, kept a separate endpoint from `disarm {force:true}` so the audit trail records which of the two was meant) |
| FlightCommandController | POST | `/api/assets/{id}/aux-function` | 202 `ReturnHomeResponse` (`{result}`) | 404 unknown asset, 403 out of scope, 409 not commandable / aircraft refused, **400** bad/missing `function` or `level` (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md C5 — body `{function:int, level:int}` both required; `MAV_CMD_DO_AUX_FUNCTION`, `level` 0 low / 1 middle / 2 high) |
| ControlProfileController | GET | `/api/control-profiles` | 200 `List<ControlProfileResponse>` — **the caller's saved layouts followed by the built-ins**, never empty | — (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md C6/C7; scoped to the acting user by ownership, not by `VisibilityScope`) |
| ControlProfileController | GET | `/api/control-profiles/catalog` | 200 `ControlCatalogResponse` | — (decision C8 — every enumerable choice the setup UI offers: vehicle kinds, input kinds + which sources each allows, switch positions + their aux levels, control functions, actions + their parameter kind + **`dangerous`**, and a seed list of `RCx_OPTION` numbers) |
| ControlProfileController | POST | `/api/control-profiles` | 201 `ControlProfileResponse` | 400 blank name / unknown vehicle kind (body `{kind, name}`; the new profile is a **copy of the built-in** for that kind, C7) |
| ControlProfileController | PUT | `/api/control-profiles/{id}` | 200 `ControlProfileResponse` | 400 bad id / invalid layout (a control bound twice, a channel driven twice, a `SWITCH_3` on a button, a missing action parameter — every one of them a domain `IllegalArgumentException`), 403 another operator's profile, 404 unknown id (body `UpdateControlProfileRequest{name, channelMap[], actionMap[]}` — the whole layout, replaced as a unit) |
| ControlProfileController | POST | `/api/control-profiles/{id}/activate` | 204 | 403 another operator's, 404 unknown id (**at most one active per (owner, vehicle kind)**, enforced down at the database — see `storage/persistence`) |
| ControlProfileController | DELETE | `/api/control-profiles/{id}` | 204 | 400 a built-in (they are not rows and cannot be deleted), 403 another operator's, 404 unknown id |
| FlightCommandController | GET | `/api/assets/{id}/flight-capabilities` | 200 `FlightCapabilitiesResponse` (`{commandable, armSupported, modeSelectSupported, selectableModes:[...], vehicleKind}`) | 404 unknown **or out-of-scope** asset, 400 bad UUID (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 2 — a scoped *read*, so out-of-scope 404s hiding existence, **not** the 403 the commands give; an in-scope non-drone asset returns `commandable:false` + empty `selectableModes`, never an error) |
| DeviceProbeController | POST | `/api/devices/probe` | 200 `ProbeDeviceResponse` (`ok:true`) | 400 malformed request (blank/missing `protocol`/`uri`, unparseable `uri`, or a protocol **neither** a video nor a telemetry adapter claims — `UnsupportedProtocolException`, in `perception.application.stream`); 422 a claimed protocol whose connection itself fails/times out/ends without a frame **or, for a telemetry-only link, without a sample** (`ProbeFailedException`, in `perception.application.device` since W1.6d — docs/plans/active/DOMAIN-SEPARATION-W1.md §15, moved from `warehouse.application.device` with its thrower `DefaultProbeService`, specific actionable message per UX-DESIGN §5.1) (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3, CONTRACT 1 — never registers anything) |
| DeviceController | POST | `/api/devices` | 201 `DeviceResponse` | 400 validation |
| DeviceController | GET | `/api/devices?includeDeleted=` | 200 `List<DeviceResponse>` | — (`includeDeleted` defaults `false`) |
| DeviceController | PATCH | `/api/devices/{id}` | 200 `DeviceResponse` | 404 unknown id, 400 bad UUID/partial-stream fields (docs/main/CYCLES-PLAN.md §8's pinned contract) |
| DeviceController | POST | `/api/devices/{id}/state` | 200 `DeviceResponse` | 404 unknown id, 400 unrecognized state, 409 `DELETED`→`ACTIVE` (docs/main/CYCLES-PLAN.md §8; idempotent; `DEACTIVATED` on a `DELETED` device restores it) |
| DeviceController | DELETE | `/api/devices/{id}` | 200 `DeviceResponse` | 404 unknown id (soft delete/archive, idempotent; docs/main/CYCLES-PLAN.md §8) |
| StreamController | POST | `/api/devices/{deviceId}/stream` | 201 `StartStreamResponse` | 400 bad UUID/validation, 404 unknown-or-out-of-scope device (LIVE-SCOPE W2), 409 already streaming |
| StreamController | GET | `/api/streams` | 200 `List<ActiveStreamResponse>` | filtered to the caller's visible streams, not all-or-nothing 403'd (LIVE-SCOPE W2) |
| StreamController | DELETE | `/api/streams/{streamId}` | 204 | idempotent no-op for an unknown/already-stopped stream; 404 if currently running but out of the caller's scope (LIVE-SCOPE W2) |
| StreamController | GET | `/api/streams/{streamId}/detections?limit=` | 200 `List<DetectionResultResponse>`, newest first | 400 non-positive `limit` (docs/plans/done/MVP1-PLAN.md §C8 bullet 3; unknown stream → empty list, `limit` defaults 50); 404 if currently running but out of the caller's scope (LIVE-SCOPE W2) |
| StreamController | GET | `/api/streams/{streamId}/snapshot` | 200 `image/jpeg` bytes, `Cache-Control: no-store` | 404 unknown stream, no frame published yet, or (LIVE-SCOPE W2) currently running but out of the caller's scope; 400 bad UUID (docs/plans/done/MVP3-PLAN.md C-a; downscaled to `SnapshotJpegEncoder.MAX_SNAPSHOT_WIDTH`=480px wide, aspect-preserving; the one binary, non-JSON response in this module) |
| StreamController | PATCH | `/api/streams/{streamId}/config` | 200 `{streamId, modelReArmed, trackingChanged}` | 404 unknown/not-running stream, or (LIVE-SCOPE W2) running but out of the caller's scope; 400 invalid merged value (docs/plans/done/CV-CONTROL-PLAN.md §3's frozen wire contract — live per-stream detection control: confidence/inference-fps/labelFilter/detectionEnabled apply hot with no video interruption; a changed `model` briefly re-arms detection instead, reported via `modelReArmed`; body is a true partial patch, every field optional/absent-means-unchanged, whole body may be absent (no-op); no acting user threaded, same stance as every other endpoint on this controller; **docs/plans/done/TRACKING-PLAN.md §4.D** adds an optional `tracking` object — mode/engineId/verifyEveryMillis/followFps/redetectIouPercent/maxAgeFrames/minHits/lock — which is a **hot knob like the rest: it never re-arms the detector**, reported via the new `trackingChanged`; an unknown `mode` and a `lock` that is not exactly one of `{trackId}`/`{pointX,pointY}`/`{release:true}` are both 400, and a client never sends `lockSeq`. **Click-to-follow is this call**, not a new endpoint) |
| StreamController | GET | `/api/streams/{streamId}/tracks` | 200 `{streamId, lockedTrackId, tracks:[...], stats?, latency?}` | 400 bad UUID; 404 if currently running but out of the caller's scope (LIVE-SCOPE W2 — the one case this handler is not forgiving about) (docs/plans/done/TRACKING-PLAN.md §4.E's frozen wire contract — the running stream's track book plus the duty-cycle counters; **never errors for an unknown/stopped stream**: that case is a 200 with `tracks:[]`, `lockedTrackId:0` and no `stats`, the same forgiving idiom `GET .../detections` uses. `lockedTrackId` is hoisted **above** `stats`, and `stats` is omitted entirely until the window has recorded a detector pass — see the DTO paragraph) |
| CvTrackersController | GET | `/api/cv/trackers` | 200 `{trackers:[{id, displayName, modes, needsAssets, costHint}]}` | — (docs/plans/done/TRACKING-PLAN.md §4.F's frozen wire contract; never errors; a static, config-backed `vision-app` bean exactly like `GET /api/cv/models`' roster — the engine list changes at deploy time, not runtime) |
| CvModelsController | GET | `/api/cv/models` | 200 `{models:[{id, displayName, kind, openVocab, defaultLabelFilter}]}` | — (docs/plans/done/CV-CONTROL-PLAN.md §4's frozen wire contract; never errors; `yolo26n.pt` listed first, the default; roster is a static, config-backed `vision-app` bean, not the dormant `ModelRegistryPort`) |
| UsageTimelineController | GET | `/api/usages?limit&assetId` | 200 `List<UsageSummaryResponse>`, newest first | 400 malformed `assetId` UUID (docs/plans/done/NAV-IA-REDESIGN-PLAN.md Wave 4, F8, docs/extracts/design/10-replay.md's frozen contract — the "replay library" list; scoped to `CurrentUser#scope()`, an unknown/out-of-scope `assetId` yields `[]`, never an error; `limit` defaults 50, clamped to `DefaultUsageService.MAX_LIMIT`=500) |
| UsageTimelineController | GET | `/api/usages/{usageId}/timeline?fromMs&toMs&maxPoints` | 200 `UsageTimelineResponse` | 404 unknown usage, 400 bad UUID/non-positive `maxPoints`/`toMs` before `fromMs` (docs/plans/done/MVP2-PLAN.md §R, R-a — flight replay; windowed + downsampled, unlike `AssetController`'s older `.../telemetry`; see Gotchas) |
| UsageTimelineController | GET | `/api/usages/{usageId}/recording` | 200 `UsageRecordingResponse` (`available:true` with `url`/`start`/`durationSeconds`, or `available:false` alone when the usage has no `streamId` or the stream publisher has no recording/playback endpoint — never an error) | 404 unknown usage, 400 bad UUID (docs/plans/done/OPS-CORE-PLAN.md §R, R-b — same collaborator as `timeline` above, so this endpoint joins that controller rather than a new one) |
| UsageTimelineController | GET | `/api/usages/by-stream/{streamId}` | 200 `UsageSummaryResponse` | 404 no visible usage carries that stream id, 400 bad UUID (docs/plans/done/STREAM-STATE-PLAN.md §2.6, S5 — "what happened to stream X"; a stopped stream is absent from `GET /api/streams` by design, and this is where its record is read from. Scoped to `CurrentUser#scope()`: out-of-scope and does-not-exist are the **same** 404, never a 403 that would confirm existence) |
| GeofenceController | GET | `/api/geofences` | 200 `List<GeofenceZoneResponse>` | — (docs/plans/done/OPS-CORE-PLAN.md §G; sorted by name, see `GeofenceService#zones()`) |
| GeofenceController | POST | `/api/geofences` | 201 `GeofenceZoneResponse` | 400 unrecognized `kind`, polygon with fewer than 3 vertices, or an out-of-range vertex/`maxAltitudeMeters` (docs/plans/done/OPS-CORE-PLAN.md §G) |
| GeofenceController | PUT | `/api/geofences/{id}` | 200 `GeofenceZoneResponse` | 404 unknown id, 400 bad UUID/same validation as create (docs/plans/done/OPS-CORE-PLAN.md §G; wholesale replace — same body shape as create) |
| GeofenceController | DELETE | `/api/geofences/{id}` | 204 | 404 unknown id (docs/plans/done/OPS-CORE-PLAN.md §G; idempotent per `GeofenceRepositoryPort`, but the service checks existence first so an unknown id still 404s — see Conventions/`ApiExceptionHandler` table), 400 bad UUID |
| EventController | GET | `/api/events?sinceMs&limit` | 200 `List<DetectionEventResponse>`, newest first by `lastSeen` | 400 non-positive `limit` (docs/plans/done/MVP2-PLAN.md §E, E-a; `sinceMs` absent = no lower bound, `limit` defaults 50) |
| EventController | GET | `/api/streams/{streamId}/events?limit` | 200 `List<DetectionEventResponse>`, newest first by `lastSeen` | 400 bad UUID/non-positive `limit` (docs/plans/done/MVP2-PLAN.md §E, E-a; unknown stream → empty list, same precedent as `StreamController#detections`, `limit` defaults 50) |
| FleetController | GET | `/api/fleet/summary?includeArchived=` | 200 `FleetSummaryResponse` | — (docs/plans/done/MVP3-PLAN.md C-a; `includeArchived` defaults `false`, mirrors `AssetService#assets(boolean)`'s `includeDeleted` — see Conventions for the deliberate naming difference) |
| LiveController | GET | `/api/live?topics=` | 200 `text/event-stream` (`SseEmitter`) | 400 a malformed `topics` entry (docs/plans/done/REALTIME-PLAN.md §4 — server-push data plane; see its own subsection below); gated by `vision.live.enabled` (default `true`) — `false` removes this controller (and `LiveUpdateRegistry`) as beans entirely, so the route 404s like any other unmapped path |
| LiveController | PATCH | `/api/live/{connectionId}/topics` | 200 `LiveSubscriptionResponse` | 404 unknown `connectionId`, 400 a malformed topic entry |
| SimulationController | POST | `/api/simulations` | 201 `SimulationResponse` (absent/blank `videoPath` with the default `direct` transport is a fully synthetic simulation, docs/main/CYCLES-PLAN.md §9, CU-a — not an error) | 400 a non-null `videoPath` failing `SimulationService`'s filesystem checks, unrecognized `transport`, (docs/main/CYCLES-PLAN.md §9, CU-a) a `null`/blank `videoPath` combined with `transport=rtsp`/`mjpeg`, (docs/main/CYCLES-PLAN.md §3, §5) a `transport=rtsp`/`mjpeg` spec no registered `FeedTransmitterPort` supports, or (docs/main/CYCLES-PLAN.md §7) an invalid `telemetry` object (fewer than 2 waypoints, an out-of-range coordinate, a non-positive `speedMps`, or an unrecognized `routeMode`); 409 `simulated` category not seeded; 403 if the caller's scope may not `canManageOrg()` (LIVE-SCOPE W2, closes the asymmetry with `AssetController#create`'s own gate — previously ungated) |
| SimulationController | DELETE | `/api/simulations/{assetId}` | 204 | idempotent no-op for a visible asset; 404 unknown-or-out-of-scope asset (LIVE-SCOPE W2, was an unconditional no-op before); 400 bad UUID |
| DiscoveryController | POST | `/api/discovery/scan` | 200 `ScanResultResponse` | 400 unknown method name |
| HlsProxyController | GET | `/hls/{streamId}/**` | proxied upstream status (typically 200 or 206 for a `Range` request), `Content-Type`/`Cache-Control`/`Set-Cookie`/`Content-Range`/`Accept-Ranges`/`Content-Length` passed through (docs/plans/done/MVP2-PLAN.md V-a: `Cache-Control` forwarding added, was previously dropped; docs/plans/done/SCALE-100-PLAN.md §5 S1: streamed rather than buffered, `Range` forwarded) | 502 upstream unreachable or a redirect chain longer than `maxRedirectHops` (default 5, `vision.api.hls-proxy.max-redirect-hops` as of §5 S7); 404 if no `{streamId}` segment (unmapped, Spring's default) |
| AuthController | POST | `/api/auth/login` | 200 `MeResponse` (+ session cookie when auth enabled) | 401 bad credentials (auth enabled); with auth **disabled** always 200 dev admin, no-op (docs/plans/done/U-AUTH-PLAN.md wave 3) |
| AuthController | POST | `/api/auth/logout` | 204 (invalidates session) | — (idempotent; no-op when auth disabled) |
| AuthController | GET | `/api/auth/me` | 200 `MeResponse` | 401 when auth enabled + unauthenticated (Spring Security answers it — `/api/auth/me` is not in the enabled chain's permit-list); with auth disabled always 200 dev admin (`authEnabled=false`) (docs/plans/done/U-AUTH-PLAN.md wave 3) |
| AssignmentController | PUT | `/api/assets/{assetId}/pilots/{userId}` | 204 (idempotent assign) | 404 unknown asset, 403 asset out of granter's scope, 400 bad UUID (docs/plans/done/U-SCOPE-PLAN.md slice 2, feature 2) |
| AssignmentController | DELETE | `/api/assets/{assetId}/pilots/{userId}` | 204 (idempotent unassign) | 404 unknown asset, 403 out of granter's scope, 400 bad UUID |
| AssignmentController | GET | `/api/assets/{assetId}/pilots` | 200 `List<PilotResponse>` | 404 unknown or out-of-scope asset (scoped read → 404 hides existence), 400 bad UUID |
| AssignmentController | GET | `/api/me/assignments` | 200 `List<AssignmentResponse>` | — (the caller's own assigned asset ids; empty if none) |
| ActivityController | GET | `/api/me/activity?limit=` | 200 `List<AuditEntryResponse>`, newest first | — (`limit` defaults 50, capped 500, floored 1; the caller's own audit entries only — docs/plans/done/U-SCOPE-PLAN.md slice 2, feature 7) |
| UserAdminController | GET | `/api/users` | 200 `List<UserResponse>` | — (scope-filtered to the caller's subtree; unbounded/ADMIN → all, docs/plans/done/U-SCOPE-PLAN.md slice-2 cleanup) |
| UserAdminController | POST | `/api/users` | 201 `UserResponse` | 403 caller may not manage / grants a role or group outside scope / (manager) creates a user with no memberships; 400 blank/bad field or unknown role, 409 username already taken (docs/plans/done/U-SCOPE-PLAN.md slice 2) |
| UserAdminController | POST | `/api/users/{id}/enabled` | 200 `UserResponse` | 403 caller may not manage / target outside scope, 404 unknown id, 400 bad UUID |
| GroupAdminController | GET | `/api/groups` | 200 `List<GroupResponse>` | — (scope-filtered; unbounded/ADMIN → all, docs/plans/done/U-SCOPE-PLAN.md slice-2 cleanup) |
| GroupAdminController | POST | `/api/groups` | 201 `GroupResponse` | 403 caller may not manage / (manager) creates a root group / parent outside scope; 400 blank name/bad parent UUID, 404 unknown parent group (docs/plans/done/U-SCOPE-PLAN.md slice 2) |
| MapLayersController | GET | `/api/map/layers` | 200 `List<LayerResponse>` | — (docs/plans/done/MAP-REWORK-PLAN.md §4.1; visible layers only, COP first then by name; `grants` present only when `myAccess == MANAGE`; `markCount`/`drawingCount` are the caller's own visible `ACTIVE` marks / drawings on that layer) |
| MapLayersController | POST | `/api/map/layers` | 201 `LayerResponse` | 400 unrecognized `kind` (incl. `COP`, which is infrastructure) / blank-or-over-80-char `name` / `TEAM` with no `groupId` / malformed `groupId`; 403 caller is neither ADMIN nor MANAGER of that group |
| MapLayersController | PATCH | `/api/map/layers/{id}` | 200 `LayerResponse` | 404 unknown layer, 400 bad UUID/blank name, 403 caller does not manage it, **409 the COP layer cannot be renamed** |
| MapLayersController | DELETE | `/api/map/layers/{id}` | 204 | 404 unknown layer, 400 bad UUID, 403 caller does not manage it, **409 the COP layer cannot be deleted** (cascades to its marks + drawings, each emitting its own `map` SSE event) |
| MapLayersController | PUT | `/api/map/layers/{id}/grants` | 200 `LayerResponse` | 404 unknown layer, 400 unrecognized `subjectType`/`level` or malformed `subjectId`, 403 caller does not manage it (**wholesale replacement**: an absent/empty list clears every grant) |
| MapMarksController | GET | `/api/map/marks` | 200 `List<MarkResponse>` | — (docs/plans/done/MAP-REWORK-PLAN.md §4.1; `ACTIVE` marks on layers the caller may view, newest first — **layer-scoped, superseding the old deployment-wide `/api/marks`**) |
| MapMarksController | POST | `/api/map/marks` | 201 `MarkResponse` | 400 unrecognized `kind`/`affiliation` (note `FRIENDLY` is no longer a `kind`), out-of-range coordinates, blank `label`, malformed `layerId`; 404 unknown `layerId`; 403 caller may not contribute to the resolved layer (absent `layerId` = "my default layer", never an error) |
| MapMarksController | POST | `/api/map/marks/geolocate` | 201 `MarkResponse` (`measured` set) | 400 blank/malformed `assetId`, unrecognized `kind`/`affiliation`, incomplete telemetry (`"cannot geolocate: telemetry incomplete"`); 404 unknown `layerId`; 403 may not contribute (absent `kind`/`affiliation`/`label` default to `TARGET`/`HOSTILE`/`"Contact"`; **`depressionDegrees` does *not* default** since docs/plans/done/GEO-POSE-PLAN.md wave V3 — absent/`null` lets the resolved pose decide (real gimbal reading if present, else `GeoProjection.DEFAULT_DEPRESSION_DEGREES`), a present value is an operator override that wins even over a gimbal reading — see `GeolocateMarkRequest`) |
| MapMarksController | PATCH | `/api/map/marks/{id}` | 200 `MarkResponse` | 404 unknown mark, 400 bad UUID / unrecognized `kind`/`affiliation`/`status` / **only one half of the `latitude`+`longitude` pair**, 403 caller is neither the creator (while `UNVERIFIED`) nor a manager of the layer |
| MapMarksController | POST | `/api/map/marks/{id}/verify` | 200 `MarkResponse` | 404 unknown mark, 400 unrecognized or `UNVERIFIED` `decision`, **403 non-manager** (docs/plans/done/MAP-REWORK-PLAN.md §3 — `canManage` on the mark's current layer) |
| MapMarksController | POST | `/api/map/marks/{id}/promote` | 200 `MarkResponse` | 404 unknown mark or unknown target layer, 400 malformed `targetLayerId`, 403 caller does not manage the source layer or may not contribute to the target (**whole body optional**; absent `targetLayerId` = the COP layer; stamps `CONFIRMED` if not already) |
| MapMarksController | DELETE | `/api/map/marks/{id}` | 204 | 404 unknown mark, 400 bad UUID, 403 neither creator-while-unverified nor manager |
| MapDrawingsController | GET | `/api/map/drawings` | 200 `List<DrawingResponse>` | — (docs/plans/done/MAP-REWORK-PLAN.md §4.1; drawings on layers the caller may view) |
| MapDrawingsController | POST | `/api/map/drawings` | 201 `DrawingResponse` | 400 unrecognized `kind`, wrong point count for the kind (`LINE`/`ARROW` ≥2, `POLYGON` ≥3, `TEXT` exactly 1 + non-blank label), non-kebab-case `colorToken`, out-of-range point, malformed `layerId`; 404 unknown `layerId`; 403 may not contribute |
| MapDrawingsController | PATCH | `/api/map/drawings/{id}` | 200 `DrawingResponse` | 404 unknown drawing, 400 bad UUID / out-of-range point / point count wrong for the drawing's kind, 403 neither creator nor manager (`points`, when present, **replaces geometry wholesale**) |
| MapDrawingsController | DELETE | `/api/map/drawings/{id}` | 204 | 404 unknown drawing, 400 bad UUID, 403 neither creator nor manager |
| DatasetController | POST | `/api/datasets` | 201 `DatasetResponse` | 400 blank name / invalid `targetCategory` slug, 403 caller may not manage the organization (docs/plans/done/CV-TRAINING-PLAN.md §3's frozen wire contract, Wave T4; gated by `vision.training.enabled`, default `false` — absent entirely when off, every route 404s like any unmapped path) |
| DatasetController | GET | `/api/datasets` | 200 `DatasetsResponse` (`{datasets:[...]}}`) | — (scope-filtered; `sampleCounts` computed per dataset from `TrainingSampleRepositoryPort#countByDataset`, not a `Dataset` field) |
| DatasetController | GET | `/api/datasets/{id}` | 200 `DatasetResponse` | 404 unknown id, 400 bad UUID, 403 dataset outside caller's scope (deliberately not the usual hiding 404 — `DatasetService#get`'s own frozen contract) |
| DatasetController | DELETE | `/api/datasets/{id}` | 204 | 404 unknown id, 403 caller may not manage the organization (does not cascade to the dataset's samples/images — `DatasetService#delete`'s own javadoc) |
| LabelingController | POST | `/api/streams/{streamId}/samples` | 201 `SampleResponse`   body: `{datasetId}` | 404 unknown dataset, or the stream has no frame published yet; 403 dataset/source asset outside caller's scope; 400 blank/malformed `datasetId` (docs/plans/done/CV-TRAINING-PLAN.md §3; capture reads the stream's current **raw**, full-resolution frame + latest detections — `TrainingFrameEncoder`, `vision-application` — the controller passes only ids; annotations pre-filled `source="MODEL"`) |
| LabelingController | POST | `/api/usages/{usageId}/samples` | 201 `SampleResponse`   body: `{datasetId, atSeconds}` | 404 unknown usage/dataset, usage with no recorded video stream, or no recorded frame at that instant; 403 dataset or usage's asset outside caller's scope; 400 malformed uuid, missing/negative/non-finite `atSeconds`, or `atSeconds` past the usage's recorded window (docs/plans/done/CV-TRAINING-V2-PLAN.md §5 — the replay counterpart to the live capture above, invoked from the Replay page's "Add to dataset" action rather than a stream picker; reuses `SampleResponse` verbatim, annotations pre-filled `source="MODEL"` from the nearest stored detection within a ±2s window, `[]` when nothing was detected near that instant) |
| LabelingController | GET | `/api/datasets/{id}/samples?status=&limit=` | 200 `SamplesResponse` (`{samples:[...]}}`) | 404 unknown dataset, 403 dataset outside scope, 400 unrecognized `status` (`limit` defaults 50) |
| LabelingController | GET | `/api/samples/{id}/image` | 200 raw image bytes, `Content-Type` from storage, `Cache-Control: no-store` | 404 unknown sample or no image stored, 403 dataset outside scope |
| LabelingController | PUT | `/api/samples/{id}/annotations` | 200 `SampleResponse` (confirm/correct) | 404 unknown sample, 403 dataset/asset outside scope, 400 unrecognized `status`/annotation `source`, or an annotation label outside the dataset's class vocabulary |
| ModelRegistryController | GET | `/api/cv/registry/models` | 200 `RegisteredModelsResponse` (`{models:[{id,version,active}]}}`) | — (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9; gated by `vision.training.enabled`, default `false` — absent entirely when off; unscoped/unaudited read, any authenticated caller may see it, mirroring `CvModelsController`'s own "any caller may read the roster" precedent — **not** the same roster as `GET /api/cv/models`, which is a static, config-backed picker; this one is the dynamic registry sourced live over gRPC, see the controller's own javadoc) |
| ModelRegistryController | POST | `/api/cv/registry/models/{id}/promote` | 200 `RegisteredModelResponse` (`{id,version,active:true}`) | 403 caller may not manage the organization, 409 cv-service refuses the promotion (unknown model id — rsync the artifact first — or no registry reachable at all), 400 blank `version` (`ModelRef`'s own compact-constructor check) (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9; body `{version}` required — the path `{id}` alone doesn't resolve a `ModelRef`; response is constructed directly from the now-promoted reference rather than re-querying the registry, since `ModelRegistryService#promote` returns `void`) |
| TrainingJobController | POST | `/api/datasets/{id}/train` | 202 `TrainingJobResponse` body: `{baseModel, epochs}` | 404 unknown dataset, 403 caller may not manage the organization or the dataset is outside their scope, 400 malformed dataset id, blank `baseModel`/non-positive `epochs` (`TrainingJobSpec`'s own compact-constructor check), or the dataset has no `LABELED` samples to train on (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2's last backend wave, delta'd by docs/plans/done/CV-TRAINING-V2-PLAN.md §5; gated by `vision.training.enabled`, default `false`; `{id}` is now parsed into a `DatasetId` **at this controller's edge** — a malformed id is a synchronous 400 rather than an opaque failure downstream — then its canonical string form is threaded into `TrainingJobSpec#datasetId()`, which itself stays a plain string all the way to the gRPC boundary; the unknown-dataset/out-of-scope/no-labeled-samples cases are `TrainingJobService#start`'s own synchronous pre-check, not this controller's; body/response shape is otherwise byte-identical to before — the handler now also uploads the dataset to cv-service over gRPC as part of the same job, see contexts/vision-learning/MODULE.md) |
| TrainingJobController | GET | `/api/training/jobs/{jobId}` | 200 `TrainingJobResponse` | 404 unknown `jobId` (never started, or evicted under `TrainingJobService`'s finished-job retention policy) (docs/plans/done/CV-TRAINING-PLAN.md §7/§8; a training **failure** is reported here as `state:"FAILED"` — never a thrown exception) |
| TrainingJobController | GET | `/api/training/jobs` | 200 `TrainingJobsResponse` (`{jobs:[...]}}`) | — (docs/plans/done/CV-TRAINING-PLAN.md §7/§8; every tracked job, newest-first by `startedAt`, unscoped/unaudited read — any authenticated caller may poll, mirroring `ModelRegistryController#models`'s own "any caller may read" precedent) |
| DemoController | GET | `/api/demo` | 200 `DemoStatusResponse` (`{enabled:true, videosDirectory, videos:[…]}`) | — (the console's demo-button availability probe; gated by `vision.demo.enabled`, default **on** — `false` removes this controller and every `…api.demo` bean, so both routes 404 like any unmapped path) |
| DemoController | POST | `/api/demo/seed` | 201 `DemoSeedResponse` | — (fault-tolerant by design: a step that fails lands in the response's `problems` list, never in an error status; body `DemoSeedRequest` optional in whole and in every field, absent means `DemoPlan.DEFAULT` = 10 assets / 10 users / 3 streams, each count clamped to `DemoPlan.MAX`=50) |
| SystemStatusController | GET | `/api/system/status` | 200 `SystemStatusResponse` (`{overall, checkedAt, subsystems:[{id, label, health, detail, since?, hint?}]}}`) | never errors — a throwing `SubsystemStatusPort` is caught per-provider and reported as that one subsystem's `health:"UNKNOWN"` with the exception's message as `detail`, rather than failing the whole response (docs/plans/done/SYSTEM-STATUS-PLAN.md §4.3, wave S2; UX-DESIGN §7.2's "honest status over optimistic status" doctrine. `overall` is the worst `health` across every subsystem, `DISABLED` excluded from that comparison so an intentionally-off subsystem never reads as a fault; `UNKNOWN` when the provider list is empty or every subsystem is `DISABLED`. Readable by **any authenticated user, not manager-only** — a deliberate call, no secrets are exposed here. Providers are collected as `List<SubsystemStatusPort>` (`vision-platform`), type-collected by Spring — see station/vision-app/MODULE.md's `SystemStatusWiring` for which four beans populate it) |
| OnboardingController | POST | `/api/onboarding/probe` | 200 `VehicleProfileResponse` | 409 `{"detail":"vehicle probing is disabled (vision.onboarding.probe.enabled)"}` when the flag is off (default) — the frozen 409 body, via `NoopVehicleConfigPort` — or an unreachable candidate (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1/O5; pre-registration probe, not scoped/audited — nothing yet exists to scope or audit against, D7) |
| OnboardingController | GET | `/api/assets/{assetId}/profile` | 200 `VehicleProfileResponse` | 404 unknown, out-of-scope, or never-probed asset (a scoped read, all three collapse identically — `VehicleProfileService#latestProfile`) |
| OnboardingController | POST | `/api/assets/{assetId}/probe` | 200 `VehicleProfileResponse` | 403 `!scope.canManage(ownership)`, audited (D8 — probing puts traffic on the aircraft's own link); 404 unknown asset; 409 no probeable device, or probing disabled |
| OnboardingController | POST | `/api/assets/{assetId}/remediate` | 200 `RemediationResultResponse` | 403 `!scope.canManage(ownership)`, audited; 409 armed / arming unknown / probing disabled — every status code comes from `RemediationService`'s own gates, propagated unchanged through `RemediationOrchestrator` (see its own javadoc, `support/`, for why a request that dispatches nothing — e.g. the asset was never probed — answers 200 with every action `UNSUPPORTED` rather than 404) |
| OnboardingController | GET | `/api/assets/{assetId}/usages/{usageId}/passport` | 200 `FlightPassportResponse` | 404 unknown asset, out-of-scope, or `usageId` not belonging to `assetId` — all three collapse identically (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1/O13, `VehicleProfileService#passport`; a distinguishable 404 would leak another asset's flight history to a caller scoped only to this one) |
| OnboardingController | GET | `/api/assets/{assetId}/usages/{usageId}/drift` | 200 `ParameterDriftResponse` (`{drift:[ParameterDriftRow]}`) | 404 same three cases as `.../passport` — an **empty** `drift` array is a correct 200 ("nothing to compare": no previous flight, or a missing snapshot), never a 404 (`VehicleProfileService#driftFromPreviousFlight`) |
| ReadinessController | GET | `/api/assets/{assetId}/readiness` | 200 `ReadinessReportResponse` | 404 unknown or out-of-scope asset (a scoped read) |
| ReadinessController | GET | `/api/fleet/readiness` | 200 `FleetReadinessResponse` (`{assets:[ReadinessRow]}`) | — (thin composition of `AssetService#assets(scope,false)` + one `ReadinessService#evaluate` per asset; never 404s itself) |

`ApiExceptionHandler` (`@RestControllerAdvice`) mapping table (body `{"error","message"}`):

| Exception | Status | code |
|---|---|---|
| `IllegalArgumentException`, `UnsupportedProtocolException` | 400 | `BAD_REQUEST` |
| `NoSuchElementException` | 404 | `NOT_FOUND` |
| `AccessDeniedException` (application, docs/plans/done/U-SCOPE-PLAN.md slice 2) | 403 | `FORBIDDEN` (a scoped **command/grant** against an asset the caller cannot see — deliberately distinct from the 404 a scoped **read** gives, which hides existence, and the 409 a plain `IllegalStateException` gives; thrown by `FlightCommandService#returnToHome`, `AssignmentService#assign`/`unassign` when the asset is out of the acting user's scope, and — docs/plans/done/MAP-REWORK-PLAN.md §3 — by every `MapLayerService`/`MarkService`/`DrawingService` gate: creating a TEAM layer for a group you do not manage, renaming/deleting/re-granting a layer you do not manage, contributing to a layer you may not contribute to, editing a mark you neither created (while `UNVERIFIED`) nor manage, verifying without `canManage`, or promoting without `canManage` on the source. **Note the deliberate 403-vs-404 split the map surface inherits: a scoped *read* hides (an invisible layer/mark/drawing is simply absent from the list), while a *command* against something you cannot see is an honest 403, not a hiding 404** — the same stance `FlightCommandService` already takes) |
| `IllegalStateException` | 409 | `CONFLICT` |
| `HlsUpstreamUnavailableException` | 502 | `BAD_GATEWAY` |
| `ProbeFailedException` | 422 | `UNPROCESSABLE_ENTITY` (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3 — via `HttpStatus.UNPROCESSABLE_CONTENT`, not the deprecated `UNPROCESSABLE_ENTITY` enum constant on this Spring Framework 7 version; same 422, wire `code` string kept at the conventional HTTP-status name) |
| `PayloadTooLargeException` | 413 | `PAYLOAD_TOO_LARGE` (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3 — via `HttpStatus.CONTENT_TOO_LARGE`, same deprecation-avoidance reasoning) |

`SpaResourceConfiguration` — `WebMvcConfigurer` registering `/**` over `classpath:/META-INF/resources/`, `classpath:/static/`, `classpath:/public/` with an SPA-fallback `PathResourceResolver` (serves `index.html` for extension-less, non-`api/`/`actuator/` paths). See Gotchas — nothing populates those classpath locations from this module's own build.

### `com.drones.vision.api.live` — the server-push data plane (docs/plans/done/REALTIME-PLAN.md §4)

The one implementation of all five live-update ports (`FleetLiveUpdatePort`, `TelemetryLiveUpdatePort`, `DetectionLiveUpdatePort`, `MapLiveUpdatePort`, `EventLiveUpdatePort` — vision-domain; the ports the former god-port `LiveUpdatePublisherPort` split into, docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6b), plus the SSE connection/topic/replay/coalescing machinery `LiveController` sits on top of. Everything here is process-local, single-instance (per the plan's own explicit scope — multi-instance fan-out is out of scope until a second backend instance exists). Implementing all five on one class is exactly what an adapter is for — every context's application code still only ever holds the one narrow port it actually calls; only this driving adapter needs to depend on all of them at once.

- **`LiveUpdateRegistry`** (`@Component`, `@ConditionalOnProperty(vision.live.enabled, default true)`) — implements `FleetLiveUpdatePort`, `TelemetryLiveUpdatePort`, `DetectionLiveUpdatePort`, `MapLiveUpdatePort`, `EventLiveUpdatePort` and owns every connection.
  - `LiveUpdateRegistry(ObjectProvider<AssetService> assetService, ObjectProvider<DeviceService> deviceService, ObjectProvider<StreamService> streamService, StreamPublisherPort streamPublisherPort, ObjectProvider<DetectionEventRepositoryPort> detectionEventRepositoryPort, VisionApiProperties.Live live)` — production ctor (`@Autowired`, disambiguating it from the other three overloads below, since Spring cannot pick between multiple candidate constructors on its own; backend follow-up batch grew this from a 1-arg ctor to 5, then docs/plans/done/SCALE-100-PLAN.md §5 S7 added `live`, a settings bundle rather than a sixth collaborator — see below). **`assetService`/`deviceService`/`streamService`/`detectionEventRepositoryPort` are each an `ObjectProvider`, not the plain type, to break a genuine circular bean dependency**: `DefaultAssetService`/`DefaultDeviceService` depend on `AuditTrailPort`, which (when `vision.live.enabled=true`) `vision-app` wraps in `LiveUpdateAuditTrail`, which depends on `FleetLiveUpdatePort`, which resolves to this class; `DefaultStreamService` depends on `DetectionLiveUpdatePort` directly; and the `detectionEventRepositoryPort` bean is itself wrapped in `LiveUpdateDetectionEventRepository`, which depends on `DetectionLiveUpdatePort` too — the four ports are distinct interfaces now (W1.6b), but every one of them still resolves to this same class, so a plain constructor-injected dependency on any of the four here would still deadlock Spring's bean graph at startup; deferring the actual lookup to `freshFleetEnvelope()`/`freshDevicesEnvelope()`/`seedDetectionEventsIfEmpty()` (only ever called once the whole context has finished starting) breaks every one of these cycles. `streamPublisherPort` carries no such risk (neither `MediamtxStreamPublisher` nor `NoopStreamPublisher` depends on any of this class's five ports), so it stays a plain constructor parameter, used only to resolve `viewUrl`/`whepUrl` for the `devices` topic's active-stream list, mirroring `StreamController#list`. See station/vision-app/MODULE.md's own Gotcha for the full circular-dependency chain and the exact `UnsatisfiedDependencyException` this pattern resolves. `live` is supplied by `PublishWiring#liveSettings` (vision-app), mapped from `VisionApiProperties#live()`.
  - Three more constructor overloads, all delegating to the one above (or to the full 7-arg constructor below) with a default: a **public 5-collaborator legacy overload** (no `live` — defaults to `VisionApiProperties.Live.defaults()`; kept because `LiveControllerTest`/`LiveMapScopingTest`, in package `com.drones.vision.api.controller`, construct this class directly and can only reach a `public` constructor), the **package-private 6-arg test seam** (`..., ScheduledExecutorService`, unchanged signature — defaults `live` too, kept for `LiveUpdateRegistryTest`'s `registry()` helper), and the **package-private 7-arg full constructor** (`..., VisionApiProperties.Live live, ScheduledExecutorService scheduler`) — the one place fields are actually assigned and `scheduler`'s three fixed-rate tasks are scheduled; every other overload delegates here.
  - `SseEmitter connect(String topicsParam, Long lastEventId, UserId userId, Predicate<String> mapVisibility, Predicate<AssetId> assetVisibility)` (public — called cross-package from `LiveController`; **`userId`/`assetVisibility` added docs/plans/done/LIVE-SCOPE-PLAN.md §2, W3**, see "Scoped SSE delivery" below) — registers a new connection (always subscribed to the implicit `fleet`/`event`/`devices`/`detection-events`/`marks` topics — the last three added by the backend follow-up batch and docs/plans/done/TACTICAL-MARKS-PLAN.md M4 respectively, see `LiveTopicKind` below — plus whatever `topicsParam` parses to, **already pre-filtered by the caller (`LiveController` via `LiveAssetAccess#filterTopicsParam`) down to topics the caller may currently see** — this method trusts that filtering rather than repeating it), binds the connection to `userId` (so a later `updateTopics` can refuse a caller who does not own it), then **synchronously, on the calling thread**, sends the `connection` handshake event followed by a snapshot-or-resume burst per subscribed topic — re-filtered per envelope by `mapVisibility`/`assetVisibility` exactly as a live broadcast is — before returning the emitter. Deliberately synchronous (unlike every `publish*` method below) — a one-time connect burst is cheap and bounded, and `ResponseBodyEmitter`'s own early-send buffering (sends before the framework attaches its handler are queued internally, not dropped or rejected) makes this both correct and exactly what makes `LiveControllerTest` deterministic without polling for the *initial* burst.
  - `LiveSubscriptionResponse updateTopics(String connectionId, UpdateLiveTopicsRequest, UserId callerUserId)` (public; **`callerUserId` added W3**) — adds/removes topics on an already-open connection; a newly-added topic immediately gets its own snapshot burst (whatever's currently buffered, no resume concept since it's new to this connection); `fleet`/`event`/`devices`/`detection-events` are never actually removed even if named in `request.remove()`. Throws `NoSuchElementException` for an unknown `connectionId`, **or one that exists but belongs to a different user** (404 via `ApiExceptionHandler` either way — "existence hides itself," the same idiom `StreamAccess` uses, so a caller who merely learned another connection's id cannot distinguish the two cases). `request.add()` is already pre-filtered by `LiveController` via `LiveAssetAccess#filterAdditions` down to topics `callerUserId` may currently see — this method does not re-check it, only the ownership of `connectionId` itself.
  - `publishEvent(Event)`/`publishDetectionEvent(DetectionEvent)` — dispatched onto the shared scheduler (`Executor#execute`, fire-and-forget from the caller's perspective), not coalesced (both are comparatively rare): compute/serialize the envelope, append to the topic's `LiveRingBuffer`, broadcast to every subscribed connection. `publishDetectionEvent` backs the `detection-events` topic — see below.
  - `publishFleetChanged()` — refreshes *both* the `fleet` (asset-centric) and `devices` (device-list + active-stream-list) buffers/broadcasts in the one dispatch — every seam that already called it (asset/device CRUD via `LiveUpdateAuditTrail`, stream start/stop via `LiveUpdateEventPublisher`, both `vision-app`) is exactly the set that should refresh `devices` too, so the existing no-payload port method was extended rather than adding a second, near-duplicate one. **As of docs/plans/done/SCALE-100-PLAN.md §5 S5, coalesced leading+trailing**, the same treatment telemetry/detections already get: a call past the current window's close (`fleetRecomputeWindowUntilNanos`, an `AtomicLong` nanoTime deadline) wins a compare-and-set and dispatches the recompute immediately — so a lone write is still delivered with no added latency — while any call landing inside an already-open window only sets `fleetChangedDuringWindow` (an `AtomicBoolean`); `flushPending()` checks that flag on every tick and performs exactly one trailing recompute if it is set, so the window's true final state is always delivered (CLAUDE.md rule 9), never silently dropped. A sustained fleet/device write storm therefore recomputes at most once per `coalesceMillis` (default 150ms, `vision.api.live.coalesce` as of docs/plans/done/SCALE-100-PLAN.md §5 S7 — an instance field derived from `VisionApiProperties.Live` in the constructor, not a `static final` constant) — `fleetCoalesceWindowNanos` reuses it rather than a second, independently-tunable field — instead of once per write. The recompute body itself (`freshFleetEnvelope()`+`freshDevicesEnvelope()`+append+broadcast, both topics) is factored into a private `recomputeFleetAndDevices()`, shared by both the leading dispatch and `flushPending()`'s trailing catch-up.
  - `publishTelemetryAppended(AssetId, Telemetry)`/`publishDetections(AssetId, DetectionResult)` — the genuinely hot-path methods (called once per appended sample / once per completed inference): each just enqueues into a small pending map (a `ConcurrentLinkedQueue<Telemetry>` per asset for telemetry — every sample kept; a plain `ConcurrentHashMap<AssetId, DetectionResult>` for detections — a later `put` simply overwrites, giving "latest-frame-only" for free) and returns immediately, no I/O, no synchronization beyond the concurrent map's own.
  - `void flushPending()` (package-private) — drains both pending maps roughly every `coalesceMillis`ms (default 150, the shared scheduler's own repeating task calls this; a pure unit test calls it directly instead of waiting): one coalesced `List<TelemetrySampleResponse>` envelope per asset with anything pending, one latest-only `DetectionResultResponse` envelope per asset with anything pending — each appended to its topic's buffer and broadcast.
  - `void heartbeatAll()` (package-private, same test-seam reasoning) — dispatches an SSE **comment** line (`SseEmitter.event().comment(...)`, never reaches `EventSource.onmessage`) to every connection roughly every `heartbeatMillis`ms (default 15,000, `vision.api.live.heartbeat`) so proxies don't kill an idle stream — as of docs/plans/done/SCALE-100-PLAN.md §5 S2, each connection's heartbeat write goes through the same `dispatchWrite`/`connectionWriteExecutor` path `broadcast` uses (see "Connection writes" below), not a direct blocking `connection.heartbeat()` call.
  - `List<LiveEnvelopeResponse> replayFor(LiveTopic, Long lastEventId)` / `LiveRingBuffer bufferFor(LiveTopic)` (package-private, purely a test seam for pure, `SseEmitter`-free unit tests in this same package) — the resume-vs-snapshot decision: if `lastEventId` is given and the topic's buffer `canResumeFrom` it (no gap), replay only what's newer; otherwise fall back to "snapshot" via the private `seedIfEmpty` — which, for **`fleet`, `devices`, and `detection-events` specifically** (the latter two added by the backend follow-up batch), means computing one real, live query first if the buffer is still empty (nothing has ever changed since this process started, so there's nothing better buffered yet): `AssetService#assets()` (fleet), `DeviceService#devices()` + `StreamService#streams()` (devices), `DetectionEventRepositoryPort#findRecent` — the exact same source `EventController` reads for `GET /api/events` (detection-events, seeded oldest-first since the port returns newest-first) — every other topic's "snapshot" is honestly just "whatever this process has buffered since it started" (a documented, deliberate limitation: a viewer's first-ever subscription to an asset's `telemetry`/`detections` topic sees nothing until the next sample/result arrives, even if that asset has been streaming the whole time this process has been up; `event` is the one always-on topic that stays in this "honestly limited" bucket too, since `EventPublisherPort` has no read side to query).
  - **Coalescing runs once per topic, shared across every subscribed connection — not independently per connection.** The plan's own "batch...per connection" framing is satisfied in effect (delivery is still batched to roughly one envelope per `COALESCE_MILLIS`ms per topic) while keeping exactly one canonical, resumable sequence number per topic; a genuinely independent per-connection coalescing buffer would have made `Last-Event-ID` resume ambiguous the moment two connections shared a topic. Documented here as a deliberate simplification, not an oversight.
  - **Connection writes (docs/plans/done/SCALE-100-PLAN.md §5 S2)** — sequencing (`sequencer`), coalescing, and the broadcast fan-out decision all still run on the single `scheduler` thread (`live-update-dispatcher`), exactly as before; what moved off it is the actual per-connection write. `private String serialize(LiveEnvelopeResponse)` (`JsonMapper`, `tools.jackson.databind.json`) encodes an envelope to JSON **exactly once** per `broadcast` call — the prior design re-serialized the same object once per subscribed connection; a `RuntimeException` from serialization is caught, logged (`System.Logger`), and treated as "nothing valid to send" rather than propagated, since letting it escape would have permanently killed `flushPending`'s own `scheduleAtFixedRate` tick. `broadcast`/`heartbeatAll` then dispatch one write per matching connection via `LiveConnection#enqueueSend`/`enqueueHeartbeat` onto a field, `private final ExecutorService connectionWriteExecutor = Executors.newVirtualThreadPerTaskExecutor()` — one virtual thread per write, unconditionally instantiated rather than constructor-injected (this class's production constructor is already at the five-parameter ceiling before the one settings-bundle exception docs/plans/done/SCALE-100-PLAN.md §5 S7 added; nothing about this field needs the deterministic single-step test control `scheduler`'s injectable-seam constructor exists for, only real concurrency to exercise). `private void dispatchWrite(LiveConnection, CompletableFuture<Void> write)` bounds each queued write with `write.orTimeout(connectionWriteTimeoutMillis, MILLISECONDS).exceptionally(cause -> { unregister(...); return null; })` — `connectionWriteTimeoutMillis` (default 3,000ms) is now `vision.api.live.send-timeout` (docs/plans/done/SCALE-100-PLAN.md §5 S7 finishes what S2 introduced as `CONNECTION_WRITE_TIMEOUT_MILLIS`; two `static final` compatibility constants of the same name/`DETECTION_EVENT_BUFFER_CAPACITY` remain, derived from `VisionApiProperties.Live.defaults()` rather than a second literal, kept only because `LiveUpdateRegistryTest` needs a compile-time value to reference). A connection whose write is still pending past the timeout — whether its own write stalled or an earlier write still ahead of it in its own per-connection order is stuck — is unregistered, the same outcome a direct `IOException` always produced. **`vision.api.live.dispatch-threads` was surveyed for S7 and deliberately not added**: `connectionWriteExecutor` is `Executors.newVirtualThreadPerTaskExecutor()`, which has no pool-size/thread-count concept to configure at all — there is no bound a "thread count" setting could mean here, so offering the key would mean reading, storing, and silently ignoring it. The key stays a documented non-decision (see the field's own javadoc): it becomes relevant only if this executor is ever swapped for a bounded platform `ThreadPoolExecutor`, and that swap — not this wave — is what should introduce it.
  - **Per-asset buffer eviction (docs/plans/done/SCALE-100-PLAN.md §5 S2 item 4)** — `telemetryBuffers`/`detectionBuffers` (`ConcurrentHashMap<AssetId, LiveRingBuffer>`) only ever grow via `bufferFor`'s `computeIfAbsent`; nothing previously removed an entry once every connection watching that asset disconnected, so a fleet that has ever had N distinct assets watched kept N buffers for the life of the process. `void evictUnusedAssetBuffers()` (package-private, same test-seam reasoning as `flushPending`/`heartbeatAll`) sweeps both maps, retaining only asset ids at least one open connection currently subscribes to (`subscribedAssetIds(LiveTopicKind)`, a stream over every connection's topic set); the production constructor schedules it via `scheduler.scheduleAtFixedRate` every `bufferEvictionMillis` (default 60,000ms, `vision.api.live.buffer-eviction` as of docs/plans/done/SCALE-100-PLAN.md §5 S7).
  - `String register(SseEmitter, Set<LiveTopic>, UserId userId, Predicate<String> mapVisibility, Predicate<AssetId> assetVisibility)` (package-private, new S2 test seam; **`userId`/`assetVisibility` added W3**) — registers a connection around an already-constructed `SseEmitter` (typically a test double that records or deliberately blocks on `send`), bypassing `connect`'s handshake/snapshot burst entirely. Exists because S2's new concurrency behavior (a stuck connection not blocking others, the write timeout, per-connection ordering under concurrent dispatch) can only be observed by inspecting what a connection's emitter actually received and when — `connect()` always constructs its own bare `new SseEmitter(0L)`, giving a test no hook to intercept writes with.
- **`LiveConnection`** (package-private) — one open connection's `SseEmitter`, the `UserId` it belongs to (`ownerUserId()`, W3 — a plain identity value, used only by `updateTopics`'s ownership check, never a live handle back to the request), and its live, mutable `Set<LiveTopic>` (a `ConcurrentHashMap.newKeySet()`, safe to read/mutate from the connecting thread, a later `PATCH` thread, and the shared broadcast thread all at once). As of docs/plans/done/SCALE-100-PLAN.md §5 S2: `sendLock` is a `ReentrantLock`, not `synchronized` — a `synchronized` block held across a blocking I/O call pins a virtual thread's carrier for the whole blocked duration regardless of contention (the JDK 21 pinning caveat this repo is on; fixed only in JDK 24+/JEP 491), which is exactly the failure mode this wave removes; `ReentrantLock` parks instead. `send(LiveEnvelopeResponse)` was replaced by `send(long seq, String json)` — the caller (`LiveUpdateRegistry#serialize`) now serializes once and passes the same JSON `String` to every connection, rather than each connection re-encoding the same envelope. New `enqueueSend(long seq, String json, Executor)`/`enqueueHeartbeat(Executor)` queue a write onto `writeChain`, a per-connection `AtomicReference<CompletableFuture<Void>>` chain (`previous.thenRunAsync(write, executor)`) — **the ordering guarantee a lock alone cannot give** once dispatch runs on a virtual-thread-per-task executor with no shared, ordered work queue: two writes submitted A-then-B could otherwise race to acquire `sendLock` B-then-A. Chaining means a later write cannot even *start* until the earlier one has finished, so one connection's own writes stay in submission order regardless of which virtual thread happens to run first. `sendConnected` (the `connect()` handshake burst) is deliberately **not** chained — it still runs synchronously, once, before the connection is reachable by any concurrent dispatch for a topic it has not yet subscribed to. `mayReceive(LiveEnvelopeResponse)` (see "Scoped SSE delivery" below) now gates on **two** independent predicates, keyed off what the envelope carries rather than which topic it arrived on: a `MapEventPayload` against `mapVisibility`'s `layerId`; anything else with a non-null `assetId()` (`telemetry`/`detections`/`geo`) against `assetVisibility` (W3). Everything else (`fleet`/`event`/`devices`/`detection-events`) passes unconditionally.
- **`LiveTopic`** (package-private record: `kind: LiveTopicKind`, `assetId: AssetId` nullable) — `FLEET`/`EVENT`/`DEVICES`/`DETECTION_EVENTS`/`MAP` (the last three added by the backend follow-up batch and docs/plans/done/MAP-REWORK-PLAN.md §4.3 respectively; `MAP` **replaces** the `MARKS` topic docs/plans/done/TACTICAL-MARKS-PLAN.md M4 added) are shared constants (`assetId=null`); `telemetry(AssetId)`/`detections(AssetId)` build the per-asset ones. `wire()` renders e.g. `"telemetry:<assetId>"`; `parse(String)`/`parseTopicsParam(String)` (comma-separated) do the reverse, throwing `IllegalArgumentException` for an unknown kind or a missing/malformed asset id (→ 400 via `ApiExceptionHandler`, same as every other id-parsing spot in this module) — `"fleet"`/`"event"`/`"devices"`/`"detection-events"`/`"map"` with no asset id parse as harmless, redundant aliases for the already-implicit topics of the same kind. **`"marks"` no longer parses at all** and is now a 400: the topic is gone, and failing loudly beats silently subscribing an un-migrated client to nothing.
- **`LiveTopicKind`** (package-private enum, current 8 constants: `FLEET`, `EVENT`, `TELEMETRY`, `DETECTIONS`, `DEVICES`, `DETECTION_EVENTS`, `MAP`, `GEO` — `DEVICES`/`DETECTION_EVENTS` added by the backend follow-up batch extending the R-c channel for the fleet/warehouse and events UIs; `MAP` added/replacing the prior `MARKS` docs/plans/done/MAP-REWORK-PLAN.md §4.3/docs/plans/done/TACTICAL-MARKS-PLAN.md M4; `GEO` added docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.4, D11 — visual geolocation's per-asset corrected-track topic, exactly `TELEMETRY`'s shape/scoping: opt-in, ring-buffer capacity 1 since only the freshest fix matters, replay after reconnect comes from `GET /api/geo/corrections` rather than this buffer) — each constant now carries its own explicit wire string (rather than deriving it from `name()`) so `DETECTION_EVENTS` can use the hyphenated `"detection-events"` (matching `GET /api/events`'s own naming) rather than the underscore a lower-cased enum name would produce; `wire()` doubles as both the topic-string prefix and the `LiveEnvelopeResponse#type()` value for envelopes of that kind, since the plan deliberately uses the same vocabulary for both. **`DEVICES`**: the device-list + active-stream-list snapshot `FleetStore` (vision-web) otherwise polls via `GET /api/devices`+`GET /api/streams` every 5s — deliberately its *own* topic, not folded into `fleet`, since `fleet`'s payload is asset-centric (`AssetSummaryResponse`) and shares nothing with `FleetStore`'s domain (raw `Device`/`ActiveStream`); both lists travel in one envelope (`DevicesSnapshotResponse`) under the channel's one shared `seq`, so a viewer can never see a device list and an active-stream list snapshotted at different moments. **`DETECTION_EVENTS`**: the debounced `DetectionEvent` occurrences (open/advance/close) `GET /api/events` serves — deliberately its own topic, not folded into `event` (the unrelated generic domain `Event`), reusing `DetectionEventResponse` (the exact DTO `EventController` already returns) so `EventsStore` (vision-web) can convert trivially. **`MAP`** (docs/plans/done/MAP-REWORK-PLAN.md §4.3, **replacing `MARKS` outright** — removed, not deprecated: the SPA is the only client and migrates in Wave E): the whole Common Operational Picture — marks, drawings *and* layers — as one always-on topic carrying `entity` (`mark`/`drawing`/`layer`) and `action` (`created`/`updated`/`cleared`/`deleted`) as fields inside `MapEventPayload`, rather than twelve topic kinds, mirroring exactly how `DETECTION_EVENTS` carries OPEN/CLOSED in one topic instead of two. **Along with per-asset `TELEMETRY`/`DETECTIONS`/`GEO` (W3), it is one of the topics whose delivery is filtered per connection** — see "Scoped SSE delivery" below. **Deliberately has no live-query seed** (unlike `FLEET`/`DEVICES`/`DETECTION_EVENTS`): seeding it would need two more `ObjectProvider` constructor parameters on `LiveUpdateRegistry` (past the five-parameter ceiling it is already at) **and** a shared seeded snapshot could not be re-scoped per recipient anyway. A viewer's first connection relies on its own `GET /api/map/layers`+`/marks`+`/drawings` reads, each already scoped correctly, before layering live deltas on top.
- **Scoped SSE delivery — `map`, and (W3) per-asset `telemetry`/`detections`/`geo`** (docs/plans/done/MAP-REWORK-PLAN.md §4.3; docs/plans/done/LIVE-SCOPE-PLAN.md §2, W3 — the security-critical half of each). Every other topic broadcasts one envelope to every subscribed connection; these do not, because visibility is a property of the data. Deliberately split so the registry never resolves an identity itself:
  - **`MapVisibility`** (`@Component`, same `vision.live.enabled` gate as the registry) — one constructor argument, `MapLayerService`. `deliveryPredicate(Viewer)` returns a `Predicate<String>` over an event's `layerId`; `canView` answers it from a per-`Viewer` TTL cache of visible layer ids (`Viewer` is a record, so a sound map key). **TTL `10_000`ms**, a third of the plan's "stale-grant visibility beyond 30s is a bug" bar. A plain TTL was chosen over invalidate-on-layer-event because a TTL bounds *every* staleness source — a revoked group membership or a changed role emits no map event at all — and because event-driven invalidation would have needed another collaborator on `LiveUpdateRegistry`, already at its five-parameter ceiling. **Negative answers are re-checked, positives are not**: a cached "yes" can only be stale toward over-sharing (bounded by the TTL), but a cached "no" is what a user notices immediately (create a layer, and its own event would be filtered out of your own stream), so a miss triggers one re-resolve, rate-limited to one per `NEGATIVE_TTL_MILLIS`=1000ms per viewer and anchored to the last *miss-triggered* refresh — not to when the entry was filled, which was a real bug the tests caught (an entry filled 0ms ago would otherwise black out a brand-new layer for a full second).
  - **`LiveAssetAccess`** (`com.drones.vision.api.live`, `@Component`, same `vision.live.enabled` gate; **new, W3**) — the per-asset twin of `MapVisibility`, closing the hole `MapVisibility` never covered: per-asset `telemetry:<assetId>`/`detections:<assetId>`/`geo:<assetId>` topics were unauthorized at subscribe time (any authenticated caller could subscribe to any asset's live feed by guessing an id), `PATCH .../topics` never checked connection ownership or authorized a newly-added topic, and a resume replayed the shared buffer with no re-check at all. Reuses W2's exact policy — `StreamAccess.visibleAsset(AssetId, VisibilityScope)` (a small addition to that class this wave needed: the same `includes`-gated visibility check `visible(DeviceId)` already ran, exposed to take an explicit `VisibilityScope` rather than reading `CurrentUser.scope()`, since this class's per-delivery re-check runs on the registry's shared dispatcher thread for a connection some other request opened, not the caller's own request thread) — rather than inventing a second one ("one seam is better than two," per the task brief). Constructor: `StreamAccess`, `ScopeResolver` (vision-identity, re-derives a fresh `VisibilityScope` from a plain `User`), `UserRepositoryPort` (resolves the `User` a cached check's `UserId` names), `@Value("${vision.live.asset-access-ttl-ms:5000}") long ttlMillis`. Three methods:
    - `filterTopicsParam(UserId, String topicsParam)` / `filterAdditions(UserId, UpdateLiveTopicsRequest)` — called **directly** by `LiveController` (see below) before a fresh connect's `topics` query param / a `PATCH`'s `add` list ever reaches the registry, silently dropping any per-asset entry the caller may not currently see. This is subscribe-time authorization: a caller never even subscribes to an asset outside their scope.
    - `deliveryPredicate(UserId)` — a `Predicate<AssetId>` handed to `LiveUpdateRegistry#connect`, stored on the `LiveConnection` and consulted by `mayReceive` on **every broadcast and every snapshot/resume replay** — the same treatment `MapVisibility`'s predicate gets for `map`. This is what makes authorization hold for the life of the connection, not only the moment a topic was added: a scope can change mid-connection (an assignment revoked) with no `PATCH` to re-trigger the subscribe-time check, so delivery re-checks independently, bounded by the cache TTL.
    - Both authorization points share one `ConcurrentHashMap<(UserId, AssetId), (visible, resolvedAt)>` cache (`canView`, package-private) — re-deriving a scope is a repository read (or two); doing it once per envelope per connection would turn every coalesced telemetry/detections tick into a DB hit per open connection. **TTL `vision.live.asset-access-ttl-ms` (`application.yaml`), default `5000`ms** — deliberately shorter than `MapVisibility`'s 10s: this cache bounds exposure of an aircraft's live position, a narrower and more sensitive fact than a map layer's mere existence. Unlike `MapVisibility`, both a cached "yes" and a cached "no" are trusted for the full TTL (no negative-recheck asymmetry) — a false negative here is "your own asset's telemetry pauses for up to `ttlMillis` after being newly assigned," not "your own action looks like it silently failed," a materially lower-stakes miss than `MapVisibility`'s "you can't see the layer you just created." Same sweep-on-`SWEEP_THRESHOLD`=256 bound as `MapVisibility`. A user resolved to `Optional.empty()` (deleted mid-connection) sees nothing — fail-closed, same stance as `StreamAccess`'s own unresolvable-target fallback.
    - **Why this class's name ends in "Access," and why `filterTopicsParam`/`filterAdditions` are called directly rather than only stored as a predicate:** `EndpointAuthorizationTest`'s (vision-app) static call-graph guard recurses only into classes whose owner starts with `com.drones.vision.api`, looking for a call to `CurrentUser.scope()`/`.viewer()` or to any class whose name ends `"Access"`. A check hidden behind a stored `java.util.function.Predicate` field/closure is **invisible** to it: `Predicate.test()`'s owner is `java.util.function.Predicate`, not `com.drones.vision.api.*`, so the BFS recursion stops there. Relying solely on `deliveryPredicate`'s per-delivery re-check (mirroring `MapVisibility` exactly) would have satisfied every runtime test but silently failed this build-time guard for `LiveController#updateTopics`. `LiveController` therefore calls `assetAccess.filterTopicsParam(...)`/`assetAccess.filterAdditions(...)` **directly, in the handler method body** — genuinely satisfying the guard, not merely appearing to — with `deliveryPredicate` layered on top only for the "revoked mid-connection, no PATCH in between" case the subscribe-time filter cannot reach.
  - **`LiveController`** captures the connecting request's `CurrentUser#viewer()`/`#userId()` **once, at connect**, turning them into `mapVisibility.deliveryPredicate(viewer)`/`assetAccess.deliveryPredicate(userId)`, and filters `topics` through `assetAccess.filterTopicsParam(userId, topics)` before ever calling `registry.connect(topics, lastEventId, userId, mapVisibility, assetVisibility)`. `updateTopics` mirrors it: filters `request.add()` via `assetAccess.filterAdditions`, then passes `userId` through to `registry.updateTopics` as the ownership check. This is the only place in the SSE stack that touches identity — the constructor is `registry`, `MapVisibility`, `LiveAssetAccess`, `CurrentUser` (W3 added the third).
  - **`LiveConnection`** stores both predicates plus the connecting `UserId`, and exposes `mayReceive(LiveEnvelopeResponse)`/`ownerUserId()`, which the registry consults on **every broadcast and every snapshot/resume replay**, and on every `updateTopics` call, respectively. `mayReceive` keys off what the envelope actually carries: a `MapEventPayload`'s own `layerId` against `mapVisibility`; anything else with a non-null `assetId()` against `assetVisibility`. Keying off envelope content rather than topic is what lets one **unfiltered, shared** ring buffer keep one canonical resumable `seq` while still serving viewers at different access levels — no parallel per-envelope bookkeeping to keep in step, and a `Last-Event-ID` resume is automatically re-filtered against what the viewer may see *now*: a resumed connection runs through `LiveController#connect` exactly like a fresh one, so a revoked asset's topic is stripped from the resumed topic set the same way a never-visible one is, and its buffered history is never replayed — closing W3's "replay must apply the same authorization as live delivery" without a second, parallel replay-specific mechanism.
- **`LiveRingBuffer`** (package-private) — a bounded, sequence-numbered per-topic backlog of `LiveEnvelopeResponse`s, in one of two retention modes selected at construction (`capacity: int, collapseToLatest: boolean`):
  - **FIFO** (`collapseToLatest=false`, `telemetry`/`event`/`detection-events` topics) — every appended envelope retained up to `capacity`, oldest evicted first; each one is individually meaningful.
  - **Latest-only** (`collapseToLatest=true`, `fleet`/`detections`/`devices` topics, `capacity=1`) — `append` replaces the single retained entry outright (docs/plans/done/REALTIME-PLAN.md §4 item 3: "detections emit latest-frame-only", applied to `fleet`/`devices` too by the same logic — an older snapshot has no value once a newer one lands).
  - `since(long sinceSeq)`/`snapshot()`/`isEmpty()` are the read side; `canResumeFrom(long sinceSeq)` is the gap check — **tracks whether this buffer has ever actually dropped anything (`everDropped`), rather than just comparing `sinceSeq` against the oldest retained entry's `seq`.** This matters because `seq` is a single counter shared across *every* topic (so an SSE connection's one `Last-Event-ID` resumes correctly regardless of which topics interleaved which envelopes) — a buffer's own oldest retained entry can legitimately have a much higher `seq` than a caller's `sinceSeq` simply because *other* topics were busy in between, not because this topic ever lost anything; a buffer that has never evicted/replaced anything is resumable from *any* `sinceSeq`, however old it looks. `LiveRingBufferTest` (pure, no Spring) covers this distinction directly. **`boolean everDropped()`** (package-private, docs/plans/done/SYSTEM-STATUS-PLAN.md §4.2, wave S2) — a plain read of the existing flag; backs `LiveUpdateRegistry#anyBufferEverDropped` below.
- **`LiveUpdateRegistry#connectionCount()`/`#anyBufferEverDropped()`** (package-private, wave S2) — `connectionCount()` is `connections.size()`; `anyBufferEverDropped()` ORs `everDropped()` (above) across all five fixed-topic buffers plus every per-asset telemetry/detections buffer. Backs `LiveUpdateStatusProvider`, below.
- **`LiveUpdateStatusProvider`** (`@Component`, `@ConditionalOnProperty(vision.live.enabled, default true)`, implements `vision-platform`'s `SubsystemStatusPort`, **new**, wave S2) — `live-updates`'s health self-report for `GET /api/system/status`. Constructor takes `@Qualifier("liveUpdateRegistry") LiveUpdateRegistry` — the qualifier is load-bearing, not decorative: `vision-app`'s `ApplicationServiceWiring` exposes this same `LiveUpdateRegistry` singleton under five more `@Bean` methods, each returning it cast to a narrower port interface (`fleetLiveUpdatePort`/`telemetryLiveUpdatePort`/`detectionLiveUpdatePort`/`mapLiveUpdatePort`/`eventLiveUpdatePort`); Spring's type-based autowiring sees the *runtime* type behind all six bean names as `LiveUpdateRegistry` and a plain by-type injection here finds six candidates, not one — the qualifier pins it to the bean literally named `liveUpdateRegistry` (this class's own `@Component` default name). No `Health.DOWN` case exists — this subsystem dispatches purely in-process, so the bean's mere presence and ability to answer already proves it's alive — but it **can** self-report `Health.DEGRADED`: if `anyBufferEverDropped()` is true, a slow SSE consumer missed at least one update, and UX-DESIGN §7.2's "honest status" doctrine says that must surface rather than be silently absorbed; otherwise `Health.OK`, `detail` states the open connection count either way.
- **`LiveUpdateStatusDisabledProvider`** (`@Component`, `@ConditionalOnProperty(vision.live.enabled, havingValue="false")`, wave S2) — the companion bean for the opposite condition, reports `Health.DISABLED`. Same repeated-condition pattern the rest of this wave uses (docs/plans/active/CV-RECONNECT-PLAN.md §3.3) rather than `@ConditionalOnBean`.

### `/ws/manual-control` — the RC relay WebSocket transport (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §4, R4)

A raw (non-STOMP) servlet `WebSocketHandler` bridging the browser's Gamepad-sourced stick/switch
stream to `ManualControlService` (vision-application, R2) — the streaming, ack-less, watchdog-supervised
counterpart to `FlightCommandController`'s request→ack one-shots. `ManualControlHandshakeInterceptor`/
`ManualControlWebSocketHandler` live in `com.drones.vision.api.ws` (docs/plans/active/LAYERING-REFACTOR-PLAN.md
§3/§7 row B); `ManualControlWebSocketConfig` stays in `config/` alongside `SpaResourceConfiguration`.

- **`ManualControlWebSocketConfig`** (`config/`, `@Configuration @EnableWebSocket`, `WebSocketConfigurer`) registers `ManualControlWebSocketHandler` at `/ws/manual-control` with `ManualControlHandshakeInterceptor` attached. No `setAllowedOrigins`/`setAllowedOriginPatterns` call — Spring's own same-origin default (`OriginHandshakeInterceptor`) applies, matching every other endpoint in this app (no CORS configuration anywhere, session-cookie auth is inherently same-origin).
- **`ManualControlHandshakeInterceptor`** (`HandshakeInterceptor`) resolves the acting principal via the same singleton `CurrentUser` bean every REST controller uses — `userId()`/`scope()` — and stashes both into the WebSocket session's `attributes` map (the same map `WebSocketSession#getAttributes()` returns for the connection's lifetime) under package-private keys `ATTR_USER_ID`/`ATTR_SCOPE`. Since the upgrade `GET` passes through the same `SecurityFilterChain` as `/api/**` before this interceptor ever runs, `CurrentUser` already resolves the right identity — the fixed dev principal when `vision.auth.enabled=false`, the session's authenticated principal when `true`. A defensive `try/catch` answers `401` (`response.setStatusCode(HttpStatus.UNAUTHORIZED)`, `return false`) if `CurrentUser` cannot resolve an identity at all — never expected in practice once `vision-app`'s secured chain adds `/ws/**` to `authenticated()` (see station/vision-app/MODULE.md), since an unauthenticated request is already rejected before this code runs.
- **`ManualControlWebSocketHandler`** (`@Component`, `TextWebSocketHandler`) parses/emits the frozen §4 JSON text-frame protocol, discriminated by `type`. The `engaged` frame's `rateHz` is **read live** from `ManualControlSession#rateHz()` (docs/plans/done/RC-LATENCY-PLAN.md §2 C) — this module still may not depend on `adapter-mavlink`, so the number rides the port seam the adapter already stamps it on; the hand-mirrored `ManualControlEngagedFrame.DEFAULT_RATE_HZ` constant is **gone**. Note its meaning: a keepalive *floor* (how often an unchanged stick must report), not a cap on how fast a change may be sent. Per-connection state (`ConnectionState`: a `sendLock` plus a `volatile ManualControlSession`) is tracked in a `Map<String, ConnectionState>` keyed by `WebSocketSession#getId()` — the handler bean itself is a Spring singleton shared across every connection, so nothing connection-specific lives on the handler's own fields.
  - **`engage`** — resolves `AssetId`/the handshake-stashed `UserId`+`VisibilityScope`, calls `manualControlService.engage(...)` with a `WatchdogListener` lambda that clears this connection's session and pushes a `watchdog` frame on trip. Success → `engaged {assetId, rateHz, vehicleKind, profileCode, profileName, channelMap}` — the profile fields are read from `ManualControlSession#controlProfile()`, so the client's control surface is shaped by the vehicle the server is actually hearing (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P2/P13; additive to §4's frozen frame, nothing removed or renamed). A second `engage` on the *same* connection while already active → `denied ALREADY_ENGAGED` without touching the service again. Exception mapping (best-effort, message-text based — `ManualControlService#engage` throws exactly `AccessDeniedException`/`IllegalStateException`, the latter for three causes the application layer does not distinguish by type): `AccessDeniedException` → `OUT_OF_SCOPE`; an `IllegalStateException` message containing `"already active"` (the service's own cross-*connection* one-session-per-singleton-handle guard — a *different* connection engaging while another's session is still live) → `ALREADY_ENGAGED`; containing `"does not support device"` (the port's own defensive not-supported guard, should never fire in practice since `supports()` is pre-filtered) → `UNSUPPORTED`; everything else (no commandable device found, or a found device that is not currently reachable — `"you cannot command what you cannot hear"`) → `NOT_COMMANDABLE`, the closest of the four frozen codes for lack of a dedicated "unreachable" one. A missing/malformed `assetId`, malformed JSON, or an unrecognized frame `type` are handled defensively with additional non-frozen `denied` codes (`BAD_REQUEST`/`MALFORMED`/`UNKNOWN_TYPE`) — deliberately outside the frozen `OUT_OF_SCOPE|NOT_COMMANDABLE|UNSUPPORTED|ALREADY_ENGAGED` enum (see `ManualControlDeniedFrame`'s own javadoc), since §4 pins engage-refusal semantics only, not malformed-input handling.
  - **`channels`** — a no-op (silently dropped) if this connection has no active session; otherwise forwards `axes`/`buttons`/`seq`/`tSent` to `ManualControlSession#onChannels` and sends exactly one `ack {seq, tSent, tServer}` echoing the client's own values plus the server's receive timestamp (glass-to-stick RTT = `now - tSent` client-side).
  - **`release`** — releases the active session (idempotent no-op if none) and sends `released {reason: EXPLICIT}`.
  - **`afterConnectionClosed`** (any cause) — releases an active session (idempotent) but does **not** attempt to send a `released SOCKET_CLOSE` frame (the socket is already gone by the time this callback fires — `sendFrame`'s own `session.isOpen()` guard would have no-op'd it anyway; see `ManualControlReleasedFrame`'s own javadoc for the "best-effort, never actually sent" framing).
  - **Per-connection send lock** — every outbound frame goes through `sendFrame`, which `synchronized`s on `ConnectionState#sendLock` before calling `session.sendMessage`, exactly as `com.drones.vision.api.live.LiveConnection` guards `SseEmitter#send` — an `ack` (this connection's own read/dispatch thread) and a `watchdog` frame (pushed from whatever thread the shared `"rc-watchdog"` scheduler runs the trip callback on) can race without it.
  - **Channel-map `label` is no longer built here.** It used to be hardcoded in the handler, keyed by `rcChannel` — which silently assumed every vehicle used the default map. It now comes from `ControlBinding#function().label()` (`ControlFunction`, vision-flight domain), so a rover's CH1 reads `"Steering"` and an aircraft's reads `"Roll"` without this module knowing either exists (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P5). `ManualControlWebSocketHandler#labelFor` is gone.
- **Additive `engaged`-frame fields** (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md §4.4): `profileId` and `profileSource` (`"SAVED"`/`"BUILT_IN"`) joined `profileCode`/`profileName`, and each `channelMap` row gained `kind` (`"AXIS"`/`"BUTTON"`/`"SWITCH_2"`/`"SWITCH_3"`). Purely additive — no field was renamed or removed, so the frozen R4 contract still holds and an older client ignores them.
- **Frame DTOs** (`com.drones.vision.api.dto`), all plain records with a convenience constructor filling in the fixed `type` literal: client→server `ManualControlEngageRequest(type, assetId)`/`ManualControlChannelsRequest(type, axes, buttons, seq, tSent)` (the latter's compact ctor defaults `null` `axes`/`buttons` to `List.of()`); server→client `ManualControlEngagedFrame(type, assetId, rateHz, vehicleKind, profileCode, profileName, channelMap:List<ManualControlChannelBindingResponse>)`/`ManualControlChannelBindingResponse(source, function, travel, sourceIndex, rcChannel, minMicros, centerMicros, maxMicros, label)` (built by its own `static from(ControlBinding)`; `travel` is `"CENTERED"`/`"UNIDIRECTIONAL"` — the client needs it to know whether a released control springs back and whether 0 % or 50 % means "stop")/`ManualControlDeniedFrame(type, code, reason)`/`ManualControlAckFrame(type, seq, tSent, tServer)`/`ManualControlReleasedFrame(type, reason)` (constants `REASON_EXPLICIT`/`REASON_SOCKET_CLOSE`)/`ManualControlWatchdogFrame(type, timeoutMs)`. The handler itself parses inbound frames via a raw `JsonNode` tree (read `type` first, branch, then pull fields) rather than deserializing straight into `ManualControlEngageRequest`/`ManualControlChannelsRequest` — a discriminated union needs the type read before the concrete shape is known; those two request records exist as the documented, tested wire-shape reference (see `ManualControlFrameDtoTest`) and as literal frame-builders in `ManualControlWebSocketHandlerTest`, even though production parsing doesn't deserialize into them directly.

### `com.drones.vision.api.dto`

Request DTOs (each validates + converts via a `toX()`/`toRegistration()`/`toScanRequest()` method):
`RegisterDeviceRequest(name, protocol, uri, options?, capabilities?, origin?)` (`origin` —
docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R4, no convenience overload dropping it — only 2
call sites, both in-module tests, so a chained ctor would have saved nothing — see `DeviceOriginParsing`'s
own paragraph below) · `CreateAssetRequest(displayName, category, attributes?, devices:List<DeviceSpec>, deviceIds?:List<String>)` + nested `DeviceSpec(name, protocol, uri, options?, capabilities?, origin?)` (`deviceIds` — backend follow-up batch, the "promote to asset" flow: existing, unowned device ids to assign to the new asset, validated exactly like `POST /api/assets/{id}/devices`; `toSpec()` parses each via `DeviceId.of` — a malformed UUID is 400, same idiom as every other id-parsing spot in this module — and passes them as `AssetSpec#existingDeviceIds()`. A 4-arg convenience ctor defaults it to `List.of()`, keeping every pre-existing caller compiling; `devices`/`deviceIds` may combine freely, and at least one device between the two is required — enforced by `AssetSpec` itself, not duplicated here) · `StartStreamRequest(confidenceThreshold?, inferenceFps?, model?, labelFilter?:List<String>, labelDenyFilter?:List<String>, detectionEnabled?, tracking?:TrackingConfigRequest)` · `StartAssetStreamRequest(deviceId?, confidenceThreshold?, inferenceFps?, model?, labelFilter?:List<String>, labelDenyFilter?:List<String>, detectionEnabled?, tracking?:TrackingConfigRequest)` (**no `overlayBurnIn` field — removed entirely, not defaulted off**, docs/plans/done/CV-CLEAN-FEED-PLAN.md D-1: server-side burn-in no longer exists, so there is nothing left for the field to toggle; `labelDenyFilter` (D-2) took its place in the field order, mirroring `labelFilter`'s own present/absent-list semantics exactly — a present value overrides `PipelineConfig#labelDenyFilter()`, absent/`null` keeps `PipelineConfig.defaults()`'s empty set; `StartAssetStreamRequest#mergeOntoDefaults()` still just delegates to `StartStreamRequest`'s, one more field along for the ride. `model` (backend follow-up batch — the frontend's detection-model picker) is a `String`, present/non-blank overriding `PipelineConfig#model()`'s `id` — `new ModelRef(model, defaults.model().version())`, i.e. the `version` always stays the default's own (`"latest"`, `PipelineConfig.defaults()`); the raw string is carried through **verbatim, never split** — it may be a comma-composite (e.g. `"yolo11n.pt,orion12l.pt"`) that `cv-service`'s model registry parses server-side. `ModelRef`'s own compact ctor (vision-domain) only rejects a blank `id`/`version`, so commas/dots needed no relaxation — checked, not assumed. `labelFilter`/`detectionEnabled` (docs/plans/done/CV-CONTROL-PLAN.md §2) follow the identical per-stream-override pattern: a present `labelFilter` (JSON array) folds into `PipelineConfig#labelFilter()`'s `Set<String>` — an explicit empty array is a real value ("all labels"), same as the domain field's own semantics; absent keeps `PipelineConfig.defaults()`'s empty set. A present `detectionEnabled` overrides `PipelineConfig#detectionEnabled()`; absent keeps the default `true`. Both thread through `StartAssetStreamRequest#mergeOntoDefaults()` by delegating to `StartStreamRequest`'s implementation exactly as the other override fields do — no independent merge logic) · `UpdateStreamConfigRequest(confidenceThreshold?, inferenceFps?, labelFilter?:List<String>, labelDenyFilter?:List<String>, detectionEnabled?, model?, tracking?:TrackingConfigRequest)` (docs/plans/done/CV-CONTROL-PLAN.md §3's frozen wire contract, body of `PATCH /api/streams/{streamId}/config` — a true partial patch: every field optional, absent means "leave this knob unchanged", not "reset to default", unlike the start-request DTOs above which merge onto `PipelineConfig.defaults()`. `labelDenyFilter` (docs/plans/done/CV-CLEAN-FEED-PLAN.md §2, D-2) mirrors `labelFilter`'s exact patch semantics — present replaces the running deny-list wholesale (an empty-but-present list means "deny nothing"), absent leaves it alone. `toPatch()` maps 1:1 onto the application-layer `PipelineConfigPatch` (vision-application), folding `labelFilter`/`labelDenyFilter`'s `List<String>`s into `Set<String>`s; validation is deliberately not duplicated here — the merged config is validated by `PipelineConfig`'s own compact ctor inside `DefaultStreamService#updateConfig`, surfacing as `IllegalArgumentException`→400 same as every other domain-validated field in this module) · `ScanRequestDto(timeoutMs?, methods?)` · `StartSimulationRequest(displayName?, videoPath?, latitude?, longitude?, autoStart?, transport?, telemetry?, telemetryTransport?)` (docs/main/CYCLES-PLAN.md §1c, §3, §5, §7, §9; `toSpec()` defaults `autoStart` to `true` and `transport` to `SimulationTransport.DIRECT` when absent — request DTOs whose default isn't "leave the domain default alone" — and, since CU-a, treats a `null`/blank `videoPath` as equivalent, normalizing both to `null` before constructing `SimulationSpec` (unlike `SimulationSpec`'s own compact ctor, which treats a genuinely blank string as distinct from — and invalid unlike — absent). A `null` `videoPath` is a fully synthetic simulation (docs/main/CYCLES-PLAN.md §9, CU-a): `POST /api/simulations {}` alone (default `direct` transport) is valid and yields a moving synthetic drone; combining it with `transport=rtsp`/`mjpeg` surfaces `SimulationSpec`'s own cross-field `IllegalArgumentException` (→ 400) unchanged. `transport` is a `String` matched case-insensitively against `SimulationTransport` names (`"direct"`/`"rtsp"`/`"mjpeg"`); an unrecognized value throws `IllegalArgumentException` listing the valid values — dynamically enumerated from `SimulationTransport.values()`, so this DTO needed no code change when `MJPEG` was added, only its javadoc — the same idiom as `CapabilityParsing`. `telemetryTransport` (docs/plans/active/DRONE-INFRA-PLAN.md's own natural follow-up, wave 2 — see contexts/vision-simulation/MODULE.md's `SimulationSpec#telemetryTransport` entry) is a `String` matched case-insensitively against `TelemetryTransport` names (`"SIM"`/`"MAVLINK"`), same idiom/error-message shape as `transport`; `null`/blank defaults to `TelemetryTransport.SIM`. An 8th record component — a new 7-arg convenience ctor (mirroring `SimulationSpec`'s own N-1-arg convenience-ctor idiom) keeps every pre-existing call site (there were none outside JSON deserialization, but the pattern is kept for consistency) source-compatible. Orthogonal to `transport` (video), exactly like `SimulationSpec#telemetryTransport()` itself — every combination is valid, no cross-field check needed here either.

`telemetry` (docs/main/CYCLES-PLAN.md §7, CT-a) is an optional nested `TelemetryRequest(speedMps?, routeMode?, route:List<WaypointRequest>)` + nested `WaypointRequest(latitude, longitude, altitudeMeters?)` — mirroring `CreateAssetRequest.DeviceSpec`'s nested-request convention — replacing the bare circular home-point track with a configurable flight plan. `TelemetryRequest#toPlan()` (package-private) converts to a `TelemetryPlan`: `route` must have ≥2 points (`IllegalArgumentException` otherwise); each `WaypointRequest` converts via its own `toWaypoint()`, which lets `Waypoint`'s (vision-application) own range validation surface out-of-range coordinates as the same exception; `routeMode` is matched case-insensitively against `RouteMode` names, an unrecognized value throwing (listing valid values, same idiom as `transport`), while `null`/blank resolves to `null` rather than a hardcoded default — `SimulatedTelemetrySource` (adapter-simulation) stays the one source of truth for the actual default (`loop`). All of these surface as 400 via `ApiExceptionHandler`, same as every other validation failure in this class. `toSpec()` passes `telemetry == null ? null : telemetry.toPlan()` as `SimulationSpec`'s new `plan` argument.).

`capabilities` (both `RegisterDeviceRequest` and `DeviceSpec`) is an optional `List<String>`, matched case-insensitively against `Capability` names via the `CapabilityParsing.parse(List<String>)` helper (`support/`, since docs/plans/active/LAYERING-REFACTOR-PLAN.md §7 row B) shared by both records; `null`/empty defers to the **protocol** — `mavlink` defaults to `Set.of(Capability.TELEMETRY)`, everything else keeps `Set.of(Capability.VIDEO)` (docs/plans/active/TELEMETRY-ONLY-ONBOARDING-CONTEXT.md W3; both registration DTOs now call the `parse(List<String>, String protocol)` overload) — and an unrecognized name throws `IllegalArgumentException` listing the valid values (→ 400 via `ApiExceptionHandler`). This is what lets `POST /api/assets` register a `TELEMETRY`-capable device so `UsageTracker` (vision-application) records positions/sampleCount for it — previously `CreateAssetRequest.DeviceSpec#toRegistration()` hardcoded `Set.of(Capability.VIDEO)`, so asset devices could never carry telemetry through the API even though the domain/use-case layer always supported arbitrary `Set<Capability>`.

`origin` (`RegisterDeviceRequest`, `DeviceSpec`, `UpdateDeviceRequest`) — docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R4 — a plain `String` matched case-insensitively against `DeviceOrigin` names via a new `support.DeviceOriginParsing` helper, mirroring `CapabilityParsing`'s own shape exactly: `parse(String)` defaults `null`→`LIVE` (used by the two registration DTOs); `parseOptional(String)` leaves `null` as `null` (used by `UpdateDeviceRequest`'s partial-edit semantics, where absent means "unchanged", not "reset to LIVE"). An unrecognized value throws `IllegalArgumentException` listing the valid values (→ 400), same idiom as every other case-insensitive enum parse in this module.

**Warehouse request DTOs** (docs/main/CYCLES-PLAN.md §8's pinned contract — CW-a): `UpdateDeviceRequest(name?, protocol?, uri?, options?, capabilities?, origin?)` → `toEdit(): DeviceEdit` — the stream descriptor is replaced as a unit: `protocol`+`uri` required together (with `options` optional) whenever either is present, or all three left absent to keep the stream untouched; sending `options` alone is rejected rather than guessed at. `UpdateAssetRequest(displayName?, category?, attributes?)` → `toEdit(): AssetEdit`. Both have an `EMPTY` constant used when the request body is entirely absent (`@RequestBody(required = false)`). **Flight command request DTOs** (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 2): `SetModeRequest(mode)` → `requireMode()` rejects a null/blank `mode` (`IllegalArgumentException` → 400) before it reaches the service, body of `POST /api/assets/{id}/mode`. `ForceCommandRequest(Boolean force)` — **shared by both** `POST /api/assets/{id}/arm` and `.../disarm` (identical `{force?}` shape); `force` is nullable and `forceOrDefault()` defaults it to `false`, and the controller injects it with `@RequestBody(required = false)` + an `EMPTY` constant so a wholly-absent body is valid too (`POST .../arm` with no body arms without force). `SetLifecycleStateRequest(state)` — shared by `POST /api/devices/{id}/state` and `POST /api/assets/{id}/state`; `toLifecycleState()` matches `state` case-insensitively against only `ACTIVE`/`DEACTIVATED` (`DELETED` is reached solely through the `DELETE` endpoints), throwing `IllegalArgumentException` listing the two valid values otherwise — same idiom as `CapabilityParsing`/`StartSimulationRequest#parseTransport`. `AssignDeviceRequest(deviceId)` — body of `POST /api/assets/{id}/devices`; `toDeviceId()` rejects a blank id before it ever reaches `DeviceId.of`.

Response DTOs (`@JsonInclude(NON_NULL)` unless noted — see Conventions), each with a static `from(domainType)` mapper:
`DeviceResponse(id, name, capabilities:List<String>, protocol, uri, options, state, origin)` (no `NON_NULL`; `state` is `ACTIVE`/`DEACTIVATED`/`DELETED`, docs/main/CYCLES-PLAN.md §8's pinned contract; `origin` — docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R4, 8th component, always present since `Device#origin()` is never null — `"LIVE"`/`"SIMULATED"`, `device.origin().name()` verbatim) · `DiscoveredDeviceResponse(method, name, address, suggestedCategory?, protocol?, uri?, details)` · `ScanResultResponse(devices:List<DiscoveredDeviceResponse>, failedMethods:Set<String>)` (no `NON_NULL`) · `StartStreamResponse(streamId, viewUrl?, whepUrl?)` · `ActiveStreamResponse(streamId, deviceId, startedAt, viewUrl?, whepUrl?, state, detectionEnabled, detectionState)` — **no `burnedIn` field on either** — removed along with `StreamService#burnedIn(StreamId)`/`ActiveStream#burnedIn()` themselves, not defaulted off (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-1): server-side burn-in no longer exists, so there is nothing left to report on. `AssetStreamController#startStream` and `StreamController#start`/`#list` no longer need a `StreamService` collaborator to resolve it — `AssetStreamController` dropped that constructor parameter entirely (back to `(AssetService, AssetStreamService, CurrentUser, StreamViewerLinks, PipelineConfig)`) (docs/plans/done/MVP2-PLAN.md §L, L-a: `whepUrl` sits beside `viewUrl` on every stream-bearing response — `StreamPublisherPort#whepUrl`, independently `Optional`/omit-if-empty from `viewUrl`, so a publisher can expose HLS without WebRTC or vice versa, though `MediamtxStreamPublisher` in practice always has both once enabled. Unlike `viewUrl` — which can be app-relative (see `HlsProxyController` below) — `whepUrl`, when present, is always the media server's own absolute origin URL: WHEP is a POST/SDP + ICE exchange, not a byte stream this app can reverse-proxy the way it does for HLS segments, so there is no `whep`-flavored sibling of `HlsProxyController`.) · `AssetSummaryResponse(assetId, displayName, category, categoryName, owner, status, lifecycle, lastUsedAt?, lastKnownPosition?, attributes, hasImage)` (`lifecycle` — docs/main/CYCLES-PLAN.md §8's pinned contract; **field name deliberately `lifecycle`, not `state`** — `AssetSummary.asset().state()`'s wire name, kept distinct from the derived streaming `status`; renamed from `state` during CW-a specifically because vision-web's `AssetSummary`/`AssetDetails` types already coded against `lifecycle`. `hasImage` — docs/plans/done/UX-REWORK-PLAN.md §U-d item 3, CONTRACT 2 — a second, boolean-only `from(AssetSummary, boolean hasImage)` argument, since `AssetSummary` itself carries no notion of one; `AssetController` resolves it per-asset via `AssetImageRepositoryPort#existsByAssetId` (cheap, never loads image bytes), and the SSE `fleet` topic's own snapshot (`LiveUpdateRegistry`, `com.drones.vision.api.live`) deliberately always passes `false` — see that class's own javadoc for why, rather than growing its already-at-the-ceiling constructor) · `AssetDetailsResponse(...AssetSummaryResponse fields incl. lifecycle and hasImage, devices:List<DeviceResponse>, recentUsages:List<AssetUsageResponse>)` (`from(AssetDetails, boolean hasImage)`, same second-argument shape as `AssetSummaryResponse`) · `AssetUsageResponse(usageId, startedAt, endedAt?, startPosition?, lastPosition?, sampleCount, phase, origin)` (`phase` — docs/plans/active/DRONE-ONBOARDING-PLAN.md O7 — the `UsagePhase` enum name, `"PREFLIGHT"`/`"IN_FLIGHT"`/`"POSTFLIGHT"`/`"LINK_LOST"`/`"ABANDONED"`/`"CLOSED"`; always present, since `AssetUsage.phase()` defaults to `PREFLIGHT` rather than null. Added after O6 found it computed by `UsageTracker` and stored by `AssetUsageMapper` but served nowhere. §8.1 also names `firstArmedAt`/`lastDisarmedAt`; **those are still not exposed** — their columns exist (V19) but the domain `AssetUsage` record has no such fields, so adding them is a vision-warehouse change, not a DTO one. `origin` — docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D2, wave R2 — the `UsageOrigin` enum name, `"STREAM"`/`"TELEMETRY"`/`"OPERATOR"`; always present, since `AssetUsage.origin()` is non-null by the domain record's own compact constructor. Exposed for the same reason `phase` is, and for a future UI wave to tell an operator-engaged session apart from a stream-opened one — `AssetSessionController` is the endpoint pair that produces `OPERATOR`) · `AssetDeletionResponse(assetId, displayName, devicesDeleted, usagesRetained, streamsStopped)` (docs/main/CYCLES-PLAN.md §8's pinned contract; body of `DELETE /api/assets/{id}`, from `AssetDeletion`; named to match CW-b's wire type exactly — renamed from the pre-CW-a `DeletionSummaryResponse`) · `CategoryResponse(slug, name, parent?, attributeHints:List<String>)` · `GeoPositionResponse(latitude, longitude, altitudeMeters?)` · `TelemetrySampleResponse(deviceId, at, latitude?, longitude?, altitudeMeters?, headingDegrees?, batteryPercent?, flightState?:FlightStateResponse, extra?:Map<String,Double>)` (`deviceId` — docs/main/CYCLES-PLAN.md §11, CD-a — the canonical UUID string of the telemetry device the sample came from; always present, since domain `Telemetry#deviceId()` is non-null-validated in its own compact ctor, unlike every other field here; `flightState`/`extra` — docs/plans/done/FC-INTEGRATIONS-PLAN.md F-b — `extra` is `Telemetry#extra()` carried through for the first time (previously silently dropped at this DTO), omitted when empty rather than serialized `{}`; `flightState` wraps `Telemetry#flightState()` via `FlightStateResponse.from`, omitted when `null`. Serves `GET /api/usages/{usageId}/telemetry`, the replay timeline, and the `telemetry:<assetId>` SSE topic — one DTO, no per-transport variant) · `FlightStateResponse(firmware?, mode?, armed?, failsafe?, gpsFixType?, satellites?, hdop?, rssiPercent?, armingBlockers?:List<String>)` (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-b — mirrors domain `FlightState` field-for-field per the plan's frozen wire contract, `@JsonInclude(NON_NULL)`; every field individually absent when unknown, `armingBlockers` additionally absent when empty rather than `[]`, matching `FlightState`'s own per-field nullability discipline — a decoder merges this incrementally as different MAVLink messages arrive, so "unknown" must stay distinguishable from "known false/zero" all the way to the wire) · `SimulationResponse(assetId, streamId?, viewUrl?, whepUrl?)` (docs/main/CYCLES-PLAN.md §1c; `streamId`/`viewUrl`/`whepUrl` all absent when the simulation wasn't auto-started; `viewUrl`/`whepUrl` each independently absent per the same rule as `StartStreamResponse`/`ActiveStreamResponse` above, docs/plans/done/MVP2-PLAN.md §L) · `ReturnHomeResponse(result)` (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1's frozen wire contract; no `NON_NULL` — `result` is always present, `CommandResult#name()` verbatim, `"ACCEPTED"`/`"NO_ACK"`; via a static `from(CommandResult)` mapper. **Reused as-is by all four command endpoints** — `return-home`, `mode`, `arm`, `disarm` all return `{result}`, so no per-command response DTO was invented, per the frozen contract's identical `{result}` shape) · `FlightCapabilitiesResponse(commandable, armSupported, modeSelectSupported, selectableModes:[...], vehicleKind)` (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 2, body of `GET /api/assets/{id}/flight-capabilities`; no `NON_NULL` — every field always present, `selectableModes` an empty list rather than absent when mode select is unsupported, `vehicleKind` the `VehicleKind` enum name and `"UNKNOWN"` rather than absent when unheard; mirrors domain `FlightCapability` field-for-field via a static `from(FlightCapability)` mapper) · `UpdateStreamConfigResponse(streamId, modelReArmed)` (docs/plans/done/CV-CONTROL-PLAN.md §3's frozen wire contract, body of `PATCH /api/streams/{streamId}/config`; no `NON_NULL` — both fields always present; `modelReArmed` straight from `UpdateOutcome#modelReArmed()`, vision-application) · `CvModelResponse(id, displayName, kind, openVocab, defaultLabelFilter:List<String>)` (docs/plans/done/CV-CONTROL-PLAN.md §4's frozen wire contract, one roster entry; no `NON_NULL` — `defaultLabelFilter` is a real, possibly-empty list, never absent) · `CvModelsResponse(models:List<CvModelResponse>)` (its wrapper, body of `GET /api/cv/models`) · `ErrorResponse(error, message)` (no annotation).

**Flight command 400-vs-409 split (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 2):** `POST /api/assets/{id}/mode`'s **400 unknown mode** and **409 not-commandable** are two distinct outcomes even though both are "the command didn't go." The split lives entirely in `DefaultFlightCommandService` (vision-application), not this module's exception handler: an unknown mode for a mode-capable vehicle is validated against `FlightCapability#selectableModes()` *before* dispatch and thrown as a plain `IllegalArgumentException` → the global `IllegalArgumentException`→400 rule; a not-commandable vehicle (unheard/Betaflight/no MAVLink device) surfaces as `IllegalStateException` → 409, exactly as `return-home` already does. A blank/missing `mode` in the request body is likewise a 400 (`SetModeRequest#requireMode()` throws `IllegalArgumentException`). No new exception type and no change to `ApiExceptionHandler` were needed.

**Detections** (docs/plans/done/MVP1-PLAN.md §C8 bullet 3, body of `GET /api/streams/{streamId}/detections`, and the `detections` SSE topic's payload): `BoundingBoxResponse(x, y, width, height)` (no `NON_NULL`; mirrors `kernel.BoundingBox`, each component normalized [0,1]) · `DetectionResponse(label, confidence, box:BoundingBoxResponse, modelId, modelVersion, track?:DetectionTrackResponse)` · `DetectionResultResponse(streamId, frameSequence, capturedAt, inferenceMillis, detections:List<DetectionResponse>, tracking?:FrameTrackingResponse)`. **The last two gained `@JsonInclude(NON_NULL)` for exactly one field each** (docs/plans/done/TRACKING-PLAN.md §4.G, wave T6) — every other field is still always present, and an untracked payload is therefore **byte-identical to the pre-tracking wire**, which is the whole reason the track facts are one nested object rather than five flat siblings (docs/extracts/TRACKING-ORCHESTRATION.md §6 rule 1). Both keep their pre-T6 canonical constructor as an N-1-arg convenience ctor, so every existing call site compiles unchanged.

**Tracking wire shapes** (docs/plans/done/TRACKING-PLAN.md §4.D/§4.E/§4.F/§4.G, wave T6; V3 fields added docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md §2, wave J3): `DetectionTrackResponse(id, state, source, velocityX, velocityY, reupdated)` — the nested `"track"` object, deliberately **without** `ageFrames` (book-keeping the tracks endpoint carries, not something a box needs 6×/s); `reupdated` (wave J3, a 5-arg convenience ctor defaults it `false`) is `TrackRef#reupdated()` verbatim — this track's gap was reconstructed by ORU on this frame · `FrameTrackingResponse(detectorRan, detectorReason?, trackerMillis, engineId, lockedTrackId, detectionLagMillis, reupdateMillis, reupdatedTracks, capability?:TrackingCapabilityResponse)` (`@JsonInclude(NON_NULL)`) — the nested `"tracking"` object; **`detectorReason` is present iff `detectorRan`**, and `trackerMillis` is a *fractional* millisecond (nanos/1e6 — `Duration#toMillis()` would report every 0.4 ms tracker pass as `0`, which is precisely the number this feature exists to show); the four wave-J3 fields are a straight `TrackingTelemetry` mapping (a 5-arg convenience ctor defaults them to `0`/`0`/`0`/absent, the pre-V3 shape) — `detectionLagMillis`/`reupdateMillis` are whole milliseconds (the wire itself carries them as proto `int64`, no sub-millisecond precision to lose), and **`capability` is present only when `TrackingTelemetry#capability()` is non-null** (invariant B3) · `TrackingCapabilityResponse(levelServed, reason)` — the nested `"capability"` object, a straight `TrackingCapability` mapping; **`levelServed` is what the server actually served, never what a request asked for** (invariant B5 — see `TrackingConfigRequest#capabilityLevel` below, which this type has no relationship to at the Java level: `FrameTrackingResponse.from` builds `capability` purely from `TrackingTelemetry`, so there is no request value in scope to confuse it with) · `TrackResponse(trackId, label, confidence, box, state, source, velocityX, velocityY, ageFrames, reupdated, firstSeen, lastSeen)` from `perception.domain.model.TrackedObject` — flat, not nested, because *this is* the track resource; `reupdated` (wave J3) added between `ageFrames` and `firstSeen`, matching `TrackRef`'s own field order · `TrackStatsResponse(mode, engineId, windowSeconds, detectorPasses, trackerFrames, dutyRatio, trackerMillisP50, trackerMillisP95, lastDetectorReason, byState:Map<String,Integer>)` from `application.pipeline.TrackingStats` (`window.toSeconds()` → `windowSeconds`; `byState` is a `LinkedHashMap` copy so every state appears, zero included, in lifecycle order; **no `lockedTrackId` here** — §4.E hoists it) · `StreamTracksResponse(streamId, lockedTrackId, tracks, stats?, latency?, rate?, detectionState?)` (`@JsonInclude(NON_NULL)` for all four optionals; N-1-arg convenience ctors default `rate`/`detectionState` to absent, most recently `detectionState` — docs/plans/done/CV-DEMAND-PLAN.md §3.6, wave D2 — reporting which of the two independent detection gates (operator intent vs. system-derived demand) currently explains a stream's boxes-or-no-boxes state; absent for an unknown/not-running stream, same as `stats`) · `PipelineLatencyResponse(windowSeconds, samples, roundTripMillisP50, roundTripMillisP95, roundTripMillisMax, updateIntervalMillisP50, effectiveFps, worstBoxAgeMillis)` from `application.pipeline.PipelineLatency` (docs/conclusions/CV-RATE-BUDGET.md §3) — **present independently of `stats`**, gated only on the window having sampled anything: a stream with tracking off reports latency and no stats, which is the combination this endpoint most needs to serve, since a lagging overlay is exactly the complaint likely to be raised against it. `roundTripMillis*` is what the pipeline adds (encode, both hops, inference, decode); `updateIntervalMillisP50` is how long until the next box, set by the sample rate; `worstBoxAgeMillis` sums the two and is the number to quote for "how far behind is the overlay?" · `DetectionRateResponse(windowSeconds, sourceFps, targetFps, demandFps, submittedFps, submitted, droppedInFlight, droppedOutage, missedDeadlines, dropRatio, transport, decodeMillisP50)` from `application.pipeline.DetectionRate` (docs/plans/done/CV-RATE-CONTROL-PLAN.md §1; `transport`/`decodeMillisP50` added docs/plans/done/MEDIA-SOT-PLAN.md §5.4/§7, wave M5) — the `"rate"` object, **beside** `latency` rather than inside it: `effectiveFps` reports the rate boxes arrive at but cannot say *why* it fell short, and a starving source and a saturated detector look identical from it while having opposite fixes. Compare `targetFps` with `submittedFps`; when they differ exactly one counter is non-zero and names the loss — `missedDeadlines` (source slower than the target; `sourceFps` will confirm), `droppedInFlight` (detector saturated), `droppedOutage` (cv-service down). `demandFps` is what the tracked target's motion asked for before any ceiling applied, so `demandFps > targetFps` is the one comparison separating "raise the ceiling" from "shrink the round trip". Gated on a **served deadline** (`due() > 0`), not a completed detection, so a stream whose samples are *all* being dropped still reports why. **`transport`** (`"push"`|`"pull"`) is which loop counted these figures, and doubles as the reader's cue for which definition the sibling `latency.roundTripMillis*` is using (§7: pull mode redefines it as `receivedAt - capturedAt`, box age at arrival, since there is no Java→Python round trip to measure) — `PipelineLatencyResponse` itself gained no field of its own for this. **`decodeMillisP50`** is the worker's median local decode cost, `0` in push mode · `CvTrackerResponse(id, displayName, modes:List<String>, needsAssets, costHint)` + `CvTrackersResponse(trackers)` (no `NON_NULL`, mirroring `CvModelResponse`/`CvModelsResponse` exactly). Request side: `TrackingConfigRequest(mode?, engineId?, verifyEveryMillis?, followFps?, redetectIouPercent?, maxAgeFrames?, minHits?, capabilityLevel?, reupdateMaxGapMillis?, lock?:TargetLockRequest)` with `toPatch()`/`toStartPatch()` — both map **one-for-one onto `application.stream.TrackingConfigPatch`**, an absent JSON field becoming a `null` the application layer reads as "leave this knob unchanged"; the latter additionally **rejects a `lock`** (it names a track that cannot exist before the stream produces one). `capabilityLevel`/`reupdateMaxGapMillis` (wave J3, docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md §2) are ordinary nullable patch fields at this edge like every other knob here — **a request state a *ceiling*** (invariant B5); this DTO has no field for, and must never be read as, what was actually served — that is `FrameTrackingResponse#capability()`'s job, on a later, different response. **Nothing is merged at this edge**: the running configuration is the application layer's state, and the deployment seed is applied there too — see the follow-up section at the end of this file. `TargetLockRequest(trackId?, pointX?, pointY?, release?)` with `toTargetLock()` leaves `lockSeq` at `0` and lets `perception.domain.model.TargetLock`'s own compact ctor be the single arbiter of the one-of-three rule (→400). `UpdateStreamConfigRequest` gained `tracking` (→ `toPatch()`), `UpdateStreamConfigResponse` gained `trackingChanged`, and both `StartStreamRequest`/`StartAssetStreamRequest` gained `tracking`, exposed to the controllers as a separate `trackingPatch()` beside the plain `mergeOntoDefaults()`.

**Replay library** (docs/plans/done/NAV-IA-REDESIGN-PLAN.md Wave 4, F8, docs/extracts/design/10-replay.md's frozen wire
contract, body of `GET /api/usages`), `@JsonInclude(NON_NULL)` (`endedAt`/`durationSeconds` genuinely
absent for a still-open usage, the same convention `UsageRecordingResponse` uses for its own
nullable fields): `UsageSummaryResponse(usageId, assetId, assetName, startedAt, endedAt?,
durationSeconds?, sampleCount)`, from `application.usage.UsageSummary` via
`UsageSummaryResponse.from`. `assetName` is `""` (present, never omitted) rather than absent when the
owning asset can no longer be resolved at all — see `UsageSummary`'s own javadoc (vision-application)
for exactly when that is.

**Replay** (docs/plans/done/MVP2-PLAN.md §R, R-a, body of `GET /api/usages/{usageId}/timeline`), no `NON_NULL` (`from`/`to` are always resolved server-side, both list fields always present though possibly empty): `UsageTimelineResponse(usage:AssetUsageResponse, from, to, telemetry:List<TelemetrySampleResponse>, detections:List<DetectionResultResponse>)` — reuses `AssetUsageResponse`/`TelemetrySampleResponse`/`DetectionResultResponse` as-is rather than defining parallel replay-flavored DTOs, from `application.UsageTimeline` via `UsageTimelineResponse.from`. `detections` is real for usages opened after R-a2 (`AssetUsage.streamId` links the usage to its stream's `DetectionResult`s); usages from before that link, or streamless ones, honestly return `[]`.

**Recording** (docs/plans/done/OPS-CORE-PLAN.md §R, R-b, body of `GET /api/usages/{usageId}/recording`), `@JsonInclude(NON_NULL)` — `url`/`start`/`durationSeconds` are genuinely absent when `available` is `false`, so a `{"available":false}` response is exactly that, not three `null`s: `UsageRecordingResponse(available, url?, start?, durationSeconds?)`, from `Optional<application.UsageRecording>` via `UsageRecordingResponse.from` (a static `UNAVAILABLE` constant backs the empty branch, avoiding a fresh allocation on every "no recording" response).

**Geofences** (docs/plans/done/OPS-CORE-PLAN.md §G's frozen wire contract, body/request of `GET/POST /api/geofences`, `PUT /api/geofences/{id}`), `@JsonInclude(NON_NULL)` on the response (`maxAltitudeMeters` genuinely absent when the zone has no ceiling): `GeofenceZoneResponse(id, name, kind, polygon:List<GeoPositionResponse>, maxAltitudeMeters?, enabled)`, from `flight.domain.model.GeofenceZone` via `GeofenceZoneResponse.from` — reuses `GeoPositionResponse` as-is for `polygon` (each vertex's own `altitudeMeters` is always absent for a geofence zone; the zone-level `maxAltitudeMeters` is the one altitude concept a zone carries) rather than inventing a parallel vertex DTO. `GeofenceZoneRequest(name, kind, polygon:List<PolygonPointRequest>, maxAltitudeMeters?, enabled)` (request, same shape for create/update per the frozen contract) → `toSpec(): application.GeofenceZoneSpec` — `kind` matched case-insensitively against `ZoneKind` names (same idiom as `SetLifecycleStateRequest`/`CapabilityParsing`, throwing `IllegalArgumentException` listing the valid values); the nested nullable-altitude-per-vertex shape `GeoPosition` itself allows is deliberately not exposed here — `PolygonPointRequest(latitude, longitude)` (mirrors `CreateAssetRequest.DeviceSpec`'s nested-request convention) converts to `GeoPosition` with `altitudeMeters=null` always. Polygon-size/altitude/blank-name validation is **not** duplicated in this DTO — it's left to `GeofenceZoneSpec`'s own compact constructor (vision-application), which already enforces it and produces a spec-specific message, the same "map shapes, let the domain/spec validate" idiom `CreateAssetRequest` follows for its own cross-field checks.

**Marks** (docs/plans/done/TACTICAL-MARKS-PLAN.md §4's frozen wire contract, body/request of `GET/POST /api/marks`, `POST /api/marks/geolocate`, `PATCH`/`DELETE /api/marks/{id}`), `@JsonInclude(NON_NULL)` on the response (`note` genuinely absent when the mark carries none): `MarkResponse(id, kind, label, note?, position:GeoPositionResponse, createdBy, createdAt, status, source)`, from `map.domain.model.Mark` via `MarkResponse.from` — `kind`/`status`/`source` are the enum names verbatim, `createdBy` is `mark.ownership().ownerId()`'s UUID string; reuses `GeoPositionResponse` as-is (a mark's own `altitudeMeters` is genuinely nullable — a projected ground point's is always absent, unlike a geofence vertex's, which is always absent by construction). `CreateMarkRequest(kind, label, note?, position:PointRequest)` → `toSpec(): application.MarkSpec` — `kind` matched case-insensitively against `MarkKind` names (same `CreateMarkRequest.toKind` helper `PatchMarkRequest`/`GeolocateMarkRequest` reuse, package-visible for that reason), `position` left to `MarkSpec`'s own null check (same "map shapes, let the spec validate" idiom `GeofenceZoneRequest` follows); nested `PointRequest(latitude, longitude, altitudeMeters?)` (unlike `GeofenceZoneRequest.PolygonPointRequest`, carries its own optional altitude — a mark's position is a standalone `GeoPosition`, not one vertex of a shape whose altitude ceiling lives elsewhere) is also reused by `PatchMarkRequest`'s own `position` field. `GeolocateMarkRequest(assetId, kind?, label?, note?, depressionDegrees?)` → `toSpec(): application.GeolocateSpec` — **three fields default rather than fail when absent** so the cockpit's one-tap "Mark target" action needs to send only `assetId`: `kind` absent/blank → `MarkKind.TARGET` (an explicit-but-unrecognized value still 400s), `label` absent/blank → `"Contact"`, `depressionDegrees` absent → `GeoProjection.DEFAULT_DEPRESSION_DEGREES` (vision-domain). `PatchMarkRequest(kind?, label?, note?, position?, status?)` → `toPatch(): application.MarkPatch` — a **true partial patch** (every field optional, `null`/absent leaves it unchanged, mirroring `UpdateStreamConfigRequest`'s convention rather than `GeofenceZoneRequest`'s wholesale-replace shape); `kind`/`status` matched case-insensitively (same idiom); a present-but-blank `note` (`""`) is a real value that clears it (`Mark#withDetails`'s own blank-normalizes-to-null rule), distinct from an absent/`null` `note`, which leaves it untouched — the wire's only way to express "clear this field" with a plain-nullable-field DTO shape (no `Optional`-wrapped JSON fields), same trick `MarkSpec`/`Mark` themselves already use for their own `note`.

**Detection events** (docs/plans/done/MVP2-PLAN.md §E, E-a, body of `GET /api/events`/`GET /api/streams/{streamId}/events`), `@JsonInclude(NON_NULL)` (unlike Detections/Replay above — `assetId`/`position` are genuinely absent when unresolvable, so they're omitted rather than serialized `null`): `DetectionEventResponse(id, streamId, assetId?, label, peakConfidence, firstSeen, lastSeen, state, position?:GeoPositionResponse)` — mirrors `perception.domain.model.DetectionEvent` field-for-field (`state` is the enum name, `"OPEN"`/`"CLOSED"`; `DetectionEvent` moved from `events` to `perception` in W1.6b — docs/plans/active/DOMAIN-SEPARATION-W1.md §15, it stores/shapes detections and only perception ever constructs it, filed by consumer not owner before this wave), reuses `GeoPositionResponse` as-is (same as `AssetSummaryResponse`/`AssetUsageResponse` already do) rather than a parallel event-flavored position type, from `perception.domain.model.DetectionEvent` via `DetectionEventResponse.from`.

**Fleet summary** (docs/plans/done/MVP3-PLAN.md C-a, body of `GET /api/fleet/summary`): `FleetSummaryResponse(categories:List<CategoryCountsResponse>, assets:List<AssetAttentionResponse>, totalAssets)` (no `NON_NULL` — every field always present, from `application.FleetSummary` via `FleetSummaryResponse.from`) · `CategoryCountsResponse(categoryId, categoryName, total, active, deactivated, deleted, streaming)` (no `NON_NULL` — nothing here is nullable, from `application.CategoryCounts`) · `AssetAttentionResponse(assetId, displayName, categoryId, categoryName, lifecycle, streaming, streamId?, batteryPercent?, telemetryAgeMs?, openEventCount, flightMode?, armed?, failsafe?)` (`@JsonInclude(NON_NULL)` — `streamId`/`batteryPercent`/`telemetryAgeMs` genuinely absent when unavailable, from `application.AssetAttention`; `flightMode`/`armed`/`failsafe` — docs/plans/done/FC-INTEGRATIONS-PLAN.md F-b — same absent-when-unavailable rule, straight passthrough of `AssetAttention`'s own three new fields, no extra mapping logic). **Deliberately no `sourceState` field** — see `application.AssetAttention`'s own javadoc (contexts/vision-warehouse/MODULE.md) for exactly why it was left out (no honest read today) rather than faked.

**Asset stats** (docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md Wave A, body of `GET /api/assets/{id}/stats`), `@JsonInclude(NON_NULL)` — `firstFlownAt`/`lastFlownAt`/`avgFlightSeconds`/`lastKnownBatteryPercent` genuinely absent when unavailable (no flights fetched / no closed flights / no telemetry ever reported, respectively): `AssetStatsResponse(totalFlightSeconds, flightCount, firstFlownAt?, lastFlownAt?, avgFlightSeconds?, lastKnownBatteryPercent?, flightInProgress)`, from `application.AssetStats` via `AssetStatsResponse.from` — mirrors the application record field-for-field, no extra mapping logic.

**Device probe** (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3, CONTRACT 1, body of `POST /api/devices/probe`): `ProbeDeviceRequest(protocol, uri, options?)` → `toDescriptor(): StreamDescriptor` — the exact same connection shape `RegisterDeviceRequest` carries minus a name/capabilities (a probe never registers anything); deliberately does **not** normalize `protocol`'s case, mirroring `RegisterDeviceRequest`'s own precedent — `StreamDescriptor`'s compact constructor (vision-domain) is what actually rejects a non-lower-case value (400). `ProbeDeviceResponse(ok, widthPx?, heightPx?, codec?, fps?, telemetryDetected, frameJpegBase64?, warnings:List<String>)` (`@JsonInclude(NON_NULL)`; `warnings` is always present, possibly empty). **Two legal shapes** (docs/plans/active/TELEMETRY-ONLY-ONBOARDING-CONTEXT.md W2): a video probe returns the shape it always has; a *telemetry-only* probe omits `widthPx`/`heightPx`/`frameJpegBase64` entirely rather than reporting a fabricated `0x0` frame a client could not tell from a real one — so `frameJpegBase64 === undefined`, not a truthiness check on `widthPx`, is the reliable "no picture" test. `ok`/`telemetryDetected` are unchanged in both. `DeviceProbeController` skips the JPEG encode altogether when `ProbeResult#telemetryOnly()` rather than handing `SnapshotJpegEncoder` a null — `ok` is always `true` on this type, since a failed probe never reaches it (`ProbeFailedException`/`UnsupportedProtocolException` throw instead, see the exception-handler table above); `from(ProbeResult, byte[] jpegBytes)` Base64-encodes the already-JPEG-encoded preview (see `DeviceProbeController`'s own javadoc for why the encode itself happens in the controller, reusing `SnapshotJpegEncoder`, not in `application.ProbeResult`).

**Live updates** (docs/plans/done/REALTIME-PLAN.md §4, body of `GET /api/live`'s SSE events — see the `com.drones.vision.api.live` subsection above for the registry/topic/ring-buffer machinery that produces these):
`LiveEnvelopeResponse(seq, assetId?, type, payload)` (`@JsonInclude(NON_NULL)` — `assetId` absent for `fleet`/`event`/`devices`/`detection-events`/`marks` envelopes) — the one wire shape every regular SSE `data:` line carries; `seq` is a single counter shared across every topic (doubles as the SSE `id:` field, so `EventSource`'s own automatic `Last-Event-ID` resume needs no client code at all); `type` is one of `fleet`/`telemetry`/`detections`/`event`/`devices`/`detection-events`/`marks` (the last three, backend follow-up batch and docs/plans/done/TACTICAL-MARKS-PLAN.md M4 respectively); `payload` is `List<AssetSummaryResponse>` (fleet, a full snapshot each time — not a diff), `List<TelemetrySampleResponse>` (telemetry, a coalesced batch of samples appended since the last flush), a single `DetectionResultResponse` (detections, latest-frame-only), a single `EventResponse` (event), a single `DevicesSnapshotResponse` (devices, a full device-list + active-stream-list snapshot each time, not a diff — mirrors `fleet`'s own convention), a single `DetectionEventResponse` (detection-events, one event's current state per envelope — open/advance/close, the same shape `GET /api/events` serves), or a single `MarkPayload{action, mark:MarkResponse}` (marks, one lifecycle event per envelope — `action` is `"created"`/`"updated"`/`"cleared"`, `mark` reuses the exact `MarkResponse` shape `GET /api/marks` serves so a consumer parses one shape regardless of transport; for `"cleared"`, `mark.status` is always `"CLEARED"` even when the underlying delete removed a mark that was still `ACTIVE`) — every payload shape reuses an existing response DTO; `EventResponse` (E-a/R-c), `DevicesSnapshotResponse` (backend follow-up batch), and `MarkPayload` (docs/plans/done/TACTICAL-MARKS-PLAN.md M4) are the only three invented purely for this feed. `LiveConnectedResponse(connectionId, topics:List<String>)` — the payload of the separate, distinctly-*named* `connection` SSE event sent once, first, on every new connection (not wrapped in `LiveEnvelopeResponse` — it has no `seq`, isn't replayed on resume, and isn't really a "live update", just connection handshake metadata); `topics` is the wire form (`LiveTopic#wire()`) of every topic the connection is subscribed to right after connecting. `UpdateLiveTopicsRequest(add?:List<String>, remove?:List<String>)` (request body of `PATCH /api/live/{connectionId}/topics`; both default to `List.of()` when absent, `EMPTY` constant for a wholly-absent body) · `LiveSubscriptionResponse(connectionId, topics:List<String>)` (its response body — the connection's full topic set afterward). `EventResponse(id, streamId?, at, type, message, attributes)` (`@JsonInclude(NON_NULL)` — mirrors domain `Event` field-for-field; this is the *only* place in this module a generic `Event` gets a wire shape at all, since `EventPublisherPort` otherwise has no read side to back a REST endpoint with — see this file's own Gotchas for the pre-existing "why `/api/events` can't carry these" writeup, which this DTO deliberately does not attempt to resolve for that older polling endpoint, only for the new SSE one). `DevicesSnapshotResponse(devices:List<DeviceResponse>, streams:List<ActiveStreamResponse>)` (no `NON_NULL` annotation — both fields always present, backend follow-up batch) — the `devices` topic's payload; both lists travel together under the channel's one shared `seq` so a viewer never observes them snapshotted at different moments, reusing `DeviceResponse`/`ActiveStreamResponse` as-is (the latter's `viewUrl`/`whepUrl` resolved through `StreamPublisherPort` exactly like `StreamController#list`). `MarkPayload(action, mark:MarkResponse)` (`@JsonInclude(NON_NULL)`, docs/plans/done/TACTICAL-MARKS-PLAN.md §5) — the `marks` topic's payload; reuses `MarkResponse` as-is (see the "Marks" DTO paragraph above) rather than a parallel live-flavored shape, so the same JSON shape appears under `GET /api/marks`'s array elements and under this envelope's `payload.mark`.

**System status** (docs/plans/done/SYSTEM-STATUS-PLAN.md §4.3, wave S2, body of `GET /api/system/status`): `SubsystemStatusResponse(id, label, health, detail, since?, hint?)` (`@JsonInclude(NON_NULL)` — `since`/`hint` genuinely absent, mirroring `vision-platform`'s `SubsystemStatus` field-for-field), `static from(SubsystemStatus)`. `SystemStatusResponse(overall, checkedAt, subsystems:List<SubsystemStatusResponse>)` (no `NON_NULL` — every field always present, `subsystems` copied defensively in the compact constructor via `List.copyOf`). `health`/`overall` serialize as `Health`'s enum name verbatim (`OK`/`DEGRADED`/`DOWN`/`DISABLED`/`UNKNOWN`) — Jackson 3's default enum handling, no custom serializer needed.

**Onboarding** (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1's frozen wire contract, wave O5 — see
the done-wave section at the end of this file for the full report): no `@JsonInclude(NON_NULL)` on
any of these — §8.1's own examples show literal `null`s (`"incompleteReason": null`,
`"previousValue": null`) that must serialize, never be omitted, per C7. `VehicleProfileResponse(linkKey,
observedAt, sysid?, firmware?, firmwareVersion?, vehicleKind?, capabilityBitmask?,
capabilityFlags:List<String>, messages:List<MessageObservationResponse>,
parameters:List<ParameterReadingResponse>, linkBytesPerSecond?, complete, incompleteReason?)` + nested
`MessageObservationResponse(messageId, name?, hz, count)`/`ParameterReadingResponse(name, value, type)`,
`static from(VehicleProfile)` — body of `POST /api/onboarding/probe`,
`GET/POST /api/assets/{assetId}/profile|probe`. `ProbeCandidateRequest(protocol, uri, options?)` →
`requireProtocol()`/`toLinkKey()` (folds `options["sysid"]` into `"<uri>#<sysid>"`, mirroring
`ProbeDeviceRequest`'s shape deliberately). `ReadinessReportResponse(assetId, verdict, evaluatedAt,
profileObservedAt?, features:List<FeatureReadinessResponse>, blockers:List<String>)` + nested
`FeatureReadinessResponse(feature, label, status, detail, remedy?)` — **`feature`, not `featureKey`**,
the one wire rename off the domain `FeatureReadiness` type — `static from(ReadinessReport)`; body of
`GET /api/assets/{assetId}/readiness` and `RemediationResultResponse#reprobe`.
`ReadinessRowResponse(assetId, displayName, verdict, features:Map<String,String>)`,
`static from(Asset, ReadinessReport)` — one `GET /api/fleet/readiness` row, deliberately flatter than
the full report (no `detail`/`remedy` text). `FleetReadinessResponse(assets:List<ReadinessRowResponse>)`
— its wrapper. `RemediationRequest(features?:List<String>, actions?:List<String>)` — request body of
`POST /api/assets/{assetId}/remediate`; `null` lists treated as empty via `featuresOrEmpty()`/
`actionsOrEmpty()`. `RemediationResultResponse(requestedAt, verifiedAt?, actions:List<RemediationActionResponse>,
reprobe?:ReadinessReportResponse)` + nested `RemediationActionResponse(action?, messageId?,
intervalMicros?, outcome, previousValue?, newValue?, detail?)` — assembled by `RemediationOrchestrator`
(`support/`), not mapped off one domain type (see that class's own javadoc for why no single
`vision-flight` application service composes this end to end — a flagged plan gap).

**Onboarding — the flight passport** (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1's frozen wire
contract, wave O13; the two DTOs below deliberately break from the "no `NON_NULL`" rule the rest of
this paragraph states, see each type's own javadoc for why): `FlightPassportResponse(usageId, assetId,
preflight?:VehicleProfileResponse, postflight?:VehicleProfileResponse)`, `@JsonInclude(NON_NULL)` — an
uncaptured snapshot is **absent**, not a literal `null` (the plan's own comment on this shape: "we did
not look" and "we looked and found nothing" are different claims), the opposite convention from
`VehicleProfileResponse` itself, matching `AssetUsageResponse`'s `endedAt`/positions idiom instead —
`static from(FlightPassport)`; body of `GET /api/assets/{assetId}/usages/{usageId}/passport`.
`ParameterDriftResponse(drift:List<ParameterDriftRowResponse>)` + nested
`ParameterDriftRowResponse(parameterName, previousValue, currentValue, previousObservedAt,
currentObservedAt)`, `static from(List<ParameterDrift>)` — body of
`GET /api/assets/{assetId}/usages/{usageId}/drift`; no `NON_NULL` here (every field always present),
and an empty `drift` list serializes as `{"drift":[]}`, a correct 200, never omitted or 404'd.
`DiscoveredDeviceResponse`
gained an additive `suggestedOptions:Map<String,String>` field (D16) — synthesized from the existing
`details` map (currently just `{"sysid": details["sysid"]}}` when present, `{}` otherwise) rather than
a new `DiscoveredDevice` domain field, since every value it carries already exists on `details` today;
`details` itself stays for one release, its removal deferred and named here, not yet scheduled.

### `com.drones.vision.api.proxy` — `HlsProxyController`, HLS reverse proxy

`HlsProxyController(URI hlsUpstreamBase, VisionApiProperties.HlsProxy hlsProxy)` (`@Autowired`; `hlsUpstreamBase` wired by `vision-app`'s `PublishWiring#hlsProxyUpstreamBase` from `VisionPublishProperties.Mediamtx#hlsBase()`, `hlsProxy` by `PublishWiring#hlsProxySettings` from `VisionApiProperties#hlsProxy()` as of docs/plans/done/SCALE-100-PLAN.md §5 S7 — see Conventions for why this one controller deviates from the "ports only" rule). A package-private 1-arg overload (`HlsProxyController(URI hlsUpstreamBase)`, defaulting to `VisionApiProperties.HlsProxy.defaults()`) is the test seam every existing `HlsProxyControllerTest` case still uses. `GET /hls/{streamId}/**` forwards the request to `hlsUpstreamBase + "/" + <raw remainder after "/hls/">`, so browsers never talk to the mediamtx sidecar directly — see `StreamPublisherPort#viewUrl`'s new app-relative contract below.

**Rewritten for docs/plans/done/SCALE-100-PLAN.md §5 S1** (video out of the JVM byte path — the app's hardest concurrency ceiling per that plan's §2a/b): streaming instead of buffering, one shared `HttpClient` instead of one per request, `Range` forwarding. Full detail lives in the class's own javadoc; summary below.

- **Raw pass-through**: unchanged — the forwarded path/segment name comes from `HttpServletRequest#getRequestURI()` (servlet-spec-guaranteed undecoded), not the decoded `@PathVariable`, so percent-encoded segment names are never decoded-then-re-encoded.
- **Body — streamed, not buffered**: `proxy` now returns `ResponseEntity<InputStreamResource>` wrapping `HttpResponse.BodyHandlers.ofInputStream()`, and Spring's `ResourceHttpMessageConverter` copies it to the servlet output stream in fixed-size chunks — no full-segment `byte[]` allocation per request any more. The **only** body content this controller ever buffers is a bounded diagnostic preview (`errorBodyPreviewMaxChars`, default 200 bytes, `vision.api.hls-proxy.error-body-preview-max-chars` as of §5 S7) of a **non-2xx** upstream response, read via `InputStream#readNBytes` and stitched back onto the rest of the (still-streamed) body with a `SequenceInputStream` so the browser still receives the complete error body. `Content-Length` is forwarded from upstream unchanged in both cases, since re-splitting an unchanged body into two `InputStream`s doesn't change its total size.
- **One shared `HttpClient`, no shared cookie jar**: built once in the constructor (`Redirect.NEVER`, `connectTimeout` only — deliberately **no** `cookieHandler`) and reused for every request, replacing the old per-request client that leaked a selector thread + connection pool on every call (never closed). `@PreDestroy` closes it (`HttpClient` is `AutoCloseable` since Java 21) when the bean is destroyed. **Why no cookie handler**: mediamtx issues per-viewer session cookies; a `java.net.CookieHandler` attached to a *shared* client would remember viewer A's cookie and hand it to viewer B's request to the same upstream host — a cross-viewer session leak. Cookies are instead handled entirely as request/response headers, scoped to the one servlet request each call belongs to. Proven by `sharedClientDoesNotLeakOneViewersCookieToAnother` (`HlsProxyControllerTest`) — this wave's acceptance gate: two sequential requests through the *same* controller instance (same shared client) with different `Cookie` headers each reach upstream carrying only their own cookie, and the `Set-Cookie` mediamtx issues for viewer A never reaches viewer B's request.
- **Redirects — followed by hand, not by the client**: with no cookie handler, `HttpClient.Redirect.NORMAL` can't be trusted to carry a cookie set on hop 1's response onto hop 2's request (that carry-over is exactly what a `CookieHandler` would otherwise supply). So `fetch` loops itself (`Redirect.NEVER` on the client, bounded at `maxRedirectHops`, default 5, `vision.api.hls-proxy.max-redirect-hops` as of §5 S7 — mediamtx's own pinning flow is exactly one hop, more than the bound throws `IOException` → 502, proven with a 1-hop bound by `configuredMaxRedirectHopsBoundsTheHandFollowedRedirectLoop`), resolving each hop's `Location` against the previous URI and folding each hop's `Set-Cookie` values into the `Cookie` header sent on the next hop by hand (`mergeCookies` — parses just the `name=value` pair, attributes like `Path`/`Max-Age` are dropped since an outgoing `Cookie` header can't carry them anyway). Every hop's `Set-Cookie` is still collected, oldest hop first, and relayed back to the browser exactly as before.
- **`Set-Cookie` is rewritten to the viewer's scheme** (`relayableSetCookie`, fix `6e640d3`): when the incoming request is **not** HTTPS, `Secure`, `Partitioned` and `SameSite=None` are stripped from each relayed cookie. mediamtx emits every HLS session cookie twice — bare, then hardened with all three — and both copies share a name and path, so the hardened one *replaces* the usable one in the viewer's jar. Over https that is correct and it passes through untouched; over plain http the browser drops it, the viewer can never return `hlsSession`, and mediamtx answers the media playlist with **401** — every HLS request in a 100-viewer sweep failed this way before the fix ([`SCALE-100-AFTER.md`](../../docs/conclusions/SCALE-100-AFTER.md) §4). This is the reverse-proxy job nginx spells `proxy_cookie_flags`. Behind a TLS-terminating front proxy, `isSecure()` reports *this* hop unless `server.forward-headers-strategy` is configured. Covered by `secureOnlyCookieAttributesAreStrippedForAPlainHttpViewerAndKeptForAnHttpsOne`. **Note this only became reachable with the shared client**: the old per-request cookie jar never echoed mediamtx's `cookieCheck` probe, so mediamtx put the session in the playlist URL and no cookie was needed at all.
- **`Range` forwarded**: the incoming `Range` header (byte-range requests — the recording playback path; live HLS never sends one) is forwarded on every hop, and the upstream's `Content-Range`/`Accept-Ranges` are passed back alongside `Content-Type`/`Cache-Control`. Covered by `rangeHeaderIsForwardedUpstreamAndContentRangeAcceptRangesArePassedBack`.
- **Failure**: unchanged in shape — only a failure to reach upstream at all (`IOException`/interrupted/too many redirects) throws `HlsUpstreamUnavailableException` → 502; a normal non-2xx actually received from upstream (e.g. a segment not ready yet) passes through verbatim, same as `Content-Type`/`Cache-Control`/status on success.
- **Caching (docs/plans/done/MVP2-PLAN.md V-a proxy audit)**: unchanged — this controller never *sets* a `Cache-Control` header of its own, only *forwards* whatever value upstream sent (mediamtx marks every LL-HLS live media playlist `no-cache`). See `cacheControlIsForwardedFromUpstreamNotAddedOrDropped` in `HlsProxyControllerTest`.
- **LL-HLS query strings (docs/plans/done/MVP2-PLAN.md V-a proxy audit)**: unchanged — `buildUpstreamUri` forwards the full raw query string untouched, so LL-HLS's blocking-reload protocol (`_HLS_msn`/`_HLS_part`/`_HLS_skip`) still rides through correctly. `requestTimeout` (default 15s, `vision.api.hls-proxy.request-timeout`) still bounds how long a single hop (including a blocking LL-HLS reload) can take before surfacing a 502 — mediamtx's own blocking-wait has no independent timeout beyond an initial "too-far-ahead" 400 check.
- **404 without `{streamId}`**: unchanged — `/hls` or `/hls/` simply doesn't match the `@GetMapping` pattern and falls through to Spring's ordinary unmapped-route 404 — no special-case code.
- **No more local constants (docs/plans/done/SCALE-100-PLAN.md §5 S7)**: `connectTimeout`/`requestTimeout`/`errorBodyPreviewMaxChars`/`maxRedirectHops` are all instance fields now, sourced from the `VisionApiProperties.HlsProxy` the `@Autowired` constructor receives (`PublishWiring#hlsProxySettings`, mapped from `VisionApiProperties#hlsProxy()`) — finishing what S1 task 4 deliberately deferred. No literal timing/sizing constant remains in this class.

### `com.drones.vision.api.ratelimit` — `RateLimitFilter`, per-principal token bucket (docs/plans/done/SCALE-100-PLAN.md §5 S6 item 3)

A plain `jakarta.servlet` `OncePerRequestFilter`, not a controller — a **blast-radius bound**, not
security hardening or a DoS defence (the plan says this plainly): today one misbehaving browser tab
can issue enough requests to degrade the app for every other user on the same JVM. One flat limit,
one knob, no per-endpoint tiers, no auth-aware policy.

- **`RateLimitFilter(CurrentUser)`** — the production constructor; `permitsPerMinute` defaults to
  `DEFAULT_PERMITS_PER_MINUTE` (600/min, 10/s sustained, burstable to a full minute's allotment —
  see the field's own javadoc for the sizing rationale against docs/plans/done/SCALE-100-PLAN.md
  §2.1's ~1 req/s-per-idle-tab measurement). A second public constructor takes an explicit
  `permitsPerMinute`. A package-private third constructor injects the `ScheduledExecutorService`
  (bucket eviction) and the `LongSupplier` time source — the test seam, unused in production.
- **Keying**: `CurrentUser#userId()` — the same identity every write on the request is already
  attributed to, so it degrades exactly like the rest of the pipeline: one shared bucket for the
  fixed dev principal when `vision.auth.enabled=false`, one bucket per real user when `true`.
  `SecurityContextPrincipalResolver` throws `IllegalStateException` for a request with no
  authenticated session (vision-app) — under the secured chain the only `/api/**` paths reachable
  that way are the permit-all `/api/auth/login`/`/api/auth/logout` (`SecurityConfig`; every other
  `/api/**` path is already rejected 401 before this filter runs — see "Ordering" below), and those
  fall back to `request.getRemoteAddr()` instead of sharing one bucket with every anonymous caller.
- **Path scope**: `shouldNotFilter` limits to `/api/**`, excluding `/api/live` and everything under
  it (`/api/live/{connectionId}/topics`) — a token bucket in front of the long-lived SSE stream
  would be a self-inflicted outage. `/hls/**` never matches the `/api/` prefix at all.
- **Ordering**: registered with no explicit order, so it runs at Spring Boot's default
  `LOWEST_PRECEDENCE` — after `springSecurityFilterChain` (order `-100`, whichever
  `SecurityFilterChain` in `SecurityConfig` is active). That ordering is what makes the keying above
  correct (see class javadoc "Ordering" for the full argument).
- **Wiring, and why it ships off**: `vision-app`'s `RateLimitWiring` registers this behind
  `@ConditionalOnProperty("vision.api.rate-limit.enabled")`, **default `false`**. Not caution — a
  consequence of the keying two bullets up: with `vision.auth.enabled=false` every caller in the
  deployment resolves to one fixed dev principal, so enabling the limit there hands *all* of them a
  single `permits-per-minute` budget, which this plan's own target of 100 concurrent users (~1 req/s
  each) exhausts on legitimate traffic alone. It belongs on together with auth, where each real user
  gets their own bucket. `DEFAULT_PERMITS_PER_MINUTE` is `public` for that wiring to default from.
- **`TokenBucket`** (package-private) — capacity and average refill rate both `permitsPerMinute`;
  every method takes `now`/`cutoff` as an explicit `long` nanos parameter rather than reading
  `System.nanoTime()` itself, so it stays a pure, directly-unit-testable function of its own state
  and the given instant (`TokenBucketTest`). `synchronized` methods, pure in-memory arithmetic, no
  I/O — same virtual-thread-safety reasoning `LiveRingBuffer`'s javadoc gives for its own
  synchronized methods (docs/plans/done/SCALE-100-CONTEXT.md §7).
- **Bounded memory**: `buckets` (`ConcurrentHashMap<Object, TokenBucket>`) would otherwise retain
  one bucket per principal ever seen since boot — the same leak class docs/plans/done/SCALE-100-PLAN.md
  §5 S2 fixed for `LiveUpdateRegistry`'s per-asset buffers. `evictIdleBuckets()` (package-private,
  directly callable from a test) sweeps buckets idle past `BUCKET_IDLE_MILLIS` (10 min) on a daemon
  scheduler ticking every `BUCKET_EVICTION_MILLIS` (10 min) — same shape as
  `LiveUpdateRegistry#evictUnusedAssetBuffers`.
- **429 body**: `ErrorResponse("TOO_MANY_REQUESTS", "rate limit exceeded, try again shortly")`,
  hand-serialized via a locally-constructed `JsonMapper` (same idiom `LiveUpdateRegistry` uses) —
  `ApiExceptionHandler` is unreachable from a servlet filter (it only sees exceptions thrown inside
  `DispatcherServlet`-dispatched controller methods), so this filter writes the response itself,
  reusing the existing `ErrorResponse` shape for consistency with every other `4xx`/`5xx` body.

### `com.drones.vision.api.support` — edge-local helpers + `VisionApiProperties`

**`SnapshotJpegEncoder`** — `StreamController#snapshot`'s and `DeviceProbeController#probe`'s shared collaborator, turning a `VideoFrame` into downscaled JPEG bytes. Lives here rather than in an adapter because this module may not depend on any adapter (ArchUnit-enforced) and is already Spring-full, so `javax.imageio` is a fine direct dependency for a one-shot, on-request encode. **No longer a static utility** (docs/plans/active/LAYERING-REFACTOR-PLAN.md §7 row B) — public, instance-based, constructor-injected with `VisionApiProperties`; `public` (not package-private) purely because it now lives in a different package (`support`) from its two callers (`controller`), not because a second implementation exists (still none — `.claude/skills/java-clean-code/SKILL.md` §1).

- `SnapshotJpegEncoder(VisionApiProperties properties)` reads `properties.snapshot().maxWidth()`/`.jpegQuality()` into final instance fields — no more `static final` constants for the tunables (`MAX_SNAPSHOT_WIDTH` stays as a `public static final int` purely so the javadoc `{@value}` and existing tests can still read the default by name, mirroring `DefaultReplayService.DEFAULT_MAX_POINTS`'s own precedent, vision-application).
- `byte[] encode(VideoFrame frame)` (now an instance method) — downscales to at most the configured max width (aspect-preserving); a frame that is already `PixelFormat.JPEG` **and** already at or under that width is returned untouched (no decode/re-encode round trip — the common case for adapter-simulation/adapter-mjpeg's typically-small frames).
- **Supported `PixelFormat`s**: only `BGR24` and `JPEG` — the only two any adapter in this codebase actually produces (verified by grepping every adapter's `PixelFormat.*` usage: adapter-rtsp emits `BGR24`; adapter-simulation/adapter-mjpeg emit `JPEG`). Anything else throws `IllegalStateException`, deliberately left unmapped in `ApiExceptionHandler` (falls through to Spring's default 500) — unreachable with today's adapters, and a genuine server-side surprise rather than a client mistake if it ever did happen.
- **Idiom**: the `BGR24`→`BufferedImage` bulk-copy (`TYPE_3BYTE_BGR` + `DataBufferByte`) and explicit-`ImageWriter` JPEG encode mirror `adapter-cv-grpc`'s `GrpcDetectionPort` downscale/encode path (docs/main/CYCLES-PLAN.md §12, CP-b) exactly — independently reimplemented here since this module cannot depend on that adapter.
- **Wiring gap CLOSED (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave D)**: `StreamController`/`DeviceProbeController` no longer self-construct a `SnapshotJpegEncoder` field from `VisionApiProperties.defaults()` — both now take `SnapshotJpegEncoder` as an ordinary constructor parameter (`StreamController`'s ctor grew from 3 args to 4; `DeviceProbeController`'s from 1 to 2), supplied by `vision-app`'s `com.drones.vision.app.config.wiring.PublishWiring#snapshotJpegEncoder` bean — a real Spring `VisionApiProperties` (`...app.config.properties`, distinct type, same simple name — see that class's own javadoc for how `PublishWiring` tells the two apart) now maps across to this module's plain one. Both controllers' own unit tests (`StreamControllerTest`/`DeviceProbeControllerTest`) construct a `new SnapshotJpegEncoder(VisionApiProperties.defaults())` explicitly and pass it in, rather than relying on a field initializer.

**`LocalNetworkAddresses`** — `SystemNetworkController`'s one collaborator (see that controller's own javadoc in `controller/`), enumerating this host's site-local IPv4 addresses. Public for the same cross-package reason as `SnapshotJpegEncoder` (its caller is now in `controller/`, not `support/`) — still no interface, still no second implementation.

**`CapabilityParsing`** — moved out of `dto/` (docs/plans/active/LAYERING-REFACTOR-PLAN.md §7 row B: it's a helper, not a wire record — `dto/` stays wire-records-only). Public for the same cross-package reason; `RegisterDeviceRequest`/`CreateAssetRequest.DeviceSpec` (both `dto/`) call `CapabilityParsing.parse(List<String>, String)` across the package boundary now. Two overloads: `parse(names)` keeps the historical flat `[VIDEO]` default for callers with no protocol to hand, `parse(names, protocol)` defaults from the protocol. **Defaulting only** — an explicitly supplied list always wins, so no existing caller that names its capabilities changed behavior. `sim` is deliberately *not* given `TELEMETRY` even though `SimulatedTelemetrySource` would claim it: `DefaultSimulationService` already builds simulated devices with explicit capabilities, and the wizard's `synthetic` option promises "no telemetry" in as many words. `UpdateDeviceRequest` still calls the 1-arg form — it guards nulls before calling, so the default is unreachable there. See docs/plans/active/TELEMETRY-ONLY-ONBOARDING-CONTEXT.md §2 B2 for the silent failure this closes: a `mavlink` device registered without capabilities was created, looked healthy, and could never report a position or take a stick input, because `MavlinkTelemetrySource#supports` requires `TELEMETRY`.

**`DeviceOriginParsing`** (`support/`, docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R4) — a new helper in the same `support/` package as `CapabilityParsing`, same shape: a private `toOrigin(String)` loop matching `DeviceOrigin.values()` case-insensitively, throwing `IllegalArgumentException` listing the valid values on no match. Two entry points instead of `CapabilityParsing`'s protocol-aware pair, because `origin` has no analogous "infer from something else" signal: `parse(String)` — `null`→`LIVE` — used by `RegisterDeviceRequest`/`CreateAssetRequest.DeviceSpec` (a genuinely new device is `LIVE` unless told otherwise); `parseOptional(String)` — `null` stays `null` — used by `UpdateDeviceRequest`, whose partial-edit semantics need "not sent" distinguishable from "sent as LIVE".

**`OnboardingProperties`** (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1/O5) — the same "plain framework-free record, `vision-app` binds its `@ConfigurationProperties` mirror onto an instance of this" bridge `VisionApiProperties` establishes, one level down: just `inventoryWindow`/`requestTimeout` `Duration`s, since `vision-api` may not depend on `vision-app`'s own `VisionOnboardingProperties`. Populated by `OnboardingWiringConfiguration#onboardingApiProperties`.

**`RemediationOrchestrator`** (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1/O5) — composes `POST /api/assets/{assetId}/remediate`'s `{features, actions}` request into dispatched `RemediationService` calls plus a conditional re-probe; constructor-injected with `FeatureRequirementRepositoryPort`/`RemediationService`/`VehicleProfileService`/`ReadinessService`/`OnboardingProperties`. See its own javadoc for the full algorithm and, in particular, why it lives here rather than as a fourth `vision-flight` application service (a plan gap this wave could not close — that context was O3's, already closed) — flagged in the wave report at the end of this file.

**`VisionApiProperties`** (new, plain framework-free record — `@ConfigurationProperties` may only live in `vision-app`, docs/plans/active/LAYERING-REFACTOR-PLAN.md §1.3 rule 1) — the single documented source of truth for every `vision.api.*` tunable, with a `static defaults()` factory reproducing each literal it replaces byte-for-byte:

| Nested record | Fields (default) |
|---|---|
| `Snapshot` | `maxWidth` (480), `jpegQuality` (0.8) — `SnapshotJpegEncoder`'s tunables |
| `HlsProxy` | `connectTimeout` (5s), `requestTimeout` (15s), `errorBodyPreviewMaxChars` (200), `maxRedirectHops` (5) — `HlsProxyController`'s upstream `HttpClient` timeouts and buffer/redirect bounds (the last two added docs/plans/done/SCALE-100-PLAN.md §5 S7) |
| `Live` | `coalesce` (150ms), `heartbeat` (15s), `telemetryBuffer` (50), `eventBuffer` (300), `detectionBuffer` (300), `mapBuffer` (300), `sendTimeout` (3s), `bufferEviction` (60s) — `LiveUpdateRegistry`'s cadence, per-topic ring-buffer capacities, and per-connection dispatch bounds (`mapBuffer` renamed from `marksBuffer` to match the `marks`→`map` topic rename docs/plans/done/MAP-REWORK-PLAN.md §4.3 already made everywhere else; `sendTimeout`/`bufferEviction` added §5 S7) |
| `Paging` | `defaultLimit` (50), `maxLimit` (500) — the shared page-size shape several list endpoints use (e.g. `ActivityController`) |
| `Upload` | `maxImageBytes` (2097152) — `AssetImageController`'s upload cap |

**`SnapshotJpegEncoder`, `HlsProxyController` and `LiveUpdateRegistry` are all wired to a real, property-bound instance** (see the wiring-gap-CLOSED bullet above for `SnapshotJpegEncoder`; `HlsProxyController`/`LiveUpdateRegistry` as of docs/plans/done/SCALE-100-PLAN.md §5 S7 — `vision-app`'s `PublishWiring#snapshotJpegEncoder`/`#hlsProxySettings`/`#liveSettings`, each mapping the Spring-bound `...app.config.properties.VisionApiProperties` field-by-field onto this plain record's matching nested record). `AssetImageController`'s upload cap and the per-controller paging defaults (e.g. `ActivityController.DEFAULT_LIMIT`) are the only ones still reading their own local `private static final` constants — rewiring those to this record's `paging`/`upload` fields remains a documented gap, out of S7's scope. No `dispatch-threads` key exists for `LiveUpdateRegistry`'s per-connection write dispatch — surveyed for S7 and deliberately not added, since that executor is `Executors.newVirtualThreadPerTaskExecutor()`, which has no pool-size/thread-count concept a knob by that name could honestly govern (see that class's own `connectionWriteExecutor` field javadoc). New `vision.api.*` keys are documented, commented out, in `vision-app`'s `application.yaml`.

### `CvModelsController` — detection-model roster (docs/plans/done/CV-CONTROL-PLAN.md §4)

`GET /api/cv/models` for the Fly cockpit's model picker. Constructor-injected with a single plain `List<CvModelResponse>` bean (not a use-case port) — the same "raw collaborator, not a domain port" exception `HlsProxyController` already documents for `hlsProxyUpstreamBase`: `vision-app`'s `WiringConfiguration#cvModelRoster` supplies the actual roster content as a static, config-backed list, and this controller just wraps whatever it was given under `{"models": [...]}}`. Never errors — an empty roster (if one were ever wired) still 200s with `{"models": []}`.

**Deliberately not the dormant `ModelRegistryPort`, not a cv-service RPC** (docs/plans/done/CV-CONTROL-PLAN.md §D, a frozen decision, not an oversight): that port models versioned promote/rollback — a Phase-3 training-studio concern with zero implementations today — and wiring it now for a picker that only needs a display list would be over-building. A future real source, if the roster ever needs to change at runtime rather than deploy time, is either that port or a small `cv-service` roster RPC (its own `ModelRegistry` already knows the local checkpoint set); this is a documented seam in both this class's javadoc and `WiringConfiguration#cvModelRoster`'s, not built now.

### `SystemStatusController` — subsystem health rollup (docs/plans/done/SYSTEM-STATUS-PLAN.md §4.3, wave S2)

`GET /api/system/status` for the "is anything actually broken" operator question (UX-DESIGN §7.2's "honest status over optimistic status" doctrine). Constructor takes `List<SubsystemStatusPort>` (`vision-platform`) — Spring **type-collects** every bean of that type across the whole context into this one list (the same mechanism, not a single named collection bean, `vision-app`'s `SystemStatusWiring` relies on to add a provider without touching this controller); no `@Qualifier`/ordering guarantee on the list's element order, and none is needed since the response reports each subsystem independently and sorts nothing.

- **`status()`** maps every provider through a private `safeStatus(SubsystemStatusPort)` — `try { provider.status() } catch (RuntimeException e) { new SubsystemStatus(provider.getClass().getSimpleName(), sameId, Health.UNKNOWN, e.getMessage(), null, null) }`. **A throwing provider never fails the whole endpoint** — this is the one controller in this module whose entire job is to stay up and report honestly even when its own dependencies are unhealthy; catching per-provider (not e.g. a blanket try/catch around the whole `stream().map()`) means one bad subsystem never hides the other three's real answers.
- **`overall()`** is the worst `Health` across every reported subsystem, `Health.DISABLED` filtered out first (so an intentionally-off subsystem never taints the rollup), via a `private static final Map<Health, Integer> SEVERITY` (`EnumMap`: `OK`=0, `DEGRADED`=1, `UNKNOWN`=2, `DOWN`=3) — **a confirmed `DOWN` outranks an inconclusive `UNKNOWN`**, a deliberate call documented in this class's own javadoc (the plan does not specify the ordering; `Health`'s declaration order is *not* this ranking, see `Health`'s own javadoc, core/vision-platform/MODULE.md). Empty provider list, or every provider `DISABLED` → `Health.UNKNOWN`.
- **No `managerOnly`/scope gate** — readable by any authenticated user (or, with `vision.auth.enabled=false`, everyone). Deliberate: nothing in the response is a secret (health enum, a human sentence, a timestamp), and an operator who cannot yet act on a subsystem still benefits from seeing it's down.
- `SystemStatusControllerTest` (MockMvc `standaloneSetup`, house style) — 5 tests against `List<SubsystemStatusPort>` fakes (a `fixed(SubsystemStatus)` helper lambda): overall-is-worst-across-mixed-OK/DEGRADED, DISABLED-excluded-from-rollup, overall-UNKNOWN-with-no-providers, overall-UNKNOWN-when-every-subsystem-DISABLED, and a throwing-provider case asserting the exact `subsystems[1].health:"UNKNOWN"` / `.detail` (the raw exception message) / `overall:"UNKNOWN"` shape — none of the four *real* providers (`CvStatusProvider`/`MavlinkLinkStatusProvider`/`PublishStatusProvider`/`LiveUpdateStatusProvider`) are exercised here, deliberately: this test suite's job is the controller's own aggregation/exception-mapping logic, independent of any one provider's wiring quirks; each provider's own behavior is covered where it's cheap to construct (see each adapter module's own MODULE.md; `LiveUpdateStatusProviderTest`, this module, for the one whose collaborator — `LiveUpdateRegistry` — is cheap to build in a pure unit test).

### `DatasetController` / `LabelingController` — CV model-improvement training loop (docs/plans/done/CV-TRAINING-PLAN.md §3, Wave T4, delta'd by docs/plans/done/CV-TRAINING-V2-PLAN.md §5)

Both `@RestController @ConditionalOnProperty(prefix="vision.training", name="enabled", havingValue="true")` — the whole capture→correct→dataset→train loop is entirely absent from the app (every route 404s like any unmapped path) when `vision.training.enabled` is `false` (the default), mirroring `LiveController`'s own gating for `vision.live.enabled`. Both take `CurrentUser` and thread `userId()`/`scope()` into every call, exactly like `MarksController`/`AssetController`; `DatasetController#create` additionally threads `ownership()` (`datasetService.create(spec, currentUser.ownership(), currentUser.userId(), currentUser.scope())` — **not** a `GroupId` derived from `scope.groups()`, which would be the manager's whole visible subtree, not their own home group).

- **`DatasetController`** — CRUD over `Dataset` (`DatasetService`). Also takes `TrainingSampleRepositoryPort` directly (a read-only driven port, not a domain port `DatasetService` itself exposes) so every `DatasetResponse` can carry its `sampleCounts` (`{PENDING, LABELED, DISCARDED}`, every key always present even at zero) without growing `DatasetService`'s own surface for a controller-only presentation concern — the same "controllers call a driving-port service, driven ports only read-only" exception `AssetController` already documents for its own `TelemetryRepositoryPort`/`AssetImageRepositoryPort` collaborators.
- **`LabelingController`** — capture/replay-capture/list/image/label (`LabelingService`). Constructor is just `(LabelingService, CurrentUser)` — the manual export/download routes this controller used to carry are **deleted, not hidden** (docs/plans/done/CV-TRAINING-V2-PLAN.md §A: dataset delivery to the training host is now an implicit part of `POST /api/datasets/{id}/train`, over a gRPC upload — see `TrainingJobController`), and `DatasetService`/`DatasetExportPort` were injected only for those two handlers' scope check + zip resolution, so both collaborators dropped along with them. Capture (`POST /api/streams/{streamId}/samples`) passes only `{streamId, datasetId}` down to `LabelingService#capture` — the frame is read (`StreamService#latestRawFrame`, always the clean, full-resolution frame — no burn-in exists to strip out any more, docs/plans/done/CV-CLEAN-FEED-PLAN.md D-1) and JPEG-encoded (`TrainingFrameEncoder`) entirely inside `vision-application`; this controller never touches image bytes on capture. Replay capture (`POST /api/usages/{usageId}/samples`, docs/plans/done/CV-TRAINING-V2-PLAN.md §5) is its live counterpart: `{usageId, datasetId, atSeconds}` down to `LabelingService#captureFromReplay` as a `ReplayCaptureSpec` — this controller resolves nothing itself (no usage lookup, no frame extraction, no nearest-detection query); it reuses `SampleResponse` verbatim, the same wire shape live capture returns, so no new response type exists. It lives on this controller, not the ungated `UsageTimelineController`, because it is a training-gated action (`vision.training.enabled`), while that controller's own `GET /api/usages/{usageId}/recording`/`timeline` routes are unconditional.

**Wire shapes** (`com.drones.vision.api.dto`): `CreateDatasetRequest(name, targetCategory?, classes?)` → `toSpec(): DatasetSpec` (absent `classes` defaults `[]`); `DatasetResponse(id, name, targetCategory?, classes, status, createdAt, sampleCounts)` (`@JsonInclude(NON_NULL)` — `targetCategory` genuinely absent when the dataset isn't tied to one category; `status` is `OPEN`/`ARCHIVED` only — `EXPORTING` was deleted with the export step, docs/plans/done/CV-TRAINING-V2-PLAN.md §3, and nothing ever set it); `DatasetsResponse(datasets:[...])`/`SamplesResponse(samples:[...])` — wrapper objects (not bare arrays), mirroring `CvModelsResponse`'s `{"models":[...]}` shape rather than this codebase's more common bare-`List<T>` list-endpoint convention, per the plan's own distinct `DatasetsResponse`/`SamplesResponse` type names (a judgment call — see this file's own Status/T4 entry). `CaptureSampleRequest(datasetId)` → `toDatasetId()` (blank/malformed → 400). `CaptureFromReplayRequest(datasetId, atSeconds)` → `toDatasetId()` (same blank/malformed → 400 idiom); `atSeconds` is a primitive `double` so a JSON body omitting it deserializes as `0.0` (a legal, non-error offset) rather than "missing" — negative/non-finite/past-the-usage-window are all `ReplayCaptureSpec`'s/`LabelingService`'s own validation, not this DTO's. `AnnotationResponse(label, source, box:BoundingBoxResponse)`/`AnnotationRequest(label, source, box:BoundingBoxRequest)` (the latter's `toAnnotation()` matches `source` case-insensitively against `AnnotationSource` names, same `CapabilityParsing`/`SetLifecycleStateRequest` idiom); `BoundingBoxRequest(x, y, width, height)` — the request-side mirror of `BoundingBoxResponse`, new since every other bounding box in this module was response-only until now. `SampleResponse(id, datasetId, streamId, assetId?, capturedAt, width, height, status, labeledBy?, labeledAt?, annotations)` (`@JsonInclude(NON_NULL)` — `assetId`/`labeledBy`/`labeledAt` genuinely absent when unresolved/unreviewed, per this module's own DTO convention; the plan's own frozen-contract JSON example instead shows `labeledBy`/`labeledAt` serialized as literal `null` — a deliberate deviation, flagged in this file's own Status/T4 entry, not a miss) — shared verbatim by both capture routes above. `LabelAnnotationsRequest(status, annotations?)` → `toSpec(): LabelSpec` (`status` matched case-insensitively, `LabelSpec`'s own compact ctor backstops `LABELED`/`DISCARDED`-only; absent `annotations` defaults `[]`). **`DatasetExportResponse` is deleted** (docs/plans/done/CV-TRAINING-V2-PLAN.md §A) along with the two export routes.

**Error mapping is entirely `DatasetService`/`LabelingService`'s own exceptions surfacing through `ApiExceptionHandler`** — no controller-side translation beyond the DTO-boundary `IllegalArgumentException`s above: `AccessDeniedException` → 403 (used for *every* out-of-scope case on both controllers, including `DatasetService#get`'s deliberate non-hiding 403); `NoSuchElementException` → 404; `IllegalStateException` → 409 (unused by either controller today — no method here can currently throw it, but the mapping applies uniformly regardless); `IllegalArgumentException` → 400.

### `ModelRegistryController` — CV model registry (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9)

`@RestController @ConditionalOnProperty(prefix="vision.training", name="enabled", havingValue="true")` — same gating property as `DatasetController`/`LabelingController` above, no new flag. Sits behind `ModelRegistryService` (`vision-application`), which itself sits behind `ModelRegistryPort`, wired to `GrpcModelRegistryPort` (adapter-cv-grpc) sharing the same gRPC `ManagedChannel` `GrpcDetectionPort` uses — see station/vision-app/MODULE.md's "CV inference wiring"/"CV training loop wiring" for the wiring itself; nothing about that sharing is visible from this module, which only ever sees the `ModelRegistryService` interface.

- **`models()`** (`GET /api/cv/registry/models`) takes no `CurrentUser` argument at all — `ModelRegistryService#models()` itself takes no `userId`/`scope` (an unscoped, unaudited read, per that interface's own javadoc, "Scope") — so this is the one training-loop endpoint that doesn't thread the acting user down.
- **`promote()`** (`POST /api/cv/registry/models/{id}/promote`) builds a `ModelRef` directly from the path `{id}` plus the request body's `version` (`new ModelRef(id, request.version())` — blank either → `IllegalArgumentException` → 400, `ModelRef`'s own compact-constructor check, no controller-side validation needed) and threads `currentUser.userId()`/`currentUser.scope()` into `modelRegistryService.promote(...)`, exactly like `DatasetController#create`. Since `ModelRegistryService#promote` returns `void`, the response is built directly from the reference just promoted (`active` hardcoded `true`) rather than re-querying the registry — see this file's own Status/T9 entry for that judgment call.

**Wire shapes** (`com.drones.vision.api.dto`): `RegisteredModelResponse(id, version, active)` (no `@JsonInclude(NON_NULL)` — every field always present, mirroring `CvModelResponse`'s own "no nullable fields" posture) with a `from(RegisteredModel)` mapper; `RegisteredModelsResponse(models:[...])` — a wrapper object, same `{"models":[...]}` precedent `CvModelsResponse` set; `PromoteModelRequest(version)` — no validation of its own, relying entirely on `ModelRef`'s compact constructor.

**Not the same roster as `CvModelsController`**: that controller's `GET /api/cv/models` is a static, config-backed picker for the Fly cockpit's model dropdown (`cvModelRoster`, a plain `vision-app` bean, deliberately not backed by `ModelRegistryPort` — see that controller's own javadoc). `ModelRegistryController` is the dynamic registry — every model reference cv-service's own `Training/ListModels` RPC actually reports, live — and the one place a model gets promoted.

**Error mapping**: `AccessDeniedException` → 403 (caller may not manage the organization — `promote` only, `models()` never throws); `IllegalStateException` → 409 (cv-service refuses the promotion — an unknown model id, "rsync the artifact first," or no registry reachable at all); `IllegalArgumentException` → 400 (blank `id`/`version`). All three via the pre-existing, unmodified `ApiExceptionHandler`.

### `TrainingJobController` — CV training-job flow (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2's last backend wave, delta'd by docs/plans/done/CV-TRAINING-V2-PLAN.md §5)

`@RestController @ConditionalOnProperty(prefix="vision.training", name="enabled", havingValue="true")` — same gating property as every other training-loop controller, no new flag. Sits behind `TrainingJobService` (`vision-application`, `DefaultTrainingJobService`), which itself sits behind `TrainingPort`, wired to `GrpcTrainingPort` (adapter-cv-grpc) sharing the same gRPC `ManagedChannel` `GrpcDetectionPort`/`GrpcModelRegistryPort` already use — see station/vision-app/MODULE.md's "CV training loop wiring" for the wiring itself; nothing about that sharing is visible from this module. The last piece of the training loop: `DatasetController`/`LabelingController` capture/label samples, this controller uploads the dataset over gRPC **and** runs the fine-tune (the old, separate "press Export" step is gone — docs/plans/done/CV-TRAINING-V2-PLAN.md §A), `ModelRegistryController` promotes the result.

- **`start()`** (`POST /api/datasets/{id}/train`) now parses the path `{id}` into a `DatasetId` **at this edge** (`DatasetId.of(id)` — malformed → 400, this controller's own edge check, same idiom every other `{id}` path variable in this module already uses) before threading its canonical string form into the built `TrainingJobSpec` — `TrainingJobSpec#datasetId()`'s own field stays a plain string all the way to the gRPC boundary regardless (its own javadoc explains why), so this is a *parse-then-re-stringify* at the edge, not a shape change to the spec. Threads `currentUser.userId()`/`currentUser.scope()` into `trainingJobService.start(...)`, which now also runs a synchronous "does this dataset exist, is it in scope, does it have `LABELED` samples" pre-check (`TrainingJobService`'s own contract) — an unknown/out-of-scope/empty dataset is therefore a real 404/403/400 on this request thread, not a job that fails moments later. Once `start` returns, the handler immediately reads the fresh job back via `trainingJobService.job(jobId)` — guaranteed present per that method's own contract, so a missing read here would mean an internal invariant broke, not a normal 404 (an `IllegalStateException` guards it, never expected to trigger in practice).
- **`job()`** (`GET /api/training/jobs/{jobId}`) / **`jobs()`** (`GET /api/training/jobs`) are both unscoped, unaudited reads — like `ModelRegistryController#models`, any authenticated caller may poll job progress (`TrainingJobService`'s own javadoc, "Scope"). Neither takes `CurrentUser` for that reason.

**Wire shapes** (`com.drones.vision.api.dto`): `StartTrainingJobRequest(baseModel, epochs)` — no validation of its own, relying entirely on `TrainingJobSpec`'s compact constructor (a missing/absent `epochs` field deserializes to the primitive default `0`, which that same compact constructor already rejects as non-positive). `TrainingJobResponse(jobId, baseModel, datasetId, epochs, epoch, totalEpochs, loss, map50, state, message, startedAt)` — the flattened `TrainingJobView` (no `@JsonInclude(NON_NULL)`, every field always present, `state` is `JobState#name()` verbatim), with a `from(TrainingJobView)` mapper. `TrainingJobsResponse(jobs:[...])` — a wrapper object, same `{"models":[...]}}`-shaped precedent `RegisteredModelsResponse`/`CvModelsResponse` already set.

**A training run that fails mid-flight is never an HTTP error.** `DefaultTrainingJobService` catches every transport/runtime failure internally and records it as a terminal `JobState.FAILED` job — so `job()`/`jobs()` report it as `state:"FAILED"` with `message` carrying the failure reason, exactly like a normal `SUCCEEDED` completion. Nothing in this controller special-cases it.

**Error mapping**: `AccessDeniedException` → 403 (`start` only — caller may not manage the organization, mirroring `ModelRegistryController#promote`'s manager/admin gate exactly); `IllegalArgumentException` → 400 (`start` only — blank `baseModel` or non-positive `epochs`, `TrainingJobSpec`'s own compact-constructor checks); `NoSuchElementException` → 404 (`job` only — unknown `jobId`). All three via the pre-existing, unmodified `ApiExceptionHandler`.

### `com.drones.vision.api.demo` — the demo data package (`vision.demo.enabled`, default on)

A **strictly additive** package: one press of the console's green button fills an empty platform with
a fleet, a roster, assignments, geofence zones and marks. It introduces no port, decorates no
existing bean, changes no wiring, and disappears entirely — beans and routes — when
`vision.demo.enabled=false`. Everything it creates goes through the platform's own application
services, exactly as the corresponding page would; nothing here touches a repository port directly.

| Type | Collaborators | What it does |
|---|---|---|
| `DemoScenario` | `DemoPeople`, `DemoFleet`, `DemoOperations`, `AssignmentService`, `CurrentUser` (5, at the ceiling) | The orchestrator `DemoController` calls. Resolves the acting user once (ownership/actor/scope), runs the five passes, and assigns each asset to a pilot round-robin — plus a second pilot on every third asset, so the roster shows both 1:1 and shared assignments. |
| `DemoPeople` | `UserService`, `GroupService` | One reused `Demo Squad` group (parented under whatever root group already exists, so a MANAGER-scoped press works) + N users, call-signed `demo.falcon`…, every fourth a MANAGER. **DEV-ONLY**: all share the password `demo`, the same stance the `admin`/`admin` dev account takes (docs/plans/done/POSTGRES-ONLY-CONTEXT.md W1: seeded by `storage/persistence`'s `V90001__dev_accounts.sql` Flyway migration now, not the deleted `AuthSeedRunner`). Usernames are de-duplicated against existing ones (`demo.falcon2` on a second press), never a 409. |
| `DemoFleet` | `SimulationService`, `AssetService`, `AssetStreamService` (docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6e — split off `AssetService`; `AssetService` still backs `registeredNames`), `DemoVideoLibrary` | N simulated assets via `SimulationService#simulate` — each on its own home point around a ~1.1km ring at `50.45/30.52`, each flying its own 4-point LOOP route at its own speed/altitude, each backed by the next video in the library (round-robin; fully synthetic when the folder holds none). Call signs come from a fixed roster of real airframes (`FPV Pis-UN`, `FPV Vyriy`, `Skyfall Vampire`, `Bayraktar TB2`, `Leleka-100`, …) rather than `Demo NN`, so a demo reads like a fleet someone actually flies. Each candidate is checked against the names already registered (`includeDeleted`, so the archive view never shows two of one name) and the roster laps with a numeric suffix (`Furia 2`) once exhausted — a second press extends the fleet instead of minting duplicates. **Creation and streaming are two passes** — `simulate(autoStart=true)` aborts the whole call on a stream failure and orphans the asset it just created, so `startStreams` starts only the requested prefix through `AssetStreamService#startStream` and reports each failure instead. |
| `DemoOperations` | `GeofenceService`, `MarkService` | A `KEEP_IN` operating area + a `KEEP_OUT` no-fly box, and five marks (2 TARGET, HAZARD, FRIENDLY, POI). Both passes are name-idempotent — a second press adds neither a duplicate zone nor a duplicate mark. |
| `DemoVideoLibrary` | `@Value("${vision.demo.videos-dir:}")` | A **flat, non-recursive** listing of `$HOME/Videos` (override with the property): regular, readable files with a known video extension, sorted by name, sub-directories skipped rather than descended. A missing/unreadable folder yields an empty list, not an error. Its production constructor carries `@Autowired` because a package-private `Path` test-seam constructor is a second candidate — the same disambiguation `LiveUpdateRegistry` needs. |
| `DemoPlan` / `DemoSeedReport` / `DemoAsset` | — | The resolved plan (clamped to `[0, MAX]`, `startStreams ≤ assets`), what one run created, and one created asset. Deliberately **not** `…api.dto` types: the wire records (`DemoSeedRequest`/`DemoSeedResponse`/`DemoStatusResponse`) are nullable-everywhere and count-derived; the controller maps between the two. |

**Fault tolerance over atomicity.** Every pass catches per-item `RuntimeException`s into a
`Consumer<String> problems` sink rather than aborting: a run that cannot open three video streams
(no mediamtx) still leaves ten assets, ten users and a populated map behind, and reports the three
lines. `DemoSeedResponse#problems` is where they surface — a 500 from `/api/demo/seed` therefore
means something outside the seeded steps broke.

**Security posture.** Seeding is unauthenticated whenever `vision.auth.enabled=false` (like every
other route in that mode), and the accounts it creates share one well-known password — so the flag
is the deployment control: leave `vision.demo.enabled` on for local development, set it to `false`
anywhere else. `DemoScenario` logs a `WARN` on every press saying exactly that.

### Auth seams + DTOs (docs/plans/done/U-AUTH-PLAN.md wave 3)

**`CurrentUser`/`PrincipalResolver` live in `com.drones.vision.api.security`** (docs/plans/active/LAYERING-REFACTOR-PLAN.md §3/§7 row B — the token→`UserId` edge, still zero `org.springframework.security` dependency). **`CurrentUser` was rewritten around a seam.** It no longer takes an `Ownership fallback`; it takes a `PrincipalResolver` (interface, `security/`: `UserId userId()` + `Ownership ownership()` + `VisibilityScope scope()` — the last added by docs/plans/done/U-SCOPE-PLAN.md slice 2) and delegates. `vision-app` supplies the resolver — a fixed dev principal when `vision.auth.enabled=false` (identical to the pre-auth behavior), or one reading Spring Security's `SecurityContextHolder` when `true`. **This is deliberately how vision-api stays free of any `org.springframework.security` dependency** (the architecture rule for wave 3): the SecurityContext-reading lives entirely in vision-app; vision-api only knows the plain seam. Every controller still calls `currentUser.userId()`/`.ownership()` unchanged; scope-aware controllers additionally call `currentUser.scope()`. A convenience constructor `CurrentUser(Ownership)` (wrapping `PrincipalResolver.fixed(...)`) is kept so the standalone controller unit tests construct it from a plain `Ownership` exactly as before — and, crucially, **`PrincipalResolver.fixed(...)#scope()` returns `VisibilityScope.unbounded()`**, so every test that builds `CurrentUser(ownership)` (and the auth-off dev principal) keeps behaving as if scoping were off: a scoped read given an unbounded scope returns exactly the unscoped result. The production `PrincipalResolver` constructor is the `@Autowired` one so Spring never picks the convenience ctor.

**Visibility scoping (docs/plans/done/U-SCOPE-PLAN.md slice 2, feature 1).** `VisibilityScope` is an application type; vision-api already depends on vision-application, so `CurrentUser#scope()` threads it into the scoped read/command methods with no new dependency. `AssetController` scopes `list()`→`assetService.assets(scope, includeDeleted)` and `details`/every post-mutation render→`assetService.details(scope, id)` (an out-of-scope asset 404s exactly as an unknown id, hiding existence). `FleetController` scopes `summary(scope, includeArchived)`; `FlightCommandController` passes `currentUser.userId()` + `currentUser.scope()` to `returnToHome`.

**Authority, not visibility, guards the five asset writes (docs/plans/done/OPS-UX-PLAN.md §1, Wave C, C2).** `update`/`setState`/`delete`/`assignDevice`/`unassignDevice` each call a private `requireManageable(id)` — the renamed, extended successor of the old `requireInScope(id)` — which still re-reads the asset through `scope` first (an unknown-or-invisible asset still 404s *before* the mutation runs, hiding existence exactly as before), then additionally requires `scope.canManage(details.summary().asset().ownership())`, throwing `AccessDeniedException` (→403 via `ApiExceptionHandler`, already-existing mapping, no handler change needed) for an asset the caller can see but does not administer — the case a PILOT hits on their own assigned aircraft (seeing it is what lets them fly it; `canManage` is unconditionally `false` for `ASSIGNED_ASSETS`, so this is not a `getAsset`-then-branch race, it is structural). **Request-body validation still runs first** (a malformed edit/state/device-id is a 400 before either the visibility 404 or the authority 403, so a bad request never depends on the caller's scope; this ordering is what keeps `AssetControllerTest`'s `verifyNoInteractions` cases green). `create` gets its own gate, `!scope.canManageOrg()` → 403, checked before `request.toSpec()` runs (so an invalid body from a caller who also lacks authority still surfaces as 403, not 400 — authority is checked first, matching "does this caller need to know the body was well-formed" reasoning). With `vision.auth.enabled=false` every `CurrentUser` is `unbounded()`, so both `canManage`/`canManageOrg` are always `true` and every one of these gates is a no-op — proved by the full existing `AssetControllerTest`/vision-api suite staying green (see Status).

**`SessionAuthenticator`** (interface, `security/`) is the login/logout seam `AuthController` uses — `Optional<User> login(username, password, HttpServletRequest, HttpServletResponse)` (establishes a session on success) + `void logout(...)`. Only `jakarta.servlet` + domain types cross it; vision-app implements it (a no-op when auth disabled, a real session-establishing one when enabled). This is why login/logout run through a thin controller-plus-seam rather than Spring Security's own JSON form-login filter — it keeps spring-security out of vision-api, the explicitly-allowed alternative in the plan.

**`AuthController`** injects `AuthService`/`GroupService`/`CurrentUser`/`SessionAuthenticator` + `@Value("${vision.auth.enabled:false}")` (5 params, at the ceiling): auth **disabled** → every endpoint reports the dev admin (`MeResponse.devAdmin(...)`, `authEnabled=false`), login/logout are no-ops that never touch the seam; **enabled** → login verifies + starts a session, `me` reflects the authenticated user (an unauthenticated `me` never reaches the controller — Spring Security 401s it). `me` resolves each membership's group name via `GroupService.list()` (an unknown group falls back to its id string).

**New DTOs** (`com.drones.vision.api.dto`): `LoginRequest(username, password)` (no validation — a blank/unknown field is a 401, not a 400, the credential check being the one arbiter). `MeResponse(userId, username, displayName, email, memberships:[MembershipResponse{groupId, groupName, role}], topRole, authEnabled)` (`@JsonInclude(NON_NULL)` — `email`/`topRole` omitted when absent), with `from(User, groupNames, authEnabled)` and a `devAdmin(userId, groupId)` factory for the disabled-mode fixed admin.

## Conventions

- Controllers: constructor-injected with driving use-case interfaces only, plus (only where no driving use case exists for the read) driven ports used read-only: `StreamPublisherPort` (viewUrl/whepUrl), `TelemetryRepositoryPort` (telemetry trail), `DetectionRepositoryPort` (docs/plans/done/MVP1-PLAN.md §C8 bullet 3, `StreamController#detections`), and — docs/plans/done/MVP2-PLAN.md §E, E-a — `DetectionEventRepositoryPort` (`EventController`, same precedent), plus (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3) `AssetImageRepositoryPort` read-only in `AssetController` (populating `hasImage`). Never an adapter — ArchUnit-enforced in vision-app. **One documented exception**: `HlsProxyController` takes a raw `URI` (not a port) because it isn't a use-case-facing controller at all — it's a byte-level reverse proxy with no domain concept to depend on; `vision-app` supplies the `URI` as its own bean (`hlsProxyUpstreamBase`) so the controller itself stays a plain component-scanned bean like every other controller here, rather than being hand-constructed in `WiringConfiguration` (which would collide with component-scanning's own auto-registration of the same `@RestController` class — see station/vision-app/MODULE.md). **`whepUrl` has no such proxy sibling** (docs/plans/done/MVP2-PLAN.md L-a) — see the DTO note above for why. **A second documented exception** (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3): `AssetImageController` takes `AssetImageRepositoryPort` directly and calls it for **writes** too (`save`/`deleteByAssetId`), not just reads — storing/fetching/deleting bytes keyed by an asset id has no business rule beyond the wire-boundary concerns already enforced right in that controller (max size, allowed content types), so wrapping the port's three one-line pass-throughs in a same-shaped application service was judged ceremony with no behavior of its own; see that class's own javadoc for the full reasoning. `DeviceProbeController`, by contrast, is **not** an exception — `ProbeService` is a genuine driving-port service interface (vision-application), following the usual convention exactly.
- Mapping/validation happens on the DTO records themselves (`toRegistration()`, `toSpec()`, `toScanRequest()`) — no mapper library, controllers stay thin. The one shared exception is `support.CapabilityParsing` (public — see its own paragraph above for why), factored out purely to avoid duplicating the same case-insensitive `Capability` name lookup + error message between `RegisterDeviceRequest` and `CreateAssetRequest.DeviceSpec`.
- `@JsonInclude(Include.NON_NULL)` on every response DTO that has an optional field — omits absent fields from JSON entirely rather than serializing `null`.
- Ids in path variables are canonical UUID strings, parsed via `XId.of(String)` — its `IllegalArgumentException` on a malformed UUID surfaces as 400 through the same mapping as domain validation.
- 404-vs-400 for "unknown asset"/"unknown device": `AssetService`/`DeviceService` throw `NoSuchElementException` directly for an unknown id (→404 via `ApiExceptionHandler`) and `IllegalArgumentException` for genuine validation failures (ambiguous device, unrecognized state/category) — no controller-side translation needed; both map through the same `ApiExceptionHandler` uniformly. (Earlier documentation here described a since-removed `GetAssetDetailsUseCase`/`StartAssetStreamUseCase` split with a controller-level exception translation step — that layer no longer exists; `AssetController` calls `AssetService` directly.)
- **`FleetController#summary`'s `includeArchived` query parameter is a deliberate naming exception**, not an oversight: every other "include soft-deleted" list endpoint (`GET /api/assets`/`GET /api/devices`) names it `includeDeleted`, but docs/plans/done/MVP3-PLAN.md C-a's own spec names the fleet-summary one `includeArchived` — matching the MVP3 information-architecture rename (`LifecycleState.DELETED` assets live under the "Assets" warehouse page, described there as "archived"). Both flow to the exact same `AssetService#assets(boolean includeDeleted)` parameter underneath.
- **Logging: `System.Logger`, not SLF4J** — `private static final System.Logger LOG = System.getLogger(...)`, matching `HlsProxyController`'s pre-existing convention (itself matching `adapter-publish-hls`/`vision-application`'s `StreamPipeline` across the rest of the codebase). SLF4J appears in this codebase only in `vision-app`'s `LoggingEventPublisher`, a Spring-only devsupport bean; every plain controller/adapter class uses `System.Logger` instead. See the streaming-freeze observability paragraph in Status below for exactly what's logged and why.
- **`vision-domain` package layout changed (docs/plans/active/DOMAIN-SEPARATION-W1.md, wave W1.5a)**: the flat `com.drones.vision.domain.model`/`domain.port.out` packages this doc used to cite no longer exist. Domain types moved into per-context packages, `com.drones.vision.<context>.domain.model`/`.domain.port` (e.g. `TrackedObject`/`TargetLock` → `perception.domain.model`, `GeofenceZone` → `flight.domain.model`, `Mark` → `map.domain.model`, `DetectionEvent` → `events.domain.model`), except the shared-kernel types (every id type, `GeoPosition`, `BoundingBox`, `Ownership`, etc. — 17 since W1.6c added `Telemetry`/`FlightState`, docs/plans/active/DOMAIN-SEPARATION-W1.md §15) which moved to `com.drones.vision.kernel` instead — every context's domain may depend on those. This module's own REST/DTO/SSE behavior is unaffected; only the domain-package paths cited elsewhere in this doc were corrected to match. **W1.7a** (§16) later split `com.drones.vision.kernel` and its sibling `com.drones.vision.platform` (events, audit trail, visibility scope) out of `vision-domain` into their own Maven modules, `vision-kernel`/`vision-platform` — package names unchanged, so no import in this module needed touching; see those modules' own MODULE.mds.

## Authority split on `PATCH /api/assets/{id}`

`update` is the one asset write whose gate depends on the **body**, not just the caller
(docs/plans/done/OPS-UX-PLAN.md §1). `AssetEdit#changesManagedFields()` decides: a body touching only
`displayName`/`attributes` needs visibility alone, so a PILOT may rename the aircraft assigned to
them and edit its custom fields; a body touching `category` is fleet classification and needs
`scope().canManage(ownership)` — 403 otherwise. `setState`/`delete`/`assignDevice`/`unassignDevice`
always require manage.

**A 403 from any of these is a PILOT-only outcome, by construction.** For a `GROUPS` scope
`canManage(ownership)` and `includes(id, ownership)` are the same predicate, so a manager who can
see an asset can always manage it; one who cannot is stopped by `details()`'s existence-hiding 404
first. Only a scope that sees without managing — a pilot's — reaches the 403. A test asserting a
403 for an out-of-subtree *manager* is asserting a mock artifact, not behaviour (one did; it is now
`updateReturns404ForAManagerScopeOutsideTheAssetsSubtree`).

## Gotchas

- **`hasImage` is always `false` on the SSE `fleet` topic's live snapshot** (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3) — deliberate, not a bug: `LiveUpdateRegistry` already sits at the five-constructor-parameter ceiling (`.claude/skills/java-clean-code/SKILL.md` §3), and `AssetImageRepositoryPort` has no per-asset lifecycle event of its own to announce a change through the way `AuditTrailPort`/`EventPublisherPort` already do (both decorated in `vision-app` for exactly this purpose). A viewer that needs an accurate `hasImage` should read `GET /api/assets`/`GET /api/assets/{id}` instead, where `AssetController` computes it correctly per request. See `LiveUpdateRegistry#freshFleetEnvelope`'s own javadoc for the full reasoning.
- **Jackson 3** (Spring Boot 4, `tools.jackson.*`): DTOs still import `com.fasterxml.jackson.annotation.JsonInclude` — only the databind package moved; the annotations package is unchanged and shared with Jackson 2.
- **No static resources ship from this module.** `station/vision-api/src/main/resources` does not exist. `SpaResourceConfiguration` configures resource-serving paths, but nothing in vision-api's own build populates `classpath:/META-INF/resources/` etc. — the Angular frontend (`vision-web`) is a *sibling* module that only **vision-app** depends on and packages alongside this jar (see docs/plans/done/WEB-PLAN.md). Running vision-api's tests or jar standalone serves no UI.
- The old Phase-1 static console (`static/index.html` + `app.js`: register form, device table, scan section, hls.js player) referenced in older plans/docs is **gone from source**. `target/classes/static/**` in a not-yet-cleaned build directory is a leftover artifact from before this cleanup — don't trust it as a source-of-truth.
- Tests use MockMvc `standaloneSetup(new XController(mock(UseCase.class), ...)).setControllerAdvice(new ApiExceptionHandler())` — no Spring context, use-case ports mocked directly with Mockito, request bodies as raw JSON text blocks, assertions via `jsonPath`/Hamcrest. This means these tests do **not** catch DI wiring gaps — see vision-app's Status.
- **`PATCH /api/devices/{id}`/`PATCH /api/assets/{id}`/`DELETE /api/devices/{id}`/`DELETE /api/assets/{id}`/`GET …?includeDeleted=` predate CW-a.** They (plus their `UpdateDeviceRequest`/`UpdateAssetRequest`/`DeletionSummaryResponse` DTOs) were present in source since the very first commit but were **completely untested and undocumented** until CW-a (docs/main/CYCLES-PLAN.md §8) added MockMvc coverage for them and folded them into the pinned contract table above — don't assume "no tests exist for X" means "X doesn't work" without actually reading the controller source first, as the old copy of this table did.
- **CW-a removed `POST /api/devices/{id}/{de,}activate`/`/restore` and their `AssetController` equivalents** (also present, untested, since the first commit) in favor of the single pinned `POST .../state {state}` endpoint from docs/main/CYCLES-PLAN.md §8 — nothing else in the codebase referenced those paths (confirmed by `git grep` across all modules before removing them), and vision-web's `VisionApi` only ever calls `.../state`.
- **`EventController` (docs/plans/done/MVP2-PLAN.md §E, E-a) is polling, not SSE** — the simplest thing consistent with vision-web's existing `PollScheduler` pattern (already polls `/api/streams`/detections on an interval rather than holding a server push connection open); `sinceMs` exists specifically to keep steady-state polling cheap (a caller passes back the newest `lastSeen` it has already rendered). Neither `/api/events` nor `/api/streams/{id}/events` gets a 404 case: like `StreamController#detections` before it, there is no service method here to layer "unknown stream" validation onto, so an unknown stream id just yields an empty list — the only 400s are a malformed stream-id UUID and a non-positive `limit`.
- **`EventController` cannot cheaply surface generic `Event`s (e.g. `PIPELINE_ERROR`) through the same `/api/events` surface** — `EventPublisherPort` (the port `PIPELINE_ERROR`/`STREAM_STARTED`/etc. already flow through) is fire-and-forget/write-only with no matching read/query port at all (`LoggingEventPublisher`, its only implementation, just logs), and `Event`'s shape (`id, streamId, at, type, message, attributes`) has nothing in common with `DetectionEvent`'s (`label, peakConfidence, firstSeen, lastSeen, state, position`) to unify under one response DTO even if a store existed. Doing this properly needs a new `EventRepositoryPort`-shaped read side for `Event` itself — out of this task's scope; documented here (and in vision-domain's own Gotchas) so a future UI task asking for "pipeline errors in the events feed" scopes around it rather than assuming it's already covered.
- **Usage-scoped `GET`s are split across two controllers, not consolidated** (docs/plans/done/MVP2-PLAN.md §R, R-a): `AssetController` still owns `GET /api/usages/{usageId}/telemetry` (unwindowed, un-downsampled, no 404 on unknown usage — its original Phase-0 shape), while the new `UsageTimelineController` owns `GET /api/usages/{usageId}/timeline` (windowed, downsampled, 404s on unknown usage). No standalone "usages controller" existed to fold the new endpoint into, and giving `UsageTimelineController` its own single-dependency (`ReplayService`) constructor was cleaner than growing `AssetController`'s to five collaborators for an unrelated read. A future cleanup could consolidate both under one `UsageController`, but nothing forces it — the two endpoints solve genuinely different problems (raw dump vs. replay window) and neither depends on the other's presence.
- **`GET /api/usages/{usageId}/timeline` is wired since R-a2**: `WiringConfiguration.replayService` provides `DefaultReplayService(assetUsageRepositoryPort, telemetryRepositoryPort, detectionRepositoryPort)`; the earlier no-bean gap flagged by R-a is closed (docs/plans/done/MVP2-PLAN.md §R, R-a2).
- **`LiveController`'s constructor parameter needs `@Qualifier("liveUpdateRegistry")`, found by a real `NoUniqueBeanDefinitionException` at context-startup, not by inspection** (docs/plans/done/REALTIME-PLAN.md §4): `vision-app`'s `ApplicationServiceWiring#fleetLiveUpdatePort` (one of five near-identical `@Bean` methods since W1.6b, docs/plans/active/DOMAIN-SEPARATION-W1.md §15 — was one `liveUpdatePublisherPort` method before the port split) is declared to return `FleetLiveUpdatePort`, but its actual runtime instance (when `vision.live.enabled=true`) *is* the very same `LiveUpdateRegistry` singleton as the `liveUpdateRegistry` component-scanned bean — and every one of the other four `*LiveUpdatePort` bean methods resolves to that same instance too. Spring's autowire-by-type resolution, once both beans exist, sees **two** bean names whose actual class matches a plain, unqualified `LiveUpdateRegistry` constructor parameter — `expected single matching bean but found 2: liveUpdateRegistry,fleetLiveUpdatePort` (or whichever of the five is in scope). The `@Qualifier` names the bean by its component-scanned name explicitly, sidestepping the ambiguity; a plain direct `new LiveController(registry)` in a MockMvc test (bypassing Spring's container) is completely unaffected by this, since annotations on a constructor parameter are metadata the container reads, not something a bare `new` call ever consults.
- **`LiveUpdateRegistry`'s own constructor takes `ObjectProvider<AssetService>`, not a plain `AssetService`, to avoid a genuine circular bean dependency, found the same way** (docs/plans/done/REALTIME-PLAN.md §4): `DefaultAssetService` → `AuditTrailPort` → (when live-updates are enabled) `LiveUpdateAuditTrail` → `FleetLiveUpdatePort` → `LiveUpdateRegistry` → `AssetService` is a real cycle at bean-construction time (`UnsatisfiedDependencyException: ... Requested bean is currently in creation`), not merely a theoretical one — reproduced by the very first attempt at wiring this feature with a plain constructor parameter. Deferring the actual `.getObject()` call to `freshFleetEnvelope()` (only ever invoked well after the whole context has finished starting — on a connect/resume or a `publishFleetChanged()` dispatch) breaks the cycle without changing when a fleet snapshot is actually computed. See station/vision-app/MODULE.md's own wiring Gotcha for the `vision-app`-side half of this same story (why `AuditTrailPort`/`EventPublisherPort`/the five live-update ports are the seams that create the cycle in the first place).
- **Testing an `SseEmitter` controller with MockMvc relies on `ResponseBodyEmitter`'s own early-send buffering, not a documented MockMvc feature** (docs/plans/done/REALTIME-PLAN.md §4): `LiveUpdateRegistry#connect` sends its `connection` handshake + snapshot burst *synchronously*, inside the same call stack as the `@GetMapping` method, before that method returns the `SseEmitter` — this works (rather than throwing "emitter not yet initialized") because `ResponseBodyEmitter` (`SseEmitter`'s superclass) buffers any `send()` calls made before Spring's `ResponseBodyEmitterReturnValueHandler` attaches its internal `Handler` (which happens as part of processing the controller's return value, itself still inside the same synchronous call), flushing them the moment that attachment completes. `LiveControllerTest` (`mockMvc.perform(get(...)).andExpect(request().asyncStarted()).andReturn()`, then reading `MvcResult#getResponse().getContentAsString()` immediately) relies on exactly this — content sent this way is already in the `MockHttpServletResponse` buffer by the time `perform()` returns. Deltas published *after* the initial connect (from `publishFleetChanged()`/`publishEvent()`, which dispatch onto the registry's own real background scheduler — deliberately not stubbed out even in this test, to exercise the actual production code path) are **not** synchronous, so the test polls `getContentAsString()` in a bounded loop (up to 2s, 20ms between checks) rather than asserting immediately.

## Status

*Entries below predate docs/plans/active/DOMAIN-SEPARATION-W1.md §15's W1.6b and cite the god-port*
*`LiveUpdatePublisherPort` (and `WiringConfiguration`, its `vision-app` wiring class before a later*
*rename) by the names that were correct when each wave landed. The port no longer exists — deleted*
*and split into five per-context ports (`FleetLiveUpdatePort`, `TelemetryLiveUpdatePort`,*
*`DetectionLiveUpdatePort`, `MapLiveUpdatePort`, `EventLiveUpdatePort`), all implemented by*
*`LiveUpdateRegistry` still — see "API surface" above for the current shape. The behavioral claims*
*in every entry below are otherwise unaffected by the split.*

**Demo data package done** (`com.drones.vision.api.demo` + `DemoController` + three DTOs, 21 new
tests): `GET /api/demo` and `POST /api/demo/seed` fill an empty platform with 10 simulated assets
(video from `$HOME/Videos`, flat listing), 10 users in a `Demo Squad` group, pilot assignments, 2
geofence zones and 5 marks — verified live against a running app (10/10/14/2/5, 3 streams on the
air with real HLS playback, `problems: []`, second press extends to `Demo 11…` and skips the
existing zones/marks). Gated by `vision.demo.enabled` (default on; `false` → both routes 404). The
console's green "Fill demo data" button (`vision-web`, `features/demo/`) is the only caller. Nothing
pre-existing in this module changed — the package is purely additive, which is the whole point of
its separate subpackage.

docs/plans/done/NAV-IA-REDESIGN-PLAN.md **Wave 4, F8 done** (replay library, vision-api half): `UsageTimelineController`
gained a third endpoint, `GET /api/usages` (docs/extracts/design/10-replay.md's frozen wire contract), and two
new constructor collaborators — `UsageService` (vision-application, the new read side) and
`CurrentUser` (for `VisibilityScope` — a user must not see another org's flights, per the plan).
New DTO: `UsageSummaryResponse` (see its own DTO paragraph above). `limit` defaults 50 (`@RequestParam
Integer`, `DefaultUsageService.DEFAULT_LIMIT` when absent, same idiom `timeline`'s own `maxPoints`
already uses); `assetId`, when present, is parsed via `AssetId.of(...)` **before** the scoped call —
a malformed UUID is a 400 via the existing global `IllegalArgumentException` rule, no new exception
type. An unknown/out-of-scope `assetId`, or a scope excluding every returned row, is **not** an
error — this is a list endpoint, so it silently yields `[]`, the same posture `AssetController#list`
already takes for out-of-scope rows, deliberately not the 404-hides-existence convention a
single-resource scoped read (`AssetController#details`) uses. `timeline`/`recording` are unchanged
and remain unscoped (a pre-existing gap, out of this task's scope to close — see the controller's own
updated javadoc). `./mvnw -B -pl station/vision-api test`: **437/437 green** (up from 430; +7
`UsageTimelineControllerTest` cases: happy path incl. `assetName`/`durationSeconds` mapping, an open
usage omitting `endedAt`/`durationSeconds`, `limit` defaulting to 50, `limit` passed through, `assetId`
parsed and passed through, 400 for a malformed `assetId`, 400 when the service rejects a non-positive
`limit`).

**Green**, verified by `./mvnw -B -pl vision-application,station/vision-api test` (this module needs `vision-application`'s `ReplayService`/`UsageTimeline` installed first — same reactor-scoped command the R-a task ran): main and test sources compile with zero errors, **154/154** tests pass as of docs/plans/done/MVP2-PLAN.md E-a (up from 144 after V-a — see that paragraph below; `EventControllerTest` is 10 new, see its own paragraph below). Earlier snapshot for context (AssetControllerTest 43, StreamControllerTest 22, DiscoveryControllerTest 8, DeviceControllerTest 22, CategoryControllerTest 2, HlsProxyControllerTest 6, SimulationControllerTest 29, UsageTimelineControllerTest 10 — new, docs/plans/done/MVP2-PLAN.md §R, R-a). AssetControllerTest/DeviceControllerTest grew from 21/10 to 40/22 for docs/main/CYCLES-PLAN.md **§8's pinned warehouse contract (CW-a)**: `PATCH`/`POST .../state`/`DELETE`/`GET ?includeDeleted=` for both devices and assets, plus `POST`/`DELETE /api/assets/{id}/devices[/{deviceId}]` (assign/unassign) — covering every 400/404/409 case in the pinned table, including the `DELETED`→`DEACTIVATED` restore-via-state semantics and `DELETED`→`ACTIVE` refusal. `AssetSummaryResponse`/`AssetDetailsResponse`'s lifecycle field was renamed `state`→`lifecycle` and `DeletionSummaryResponse`→`AssetDeletionResponse` to match vision-web's pinned wire names exactly.

`UsageTimelineControllerTest` (docs/plans/done/MVP2-PLAN.md §R, R-a — flight replay API) covers: happy path (merged usage/telemetry/detections body), `fromMs`/`toMs` passed through as `Instant`s (windowing), `null`/absent passed through when the query params are absent (so the service's own default resolution — including "open usage → now" — is what actually runs; verified indirectly here via argument capture, exercised for real in `DefaultReplayServiceTest`), `maxPoints` passed through, response-order preservation for whatever the (mocked) `ReplayService` already thinned (downsampling determinism at this layer means "we don't re-sort/re-thin", not re-proving the algorithm — that's `DefaultReplayServiceTest`'s job), 404 for an unknown usage, 400 for a malformed usage id / a service-rejected non-positive `maxPoints` / a service-rejected `to`-before-`from`, and an open-usage case asserting `usage.endedAt` is omitted while `to` still comes back resolved. `ReplayService` is wired in `vision-app` since R-a2.

docs/plans/done/MVP2-PLAN.md **L-a** (WebRTC/WHEP viewing URL beside HLS) grew `AssetControllerTest` 41→43, `StreamControllerTest` 18→22, `SimulationControllerTest` 26→29 (9 new MockMvc tests total): each controller's stream-starting/listing endpoint gained a `whepUrl`-present case (asserting `$.whepUrl`/`$[0].whepUrl` alongside the pre-existing `viewUrl` assertion) and a `whepUrl`-absent case (`streamPublisherPort.whepUrl(...)` stubbed to `Optional.empty()`, JSON field asserted `doesNotExist`) — mirroring each file's existing `viewUrl` present/absent pair exactly. No test changes were needed purely to keep *existing* tests green: Mockito's default answer for an unstubbed `Optional`-returning method (`streamPublisherPort.whepUrl(...)` on every pre-existing test's mock) is already `Optional.empty()`, so `whepUrl` was already correctly omitted from every response the old tests assert on, without those tests needing to know `whepUrl` exists at all.

`AssetControllerTest` grew again, 40→41, for docs/main/CYCLES-PLAN.md **§11, CD-a** (`TelemetrySampleResponse` gains `deviceId`): one new test asserts samples from two different telemetry devices carry distinguishable `deviceId`s, and the existing single-sample telemetry test now also asserts the field. `SimulationControllerTest` grew 23→26 for docs/main/CYCLES-PLAN.md **§9, CU-a** (fully synthetic simulation — `StartSimulationRequest#videoPath` optional): the old `simulateReturns400ForBlankVideoPath`/`simulateReturns400ForMissingVideoPath` cases were replaced (a blank/absent `videoPath` is no longer an error, it now builds a fully synthetic spec) by tests covering the blank-videoPath/empty-body/telemetry-only-body 201 paths plus 400s for `transport=rtsp`/`mjpeg` combined with no `videoPath`.

`StreamControllerTest` grew from 13 to 18 for docs/plans/done/MVP1-PLAN.md **§C8 bullet 3** (`GET /api/streams/{streamId}/detections`): mapping/sort/default-limit/non-positive-limit-400/unknown-stream-empty-list, `DetectionRepositoryPort` mocked directly (same style as every other MockMvc test here).

docs/plans/done/ASSET-MODEL-PLAN.md's **M3** ("Asset-first API + UI", scope `station/vision-api/src/**`) is functionally complete on the API side: `AssetController`/`CategoryController` exist; `RegisterDeviceRequest`/`DeviceResponse` carry no `DeviceType` field; `DiscoveredDeviceResponse` already exposes `suggestedCategory` (not `suggestedType`); `StreamController`/`AssetController` parse ids correctly via `DeviceId.of`/`StreamId.of`/`AssetId.of`. (Earlier guidance describing this module as still red against `DeviceType` was stale — a `clean` rebuild disproves it; a non-`clean` `mvn test` here can be misled by pre-refactor `target/` artifacts, see vision-app's Gotchas.) The "UI rework" half of M3 is **not** done in this module — see the static-console gotcha above; that work belongs to vision-web (docs/plans/done/WEB-PLAN.md W2 done, W5 pending), not vision-api.

vision-app's wiring for these controllers is complete (docs/plans/done/ASSET-MODEL-PLAN.md M4 closed — `AssetWiringTest` asserts every bean); the earlier caveat about a non-bootable context is resolved. Remember these MockMvc tests still mock use-case ports directly, so future DI gaps surface only in vision-app's context tests, not here.

docs/plans/done/MVP2-PLAN.md **V-a** (glass-to-glass latency, proxy audit half — the encoder/mediamtx half lives in adapter-publish-hls/MODULE.md): `HlsProxyController` now forwards the upstream `Cache-Control` header instead of silently dropping it (see "Caching" bullet above); buffering and query-string pass-through were audited and found already correct, no change needed there. `./mvnw -B -pl station/vision-api test`: **144/144 green** (was 142) — `HlsProxyControllerTest` grew 6→8: `cacheControlIsForwardedFromUpstreamNotAddedOrDropped` (new coverage) and `llHlsBlockingReloadQueryParametersAreForwardedUntouched` (regression test for pre-existing, already-correct behavior). Every other controller's test count is unchanged by this task.

docs/plans/done/MVP2-PLAN.md **E-a done** (detection events, the vision-api thin ripple) — `EventController` (new, component-scanned like every other controller here), constructor-injected with exactly one collaborator (`DetectionEventRepositoryPort`, used read-only, same precedent `StreamController` already sets for `DetectionRepositoryPort`): `GET /api/events?sinceMs&limit` (cross-stream, newest-first by `lastSeen`, `sinceMs` a nullable polling cursor) and `GET /api/streams/{streamId}/events?limit` (one stream, same ordering, empty list rather than 404 for an unknown stream — mirroring `StreamController#detections`'s own precedent exactly). `DetectionEventResponse` (new DTO) maps `events.domain.model.DetectionEvent` field-for-field, `@JsonInclude(NON_NULL)` on `assetId`/`position`. **Polling, not SSE** — the simplest thing consistent with vision-web's existing `PollScheduler` pattern (see Gotchas above for the full reasoning). `./mvnw -B -pl station/vision-api test`: **154/154 green** (was 144) — `EventControllerTest` is 10 new tests: mapped-response shape + field omission for absent `assetId`/`position`, `sinceMs` passed through as an `Instant` (and `null` when absent), `limit` passed through and defaulted, 400 for non-positive `limit` (both endpoints), 400 for a malformed stream-id UUID, and the empty-list-not-404 case for an unknown stream. **One real gap found, not fixed here (documented instead)**: this surface cannot cheaply also carry `EventPublisherPort`'s generic `Event`s (`PIPELINE_ERROR` etc., docs/plans/done/MVP2-PLAN.md §U-info's ask) — see the Gotchas bullet above for exactly why, and what a real fix needs; E-b (events UI) should scope around this rather than assume pipeline errors show up in the same feed.

docs/plans/done/MVP2-PLAN.md **V-e done** (optional burn-in skip, the vision-api DTO ripple — LAN WHEP itself is compose/domain/application-only, no vision-api change): `StartStreamRequest`/`StartAssetStreamRequest` each gained a third/fourth nullable `Boolean overlayBurnIn` field, mirroring `confidenceThreshold`/`inferenceFps`'s existing per-stream-override shape exactly — a present value overrides `PipelineConfig#overlayBurnIn()`, absent/`null` keeps `PipelineConfig.defaults()`'s `true` (unchanged behavior). `StartAssetStreamRequest#mergeOntoDefaults()` needed no logic change, it already just delegates to `StartStreamRequest`'s implementation with one more field along for the ride. This was a deliberate, minimal DTO ripple (the task's own scope note allowed it "if the config genuinely ripples into a DTO") because the brief explicitly asked for the toggle to be **per-stream settable**, mirroring how `inferenceFps` already flows API→domain — a vision-app-level global-default property was considered and rejected (see station/vision-app/MODULE.md's own V-e section: no existing precedent for a property overriding a `PipelineConfig.defaults()` field, so none was invented here either). `./mvnw -B -pl station/vision-api test`: **155/155 green** (was 154) — `StreamControllerTest` gained one new test, `startMergesOverlayBurnInOverrideOntoDefaults` (JSON body `{"overlayBurnIn":false}` → `PipelineConfig#overlayBurnIn()` false, other fields still default), following the exact `startMergesRequestOverridesOntoDefaults`/`startMergesPartialOverrideKeepingOtherDefault` pattern already established for the other two override fields. No equivalent test was added to `AssetControllerTest`/`SimulationControllerTest` — neither has ever had a dedicated confidenceThreshold/inferenceFps pass-through test either, since both paths delegate to the identically-tested `StartStreamRequest#mergeOntoDefaults()`, so one shared-logic test suffices (same judgment call this module already makes for the two pre-existing override fields).

docs/plans/done/MVP3-PLAN.md **C-a done** (backend enablers for the command point — snapshots + fleet summary; see contexts/vision-perception/MODULE.md and contexts/vision-warehouse/MODULE.md for the two collaborators this module composes and station/vision-app/MODULE.md for wiring):

1. **`GET /api/streams/{streamId}/snapshot`** — `StreamController` gained one new endpoint (its one binary response), backed by the new `SnapshotJpegEncoder` (now `support/`, see its own subsection above) and `StreamService#latestFrame` (vision-application). 404 (`NoSuchElementException`) covers both "unknown stream" and "known but no frame published yet" — `latestFrame` makes no distinction, both are `Optional.empty()`; 400 for a malformed stream-id UUID, same `StreamId.of` idiom as every other path-variable id in this module. `Cache-Control: no-store` set explicitly (every poll wants the actual latest frame).
2. **`GET /api/fleet/summary`** — new `FleetController` (single collaborator, `FleetSummaryService`, mirroring `EventController`/`UsageTimelineController`'s one-focused-dependency shape) plus three new response DTOs (`FleetSummaryResponse`/`CategoryCountsResponse`/`AssetAttentionResponse`, see their own paragraph above). `includeArchived` (deliberately not named `includeDeleted` — see Conventions) defaults `false`.

`./mvnw -B -pl station/vision-api test`: **170/170 green** (was 155) — `StreamControllerTest` grew 23→27 (+4 snapshot cases: bytes/content-type/cache-control for a small already-JPEG frame, 404 for no-frame-yet, 404 for an unknown stream, 400 for a malformed id); new `SnapshotJpegEncoderTest` (5: small-JPEG passthrough unchanged, large-JPEG downscale, BGR24 downscale, BGR24 at-or-under-width full-resolution, unsupported-format throws); new `FleetControllerTest` (6: full shape mapping incl. every field, optional-field omission, `includeArchived` default/pass-through, 400 for a malformed `includeArchived` value, empty fleet). **Deliberate test-layer split**: `FleetControllerTest` proves shape/mapping/wiring only — the counts math, per-category accumulation, and battery/staleness derivation are proven against real inputs in `DefaultFleetSummaryServiceTest` (vision-application), the same split this codebase already makes between e.g. `UsageTimelineControllerTest` and `DefaultReplayServiceTest`.

**Deviations from the brief**: none in shape. One addition beyond the plan's minimal field list: `AssetAttentionResponse` carries `categoryName` alongside `categoryId` (not explicitly asked for) since `AssetSummary#categoryName()` was already available for free from `AssetService#assets` and saves the Command UI a second lookup — flagged here rather than silently added. `sourceState` was omitted exactly as the plan's own fallback instructed (see contexts/vision-warehouse/MODULE.md's `AssetAttention` entry and this file's own DTO paragraph for the full "why nothing honest to read today" reasoning) rather than faked.

**Streaming-freeze observability pass** (frozen-video symptom: a viewer requests a mediamtx path that doesn't exist, and the only signal was mediamtx's own 404 body, buried in a proxied HTTP response with nothing logged server-side):

1. **`viewUrl`/`whepUrl` construction audited end-to-end, no bug found.** `WiringConfiguration#streamPublisherPort` (vision-app) wires `MediamtxStreamPublisher(mediamtx.rtspBase(), properties.viewBase(), mediamtx.whepBase())`: `viewUrl` is built from `properties.viewBase()` (app-relative `/hls`, proxied by this module's `HlsProxyController`), `whepUrl` from `mediamtx.whepBase()` handed to the viewer verbatim (per its own contract, WHEP can't be proxied). `application.yaml`' `vision.publish.mediamtx.whep-base=http://localhost:18889` — the **docker-mapped** host port (`docker-compose.yml`: `"18889:8889"`), not mediamtx's own internal default `8889` — confirming the port collision this task was asked to check for does *not* exist in the current config. `HlsProxyController`'s own upstream base (`hlsProxyUpstreamBase` bean = `properties.mediamtx().hlsBase()` = `http://localhost:18888`, mapped from container `8888`) matches similarly. The mediamtx *publish* path (`MediamtxStreamPublisher#pushUrl`: `{rtspPushBase}/{streamId}`) and the *view* path (`viewUrl`: `/hls/{streamId}/index.m3u8`, proxied to `{hlsUpstreamBase}/{streamId}/index.m3u8`) share the same raw `streamId` segment — no prefix/suffix mismatch between what's pushed and what's requested.
2. **`HlsProxyController` gained request-level observability** (new `logProxyOutcome`, called from `proxy` right after the upstream fetch): DEBUG on every proxied request (`path -> upstream status`); WARN when the upstream status is non-2xx, including a ≤200-char preview of the upstream body (`bodyPreview`, package-private-static, new `ERROR_BODY_PREVIEW_MAX_CHARS`=200 constant) — this is what actually surfaces mediamtx's own `"no stream is available on path '...'"` 404 body server-side instead of only in the browser's network tab; WARN when the upstream `Content-Type` contains `text/html` ("wrong service on the HLS port?") — the exact signature of the uvicorn-on-8888 collision this module's config comments already warn about, now logged instead of silently mis-rendered.
3. **`StreamController` gained stream-lifecycle observability**: INFO on `start` (`streamId`, `deviceId`, `viewUrl`, `whepUrl` — logged once the response DTO is built, so it reflects exactly what the caller receives) and on `stop` (`streamId`). Not added to `DefaultStreamService` (vision-application) — that service never computes `viewUrl`/`whepUrl` itself (only `StreamController` calls `StreamPublisherPort#viewUrl`/`whepUrl`), and `vision-application` has no logging framework dependency of its own beyond the JDK's `System.Logger` (already used by `StreamPipeline` there for the same reason SLF4J isn't: the module must stay framework-free, and `System.Logger` needs no dependency at all).

`./mvnw -B -pl station/vision-api,video-output/publish-hls,vision-application test`: see this module's own test run in the session that made this change — no test assertions were added for the new log lines themselves (consistent with this codebase's existing precedent, e.g. V-c's lag logging in adapter-publish-hls: pure math/wiring is unit-tested, `System.Logger` output itself is not parsed by any test here).

docs/plans/done/REALTIME-PLAN.md **§4 done** (server-push data plane, vision-api half — backend of Phase R-c): new `com.drones.vision.api.live` package (`LiveUpdateRegistry`/`LiveConnection`/`LiveTopic`/`LiveTopicKind`/`LiveRingBuffer`, see that subsection above) plus new `LiveController` (`GET /api/live`, `PATCH /api/live/{connectionId}/topics`) and five new DTOs (`LiveEnvelopeResponse`/`LiveConnectedResponse`/`UpdateLiveTopicsRequest`/`LiveSubscriptionResponse`/`EventResponse`, see the "Live updates" DTO paragraph above). Reuses existing response DTOs (`AssetSummaryResponse`/`TelemetrySampleResponse`/`DetectionResultResponse`) as envelope payloads rather than inventing parallel shapes, per the task's own instruction — `EventResponse` is the one genuinely new payload DTO, since no existing shape maps a generic domain `Event`.

`./mvnw -B -pl vision-domain,vision-application,station/vision-api test`: **vision-domain 142/142** (unchanged), **vision-application 291/291** (was 285 — see that module's own MODULE.md for its half of this task), **vision-api 204/204 green** (was 170) — 34 new: `LiveRingBufferTest` (9, pure — capacity validation, FIFO eviction, collapse-to-latest replacement, `since`/`canResumeFrom` including the `everDropped`-vs-"just a high starting seq" distinction the class javadoc explains), `LiveTopicTest` (10, pure — parse/wire round-trips for every kind, blank/unknown-kind/missing-or-malformed-asset-id rejection, `parseTopicsParam`'s comma-splitting and blank-entry tolerance), `LiveUpdateRegistryTest` (10, pure — no Spring, a hand-rolled directly-executing `ScheduledExecutorService` test double makes `publishFleetChanged`/`publishEvent` synchronous; covers fleet-buffer population, the empty-fleet-buffer-triggers-a-live-query resume/snapshot case, telemetry coalescing into one envelope per asset per flush — including per-asset independence and a no-op flush when nothing's pending — detections' latest-only behavior, immediate (non-coalesced) event publishing, gap-free resume returning only newer entries, an entirely-unbuffered topic's resume returning empty, and `updateTopics` 404ing for an unknown connection), `LiveControllerTest` (5, MockMvc, `standaloneSetup` like every other controller test in this module — connect receives the handshake then the fleet snapshot; a later `publishFleetChanged()` delta reaches the same still-open connection, awaited via a bounded poll since the registry's *real* background scheduler dispatches it, deliberately not stubbed out; `Last-Event-ID` resume replays only what came after it, proven by publishing one event and reconnecting with the earlier connection's own last `seq`; `PATCH .../topics` adds a topic and 404s for an unknown connection). See this file's own Gotchas above for the two real bugs found and fixed while wiring this (the `LiveController`/`LiveUpdateRegistry` bean-type-ambiguity `@Qualifier` fix, and the circular-bean-dependency `ObjectProvider<AssetService>` fix) and the `ResponseBodyEmitter` early-send-buffering behavior `LiveControllerTest` relies on.

**Deviations from the brief**: none in shape. Two judgment calls, both documented in-code and in this file's Gotchas/live-package writeup: (1) coalescing is per-topic (shared across every subscribed connection), not literally per-connection, to keep exactly one resumable sequence per topic; (2) every topic except `fleet` has an honestly-limited "snapshot" (whatever's buffered since process start, not a fresh DB/repository query) — `fleet` alone gets a real live query as a fallback, since `AssetService#assets()` is already cheap and in-memory and the alternative (an empty fleet on every fresh connect right after a restart) would be actively misleading.

## Backend follow-ups batch (2026-07-24)

Two of the three items in this task's brief landed here:

1. **`devices`/`detection-events` live topics** (extending the R-c channel above): `LiveTopicKind`/`LiveTopic` each grew two more always-on constants; `LiveUpdateRegistry`'s constructor grew from 1 to 5 collaborators (`ObjectProvider<DeviceService>`/`ObjectProvider<StreamService>`/`StreamPublisherPort`/`ObjectProvider<DetectionEventRepositoryPort>`, three of them `ObjectProvider` for the same circular-bean-dependency reason `assetService` already was — see the `com.drones.vision.api.live` subsection above for the full mechanics). New DTO: `DevicesSnapshotResponse`. `EventController`'s existing polling endpoints (`GET /api/events`/`GET /api/streams/{streamId}/events`) are untouched — `detection-events` is an additional, live alternative source for the same underlying data (`DetectionEventRepositoryPort`), not a replacement; `EventsStore` (vision-web) can migrate to it at its own pace, the same relationship `telemetry`/`detections` already have with their own older polling equivalents.
2. **`CreateAssetRequest#deviceIds`** — `AssetController` itself needed **zero changes**; only the DTO (`toSpec()` now also parses `deviceIds` into `AssetSpec#existingDeviceIds()`) and its tests changed. See contexts/vision-warehouse/MODULE.md for the `DefaultAssetService#create`/`#assignDevice` shared-validation half.

Item 3 (simulated-feed resume-on-boot) is entirely vision-application/vision-app scope — nothing in this module changed for it.

**What the frontend should call instead of its old workaround**: `devices.ts#confirmCreateAsset` (vision-web) currently re-registers a brand-new device wrapping the existing one's connection details, then archives the original (`buildCreateAssetRequestForDevice` + `fleet.deleteDevice`) — its own doc comment says this is because "`POST /api/assets` cannot reference an existing device by id". That's no longer true: `POST /api/assets {"displayName","category","deviceIds":["<existing device id>"]}` (no `devices` array at all) assigns the existing device directly, with no new `Device` row and no archive step needed.

Tests: `./mvnw -B -pl vision-domain,vision-application,station/vision-api,station/vision-app test -DskipWeb`: **vision-api 213/213 green** (was 204) — `LiveUpdateRegistryTest` 10→15, `LiveControllerTest` 5 (rewritten for the two new topics, same count — `connectingReceivesTheConnectionHandshakeThenTheFleetAndDevicesSnapshots` replaces the old fleet-only test, looking up envelopes by `type` rather than a fixed index since the topic-replay loop iterates a plain `Set` with no guaranteed order between two topics that both contribute content on a fresh connect), `AssetControllerTest` 43→47 (+4: `deviceIds`-only create, combined `devices`+`deviceIds`, a malformed device id → 400, a 409 pass-through from the service).

**Deviations from the brief**: none in shape. One design decision, made explicit per the brief's own ask: `devices` is its own topic rather than extending `fleet`'s payload, specifically because `fleet` is asset-centric (`AssetSummaryResponse`) and `FleetStore`'s domain (raw `Device`/`ActiveStream`) shares nothing with it — conflating the two would grow every `fleet` envelope for consumers that only care about assets, and vice versa. Both lists inside `devices` still travel in one envelope (not two separate topics) specifically so a consumer never observes them from different moments — this is the "ONE resumable seq" requirement the brief asked for, satisfied for free by the channel's existing single shared counter, reinforced here by not letting the two halves drift into separate topics at all.

## Follow-on: `model` field on start-stream requests (2026-07-24)

The frontend's detection-model picker already sent a `model` id (e.g. `"yolo11n.pt"`, or a comma-composite `"yolo11n.pt,orion12l.pt"`) in the stream-start JSON body, but neither `StartStreamRequest` nor `StartAssetStreamRequest` had a matching field, so `mergeOntoDefaults()` silently dropped it and every stream ran `PipelineConfig.defaults().model()` regardless. Both DTOs gained a 4th/5th `String model` field (present/non-blank overrides `PipelineConfig#model()`'s `id`; `version` always stays the default's own) — see the DTO paragraph above for the exact mapping. `StartAssetStreamRequest#mergeOntoDefaults()` still just delegates to `StartStreamRequest`'s, one more field along for the ride, same as every prior addition to this pair.

**Checked, not assumed**: `ModelRef`'s compact constructor (vision-domain) only rejects a blank `id`/`version` — commas and dots pass through unvalidated already, so no domain-layer relaxation was needed for the comma-composite model-id case.

`./mvnw -B -pl station/vision-api test`: **216/216 green** (was 213) — `StreamControllerTest` grew 27→30: `startMergesModelOverrideOntoDefaults` (a comma-composite id carried through verbatim, version stays the default's), `startWithoutModelKeepsTheDefaultModel`, `startTreatsABlankModelAsAbsent`. No equivalent test added to `AssetControllerTest`/`SimulationControllerTest` — same precedent as `overlayBurnIn`/`confidenceThreshold`/`inferenceFps`: both paths delegate to the identically-tested `StartStreamRequest#mergeOntoDefaults()`, so one shared-logic test suffices. `./mvnw -B -pl station/vision-app test -DskipWeb` reconfirmed **82/82 green** (no vision-app source touched; nothing there constructs these DTOs directly).

## docs/plans/done/UX-REWORK-PLAN.md §U-d item 3 done (test-before-save probe + asset image, vision-api half)

Both of CLAUDE.md's pinned CONTRACT 1/CONTRACT 2 shapes landed exactly as specified — no contract deviation.

1. **CONTRACT 1 — `DeviceProbeController`** (new, one dependency: `ProbeService`, vision-application) — `POST /api/devices/probe`. `ProbeDeviceRequest`/`ProbeDeviceResponse` (new DTOs, see their own paragraph above). JPEG-encoding the grabbed frame reuses the existing `SnapshotJpegEncoder` directly (`support/`, no new dependency) rather than duplicating BGR24/JPEG-encode logic — the encoder already handles exactly the two `PixelFormat`s any registered `VideoSourcePort` produces.
2. **CONTRACT 2 — `AssetImageController`** (new, one dependency: `AssetImageRepositoryPort`, vision-domain, used for both reads and writes — see Conventions above for why this is a documented, deliberate deviation from the usual "driven ports read-only" rule) — `PUT`/`GET`/`DELETE /api/assets/{id}/image`. Enforces the 2MB cap and the `image/jpeg`/`image/png` content-type allowlist itself (both are wire-boundary concerns, not domain rules) via `IllegalArgumentException`/`PayloadTooLargeException` (400/413).
3. **`hasImage`** — `AssetSummaryResponse`/`AssetDetailsResponse` both gained the field (see the DTO paragraph above for the exact `from(..., boolean hasImage)` shape change); `AssetController` grew a 5th constructor dependency (`AssetImageRepositoryPort`, read-only, `existsByAssetId`) to populate it on every summary/detail response, still well under the five-parameter ceiling.
4. **Verified, not fixed: `PATCH /api/assets/{id}` attributes.** CLAUDE.md's "ALSO" instruction asked to confirm `UpdateAssetRequest#attributes` actually reaches `DefaultAssetService#update` end-to-end — it already did (`UpdateAssetRequest.toEdit()` passes `attributes` straight through to `AssetEdit`, whose compact constructor copies it, and `DefaultAssetService#update` applies it via `asset.withDetails(...)` when present). No code changed for this; a new MockMvc test (`updateAppliesAnAttributesEditIncludingRegistrationNumber`) and a matching `DefaultAssetServiceTest` case (contexts/vision-warehouse/MODULE.md) prove it, using the frontend's actual `registrationNumber` key as the example.
5. New `PayloadTooLargeException` (413) and reuse of `ProbeFailedException` (application-layer, 422) in `ApiExceptionHandler` — see the mapping table above. Both map through Spring Framework 7's non-deprecated `HttpStatus.CONTENT_TOO_LARGE`/`UNPROCESSABLE_CONTENT` constants (the historically-named `PAYLOAD_TOO_LARGE`/`UNPROCESSABLE_ENTITY` ones are deprecated on this Spring version — found by a compiler warning, not assumed) while keeping the wire `error` code string at the conventional HTTP-status name (`"PAYLOAD_TOO_LARGE"`/`"UNPROCESSABLE_ENTITY"`).

Tests: `./mvnw -B -pl vision-domain,vision-application,station/vision-api,storage/persistence,station/vision-app test`: **vision-api 236/236 green** (was 216) — new `DeviceProbeControllerTest` (6: mapped-shape success, optional-field omission, blank-protocol/malformed-uri 400s, unrecognized-protocol 400, probe-failure 422 with exact message pass-through), new `AssetImageControllerTest` (11: PUT success for both content types, 400 unsupported/missing content-type, 413 oversized, 400 empty body, GET success + 404 + 400, DELETE success (idempotent) + 400), `AssetControllerTest` 47→50 (+3: `hasImage` on list, `hasImage` on details, the attributes-patch regression test) plus its constructor grew a 5th mock. See vision-domain/vision-application/adapters/adapter-persistence/vision-app's own MODULE.mds for the domain type/port, the probe service, the JPA/bytea storage, and the wiring/end-to-end smoke tests.

**Deviations from the brief**: none in shape.

## docs/plans/done/FC-INTEGRATIONS-PLAN.md F-b done (flight-controller-aware telemetry, vision-api half)

New `FlightStateResponse` (`com.drones.vision.api.dto`, see its own DTO paragraph above) mirrors the plan's **frozen wire contract** field-for-field — `firmware, mode, armed, failsafe, gpsFixType, satellites, hdop, rssiPercent, armingBlockers` — since the frontend (vision-web, F-d, built in parallel against this exact shape) was already coded against it; nothing was renamed. `TelemetrySampleResponse` gained `flightState`/`extra` (the latter closing a real, pre-existing gap: `Telemetry#extra()` was silently dropped at this DTO before this task); `AssetAttentionResponse` gained `flightMode`/`armed`/`failsafe`, straight passthrough of `AssetAttention`'s own three new fields with no extra logic. **SSE needed no change at all** — `LiveUpdateRegistry#flushPending` already builds its telemetry envelopes via `TelemetrySampleResponse::from` (a method reference, not inlined field-by-field construction), so the two new fields flow onto the `telemetry:<assetId>` topic for free; same for `FleetSummaryResponse::from`/`AssetAttentionResponse::from` on the `fleet` topic. One DTO, three transports (`GET /api/usages/{usageId}/telemetry`, the replay timeline, and SSE) — no per-transport variant, matching how `TelemetrySampleResponse` already worked before this task.

Tests: `./mvnw -B -pl station/vision-api test`: **239/239 green** (was 236) — `AssetControllerTest` gained 3 new methods (`telemetryOmitsFlightStateAndExtraWhenTheSampleCarriesNeither`, `telemetryIncludesFlightStateAndExtraWhenTheSampleCarriesThem` — full round-trip through a `FlightState` with some fields known/some not plus a non-empty `armingBlockers`, `telemetryOmitsFlightStateWhenPresentButAllFieldsUnknownAndArmingBlockersEmpty` — `FlightState.empty()` still serializes the `flightState` object itself, since it's non-null, but every one of its own fields is individually absent); `FleetControllerTest`'s two existing attention-row tests (`summaryMapsCategoriesAndAssetsShape`/`summaryOmitsAbsentOptionalFieldsOnAnAssetRow`) gained `flightMode`/`armed`/`failsafe` assertions each (present vs. `doesNotExist()`), no new test methods — net delta is +3. `LiveUpdateRegistryTest`/`LiveTopicTest` needed no changes, confirming the "SSE is automatic" claim above wasn't just asserted but is actually exercised by the existing coalescing tests continuing to pass unmodified.

## docs/plans/done/OPS-CORE-PLAN.md G-b + R-b done (geofence REST surface; recording endpoint)

**G-b** — new `GeofenceController` (`com.drones.vision.api`, one dependency: `GeofenceService`, vision-application — zones are global reference data with no ownership/audit concerns, so this stays a one-collaborator controller like `CategoryController`/`FleetController`): `GET/POST /api/geofences`, `PUT`/`DELETE /api/geofences/{id}` exactly per the plan's frozen wire contract. No controller-side exception translation needed — `GeofenceService`'s own `NoSuchElementException` (unknown zone id on update/delete) and `GeofenceZoneSpec`'s `IllegalArgumentException` (unrecognized `kind`, polygon under 3 vertices, out-of-range coordinates/altitude) surface through the existing `ApiExceptionHandler` mapping unchanged. New DTOs: `GeofenceZoneResponse`/`GeofenceZoneRequest` (+ nested `PolygonPointRequest`) — see their own DTO paragraph above.

**R-b** — `UsageTimelineController` gained a second endpoint, `GET /api/usages/{usageId}/recording` (docs/plans/done/OPS-CORE-PLAN.md §R), reusing the controller's existing sole collaborator (`ReplayService`, which grew its own `recordingFor` method in vision-application) rather than standing up a new controller for one more read — see that class's own updated javadoc for the "can an existing controller own this" reasoning. New DTO: `UsageRecordingResponse` (see its own DTO paragraph above). 404/400 mapping mirrors `timeline`'s own exactly (unknown usage → 404 via `NoSuchElementException`, malformed UUID → 400 via `IllegalArgumentException`); a known usage with nothing to play back is **not** an error — `{"available":false}` still 200s, per the plan's "honest-cheap availability check" design.

Tests: `./mvnw -B -pl vision-domain,vision-application,station/vision-api,storage/persistence,station/vision-app -am test -DskipWeb=true`: **vision-api 254/254 green** (was 239) — new `GeofenceControllerTest` (11: mapped-shape list incl. empty/optional-field omission, create 201 with spec captured and mapped through, 400 for a sub-3-vertex polygon, 400 for an unrecognized `kind`, update 200 with mapped result, update 404 for an unknown zone id, update 400 for a malformed zone id, delete 204 + delete 404 for an unknown id + delete 400 for a malformed id); `UsageTimelineControllerTest` grew 10→14 (+4: `recording` 200 with `available:true` and the mapped url/start/durationSeconds, 200 with `available:false` and every other field omitted, 404 for an unknown usage, 400 for a malformed usage id). `vision-application`'s own `DefaultReplayServiceTest`/`adapter-persistence`'s new `GeofenceRepositoryTests`/`vision-app`'s `AssetWiringTest`/`PersistenceWiring(Configuration)Test` extensions are each documented in their own module's MODULE.md.

**Deviations from the brief**: none in shape. One judgment call, made explicit in both classes' javadoc: the recording endpoint joins the pre-existing `UsageTimelineController` rather than a new one, since it needs no collaborator beyond the one already there (`.claude/skills/java-clean-code/SKILL.md`'s "can an existing service/controller own this method instead of a new type?").

**Deviations from the brief**: none.

## docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1 wave 2 done (REST surface, vision-api half)

**`FlightCommandController`** (new, two collaborators: `FlightCommandService`, `CurrentUser` — mirroring `AssetController`/`SimulationController`'s own "service + acting-user" shape): `POST /api/assets/{id}/return-home`, no request body, `202` with `ReturnHomeResponse`. See the controller table above and `ReturnHomeResponse`'s own DTO paragraph.

**The 409 mapping needed no `ApiExceptionHandler` change** — read before writing anything, per the task brief's own instruction: `IllegalStateException`→409 is already there, global, unmodified, predating this feature (see the exception-handler table at the top of this file). `FlightCommandService` (vision-application) does the work of making sure *both* of this endpoint's refusal outcomes (no commandable device; the port's own unsupported-device/firmware `IllegalArgumentException`; the aircraft's own explicit refusal) arrive at this module as `IllegalStateException` — see that class's own MODULE.md entry for the translation. This endpoint is the first one in this codebase where an application-layer service deliberately translates an exception type specifically to land on a different `ApiExceptionHandler` branch than that type gets everywhere else; documented here and there so a future reader doesn't mistake it for an inconsistency.

**`StartSimulationRequest` gained `telemetryTransport`** (see its own DTO paragraph above) — exposes `SimulationSpec#telemetryTransport()`'s already-landed `MAVLINK` choice (vision-application, prior wave) through the REST surface for the first time; `DefaultSimulationService`'s own dispatch logic needed no change (already handled it).

Tests: `./mvnw -B -pl vision-domain,vision-application,station/vision-api,station/vision-app -am test -DskipWeb`: **vision-api 263/263 green** (was 254) — new `FlightCommandControllerTest` (6: 202 `ACCEPTED`, 202 `NO_ACK`, 404 unknown asset, 400 bad UUID never touching the service, 409 no commandable device, 409 aircraft refused — the latter two both driven by `FlightCommandService` throwing `IllegalStateException`, proving the endpoint needs no handler change); `SimulationControllerTest` grew by 3 (`telemetryTransport` defaults to `SIM` when absent, parses `MAVLINK` case-insensitively, 400 for an unrecognized value listing both valid names, mirroring `transport`'s own three tests exactly).

**Deviations from the brief**: none in shape.

## docs/plans/done/U-AUTH-PLAN.md slice 1 wave 3 done (session auth REST surface + CurrentUser rewrite, vision-api half)

`CurrentUser` rewritten around the `PrincipalResolver` seam (see "Auth seams + DTOs" above); new `PrincipalResolver`/`SessionAuthenticator` interfaces, `AuthController` (`/api/auth/login`/`logout`/`me`), and `LoginRequest`/`MeResponse` DTOs. **vision-api gained no `org.springframework.security` dependency** — the whole point of the seam. Every pre-existing controller is unchanged (they still call `currentUser.userId()`/`.ownership()`); the 4 controller tests that construct `new CurrentUser(ownership)` compile untouched thanks to the retained convenience constructor.

Tests: `./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app -am test -DskipWeb`: **vision-api 283/283 green** (was 277) — new `AuthControllerTest` (6, standalone MockMvc, seams mocked, no Spring Security): dev-admin `me`/`login`/`logout` with auth disabled (`authEnabled=false`), authenticated `me` resolving the user + group names with auth enabled, login success building `MeResponse`, login failure → 401. Every other controller test count unchanged.

**Deviations from the brief**: none. The one design choice — login/logout via a thin `AuthController` + `SessionAuthenticator` seam rather than Spring Security's own JSON form-login filter — is the plan's explicitly-allowed alternative, chosen to keep spring-security out of vision-api.

## docs/plans/done/U-SCOPE-PLAN.md slice 2 done (visibility scoping + assignment/activity/org-settings surface, vision-api half)

`CurrentUser` gained `scope()` (see "Auth seams" above); `AssetController`/`FleetController`/`FlightCommandController` scope their reads/command; `ApiExceptionHandler` maps `AccessDeniedException`→**403** (see the exception table). Four new component-scanned controllers, plus their DTOs:

- **`AssignmentController`** (4 collaborators: `AssignmentService`, `AssignmentRepositoryPort` read-only for `pilotsForAsset` — same "read a driven port directly" precedent `AssetController` sets, `AssetService` to 404 an out-of-scope pilots-list, `CurrentUser`): `PUT/DELETE /api/assets/{assetId}/pilots/{userId}` (204, idempotent, 403 out-of-scope grant), `GET /api/assets/{assetId}/pilots` (404s an out-of-scope asset), `GET /api/me/assignments`.
- **`ActivityController`** (`ActivityService` + `CurrentUser`): `GET /api/me/activity?limit=` — the caller's own audit entries (the "who" is always `currentUser.userId()`, never a path param), `limit` default 50 / cap 500 / floor 1 (clamped, not rejected — an activity feed has no empty-page error). Reuses `AuditEntryResponse`.
- **`UserAdminController`** (`UserService`, `CurrentUser`) / **`GroupAdminController`** (`GroupService`, `CurrentUser`): `GET/POST /api/users`, `POST /api/users/{id}/enabled`, `GET/POST /api/groups`. Every operation passes `currentUser.scope()` into the service, which derives management authority from the scope's kind (unbounded = ADMIN, groups = MANAGER, else PILOT/empty) and enforces the ADMIN/MANAGER management gate, the ≤-own-scope role/group grant rule, the ADMIN-only root-group / no-membership-user rules, and scope-filters the list reads (docs/plans/done/U-SCOPE-PLAN.md slice-2 cleanup). A refused operation surfaces `AccessDeniedException`→**403** via `ApiExceptionHandler`. With auth off (default) the dev principal's scope is unbounded, so every operation is permitted and the list reads are unfiltered — the default-off build is unchanged. The two standalone controller tests build `CurrentUser` from a plain `Ownership` (→ unbounded scope), so they assert wiring/status only; the gate itself is proven in the application-layer tests and the auth-on `OrgManagementAuthEnabledTest` (vision-app).

**New DTOs** (`com.drones.vision.api.dto`): `AssignmentResponse(assetId)` (element of `/api/me/assignments`) · `PilotResponse(userId)` (element of `/api/assets/{id}/pilots`) — both records-not-bare-string-arrays so the shape can grow · `UserResponse(userId, username, displayName, email, enabled, memberships:[{groupId, role}], topRole?)` (`@JsonInclude(NON_NULL)` for `topRole`; the admin roster view — id-oriented, no group-name resolution, never the password hash, distinct from `MeResponse`'s authenticated-self view) · `CreateUserRequest(username, displayName, email, password, memberships?:[{groupId, role}], enabled?)`→`toSpec():UserSpec` (blank/bad-field validation left to `UserSpec`/`User`; only membership `groupId` UUID + `role` case-insensitive parse handled here — an unknown role is 400; `enabled` defaults `true`) · `SetUserEnabledRequest(enabled)` · `GroupResponse(id, name, parentGroupId?)` (`@JsonInclude(NON_NULL)`) · `CreateGroupRequest(name, parentGroupId?)`→`toSpec():GroupSpec` (blank-name left to `GroupSpec`; only parent UUID parse here).

`./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app test -DskipWeb`: **vision-api 306/306 green** (was 283) — new `AssignmentControllerTest` (7, incl. the 403-mapping and 404-hides-existence cases), `ActivityControllerTest` (4, limit default/cap/floor), `UserAdminControllerTest` (4), `GroupAdminControllerTest` (3); `FlightCommandControllerTest` +1 (403 out-of-scope); `FleetControllerTest`/`AssetControllerTest` updated to the scoped 2-arg `summary`/`assets`/`details` signatures (mechanical — every stub now takes `any(VisibilityScope.class)`).

docs/plans/done/U-SCOPE-PLAN.md **slice-2 cleanup done (2026-07-30)**: the previously-deferred role gate + ≤-own-scope rule on user/group management are now enforced. `UserAdminController`/`GroupAdminController` gained a `CurrentUser` collaborator and pass `currentUser.scope()` into the (now scope-taking) `UserService`/`GroupService` methods; `AuthController#groupNameLookup` passes `VisibilityScope.unbounded()` (a system read of every group for the current user's own membership names). vision-api stays **306/306 green** (the two admin controller tests and `AuthControllerTest` were migrated to the scope-taking mocks with a `CurrentUser(Ownership)` → unbounded scope; no new vision-api tests — the gate is proven at the application layer and in vision-app's auth-on `OrgManagementAuthEnabledTest`).

## docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md Wave A done (per-asset flight stats REST surface)

New `AssetStatsController` (`com.drones.vision.api`, two collaborators: `AssetService`, `AssetStatsService` — kept as its own controller rather than folded into `AssetController`, which is already at the five-constructor-parameter ceiling): `GET /api/assets/{id}/stats` → 200 `AssetStatsResponse`. New DTO: `AssetStatsResponse` (see its own paragraph above).

**404 for an unknown asset, without duplicating existence logic in `AssetStatsService`**: that service (vision-application) deliberately never checks whether an asset id is real — aggregating "whatever usages exist" for an unknown id is honestly the same all-zero/all-`null` result a genuinely empty history produces. So this controller resolves the 404 itself by calling `AssetService#details(AssetId)` first, purely for its `NoSuchElementException` side effect — the exact same check `GET /api/assets/{id}` already relies on — before ever calling `AssetStatsService#statsFor`. No new `ApiExceptionHandler` mapping needed: `NoSuchElementException`→404 and `IllegalArgumentException` (a malformed UUID, from `AssetId.of`)→400 are both pre-existing, global, unmodified.

Tests: `./mvnw -B -pl station/vision-api test`: **287/287 green** (was 283) — new `AssetStatsControllerTest` (4: full-shape 200 with every optional present, 200 with every optional omitted for a zero-flight/no-telemetry asset, 404 for an unknown asset (asserting `AssetStatsService` is never called), 400 for a malformed UUID (asserting neither collaborator is ever called)). Every other controller test count unchanged.

**Deviations from the brief**: none in shape.

## docs/plans/done/CV-CONTROL-PLAN.md Wave D done (live per-stream CV control + model roster, vision-api half)

Depends on Waves B (`vision-domain`: `PipelineConfig#detectionEnabled` + the `defaults()` model-id fix) and C (`vision-application`: `StreamService#updateConfig`/`PipelineConfigPatch`/`UpdateOutcome`), both already green. This wave adds the REST surface over them, scope `station/vision-api/**` only (the roster's actual content/wiring is `vision-app`'s half — see its own MODULE.md).

- **`PATCH /api/streams/{streamId}/config`** (new, on `StreamController`) — `UpdateStreamConfigRequest` → `PipelineConfigPatch` (a straight 1:1 field mapping, `labelFilter`'s `List<String>`→`Set<String>`, `model`→`modelId`) → `streamService.updateConfig(streamId, patch)` → `UpdateStreamConfigResponse{streamId, modelReArmed}`. **No new `ApiExceptionHandler` mapping needed**: `NoSuchElementException`→404 and `IllegalArgumentException`→400 are both pre-existing, global, unmodified — `DefaultStreamService#updateConfig` throws exactly these two for "unknown/not-running stream" and "invalid merged value" respectively, so this endpoint slots into the existing table with zero handler changes, same posture the plan predicted. The frozen contract's reserved 409 slot produces no case in Stage-1 (no natural "cannot apply" state exists yet) — if `DefaultStreamService` ever throws `IllegalStateException` for one, the existing global `IllegalStateException`→409 rule already covers it with no further change here either. **No acting user threaded** — `updateConfig` follows the exact same stance `StreamController`'s class javadoc already states for `start`/`stop`: a stream's live config is transient plumbing, not an audited fleet change. Body is `@RequestBody(required = false)` + an `EMPTY` constant (whole body absent = no-op patch), mirroring `UpdateDeviceRequest`/`UpdateAssetRequest`'s existing PATCH-body idiom exactly.
- **Start DTOs**: `StartStreamRequest`/`StartAssetStreamRequest` each gained `labelFilter?:List<String>` and `detectionEnabled?:Boolean`, appended after the pre-existing `model` field (matching the frozen JSON's own field order) — same per-stream-override pattern `overlayBurnIn`/`model` already established (docs/plans/done/MVP2-PLAN.md §V, V-e; backend follow-up batch). `StartStreamRequest#mergeOntoDefaults()` folds `labelFilter` into `PipelineConfig#labelFilter()`'s `Set<String>` (an explicit empty array is a real "all labels" value, same as absent) and `detectionEnabled` straight onto `PipelineConfig#detectionEnabled()`; both default to `PipelineConfig.defaults()`'s own values when absent (empty set / `true`). `StartAssetStreamRequest#mergeOntoDefaults()` needed no new merge logic — it already just delegates to `StartStreamRequest`'s implementation with two more fields along for the ride, the same "one shared-logic test suffices" judgment call V-e made for `overlayBurnIn` (no independent `AssetControllerTest`/`SimulationControllerTest` coverage was added for these two fields either, for the same reason).
- **`GET /api/cv/models`** (new `CvModelsController`, see its own subsection above) — wraps a `List<CvModelResponse>` bean `vision-app` supplies, per the frozen §4 contract and the deliberate config-backed-not-`ModelRegistryPort` decision (§D).
- **Reconciled against the actual `PipelineConfig`/`StreamService` shapes already landed by Waves B/C** — no contract mismatch found: `PipelineConfig`'s 9-arg canonical ctor, `PipelineConfigPatch`'s five nullable fields, and `UpdateOutcome#modelReArmed()` all matched this wave's assumptions exactly (verified by reading the actual sources before writing any DTO, per the mandatory-MODULE.md-first workflow).

`./mvnw -B -pl station/vision-api test`: **330/330 green** (was 318) — 12 new tests: `StreamControllerTest` grew by 10 (`startMergesLabelFilterOverrideOntoDefaults`, `startWithoutLabelFilterKeepsTheDefaultEmptySet`, `startMergesDetectionEnabledFalseOverrideOntoDefaults`, `startWithoutDetectionEnabledKeepsTheDefaultTrue`, and six `updateConfig*` cases: hot-knob 200 with `modelReArmed:false` + patch-field assertions, `modelReArmed:true` on a changed `model`, labelFilter/detectionEnabled threading, 404 for unknown/not-running, 400 for an invalid value, and an absent-body no-op patch asserting the service received `PipelineConfigPatch.NOTHING`); new `CvModelsControllerTest` (2: full roster shape/order incl. `yolo26n.pt` first and the empty-array `defaultLabelFilter` fields, plus a never-errors-on-an-empty-roster case). Every other controller test count unchanged.

**Deviations from the brief**: none. The roster bean is a plain `List<CvModelResponse>`, not a small dedicated interface — matching `HlsProxyController`'s existing "raw collaborator" precedent exactly rather than inventing a new one for a single config-backed list.

## docs/plans/done/RC-CONTROL-PHASE1-PLAN.md R4 done (WebSocket transport, vision-api half)

Depends on R2 (`vision-application`: `ManualControlService`/`ManualControlSession`/`WatchdogListener`/`DefaultManualControlService`) and R3 (`drone-link/mavlink`: `MavlinkManualControlSender`), both already green. This wave adds the `/ws/manual-control` WebSocket transport — see its own subsection above (`/ws/manual-control` — the RC relay WebSocket transport) for the full surface: `ManualControlWebSocketConfig`/`ManualControlHandshakeInterceptor`/`ManualControlWebSocketHandler` + six frame DTOs, all new, flat in `com.drones.vision.api`/`com.drones.vision.api.dto`. `vision-app`'s half (the `manualControlService`/`mavlinkManualControlSender` beans, `VisionRcProperties`, and `SecurityConfig`'s `/ws/**` addition) is documented in station/vision-app/MODULE.md.

**Auth seam reused verbatim, no new one invented**: the handshake interceptor resolves identity through the exact same `CurrentUser`/`PrincipalResolver` seam every REST controller already uses (see "Auth seams + DTOs" above) — no WebSocket-specific auth code exists anywhere in this module, and vision-api still carries zero `org.springframework.security` dependency (ArchUnit-verified, `ArchitectureTest` stayed green with no new rule needed).

`./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app test -DskipWeb`: **vision-api 351/351 green** (was 330) — new `ManualControlWebSocketHandlerTest` (11: engage→engaged incl. channel-map shape, `ALREADY_ENGAGED`/`OUT_OF_SCOPE`/`NOT_COMMANDABLE`/`UNSUPPORTED` denied-code mapping, channels→ack, explicit release→released, `afterConnectionClosed` releasing without sending a frame — twice, once with and once without a prior engage, a genuine multi-threaded race test proving the per-connection send lock actually serializes concurrent `ack`/`watchdog` sends against a hand-fake `WebSocketSession` that detects overlapping `sendMessage` calls), `ManualControlHandshakeInterceptorTest` (2: stashes `UserId`/`VisibilityScope` from `CurrentUser` into the handshake attributes map, the defensive 401 path), `dto.ManualControlFrameDtoTest` (8: (de)serialization of both client-frame request records and all five server-frame response records against the frozen §4 JSON shapes). `vision-app` **142/142 green** (was 139) — see station/vision-app/MODULE.md's own Status entry.

**Docker**: `adapter-persistence`'s `PostgresDockerIntegrationTest` ran (not skipped) in the environment this was verified in — 71/71 green, unrelated to this wave but included since the scoped build command covers that module too.

**Deviations from the brief / judgment calls, documented honestly**:
- **`rateHz` is a fixed constant, not read live from the adapter's env knob** — see the transport subsection above; this module cannot depend on `adapter-mavlink` (ArchUnit), so there is no seam to read `MavlinkManualControlSender`'s actual configured `VISION_RC_OVERRIDE_HZ` through. If that knob is ever SITL-tuned away from its default (the plan's own step 6 explicitly anticipates this), the reported `rateHz` will silently drift from the real wire rate until this constant is updated by hand — flagged for the SITL/manual pass, not fixed here.
- **The exception→denied-code mapping is message-text based**, exactly as the plan's own §4 prose says ("message-text based, no typed subtypes") — `ManualControlService#engage`'s `IllegalStateException` covers three causes the application layer does not distinguish by exception type. The one addition beyond the plan's own three named cases: a same-*handle*, cross-*connection* "already active" `IllegalStateException` (from `DefaultManualControlService`'s own one-session-per-singleton-handle guard, distinct from this handler's own same-*connection* `ALREADY_ENGAGED` check) is also mapped to `ALREADY_ENGAGED` rather than falling into the `NOT_COMMANDABLE` catch-all — the more honest of the two, since it genuinely is "already engaged," just by a different connection.
- **Three additional, non-frozen `denied` codes** (`BAD_REQUEST`/`MALFORMED`/`UNKNOWN_TYPE`) handle malformed input (bad JSON, missing `type`, missing/malformed `assetId`, an unrecognized `type`) — outside the frozen `OUT_OF_SCOPE|NOT_COMMANDABLE|UNSUPPORTED|ALREADY_ENGAGED` enum, since §4 pins engage-refusal semantics only. Documented as a plain string on the wire, not a closed set, in `ManualControlDeniedFrame`'s own javadoc.
- **What remains for the SITL/manual pass (R5 is the web client)**: real glass-to-stick latency measurement, the watchdog-fires drill against a live SITL instance + RC transmitter, and confirming `VISION_RC_OVERRIDE_HZ`/`vision.rc.watchdog-timeout-ms` tuning against measured latency — none of this is reachable from an automated test in this repo (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md's own "SITL verification (user-run — not CI)" section).

## docs/plans/done/TACTICAL-MARKS-PLAN.md M4 done (tactical marks REST + live-envelope extension)

Depends on M2 (`adapter-persistence`/`vision-app`: `MarkRepositoryPort`, already green) and M3
(`vision-application`: `MarkService`/`DefaultMarkService`, already green — **revised** from the
plan's original group-scoped shape, see below). This wave adds the REST surface (new
`MarksController`) and extends the live SSE stack (new `marks` topic) — see the controller table,
the "Marks" DTO paragraph, and the `com.drones.vision.api.live` subsection above for the full
surface; `vision-app`'s half (the one-line `markService` bean) is documented in its own MODULE.md.

**M3 landed revised — the plan's group-scoped `list(VisibilityScope, GroupId)` is superseded.**
`MarkService#list()` takes **no arguments** and returns every `ACTIVE` mark deployment-wide,
newest-first; `update`/`delete` gate a `status→CLEARED` transition, an annotation edit, or a
delete to **creator-or-manager** (`AccessDeniedException`→403) with **no group-visibility gate in
front of it** — a PILOT's `VisibilityScope` carries no group at all (`ASSIGNED_ASSETS` kind), so a
group-filtered read would have hidden a pilot's own marks from themselves; see vision-application/
MODULE.md's own M3 entry for the full rationale. `MarksController#list()` therefore threads no
`CurrentUser#scope()` into `list()` at all (unlike `AssetController#list`), and `GeolocateMarkRequest`/
`CreateMarkRequest` still thread `currentUser.ownership()`/`currentUser.userId()` exactly per the
original plan.

1. **`MarksController`** (new, two collaborators: `MarkService`, `CurrentUser` — mirroring
   `AssetController`'s "service + acting-user" shape, not `GeofenceController`'s no-`CurrentUser`
   one, since a mark is owned/audited unlike a geofence zone): `GET/POST /api/marks`, `POST
   /api/marks/geolocate`, `PATCH`/`DELETE /api/marks/{id}` — see the controller table above for the
   exact status codes. **No controller-side exception translation** — `IllegalArgumentException`
   (bad UUID, unrecognized `kind`/`status`, incomplete telemetry)→400, `NoSuchElementException`
   (unknown mark id)→404, `AccessDeniedException` (creator-or-manager gate)→403 all flow through the
   pre-existing, unmodified `ApiExceptionHandler`.
2. **New DTOs** (`com.drones.vision.api.dto`): `MarkResponse`, `MarkPayload`, `CreateMarkRequest`
   (+ nested `PointRequest`, reused by `PatchMarkRequest`'s own `position` field), `GeolocateMarkRequest`,
   `PatchMarkRequest` — see the "Marks" DTO paragraph above for the exact shapes and the
   `kind`/`label`/`depressionDegrees` defaulting `GeolocateMarkRequest` performs (absent → `TARGET`/
   `"Contact"`/`GeoProjection.DEFAULT_DEPRESSION_DEGREES` respectively) — a **judgment call beyond
   the plan's own text**, which named only `depressionDegrees`'s default explicitly; defaulting
   `kind` too was reasoned from the plan's own M5 UX ("a **'Mark target'** action →
   `marksStore.geolocate(currentAssetId)`" — no kind prompt at all in the simplest call), flagged
   here rather than silently assumed.
3. **`marks` live topic** — `LiveTopicKind.MARKS`/`LiveTopic.MARKS` (+ `parse` case), always-on in
   `LiveUpdateRegistry#connect`/`#updateTopics` alongside `fleet`/`event`/`devices`/
   `detection-events`; the three `publishMark*` overrides (`LiveUpdatePublisherPort`'s `default`
   no-op methods, added by M1) each build one `LiveEnvelopeResponse{type:"marks",
   payload:MarkPayload{action, mark}}` and broadcast on `LiveTopic.MARKS` — see the `LiveTopicKind`
   bullet above and this file's own `com.drones.vision.api.live` subsection for the exact mechanics.
   `publishMarkCleared` forces `mark.status=CLEARED` in the payload via `mark.withStatus(CLEARED)`
   (a plain domain-record copy, not DTO-layer special-casing) even when the `Mark` passed in (the
   delete path, pre-deletion state) is still `ACTIVE`.
4. **`NoopLiveUpdatePublisher`** (vision-app devsupport) needed **no edit** — it inherits
   `LiveUpdatePublisherPort`'s `default` no-op `publishMark*` bodies unchanged, exactly as M1
   predicted.

`./mvnw -B -pl vision-domain,vision-application install -DskipTests` then `./mvnw -B -pl
storage/persistence,station/vision-api,station/vision-app test -DskipWeb`: **vision-api 373/373 green**
(was 351, +22) — new `MarksControllerTest` (17: mapped-shape list incl. empty, create 201 +
ownership/actor threaded correctly + 400 for an unrecognized `kind`, geolocate 201 with
`kind`/`label`/`depressionDegrees` defaulted when absent, geolocate 201 honoring explicit values,
geolocate 400 for incomplete telemetry (exact message pass-through), geolocate 400 for a blank
`assetId`, update 200 + captured-patch assertions, update 403 for neither-creator-nor-manager,
update 404 for an unknown id, update 400 for a malformed id, a present-blank-`note`-stays-present-
while-every-other-absent-field-stays-`Optional.empty()` case, delete 204 + delete 403 + delete 404 +
delete 400 for a malformed id); `LiveUpdateRegistryTest` grew by 5 (`publishMarkCreated`/
`publishMarkUpdated` each append one `marks` envelope with the matching `action`, `publishMarkCleared`
forces `status:"CLEARED"` for a still-`ACTIVE` input `Mark` and leaves it `CLEARED` when already
`CLEARED`, and the `marks` topic has nothing to replay on a fresh connect since it deliberately
carries no live-query seed — see below). **`LiveControllerTest`'s pre-existing exact-count assertion
(`connection handshake + fleet snapshot + devices snapshot` = 3 data lines) needed no change** —
`marks` contributes zero envelopes to a fresh connect precisely because it has no seed, so the count
stayed exactly 3, proving this wave's own "default-config suites stay 100% green" guardrail without
touching that test. **vision-app 149/149 green** (was 149 — see station/vision-app/MODULE.md's own M4
entry: this wave added zero new test *methods* there, only three more `assertNotNull` calls inside
`AssetWiringTest`'s existing one). `ArchitectureTest`'s 5 rules stayed green — `MarksController`/the
new DTOs/the live-stack extension all landed in vision-api (never vision-domain/vision-application),
and this module still imports zero `org.springframework.security` types.

**Docker**: `adapter-persistence`'s `PostgresDockerIntegrationTest` result varied across otherwise-
identical scoped-build invocations in the sandboxed environment this wave was verified in — one run
reported `Tests run: 0` (its `@EnabledIf(dockerAvailable)` gate disabled the class, even though a
plain `docker info` outside the Maven JVM succeeded), a later run reported **78/78 green** (real
Postgres Testcontainers round-trip, not skipped). Either way this is irrelevant to this wave's own
correctness — M4 touches no persistence code at all (M2 already covers `MarkEntity`/
`JpaMarkRepository`/`V10__marks.sql`); a docker-availability flake in this sandbox, not a regression.

**A deliberate, documented gap: the `marks` topic has no live-query seed**, unlike `fleet`/
`devices`/`detection-events`. Those three fall back to a real repository/service read when their
ring buffer is still empty on a fresh connect; seeding `marks` the same way would need a sixth
`ObjectProvider<MarkService>` constructor parameter on `LiveUpdateRegistry` — the same circular-
bean-dependency shape its four existing `ObjectProvider`s already carry (`DefaultMarkService`
itself depends on `LiveUpdatePublisherPort`, which resolves back to this very class), and that class
is **already at the five-parameter ceiling** (`.claude/skills/java-clean-code/SKILL.md` §3 — see
`LiveUpdateRegistry#freshFleetEnvelope`'s own javadoc, which declined an identical trade for
`AssetImageRepositoryPort`'s `hasImage`). `marks` instead joins `event` in the registry's
"honestly-limited" bucket: a fresh viewer relies entirely on `GET /api/marks` for the current
picture, with the live topic carrying only *deltas* from that point on — exactly the shape
docs/plans/done/TACTICAL-MARKS-PLAN.md's own M5 plan already calls for (`MarksStore`: initial GET, then merge
live deltas), so this costs nothing in practice. Documented in `LiveUpdateRegistry`'s own class
javadoc too, not only here.

**The frozen `marks` SSE envelope, verbatim** (the exact shape M5's `MarksStore` codes against):

```json
{ "seq": 128, "type": "marks",
  "payload": { "action": "created",
    "mark": { "id": "<uuid>", "kind": "TARGET", "label": "Bunker", "note": null,
              "position": { "latitude": 50.45, "longitude": 30.52, "altitudeMeters": null },
              "createdBy": "<uuid>", "createdAt": "2026-07-31T10:00:00Z",
              "status": "ACTIVE", "source": "MANUAL" } } }
```

`action` ∈ `{"created", "updated", "cleared"}`; `mark` is the exact `MarkResponse` shape `GET
/api/marks` returns (field names: `id`, `kind`, `label`, `note?`, `position`, `createdBy`,
`createdAt`, `status`, `source`) — one shape, two transports. The topic is always-on (every `GET
/api/live` connection auto-subscribes, like `fleet`/`event`/`devices`/`detection-events`) and
deployment-wide, matching `MarkService#list()`'s own unscoped shape and the pre-existing `fleet`/
`event` topics' own posture (a documented, accepted multi-tenant limitation, not this wave's to fix
— docs/plans/done/TACTICAL-MARKS-PLAN.md's own Open Q4).

**Deviations from the brief**: one, explicit and reasoned above — the plan's §5 text describes
seeding the `marks` buffer from an injected `MarkService` snapshot on an empty-buffer replay,
mirroring `fleet`/`devices`/`detection-events`; this wave omits that seed specifically to respect
the constructor-parameter ceiling `LiveUpdateRegistry` was already at, choosing the same "honestly
limited, GET covers the gap" posture the plan's own `event` topic already accepts — flagged, not
silently dropped. One further judgment call: `GeolocateMarkRequest#kind` also defaults to `TARGET`
when absent (see point 2 above), which the plan's own DTO shape text didn't explicitly call out.
Otherwise none — endpoint paths, status codes, DTO field names, and the live envelope match the
frozen contract exactly.

## docs/plans/done/CV-TRAINING-PLAN.md Wave T4 done (CV model-improvement loop, REST surface)

Wave T4: `DatasetController`/`LabelingController` plus their `com.drones.vision.api.dto` types (see
their own subsection above) exposing the already-green T2 (`vision-application`)/T3
(`adapter-persistence`/`vision-app`) layers over HTTP — gated by `vision.training.enabled` (default
`false`, `vision-app`'s `TrainingWiringConfiguration`, see that module's own MODULE.md).

New: `DatasetController`, `LabelingController`, `dto/{CreateDatasetRequest, DatasetResponse,
DatasetsResponse, CaptureSampleRequest, AnnotationRequest, AnnotationResponse, BoundingBoxRequest,
LabelAnnotationsRequest, SampleResponse, SamplesResponse, DatasetExportResponse}`. **No change to
`ApiExceptionHandler`, `CurrentUser`, `PrincipalResolver`, or any pre-existing controller/DTO** —
the frozen exception→status mapping (`AccessDeniedException`→403, `NoSuchElementException`→404,
`IllegalArgumentException`→400, `IllegalStateException`→409) already covered every case this wave
needed.

New tests: `DatasetControllerTest` (12), `LabelingControllerTest` (19) — MockMvc standalone setup
over mocked `DatasetService`/`LabelingService`/`DatasetExportPort`/`TrainingSampleRepositoryPort`,
`ApiExceptionHandler` attached, mirroring `MarksControllerTest`/`GeofenceControllerTest`'s style
exactly (constructor injection, per-endpoint 2xx/4xx cases, `ArgumentCaptor` verification of what
each controller threads down to its service).

`./mvnw -B -pl vision-domain,vision-application,storage/persistence install -DskipTests`
then `./mvnw -B -pl station/vision-api test -DskipWeb`: **404/404 green** (was 373, +31 — see above). No
pre-existing test was modified or broken.

**Deviations from the brief / judgment calls** (both flagged in the subsection above, not silent):
`DatasetsResponse`/`SamplesResponse` are wrapper objects (`{"datasets":[...]}}`/`{"samples":[...]}}`)
rather than bare JSON arrays — the plan names them as distinct types (unlike every other list
endpoint in this module, typed plainly `List<X>` in its own table row), read as a deliberate signal
mirroring `CvModelsResponse`'s own wrapped-list precedent. `SampleResponse`'s nullable fields
(`assetId`/`labeledBy`/`labeledAt`) are omitted-when-absent (`@JsonInclude(NON_NULL)`, this module's
own DTO convention) rather than the plan's illustrative JSON example's literal `null`s. Otherwise
none — endpoint paths, status codes, and DTO field names match the frozen §3 contract exactly.

## docs/plans/done/CV-TRAINING-PLAN.md Phase 2 T9 done (CV model registry, REST surface)

T9: `ModelRegistryController` plus its `com.drones.vision.api.dto` types (see its own subsection
above) exposing the already-green `ModelRegistryService`/`DefaultModelRegistryService`
(`vision-application`) and `GrpcModelRegistryPort` (`adapter-cv-grpc`) layers over HTTP — gated by
the same `vision.training.enabled` (default `false`) `DatasetController`/`LabelingController`
already use. The load-bearing half of this task was entirely on the `vision-app` side (the shared
gRPC channel `GrpcDetectionPort`/`GrpcModelRegistryPort` now both consume, and who owns its
shutdown) — see station/vision-app/MODULE.md's own "docs/plans/done/CV-TRAINING-PLAN.md Phase 2 T9 done" entry for
that decision record; nothing about it is visible from this module.

New: `ModelRegistryController`, `dto/{RegisteredModelResponse, RegisteredModelsResponse,
PromoteModelRequest}`. **No change to `ApiExceptionHandler`, `CurrentUser`, `PrincipalResolver`,
`CvModelsController`, or any pre-existing controller/DTO** — the frozen exception→status mapping
already covered every case this task needed (`AccessDeniedException`→403,
`IllegalStateException`→409, `IllegalArgumentException`→400).

New tests: `ModelRegistryControllerTest` (7) — MockMvc standalone setup over a mocked
`ModelRegistryService`, `ApiExceptionHandler` attached, mirroring `DatasetControllerTest`'s style
exactly.

`./mvnw -B -pl vision-domain,vision-application,cv/grpc install -DskipTests` then
`./mvnw -B -pl station/vision-api test -DskipWeb`: **411/411 green** (was 404, +7 — see above). No
pre-existing test was modified or broken.

**Deviations from the brief / judgment calls**: none against the frozen contract text (the brief
specified the two routes and their request/response shapes at a high level, not exact field names).
One judgment call, flagged in the subsection above: `promote`'s 200 response is built directly from
the just-promoted `ModelRef` (`active: true` hardcoded) rather than re-querying the registry, since
`ModelRegistryService#promote` returns `void` — cheaper than a round trip, always consistent with
what just happened, and the one other model whose `active` flag silently flips to `false` in the
same instant is a non-issue for a client that already knows only one model is ever active.

## docs/plans/done/CV-TRAINING-PLAN.md Phase 2 done (CV training-job flow, REST surface — last backend wave)

`TrainingJobController` plus its `com.drones.vision.api.dto` types (see its own subsection above)
exposing the already-green `TrainingJobService`/`DefaultTrainingJobService` (`vision-application`)
and `GrpcTrainingPort` (`adapter-cv-grpc`) layers over HTTP — gated by the same
`vision.training.enabled` (default `false`) every other training-loop controller uses. Like T9, the
load-bearing half of this task was on the `vision-app` side (adding `GrpcTrainingPort` as a third
consumer of the shared gRPC channel `GrpcDetectionPort`/`GrpcModelRegistryPort` already share) — see
station/vision-app/MODULE.md's own "docs/plans/done/CV-TRAINING-PLAN.md Phase 2 done" entry for that decision record;
nothing about it is visible from this module. This closes the training loop's backend: capture
(T4) → correct (T4) → export (T4) → **train (this wave)** → promote (T9).

New: `TrainingJobController`, `dto/{StartTrainingJobRequest, TrainingJobResponse,
TrainingJobsResponse}`. **No change to `ApiExceptionHandler`, `CurrentUser`, `PrincipalResolver`, or
any pre-existing controller/DTO** — the frozen exception→status mapping already covered every case
this task needed (`AccessDeniedException`→403, `NoSuchElementException`→404,
`IllegalArgumentException`→400).

New tests: `TrainingJobControllerTest` (9) — MockMvc standalone setup over a mocked
`TrainingJobService`, `ApiExceptionHandler` attached, mirroring `ModelRegistryControllerTest`'s
style exactly (constructor injection, per-endpoint 2xx/4xx cases including a `FAILED`-state poll
asserting 200 not an error, `ArgumentCaptor` verification of the `TrainingJobSpec` threaded down).

`./mvnw -B -pl vision-domain,vision-application,cv/grpc install -DskipTests` then
`./mvnw -B -pl station/vision-api test -DskipWeb`: **420/420 green** (was 411, +9 — see above). No
pre-existing test was modified or broken.

**Deviations from the brief / judgment calls**: none against the frozen contract text. One judgment
call at the time, since **closed** by docs/plans/done/CV-TRAINING-V2-PLAN.md W6 (see that section below):
`POST /api/datasets/{id}/train`'s path `{id}` used to be threaded into `TrainingJobSpec` as a raw,
unparsed string; it is now parsed into a `DatasetId` at this controller's edge first.

## docs/plans/done/CV-TRAINING-V2-PLAN.md W6 done (REST + wiring + persistence cleanup)

`vision-api` half of docs/plans/done/CV-TRAINING-V2-PLAN.md §5/§7's delta on top of the three "done" sections
above — the export step is deleted (not hidden), replay capture is added, and the training-start
edge now parses its dataset id, closing the "raw, unparsed string" deviation the previous section
flagged.

**`LabelingController`**: dropped `DatasetService`/`DatasetExportPort` (only ever used by the two
deleted export handlers) — constructor is now `(LabelingService, CurrentUser)`. Removed `export`/
`downloadExport`/`readBytes` and the two routes they served (`POST`/`GET
/api/datasets/{id}/export[/{exportId}]`) — genuinely gone, not gated off, so both now 404 like any
unmapped path. Added `captureFromReplay` (`POST /api/usages/{usageId}/samples`, new
`CaptureFromReplayRequest(datasetId, atSeconds)` DTO) reusing `SampleResponse` verbatim — see this
file's own subsection above for the full wire shape. Deleted `dto/DatasetExportResponse.java`.

**`TrainingJobController`**: `start` now parses `DatasetId.of(id)` at the edge (400 on a malformed
id) before threading its canonical string form into `TrainingJobSpec`, closing the deviation the
previous section flagged. Wire body/response shape unchanged; new failure modes (404 unknown
dataset, 403 out of scope, 400 no `LABELED` samples) all bubble up from `TrainingJobService#start`'s
own synchronous pre-check through `ApiExceptionHandler`'s pre-existing, unmodified mapping table — no
handler code was added.

**`DatasetResponse`**: stale `EXPORTING` javadoc reference fixed (`status` is `OPEN`/`ARCHIVED` only
— the enum constant was deleted in `vision-domain`, nothing ever set it).

**No change to `ApiExceptionHandler`** — every new failure mode (malformed usage/dataset uuid,
missing/negative/non-finite/past-window `atSeconds`, dataset/asset out of scope, unknown
usage/dataset/no-recorded-stream/no-recorded-frame, no `LABELED` samples) was already covered by the
existing `IllegalArgumentException`→400/`NoSuchElementException`→404/`AccessDeniedException`→403
mappings.

New tests: `LabelingControllerTest` gained `captureFromReplay*` cases (201; 400 malformed usage id/
blank dataset id/negative `atSeconds`/past-window; 403 out of scope; 404 unknown usage/no recorded
stream/unknown dataset/no recorded frame) and two "removed route now 404s" cases, replacing the
deleted `export`/`downloadExport` tests (19 → 26). `TrainingJobControllerTest` gained a malformed-id
400 case plus 404/400 cases for the service's new pre-check exceptions (9 → 12).

`./mvnw -B -pl vision-domain,vision-application,cv/grpc,video-output/publish-hls
install -DskipTests` then `./mvnw -B -pl station/vision-api test -DskipWeb`: **430/430 green** (was 420,
+10). No pre-existing test was broken; the two removed export tests were replaced, not silently
dropped.

**Deviations from the brief**: none. The plan's open question #1 (replay capture living on
`LabelingController` rather than `UsageTimelineController`, since it's a training-gated action) is
confirmed as implemented.

## docs/plans/active/LAYERING-REFACTOR-PLAN.md Wave B done (package reshuffle + `VisionApiProperties`)

Pure structural repackaging — **no route, JSON shape, status code, or default value changed.**
Finished the half-migrated split flagged in the plan's §0 (36 classes — 28 `@RestController`s + 8
others — sitting flat at `com.drones.vision.api` root, plus an empty untracked `controller/` dir).

**Moved**: every `@RestController` (28) → `controller/`. `exceptions/` → `exception/` (3 files,
singular per house rule). `CurrentUser`/`PrincipalResolver`/`SessionAuthenticator` → new `security/`
(the token→`UserId` edge). `ManualControlHandshakeInterceptor`/`ManualControlWebSocketHandler` → new
`ws/`. `HlsProxyController` → new `proxy/`. `LocalNetworkAddresses`/`SnapshotJpegEncoder` → new
`support/`; `CapabilityParsing` moved there too, out of `dto/` (it's a helper, not a wire record —
`dto/` stays wire-records-only, now 95 files). `dto/`, `live/`, `config/` package contents otherwise
untouched. See "Package layout" at the top of this file and each moved section's own paragraph for
the new locations.

**`SnapshotJpegEncoder` is now an instance, not a static utility** (the plan's own explicit
instruction, §7 row B) — constructor-injected with the new `VisionApiProperties` (see the
`support/` subsection above for its full field/default table). `StreamController`/
`DeviceProbeController` each hold a private field built from `VisionApiProperties.defaults()`
directly rather than taking one as a constructor parameter, since `vision-app`'s
`WiringConfiguration` does not yet supply a bean for it (out of scope for this wave — flagged as a
follow-up in that record's own javadoc). Every other `vision.api.*`-shaped tunable (`HlsProxyController`'s
timeouts, `LiveUpdateRegistry`'s coalesce/heartbeat/buffer capacities, `AssetImageController`'s
upload cap, per-controller paging defaults) still reads its own pre-existing local constant —
`VisionApiProperties` documents their current values as the single source of truth without rewiring
them yet, for the same "no `vision-app` bean available" reason.

**Superseded by docs/plans/active/LAYERING-REFACTOR-PLAN.md wave D**: the "no `vision-app` bean available" gap
above is closed — both controllers now take `SnapshotJpegEncoder` as a real constructor parameter,
supplied by `vision-app`'s `PublishWiring#snapshotJpegEncoder` bean (mapped from a Spring-bound
`VisionApiProperties` in `...app.config.properties` — a distinct class, same simple name, see that
class's own javadoc). See the `support/` subsection above, "Wiring gap CLOSED", for the current state.

**Further superseded by docs/plans/done/SCALE-100-PLAN.md §5 S7**: `HlsProxyController`'s timeouts
and `LiveUpdateRegistry`'s coalesce/heartbeat/buffer capacities — called out just above as still
reading their own local constants — are wired the same way `snapshot` already was, via
`PublishWiring#hlsProxySettings`/`#liveSettings`. Only `AssetImageController`'s upload cap and the
per-controller paging defaults remain unwired. See the `support/` subsection's `VisionApiProperties`
table above for the current, accurate field list.

**`UsageTimelineController`'s own duplicate `DEFAULT_MAX_POINTS=500` constant is deleted** (the
plan's own §2.3 duplicate-defaults table entry) — it now reads
`com.drones.vision.application.replay.DefaultReplayService.DEFAULT_MAX_POINTS` directly, a field
`vision-application`'s own Wave A left `public` specifically for this cross-module read (see that
class's own javadoc: "exposed here purely so ... any external reader still using this constant" can
read it by name). One necessary side effect: `DefaultReplayService.DEFAULT_MAX_POINTS` is **not** a
compile-time constant (its initializer calls `ReplayServiceSettings.defaults()`), so it cannot back
a `@RequestParam(defaultValue = ...)` annotation attribute (JLS requires a constant expression
there) — `timeline()`'s `maxPoints` parameter changed from `@RequestParam(defaultValue = "500") int`
to `@RequestParam(required = false) Integer`, with the fallback applied in the method body instead.
Behavior is byte-identical (absent `maxPoints` still resolves to 500); this is a mechanical
consequence of removing the duplicate, not a wire change.

**Visibility widening (unavoidable, per §1.4)**: `LocalNetworkAddresses`, `SnapshotJpegEncoder`, and
`CapabilityParsing` all went from package-private to `public` — each now lives in a different
package (`support/`) from its caller(s) (`controller/`, `dto/`), and Java has no cross-package
package-private access. Their test-seam constructors/helper methods that no test outside their own
package touches stayed package-private (e.g. `LocalNetworkAddresses`'s `NetworkInterfaceSource`
second constructor).

**Tests moved in lockstep, import-fixes only** — no test deleted, weakened, or added; the one
exception is `SnapshotJpegEncoderTest`, which switched from calling `SnapshotJpegEncoder.encode(...)`
statically to constructing an instance (`new SnapshotJpegEncoder(VisionApiProperties.defaults())`)
per the sanctioned static→instance conversion above, same assertions otherwise.

**`vision-app` mechanical import fixes** (nothing else touched there — that module is red for
unrelated reasons, an adapter-constructor-signature track outside this wave's scope): 6 main files
(`NoopSessionAuthenticator`, `AuthWiringConfiguration`, `DevPrincipalResolver`,
`SecurityContextPrincipalResolver`, `SecuritySessionAuthenticator`, `WiringConfiguration`) and 9 test
files (`DiscoveryWiringTest`, `LiveWiringTest`, `LiveDisabledWiringTest`, `AssetWiringTest`,
`PublishWiringTest`, `TrainingDisabledWiringTest`, `TrainingEnabledWiringTest`,
`CvEnabledWiringTest`, `config/PrincipalResolverTest`) had their `com.drones.vision.api.X` imports
redirected to the new `com.drones.vision.api.{security,proxy}.X` packages — one-line import changes
only, verified by re-compiling `vision-app` after installing the rebuilt `vision-api` jar: every
`com.drones.vision.api.*`-shaped error this wave could have caused is gone; the remaining
compilation errors there (`adapter-persistence`'s `PersistenceWiringConfiguration` referencing
not-yet-existing `Jpa*Repository` classes) are the pre-existing, unrelated parallel-track breakage
this wave was told not to touch.

New `vision.api.*` keys documented, commented out, in `vision-app`'s `application.yaml`
(appended after the existing `vision.application.*` block, matching its style).

`./mvnw -B -pl station/vision-api test`: **430/430 green**, unchanged from before this wave (verified via
`@Test`-annotation count at `HEAD` vs. the working tree: 430 both before and after — this was a pure
move, no test added or removed). `./mvnw -B -pl station/vision-api compile`/`test-compile`: clean. Build gate
was `-pl station/vision-api` only, per this wave's scope (not `-pl station/vision-api,station/vision-app`, since `vision-app`
can't compile right now for unrelated reasons).

## docs/plans/done/MAP-REWORK-PLAN.md Wave C done (the map as a COP — REST surface + scoped SSE)

Replaces the whole docs/plans/done/TACTICAL-MARKS-PLAN.md M4 surface: `/api/marks/**` and the `marks` SSE topic
are **deleted, not deprecated** (the SPA is this repo's only client and migrates in Waves D/E, so a
compatibility shim would have been dead weight from the day it was written).

### What went away

- `MarksController` + `MarksControllerTest` (deleted).
- `MarkPayload` (deleted — superseded by `MapEventPayload`).
- `LiveTopicKind.MARKS` / `LiveTopic.MARKS` (renamed to `MAP` and re-scoped; `"marks"` as a `topics`
  query value is now a 400).
- `LiveUpdatePublisherPort#publishMarkCreated`/`#publishMarkUpdated`/`#publishMarkCleared` (the Wave A
  domain replaced all three with `publishMapEvent(MapEvent)`); `LiveUpdateRegistry` implements the
  one method now, and the old "force `status=CLEARED` in the payload even for a still-ACTIVE mark"
  hack is gone with them — the domain carries a real `DELETED` action, so nothing has to lie.

### What replaced it

Three controllers rather than one `MapController`: `MapLayersController`, `MapMarksController`,
`MapDrawingsController` — the §4.1 table is 16 endpoints over three distinct resources, and this
module's convention is one controller per resource (`AssetController`/`GeofenceController`/…). Each
is a thin HTTP translation with `CurrentUser` as its identity seam; **all authorization lives in
`MapAccessPolicy`**, so there are no per-role HTTP rules for these paths — they ride the same
`authenticated()` rule for `/api/**` every other route already has (verified in `vision-app`, no
`SecurityConfig` change was needed).

`MapLayersController` is the one with four constructor dependencies (`MapLayerService`, `MarkService`,
`DrawingService`, `CurrentUser`): `LayerResponse#markCount`/`#drawingCount` are per-viewer display
facts, and `MapLayerService` deliberately does not compute them (it owns layers, not their contents).
Reading the two already-scoped lists and grouping by layer was preferred over widening that service's
contract for a display concern. Still inside the five-parameter ceiling.

### New DTOs (`dto/`, §4.2 verbatim)

`LayerResponse`, `GrantDto`, `CreateLayerRequest`, `RenameLayerRequest`, `SetGrantsRequest`,
`MarkResponse` (rewritten), `CreateMarkRequest` (rewritten), `PatchMarkRequest` (rewritten),
`GeolocateMarkRequest` (extended), `VerifyMarkRequest`, `PromoteMarkRequest`, `DrawingResponse`,
`CreateDrawingRequest`, `PatchDrawingRequest`, `PositionDto`, `MapEventPayload`, plus the
package-private `MapRequests` helper (the one place "an absent `layerId` means *resolve my default*,
a malformed one is a 400" is decided, shared by the four request records that need it).

Two decisions worth knowing:

- **`LayerResponse#grants` is `null` unless `myAccess == MANAGE`**, and always `null` over SSE.
  Who else can see a layer is itself privileged information. `@JsonInclude(NON_NULL)` omits the field
  rather than sending `"grants": null`.
- **`LayerResponse.forEvent`** (the SSE form) also nulls `myAccess` and zeroes
  `markCount`/`drawingCount` — all three are per-viewer facts, and one broadcast envelope reaches
  connections at different access levels, so there is no single correct answer. **Wave E must treat a
  `layer` event as "refetch `GET /api/map/layers`", not as a complete replacement row.**

A new `support/EnumParsing` replaces the hand-copied case-insensitive-enum-lookup loop that
`CreateMarkRequest#toKind`/`PatchMarkRequest#toStatus`/`CapabilityParsing` each spelled out; the map
contract parses **eight** enums off request bodies, and eight copies would have been eight places for
the error-message format to drift.

### `CurrentUser#viewer()` — the new seam

`PrincipalResolver` gained `MapAccessPolicy.Viewer viewer()`; `CurrentUser` delegates. vision-api
still carries **no** `org.springframework.security` dependency — both implementations live in
vision-app (see that MODULE.md). `PrincipalResolver.fixed(Ownership)` answers with an `ADMIN` viewer
over the fixed group, which is the map-side twin of its existing `VisibilityScope.unbounded()`: an
auth-disabled deployment (and every controller unit test) sees the whole picture, exactly as the
unscoped marks stack did before layers existed.

Deliberately **not** derived from `CurrentUser#scope()` — `VisibilityScope#includesGroup` is
hard-`false` for a PILOT's `ASSIGNED_ASSETS` scope, which would make every TEAM layer structurally
invisible to the primary FPV-operator persona. That is the exact trap docs/plans/done/MAP-REWORK-PLAN.md §1
records; see `MapAccessPolicy`'s own javadoc.

### `DemoOperations` migrated

`seedMarks(Ownership, UserId, …)` → `seedMarks(Viewer, …)`, and the demo marks now land on the **COP
layer explicitly** (`MapLayerService#copLayerId()`), not on whatever default layer `LayerResolver`
would pick for the pressing user — a demo exists to show the *shared* picture, and marks on the
presser's own TEAM/PERSONAL layer would be invisible in exactly the other-role windows a demo is
being shown in. Their affiliations follow §2.2's migration table, so a freshly-seeded demo and a
migrated deployment show identical symbology (`TARGET→HOSTILE`, `HAZARD→UNKNOWN`, `POI→NEUTRAL`, and
the old `FRIENDLY` kind → `UNIT`+`FRIENDLY`).

### Tests

`./mvnw -B -pl station/vision-api test`: **521/521 green** (was 460). Net +61: `MarksControllerTest` (22)
deleted; `MapLayersControllerTest` (18), `MapMarksControllerTest` (30), `MapDrawingsControllerTest`
(18), `LiveMapScopingTest` (4), `MapVisibilityTest` (8) added; `LiveUpdateRegistryTest`'s three
`publishMark*` cases became five `publishMapEvent` ones (incl. one asserting a layer event never puts
`grants` on the wire); `LiveControllerTest`/`DemoScenarioTest`/`ManualControlHandshakeInterceptorTest`
updated for the new constructor/method/interface shapes.

`LiveMapScopingTest` is the one that matters: two real SSE connections, two different viewers, one
real `LiveUpdateRegistry` (real ring buffers, real background dispatcher). It proves a mark on one
team's layer reaches that team's connection **and never appears anywhere in the other's stream**, a
COP-layer mark reaches both, the unscoped topics (`fleet`/`devices`) are unaffected by the map
filter, and a `Last-Event-ID` resume replays only what the *resuming* viewer may see.

**Two bugs the new tests caught before they shipped**, both in code written this wave: the
`MapVisibility` negative-refresh window was anchored to the entry's fill time instead of the last
miss-triggered refresh (so a just-created layer was invisible to its own creator for a full second),
and the SSE assertions were originally positional — the connect burst writes one envelope per
subscribed topic and the topic set is unordered, so "the last data line" was never a stable handle
(now selected by content).

**Deviations from the frozen §4 contract**: none in shapes or status codes. One thing the plan left
implicit and this wave had to settle: **the 403-vs-404 split**. Reads hide (an invisible layer, mark
or drawing is simply absent from its list), commands are honest (acting on something you cannot see
is a 403, not a hiding 404) — inherited unchanged from Wave B's frozen service javadocs, and the same
stance `FlightCommandService` already takes.

## docs/plans/done/TRACKING-PLAN.md wave T6 done (tracking REST surface — PATCH, tracks, trackers, SSE)

`./mvnw -B -pl station/vision-api test`: **551/551 green** (was 532 — +19: `StreamControllerTest` +17,
`CvTrackersControllerTest` 2 new). Nothing pre-existing changed shape: the two response DTOs gained
one nullable field each under `@JsonInclude(NON_NULL)`, so an untracked stream's JSON is byte-for-byte
what it was, and that is asserted (`anUntrackedDetectionsPayloadIsByteIdenticalToThePreTrackingWire`).

New: `CvTrackersController` (`GET /api/cv/trackers`), `StreamController#tracks`
(`GET /api/streams/{streamId}/tracks`), the `tracking` object on `PATCH .../config` and on both
start-stream bodies, and eight DTOs (see the DTO section above).

**Two judgment calls, both flagged rather than buried — and both since resolved.** They are kept
here because the reasoning is what the follow-up acted on; see "T3/T6 follow-up" at the end of this
file for what the code does now.

1. **`StreamController#updateConfig` merged a partial `tracking` object onto a readback base.** The
   application layer's fold (`DefaultStreamService#foldTracking`, wave T3) replaced mode/engine/every
   cadence *wholesale* whenever a `tracking` object was present, while the SPA (wave T7) sends **one
   knob at a time** — `{"tracking":{"engineId":"ncc"}}`, `{"tracking":{"lock":{"release":true}}}`,
   `{"tracking":{"verifyEveryMillis":5000}}`. This edge's only readback was
   `StreamService#trackingStats` (mode + the engine actually serving), which left the five cadence
   knobs with no source at all: changing *two different* cadences in sequence lost the first.
   **Resolved** — the fold is now per-field in the application layer (`TrackingConfigPatch`), and the
   readback workaround is deleted. This controller passes the body through and reconstructs nothing.
2. **`AssetController`'s constructor was six arguments**, one past
   `.claude/skills/java-clean-code/SKILL.md` §3's ceiling, for the `TrackingConfig` deployment seed;
   `StreamController` went 4→5 for the same collaborator. **Resolved** — the seed moved beside
   `StreamPipelineSettings` in the application layer, where every other stream-start setting already
   lives, so both controllers gave the argument back (**6→5** and **5→4**) and every start path —
   including the simulation-started streams this edge could never reach — seeds identically.

**`stats` is omitted, never zeroed.** `GET .../tracks` leaves the whole `stats` object out until the
window has recorded at least one detector pass. Two reasons, and the second is the load-bearing one:
the SPA's own contract says an absent `stats` means "hide the flow strip", and `TrackingStats`
reports a `null` `lastDetectorReason` for an empty window — which the strip's formatter
(`formatDetectorReason`, vision-web) would call `.toLowerCase()` on. Emitting a half-populated object
would be a runtime error in the client, not a cosmetic one.

## docs/plans/done/TRACKING-PLAN.md T3/T6 follow-up done (this edge stops reconstructing state it does not own)

The two judgment calls flagged in the T6 section above are paid down. Nothing on the wire changed — request and response JSON are byte-identical, so `vision-web` needed no change; what changed is where the merging happens.

**`StreamController`: 5 constructor arguments → 4.** The `TrackingConfig trackingSeed` collaborator is gone (it moved to the application layer's `StreamPipelineSettings`), and so is `trackingBase(StreamId)` — the T6 workaround that read a running stream's mode and engine back off `StreamService#trackingStats` to fill in the fields a partial `tracking` object omitted. It could not recover the five cadence knobs, so two cadence changes in a row lost the first. `updateConfig` now does exactly one thing: `streamService.updateConfig(id, body.toPatch())`.

**`AssetController`: 6 constructor arguments → 5**, back inside `.claude/skills/java-clean-code/SKILL.md` §3's ceiling, for the same reason.

**DTO changes** (all in `dto/`):
- `TrackingConfigRequest` — `toTrackingConfig(base)`/`toStartTrackingConfig(base)` became `toPatch()`/`toStartPatch()`, returning `application.stream.TrackingConfigPatch`. No base, no merge: an absent JSON field maps to `null` and the application layer decides what unchanged means. `toStartPatch()` still rejects a `lock` (→400) and `mode` is still parsed here (an unknown mode is still a 400 at the edge, not a startup surprise).
- `UpdateStreamConfigRequest` — one `toPatch()` again; the `toPatch(TrackingConfig base)` overload is gone.
- `StartStreamRequest`/`StartAssetStreamRequest` — `mergeOntoDefaults(TrackingConfig seed)` is gone; `mergeOntoDefaults()` leaves the config's tracking at the **domain** default (the bottom layer of the fold) and the request's own tracking travels beside it as `trackingPatch()`. `StartAssetStreamRequest` still delegates both to `StartStreamRequest` so the two shapes share one implementation.

**Why the edge is the wrong place for either job.** A controller cannot fold a partial patch onto a running stream's configuration, because it does not hold that configuration — the readback it improvised recovered two of eight knobs. And seeding a deployment default at two REST endpoints leaves every non-REST start path (simulation, demo fleet) unseeded. Both belong at `DefaultStreamService#start`/`#updateConfig`, which every path goes through.

**Tests**: `./mvnw -B -pl station/vision-api test` — **551/551 green** (unchanged count). `StreamControllerTest`'s two readback tests were replaced by two that assert the opposite and stronger property: a partial `tracking` object arrives as a partial `TrackingConfigPatch` with every unmentioned knob `null`, and `trackingStats` is never called on the PATCH path at all. The two start tests now assert the request's patch is what travels (`TrackingConfigPatch.NOTHING` when the body says nothing), rather than a pre-merged `TrackingConfig`.

## docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6e done (perception stops being driven by warehouse, warehouse stops reading runtime)

`AssetController` had `AssetStreamService` added to its constructor mid-task (starting a stream moved off `AssetService` in vision-application — see that module's MODULE.md), which would have made it **six** constructor arguments, one past `.claude/skills/java-clean-code/SKILL.md` §3's ceiling — the exact shape of problem this module already resolved once for the `TrackingConfig` seed (see the T3/T6 section above). Same fix: a new controller, not a bigger one.

**New `AssetStreamController`** (`api.controller`, 5 collaborators: `AssetService`, `AssetStreamService`, `CurrentUser`, `StreamPublisherPort`, `StreamService` — the fifth arrived with the MEDIA-SOT merge, which had added `burnedIn` to `AssetController#startStream` while this split was moving that endpoint here; it puts this constructor **at** the five-parameter ceiling) takes over `POST`/`DELETE /api/assets/{id}/stream` verbatim — same routes, same request/response DTOs, same status codes, same scoping (`startStream` still guards through `CurrentUser#scope()` before mutating; `stopStream` still unscoped, mirroring `AssetService#stopStream`'s own no-op-for-unknown-asset contract). `AssetController` drops both endpoints, the `AssetStreamService`/`StreamPublisherPort` collaborators (the latter existed only to resolve `startStream`'s `viewUrl`/`whepUrl`), and is back down to **4** constructor arguments (`AssetService`, `CurrentUser`, `TelemetryRepositoryPort`, `AssetImageRepositoryPort`) — one fewer than before this task even started, since `StreamPublisherPort` left with the endpoints that used it.

No DTO changed. `StartStreamResponse`/`StartAssetStreamRequest` are untouched — this was purely a controller-boundary move, not a wire change.

**Tests**: `./mvnw -B -pl station/vision-api test` — **551/551 green** at the time of the split (unchanged count: `AssetControllerTest` lost 9 stream-lifecycle tests, `AssetStreamControllerTest` (new) gained the same 9, moved verbatim — only the target controller and its mocks changed). **558/558** after the MEDIA-SOT merge added `StreamControllerTest`'s `burnedIn` cases.

## docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md wave J3 done (the capability ladder + ORU reach the wire)

J1 (`contexts/vision-perception`) widened the domain records; this wave is what an operator/UI can
actually see and set. Every DTO change is additive and follows the pre-existing `@JsonInclude(NON_NULL)`
idiom this module already uses for the pre-V3 tracking fields — see the "Tracking wire shapes"
paragraph above for the exact shapes, restated here only for the decisions behind them.

**`TrackingConfigRequest` gained `capabilityLevel`/`reupdateMaxGapMillis`** (both boxed `Integer`,
absent = unchanged, mapped straight through `toPatch()` to the same-named `TrackingConfigPatch`
fields J1 added) — ordinary patch fields, no new validation at this edge (`TrackingConfig`'s own
compact constructor is still the single arbiter, same convention as every other tracking field here).

**`FrameTrackingResponse` gained four fields**: `detectionLagMillis`/`reupdateMillis` (whole
milliseconds — the wire's own `int64` granularity has no fraction to preserve, unlike `trackerMillis`,
which stays fractional for the sub-millisecond tracker cost it was built to show), `reupdatedTracks`,
and a new nested `capability?:TrackingCapabilityResponse` object. A 5-arg convenience constructor
keeps every pre-V3 call site (there are none outside `from(TrackingTelemetry)`, but the idiom is kept
for consistency with every other widened record this wave touches) compiling unchanged, defaulting to
`0`/`0`/`0`/absent.

**New `TrackingCapabilityResponse(levelServed, reason)`** — the nested `"capability"` object, a
straight `TrackingCapability` mapping. No `@JsonInclude` of its own; the *object* is what's
optional (via `FrameTrackingResponse`'s existing `@JsonInclude(NON_NULL)`), not either of its two
always-present fields once it exists.

**B5 is enforced structurally, not by convention.** `FrameTrackingResponse.from(TrackingTelemetry)` is
the only place `capability` is populated, and it reads exclusively from the domain telemetry — there
is no code path from a request's `capabilityLevel` (or anything else) into this field. A reader who
wants to show "asked for L4, got L2" must fetch both facts from two different places (the stream's own
config and this response) and is never tempted to alias one field as the other, because no single
field carries both meanings.

**`DetectionTrackResponse`/`TrackResponse` both gained `reupdated`** (`TrackRef#reupdated()` verbatim)
— every place a per-detection track object is serialized now carries it: the nested `"track"` object
on `GET .../detections` (and the `detections` SSE topic) and the flat per-entry shape of
`GET /api/streams/{streamId}/tracks`.

**Tests** (`StreamControllerTest`, extended in place): +3 new methods —
`updateConfigThreadsCapabilityLevelAndReupdateMaxGapMillisThroughToThePatch` (the PATCH mapping),
`detectionsCarryTheV3ReupdateAndCapabilityFactsWhenTheServerReportsThem` (all six new facts present
and correct when the domain carries them), `aServedCapabilityBelowWhatWasRequestedRendersTheServedValueNeverTheRequest`
(B5 — a `TrackingCapability(2, "...")` renders `levelServed:2` regardless of any hypothetical
requested level, because the response has no field for the request at all). Plus additive assertions
on three pre-existing tests (no assertion removed or weakened): `detectionsCarryTheNestedTrackAndTrackingObjectsWhenTrackingIsOn`
now also asserts `track.reupdated:false` and that `tracking.capability` is absent with the four V3
numeric fields at `0` (the pre-V3-convenience-ctor shape, invariant B3);
`tracksReturnsTheBookedTracksWithLockedTrackIdHoistedAboveStats` now asserts `tracks[0].reupdated`
(built with `reupdated=true` to prove the field actually threads through, not just default `false`).

**Before/after**: **558 → 561 (+3 new test methods)**, confirmed green:
`./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app test -DskipWeb` → `Tests run: 561,
Failures: 0, Errors: 0, Skipped: 0` for `vision-api`. No pre-existing assertion changed — only
additive assertions on already-passing tests plus three new test methods.

**vision-app's half of this wave** (`VisionTrackingProperties`/`TrackingWiring`/`application.yaml`) is
documented in station/vision-app/MODULE.md's own wave J3 section, including why that module's `mvn test` could
not be run to completion in this task (a pre-existing, unrelated stale `adapter-cv-grpc` local-repo
artifact — concurrent wave J2's module, not touched here).

## docs/plans/done/CV-DEMAND-PLAN.md wave D2 done (system-derived detection demand — the REST + wiring half)

D1 (`contexts/vision-perception`, out of scope, not touched here) added the `DetectionDemandPort`
seam and flipped `PipelineConfig.defaults().detectionEnabled()` from `true` to `false` (D1's own
javadoc, §1); D3 (`station/vision-web`, out of scope, not touched here) is the frontend half. This
wave is the two REST-adjacent modules that had to wire the two halves together: an implementation of
the port, the DTO/endpoint changes that let it observe real demand, and the deployment default that
decides what a brand-new stream's detection starts at.

**New `com.drones.vision.api.live.LiveAndPollDetectionDemand implements DetectionDemandPort`** — the
one fact `DefaultStreamService`'s demand-poll task wants, collapsed from the two driving protocols the
frontend actually uses:

- **SSE half** — `assetId != null && watchingDetections.test(assetId)`, where `watchingDetections`
  is a `Predicate<AssetId>`, not `LiveUpdateRegistry` itself. Same seam idiom
  `ApplicationServiceWiring#usageTracker` already uses for `GeofenceMonitor::evaluate` — this class
  needs exactly one capability off the registry (`LiveUpdateRegistry#watchingDetections(AssetId)`,
  new method, see below), so it takes that capability as a narrow functional parameter instead of the
  whole concrete `final` class. Keeps the port trivially testable with a plain lambda, including a
  throwing one, without constructing or mocking `LiveUpdateRegistry` at all — this repo has no inline
  Mockito mock-maker configured, so mocking a `final` class was never an option here.
- **Poll half** — `touched(StreamId)` stamps a `ConcurrentHashMap<StreamId, Instant>` entry;
  `detectionWanted` prunes expired entries (TTL = `VisionCvProperties.Demand#pollTtl()`, default 10s)
  on every read rather than on a separate scheduled sweep, so the map never needs its own background
  thread. `StreamController#detections` calls `touched(id)` on every `GET
  /api/streams/{streamId}/detections` — a Wall/Live page polling that endpoint counts as demand
  exactly like an open SSE `detections:<assetId>` subscription does.
- **An internal failure fails OPEN — it answers "wanted", never "not wanted."** `detectionWanted`
  honours the port's never-throw contract with a swallowing `catch`, and the *direction* of that
  swallow is load-bearing enough to have its own test
  (`aThrowingPredicateFailsOpenSoOneBrokenLookupNeverGatesDetectionOffEverywhere`). "We could not
  determine whether anyone is watching" is not the confident negative "nobody is watching":
  answering `false` would gate detection off for **every** stream at once and — since the gate is
  precisely what `DetectionState` reports — surface as `IDLE_NO_VIEWERS` to an operator sitting
  right there watching. A wrong answer that costs CPU is recoverable and visible; a wrong answer
  that silently stops detection and then explains itself with a falsehood is neither. Points the
  same way as every other failure decision in this feature (an absent port gates nothing;
  `StreamPipeline`'s own demand flag initialises `true`). Shipped `false`; corrected during review.
- **Never throws**, per `DetectionDemandPort`'s own contract: `detectionWanted` swallows any
  `RuntimeException` from `watchingDetections` (or a `null` `assetId`) and reports "not watched"
  rather than propagating — a periodically-scheduled caller (`DefaultStreamService`'s demand-poll
  task) must never have one failing evaluation take down every later one.

**New `com.drones.vision.api.support.StreamDetectionSupport`** (record, `defaultConfig:
PipelineConfig`, `demand: LiveAndPollDetectionDemand` nullable) — bundles `StreamController`'s two new
detection-demand collaborators behind one constructor parameter. `StreamController` was already at
four constructor arguments; neither the deployment-default `PipelineConfig` read (once per `start`)
nor the poll-touch delegation (once per `detections` read) is substantial enough alone to justify a
fifth slot or splitting the controller — so both ride in one named, purpose-built bundle instead of an
unlabeled extra field. `touched(StreamId)` is a no-op when `demand()` is `null` (`vision.cv.demand.enabled
=false`, the port bean absent entirely). Plain class, not a `@Component` — assembled by `vision-app`'s
`CvWiring#streamDetectionSupport`, mirroring `SnapshotJpegEncoder`'s own precedent for a
framework-free support class this module holds but only `vision-app` knows how to build.

**New `com.drones.vision.api.support.StreamViewerLinks`** (`@Component`) — the narrow
`viewUrl`/`whepUrl`/`burnedIn` read `AssetStreamController` needs off `StreamPublisherPort`/
`StreamService`, split out so that controller could add a `PipelineConfig defaultConfig` fifth
parameter without exceeding `.claude/skills/java-clean-code/SKILL.md` §3's five-constructor-parameter
ceiling — trading two parameters for one frees exactly the slot the new default needed.
**`AssetStreamController`'s constructor is now `(AssetService, AssetStreamService, CurrentUser,
StreamViewerLinks, PipelineConfig)`** — this corrects the "5 collaborators: `AssetService`,
`AssetStreamService`, `CurrentUser`, `StreamPublisherPort`, `StreamService`" shape documented in the
W1.6e section above, which this wave's own change made stale; that section is left as-is (it is an
accurate record of the shape as of W1.6e) rather than rewritten, per this file's own append-only
convention. `StreamController` keeps its own `StreamService`/`StreamPublisherPort` collaborators
directly — it uses them for far more than these three reads, so wrapping there would be a swap, not a
reduction. **This was a plan gap, not a spec item**: §3.8 of the frozen plan only named
`StartStreamRequest`/`StartAssetStreamRequest`/`DemoFleet` as the merge-onto-defaults call sites,
missing that `AssetStreamController` (added by the later, independent W1.6e split) is a fourth stream-start
path with the identical need — found and fixed as part of this wave, not deferred.

**`StreamTracksResponse` gained `detectionState`** — see the "Tracking wire shapes" paragraph above
for the exact record shape; `StreamController#tracks` populates it from `StreamService#detectionState
(StreamId)`, absent for an unknown/not-running stream, same absence idiom as `stats`.

**`StartStreamRequest#mergeOntoDefaults()` (no-arg) became `mergeOnto(PipelineConfig defaults)`** (an
instance method taking the deployment's default config as a parameter, replacing an internal call to
the domain's static `PipelineConfig.defaults()`) — the mechanism that lets `vision.cv.detection
-default-enabled` (`CvWiring#streamDefaultConfig`, `vision-app`) actually reach a started stream.
`StartAssetStreamRequest#mergeOnto(PipelineConfig)` still just delegates to `StartStreamRequest`'s
implementation, one more parameter along for the ride, same as every prior addition to this pair. Four
call sites merge onto a deployment default now, not the domain default directly: `StreamController
#start` (`streamDetectionSupport.defaultConfig()`), `AssetStreamController#startStream` (its own
`PipelineConfig defaultConfig` parameter), `DemoFleet` (`api.demo`, its own `PipelineConfig
defaultConfig` constructor parameter, 5 collaborators total), and `StartAssetStreamRequest`'s own
delegation. Every one of these four beans/parameters is fed the **same** `CvWiring#streamDefaultConfig`
bean from `vision-app`, so `vision.cv.detection-default-enabled` reaches every non-simulation stream
start path identically — the same "one deployment default, every start path agrees" property
`docs/extracts/TRACKING-ORCHESTRATION.md` §4.1 already established for the tracking seed.

**`LiveUpdateRegistry` gained `boolean watchingDetections(AssetId)`** (public, package `api.live`) —
`true` iff at least one open connection is subscribed to that asset's `detections:<assetId>` topic;
the one capability `LiveAndPollDetectionDemand`'s SSE half needs, resolved once by `vision-app`'s
`CvWiring#detectionDemandPort` into the `Predicate<AssetId>` seam described above.

**Tests** — `LiveAndPollDetectionDemandTest` (new, 8 methods, pure unit, no Spring context): the SSE
half true/false, a `null` assetId skipping the SSE half entirely, the poll half counting as demand on
its own, cross-stream isolation (`touched` on one stream never demands another), a poll just inside
the TTL still counting, a poll exactly at the TTL boundary having expired, and a throwing predicate
being swallowed rather than propagated (the injectable-clock test constructor moves time without
sleeping for the two TTL-boundary cases). `LiveUpdateRegistryTest` +2: `watchingDetections` false with
no subscriber, true once a connection subscribes to that asset's `detections` topic — both pure unit
tests, no `MockMvc`/real HTTP needed, since `SseEmitter`/`ResponseBodyEmitter` buffers early `send()`
calls before `initialize()` rather than throwing, so calling `LiveUpdateRegistry#connect(...)` directly
is safe in a plain test. `StreamControllerTest` +3: `tracksReportsWhichDetectionGateExplainsTheCurrentState`
(`detectionState` present when the service reports one), `tracksOmitsDetectionStateForAnUnknownOrStoppedStream`
(absent otherwise), `detectionsTouchesTheDemandPortSoAPollingReaderCountsAsDemand` (a real
`LiveAndPollDetectionDemand` wired through `StreamDetectionSupport` in the test, asserting
`detectionWanted` flips `false`→`true` after calling the endpoint once). One pre-existing
`StreamControllerTest` assertion was **corrected, not weakened**: `startWithoutDetectionEnabledKeepsTheDefaultTrue`
hardcoded `assertTrue(captor.getValue().detectionEnabled())`, an assumption D1's already-landed
`PipelineConfig.defaults()` flip (`true`→`false`) silently broke — D1's own scoped build never
re-verified this module's dependent tests. Renamed to
`startWithoutDetectionEnabledKeepsWhateverStreamDetectionSupportsDefaultConfigSays` and changed to
compare against `PipelineConfig.defaults().detectionEnabled()` dynamically: this controller never
hardcodes that value itself, it only merges onto whatever `StreamDetectionSupport` hands it, so the
test now pins that *delegation*, not a specific literal that belongs to a different module.
`AssetStreamControllerTest`/`DemoFleetTest` were updated in place (constructor shape / mock wiring for
the new `PipelineConfig`/`StreamViewerLinks` collaborators) with no new test methods — same "one
shared-logic test suffices" judgment call this module already makes for `overlayBurnIn`/`model`/etc.

**Before/after**: **562 → 575** (+13: 3 `StreamControllerTest`, 2 `LiveUpdateRegistryTest`, 8 new
`LiveAndPollDetectionDemandTest`). `./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app test
-DskipWeb` confirmed green — `vision-api: Tests run: 575, Failures: 0, Errors: 0, Skipped: 0`.

**Not touched, per scope**: `contexts/vision-perception` (D1) and `station/vision-web` (D3) — this
wave's diff is contained entirely to `station/vision-api/**` and `station/vision-app/**`, confirmed via
`git status` before finishing.

**docs/plans/done/OPS-UX-PLAN.md Wave C (C2) done — authority, not visibility, guards asset writes.**
`AssetController`'s private `requireInScope(id)` was renamed to `requireManageable(id)` and extended:
it still 404s an unknown/invisible asset via its own scoped `assetService.details(scope, id)` read
(unchanged, hides existence), then additionally throws `AccessDeniedException` (403) when
`!scope.canManage(details.summary().asset().ownership())` — visible-but-not-administrable, the case a
PILOT hits on their own assigned aircraft. All five write endpoints (`update`/`setState`/`delete`/
`assignDevice`/`unassignDevice`) call it; `create` gets its own `!scope.canManageOrg()` → 403 gate,
checked first, before `request.toSpec()` parses the body. No `ApiExceptionHandler` change needed —
`AccessDeniedException`→403 already existed. See "Authority, not visibility" above for the full
reasoning and the endpoint table for the per-route 403 additions.

**Existing `AssetControllerTest` stubbing gap this surfaced, fixed, not weakened**: `requireManageable`
now *dereferences* `assetService.details(...)`'s result (`.summary().asset().ownership()`), where the
old `requireInScope` discarded it — every test that stubbed only the *write* method to throw (leaving
`details(...)` an unstubbed Mockito-default `null`) started NPEing. Each was fixed per what it actually
simulates: a genuinely-unknown-asset test (`updateReturns404ForUnknownAsset`,
`setStateReturns404ForUnknownAsset`, `deleteReturns404ForUnknownAsset`,
`unassignDeviceReturns404ForAnUnknownAsset`) now stubs `assetService.details(...)` itself to throw
`NoSuchElementException` — the same exception the real `AssetService` throws for an unknown/invisible
id, so this is a *more* accurate test double than before, not a workaround; a known-asset-other-failure
test (`updateReturns400ForAnUnknownCategory`, `setStateActiveOnADeletedAssetReturns409`,
`deleteReturns200WithTheDeletionSummary`, `assignDeviceReturns409WhenTheDeviceAlreadyBelongsToAnotherAsset`,
`assignDeviceReturns404ForAnUnknownDevice`, `unassignDeviceReturns409WhenRemovingTheLastDevice`,
`unassignDeviceReturns400WhenTheDeviceDoesNotBelongToTheAsset`) gained a `stubExistingAsset(assetWithId(assetId))`
call so `requireManageable`'s own read succeeds before the write-stub's failure fires. New helper
`assetWithId(AssetId)` (an `Asset` with a caller-chosen id, for tests that need to name the id before
building the asset).

**New C5 authority tests** (`AssetControllerTest`, 11 new methods) prove the PILOT-vs-MANAGER
boundary this wave draws, via two new helpers: `currentUserWithScope(VisibilityScope)` (a `CurrentUser`
built from an anonymous `PrincipalResolver` fixing `ownership`/`ownerId` but taking a caller-supplied
scope — `viewer()` throws `UnsupportedOperationException`, since `AssetController` never calls it) and
`mockMvcFor(CurrentUser)` (a fresh standalone `MockMvc` bound to that user, alongside the class-level
`mockMvc` which stays on the unbounded `currentUser`). Covered: `create` 403 for a PILOT / 201 for a
MANAGER (`canManageOrg()`); `update`/`setState`/`delete`/`assignDevice`/`unassignDevice` 403 for a
PILOT scope even when the asset is the exact one assigned to them (`ASSIGNED_ASSETS` grants visibility,
never `canManage`); `update`/`setState`/`delete` 200 for a MANAGER scope whose `groups()` contains the
asset's own group; `update` 403 for a MANAGER scope whose `groups()` does **not** contain it (a second
manager's subtree is not this asset's subtree — proves the gate isn't just "any `GROUPS` scope passes",
the real `groups.contains(ownership.groupId())` check runs).

**Before/after**: `AssetControllerTest` **44 → 55** (11 new C5 tests; 0 pre-existing tests deleted,
11 pre-existing tests' stubs corrected as described above, all still passing). Module total (`./mvnw
-B -pl station/vision-api test -DskipWeb`): **587/587** green. Combined `./mvnw -B -pl
storage/persistence,station/vision-api,station/vision-app test -DskipWeb`: `adapter-persistence`,
`vision-api` (587), `vision-app` (240) all green — the default-config guardrail (`vision.auth.enabled=false`
→ every caller `unbounded()` → every new gate a no-op) holds across the full integration slice.

**Not touched, per scope**: `station/vision-web/**` (owned by a concurrent wave); no `vision-app`
wiring change was needed — no new constructor parameter or bean was introduced, `AssetController`'s
constructor shape is unchanged (still `AssetService, CurrentUser, TelemetryRepositoryPort,
AssetImageRepositoryPort`).

## docs/plans/done/SCALE-100-PLAN.md §5 S1 done (video out of the JVM byte path — `HlsProxyController` rewrite)

Rewrote `HlsProxyController` per the plan's three ranked tasks: stream instead of buffer, one shared
`HttpClient` with no shared cookie handler, forward `Range`. Full mechanism documented in the class's
own javadoc and summarized in the `com.drones.vision.api.proxy` section above; not repeated here.

**Decisions**:
- **Streaming**: `ResponseEntity<InputStreamResource>` (Spring's `ResourceHttpMessageConverter`,
  which copies in fixed-size chunks synchronously on the request thread) chosen over
  `StreamingResponseBody` specifically because the latter triggers Spring MVC's async request
  processing, which would have forced every existing `MockMvc` test in `HlsProxyControllerTest` to
  add explicit `asyncDispatch` plumbing — a test-shape change the wave's acceptance bar ("no
  assertion edited") ruled out. `InputStreamResource` needed none of that: it's a normal synchronous
  return type, so every pre-existing test still passes unmodified.
- **Redirects**: `HttpClient.Redirect.NEVER` + a bounded manual hop loop (`fetch`, `MAX_REDIRECT_HOPS`
  = 5), not `Redirect.NORMAL`. With the cookie handler removed (see below), whether the JDK client's
  own `NORMAL` redirect logic replays a manually-set `Cookie` header from hop 1 onto hop 2 is
  undocumented internal behavior — not something to build mediamtx's viewer-pinning flow on top of.
  Manual hop-following makes the cookie carry-over explicit and testable (`mergeCookies`) instead of
  relying on it.
- **Cookie isolation**: no `cookieHandler` on the shared client at all; cookies flow only as
  request/response headers scoped to one servlet request. This is the wave's stated security trap and
  its acceptance gate — `sharedClientDoesNotLeakOneViewersCookieToAnother` proves two sequential
  requests through the same shared client with different `Cookie` headers never cross-contaminate,
  including the `Set-Cookie` mediamtx issues mid-chain.
- **`CONNECT_TIMEOUT`/`REQUEST_TIMEOUT`**: left as local constants exactly as instructed (plan §5 S1
  task 4) — they duplicate `VisionApiProperties.HlsProxy` already, but wiring them up belongs to S7,
  which also owns `VisionApiProperties.java` and `vision-app`'s wiring (both reserved, untouched here).

**Config keys for the orchestrator** (not added here — `application.yaml` and
`station/vision-app/.../config/wiring/**` are reserved for Band A, per
docs/plans/done/SCALE-100-CONTEXT.md §1): none *new* were introduced by this wave — no constructor
parameter or bean changed shape (`HlsProxyController(URI hlsUpstreamBase)` is unchanged), so no wiring
edit is required for this wave to function. The plan's own §5 S1 task 4 and §6 additionally ask for
`spring.threads.virtual.enabled=true` and a pinned `server.tomcat.max-connections` — those are plain
`application.yaml` lines with no code-side dependency on anything in this wave's diff, left for the
orchestrator to apply alongside S2/S3's reserved-file keys.

**Before/after** (`./mvnw -B -pl station/vision-api test -DskipWeb`): `HlsProxyControllerTest`
**8 → 10** (2 new: the cookie-isolation acceptance gate, and `Range`/`Content-Range`/`Accept-Ranges`
forwarding). All 8 pre-existing tests pass with **zero assertions edited**. Module total: **592/592**
green, no other test file touched or affected (`git diff --stat` for this task: only
`HlsProxyController.java` and `HlsProxyControllerTest.java`).

**Follow-up 2026-08-18** (`6e640d3`): `HlsProxyControllerTest` **10 → 11**, module total **599/599**
green. (Count taken from Maven's own summary line. Summing `target/surefire-reports/TEST-*.xml`
overstates it — that directory keeps reports for renamed/removed test classes until a `clean`.) The added test is the scheme-rewrite regression above — the cookie-isolation gate this wave
shipped was necessary but not sufficient, since it only ever exercised `http://localhost`, where
browsers keep `Secure` cookies.

**Not done, deferred to later waves**: `spring.threads.virtual.enabled` / `server.tomcat.max-connections`
(application.yaml, reserved); wiring `CONNECT_TIMEOUT`/`REQUEST_TIMEOUT` to `VisionApiProperties.HlsProxy`
(S7, `VisionApiProperties.java` reserved); the S0 load-rig numbers this wave's acceptance criteria are
ultimately measured against (heap allocation rate, thread count under 100 concurrent viewers,
byte-identical segment hashes) — those are S0's rig's job, not exercised by this module's unit tests.

## docs/plans/done/SCALE-100-PLAN.md wave S2 done (SSE dispatch: serialize once, bounded async per-connection writes, per-asset buffer eviction)

`live-update-dispatcher` — the single `scheduler` thread — previously did four things: assign every
envelope's `seq`, run coalescing, decide fan-out, **and** block on `SseEmitter#send` once per
subscribed connection. One slow/stalled client stalled delivery to everyone else on that thread, and
every connection re-serialized the same envelope independently. This wave (`com.drones.vision.api.live`
only, per the plan's disjointness contract) keeps the first three on `scheduler` — envelope ordering
within a topic is unchanged — and moves only the fourth off it. See the "API surface" subsection above
for the full mechanics (`LiveUpdateRegistry#serialize`/`connectionWriteExecutor`/`dispatchWrite`/
`evictUnusedAssetBuffers`, `LiveConnection#enqueueSend`/`enqueueHeartbeat`/`writeChain`); summary:

1. **Serialize once, write N times** — `broadcast` now JSON-encodes an envelope exactly once
   (`serialize`) and hands the same `String` to every subscribed connection, instead of each one
   re-encoding the same object.
2. **Split the executor** — `connectionWriteExecutor` (`Executors.newVirtualThreadPerTaskExecutor()`,
   an unconditional field, not a sixth constructor parameter) runs the actual per-connection write;
   `scheduler` never blocks on one. `LiveConnection`'s new `writeChain` (a per-connection
   `CompletableFuture` chain) guarantees one connection's own writes still run in submission order
   despite the executor having no shared FIFO queue — proven by
   `concurrentDispatchNeverReordersOneConnectionsOwnEnvelopes` (20 connections, jittered test-double
   `send()`, asserts every connection's received `seq`s come back strictly increasing).
3. **Bound the write** — `dispatchWrite` wraps each queued write in
   `orTimeout(CONNECTION_WRITE_TIMEOUT_MILLIS, MILLISECONDS).exceptionally(...)`; past the timeout the
   connection is unregistered, same outcome an `IOException` always produced. Proven by
   `aBlockedConnectionDoesNotStopOthersFromReceivingEnvelopes` (a stuck connection alongside two
   healthy ones — both healthy ones receive their envelope well inside the poll window) and
   `aConnectionWhoseWriteStaysBlockedPastTheTimeoutIsUnregistered` (`watchingDetections` flips to
   `false` once `CONNECTION_WRITE_TIMEOUT_MILLIS` elapses).
4. **Evict per-asset buffers** — `evictUnusedAssetBuffers()`, scheduled every `BUFFER_EVICTION_MILLIS`
   (60s), sweeps `telemetryBuffers`/`detectionBuffers` down to only the asset ids some open connection
   still subscribes to. Proven by two tests: an unwatched asset's buffer comes back empty after
   eviction (`bufferFor` recreates a fresh one via `computeIfAbsent`, which is the observable proof the
   old one — and its data — is actually gone), and an actively-subscribed asset's buffer survives.

**Resume under concurrent dispatch** — `resumeStaysCorrectWhileAnotherConnectionsWriteIsStuckInFlight`:
with one connection's write parked indefinitely (mid-flight on `connectionWriteExecutor`, exactly the
risk the plan's §9 risk table calls out — "multi-threaded dispatch reorders envelopes within a topic"),
two more envelopes are published and `replayFor(topic, firstSeq)` still returns exactly the two newer
ones in order. This holds by construction (sequencing/buffer-append never left `scheduler`), not by
timing luck.

**Test seams added** (`api/live/**` only): `LiveUpdateRegistry#register(SseEmitter, Set<LiveTopic>,
Predicate<String>)` (package-private) plugs a test-double `SseEmitter` into the registry, bypassing
`connect()`'s synchronous handshake — needed because none of this wave's new behavior is observable
through the pre-existing `bufferFor`/`replayFor` seams alone. `LiveUpdateRegistryTest` gained two
private nested `SseEmitter` subclasses: `RecordingSseEmitter` (captures each JSON `data:` payload,
filtered by `MediaType.APPLICATION_JSON` since `SseEventBuilder#build()` also carries the raw
`"id:...\n"` protocol prefix as its own `TEXT_PLAIN` entry) and `BlockingSseEmitter` (parks on a
caller-supplied `CountDownLatch`, released explicitly at the end of each test that uses it so no
virtual thread leaks past the test).

**Before/after**: `LiveUpdateRegistryTest` **22 → 28** (+6, all additive — `git diff --numstat` on the
test file: `219 insertions(+), 0 deletions(-)`; no pre-existing assertion touched). Module total
(`./mvnw -B -pl station/vision-api test`): **590 → 596**, all green.

**Config keys needed, not added (reserved files)**: `CONNECTION_WRITE_TIMEOUT_MILLIS` (3,000ms) and
`BUFFER_EVICTION_MILLIS` (60,000ms) are `static final` constants in `LiveUpdateRegistry`, not yet
backed by a config key — `vision.api.live.send-timeout` (for the first) and `vision.api.live
.dispatch-threads` (governing whether `connectionWriteExecutor` stays one-virtual-thread-per-write or
becomes a bounded platform pool) are already named as S2 candidates in the plan's own §6 table.
`VisionApiProperties.java`/`application.yaml`/`config/wiring/**` are reserved to a different agent this
wave — wiring either key up is deferred to whichever wave owns those files next.

**Docker**: not applicable — nothing in `com.drones.vision.api.live` or its tests uses Testcontainers
or the `docker` CLI; this module's Postgres/docker-gated tests (if any) live elsewhere
(`storage/persistence`).

**New endpoint shapes**: none. This wave is entirely internal dispatch machinery behind the existing
`GET /api/live`/`PATCH /api/live/{connectionId}/topics` surface — no request/response DTO changed.

**Not touched, per scope**: `api/proxy/**` (a concurrent S1 wave), `support/VisionApiProperties.java`,
`application.yaml`, `config/wiring/**` (reserved files — needed config keys reported above instead of
edited directly), every other package under `com.drones.vision.api`.

## docs/plans/done/SCALE-100-PLAN.md wave S5 done (fleet snapshot recompute debounced leading+trailing)

`publishFleetChanged()` used to recompute the *entire* fleet+devices snapshot (all assets, all
devices, all streams) on every single asset/device/stream lifecycle write — a bulk import of N assets
recomputed the whole fleet N times, and the cost grows with total asset count, not with how many
actually changed. This wave (`LiveUpdateRegistry`'s `freshFleetEnvelope`/`freshDevicesEnvelope`/
`publishFleetChanged` path only, per the plan's disjointness contract, sequenced after S2 since both
touch this file) debounces it the same way `flushPending()` already debounces telemetry/detections,
composing with S2's serialize-once/async-write path for free — nothing about *how* an envelope
reaches a connection changed, only how often the fleet/devices envelopes get recomputed in the first
place.

**Leading+trailing, not pure trailing** — a pure "wait `COALESCE_MILLIS` then recompute" debounce
would have added latency to the common case (a single, isolated write) and, worse, is indistinguishable
from a bug when a test calls `publishFleetChanged()` once and expects a synchronous result (every
pre-existing test in this file does exactly that — see below). Two new fields carry the state:
`fleetRecomputeWindowUntilNanos` (`AtomicLong`, the nanoTime a coalescing window closes; `Long.MIN_VALUE`
initially so the first call on a fresh registry always dispatches) and `fleetChangedDuringWindow`
(`AtomicBoolean`, set whenever a call is coalesced away). `publishFleetChanged()`: a call past the
window's close wins a compare-and-set on `fleetRecomputeWindowUntilNanos`, opens the next window, and
dispatches `recomputeFleetAndDevices()` (the extracted former body of this method) via `scheduler.execute`
exactly as before; a call inside an open window (or one that lost the compare-and-set race to a
concurrent caller) only sets `fleetChangedDuringWindow`. `flushPending()` — already ticking every
`COALESCE_MILLIS` on the real scheduler, already directly callable in tests — gained one line at the
top: `if (fleetChangedDuringWindow.compareAndSet(true, false)) recomputeFleetAndDevices();`, which is
the trailing recompute that guarantees the window's last write is never silently dropped.

**Why this preserves every pre-existing test unchanged**: a lone `publishFleetChanged()` call on a
fresh registry always finds `now >= windowUntil` (nothing has opened a window yet), so it dispatches
immediately via `scheduler.execute` — under the test module's `ImmediateScheduledExecutorService` that
runs synchronously on the calling thread, exactly as the un-debounced version always did. Every
existing test that calls `publishFleetChanged()` (in this file, `LiveControllerTest`,
`LiveMapScopingTest`) does so exactly once per freshly-constructed registry, so none of them ever
observes a window — confirmed by re-running the suite before this change (**599** tests) and after
(**602**, +3, purely additive: `git diff --numstat` on the test file shows `63 insertions(+), 0
deletions(-)`, no pre-existing assertion touched).

**Sequencing** (hard constraint from the plan): the compare-and-set bookkeeping runs on the calling
thread (whichever thread committed the write — the same shape `publishTelemetryAppended`'s
`pendingTelemetry.computeIfAbsent(...).add(...)` already uses), but `sequencer.incrementAndGet()`,
`fleetBuffer`/`devicesBuffer.append`, and `broadcast` only ever run inside `recomputeFleetAndDevices()`,
which only ever runs on `scheduler`'s single thread (dispatched via `scheduler.execute` for the leading
call, inline for `flushPending()`'s trailing call, since `flushPending` itself only ever runs on that
thread). `seq` ordering and `Last-Event-ID` resume are therefore unaffected — proven by the full
pre-existing SSE/resume test suite passing unchanged, including every `LiveControllerTest`/
`LiveMapScopingTest` resume test.

**Three new tests, `LiveUpdateRegistryTest`** (`aFlushWithNoCoalescedFleetChangeDoesNotTriggerAnExtraRecompute`/
`fiftyRapidPublishFleetChangedCallsCoalesceIntoAtMostTwoRecomputes`/
`theTrailingRecomputeAfterACoalescedBurstReflectsTheNewestStateNotTheFirst`) prove, respectively: a
single call plus an idle `flushPending()` tick does not manufacture a phantom second recompute
(`verify(assetService, times(1)).assets()`); 50 back-to-back calls in a tight loop (no real time
elapses) produce exactly one immediate recompute, and exactly one more once `flushPending()` simulates
the window closing (`times(1)` then `times(2)` — the plan's own "≤2 recomputes" acceptance bar, hit at
its tightest); and the trailing recompute reflects the *last* write inside the window, not the first —
the mock is reconfigured to a different asset id between the leading and the coalesced call, and the
buffered fleet payload after `flushPending()` carries the second id, proving CLAUDE.md rule 9 rather
than assuming it.

**No new tunable config key** — `FLEET_COALESCE_WINDOW_NANOS` is `TimeUnit.MILLISECONDS.toNanos(COALESCE_MILLIS)`,
derived from the existing constant rather than a second literal, so S7 wiring `COALESCE_MILLIS` to
`VisionApiProperties.Live`'s already-named `coalesce` key (docs/plans/done/SCALE-100-PLAN.md §6) covers this
window too, with no separate key needed.

**Docker**: not applicable — same reasoning as S2's entry above.

**New endpoint shapes**: none — internal dispatch machinery only, same `GET /api/live`/`PATCH
/api/live/{connectionId}/topics` surface, no DTO changed.

**Not touched, per scope**: `api/proxy/**`, `support/VisionApiProperties.java`, `application.yaml`,
`config/wiring/**` (reserved), every other package under `com.drones.vision.api`.

## docs/plans/done/SCALE-100-PLAN.md wave S6 done, backend half only (per-principal token-bucket rate limit)

Item 3 of the plan's §5 S6 — a new `RateLimitFilter` (+ `TokenBucket`) in a new
`com.drones.vision.api.ratelimit` package, full detail in that package's API surface section above.
Items 1/2 (the frontend pollers, `station/vision-web/**`) belong to a different agent and were not
touched here.

**Design decisions**:
- **Keying degrades with `CurrentUser`, not a second identity scheme**: reusing
  `CurrentUser#userId()` rather than inventing a separate "who is this request" concept means the
  filter automatically inherits the existing dev-mode-vs-real-auth split — one shared bucket for
  every caller when `vision.auth.enabled=false` (matches today's single dev-admin identity exactly),
  one bucket per real user when `true`. The one gap that identity doesn't cover — the two permit-all
  `/api/auth/login`/`/api/auth/logout` endpoints, reachable with no session at all under the secured
  chain — falls back to `request.getRemoteAddr()` (see `SecurityContextPrincipalResolver`'s
  `IllegalStateException` contract, vision-app, and `SecurityConfig`'s `permitAll` rule for those two
  paths).
- **Filter, not an interceptor or a controller concern**: a `Filter` runs before Spring MVC's
  handler-mapping/argument-resolution machinery, so an over-budget request never reaches a
  controller method at all — cheaper, and keeps every controller free of rate-limit awareness.
  `ApiExceptionHandler` is unreachable from here (it only sees `DispatcherServlet`-dispatched
  exceptions), so the `429` body is hand-written, reusing the existing `ErrorResponse` shape.
- **Time as a parameter, not a hidden `System.nanoTime()` call**: both `TokenBucket` and
  `RateLimitFilter#evictIdleBuckets` take `now`/`cutoff` explicitly (`RateLimitFilter` owns one
  `LongSupplier nanoClock`, real in production, fake in tests) — makes refill and eviction
  deterministically testable without sleeping real time, and keeps `TokenBucket` a pure class with
  no I/O of its own.
- **Eviction sweep shape copied from `LiveUpdateRegistry#evictUnusedAssetBuffers`** (S2's fix for the
  identical class of bug — a map that only ever grows): a single daemon
  `ScheduledExecutorService`, package-private sweep method, injectable scheduler in tests so nothing
  waits on a real timer.

**Config keys, as requested here and as since wired** (`application.yaml` and
`station/vision-app/.../config/wiring/**` were reserved this wave, per
docs/plans/done/SCALE-100-CONTEXT.md §1; the orchestrator applied them — see `vision-app`'s
MODULE.md for `RateLimitWiring`, its own `RateLimitWiringTest`, and the one deviation: the
`@ConditionalOnProperty` moved from the `@Bean` to a dedicated `@Configuration` class, and the
permits knob binds through `VisionApiProperties.RateLimit` rather than a raw `@Value`):

```yaml
vision:
  api:
    rate-limit:
      # Master switch — RateLimitFilter is a blast-radius bound, not security hardening (see that
      # class's javadoc). Off by default: docs/plans/done/SCALE-100-PLAN.md §6 pins this false so
      # landing it changes no default-config behavior; an operator opts in per deployment.
      enabled: false
      # Bucket capacity and average refill rate, requests per rolling minute, per acting principal
      # (docs/plans/done/SCALE-100-PLAN.md §5 S6 item 3). 600 (10/s sustained, burstable to a full
      # minute's allotment) comfortably covers several browser tabs open under one identity — see
      # RateLimitFilter.DEFAULT_PERMITS_PER_MINUTE's own javadoc for the sizing argument against
      # §2.1's ~1 req/s-per-idle-tab measurement.
      permits-per-minute: 600
```

Exact `@Bean` requested (mirrors `AuthWiringConfiguration`'s `@ConditionalOnProperty` shape for a
seam that only sometimes exists):

```java
@Bean
@ConditionalOnProperty(prefix = "vision.api.rate-limit", name = "enabled", havingValue = "true")
public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
        CurrentUser currentUser,
        @Value("${vision.api.rate-limit.permits-per-minute:600}") int permitsPerMinute) {
    FilterRegistrationBean<RateLimitFilter> registration =
            new FilterRegistrationBean<>(new RateLimitFilter(currentUser, permitsPerMinute));
    registration.addUrlPatterns("/api/*");
    return registration;
}
```

A plain `@Bean RateLimitFilter` (letting Spring Boot auto-register it at `LOWEST_PRECEDENCE` for
every URL) works exactly as well — `shouldNotFilter` already scopes it to `/api/**` internally, so
`addUrlPatterns("/api/*")` above is redundant belt-and-suspenders, not a requirement. Either shape
satisfies the "runs after `springSecurityFilterChain`" ordering requirement documented on the class
without any explicit `order(...)` call, since Boot's filter auto-registration default
(`LOWEST_PRECEDENCE`) already sorts after Security's `-100`.

**Named constants for S7** (`VisionApiProperties.java` reserved this wave — these are `static final`
fields on `RateLimitFilter` today, ready to lift into a new `.rateLimit()` nested record):
- `RateLimitFilter.DEFAULT_PERMITS_PER_MINUTE` = 600 → `vision.api.rate-limit.permits-per-minute`
  **(done — the field was made `public` so `VisionApiProperties.RateLimit` defaults from it rather
  than repeating the number)**
- `RateLimitFilter.BUCKET_EVICTION_MILLIS` = 600_000 (10 min) — sweep cadence, no plan-listed key;
  candidate `vision.api.rate-limit.bucket-eviction` if S7 wants it tunable
- `RateLimitFilter.BUCKET_IDLE_MILLIS` = 600_000 (10 min) — idle-before-eviction threshold, same
  candidate-key note as above

**Before/after** (`./mvnw -B -pl station/vision-api test`, counts from Maven's own summary line):
**602 → 614** (+12: 9 in the new `RateLimitFilterTest`, 3 in the new `TokenBucketTest`). Zero
pre-existing files touched, zero assertions edited — confirmed by running the suite once with the
new `ratelimit/` package stashed out (`git stash -u`) and once with it restored, both green.

**Acceptance bar met**: `returns429OnBreach` proves a `429` on breach with the limit as a
constructor parameter (the future `application.yaml`-bound property); `doesNotThrottleTheLiveStreamOrItsTopicsEndpoint`
proves `/api/live`/`/api/live/{id}/topics` bypass the bucket entirely (a one-permit budget survives
20 consecutive requests); `evictsOnlyBucketsIdlePastTheWindow` proves the eviction sweep is
per-bucket-idle, not a blanket clear.

**Docker**: not run — this wave is pure in-JVM unit tests (`MockHttpServletRequest`/
`MockHttpServletResponse` + Mockito), no Postgres/Testcontainers dependency.

**Deferred, out of this wave's exclusive scope**: the frontend pollers (§5 S6 items 1/2,
`station/vision-web/**`, a different agent); wiring the `@Bean`/`application.yaml` keys above
(orchestrator); lifting the three named constants into `VisionApiProperties` (S7).

## docs/plans/done/SCALE-100-PLAN.md §5 S7 done (finishing the properties extraction)

`HlsProxyController` and `LiveUpdateRegistry` both already had a same-shaped `VisionApiProperties.HlsProxy`/`.Live` nested record sitting in `support/VisionApiProperties.java` since Wave B (docs/plans/active/LAYERING-REFACTOR-PLAN.md) — but neither class had actually been rewired to read it; both still carried their own `private static final` timing/sizing constants. This wave finishes that extraction, plus wires two new S2/S5-introduced knobs (`send-timeout`, and a `map-buffer` rename from the stale `marks-buffer`) that never got a config key. `com.drones.vision.api.support.VisionApiProperties` itself was mid-migration when this wave started (the `HlsProxy`/`Live` records already carried `errorBodyPreviewMaxChars`/`maxRedirectHops` and `mapBuffer`/`sendTimeout`/`bufferEviction`) — this wave's own work was entirely the two controllers' constructors, `PublishWiring`'s two new `@Bean` methods, and `application.yaml`.

**`HlsProxyController`**: `CONNECT_TIMEOUT`/`REQUEST_TIMEOUT`/`ERROR_BODY_PREVIEW_MAX_CHARS`/`MAX_REDIRECT_HOPS` (S1's own deferred task 4) all became instance fields (`connectTimeout` folds into the constructed `HttpClient`'s own builder rather than being stored, since nothing reads it back after `build()`). New `@Autowired` 2-arg constructor (`URI`, `VisionApiProperties.HlsProxy`); the existing 1-arg constructor became a package-private test seam defaulting to `VisionApiProperties.HlsProxy.defaults()`, so all 11 pre-existing `HlsProxyControllerTest` cases needed zero changes.

**`LiveUpdateRegistry`**: `COALESCE_MILLIS`/`HEARTBEAT_MILLIS`/`TELEMETRY_BUFFER_CAPACITY`/`EVENT_BUFFER_CAPACITY`/`MAP_BUFFER_CAPACITY` map straight onto `Live`'s matching fields; `CONNECTION_WRITE_TIMEOUT_MILLIS` (S2) → `Live.sendTimeout()`; a new `Live.bufferEviction()` field replaces `BUFFER_EVICTION_MILLIS` (S2, previously undocumented as a config candidate); `DETECTION_EVENT_BUFFER_CAPACITY` → `Live.detectionBuffer()`. Two of those eight names stayed `static final` — `DETECTION_EVENT_BUFFER_CAPACITY` and `CONNECTION_WRITE_TIMEOUT_MILLIS` — because `LiveUpdateRegistryTest` (same package) references them by name outside any instance; both are now derived (`VisionApiProperties.Live.defaults().detectionBuffer()`/`.sendTimeout().toMillis()`) rather than a second hand-copied literal, so they cannot drift from the real default. The five `LiveRingBuffer` fields whose capacity now comes from a constructor parameter (`eventBuffer`/`detectionEventsBuffer`/`mapBuffer`) moved from field initializers to constructor-body assignment — a field initializer runs before any constructor-body assignment, so a capacity sourced from `live.eventBuffer()` couldn't be read from one; `fleetBuffer`/`devicesBuffer` (fixed `capacity=1`, latest-only, never configurable) kept their inline initializers. `FLEET_COALESCE_WINDOW_NANOS` (derived from `COALESCE_MILLIS`) became an instance field `fleetCoalesceWindowNanos`, computed once in the constructor from `coalesceMillis`, for the same reason.

Constructor shape grew from two overloads to four, all funneling into one real implementation:
- `LiveUpdateRegistry(5 collaborators, VisionApiProperties.Live live)` — new `@Autowired` production constructor. `live` is a settings bundle, not a collaborator, so it carries none of the other five parameters' circular-bean-dependency risk and needs no `ObjectProvider` wrapper.
- `LiveUpdateRegistry(5 collaborators)` — the old public production constructor, kept as a legacy overload defaulting to `VisionApiProperties.Live.defaults()`. Necessary because `LiveControllerTest`/`LiveMapScopingTest` (package `com.drones.vision.api.controller`, a different package) construct this class directly and can only reach a `public` constructor — not a candidate for a package-private test seam.
- `LiveUpdateRegistry(5 collaborators, ScheduledExecutorService scheduler)` — the pre-existing package-private test seam, **signature unchanged**, now defaulting `live` too. `LiveUpdateRegistryTest`'s `registry()` helper (the one call site) needed zero changes.
- `LiveUpdateRegistry(5 collaborators, VisionApiProperties.Live live, ScheduledExecutorService scheduler)` — new package-private "full" constructor every other overload delegates to; the one place fields are actually assigned and the three `scheduleAtFixedRate` calls happen.

**The `dispatch-threads` decision (surveyed before implementing, per the task brief's explicit instruction)**: `connectionWriteExecutor` is `Executors.newVirtualThreadPerTaskExecutor()` (S2) — one platform-scheduled virtual thread per submitted write, with no pool-size/thread-count concept to configure at all. There is no bound a `dispatch-threads` setting could honestly mean against that executor type; offering the key would mean reading, storing, and silently ignoring it, which is worse than no knob. **Not implemented.** The reasoning is documented in three places that will stay in sync with the code: the field's own javadoc in `LiveUpdateRegistry.java`, this file's `com.drones.vision.api.live` API-surface subsection above, and `application.yaml`'s own comment block. The key becomes honest, and should be added, only if this executor is ever swapped for a bounded platform `ThreadPoolExecutor` — a decision for whichever wave makes that swap, not this one.

**`marks-buffer` → `map-buffer` rename**: the config field name was never updated when `LiveTopicKind.MARKS`/`LiveTopic.MARKS` were renamed to `MAP` and re-scoped (docs/plans/done/MAP-REWORK-PLAN.md §4.3, Wave C) — `VisionApiProperties.Live` still carried `marksBuffer` until this wave. Verified safe to rename (no test or production reference to the old field/key name existed outside `support/VisionApiProperties.java`/`app.config.properties.VisionApiProperties`/`application.yaml` themselves) before renaming.

**New endpoint shapes**: none — this wave adds zero wire-visible surface. Every change is internal wiring; `HlsProxyController`'s HTTP contract and every `LiveUpdateRegistry`-backed SSE envelope shape are byte-identical to before.

**New tests** (both additive, proving the wiring is live, not just stored): `HlsProxyControllerTest#configuredMaxRedirectHopsBoundsTheHandFollowedRedirectLoop` — an upstream that redirects forever, constructed with `maxRedirectHops=1`, still gets a 502 (rather than following the old hardcoded 5). `LiveUpdateRegistryTest#configuredTelemetryBufferCapacityActuallyBoundsTheRingBufferSize` — constructed via the new full constructor with `telemetryBuffer=2`, three separate coalesced flushes for one asset leave exactly 2 envelopes buffered, not the old default of 50.

**Before/after** (`./mvnw -B -pl storage/persistence,contexts/vision-perception,station/vision-api,station/vision-app -DskipWeb test`, counts from Maven's own `Tests run:` summary line, never summed from surefire XML reports — that directory keeps stale reports for renamed/removed classes until a `clean`): `storage/persistence` **157 → 157** (unchanged), `contexts/vision-perception` **494 → 494** (unchanged), `station/vision-api` **614 → 616** (+2, the two new tests above; zero pre-existing assertions edited), `station/vision-app` **195 → 195** (unchanged) — the default-config acceptance bar this wave's task brief pins ("the default-config suites stay 100% green") is met exactly: identical counts everywhere except the two new tests that were added on purpose, and the whole scoped build is green end to end.

**Docker**: ran, not skipped — `storage/persistence`'s `PostgresDockerIntegrationTest` (157 tests, every nested `@Nested` nested class) executed against a real Testcontainers Postgres, not the docker-unavailable skip path.

**Deferred, out of this wave's scope**: `AssetImageController`'s upload cap and the per-controller paging defaults (e.g. `ActivityController.DEFAULT_LIMIT`) remain unwired to `VisionApiProperties.Paging`/`.Upload` — those two records were never part of this wave's named-constants list and needed no new field additions, so leaving them was a scope decision, not an oversight; `support/VisionApiProperties.java`'s own "Wiring status" javadoc paragraph already flagged this gap correctly.
## docs/plans/done/SYSTEM-STATUS-PLAN.md wave S2 done (subsystem status endpoint, vision-api half)

`GET /api/system/status` — see `SystemStatusController`'s own subsection (API surface, above) for the
aggregation/exception-mapping design, and the "System status" DTO paragraph for the wire shapes. New
in `com.drones.vision.api.live`: `LiveUpdateStatusProvider`/`LiveUpdateStatusDisabledProvider` (the
`live-updates` subsystem's own self-report — see that subsection above) plus two small additive reads,
`LiveRingBuffer#everDropped()` and `LiveUpdateRegistry#connectionCount()`/`#anyBufferEverDropped()`. No
new Maven dependency — `vision-platform` (for `SubsystemStatusPort`/`SubsystemStatus`/`Health`) was
already a direct dependency of this module. `vision-app`'s `SystemStatusWiring` is what actually
populates the controller's `List<SubsystemStatusPort>` — three of the four providers
(`CvStatusProvider`/`MavlinkLinkStatusProvider`/`PublishStatusProvider`) live in their own adapter
modules (cv/grpc, drone-link/mavlink, video-output/publish-hls respectively — see each module's own
MODULE.md), this module owns only the fourth (`live-updates`) plus the controller/DTOs that aggregate
all four.

**Before/after**: **567 → 569 (+2, the new `LiveUpdateStatusProviderTest`)**, confirmed green via the
task's own scoped command: `./mvnw -B -pl core/vision-platform,cv/grpc,drone-link/mavlink,
video-output/publish-hls,station/vision-api,station/vision-app test -DskipWeb` → `Tests run: 569,
Failures: 0, Errors: 0, Skipped: 0` for `vision-api` (`SystemStatusControllerTest` 5 new,
`LiveUpdateStatusProviderTest` 2 new — the latter's shared `ImmediateScheduledExecutorService` test
double was extracted from a `LiveUpdateRegistryTest`-private nested class to its own package-private
top-level file, same package, so both test classes reuse it rather than duplicating a 15-method
`ScheduledExecutorService` fake; `LiveUpdateRegistryTest` itself is unchanged in test count and
behavior). No pre-existing test, route, or DTO shape changed — every addition here is new surface, not
a rework of anything this endpoint's UI wave (S3, a separate agent) depends on already existing.

**Wiring decision**: mirrors the CV-RECONNECT-PLAN §3.3 rule this codebase already established —
`SystemStatusWiring` repeats each provider's own enabling `@ConditionalOnExpression`/
`@ConditionalOnProperty` condition (and its negation, for a companion `Health.DISABLED` bean) rather
than reading a sibling bean's presence via `@ConditionalOnBean`, since that annotation is sensitive to
Spring's bean-definition-processing order in a way a plain property expression is not. `mavlink-link`
is the one provider wired unconditionally (MAVLink has no enable/disable flag of its own).

**Endpoint is readable by any authenticated user** — `SystemStatusController` carries no `managerOnly`/
scope check, a deliberate call (see that class's own subsection above): nothing in `SystemStatus`'s
wire shape is a secret.

## docs/plans/active/DRONE-ONBOARDING-PLAN.md Wave O5 done (vehicle onboarding, vision-api/vision-app/persistence half)

Six new endpoints across two new controllers — `OnboardingController` (PROBE + CONFIGURE stages) and
`ReadinessController` (NEGOTIATE stage, read-only) — see the API-surface controller table above for
the full status-code breakdown, and the "Onboarding" DTO paragraph for every new wire shape. Byte-level
proof every DTO matches docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1's frozen contract exactly
(field names, nullability, enum spellings, explicit `null` never omitted) lives in
`OnboardingWireContractTest` — a plain `JsonMapper`, no Spring context, substring-assertion idiom
matching `ManualControlFrameDtoTest`'s own precedent.

**Endpoints**: `POST /api/onboarding/probe` (pre-registration candidate probe, D7 — not scoped, not
audited, nothing yet exists to scope or audit against), `GET /api/assets/{assetId}/profile` (latest
observed snapshot), `POST /api/assets/{assetId}/probe` (active probe of a registered asset, D8 —
authority action), `POST /api/assets/{assetId}/remediate` (CONFIGURE's vehicle-side half), `GET
/api/assets/{assetId}/readiness`, `GET /api/fleet/readiness`.

**Authority, mapped 1:1 to §6.1**: every gate is enforced inside O3's own `Default*Service` classes
(`DefaultVehicleProfileService`/`DefaultRemediationService`/`DefaultReadinessService`, already closed
before this wave started) — neither the two new controllers nor `RemediationOrchestrator` add any
authority logic of their own; they let `ApiExceptionHandler`'s existing central mapping do the work
(`NoSuchElementException`→404, `AccessDeniedException`(platform)→403, `IllegalStateException`→409).
`/onboarding/probe` is the one endpoint outside that pattern (no asset exists yet to gate against).
Every other write (`/probe`, `/remediate`) requires `canManage` and is audited on denial (D8); every
read (`/profile`, `/readiness`, `/fleet/readiness`) is a **scoped lookup** that collapses
unknown/out-of-scope/no-data-yet into one hiding 404 — a caller who may see an asset but not change it
still gets 403 on a write, never 404, matching the OPS-UX "authority is not visibility" doctrine
already shipped elsewhere in this codebase.

**New `vision-api/support` types**: `OnboardingProperties` (plain framework-free bridge record,
`inventoryWindow`/`requestTimeout`, populated by `OnboardingWiringConfiguration#onboardingApiProperties`
from `vision-app`'s `VisionOnboardingProperties` — the same "vision-api cannot depend on vision-app's
`@ConfigurationProperties`" bridge `VisionApiProperties`/`PublishWiring#snapshotJpegEncoder` already
establish) and `RemediationOrchestrator` (composes `{features, actions}` into dispatched
`RemediationService` calls plus a conditional re-probe — see its own javadoc for the full algorithm
and for the plan gap it papers over: **no single `vision-flight` application service composes
`{features,actions}`→dispatch→reprobe end-to-end**, so this composition had to live here instead of as
a fourth O3 service, flagged for whoever next touches that context). `PARAM_WRITE`-shaped actions are
always reported `UNSUPPORTED` with an honest detail — the frozen `RemediationRequest` shape carries no
target value or explicit-consent flag, and guessing either would be exactly the "probably fine, didn't
check" lie C7 forbids.

**Flag-off guardrail (D17)**: `vision.onboarding.probe.enabled` defaults `false`.
`OnboardingWiringConfiguration` wires `VehicleProfileService`/`RemediationService`/`ReadinessService`
unconditionally (they behave identically regardless of the flag — same "core services unconditional,
only the port varies" shape `AuthWiringConfiguration` already established for `AuthService`/
`UserService`); only `VehicleConfigPort` is flag-gated, to `NoopVehicleConfigPort` when the flag is
`false`/absent, refusing every probe with the exact §8.1-frozen 409 body
(`"vehicle probing is disabled (vision.onboarding.probe.enabled)"`). **No bean at all when the flag is
`true`** — this wave's file scope cannot construct `adapter-mavlink`'s real
`MavlinkVehicleConfigurator` (O4, a separate concurrent wave), and a silent no-op fallback under a flag
an operator explicitly turned on would itself be dishonest; flipping the flag on before O4 lands must
fail application startup, not fail quietly at request time. `OnboardingWiringTest` proves the whole
pipeline wires (every bean present, so the driving adapters mount) while the `VehicleConfigPort`
resolves to the Noop and refuses with the frozen message — deliberately reusing the
`@SpringBootTest(properties = "vision.publish.enabled=false")` shape 15+ existing vision-app test
classes already share, to avoid growing `PostgresContextCustomizerFactory`'s cached-context count
(see that class's own javadoc on the pool-exhaustion risk of a new distinct `@SpringBootTest`
properties combination).

**Persistence (`storage/persistence`)**: `V17__vehicle_profiles.sql` (append-only observations, jsonb
`messages`/`parameters`/`capabilityFlags`), `V18__feature_requirements.sql` (the feature×requirement
matrix as seed data, D6 — eleven rows, `firmware='ardupilot'` only per D13; two plan-sourced
thresholds, `map-position`≥2.0Hz and `visual-geolocation`≥5.0Hz, the other five message-bearing rows
seeded at a **judgment-call** 1.0Hz floor with no plan-given number to draw from), `V19__asset_usage_phase.sql`
(additive columns on `asset_usages` — `phase`/`first_armed_at`/`last_disarmed_at`). `phase` shipped
schema-only at first, deliberately deferred to O7 (`contexts/vision-warehouse`, out of this wave's file
scope) per the plan's module-placement table; O7 landed `AssetUsage#phase()` but reported that
`AssetUsageEntity`/`AssetUsageMapper` mapped nothing to it, silently reverting every reload to
`PREFLIGHT` — fixed in `storage/persistence` by this wave after all (entity field + both mapper
directions + three round-trip tests). `first_armed_at`/`last_disarmed_at` remain unmapped — no
`AssetUsage` field exists for either yet. Full detail — every column, every seeded row, every judgment
call and representation gap — lives in `storage/persistence/MODULE.md`'s own Schema section and its
"O5 done" narrative; not repeated here.

**Before/after** (`./mvnw -B -pl station/vision-api -am test -DskipWeb`, Maven's own `Tests run:`
summary line, three consecutive runs): **624 → 633 (+9, `OnboardingWireContractTest`)**, `Tests run: 633,
Failures: 0, Errors: 0, Skipped: 0` all three times. Zero pre-existing test, route, or DTO shape
changed except `DiscoveredDeviceResponse`'s additive `suggestedOptions` field (D16 — synthesized
purely at the DTO mapping layer from the existing `details` map, no domain change; `details` itself is
untouched and still serializes, so no pre-existing caller of that DTO shape breaks).

**Docker**: not run for `vision-api` itself (no Testcontainers dependency, pure unit/JsonMapper tests);
ran for `storage/persistence`'s `PostgresDockerIntegrationTest` and for `vision-app`'s full suite (both
require Docker since docs/plans/done/POSTGRES-ONLY-CONTEXT.md — Postgres is the only store, no
in-memory devsupport fallback left to skip to).

**Plan gaps found, flagged rather than silently resolved** (see also the migration header comments in
`storage/persistence`): (1) no single `vision-flight` service composes remediation dispatch end-to-end
— `RemediationOrchestrator` above; (2) ANY-DRONE §S1.2's `STATUSTEXT` requirement for `preflight-checks`
is not independently modeled (`FeatureRequirement` holds one message per row); (3) the 45%-low-battery
bar (§S8.1) has no field to live in (`FeatureRequirement` carries `minimumHz`/`requiredParameterName`
only, no percentage threshold); (4) two of the five un-sourced minimum-Hz values are a judgment call,
not a plan-given number; (5) §7's module-placement table's "devsupport in-memory repositories" mention
is stale — POSTGRES-ONLY-CONTEXT.md already deleted that whole layer, so this wave wrote **no**
in-memory devsupport repository for `VehicleProfileRepositoryPort`/`FeatureRequirementRepositoryPort`,
only the real JPA ones.

**Deferred, out of this wave's scope**: wiring `AssetUsageEntity`/`AssetUsageMapper`/
`JpaAssetUsageRepository` to `V19`'s new columns (O7, `contexts/vision-warehouse`, concurrent); O4's
mirror-image `@ConditionalOnProperty(havingValue = "true")` bean constructing the real
`MavlinkVehicleConfigurator` (flagged in `OnboardingWiringConfiguration`'s own javadoc); promoting
`RemediationOrchestrator` into a proper `vision-flight` application service.

## docs/plans/active/DRONE-ONBOARDING-PLAN.md Wave O13 done (the passport REST surface)

Two new endpoints on the existing `OnboardingController` — no new controller was needed, it already
held `VehicleProfileService` and `CurrentUser`: `GET /api/assets/{assetId}/usages/{usageId}/passport`
and `GET /api/assets/{assetId}/usages/{usageId}/drift` (docs/plans/active/DRONE-ONBOARDING-PLAN.md
§8.1's frozen wire contract) — the REST surface O11 built and nothing could reach
(`VehicleProfileService#passport`/`#driftFromPreviousFlight`; that service's `AssetUsageRepositoryPort`
5th constructor argument was already wired by O11's own post-merge fix, so no `vision-app` wiring
change was needed either). Both are scoped reads: `currentUser.scope()` passed straight through, no
authority logic added in the controller — `ApiExceptionHandler`'s existing
`NoSuchElementException`→404 mapping does the whole job, exactly like every other onboarding read
(`/profile`, `/readiness`). Class javadoc's "Status codes (§8.1, frozen)" list updated with both.

**New DTOs**: `FlightPassportResponse`/`ParameterDriftResponse` — see the "Onboarding — the flight
passport" DTO paragraph above for the full shape. `FlightPassportResponse` is the one member of the
onboarding DTO family that *does* use `@JsonInclude(NON_NULL)` — deliberately the opposite of
`VehicleProfileResponse`'s own "never omit, always literal `null`" rule, because a whole missing
snapshot ("we never captured this") is a different claim than one unanswered field inside a captured
snapshot ("we looked and found nothing"), per the plan's own comment on this exact shape.
`ParameterDriftResponse` carries no `NON_NULL` (every field always present); an empty `drift` list
serializes as `{"drift":[]}`, a correct `200` per §8.1 — "nothing to compare" is not an error and
must never be confused with an omitted/withheld field.

**Security — read directly, not inferred**: `station/vision-app`'s
`com.drones.vision.app.config.SecurityConfig` was opened and read in full. Its `securedFilterChain`
bean's `.requestMatchers("/api/**", "/ws/**").authenticated()` rule (line 82) already matches any
path under `/api/**`, including both new ones — they are plain `@GetMapping`s added to an
already-covered controller, not a new prefix. **No change was made to that file.** No ArchUnit or
endpoint-inventory test in `vision-app` needed to learn about the two new routes either:
`ArchitectureTest#restControllersLiveOnlyInApiControllerOrProxyPackage` checks package location only
(`OnboardingController`'s package is unchanged), and `ContextArchitectureTest`'s `DECLARED_EDGES` set
checks cross-context dependency edges, not REST routes — this wave added no new edge, it calls two
more methods on a `VehicleProfileService` `vision-api` already depended on. Confirmed no test in
either module enumerates concrete route strings anywhere else in the module (grepped for
`RequestMappingHandlerMapping`/`getHandlerMethods`/endpoint-inventory patterns; the only two incidental
hits were an unrelated "route" substring in a doc comment and a deleted-route regression test for a
different controller).

**Before/after** (Maven's own `Tests run:` summary line, `-am` used both times per this module's own
build-verification gotcha):
- `./mvnw -B -pl station/vision-api -am test`: **635 → 650 (+15)** — 5 new DTO tests added to
  `OnboardingWireContractTest` (9→14: omitted-snapshot, both-captured, both-omitted, drift-row shape,
  empty-drift-array) and a new `OnboardingControllerTest` (0→10: 200/404/400 for both endpoints, the
  omitted-snapshot case, the empty-drift-array-not-404 case). `[INFO] Tests run: 650, Failures: 0,
  Errors: 0, Skipped: 0`, `[INFO] BUILD SUCCESS`. Zero pre-existing test touched.
- `./mvnw -B -pl station/vision-app -am test`: **214 → 214 (unchanged)**, run in the foreground to
  completion. `[INFO] Tests run: 214, Failures: 0, Errors: 0, Skipped: 0`, `[INFO] BUILD SUCCESS` —
  no new `vision-app` test was needed because no new `vision-app` wiring was needed:
  `VehicleProfileService`'s bean already exposed `passport`/`driftFromPreviousFlight` as ordinary
  interface methods once O11's own post-merge fix wired its 5th constructor argument. Docker actually
  ran (not silently skipped): the raw log shows `Testcontainers version: 2.0.5`, a real Ryuk reaper
  container starting, and `Connected to docker`, and the module summary's `Skipped: 0` covers every
  Postgres-backed test in the run.

**Deferred, out of this wave's scope**: `station/vision-web` has no passport/drift UI yet (a separate,
later wave per O11's own Status section — this wave is backend-only); `vision-perception`'s
`UsageTracker` still needs to call `captureSnapshot` at the `PREFLIGHT`/`POSTFLIGHT` transitions for a
passport to ever have real data to serve (O12, concurrent, out of this wave's file scope — this wave
only exposes the read side O11 already built, it does not make anything populate it).

## docs/plans/done/FIXED-CAMERA-GEO-PLAN.md Wave G4 done (fixed-camera geolocation, REST surface)

Four camera-pose endpoints, `GET /api/map/tracks`, and a `MapEventPayload`/D9 extension — §5's frozen
wire contract, byte-matched. Flag off (`vision.geo.fixed-camera.enabled=false`, the default) makes
every one of these endpoints a `409` and leaves every pre-existing test untouched.

**`CameraPoseController`** (`api/controller/`) — asset-scoped CRUD plus the calibration solve, matching
`AssetController`'s own authority split (D10):
- `GET /api/assets/{assetId}/camera-pose` — visibility only (`requireVisible`, mirrors
  `AssetController#requireVisible`); `404` if no pose is stored, or the asset is unknown/out of scope
  (both hide which — `NoSuchElementException` either way).
- `PUT /api/assets/{assetId}/camera-pose` — full-replace, `requireManageable` (403 if visible-but-not-
  manageable); body validated (`PutCameraPoseRequest#toInput()`) **before** the scope guard, so a
  malformed body is always `400` regardless of authority, matching `AssetController#update`'s ordering.
- `DELETE /api/assets/{assetId}/camera-pose` — idempotent, `204`, `requireManageable`.
- `POST /api/assets/{assetId}/camera-pose/calibration` — `requireManageable`; solves via
  `CameraCalibrationSolver.solve` and **never persists** (D5) — saving is a separate `PUT` the operator
  issues after reviewing the result. `CameraPoseService#put`/`#delete` audit the write themselves
  (this context's first audit write, per D10) — no separate audit call in the controller.

Every method's first line is `FixedCameraGeoProperties#requireEnabled()` — checked before path/body
parsing, so the flag-off `409` is the answer regardless of what else might be wrong with the request.

**`MapTracksController`** (`api/controller/`) — `GET /api/map/tracks`, scoped by `TrackProjectionService
#list(Viewer)` reading `CurrentUser#viewer()` — the same `MapAccessPolicy#canView(layerId)` predicate
`MapMarksController#list` already uses, never `VisibilityScope`. What a page reload rebuilds the picture
from; live deltas ride the `map` SSE topic instead.

**New DTOs** (`api/dto/`), all `@JsonInclude(NON_NULL)` except `CalibrationResponse` (§5's frozen
examples show explicit `null` keys, not omitted ones):
- `CameraPoseResponse(assetId, latitude, longitude, aglMeters, yawDegrees, pitchDegrees, hfovDegrees,
  targetLayerId, source, rmsErrorPixels, updatedAt)` — position flattened (matches `MarkResponse`), no
  `altitudeMeters` field (unused by the projection). Two factories: `from(CameraPose)` (a stored pose)
  and `preview(AssetId, FixedCameraPose, rmsErrorPixels, solvedAt)` (a solved-but-never-persisted
  calibration result — `targetLayerId` always absent, `source` always `CALIBRATED`).
- `PutCameraPoseRequest`/`CalibrateCameraPoseRequest`/`CalibrationPointRequest` — request bodies;
  numeric-range validation (`aglMeters ≥ 0`, `pitchDegrees ∈ [-10,90]`, `hfovDegrees ∈ (10,160)`,
  2–8 calibration points, `u`/`v` ∈ [0,1]) lives entirely in the domain records' own compact
  constructors (`CameraPose`, `CalibrationRequest`, `CalibrationLandmark`) — not duplicated in the DTO,
  the same "let the domain type be the single source of truth" idiom as `CreateMarkRequest`.
- `CalibrationResponse(solved, pose, rmsErrorPixels, quality, reason)` — `rmsErrorPixels` mirrors
  `CalibrationResult#rmsErrorPixels()` verbatim, including its one legitimate `null` case (a refusal on
  a degenerate-geometry check before any fit ran) — a found-but-non-breaking DTO looseness: nothing on
  the frontend dereferences it when `solved:false`, but a future consumer needs to be prepared for it.
- `ProjectedTrackResponse(assetId, trackId, label, layerId, latitude, longitude, rangeMeters,
  errorRadiusMeters, updatedAt, trail)` — one record, three shapes by factory: `from(ProjectedTrackView)`
  (full `GET` row, `trail` populated), `live(ProjectedTrack)` (live `created`/`updated` event, `trail`
  always absent — a reload's `GET` is the trail's own source), `cleared(assetId, trackId)` (live
  `cleared` event, every other field absent). `TrackPointResponse` (one `trail` entry) is deliberately
  bare — `{latitude, longitude, at}` only, no `errorRadiusMeters`/`label`/`layerId` — per §5's frozen
  shape; do not add fields to it without re-checking the plan.
- `MapTracksResponse(List<ProjectedTrackResponse> tracks)` — `GET /api/map/tracks`'s body.

**`MapEventPayload`** (`api/dto/`) gains a `track` field and `from(MapEvent)` gains the `TRACK` case
(entity/action strings stay lowercase, matching every other case). Rides the *existing* `map` topic, not
a new one — `LiveUpdateRegistry`'s existing per-connection `canView(layerId)` scoping applies to a
`TRACK` event exactly as it already does to `MARK`/`DRAWING`/`LAYER`; no scoping code changed for this
wave. A `CLEARED` action always maps through `ProjectedTrackResponse#cleared` (stripped
assetId/trackId-only shape) regardless of the event's underlying payload type, per `MapEvent`'s own
compact-ctor invariant that a `TRACK` event's payload is always a full `ProjectedTrack`.

**`FixedCameraGeoProperties`** (`api/support/`) — the framework-free bridge `vision-app`'s real
`@ConfigurationProperties` `VisionGeoProperties` maps onto (`vision-api` may not depend on
`org.springframework.boot.context.properties`, or on `vision-app` at all), same pattern as
`OnboardingProperties`/`VisionApiProperties`. `enabled` (default `false`, D8) and
`calibrationMaxRmsErrorPixels` (D5, `CameraCalibrationSolver#solve`'s tolerance argument).
`requireEnabled()` throws `IllegalStateException(DISABLED_MESSAGE)` —
`"fixed-camera geolocation is disabled (vision.geo.fixed-camera.enabled)"` verbatim, mapped by
`ApiExceptionHandler` to the frozen §5 `409` body `{"error":"CONFLICT","message":"..."}`.

**D9 — `LiveAndPollDetectionDemand`** (`api/live/`) gains a third OR-term: `hasCameraPose`, a
`Predicate<AssetId>` seam (same idiom as `watchingDetections`) resolved in `vision-app`'s `CvWiring` from
`TrackProjectionRunner#hasCameraPose` — a cache the runner refreshes once per tick, **never** a
per-poll-tick repository hit (the plan's named hazard 2). Fails open like the other two terms (an
exception anywhere in `detectionWanted` reports "wanted", never "not wanted" — see the class's own
Contract section). The two pre-existing public constructors that omit the new predicate default it to
`assetId -> false`, so every pre-G4 caller/test is byte-identical in behavior.

**Tests**:
- `MapEventPayloadTest` (new, `api/dto/`) — 4 tests, the `TRACK` case of `from(MapEvent)`.
- `CameraPoseControllerTest` (new, `api/controller/`) — 21 tests: flag-off `409` ×4 endpoints, `404`
  (unknown/out-of-scope asset, no pose stored), `200` with full/partial DTO shape assertions,
  visible-but-unmanageable `GET` succeeds, `403` on `PUT`/`DELETE`/`POST .../calibration` for a
  pilot-scope caller, body-validation-before-scope-guard ordering, calibrate-never-persists
  (`verifyNoInteractions` on the write path), calibration input validation (point-count, `u`/`v` range),
  malformed-`assetId` `400`.
- `MapTracksControllerTest` (new, `api/controller/`) — 4 tests: flag-off `409`, full-shape `200`
  (including the bare `trail[].{latitude,longitude,at}` shape and `errorRadiusMeters` absent on trail
  points), empty-array `200` when nothing is visible, the caller's `Viewer` threaded into the service
  call unchanged.
- `LiveAndPollDetectionDemandTest` extended (9→13): the new predicate reports demand even with no SSE
  watcher/poll; the two-arg constructor still defaults it to `false` (pre-G4 behaviour preserved); a
  `null` assetId skips the camera-pose half exactly like the SSE half; a throwing predicate fails open.
- `LiveMapScopingTest` extended (4→5): `aTrackOnOneTeamsLayerReachesThatTeamsConnectionOnly` — the
  mandated scoping proof that a `TRACK` event on one team's layer reaches only a connection that
  `canView`s that layer, mirroring the pre-existing `Mark` equivalent in the same file.

**Before/after** (`./mvnw -B -pl station/vision-api -am test`, run three times in the foreground, all
three runs identical): **650 → 684 (+34)**, `Tests run: 684, Failures: 0, Errors: 0, Skipped: 0`,
`BUILD SUCCESS` every time. Every pre-existing test passes unchanged with the flag at its default.

**Deferred, out of this wave's scope**: none — G4's own exit criteria (§8) are fully met. See
`station/vision-app/MODULE.md`'s own G4 entry for the wiring half, including a real circular-dependency
bug this wave's own `FixedCameraGeoEnabledWiringTest` caught (not a defect in this module).

## docs/plans/done/AFTER-ACTION-PLAN.md Wave W1 done (after-action evidence package, backend)

One request against one finished (or still-open) flight returns everything the platform knows about
it — telemetry, detections, marks, recording reference, flight passport, audit trail — as a JSON
manifest or a streamed ZIP, with an honest `PRESENT | ABSENT | TRUNCATED | FORBIDDEN` state per part
(D3). Ships **on**, no flag (D1's own framing: nothing new is exposed, only re-shaped).

**`com.drones.vision.api.support.afteraction`** (new package) — the framework-free assembler and its
value types, deliberately isolated from `dto/`/`controller/` (D1):
- **`AfterActionAssembler`** (constructor: `AssetService, ReplayService, AfterActionSources,
  AfterActionProperties` — 4 params, under the 5-param ceiling via the `AfterActionSources` bundle) —
  no Spring, no Jackson import; `assemble(AssetId, UsageId, VisibilityScope, Viewer, String scopedTo)`
  returns a domain-ish `AfterActionPackage`. Two authority gates, not one: the top-level export gate
  (`scope.canManage(ownership)`, same predicate `AssetController#requireManageable` uses — a PILOT
  sees their asset, 404 never fires, but may not export its evidence package, 403) checked once before
  any part resolves; the `audit` part's own gate (`!scope.canManageOrg()`, mirroring `AuditController`
  verbatim) produces `FORBIDDEN` for that one part rather than failing the whole request. Six
  package-private `resolve*` methods (one per `AfterActionPartKind`), each independently unit-testable
  without going through `assemble()` — this is what let `AfterActionAssemblerTest` exercise the
  `audit` part's `FORBIDDEN` branch directly (see Findings below — it is not reachable end-to-end).
- **`AfterActionPartKind`** (enum, `TELEMETRY, DETECTIONS, MARKS, RECORDING, PASSPORT, AUDIT` —
  declaration order **is** the wire order), **`AfterActionPartState`** (`PRESENT, ABSENT, TRUNCATED,
  FORBIDDEN`), **`AfterActionPart`** (record: `part, state, count, note`), **`DetectionRow`** (one
  flattened detection — a `DetectionResult` carries a whole frame, a CSV needs one row per detected
  object).
- **`AfterActionPackage`** — the assembler's return type: manifest fields plus the raw domain content
  the archive's eight entries are built from. `complete()`/`caveats()` are pure functions of `parts()`
  (see "Mid-wave spec correction" below for `caveats()`'s exact rule).
- **`AfterActionSources`** (record: `MarkService, VehicleProfileService, AuditTrailPort`) — exists
  purely to keep the assembler's constructor at 4 params, same precedent as `vision-events`'
  `ReplaySources`/`vision-learning`'s `TrainingStores`.
- **`AfterActionProperties`** (record: `telemetryMaxPoints, auditLimit`) — the framework-free
  `vision-app`-bridges-onto-this-instance idiom `OnboardingProperties` established; `defaults()` reads
  `ReplayServiceSettings.defaults().maxPointsCeiling()` (2000) so D7's thinning-detection ceiling is
  never a second, independently-drifting literal.
- **`AfterActionArchiveWriter`** — writes the ZIP's eight entries (`manifest.json, README.txt,
  telemetry.csv, detections.csv, marks.geojson, passport.json, audit.csv, recording.txt`), always all
  eight, never a zero-byte one for an `ABSENT`/`FORBIDDEN` part (§3.2). `manifest.json`/`passport.json`
  go through `JsonMapper` (Jackson 3, D8); the two CSVs are hand-written RFC 4180 (`\r\n`, `"`
  doubling, header row always present even when empty); `marks.geojson` is hand-written RFC 7946
  (`FeatureCollection`, empty `features` array when absent). `recording.txt` is the one entry §3.2
  names a literal explanatory line for; the CSV/GeoJSON/passport-JSON entries instead use each
  format's own valid-but-empty shape for `ABSENT`/`FORBIDDEN` — a judgment call (see Findings).

**`AfterActionController`** (`api/controller/`, new) — constructor `AfterActionAssembler, CurrentUser`;
no `@PreAuthorize` (this module carries zero `org.springframework.security` dependency — every
controller in this codebase resolves authority through `CurrentUser#scope()`/`#viewer()`, never a
Spring Security annotation; see Findings for the plan's imprecise wording here):
- `GET /api/assets/{assetId}/usages/{usageId}/after-action` → 200 `AfterActionManifestResponse`.
- `GET /api/assets/{assetId}/usages/{usageId}/after-action/archive` → 200, `Content-Type:
  application/zip`, `Content-Disposition: attachment; filename="after-action-{usageId}.zip"`, body a
  `StreamingResponseBody` (D-mandated, a deliberate departure from `HlsProxyController`'s
  `InputStreamResource` choice — never buffered whole in memory). The package is resolved
  **synchronously** before the `ResponseEntity<StreamingResponseBody>` is returned, so a 404/403 lands
  on the response before any async dispatch starts at all — proven by
  `archiveReturns404BeforeAnyAsyncDispatchForAnUnknownAsset`/`...403...` asserting
  `request().asyncNotStarted()`.
- Both endpoints share one error mapping, entirely `assemble()`'s own exceptions through
  `ApiExceptionHandler`: `NoSuchElementException` (unknown/out-of-scope asset, unknown/mismatched
  usage — all collapse to 404, never leaking which) and `AccessDeniedException` (sees the asset, may
  not export it — 403). No new exception mapping was added to `ApiExceptionHandler` — both types were
  already wired for other endpoints.

**New DTOs** (`api/dto/`), **no `@JsonInclude(NON_NULL)` on either** — §3.1's `endedAt: null` (a
still-open usage) and every part's `note: null` are meaningful and must appear on the wire, the
opposite convention from `FlightPassportResponse`:
- `AfterActionManifestResponse(assetId, assetName, usageId, startedAt, endedAt, open, generatedAt,
  scopedTo, parts, complete, caveats)` — `from(AfterActionPackage)`.
- `AfterActionPartResponse(part, state, count, note)` — `part` lowercase (`wireName()`), `state`
  the enum name verbatim — `from(AfterActionPart)`.

**Mid-wave spec correction applied (D3.1 `caveats` rule)**: the plan owner corrected §3.1 after the
parallel web-agent wave found its worked JSON example contradicted its own prose rule twice (omitted
`audit`'s `FORBIDDEN` note; paraphrased `telemetry`'s note instead of quoting it verbatim). The
corrected, now-authoritative rule: `caveats` is **every non-null `note`, verbatim, in canonical part
order, regardless of `state`** — a `PRESENT` part's note (the `marks` part's standing "not bound to a
flight" qualifier, D5, is the case that matters) still counts. `complete` stays defined on part states
only (`true` iff all six `PRESENT`), so `complete: true` with a non-empty `caveats` is now an expected,
correct combination, not a bug — "an approximation is not an absence." Landed after
`AfterActionPackage`/`AfterActionArchiveWriter`/DTOs were already written but before any test existed,
so nothing needed retroactive rewriting; `AfterActionArchiveWriter#readme`'s "Caveats: none" branch
(previously keyed on `complete()`) was fixed to key on `caveats().isEmpty()` instead — the one place
the old, incorrect coupling was baked into logic rather than just prose.

**Findings — where the plan needed a judgment call or was imprecise** (every wave in this repo finds
at least one; this wave found five):
1. **D7's thinning-detection heuristic is scoped to `telemetry` only, but `DefaultReplayService.timeline`
   thins `detections` with the exact same `thin()` call and the exact same ceiling.** A flight with
   more detection-results than `telemetryMaxPoints` will report `detections: PRESENT` with no
   truncation note — the precise "quietly omit" failure this feature exists to prevent, just on the
   part D7 didn't name. Implemented literally per the frozen contract (its own §3.1 worked example
   shows `detections` as plain `PRESENT`, count 214, no caveat) since deviating from a frozen contract
   a parallel agent is coding against is worse than reporting the gap. Flagged, not fixed — a
   follow-up wave's job.
2. **The `audit` part's `FORBIDDEN` state is structurally unreachable end-to-end via `assemble()`
   today.** `VisibilityScope.canManage(ownership) == true` always implies `canManageOrg() == true`
   (both reduce to `UNBOUNDED`, or `GROUPS` with a matching group) — so whoever clears the top-level
   export gate always also clears the audit gate; only a caller who fails the top-level gate (and
   therefore never reaches part resolution) could see it. The branch is real, correctly wired to
   `AuditController`'s own policy, and covered directly by `resolveAuditReturnsForbiddenWhenScopeMayNotManageOrg`
   — exactly why every `resolve*` method is package-private rather than folded into `assemble()`.
3. **§3.1's example `scopedTo: "referee@example.org"`** cannot be produced from any existing seam —
   `CurrentUser` has no email/username accessor, only `userId()` (a UUID). `AfterActionController`
   passes `currentUser.userId().value().toString()` instead; a display-name/email seam is a separate,
   undelegated piece of identity work.
4. **The plan's "`@PreAuthorize` consistent with `UsageTimelineController`" (§6 step 4) describes a
   pattern that does not exist anywhere in this codebase.** No controller uses `@PreAuthorize` —
   `vision-api` may not depend on `org.springframework.security` at all. Implemented via
   `CurrentUser#scope()`/`#viewer()`, matching every other controller.
5. **§3.2's "ABSENT/FORBIDDEN parts still get a file — a single explanatory line" general rule reads
   as if it applies uniformly, but conflicts with the same section's per-format rules** (CSV's "header
   row always present" implies structured emptiness, not prose; RFC 7946 GeoJSON validity likewise).
   Resolved by using each format's own valid-but-empty shape for `telemetry.csv`/`detections.csv`/
   `marks.geojson`/`passport.json`, reserving the literal "one explanatory line" treatment for
   `recording.txt` — the one entry §3.2 names that way explicitly.

**Tests** (all new):
- `AfterActionAssemblerTest` (`api/support/afteraction/`, plain JUnit + hand-written fakes of
  `AssetService`/`ReplayService`/`MarkService`/`VehicleProfileService`/`AuditTrailPort` — no Spring, no
  Mockito, matching the class's own framework-free design) — 23 tests: full happy path (all six parts
  `PRESENT`, fixed order), a still-open usage (`endedAt: null`, never 404), a no-recording/no-passport
  manifest asserted field-by-field, every §3.3 error case (unknown asset, mismatched usage, visible-
  but-unmanageable → 403 with `replayService.timeline` proven never called), each `resolve*` method's
  every state (including the unreachable `audit` `FORBIDDEN`), `flattenDetections`
  (one row per detection, not per frame), `filterMarksInWindow` (inclusive both ends).
- `AfterActionControllerTest` (`api/controller/`) — 10 tests: manifest 200 with the corrected
  `caveats` behavior asserted directly (a `PRESENT` marks part's note appears in `caveats` even though
  `complete: true`), `note: null` asserted present-not-omitted on the wire, still-open-usage 200, every
  §3.3 error mapping ×2 endpoints, the archive's 8-entry-fixed-order ZIP via the
  `request().asyncStarted()` → `asyncDispatch()` pattern (no prior precedent for `StreamingResponseBody`
  testing existed in this module before this wave), and the synchronous-404-before-any-async-dispatch
  proof for both error paths on the archive endpoint.

**Before/after** (`./mvnw -B -pl station/vision-api,station/vision-app test -DskipWeb`, foreground; no
`-am` — upstream context/adapter jars were already freshly installed by an earlier step in this same
session and this wave touches no upstream module, so `-am` would only have dragged an unrelated,
pre-existing flaky `adapter-mavlink` UDP-port-bind test into the run): vision-api **684 → 717 (+33)**,
vision-app unchanged at **237 → 237 (+0 test files, +1 wiring bean)** — `ArchitectureTest`/
`ContextArchitectureTest` both still green, so the new `AfterActionWiringConfiguration` bean introduces
no dependency-rule violation. `BUILD SUCCESS` both modules. Docker ran (Testcontainers Postgres,
Flyway migrated to v22, confirmed by log output) — not skipped.

**Deferred, out of this wave's scope** (§7, verbatim): a raw (unthinned) telemetry export path; fixing
`UsageTimeline#detections`'s stale field javadoc ("always empty today" — confirmed false by reading
`DefaultReplayService`, see Findings item 1, which is the real, non-stale version of this same gap);
signing/hashing the package. Also deferred, not in §7 but found this wave: Finding 1 above (`detections`
truncation-detection), and a `scopedTo` display-name/email seam (Finding 3).

## docs/plans/done/VISUAL-GEO-V2-PLAN.md Wave H5 done (visual geolocation, REST + SSE surface)

Two controllers, six routes total, matching §3.3's frozen wire contract byte-for-byte; a 7th
live-update port bridged onto SSE `geo:<assetId>` (§3.4/D11). Flag off
(`vision.geo.visual.enabled=false`, the default) makes every one of the six routes a `409` with the
frozen D9 body and leaves every pre-existing test untouched.

**`GeoCorrectionController`** (`api/controller/`) — reads `TrackCorrectionService`:
- `GET /api/geo/corrections/live` — the latest correction per asset the viewer may see; starts from
  `AssetService#assets(scope, false)` (which already applies GROUPS) rather than asking the service
  for "every asset's latest" unscoped, since `DefaultTrackCorrectionService#isVisible` only enforces
  UNBOUNDED/ASSIGNED_ASSETS internally (the same amendment `vision-flight`'s H2a wave documented) —
  GROUPS is deferred entirely to this edge.
- `GET /api/geo/corrections?usageId=&limit=` — oldest-to-newest for one usage, `limit` defaulting to
  2000, clamped to `[1,10000]` (`400` outside that range); resolves the usage's owning asset and
  calls `AssetService#details` purely as a scope guard — unknown or out-of-scope usage is
  `NoSuchElementException` → `404`, "hide, don't 403."

**`GeoRegionController`** (`api/controller/`) — a thin proxy onto `ReferenceRegionService`, which owns
the real merge between cv-service's built regions and Java's own in-memory in-flight ingest jobs
(D10 — no `geo_regions` table):
- `GET /api/geo/regions` — every known region, built or building; no additional authorization
  (regions are a global map-tile resource, not asset-scoped).
- `POST /api/geo/regions` — starts an async ingest, `202` with status `BUILDING`; body validated
  (`RegionIngestRequest#toSpec()`) **before** the `canAdminister()` guard, the `CameraPoseController`
  precedent — malformed body is always `400` regardless of authority.
- `DELETE /api/geo/regions/{regionId}` — idempotent, `204`, `canAdminister()`.
- `GET /api/geo/regions/{regionId}/progress` — `404` if neither an in-flight job nor a READY region
  exists.

Every method's first line is `VisualGeoProperties#requireEnabled()` (framework-free bridge record
over `vision-app`'s real `@ConfigurationProperties`, the same pattern `FixedCameraGeoProperties`
documents) — checked before path/body parsing, so the flag-off `409` is the answer regardless of what
else might be wrong with the request.

**`GeoServiceUnavailableException` → 503** (new, `ApiExceptionHandler`) — `GeoRegionController#safely`
translates any transport failure `ReferenceRegionService`'s underlying gRPC port lets through
(unnameable here as `io.grpc.StatusRuntimeException`) to this, while rethrowing every already-mapped
exception type unchanged. Symmetric on `ingest` even though that path never blocks on cv-service
reachability in practice (the build runs on a background thread; failure reports through `progress`,
not the POST call).

**SSE — `LiveTopicKind.GEO` / `LiveTopic.geo(AssetId)`** (§3.4/D11, 8th topic kind) —
`LiveUpdateRegistry` now implements `TrackCorrectionLiveUpdatePort` (6th live-update port); its
`geoBuffers` uses a ring capacity of **1** (latest-wins — a stale fix is worse than none), unfiltered/
opt-in like every other asset-scoped topic. `vision-app`'s `NoopLiveUpdatePublisher` picks up the
same port when `vision.live.enabled=false`.

*Tests:* `GeoCorrectionControllerTest` (+9) — live/forUsage happy paths, GROUPS-at-edge scoping via
`AssetService#assets`, limit clamping (`0`/`10001` → `400`), unknown/out-of-scope usage → `404`, and
D9 `409` on both routes, and (wave H8) `forUsageSerializesTheTwoBooleansThatDecidePromotion`, a `PROBABLE`
row asserting `cellCalibrated`/`sequenceConverged` reach the JSON as `true`/`false` rather than being
dropped — the point of §9.11 defect 4 is that a `PROBABLE` row must be able to say *which* gate it failed. `GeoRegionControllerTest` (+13) — all four routes' happy paths,
`canAdminister()` 403-vs-body-400 ordering on `POST`/`DELETE`, unknown region → `404` on `progress`,
the 503 translation on a simulated transport failure, and D9 `409` on all four routes.

**Before/after**: vision-api **718 → 740 (+22: `GeoCorrectionControllerTest` +9,
`GeoRegionControllerTest` +13)** — the correction controller's ninth test arrived in wave H8 with
`CorrectionResponse`'s two new promotion booleans (see §3.3/§9.12), measured as part of the same 27/27-module `BUILD SUCCESS` that
landed this wave (real Postgres 16 container via Testcontainers; Docker ran, not skipped) —
see the H5 commit (`feat(station): H5 -- persistence, REST, SSE, wiring for visual geolocation`)
for the full cross-module figures (`adapter-persistence` 205→214, `vision-app` 237→241).

**Deferred, out of this wave's scope**: none identified against §3.3/§3.4/§3.7 — every frozen
endpoint, DTO, and exception mapping matched the plan as specified. H7 (per §5) is the next wave, not
scoped here; see `station/vision-app/MODULE.md`'s own H5 entry for the composition/wiring half.

**docs/plans/done/STREAM-STATE-PLAN.md wave S4 — what "somebody is watching this video" means here.**

- `LiveUpdateRegistry#watchingAsset(AssetId)` (new, public) — whether any open connection carries
  **any** topic scoped to that asset. Deliberately broader than `watchingDetections`: that one asks
  whether anyone wants *boxes*, which since CV-DEMAND is off by default and therefore says nothing
  about whether the video is being watched. Video has no SSE topic of its own (it travels over
  HLS/WHEP), so an asset-scoped subscription is the closest honest proxy this registry can offer —
  and it is only ever one OR-term.
- `public final class LiveHlsAndReaderVideoDemand implements VideoDemandPort` — structurally the
  sibling of `LiveAndPollDetectionDemand` (same narrow-`Predicate` seams, same never-throws contract,
  same clock seam). Three OR-terms, **cheapest first so the network one short-circuits**: `watchingAsset`
  → a recent `touched(StreamId)` within `demandTtl` → `MediamtxReaderProbe#hasReaders`.
- `HlsProxyController` takes an optional third constructor arg (`LiveHlsAndReaderVideoDemand`, N-1-arg
  convenience ctor keeps every existing call site) and stamps demand on every proxied fetch —
  **before** the upstream call, so a viewer still counts while mediamtx is slow or erroring. An
  unparseable id is logged at DEBUG and ignored: this is a side observation, and failing the proxy
  request over it would turn bookkeeping into a broken video player.

**Fail-open, and the stakes are higher than the detection port's identical choice.** A wrong `false`
there gates a detector; a wrong `false` here *stops the stream*, in front of an operator using it,
attributing it to nobody. Notably this is also what happens when mediamtx is unreachable: nothing is
reaped while we cannot see who is watching, which is the correct way for this policy to break.

**Deliberately not a demand signal: `GET /api/streams/{id}/snapshot`.** Checked rather than assumed —
its only SPA callers are the camera-calibration wizard and the alert detail panel, both of which show
one static JPEG. It is not a watching poll, so counting it would have kept streams alive for a page
nobody has open.

*Tests:* `LiveHlsAndReaderVideoDemandTest` (+8) — each term alone, the TTL boundary, the WHEP case
(readers with no SSE and no proxy traffic), the single answer that stops a stream, both throwing
seams failing open, an explicit assertion that an open cockpit means mediamtx is **not** asked, and a
null asset treated as ordinary rather than as failure.

**docs/plans/done/STREAM-STATE-PLAN.md wave S5 — the ended-stream read path.** `GET
/api/usages/by-stream/{streamId}` on `UsageTimelineController` (now four endpoints, still three
collaborators — no new dependency: `UsageService` was already injected for `GET /api/usages`).

- **Why not `StreamController`.** That is where a reader would look first, and it is exactly the
  wrong place twice over: it sits at this codebase's five-constructor-parameter ceiling (the reason
  `StreamDetectionSupport` exists), and the resource being read is a *usage*, not a stream — the
  stream is gone. Putting it under `/api/usages` keeps the axis split §1 of the plan is built on.
- **404, never 403, for an out-of-scope usage.** `UsageService#byStream` collapses "you may not see
  it" and "it does not exist" into the same `Optional.empty()`, so the status code cannot be used to
  probe for the existence of other operators' flights — the same posture `GET /api/usages` already
  takes by silently excluding rows it will not show.
- **No new DTO.** It returns `UsageSummaryResponse`, the row shape `GET /api/usages` already serves,
  rather than the existing `AssetUsageResponse` (which no controller returns directly and carries
  positions but no `assetName`/`durationSeconds`). A client that follows this lookup renders the same
  card it already renders in the replay library.

*Tests:* `UsageTimelineControllerTest` (+4, 25 total) — the mapped 200 body, scope and parsed
`StreamId` passed through, 404 on empty, 400 on a malformed UUID. Module **734/734**.

### Endpoint authorization: the two seams and the guard (LIVE-SCOPE W1)

Authorization here is hand-written — there are **no** Spring Security role annotations anywhere in the
repo, and the filter chain only asserts `.authenticated()`. A handler that forgets to check is not
caught by anything at runtime, so it is caught at build time instead: `EndpointAuthorizationTest`
(vision-app) requires every `@RestController` handler to reach one of the two authority seams through
its own call graph, or to carry `@OpenByDesign(reason = …)`.

| Call | Answers | Counts as authorization? |
|---|---|---|
| `CurrentUser.scope()` | may they? (assets) | **yes** |
| `CurrentUser.viewer()` → `MapAccessPolicy` | may they? (map — deliberately not `VisibilityScope`) | **yes** |
| `CurrentUser.userId()` / `.ownership()` | who is this? | **no — attribution for the audit trail** |

That last row is the trap this guard exists to catch: every write already passes `userId()` for the
audit trail, which reads exactly like a permission check without being one. Device CRUD looked
authorized for precisely that reason and was not.

**Status 2026-08-21 (LIVE-SCOPE W1):** guard in place; 46 handlers had no authority check, 8 were
verified genuinely open and annotated, 38 sit in the test's `TEMPORARY_UNSCOPED` ledger. Waves W2–W5
empty it; entries outside the live surface are named there as still unowned.

### `com.drones.vision.api.security.StreamAccess` — the live-operations authority seam (LIVE-SCOPE W2)

Closes the gap the W1 audit found on the live surface: none of `StreamController`'s eight handlers
checked the caller's `VisibilityScope` at all, so a PILOT scoped to two assets could list, read,
snapshot and reconfigure any stream in the fleet. `StreamAccess` (new, `final`, one `@Component`,
constructor-injected — no interface, since nothing else implements or substitutes it) composes:

- **Device → asset → owner:** `AssetRepositoryPort.findByDeviceId(DeviceId): Optional<Asset>`
  (already published by warehouse — no context/port change was needed for this wave).
- **Stream → device:** `StreamService.streams()` filtered by `streamId`, the same list `GET
  /api/streams` is already built from — there was no dedicated `streamId → deviceId` lookup, and
  adding one would have duplicated data `streams()` already carries.
- **The visibility check itself:** `VisibilityScope.includes(AssetId, Ownership)`.

**Two decisions worth flagging:**

1. **Includes, not `canManage`, gates all eight handlers — including the three "writes" (start,
   stop, updateConfig).** The plan's §2.2 table calls for `canManage(ownership)` on the writes, but
   `canManage` is hardcoded `false` for every `ASSIGNED_ASSETS` scope *regardless of the asset* (see
   `VisibilityScope`'s own javadoc: seeing-and-flying the assigned aircraft "is the whole of a
   pilot's authority"). Read literally, the plan's own table would 403 a PILOT starting or stopping
   their *own* assigned stream — directly contradicting its "a PILOT may still start+stop their own
   assigned asset" clause, and the hard constraint this wave was built against. Since `canManage`
   and `includes` agree for every other `Kind` (both `true` for `UNBOUNDED`; identical group test for
   `GROUPS`), `includes` is the only reading that does not self-contradict, and is what all eight
   handlers use — one check, no read/write split, nothing to call by mistake. **Flagging this back
   to the plan as a defect worth correcting at the source**, not just worked around here.
2. **A device that belongs to no asset at all is visible only to a caller whose scope
   `canAdminister()`** (the same "deployment-global, no group boundary" gate `AssetController#create`
   uses) — an unowned device is a fleet-administration concern, not any one group's, so it neither
   fails open nor blanket-fails the request.

`requireVisible(StreamId)` is a **no-op** when the id does not currently name a running stream (no
device to resolve, hence nothing to check) — every handler's pre-existing "unknown/stopped stream"
behavior (404 for `config`/`snapshot`/`updateConfig`, forgiving empty/idempotent result for
`tracks`/`detections`/`stop`) is unchanged; this leaks nothing, since those responses already say
nothing ownership-specific. `list` calls `filterVisible`, not `requireVisible` — filtered, not
all-or-nothing 403'd, per the plan's §2.2.

**Constructor ceiling.** `StreamController` now takes six constructor parameters, one past this
codebase's five-parameter target (`.claude/skills/java-clean-code/SKILL.md` §3). None of the other
five collaborators (URL resolution, JPEG encoding, detection-demand bookkeeping) can absorb
`StreamAccess` without conflating an authorization concern into an unrelated one, so the sixth
parameter is accepted deliberately — documented in the class's own javadoc — rather than forced into
a false merge.

**Also scoped this wave, same "visible, not exclusive-claim" posture:**

- `AssetStreamController#stopStream` — previously unscoped by design (mirroring
  `AssetService#stopStream`'s no-op-for-unknown-asset contract); now re-reads the asset through
  `CurrentUser.scope()` first, exactly like `startStream`'s pre-existing `requireInScope`. An
  out-of-scope or unknown asset now 404s instead of the previous unconditional no-op/204.
- `SimulationController#stop` — same `assetService.details(scope, id)` re-read before
  `simulationService.stop(id)`.
- `SimulationController#simulate` — closes a separate asymmetry: it registers a brand-new asset
  exactly like `AssetController#create`, but had no gate at all. Now requires
  `scope().canManageOrg()` (true for `UNBOUNDED`/`GROUPS`, false for `ASSIGNED_ASSETS`) — a caller
  that fails it gets 403 via `AccessDeniedException`, matching `create`'s own gate.

All four of the above are deliberately **scoped, not exclusive-claim/arbitration**: two authorized
operators contending for the same aircraft's stream is out of scope here and stays with
`CREW-CONTROL-PLAN.md` §2.6/§4.5 (`AssignmentRole{PIC,OBSERVER}` + a TTL control claim).

**Guard-satisfaction, for the orchestrator's `EndpointAuthorizationTest` ledger:** all eleven
handlers touched this wave now reach the guard — the eight `StreamController` handlers call a method
on `StreamAccess` directly (satisfies `owner.endsWith("Access")`); `AssetStreamController#stopStream`,
`SimulationController#stop` and `SimulationController#simulate` all reach `CurrentUser.scope()`
directly or one call away. They should be removable from `TEMPORARY_UNSCOPED`.

**Tests:** `StreamControllerTest` (+22), `AssetStreamControllerTest` (+2), `SimulationControllerTest`
(+4) — for every one of the eleven handlers, a PILOT scoped to a different asset gets 404/403 (proved
against the pre-W2 code first: 756/756 green with **no** such case existing) and a PILOT scoped to
the stream/asset's own owner keeps working (the hard constraint). Module **778/778**, unbounded
(default-config, `vision.auth.enabled=false`-equivalent) scope untouched throughout — the guardrail
bar.

### `LiveAssetAccess` — SSE per-asset topic authorization (LIVE-SCOPE W3)

W2 scoped every REST stream-management handler; it left the SSE data plane itself open. `LiveController#connect`
called `currentUser.viewer()` (passes the W1 guard) purely to scope the `map` topic — the per-asset
`telemetry:<assetId>`/`detections:<assetId>` topics (and `geo:<assetId>`, present in source since
docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.4/D11 but **not mentioned in the LIVE-SCOPE-PLAN.md text** —
flagging this back to the plan as a gap in its own survey) were unauthorized at subscribe time: any
authenticated caller could subscribe to any asset's live feed by guessing its id. `updateTopics` never
verified the caller owned the target connection, nor authorized a newly-added topic, and a
`Last-Event-ID` resume replayed the shared ring buffer with no re-check at all — three defects, closed
together since all three are one authorization surface.

**Design: two authorization points, one policy, one seam extension.** See `LiveAssetAccess`'s own
entry above ("Scoped SSE delivery") for the full mechanism (`filterTopicsParam`/`filterAdditions` at
subscribe time, `deliveryPredicate` at delivery/resume time, one shared TTL cache). `StreamAccess`
needed one small addition to serve both W2's request-thread callers and this wave's off-thread,
per-connection re-checks: `visibleAsset(AssetId, VisibilityScope)`, the same `includes`-gated policy
`visible(DeviceId)` already ran, taking an explicit `VisibilityScope` parameter instead of reading
`CurrentUser.scope()` — extending the existing seam rather than standing up a second one, per the task's
own instruction ("one seam is better than two").

**The guard's blind spot, found while designing this wave, not merely worked around:**
`EndpointAuthorizationTest`'s static BFS only recurses into classes whose owner starts with
`com.drones.vision.api`, so a check reached only through a stored `java.util.function.Predicate`
field/closure is invisible to it (`Predicate.test()`'s owner is `java.util.function.Predicate`, not this
module). Relying solely on `deliveryPredicate`'s per-delivery re-check — the most direct reading of
"authorize each per-asset topic against `CurrentUser.scope().includes(...)`" — would have passed every
runtime test while silently failing this build-time guard for `updateTopics`, since nothing in that
method's own call graph reaches an authority seam directly. `LiveController` therefore also calls
`filterTopicsParam`/`filterAdditions` directly in both handler bodies — a second, genuine reason (beyond
subscribe-time UX) `LiveAssetAccess` exists as a class calling itself, deliberately named to end in
`"Access"`, rather than only as a factory for a predicate.

**Connection↔user binding.** `LiveConnection` now carries the `UserId` that opened it (`ownerUserId()`);
`LiveUpdateRegistry#updateTopics` 404s (never 403 — "existence hides itself," matching W2's own idiom)
for both an unknown `connectionId` and one that exists but belongs to someone else, so a caller who
merely learned another connection's id cannot distinguish the two.

**TTL:** `vision.live.asset-access-ttl-ms` (`station/vision-app/src/main/resources/application.yaml`,
under the active `vision: live:` block, default `5000`) — deliberately below `MapVisibility`'s `10_000`ms:
this cache bounds exposure of an aircraft's live position, a narrower, more sensitive fact than a map
layer's mere existence. Chosen over a same-value reuse of `MapVisibility.TTL_MILLIS` because the two
predicates answer different-stakes questions and nothing forces them to share a knob; a dedicated
property means either can move independently later without a misleading shared name.

**Guard-satisfaction:** `connect`/`updateTopics` both now call a method on `LiveAssetAccess` directly
(`filterTopicsParam`/`filterAdditions`), satisfying `owner.endsWith("Access")` — should be removable
from `TEMPORARY_UNSCOPED` (they were not new entries there, since `connect` already passed via
`viewer()`, but `updateTopics` had no authority check at all before this wave and should have been).

**Tests:** new `LiveAssetScopingTest` (+7) proves, over real SSE frames with a real `LiveUpdateRegistry`:
a PILOT's own assigned asset streams normally through connect/PATCH/resume; a foreign asset's
`telemetry`/`detections` topic is silently dropped at connect (never delivered, never appears in the
topic list); `updateTopics` against another user's connection 404s; `updateTopics` cannot add a foreign
asset topic; a scope revoked **mid-connection, with no PATCH or reconnect**, stops delivery on an
already-open connection (the per-delivery re-check, not merely subscribe-time filtering); and a
`Last-Event-ID` resume for a now-revoked asset neither re-grants the topic nor replays its buffered
history. `LiveControllerTest`/`LiveMapScopingTest`/`LiveUpdateRegistryTest` updated for the new
`connect`/`register`/`updateTopics` signatures (an always-visible `LiveAssetAccess` test double, built
from Mockito mocks of `StreamAccess`/`ScopeResolver`/`UserRepositoryPort`, added to each). Module
**785/785** (778 + the 7 new `LiveAssetScopingTest` cases), unbounded (default-config) scope untouched
throughout — the guardrail bar holds.

### Device + geofence CRUD authority (LIVE-SCOPE W5)

Closes the last two holes the W1 audit found: `DeviceController`'s five handlers and
`GeofenceController`'s four passed `userId()` for the audit trail (attribution) but ran no authority
check at all — the exact trap the guard exists to catch, and the case that motivated W1 in the first
place. `DeviceService` itself carries no `VisibilityScope` reference and gained none this wave — see
"Where the check lives" below.

**Device authority — three different gates for three different questions:**

- **`register`/`delete` require `scope().canManageOrg()`** (the same org-level gate
  `AssetController#create` uses), not the deployment-global `canAdminister()` the plan's §2.2 table
  names for a "global" resource. Reasoning specific to this type: `Device` carries no `Ownership`
  field of its own — only an `Asset` does, once a device is assigned to one — so a freshly-registered
  or about-to-be-deleted device row has no per-group boundary to check yet (`register`) or
  independent of (`delete`, which removes the row itself, not any one asset's membership in it).
  `canManageOrg()` mirrors `AssetController#create`'s own reasoning exactly: team-scoped management,
  not deployment-global administration.
- **`update`/`setState` reach the device through `StreamAccess#requireVisible(DeviceId)`** — the
  identical device→asset→owner resolution W2 built for `StreamController#start`, reused verbatim
  rather than re-implemented. Deliberately **not** `canManage(ownership)`: that predicate is
  hardcoded `false` for every `ASSIGNED_ASSETS` (PILOT) scope regardless of the asset (see
  `VisibilityScope`'s own javadoc), which would 403 a PILOT editing or retiring their own assigned
  camera — exactly the case this wave's "a PILOT must keep working" constraint forbids. This is the
  same W2 correction applied a second time: `includes`, not `canManage`, is the only predicate that
  does not contradict "a PILOT may still operate their own assigned asset." `StreamAccess`'s own
  javadoc on `requireVisible(DeviceId)` now documents both call sites side by side, so the next
  reader sees the shared reasoning in one place rather than two independent copies of it.
- **`list` filters via a new `StreamAccess#filterVisibleDevices(List<Device>)`** (mirroring
  `filterVisible(List<ActiveStream>)`'s existing shape exactly), narrowing rather than 403ing the
  whole list — the identical "filtered, not all-or-nothing" posture `StreamController#list` already
  established.
- **An unowned device (belongs to no asset at all) is reachable only by a caller whose scope
  `canAdminister()`** — `StreamAccess`'s own pre-existing W2 ruling for this exact case, reused
  unchanged rather than re-decided; `requireVisible`/`filterVisibleDevices` both fall through to it
  via the existing private `visible(DeviceId)`.

**Where the check lives — controller, not service.** `DeviceService`/`DefaultDeviceService` gained no
`VisibilityScope` parameter. Every check above runs in `DeviceController`, before the service is ever
called — the same layering `AssetController` already uses (its mutate methods — `update`/`setState`/
`delete`/`assignDevice`/`unassignDevice` — take no scope either; only its scoped *reads* do). Adding a
scope parameter to `DeviceService` would have meant threading `VisibilityScope` through
`contexts/vision-warehouse` for a check `StreamAccess` already resolves entirely at the vision-api
edge with data warehouse already publishes (`AssetRepositoryPort#findByDeviceId`) — no context-module
signature changed, no port changed, no ArchUnit boundary touched.

**Geofence authority — every zone is the plan's "global" case, so `canAdminister()` gates every
write.** The plan's §2.2 table splits geofence writes into "scoped to the zone's owning asset/group"
(`canManage()`) versus "a global zone… requires `canAdminister()`," conditional on whether the model
has a notion of an asset-bound zone at all. It does not: `GeofenceZone` carries no asset/group field,
and `GeofenceService`'s own pre-existing javadoc already states zones are "global reference data — no
ownership, no per-user scoping, no audit trail." Every zone in this codebase is therefore the plan's
"global" case with no narrower one to fall back from, so `create`/`update`/`delete` all require
`scope().canAdminister()` uniformly — **a MANAGER's `canManageOrg()` does not reach a boundary every
group's aircraft must obey**, consistent with this wave's framing of geofences as flight-safety data,
not per-group inventory. `GeofenceService` itself needed no change (it already takes no scope), so no
`contexts/vision-flight` file changed this wave.

**`list` stays fully open, `@OpenByDesign`, not filtered — a deliberate departure from the plan's
literal "filter to zones the caller may see."** Since no zone has an owning asset/group, "the zones a
caller may see" is unconditionally every zone, for every scope — there is nothing to filter *by*.
Two ways to express that: call `currentUser.scope()` and discard the result (satisfies the guard
without meaning anything), or say so honestly via `@OpenByDesign`, the same annotation
`CategoryController#list` already carries for identical structural reasons ("deployment-wide
reference data… carries no per-asset or per-user information"). Chosen over the first option because
it is dishonest theater to call a check that decides nothing, and chosen over literally filtering
because there is no field to filter on without inventing one. Sharpened here by a safety argument
`CategoryController` does not need: hiding a keep-out zone from a PILOT because it "isn't theirs"
would create the exact hazard this endpoint exists to prevent, not close an information leak.
**Flagging this back to the plan** as a second defect worth correcting at the source (after W2's
`canManage`-vs-`includes` one) — §2.2's conditional assumed a model this codebase does not have.

**Guard-satisfaction:** `DeviceController#register`/`#delete` call `requireManageOrg()` →
`currentUser.scope().canManageOrg()`, reaching the guard's `CurrentUser.scope()` seam transitively;
`#update`/`#setState` call `streamAccess.requireVisible(...)` directly (`owner.endsWith("Access")`);
`#list` calls `streamAccess.filterVisibleDevices(...)` directly. `GeofenceController#create`/`#update`/
`#delete` call `requireAdminister()` → `currentUser.scope().canAdminister()`; `#list` carries
`@OpenByDesign` and needs no call at all. All nine handlers should be removable from
`EndpointAuthorizationTest`'s `TEMPORARY_UNSCOPED` ledger.

**Tests:** `DeviceControllerTest` (+12) — register/delete 403 for PILOT and success for MANAGER
in-group; update/setState 200 for a PILOT on their own assigned device's device, 404 for a PILOT
assigned elsewhere, 404 for a PILOT when the device belongs to no asset at all, 200/404 for a MANAGER
in/outside the asset's group subtree; list filters out a device belonging to another group's asset.
`GeofenceControllerTest` (+8) — create/update/delete 403 for both PILOT and MANAGER scopes, 201 for
UNBOUNDED (ADMIN/auth-off); list unfiltered for a PILOT scope. Every pre-existing test in both files
runs under the unchanged class-level unbounded `CurrentUser` and is untouched. **Before/after: vision-api
785 → 805 (+20)**; `vision-app`'s full suite (245/245, including `EndpointAuthorizationTest`) re-run
against real Postgres via Testcontainers — Docker ran, not skipped.

**Deferred, not silently dropped:** `HlsProxyController#proxy` (W4, separate wave, not in this
wave's file scope) is the one live-surface `TEMPORARY_UNSCOPED` entry this wave does not touch.

### R7 (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R7, finding A2) — the three remaining unscoped controllers

Re-audited by actually reading what each handler returns, not guessing:

- **`HlsProxyController#proxy`** (the deferred entry above) — genuinely scopes: proxies one asset's
  live video bytes, so it gets exactly `StreamController`'s own gate. New `StreamAccess` constructor
  parameter (required in all three constructors — never optional, an authorization seam that could
  be silently skipped by construction would defeat the point); a private `requireVisibleStream`
  calls `StreamAccess.requireVisible(StreamId)` before the upstream is ever contacted. A `streamId`
  path segment that fails `StreamId.of(...)` is treated as "not currently running" (a no-op, not a
  400) — production `streamId`s are always canonical UUIDs (`MediamtxUrls`), so a non-UUID segment
  can only be a request this app never generated, which mediamtx has nothing to serve either way.
  Out-of-scope running stream → `404` (existence never revealed), before the upstream HTTP call.
- **`DeviceProbeController#probe`** — `@OpenByDesign`: `ProbeDeviceRequest` names a caller-supplied
  `protocol`+`uri`, not an existing device or asset; nothing is created, nothing already in anyone's
  fleet is read. No `AssetId`/`Ownership` anywhere in the request or `ProbeDeviceResponse` to scope
  against — a check here would be theater, the same shape `OnboardingController#probeCandidate`
  already carries `@OpenByDesign` for.
- **`EventController`** — genuinely scopes: a `DetectionEvent` is fleet-operational data tied to one
  asset, not reference data. New `StreamAccess`/`CurrentUser` constructor parameters. `#forStream`
  mirrors `StreamController#detections` exactly (`requireVisible(StreamId)`, no-op for a
  not-currently-running stream, so the pre-existing "unknown stream → empty list" contract is
  unchanged). `#recent` has no single stream to gate on, so it filters the fleet-wide list instead of
  403ing the whole request (mirrors `StreamController#list`'s `filterVisible` posture) — a `null`
  `assetId` (device not yet attached to any asset) is visible only to a caller who
  `scope.canAdminister()`, the same "unowned device" fallback `StreamAccess.visibleAsset` already
  applies.

**Tests:** `HlsProxyControllerTest` +2 (running stream on a foreign asset → 404, no upstream server
even started so a pass proves the upstream was never touched; running stream on the caller's own
assigned asset → 200, proxying continues) — all 12 pre-existing wire-mechanics tests pass a real
no-op `StreamAccess` (a `StreamService` stub reporting no running streams) via a new
`openStreamAccess()` helper, unchanged otherwise. `EventControllerTest` +3 (fleet-wide filter drops a
foreign-asset event and a null-assetId event for a PILOT scope, keeps the caller's own; running
stream on a foreign asset → 404; running stream on the caller's own asset → 200) via a
`mockMvcFor(CurrentUser)`/`currentUserWithScope(VisibilityScope)` pair mirroring
`StreamControllerTest`'s own idiom. `DeviceProbeControllerTest` unchanged (no constructor change).
**Before/after: vision-api 836 → 841 (+5)**; `EndpointAuthorizationTest`'s `TEMPORARY_UNSCOPED` no
longer names any of the three (see vision-app/MODULE.md's own EndpointAuthorizationTest entry).

## docs/plans/done/CV-CLEAN-FEED-PLAN.md W1 done (burn-in removed, deny-list filter added — vision-api half)

Server-side detection burn-in removed entirely, not defaulted off (D-1): `StartStreamRequest`/
`StartAssetStreamRequest` lost `overlayBurnIn`, `StreamConfigResponse` lost `overlayBurnIn`/
`overlayTelemetry`, `StartStreamResponse`/`ActiveStreamResponse` lost `burnedIn`, and
`StreamViewerLinks` lost its `burnedIn(StreamId)` method and its `StreamService` collaborator
(now a single-arg `StreamViewerLinks(StreamPublisherPort)`) — nothing left to compute it from.
`AssetStreamController`/`StreamController` dropped the matching constructor arguments. Every DTO
gained `labelDenyFilter?:List<String>` in the same field position `labelFilter` already occupies
(D-2), mirroring its present/absent-list semantics exactly; `UpdateStreamConfigRequest#toPatch()`
folds it onto `PipelineConfigPatch`'s new field the same way. `StreamViewerLinks`' external-player
copy (`viewUrl`/`whepUrl` composition) was audited and confirmed to never have promised
boxes-in-the-video in its own text, so no wording changed there beyond the removed method.

*Tests:* `./mvnw -B -pl station/vision-api test`: **753/753 green**. New coverage:
`startMergesLabelDenyFilterOverrideOntoDefaults` replaces the deleted
`startMergesOverlayBurnInOverrideOntoDefaults`, plus a `labelDenyFilter` jsonPath assertion on the
existing config-read-back test. `AssetStreamControllerTest` updated for the single-arg
`StreamViewerLinks` constructor.

*Not in this module:* the removal of `OverlayPort`/`AnnotatedFrame`/the `labelDenyFilter` drop site
itself (`vision-perception`), the deletion of `adapter-overlay`/`video-output/overlay/` (whole
module gone), and `vision-app`'s wiring/E2E-test half — see each module's own MODULE.md.

**docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md Wave P4 done** (the wire half of per-vehicle stick layouts): `ManualControlEngagedFrame` gained `vehicleKind`/`profileCode`/`profileName`; `ManualControlChannelBindingResponse` gained `function`/`travel`/`minMicros`/`centerMicros`/`maxMicros` and a `from(ControlBinding)` mapper; `FlightCapabilitiesResponse` gained `vehicleKind`; `ManualControlWebSocketHandler#labelFor` was deleted. **Every wire change is additive** — no field was removed or renamed, so §4's frozen protocol still reads exactly as specified and an older client keeps working. The one thing this wave really fixes is that the handler no longer *invents* channel semantics: labels used to be hardcoded per `rcChannel`, which is only correct for the map that no longer exists.

## docs/plans/active/CONTROLLER-SETUP-CONTEXT.md C5 done (controller setup REST surface)

`ControlProfileController` (list / catalog / create / update / activate / delete), the DTOs `ControlProfileResponse`, `ControlBindingPayload`, `ActionBindingPayload`, `CreateControlProfileRequest`, `UpdateControlProfileRequest`, `ControlCatalogResponse`, `AuxFunctionRequest`; `support/ControlEnumParsing` and `support/AuxFunctionCatalog`; two new `FlightCommandController` endpoints; the additive `/ws/manual-control` `engaged` fields.

Four things worth knowing before touching this surface:

- **The catalogue is served, not hardcoded in the SPA** (decision C8, and CLAUDE.md rule 1). `ControlCatalogResponse.of(...)` is derived from the domain enums themselves — `ControlInputKind#allows` decides which sources each kind lists, `SwitchPosition#auxFunctionLevel()` supplies each position's level, `ControlAction#dangerous()` supplies the danger flag. A client that invented its own copy of any of these would eventually offer a binding the server refuses, or — worse, for `dangerous` — skip a confirmation.
- **`AuxFunctionCatalog` is a labelled seed list, not the firmware's own table.** Thirteen common `RCx_OPTION` numbers (RTL 4, camera trigger 9, gripper 19, parachute 22, motor e-stop 31, …) exist to save an operator a trip to the docs. **Any number in `[0,400]` is accepted** whether it is listed or not; the list is a convenience, and this module deliberately keeps no claim to be complete — a stale copy of the firmware's list is worse than none. It is a `@ConfigurationProperties`-shaped record so a deployment can extend it (`vision.control.aux-functions[*]`, `station/vision-app`).
- **No `VisibilityScope` anywhere in `ControlProfileController`.** Every other controller here threads `currentUser.scope()`; this one threads only `currentUser.userId()`, because a controller layout is personal equipment configuration and the authority question is ownership. `ControlProfileService` throws `AccessDeniedException` for another operator's profile, which the existing handler maps to 403 — no new exception type and no `ApiExceptionHandler` change.
- **Layout validation is the domain's, not the DTO's.** `UpdateControlProfileRequest` parses strings to enums (`ControlEnumParsing`, the same case-insensitive-with-listed-alternatives idiom as `CapabilityParsing`) and then builds real `ChannelMap`/`ActionMap` records — so "the same stick both drives CH3 and arms the vehicle" is rejected by `ControlProfile`'s own compact constructor and surfaces as a 400 with the domain's own message. Nothing re-validates it here.

## docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R4 done (device origin, wire half)

`RegisterDeviceRequest`/`CreateAssetRequest.DeviceSpec` gained `origin?` (defaults `LIVE`),
`UpdateDeviceRequest` gained `origin?` (a true partial-edit field — absent means unchanged, not
"reset to LIVE"), `DeviceResponse` gained `origin` (always present, 8th component). New
`support.DeviceOriginParsing` (`parse`/`parseOptional`) mirrors `CapabilityParsing`'s
case-insensitive-name-lookup shape exactly — see the DTO/support-helper paragraphs above for the
full contract. `DeviceController` itself needed **zero changes** — every bit of origin handling
flows through the DTOs, the same "controller stays thin" shape `CreateAssetRequest#deviceIds`
established. No new `ApiExceptionHandler` mapping: an unrecognized `origin` string throws
`IllegalArgumentException`, already mapped to 400.

*Tests:* `./mvnw -B -pl station/vision-api test -DskipWeb`: **840/840 green**. New coverage in
`DeviceControllerTest` — an explicit `SIMULATED` origin round-trips through register; an unknown
origin string 400s; `DeviceResponse#origin` is asserted on the default-capabilities-video
registration case; a PATCH replacing `origin` round-trips. `CapabilityParsingTest`'s three existing
call sites (`RegisterDeviceRequest`×2, `CreateAssetRequest.DeviceSpec`×1) needed a trailing `null`
origin argument each, nothing else.

*Not in this module:* the kernel `DeviceOrigin` enum, `Device`/`DeviceRegistration`/`DeviceEdit`'s
domain-side `origin` field, `SimulationService#fitSimulatedDevice` (the "fit a synthetic camera onto
a real, existing asset" entry point this DTO surface exists to eventually expose — **no new
`/api/simulations/**` route was added in this wave**, deliberately out of this file-scope; see
`contexts/vision-simulation`'s own MODULE.md), the `V25__device_origin.sql` migration, and
`station/vision-web`'s `origin?` type addition on `Device`/`DeviceEdit`/`RegisterDeviceRequest`/
`CreateAssetDeviceSpec` in `core/api/models.ts` (types only — no component/store/facade wired to it
yet) — see each module's own MODULE.md.

## docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D2/R2 done (usage origin, wire half)

New `AssetSessionController` (`api.controller`, 3 collaborators: `AssetService`, `UsageTracker`,
`CurrentUser`) — the driving REST adapter for `UsageTracker#engage`/`#disengage` (see
`contexts/vision-perception`'s own MODULE.md for the domain/application-side collision rules).
`POST`/`DELETE /api/assets/{id}/session`, not `.../engage`/`.../disengage` — named to match this
codebase's established sub-resource idiom (`AssetStreamController`'s `.../stream`) rather than
encoding the verb into the path a second way. `engage` answers `200 OK` with the resulting
`AssetUsageResponse` (not `201 Created` — engaging is idempotent and may instead *promote* an
already-open usage or no-op, so "created" would be false two times out of three); `disengage`
answers `204 No Content` unconditionally, mirroring `AssetStreamController#stopStream`'s
idempotent-DELETE contract. Both re-read the asset through `CurrentUser#scope()` before mutating —
`requireInScope`, the identical pattern `AssetStreamController#requireInScope` already established
— so an out-of-scope or unknown asset 404s (`AssetService#details` throws `NoSuchElementException`,
mapped by `ApiExceptionHandler`'s pre-existing rule), never a 403 revealing existence; engaging a
deactivated/deleted asset surfaces `IllegalStateException` from `UsageTracker#engage` and maps to
409, also via the pre-existing rule. **No new `ApiExceptionHandler` mapping was needed.** Scoped by
visibility, not management authority — the same "can the caller see this asset, not does the caller
administer it" posture `AssetStreamController` uses, since engaging/disengaging is an operator act
on an aircraft the caller may fly, not an administrative one.

`AssetUsageResponse` gained an 8th field, `origin` (the `UsageOrigin` enum name — see the DTO
paragraph above); `AssetUsageResponseTest` gained two tests pinning it survives the trip to the
wire, mirroring the existing `phase` pin exactly (same rationale: a mapping that quietly dropped it
would leave the value correct everywhere except where anyone can see it, and no other test in this
module would notice). No other DTO or controller in this module needed a change — every existing
`new AssetUsage(...)` test call site (`GeoCorrectionControllerTest`, `AssetControllerTest`,
`AfterActionAssemblerTest`, `UsageTimelineControllerTest`) already used one of `AssetUsage`'s three
legacy convenience constructors (7-/8-/9-arg, all still present — see `contexts/vision-warehouse`'s
own MODULE.md), which now default `origin=STREAM` internally; none needed the new 10-arg shape.

`EndpointAuthorizationTest` (vision-app) needed no new `@OpenByDesign` exemption and no new
`TEMPORARY_UNSCOPED` entry — both handlers call `requireInScope`, which reaches
`currentUser.scope()`, satisfying the static call-graph guard the same way every other scoped
mutation here does.

*Tests:* `./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app -am test -DskipWeb`
(measured from a clean redirect-to-file run, not piped through `tail`): vision-api **859/859 green**
— 14 new (12 in the new `AssetSessionControllerTest`, 2 in `AssetUsageResponseTest`); `vision-app`
**259/259 green, unchanged count** (`EndpointAuthorizationTest` 2/2, including its
no-stale-ledger-entries assertion); `adapter-persistence` **224/224 green** (see its own MODULE.md's
R2 Status entry). Docker was reachable and used for real — `PostgresDockerIntegrationTest`'s
`AssetUsageRepositoryTests` (16/16, including the new origin round-trip) and
`UpgradePathMigrationTest` (4/4) both ran, not skipped.

*Not in this module:* the `UsageOrigin` kernel enum, `AssetUsage`'s domain-side `origin` field and
the three stream/operator collision rules, `UsageTracker#engage`/`#disengage` themselves, and the
`onTelemetryDeviceDiscovered` deletion decision (all `contexts/vision-warehouse`/
`contexts/vision-perception`); `V26__asset_usage_origin.sql`/`AssetUsageEntity#origin`/
`AssetUsageMapper` (`storage/persistence`); any UI for engage/disengage — **deliberately not built
this wave**, ships with no button by design, a future wave's job — see each module's own MODULE.md.
