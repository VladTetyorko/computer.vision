# FLY-CONTROL-UX-PLAN — neutral-stick arm gate + on-video control HUD + handshake fix

**Status:** FROZEN 2026-09-01 · **Branch:** `feat/fly-control-ux` ·
**Inputs:** [fly-control-ux/R1-code-truth.md](fly-control-ux/R1-code-truth.md),
[fly-control-ux/R2-design-research.md](fly-control-ux/R2-design-research.md),
R3-handshake-denial.md (wave H1 scoped when it lands).

## 0. Why this reverses CONTROLLER-UX U5 — and what survives it

U5 ("the drawer sits over video; text competes with it, chips do not") kept the whole control
surface in a drawer. The owner now finds the drawer overwhelming as the single source of truth
*and* command surface. The **law survives, the location doesn't**: chips, bars and pills move onto
the video; every *sentence* leaves it (toast, badge tooltip, or rail). Industry consensus (R2):
QGC/DJI/Betaflight all put live state + the one live action on the video and keep setup/mapping
off it.

## 1. Scope

1. **Neutral-stick arm gate (web-side).** Arm is enabled only when live sticks read neutral —
   centered axes at 50, unidirectional throttle at 0 (tolerance from config). Server-side gate is
   **deliberately deferred**: R1 §4 shows arm and manual-control share no collaborator, the latest
   `RcChannels` frame is not exposed by any port, and "arm with no RC session" (the common case)
   needs a product policy. The flight controller's own pre-arm ("Throttle too high") remains the
   hard interlock; ours is the honest UI gate, matching ArduPilot/Betaflight conventions (R2 §6).
2. **On-video control HUD + informational rail** (contract §3).
3. **H1 — take-control handshake denial** ("The station never confirmed control") vs latest rover
   firmware — scoped by R3 when it reports.

## 2. Frozen contract — neutral gate

- **Config:** `vision.ops.rc.neutral-tolerance-percent`, default **5**, validated 1..25, in
  `VisionOpsProperties` as a nested `Rc` record exactly per the `Battery` pattern (defaults
  documented commented-out in root `application.yaml`). Exposed by the existing
  `GET /api/ops/thresholds` as `{"battery":{…},"rc":{"neutralTolerancePercent":5}}`.
  Web: `ThresholdsStore` (the one fetch-once store) gains the matching signal with local
  fallback default 5 — no second store.
