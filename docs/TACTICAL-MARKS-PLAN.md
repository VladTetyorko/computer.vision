# TACTICAL-MARKS-PLAN — geolocated tactical marks + shared operational picture

Status: **draft for review** (2026-07-31). Freezes the contract below; implementation delegated to
disjoint waves M1–M5. Lets any in-scope role (FPV operator / crew / manager) drop geolocated tactical
**marks** on the map that appear **live** on everyone else's map at the command point — cockpit inset,
command map, and wall. A mark is, structurally, a *point* version of a **geofence zone** with tactical
types, so this plan **mirrors the proven geofence stack end-to-end** and adds exactly two genuinely-new
pieces: a **live broadcast** of mark deltas over the existing SSE channel, and a **pure geo-projection**
helper that turns a drone's pose into an estimated ground point. **Pure awareness/coordination — no
command-TX, not capability-gated.**

## Goal, in the operator's terms

> "When I see something that matters — a target, a hazard, a friendly, a point of interest — let me
> drop a pin on the map with one tap (or geolocate whatever the drone is looking at), give it a kind
> and a label, and have it show up on everyone's screen at the command point instantly. Let me pick a
> pin and read the bearing and distance to it from the drone and from home. If the geolocated pin lands
> a bit off, let me drag it to where it really is."

Made precise:
- A **mark** is a single `GeoPosition` + a tactical `kind` (TARGET / HAZARD / POI / FRIENDLY,
  colour-by-kind) + a short `label` + an optional `note`, owned by the creator's group, with a
  lifecycle `status` (ACTIVE | CLEARED) and a `source` (MANUAL | DETECTION).
- Created two ways: **(a)** click anywhere on any shared map (`live-map` / `fleet-map`); **(b)**
  **geolocate** in the cockpit — the server reads the asset's latest telemetry (position + heading +
  altitude), projects a ground point ahead of the drone, and drops a `DETECTION`-sourced mark there.
- **Persisted AND broadcast live.** A mark one person drops appears on everyone's map in real time,
  via the existing `/api/live` SSE channel — the same way detections and events already stay live.
- **Scope-shared:** the operational picture is common to everyone in the same group (command point).
- Selecting a mark shows **bearing + distance** from the drone (live telemetry) and from home.
- The geolocated point is an **honest estimate** (no gimbal / camera-intrinsics data) — so every mark
  is **draggable / editable** to correct it.

---

## The core decision: mirror the geofence stack (and where it must diverge)

The codebase already has a full, proven template for "a shape on the shared map with CRUD + a store +
a map overlay" — **Geofence**. Every wave mirrors the real geofence file at the equivalent layer:

| Layer | Geofence template (real file) | Mirror as |
|---|---|---|
| domain model | `vision-domain/…/model/GeofenceZone.java` (record + validation + `withDetails`/`withEnabled`) | `Mark` |
| domain id | `vision-domain/…/model/ZoneId.java` (`UUID`, `random()`/`of`) | `MarkId` |
| domain kind | `vision-domain/…/model/ZoneKind.java` (enum) | `MarkKind` |
| domain out-port | `vision-domain/…/port/out/GeofenceRepositoryPort.java` (`save`/`findById`/`findAll`/`deleteById`) | `MarkRepositoryPort` |
| JPA entity | `adapters/adapter-persistence/…/entity/GeofenceZoneEntity.java` (`@Entity`, enum as STRING) | `MarkEntity` |
| JPA repo | `adapters/adapter-persistence/…/JpaGeofenceRepository.java` (EMF → `JpaOperations`, `merge`/`find`/JPQL/remove) | `JpaMarkRepository` |
| migration | `adapters/adapter-persistence/…/db/migration/V7__geofence_zones.sql` | `V8__marks.sql` |
| in-memory devsupport | `vision-app/…/devsupport/InMemoryGeofenceRepository.java` (`ConcurrentHashMap`) | `InMemoryMarkRepository` |
| opt-in wiring | `vision-app/…/PersistenceWiringConfiguration.java` (`if properties.enabled() … else in-memory`) | add `markRepositoryPort` bean |
| application service | `vision-application/…/GeofenceService.java` + `DefaultGeofenceService.java` | `MarkService` + `DefaultMarkService` |
| application spec | `vision-application/…/GeofenceZoneSpec.java` | `MarkSpec` / `MarkPatch` / `GeolocateSpec` |
| REST controller | `vision-api/…/GeofenceController.java` (thin, `@RestController`) | `MarksController` |
| request/response DTO | `vision-api/…/dto/GeofenceZoneRequest.java` / `GeofenceZoneResponse.java` | `CreateMarkRequest` / `PatchMarkRequest` / `MarkResponse` |
| web api client | `vision-web/…/core/api/vision-api.ts` (`listGeofences`/`createGeofence`/…) | marks methods |
| web models | `vision-web/…/core/api/models.ts` (`GeofenceZone`, `ZoneKind`) | `Mark`, `MarkKind` |
| web store | `vision-web/…/core/geofence/geofence-store.ts` (`@Injectable`, signal + poll refresh) | `MarksStore` (initial GET **+ live SSE deltas**) |
| web pure logic | `vision-web/…/core/geofence/geofence-logic.ts` (`zoneLayerStyle`, mirrored `contains`) | `mark-logic.ts` (`markStyle`, mirrored `bearingDistance`) |
| map overlay | `[zones]` input on `vision-web/…/shared/map/live-map/live-map.ts` **and** `…/fleet-map/fleet-map.ts` (`input<GeofenceZone[]>` + `effect` → `applyZones`) | `[marks]` input + `applyMarks` |

