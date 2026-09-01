# R1 — Fly control-UX rework: code truth

Read first: `station/vision-web/MODULE.md:51,55`, `contexts/vision-flight/MODULE.md` (RC/arm sections
throughout), `drone-link/mavlink/MODULE.md` (ManualControl/VehicleKind sections), `docs/plans/active/CONTROLLER-UX-PLAN.md`
(§2.2, decisions U5/U6, wave X2 — the prior wave that built today's `rc-monitor` drawer). Everything
below is verified against source.

## 1. Fly rail composition

Route `/fly/:assetId` → `features/fly/cockpit.ts`/`cockpit.html` (`CockpitPage`). Tool-rail icons live
in `.grid-rail` (`cockpit.html:433-497`), five ids `rc · cv · marks · map · help` (`fly-logic.ts:156`).
The "Controller" rail button (`cockpit.html:437-443`, icon `gamepad`) toggles panel `rc`.

**`rc-monitor.ts`/`.html`/`.css`** (three-file) — mounted only `@if (isPanelOpen('rc'))`
(`cockpit.html:318-336`) — is the actual Controller drawer, wrapped in
`<vision-side-panel title="Controller" icon="gamepad" subtitle="Sticks · mode · arm">`
(`rc-monitor.html:1`). Contents top→bottom: state-strip chips (armed/mode/device+rate,
`rc-monitor.html:9-19`) → `<vision-transmitter-view>` stick picture (shared,
`shared/ui/transmitter-view/`) → `<vision-flight-command-panel>` (mode/arm/disarm rows,
`rc-monitor.html:107-116`) → sticky footer = input-mode tabs **On-screen/Transmitter/Keyboard**
(`rc-monitor.html:131,140,149`) + Take-control/Release (`rc-monitor.html:153-188`).

**`flight-command-panel.ts`/`.html`/`.css`** — body-only, "owns no `<vision-side-panel>`, no `open`,
no `close`" (`flight-command-panel.ts:21-26`) — renders Mode picker+Set, Arm/Disarm, and its own 3
confirm dialogs via a private `UiStore` (`flight-command-panel.ts:109-112`).

**`arm-confirm-dialog.ts`/`.html`/`.css`** — separate two-stage Arm confirm modal (not
`shared/ui/confirm-dialog.ts`).

The `rc-monitor` `<vision-side-panel>` is `position: fixed; right: 0; z-index: 120`
(`shared/ui/side-panel.css:11-20`) — **already a fixed overlay docked to the right edge**, sliding
over the video, not a static sidebar column; only in the DOM while toggled open. So "rail → overlay"
is really "drawer → in-frame HUD", not "static → overlay".

**Not** on video today: `fly-osd.ts`/`.html`/`.css` sits in `.grid-telemetry`, a real CSS-grid row
*below* `.grid-main` (`cockpit.css:98-121`), not an absolute overlay — contrary to a prior assumption.

**Status/instruction strings quoted by the owner, and everything adjacent, with sources:**

| String | Source |
|---|---|
| "This browser doesn't expose gamepad input…" | `rc-monitor.html:23-24` |
| "Plug your transmitter in over USB in USB Joystick mode…" | `rc-monitor.html:28-30` |
| "Nothing on your transmitter is bound to a command yet." | `rc-monitor.html:70-71` |
| "These do nothing until this drone accepts commands." | `rc-monitor.html:75` |
| "No video stream is running for this drone — engage its link directly…" | `rc-monitor.html:88-89` |
| "On-screen" / "Transmitter" / "Keyboard" (tabs) | `rc-monitor.html:131,140,149` |
| "Take control" / "RELEASE" / "Engaging…" | `rc-monitor.html:178,184,155` |
| "Control denied — {{ deniedReason }}" / "Try again" | `rc-monitor.html:163-166` |
| "Control released — input stalled…failsafe took over." | `rc-monitor.html:171-172` |
| "Pick a drone first." / "This drone isn't commandable right now." / "Plug your transmitter in, or switch to the on-screen controls." | `rc-monitor-logic.ts:47,50,53` (`engageDisabledReason`) |
| "Mode" label + "Set"/"Setting…" | `flight-command-panel.html:7,21` |
| "also on {{ hint }}" | `flight-command-panel.html:24,49` |
| Arm title `groundedReason() ?? 'Arm — the propellers will spin'` | `flight-command-panel.html:38` |
| grounded-reason paragraph under Arm row | `flight-command-panel.html:53-55` |
| "Set `<asset>` to `<mode>`?" / "This will ARM `<asset>`…" / "Yes, arm `<asset>`" | `flight-command-panel-logic.ts:46,56,65` |
| "`<asset>` is armed…disarming will make it fall." / "Disarm `<asset>`?" | `flight-command-panel-logic.ts:85-86` |
| Toasts "Mode set"/"Armed"/"Disarmed", NO_ACK variants | `flight-command-panel-logic.ts:101-109` |
| "Arm aircraft" / "Stand clear of the propellers…" / "Continue"/"Cancel" | `arm-confirm-dialog.html:9,15,18,20` |
| "Last step — this cannot be recalled once sent." / "Arming…" | `arm-confirm-dialog.html:25,28` |
| Grounded-banner text `<kind> — <summary>` | `readiness-logic.ts:328-330` → `grounded-banner.html:1-6` |

**Three-file convention:** compliant everywhere listed above. **Not split** (flag for rework):
`features/fly/diagnostics-card.ts` (inline `template:`/`styles:`), `features/fly/failsafe-banner.ts`
(same).

## 2. Input pipeline (web)

**One mode-agnostic observable exists: `RcSource`** (`core/rc/rc-source.service.ts`). `axes`/`buttons`
are `computed()` signals that switch on `_kind(): 'gamepad'|'virtual'|'keyboard'`
(`rc-source.service.ts:63-79`) over three per-mode services (`RcInputService` = gamepad,
`VirtualRcInputService` = on-screen, `KeyboardRcInputService` = keyboard). `ManualControlClient`
(`core/rc/manual-control-client.ts:52`) reads only `RcSource`, never a per-mode service — "the one
seam `ManualControlClient` reads its stick values through". This is exactly the single stream a
neutral-check needs: `RcSource.axes()` + `RcSource.kind()` (auto-promotes gamepad-over-virtual,
never demotes mid-session, `rc-source.service.ts:97-107`).

**Value ranges — raw web values are `-1..1` for axes / `0..1` for buttons** (gamepad-API convention,
carried straight through `ManualControlSession.onChannels` javadoc, backend:
`ManualControlSession.java:26`). **Display** is the 0–100/50-center convention the owner described,
already implemented: `core/rc/control-surface-logic.ts:96` — "a multirotor's throttle is 0-100 with
rest at 0; a car's is 50 at stop, 50→0 reverse, 50→100 forward" — computed by `displayPercent`
(`control-surface-logic.ts:112`): `UNIDIRECTIONAL → round(clamp(v,0,1)*100)`,
`CENTERED → round((clamp(v,-1,1)+1)*50)`. So the owner's "50/50 vs throttle-0" framing is **already
the codebase's own display law** for this exact control-shape distinction — a rover's pad shows
steering+throttle both centered-at-50, a copter's pad shows roll/pitch at 50 plus a separate
throttle slider resting at 0.

**Vehicle kind on the web** comes from the server's `engaged` frame, not computed locally:
`ManualControlClient.vehicleKind` signal (`manual-control-client.ts:135`), set from what the backend
resolved live at engage time (see §4). `rc-monitor.ts:216` — `vehicleKind = computed<VehicleKind>(…)`.
Travel/rest-point per axis is derived from the **profile's own `ChannelMap`** (fetched alongside
`vehicleKind`), not hardcoded per-kind in the web layer — see `control-surface-logic.ts:78,87-88` (rest
= `centerMicros` for centred, `minMicros` for unidirectional).

