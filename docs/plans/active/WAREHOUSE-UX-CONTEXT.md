# WAREHOUSE-UX — working context

Opened 2026-08-29 · Branch `feat/warehouse-ux` (cut from master `59b879a5`) · Spec: [WAREHOUSE-UX-PLAN.md](WAREHOUSE-UX-PLAN.md)

## Decisions taken at start (defaults for §6 open questions, owner may override)

| OQ | Taken |
|---|---|
| OQ1 | `MAINTENANCE` **blocks**: readiness NO-GO with the record's summary; `engage` refuses. Manager releases in one click. |
| OQ2 | Custody per person (`UserId`). |
| OQ3 | Batteries/equipment first-class now; cycles as `attributes.cycles`. |
| OQ4 | `/assets` stays the canonical URL for W4 (rename deferred — 640 citations & bookmarks); `/devices`, `/manage/categories`, `/manage/reports` redirect into its tabs. |
| OQ5 | W1 ships first as pure IA. |

## Wave ledger

| Wave | Agent | State | Commit |
|---|---|---|---|
| W1 rail | web-ui | done | `3b8a9649` |
| W2 domain | domain-modeler | done | `47f7eb99` |
| W3 persistence + API | spring-integrator | done | `5a712e71` |
| W4 inventory page | web-ui | ready (W1 + W3 both done) | |
| W5 readiness ← maintenance | application-service | done | `445e145b` |
| W6 wizard | web-ui | done | `eafb807e` |
| W7 maintenance + crew | web-ui | done | `cae23506` |

Shared tree: agents commit **by path**, never stash. Unrelated dirty files (`infra/rover-sim/**`, `core/rc/manual-control-client*`, `DefaultPeerDirectory.java`) belong to another session — do not touch.

## W1 notes for W4 (inventory page)

