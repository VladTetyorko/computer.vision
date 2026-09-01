# R3 — take-control handshake denial

Scope: trace "Control denied — The station never confirmed control" for FLY-CONTROL-UX item 2.
Read-only research; no product code touched.

## 1. The exact string, and its trigger

`station/vision-web/src/app/core/rc/manual-control-client.ts:39` — `ENGAGE_TIMEOUT_MS = 4000`.
`:214` — `engage()` arms `this.engageTimer = setTimeout(() => this.abandonEngage(), ENGAGE_TIMEOUT_MS)`
right after sending the `engage` frame. `:293-301` — `abandonEngage()`:

```
this._deniedReason.set('The station never confirmed control. Nothing is being sent — try again.');
this._state.set('denied');
```

This fires **only** if neither an `engaged` nor a `denied` frame (nor a socket close/error, which
routes through `handleSocketGone()` → state `'released'`, a different UI text) arrives within 4s
while the socket stays open. It is a pure client-side timeout, not a server-forwarded reason —
the string never crosses the wire. The doc comment at `:27-38` states the design intent bluntly:
"the server does no vehicle I/O inside `engage`... anything past a couple of seconds is a fault,
not slowness." `rc-monitor.spec.ts` greps for "Control denied" as the wrapper UI prefix around
`deniedReason`.

## 2. Web→backend engage flow

`ManualControlClient.engage()` (`manual-control-client.ts:216-222`) opens `WebSocket('/ws/manual-control')`,
sends `{type:'engage', assetId}` on `onopen` (`buildEngageFrame`, `manual-control-logic.ts:31-33`).
It waits for either `{type:'engaged', ...}` or `{type:'denied', code, reason}` (`handleMessage`,
`manual-control-client.ts:242-284`).

Server side, `ManualControlWebSocketHandler.handleEngage` (`station/vision-api/.../ws/ManualControlWebSocketHandler.java:173-223`)
calls `manualControlService.engage(assetId, actor, scope, onWatchdog)` **synchronously on the WS
read/dispatch thread** and replies immediately — `ManualControlEngagedFrame` on success, or one of
`ManualControlDeniedFrame(CODE, reason)` for the three caught exception types
(`AccessDeniedException`→`OUT_OF_SCOPE`, `VehicleUnidentifiedException`→`VEHICLE_UNIDENTIFIED`,
`IllegalStateException`→best-effort code via `mapIllegalState`, line 259-268).

`DefaultManualControlService.engage` (`contexts/vision-flight/.../application/DefaultManualControlService.java:221-293`)
is fully local and bounded: scope check → `readinessService.evaluate` (DB-backed, no live link,
per its own javadoc §"A silently misconfigured vehicle is refused too") → maintenance-grounded
check → `firstCommandableDevice` (filters on `manualControlPort::supports`, a pure protocol check,
`:362-367`) → `manualControlPort.engage(device)`. Every `IllegalArgumentException` this last call
can throw is caught and rethrown as `IllegalStateException` (`:254-260`) — which the WS handler
catches. **There is no server-side blocking or vehicle round-trip anywhere in this path** — it
either returns or throws in microseconds, and every documented throw type is caught by the
handler. For the 4s client timeout to fire, either the WS frame never reaches the browser, or an
exception type *not* in `{AccessDeniedException, VehicleUnidentifiedException, IllegalStateException,
NoSuchElementException-from-assetService.details is uncaught too}` escapes `handleTextMessage`
uncaught — no such path was found in this trace.

## 3. Backend→vehicle handshake: there isn't one