## 3. Arm flow end-to-end

`flight-command-panel.ts` (Arm section): click → `requestArm()` (`:…`) — poka-yoke re-checks
`armDisabled()` — → `dialog.open('arm')` → `<vision-arm-confirm-dialog>` shown (two-stage: "Continue"
then "Last step…") → `confirmArm()` → `this.api.arm(this.assetId())` →
`VisionApi.arm(assetId, force?)` (`vision-api.ts:1022`) → **`POST /api/assets/{id}/arm`**
(`FlightCommandController.java:104`, `@ResponseStatus ACCEPTED`, optional body
`{force}`, default `false`) → `flightCommandService.arm(AssetId, force, actor, scope)`
(`FlightCommandService.arm`) → `DefaultFlightCommandService.arm`
(`DefaultFlightCommandService.java:149-155`).

**Existing disable reason — a single string, not a list**:
`armDisabled = computed(() => this.groundedReason() !== undefined)`
(`flight-command-panel.ts`, near `armDisabled`), where `groundedReason` is an `input()` forwarded from
`CockpitPage`'s `GroundingStore.groundedReason` through `rc-monitor` (ASSET-FLOWS WB1). It is **one
optional reason string**, not a composed/priority list — the button's `title` becomes
`groundedReason() ?? 'Arm — the propellers will spin'` and a `<p class="disabled-reason">` renders it
under the row when present (`flight-command-panel.html:38,53-55`). Disarm/mode are **never** gated
this way (S1 rule: recovery verbs stay available). Server-side, the *same* concept exists independently
as `DefaultFlightCommandService#requireNotMaintenanceGrounded`
(`DefaultFlightCommandService.java:240-260`) — `arm` only, checks
`ReadinessReport.blockers()` for the `MAINTENANCE_GROUNDED:` prefix, throws `IllegalStateException`
(409) if any survive. **A stick-neutrality gate would be a second, independent disable-reason source**
that needs to compose with `groundedReason` (currently: exactly one reason wins, no priority-ordering
code exists yet because there was only ever one reason).

