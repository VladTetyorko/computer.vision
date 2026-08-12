# vision-map

The Common Operational Picture context: access-controlled layers, tactical marks and drawings shared
across a deployment (docs/plans/done/MAP-REWORK-PLAN.md, superseding docs/plans/done/TACTICAL-MARKS-PLAN.md).
Owns *where things are drawn on the shared map and who may see/edit them* — layer CRUD + grants,
marks (point annotations with affiliation/verification/geolocation), and drawings (line/polygon/arrow/
text annotations). Deliberately does **not** own asset positions/telemetry (kernel/warehouse/perception),
detections (perception), or any notion of a flight/mission plan beyond the "drawings" substrate a later
slice may build on.

Extracted from the flat `vision-domain`/`vision-application` modules in **W1.7b**
(docs/plans/active/DOMAIN-SEPARATION-W1.md §16) — domain and application layers now live together
under one Maven module, package-rooted by context rather than by layer, so the extraction was a
directory move (`com/drones/vision/map/**`) rather than a repackage. Both layers are still
ArchUnit-enforced one-way (`domain` never imports `application`).

**Depends on:**
- `vision-kernel` — every typed id, `GeoPosition`, `Ownership`, `GeoProjection` (mark geolocation math)
- `vision-platform` — `AccessDeniedException` (every authorization refusal in this context throws it);
  `AuditTrailPort`/`Audit*` are declared but **not currently used** by this context (no service here
  writes an audit line — see Gotchas)
