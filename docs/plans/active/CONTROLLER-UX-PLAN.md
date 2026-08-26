# CONTROLLER-UX — Fly controller drawer + step-by-step controller setup

Started 2026-08-26. Branch `feat/controller-ux` (sub-branches per wave, merged back). Pure
`station/vision-web` work against the **frozen** wire of
[CONTROLLER-SETUP-CONTEXT.md](CONTROLLER-SETUP-CONTEXT.md) (C1–C12) and
[VEHICLE-CONTROL-PROFILES-CONTEXT.md](VEHICLE-CONTROL-PROFILES-CONTEXT.md) (P1–P14). No backend
change, no wire change.

**Task, in the operator's words:** *"1) the /fly side panel control part … remake it so the user
could see the real joystick movements, buttons, arming/disarming from joystick etc. Reuse user
settings for joystick commands and axes, in a proper, visualised way, not breaking the video
context. 2) The controller settings look overcomplicated — remake so setup of each step is easy,
step-by-step for throttle, roll, pitch etc."*

Style law: `.claude/skills/frontend-style/SKILL.md` — tokens only, no animation beyond 0.15s,
one selection language, `.mono` for anything that ticks.

---

## 1. What is wrong today (read from the templates, not guessed)

| Surface | Defect |
|---|---|
| `fly/rc-monitor.html` | Axes are 8 anonymous horizontal bars (“Axis 0 … 42 %”) — the operator must mentally map bar → stick → function. Switches are a wall of `Sw 0 … Sw 11` pills with no relation to what they fire. Bound actions are a *separate* text list. Mode/Arm sit at the top, disconnected from the switch that also arms. The on-screen pads exist but only render when engaged with the virtual source; a plugged transmitter never gets a stick picture at all |
| `controller/controller-setup.html` | One page, two cards, every control is a card of 4–6 dropdowns (“This is a / and it / Function / Channel / Rests at / Reversed”). The operator has to already know the answer to every question to fill one row. Nothing sequences the work; nothing tells them they are done |

Both surfaces already sit on the right data: `ControlProfile.channelMap` (function, travel, rest
micros, reversed) and `actionMap` (positions → action). The defect is presentation, so this plan
adds **one shared visual** and **one sequenced editor** over the unchanged facade/store.

---

## 2. Design

### 2.1 The shared piece — `vision-transmitter-view` (the "third part")

One component that draws a layout **as a transmitter**, driven by any value source:

```
┌──────────────────────────────────────────────┐
│  ┌──────────┐            ┌──────────┐        │   pads from padsFrom(channelMap)
│  │    ·     │            │    ·     │        │   rover → one pad, copter/plane → two
│  │  ──┼──   │            │  ──┼──   │        │   rest mark drawn from travel:
│  │    ●     │            │    │     │        │     CENTERED → crosshair centre
│  └──────────┘            └──────────┘        │     UNIDIRECTIONAL → idle line at bottom
│  Yaw  +12  Throttle 0    Roll 0  Pitch  -3   │   .mono readouts, function names not "Axis 2"
│                                              │
│  SB  ▮▯▯  Disarm · — · Arm        ← 3-pos    │   one row per action binding: a position gauge
│  SC  ▮▯   Mode HOLD · Mode AUTO   ← 2-pos    │   lit cell = live position, text = what it fires
│  B4  ●    Emergency stop           ← button  │
│  Sw 5, Sw 6, Axis 6 — not mapped · Set up ›  │   the unbound rest, collapsed to one faint line
└──────────────────────────────────────────────┘
```

- **Inputs:** `channelMap`, `actionMap`, `vehicleKind`, a `values` source (`axes[]`/`buttons[]`),
  `interactive` (false = mirror a transmitter, true = pads are draggable and emit values).
- **Interactive mode is the existing `virtual-control-surface`**; the component absorbs it
  (`control-surface-logic.ts` pads/knob math is reused unchanged). One component, two modes,
  so the picture an operator sees of their radio is the same picture they drag on a phone.
- **Position gauge** is a new small primitive (`switch-gauge`): 2 or 3 cells from
  `catalog.inputKinds[kind].positions`, lit cell = `positionOf(source, kind, raw)`; a `BUTTON`
  is one cell lit while pressed. Dangerous actions (`catalog.actions[].dangerous`) render the
  cell text in `--color-danger-text`; the hold-to-fire progress of C9 shows as the cell filling
  (`dispatcher.holding()`), so arming from a switch is *visible* while it is happening.
- Lives in `shared/ui/transmitter-view/` (used by two features → shared, per style §1).

### 2.2 Fly drawer — `rc-monitor` rebuilt around the transmitter view

Same 24rem `vision-side-panel`; the video stays the page. Anatomy, top to bottom:

```
┌ Controller ──────────────────────────── ✕ ┐
│ ● DISARMED   HOLD   RadioMaster · 62 Hz    │  state strip: armed / mode / device+rate, chips
├────────────────────────────────────────────┤
│ [ transmitter view — §2.1 ]                │  values = selected source (gamepad or virtual)
│                                            │  before engage: mirrors the radio, pads read-only
├────────────────────────────────────────────┤
│ Mode  [HOLD ▾] [Set]      also on SC       │  flight-command-panel kept, one row each,
│ [ Arm ]  [ Disarm ]       also on SB ↑     │  "also on <switch>" tells where the same
│                                            │  command sits on the radio
├────────────────────────────────────────────┤
│ Input  (On-screen | Transmitter)           │  sticky footer
│ [        Take control        ]             │  engaged → RELEASE (danger) + link chips
└────────────────────────────────────────────┘
```

Decisions:

| # | Decision | Because |
|---|---|---|
| **U1** | The transmitter view renders **before** engage, mirroring the plugged radio | The operator's ask — "see the real joystick movements". Today nothing draws until engaged |
| **U2** | Engaged + virtual source → same view becomes interactive; engaged + gamepad → stays a mirror | One picture, not two components; P10's "source, not client" carried into the UI |
| **U3** | Mode/Arm buttons stay (clickable fallback, two-stage confirm) but move **below** the sticks and each shows *"also on SB ↑"* when a switch is bound to the same action | The radio and the buttons are the same commands; showing the pairing is what makes the switch discoverable |
| **U4** | Raw `Axis n`/`Sw n` bars and pills are **deleted**; unbound inputs collapse to one line linking to `/manage/controller` | They were the debugging view. Their only remaining job (“which one is Sw 5?”) belongs to setup's Detect |
| **U5** | State strip is chips only; no prose above the fold. Prose (USB Joystick mode caveat, watchdog notice) stays but sits under the strip as `vision-notice` only when true | The drawer sits over video; text competes with it, chips do not |
| **U6** | Take-control block becomes a **sticky footer** | Engage/release must be reachable without scrolling past the sticks — it is the safety control |

### 2.3 Setup page — a sequence, because setup *is* one

Route unchanged (`/manage/controller`). The page becomes: a slim layout bar, a step rail, one
step at a time, a review step that is the transmitter view.

```
┌ Controller ──────────────────────────────────────────────────────────┐
│ Layout  [Bench rover ▾]  Rover · Active        [New layout] [All controls ▾] │
│                                                                      │
│ ✓ Throttle   ✓ Steering   ● Arm   ○ Mode   ○ Extras   ○ Review       │  step rail
├──────────────────────────────┬───────────────────────────────────────┤
│ Step 3 of 6                  │  This switch will                     │
│ Arm switch                   │  ┌ LOW ─────────┐ ┌ HIGH ────────┐    │
│                              │  │ Disarm      ▾│ │ Arm         ▾│    │
│ Flip the switch you want     │  └──────────────┘ └──────────────┘    │
│ to arm with.                 │                                       │
│                              │  ⚠ Arm from a switch needs a hold —   │
│   SB   ▮ ▯ ▯   ← live        │    a stray flick won't arm.           │
│   detected: Axis 4,          │                                       │
│   3-position switch  [change]│                                       │
│                              │                                       │
│ [ Skip this step ]           │                    [ Back ] [ Next → ]│
└──────────────────────────────┴───────────────────────────────────────┘
```

**Step list is derived, not hardcoded** (`controller-wizard-logic.ts`, pure):
from `catalog.vehicleKinds` + the built-in layout for that kind — one step per channel function
the built-in binds (rover: Throttle, Steering; copter/plane: Throttle, Yaw, Pitch, Roll), then
**Arm**, **Mode**, **Extras** (Emergency stop / Return home / Aux, optional, multi), **Review**.

Per step kind:

| Step | Left (instruction + live) | Right (the choices) | Facade calls |
|---|---|---|---|
| Channel function | "Move the **throttle** all the way up, then down." Large vertical/horizontal gauge of the detected control; Detect is *on* by default while the step is open (`learning()`), the input with the largest travel since step-open wins; "change" reopens detection | **Direction** tiles: "Up is more" / "Up is less" (→ `reversed`), preselected from live sign. **Rests** tiles in prose, only for throttle: "At the bottom — idle is 0 %" / "In the centre — centre is stop" (→ `travel`), preselected from the built-in. `Advanced ▸` reveals channel select | `addControl`, `setRole('CHANNEL')`, `setFunction`, `setTravel`, `setReversed`, `setChannel` |
| Arm | "Flip the switch you want to arm with." Switch gauge, detected kind (2/3-pos/button) editable | One action select per position, defaults `LOW→Disarm`, `HIGH→Arm` (3-pos: MIDDLE → nothing). Dangerous notice (C9) | `addControl`, `setKind`, `setRole('ACTIONS')`, `setPositionAction` |
| Mode | "Flip your mode switch." | Per position: mode name from `capabilities`-independent free text today (`MODE_NAME` parameter) — offered as a datalist of the kind's known ArduPilot mode names (from the built-in's catalog; if absent, free text) | same + `setPositionParameter` |
| Extras | "Anything else on the radio?" list of remaining detected controls; pick one → same position editor | Emergency stop / Return home / Aux function (number select) | same |
| Review | The transmitter view (§2.1) driven live by the radio, so the operator *moves every stick and sees it land* | Issues list (`facade.issues()`), **Save** / **Save and activate** | `save`, `activate` |