`arm-confirm-dialog` shows no stick state today — purely a two-click danger confirm
(`arm-confirm-dialog.html:9-28`); it would be the natural place to *also* show "sticks not neutral" if
the gate is UI-side.

## 4. Backend view of sticks at arm time — the central finding

**Arm and manual-control sticks are two fully decoupled pipelines**, and this is the biggest structural
fact for the rework:

- **Arm** = `DefaultFlightCommandService.arm` → `FlightCommandPort.arm(Device, force)`
  (`FlightCommandPort.java`) → one blocking MAVLink `COMMAND_LONG`/`MAV_CMD_COMPONENT_ARM_DISARM`
  request-then-ack, implemented by `MavlinkFlightCommander` (`drone-link/mavlink`). No session, no
  stick state, no `ManualControlService` collaborator anywhere in this path
  (`DefaultFlightCommandService`'s constructor takes only `AssetService, FlightCommandPort,
  AuditTrailPort, ReadinessService` — no `ManualControlService`/`ManualControlPort`).
- **Manual control** = `DefaultManualControlService.engage/onChannels` → `ManualControlPort.send`
  (streaming, ack-less `RC_CHANNELS_OVERRIDE` #70), implemented by `MavlinkManualControlSender` →
  `mavlink-core`'s `ManualControlService`/`RcLinkRuntime`.

**Does anything retain the latest frame?** `DefaultManualControlSession` (vision-flight,
`DefaultManualControlService.java:~430-520`) stores only a `volatile Instant lastInput` timestamp for
the watchdog — **it does not store the channel values themselves**. The actual latest `RcChannels`
frame lives in `mavlink-core`'s `RcLinkRuntime.mailbox` (private field,
`drone-link/mavlink-core/.../ManualControlService.java`, `RcLinkRuntime` inner class) — **not exposed
by any port method**. `ManualControlLink` (the only interface a caller can hold) exposes just
`active()`, `rateHz()`, `vehicleKind()`, `unidentifiedReason()` — no channel getter
(`ManualControlLink.java`). So today **nothing anywhere can ask "what are the current sticks for asset
X"** — not `DefaultManualControlSession`, not `ManualControlPort`, not `mavlink-core`'s public API.

**Wire values are microseconds** (1000–2000, center 1500 = `ControlBinding.CENTER_MICROS`), not the
web's raw `-1..1` — `RcChannels.MIN_MICROS/MAX_MICROS` (`RcChannels.java:44-48`), conversion in
`ControlBinding.toMicros`/`axisMicros` (`ControlBinding.java`). "Neutral" for a centred binding
(rover throttle, roll/pitch/yaw) = `centerMicros` (1500); for a unidirectional binding (copter/plane
throttle) = `minMicros` (1000, the *rest* point — `ControlBinding.Travel.UNIDIRECTIONAL`,
`ControlBinding.java` "Travel is in the microseconds" section). This is exactly the
COPTER-idle-vs-ROVER-stop distinction the owner described, and it is already a first-class domain
concept (`ControlProfile.forKind`, `ChannelMap`, `VehicleKind` — `contexts/vision-flight`'s domain
model), **not** something to invent.

**Vehicle kind on the backend**: resolved live, per-engage, from the MAVLink heartbeat —
`ManualControlLink.vehicleKind()` (`ManualControlLink.java`), populated by
`MavlinkManualControlSender.engage` via `FlightModes.vehicleKind(target.mavType())`
(`MavlinkManualControlSender.java`). **`DefaultFlightCommandService.arm` has no vehicle-kind
knowledge at all** — `FlightCommandPort.arm(Device, force)` doesn't need it (arm is the same MAVLink
command regardless of kind); only `ManualControlLink`/`ManualControlService` and
`FlightCommandPort.emergencyStop` (vehicle-kind-branching, FLEET-RADIO R4b) currently care.

**Feasibility of a server-side "refuse arm unless neutral" gate**: would require **all** of:
1. Exposing the mailbox contents through `ManualControlLink`/`ManualControlPort` (new port method,
   e.g. `Optional<RcChannels> latestChannels(ManualControlLink)`), crossing from `mavlink-core` up
   through `MavlinkManualControlSender` into `vision-flight`'s domain.
2. New coupling from `DefaultFlightCommandService`/`arm` to `DefaultManualControlService`/its active
   session — these are currently sibling services with **zero shared collaborator**, each with their
   own port. `arm` would need to look up "is there an active `ManualControlSession` for this asset,
   and what did it last send" — a session lookup by `AssetId` that doesn't exist today (the service
   holds one `activeSession` field, keyed by nothing — `DefaultManualControlService.java` `sessionLock`
   /`activeSession`).
3. Deciding what happens when **no manual-control session is active at all** — which is the common
   case: arm does not require RC override engagement today, and an operator can (and typically does)
   arm before ever taking manual control. A pure backend gate needs a policy answer for "no live
   sticks to check" (refuse arm entirely? allow it? — a product decision, not a code fact).

Given this, **the practical implementation is almost certainly web-side** (gate the Arm button/confirm
on `RcSource.axes()` + the active `ControlProfile`'s per-axis rest point, computed via
`control-surface-logic.ts`'s existing `travel`/rest-point helpers), mirroring how `groundedReason`
already gates Arm client-side — with the caveat that a purely client-side gate is advisory, not a
safety interlock, since nothing stops a direct `POST /api/assets/{id}/arm`.

## 5. Overlay precedent on the video

All overlays live inside `.grid-main` (`position: relative`, `cockpit.css:242-247`) or, for the
drawers, are `position: fixed` panels layered on top. No generalized z-index token scale — every value
is a one-off literal with an inline comment:

| Overlay | z-index / position |
|---|---|
| `.grid-banner` (grounded/failsafe) | real grid row, `z-index: 10` |
| `.main-map` (tactical inset) | absolute, `z-index: 0` |
| `.stage-notice` / `.detection-off-chip` | absolute, `z-index: 5` |
| `.not-streaming-card` | absolute, `z-index: 2` |
| `.main-header` (exit/switcher/Bring-home) | absolute, `z-index: 6` |
| `.main-secondary` (secondary tiles) | absolute, `z-index: 4` |
| `.main-preflight` / `.main-diagnostics` | absolute, `z-index: 3` |
| Tool-rail drawers incl. `rc-monitor` | fixed, `z-index: 120` (`side-panel.css:11-20`) |
| `.hud-confirm` (stop-stream scrim) | absolute inset:0, `z-index: 20` |
| CV detection boxes | `<canvas class="overlay-canvas">` inside `shared/player/player.html`, absolute inset:0, pointer-events:none unless interactive |

**No dedicated overlay-host component** — each overlay independently `position: absolute`/`fixed`
inside its relative container, coordinated only by these literals plus `UiStore` (below).

**`UiStore`** (`core/ui/ui-store.ts`) is the mutual-exclusion coordinator: "the single coordinator for
a group of mutually-exclusive overlays" (`ui-store.ts:3-23`) — opening one closes the group's other
member; a feature owns one `UiStore` instance per independent overlay *group* (e.g. `CockpitPage`'s
`panels` = persisted tool-rail group, `dialog` = transient confirm group;
`flight-command-panel.ts`'s own `dialog` group for its 3 confirms). Design doc:
`docs/plans/done/UI-ARCHITECTURE-PLAN.md:11-33,39-62` — layering
`Component → Facade → Store → Service`, `UiStore` as "the consistency linchpin". Guard:
`core/ui/architecture.spec.ts` — see §7.

**Prior decision directly on point**: `docs/plans/active/CONTROLLER-UX-PLAN.md` quotes the owner's own
framing (`:9-13`): *"remake it so the user could see the real joystick movements, buttons,
arming/disarming from joystick etc… in a proper, visualised way, not breaking the video context."*
That plan's wave X2 (already built — this is today's `rc-monitor`) explicitly chose to **keep it a
side-panel drawer**: §2.2 (`:67-69`) *"Same 24rem `vision-side-panel`… the video stays the page"*;
decision U5 (`:95`): *"The drawer sits over video; text competes with it, chips do not."* The rework
under discussion is a **reversal** of that prior explicit decision, not a first attempt — worth
flagging to whoever authors the plan.

## 6. Config pattern to reuse (`vision.ops.*`)

Full chain for `vision.ops.battery.*` (ASSET-FLOWS BK3), the template for a `vision.ops.rc.*`
neutral-tolerance value:

- **yaml** (commented-out documentation of defaults): `station/vision-app/src/main/resources/application.yaml:1146-1160` —
  `# ops:\n#   battery:\n#     warning-percent: 25\n#     critical-percent: 10`, with a comment block
  explaining "every key below is commented out: each documents `VisionOpsProperties.Battery`'s own
  default rather than overriding it."
- **properties class**: `VisionOpsProperties.java` (`station/vision-app/.../config/properties/`) —
  `@ConfigurationProperties(prefix = "vision.ops")`, nested `record Battery(@DefaultValue int
  warningPercent, @DefaultValue int criticalPercent)` with compact-constructor validation; outer
  compact ctor falls back to `Battery.defaults()` if the whole nested block is absent (Spring relaxed
  binding does not apply a nested record's own field defaults when the block itself is `null`).
- **wiring**: `OpsWiringConfiguration.java` — `@EnableConfigurationProperties(VisionOpsProperties.class)`,
  one `@Bean OpsThresholdsResponse opsThresholds(VisionOpsProperties)` built once at startup (deploy-time
  config, not re-read per request).
- **controller**: `OpsThresholdsController.java` — `GET /api/ops/thresholds`, `@OpenByDesign` (any
  signed-in caller), constructor-injected with the already-built DTO bean, returns it verbatim.
- **DTOs**: `OpsThresholdsResponse`/`BatteryThresholdsResponse` (`station/vision-api/.../dto/`).

**Web side** — `core/ops/thresholds-store.ts`: `@Injectable({providedIn:'root'})` singleton,
signal seeded from a pure `*-logic.ts` default constant (`thresholds-logic.ts:17-20`,
`DEFAULT_BATTERY_THRESHOLDS`), **fetch-once in the constructor** (`void this.refresh()`,
`thresholds-store.ts:41-43`) — explicitly *not* a `PollScheduler` poller, since this is process-level
config that "cannot change without a server restart." `refresh()` swallows fetch errors into an
`error` signal, leaving the last-good/default value in place (honest-degrade, never blocks). Consumed
today only by `fly-osd.ts:4,113` via direct `inject()` (legal because `fly-osd` is a non-routed
presentational component per the `architecture.spec.ts` carve-out, §7). A neutral-tolerance value
should follow this exact shape: `VisionOpsProperties` gains a sibling nested record (or a new
`vision.ops.rc.*` block), a new field on `OpsThresholdsResponse` (or a parallel
`GET /api/ops/thresholds` addition), and `ThresholdsStore` gains a matching signal — reusing the one
existing fetch-once store rather than adding a second one.

## 7. Tests / architecture guard

No dedicated spec file for a control "rail"/HUD layout exists yet; coverage today is split:

- `flight-command-panel.spec.ts` — TestBed wiring: grounded-gate disables only Arm, `.disabled-reason`
  renders verbatim, `requestArm()` no-ops while grounded.
- `flight-command-panel-logic.spec.ts` — pure: `canShowCommandPanel`, confirm-copy builders, toast
  wording per ACCEPTED/NO_ACK.
- `rc-monitor.spec.ts` / `rc-monitor-logic.spec.ts` — gamepad/keyboard/virtual wiring; pure:
  `engageDisabledReason`, `armedChip`, hints, `rcReadinessRows`, `latencyLabel`, `keyLegendLines`.
- `fly-osd-logic.spec.ts` — `isStaleReading`, `osdGroupLabel`, `armedOsdText`.
- `stream-state-logic.spec.ts`, `cv-control-panel-logic.spec.ts`, `fly-logic.spec.ts`,
  `drone-picker*.spec.ts`, `fly.routes.spec.ts` — adjacent, not control-surface-specific.

**`core/ui/architecture.spec.ts`** (`station/vision-web/src/app/core/ui/architecture.spec.ts`) — a
pure source-scan (no `TestBed`), scoped to `ROUTED_PAGES` only (`import.meta.glob`, `:78-82`). Rules:
(1) a routed page injects only its facade, never `VisionApi`/a `*Store` directly (`:97-102`); (2) no
routed-page field matching `/(Open|Menu|Confirm|Editing)\s*=\s*signal\(/` — such overlay state must
live in a `UiStore` (`:104-110`); (3) a matching `<feature>-facade.ts` must exist per routed page
(`:112-115`). **Explicit carve-out (`:20-23`)**: *"Non-routed presentational child components
(`flight-command-panel`, `telemetry-osd`, `wall-tile`, `pilots-card`) are intentionally out of
scope… they may still DI-share a host-provided store."* Only `fly/drone-picker` and `fly/cockpit` are
routed under `fly/`. **Consequence for this rework**: a new on-video HUD/control widget, so long as
it stays a non-routed child of `cockpit`, is *not* subject to the facade-injection rule and may inject
`RcSource`/`ManualControlClient`/`ThresholdsStore` directly, exactly as `flight-command-panel`/`fly-osd`
already do — but any new *mutually-exclusive show/hide* state it introduces still must go through a
`UiStore`, not a bare `signal()`.

## frontend-style — binding rules for an on-video control HUD

(Full skill loaded; section refs are the skill's own headers.)

- **§2 "video surfaces always dark" law**: full-bleed video (Fly cockpit named explicitly) carries
  `.surface-dark`, dark in both themes; never on a lone widget, never hand-built dark grays.
- **§2 the load-bearing rule for this task**: controls floating *directly over video/map tiles* use
  `--hud-*` frosted-pill tokens + `--scrim*` — theme-invariant, legal inside `.surface-dark` **or on
  any control floating directly over video**, regardless of the control's own enclave. Does **not**
  widen to ordinary page chrome over a themed panel (toolbar, docked dialog) — those stay
  `--panel-raised`/`--border`/`--shadow`.
- A control adopting `--hud-*` **outside** an already-`.surface-dark` root must self-apply the
  `surface-dark` class too, or its non-background tokens (text/border) read invisibly.
- §1: tokens only, no raw hex, 8px `--space-*` scale, two fonts (`--font` prose, `--mono` for
  telemetry numerals — throttle/steering readouts belong in `--mono`).
- §3: no large saturated fills outside `.btn` primary — an Arm button should use the standard `.btn`
  primitive + a status-hue token, not a bespoke red block.
- §7 (map overlays) is the closest existing precedent: corner-pinned frosted pills on the 8px grid,
  self-applying `surface-dark` — treat a throttle/arm HUD the same way Leaflet's own corner chrome is
  treated.
- §9 anti-slop hard bans: no gradients/glow/neon; glassmorphism legal **only** for `--hud-*` pills, not
  general chrome; no purple; no shadows for elevation; no emoji icons; motion limited to existing
  pulse/0.15s transitions, respect `prefers-reduced-motion`.
- §10: keep the same verb through a flow ("Arm" throughout warn→confirm→toast, matching
  `flight-command-panel-logic.spec.ts`'s existing assertions).
- Theme toggling: HUD content is designed to be theme-invariant by construction — `--hud-*`/`--scrim*`
  don't change with the light/dark toggle; there is no separate "what happens on toggle" rule beyond
  that.
- Final checklist: grep diff for raw hex/off-grid px/misused `--hud-*`; verify tables/panels touched
  conform to §5/§6; screenshot both themes; `npm run test:ci` + `tsc --noEmit` green; update
  `station/vision-web/MODULE.md`.

## Risks / wrinkles

1. **Arm and manual-control sticks are architecturally unrelated today.** A server-side neutral gate
   is not a small addition — it requires a new port method to expose the mailbox, a new
   arm↔manual-control coupling that doesn't exist, and a product decision for "arm with no active RC
   session" (the common case). A web-side gate is far cheaper but is advisory only (nothing stops a
   direct API call).
2. **`groundedReason` is currently a single optional string, not a composable list.** Adding a second
   independent disable-reason (stick-not-neutral) means designing reason *composition/priority* for
   the first time in this codebase — there's no existing pattern for "show reason A over reason B."
3. **This rework reverses an explicit, documented prior decision.** `CONTROLLER-UX-PLAN.md` §2.2/U5
   deliberately kept the control surface as a side-panel drawer specifically because "text competes
   with video, chips do not." Whoever writes the plan should address why that reasoning no longer
   holds, not silently contradict it.
4. **`fly-osd` is not currently a video overlay** — it's a below-video grid row
   (`cockpit.css:98-121`, with its own comment explaining *why* it was deliberately moved there,
   away from "video-as-hero" competition). Any assumption that OSD-style content already lives on the
   video is wrong; moving control readouts onto the video is a new precedent, not an extension of an
   existing one.
5. **Rover vs. copter "neutral" are different microsecond targets** (1500 centered vs. 1000
   unidirectional-rest) and this distinction is resolved **live from the vehicle's heartbeat**, never
   cached — a neutral check (wherever it lives) must read the session's actual `ControlProfile`/
   `VehicleKind`, not assume one kind.
6. **No z-index token scale exists** — every current overlay's stacking order is a hand-picked literal
   with an inline comment justifying it relative to neighbors (0,2,3,4,5,6,10,20,120). A new on-video
   HUD needs its rank chosen and documented the same ad hoc way, or this would be a good moment to
   introduce a scale — worth a call in the plan.
7. **`architecture.spec.ts`'s facade rule doesn't block a non-routed HUD child from injecting
   `RcSource` directly** — convenient, but means nothing enforces "the HUD only reads state its host
   already computed"; easy to accidentally duplicate `rc-monitor`'s own DI graph in a second place.
8. **CV detection boxes already occupy the video's canvas layer** (`shared/player/player.html`
   `overlay-canvas`) — a new control HUD sharing the same video frame needs a stacking/pointer-events
   plan that doesn't fight the existing detection-box canvas (`pointer-events: none` unless
   `interactive`).
