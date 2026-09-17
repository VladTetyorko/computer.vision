# vision-flight — wave history

`MODULE.md` is the contract (current state only, no dates, no test counts). This file is the
wave-by-wave narrative: why decisions were made, what changed when, and what each build run
measured at the time. Newest first.

## 2026-09-06 — LIVE-POLL-RETIREMENT-PLAN.md wave L3 — the `zones` SSE topic's flight-context half

New `GeofenceZoneEvent` (record, `Action{CREATED,UPDATED,DELETED}` + `GeofenceZone`) and
`GeofenceLiveUpdatePort` (one method, `publishZoneEvent`) — see MODULE.md's own API-surface entries
for the full shape and the "why not ride `map`" reasoning (no `map -> flight` architecture edge
exists). `DefaultGeofenceService` gained a required 3rd constructor collaborator,
`GeofenceLiveUpdatePort`, and now publishes a `GeofenceZoneEvent` from `create`/`update`/`delete`,
immediately after `GeofenceMonitor#refresh()`; `delete` was changed to capture `require(id)`'s return
value (previously discarded) so the `DELETED` event carries the zone's full last-known state, not
just its id. `station/vision-api`'s `LiveUpdateRegistry` is the real implementation (now seven
`*LiveUpdatePort`s); `station/vision-app`'s `NoopLiveUpdatePublisher` and `ApplicationServiceWiring`
gained a seventh selector bean, byte-identical in shape to the six preceding it — see that module's
own MODULE.md for the wiring/qualifier details, and `station/vision-api/MODULE.md`'s "Live updates"
section for the topic/envelope/buffer shape (`GeofenceZoneEventPayload{action, zone}`, FIFO buffer
sharing `eventBufferCapacity`).

`DefaultGeofenceServiceTest` was widened in place (no new test *methods* — every pre-existing case
gained publish assertions): `createSavesANewZoneAndRefreshesTheMonitor`/
`updateReplacesAnExistingZoneAndRefreshesTheMonitor` each now capture the published
`GeofenceZoneEvent` via `ArgumentCaptor` and assert `CREATED`/`UPDATED` plus the exact zone;
`deleteRemovesAnExistingZoneAndRefreshesTheMonitor` asserts `DELETED` carries the zone that existed
*before* deletion, in full; both `...ThrowsForAnUnknownId` cases gained
`verify(geofenceLiveUpdatePort, never()).publishZoneEvent(any())`; `constructorRejectsNullCollaborators`
now covers all three null-collaborator cases. `./mvnw -B -pl contexts/vision-flight test` —
**449/449** green (unchanged test *count* from the pre-wave 447 plus the two-asset-concurrency +2
from the wave below — this wave strengthened existing assertions rather than adding new test
methods, since every required proof fit inside `DefaultGeofenceServiceTest`'s existing five cases).
No `ApiExceptionHandler` mapping needed (this module never depends on it) — `GeofenceLiveUpdatePort`
is fire-and-forget from the service's point of view, same as every other `*LiveUpdatePort` this
module's siblings already call. Nothing deferred; L4 (the `system` SSE topic, a pure `vision-api`
concern with no `vision-flight` collaborator) is documented in `station/vision-api/MODULE.md` instead.

## 2026-09-06 — E2E-FLOW-AUDIT-2026-09-05.md, proposal S1 — manual control exclusive per asset, not per JVM

`DefaultManualControlService`'s single `activeSession` field became
`Map<AssetId, DefaultManualControlSession> activeSessions`, still guarded by the pre-existing
`sessionLock`. The audit (`docs/plans/active/E2E-FLOW-AUDIT-2026-09-05.md`, proposal S1) measured
this as the platform's hardest concurrency ceiling: because `ApplicationServiceWiring` registers
one singleton bean, **the whole fleet supported exactly one manual-control pilot at a time** — a
second operator engaging a completely different aircraft was refused "already active". The old
class javadoc called this "an intentional Phase 1 simplification (single SITL operator)" and
predicted a fix would need per-connection service instances; it did not — the state was simply
unkeyed.

