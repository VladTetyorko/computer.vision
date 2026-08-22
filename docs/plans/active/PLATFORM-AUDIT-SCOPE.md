# PLATFORM AUDIT — Lane B: server-side authority

**Status:** read-only audit, complete. No product code changed. No build/tests run (every finding
confirmed by direct source reading — controllers, application services, `SecurityConfig`,
`AuthWiringConfiguration`, `ArchitectureTest` — plus existing test names that corroborate the
positive findings: `AuthDisabledSecurityTest`, `ScopedAssetReadAuthEnabledTest`,
`OrgManagementAuthEnabledTest`, `ManualControlSecurityEnabledTest`). Docker: not needed, not run.

**Method:** every `@RestController` under `station/vision-api` (41 controllers + `HlsProxyController`,
124 endpoints total) read in full; each mutating/reading call traced into its application service far
enough to see whether a `VisibilityScope`/`MapAccessPolicy.Viewer` is consulted, or nothing is.
`SecurityConfig`, `PrincipalResolver`/`CurrentUser`, `DevPrincipalResolver`,
`SecurityContextPrincipalResolver`, `ManualControlHandshakeInterceptor`/`WebSocketHandler`,
`LiveUpdateRegistry`, and `ArchitectureTest` read directly. `DeviceService`, `GeofenceService`,
`StreamService` grepped for `VisibilityScope` to confirm the application layer itself carries no
scope concept for those three (zero hits — the absence is at the service layer, not just forgotten at
the controller).

---

## 1. The category system, and one finding before the table