- Asset categories, Inventory reports, and Devices — no §3.1 group names a home for these three — landed in **FLEET** alongside the renamed Inventory/Add vehicle/Crew, since W1 is pure IA (OQ5) and none of the three is a stub. They're still separate nav entries/routes today (`/manage/categories`, `/manage/reports`, `/devices`); when W4's merged Inventory page ships its own tabs (OQ4), drop the three now-redundant `nav-entries.ts` FLEET entries in the same commit rather than leaving dead rail links alongside the new tabs.
- Manager entry count is **20**, not §3.1's own illustrative "15" — that figure already assumes W4's tab merge + W7's Maintenance entry, neither of which exists yet. Pilot count matches the plan's "10" exactly. Full reconciliation in `station/vision-web/MODULE.md`'s W1 changelog entry and `nav-entries.spec.ts`'s own count test.
- `/devices`, `/manage/categories`, `/manage/reports` all gained `canActivate: [orgGuard]` this wave (they didn't have it before) — W4's OQ4 redirect-into-tabs plan should keep that gate on whatever route ends up serving that content.

## W2 → W3 handoff (done — kept for the record; W3 built directly on this)

W2's domain + application changes to `contexts/vision-warehouse` landed in `47f7eb99`
(`./mvnw -B -pl contexts/vision-warehouse test` — 315 tests, 0 failures at the time). The handoff
below is left as originally written (including "must persist"/"41 files" framing) since it is the
spec W3 actually built against; see "W3 → W4/W6/W7 handoff" further down for what shipped.

### What W3 must persist (D7, `V28__asset_inventory.sql`)

- `assets` += `serial_number, make, model, registration` (from `Identity`), `custodian_id,
  location, custody_since` (from `Custody`), `inventory_state` (stored values only —
  `IN_STOCK`/`MAINTENANCE`/`RETIRED`, default `IN_STOCK`), `created_at` (default `now()`),
  `updated_at`.
- `categories` += `connected boolean default true`.
- New tables: `maintenance_records` (mirrors `MaintenanceRecord`'s 8 fields), `asset_notes`
  (mirrors `AssetNote`'s 5 fields).
- `asset_usages` += `pilot_id` — not part of W2's own model change, but bundled into the same D7
  migration task per the plan; unrelated to anything this wave touched in `AssetUsage`.

### Out-of-module compile breaks (41 files, none touched by W2 — grep re-run at handoff time)

`Asset`'s canonical constructor grew from 7 to 12 components (`Asset.register(...)` is the new
"asset with defaults" factory; the old 6-arg convenience ctor is gone), `AssetSpec` grew from 5 to 7
(canonical) with only its existing 4-arg convenience ctor kept, `AssetEdit` grew from 3 to 4,
`AssetSummary` grew from 5 to 8, and `DeviceCategory` grew from 4 to 5 (`connected`). Every call
site below needs updating in W3; none are safe to leave as "will fix later" since they fail
`test-compile`/`compile` in their own module today.

**Main-source (non-test), fails a real build, not just tests:**
- `contexts/vision-simulation/src/main/java/com/drones/vision/simulation/application/DefaultSimulationService.java`
- `station/vision-api/src/main/java/com/drones/vision/api/dto/CreateAssetRequest.java`
- `station/vision-api/src/main/java/com/drones/vision/api/dto/UpdateAssetRequest.java`
- `storage/persistence/src/main/java/com/drones/vision/adapter/persistence/mapper/AssetMapper.java`
- `storage/persistence/src/main/java/com/drones/vision/adapter/persistence/mapper/CategoryMapper.java`

**Test sources (fail `test-compile` in their own module):**
- `contexts/vision-flight/src/test/java/com/drones/vision/flight/application/DefaultFlightCommandServiceTest.java`
- `contexts/vision-flight/src/test/java/com/drones/vision/flight/application/DefaultManualControlServiceTest.java`
- `contexts/vision-flight/src/test/java/com/drones/vision/flight/application/DefaultReadinessServiceTest.java`
- `contexts/vision-flight/src/test/java/com/drones/vision/flight/application/DefaultRemediationServiceTest.java`
- `contexts/vision-flight/src/test/java/com/drones/vision/flight/application/DefaultVehicleProfileServiceTest.java`
- `contexts/vision-identity/src/test/java/com/drones/vision/identity/application/DefaultAssignmentServiceTest.java`
- `contexts/vision-learning/src/test/java/com/drones/vision/learning/application/DefaultLabelingServiceTest.java`
- `contexts/vision-perception/src/test/java/com/drones/vision/perception/application/pipeline/UsageTrackerTest.java`
- `contexts/vision-perception/src/test/java/com/drones/vision/perception/application/stream/DefaultAssetStreamServiceTest.java`
- `contexts/vision-simulation/src/test/java/com/drones/vision/simulation/application/DefaultSimulationServiceTest.java`
- `station/vision-api/src/test/java/com/drones/vision/api/controller/AssetControllerTest.java`
- `station/vision-api/src/test/java/com/drones/vision/api/controller/AssetImageControllerTest.java`
- `station/vision-api/src/test/java/com/drones/vision/api/controller/AssetStreamControllerTest.java`
- `station/vision-api/src/test/java/com/drones/vision/api/controller/CameraPoseControllerTest.java`
- `station/vision-api/src/test/java/com/drones/vision/api/controller/CategoryControllerTest.java`
- `station/vision-api/src/test/java/com/drones/vision/api/controller/DeviceControllerTest.java`
- `station/vision-api/src/test/java/com/drones/vision/api/controller/EventControllerTest.java`
- `station/vision-api/src/test/java/com/drones/vision/api/controller/GeoCorrectionControllerTest.java`
- `station/vision-api/src/test/java/com/drones/vision/api/controller/LiveAssetScopingTest.java`
- `station/vision-api/src/test/java/com/drones/vision/api/controller/LiveControllerTest.java`
- `station/vision-api/src/test/java/com/drones/vision/api/controller/StreamControllerTest.java`
- `station/vision-api/src/test/java/com/drones/vision/api/demo/DemoFleetTest.java`
- `station/vision-api/src/test/java/com/drones/vision/api/dto/OnboardingWireContractTest.java`
- `station/vision-api/src/test/java/com/drones/vision/api/live/LiveUpdateRegistryTest.java`
- `station/vision-api/src/test/java/com/drones/vision/api/proxy/HlsProxyControllerTest.java`
- `station/vision-api/src/test/java/com/drones/vision/api/support/afteraction/AfterActionAssemblerTest.java`
- `station/vision-app/src/test/java/com/drones/vision/app/AssetParameterFlagGatingTest.java`
- `station/vision-app/src/test/java/com/drones/vision/app/CvDetectionE2ETest.java`
- `station/vision-app/src/test/java/com/drones/vision/app/CvDetectionEndpointE2ETest.java`
- `station/vision-app/src/test/java/com/drones/vision/app/CvDetectionResilienceSmokeTest.java`
- `station/vision-app/src/test/java/com/drones/vision/app/geo/TrackProjectionRunnerTest.java`
- `station/vision-app/src/test/java/com/drones/vision/app/geo/VisualGeoRunnerTest.java`
- `station/vision-app/src/test/java/com/drones/vision/app/ScopedAssetReadAuthEnabledTest.java`
- `station/vision-app/src/test/java/com/drones/vision/app/SimStreamSmokeTest.java`
- `station/vision-app/src/test/java/com/drones/vision/app/TrackingAssociateE2ETest.java`
- `storage/persistence/src/test/java/com/drones/vision/adapter/persistence/PostgresDockerIntegrationTest.java`

### Design deviations from the plan text (with reasons)

- **`MaintenanceQuery` filed in `application.maintenance`, not `domain.port`** — it is a
  cross-context read contract this module *implements* (for vision-flight), not a driven port this
  module calls outward; mirrors `UsageSessionService`'s own placement. See `MODULE.md` Gotchas.
- **`AssetCustodyService`/`MaintenanceService` authorization pattern sourced from vision-flight's
  `DefaultVehicleProfileService#probe`, not `AssetService#setState`** — the plan's "find it" pointed
  at `setState`, but that method does not itself call `canManage` (the check lives one layer up in
  `vision-api`'s `AssetController`). `DefaultVehicleProfileService` is the real in-application-layer
  precedent for `VisibilityScope`-as-parameter + `canManage`/`includes` + audit + throw.
- **`Asset`'s old 6-arg convenience constructor was removed, not extended** — a 12-component
  canonical record makes a 7th convenience ctor unreadable; `Asset.register(...)` replaces it as a
  named factory. Every in-module call site was updated in the same change (CLAUDE.md rule 10 / java-
  clean-code §3: update call sites, don't add another overload layer). Out-of-module callers are
  the 41-file list above.
- **`AssetCustodyService#issue`'s guard checks `custody().custodianId()==null` in addition to
  `inventoryState()==IN_STOCK`** — not spelled out explicitly in the plan's state diagram, but
  required to actually enforce it: an issued asset's *stored* state is still `IN_STOCK` (only
  `custody` carries the "who has it" fact), so a stored-state-only guard would silently let a
  second `issue()` overwrite an existing custodian. Caught by a unit test during W2.
- **`updatedAt` stamping extended beyond the custody/maintenance verbs into `DefaultAssetService`'s
  pre-existing mutators** (`setState`, `delete`, `assignDevice`, `unassignDevice`, in addition to
  `update`) — since `Asset` now carries `updatedAt`, leaving it stale on those verbs would have made
  the field actively misleading rather than merely absent.

## W5 handoff (readiness ← maintenance, D6/OQ1 default) — done, see below for the wiring W3 owed it

`contexts/vision-flight`'s changes landed in `445e145b`
(`./mvnw -B -pl contexts/vision-flight test` — **379 tests, 0 failures** at the time). The "wiring
change vision-app/W3 must still make" section below is left as originally written for the record;
W3 made exactly that change (`OnboardingWiringConfiguration#readinessService` now takes the 4th
`MaintenanceQuery` parameter) — see "W3 → W4/W6/W7 handoff" further down.

### What changed

- `DefaultReadinessService` gained a new required 4th constructor parameter,
  `com.drones.vision.warehouse.application.maintenance.MaintenanceQuery maintenanceQuery` (no
  default — every call site must now pass it; the public ctor is
  `DefaultReadinessService(AssetService, VehicleProfileRepositoryPort,
  FeatureRequirementRepositoryPort, MaintenanceQuery)`, plus a package-private 5-arg test seam adding
  `Supplier<Instant> clock`).
- `evaluate(AssetId, VisibilityScope)` now forces `ReadinessVerdict.NO_GO` — **overriding even
  `UNKNOWN`** — whenever `maintenanceQuery.openBlockers(assetId)` yields at least one record that is
  both `MaintenanceRecord#isOpen()` and `MaintenanceKind#blocksFlight()` (true only for
  `GROUNDING`/`INSPECTION_DUE`; `REPAIR`/`NOTE` never block, and are filtered defensively even though
  the port's own contract already promises "open, blocking only"). Each surviving record contributes
  exactly one entry to `ReadinessReport#blockers()`.
- **The exact wire shape**, as it appears verbatim in `GET /api/assets/{id}/readiness`'s JSON body
  (`ReadinessReportResponse`, unchanged shape — no new field): a `blockers` array entry of the form
  ```
  "MAINTENANCE_GROUNDED:<KIND>:<summary>"
  ```
  e.g. `"MAINTENANCE_GROUNDED:GROUNDING:Propeller crack found on preflight"`. The prefix is the public
  constant `DefaultReadinessService.MAINTENANCE_BLOCKER_PREFIX = "MAINTENANCE_GROUNDED:"`. This rides
  the pre-existing `blockers: List<String>` field — deliberately **not** a new `FeatureReadiness` row,
  since `featureKey` is validated against the frozen `FeatureRequirement.FEATURE_KEYS` eleven-key set
  and a maintenance record isn't one of those keys. `verdict` on the same response, and the fleet
  board's `GET /api/fleet/readiness` (`ReadinessRowResponse`, which carries `verdict` but not
  `blockers`), both already surface the resulting `NO_GO` correctly with no DTO change either. **No
  `vision-api` or `station/vision-web` change is needed for this to render** —
  `readiness-logic.ts#featureLabel`'s existing fallback (`FEATURE_LABELS[key] ?? key`) already renders
  an unrecognized string verbatim rather than blank.
- `DefaultManualControlService#engage` now also refuses a grounded asset: right after the scope gate,
  it calls `readinessService.evaluate(assetId, scope)` once and reuses that single `ReadinessReport`
  for two checks — the new `requireNotMaintenanceGrounded` (checked first; audits
  `REFUSED:maintenance-grounded`, throws `IllegalStateException` naming every blocker's summary) and
  the pre-existing FLEET-RADIO R6 `requireRcRelayReady` (refactored to take the already-fetched report
  instead of calling `evaluate` a second time internally — net zero change in `AssetService#details`
  call count per `engage`). This is OQ1's "`engage` refuses" half.

### What this wave deliberately did NOT gate (flagged, not built)

- **`DefaultFlightCommandService#arm`/`disarm`** (the MAVLink command path) — a different service,
  not named by WAREHOUSE-UX-PLAN.md §4's own W5 row (`contexts/vision-flight/**readiness**`), and
  arguably a *harder* case (an already-armed/airborne vehicle grounded mid-flight should not be
  force-disarmed by a maintenance record landing at the wrong moment) that deserves its own design
  pass, not a drive-by addition here.
- **Perception's `UsageTracker`** (opens the underlying `AssetUsage` session) — lives in
  `contexts/vision-perception`, a different context module, out of this agent's file scope entirely.
  If OQ1's "engage refuses" is meant to also mean "cannot even open a flight session on a grounded
  asset", the hook belongs on `UsageTracker`'s own session-open path (perception depends on warehouse
  directly, so it could call `MaintenanceQuery` itself) — not routed through `vision-flight`.

### Wiring change `vision-app`/W3 must still make (does not compile without it)

`station/vision-app/src/main/java/com/drones/vision/app/config/wiring/OnboardingWiringConfiguration.java`,
the `readinessService(...)` `@Bean` factory (currently ~lines 110-138), still calls
`DefaultReadinessService`'s old 3-arg constructor:

```java
@Bean
public ReadinessService readinessService(AssetService assetService,
                                          VehicleProfileRepositoryPort vehicleProfileRepositoryPort,
                                          FeatureRequirementRepositoryPort featureRequirementRepositoryPort) {
    return new DefaultReadinessService(assetService, vehicleProfileRepositoryPort,
            featureRequirementRepositoryPort);
}
```

This needs a 4th `MaintenanceQuery maintenanceQuery` parameter, threaded into the `new
DefaultReadinessService(...)` call last. **This cannot be fixed yet**: as of this handoff, no
`MaintenanceQuery`/`MaintenanceService` Spring `@Bean` and no `MaintenanceRepositoryPort` JPA
implementation exist anywhere in `vision-app`/`storage/persistence` (confirmed by grep at handoff
time) — W3's own D7 migration (`V28__asset_inventory.sql`, `maintenance_records` table, see the W2 →
W3 handoff above) has to land first. So this is a **two-part** follow-up for W3, not the one-line fix
FLEET-RADIO R6 left behind in the same file: (1) add the persistence-backed `MaintenanceQuery`
implementation + its `@Bean`, (2) add the 4th parameter to `readinessService(...)` and pass it
through. Until both land, `vision-app` will not compile with `vision-flight`'s change picked up —
same "blocked on a sibling module's bean" situation this file already documents for other call sites.

## W3 → W4/W6/W7 handoff (persistence + API, D1–D8)

W3's changes are complete and green — `./mvnw -B -pl contexts/vision-warehouse,contexts/vision-identity,
contexts/vision-flight,contexts/vision-perception,contexts/vision-learning,contexts/vision-simulation,
storage/persistence,station/vision-api,station/vision-app -DskipWeb test` — **all 9 modules BUILD
SUCCESS, 0 failures/errors** (per-module counts in the "Build proof" table below). Committed at
`5a712e71`. This section gives W4/W6/W7 the exact wire shapes so they can build
against the contract without re-reading `station/vision-api` source.

### Build proof (before → after this wave)

| Module | Before | After |
|---|---|---|
| vision-warehouse | 315 (W2's own count) | 319 |
| vision-identity | — | 93 |
| vision-flight | 379 (W5's own count) | 379 |
| vision-perception | — | 571 |
| vision-learning | — | 160 |
| vision-simulation | — | 72 |
| storage/persistence | 224 (MODULE.md's last-measured figure) | 225 |
| vision-api | — | 893 |
| vision-app | — | 277 |

"Before" is left blank where no baseline was recorded for this exact module list before this wave
(only vision-warehouse/vision-flight had one, from W2/W5's own reports); every module's "after" count
is BUILD SUCCESS with 0 failures, 0 errors. `vision-app`'s `ArchitectureTest`/`ContextArchitectureTest`
(the dependency-rule ArchUnit suites) and `EndpointAuthorizationTest`/`OnboardingWiringTest` all stay
green with the new wiring. Docker was available and used — `storage/persistence`'s
`PostgresDockerIntegrationTest` (Testcontainers `postgres:16`) ran for real, not skipped.

### New/changed DTOs (`station/vision-api/.../dto`)

`IdentityResponse` — embedded in `AssetSummaryResponse`/`AssetDetailsResponse` as `identity`, always
present as an object, individual fields omitted (not `null`) when unknown:
```json
{"serialNumber": "SN-1234", "make": "DJI", "model": "Mavic 3", "registration": "FA3-1234-ABCD"}
```

`CustodyResponse` — embedded as `custody`, always present, `custodianId`/`since` omitted when in stock:
```json
{"custodianId": "3db9ba6d-...", "location": "Hangar B", "since": "2026-08-29T12:00:00Z"}
```
or, in stock: `{}`

`IdentityRequest` — shared shape on `CreateAssetRequest`/`UpdateAssetRequest`; any field left `null` is
unknown, and a present `identity` object always replaces the asset's identity wholesale (not a
per-field patch):
```json
{"serialNumber": "SN-1234", "make": "DJI", "model": "Mavic 3", "registration": "FA3-1234-ABCD"}
```

`CreateAssetRequest.CustodySpec` — optional `custody` field on `POST /api/assets`, issues straight to a
pilot instead of receiving into stock; `custodianId` absent/blank means `Custody.NONE`:
```json
{"custodianId": "3db9ba6d-...", "location": "Hangar B"}
```
Full `CreateAssetRequest` now also accepts top-level `identity` (an `IdentityRequest`, optional).

`UpdateAssetRequest` gained the same optional `identity` field (an `IdentityRequest`) — PATCH
semantics still apply to the request as a whole, but `identity`, like every other field on this
DTO, is a whole-value replacement when present (there is no field-level identity patch).

`CustodyActionRequest` — body for `POST /api/assets/{id}/custody`:
```json
{"action": "ISSUE", "custodianId": "3db9ba6d-...", "location": "Hangar B"}
```
```json
{"action": "RETURN"}
```
`action` is `ISSUE`|`RETURN`, case-insensitive; response is the asset's `AssetDetailsResponse`.

`InventoryActionRequest` — body for `POST /api/assets/{id}/inventory`:
```json
{"action": "GROUND", "kind": "GROUNDING", "summary": "Propeller crack found on preflight"}
```
```json
{"action": "RELEASE"}
```
```json
{"action": "RETIRE"}
```
`action` is `GROUND`|`RELEASE`|`RETIRE`; `kind`/`summary` required only for `GROUND`; response is
`AssetDetailsResponse`.

`CreateMaintenanceRecordRequest` — body for `POST /api/assets/{id}/maintenance` (opens a record
*without* also grounding the asset — use the `inventory` GROUND action above for that):
```json
{"kind": "INSPECTION_DUE", "summary": "100-hour service due"}
```
`kind` is `GROUNDING`|`INSPECTION_DUE`|`REPAIR`|`NOTE`, case-insensitive.

`MaintenanceRecordResponse` — one element of `GET /api/assets/{id}/maintenance`'s array, and the
return value of the open/close endpoints; `closedAt`/`flightSecondsAt` omitted (not `null`) when
absent:
```json
{
  "id": "b1f2...", "assetId": "cb3223ab-...", "kind": "GROUNDING",
  "openedAt": "2026-08-29T12:00:00Z", "openedBy": "3db9ba6d-...",
  "summary": "Propeller crack found on preflight"
}
```
closed example adds `"closedAt": "2026-08-30T09:00:00Z"` and, if known, `"flightSecondsAt": 12345`.

`CreateCategoryRequest` — body for `POST /api/categories` (201, gated on `canManageOrg`):
```json
{"id": "battery", "name": "Battery", "parentId": null, "connected": false,
 "attributeHints": ["capacity-mah", "chemistry", "cycles"]}
```

`UpdateCategoryRequest` — body for `PUT /api/categories/{id}` (gated on `canManageOrg`) — a **whole-
record replacement**, not a partial patch (see `CategoryEdit`'s own javadoc: `parentId` can
legitimately be `null`, so there is no unambiguous "unchanged" sentinel):
```json
{"name": "Battery", "parentId": null, "connected": false, "attributeHints": ["capacity-mah"]}
```

`CategoryResponse` gained `connected` (boolean, always present):
```json
{"slug": "battery", "name": "Battery", "attributeHints": ["capacity-mah"], "connected": false}
```
(`parent` omitted here as an example of a top-level category.)

`CategoryCountsResponse` (one row of `GET /api/fleet/summary`'s `categories` array) gained 5 fields,
always present — `inStock`/`issued`/`inField`/`maintenance`/`retired`, each counting `total`'s subset
at that **effective** inventory state:
```json
{"categoryId": "drone", "categoryName": "Drone", "total": 2, "active": 2, "deactivated": 0,
 "deleted": 0, "streaming": 1, "inStock": 1, "issued": 1, "inField": 0, "maintenance": 0, "retired": 0}
```

`AssetSummaryResponse`/`AssetDetailsResponse` both gained 5 trailing fields (same shapes, same
position at the end of the record): `identity` (`IdentityResponse`, always an object), `custody`
(`CustodyResponse`, always an object), `inventoryState` (string, one of `IN_STOCK`/`ISSUED`/
`IN_FIELD`/`MAINTENANCE`/`RETIRED` — the **effective** value, `InventoryStates#effective`, not the raw
stored one), `createdAt`/`updatedAt` (`Instant`, always present).

### New endpoints (`station/vision-api/.../controller`)

| Method | Path | Body | Returns | Authorization |
|---|---|---|---|---|
| `POST` | `/api/assets/{id}/custody` | `CustodyActionRequest` | `AssetDetailsResponse` | `canManage` (checked by `AssetCustodyService`) |
| `POST` | `/api/assets/{id}/inventory` | `InventoryActionRequest` | `AssetDetailsResponse` | `canManage` |
| `GET` | `/api/assets/{id}/maintenance` | — | `List<MaintenanceRecordResponse>` | `scope.includes` (visibility, not authority — read-only) |
| `POST` | `/api/assets/{id}/maintenance` | `CreateMaintenanceRecordRequest` | `MaintenanceRecordResponse` (201) | `canManage` |
| `POST` | `/api/assets/{id}/maintenance/{recordId}/close` | — | `MaintenanceRecordResponse` | `canManage` |
| `GET` | `/api/inventory/export` | — (query `format=csv`, default) | `text/csv` attachment `inventory.csv` | caller's own `scope()` (one row per asset in scope, no elevated authority needed — same visibility rule as `GET /api/assets`) |
| `POST` | `/api/categories` | `CreateCategoryRequest` | `CategoryResponse` (201) | `canManageOrg` |
| `PUT` | `/api/categories/{id}` | `UpdateCategoryRequest` | `CategoryResponse` | `canManageOrg` |

All 6 new `AssetInventoryController`/`InventoryExportController` handlers, plus the 2 new
`CategoryController` handlers, call `currentUser.scope()` directly — `EndpointAuthorizationTest`
(vision-app's ArchUnit-based call-graph check) verified without needing `@OpenByDesign` or a
`TEMPORARY_UNSCOPED` ledger entry.

### Wiring decisions (`station/vision-app`)

- `ApplicationServiceWiring` gained 3 beans: `assetCustodyService` (`DefaultAssetCustodyService`),
  `maintenanceService`, and `inventoryExportService`.
- **`maintenanceService`'s `@Bean` factory method returns the concrete `DefaultMaintenanceService`
  type, not the `MaintenanceService` interface** — `DefaultMaintenanceService implements
  MaintenanceService, MaintenanceQuery`, so declaring the concrete return type lets this one bean
  instance satisfy both injection points. This is what let `OnboardingWiringConfiguration
  #readinessService` take the 4th `MaintenanceQuery` parameter W5 required without a second bean or
  a wrapper class.
- `PersistenceWiringConfiguration` gained 2 ports: `maintenanceRepositoryPort`
  (`JpaMaintenanceRepository`) and `assetNoteRepositoryPort` (`JpaAssetNoteRepository`).

### Deferred (flagged, not built)

- **`AssetNoteRepositoryPort` is wired (JPA impl + bean) but nothing calls it yet** — no
  `AssetNoteService`/application-layer use case and no controller endpoint exist. W7 (crew notes UI)
  is the natural owner of that application service + `POST/GET /api/assets/{id}/notes` — the
  persistence is ready and waiting.
- **`CategorySpec`/`CategoryEdit` have no delete verb** — `DELETE /api/categories/{id}` was not in
  this wave's deliverable list and was not added speculatively.
- A migration-layer bug was found and fixed in this wave, not left for W4/W6/W7 to hit: `Asset
  .register`'s internally-stamped `Instant.now()` round-trips lossy through Postgres `TIMESTAMPTZ`
  (microsecond precision, rounds rather than truncates) — see `storage/persistence/MODULE.md`
  Gotchas for the fix. Purely a test-assertion concern; `createdAt`/`updatedAt` are display/sort
  fields, never compared for exact equality in production code.
- A pre-existing, unrelated bug surfaced by this wave's own D4 change (already fixed, not deferred):
  `AssetControllerTest#createReturns400ForZeroDevices` asserted `verifyNoInteractions(assetService)`,
  which stopped being true once W2 moved the "at least one device" check from `AssetSpec`'s own
  validation into `DefaultAssetService#create` (D4 — the rule is now category-`connected`-dependent,
  which the DTO layer cannot evaluate on its own). Renamed to
  `createReturns400ForZeroDevicesInAConnectedCategory` and restubbed to match the real call path.

## W7 status (Maintenance page + Crew tabs) — done

Built against W3's contract above with no re-reads of `station/vision-api` source. File scope kept
to `features/maintenance/**` (new), `features/roster/**`, `features/org-settings/**`,
`core/maintenance/**` (new), plus this file and `station/vision-web/MODULE.md` — did not touch
`app.routes.ts`, `nav-entries.ts`, `features/hubs/**`, `features/inventory|assets|devices|asset-detail/**`
(W4), or `features/onboarding/**` (W6).

**What shipped:**
- `/fleet/maintenance` — `MaintenancePage`/`MaintenanceFacade`, registered in
  `features/roster/roster.routes.ts` (not `hubs.routes.ts` — see the redirect handoff below). KPI
  tiles (Grounded/Inspection due/In repair/Retired), a "Ground a vehicle" form
  (`setAssetInventory({action:'GROUND', kind, summary})`), an open-records table (Close/Release per
  row), a "Recently closed" history (last 20). Pure logic + spec:
  `core/maintenance/maintenance-logic.ts`/`.spec.ts`.
- `/manage/roster` is now **Crew** (`CrewPage`) — `?tab=roster|org` tabs, each mounting the
  pre-existing `RosterPage`/`OrgSettingsPage` wholesale via a new `embedded` input (not copied).
  `/org` is now a guard-only redirect (`org-to-crew-guard.ts`) to `/manage/roster?tab=org`. Roster's
  "By asset" pivot rows show the asset's custodian next to the pilot-assignment dots (D3).
- `core/api/models.ts`/`vision-api.ts` gained the client-side mirror of this section's own DTOs:
  `InventoryState`, `AssetIdentity`, `AssetCustody` (on `AssetSummary`, optional in TS — see the
  next paragraph), `MaintenanceKind`, `MaintenanceRecord`, `CreateMaintenanceRecordRequest`,
  `InventoryAction`/`InventoryActionRequest`, and `VisionApi#listAssetMaintenance`/
  `#createMaintenanceRecord`/`#closeMaintenanceRecord`/`#setAssetInventory`.

**One deliberate deviation from a byte-exact DTO mirror:** the backend always sends
`identity`/`custody`/`inventoryState` on `AssetSummaryResponse` (per this section's own wire
contract above), but this wave declared the matching `AssetSummary` TS fields **optional**
rather than required. A required field would have broken ~12 spec files outside this wave's file
scope that build `AssetSummary` object literals without them (`asset({...})` test-fixture helpers
across `features/**`). Marking them optional keeps every one of those fixtures compiling untouched;
the real wire payload still always includes them, so nothing here changes runtime behavior — only
what TypeScript can statically assume. Flagging in case a future wave wants to do the mechanical
sweep of updating all ~12 fixtures and tightening the type back to required.

**Handoff to W4 (nav/rail, `features/hubs/**`):** this wave's own exit criterion
(WAREHOUSE-UX-PLAN.md §4 — "`/manage/health` route redirects to a real page") is not yet met.
`features/hubs/hubs.routes.ts` still routes `manage/health` to the `ComingSoon` scaffold; that file
is out of this wave's declared scope (owned by W4's nav/rail work), so the redirect to
`/fleet/maintenance` needs to land there, not here. `features/hubs/nav-entries.ts` will also want a
"Maintenance → /fleet/maintenance" entry in the Fleet group (per this plan's original task framing)
once W4 lands — not added here for the same reason.

**Handoff to W3 (`contexts/vision-warehouse`, `station/vision-api`):** there is still no fleet-wide
maintenance-records endpoint. `MaintenancePage` builds its open/closed tables by calling
`GET /api/assets/{id}/maintenance` once per asset currently in `MAINTENANCE` state (never for the
full fleet) — correct today, but O(grounded assets) requests that a real `GET
/api/maintenance?state=open` (or folding open/closed records into `GET /api/fleet/summary`) would
replace with one call. Follow-up for whoever next touches `vision-warehouse`'s maintenance slice.

**Not built, out of this wave's declared deliverables:** the crew-notes UI over
`AssetNoteRepositoryPort` this section's own "Deferred" note above flags for W7 — nothing in this
wave's task brief asked for it, and no `AssetNoteService`/`POST /api/assets/{id}/notes` endpoint
exists yet to build against; still open for a future wave.

**Verify:** `npx tsc --noEmit -p tsconfig.app.json`/`tsconfig.spec.json` — zero errors in any file
this wave owns (`features/maintenance|roster|org-settings/**`, `core/maintenance/**`,
`app.routes.spec.ts`), confirmed by grepping tsc's output for those paths. Both configs still fail
on two other in-progress waves' own files, isolated by `git status` (both show as `M`/untracked
outside this wave's staged paths): `features/inventory/inventory.routes.ts` (W4, `import('./inventory')`
— the component file doesn't exist on disk yet, mid-refactor) and
`features/reports/reports-logic.spec.ts` (a `CategoryCounts` fixture not yet updated for W4's own
in-progress `inStock`/`issued`/`inField`/`maintenance`/`retired` fields on that same shared
`models.ts`). `npm run test:ci` and `ng build --configuration production` both fail identically on
`inventory.routes.ts`'s unresolved import (esbuild can't resolve the module at all, so this blocks
the whole-project bundle, not just its own tests) — confirmed this is a whole-graph compile, not
scoped to the failing files, in an earlier pass of this same wave
(`ng test --watch=false --include='src/app/core/maintenance/**'` still surfaced the identical
errors). Recommend W4 re-run `npm run test:ci`/`ng build` once `features/inventory/inventory.ts`
lands, and this wave's own new `core/maintenance/maintenance-logic.spec.ts` (21 `it` cases,
reviewed by hand — pure functions, no DI) be confirmed green in that run.