**Where the mirror deliberately DIVERGES (these are the design, not accidents):**

1. **Scope + ownership.** Geofence is **global reference data** — it threads **no** `UserId`, **no**
   `VisibilityScope`, no owner, no audit (`GeofenceController` has no `CurrentUser` param at all; see
   its javadoc). Marks are **owned + group-scoped**, so for the ownership/scope dimension the plan
   mirrors **`AssetService` / `AssetController` / `Ownership`** (which do thread actor + scope), not
   geofence. This is the single biggest divergence and the reason M3/M4 differ from the geofence shape.
2. **Geometry is a point, not a ≥3-vertex polygon.** Drop geofence's `MIN_POLYGON_VERTICES = 3`
   invariant; a `Mark` holds one `GeoPosition` (with its own `altitudeMeters`), stored as plain
   lat/lon/alt columns — not a jsonb polygon array.
3. **`kind` is an icon/colour category, not a breach semantic.** No `contains` / ray-casting / breach
   machinery — those have no analog for a point.
4. **A real PATCH, not PUT-wholesale.** Geofence re-sends the full body on every edit; a mark has many
   independently-editable attributes and a drag-to-correct that sends only `position`, so marks use a
   partial `PATCH /api/marks/{id}`.
5. **Live push, not fetch-on-load.** Geofence is a 30 s poll (`GeofenceStore` + `PollScheduler`). Marks
   push over `/api/live` (see genuinely-new piece #1). `MarksStore` keeps the initial GET + a poll as a
   safety net, but the live channel is the primary path.
6. **Map layer is interactive.** Geofence renders `L.polygon` with `interactive:false`, no popup. Marks
   render `L.circleMarker` that is clickable (select → bearing/distance readout) and draggable
   (drag-to-correct → PATCH position).
7. **Clear is lightly gated.** Geofence lets anyone delete; marks gate clear/delete to **creator or
   manager** (see Roles). Annotation (label/note/position/kind) stays open to any in-scope viewer.

---

## The two genuinely-new pieces

### New piece #1 — live broadcast of mark deltas (shared, real-time)

The existing SSE path (surveyed, real): domain out-port
`vision-domain/…/port/out/LiveUpdatePublisherPort.java` (framework-free, fire-and-forget:
`publishFleetChanged`, `publishTelemetryAppended`, `publishDetections`, `publishEvent`,
`publishDetectionEvent`) → the one real impl `vision-api/…/live/LiveUpdateRegistry.java` (SSE fan-out
over `SseEmitter`, one `LiveRingBuffer` per topic, one global `AtomicLong` sequencer) exposed at
`GET /api/live` by `vision-api/…/LiveController.java`; the disabled-branch no-op
`vision-app/…/devsupport/NoopLiveUpdatePublisher.java`. On the wire the envelope is
`vision-api/…/dto/LiveEnvelopeResponse.java` `record(long seq, String assetId, String type, Object
payload)`, and the enumerated `type` vocabulary lives in `vision-api/…/live/LiveTopicKind.java`
(`FLEET("fleet")`, `EVENT("event")`, `TELEMETRY("telemetry")`, `DETECTIONS("detections")`,
`DEVICES("devices")`, `DETECTION_EVENTS("detection-events")`). The web side is
`vision-web/…/core/live/live-store.ts` (one `EventSource`, `applyEnvelope` switch merges each `type`
into a signal) with the TS `LiveEnvelope` discriminated union in `vision-web/…/core/api/models.ts`.

**Frozen divergence baked in here.** The codebase enforces **`envelope.type === LiveTopicKind.wire()`
1:1** — one envelope type per topic. The requested `mark.created` / `mark.updated` / `mark.cleared`
are **three sub-states of one concept**. The idiomatic mirror (exactly how `detection-events` carries
OPEN/CLOSED lifecycle in one topic) is **one always-on topic `MARKS("marks")` with the lifecycle in the
payload**. So the three logical events survive as an `action` field, not three topic kinds. See
Frozen contract §5. `MarkService` publishes on every change via `LiveUpdatePublisherPort`;
`LiveUpdateRegistry` maps each to a `marks` envelope; `MarksStore` merges deltas into its signal.

### New piece #2 — geolocation (drone pose → estimated ground point)

A **pure, unit-tested** helper `GeoProjection` in **vision-domain** (no existing bearing/distance/
haversine helper exists — surveyed; `DetectionExtrapolator.distance` is pixel-space, `GeofenceZone.
contains` is planar point-in-polygon — neither applies). It **reuses the existing geo type**
`vision-domain/…/model/GeoPosition.java` `record(double latitude, double longitude, Double
altitudeMeters)` for both input (drone pose) and output (ground point) — no second geo type is invented.
Inputs come straight off `vision-domain/…/model/Telemetry.java` (`latitude`, `longitude`,
`altitudeMeters`, `headingDegrees` — all `Double`, nullable). The server reads the asset's freshest
sample via `vision-application/…/UsageTracker.java` `Optional<Telemetry> latestTelemetry(AssetId)`
(the correct seam — `TelemetryRepositoryPort` has **no** "latest sample" query, per its javadoc).

**Honesty guardrail:** with no gimbal / camera-intrinsics data this is an **estimate** (assumed camera
depression angle, flat-forward projection). The created mark is `source = DETECTION`,
draggable/editable so the operator corrects it on the map. This is stated in the UI copy, not hidden.

---

## Current state (honest)

| Layer | Today | Gap this plan closes |
|---|---|---|
| domain marks | **no `Mark` type anywhere** | `Mark` + `MarkId` + `MarkKind` + `MarkStatus` + `MarkSource` + `MarkRepositoryPort` |
| geo math | **no** haversine/bearing/destination-point helper in domain or application | pure `GeoProjection` (`project` + `bearingDistance`) + `BearingDistance` record, thoroughly unit-tested |
| latest telemetry read | `UsageTracker.latestTelemetry(AssetId)` exists and carries `headingDegrees` | reuse it verbatim for `POST /api/marks/geolocate` |
| persistence | geofence table `V7`, JPA + in-memory + opt-in wiring proven | `marks` table `V8` + `JpaMarkRepository` + `InMemoryMarkRepository` + wiring, mirroring geofence |
| application | `GeofenceService` (unscoped); `AssetService` threads `Ownership`/`VisibilityScope`; `VisibilityScope.includesGroup(GroupId)` exists | `MarkService` = geofence CRUD shape **+** asset-style ownership/scope threading + geolocate + live-publish |
| REST | `GeofenceController` (no `CurrentUser`); `AssetController` reads `currentUser.ownership()/userId()/scope()` | `MarksController` = geofence CRUD **+** asset-style `currentUser` threading + geolocate + PATCH |
| live SSE | 6 topic kinds; `LiveUpdatePublisherPort` 5 methods; 1:1 `type`↔topic | add `MARKS("marks")` topic + 3 `publishMark*` port methods + registry override + TS union branch |
| web | `GeofenceStore` (poll), `[zones]` overlay on `live-map`/`fleet-map`, `geofence-logic` | `MarksStore` (GET + live), `[marks]` overlay, `mark-logic`, cockpit "Mark target" + bearing/distance readout |
| flags | `vision.persistence.enabled` (default false → in-memory), `vision.live.enabled` (default on) | reused as-is; **no new flag** — everything is additive |

Everything is **additive**: new domain types, a new table, new-file services/controllers/stores, and
three `default`-bodied port methods (so existing `LiveUpdatePublisherPort` implementors compile
unchanged). **No existing test changes; all stay green.**

---

## Frozen wire & type contracts

Everything below is frozen. All waves code against it and may parallelize. Names, JSON shapes, status
codes, and property names are pinned exactly.

### 1. Domain — `Mark` + values + `MarkRepositoryPort` (`vision-domain`)

Package `com.drones.vision.domain.model` (values) and `…domain.port.out` (port). Framework-free;
validation in compact ctors with manual `if (…) throw new IllegalArgumentException(…)`.

```java
public record Mark(MarkId id, GeoPosition position, MarkKind kind, String label, String note,
                   Ownership ownership, Instant createdAt, MarkStatus status, MarkSource source) {
    // compact ctor: id, position, kind, ownership, createdAt, status, source non-null;
    //               label non-blank; note nullable (blank → null).
    public UserId createdBy() { return ownership.ownerId(); }            // display + "creator can clear"
    public Mark withDetails(MarkKind kind, String label, String note, GeoPosition position); // identity + status kept
    public Mark withStatus(MarkStatus status);                          // ACTIVE ↔ CLEARED, identity kept
}

public record MarkId(UUID value) {            // mirror ZoneId exactly
    public static MarkId random();
    public static MarkId of(String);          // IllegalArgumentException on null / non-UUID
}

public enum MarkKind   { TARGET, HAZARD, POI, FRIENDLY }   // colour-by-kind (styling lives in web)
public enum MarkStatus { ACTIVE, CLEARED }
public enum MarkSource { MANUAL, DETECTION }
```

```java
package com.drones.vision.domain.port.out;   // mirror GeofenceRepositoryPort
public interface MarkRepositoryPort {
    Mark save(Mark mark);                     // upsert by id
    Optional<Mark> findById(MarkId id);
    List<Mark> findAll();                     // snapshot; scope filtering is the application's job
    void deleteById(MarkId id);               // idempotent
}
```

`GeoProjection` — **pure**, `public final class` with a private ctor and static methods (no interface;
it earns none — per the java-clean-code rule). Reuses `GeoPosition`; **`GroundPoint` == `GeoPosition`**
(reused, not invented).

```java
public final class GeoProjection {
    public static final double DEFAULT_DEPRESSION_DEGREES = 45.0;   // documented assumption; no gimbal data
    public static final double EARTH_RADIUS_METERS       = 6_371_000.0;
    private GeoProjection() {}

    /** Estimate the ground point the drone is looking at.
     *  slantGroundRange = altitudeMeters / tan(depression); project that range from `drone`
     *  along `headingDegrees` (spherical forward / destination-point). depression=90° ⇒ nadir
     *  (range 0 ⇒ point directly below). Returned position altitudeMeters = null (ground unknown).
     *  @throws IllegalArgumentException drone lat/lon absent; altitudeMeters < 0;
     *                                   depression not in (0, 90]. heading is normalised mod 360. */
    public static GeoPosition project(GeoPosition drone, double headingDegrees,
                                      double altitudeMeters, double depressionDegrees);

    /** Great-circle initial bearing (0..360°, clockwise from north) + haversine distance (m). */
    public static BearingDistance bearingDistance(GeoPosition from, GeoPosition to);
}

public record BearingDistance(double bearingDegrees, double distanceMeters) {}
```

`LiveUpdatePublisherPort` — **append three `default`-bodied no-op methods** (so `LiveUpdateRegistry`
and `NoopLiveUpdatePublisher` compile unchanged after M1; the registry overrides them in M4, the noop
inherits the no-op):

```java
default void publishMarkCreated(Mark mark) {}   // → "marks" topic, action "created"
default void publishMarkUpdated(Mark mark) {}   // → "marks" topic, action "updated"
default void publishMarkCleared(Mark mark) {}   // → "marks" topic, action "cleared" (clear or delete)
```

### 2. Application — `MarkService` + scope/ownership + geolocate + publish (`vision-application`)

Spring-annotation-free. Threads **actor + scope** the way `DefaultAssetService` does — `Ownership`
(who it belongs to) and `UserId actor` are **separate** params; scope filtering keys on **group**
(reusing the existing `VisibilityScope.includesGroup(GroupId)` — **no new `VisibilityScope` method**).

```java
public interface MarkService {
    /** Group-shared read: unbounded (admin) sees all; otherwise marks whose owning group is the
     *  viewer's group or within the manager's group subtree. */
    List<Mark> list(VisibilityScope scope, GroupId viewerGroup);

    /** MANUAL create (map click). ownership from currentUser; status ACTIVE. Publishes markCreated. */
    Mark create(MarkSpec spec, Ownership ownership, UserId actor);

    /** DETECTION create (cockpit geolocate). Reads UsageTracker.latestTelemetry(spec.assetId());
     *  projects via GeoProjection; status ACTIVE; source DETECTION. Publishes markCreated.
     *  @throws IllegalArgumentException no/insufficient telemetry (missing lat/lon/heading, alt ≤ 0). */
    Mark geolocate(GeolocateSpec spec, Ownership ownership, UserId actor);

    /** Annotate and/or clear. Any in-scope viewer may edit label/note/position/kind; a status→CLEARED
     *  transition requires creator-or-manager. Publishes markUpdated (annotate) or markCleared (clear). */
    Mark update(MarkId id, MarkPatch patch, UserId actor, VisibilityScope scope);

    /** Remove. Creator-or-manager only. Publishes markCleared. */
    void delete(MarkId id, UserId actor, VisibilityScope scope);
}

public record MarkSpec(MarkKind kind, String label, String note, GeoPosition position) {}
public record GeolocateSpec(AssetId assetId, MarkKind kind, String label, String note,
                            double depressionDegrees) {}   // depression defaulted at the DTO layer
public record MarkPatch(Optional<MarkKind> kind, Optional<String> label, Optional<String> note,
                        Optional<GeoPosition> position, Optional<MarkStatus> status) {}
```

- **`DefaultMarkService`** deps (`Objects.requireNonNull`): `(MarkRepositoryPort markRepository,
  UsageTracker usageTracker, LiveUpdatePublisherPort liveUpdatePublisher)`. Holds no mutable state.
- **`list`** mirrors `DefaultAssetService.assets(scope, …)`:
  `findAll().stream().filter(m -> scope.isUnbounded() || scope.includesGroup(m.ownership().groupId())
  || m.ownership().groupId().equals(viewerGroup)).sorted(by createdAt desc).toList()`.
- **`create`** → `new Mark(MarkId.random(), spec.position(), spec.kind(), spec.label(), spec.note(),
  ownership, Instant.now(), ACTIVE, MANUAL)`, `save`, `publishMarkCreated`, return saved.
- **`geolocate`** → `usageTracker.latestTelemetry(spec.assetId()).orElseThrow(IllegalArgumentException)`;
  require `latitude`, `longitude`, `headingDegrees` present and `altitudeMeters` present & > 0 (else
  `IllegalArgumentException "cannot geolocate: telemetry incomplete"`); `GeoProjection.project(new
  GeoPosition(lat, lon, alt), heading, alt, spec.depressionDegrees())` → ground `GeoPosition`; build
  `Mark(…, source = DETECTION)`, `save`, `publishMarkCreated`.
- **`update`** — scoped read: `require(id)` (private, `findById(...).orElseThrow(NoSuchElementException)`);
  hide out-of-group with `NoSuchElementException` (→ 404). Apply present patch fields via `withDetails`.
  If `patch.status()` is CLEARED (or ACTIVE re-open) → require creator (`mark.ownership().ownerId()
  .equals(actor)`) **or** manager (`scope.canManageOrg()`), else `AccessDeniedException` (→ 403);
  apply `withStatus`. `save`. Publish `markCleared` when the net status is CLEARED, else `markUpdated`.
- **`delete`** — `require(id)`; creator-or-manager gate (as above) else `AccessDeniedException`;
  `deleteById`; `publishMarkCleared(mark)` (clients drop the pin).

### 3. Persistence — entity + migration + in-memory (`adapter-persistence`, `vision-app`)

Mirror `GeofenceZoneEntity` / `JpaGeofenceRepository` / `InMemoryGeofenceRepository`, minus the jsonb
polygon (point columns instead), plus ownership/lifecycle columns.

`adapters/adapter-persistence/…/db/migration/V8__marks.sql` (next free version after `V7`):

```sql
CREATE TABLE marks (
    id                  UUID PRIMARY KEY,
    kind                VARCHAR(16)      NOT NULL,   -- MarkKind enum name
    label               VARCHAR(255)     NOT NULL,
    note                TEXT,
    latitude            DOUBLE PRECISION NOT NULL,
    longitude           DOUBLE PRECISION NOT NULL,
    altitude_meters     DOUBLE PRECISION,            -- nullable (ground point)
    owner_id            UUID             NOT NULL,   -- Ownership.ownerId  (== createdBy)
    group_id            UUID             NOT NULL,   -- Ownership.groupId  (scope filter key)
    created_at          TIMESTAMPTZ      NOT NULL,
    status              VARCHAR(16)      NOT NULL,   -- MarkStatus enum name
    source              VARCHAR(16)      NOT NULL    -- MarkSource enum name
);
CREATE INDEX idx_marks_group_id ON marks (group_id);
```

`MarkEntity` mirrors `GeofenceZoneEntity` (`@Entity @Table(name="marks")`, enums
`@Enumerated(EnumType.STRING) @Column(length=16)`, `owner_id`/`group_id` as `UUID`, protected no-arg
ctor + all-args ctor, accessor methods, private static `toEntity`/`toDomain`). `JpaMarkRepository`
mirrors `JpaGeofenceRepository` (EMF → `JpaOperations`, `merge`/`find`/JPQL `select m from MarkEntity
m`/find-then-remove). `InMemoryMarkRepository` mirrors `InMemoryGeofenceRepository`
(`ConcurrentHashMap<MarkId, Mark>`). `PersistenceWiringConfiguration.markRepositoryPort(properties,
ObjectProvider<EntityManagerFactory>)` = `if properties.enabled() → new JpaMarkRepository(emf) else new
InMemoryMarkRepository()` (default `enabled=false` ⇒ in-memory), exactly as `geofenceRepositoryPort`.

### 4. API — `MarksController` + DTOs (`vision-api`)

`@RestController` at `/api/marks`, ctor-injected `(MarkService markService, CurrentUser currentUser)`
— mirroring `AssetController`'s `currentUser` threading (not geofence's no-`CurrentUser` shape).