- **Gate law (web):** applies only while a take-control session is engaging/engaged (that's when
  `RcSource` axes are live and bound). Per bound axis, using the profile's existing travel
  semantics (`control-surface-logic.ts`): CENTERED → `|displayPercent − 50| ≤ tol`;
  UNIDIRECTIONAL → `displayPercent ≤ tol`. Unbound axes ignored. No session → no stick gate
  (today's behavior; FC pre-arm still applies).
- **Reason composition (first time two disable reasons exist):** ordered — grounding
  (`groundedReason`, safety) wins over sticks-not-neutral. The sticks reason **names the worst
  offending axis with its live value**, per ArduPilot convention: e.g. "Throttle 62% — center
  sticks to arm" (rover) / "Throttle 34% — throttle to zero to arm" (copter). Pure logic +
  specs; composition helper lives in `flight-command-panel-logic.ts`.
- **Never gated:** Disarm, emergency verbs, mode (S1 law unchanged).
- The arm-confirm dialog additionally shows the live neutral state and refuses to reach its
  final step while the gate holds (poka-yoke both at button and dialog).

## 3. Frozen contract — HUD zones and the calm rail

R2's Option B (progressive disclosure at the opt-in boundary) with Option A's zone discipline,
constrained by frontend-style (`--hud-*` frosted pills + `--scrim*` only, `--mono` numerals,
no gradients/glow, motion ≤ 0.15s, tokens only):

**On the video (`.grid-main`, absolute, new documented z-index literal above `.main-secondary`,
below drawers; must not fight the CV `overlay-canvas` — HUD is pointer-active, canvas stays
`pointer-events:none`):**

- **At rest (control not taken):** bottom-center — ONE frosted "Take control" pill + one
  commandable badge (icon + one word: Ready / No link / Not commandable). Disabled pill carries
  the reason as `title`; full sentence lives in the rail. Nothing else new on video.
- **Engaged:** bottom-center **input widget** — live stick state as bars/glyphs, `--mono`
  numerals: rover = steering (horizontal, center-marked) + throttle (center-marked, 50-rest);
  copter/plane = two mini stick-position glyphs (roll/pitch, yaw) + throttle bar (0-rest).
  Shapes derive from the profile's travel semantics — one component, axis-shape driven, not two
  forks. Adjacent compact input-mode pills (On-screen · Transmitter · Keys; keyboard mode shows
  a key-glyph ticker instead of stick glyphs) and the Release pill.
- **Bottom-right: Arm / Disarm pills.** Existing two-stage confirm dialogs kept verbatim (same
  verbs, same specs). Arm pill visually **locked** (dimmed + reason glyph) while gated
  (grounding or sticks); `title` + a tap show the composed reason. Disarm always live when armed.
- **Status:** armed/mode chips join the existing `.main-header` cluster (top), not a new zone.
- **Transients:** "Control released — failsafe took over" and "Control denied — …" become
  toasts (existing toast infra) + rail detail; never persistent video text.

**The rail (`rc-monitor` drawer) becomes informational:** transmitter picture, per-axis mapping
rows ("Axis 2 — not mapped · Set up ›" stays here — no GCS puts mapping on video, R2 §3),
device/rate/latency chips, hints, diagnostics, and the full-sentence explanations for every
badge/lock the HUD abbreviates. It **loses** Take-control/Release, Arm/Disarm and the input-mode
tabs (all moved to HUD). Opening the drawer is never required to take control or arm.

**Conventions binding the build:** three-file components (split `failsafe-banner.ts`/
`diagnostics-card.ts` if touched), UiStore for any new exclusive show/hide state,
`architecture.spec.ts` carve-out respected (HUD = non-routed child of cockpit), specs updated
with moved responsibilities, `npm run test:ci`, MODULE.md updated.

## 4. Waves

| Wave | Agent | Scope (disjoint) | Gate |
|---|---|---|---|
| BK1 | spring-integrator | `vision.ops.rc.*` in `station/vision-app` (properties, wiring, yaml doc-comment) + thresholds DTO field in `station/vision-api` | scoped `-pl … -am test` |
| WEB1 | controller-ux | ALL of §2's web side + §3 in `station/vision-web` (one wave — serialized by design; last cycle's parallel-web-wave races) | `npm run test:ci` + `tsc --noEmit` |
| H1 | spring-integrator, **after BK1** (shares vision-api) | per R3: engage denial is a station-local 4s timeout, no vehicle ack exists. (a) catch-all exception → honest `denied` frame in `ManualControlWebSocketHandler.handleEngage` (today only 3 exceptions caught — anything else is silence); (b) verify/bound any engage-path block >4s (live vehicle-kind resolution from heartbeat) so a real `denied` always beats the client's abandon; (c) WARN log with engage duration+outcome; (d) read-only: does rover firmware's F4 first-peer authority gate also drop `RC_CHANNELS_OVERRIDE` from a second source? (would explain "granted but doesn't move" — report, firmware is out-of-repo) | scoped `-pl` tests |
| V | orchestrator | combined verify + docs + MODULE.md audit + merge | full scoped set |

BK1 ∥ WEB1 (disjoint modules; WEB1 builds against the frozen JSON contract with local fallback).

## 5. Out of scope, explicitly

Server-side neutral gate (deferred with rationale §1.1) · hold-to-arm gesture replacing the
two-stage dialog (R2 likes it; our dialog is built+tested — revisit only if the owner asks) ·
moving `fly-osd` telemetry onto the video (deliberately a below-video row, R1 §5) · z-index
token scale (one more documented literal; scale is a named future task) · any firmware editing
(sketch lives outside the repo — H1 produces the requirement text).