## W6 status (add-vehicle wizard rebuild) — done

Built against W3's contract above with no re-reads of `station/vision-api` source beyond the one
targeted read of `DefaultAssetCustodyService#issue` noted below. File scope kept to
`features/onboarding/**`, `core/onboarding/**` (new), plus small additive edits to the two shared
files `core/api/models.ts`/`vision-api.ts` (see below), this file, and `station/vision-web/MODULE.md`
— did not touch `features/inventory|assets|devices|asset-detail/**` (W4), `app.routes.ts`,
`nav-entries.ts`, `features/maintenance|roster|org-settings/**` (W7), or any other session's dirty
files (`infra/rover-sim/**`, `core/rc/manual-control-client*`, `DefaultPeerDirectory.java`).

**What shipped:** the wizard is now **Identify · Connect · Prove · Register · Hand over**
(WAREHOUSE-UX-PLAN.md §3.4/§4 row W6, SOURCE-ONBOARDING-CONTEXT.md §6), with `sysid` kept as a
hidden conditional interstitial, never in the visible stepper. Full per-step detail, file-level
citations, and the exact request/DTO shapes are in `station/vision-web/MODULE.md`'s `onboarding/`
bullet (features section) — this section covers only what W4/whoever-next needs and doesn't
duplicate that write-up. Headline changes:
- **Identify** = old Profile + serial/make/model. A `connected: false` category (equipment) short-
  circuits straight to **Hand-over**, skipping Connect/Prove/Register as rendered steps; `POST
  /api/assets` is sent with `devices` omitted.