| Method | Path | Request body | Response | Status |
|---|---|---|---|---|
| GET | `/api/marks` | — | `List<MarkResponse>` (group-scoped) | 200 |
| POST | `/api/marks` | `CreateMarkRequest` | `MarkResponse` | **201** |
| POST | `/api/marks/geolocate` | `GeolocateMarkRequest` | `MarkResponse` | **201** |
| PATCH | `/api/marks/{id}` | `PatchMarkRequest` | `MarkResponse` | 200 |
| DELETE | `/api/marks/{id}` | — | void | **204** |

Threading: GET → `markService.list(currentUser.scope(), currentUser.ownership().groupId())`; POST →
`create(req.toSpec(), currentUser.ownership(), currentUser.userId())`; geolocate →
`geolocate(req.toSpec(), currentUser.ownership(), currentUser.userId())`; PATCH →
`update(MarkId.of(id), req.toPatch(), currentUser.userId(), currentUser.scope())`; DELETE →
`delete(MarkId.of(id), currentUser.userId(), currentUser.scope())`.

**Error contract** (no controller-side translation — flows through `vision-api/…/ApiExceptionHandler`):
`IllegalArgumentException` (bad UUID, unknown kind/status, incomplete telemetry) → **400**;
`NoSuchElementException` (unknown/out-of-group id) → **404**; `AccessDeniedException` (clear/delete not
creator-or-manager) → **403**.

