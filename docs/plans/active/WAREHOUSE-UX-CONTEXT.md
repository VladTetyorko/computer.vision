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
| W2 domain | domain-modeler | done (uncommitted — see W2 → W3 handoff) | — |
| W3 persistence + API | spring-integrator | blocked on W2 | |
| W4 inventory page | web-ui | blocked on W1, W3 | |
| W5 readiness ← maintenance | application-service | done (uncommitted — agent instructions forbid `git commit`; see W5 handoff) | — |
| W6 wizard | web-ui | blocked on W3 | |
| W7 maintenance + crew | web-ui | blocked on W3 | |

Shared tree: agents commit **by path**, never stash. Unrelated dirty files (`infra/rover-sim/**`, `core/rc/manual-control-client*`, `DefaultPeerDirectory.java`) belong to another session — do not touch.

## W1 notes for W4 (inventory page)

- Asset categories, Inventory reports, and Devices — no §3.1 group names a home for these three — landed in **FLEET** alongside the renamed Inventory/Add vehicle/Crew, since W1 is pure IA (OQ5) and none of the three is a stub. They're still separate nav entries/routes today (`/manage/categories`, `/manage/reports`, `/devices`); when W4's merged Inventory page ships its own tabs (OQ4), drop the three now-redundant `nav-entries.ts` FLEET entries in the same commit rather than leaving dead rail links alongside the new tabs.
- Manager entry count is **20**, not §3.1's own illustrative "15" — that figure already assumes W4's tab merge + W7's Maintenance entry, neither of which exists yet. Pilot count matches the plan's "10" exactly. Full reconciliation in `station/vision-web/MODULE.md`'s W1 changelog entry and `nav-entries.spec.ts`'s own count test.
- `/devices`, `/manage/categories`, `/manage/reports` all gained `canActivate: [orgGuard]` this wave (they didn't have it before) — W4's OQ4 redirect-into-tabs plan should keep that gate on whatever route ends up serving that content.

## W2 → W3 handoff

W2's domain + application changes to `contexts/vision-warehouse` are complete and green
(`./mvnw -B -pl contexts/vision-warehouse test` — 315 tests, 0 failures) but **left uncommitted in
the working tree** — the domain-modeler agent's own operating instructions say not to commit, which
overrides this ledger's usual "commit by path" convention; whoever picks up W3 should review and
commit the `contexts/vision-warehouse/**` diff first (nothing outside that path was touched).

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

## W5 handoff (readiness ← maintenance, D6/OQ1 default)

`contexts/vision-flight`'s changes are complete and green
(`./mvnw -B -pl contexts/vision-flight test` — **379 tests, 0 failures**) but **left uncommitted in
the working tree** — the application-service agent's own operating instructions say `Do NOT git
commit`, which overrides this ledger's usual "commit by path" convention (the same situation W2 left
for `contexts/vision-warehouse`). Whoever next touches `vision-flight`, or lands W3, should review and
commit the `contexts/vision-flight/**` diff (nothing outside that path was touched — `vision-warehouse`,
`vision-api`, `vision-app`, `vision-simulation` and every other dirty file in the tree belong to other
sessions and were not read for API surface beyond their `MODULE.md`s, let alone edited).

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