Decisions:

| # | Decision | Because |
|---|---|---|
| **U7** | Steps are a real sequence and are numbered ("Step 3 of 6") | Order carries meaning: sticks before switches, arm before mode, review last. Numbering is justified here, nowhere else |
| **U8** | Every step is **skippable** and re-enterable from the rail; a skipped channel step leaves the built-in's binding in place | C7: the built-in is the floor. Skipping never produces a layout with no throttle |
| **U9** | Detect is on while a step is open; no separate Detect button | The gesture every station has (ArduPilot §2.2). Making it the default removes one click per step |
| **U10** | Channel numbers, µs, deadband are **Advanced**, collapsed | Derived from travel already (`controller-setup-logic.ts` header). Asking for them is asking to hand-maintain an invariant |
| **U11** | The old full editor survives as **"All controls"** (a collapsible under the layout bar) | Power users and the "unmap this" case. Zero facade change, so it is free to keep; it is no longer the page |
| **U12** | Built-in layouts open the wizard read-only with a single "Make a copy to edit" action on every step | Existing rule (built-ins never edited) kept; the wizard just shows it in place instead of a notice |

### 2.4 Visual direction (the plan's own token choices)

- Palette is the app's: `--panel`/`--panel-raised` pads, `--border-strong` pad frame, knob in
  `--color-info` (interactive) or `--text` (mirror), rest mark in `--border-strong`, lit switch
  cell `--color-info-soft` + 2px inset bar (style §4 — the one selection language, reused for
  "this position is live"). Arm cell text `--color-danger-text`. Nothing new in `styles.css`.
- Type: function names in the display register; every value in `.mono`; step rail labels in the
  structural-label register (uppercase, +0.04em).
- Motion: knob follows values with no transition (it must be live); lit cell 0.15s; hold-to-fire
  fill is the one deliberate animation, linear over the dispatcher's hold time, and is a state
  indicator not decoration. `prefers-reduced-motion` drops the fill to a step change.
- The one risk taken: the drawer shows a *transmitter*, not a form. The Mode/Arm buttons, the
  most prominent thing today, become secondary to the picture of the radio.

---

## 3. Waves (disjoint file scopes, all `station/vision-web`)

| Wave | Agent | Files | Content | Green when |
|---|---|---|---|---|
| **X1** | web-ui | `core/rc/transmitter-view-logic.ts` (+spec), `shared/ui/transmitter-view/*` (ts/html/css), `shared/ui/switch-gauge/*` | §2.1 component: pads (absorbs `virtual-control-surface`'s template/CSS — that component becomes a thin re-export or is deleted in X2), switch gauges, unmapped line, `interactive` mode | spec: pads for rover/copter, lit position for 2/3/button, dangerous styling, unmapped list; `tsc` |
| **X2** | web-ui | `features/fly/rc-monitor.{ts,html,css}` (+spec), `features/fly/rc-monitor-logic.ts`, `features/fly/virtual-control-surface.*` (delete/alias), `features/fly/flight-command-panel.{ts,html,css}` ("also on") | §2.2 drawer | existing rc-monitor specs adapted; both themes screenshot inside `.surface-dark` |
| **X3** | web-ui | `core/rc/controller-wizard-logic.ts` (+spec) | Pure step derivation + per-step defaults + detect winner selection (largest travel) | spec: rover/copter step lists, defaults, detect ranking |
| **X4** | web-ui | `features/controller/controller-setup.{ts,html,css}`, `features/controller/wizard-step.*`, `features/controller/step-rail.*`, `features/controller/all-controls.*` (old editor moved verbatim) | §2.3 page | existing controller specs green; both themes |
| **X5** | web-ui | `MODULE.md`, `docs/plans/README.md`, this file §4 | docs | — |

X1 and X3 run in parallel; X2 needs X1; X4 needs X1 + X3. X5 last.

Agent rules: three-file components (`.ts/.html/.css`), tokens only, no new facade methods unless
the existing set genuinely cannot express a wizard action (report it; do not add a constructor
overload or a null-means-off parameter). Run `npx tsc --noEmit` and the scoped `ng test` for the
touched specs; do not run the Maven reactor.

---

## 4. Status

| Wave | State |
|---|---|
| X1 | open |
| X2 | open |
| X3 | open |
| X4 | open |
| X5 | open |