- **Connect** is now a two-row fit-out table (`core/onboarding/fit-out-logic.ts`) — **Sense**
  (telemetry) and **Sight** (video), each independently `find…`/`simulate`/`—`. Closes coupling C2:
  a vehicle with both a real flight controller and a real camera now registers both devices in one
  visit (`fitOutDeviceSpecs` → `CreateAssetRequest.devices[]`, one entry per filled row).
- **Prove** merges the old Test + Verify steps, run once per filled `find` row, results shown per
  row.
- **Register** is substance-unchanged (`POST /api/assets`), now carrying `identity` (`AssetIdentity`)
  and N devices instead of exactly one.
- **Hand-over** replaces "Pilots"/Assign: "Issue to" a custodian
  (`VisionApi#setAssetCustody({action:'ISSUE', custodianId, location?})` **plus** a separate
  `assignPilot(assetId, custodianId)` call) or "Leave in stock". Confirmed by reading
  `DefaultAssetCustodyService#issue` (`contexts/vision-warehouse`) that `ISSUE` alone only mutates
  `Custody`/`InventoryState` + audit — it does **not** create a pilot assignment, so the two-call
  sequence is required to reproduce the pre-W6 Assign step's actual effect. Ends in a completed
  sub-state (`handoverOutcome: 'issued'|'stocked'`) naming the next verb — "Open readiness ›" →
  `/assets/:id/readiness` for a connected vehicle, "Back to inventory" → `/assets?tab=equipment` for
  equipment — rather than an automatic router redirect.

