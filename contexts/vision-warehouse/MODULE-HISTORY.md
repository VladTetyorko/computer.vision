# vision-warehouse — wave history

`MODULE.md` is the contract: current state only, no dates, no test counts. This file is the
wave-by-wave narrative — why decisions were made, what changed when, and what each build run
measured at the time. Newest first.

## docs/plans/active/LINK-PAIRING-PLAN.md — wave L2

§3.3 — a vehicle's persisted identity, station-minted rather than address-derived: `PairingId`,
`VehicleKey` (a random 32-byte key, redacted in `toString()`), `RadioBind` (reserved, `NONE` only
today), `Pairing` (keyed on `DeviceId`, not `AssetId` — a fact about the physical board, not the
categorized/owned inventory row), `PairingRepositoryPort`, and `PairingService`/`DefaultPairingService`
(`application.pairing`). `pair` is idempotent per `DeviceId`; the sysid rule keeps a heard sysid only
when it falls inside `PairingSettings`' configured range and no other pairing already holds it,
otherwise assigning the lowest free number — and, when the assigned sysid differs from the heard one,
mirrors it onto the device's own `StreamDescriptor.options()["sysid"]` so the runtime actually opens
against the sysid this pairing claims (defect #4 found in the plan's §8 live walk). `forget` is a
deliberate ⚠ hard delete, the module's one exception to its usual soft-delete convention (a forgotten
pairing's sysid/key must stop being valid immediately).

Same wave widened `DiscoveryCandidate.identityKeyFor` to be identity-first: a sysid, once known, is
the whole key (no address component), so a vehicle re-heard at a new address resolves to the same
candidate instead of spawning a duplicate — and `DefaultAssetService#matchDevice`/`findDuplicateDevice`
gained the matching sysid-only match-then-self-heal-the-address behavior (`DefaultAssetServiceDedupTest`).
`./mvnw -B -pl contexts/vision-warehouse test` — **407** tests, up from 391 before this wave
(`DefaultPairingServiceTest` +10, `DefaultAssetServiceDedupTest` +3; the remaining delta is other
concurrent-wave work already landed on this shared branch, not itemized here).

## docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md — wave B1

§3.2 C1/C4/C5, §3.2 U2/U8 — closed the P4 re-onboarding dead ends and the health-reporting honesty gap: `DiscoveryInboxService#attach`+`#restore`,
`report`'s `ReportOutcome` return type + the `REGISTERED→NEW` auto-reopen rule, `SourceStatus#NEVER_SCANNED` +
`SourceHealth#lastScanAt` + `DeviceDiscoveryPort#lastStatus()`'s default flip, `DiscoveryCandidate#restore()`,
`DiscoveryCandidateAlreadyRegisteredException`, and the `Device#withState` origin-preservation fix (U2,
regression-tested). Domain + application are fully implemented and unit-tested in this module; **no
outer-layer implementation exists yet** — `vision-api`'s `DiscoveryInboxController` has no `attach`/`restore`
endpoints, `vision-app`'s sweep runner does not yet branch on `ReportOutcome#changed`, and neither
`RegisterDiscoveryCandidateResponse` nor `DiscoveryInboxController`'s error mapping knows about
`SourceHealth#lastScanAt` or `DiscoveryCandidateAlreadyRegisteredException` — all of that is a later
`spring-integrator` wave's (C1) job, out of this module's file scope. `./mvnw -B -pl contexts/vision-warehouse test`
— 376 → 391 tests, all green.

Two implementation choices not spelled out literally by the plan text, worth flagging for the C1
wave: (1) `ReportOutcome#changed` is computed as `existing.isEmpty() || status changed || discovered
changed` rather than literally comparing `firstSeen.equals(lastSeen)` on the saved candidate as the
plan's prose describes — semantically equivalent for a first sighting (`newlyReported` always stamps
both to the same instant) but robust against a fixed test clock producing a false tie. (2) `attach`'s
javadoc says "one audit entry, no orphan device on failure" — this implementation composes the
existing `DeviceService#register` (audits `CREATED`) and `AssetService#assignDevice` (audits
`UPDATED`), so two audit rows are written per `attach`, not one; "no orphan device on failure" is
honored exactly (every validation runs before the device is registered), but "one audit entry" is
read here as describing the operation's atomicity from an operator's perspective, not a literal
single-row invariant — reusing the two existing, already-audited services was judged truer to the
plan's own "registers... calls the existing `AssetService#assignDevice`" instruction than inventing a
bypass write. Flag this for the C1 wave if a literal single-audit-row contract turns out to matter.

## docs/plans/active/AUTH-ROLES-PLAN.md — wave B6

