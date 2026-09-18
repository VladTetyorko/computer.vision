# R2 — Cockpit design analogs for `/fly/:assetId`

Research only, no code changed. Grounded in `cockpit.html`/`fly-hud.html`/`fly-osd.html`/
`fly-logic.ts`/`rc-monitor.html`/`follow-hud.html`, `FLY-FLOW-PLAN.md` §2-§4, and
`.claude/skills/frontend-style/SKILL.md`.

**Where we already stand**, so the analogs read as validation-or-correction, not a blank slate: L0
glass carries only video + detection boxes + follow-crop, nothing else burned in. L1 frame is
header (top) + OSD shelf (one bottom row: Power/Nav/Link/Env) + tool-rail (right edge, grouped
icon buttons). L2 is the one bottom-center dock (idle card → live badge+CTA → engaged widget+
Release) plus the bottom-right Arm zone. L3 is on-demand: rail drawers, the take-control ritual
modal, the CV setup modal, the stop-confirm scrim. FLY-FLOW-PLAN's own W1-W4 already deleted a
duplicate header armed/mode bar, a duplicate Start-stream card, a dead mini-map, and a floating
preflight chip — this research assumes those cuts stand and mostly asks whether the state of the
art agrees, and what's left to steal.

## 1. The six analogs

**DJI Fly / DJI Pilot 2.** Every instrument is corner/edge-anchored on a rigid grid; the top strip
is one *collapsed* status row that expands only on tap; bottom-right is a dual altitude/distance
readout that appears only once airborne. RTH is one icon with a hold-to-confirm gesture, physically
separated from the camera-control dock on the opposite edge. *Transfers*: corner/edge discipline +
clear center is already our L0/L1; the hold-to-confirm gesture is worth stealing for "Bring home"
(today a single tap); the tap-to-expand strip is the same idiom as our OSD's Env chevron.

**QGroundControl / Mission Planner / Auterion Mission Control.** Pro GCS separates a persistent
instrument-panel widget (alt/hspeed/vspeed/flight-time/distance) from one top bar reading the whole
vehicle at a glance (vehicle/mode/armed/GPS/link/battery), and gates every state-changing action
behind a hold-to-confirm slider. *Transfers*: the hold-to-confirm slider generalizes to our Arm
zone and the take-control modal's "Start control." The top-bar-as-one-source-of-truth idea is
mainly a **correction**: it's the literal shape of the header armed/mode chips FLY-FLOW-PLAN §4 W2
deleted by owner request for duplicating the OSD — pro-GCS precedent doesn't undo that call.

