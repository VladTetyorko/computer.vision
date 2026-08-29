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
| W5 readiness ← maintenance | application-service | blocked on W2 | |
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
