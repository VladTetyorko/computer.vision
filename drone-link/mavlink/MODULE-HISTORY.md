# adapter-mavlink — history

Wave-by-wave narrative for `drone-link/mavlink`: what each plan wave did, when, on what test counts,
and the design deliberation behind decisions that are now just current behavior in `MODULE.md`.
**`MODULE.md` is the contract — read that first, every time.** This file is for *why*, and only when
you need it. Newest entry first. Durable protocol/adapter facts that used to live only in this
narrative have already been lifted into `MODULE.md`'s own `## Gotchas`/`## Status` in present tense —
this file keeps the full deliberation and test evidence behind them.

## `docs/plans/active/LINK-PAIRING-PLAN.md` — wave L1 (2026-09-18)

**`docs/plans/active/LINK-PAIRING-PLAN.md` wave L1 done.** `MavlinkGateway` now `implements
LinkRegistry` and opens no socket of its own — see `MODULE.md`'s API surface and Gotchas for the
full contract change (`register`/`unregister` replacing the deleted `(String, int, MavlinkSettings)`
production constructor and the FLEET-RADIO R4 `(MavlinkLink, MavlinkSettings)` test seam;
`CommandTarget.sourceAddress` now `LinkPeer`, not `InetSocketAddress`; the new `linkRegistry(int)`
accessor `vision-app`'s `CarrierWiring` uses). New `LinkRegistryTest` (6, exercises the contract
directly since `mavlink-core` ships no concrete `LinkRegistry` implementation of its own).
`MavlinkGatewayLinkFailureTest`/`MavlinkLobbyHoldTest` updated for the new construct-then-register
shape. `./mvnw -B -pl drone-link/mavlink test` — **279 tests**, all green (2026-09-18).

## `docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md` — A2 (2026-09-04)