The audit brief posits three categories: (a) resolves+checks a `VisibilityScope`/authority predicate,
(b) Spring Security role annotation only, (c) nothing. **Category (b) does not exist anywhere in this
codebase.** `grep -rl "@PreAuthorize\|hasRole\|hasAuthority\|@Secured\|@RolesAllowed"` across all of
`station/vision-api` and `station/vision-app` returns zero matches (the one textual hit,
`AfterActionController.java:29`, is a javadoc sentence *explaining* why there's no `@PreAuthorize`).
Spring Security's enabled filter chain (`SecurityConfig.java:69-90`) only ever answers "is there a
session" (`.requestMatchers("/api/**","/ws/**").authenticated()`) — it has no role-based rule at all.
**Every authorization decision in this codebase is either (a) or (c); there is no middle tier.** This
also means nothing stops a future PR from importing `org.springframework.security` into `vision-api`
— the "vision-api carries zero dependency on Spring Security" rule is a documented convention
(`CurrentUser`/`PrincipalResolver` javadoc), not an ArchUnit-enforced one; `ArchitectureTest` has no
rule naming `org.springframework.security`.

---

## 2. Endpoint × authorization table

124 endpoints. **Legend:** **(a)** = scope/authority checked (`VisibilityScope.includes/canManage/
canAdminister/canManageOrg`, or `MapAccessPolicy.Viewer`); **(c)** = nothing checked beyond "is there a
session" (or nothing at all, auth off); **open** = deliberately unscoped reference/system/auth-flow
data with a documented low-risk rationale (categories/roster lists, host network info, login/logout,
global map-tile regions) — not a defect, called out so it isn't miscounted as (c).

### Assets, devices, streams, discovery

| Method | Path | Mutates/Reads | Cat. | Mechanism |
|---|---|---|---|---|
| POST | `/api/assets` | creates asset+devices | a | `scope().canManageOrg()` — `AssetController.java:123` |
| PATCH | `/api/assets/{id}` | edits asset | a | managed fields → `canManage`; operator fields → visibility only — `AssetController.java:150-154` |
| POST | `/api/assets/{id}/state` | activate/deactivate/delete-restore | a | `requireManageable` (`canManage`) — `:176` |
| DELETE | `/api/assets/{id}` | soft-delete asset | a | `requireManageable` — `:195` |
| GET | `/api/assets` | list assets | a | `assetService.assets(scope,...)` — `:208` |
| GET | `/api/assets/{id}` | asset detail | a | `assetService.details(scope, id)` — `:221` |
| POST | `/api/assets/{id}/devices` | attach device | a | `requireManageable` — `:237` |
| DELETE | `/api/assets/{id}/devices/{deviceId}` | detach device | a | `requireManageable` — `:255` |
| GET | `/api/usages/{usageId}/telemetry` | **raw telemetry, any usage** | **c** | no scope call at all — `AssetController.java:275-281` |
| PUT/GET/DELETE | `/api/assets/{id}/image` | asset image bytes | a | `requireInScope` (visibility only, not `canManage` — operator-tier by design) — `AssetImageController.java:107,122,143` |
| GET | `/api/assets/{id}/stats` | flight stats | a | `assetService.details(scope,id)` for its 404 side-effect — `AssetStatsController.java:67` |
| POST | `/api/assets/{id}/stream` | start asset stream | a | `requireInScope` (visibility only) — `AssetStreamController.java:114` |
| DELETE | `/api/assets/{id}/stream` | **stop asset stream, any asset** | **c** | no call at all, documented "nothing to hide" — `AssetStreamController.java:131-133` |
| POST | `/api/devices` | **register device** | **c** | no scope call anywhere — `DeviceController.java:67` |
| GET | `/api/devices` | **list every device, fleet-wide** | **c** | `deviceService.devices(includeDeleted)` — no scope arg — `:80` |
| PATCH | `/api/devices/{id}` | **edit any device** | **c** | `:96-101` |
| POST | `/api/devices/{id}/state` | **(de)activate any device** | **c** | `:118` |
| DELETE | `/api/devices/{id}` | **delete any device** | **c** | `:135` |
| POST | `/api/devices/probe` | test-connect, nothing persisted | open | no ownable resource yet |
| GET | `/api/categories` | list categories | open | global reference data |
| GET | `/api/fleet/summary` | dashboard summary | a | `fleetSummaryService.summary(scope,...)` — `FleetController.java:43` |
| POST | `/api/discovery/scan` | trigger network scan | open (minor) | no stored-resource touch; any authenticated user can trigger a host-network scan |

### Identity / admin

| Method | Path | Mutates/Reads | Cat. | Mechanism |
|---|---|---|---|---|
| POST/POST/GET | `/api/auth/login,logout,me` | session lifecycle | open (self-auth) | credential-verified by design; `AuthController.java` |
| GET | `/api/users` | list users | a | `userService.list(scope)` — `UserAdminController.java:52` |
| POST | `/api/users` | create/invite user | a | `canManageOrg`+`includeGroup`+`maxGrantableRole` inside `DefaultUserService#create` — `:64` |
| POST | `/api/users/{id}/enabled` | enable/disable user | a | management gate inside `DefaultUserService#setEnabled` — `:76` |
| GET | `/api/groups` | list groups | a | `groupService.list(scope)` — `GroupAdminController.java:49` |
| POST | `/api/groups` | create group | a | `canManageOrg`+root-only-for-unbounded inside `DefaultGroupService#create` — `:61` |
| PUT | `/api/assets/{assetId}/pilots/{userId}` | assign pilot | a | `granterScope.canManage(ownership)` inside `DefaultAssignmentService#assign` — `AssignmentController.java:71` |
| DELETE | `/api/assets/{assetId}/pilots/{userId}` | unassign pilot | a | same gate, `unassign` — `:83` |
| GET | `/api/assets/{assetId}/pilots` | roster for one asset | a | `assetService.details(scope,id)` 404-guard first — `:96` |
| GET | `/api/me/assignments` | caller's own assignments | a (self-only) | keyed off `currentUser.userId()` — `:109` |
| GET | `/api/me/activity` | caller's own audit feed | a (self-only) | keyed off `currentUser.userId()` — `ActivityController.java:50` |
| GET | `/api/audit` | **fleet-wide audit trail** | a | `scope().canManageOrg()` gate — `AuditController.java:70` (note: an admitted MANAGER sees the *whole* fleet's trail, not just their subtree — a documented, smaller residual leak) |

### Flight, geofence, geo, camera pose

| Method | Path | Mutates/Reads | Cat. | Mechanism |
|---|---|---|---|---|
| POST | `/api/assets/{id}/return-home,mode,arm,disarm` (×4) | flight commands | a | `scope.includes(asset)` inside `DefaultFlightCommandService` — `FlightCommandController.java:75,90,108,126` |
| GET | `/api/assets/{id}/flight-capabilities` | capability snapshot | a | scoped read — `:141` |
| GET | `/api/geofences` | list zones | open (minor) | global airspace reference, but see defect list |
| POST | `/api/geofences` | **create no-fly zone** | **c** | no scope call — `GeofenceController.java:66` |
| PUT | `/api/geofences/{id}` | **edit zone** | **c** | `:80` |
| DELETE | `/api/geofences/{id}` | **delete zone** | **c** | `:91` |
| GET | `/api/geo/regions`, `.../progress` | list/progress reference-imagery regions | open | global map-tile resource, not asset-scoped |
| POST | `/api/geo/regions` | start ingest | a | `scope().canAdminister()` — `GeoRegionController.java:114-117` |
| DELETE | `/api/geo/regions/{id}` | delete region | a | same gate — `:96` |
| GET | `/api/geo/corrections/live` | live corrected tracks | a | built from `assetService.assets(scope,...)` — `GeoCorrectionController.java:77` |
| GET | `/api/geo/corrections?usageId=` | usage corrections | a | `assetService.details(scope, usage.assetId())` guard — `:95` |
| GET | `/api/assets/{assetId}/camera-pose` | read pose | a | `requireVisible` — `CameraPoseController.java:92` |
| PUT/DELETE | `/api/assets/{assetId}/camera-pose` | set/delete pose | a | `requireManageable` — `:111,125` |
| POST | `.../camera-pose/calibration` | solve (never persists) | a | `requireManageable` — `:145` |
| GET | `/api/assets/{a}/usages/{u}/after-action`, `.../archive` | evidence package/zip | a | scope+viewer threaded to `AfterActionAssembler` — `AfterActionController.java:96` |

### Map (layers, marks, drawings, tracks)

All 17 endpoints across `MapDrawingsController`, `MapLayersController`, `MapMarksController`,
`MapTracksController` are **(a)** — every method resolves `CurrentUser.viewer()` and delegates
entirely to `MapAccessPolicy`/`MapLayerService`/`MarkService`/`DrawingService`/`TrackProjectionService`,
never a raw driven-port call. This is the cleanest-designed authorization surface in the codebase.

### CV / training (all behind `vision.training.enabled`, default false, except the two roster reads)

| Method | Path | Mutates/Reads | Cat. | Mechanism |
|---|---|---|---|---|
| GET | `/api/cv/models`, `/api/cv/trackers` | static rosters | open | deploy-time config, no ownership |
| POST | `/api/datasets` | create dataset | a | `canManageOrg` inside `DatasetService#create` — `DatasetController.java:78` |
| GET | `/api/datasets`, `/{id}` | list/read | a | scope-filtered / 403-not-404 by design — `:90,103` |
| DELETE | `/api/datasets/{id}` | delete | a | scoped — `:116` |
| POST | `/api/streams/{streamId}/samples`, `/api/usages/{usageId}/samples` | capture sample | a | scope threaded to `LabelingService` — `LabelingController.java:90,111` |
| GET | `/api/datasets/{id}/samples`, `/api/samples/{id}/image` | list/read samples | a | scoped — `:128,143` |
| PUT | `/api/samples/{id}/annotations` | label sample | a | scoped — `:159` |
| GET | `/api/cv/registry/models` | list registry | open (documented) | "any authenticated caller may read this" |
| POST | `/api/cv/registry/models/{id}/promote` | **promote model to prod** | a | `canManageOrg` inside `ModelRegistryService#promote` — `ModelRegistryController.java:80` |
| POST | `/api/datasets/{id}/train` | start training job | a | `canManageOrg`+dataset-scope inside `TrainingJobService#start` — `TrainingJobController.java:85` |
| GET | `/api/training/jobs/{jobId}` | **poll any job by id** | **c** | no scope call — `TrainingJobController.java:99-101` |
| GET | `/api/training/jobs` | **list every job, fleet-wide** | **c** | no scope call — `:110-113` |

### Live data, streaming, replay, system, simulation, onboarding

| Method | Path | Mutates/Reads | Cat. | Mechanism |
|---|---|---|---|---|
| GET | `/api/events` | **all detection events, fleet-wide** | **c** | no scope call — `EventController.java:60-68` |
| GET | `/api/streams/{streamId}/events` | events for one stream | **c** | no scope call — `:83-90` |
| GET | `/api/live` (SSE connect) | live push connection | **mixed — see §4** | only the `map` topic is scoped; fleet/event/devices/detection-events broadcast to every connection; `telemetry:<assetId>`/`detections:<assetId>` opt-in topics carry **no ownership check** — `LiveController.java:85-90`, `LiveUpdateRegistry.java:96-107` |
| PATCH | `/api/live/{connectionId}/topics` | add/remove topics | **c** | same gap — any connection can add `telemetry:<any-asset-id>` — `LiveController.java:103-107` |
| GET | `/api/system/network` | host NICs + MAVLink port | open (documented) | no domain meaning |
| GET | `/api/system/status` | subsystem health | open (documented) | "no managerOnly restriction — deliberate" |
| POST | `/api/simulations` | **create+register simulated asset** | **c** | no `canManageOrg` gate, unlike the equivalent `AssetController#create` — `SimulationController.java:76-84` |
| DELETE | `/api/simulations/{assetId}` | stop simulation | **c** | unscoped, mirrors asset-stream stop — `:104-106` |
| POST | `/api/onboarding/probe` | pre-registration probe | open | nothing exists yet to scope |
| GET | `/api/assets/{id}/profile` | latest vehicle profile | a | scoped — `OnboardingController.java:111` |
| POST | `/api/assets/{id}/probe` | active probe | a | `canManage` inside service — `:124-127` |
| POST | `/api/assets/{id}/remediate` | remediation actions | a | `canManage`+arming-state inside `RemediationService` — `:142-144` |
| GET | `.../usages/{u}/passport`, `.../drift` | flight passport/drift | a | scoped, 3-way 404 collapse — `:158,176` |
| GET | `/api/demo` | demo status | open | no resource |
| POST | `/api/demo/seed` | seed demo data | a (by delegation) | no gate in `DemoController` itself; every created thing goes through the same already-gated services (`AssetService`, `UserService`, ...) — fault-tolerant, so a non-manager's seed attempt partially succeeds/partially reports `problems`, not a clean 403 |
| GET | `/api/assets/{id}/readiness`, `/api/fleet/readiness` | readiness reports | a | scoped — `ReadinessController.java:59,70` |
| GET | `/api/usages` | usage list | a | scoped — `UsageTimelineController.java:92` |
| GET | `/api/usages/{usageId}/timeline` | **replay telemetry/detections** | **c** | self-documented gap: "a pre-existing gap ... out of this task's scope to close" — `UsageTimelineController.java:50-52,125` |
| GET | `/api/usages/{usageId}/recording` | **recorded clip URL** | **c** | same gap — `:144` |
| GET | `/api/usages/by-stream/{streamId}` | usage for a stream | a | scoped — `:168` |
| POST | `/api/devices/{deviceId}/stream` | **start stream, device-level** | **c** | zero scope call, bypasses `AssetStreamController`'s own gate for the same action — `StreamController.java:134-144` |
| GET | `/api/streams` | **every active stream, fleet-wide** | **c** | `streamService.streams()` — no scope arg — `:159-164` |
| DELETE | `/api/streams/{streamId}` | **stop any stream** | **c** | `:176` |
| PATCH | `/api/streams/{streamId}/config` | **reconfigure detection/tracking on any stream** | **c** | `:217` |
| GET | `/api/streams/{streamId}/config` | read effective config | **c** | `:241` |
| GET | `/api/streams/{streamId}/tracks` | track book | **c** | `:273-297` |
| GET | `/api/streams/{streamId}/detections` | recent detections | **c** | `:317-329` |
| GET | `/api/streams/{streamId}/snapshot` | **JPEG frame of any stream** | **c** | `:352` |
| GET | `/hls/{streamId}/**` | **live HLS video segments, any stream** | **c** | no scope call anywhere in `HlsProxyController` — `HlsProxyController.java:215-217` |

**Totals:** 124 endpoints — **80 (a)**, **0 (b)**, **29 (c)** (14 writes / 15 reads), **15 open-by-design**.

---

## 3. Ranked defect list (blast radius, descending)

| # | Defect | File:line | Story | Severity | Smallest correct fix |
|---|---|---|---|---|---|
| 1 | The entire live-stream surface is unscoped: list, config read/write, tracks, detections, snapshot | `StreamController.java:134-144,159-164,176,217,241,273-297,317-329,352` | A PILOT scoped to 2 assets can `GET /api/streams` and see viewer URLs for all 100 assets currently flying, pull a live JPEG snapshot of any of them, and `PATCH .../config` to kill detection or steal the FOLLOW track lock on someone else's stream | **Critical** | Resolve each `streamId`'s owning asset (the device↔asset↔stream chain already exists — `AssetStreamController` does this one hop today) and gate every method behind the same `requireInScope`/`requireManageable` pattern `AssetController`/`AssetStreamController` already use; add one shared helper so this is written once, not 8 times |
| 2 | SSE `telemetry:<assetId>`/`detections:<assetId>` topics carry no ownership check; `fleet`/`event`/`devices`/`detection-events` topics broadcast to every connection | `LiveController.java:85-90,103-107`; `LiveUpdateRegistry.java:96-107` (class javadoc states this outright: "Scoped delivery — map only") | A PILOT opens `GET /api/live?topics=telemetry:<any-other-assetId>` and receives that asset's live telemetry stream forever, with no server-side check at connect or update time | **Critical** | At `connect`/`updateTopics`, resolve each requested `telemetry:`/`detections:` asset id against `currentUser.scope().includes(assetId, ownership)` before admitting the topic — the same predicate-injection pattern `MapVisibility.deliveryPredicate` already establishes for `map` |
| 3 | `/hls/{streamId}/**` proxies live video with zero authorization | `HlsProxyController.java:215-217` | Any authenticated caller (or anyone at all, auth off) who knows/guesses a `streamId` UUID can watch that stream's live video through this app's own origin | **High** | Same owning-asset resolution as #1, checked once in `proxy()` before `stampVideoDemand`/`fetch` |
| 4 | Device CRUD has no authority check anywhere — controller or service | `DeviceController.java:67,80,96-101,118,135`; confirmed zero `VisibilityScope` references in `DeviceService` | A PILOT assigned to 2 aircraft can `DELETE /api/devices/{id}` or `POST /api/devices/{id}/state` for a camera on an asset they cannot even see | **High** | Resolve the device's owning asset (`Device` belongs to exactly one `Asset` per the domain model) and gate mutations behind `canManage(ownership)`, mirroring `AssetController.requireManageable`; scope `list()` to devices whose owning asset is in scope |
| 5 | Geofence zone CRUD has no authority check | `GeofenceController.java:66,80,91`; confirmed zero `VisibilityScope` references in `GeofenceService` | Any authenticated PILOT can delete or redraw a no-fly zone system-wide | **High** (safety-relevant) | Gate `create`/`update`/`delete` behind `scope().canManageOrg()`, same shape as `AssetController.create`; leave `list()` open |
| 6 | Stream/simulation "stop" is fully unscoped (documented, but real) | `AssetStreamController.java:131-133`; `SimulationController.java:104-106` | Any authenticated pilot can stop any other operator's active stream/simulation mid-flight | **Medium** | Already specced: `docs/plans/active/CREW-CONTROL-PLAN.md` §2.6/§4.5 turns this into a `commandScope()` 403 in wave CC-3, not yet built — implement that wave, or interim-gate on `requireInScope` today |
| 7 | `POST /api/simulations` creates+registers a brand-new asset with no `canManageOrg` gate, unlike the equivalent `AssetController#create` | `SimulationController.java:76-84` | A PILOT with `ASSIGNED_ASSETS` scope (explicitly refused asset registration by `AssetController`) can register new simulated assets through the sibling endpoint instead | **Medium** | Add the identical `if (!currentUser.scope().canManageOrg()) throw new AccessDeniedException(...)` guard `AssetController.create` already has |
| 8 | Two smaller, self-documented IDOR-style gaps: usage replay/recording by guessed id, and training-job polling/listing with no scope | `UsageTimelineController.java:50-52,125,144`; `TrainingJobController.java:99-101,110-113` | A caller who knows/guesses a `usageId` replays another operator's full flight; any authenticated user lists every training job's dataset/model/progress once `vision.training.enabled=true` | **Medium** | Usage: resolve the usage's owning asset and `assetService.details(scope, assetId)`-guard, the exact pattern `GeoCorrectionController.forUsage` already uses one file over. Training jobs: thread the starting user's scope/ownership through job storage and filter/gate reads the way `DatasetController` already does |

Two smaller items noted but not ranked (lower blast radius, already partially mitigated by design):
`AuditController`'s admitted MANAGER sees the *whole* fleet's audit trail rather than their own
subtree (`AuditController.java:37-39`, self-documented as deferred slice-2 cleanup); `DeviceController.register`/
`DiscoveryController.scan` create/touch no owned resource yet, so their absent gate is lower-stakes
than the four above.

---

## 4. SSE/WebSocket verdict

Two independent push mechanisms exist, with opposite outcomes:

- **`/ws/manual-control`** (RC control): correctly scoped. `ManualControlHandshakeInterceptor` stashes
  `CurrentUser.scope()` into the WebSocket session attributes at handshake time (same seam as REST);
  `ManualControlWebSocketHandler.java:174-190` reads it back on every `engage` frame and threads it into
  `ManualControlService#engage`, which throws `AccessDeniedException` for an out-of-scope asset. This
  is the one live-data surface in the codebase that got the authorization model right.
- **`/api/live`** (SSE): scoped only for the `map` topic (marks/drawings/layers) via
  `MapVisibility.deliveryPredicate(currentUser.viewer())`. Every other topic —
  `fleet`/`event`/`devices`/`detection-events` (broadcast to all) and the opt-in
  `telemetry:<assetId>`/`detections:<assetId>` topics (subscribable by asset id with no ownership
  check) — is unscoped. This is defect #2 above and the second-largest hole in the audit.

---

## 5. The `vision.auth.enabled=false` dev path

**What it grants:** `SecurityConfig.permitAllFilterChain` (active when the property is `false` *or
absent*) is permit-all with CSRF disabled on every route, including `/api/**` and `/ws/**`
(`SecurityConfig.java:56-64`). `AuthWiringConfiguration` wires `DevPrincipalResolver` as the
`PrincipalResolver` in this mode: every request — with no login, no cookie, no credential of any
kind — is the fixed `DevPrincipal` (`UserId(UUID(0,0))`, `GroupId(UUID(0,1))`), whose `scope()` is
`VisibilityScope.unbounded()` and whose `viewer()` is `Role.ADMIN` over that one group
(`DevPrincipalResolver.java:29-49`). In this mode there is no distinction between PILOT/MANAGER/ADMIN
at all — literally anyone who can reach the port has full ADMIN authority over the whole deployment.

**Where the default is set:** `vision.auth.enabled: false` is the compiled default in
`station/vision-app/src/main/resources/application.yaml:146` (also `matchIfMissing = true` on both
`@ConditionalOnProperty` annotations in `SecurityConfig`/`AuthWiringConfiguration`, belt-and-suspenders
against a missing property). **This is deliberate**, not an oversight: the javadoc and the property's
own comment (`application.yaml:138-141`) name it explicitly as the guardrail that keeps ~26 unrelated
`@SpringBootTest` classes plus `AuthDisabledSecurityTest` green.

**Can it ship on by accident?** Two different answers for two different artifacts:
- The **compiled default** (a bare `java -jar vision-app.jar`, or `./mvnw spring-boot:run` with no
  override) is **unsafe by default** — fully open, one fixed ADMIN principal, no login screen, no
  server-side warning of any kind (no startup log line, no refusal to bind on a non-loopback
  interface). The only mitigation that exists anywhere is a **client-side** banner (OPS-UX wave A6,
  merged) — a SPA-rendered warning, not a server-enforced control, and it does nothing for a caller
  hitting the REST API directly.
- The **shipped `docker-compose.yml`** (the one deployment artifact this repo checks in) is **safe by
  default**: `VISION_AUTH_ENABLED: "true"` is hardcoded directly in the compose file
  (`docker-compose.yml:431`), not sourced from `.env`/`.env.example` (grepped, no entry), so it cannot
  be silently overridden by a missing or stale `.env`. Standing this compose file up with no further
  changes gets real session auth with three seeded dev accounts (`admin/admin`, `manager/manager`,
  `pilot/pilot` — themselves a residual risk if left in place past first boot, per that migration's
  own header warning).

**Verdict:** unsafe-by-default at the framework/jar level, safe-by-default at the one shipped
deployment path. The residual risk is entirely in the gap between those two: any custom deploy script,
`docker-compose.override.yml`, or bare-metal run that doesn't explicitly set `VISION_AUTH_ENABLED=true`
silently reverts to the fully-open single-ADMIN mode with no runtime signal that it did so.

---

## 6. Verdict: are the three scopes real or decorative?

**Real, but only on the surfaces the U-SCOPE/OPS-UX waves actually touched — and those surfaces are a
minority of the API.** Asset CRUD, camera pose, map (layers/marks/drawings/tracks), identity/admin,
flight commands, onboarding, readiness, and the CV-training pipeline are genuinely, consistently
gated: every one of those controllers threads `CurrentUser.scope()`/`viewer()` into a service that
enforces `includes`/`canManage`/`canAdminister`/`canManageOrg`/`MapAccessPolicy`, and the "read hides,
command 403s" convention is followed exactly and uniformly. This is not decoration — a PILOT's
`ASSIGNED_ASSETS` scope really does 404 an asset-CRUD or camera-pose call outside it, and really does
403 a management attempt on an asset they can merely see.

But the audit also found that **the live-operations surface — the thing an operator actually watches
while flying — was never brought under the same model.** `StreamController` (device/stream-level, as
opposed to `AssetController`'s asset-level twin), the SSE `/api/live` connection's non-`map` topics,
and the HLS proxy together mean that once a user is authenticated at all (including the single
auth-off dev principal), they can watch, snapshot, and reconfigure *any* stream in the deployment and
subscribe to *any* asset's live telemetry/detections — completely independent of which assets they are
assigned to. `DeviceController` and `GeofenceController` show the same gap on the write side for
lower-frequency but still safety/ops-relevant resources. So: **the scope model is real engineering,
correctly and carefully built where it exists — it just doesn't exist yet on the pages a PILOT
actually spends their time on.** The defect list in §3 is a punch list for finishing the same pattern
on the remaining surface, not a redesign.

---

## 7. Crew authority: can today's model express it?

**No — and the codebase already has an authoritative, unbuilt answer for why and how.**
`docs/plans/active/CREW-CONTROL-PLAN.md` is a frozen spec (dated 2026-08-16) that answers this exact
question; nothing in it is implemented (`grep` for `ControlClaim`, `AssignmentRole`, `Invite` across
the whole repo returns zero matches outside that plan document).

Today's model has exactly one lever per asset: an assignment (`AssignmentRepositoryPort`) is a flat
pilot↔asset join with no attributes — being assigned means full command authority
(`scope.includes(asset)` gates every flight command identically for every assignee), and there is no
occupancy control: two pilots assigned to the same aircraft can both send `arm`/`RTH` concurrently with
no conflict, no "who's flying" fact, and no time-boxing. `Role` (`PILOT < MANAGER < ADMIN`) cannot
grow a fourth constant without corrupting `User#topRole()`/`maxGrantableRole` (both pick the max
ordinal) — the plan states this as a hard constraint, not a preference.

The plan's answer, unbuilt: (1) `AssignmentRole { PIC, OBSERVER }` lives on the *assignment*, not on
`Role` — a per-asset property, not a system-wide one; (2) a second scope, `commandScopeFor(User)`,
identical to today's visibility scope except a PILOT's variant only includes *PIC* assignments (an
OBSERVER sees and reads everything a PIC does, but 403s on every command) — zero new types thread
through `vision-flight`'s signatures, since "may command" becomes just another `VisibilityScope`-shaped
value; (3) `ControlClaimService` — an in-heap, TTL'd, single-holder claim per asset (deliberately *not*
persisted — a stale lock surviving a crashed browser is judged worse than no lock at all), auto-
acquired on first command, renewed every 5s, force-takeable only by `canManage`; (4) a real invite —
one-time token, ≤-own-scope, no mail server, self-service password change first. The plan is
architecturally sound (reuses `VisibilityScope`'s exact shape rather than inventing new authority
plumbing) and estimated at ~4 agent-days for the control-claim half, ~3 more for invites — this is a
build-it decision, not a design one.

**Bottom line:** a narrow, time-boxed, non-piloting authority on one flight cannot be expressed today
at all — the closest available proxy is "assign them as a second pilot," which grants full arm/disarm
authority, not observation. The gap is fully specced and estimated; it is simply not on this branch.

---

## 8. Deferred / out of scope for this audit

- Full read of `docs/plans/active/SCALE-100-PLAN.md` — not needed: `RateLimitFilter` is a throughput
  safeguard (per-principal request budget), off by default, keyed on `CurrentUser#userId()`, and its
  own javadoc (`VisionApiProperties.java:136-146`) already explains why it stays off until auth is on
  (one dev principal would give every caller one shared bucket). No authorization interaction found.
- `DeviceProbeController.probe` accepts an arbitrary `uri`/`protocol` from any authenticated caller and
  makes the server connect to it — a potential SSRF-adjacent surface, flagged here for awareness but
  out of this audit's authority/scope framing (it touches no owned resource, so it doesn't fit the
  (a)/(b)/(c) authority question this pass was scoped to).
- Application-service-level unit test coverage for each (a) finding was not independently re-verified
  by running `-pl` tests (disk was at 95%/8.6G free — borderline, and every (a) finding was already
  corroborated by an existing, named test class); the (c) findings are all confirmed by the absence of
  any `VisibilityScope`/`CurrentUser.scope()` reference on the relevant code path, which a passing test
  suite cannot contradict (there is nothing to test that isn't there).