**ATAK / military moving map.** Clutter is managed by strict layer discipline — every symbol type
lives in a toggleable layer, off by default, one consistent glyph vocabulary; the map is the star,
video is one pane among several. *Transfers*: this is already our Marks/Map-layers drawer model
(MAP-REWORK-PLAN's layer+grant system) — the lesson is defensive: don't let the map inset silently
accrue new always-on layers without an equivalent toggle.

**FPV OSD (Betaflight / DJI goggles).** A strict "glance zone" (top rows) carries only what changes
a second-to-second decision (voltage/RSSI/timer); layouts scale by information budget — a minimal
race profile (voltage+timer+crosshair) up to a long-range profile (adds GPS/home-arrow/altitude).
Center crosshair is one undecorated 1px glyph. *Transfers*: profile-scaled density is our Env
chevron / rail-dot idiom, worth extending to new fields by default; the crosshair etiquette is a
hard argument against ever putting a fixed glyph at video-center (§5).

**Aviation PFD.** Dark-cockpit: nothing illuminates while nominal; a caution earns amber, a warning
earns red, and *absence of color* is the "all normal" signal — not a separate green light. Tape
gauges and the attitude ladder exist only because the airframe has a continuous, high-rate, trusted
sensor feeding them; without a window, the tape *is* the pilot's horizon. *Transfers*: dark-cockpit
is the single most load-bearing idea here — the theoretical grounding for FLY-FLOW-PLAN's whole
declutter swing, and the strongest argument in §5 for what a fake attitude ladder would cost us.

**Skydio / Parrot FreeFlight.** Since the vehicle flies itself, chrome shifts from control
instruments to subject/mission state: a tracked-subject box/halo on the video itself (never a side
panel), a compact mode chip standing in for a whole instrument cluster, an AR north-arrow for
orientation without leaving the feed. *Transfers*: validates our Follow HUD (a pill near the video,
not a drawer) and our on-player detection boxes — already this shape. The AR arrow is worth a
narrow, non-on-glass version (steal-list #5), not the full on-video overlay (§6).

## 2. Patterns worth stealing

1. **Hold-to-confirm the one-shot irreversible command** (DJI RTH, Auterion's slider). Cheaper
   than a modal under stress while still requiring deliberate intent. Candidate: our L1 "Bring
   home" button, today a single tap with a toast-only result. Does *not* argue for touching
   Stop-stream's confirm scrim — that's a deliberate two-step modal per an existing poka-yoke rule,
   and a hold gesture is a weaker guarantee than a second explicit tap.
2. **Progressive disclosure by information class**, not one flat row (FPV profiles, DJI's collapsed
   strip). We already do this once (OSD's Env chevron) — keep defaulting new telemetry into an
   existing group behind that idiom rather than growing the shelf's flat group count.
3. **Glance-zone weighting inside the OSD shelf.** Safety-gating facts (battery/link-age severity)
   read first; purely informational facts (GPS quality, heading, speed) read after. Our Power→Nav→
   Link→Env order already roughly does this — worth protecting as new fields land.
4. **On-glass subject lock, off-glass status pill** (Skydio, Parrot). Already exactly our shape:
   `lockedTrackId`/`lostBox` on L0, `<vision-follow-hud>` pill on L1/L2. Cited as validation — don't
   move lock status into a drawer "for consistency" with the other rail drawers.
5. **A rotating direction glyph beside the numeric heading**, not instead of it (Skydio's AR arrow,
   scoped down). `fly-osd.html` already has this exact idiom one metric over
   (`windDirectionDegrees()`'s rotated `.wind-arrow`) — reusing it on `headingLabel()` is cheap and
   precedented in the same file, on the OSD, not the glass.
6. **Hold-to-confirm the arm ritual itself**, not just gate it (PX4 GCS convention). Readiness
   checks catch *whether* arming is safe; a deliberate gesture catches *whether the operator meant
   to* — a distinct failure mode. Our neutral-stick gate covers the first half; `flight-command-
   panel`'s Arm button is the L2 candidate for the second.
7. **Physically separate "read" chrome from "act" chrome** (DJI's camera dock vs. instrument strip;
   Auterion's top bar vs. quick-actions sidebar). Already true for us — OSD shelf (read-only)
   opposite the tool-rail (all buttons). The one blend point, the dock's badge+Take-control row, is
   a deliberate one-CTA exception, not a precedent to extend.
8. **One secondary surface open at a time, never two.** Every analog researched enforces this; our
   `panels` state already does too (`isPanelOpen`/`togglePanel` close-before-open). Cited as a
   pattern to defend, since relaxing it is exactly how D1's competing-zones defect happened.

## 3. Anti-patterns to avoid

- **Fake instruments for data we don't have at the needed rate/trust** — an attitude ladder, tape
  gauge, or AR horizon each need a continuous high-rate trusted stream; ours arrives at sample rate
  (the OSD's own `age` chip measures staleness in seconds). Building the widget before the stream
  is CLAUDE.md rule 9's "never fabricate," applied to UI.
- **A second "everything is fine" bar** duplicating the OSD (Auterion's top-bar shape) — the
  literal D3 defect already found and fixed; don't let it back in under a new name.
- **A permanent always-on mini-map thumbnail** distinct from the toggleable map inset — reopens the
  "world-zoomed empty Leaflet answers nothing" bug (D5) in a second slot.
- **Decoration borrowing an intent color, or gradient/glow/animation for a routine state** —
  anti-slop §9 bans this outright; a stale-but-not-critical reading needs a color, not a pulse.
- **A center-screen crosshair/reticle** — FPV needs one to aim through gates; we have no "aim"
  concept, so it's pure decoration on glass reserved for detections/follow-crop only.
- **Relaxing "one drawer at a time"** for a power-user ask — direct path back to D1.
- **A green "all systems OK" light.** Dark-cockpit's real claim is that *silence* means healthy, not
  that a lit indicator does — an affirmative "OK" glyph is itself a claim that can go stale (a
  missed poll, a stuck sensor) exactly like a red one can. Silence only, never a positive glyph.

## 4. Recommended glass layout

Matches what's already built, plus the additions from §2 marked "(new)" — this is research, not an
implementation license.

### S4 — live, engaged

```
┌──────────────────────────────────────────────────────────────────────┐
│ ‹ ⛨ 🗺 [Simulated feed]              Drone: Falcon-3 ▾  ⟨Bring home⟩│  L1 header
├──────────────────────────────────────────────────────────────────────┤
│ ┌ ticker ≤3 rows ┐                                                [C]│
│                                                                    [V]│  L1 tool-rail
│                    ░░░  video, full-bleed, L0  ░░░                [S]│  (Control/Vision/
│                                                                    [M]│   Situational/Help)
│ ┌ map inset / honest placeholder ┐                                [H]│
│ ┌ secondary cam tile(s) ┐                                             │
│  ┌follow-hud──────────────┐            ┌── Arm/Disarm (L2) ──┐       │
│  │● Following #14 Zoom×2 Release│      │  ARMED · Loiter      │       │
│  └──────────────────────────┘          └──────────────────────┘       │
│              ┌── engaged widget (L2 dock) ──┐                        │
│              │ ⇄ stick bars / key-ticker      │                        │
│              │ [Release]  Source: On-screen▾  │                        │
│              └────────────────────────────────┘                        │
├──────────────────────────────────────────────────────────────────────┤
│ POWER 87% ARMED │ NAV 51.40,4.41·62m·142°↗(new)·4.1m/s·Loiter·GPS 11 │ L1 OSD shelf
│ LINK 0.4s·62%·WebRTC·Δ2m        Env▾                 [Stop stream]  │ (weighted L→R)
└──────────────────────────────────────────────────────────────────────┘
```

Banner row (grounded/failsafe) sits above the header shown here — zero-height absent a real
condition, so omitted (it's a state-change overlay, see §5). Diagnostics card (main-right) omitted
too — only present `@if diagnosticsRows().length > 0`.

### S1/S2 — idle / starting

```
┌──────────────────────────────────────────────────────────────────────┐
│ ‹ ⛨ 🗺 [Simulated feed]              Drone: Falcon-3 ▾              │  L1 header (no
├──────────────────────────────────────────────────────────────────────┤   Bring-home pre-session
│                                                                    [C]│
│                                                                    [V]│
│                 ░░  static / last-frame or black, L0  ░░           [S]│
│                                                                    [M]│
│ ┌ map inset / honest placeholder ┐                                [H]│
│ ┌ secondary cam tile(s) ┐                                             │
│              ┌── dock-card (L2, one CTA) ─────┐                      │
│              │ Not streaming                    │                      │
│              │ Last seen 4m 12s ago              │                      │
│              │ 51.4012, 4.4123 · Open on map ›  │                      │
│              │ Pre-flight: 2 blockers            │                      │
│              │       [ Start stream ]             │                      │
│              │ Replay last flight ⋮                │                      │
│              └───────────────────────────────────┘                      │
├──────────────────────────────────────────────────────────────────────┤
│ POWER No telemetry        NAV —          LINK —                 Env▾ │
└──────────────────────────────────────────────────────────────────────┘
```

The take-control ritual modal isn't drawn here — it's an L3 dialog opened only from the S3-live
dock's "Take control" pill, out of scope for this S1/S2 frame.

## 5. The dark-cockpit question: rest-state inventory

**Structural chrome, always visible once an asset is selected** — navigational furniture, not an
instrument, so dark-cockpit doesn't ask it to hide: exit/readiness/map-toggle icons, sim-feed chip
(only if origin is synthetic), drone switcher, the tool-rail's grouped buttons.

**Baseline instruments, always visible whenever the backing data exists** — dark-cockpit's claim is
about *warnings*, not about primary instruments disappearing (a PFD's altitude tape doesn't hide at
rest either): OSD Power/Nav/Link whenever telemetry exists; Env behind its own chevron. Every
metric's digit stays present; only its *color* escalates (batt-/gps-/age-severity) on a real
threshold breach — this is where dark-cockpit discipline actually bites, at the metric's color, not
the group's existence.

**Per-stage rest content** (the S1→S4 dock): S1/S2 show the dock-card; S3 at rest shows badge+
Take-control only; S4 shows the engaged widget+Release. None of this is a "warning" — it's the
one-CTA law's own affordance, correctly present at every stage.

**Appears only on a real state change — the actual dark-cockpit tell:**
- `grid-banner` (grounded/failsafe) — zero height until a real condition exists.
- The mutually-exclusive notice line above the dock (stalled video / detections-paused /
  detection-off) — silent unless something genuinely needs stating.
- Crew camera-presence line — "not a badge cluster, not a notification," zero pixels unless someone
  else holds the seat.
- Follow HUD — renders nothing unless a lock has ever been issued.
- Tool-rail's "detection is off" dot, and the diagnostics card (`@if rows().length > 0`).
- Every L3 surface (drawers, CV setup modal, stop-confirm scrim, take-control modal) — on demand
  only, by construction.

**Verdict.** The app already implements dark-cockpit correctly at the notice/banner/severity layer.
One policy worth stating explicitly for future work: **silence, not a green light.** Boeing's
"quiet and dark" reads absence-of-signal as fine; some panels instead light a positive "system OK"
indicator (closer to Airbus). Ours should stay the former — a green "all clear" chip anywhere here
would itself be a claim that can go stale (a stuck poll, a missed sample) exactly like a red one
can, the same class of thing CLAUDE.md rule 9 already rules out for every other fact in this app.

## 6. What NOT to build — scope traps

1. **Attitude indicator / artificial horizon over the video.** No genuine high-rate attitude stream
   is wired into this cockpit today (the attitude/gimbal decode from the geo-pose work feeds map
   math, not this UI) — building the widget first is the fake-instrument anti-pattern. Even wired,
   it's redundant: the video frame *is* the real horizon; a synthetic overlay only ever agrees (adds
   nothing) or visibly disagrees during lag (an honesty violation, two "truths" in one frame). PFDs
   need this because the pilot has no window; we always have the window.
2. **A permanent mini-map / PIP.** Already tried and reversed (D5's empty-Leaflet ban) — a fixed
   always-on minimap distinct from the toggleable inset reopens the identical bug elsewhere.
3. **Tape gauges.** Earned only by a continuous tens-of-Hz sensor with real interpolation; our
   values update at sample rate (the OSD's own age chip measures the gap in seconds). A tape gauge
   here either freezes between samples or fabricates motion between real points — dishonest either
   way. A digit + severity color, as today, is the honest instrument for this refresh rate.
4. **A second vehicle-state summary bar in the header.** The literal shape of the D3 defect already
   found and deleted by owner request. Any future "glance summary" ask should extend the existing
   OSD group, never add a second bar under a new name.
5. **A center-screen crosshair/reticle.** FPV earns one aiming through gates; we have no equivalent
   "aim" concept, so it's pure decoration on L0 — banned by anti-slop §9 and by L0's own "nothing
   burned on the glass but video/detections/crop" rule.
6. **An on-glass AR compass arrow composited over the video** (Skydio's full implementation, not
   the scoped-down version in steal-list #5). It needs a georeferencing-confidence story we haven't
   scoped, and it burns a new permanent glyph onto L0. The cheap, precedented version belongs in the
   OSD heading metric (reuse the wind-arrow idiom) — build that, not the on-video overlay.
7. **Multiple simultaneously open tool-rail drawers**, or a "pinned" always-open drawer. Every
   analog researched enforces exactly one open secondary surface; relaxing our `panels` single-open
   invariant is the direct path back to D1's competing-zones defect class.
8. **Animated/pulsing chrome for routine severity tiers.** Reserve motion for the rarest, most
   urgent tier only — a stale-but-not-critical link age or a low-but-not-blocking battery reading
   should change color, never gain motion; anti-slop §9 already bans decorative animation outright.