**`docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md` A2 done.** `MavlinkTelemetrySource` gained
`intakeStatus(int port)`, `MavlinkGateway` gained a package-private `intakeStatus(String
bindAddress)` plus an `AtomicLong framesDecoded` counted from `onFrame`, and a new public record
`MavlinkIntakeStatus` composes both together with mavlink-core's own A1 `LinkIntake` (pre-parse
datagram/byte counters, `drone-link/mavlink-core`) and the gateway's unclaimed/claimed sysid lists —
the P1/P2 diagnostic: zero datagrams means nothing reaches the socket, datagrams with zero frames
decoded means garbage/wrong-protocol is arriving. `MavlinkHeartbeatScanner` also gained
`lastStatus()` (U8), mirroring `MediamtxPathScanner`'s established `SourceStatus` idiom. See
MODULE.md's Gotchas for the exact semantics, the `DEFAULT_BIND_HOST`-only resolution caveat, and
the test-seam `MavlinkLink` double's honest all-zero `LinkIntake`. New tests: `MavlinkIntakeStatusTest`
(4, real UDP loopback — a raw garbage datagram proves `datagramsReceived` advances while
`framesDecoded` stays 0; a real `HEARTBEAT` via `UdpTargetLink`/`FrameWriter` proves both advance);
3 new `lastStatus()` cases in `MavlinkHeartbeatScannerTest` (self-bind success stays `OK`, a genuine
bind conflict flips to `UNREACHABLE` and self-heals on the next scan, the hub-borrow path stays `OK`).
No port interface changed, no call site outside this module needed a change — both are new,
additive read surfaces, consistent with this wave's accepted decision that new emitters ship on by
default. `./mvnw -B -pl drone-link/mavlink test` (after `-pl drone-link/mavlink-core install
-DskipTests` to pick up A1's `LinkIntake` from a stale `~/.m2` jar) — **273 tests**, all green,
foreground/blocking run (2026-09-04).

## `docs/plans/active/FLY-CONTROL-UX-PLAN.md` — H2 (2026-09-02)

**`docs/plans/active/FLY-CONTROL-UX-PLAN.md` H2 done — already unified, proved rather than fixed.**
The rover firmware's learned-peer authority gate (MAVLINK-COMMANDS-PLAN F4, extended by the H1
firmware note to cover `RC_CHANNELS_OVERRIDE` too, not just `COMMAND_LONG`) requires the station to
present one wire identity toward a vehicle for telemetry RX and every command TX path. Traced and
confirmed already true, no production code changed: `MavlinkFlightCommander.send` and
`MavlinkManualControlSender.engage` both build their `mavlink-core` service (`CommandService`/
`ManualControlService`) from `gateway.sink()`/`gateway.correlator()`/`gateway.peers()` — the *same*
`MavlinkGateway` (one `UdpListenLink`, one `MavlinkSession`) telemetry RX registered against, never a
socket of their own; `MavlinkTelemetrySource.open`/`holdLobby` both key into the identical
`gateways.compute(bindKey, ...)` map, so a zero-config-announced rover's later `open()` reuses the
exact gateway the standing lobby already held (`MavlinkHeartbeatScanner.toDiscoveredDevice` builds its
`StreamDescriptor` URI from the wildcard `DEFAULT_BIND_HOST`, never the vehicle's own learned address,
so no split-socket risk there). RC-override TX already had a proof test
(`MavlinkManualControlSenderTest.engageStartsAFixedRateSenderThatCarriesSentChannelsToTheVehicleOnTheSharedSocket`,
asserting the vehicle receives the frame from the exact local port telemetry is bound to); this wave
closed the matching gap for `COMMAND_LONG` — new
`MavlinkFlightCommanderTest.sendsCommandLongFromTheSameLocalPortTelemetryIsBoundToOnTheSharedSocket`
and a small `lastCommandSourcePort()` capture on that test's `FakeVehicle` double, both test-only. One
residual, narrow, unfixed risk documented (not a defect): the gateway binds the wildcard host, so the
OS — not this code — chooses which local interface IP labels an outgoing datagram's source; a station
host changing its own primary IP mid-session (multi-homed roam/DHCP renewal) could momentarily mismatch
the rover's learned identity, self-healing via the firmware's own 500ms re-learn window
(`infra/rover-sim/link_test.cpp`'s `commandTimeoutMs`) rather than staying stuck. Full verdict with
file:line citations: `docs/plans/active/fly-control-ux/R3-handshake-denial.md`'s "Station identity
note".
`./mvnw -B -pl drone-link/mavlink -am test` — **266 tests**, all green, foreground/blocking run
(2026-09-02).

## `docs/plans/active/ASSET-FLOWS-PLAN.md` — C5 (2026-09-01)

### Gotchas (moved verbatim)

- **The false positive named by ARCHITECTURE-AUDIT-2026-08-26 D3 was real, but narrower than "every
  `supports()` is dishonest".** Before this wave, `MavlinkFlightCommander.supports(Device)` delegated
  straight to `MavlinkTelemetrySource.supports(Device)` — "is this a MAVLink device at all" — so a
  claimed Betaflight aircraft answered `true` even though every command method already rejects it via
  `requireCommandableFirmware`. The consequences were real but already partly mitigated: `capabilities(
  Device)` (what the web command panel actually reads, `GET /api/assets/{id}/flight-capabilities`) was
  already firmware-honest before this wave — MAVLINK-COMMANDS-PLAN's own `requireCommandableFirmware`
  and this method's own `!FIRMWARE_ARDUPILOT.equals(target.firmware())` guard already made it report
  `notCommandable()` for Betaflight. The two live gaps `supports()` itself left open: (1) a caller that
  trusts `supports()` alone instead of the finer-grained `capabilities()` — the port's own javadoc
  explicitly *allows* this ("does not by itself guarantee any command will succeed"), which is exactly
  what made the false positive legal-by-contract; (2) `contexts/vision-flight`'s
  `DefaultFlightCommandService.firstCommandableDevice` filters an asset's devices on this exact method
  and "silently takes the first" match (its own class javadoc) — a decoy Betaflight device sharing an
  asset with a real ArduPilot one could shadow the commandable device entirely, turning both
  `resolveForCommand` and `capabilities()` dishonest for that asset even though a genuinely commandable
  device exists on it. Neither gap needed a `contexts/vision-flight` file touched to fix — both close
  once `MavlinkFlightCommander.supports(Device)` itself stops claiming what `requireCommandableFirmware`
  already knows it will refuse.
- **"Never heard from" deliberately stays `supports() == true` — this is not the "unknown-safe" default
  by inertia, it is the more-honest of the two choices, not the less.** The tempting "fully honest"
  design would be `supports()` mirroring `capabilities()`'s own guard exactly (`target == null` also
  ⇒ `false`). Rejected: `resolveReachableTarget`'s own reachability check already throws a specific,
  named message ("no MAVLink vehicle has ever been heard for device … you cannot command what you
  cannot hear") for that exact case, and `capabilities()` already answers `notCommandable()` for it too
  — both through their own, more specific mechanism. Flipping `supports()`'s never-heard case to `false`
  would not make either of those more honest; it would only make `DefaultFlightCommandService.
  firstCommandableDevice` silently skip a device instead of surfacing that specific, useful message,
  trading a precise diagnostic for a vaguer one with no honesty gained. "Honest-unknown" only wins over
  "keep current behavior" when the current behavior is actually claiming something false — here it
  isn't, because nothing downstream trusts `supports()` as the final word.
- **The internal reachability guard (`resolveReachableTarget`) must check `telemetrySource.supports(
  device)` directly, never this class's own (now firmware-aware) `supports(Device)`.** Every command
  method calls `resolveReachableTarget` *before* `requireCommandableFirmware` (directly, or via
  `resolveCustomMode`). Had `resolveReachableTarget` kept calling `this.supports(device)`, a claimed
  Betaflight device would now fail *that* guard first, throwing the generic "`MavlinkFlightCommander`
  does not support device: …" message — silently replacing `requireCommandableFirmware`'s specific,
  already-tested messages (naming Betaflight; "not commandable in DRONE-INFRA I-e (ArduPilot/INAV
  only)") with a strictly worse one. Caught by hand before it ever reached a red test: this is exactly
  the kind of two-call-sites-of-the-same-method landmine a firmware-aware `supports()` creates, and the
  reason this class's own class javadoc now calls the split out explicitly rather than leaving it to be
  rediscovered.
- **`MavlinkManualControlSender`/`MavlinkVehicleConfigurator` were deliberately left protocol-only —
  not an oversight, a researched decision.** `docs/plans/active/OPERATOR-CONTROL-CONTEXT.md` §2.1 (the
  same investigation ARCHITECTURE-AUDIT D3 cites) corrected an earlier premise: Betaflight has accepted
  `RC_CHANNELS_OVERRIDE` (#70) via its own `rx/mavlink.c` since ~2025.12.0-beta — the *same* wire
  message `MavlinkManualControlSender` sends, unlike `MAV_CMD_DO_SET_MODE`/`MAV_CMD_COMPONENT_ARM_DISARM`,
  which Betaflight's RC link genuinely never processes at any firmware version. There is therefore no
  firmware-family fact this module can check (only `HEARTBEAT.autopilot`'s coarse ardupilot/generic/px4
  label is decoded — no firmware *version*) that would make gating `ManualControlPort.supports()` on
  "generic" honest; doing so would trade today's real false positive (Betaflight claims a verb it can
  never do) for a new false negative (a modern Betaflight build genuinely honoring RC override reported
  as unsupported). `MavlinkVehicleConfigurator.probe`/`readParams`/`writeParam` have no equivalent
  firmware-verb question at all — probing is protocol-level by design (best-effort, never throws for an
  incomplete answer; `VehicleProfile.complete()`/`incompleteReason()` already carry the honesty this
  port needs), so there is nothing for its `supports(Device)` to be dishonest about.
- **Tests**: `supportsReturnsFalseForAClaimedBetaflightVehicleEvenThoughTheProtocolMatches` and
  `supportsReturnsTrueForAClaimedArdupilotVehicle` (`MavlinkFlightCommanderTest`, real UDP loopback via
  the existing `FakeVehicle` double) — the former also asserts `telemetrySource.supports(device)` stays
  `true` for the same device, proving this is a genuine `supports()`-vs-`supports()` disagreement, not a
  change in protocol detection. The pre-existing "can never disagree" test was renamed
  (`supportsMatchesTheTelemetrySourcesOwnProtocolCheckForADeviceNeverYetHeardFrom`) to scope its own
  claim to the case it actually covers (a device never yet claimed) rather than the general case, which
  is no longer true.

### Status (moved verbatim)

**`docs/plans/active/ASSET-FLOWS-PLAN.md` C5 done** (ARCHITECTURE-AUDIT-2026-08-26 D3 — the Betaflight
`supports()` false positive). `MavlinkFlightCommander.supports(Device)` is now firmware-honest: `false`
for a device whose most-recently-heard firmware is a *known* non-commandable one (today: any firmware
label other than `"ardupilot"`, once heard — matching `requireCommandableFirmware`'s own definition
exactly), `true` for protocol-match-plus-never-heard, unchanged. The internal reachability guard
(`resolveReachableTarget`) now deliberately checks `telemetrySource.supports(device)` directly rather
than this class's own `supports(Device)`, so every command's specific firmware-rejection message
(naming Betaflight, or ArduPilot/INAV-only) still surfaces exactly as before — only the public
`supports()` answer changed. `MavlinkManualControlSender`/`MavlinkVehicleConfigurator`'s own
`supports(Device)` were deliberately left protocol-only, a researched decision (not an oversight) —
see the C5 Gotchas above for why Betaflight is not a false positive for `RC_CHANNELS_OVERRIDE` the way
it is for `MAV_CMD_DO_SET_MODE`/`MAV_CMD_COMPONENT_ARM_DISARM`, and why onboarding probing has no
firmware-verb question to be dishonest about at all. No port interface changed; no call site outside
this module needed a change — `contexts/vision-flight`'s `DefaultFlightCommandService` and
`FlightCommandController` already read `capabilities()`, which was already firmware-honest before this
wave, so this closes a latent dishonesty in `supports()` itself and the `firstCommandableDevice`
device-selection edge case, not a currently-user-visible cockpit bug. New tests:
`supportsReturnsFalseForAClaimedBetaflightVehicleEvenThoughTheProtocolMatches`,
`supportsReturnsTrueForAClaimedArdupilotVehicle`; the pre-existing "can never disagree" test was
renamed to scope its claim correctly (see the C5 Gotchas above).
`./mvnw -B -pl drone-link/mavlink -am test` — **265 tests**, all green, foreground/blocking run
(2026-09-01).

## `docs/plans/active/MAVLINK-COMMANDS-PLAN.md` — P2 (2026-09-01)

### Gotchas (moved verbatim)

- **Negotiation is best-effort, by design — nothing it sends can fail a claim.**
  `MavlinkStreamNegotiator.negotiate` is fired from inside `VehicleClaimPolicy.assignClaim`'s own
  monitor, but every send it makes is async (`CompletableFuture`, never blocking); `UNSUPPORTED`/
  `DENIED`/`NO_ACK` on any one message is logged (INFO for the honest-refusal codes, WARNING for a
  send-level fault or an unexpected result) and the chain simply continues to the next message. A
  vehicle that ignores every request still claims normally and streams whatever it already streams —
  P2 can only make a link richer, never worse.
- **Unconditional by default (no flag of its own) — except the interval chain steps aside for
  Mechanism A, and that exception is a real wire-level constraint, not a policy choice.**
  `MAV_CMD_SET_MESSAGE_INTERVAL`'s `COMMAND_ACK` correlates on `(origin sysid, command id)` only,
  never on which message id was asked for (see MODULE.md's Gotchas — the `COMMAND_ACK` correlation
  entry — written for `MavlinkConnectRemediator` but equally true here) — so this negotiator's own
  interval chain and `MavlinkConnectRemediator`'s on-connect chain cannot both hold a live
  `Correlator.await` for the same peer's `(sysid, 511)` key at once; the second registration throws
  `IllegalStateException`, logged as a WARNING "could not be sent" by whichever side loses the race.
  Discovered this wave via `MavlinkConnectRemediationIntegrationTest` going red the moment P2 shipped
  unconditionally. Fixed by having `MavlinkStreamNegotiator`'s constructor read
  `settings.onboarding().requestMessagesOnConnect()` once, into `intervalChainOwnedByMechanismA`, and
  skip its own `negotiateStreams` call entirely when it is `true` — Mechanism A already owns that
  exact job for that peer when an operator has explicitly turned it on. The `AUTOPILOT_VERSION` probe
  is unaffected (a different correlator key, `(sysid, 512)`) and always runs regardless of the flag.
- **The same collision reaches `MavlinkVehicleConfigurator`'s manual, operator-invoked endpoints too
  — a second, broader manifestation the flag-based fix above does not cover, since these calls are
  not gated by any flag.** `probe`'s `AUTOPILOT_VERSION` request and `requestMessageInterval` share
  their correlator keys with `MavlinkStreamNegotiator`'s probe/interval chain respectively, and an
  operator can call either at any time — including moments after a claim, while this negotiator's own
  chain is still in flight. Before this wave's fix, that race produced an instant, spurious
  `IllegalStateException` → `NO_ACK`/no-report the moment two local collaborators wanted the same key,
  which is dishonest: it reads exactly like the aircraft never answered, when in fact the request was
  never sent at all. Caught by `MavlinkVehicleConfiguratorTest.requestsThatAreSentToAReachableAircraftAndGoUnansweredTimeOutIntoNoAck`'s
  own elapsed-time assertion ("must actually have been sent and waited on, not short-circuited"; it
  failed at ~5ms instead of waiting out `ackTimeout`). Fixed with a new private
  `MavlinkVehicleConfigurator.awaitWithContentionRetry` (plus `CORRELATOR_CONTENTION_BACKOFF = 100ms`):
  on an `IllegalStateException`-caused `ExecutionException`, it retries a **fresh** exchange (a new
  send, a new registration — replaying the same failed future would just fail again) after the
  backoff, bounded by the same overall budget the call already computes; any other outcome (a real
  reply, a real timeout, an unrelated fault) is returned exactly as the plain `await` helper already
  would. Only `requestCapabilities`/`requestMessageInterval` use it — `readParams`/`writeParam`
  correlate on `PARAM_VALUE`, a key `MavlinkStreamNegotiator` never touches, so they keep the plain
  `await`.
- **`MavlinkSitlOnConnectIntegrationTest`'s "starved baseline" premise no longer holds, and its proof
  message had to change.** `StreamNegotiation.defaults()` requests `VFR_HUD` (74) unconditionally —
  the same message id the test used to prove Mechanism A's flag caused something. A connection with
  the flag off is no longer starved of `VFR_HUD`; it is starved of nothing P2 already asks for. The
  test now uses `SERVO_OUTPUT_RAW` (36) as its proof message instead — one of Mechanism A's four
  P2-exclusive on-connect ids (`SYS_STATUS`=1, `SERVO_OUTPUT_RAW`=36, `SCALED_IMU2`=116,
  `SYSTEM_TIME`=2), never requested by P2's own default set, so seeing it arrive is still unambiguous
  proof the flag — not P2 — caused it. Re-run against real SITL after the rewrite (docker+image
  present): the flag-off connection's `MavlinkStreamNegotiator` chain got all six messages
  `ACCEPTED` by ArduPilot 4.7.0, and the flag-on connection's own `MavlinkConnectRemediator` chain ran
  cleanly afterward with zero collisions, confirming the `intervalChainOwnedByMechanismA` fix
  end-to-end against real firmware, not just the fake-vehicle unit tests.
- **`MavlinkFlightCommander.capabilities(Device)` is not wired to this probe's `CapabilityReport` this
  wave — a known, deliberate scope boundary, not an oversight.** The on-claim `AUTOPILOT_VERSION`
  probe's result is only logged, never cached or exposed through the existing `capabilities()` port
  method; a future wave that wants a claim-time capability cache to back that method can build one,
  but P2's own brief was the negotiation itself.
- **Production automatically inherits `StreamNegotiation.defaults()` the moment this module is
  rebuilt — no `vision-app` change required, unlike `commandRetries`' still-open gap above.**
  `TelemetryWiring#toMavlinkSettings` builds `MavlinkSettings` via the 8-arg back-compat constructor,
  which this wave updated (like every other back-compat constructor and `withXxx` method on this
  record) to append `StreamNegotiation.defaults()` automatically. Unlike `MavlinkFlightCommander`'s
  `ackTimeout` gap (see this file's MAVLINK-COMMANDS-PLAN P1 entry — later closed by P4), there is no
  separate, stale constructor overload in the way here — `MavlinkGateway` always builds its
  `MavlinkStreamNegotiator` off whatever `MavlinkSettings` it is constructed with, so there is nothing
  left for a future wave to thread through before this is live in production; the only future work is
  making the six ids/rate *configurable* (a `VisionMavlinkProperties` field), not making negotiation
  *happen*.

### Status (moved verbatim)

**`docs/plans/active/MAVLINK-COMMANDS-PLAN.md` P2 done.** New `MavlinkStreamNegotiator`
(package-private): on every claim, one `MAV_CMD_REQUEST_MESSAGE`(512) `AUTOPILOT_VERSION` probe, then
(unless `Onboarding.requestMessagesOnConnect()`/Mechanism A already owns the peer's interval channel)
one `MAV_CMD_SET_MESSAGE_INTERVAL`(511) per new `MavlinkSettings.StreamNegotiation.defaults()` entry —
`ATTITUDE`/`GLOBAL_POSITION_INT`/`VFR_HUD`/`RC_CHANNELS`/`GPS_RAW_INT`/`BATTERY_STATUS` at 250ms each.
No enable flag of its own, by design; every outcome is logged and none of them fail the claim. Hook is
`VehicleClaimPolicy`'s new `IntConsumer onClaimed` constructor param, wired in `MavlinkGateway`'s
constructor as `streamNegotiator::negotiate`, invoked from `assignClaim` exactly once per claim event.
`MavlinkSettings` grew a 13th field/`StreamNegotiation` nested record and a `withStreamNegotiation`
wither; every back-compat constructor and existing wither defaults it, so `vision-app`'s
`TelemetryWiring` (an 8-arg-constructor call site, out of this wave's file scope, unmodified) picks up
`StreamNegotiation.defaults()` automatically the moment this module rebuilds — unlike P1's still-open
`commandRetries`/`ackTimeout` gap, this one is not dormant. Two real, previously-latent production bugs
surfaced and were fixed this wave, both stemming from `MAV_CMD_SET_MESSAGE_INTERVAL`'s `COMMAND_ACK`
correlating on `(sysid, command id)` only, never on message id: (1) `MavlinkStreamNegotiator`'s own
interval chain colliding with `MavlinkConnectRemediator`'s, fixed by having the former step aside
entirely when Mechanism A is active; (2) the same collision reaching `MavlinkVehicleConfigurator`'s
manual `probe`/`requestMessageInterval` endpoints (no flag gates those), fixed by a new
`awaitWithContentionRetry` helper that retries a fresh exchange after a short backoff instead of
surfacing an instant, dishonest "no reply". See the P2 Gotchas above for both in full, including the
real-SITL re-verification of fix (1) and the message-id rewrite fix (1) forced on
`MavlinkSitlOnConnectIntegrationTest` (its "starved baseline" proof message, `VFR_HUD`, is now one of
P2's own six defaults, so it switched to `SERVO_OUTPUT_RAW` — a message id exclusive to Mechanism A's
own on-connect set). New tests: `MavlinkStreamNegotiatorTest` (3, real UDP loopback against a local
`FakeVehicle`); 4 new `MavlinkSettingsTest` cases for `StreamNegotiation`; `MavlinkFlightCommanderTest`
and `MavlinkConnectRemediationIntegrationTest`'s shared `FakeVehicle` test doubles gained an auto-drain
mechanism so P2's now-unconditional negotiation traffic never surfaces as an unexpected `COMMAND_LONG`
to a test asserting on its own, unrelated command.
`./mvnw -B -pl drone-link/mavlink -am test` — **263 tests**, all green, foreground/blocking run
(2026-09-01); `MavlinkSitlOnConnectIntegrationTest` re-run individually against real SITL
(docker+image present) also green, confirming the Mechanism A step-aside end-to-end against real
ArduPilot 4.7.0, not just fake-vehicle unit tests.

## `docs/plans/active/MAVLINK-COMMANDS-PLAN.md` — P1 (2026-09-01)

### Gotchas (moved verbatim)

- **The force-arm magic was `21196` (the force-*disarm* magic) until this wave — a live defect, not
  a matter of style.** `MAV_CMD_COMPONENT_ARM_DISARM`'s param2 "force" magic is `2989` on the arm
  path and `21196` on the disarm path (docs/plans/active/MAVLINK-COMMANDS-PLAN.md D2b, verified
  against ArduPilot's own arming docs and issues #32996/#26521); `armOrDisarm` sent `21196` on both
  before this wave. It "worked" for a forced arm only because ArduPilot firmware silently *bypasses
  every pre-arm check* on receiving the wrong magic instead of correctly rejecting the malformed
  request — every forced arm ever sent by this class before this wave has been an accidental
  safety-check bypass, not the deliberate "I know what I'm doing, force it" the `force=true` flag is
  supposed to mean. Now split into `ARM_FORCE_MAGIC = 2989f` / `DISARM_FORCE_MAGIC = 21196f`,
  selected by which value `armParam` is; the forced-disarm value is unchanged (still correctly
  `21196` — `emergencyStop`'s unconditional forced disarm is byte-identical to before). This defect
  and the direction-specific magic it produced are now current-state facts in MODULE.md's own Gotchas.
- **Retry eligibility is frozen at the call site, not derived from the command type.** `send`'s
  private `boolean retryable` parameter is the one place this decision lives (docs/plans/active/
  MAVLINK-COMMANDS-PLAN.md D2a) — `true` only for *absolute-state* commands, where resending after
  silence is safe because the state requested does not change between attempts (`mavlink-core`'s
  `CommandService` itself increments `confirmation` per attempt, so the vehicle can tell a resend
  from a fresh request, per MAVLink's own confirmation-field contract). All four current callers
  (`setMode`, `armOrDisarm`, `emergencyStopRover`, `auxFunction`) pass `true`, each with an inline
  comment naming why. Any future *relative/incremental* command (e.g. a delta `DO_REPOSITION`) **must
  pass `false`** — resending it would double-apply the delta, which the whole point of silence-only
  retry must never risk. A terminal `COMMAND_ACK` (`DENIED`, `UNSUPPORTED`, …) never triggers a
  retry regardless of `retryable` — `mavlink-core`'s `RequestResponse.retryOrFail` only resends on
  `TimeoutException`, confirmed by reading it directly and covered by
  `aDeniedAckIsTerminalAndNeverTriggersARetry`.
- **`vision.mavlink.command-retries` default of 2 is gated on `infra/rover-sim`'s F0 idempotency
  case** (a repeated `COMMAND_LONG` with a rising `confirmation` must be a firmware no-op, never a
  double-effect) — run by a different wave/agent in parallel with this one. Shipped as 2 regardless,
  per this wave's own instruction; if F0 finds the firmware non-idempotent, the fix is one line
  (`DEFAULT_COMMAND_RETRIES` in `MavlinkSettings`, or `vision.mavlink.command-retries: 0` in
  `application.yaml`), not a design change — the retry machinery itself doesn't care what the budget
  is, `armReturnsNoAckAfterExhaustingEveryConfiguredAttempt` reads the live default rather than
  hardcoding "3" for exactly this reason.
- **P1-era "known production gap" — CLOSED by MAVLINK-COMMANDS-PLAN P4 (`169f8665`), kept for
  history.** P1 could not touch `vision-app`, so the deployed `ackTimeout` briefly stayed 2s (worst
  case ~6s, not the intended ~2.1s). P4 then added `commandRetries` to `VisionMavlinkProperties`
  (11th component), flipped `ack-timeout`'s `@DefaultValue` to 700ms, and switched
  `TelemetryWiring#mavlinkFlightCommander` onto the canonical
  `(MavlinkTelemetrySource, MavlinkSettings)` constructor — production is 700ms × 3 attempts
  (verified against `TelemetryWiring.java` 2026-09-01; `TelemetryWiringCommandRetryWiringTest`
  pins it). Do not re-report this from stale reads of this section.
- **`MavlinkFlightCommanderTest`'s retry tests read `MavlinkSettings.defaults()` for the expected
  attempt count rather than hardcoding it**, so they stay correct if a future wave (e.g. the
  F0-triggered flip above) changes `DEFAULT_COMMAND_RETRIES`; the ack-on-retry test additionally
  `assumeTrue`s `commandRetries() >= 1` so it skips cleanly (not red) if that default ever drops to 0.

### Status (moved verbatim)

**`docs/plans/active/MAVLINK-COMMANDS-PLAN.md` P1 done.** Three independent fixes: (1) the force-arm
magic defect — `2989`/`21196` split, previously both paths sent `21196` and an ArduPilot firmware bug
silently converted every forced arm into an unintended safety-check bypass (see the P1 Gotchas above
for the full defect writeup); (2) bounded retry for absolute-state commands — `MavlinkSettings`
gained `commandRetries` (default 2) alongside `ackTimeout` re-scoped to per-attempt (default 700ms),
`MavlinkFlightCommander#send` gated by a frozen-at-call-site `retryable` parameter, all four current
callers eligible; (3) `FlightModes.selectableModes` trims ArduRover's `"Initialising"` (custom_mode
16, a boot transient) from the operator-facing list while `name`/`customModeFor` keep it fully
decodable inbound. New tests: `armSucceedsWhenTheAckOnlyArrivesOnTheSecondAttempt`,
`armReturnsNoAckAfterExhaustingEveryConfiguredAttempt`, `aDeniedAckIsTerminalAndNeverTriggersARetry`
(`MavlinkFlightCommanderTest`); `defaultsCarryTheD2aRetryPolicy`, `commandRetriesRejectsANegativeValue`,
`bothBackCompatConstructorsDefaultCommandRetriesToTheD2aDefault`,
`withAckTimeoutAndWithCommandRetriesReplaceOnlyThatOneField` (`MavlinkSettingsTest`); existing
`armSendsComponentArmDisarmWithForceMagicAndReturnsAccepted` updated to assert `2989.0f`;
`selectableModesForARoverIncludesDockAndCircleButTrimsInitialising` (renamed/extended) asserts
`Initialising` absent from the selectable list while a sibling test still proves `name()` resolves it.
**Known gap, not closed by this wave** (out of its file scope): `vision-app`'s `TelemetryWiring` still
wires `MavlinkFlightCommander` off `VisionMavlinkProperties.ackTimeout()` (2s default) via the 2-arg
back-compat constructor, so production's actual per-attempt wait stays 2s, not 700ms, until a future
wave threads a `commandRetries` field through `VisionMavlinkProperties`/`TelemetryWiring` — see the P1
Gotchas above for the worst-case latency this leaves (~6s, not the ~2.1s D2a intends). **This gap was
later closed by P4 — see the P1 Gotchas entry above.**
`./mvnw -B -pl drone-link/mavlink -am test` — **256 tests**, all green (2026-09-01).

## `docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md` — Z2b (2026-08-31)

### Gotchas (moved verbatim)

- **The lobby hold must never appear in `VehicleClaimPolicy.registrations` — it is a completely
  separate `AtomicBoolean lobbyHeld` on `MavlinkGateway`, not a fake `Device`.** An unpinned
  registration claims the first sysid heard (`VehicleClaimPolicy.claim`); if a lobby hold registered
  one to keep itself alive, it would steal the claim from whichever real device is actually meant to
  own that vehicle. `holdLobby()`/`releaseLobby()` touch only the gateway's own flag and (on release)
  query `claimPolicy.isEmpty()` — they never call `register`/`unregister`.
- **`unregister()`'s self-close condition is now `empty && !lobbyHeld.get()`, and `releaseLobby()`'s
  is the mirror: clear the flag, then close only `if (claimPolicy.isEmpty())`.** These are two
  independent multi-step operations racing on the same gateway with no shared lock beyond
  `VehicleClaimPolicy`'s own monitor (for `remove`/`isEmpty`) and the `AtomicBoolean` CAS (for
  `lobbyHeld`). Both orderings were checked by hand: whichever of the two operations *reads* the
  other's just-written state sees it (the CAS and the synchronized query are each individually
  sequentially consistent), so there is no interleaving where both operations observe "someone else
  is still holding this open" and the gateway leaks open forever, nor one where both decide to close
  while the other's caller still believes it has a live registration/hold.
- **A genuine link failure closes the gateway unconditionally, lobby held or not.**
  `MavlinkGateway.close()` always runs `stopLobbyHeartbeat()` first (idempotent — a no-op if no hold
  is active), so `handleLinkFailure` → `close()` (FLEET-RADIO R4/F7's existing wiring, unchanged)
  tears down the heartbeat scheduler exactly as it tears down every other collaborator, before
  evicting the gateway from `MavlinkTelemetrySource`'s `gateways` map. `lobbyHeld` itself is **not**
  cleared by `close()` — harmless, since a closed gateway is discarded, never reused; only
  `isClosed()` is the contract callers actually rely on.
- **Healing after close reuses `open(Device)`'s existing `compute` replace-when-closed logic — it was
  not new code to write, only to route `holdLobby(int)` through.** Both `holdLobby(int)` and
  `open(Device)` call the identical `gateways.compute(bindKey, (key, existing) -> existing == null ||
  existing.isClosed() ? newGateway(...) : existing)`; a lobby hold on a port whose gateway has already
  self-closed (link failure, or a zero-device release) gets a fresh, working gateway on the very next
  `holdLobby` call, not a resurrected dead one.
- **GCS heartbeat TX is owned by the hold itself, not by `MavlinkTelemetrySource`.**
  `holdLobby()` builds one `DefaultTxScheduler` + core `HeartbeatService` (constructed off the
  gateway's own `MavlinkSession` — `session.sink()`/`session.peers()`, so identity is GCS 255/190 for
  free) and calls `start()`; `releaseLobby()` and `close()` both call `stop()`+`scheduler.close()`
  through the same private `stopLobbyHeartbeat()`, stored in an `AtomicReference` so a concurrent
  close and release can't both tear it down or leak it. Mirrors
  `MavlinkManualControlSender`'s pattern of owning a `DefaultTxScheduler` for its own TX lifetime,
  except per-hold rather than per-instance, since a hold can be acquired and released many times.
- **`HeartbeatService` only replies to a link's *last-learned* peer — accepted, not fixed.** Its
  `emitHeartbeat()` (mavlink-core, unchanged) sends one heartbeat per distinct `LinkId` known to
  `PeerDirectory#peers()`, addressed at whatever peer was most recently heard on that link. With
  several vehicles simultaneously announcing on the same held port, the reply rotates between them —
  each new announcer's own next heartbeat re-targets it, so every vehicle transmitting at its own
  ≥1 Hz PX4-convention rate still gets its lock-on within a few seconds; nothing here queues or
  fans out one heartbeat per known peer.
- **`MavlinkHeartbeatScanner` needed no code change.** Its existing `hasActiveHub(bindKey)` check
  already steers `scan()` onto the hub-borrow path whenever *any* gateway is registered at that
  bind key — a lobby-held gateway satisfies that exactly like a device-backed one always has, so the
  unclaimed-vehicle registry the lobby accumulates is already the scanner's discovery feed with zero
  scanner changes.
- **`MavlinkLobbyHoldTest` covers this wave** (real UDP loopback throughout, no mocks): hold-then-open
  and open-then-hold both reuse one `MavlinkGateway` (asserted by reference identity, package-private
  `gateway(bindKey)`) and the device's claim still works; release with zero devices closes and frees
  the socket (a probe re-bind after release must succeed); release with a live device leaves the
  gateway open and clears only the flag; a lobby hold alone never claims a heard sysid (stays in
  `unclaimedVehicles` until a real device registers and claims it); a lobby hold heals after its
  gateway has already closed; the heartbeat scheduler is proven to start on hold and stop on release
  by actually listening for (and later for absence of) a `HEARTBEAT` reply on a hand-built fake
  vehicle's own UDP socket, not by inspecting threads; a dedicated `FailingLink`-based gateway-level
  test proves a genuine link failure closes a held gateway regardless of the hold; the scanner test
  asserts both `hasActiveHub` and an actual successful `scan()` discovery through a held-only port.

### Status (moved verbatim)

**`docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md` §11 Z2b done.** `MavlinkTelemetrySource`
gained `holdLobby(int port)`/`releaseLobby(int port)` — the claim-free "standing lobby" that keeps a
gateway's socket bound (and its GCS heartbeat replying) with zero device registrations, so a vehicle
broadcasting to a well-known port (PX4 convention: :14550) locks unicast onto this app before any
operator has added a device for it. Mechanism: an `AtomicBoolean lobbyHeld` + `AtomicReference`-held
`DefaultTxScheduler`/`HeartbeatService` pair directly on `MavlinkGateway`, never a fake `Device` or a
`VehicleClaimPolicy` registration — see the Gotchas section above for the full race-safety and
heal-after-close reasoning. `VehicleClaimPolicy` gained one new query, `isEmpty()`. No new
`MavlinkSettings` knob was needed — the hold reuses `MavlinkGateway`'s existing `coreSettings`
(derived from `MavlinkSettings.closeJoinTimeout()`) and mavlink-core's own 1 Hz `HeartbeatService`
default. New `MavlinkLobbyHoldTest` (9 tests, real UDP loopback, no mocks).
`./mvnw -B -pl drone-link/mavlink -am test` — **249 tests**, all green (2026-08-31).

## `docs/plans/active/OPERATOR-UX-4-PLAN.md` — N1 / W4 (2026-08-29)

**`docs/plans/active/OPERATOR-UX-4-PLAN.md` N1 (W4) done.** `PositionAndPowerState.applyPosition`
now takes `boolean hasGpsFix` (`FlightStatusState.hasGpsFix()`, the caller — `MavlinkTelemetryDecoder`
— resolves it fresh per `GLOBAL_POSITION_INT`); `lat`/`lon` are recorded only when true, else both
set `null` — see MODULE.md's Gotchas (the GPS-fix entry) for the full defect/fix/test-impact
writeup. 4 new decoder tests (`noGpsFixSampleHasNoPosition`,
`a3dGpsFixSampleHasAPosition`, `a2dGpsFixIsAlsoSufficientForAPosition`,
`gpsFixLostMidSessionDropsThePosition`); 4 pre-existing `MavlinkTelemetryDecoderTest` fixtures that
asserted on a bare `GlobalPositionInt` with no preceding fix now prime one first
(`mapsGlobalPositionIntWithUnitConversions`, `heartbeatEmitsASampleWithoutChangingAnyOtherField`,
`locksOntoTheFirstSystemIdAndIgnoresAllOthers`,
`preExistingPositionBatteryAndHeadingMappingsAreUnchangedAlongsideFlightState`) — the position values
themselves are unchanged, only the fixture setup. `./mvnw -B -pl drone-link/mavlink -am test` —
**240 tests**, all green (2026-08-29).

## `docs/plans/active/FLEET-RADIO-PLAN.md` — R7 (2026-08-27)

**`docs/plans/active/FLEET-RADIO-PLAN.md` R7 test half done — F11 closed.** New
`MavlinkSitlRoverIntegrationTest` (1, docker-and-image gated exactly like the other four `MavlinkSitl*`
tests — see `SitlContainer`) drives a genuine ArduRover SITL instance through arm → mode change → RC
override and asserts all three of R7's own claims against real firmware: (1) the vehicle's own live
`HEARTBEAT.type` (10, GROUND_ROVER) fed into `FlightModes.selectableModes` resolves the rover table
with `"Dock"` present — a copter table can never contain it; (2) commanding `"Circle"` (one of R1's
own three newly-added modes, not a mode the pre-R1 table already had) is confirmed by the vehicle's
own *subsequent heartbeat* reporting `mode=Circle`, not merely by an `ACCEPTED` ack; (3) an RC
override on **extension channel 9** (R3/F4's exact bug) is confirmed by reading the vehicle's own
`RC_CHANNELS` (#65) telemetry back over a **second, independent** MAVLink connection opened straight
to SITL's own `serial2` control port (see `SitlContainer.serial2Port()`) — a channel this
module's production code never opens or reads — proving `chan9Raw` actually changes from its
pre-override baseline to the exact value the override placed there. See this test's own class javadoc
for the one honestly-scoped caveat: it proves the override changed the vehicle's belief about its RC
input, not that an `RCx_OPTION` aux function bound to CH9 would fire (this module deliberately keeps
no table of aux function numbers — see MODULE.md's Gotchas on `MavlinkFlightCommander.auxFunction`
— so asserting on one here would mean asserting on a guessed magic number, not on anything R3 changed).
`SitlContainer` gained a `vehicle` parameter (`start(purpose, port, sysid, speedup, vehicle)`, the
three pre-existing overloads unchanged and still default to the image's own `copter`) and a
`serial2Port()` accessor for exactly this second-connection use case. Measured stable across three
consecutive runs: **~15.8s each**, no flakiness observed. `./mvnw -B -o -pl drone-link/mavlink test` —
**236 tests**, all green (2026-08-27, Docker available and used, not skipped).

## `docs/plans/active/FLEET-RADIO-PLAN.md` — R4 (2026-08-27)

### Gotchas (moved verbatim)

- **`MavlinkGateway`'s field was widened from `UdpListenLink` to the `MavlinkLink` interface purely
  to get a clean test seam — this cost nothing in production.** Every use of the field
  (`close()`, `id()`, passing it to `session.addLink(link)`) was already declared on `MavlinkLink`
  itself; nothing about production behavior changed. The alternative considered and rejected was
  reflection-based sabotage of a real `DatagramSocket` (closing it out from under `UdpSocketIo`/
  `UdpListenLink` via two or more private-field hops) to force a genuine `IOException` for
  `MavlinkGatewayLinkFailureTest` — rejected as fragile and invasive compared to a one-parameter
  package-private constructor overload injecting a hand-built `MavlinkLink` double.
  **Superseded by LINK-PAIRING L1**: the single `UdpListenLink` field is gone, replaced by
  `Map<LinkId, MavlinkLink> registeredLinks`, populated only via `register(MavlinkLink,
  LinkDescriptor)` after construction — but the alternative-considered-and-rejected reasoning above
  still holds for L1's own `MavlinkGatewayLinkFailureTest`/`MavlinkLobbyHoldTest` doubles, which
  register a hand-built `MavlinkLink` the same way rather than sabotaging a real socket.
- **`VehicleClaimPolicy.closeAllPublishersExceptionally` closes *every* currently-registered
  publisher on the gateway, not just one device's.** This is correct for what F7 actually models: a
  `MavlinkSession`'s reader thread failing means the one shared UDP socket for that bind address is
  gone, which affects every vehicle claimed through it, not a single device. A multi-vehicle gateway
  (several sysids sharing one `bindHost:port`) therefore reports every one of its claimed devices as
  failed, together, on one socket death — this is accurate, not over-broad.
- **An ordinary `unregister()`/gateway `close()` still never calls `publisher.close()` (normal
  completion) on the departing device's `SubmissionPublisher` — this is a pre-existing fact, outside
  R4's scope, not something this wave fixed for symmetry.** `MavlinkGatewayLinkFailureTest`'s ordinary-
  teardown test therefore asserts the *absence* of `onError`, not the presence of `onComplete` — the
  correct, narrow claim for "shutdown must not masquerade as a link failure" (Expected Result #4),
  without depending on unrelated pre-existing behavior (publishers being simply abandoned on the
  normal path today) that this wave was not asked to change.
- **`SystemStatusWiring` now declares `@EnableConfigurationProperties(VisionMavlinkProperties.class)`
  alongside `TelemetryWiring`'s and `DiscoveryWiringConfiguration`'s own identical declarations of the
  same properties class — not a mistake, matching existing precedent.** Spring tolerates the same
  `@ConfigurationProperties` class being enabled from more than one `@Configuration` class; this wiring
  needed `VisionMavlinkProperties` for its three new D7 thresholds and pulling `TelemetryWiring`'s full
  `MavlinkSettings` object in just to read three scalars would have been a heavier, unnecessary coupling.
- **`failureGrace`/D7's threshold plumbing was placed in `SystemStatusWiring.mavlinkLinkStatus`, not
  threaded through `TelemetryWiring.toMavlinkSettings()`'s `MavlinkSettings` object, even though
  `MavlinkSettings.LinkStatus` is a field on that very record.** Both wiring classes now independently
  construct a `MavlinkSettings.LinkStatus` from the same `VisionMavlinkProperties` fields — `TelemetryWiring`
  because `MavlinkGateway`/`MavlinkTelemetrySource` never read `linkStatus` off `MavlinkSettings` today
  (nothing at that layer consumes drop-rate severity), and `SystemStatusWiring` because that is the one
  bean that actually needs the thresholds. `MavlinkSettings.linkStatus` exists so the field has a home
  on the settings record precedent (`Scan`/`Transmit`/`Rc`/`Inventory`/`Onboarding` are all consumer-
  grouped nested records) even though `SystemStatusWiring`'s own bean method takes `VisionMavlinkProperties`
  directly rather than reading it back off a `MavlinkSettings` instance.

### Status (moved verbatim)

**`docs/plans/active/FLEET-RADIO-PLAN.md` R4 done** — "the link has a name, and says when it dies."
`LinkHealth.Health` gained `PeerId` (D4, in `mavlink-core`); `MavlinkGateway`/`MavlinkTelemetrySource`'s
`claimedVehicleHealth()` both now return `Map<DeviceId, LinkHealth.Health>`; `MavlinkLinkStatusProvider`
was rewritten to aggregate per-vehicle at the consumer and name the specific worst device rather than
averaging a fleet-wide list. `MavlinkSession` gained `onLinkFailure` (F7, in `mavlink-core`) and
`MavlinkGateway` wires it to close every registered publisher exceptionally via
`VehicleClaimPolicy.closeAllPublishersExceptionally` (D5) before closing itself. The three severity/
promptness thresholds are `vision.mavlink.drop-rate-warn-percent`/`drop-rate-alarm-percent`/
`link-failure-grace` (D7), documented with defaults in `application.yaml`. See the FLEET-RADIO R4
Gotchas above for the `MavlinkGateway` test-seam constructor and the wiring-placement rationale.
`./mvnw -B -o -pl drone-link/mavlink test` — **235 tests**, all green (2026-08-27; +8 from R2's 227:
6 new `MavlinkLinkStatusProviderTest`, 2 new `MavlinkGatewayLinkFailureTest`).

## `docs/plans/active/FLEET-RADIO-PLAN.md` — R2

### Gotchas (moved verbatim)

- **`VehicleClass.SUBMARINE` folds into `UnidentifiedReason.UNSUPPORTED_VEHICLE`, not its own
  reason.** `UnidentifiedReason` has three values, not one per `VehicleClass` constant — an operator
  refused engage on a submarine and one refused on a real-but-unsupported airframe both get told
  "this platform does not support flying or driving what's on this link", which is the true, actionable
  fact in both cases. `VehicleClass` itself keeps `SUBMARINE` a distinct constant (R1's decision,
  unchanged) purely so a future ArduSub wave has a slot to read from; `FlightModes.unidentifiedReason`
  is where the two are deliberately merged back down for the operator-facing message.
- **`unidentifiedReason()` is resolved once, at `engage`, from the same heartbeat `vehicleKind()`
  reads — never re-resolved per `send`.** Identical freshness contract to `vehicleKind()` itself: a
  vehicle that starts heartbeating a recognized `MAV_TYPE` mid-session does not retroactively change
  an already-refused engage (there is no session to change — `engage` throws before one is built), and
  does not need to, because `DefaultManualControlService#engage` re-resolves both from a fresh link on
  every call.

### Status (moved verbatim)

**`docs/plans/active/FLEET-RADIO-PLAN.md` R2 done.** New `FlightModes.unidentifiedReason(int mavType)`
maps a `MAV_TYPE` to *why* it is `VehicleKind.UNKNOWN`, not merely that it is; `MavlinkManualControlSender`'s
`AdapterLink` now overrides `ManualControlLink#unidentifiedReason()` with it, resolved once at
`engage` alongside `vehicleKind()`. See the FLEET-RADIO R2 Gotchas above for the `SUBMARINE`→
`UNSUPPORTED_VEHICLE` folding decision.

## `docs/plans/active/FLEET-RADIO-PLAN.md` — R4b

### Gotchas (moved verbatim)

- **`emergencyStop` now branches on `FlightModes.vehicleKind(target.mavType())`, resolved from the
  same `ResolvedTarget` every command already resolves — no new lookup, no new state.** `COPTER`/
  `PLANE` are byte-identical to before this wave (`armOrDisarm(device, DISARM, true, "emergency
  stop")`, unchanged). `ROVER` sends `MAV_CMD_DO_SET_MODE` into ArduRover's `Hold` (custom_mode 4)
  instead — a ground rover or surface boat does not fall when disarmed, it *coasts* with its
  steering dead, so a forced disarm is actively wrong for it; ArduRover's `Hold` actively brakes and
  holds against a slope while keeping steering authority alive.
- **No disarm follows a successful rover `Hold`, by decision, not by omission.** A rover's active
  brake in `Hold` typically depends on the motor controller staying armed to apply reverse/holding
  torque; disarming immediately afterward would release the very brake the stop just applied, which
  on a slope is worse than never having stopped at all. An operator who wants the vehicle fully
  powered down once it is confirmed stationary issues a separate, deliberate `disarm` — never
  bundled into the panic-stop path.
- **`VehicleKind.UNKNOWN` stays on the forced-disarm path, deliberately, not as a leftover
  default.** `VehicleKind.UNKNOWN` is what `FlightModes.vehicleKind` returns for a genuinely
  unrecognized `MAV_TYPE` *and* for `VehicleClass.SUBMARINE`/`UNSUPPORTED_VEHICLE`/`NOT_A_VEHICLE` —
  none of those has a rover-shaped mode table this class could resolve `"Hold"` against, so
  attempting one would either throw before anything is sent or require inventing a new guess, which
  `VehicleKind.UNKNOWN`'s own contract (`contexts/vision-flight`'s own Gotchas: "no safe default")
  forbids. A forced disarm needs no vehicle-family mode table at all — `MAV_CMD_COMPONENT_ARM_DISARM`
  is universal across every ArduPilot vehicle kind — so it is the one stop command guaranteed to
  actually reach the aircraft and produce a real, reportable outcome for a machine that never said
  what it is, rather than an error in place of a stop attempt.
- **Never silently does nothing, on either path.** Both branches end in the same `send()` every
  other command already uses: `ACCEPTED`/`NO_ACK`, or a thrown `IllegalStateException` naming the
  vehicle's own refusal. A refused or unacknowledged rover `Hold` propagates exactly like a refused
  or unacknowledged forced disarm always has — nothing here catches and downgrades a failure into a
  false success. `contexts/vision-flight`'s `DefaultFlightCommandService.sendAndAudit` audits and
  rethrows either outcome unchanged, same as before this wave.
- **`FlightCommandPort.emergencyStop`'s own javadoc was rewritten** (in `contexts/vision-flight`,
  outside this module's own file boundary but factually stale otherwise) — it used to claim
  unconditional equivalence to `disarm(device, true)` and "an airborne vehicle will fall" for every
  device; both are now true only for `COPTER`/`PLANE`/`UNKNOWN`.
- **No web UI change shipped in R4b's own task**, despite the plan's own scope line naming
  `station/vision-web/.../fly/**`. There is no clicked "Emergency stop" button anywhere in this
  codebase's UI — `EMERGENCY_STOP` is reachable only via a bound RC switch action, dispatched by
  `station/vision-web`'s `core/rc/control-action-dispatcher.ts`, whose "Emergency stop" wording comes
  from `core/rc/control-action-logic.ts#actionLabel`. **This has since shipped, as part of FLEET-RADIO
  R2's task**: `actionLabel` now takes an optional `vehicleKind` and reads `'Emergency stop (Hold)'`
  on a `ROVER`, unchanged `'Emergency stop'` everywhere else — see `station/vision-web/MODULE.md` for
  the shipped signature and call sites.

### Status (moved verbatim)

**`docs/plans/active/FLEET-RADIO-PLAN.md` R4b done (Java half)** — `MavlinkFlightCommander.emergencyStop`
is now vehicle-kind-gated: unchanged forced disarm for `COPTER`/`PLANE`/`UNKNOWN`, ArduRover `Hold`
for `ROVER`/surface boat, never a disarm following a successful `Hold`. See the FLEET-RADIO R4b
Gotchas above for the full rationale; its web half has since shipped, in R2's own task (see below).

## `docs/plans/active/FLEET-RADIO-PLAN.md` — R1

### Gotchas (moved verbatim)

- **This module's three `MAV_TYPE`→name/family tables (`FlightModes.vehicleKind`/`tableFor`,
  `MavlinkHeartbeatScanner.vehicleKind`, `MavlinkVehicleConfigurator.vehicleKind`) disagreed on the
  same vehicles before this wave** — a surface boat was `"boat"` in discovery and `"surface boat"`
  in the onboarding probe, `"fixed-wing"` vs `"fixed wing"`, and only the configurator knew a
  submarine existed at all. None of the three knew a dodecarotor (`MAV_TYPE` 29), a decarotor (35)
  or a generic multirotor (43) — all three fell through to a `"vehicle"`/`"Mode 6"`-style fallback
  as if unrecognized. All three now delegate to `mavlink-core`'s `VehicleClass`, the one project-wide
  table; a UI or log line that previously read `"boat"` now reads `"surface boat"` — the only
  user-visible spelling change. `VehicleTaxonomyAgreementTest` fails immediately if any of the three
  grows a local, hand-maintained copy again.
- **The kept vocabulary is `MavlinkVehicleConfigurator`'s where the two disagreed** (`"surface
  boat"` over `"boat"`, `"fixed-wing"` from the scanner over the configurator's `"fixed wing"` — the
  one exception, kept because it matches this module's own existing hyphenation convention
  elsewhere), plus new labels neither table had before (`"dodecacopter"`, `"decacopter"`,
  `"multirotor"`, a label per VTOL subtype, `"submarine"`, a label per recognized-but-unsupported
  airframe, a label per not-a-vehicle instrument). `station/vision-api`'s `OnboardingWireContractTest`
  asserts only that `VehicleProfile.vehicleKind` is present/absent, never an exact string, so this
  relabeling is not a frozen-wire break.
- **`VehicleClass` gives discovery and the onboarding probe a real, distinct label for a non-vehicle
  instrument heartbeating on the same link (a gimbal, a GCS, an ADS-B transponder, ...), rather than
  collapsing it into the same fallback a genuinely unidentified vehicle gets.** Before this wave both
  cases read identically (`"vehicle"` in discovery, `UNKNOWN` `VehicleKind` in `FlightModes`) — this
  matters directly for the next wave (R2), which makes an `UNKNOWN` vehicle refuse to engage manual
  control; without this distinction, a gimbal quietly sharing a vehicle's radio link would refuse to
  engage for the wrong reason (looking exactly like an unidentified aircraft) instead of the right one
  (it was never a vehicle). `FlightModes.vehicleKind` still folds both `NOT_A_VEHICLE` and `UNKNOWN`
  into `VehicleKind.UNKNOWN` today — that enum has no finer slot yet — but the underlying
  classification is no longer ambiguous, only `VehicleKind`'s own vocabulary is.
- **`FlightModes.ARDUPILOT_ROVER`'s three missing modes (`8` Dock, `9` Circle, `16` Initialising)
  were verified against ArduPilot's own `Rover/mode.h`** for the `stable-4.7.0` generation this
  project's own `infra/rover-sim` SITL image is pinned to (`docs/plans/active/FLEET-RADIO-PLAN.md`
  R7). `"Initialising"` (with an "s", ArduRover's own spelling) is kept exactly as upstream spells
  it, distinct from `ARDUPILOT_PLANE`'s `"Initializing"` (with a "z") two tables below — different
  firmware source trees, not normalized to agree with each other.
- **`Dock` (custom_mode 8) is compiled in behind `#if MODE_DOCK_ENABLED` on real ArduPilot, so it is
  absent from some builds — a nuance that only matters for `customModeFor`'s *reverse* lookup (name
  → number, used to command a mode), never for the forward lookup (number → name, used to display
  one).** A build without Dock compiled in simply never reports `custom_mode == 8`, so the forward
  lookup's table entry is inert on that build. `customModeFor` deliberately still resolves
  `"Dock"` → `8` on every build, including ones that lack it, because there is no live per-vehicle
  capability signal to gate an optional compiled-in mode on, and hiding the entry would make Dock
  permanently uncommandable even on builds that do have it. The actual safety net is one layer up:
  `MavlinkFlightCommander.send` already throws for an explicit non-`ACCEPTED` `COMMAND_ACK`, so a
  build that honestly rejects an unsupported mode change surfaces that rejection normally, the same
  as any other rejected command. The one gap this cannot close — a build that ACKs `ACCEPTED` for a
  mode change it does not actually honor — is a firmware-honesty problem, not something a
  client-side mode-name table can fix. **These two facts — the direction-sensitive `Dock`/`Initialising`
  handling and the `#if MODE_DOCK_ENABLED` compile-time nuance — are now current-state facts in
  MODULE.md's own Gotchas.**

### Status (moved verbatim)

**`docs/plans/active/FLEET-RADIO-PLAN.md` R1 done** — `FlightModes`, `MavlinkHeartbeatScanner` and
`MavlinkVehicleConfigurator` all now delegate vehicle-family/naming to `mavlink-core`'s one
`VehicleClass` table instead of three disagreeing local copies; the ArduRover mode table is
complete (Dock/Circle/Initialising); see the FLEET-RADIO R1 Gotchas above.