**Deliberately not used: `CreateAssetRequest.CustodySpec`'s "issue straight to a pilot at create
time" shortcut** (documented above under "New/changed DTOs"). The wizard always creates the asset
in stock and only ever hands it off as the separate, explicit Hand-over step — matching
WAREHOUSE-UX-PLAN.md §3.4's own step ordering (Register then Hand-over are two distinct visible
steps, not one). `CreateAssetRequest.identity` is used (new optional field on the TS
`CreateAssetRequest`, additive); `CreateAssetRequest.custody` is not.

**Entry-point prefill contract (for W4 or anyone else linking into `/add-source`):**
`/add-source?deviceId=<uuid>` prefills the Connect step's matching fit-out row from that device's
own `protocol`/`uri`/`options` — `roleForDevice` (`fit-out-logic.ts`) sends a TELEMETRY-capable
device to the Sense row, everything else to the Sight row. Read is background/best-effort,
silent-degrade like every other constructor-time read in this wizard (`OnboardingStore#applyDevicePrefill`):
an unknown id, a 403, or no `deviceId` at all simply leaves both rows empty, never a blocked page.
**Known limitation, not closed this wave:** this creates a *new* device row on the asset being
registered, not a reference to the original `Device` by id — `CreateAssetRequest.devices[]` has no
field for that (only a `deviceIds[]` shape would, which this fit-out table does not thread through).
A caller linking here meaning "attach this already-registered device to a new asset" gets a
duplicate device row with the same protocol/uri, not a move. If W4 builds an "Add vehicle from this
link" affordance on the Devices/Links tab expecting a clean attach, it will need either a new wire
shape or to accept this duplication as the interim behavior.