- `vision-identity` — `Role` (`MapAccessPolicy.Viewer` carries a `Role` to compute manager-tier access)
- `vision-perception` — `perception.application.pipeline.UsageTracker` (`DefaultMarkService#geolocate`
  reads an asset's freshest telemetry to project a `DETECTION` mark)

**Used by:** `adapter-persistence` (JPA repositories for the four ports below), `vision-api`
(`/api/map/**` REST surface), `vision-app` (wiring, devsupport in-memory repositories)
**Build/test:** `./mvnw -B -pl contexts/vision-map test` — **223/223 green** as of the W1.7b extraction
(unchanged test count from the pre-extraction `vision-domain`+`vision-application` combined map suite;
a pure move, no behavior change)

## Package shape

```
com.drones.vision.map.domain.model     — records/enums this context owns
com.drones.vision.map.domain.port      — driven ports (".out" suffix dropped)
com.drones.vision.map.application       — layer/drawing services at the package root
com.drones.vision.map.application.mark  — mark service (kept as its own subpackage; not folded into
                                           the root because "mark" is not the context's name and this
                                           context has more than one feature, unlike e.g. `flight`)
```

## API surface

### `com.drones.vision.map.domain.model`
- `enum AccessLevel` — `VIEW | CONTRIBUTE | MANAGE`, declared least→most permissive; ordinal ordering
  is meaningful — `MapAccessPolicy` takes the max across every applicable rule and answers "at least
  CONTRIBUTE" checks via ordinal comparison. Reordering these constants would silently change what
  "more access" means.
- `enum Affiliation` — `FRIENDLY | HOSTILE | NEUTRAL | UNKNOWN`, APP-6-inspired friend/enemy symbology
  for a `Mark`; orthogonal to `MarkKind` ("whose it is" vs "what it is"). Own assets always render
  `FRIENDLY` (a web-layer rule, not modeled here).
- `enum DrawKind` — `LINE | POLYGON | ARROW | TEXT`; drives `Drawing`'s point-count invariant.
- `record Drawing(DrawingId id, LayerId layerId, DrawKind kind, List<GeoPosition> points, String label, String colorToken, Ownership ownership, Instant createdAt)`
  — a line/polygon/arrow/text annotation on a `MapLayer`. `points`' required count depends on `kind`:
  `LINE`/`ARROW` ≥2, `POLYGON` ≥3 (implicitly closed like `GeofenceZone#polygon`), `TEXT` exactly 1.
  `label` required non-blank only for `TEXT`, optional otherwise (blank→`null`), ≤120 chars.
  `colorToken` optional lower-case-kebab UI design-token name (not a hex value), ≤30 chars, same
  kebab-case pattern `CategoryId` uses. `withGeometry(List<GeoPosition>)` replaces points only;
  `withDetails(String,String)` replaces label/colorToken only — geometry/identity/layer/ownership
  untouched either way.
- `record DrawingId(UUID value)` — `static random()`, `static of(String)`.
- `record LayerGrant(SubjectType subjectType, UUID subjectId, AccessLevel level)` — one explicit
  "give this user or group this access level" grant on a `MapLayer`; nested `enum SubjectType { USER, GROUP }`.
  `subjectId` is a plain `UUID` (not a typed `UserId`/`GroupId` union) — resolving which typed id it is
  is `MapAccessPolicy`'s job.
- `record LayerId(UUID value)` — `static random()`, `static of(String)`.
- `enum LayerKind` — `COP | TEAM | PERSONAL`. `COP` is the single system-wide shared-picture layer
  (org-wide view, MANAGER+ writes, exactly one per deployment, the default mark-promotion target);
  `TEAM` is group-owned; `PERSONAL` is owner-only by default.
- `record MapEvent(EntityType entity, Action action, LayerId layerId, Object payload)` — the payload
  `MapLiveUpdatePort#publishMapEvent` carries; nested `enum EntityType { MARK, DRAWING, LAYER }`,
  `enum Action { CREATED, UPDATED, CLEARED, DELETED }`. **`payload`'s runtime type is validated against
  `entity`** (`MARK`→`Mark`, `DRAWING`→`Drawing`, `LAYER`→`MapLayer`) — a real compact-ctor invariant,
  catching a wiring bug at construction rather than a `ClassCastException` deep in DTO mapping.
  `Action.CLEARED` is only valid for `entity == MARK`.
- `record MapLayer(LayerId id, String name, LayerKind kind, Ownership ownership, List<LayerGrant> grants, Instant createdAt)`
  — `name` non-blank ≤80 chars; `grants` may be empty; exactly one `COP` layer per deployment is an
  **application-layer** invariant (the bootstrap service enforces it), not checkable on one record
  alone. `withName`/`withGrants` each replace one field, `id`/`kind`/`ownership`/`createdAt` kept.
- `record Mark(MarkId id, LayerId layerId, GeoPosition position, MarkKind kind, Affiliation affiliation, String label, String note, Ownership ownership, Instant createdAt, MarkStatus status, MarkSource source, Verification verification)`
  — a geolocated tactical mark: structurally a point version of `GeofenceZone` (one `GeoPosition`, no
  breach semantics) but owned and layer-scoped (`layerId` — visibility/write resolves from that, not
  `ownership` alone). `label` non-blank, `note` nullable (blank→`null`). `createdBy()` derives
  `ownership.ownerId()`. Withers, each replacing only the fields named: `withPosition` (drag-correct),
  `withDetails(label,note,kind,affiliation)`, `withStatus` (ACTIVE⇄CLEARED), `withVerification`,
  `withLayer` (promotion/re-file). A `MarkSource.DETECTION` mark is an honest estimate — always
  draggable/editable, never a hidden precise fix.
- `record MarkId(UUID value)` — `static random()`, `static of(String)`.
- `enum MarkKind` — `UNIT | EQUIPMENT | HAZARD | POI | TARGET`; icon/colour category, orthogonal to
  `Affiliation`. Old-kind→(new kind, default affiliation) seed mapping (pre-MAP-REWORK data):
  `TARGET`→(`TARGET`,`HOSTILE`), `HAZARD`→(`HAZARD`,`UNKNOWN`), `POI`→(`POI`,`NEUTRAL`),
  `FRIENDLY`→(`UNIT`,`FRIENDLY`) — `FRIENDLY` no longer exists as a kind.
- `enum MarkSource` — `MANUAL` (map click) `| DETECTION` (via `GeoProjection.project` from a drone's
  pose — an estimate, not a fix).
- `enum MarkStatus` — `ACTIVE | CLEARED`.
- `record Verification(VerificationState state, UserId verifiedBy, Instant verifiedAt)` — DELTA-style
  verify→confirm→share-wider review state; nested `enum VerificationState { UNVERIFIED, CONFIRMED, REJECTED }`.
  `verifiedBy`/`verifiedAt` required when `CONFIRMED`/`REJECTED`. `static unverified()` returns
  `(UNVERIFIED, null, null)`.

### `com.drones.vision.map.domain.port` (driven — implemented by adapters)
- `DrawingRepositoryPort` — `Drawing save(Drawing)` upsert by `DrawingId`; `Optional<Drawing> findById(DrawingId)`;
  `List<Drawing> findAll()` — snapshot, no access filter baked in; `void deleteById(DrawingId)` idempotent.
- `MapLayerRepositoryPort` — same minimal shape: `save`/`findById`/`findAll`/`deleteById(LayerId)`
  idempotent, **does not cascade** to marks/drawings on the deleted layer (cascading is the
  application layer's job).
- `MapLiveUpdatePort` — `void publishMapEvent(MapEvent)`: a mark, drawing, or layer
  created/updated/cleared/deleted, one method covering all three entity kinds. Delivery is scoped
  per-connection by which layers a viewer may see, unlike most other contexts' live-update ports,
  which broadcast unconditionally. This context's own slice of the former god-port
  `LiveUpdatePublisherPort` (deleted in W1.6b, split per context — docs/plans/active/DOMAIN-SEPARATION-W1.md §15).
- `MarkRepositoryPort` — mirrors `MapLayerRepositoryPort`'s shape: `save`/`findById`/`findAll`
  (no ownership/group/layer filter)/`deleteById(MarkId)` idempotent.

### `com.drones.vision.map.application`
- **`MapAccessPolicy`** (final, no interface) — the map's whole authorization model: resolves
  effective `AccessLevel` for a `Viewer` on a `MapLayer`. Pure, no ports, no mutable state.
  - nested `record Viewer(UserId userId, Set<GroupId> groups, Role topRole)` — identity + group
    memberships + highest role (`Role` from `vision-identity`); `groups` defensively copied.
  - `AccessLevel accessTo(Viewer, MapLayer)` — max across every applicable rule, or `null` for no
    access; `canView`/`canContribute`/`canManage(Viewer, MapLayer)` — thresholded at
    `VIEW`/`CONTRIBUTE`/`MANAGE` (ordinal comparison).
  - **Deliberately resolves from identity + group membership, never from `VisibilityScope`**
    (`vision-platform`) — `VisibilityScope#includesGroup` is hard-`false` for an `ASSIGNED_ASSETS`
    (PILOT) scope, which carries no group information at all. Gating map visibility on it would make
    every `TEAM` layer structurally unreachable for a PILOT, including their own team's — the exact
    trap `DefaultMarkService`'s original design fell into (see Status). `Viewer.groups()` is plain
    group membership, built by the API edge (`CurrentUser#viewer()`), not derived from any scope type.
- **`LayerResolver`** (final, no interface, `public`) — the one place `MapLayerRepositoryPort` is
  reached from this context, shared by `DefaultMapLayerService` and `DefaultMarkService`/
  `DefaultDrawingService` (cross-package, hence `public`) so the COP/personal-layer bootstrap
  invariants can never drift between the three.
  - `LayerResolver(MapLayerRepositoryPort, MapLiveUpdatePort)`
  - `MapLayer require(LayerId)` — `NoSuchElementException` if unknown; `findAll()`/`save()`/
    `deleteById()` thin pass-throughs — callers publish their own `MapEvent` afterward.
  - `synchronized LayerId copLayerId()` — find-or-create the single deployment-wide `LayerKind.COP`
    layer (name `"Common picture"`), idempotent; publishes `CREATED` only on first creation. Stamps a
    fixed system principal (`UserId`/`GroupId` wrapping `UUID(0,0)`/`UUID(0,1)`, redefined here since
    this module may not depend on `vision-app`) since `copLayerId()` is zero-arg with no acting `Viewer`.
  - `synchronized LayerId defaultLayerFor(Viewer)` — create-time default-layer rule: the viewer's
    first `TEAM` layer (tie-broken by layer name then id), else their own `PERSONAL` layer (found or
    lazily created, name `"Personal"` — not a per-viewer display name, since `Viewer` carries none),
    never `COP` directly; publishes `CREATED` only when it auto-creates a personal layer.
  - `static GroupId homeGroupOf(Viewer)` — picks a `GroupId` to satisfy `Ownership`'s non-null
    contract when this context builds one on a viewer's behalf: the viewer's lowest-UUID membership
    group, or the same system sentinel `copLayerId()` uses when they have none. Inert for
    authorization — `MapAccessPolicy`'s creator/ownerId rule already grants the owner `MANAGE`
    regardless of which group ends up recorded here.
- **`MapLayerService`** (interface) → **`DefaultMapLayerService`** — layer CRUD and grant management.
  - `DefaultMapLayerService(LayerResolver, MarkRepositoryPort, DrawingRepositoryPort, MapLiveUpdatePort, MapAccessPolicy)`
    — 5-arg, at the constructor-parameter ceiling; reaches layer storage exclusively through
    `LayerResolver` (never a second, direct `MapLayerRepositoryPort` field).
  - `List<LayerView> layers(Viewer)` — every layer `policy.canView`s, COP first then by name.
  - `MapLayer create(Viewer, LayerSpec)` — `TEAM` gated on
    `v.topRole()==ADMIN || (MANAGER && v.groups().contains(spec.groupId()))`; `PERSONAL` open to
    anyone (`groupId` falls back to `LayerResolver#homeGroupOf`); `LayerSpec` itself rejects `kind==COP`.
  - `MapLayer rename(Viewer, LayerId, String)` / `void delete(Viewer, LayerId)` — both `require` the
    layer (404), then unconditionally reject the COP layer with `IllegalStateException` (**not**
    `AccessDeniedException` — even an ADMIN cannot rename/delete it, a structural rule) before gating
    on `policy.canManage`. `delete` cascades: every mark/drawing on the layer is removed with its own
    `DELETED` `MapEvent`, then the layer itself, in that order.
  - `MapLayer setGrants(Viewer, LayerId, List<LayerGrant>)` — wholesale replace via
    `MapLayer#withGrants`, gated on `policy.canManage` (no COP restriction — a manager may grant on it).
  - `LayerId copLayerId()` — one-line delegation to `layerResolver.copLayerId()`.
- **`DrawingService`** (interface) → **`DefaultDrawingService`** — lines/polygons/arrows/text
  annotations on a layer, the substrate for a later "plans" slice.
  - `DefaultDrawingService(DrawingRepositoryPort, MapLiveUpdatePort, MapAccessPolicy, LayerResolver)` — 4-arg.
  - `List<Drawing> list(Viewer)` — every drawing on a layer `policy.canView`s, newest first.
  - `Drawing create(Viewer, DrawingSpec)` — resolves `spec.layerId()` or `LayerResolver#defaultLayerFor`,
    gated on `policy.canContribute`.
  - `Drawing patch(Viewer, DrawingId, DrawingPatch)` / `void delete(Viewer, DrawingId)` — gated on the
    drawing's own creator (unconditionally — no verification carve-out, `Drawing` has no review state)
    **or** `policy.canManage` on its layer.
- **`LayerSpec(name, kind, groupId)`** — `MapLayerService#create`'s command; duplicates `MapLayer`'s
  name-blank/length invariants plus two shape rules of its own: `kind==COP` rejected outright,
  `groupId` required non-null when `kind==TEAM`; `groupId` optional for `PERSONAL`.
- **`LayerView(layer, myAccess)`** — one row of `MapLayerService#layers`; `myAccess` is the viewer's
  own highest `AccessLevel`, precomputed so a caller never has to re-run `MapAccessPolicy` itself.
- **`DrawingSpec(layerId, kind, points, label, colorToken)`** — `DrawingService#create`'s command;
  `layerId` nullable (default-layer resolution, same as `MarkSpec`); duplicates `Drawing`'s
  per-`DrawKind` point-count invariant and TEXT's non-blank-label rule, but leaves `colorToken`'s
  kebab-case/length shape to `Drawing`'s own compact ctor.
- **`DrawingPatch(points, label, colorToken)`** — `DrawingService#patch`'s partial-patch command;
  every component `Optional<T>` (a present-but-blank label is a meaningful clear, not "absent");
  `DrawingPatch.NOTHING` the identity patch.

### `com.drones.vision.map.application.mark`
- **`MarkService`** (interface) → **`DefaultMarkService`** — the shared operational picture: geolocated
  tactical `Mark`s, created two ways (a map click, or a cockpit "geolocate"), annotated, verified,
  promoted and cleared/deleted. Every method takes a `MapAccessPolicy.Viewer`, exactly like
  `MapLayerService`/`DrawingService` — `Ownership`/`UserId actor`/`VisibilityScope` are not separate
  parameters.
  - `DefaultMarkService(MarkRepositoryPort, UsageTracker, MapLiveUpdatePort, MapAccessPolicy, LayerResolver)`
    — 5-arg, at the ceiling. `UsageTracker` is `perception.application.pipeline.UsageTracker`.
  - `List<Mark> list(Viewer)` — every `MarkStatus#ACTIVE` mark on a layer `policy.canView`s, newest
    first (a `CLEARED` mark drops off). Layer-scoped, resolved from identity/group-membership via
    `MapAccessPolicy` — see that class's own entry for why this is the fix for the pilot-visibility
    trap, not a workaround for it.
  - `Mark create(Viewer, MarkSpec)` / `Mark geolocate(Viewer, GeolocateSpec)` — both resolve the
    target layer (`spec.layerId()` if given, else `LayerResolver#defaultLayerFor(v)`), require
    `policy.canContribute` on it (checked **before** `geolocate` even reads telemetry), build the
    `Mark` with `Verification.unverified()` and `ownership = new Ownership(v.userId(), LayerResolver.homeGroupOf(v))`;
    publish `CREATED`. `geolocate` reads `UsageTracker#latestTelemetry(AssetId)` and requires
    latitude, longitude, heading present plus altitude present and `>0` — any of the four missing/
    invalid is the same `IllegalArgumentException("cannot geolocate: telemetry incomplete")`.
  - `Mark patch(Viewer, MarkId, MarkPatch)` — `require(id)` (404), then `requireEditable`: the mark's
    own creator **only while its `Verification` is still `UNVERIFIED`**, or `policy.canManage` on its
    layer, unconditionally. Once a manager confirms/rejects a mark, its creator's standing edit right
    lapses. Folds present fields via `Mark#withDetails`/`withPosition`/`withStatus`; publishes
    `CLEARED` when the saved status is `CLEARED`, else `UPDATED`.
  - `Mark verify(Viewer, MarkId, VerificationState)` — gated on `policy.canManage`; rejects
    `decision==UNVERIFIED`; stamps `new Verification(decision, v.userId(), Instant.now())`, publishes `UPDATED`.
  - `Mark promote(Viewer, MarkId, LayerId targetOrNull)` — gated on `policy.canManage` the **source**
    layer *and* `policy.canContribute` the **target** (defaulting to `layerResolver.copLayerId()`);
    moves via `Mark#withLayer`, stamps `Verification(CONFIRMED,...)` only if not already `CONFIRMED`;
    publishes `UPDATED` against the **target** layer id.
  - `void delete(Viewer, MarkId)` — same `requireEditable` gate as `patch`; publishes `Action.DELETED`.
  - **Authorization, in full**: `create`/`geolocate` → `canContribute` on the resolved layer;
    `patch`/`delete` → creator-while-`UNVERIFIED`-or-`canManage`; `verify` → `canManage`;
    `promote` → `canManage` source + `canContribute` target. **Visibility precedes every mutation
    gate**: a mark on a layer the actor cannot `canView` throws `NoSuchElementException` ("Unknown
    mark" → 404) from `requireVisible`, so a mutation can never reveal an invisible mark's existence
    — map ids are guessable UUIDs whose existence is itself the secret, unlike assets. Only failures
    on a *visible* layer are `AccessDeniedException` (403). Same rule in `DefaultDrawingService`
    (`requireVisible`) and `DefaultMapLayerService` (`requireManage` checks `canView` first).
- **`MarkSpec(layerId, kind, affiliation, label, note, position)`** — `MarkService#create`'s command,
  a `MANUAL` mark from a map click; `layerId` nullable (`null` = creator's default layer); duplicates
  `Mark`'s own label-non-blank/note-blank-normalizes-to-null invariants.
- **`GeolocateSpec(assetId, layerId, kind, affiliation, label, note, depressionDegrees)`** —
  `MarkService#geolocate`'s command, a `DETECTION` mark projected from an asset's freshest telemetry;
  carries no position of its own — `depressionDegrees` defaults to `GeoProjection.DEFAULT_DEPRESSION_DEGREES`
  at the wire boundary when omitted, validated by `GeoProjection#project` itself.
- **`MarkPatch(kind, affiliation, label, note, position, status)`** — `MarkService#patch`'s
  partial-patch command; every component `Optional<T>` (not a bare nullable field — `Optional.empty()`
  unambiguously means "leave unchanged" without colliding with `note`, which can itself legitimately
  be absent); `MarkPatch.NOTHING` the identity patch.

## Conventions
- Every domain record validates in its compact constructor with manual `if (…) throw new IllegalArgumentException(…)`.
- Every `List`/`Set`/`Map` component is reassigned via `List.copyOf`/`Set.copyOf`/`Map.copyOf` in the
  compact ctor.
- `with*` copy methods return new record instances — records have no setters.
- Typed ids (`MarkId`, `LayerId`, `DrawingId`) wrap `UUID` with `random()`/`of(String)` (rethrows
  `IllegalArgumentException` on a malformed string), the same pattern every other context's ids use.
- Application services take a `MapAccessPolicy.Viewer` as their acting-user parameter, not separate
  `UserId`/`Ownership`/`VisibilityScope` arguments — the one deliberate departure from the
  `UserId actor` + `VisibilityScope scope` idiom every other context's services use, because map
  authorization is identity/group-membership-based, not `VisibilityScope`-based (see `MapAccessPolicy`).
- `Optional<T>`-typed patch records (`MarkPatch`, `DrawingPatch`) rather than bare-nullable ones
  (`AssetEdit`/`DeviceEdit`-style) whenever a field can itself legitimately be absent — a `null`
  passed for any component normalizes to `Optional.empty()` in the compact ctor.

## Gotchas
- **`MapAccessPolicy` deliberately does not use `VisibilityScope`** (`vision-platform`) even though
  every other context's authorization runs through it — see the class's own entry above. Do not
  "simplify" this context onto `VisibilityScope` without re-reading why; doing so silently breaks
  PILOT access to their own team's map.
- **`DefaultMarkService`'s original design (docs/plans/done/TACTICAL-MARKS-PLAN.md M3) fell into
  exactly this trap and was reworked** (docs/plans/done/MAP-REWORK-PLAN.md, superseding it in full):
  the pre-rework `list()` was deployment-wide with no scope parameter at all, and the creator-or-manager
  gate ran on `VisibilityScope` — both workarounds for the same `includesGroup`-hard-`false`-for-
  `ASSIGNED_ASSETS` trap. The current, layer-scoped `list()` plus `MapAccessPolicy`'s identity-based
  resolution fix it at the root instead of routing around it.
- **`copLayerId()`/personal-layer auto-create stamp a fixed system principal, not the real bootstrap
  actor** — `LayerResolver` has no acting `Viewer` at the zero-arg `copLayerId()` call site, so it uses
  a well-known sentinel `UUID(0,0)`/`UUID(0,1)` pair redefined locally (this module may not depend on
  `vision-app`, where the equivalent `DevPrincipal` sentinel lives) — the two coincide under the
  dev/no-auth profile by construction and are simply inert elsewhere.
- **This context declares no dependency on `AuditTrailPort`** — unlike every other context with a
  write path, no map service audits its mutations today. If that changes, it's a new constructor
  parameter on `DefaultMapLayerService`/`DefaultDrawingService`/`DefaultMarkService`, all already at
  or near the 5-arg ceiling (`.claude/skills/java-clean-code/SKILL.md` §3) — bundling would likely be
  needed.
- **`MapLiveUpdatePort#publishMapEvent` scopes delivery per-connection by layer visibility** — every
  other context's live-update port (`FleetLiveUpdatePort`, `TelemetryLiveUpdatePort`,
  `DetectionLiveUpdatePort`, `EventLiveUpdatePort` in `vision-platform`) broadcasts unconditionally to
  every connected client. A driving adapter implementing this port must actually check
  `MapAccessPolicy.canView` per connection before forwarding, not just fan out.

## Status

**W1.7b extraction** (docs/plans/active/DOMAIN-SEPARATION-W1.md §16): this context moved out of the
flat `vision-domain`/`vision-application` modules into its own Maven module, `contexts/vision-map` —
a directory move (`com/drones/vision/map/**`), no package rename, no behavior change. Depends on
`vision-kernel`, `vision-platform`, `vision-identity` (for `Role`), `vision-perception` (for
`UsageTracker`, `DefaultMarkService#geolocate`'s telemetry read). **223/223 green**, unchanged from
the pre-extraction combined count.

docs/plans/done/MAP-REWORK-PLAN.md **Wave A done** (map as Common Operational Picture, domain half —
modeled on DELTA's Monitor/layers/verify-promote ideas): nine new domain types —
`Affiliation`/`LayerId`/`DrawingId`/`LayerKind`/`AccessLevel`/`LayerGrant`/`MapLayer`/`Verification`/
`DrawKind`/`Drawing` — plus `MapEvent` (the live-update payload), two new ports
(`MapLayerRepositoryPort`/`DrawingRepositoryPort`), and one breaking change: the former god-port
`LiveUpdatePublisherPort`'s three `default` no-op `publishMarkCreated`/`Updated`/`Cleared` methods
(docs/plans/done/TACTICAL-MARKS-PLAN.md §5) deleted, replaced by one non-`default`
`publishMapEvent(MapEvent)` (that port itself was deleted outright in W1.6b and replaced by
per-context ports — this context's own slice is `MapLiveUpdatePort`, unchanged in shape from Wave A's
`publishMapEvent`). `Mark` reworked in place: three new components (`layerId`, `affiliation`,
`verification`), five wither methods, `MarkKind` reshaped (`FRIENDLY` deleted — a friendly mark is now
any kind with `Affiliation.FRIENDLY` — `UNIT`/`EQUIPMENT` added). 478/478 green at the time (up from
391, +87 tests across the new types plus `MarkTest` growing from 14 to 22).

docs/plans/done/MAP-REWORK-PLAN.md **Wave B done** (application half): `MapAccessPolicy`,
`LayerResolver`, `MapLayerService`+`Default`, `DrawingService`+`Default`, plus `MarkService` reworked
in place — see the API surface above for the full method-by-method contract and the "two rounds"
history below.

docs/plans/done/TACTICAL-MARKS-PLAN.md **Wave M1 done** (geolocated tactical marks, domain half,
predates the rework above): `Mark`/`MarkId`/`MarkKind`/`MarkStatus`/`MarkSource`, `MarkRepositoryPort`,
plus `GeoProjection`/`BearingDistance` (now in `vision-kernel` — pure geo-math with no prior
bearing/distance/haversine helper anywhere in the codebase before this wave). 322/322 green at the
time (up from 266, +56).

docs/plans/done/TACTICAL-MARKS-PLAN.md **Wave M3 done, then revised** (application half — MarkService
+ geolocate + visibility/authz + live-publish). **Two rounds**: the first pass mirrored the plan's
frozen §2 contract literally — a group-filtered `list(VisibilityScope, GroupId)` and a creator-or-manager
gate confined to `update`'s status-transition branch only. Review caught that this combination locks
out the primary persona the feature is for: a PILOT's `VisibilityScope` is `ASSIGNED_ASSETS`, which
carries no group at all, so an FPV operator could never see — let alone correct or clear — even their
own mark. This is the design history `MAP-REWORK-PLAN.md`'s Wave B/`MapAccessPolicy` fully supersedes
(see Gotchas) — kept here for context, not as the current shape. 560/560 green after the revision (up
from 535, same test count across both rounds — the revision replaced tests, not added to them).

**Cross-module note**: the map context's live channel (`"marks"` SSE topic) and `list()` are
deliberately consistent — both deployment-wide-then-layer-scoped, never a mismatch between what a
REST list call and the live stream show. See `vision-api`/`vision-app`'s own `MODULE.md`s for
`LiveUpdateRegistry`'s `publishMapEvent` override and the wiring.