§3.6 — migrated every deprecated
`VisibilityScope#canManageOrg()`/`#canManage(Ownership)` call site in this module onto `Authority`:
`AssetCustodyService`'s five verbs (`issue`/`returnToStock`/`ground`/`release`/`retire`) and its
private `requireManageable` widened `VisibilityScope scope` → `Authority scope`, body now
`scope.mayManageFleet(asset.ownership())`; `MaintenanceService#open`/`#close` and their shared
private `requireManageable(AssetId, Authority)` widened the same way — `listForAsset`/`fleetWide`
were **not** touched, since they authorise on `VisibilityScope#includes` (a non-deprecated method)
and stay plain-`VisibilityScope`-typed; `DiscoveryInboxService#register` widened `VisibilityScope
scope` → `Authority scope`, body now `scope.mayManageOrg()` then
`scope.scope().includesGroup(command.ownership().groupId())`. No behavior change for an `Authority`
built from `Authority.full()`/an unbounded-scope-with-every-capability caller — every existing
production caller (all of them pre-auth-rollout ADMIN-equivalent) answers identically; the
practical effect only appears once a `VIEWER`-role `Authority` (visibility-only, no
`MANAGE_FLEET`/`MANAGE_ORG` capability) is threaded in from `vision-api`, which this module does
not itself construct. Test-double fix pattern: `DefaultAssetCustodyServiceTest`'s `inScope`/
`outOfScope` fields were retyped `VisibilityScope`→`Authority` outright (every use was
gated-call-only); `DefaultMaintenanceServiceTest` instead added parallel `inAuthority`/
`outOfAuthority` fields alongside the unchanged `inScope`/`outOfScope` (its `inScope`/`outOfScope`
are shared with the untouched `listForAsset`/`fleetWide` calls); `DefaultDiscoveryInboxServiceTest`
wrapped its four `VisibilityScope.unbounded()` register-call arguments as `Authority.full()` and its
two locally-scoped denial-test variables as `new Authority(VisibilityScope..., Set.of(...))` — note
this test file already imports `com.drones.vision.kernel.Capability` (device capabilities), so
`com.drones.vision.platform.Capability` (the `Authority` capability enum) is referenced
fully-qualified there rather than imported, to avoid a simple-name collision.
`./mvnw -B -pl contexts/vision-warehouse test` — 376 → 376 tests, all green (no count change, only
argument types).

## docs/plans/active/ASSET-FLOWS-PLAN.md — wave BK4

§2 (D1p) — closes `docs/plans/active/PLATFORM-AUDIT-DB.md` gap #4/T4 ("every
flight record is anonymous"). Widened `AssetUsage` with a nullable `pilotId: UserId` (11th component) and `UsageSessionService#open` with
a trailing `UserId pilotIdOrNull` parameter — both widened in place per CLAUDE.md rule 10, every
call site across the repo updated, no new overload. `UsageSummary` grew the same field for the
fleet-wide replay-library read (`GET /api/usages`). **No new Flyway migration** — `V28__asset_inventory.sql`
already added `asset_usages.pilot_id UUID` schema-only (see `storage/persistence/MODULE.md`'s V28
row); this wave only wires the domain field and `storage/persistence`'s entity/mapper onto that
pre-existing column. Population: `vision-api`'s `AssetSessionController#engage` passes
`CurrentUser#userId()` through to `UsageTracker#engage`, which now backfills a still-`null` pilot
onto an already-open `STREAM`-origin usage at promotion time, never overwriting an already-recorded
one (see the new Gotcha above); a device-pushed stream open (`UsageTracker#deviceStreamStarted`)
still passes `null` — no acting-user context exists at that call site, and this wave deliberately
did not invent one. `./mvnw -B -pl contexts/vision-warehouse test` — 367 → 376 tests, all green.

## docs/plans/active/ASSET-FLOWS-PLAN.md — wave BK6

§2 (A3) — closed the
"unreachable reads as empty" gap in the found-devices inbox: `SourceStatus`, `DeviceDiscoveryPort#lastStatus()`
(a `default OK` method, not a `scan` signature change — see `domain.port` above), `SourceHealth`,
and `DiscoveryService#health()`. `device-discovery/onvif-mdns-v4l2`'s `MediamtxPathScanner` is the
first (and, as of this wave, only) override of `lastStatus()`; every other registered port —
including `drone-link/mavlink`'s `MavlinkHeartbeatScanner`, untouched by this wave — inherits `OK`.
`vision-api`'s `DiscoveryInboxController` is the first caller of `health()`, surfaced as
`sources: [{id, status}]` alongside the existing `candidates` array (see that module's MODULE.md
for the wire shape).

## docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md — wave Z2a

§11 — added
the persisted discovery inbox: `DiscoveryCandidateId`/`CandidateStatus`/`DiscoveryCandidate`,
`DiscoveryCandidateRepositoryPort`, `DiscoveryInboxService`/`DefaultDiscoveryInboxService`/
`RegisterFromCandidateCommand`, and `AssetService#findDuplicateDevice`/`DuplicateDeviceMatch` (the
non-throwing sibling of `createFromCandidate`'s duplicate check, extracted into a shared
`DefaultAssetService#matchDevice` helper so the two never drift). `register` is
`AssetService#createFromCandidate`'s first production caller — before Z2a that method only had test
callers. Domain + application are fully implemented and unit-tested in this module; **no outer-layer
implementation exists yet** — `storage/persistence` has no `discovery_candidates` table, and
`vision-api`/`vision-app` wiring (the sweep runner that calls `report`, the REST surface over
`candidates`/`dismiss`/`register`) is a later Z2 sub-wave's job. `AfterActionAssemblerTest`'s
hand-written `FakeAssetService` in `station/vision-api` now needs a `findDuplicateDevice` override to
compile — flagged for that module's owner, out of this module's file scope to fix.

## docs/plans/active/WAREHOUSE-UX-CONTEXT.md — wave W8

Closed three backend follow-ups from W3/W4/W7: `MaintenanceRepositoryPort#findOpen`/`findRecentlyClosed`
+ `MaintenanceService#fleetWide`/`MaintenanceListState`/`MaintenanceRecordSummary` (backing `GET
/api/maintenance`, a fleet-wide maintenance read so the Inventory page needs one call rather than
one per grounded asset), and `AssetUsageRepositoryPort#totalFlightSecondsByAsset` (one aggregate
query the `vision-api` layer joins onto the asset row for cheap flight hours). Firmware-on-the-row
(the wave's third item) needed no change in this module — it is joined entirely at the `vision-api`
layer, since warehouse must never depend on `vision-flight`'s `VehicleProfileRepositoryPort`; see
`station/vision-api/MODULE.md`'s `AssetRowFacts`. `AssetSummary`/`AssetSpec`/etc. were **not**
widened for this wave — both new facts are read-model joins at the API layer, not new fields on the
canonical application record (avoids a ninth `new AssetSummary(...)` call-site migration across
5 modules for a purely additive read).

## docs/plans/active/WAREHOUSE-UX-CONTEXT.md — wave W2

Added the inventory layer:
`Identity`/`Custody`/`InventoryState`/`InventoryStates`, `MaintenanceId`/`MaintenanceKind`/
`MaintenanceRecord`, `NoteId`/`AssetNote`, `MaintenanceRepositoryPort`/`AssetNoteRepositoryPort`,
`AssetCustodyService`/`DefaultAssetCustodyService`, `MaintenanceService`/`MaintenanceQuery`/
`DefaultMaintenanceService`, `DeviceCategory#connected`, and the corresponding widening of `Asset`,
`AssetSpec`, `AssetEdit`, `AssetSummary`, `CategoryCounts`. Domain + application are fully
implemented and unit-tested in this module; **no outer-layer implementation exists yet** —
`storage/persistence` has no `maintenance_records`/`asset_notes` tables or `assets` columns for the
new fields, and every out-of-module caller of `Asset`/`AssetSpec`/`AssetEdit`/`DeviceCategory`'s
constructors (station/vision-api, storage/persistence, vision-app, vision-flight, vision-identity,
vision-learning, vision-perception, vision-simulation — 41 files) will not compile until a later
wave (W3) updates them. See WAREHOUSE-UX-CONTEXT.md's "W2 → W3 handoff" section for the full file
list and the exact column set W3 must persist.

## docs/plans/active/OPERATOR-UX-5-PLAN.md — wave W1

Finding U1 — `UsageIdleCloseService` fixes a
correctness defect found in `/api/usages`: `vision-perception` never closes a usage on a source or
pipeline failure alone (`DefaultStreamService#stop` is the only path that ever does, see that
module's MODULE.md), so a crashed process or a lost link on an offline rover used to leave a usage
open — and rendered as "Flying now" — indefinitely.

## docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md — wave R1-R5c

Session ownership (`AssetUsage` construction/persistence, `AssetDirectoryService`) and the
`UsageOrigin`/`DeviceOrigin` fields are the product of these waves; see that plan and
`vision-perception`'s MODULE.md for the runtime (`UsageTracker`) side of the same split. Backing
migrations (`V25__device_origin.sql`, `V26__asset_usage_origin.sql`) live in `storage/persistence`.
