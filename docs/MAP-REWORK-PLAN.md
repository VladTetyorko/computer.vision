# MAP-REWORK-PLAN — the map as a Common Operational Picture (COP)

Status: **authoritative spec — implementation in progress** (2026-08-05)
Depends on: TACTICAL-MARKS-PLAN (shipped, superseded in parts), U-AUTH-PLAN, U-SCOPE-PLAN, OPS-CORE-PLAN §G, REALTIME-PLAN.

## 0. Goal

Rework the map — backend and frontend — from "a widget embedded in pages" into a **self-explaining, standalone COP component** modeled on the ideas of Ukraine's DELTA system (Monitor module):

1. **Every object on the map has a visibility scope** ("область видимості"): who may see it is a property of the data, resolved server-side, never a client-side filter.
2. **Friend/enemy affiliation** is first-class: marks (and assets) render with APP-6-inspired affiliation symbology (friendly / hostile / neutral / unknown).
3. **Layers with grantable access** (DELTA's "give N participants access to a layer"): marks and drawings live on named layers; layer owners/managers grant VIEW / CONTRIBUTE / MANAGE to users or groups.
4. **Mark verification & promotion** (DELTA's verify→confirm→share-wider flow): anyone in scope contributes an UNVERIFIED mark on a team layer; a manager verifies it and **promotes** it to the shared COP layer everyone sees.
5. **Drawing on the map**: lines, polygons, arrows, text annotations on layers — the substrate for "plans" (a later slice groups drawings into an operation plan).
6. **One map component**: today five independent Leaflet hosts duplicate zones/marks/layer logic byte-for-byte. `FleetMap` + `LiveMap` collapse into one `TacticalMap` in `shared/map/tactical-map/` with a built-in legend, data-layer panel, and symbology — self-explaining without the hosting page.

### DELTA ideas adopted (research summary)

| DELTA concept | Our adaptation |
|---|---|
| Monitor: one shared real-time map of friendly + enemy forces | `TacticalMap` component + scoped `/api/map/*` read model |
| APP-6 symbology (affiliation frames/colors) | `Affiliation` enum + CSS divIcon symbology (not full APP-6 glyph set) |
| Layers filled manually, access granted per-layer to participants | `MapLayer` + `LayerGrant` (user/group × VIEW/CONTRIBUTE/MANAGE) |
| Marks verified/confirmed by analysts, then shared wider | `Verification` on Mark + `promote` to the system **COP layer** |
| Target Hub: mark → target workflow | `MarkKind.TARGET` + verification; full tasking = later slice |
| Mission Control: planning/drawing (trenches, positions, routes) | `Drawing` (LINE/POLYGON/ARROW/TEXT); "plans" = later slice |
| Tiered echelon access | existing `Role` (PILOT/MANAGER/ADMIN) × memberships + per-layer grants |

### Non-goals (this rework)

- No new roles. PILOT / MANAGER / ADMIN stay; visibility tiers are **data** (layers + grants), not roles.
- No offline/mobile mode, no mark chat/threads, no strike tasking, no full APP-6 glyph catalog.
- `ReplayMap`, `GeofenceZoneDialog`, `FlightPlanDialog` stay as-is (they are special-purpose editors); only `FleetMap` + `LiveMap` are consolidated.
- Geofence zones stay global reference data (no scoping) — unchanged stack.
- "Plans" (grouping drawings into an operation with phases) — designed for, not built.

---

## 1. Current state (facts the design builds on)

- Roles: `Role {PILOT, MANAGER, ADMIN}`; `VisibilityScope {UNBOUNDED, GROUPS, ASSIGNED_ASSETS}` resolved by `ScopeResolver`; `CurrentUser` (vision-api) is the only place auth is read; services get plain args.
- **Known trap** (recorded in vision-application/MODULE.md): `VisibilityScope.includesGroup()` is hard-`false` for `ASSIGNED_ASSETS`, which hid all marks from PILOTs — the shipped `MarkService.list()` therefore takes **no scope at all** (deployment-wide). This rework fixes visibility properly: **map visibility resolves from `UserId` + group memberships**, not from asset-scope.
- Marks stack (TACTICAL-MARKS waves M1–M5) is fully shipped: `Mark(MarkId, GeoPosition, MarkKind{TARGET,HAZARD,POI,FRIENDLY}, label, note, Ownership, createdAt, MarkStatus{ACTIVE,CLEARED}, MarkSource{MANUAL,DETECTION})`, `MarkRepositoryPort`, `MarkService`, `/api/marks` CRUD + geolocate, SSE topic `marks`, `core/marks/*` stores, marks panels in Fly + Command.
- SSE: `/api/live?topics=…`, `LiveUpdateRegistry` ring buffers, **broadcasts to every connection** — no per-connection filtering exists yet.
- Map UI: Leaflet via `shared/map/tile-cache/leaflet-loader.ts`; `FleetMap` (774 ln) and `LiveMap` (437 ln) duplicate `applyZones`/`applyMarks`/`escapeHtml`/layer-switch logic; state in page-provided `FleetMapStore` / `TelemetryStore`; marks UI state in root `MarksStore`.
- Persistence is opt-in (`vision.persistence.enabled`); devsupport in-memory repos mirror every JPA repo. Latest migration: `V10__marks.sql`.

---

## 2. Domain model (Wave A — FROZEN)

All in `vision-domain/.../domain/model/` unless noted. Records validate in compact constructors with manual `IllegalArgumentException`; ids wrap UUID with `random()` / `of(String)`.

### 2.1 New types

```java
public enum Affiliation { FRIENDLY, HOSTILE, NEUTRAL, UNKNOWN }

public record LayerId(UUID value) { /* random(), of(String) — standard id idiom */ }

public enum LayerKind { COP, TEAM, PERSONAL }
// COP: the single system layer, org-wide visible, MANAGER+ writes (promotion target).
// TEAM: owned by a group — group members see + contribute; grants extend further.
// PERSONAL: owner-only by default; grants extend.

public enum AccessLevel { VIEW, CONTRIBUTE, MANAGE }
// VIEW < CONTRIBUTE < MANAGE (ordinal comparison allowed; declare in this order)

public record LayerGrant(SubjectType subjectType, UUID subjectId, AccessLevel level) {
    public enum SubjectType { USER, GROUP }
}

public record MapLayer(LayerId id, String name, LayerKind kind, Ownership ownership,
                       List<LayerGrant> grants, Instant createdAt) {
    // name non-blank ≤ 80; grants immutable copy, non-null; COP layer: exactly one per deployment
    public MapLayer withName(String name) …
    public MapLayer withGrants(List<LayerGrant> grants) …
}

public record Verification(VerificationState state, UserId verifiedBy, Instant verifiedAt) {
    public enum VerificationState { UNVERIFIED, CONFIRMED, REJECTED }
    public static Verification unverified() // (UNVERIFIED, null, null)
    // CONFIRMED/REJECTED require verifiedBy + verifiedAt non-null
}

public record DrawingId(UUID value) { /* standard id idiom */ }

public enum DrawKind { LINE, POLYGON, ARROW, TEXT }

public record Drawing(DrawingId id, LayerId layerId, DrawKind kind,
                      List<GeoPosition> points, String label, String colorToken,
                      Ownership ownership, Instant createdAt) {
    // LINE/ARROW ≥ 2 points; POLYGON ≥ 3; TEXT exactly 1 and label non-blank.
    // label optional otherwise, ≤ 120; colorToken optional slug (kebab-case, ≤ 30) — a
    // UI token name ("accent", "danger"), NOT a hex value; points immutable copy.
    public Drawing withGeometry(List<GeoPosition> points) …
    public Drawing withDetails(String label, String colorToken) …
}
```

### 2.2 Reworked `Mark`

```java
public record Mark(MarkId id, LayerId layerId, GeoPosition position,
                   MarkKind kind, Affiliation affiliation, String label, String note,
                   Ownership ownership, Instant createdAt,
                   MarkStatus status, MarkSource source, Verification verification) {
    public Mark withPosition(GeoPosition p) …
    public Mark withDetails(String label, String note, MarkKind kind, Affiliation affiliation) …
    public Mark withStatus(MarkStatus s) …
    public Mark withVerification(Verification v) …
    public Mark withLayer(LayerId l) …   // promotion
}

public enum MarkKind { UNIT, EQUIPMENT, HAZARD, POI, TARGET }
// migration of the old enum: TARGET→TARGET, HAZARD→HAZARD, POI→POI, FRIENDLY→UNIT
```

`MarkStatus {ACTIVE, CLEARED}` and `MarkSource {MANUAL, DETECTION}` are unchanged. The old `MarkKind.FRIENDLY` is deleted — "whose it is" is now `Affiliation`, "what it is" is `MarkKind`. Old-kind → (new kind, default affiliation) mapping, used by both migration and devsupport seed: `TARGET→(TARGET, HOSTILE)`, `HAZARD→(HAZARD, UNKNOWN)`, `POI→(POI, NEUTRAL)`, `FRIENDLY→(UNIT, FRIENDLY)`.

### 2.3 Ports (`.../port/out/`)

```java
public interface MapLayerRepositoryPort {
    MapLayer save(MapLayer layer);
    Optional<MapLayer> findById(LayerId id);
    List<MapLayer> findAll();
    void deleteById(LayerId id);
}
public interface DrawingRepositoryPort {
    Drawing save(Drawing d);
    Optional<Drawing> findById(DrawingId id);
    List<Drawing> findAll();
    void deleteById(DrawingId id);
}
```

`MarkRepositoryPort` is unchanged (save/findById/findAll/deleteById) — visibility filtering is application-layer logic, and at this deployment's scale `findAll()` is fine.

`LiveUpdatePublisherPort` (domain out-port) — the mark methods are **replaced** by map-scoped ones:

```java
void publishMapEvent(MapEvent event);

public record MapEvent(EntityType entity, Action action, LayerId layerId, Object payload) {
    public enum EntityType { MARK, DRAWING, LAYER }
    public enum Action { CREATED, UPDATED, CLEARED, DELETED }
}
```

(`payload` is the domain object — `Mark`, `Drawing`, or `MapLayer`; the API layer maps it to DTOs. `CLEARED` used only for marks.)

---

## 3. Visibility model (Wave B — FROZEN semantics)

New pure class `vision-application/.../application/map/MapAccessPolicy.java`. **It resolves from the user's identity, not from `VisibilityScope`** (avoids the ASSIGNED_ASSETS trap). Inputs: `UserId`, `Set<GroupId>` (memberships, incl. subtree the way `ScopeResolver` expands them), `Role topRole`.

Effective access to a layer = **max** of:

| rule | grants |
|---|---|
| `ADMIN` | MANAGE on every layer |
| layer `kind == COP` | VIEW to everyone; CONTRIBUTE+MANAGE to MANAGER on any group / ADMIN |
| `ownership.ownerId == user` | MANAGE |
| `kind == TEAM` and `ownership.groupId ∈ memberships` | CONTRIBUTE (members contribute to their team layer) |
| MANAGER whose scope groups include `ownership.groupId` | MANAGE |
| explicit `LayerGrant(USER, userId, L)` | L |
| explicit `LayerGrant(GROUP, g, L)` where `g ∈ memberships` | L |

API (all pure, unit-tested with hand fakes):

```java
public final class MapAccessPolicy {
    public record Viewer(UserId userId, Set<GroupId> groups, Role topRole) {}
    public AccessLevel accessTo(Viewer v, MapLayer layer);        // may return null = no access
    public boolean canView(Viewer v, MapLayer l);                 // accessTo ≥ VIEW
    public boolean canContribute(Viewer v, MapLayer l);           // ≥ CONTRIBUTE
    public boolean canManage(Viewer v, MapLayer l);               // ≥ MANAGE
}
```

Rules applied by services:

- **list marks/drawings**: only from layers where `canView`. Server-side always; SSE too (§5).
- **create** mark/drawing on layer L: `canContribute(L)`. Default layer if none given: the user's first TEAM layer (by group), else PERSONAL auto-created (`"<displayName> — personal"`), never COP directly.
- **edit/move/clear/delete** mark: creator while UNVERIFIED, or `canManage(layer)`. After CONFIRMED, creator loses edit — only `canManage`.
- **verify** (CONFIRM/REJECT): `canManage(layer)`.
- **promote**: `canManage(source layer)` and `canContribute(target)`; default target = the COP layer. Promotion moves the mark (`withLayer`) and stamps `Verification(CONFIRMED, actor, now)` if not already confirmed.
- **layer CRUD**: create TEAM → MANAGER of that group / ADMIN; create PERSONAL → anyone; rename/delete → `canManage`; COP layer cannot be renamed/deleted; deleting a layer deletes its marks + drawings (emit DELETED events for each).
- **grants**: `PUT` wholesale by `canManage`; a MANAGER may grant at most `maxGrantableRole()`-equivalent — concretely: grant levels are free, but only on layers they manage.

### Services (`vision-application/.../application/map/`)

```java
public interface MapLayerService {
    List<LayerView> layers(Viewer v);                       // visible layers + my level
    MapLayer create(Viewer v, LayerSpec spec);              // kind TEAM|PERSONAL
    MapLayer rename(Viewer v, LayerId id, String name);
    void delete(Viewer v, LayerId id);
    MapLayer setGrants(Viewer v, LayerId id, List<LayerGrant> grants);
    LayerId copLayerId();                                    // ensured at startup
    record LayerSpec(String name, LayerKind kind, GroupId groupId) {}
    record LayerView(MapLayer layer, AccessLevel myAccess) {}
}
public interface MarkService {                               // reworked in place
    List<Mark> list(Viewer v);
    Mark create(Viewer v, MarkSpec spec);                    // spec gains layerId?, affiliation
    Mark geolocate(Viewer v, GeolocateSpec spec);
    Mark patch(Viewer v, MarkId id, MarkPatch patch);        // patch gains kind?, affiliation?
    Mark verify(Viewer v, MarkId id, VerificationState decision);
    Mark promote(Viewer v, MarkId id, LayerId targetOrNull);
    void delete(Viewer v, MarkId id);
}
public interface DrawingService {
    List<Drawing> list(Viewer v);
    Drawing create(Viewer v, DrawingSpec spec);
    Drawing patch(Viewer v, DrawingId id, DrawingPatch patch); // geometry/label/colorToken
    void delete(Viewer v, DrawingId id);
}
```

`Viewer` is built in the API layer from `CurrentUser` (a new `currentUser.viewer()` — see §4). Every mutation publishes a `MapEvent`. `DefaultMarkService`'s old no-scope `list()` and `requireCreatorOrManager` are replaced by the policy above. A startup initializer (`vision-app`) ensures the COP layer exists (name `"Common picture"`, kind COP) for both persistence modes.

---

## 4. Wire contract (Wave C — FROZEN)

All endpoints require auth when `vision.auth.enabled`; controllers thread `CurrentUser` → `Viewer`. `CurrentUser` gains `viewer()` returning `MapAccessPolicy.Viewer` (memberships expanded the same way `ScopeResolver` expands group subtrees; when auth is off, the dev-admin Viewer is ADMIN/UNBOUNDED). Old `/api/marks/**` endpoints and the `marks` SSE topic are **removed** (breaking change is fine — the SPA in this repo is the only client and migrates in Waves D/E).

### 4.1 REST — base `/api/map`

| Method + path | Req body | Resp | Notes |
|---|---|---|---|
| `GET /api/map/layers` | — | `200 [LayerResponse]` | visible layers, COP first, then by name |
| `POST /api/map/layers` | `CreateLayerRequest` | `201 LayerResponse` | |
| `PATCH /api/map/layers/{id}` | `{name}` | `200 LayerResponse` | rename only |
| `DELETE /api/map/layers/{id}` | — | `204` | cascades marks+drawings |
| `PUT /api/map/layers/{id}/grants` | `{grants:[GrantDto]}` | `200 LayerResponse` | wholesale |
| `GET /api/map/marks` | — | `200 [MarkResponse]` | ACTIVE only, newest-first |
| `POST /api/map/marks` | `CreateMarkRequest` | `201 MarkResponse` | |
| `POST /api/map/marks/geolocate` | `GeolocateMarkRequest` + `layerId?`, `affiliation?` | `201 MarkResponse` | |
| `PATCH /api/map/marks/{id}` | `PatchMarkRequest` | `200 MarkResponse` | |
| `POST /api/map/marks/{id}/verify` | `{decision:"CONFIRMED"\|"REJECTED"}` | `200 MarkResponse` | |
| `POST /api/map/marks/{id}/promote` | `{targetLayerId?}` | `200 MarkResponse` | default: COP |
| `DELETE /api/map/marks/{id}` | — | `204` | |
| `GET /api/map/drawings` | — | `200 [DrawingResponse]` | |
| `POST /api/map/drawings` | `CreateDrawingRequest` | `201 DrawingResponse` | |
| `PATCH /api/map/drawings/{id}` | `PatchDrawingRequest` | `200 DrawingResponse` | |
| `DELETE /api/map/drawings/{id}` | — | `204` | |

Errors via existing `ApiExceptionHandler`: bad input → 400, unknown id **or out-of-scope id → 404** (never reveal existence), forbidden action on a visible object → 403.

### 4.2 DTOs (vision-api `dto/`, Jackson 3 records)

```java
LayerResponse   { String layerId, name, kind, ownerUserId, groupId, myAccess,
                  List<GrantDto> grants /* null unless myAccess == MANAGE */,
                  int markCount, int drawingCount, Instant createdAt }
GrantDto        { String subjectType, subjectId, level }
CreateLayerRequest { String name, kind /* TEAM|PERSONAL */, groupId /* req for TEAM */ }
MarkResponse    { String markId, layerId, double latitude, longitude, Double altitudeMeters,
                  String kind, affiliation, label, note, createdByUserId, groupId,
                  Instant createdAt, String status, source,
                  String verification /* UNVERIFIED|CONFIRMED|REJECTED */,
                  String verifiedByUserId, Instant verifiedAt }
CreateMarkRequest { String layerId /* opt */, double latitude, longitude, Double altitudeMeters,
                  String kind, affiliation, label, String note /* opt */ }
PatchMarkRequest { Double latitude, longitude, altitudeMeters; String kind, affiliation,
                  label, note, status /* all optional, null = unchanged */ }
DrawingResponse { String drawingId, layerId, kind, label, colorToken,
                  List<PositionDto> points, String createdByUserId, Instant createdAt }
CreateDrawingRequest { String layerId /* opt */, kind, label /* opt */, colorToken /* opt */,
                  List<PositionDto> points }
PatchDrawingRequest { List<PositionDto> points; String label, colorToken /* null = unchanged */ }
PositionDto     { double latitude, longitude; Double altitudeMeters }
```

### 4.3 SSE — scoped delivery (the security-critical rework)

- New topic **`map`** replaces `marks`. Payload: `MapEventPayload { String entity /* mark|drawing|layer */, String action /* created|updated|cleared|deleted */, String layerId, MarkResponse mark, DrawingResponse drawing, LayerResponse layer /* exactly one non-null; layer.grants always null over SSE */ }`.
- `LiveUpdateRegistry` today broadcasts every event to every connection. Rework: a connection subscribing to `map` captures its `Viewer` at connect (from `CurrentUser`); events on the `map` topic are delivered **only if `policy.canView(viewer, layer)`** for the event's `layerId`. Implementation: registry keeps `layerId` on buffered map-events and a per-connection predicate supplied by `LiveController`; layer set is re-resolved when grants/layers change (simplest correct approach: the predicate re-checks against a small TTL-cached `layers()` lookup, or re-resolves on every `layer`-entity event — implementer's choice, but **stale-grant leakage beyond 30 s is a bug**).
- `Last-Event-ID` resume must also re-filter (resume replays only events the *current* viewer may see).
- When auth is disabled, dev-admin viewer sees everything (today's behavior).

### 4.4 Persistence (Wave C)

`V11__map_layers.sql`: `map_layers` (id, name, kind, owner_user_id, group_id, created_at), `map_layer_grants` (layer_id FK cascade, subject_type, subject_id, level), `map_drawings` (id, layer_id FK cascade, kind, label, color_token, points jsonb, owner_user_id, group_id, created_at); `marks` gains `layer_id` (FK, backfilled to the COP layer created in this migration), `affiliation`, `verification_state` (default UNVERIFIED), `verified_by`, `verified_at`; old kind values migrated per §2.2 mapping. In-memory devsupport repos mirror all of it.

---

## 5. Frontend (Waves D + E)

### 5.1 `TacticalMap` — the standalone component (Wave D)

`shared/map/tactical-map/` (3-file component + pure logic): **replaces `FleetMap` and `LiveMap`**. One Leaflet host, composable overlays, self-explaining chrome. Keeps every existing behavior of both hosts (tile cache, theme-aware basemap, auto-fit reducer, focus requests, delegated popup clicks, zoneless plain-field Leaflet idiom, `::ng-deep` styling).

Inputs/outputs (superset of today's two hosts):

```
[assets]           FleetMarker[]      // 0..n; 1 in follow mode
[followAssetId]    string|null       // null = fleet auto-fit mode
[zones]            GeofenceZone[]
[marks]            Mark[]            // v2 model
[drawings]         Drawing[]
[layers]           LayerView[]       // for the layer panel + mark badge coloring
[events]           EventMarker[]     // detection events (Command passes, Fly doesn't)
[selectedMarkId] [selectedAssetId] [attentionAssetIds] [focusRequest]
[interactionMode]  'view'|'mark'|'draw-line'|'draw-polygon'|'draw-arrow'|'draw-text'
(markSelected) (markMoved) (mapClicked) (drawingCompleted) (drawingSelected)
(watch) (preview) (openEventAsset) (layerVisibilityChanged)
```

Built-in, always-on chrome (this is what "self-explaining" means):

- **Legend** panel: affiliation symbology swatches (friendly/hostile/neutral/unknown), mark-kind glyphs, asset states (streaming/offline/no-position with counts), zone kinds. Collapsible, defaults per host container width.
- **Data-layer panel**: one row per `LayerView` (name, kind chip, mark+drawing counts, eye toggle) + built-in rows for Assets / Zones / Events. Eye toggles are **client-side view state** (persisted per user in localStorage `vision.map.hiddenLayers`), orthogonal to server-side visibility.
- Basemap segmented control (existing 4 basemaps, theme-aware default) folded into the same panel.

Symbology (APP-6-inspired, CSS divIcons in `tactical-map` styles, tokens from the design system):

- Affiliation → frame + color: FRIENDLY blue `#4f8cff` rounded frame; HOSTILE red `#ff5d5d` diamond; NEUTRAL green square; UNKNOWN yellow quatrefoil (approximated: rounded-diamond). Own **assets always render as FRIENDLY** symbology.
- MarkKind → inner glyph (existing `markKindIcon` approach, remapped to UNIT/EQUIPMENT/HAZARD/POI/TARGET).
- Verification: UNVERIFIED marks render dashed-frame + reduced opacity; CONFIRMED solid. COP-layer marks get a subtle ring.
- Drawings: polyline/polygon in `colorToken`-resolved color; ARROW = polyline + arrowhead marker at the end; TEXT = label-only divIcon.

Pure logic in `shared/map/tactical-map/tactical-map-logic.ts` (unit-tested): symbology class resolution, layer-visibility filtering, legend counts, drawing-vertex reducers. Shared helpers (`escapeHtml`, zone layer apply) move here from the duplicated hosts.

Host migration (still Wave D): Command, Fly cockpit, `/live/:deviceId`, asset-detail all swap to `<vision-tactical-map>`; `FleetMap` + `LiveMap` and their CSS are **deleted**. `/live` and asset-detail now also pass zones+marks (bug fix: today they silently drop them).

### 5.2 Stores + interactions (Wave E)

- `core/api/models.ts` + `vision-api.ts`: new `/api/map/*` types + calls; delete old `/api/marks` client. `core/live/live-store.ts`: `map` topic replaces `marks` (`mapEvents()` signal).
- `core/map-data/` (new feature-responsibility folder): `layers-store.ts` (root; load + CRUD + grants + eye-toggle state), `drawings-store.ts` (root; CRUD + SSE apply), reworked `marks-store.ts` (moves from `core/marks/`; v2 model, SSE apply, `pendingKind` → `pendingPalette {kind, affiliation}`). Pure logic files beside each store, unit-tested.
- **Mark palette**: entering `interactionMode='mark'`, next map click opens the palette (kind × affiliation grid + label + layer picker limited to CONTRIBUTE layers) → create. Same palette edits an existing selected mark (creator/manager per API).
- **Verify/promote UI**: mark popover shows verification chip; for managers: Confirm / Reject / **Promote to common picture** actions. Marks panels (Fly + Command) get an UNVERIFIED filter chip and per-mark verify shortcut.
- **Drawing toolbar**: mode buttons (line/polygon/arrow/text) on the map chrome; click-to-add vertices, double-click/Enter completes → `drawingCompleted` → store create. Select→drag vertices to edit (PATCH), delete key removes (creator/manager gating comes from API errors — UI hides actions when `myAccess < CONTRIBUTE`).
- **Layer manager**: from the data-layer panel, MANAGE-level rows expose a grants editor (user/group picker from existing org endpoints, level select, wholesale PUT) + create-layer flow (TEAM for managers, PERSONAL for anyone).
- Fly marks panel keeps geolocate ("Mark target" → `POST /api/map/marks/geolocate` with palette).

Feature folders (per UI-STRUCTURE rules): map chrome/domain widgets live in `shared/map/tactical-map/`; palette, toolbar, layer-manager, verify controls in `shared/map/map-controls/` (used by both Fly and Command); page wiring stays in `features/fly/` + `features/command/`.

---

## 6. Waves — disjoint scopes, agent per wave

Every wave: scoped build green (`./mvnw -B -pl <module> test` / `npm test`), MODULE.md updated, no cross-wave file edits.

| Wave | Agent | Scope (files) | Deliverable |
|---|---|---|---|
| **A** | domain-modeler | `vision-domain/**` | §2 types + ports + unit tests; `LiveUpdatePublisherPort.publishMapEvent` replacing mark methods |
| **B** | application-service | `vision-application/**` | `MapAccessPolicy` + §3 services (rework `mark/`, new `map/` pkg) + hand-fake tests incl. every §3 rule |
| **C** | spring-integrator | `vision-api/**`, `vision-app/**`, `adapters/adapter-persistence/**` | §4 REST + DTOs + scoped SSE + `CurrentUser.viewer()` + COP-layer bootstrap + V11 + devsupport repos + ArchUnit green |
| **D** | web-ui | `vision-web/src/app/shared/map/**`, hosts' templates, `core/map/**` | §5.1 `TacticalMap`, hosts migrated, FleetMap/LiveMap deleted |
| **E** | web-ui | `vision-web/src/app/core/api/**`, `core/map-data/**`, `core/live/**`, `shared/map/map-controls/**`, `features/fly|command/**` | §5.2 stores + interactions + panels |
| **F** | integrator (main) | — | cross-module verify, app smoke per role (admin/manager/pilot see different marks), docs + memory, commit |

Sequencing: A → B → C (backend chain). D starts after the plan freezes (it builds against §4/§5 contracts with existing data adapted; it may keep a thin local adapter from old `Mark` model until E lands). E after C + D. F last.

## 7. Later slices (designed-for, not built)

- **Plans / Mission Control**: `Plan(PlanId, name, layerId, phases)` grouping drawings + marks with a timeline; brief/rehearse mode on `TacticalMap`.
- **Target workflow**: TARGET marks → assignment/tasking ("go look at X"), strike feedback, BDA status — extends `Verification`.
- History/audit trail per mark (who moved/edited), mark expiry (auto-stale after N hours like DELTA's aging), clustering at low zoom, replay of the COP over time, editor migration (`GeofenceZoneDialog`, `FlightPlanDialog`) onto `TacticalMap`.
