# vision-map

The Common Operational Picture context: access-controlled map layers, tactical marks, drawings, and
fixed-camera track projection shared across a deployment (docs/plans/done/MAP-REWORK-PLAN.md,
docs/plans/done/FIXED-CAMERA-GEO-PLAN.md). Owns *where things are drawn on the shared map and who may
see/edit them* — layer CRUD + grants, marks (point annotations with affiliation/verification/
geolocation), drawings (line/polygon/arrow/text), and camera-pose/track projection. Deliberately does
**not** own asset positions/telemetry (kernel/warehouse/perception), detections (perception), or any
flight/mission plan beyond the drawings substrate a later slice may build on.

**Depends on:** `vision-kernel` (ids, `GeoPosition`, `Ownership`, `GeoProjection`, `FixedCameraGeo`/
`FixedCameraPose`/`FixedCameraGeoSettings`/`GroundFix`/`BoundingBox`, `BearingDistance`) ·
`vision-platform` (`AccessDeniedException`; `AuditTrailPort`/`AuditEntry`/`AuditAction`/`AuditTargetType`,
used only by `DefaultCameraPoseService`) · `vision-identity` (`Role`) · `vision-perception`
(`UsageTracker`, `TrackedObject`/`Detection`)
**Used by:** `adapter-persistence` (JPA repos for the ports below), `vision-api` (`/api/map/**`),
`vision-app` (wiring, devsupport in-memory repos, `TrackProjectionRunner`)
**Build/test:** `./mvnw -B -pl contexts/vision-map test`

## Package shape
```
com.drones.vision.map.domain.model     — records/enums this context owns
com.drones.vision.map.domain.port      — driven ports (".out" suffix dropped)
com.drones.vision.map.application       — layer/drawing services at the package root
com.drones.vision.map.application.mark  — mark service (own subpackage: "mark" is not the context's
                                           name, and this context has more than one feature)
com.drones.vision.map.application.track — camera pose CRUD, calibration solver, track projection
                                           (same "own subpackage" reasoning as `.mark`)
```

## API surface

### `com.drones.vision.map.domain.model`
- `enum AccessLevel` — `VIEW | CONTRIBUTE | MANAGE`, declared least→most permissive; ordinal ordering
  is meaningful — `MapAccessPolicy` takes the max across every applicable rule and checks "at least
  X" via ordinal comparison.
- `enum Affiliation` — `FRIENDLY | HOSTILE | NEUTRAL | UNKNOWN`; orthogonal to `MarkKind`.
- `enum DrawKind` — `LINE | POLYGON | ARROW | TEXT`; drives `Drawing`'s point-count invariant.
- `record Drawing(DrawingId id, LayerId layerId, DrawKind kind, List<GeoPosition> points, String label, String colorToken, Ownership ownership, Instant createdAt)`
  — `points` count depends on `kind`: `LINE`/`ARROW` ≥2, `POLYGON` ≥3 (implicitly closed), `TEXT`
  exactly 1. `label` required non-blank only for `TEXT` (blank→`null` otherwise), ≤120 chars.
  `colorToken` optional kebab-case UI token name, ≤30 chars. `withGeometry(points)`/
  `withDetails(label,colorToken)` withers.
- `record DrawingId(UUID value)` — `static random()`, `static of(String)`.
- `record LayerGrant(SubjectType subjectType, UUID subjectId, AccessLevel level)` — nested
  `enum SubjectType { USER, GROUP }`; `subjectId` is a plain `UUID`, resolved by `MapAccessPolicy`.
- `record LayerId(UUID value)` — `static random()`, `static of(String)`.
- `enum LayerKind` — `COP | TEAM | PERSONAL`. `COP` is the single system-wide shared layer (org-wide
  view, MANAGER+ writes, exactly one per deployment — enforced by `LayerResolver` in application code,
  not a schema constraint); `TEAM` is group-owned; `PERSONAL` is owner-only by default.