**Other known limitations, flagged not fixed:**
- **The legacy whole-vehicle Simulate path (`POST /api/simulations`) only covers one row
  combination** (`fit-out-logic.ts#usesLegacySimulationPath`): Sight = `simulate` and Sense is not a
  real `find` link. Any row set to `find` takes the multi-device `POST /api/assets` path instead,
  since `/api/simulations` cannot attach to a real link on the other row — a documented gap, not
  new backend support to close it.
- **Registration now rides `identity.registration`, not `core/fleet/asset-attributes.ts`'s
  `attributes['registrationNumber']` convention.** That file is left untouched (still used by
  `features/asset-detail/**`, W4's file scope) — a discrepancy now exists between the two paths
  until whoever owns `asset-detail`'s inline edit migrates it to `identity.registration` too. This
  also surfaces a pre-existing persistence-layer bug worth flagging to W3/whoever owns
  `V28__asset_inventory.sql`: its backfill (`attributes ->> 'registration'`) assumes the attribute
  key was `'registration'`, but the frontend's actual historical convention wrote
  `'registrationNumber'` — so that migration likely backfilled nothing for real historical data.
- **`features/assets/assets-facade.ts`/`features/devices/devices-facade.ts` each still carry their
  own pre-wizard single-device empty-state quick-add**, bypassing this wizard entirely — not touched
  this wave (W4 file scope) but should eventually be deleted now that the wizard is the one
  add-a-vehicle path.

**Shared files touched (additive only) — neither committed by this wave, see below:**
`core/api/models.ts` gained `CreateAssetRequest.identity?: AssetIdentity` and `AssetEdit.identity?:
AssetIdentity` (the legacy-simulate path's post-create PATCH). `vision-api.ts` was not changed net
of merging: this wave initially added its own `assetCustody` method, then found W4 had concurrently
added an identical-purpose `setAssetCustody` method on the same shared file — resolved by removing
this wave's duplicate and folding its doc-comment (the `issue`-doesn't-assign-a-pilot note above)
into W4's existing method instead, so only one method exists on `VisionApi` for this call.
**Commit boundary:** `models.ts`'s `identity` fields ended up swept into W7's own commit
`cae23506` (confirmed via `git show HEAD:.../models.ts` — they're already present in `HEAD`, most
likely picked up incidentally when W7 staged the whole file for its own concurrent additions) — no
action needed, they're already safely committed. `vision-api.ts`'s doc-comment merge is **not**
committed by this wave either: it sits inside the same uncommitted `setAssetCustody` method W4
authored (the whole method is one contiguous uncommitted addition against `HEAD`, so there is no
clean hunk boundary between "W4's method" and "this wave's two-line paragraph inside it" for
`git add -p` to isolate) — it will ride along harmlessly whenever W4 commits their own `vision-api.ts`
work (`createCategory`/`updateCategory`/`inventoryExportUrl`, none of which is this wave's). This
wave's own commit therefore touches only `features/onboarding/**`, `core/onboarding/**`, this file,
and `station/vision-web/MODULE.md`.

**Verify:** `npx tsc --noEmit -p tsconfig.app.json`/`tsconfig.spec.json` — zero errors in any file
this wave owns (`features/onboarding/**`, `core/onboarding/**`), confirmed by grepping tsc's output
for those paths (two pre-existing spec-file issues fixed along the way, both mine:
`fit-out-logic.spec.ts` passing `{}` instead of the full `{sense,sight}` shape to
`canAdvanceFromFitOutProve`, and `onboarding-logic.spec.ts`'s `row()` test helper writing `role`
twice — TS2783). Both configs still fail on the exact same two other in-progress waves' own files
W7's own "Verify" paragraph above already documents: `features/inventory/inventory.routes.ts` (W4,
`import('./inventory')` — the component file still doesn't exist on disk) and
`features/reports/reports-logic.spec.ts` (the `CategoryCounts` fixture still not updated for W4's
own in-progress fields on that same shared `models.ts`) — confirmed via `git status` that neither
file is in this wave's own scope. `npm run test:ci`/`ng build --configuration production` both fail
identically on `inventory.routes.ts`'s unresolved import (esbuild can't resolve the module at all,
blocking the whole-project bundle outright) — retried repeatedly across this wave's session
(including after a session-limit reset) with the identical result each time, confirming this is the
same whole-graph blocker W7 already hit and documented, not something that cleared on its own.
An isolated `ng test --watch=false --include='src/app/features/onboarding/*.spec.ts'
--include='src/app/core/onboarding/*.spec.ts'` was attempted to sidestep it (following W2's own
precedent above) but hits the identical `inventory.routes.ts` module-resolution error — unlike W2's
type-only blocker, a missing module fails the whole-graph bundle before any test file can run, so
this wave's own `fit-out-logic.spec.ts`/`onboarding-logic.spec.ts`/every pre-existing onboarding
spec were reviewed by hand and confirmed against the rewritten `onboarding-logic.ts`/
`fit-out-logic.ts`/`onboarding-store.ts`/`onboarding-facade.ts` APIs rather than run to green.
Recommend re-running `npm run test:ci`/`ng build` once `features/inventory/inventory.ts` lands, to
confirm this wave's own spec files and get a real `add-source` lazy-chunk bundle delta.