DTOs (mirror `GeofenceZoneRequest`/`Response`; reuse existing `GeoPositionResponse`, `@JsonInclude
NON_NULL`; `kind`/`status`/`source` are **strings** on the wire, matched case-insensitively to the
enums, unknown → `IllegalArgumentException`):

```java
public record CreateMarkRequest(String kind, String label, String note, PointRequest position) {
    public MarkSpec toSpec();               // note blank → null
    public record PointRequest(double latitude, double longitude, Double altitudeMeters) {
        GeoPosition toPosition();
    }
}
public record GeolocateMarkRequest(String assetId, String kind, String label, String note,
                                   Double depressionDegrees) {   // absent ⇒ GeoProjection.DEFAULT_DEPRESSION_DEGREES
    public GeolocateSpec toSpec();
}
public record PatchMarkRequest(String kind, String label, String note,
                               PointRequest position, String status) {   // all optional; only present fields change
    public MarkPatch toPatch();             // absent field ⇒ Optional.empty()
}
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MarkResponse(String id, String kind, String label, String note,
                           GeoPositionResponse position, String createdBy, Instant createdAt,
                           String status, String source) {
    public static MarkResponse from(Mark mark);   // createdBy = mark.ownership().ownerId() UUID string
}
```

### 5. Live envelope — the frozen wire contract for the web store