A plain `HashMap` (not `ConcurrentHashMap`) is correct here: every read and write happens inside
`synchronized (sessionLock)`, engage/release are once-per-flight events, and the ~30 Hz
`onChannels` hot path never touches this lock. `onSessionEnded` uses `remove(key, value)` so a
session that already lost its slot to a newer engage on the same asset cannot evict the newer one
while unwinding.

Two call-site contracts had to move with it: `ManualControlService#engage`'s `@throws` javadoc
(which described the limit as per-handle) and `ManualControlWebSocketHandler`'s class javadoc
(which explained cross-connection refusal as a singleton artifact — now correctly per-asset). The
handler's `mapIllegalState` matcher was **not** changed, and the new message deliberately retains
the `already active` substring it keys on; a test now asserts that substring so the coupling fails
loudly. `./mvnw -B -pl contexts/vision-flight -am test` — green, +2 tests (two assets engage
concurrently; releasing one leaves the other engaged and re-engageable).

## 2026-09-05 — CREW-CONTROL-PLAN.md wave W1 — the seat registry

New `domain.model.SeatKind` (2-value enum, no ordinal semantics), `domain.model.Seat` (record,
compact-ctor validated), and `application.seat.SeatService`/`DefaultSeatService` — see MODULE.md's
own API-surface entries for the full shape. In-heap, `ConcurrentHashMap<AssetId, SeatPair>`, one
atomic `compute` per transition, lazy expiry, no scheduler — same honest posture as
`GeofenceMonitor`. `UserId` (`vision-kernel`) needed no new dependency — this module already
imports it via `DefaultFlightCommandService` et al. **One deviation from a literal reading of the
frozen shape, not a variance in it**: `forceRelease(AssetId, SeatKind)` cannot itself write the
`FORCE:<KIND>` audit entry §3.6 names, because its own frozen signature carries no `actor` and
`AuditEntry` requires one — this is a consequence of the frozen two-argument shape, not a choice
made here; the entry must be written by whichever caller resolves the forcing manager's identity to
authorize the call (W2's `SeatController`/`SeatAccess`), flagged in MODULE.md's own Gotchas. Every
other transition (`take`/`release`/`preempt`) is audited directly. `./mvnw -B -pl
contexts/vision-flight test` — **447 tests**, all green (+37 from 410: 8 new `SeatTest` + 29 new
`DefaultSeatServiceTest`, the latter proving the rule-3/rule-4 asymmetry, lazy expiry via a mutable
test `Clock`, renewal-is-not-audited, release/forceRelease idempotency, and synchronous
`onPreempted` firing on both `preempt` and a held `forceRelease`). **Next, out of this wave's file
scope, flagged for W2**: `station/vision-api`'s `AssetAuthority`/`ScopeAssetAuthority`/
`SeatController`/`SeatAccess` (§3.7/§4 W2 row) must wire this service in, write the `FORCE:<KIND>`
audit entry `forceRelease` cannot, and map the `IllegalStateException` `take` throws when held
("... seat is held by ...") to the frozen 409 conflict body (§3.6).

## docs/plans/active/CONTROLLER-SETUP-CONTEXT.md wave C15 — the operator's transmitter

The operator's transmitter, not just their bindings — reconciled here as part of merging
`feat/controller-setup-c15` onto master. `OwnedControlProfile` gained a fifth component, the new
domain record `TransmitterView(int stickMode, boolean forwardIsUp)`: which stick carries throttle
(mode 1-4) and whether pushing a stick forward reads positive, defaulting to mode 2/forward-up
(`TransmitterView.DEFAULT`). A 4-argument convenience constructor on `OwnedControlProfile` covers
every pre-C15 call site. `ControlProfileService#update` gained a sixth parameter to accept it
(`null` reads as the default). Deliberately kept off `ControlProfile` itself — it changes how the
layout is *drawn*, never a channel a live session engages with (see MODULE.md's own API surface for
the full rationale). `station/vision-web` builds the guided step-by-step setup flow and live
CH1-CH8 microsecond readout this feeds — see that module's own MODULE.md.

## docs/plans/active/ASSET-FLOWS-PLAN.md wave S4/BK2b — link-loss wiring closed

The one remaining gap S4/BK2 flagged (`LinkLossNotifier.reportLinkLost` implemented but not called
by anything) is now closed — MODULE.md's own API-surface/Gotchas entries were updated in place
rather than left as stale "not wired yet" flags. No further change to any type in this module; the
wiring itself lives in `contexts/vision-perception`'s `UsageTracker`/`UsageTrackerSettings` and
`station/vision-app`'s `ApplicationServiceWiring` — see those modules' own MODULE.mds. `./mvnw -B -pl
contexts/vision-flight test` unaffected (no file in this module changed) — this wave's own gates
(`./mvnw -B -pl contexts/vision-perception -am test`, `./mvnw -B -pl station/vision-app -am test`)
are recorded in `contexts/vision-perception/MODULE.md`.

The already-merged FLEET-RADIO R4/F7 typed link-failure signal (`mavlink-core`'s
`MavlinkSession#onLinkFailure`, `PeerId`-carrying) reaches project code as the `IOException` a
device's supervised `TelemetrySourcePort#open` publisher closes exceptionally with — this is the
origin of the `IOException` that `UsageTracker#subscribeTelemetry`'s `SupervisedPublisher` outage
callback reacts to (see MODULE.md's own Gotchas for the current call-site fact).

## docs/plans/active/ASSET-FLOWS-PLAN.md wave S1/BK1 — shared-tree test reconciliation

`DefaultFlightCommandServiceTest` gained 5 cases
(`armRefusesWhenAssetIsMaintenanceGroundedWithoutTouchingThePort` plus one each proving
`disarm`/`emergencyStop`/`returnToHome`/`setMode` still work on a grounded asset) via a new
`readinessService = mock(ReadinessService.class)` field, defaulted to a `GO`/no-blocker stub in
`setUp()` so every pre-existing test is unaffected. `./mvnw -B -pl contexts/vision-flight -am
test`, run after both this wave's and S4/BK2's changes were present in the tree — **404 tests, all
green** (`DefaultFlightCommandServiceTest` 23→28); this is the actual current total, superseding the
arithmetic in the S4/BK2 entry below (its own "379+25=404" did not yet account for this wave's +5,
landing in the same shared file concurrently — the two waves' net effect coincidentally also lands
on 404 tests run overall, confirmed by direct count of every suite's printed line).

## docs/plans/active/ASSET-FLOWS-PLAN.md wave S4/BK2 — battery and link-loss monitors added

Added the `application.alerting` package (`BatteryAlertSettings`, `BatteryMonitor`,
`LinkLossNotifier` — see MODULE.md's own API-surface entries), the two producers behind the new
`EventType.LINK_LOST`/`BATTERY_LOW` bell kinds (`vision-platform`). `BatteryMonitor` is fully wired
end to end (composed into `GeofenceMonitor`'s existing `vision-app` telemetry-observer seam);
`LinkLossNotifier` is implemented, tested, and has a ready `vision-app` bean, but its one production
call site (`UsageTracker#subscribeTelemetry`, perception) is reserved this wave for a concurrent S1
gate change and could not be touched — closed by the S4/BK2b wave above. `./mvnw -B -pl
contexts/vision-flight -am test` — **404 tests**, all green (+25 from 379: 12 `BatteryMonitorTest` +
4 `LinkLossNotifierTest` + 4 `BatteryAlertSettingsTest`, +5 unrelated to this wave from a concurrent
agent's own in-flight work landing in the same shared tree). `station/vision-app`'s
`ApplicationServiceWiring` gained `batteryMonitor`/`linkLossNotifier` beans and composed
`batteryMonitor::evaluate` into the `usageTracker` bean's telemetry-observer lambda — see
`station/vision-app/MODULE.md`.

## 2026-09-01 — ASSET-FLOWS-PLAN.md wave S1 (BK1) — arm gated on maintenance grounding

`arm` closes the handoff W5 (below) left open. `DefaultFlightCommandService` gained a required 4th
collaborator, `ReadinessService` (mirroring `DefaultManualControlService#engage`'s exact idiom —
same `evaluate` call, same `MAINTENANCE_BLOCKER_PREFIX` filter). Only `arm` is gated
(`requireNotMaintenanceGrounded`, inserted between the scope check and device resolution —
`resolveForCommand` was split into `resolveScopedAsset` + `commandableDevice` so the other five
command methods are byte-identical); `disarm`/`emergencyStop`/`returnToHome`/`setMode` are unchanged
and explicitly tested to still work on a grounded asset (energy-reducing/recovery verbs, plan §2).
`station/vision-app`'s `flightCommandService` bean now also takes `ReadinessService`. Perception's
`UsageTracker#engage` gate is the sibling half of this wave — see
`contexts/vision-perception/MODULE.md`. `./mvnw -B -pl contexts/vision-flight test` — **379 tests**,
all green (2026-08-29; +8 from R6's 371 — `DefaultReadinessServiceTest`'s 5 new
maintenance-blocker cases plus 5 W2-broken tests across `DefaultFlightCommandServiceTest`/
`DefaultManualControlServiceTest`/`DefaultReadinessServiceTest`/`DefaultRemediationServiceTest`/
`DefaultVehicleProfileServiceTest` fixed for the new `Asset`/`AssetSummary` shapes, net +8 after 2 of
those 5 fixes needed no new test methods). `vision-warehouse`/`vision-app`/`storage/persistence`
not rebuilt this wave — a concurrent agent (W3) owned those modules for this same plan.

## 2026-09-01 — WAREHOUSE-UX-PLAN.md W5 / FLEET-RADIO-PLAN.md R6 — vision-app wiring breakage resolved

W5 and R6 each added a required constructor collaborator (`MaintenanceQuery`, `ReadinessService`)
that `vision-app`'s wiring had not yet been updated to pass, leaving it failing to compile. Verified
in-tree 2026-09-01 (ASSET-FLOWS wave R1): `OnboardingWiringConfiguration#readinessService` now
passes warehouse's `MaintenanceQuery` bean and `ApplicationServiceWiring#manualControlService`
passes `ReadinessService`; the `MaintenanceQuery` bean and its persistence implementation exist and
the grounding→readiness gate is wired end to end.

## docs/plans/active/WAREHOUSE-UX-PLAN.md wave W5 — maintenance grounding forces NO_GO (D6/OQ1)

`DefaultReadinessService` gained a new required `MaintenanceQuery` (warehouse) collaborator and now
forces `ReadinessVerdict.NO_GO` — overriding even `UNKNOWN` — whenever an asset carries at least one
open, flight-blocking (`GROUNDING`/`INSPECTION_DUE`) warehouse `MaintenanceRecord`, appending one
`"MAINTENANCE_GROUNDED:<kind>:<summary>"` entry per record to `ReadinessReport#blockers()` (constant
`MAINTENANCE_BLOCKER_PREFIX`). Deliberately not a new `FeatureReadiness` row (see MODULE.md's own
`ReadinessService` API-surface entry for why) — zero `vision-api`/`station/vision-web` wire changes
needed. `DefaultManualControlService#engage` now also refuses (audited `REFUSED:maintenance-grounded`)
on a grounded asset, reusing the one `readinessService.evaluate` call it already made for the R6 gate
rather than a second lookup — this is OQ1's "engage refuses" half. **Deliberately not gated by this
wave**: `DefaultFlightCommandService#arm`/`disarm` (the MAVLink command path — a different gate this
plan's own W5 row did not name) and perception's `UsageTracker` (which opens the underlying
`AssetUsage` session and lives in a different context module entirely) — flagged in
`docs/plans/active/WAREHOUSE-UX-CONTEXT.md`'s "W5 handoff" for a future wave. (The wiring breakage
this wave flagged was resolved by the wave above; the `arm`/`UsageTracker` handoff was closed by the
S1 wave above it — `disarm` stays deliberately ungated, per plan.)

## 2026-08-27 — FLEET-RADIO-PLAN.md wave R6 — rc-relay value/bit-aware readiness

Two ArduPilot settings that used to silently void every MAVLink RC override this platform sends — a
GCS-sysid mismatch (`SYSID_MYGCS`/`MAV_GCS_SYSID`, alias-aware, must equal `255`, the sysid this
platform transmits as) and `RC_OPTIONS` bit 1 (`IGNORE_OVERRIDES`) being set — are now both
readiness-checkable and both refuse `engage`, not just preflight. `FeatureRequirement` gained two
nullable fields (`requiredParameterValue`, `forbiddenParameterBits`); `DefaultReadinessService`
gained value/bit-aware comparison plus a `combine()` fold so `rc-relay`'s two new independent rows
(V27, seeded in `storage/persistence`, replacing V18's single always-trivially-satisfied placeholder
row) still surface as exactly one wire row; `DefaultManualControlService#engage` gained a new
required `ReadinessService` collaborator and refuses (audited `REFUSED:not-ready:rc-relay`) on a
`MISSING` verdict, before any device is resolved or any link opened. `ParameterTier` gained
`RC_OPTIONS` as Tier B so the row's own `PARAM_WRITE` remedy is actually reachable through the
existing R5 `POST /api/assets/{id}/parameters` endpoint. **Frozen-key decision, examined and kept**:
`FeatureRequirement.FEATURE_KEYS` stays the eleven keys — see MODULE.md's own Gotchas ("two rows
under one key, deliberately, not two new frozen keys") for the wire-shape reasoning.
**`CLEAR_OVERRIDES_BY_RC` (bit 14), examined and left as a documented gap**: it is the vehicle's
designed pilot-override behavior, not a link misconfiguration, and needs live-stick telemetry this
configuration-only table cannot see — see the adjacent Gotchas entry and V27's own migration header.
**Out of this task's scope, flagged**: `vision-app`'s `ApplicationServiceWiring#manualControlService`
still called `DefaultManualControlService`'s old constructor shape and failed to compile — resolved
by the wave above. No `station/vision-web` change was needed: the wire shape (`{featureKey: status}`)
is unchanged, and `readiness-logic.ts` already modeled `rc-relay` as a single key with a single
status. `./mvnw -B -pl contexts/vision-flight test` — **371 tests**, all green (2026-08-27; +20 from
R2's 351 — `FeatureRequirementTest`'s new value/bits validation cases, `ParameterTierTest`'s new
`RC_OPTIONS` case, `DefaultReadinessServiceTest`'s new value/bit/combine cases, and
`DefaultManualControlServiceTest`'s new engage-time rc-relay-gate cases). `storage/persistence`'s
`PostgresDockerIntegrationTest` (Docker available in this run) — **225 tests**, all green, including
the renamed `v18MigrationSeedsTwelveArdupilotFeatureRequirementRows` (12, not 11 — V27 retired one
placeholder row and added two) and the new
`v27MigrationRetiresThePlaceholderRcRelayRowAndSeedsTwoValueAwareRowsUnderTheSameKey`. `vision-api`
— **874 tests**, all green (no source changes needed there this wave). `vision-app` did not build
this wave — see the wiring-breakage entry above. `station/vision-web` — **2559 tests / 139 files**,
all green, unchanged.

## FLEET-RADIO-PLAN.md wave R2 — refuse UNKNOWN-kind manual control

`ControlProfile.forKind(UNKNOWN)` returns an empty, unflyable `ChannelMap` (code `----`,
displayName `"Unidentified vehicle"`) instead of the old centred four-axis map;
`DefaultManualControlService#engage` refuses to open a manual-control session on an `UNKNOWN`-kind
link at all, releasing the link it just opened and throwing `VehicleUnidentifiedException` with one
of three named, operator-facing reasons (`UnidentifiedReason`: `NOT_A_VEHICLE`/`UNSUPPORTED_VEHICLE`/
`NEVER_IDENTIFIED`) rather than a single undifferentiated refusal. New: `UnidentifiedReason` enum,
`VehicleUnidentifiedException`, `ManualControlLink#unidentifiedReason()` (default method). See
MODULE.md's own Gotchas for the full "why a separate enum, not a wider `VehicleKind`" rationale, and
`drone-link/mavlink/MODULE.md` / `station/vision-api/MODULE.md` for how the reason is produced and
carried onto the (additively extended, not broken) WebSocket contract. The web client needed **zero
changes** to show the new refusal — `features/fly/rc-monitor.html`'s existing `denied`-state
template already renders `client.deniedReason()` verbatim, and `manual-control-client.ts` already
forwards any `denied` frame's free-text `reason` regardless of `code`. **Deferred, not built**: an
explicit "operator picks the vehicle's kind and retries engage" control — building it needs a
kind-override parameter on `ManualControlClient.engage(assetId)`, which lives in
`manual-control-client.ts`, a file this wave's hard constraint said to stop and report on rather
than edit; independently of that constraint, trusting an operator-supplied kind over live MAVLink
classification is its own unresolved safety question, not a rushed addition here (carried into
MODULE.md's own Status as a standing gap).

## FLEET-RADIO-PLAN.md wave R4b — vehicle-kind-dependent emergency-stop (Java half)

`FlightCommandPort.emergencyStop`'s javadoc is rewritten to document the new vehicle-kind-dependent
contract; no other source in this module changed — the plan's own scope line named
`DefaultFlightCommandService.java`, but (mirroring R1's own precedent below) that class needed zero
changes, because it never sees `mavType` and the decision belongs entirely to the adapter that does.
**R4b's web half has since shipped, in R2's own task** (`station/vision-web`'s
`core/rc/control-action-logic.ts#actionLabel` now takes a `vehicleKind` and reads `'Emergency stop
(Hold)'` on a `ROVER`) — see `station/vision-web/MODULE.md` and this plan's own R4b note for what
shipped and what is still open.

## FLEET-RADIO-PLAN.md wave R1 — one vehicle taxonomy (no change here)

The plan's own scope line names `VehicleKind.java`/`ControlProfile.java` for R1, but nothing in
either needed to change: the new dodecarotor/decarotor/generic-multirotor `MAV_TYPE`s all resolve
onto the existing `COPTER` constant, and R1's own submarine slot is explicitly a `mavlink-core`-only
table entry — the plan itself (and a direct operator instruction, 2026-08-26) forbid adding a
`SUBMARINE` constant here. `VehicleKind` and `ControlProfile.forKind` were unchanged;
`ControlProfile.forKind(UNKNOWN)` still returned a flyable (if unsafe) default map at this point —
closing that gap was R2's own scope (above), not R1's.

## FLEET-RADIO-PLAN.md wave R3 (F3/F4/F17) — CH9+ fix and channel range narrowing

`RcChannels`/`ControlBinding` both narrowed to `[1,16]` (F17); the CH9+ silent-drop (F3) and
extension-channel release-sentinel (F4) defects were fixed on the `mavlink-core`/`adapter-mavlink`
side of the module boundary (see MODULE.md's own Gotchas for why nothing changed on the
wire-encoding side of *this* module, and for the full F3/F4 mechanism this fixed). Web half
(narrowing the channel picker in `station/vision-web`) deliberately deferred to
`feat/controller-ux`, out of this wave's scope — see that plan's own R3 note.