- `record MapEvent(EntityType entity, Action action, LayerId layerId, Object payload)` — nested
  `enum EntityType { MARK, DRAWING, LAYER, TRACK }`, `enum Action { CREATED, UPDATED, CLEARED, DELETED }`.
  `payload`'s runtime type is validated against `entity` in the compact ctor (`MARK`→`Mark`,
  `DRAWING`→`Drawing`, `LAYER`→`MapLayer`, `TRACK`→`ProjectedTrack`). `Action.CLEARED` is valid only
  for `entity == MARK` or `entity == TRACK`.
- `record MapLayer(LayerId id, String name, LayerKind kind, Ownership ownership, List<LayerGrant> grants, Instant createdAt)`
  — `name` non-blank ≤80 chars; `grants` may be empty. `withName`/`withGrants` withers.
- `record Mark(MarkId id, LayerId layerId, GeoPosition position, MarkKind kind, Affiliation affiliation, String label, String note, Ownership ownership, Instant createdAt, MarkStatus status, MarkSource source, Verification verification)`
  — a geolocated tactical mark, layer-scoped (visibility/write resolves from `layerId`, not `ownership`
  alone). `label` non-blank, `note` nullable (blank→`null`). `createdBy()` derives `ownership.ownerId()`.
  Withers: `withPosition`, `withDetails(label,note,kind,affiliation)`, `withStatus`, `withVerification`,
  `withLayer`. A `MarkSource.DETECTION` mark is always draggable/editable, never a hidden precise fix.