**One always-on topic `MARKS("marks")`; the lifecycle rides in the payload** (mirrors
`detection-events`, honours the codebase's 1:1 `type`↔topic rule).

- Add `MARKS("marks")` to `vision-api/…/live/LiveTopicKind.java`.
- Add `static final LiveTopic MARKS = new LiveTopic(LiveTopicKind.MARKS, null)` to `LiveTopic.java`
  and a case to its `parse(...)` switch (exhaustive over the enum → compiler flags a miss).
- In `LiveUpdateRegistry`: a `marksBuffer` field; always-on subscribe in `connect(...)`
  (`topics.add(LiveTopic.MARKS)`); a `bufferFor(...)` case; a `seedIfEmpty(...)` case (seed from
  `ObjectProvider<MarkService>` snapshot — inject lazily to avoid the circular-dependency the registry
  already dodges for `AssetService`); override the three `publishMark*` methods to build the envelope
  and `broadcast(LiveTopic.MARKS, …)`.
- Add `MarkPayload` DTO: `record MarkPayload(String action, MarkResponse mark)` with `action ∈
  {"created","updated","cleared"}`.

Frozen envelope on the wire (`LiveEnvelopeResponse` shape, `assetId` null — marks aren't per-asset):

```json
{ "seq": 128, "type": "marks",
  "payload": { "action": "created",
    "mark": { "id": "<uuid>", "kind": "TARGET", "label": "Bunker", "note": null,
              "position": { "latitude": 50.45, "longitude": 30.52, "altitudeMeters": null },
              "createdBy": "<uuid>", "createdAt": "2026-07-31T10:00:00Z",
              "status": "ACTIVE", "source": "MANUAL" } } }
```

`action` values: `"created"` (create/geolocate), `"updated"` (annotate), `"cleared"` (status→CLEARED
or delete). For a delete the `mark` payload is the last-known state with `status:"CLEARED"`.

TS side (`vision-web/…/core/api/models.ts`): add a `Mark` interface + a `LiveEnvelope` union branch
`{ readonly seq: number; readonly type: 'marks'; readonly payload: { readonly action:
'created'|'updated'|'cleared'; readonly mark: Mark } }`. `live-store.ts`: a `marksSignal` + an
`applyEnvelope` `case 'marks'` — `created`/`updated` upsert by `mark.id`; `cleared` remove by id.

---

## Design decisions (with rationale)

**A. Mirror geofence for shape, mirror asset for ownership.** Geofence gives a known-good CRUD +
store + map-overlay spine with zero scope machinery; asset gives the proven `Ownership` + scope + audit
threading. Marks need both, so each dimension mirrors the file that actually has it. This is cheaper
than it looks — the geo type (`GeoPosition`), the response mapper pattern (`from(domain)`), the opt-in
persistence wiring, the `[zones]`→`[marks]` map effect, and `VisibilityScope.includesGroup` all already
exist and are reused verbatim.

**B. Group-scoped visibility, not per-`UserId`.** `VisibilityScope` keys on **group** (MANAGER) or
**assetId** (PILOT), never on owner `UserId` — there is no `includes(UserId)`. A pilot's scope carries
no group, so filtering by the requested `createdBy: UserId` alone is not expressible. The command point
= a group, so v1 filters marks by **owning group**: `scope.isUnbounded()` (admin) sees all; otherwise a
mark is visible if its `group_id` is the viewer's own group or within the manager's group subtree
(`includesGroup`). This is the honest, safe reading of "operational picture common to everyone in
scope," and it reuses existing scope primitives with **no new `VisibilityScope` method**.

**C. Live lifecycle in the payload, not three topic kinds.** The registry enforces
`type == LiveTopicKind.wire()` 1:1; `detection-events` already carries OPEN/CLOSED lifecycle inside one
topic. Three `mark.*` types would triple the buffers/topics for one concept and break that convention.
One `marks` topic + `action` field keeps the three logical events the user wants while fitting the
wire's grain. (Three-topic alternative noted in Open Questions.)

**D. Geolocation is an honest estimate, corrected on the map.** No gimbal orientation or camera
intrinsics are available — only position, heading, altitude. `GeoProjection` documents its assumed
depression angle, marks the result `source = DETECTION`, and the map makes it draggable. We surface
"estimated — drag to correct," never a false precision.

**E. Guardrails leave existing behaviour unchanged.** Every change is additive: new domain types, a new
`V8` table behind the existing `vision.persistence.enabled` (default in-memory), new-file
services/controllers/stores, a new always-on live topic behind the existing `vision.live.enabled`, and
three `default`-bodied port methods so no current `LiveUpdatePublisherPort` implementor changes. **No
new global flag, no edit to an existing test.** Marks never autostart; they exist only when a user drops
one.

**F. Clear is gated one notch above geofence.** Geofence lets anyone delete; the operational picture
wants light protection so one operator doesn't wipe another's target. Annotation (label/note/position/
kind) stays open to any in-scope viewer (collaborative), but `status→CLEARED` and DELETE require
**creator or manager** (`scope.canManageOrg()`), mapped to 403 via `AccessDeniedException`. This is a
deliberate, small divergence from geofence's no-gate model, flagged in Roles.

---

## Roles

| Action | Who | Enforced |
|---|---|---|
| View the shared marks picture | any in-scope user (admin / manager / pilot at the command point) | `MarkService.list` group filter; live topic (deployment-wide, see Open Q4) |
| Create (map click) / geolocate (cockpit) | any in-scope user | ownership set from `currentUser.ownership()` |
| Annotate (label / note / position / kind, incl. drag-to-correct) | any in-scope user | scoped read only (404 out-of-group) |
| Clear (status → CLEARED) | **creator or manager** | `mark.ownership().ownerId()==actor \|\| scope.canManageOrg()` → else 403 |
| Delete | **creator or manager** | same gate |

Note: geofence itself is scope-only with **no** role gate on any mutation; marks add the creator-or-
manager gate on clear/delete only (design decision F). No command-TX anywhere — nothing here is
capability-gated the way `flight-command-panel` / manual-control is.

---

## Implementation waves (disjoint file scopes)

Each wave ends **independently green** with its scoped `-pl`/`npm` build and its `MODULE.md` updated.
Sequencing: **M1 → (M2 ‖ M3) → M4 → M5**. The frozen contracts above are the sole coupling; M2
(persistence) and M3 (application) both depend only on M1 and run in parallel. M4 needs M2 (repo bean)
+ M3 (service). M5 needs M4 (the frozen REST + live shapes).

### M1 — domain: `Mark` + values + port + `GeoProjection` — `vision-domain/**` (agent: domain-modeler)
- Add `Mark`, `MarkId`, `MarkKind`, `MarkStatus`, `MarkSource`, `MarkRepositoryPort`, `GeoProjection`,
  `BearingDistance` per §1. Append the three `default` no-op `publishMark*` methods to
  `LiveUpdatePublisherPort` (keeps api/app implementors compiling — verify vision-domain compiles and
  no downstream signature breaks).
- Reuse `GeoPosition`, `Ownership`, `UserId` — invent no new geo/owner type.
- **Tests (the heart of this wave — `GeoProjection` is pure and must be exhaustively covered):**
  `project` — nadir (depression 90° ⇒ ground point == drone lat/lon); heading 0/90/180/270 lands
  N/E/S/W of the drone at the expected ground range; ground range = `alt/tan(depression)` within
  tolerance; a known (lat, lon, heading, alt, depression) → known destination (golden value);
  antimeridian / high-latitude wrap sanity; validation throws on missing lat/lon, negative altitude,
  depression ≤ 0 or > 90. `bearingDistance` — zero distance for identical points; symmetric distance;
  bearing 0..360 clockwise-from-north for the four cardinals; haversine distance vs a known baseline.
  `Mark`/`MarkId` validation mirrors `GeofenceZone`/`ZoneId` tests.
- Verify: `./mvnw -B -pl vision-domain test` green; `vision-domain/MODULE.md` updated (new model + port
  + the pure projection helper, "estimate — correct on map" note, depression default).

### M2 — persistence: JPA + migration + in-memory + wiring — `adapters/adapter-persistence/**`, `vision-app/**` (agent: spring-integrator). Depends M1.
- `MarkEntity` + `JpaMarkRepository` (adapter-persistence) + `V8__marks.sql` per §3, mirroring the
  geofence trio. `InMemoryMarkRepository` (vision-app devsupport) mirroring `InMemoryGeofenceRepository`.
  `PersistenceWiringConfiguration.markRepositoryPort(...)` if/else bean.
- Tests: JPA round-trip (save/find/findAll/delete, enum-as-string, nullable note/altitude) in the
  module's existing Postgres-integration style (docker-gated, skips without docker); in-memory repo unit
  test. Keep ArchUnit green (adapter imports no other adapter).
- Verify: `./mvnw -B -pl adapters/adapter-persistence test` and `-pl vision-app test` green; both
  `MODULE.md`s updated (new entity/repo/migration `V8`, in-memory devsupport repo, opt-in wiring).

### M3 — application: `MarkService` + geolocate + live-publish — `vision-application/**` (agent: application-service). Depends M1.
- `MarkService` + `MarkSpec`/`GeolocateSpec`/`MarkPatch` + `DefaultMarkService` per §2. Reuse
  `UsageTracker.latestTelemetry` for geolocate, `GeoProjection` for the math, `VisibilityScope.
  includesGroup` for the filter, `publishMark*` (no-op until M4) for the broadcast.
- Tests (hand-fake `MarkRepositoryPort`, hand-fake/real `UsageTracker`, capturing fake
  `LiveUpdatePublisherPort`): `create` sets ownership + ACTIVE + MANUAL and publishes created;
  `geolocate` reads latest telemetry, projects, sets DETECTION, publishes created; geolocate with
  missing/incomplete telemetry → `IllegalArgumentException`; `list` group filter (unbounded sees all;
  manager subtree; own-group; other-group hidden); `update` annotate publishes updated; `update`
  status→CLEARED by non-creator non-manager → `AccessDeniedException`, by creator/manager → CLEARED +
  publishes cleared; out-of-group id → `NoSuchElementException`; `delete` gate + publishes cleared.
- Verify: `./mvnw -B -pl vision-application test` green; `vision-application/MODULE.md` updated
  (new service, geolocate use-case, group-scope filter, live-publish points).

### M4 — api: `MarksController` + DTOs + live-envelope extension + wiring — `vision-api/**`, `vision-app/**` (agent: spring-integrator). Depends M2 + M3.
- **vision-api**: `MarksController` + `CreateMarkRequest`/`GeolocateMarkRequest`/`PatchMarkRequest`/
  `MarkResponse` + `MarkPayload` per §4/§5. Extend the live stack: `LiveTopicKind.MARKS`,
  `LiveTopic.MARKS` + `parse` case, `LiveUpdateRegistry` (buffer field, `connect` always-on add,
  `bufferFor`/`seedIfEmpty` cases, override the three `publishMark*` to broadcast, lazy
  `ObjectProvider<MarkService>` for the seed), and the `LiveEnvelopeResponse` javadoc `type` list.
- **vision-app**: `@Bean MarkService markService(MarkRepositoryPort, UsageTracker,
  LiveUpdatePublisherPort)` in `WiringConfiguration`. `NoopLiveUpdatePublisher` needs no edit (inherits
  the `default` no-ops). Keep ArchUnit green (domain/application Spring-annotation-free; only app depends
  on adapters).
- Tests: `MarksController` slice/integration for all five endpoints incl. error contract (400/403/404),
  a geolocate happy-path (fake telemetry) + incomplete-telemetry 400, and a live-registry test asserting
  a `publishMarkCreated` produces a `marks` envelope with `action:"created"` on the SSE stream. Wiring
  smoke test.
- Verify: `./mvnw -B -pl vision-api test` and `-pl vision-app test` green; both `MODULE.md`s updated
  (new endpoints, the `marks` live topic + payload, wiring).

### M5 — web: `MarksStore` + `[marks]` overlay + cockpit geolocate + bearing/distance — `vision-web/**` (agent: web-ui). Depends M4.
- **`core/marks/marks-store.ts`** (NEW) — `@Injectable({providedIn:'root'})` `MarksStore`: initial
  `GET /api/marks` into a `marksSignal`; `inject(LiveStore)` and merge `liveStore.marks` deltas
  (created/updated upsert, cleared remove); keep a slow poll as a safety net (mirror `GeofenceStore`'s
  `PollScheduler`). Methods `create`, `geolocate(assetId)`, `annotate`, `moveTo(id, position)` (drag),
  `clear`, `remove`. Add `listMarks`/`createMark`/`geolocateMark`/`patchMark`/`deleteMark` to
  `core/api/vision-api.ts` and `Mark`/`MarkKind`/request types + the `LiveEnvelope` `marks` branch to
  `core/api/models.ts`.
- **`core/marks/mark-logic.ts`** (NEW) — framework-free: `markStyle(kind)` colour map
  (TARGET / HAZARD / POI / FRIENDLY → the tactical palette, mirroring `zoneLayerStyle`), kind labels,
  and a `bearingDistance(from, to)` **mirroring `GeoProjection.bearingDistance`** (exactly as
  `geofence-logic.polygonContains` mirrors the domain) for the selected-mark readout.
- **`core/live/live-store.ts`** — `marksSignal` + `applyEnvelope` `case 'marks'` (MINIMAL edit;
  survey the live-store shape at M5 time — see Concurrency caution).
- **`shared/map/live-map/live-map.ts` + `shared/map/fleet-map/fleet-map.ts`** — add
  `marks = input<readonly Mark[]>([])` + an `applyMarks` effect mirroring `applyZones`, rendering
  `L.circleMarker` coloured by `markStyle(kind)`; interactive: click → `selected` output; draggable →
  a `moved` output carrying id + new position (drag-to-correct). MINIMAL edits alongside the existing
  `[zones]` layer.
- **`features/fly` cockpit** — a "**Mark target**" action → `marksStore.geolocate(currentAssetId)`; a
  map-click "drop mark" affordance → `marksStore.create(...)`; a selected-mark **bearing + distance**
  readout (from the drone via live telemetry; from home via the drone's home/launch position if
  available — see Open Q3); colour-by-kind legend; edit label/note/kind + drag-to-correct + clear.
  Honest copy: "geolocated marks are estimates — drag to correct."
- Tests: vitest for `mark-logic` (`markStyle`, `bearingDistance` vs golden values), `MarksStore`
  (GET + live-delta merge, create/geolocate/clear), and the map `applyMarks` diff. `tsc` clean;
  production build green.
- Verify: `npm run test:ci` + build green; `vision-web/MODULE.md` updated.

---

## Concurrency caution

A **separate session is actively refactoring `vision-web`** (UI-ARCHITECTURE: facade / `UiStore`
sweep — see recent commits `a3b6722`, `665bb4a`). **Backend waves M1–M4 are unaffected** — that work is
web-only. **M5 MUST be collision-aware:**
- Prefer **NEW files** — `core/marks/marks-store.ts`, `core/marks/mark-logic.ts` — over edits.
- Keep edits to shared components **minimal and additive**: one `input()` + one `effect`/`applyMarks`
  on `live-map.ts` and `fleet-map.ts`, alongside the existing `[zones]` layer; one signal + one
  `case 'marks'` in `live-store.ts`.
- **Re-survey the current `live-store` / map component / facade shape at M5 start** and adapt to
  whatever the refactor landed (facade vs direct store injection, `UiStore` placement) rather than
  assuming today's shape. Rebase M5 onto `master` immediately before starting.

---

## Non-goals / deferred (named, not dropped)

- **Precise gimbal / camera-intrinsic geolocation.** v1 is an honest heading + assumed-depression
  estimate, corrected by drag. Real projection (gimbal orientation, FOV, terrain DEM intersection) is
  deferred.
- **Mark chat / threads / comments.** A mark carries a single `note`; no discussion.
- **Mark assignment / tasking.** Assigning a mark to an operator ("strike this target") is the **next**
  tactical slice — manager tasking builds on this spine. Not in v1.
- **Mark history / audit beyond `createdBy` + `createdAt`.** No edit trail, no per-field provenance,
  no `AuditTrailPort` integration (geofence has none either).
- **Clustering / decluttering at scale.** No server-side spatial index or client clustering; v1 renders
  every mark. Fine for a command point; revisit at hundreds of marks.
- **Per-group live filtering on the SSE channel.** See Open Q4 — v1's live `marks` topic is
  deployment-wide (matching fleet/events today); per-group live scoping is deferred.
- **Optional per-detection "view point".** The geolocate endpoint projects straight ahead along
  heading; a normalized image-space view point for off-centre detections is deferred.

## Open questions / to confirm during implementation

1. **Depression-angle default + exposure.** `GeoProjection.DEFAULT_DEPRESSION_DEGREES = 45°` is a
   documented guess. Confirm the number and whether to expose it as a cockpit control (`GeolocateMarkRequest.
   depressionDegrees` already carries it) or keep it fixed in v1. Default: fixed 45°, not exposed.
2. **Marks org-wide vs group subtree.** v1 filters by owning **group** (viewer's group ∪ manager
   subtree via `includesGroup`), matching how assets scope. Confirm this matches the intended "command
   point" boundary during M3/M4 (a manager whose subtree spans several groups sees all their marks; a
   single-group deployment is unaffected).
3. **"From home" reference point.** Bearing/distance-from-drone uses live telemetry position (definitely
   available). "From home" needs a home/launch position — confirm whether one is modelled (first-fix /
   takeoff position) or defer the from-home readout if not. Default: show from-drone always; from-home
   only when a home position exists.
4. **Live channel scope leak.** The `marks` SSE topic broadcasts deployment-wide (like `fleet` /
   `event` today), while the REST GET is group-filtered. In a multi-group deployment, live deltas could
   surface cross-group. Acceptable for the single-command-point model; if multi-tenant isolation is
   required on the live channel, a per-group topic key is a follow-up (deferred).
5. **Three `mark.*` topics vs one `marks` topic.** v1 uses one topic + `action` payload (idiomatic,
   §5/decision C). Revisit only if a consumer genuinely needs independent topic subscription per
   lifecycle action.