The station sends nothing to the vehicle to request or confirm control. `ManualControlPort`
(`contexts/vision-flight/.../domain/port/ManualControlPort.java:29-72`) is explicitly "the
fire-and-forget opposite of `FlightCommandPort`'s request→ack one-shots." `MavlinkManualControlSender.engage`
(`drone-link/mavlink/.../MavlinkManualControlSender.java:126-156`) resolves the device's
**already-recorded** claim (`telemetrySource.commandTarget`, i.e. whatever the RX side last heard)
and hands it to `mavlink-core`'s `ManualControlService.engage(PeerId)`
(`drone-link/mavlink-core/.../service/ManualControlService.java:147-155`), whose own class javadoc
(`:25-30`) is unambiguous: **"this class cannot express an ack wait... Manual control is genuinely
fire-and-forget on the wire (the spec defines no rate and no failsafe-on-silence for RC override)."**
`engage()` there is one check: `peers.peer(target) == null` → `IllegalArgumentException`
("has not been heard from on any link yet"); otherwise it starts the send loop and returns
immediately. The ongoing relay is `RC_CHANNELS_OVERRIDE` (#70), ack-less, streamed at a fixed
rate — no `COMMAND_ACK`, no readiness bit, no `RADIO_STATUS`, nothing from the vehicle is awaited.

**"Confirmed control" is therefore a 100% station-local verdict**, resting on: (a) scope, (b) not
maintenance-grounded, (c) `rc-relay` readiness feature != `MISSING` (read from the vehicle's
*last-probed*, stored `VehicleProfile` — no live query), (d) a commandable device exists, (e) that
device has an address the station has *already* heard a datagram from, and (f) `link.vehicleKind()`
(from the *last* `HEARTBEAT.type`, already in memory) isn't `UNKNOWN`. None of (a)-(f) is a new
vehicle round-trip triggered by `engage`. This directly contradicts the premise embedded in the bug
report and the owner's own theory — there is no "confirmation" message a firmware update could add
or remove, because the protocol never asks for one.

## 4. What changed recently (git log, today's date 2026-09-01)

`MAVLINK-COMMANDS-PLAN.md` (frozen today) is exactly the effort the owner is calling "arduino-start's
last update" — its F-track works `~/Arduino/ardupoilot-start/` (outside this repo) mirrored by
`infra/rover-sim/`. Commits already on master today, newest first::
- `c153603a` warn-only log on peer-address flap (`DefaultPeerDirectory`) — no behavior change,
  address is still overwritten as before. Not load-bearing here.
- `cc057ede` force-arm magic fix + bounded retries — **`MavlinkFlightCommander`** (arm/disarm/mode),
  not `ManualControlPort`.
- `0e55a83f` `MavlinkStreamNegotiator` fires `REQUEST_MESSAGE`/`SET_MESSAGE_INTERVAL` on every claim
  — again the command-ack path (`MavlinkVehicleConfigurator`/`MavlinkFlightCommander`), unrelated to
  `ManualControlPort`.
- `c9f954f4` `MavlinkFlightCommander.supports()` becomes firmware-honest (Betaflight false
  positive). **Explicitly, deliberately, left `MavlinkManualControlSender`/`MavlinkVehicleConfigurator`
  protocol-only** — see its own commit message and MODULE.md Gotchas: "Betaflight has accepted
  `RC_CHANNELS_OVERRIDE` since ~2025.12.0-beta... gating manual control on firmware family would
  trade today's real false positive for a new false negative." So this wave explicitly ruled out
  touching manual-control's `supports()`.

None of these four alter `ManualControlPort`/`ManualControlService`'s engage semantics at all.

**The one firmware-side change that is squarely on-topic** is `docs/plans/active/mavlink-commands/R4-firmware-audit.md`
finding 4 ("No source authentication... on the control channel") and its accepted mitigation,
implemented in wave **F4** (rover-sim commit `c13451a6`, "learned-peer command gate + TX-failure
STATUSTEXT"): `MavlinkUdpLink` now **learns** the first source that sends it a `COMMAND_LONG` frame
and **silently ignores** `COMMAND_LONG` from any other source until the incumbent has gone quiet
past `commandTimeoutMs` (500ms) — see `infra/rover-sim/link_test.cpp:185-249` for the exact,
tested behavior ("a second transmitter's command is ignored... no state change, no ACK, just a
throttled log line" / "telemetry keeps following the last sender even though its command was
refused"). This is a real, new, firmware-side authority gate that did not exist before — but it
gates `COMMAND_LONG` (arm/mode), and its test explicitly separates that from `RC_CHANNELS_OVERRIDE`
handling (`handleRcOverride`, cited only in the audit's replay-risk discussion, §2a "Replay of a
stale command"). It was not verified in this pass whether `handleRcOverride` shares the identical
learned-peer gate (the harness has no direct RC-override-from-a-second-source test case in the
files read); if it does, the effect is the rover **silently drops the override stream from a
"second" station** without ever ACKing or NACKing it — invisible on the wire, and outside what
`ManualControlService`(mavlink-core) can detect (it has no ack channel to detect it with).

**MAVLINK-COMMANDS-PLAN.md D4/"operator work"**: F1's bench verification is explicitly flagged
"operator work... record as open, do not claim it" — i.e. part of this firmware wave is
acknowledged-incomplete pending a human at the actual hardware. This matches "last update... doesn't
support it" as a real, known-open item — just not one that touches the manual-control ack question.

## 5. rover-sim evidence

`infra/rover-sim/link_test.cpp` (F4, new) is the one suite that models "who is allowed to command,"
and it proves the **firmware's own gate**, not anything the station's WS handshake reads. Grepping
the harness for `RC_CHANNELS_OVERRIDE` hits only `wire_fixture.py`/`session_fixture.py` (fixture
generation), not a source-authority test — the harness does not currently prove whether the new
peer gate does or doesn't also silently drop a second source's RC override stream, only that it
does for `COMMAND_LONG`. That is the one concrete gap: **if a real rover has an incumbent peer from
an earlier station instance/SITL run still "learned," a fresh browser session's `engage` will
report `'engaged'` (station-local, as shown in §3) while the vehicle silently ignores the channel
stream** — the sim's own comment block even calls this out as a real deployment risk ("a rover
that runs on a shared home/venue WiFi"). This produces a *different* symptom than the reported bug
(engaged-but-inert, not denied-after-timeout) but is the closest real, evidenced defect in the
current handshake.

No test in this harness reproduces the client's exact 4s-no-reply condition — nothing here
simulates the *station* (Java) side at all; rover-sim only compiles/exercises the firmware image.

## 6. Fix options

**(a) Station-side, UI honesty (preferred for the "failsafe and honest UI" principle, CLAUDE.md
rule 9 / rule "newest data wins").** `engage()` is provably fast and local — a 4s timeout that
fires is *always* a station fault (dropped frame, uncaught exception, or a wedged Spring WS
thread), never vehicle slowness. Two independent gaps: (1) instrument `handleEngage`'s three
`catch` blocks with a **catch-all `RuntimeException`** that still emits a `denied` frame (e.g.
`INTERNAL_ERROR`) instead of trusting every possible throw is one of the three named types — turns
a silent hang into an honest, fast denial and closes the actual gap this trace could not rule out
in §2. (2) Add a station-side **structured log line** at the top of `handleEngage` and right before
every `sendFrame` exit, so a real "never confirmed" report is diagnosable from the vision-app log
in seconds instead of a bisect — the client's own `:245-250` console-warn already does half of this
on the browser side, the server has no equivalent.

**(b) Firmware-side.** There is nothing to "support" for manual control specifically — the wire
message (`RC_CHANNELS_OVERRIDE`) and its ack-less contract are unchanged and match D1 of
MAVLINK-COMMANDS-PLAN ("no `MANUAL_CONTROL` sender"). The one concrete, precise ask for the
firmware owner: **confirm `handleRcOverride()` in `MavlinkUdpLink.cpp` is NOT subject to the new
F4 learned-peer gate** (or, if it should be for the same hijack-prevention reason, that the gate's
500ms re-learn window is short enough not to strand a legitimate new station session) — this is
the one place a firmware change could make control *appear* granted but be silently inert, which
is adjacent to, but distinct from, the reported "never confirmed" text.

**(c) Config.** `vision.rc.watchdog-timeout-ms` (default 300, `ManualControlWebSocketHandler`
constructor param, `:122`) and the client's `ENGAGE_TIMEOUT_MS` (hardcoded 4000, not externalized —
CLAUDE.md rule 1 flags hardcoded-values-should-be-config, worth raising for P/plan) are the only
two tunables in this path; neither is a handshake-behavior flag. There is no existing flag that
"alters the handshake" — the ack-less design is structural, not configurable.

Option (a-1) — a catch-all in `handleEngage` — is the one that best matches "failsafe and honest
UI": it cannot make a real vehicle problem look like a station bug, and it converts an
indistinguishable-from-a-fault timeout into a diagnosable, honestly-labeled denial.

## Root-cause hypothesis

**Best single-sentence hypothesis:** the reported "never confirmed control" is not caused by any
firmware behavior — `engage()`'s confirmation is proven, by design and by the mavlink-core
javadoc/tests, to be entirely station-local and ack-less — so the 4s timeout firing means an
uncaught exception or dropped frame on the station's own WS thread (not covered by
`handleEngage`'s three named `catch` blocks) is swallowing the reply; the firmware's real new
behavior worth checking (F4's learned-peer gate) would instead manifest as "control granted but
the rover doesn't move," a different, currently unverified symptom.

**Confidence: medium.** High confidence that the ack-less design rules out any vehicle-side
"confirmation" requirement (directly evidenced, tested, documented in three independent files).
Lower confidence on the specific exception/path that silently drops the WS reply — no smoking gun
found in the code read in this pass; recommend option (a-1) both as the fix and as the fastest way
to get a real stack trace out of the next reproduction.