- `record MarkId(UUID value)` — `static random()`, `static of(String)`.
- `enum MarkKind` — `UNIT | EQUIPMENT | HAZARD | POI | TARGET`; orthogonal to `Affiliation`.
- `enum MarkSource` — `MANUAL` (map click) `| DETECTION` (projected from a drone's pose — an estimate).
- `enum MarkStatus` — `ACTIVE | CLEARED`.
- `record Verification(VerificationState state, UserId verifiedBy, Instant verifiedAt)` — nested
  `enum VerificationState { UNVERIFIED, CONFIRMED, REJECTED }`; `verifiedBy`/`verifiedAt` required when
  `CONFIRMED`/`REJECTED`. `static unverified()` → `(UNVERIFIED, null, null)`.
- `enum CameraPoseSource` — `MANUAL | CALIBRATED`.
- `record CameraPose(AssetId assetId, GeoPosition position, double aglMeters, double yawDegrees, double pitchDegrees, double hfovDegrees, LayerId targetLayerId, CameraPoseSource source, Double rmsErrorPixels, Instant updatedAt, UserId updatedBy)`
  — the audited, asset-keyed, persisted pose of a stationary camera; one row per `AssetId` (upsert by
  that key). `aglMeters` ≥0; `yawDegrees` normalized to `[0,360)` in the compact ctor; `pitchDegrees` ∈
  `[-10,90]`; `hfovDegrees` ∈ `(10,160)`; `rmsErrorPixels` nullable (`null` for `MANUAL`), ≥0 when
  present. `targetLayerId` nullable — `null` means "publish to the deployment's COP layer" (resolved by
  `TrackProjectionService`). `toFixedCameraPose()` strips to the pure kernel value `FixedCameraGeo` projects from.
- `record TrackPoint(AssetId assetId, long trackId, String label, LayerId layerId, GeoPosition position, double errorRadiusMeters, Instant capturedAt)`
  — one stored, decimated point of a track's durable trail; append-only. Excluded from `db_audit_log`
  (high-volume telemetry-character data, same classification as `detection_results`/`telemetry_samples`).
- `record ProjectedTrack(AssetId assetId, long trackId, String label, LayerId layerId, GeoPosition position, double rangeMeters, double errorRadiusMeters, Instant updatedAt)`
  — the live, ephemeral read model for one tracked object a fixed camera has projected onto the ground;
  one object per `(assetId, trackId)`, updated in place, **never persisted itself** (only its trail is)
  — held in memory by `TrackProjectionService`. `errorRadiusMeters` is always populated, never omitted.
  This is `MapEvent`'s `TRACK` entity payload.

### `com.drones.vision.map.domain.port` (driven — implemented by adapters)
- `DrawingRepositoryPort` — `save(Drawing)` upsert by `DrawingId`; `findById(DrawingId)`; `findAll()`
  (snapshot, no access filter); `deleteById(DrawingId)` idempotent.
- `MapLayerRepositoryPort` — same shape: `save`/`findById`/`findAll`/`deleteById(LayerId)` idempotent,
  **does not cascade** to marks/drawings on the deleted layer (application layer's job).
- `MapLiveUpdatePort` — `void publishMapEvent(MapEvent)`. Delivery is scoped per-connection by which
  layers a viewer may see, unlike most other contexts' live-update ports, which broadcast unconditionally.
- `MarkRepositoryPort` — `save`/`findById`/`findAll` (no ownership/group/layer filter)/`deleteById(MarkId)` idempotent.
- `CameraPoseRepositoryPort` — `save(CameraPose)` upserts by `assetId`; `findByAssetId(AssetId)`;
  `findAll()` (what `TrackProjectionRunner` iterates each tick); `deleteByAssetId(AssetId)` idempotent.
- `TrackTrailRepositoryPort` — the durable, decimated trail behind a live `ProjectedTrack`; deliberately
  thin — *whether* to append a point is `TrackProjectionService`'s decision. `save(TrackPoint)` always
  inserts (append-only); `findByTrack(AssetId, long)` oldest→newest; `findLatest(AssetId, long)`;
  `trimToMostRecent(AssetId, long, int maxPoints)` (per-track cap, no-op if already under it);
  `deleteOlderThan(Instant)` (retention prune, run on the projection runner's cadence).

### `com.drones.vision.map.application`
- **`MapAccessPolicy`** (final, no interface) — the map's whole authorization model: resolves effective
  `AccessLevel` for a `Viewer` on a `MapLayer`. Pure, no ports, no mutable state.
  - nested `record Viewer(UserId userId, Set<GroupId> groups, Role topRole)` — `groups` defensively copied.
    `topRole` may now be `Role.VIEWER` (docs/plans/active/AUTH-ROLES-PLAN.md §3.2, wave B1 prepended it in
    `vision-identity`) — no rule here names `VIEWER` explicitly, and none needed to: every rule in
    `accessTo` either checks a *specific* higher role (`ADMIN`/`MANAGER`) or falls through to
    group/ownership-based grants that a `VIEWER` participates in identically to a `PILOT`, so a bare
    `VIEWER` with no matching grant naturally resolves to `null`/no access, same as a bare `PILOT` does
    today. `MapAccessPolicyTest` pins this with explicit `VIEWER` cases rather than leaving it to be true
    by accident of the rule table.
  - `AccessLevel accessTo(Viewer, MapLayer)` — max across every applicable rule, or `null`; `canView`/
    `canContribute`/`canManage(Viewer, MapLayer)` — thresholded at `VIEW`/`CONTRIBUTE`/`MANAGE`.
  - **Deliberately resolves from identity + group membership, never `VisibilityScope`** — see Gotchas.
- **`LayerResolver`** (final, no interface, `public`) — the one place `MapLayerRepositoryPort` is
  reached from this context, shared by `DefaultMapLayerService`/`DefaultMarkService`/`DefaultDrawingService`.
  - `LayerResolver(MapLayerRepositoryPort, MapLiveUpdatePort)`
  - `MapLayer require(LayerId)` — `NoSuchElementException` if unknown; `findAll()`/`save()`/`deleteById()`
    thin pass-throughs — callers publish their own `MapEvent` afterward.
  - `synchronized LayerId copLayerId()` — find-or-create the single `LayerKind.COP` layer
    (`"Common picture"`), idempotent; publishes `CREATED` only on first creation. Stamps a fixed system
    sentinel principal (see Gotchas) since it takes no acting `Viewer`.
  - `synchronized LayerId defaultLayerFor(Viewer)` — viewer's first `TEAM` layer (tie-broken by name
    then id), else their own `PERSONAL` layer (found or lazily created, `"Personal"`), never `COP` directly.
  - `static GroupId homeGroupOf(Viewer)` — viewer's lowest-UUID membership group, or the system sentinel.
- **`MapLayerService`** (interface) → **`DefaultMapLayerService`** — layer CRUD and grant management.
  - `DefaultMapLayerService(LayerResolver, MarkRepositoryPort, DrawingRepositoryPort, MapLiveUpdatePort, MapAccessPolicy)` — 5-arg.
  - `List<LayerView> layers(Viewer)` — every layer `policy.canView`s, COP first then by name.
  - `MapLayer create(Viewer, LayerSpec)` — `TEAM` gated on `ADMIN || (MANAGER && groups().contains(spec.groupId()))`;
    `PERSONAL` open to anyone; `LayerSpec` itself rejects `kind==COP`.
  - `MapLayer rename(Viewer, LayerId, String)` / `void delete(Viewer, LayerId)` — both `require` the
    layer (404), then unconditionally reject the COP layer with `IllegalStateException` (**not**
    `AccessDeniedException`) before gating on `policy.canManage`. `delete` cascades every mark/drawing
    on the layer first, each with its own `DELETED` event.
  - `MapLayer setGrants(Viewer, LayerId, List<LayerGrant>)` — wholesale replace, gated on `canManage`.
  - `LayerId copLayerId()` — delegates to `layerResolver.copLayerId()`.
- **`DrawingService`** (interface) → **`DefaultDrawingService`**.
  - `DefaultDrawingService(DrawingRepositoryPort, MapLiveUpdatePort, MapAccessPolicy, LayerResolver)` — 4-arg.
  - `List<Drawing> list(Viewer)` — every drawing on a layer `policy.canView`s, newest first.
  - `Drawing create(Viewer, DrawingSpec)` — resolves `spec.layerId()` or `defaultLayerFor`, gated on `canContribute`.
  - `Drawing patch(Viewer, DrawingId, DrawingPatch)` / `void delete(Viewer, DrawingId)` — gated on the
    drawing's own creator **or** `canManage` on its layer (no verification carve-out).
- **`LayerSpec(name, kind, groupId)`** — `kind==COP` rejected; `groupId` required when `kind==TEAM`.
- **`LayerView(layer, myAccess)`** — `myAccess` precomputed so callers never re-run `MapAccessPolicy`.
- **`DrawingSpec(layerId, kind, points, label, colorToken)`** — `layerId` nullable (default-layer resolution).
- **`DrawingPatch(points, label, colorToken)`** — every component `Optional<T>`; `DrawingPatch.NOTHING`.

### `com.drones.vision.map.application.mark`
- **`MarkService`** (interface) → **`DefaultMarkService`** — geolocated tactical marks: created (map
  click or cockpit "geolocate"), annotated, verified, promoted, cleared/deleted. Every method takes a `Viewer`.
  - `DefaultMarkService(MarkRepositoryPort, UsageTracker, MapLiveUpdatePort, MapAccessPolicy, LayerResolver)` — 5-arg.
  - `List<Mark> list(Viewer)` — every `ACTIVE` mark on a layer `policy.canView`s, newest first.
  - `Mark create(Viewer, MarkSpec)` / `GeolocationResult geolocate(Viewer, GeolocateSpec)` — both resolve
    the target layer (`spec.layerId()` or `defaultLayerFor`), require `canContribute` before `geolocate`
    even reads telemetry. `geolocate` reads `UsageTracker#latestTelemetry(AssetId)`, requires latitude,
    longitude, heading present plus altitude present and `>0` (else `IllegalArgumentException("cannot
    geolocate: telemetry incomplete")`), then projects via `GeoProjection.aimFrom(telemetry,
    GeoProjection.DEFAULT_DEPRESSION_DEGREES)` + `GeoProjection.project(GeoPosition, CameraAim)`.
    `spec.depressionDegrees()`, when non-null, overrides the resolved depression after `aimFrom` runs
    (wins even over a real gimbal reading) and forces the returned `CameraAim.measured()` to `false`.
  - `Mark patch(Viewer, MarkId, MarkPatch)` — gated on the mark's own creator **only while**
    `Verification` is still `UNVERIFIED`, or `canManage` unconditionally; publishes `CLEARED` when the
    saved status is `CLEARED`, else `UPDATED`.
  - `Mark verify(Viewer, MarkId, VerificationState)` — gated on `canManage`; rejects `decision==UNVERIFIED`.
  - `Mark promote(Viewer, MarkId, LayerId targetOrNull)` — gated on `canManage` the **source** layer
    *and* `canContribute` the **target** (defaulting to `layerResolver.copLayerId()`); stamps
    `Verification(CONFIRMED,...)` only if not already `CONFIRMED`.
  - `void delete(Viewer, MarkId)` — same gate as `patch`.
  - **Visibility precedes every mutation gate**: a mark on a layer the actor cannot `canView` throws
    `NoSuchElementException` (404) from `requireVisible`, never revealing an invisible mark's existence
    via a 403. Only failures on a *visible* layer are `AccessDeniedException`. Same rule in
    `DefaultDrawingService` (`requireVisible`) and `DefaultMapLayerService` (`canView` checked first).
- **`MarkSpec(layerId, kind, affiliation, label, note, position)`** — `layerId` nullable.
- **`GeolocateSpec(assetId, layerId, kind, affiliation, label, note, depressionDegrees)`** —
  `depressionDegrees` is `Double`, genuinely nullable: `null` = let the resolved pose decide (a real
  gimbal reading if present, else the default), non-null = operator override that always wins.
- **`GeolocationResult(mark, measured)`** — `measured` = `CameraAim.measured()`; a separate wrapper, not
  a `Mark` field (see Gotchas).
- **`MarkPatch(kind, affiliation, label, note, position, status)`** — every component `Optional<T>`;
  `MarkPatch.NOTHING`.

### `com.drones.vision.map.application.track`
- **`CameraPoseService`** (interface) → **`DefaultCameraPoseService`** — CRUD over one asset's
  `CameraPose`, audited through `AuditTrailPort` (this context's only audit write).
  - `DefaultCameraPoseService(CameraPoseRepositoryPort, AuditTrailPort)` — 2-arg.
  - `List<CameraPose> list()` / `Optional<CameraPose> find(AssetId)`.
  - `CameraPose put(AssetId, CameraPoseInput, UserId actor)` — creates or replaces; always audits
    (`CREATED` if the asset had none before, else `UPDATED`), `AuditTargetType.ASSET`.
  - `void delete(AssetId, UserId actor)` — idempotent; audits `DELETED` only when a pose actually existed.
  - **Authorization is deliberately not this service's job** (see Gotchas) — takes a plain `UserId actor`.
- **`CameraCalibrationSolver`** (static-only, no interface) — solves yaw/pitch/hfov from 2–8 clicked
  landmark correspondences.
  - `public static CalibrationResult solve(CalibrationRequest, double maxRmsErrorPixels)`.
  - Per landmark, `GeoProjection#bearingDistance` gives a measured bearing/range; range + operator-
    measured AGL gives a measured depression. A 1-D golden-section search over `hfov` ∈ [20°,120°], 100
    fixed iterations, minimizes summed squared residual with `yaw`/`pitch` re-fit at every candidate.
  - **Refuses before the search**: any landmark <`MIN_LANDMARK_DISTANCE_METERS`=3m from the camera; any
    two landmarks' bearings within `MIN_BEARING_SPREAD_DEGREES`=10° of each other (radially collinear).
  - **Refuses after the search, at every `N` including 2**: residual exceeds the caller-supplied
    `maxRmsErrorPixels`. `N==2` additionally reports `CalibrationQuality.UNDETERMINED` when it passes —
    two landmarks leave one redundant degree of freedom, too thin to cross-check.
  - The bearing-spread/landmark-distance thresholds are named constants, not `application.yaml` settings.
- **`TrackProjectionService`** (interface) → **`DefaultTrackProjectionService`** — folds a fixed
  camera's tracked objects into ground fixes, holds the live picture, decimates the durable trail, publishes `MapEvent.TRACK` events.
  - `DefaultTrackProjectionService(TrackTrailRepositoryPort, MapLiveUpdatePort, MapAccessPolicy, LayerResolver, TrackProjectionSettings)`
    — 5-arg; holds one `ConcurrentHashMap<TrackKey, ProjectedTrack>` (private `record TrackKey(AssetId, long trackId)`) as the live picture.
  - `void project(TrackProjectionInput)` — per tracked object, `FixedCameraGeo.project`s the pose; on a
    fix, decimate-appends a `TrackPoint` (only once moved `trailMinDistanceMeters` from the last stored
    point) and upserts+publishes the live `ProjectedTrack`; **on a refusal, publishes nothing and leaves
    prior live state untouched** — a refusal must never look like the object left the track book. Any
    *previously* live track for the same asset not present in this tick's list is cleared (`CLEARED`
    published, removed from the live picture).
  - `void clearAsset(AssetId)` — clears every live track for one asset; no-op if none.
  - `void pruneTrail(Instant cutoff)` — delegates to `TrackTrailRepositoryPort#deleteOlderThan`.
  - `List<ProjectedTrackView> list(Viewer)` — every live track on a layer `policy.canView`s, paired with its stored trail.
- **`CameraPoseInput(position, aglMeters, yawDegrees, pitchDegrees, hfovDegrees, targetLayerId, source, rmsErrorPixels)`**
  — the `PUT /api/assets/{assetId}/camera-pose` body; numeric ranges validated by `CameraPose`'s own
  compact ctor, not duplicated here.
- **`CalibrationLandmark(u, v, mapPosition)`** — `u`/`v` normalized pixel coords ∈`[0,1]`, top-left origin.
- **`CalibrationRequest(cameraPosition, aglMeters, imageWidthPixels, imageHeightPixels, landmarks)`** —
  `landmarks` between `MIN_LANDMARKS=2` and `MAX_LANDMARKS=8` inclusive.
- **`CalibrationQuality`** — `GOOD | UNDETERMINED`.
- **`CalibrationResult(solved, pose, rmsErrorPixels, quality, reason)`** — exactly one of two shapes,
  enforced in the compact ctor: `solved` ⇒ `pose`/`quality` non-null, `reason` null; `!solved` ⇒ reverse.
  Package-private static factories `solved(...)`/`refused(reason)`/`refused(reason, rmsErrorPixels)`.
- **`TrackProjectionInput(pose, imageWidthPixels, imageHeightPixels, tracks, observedAt)`** —
  `pose.assetId()` identifies which asset the tick is for; `tracks` is perception's own current
  `List<TrackedObject>`; `observedAt` is caller-supplied (deterministic under test).
- **`TrackProjectionSettings(geoSettings, trailMinDistanceMeters, trailMaxPointsPerTrack)`** — every
  caller-supplied number `TrackProjectionService` needs, sourced from `vision.geo.fixed-camera.*`;
  `geoSettings` is kernel's `FixedCameraGeoSettings`.
- **`ProjectedTrackView(track, trail)`** — `trail` is every stored `TrackPoint` for that track, oldest first.

## Conventions
- Every domain record validates in its compact constructor with manual `if (…) throw new IllegalArgumentException(…)`.
- Every `List`/`Set`/`Map` component is reassigned via `List.copyOf`/`Set.copyOf`/`Map.copyOf` in the compact ctor.
- `with*` copy methods return new record instances — records have no setters.
- Typed ids (`MarkId`, `LayerId`, `DrawingId`) wrap `UUID` with `random()`/`of(String)` (rethrows
  `IllegalArgumentException` on a malformed string), same pattern as every other context's ids.
- Application services take a `MapAccessPolicy.Viewer` as their acting-user parameter, not separate
  `UserId`/`Ownership`/`VisibilityScope` arguments — the one deliberate departure from the
  `UserId actor` + `VisibilityScope scope` idiom every other context's services use, because map
  authorization is identity/group-membership-based, not `VisibilityScope`-based (see `MapAccessPolicy`).
- `Optional<T>`-typed patch records (`MarkPatch`, `DrawingPatch`) rather than bare-nullable ones
  whenever a field can itself legitimately be absent — a `null` passed for any component normalizes to
  `Optional.empty()` in the compact ctor.

## Gotchas
- **`MapAccessPolicy` deliberately does not use `VisibilityScope`** (`vision-platform`) even though
  every other context's authorization runs through it: `VisibilityScope#includesGroup` is hard-`false`
  for an `ASSIGNED_ASSETS` (PILOT) scope, which carries no group information at all. Gating map
  visibility on it would make every `TEAM` layer structurally unreachable for a PILOT, including their
  own team's. `MapAccessPolicy.Viewer.groups()` is plain group membership, built by the API edge
  (`CurrentUser#viewer()`), not derived from any scope type. Do not "simplify" this context onto
  `VisibilityScope` without re-reading this — doing so silently breaks PILOT access to their own team's map.
- **`copLayerId()`/personal-layer auto-create stamp a fixed system principal, not the real bootstrap
  actor** — `LayerResolver` has no acting `Viewer` at the zero-arg `copLayerId()` call site, so it uses
  a well-known sentinel `UUID(0,0)`/`UUID(0,1)` pair redefined locally (this module may not depend on
  `vision-app`, where the equivalent `DevPrincipal` sentinel lives) — the two coincide under the
  dev/no-auth profile by construction and are simply inert elsewhere.
- **`AuditTrailPort` is used by exactly one service, `DefaultCameraPoseService`** —
  `DefaultMapLayerService`/`DefaultDrawingService`/`DefaultMarkService` audit nothing. If that changes
  for one of them, it's a new constructor parameter; all three are already at or near the 5-arg ceiling
  (`.claude/skills/java-clean-code/SKILL.md` §3) — bundling would likely be needed.
- **`CameraPoseService` takes a plain `UserId actor`, not a `MapAccessPolicy.Viewer`** — the one service
  in this context that does not follow the `Viewer`-parameter convention: a camera pose is asset-scoped,
  gated at the `vision-api` edge like `AssetController` (same `VisibilityScope#canManage` split), not
  here. Do not "fix" this into a `Viewer` parameter without re-reading why.
- **`GeolocationResult.measured` does not survive a page reload** — computed fresh by
  `DefaultMarkService#geolocate` and returned only on that call's direct response; not a `Mark` field, so
  a later `list()`/`patch()` of the same mark carries no measured-vs-assumed signal at all
  (`MarkResponse.from(Mark)`, used by every endpoint but geolocate, always sends `measured` absent).
  Persisting it durably would require widening the `Mark` domain record and `adapter-persistence`'s
  `MarkEntity`/`MarkMapper`.
- **`MapLiveUpdatePort#publishMapEvent` scopes delivery per-connection by layer visibility** — every
  other context's live-update port (`FleetLiveUpdatePort`, `TelemetryLiveUpdatePort`,
  `DetectionLiveUpdatePort`, `EventLiveUpdatePort` in `vision-platform`) broadcasts unconditionally to
  every connected client. A driving adapter implementing this port must actually check
  `MapAccessPolicy.canView` per connection before forwarding, not just fan out. `vision-api`'s `"marks"`
  SSE topic and `list()` are deliberately kept consistent with each other on this point.

## Status
Fully implemented, including fixed-camera calibration and track projection. `contexts/vision-map` is
its own Maven module (moved out of the earlier flat `vision-domain`/`vision-application` split — see
docs/plans/active/DOMAIN-SEPARATION-W1.md §16). `vision-web` does not yet surface
`GeolocationResult.measured` after a page reload (see Gotchas) — a known follow-up, not scheduled.

**AUTH-ROLES-PLAN wave B1 done** (docs/plans/active/AUTH-ROLES-PLAN.md §3.2) — no production code in
this module changed: `Role.VIEWER`'s prepend in `vision-identity` was traced through `MapAccessPolicy`'s
entire rule table (own API-surface entry above) and found to already behave correctly for a `VIEWER`
`Viewer` with zero code changes, so this wave only added pinning coverage — four new `VIEWER` cases in
`MapAccessPolicyTest` (`accessTo`/`canView`/`canContribute`/`canManage` all resolving the same way a
bare `PILOT` does, i.e. no access without an explicit grant). `MapAccessPolicy.java` itself is
byte-for-byte unchanged.

`./mvnw -B -pl core/vision-platform,contexts/vision-identity,contexts/vision-map -am test` — green
(cross-module run, since this module depends on `vision-identity`'s `Role`, which wave B1 changed).
