# FLY-CONTROL-UX — task context

**Started:** 2026-09-01 · **Status:** research → plan → build · **Branch:** `feat/fly-control-ux`

## The ask (user, verbatim intent)

1. **Arm only from neutral sticks.** Arm must be enabled only when sticks are in neutral
   position — 50 (center) for a ground vehicle, 0 throttle for a drone.
2. **Take-control handshake fails against latest rover firmware.** User reports:
   "Control denied — The station never confirmed control. Nothing is being sent — try again",
   and "last update of arduino-start doesn't support it" — the rover's (out-of-repo) Arduino/ESP32
   firmware after its latest update no longer satisfies whatever confirmation the station waits
   for when taking control. Find the handshake, decide station-side fix vs firmware requirement.
3. **/fly view: the side panel is overwhelming.** Today it is source-of-truth AND command
   surface: throttle/steering readouts, axis rows ("Axis 2 — not mapped · Set up ›"),
   Disarm·Arm, input-mode tabs (On-screen/Transmitter/Keyboard), "Take control",
   "Control released.", "These do nothing until this drone accepts commands.",
   "This drone isn't commandable right now." — all stacked in a rail.
   Move the command/control surface to an **overlay on the video**; keep the side panel
   as the calmer, informational resource. Research how to do it better, then implement.
   Use the design skills (`.claude/skills/frontend-style`).

## Constraints known at start

- Fly surface just reworked by ASSET-FLOWS WB1/WB2 (grounded banner, arm-blocked reason,
  OSD severity from /api/ops/thresholds) — build on top, don't regress.
- Controller-setup c15 merged (34e298d0): transmitter picture, AllControls, stick_mode.
- Video surfaces are always dark (visual-refresh law); overlay must obey frontend-style.
- CLAUDE.md rule 1: neutral tolerance is configuration, not a magic number.
- UI architecture: Component→Facade→Store→Service; UiStore for exclusive overlays;
  architecture.spec.ts guard; 3-file components; `npm run test:ci`.

## Delegation plan

| Wave | Agent | Scope → output |
|---|---|---|
| R1 | Sonnet, local | Code truth: fly rail composition, manual-control input pipeline web+backend (where live axis values exist, how arm flows, whether backend can see sticks at arm time) → `fly-control-ux/R1-code-truth.md` |
| R2 | Sonnet, web | Design research: how QGC/DJI/Betaflight/racing sims lay out on-video control HUDs vs info rails; overlay legibility rules → `fly-control-ux/R2-design-research.md` |
| P | Fable | Frozen plan `FLY-CONTROL-UX-PLAN.md` (overlay contract + neutral-gate contract), then implementation waves |

## Outcome (2026-09-02)

Same-day research→plan→build. R1/R2/R3 under `fly-control-ux/`; plan frozen and executed as
BK1 (`019e653d`), H1 (`42c45d50`), H2 (`bfc9e779`), WEB1 (`b7573860`) — details in
[FLY-CONTROL-UX-PLAN.md](FLY-CONTROL-UX-PLAN.md) §Close-out. Key discoveries: no vehicle
confirmation exists for take-control (denial was a station-local silent-exception timeout, now
honest); the rover firmware's first-peer gate covers RC override frames but the station already
presents one socket identity per vehicle; the 50-center/0-rest neutral law already existed in
`control-surface-logic.ts` travel semantics. Owner smoke of /fly (both themes) pending.
