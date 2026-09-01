# R2 — Design research: on-video control HUD vs informational rail

**Task:** FLY-CONTROL-UX wave R2. Research only — no product code touched.
**Question:** how do the best operator-facing tools split *information*
(rail/panel) from *command surface* (overlay on the live view), and how do
they gate a safety-critical action like arm? Feeds `FLY-CONTROL-UX-PLAN.md`.

---

## 1. QGroundControl / Mission Planner / ArduPilot GCS

QGC's **Fly View** is the closest analogue to `/fly`: full-bleed map/video with
instrument overlays and thin edge panels, not a data-dense rail.

- **On the video (overlay):** attitude/compass HUD widget, corner-anchored;
  a **configurable telemetry-values panel** floating on the map/video (operator
  picks which values show — not a hardcoded stack); a **Confirmation Slider** —
  "context sensitive slider to confirm requested actions. Slide to confirm
  operation" — used for arm, takeoff, land, RTL, appearing only while the
  action is live. Arm button → slider is **disabled until pre-arm checks
  clear**: "When all issues blocking arming have been removed you can use the
  arm button to display the arming confirmation slider." **Virtual joystick**
  (on-screen mode) draws directly on the video, with auto-center-throttle and
  left-hand swap options — the stick widget is itself an overlay. An optional
  altitude slider sits on the right edge. Disarming mid-flight requires the
  harder **Emergency Stop** confirmation ("your vehicle will crash") — a second
  tier above normal slide-to-confirm for destructive in-flight commands.
  [Fly View](https://docs.qgroundcontrol.com/master/en/qgc-user-guide/fly_view/fly_view.html) ·
  [Virtual Joystick](https://docs.qgroundcontrol.com/master/en/qgc-user-guide/settings_view/virtual_joystick.html) ·
  [Fly View 4.4 (E-stop)](https://docs.qgroundcontrol.com/v4.4.3/en/qgc-user-guide/fly_view/fly_view.html)
- **Off the video:** view selector, a "Fly Tools" panel (Pause/Action), a top
  status-indicator strip for mode + component health — thin edge strips, not a
  full-height rail. [Fly View Toolbar](https://docs.qgroundcontrol.com/Stable_V5.0/en/qgc-user-guide/fly_view/fly_view_toolbar.html)

**Mission Planner** keeps the split in a desktop idiom: the artificial-horizon
HUD sits over/beside the map, is user-customizable via right-click → "User
Items", and can be **detached to its own window** — explicitly a separable
overlay object, not baked into a fixed rail. Controls live in a separate
lower-left "Control and Status" tab area.
[Flight Data Screen](https://ardupilot.org/planner/docs/mission-planner-flight-data.html)

**Takeaway:** overlay = instruments + the one live action widget (slider),
gated by pre-checks; panel = everything not needed to fly *this second*.

---

## 2. DJI Go / DJI Fly / Autel

DJI Fly's camera view is a fully-worked edge-anchored HUD — no side rail for
flight control at all:

- **Top-left:** back arrow, one **safety status dot** (green/yellow/red)
  collapsing GPS/obstacle/system health into a single glance, plus RC +
  aircraft signal bars. **Top-center:** flight mode, battery %, GPS sat count,
  timer. **Top-right:** settings, auto-RTH icon, record. **Bottom-left:**
  mini-map. **Bottom-right:** altitude/H-speed/V-speed.
- **Command affordances are press-and-hold, not tap:** "you can press and hold
  the button to initiate auto takeoff or landing"; RTH is "click the icon...
  then in the pop-up, press and hold... to trigger Return to Home." The hold
  gesture *is* the confirmation — no modal stacked on top.
  [DJI Fly App intro](https://support.dji.com/help/content?customId=en-us03400006562&spaceId=34&re=US&lang=en&documentType=artical&paperDocType=paper) ·
  [DJI Fly overview](https://www.droneblog.com/dji-fly-app/)
- **Not-ready states are a colored dot + short label, not paragraphs**: "No
  GPS"/weak-GPS and RC-disconnect surface as a named banner ("Aircraft
  Disconnected", "Take off with caution (no GPS)") that appears only while
  true and vacates with the condition — never a persistent sentence.
  [GPS troubleshooting](https://repair.dji.com/help/content?customId=en-us03400008822&spaceId=34&re=US&lang=en&documentType=barrierTree&paperDocType=resource&encryptId=R0ME4gW0YE%2Fw6rSy18hEJA%3D%3D)

Autel Explorer follows the same genre (FPV feed is the canvas, telemetry/camera
controls are edge overlays); public docs are thin on exact zone layout.
[Autel Explorer](https://apps.apple.com/au/app/autel-explorer/id1395815245)

**Takeaway:** consumer HUDs never explain *why* something's blocked in
on-screen prose — a colored status dot in a fixed slot, explanation one tap
away. Hold-gestures double as the confirmation; no separate sentence needed.

---

## 3. Betaflight / EdgeTX + FPV OSD conventions

FPV OSD is the extreme "minimal-burn-in" precedent — a fixed character grid
over the video where every element earns its pixels.

- **Consensus essential set:** battery voltage (or avg cell), RSSI/dBm or Link
  Quality (ELRS), armed **timer**, flight mode, a warnings channel — "Focus on
  the essentials to keep your FPV feed clear and readable."
  [Oscar Liang — OSD setup](https://oscarliang.com/betaflight-osd/)
- **Placement convention:** arm/mode status top-left, timer top-center,
  battery/mAh bottom-center — status at the top edge, fuel-gauge state at the
  bottom edge, both out of the visual center the pilot is actually flying
  through. [UAVMODEL — warnings/alerts](https://blog.uavmodel.com/betaflight-osd-warnings-and-alerts-configuration-low-battery-rssi-link-quality-and-custom-thresholds-2026/)
- **A dedicated "Warnings" element** exists so alert strings don't occupy a
  permanent slot — they appear (blinking, top/center) only while true, then
  vacate: the OSD-world "toast, not permanent sentence."
- **What stays off-video:** channel mapping, rates/expo, PID tuning, arm-switch
  assignment — Configurator-only, pre-flight, exactly the class of thing our
  rail's "Axis 2 — not mapped · Set up ›" row is.
  [Betaflight Controls](https://betaflight.com/docs/wiki/guides/current/Controls)

**Takeaway:** axis-mapping/setup rows belong in the rail, full stop — no
GCS/FPV precedent puts channel mapping on live video. Only *live state*
(armed, link, battery-equivalent, mode) earns overlay space.

---

## 4. Games / simulators (racing, flight sims, drone sims)

- **Racing sims (Automobilista 2, RaceRoom):** throttle/brake/clutch (often
  steering) render as **vertical bars bottom-center**, toggle-able, live —
  the closest genre-precedent for turning throttle/steering % readouts into
  an overlay instead of rail rows.
  [AMS2 HUD](https://automobilista-2.fandom.com/wiki/Heads-Up_Display) ·
  [RaceRoom HUD](https://raceroom.miraheze.org/wiki/HUD)
- **Drone-racing sims (Liftoff, Velocidrone):** both ship a full **OSD editor**
  matching real OSD layouts — HUD layout is a first-class, user-editable
  artifact, not fixed chrome. Velocidrone explicitly toggles a **stick-position
  overlay** on/off — the same "show my own input back to me?" question we have
  for on-screen vs transmitter vs keyboard modes.
  [Liftoff OSD Editor](https://steamcommunity.com/sharedfiles/filedetails/?id=3570287146) ·
  [Velocidrone manual](https://velocidrone.co.uk/desktop_manual)
- **Game-HUD doctrine:** players spend "around 80% of their visual attention...
  on the gameplay area, leaving only 20% for HUD elements" — the case for
  **progressive disclosure**: reveal detail only when there's time/need. State
  model for any HUD element: **idle → hover → active → warning**, consistent
  treatment, not new copy each time.
  [Rocketbrush — HUD guide](https://rocketbrush.com/blog/designing-practical-and-pretty-hud-in-video-games)
- **Corner/edge convention** inherited across FPS/racing: top = status, bottom
  = resources/inputs, corners = secondary (map, inventory) — the same
  top=status/bottom=inputs split QGC and DJI independently converge on.

**Takeaway:** input readouts are a solved genre problem — bottom-center bars
directly on the viewport, not list rows. Sims treat "show my own stick
position" as an explicit opt-in toggle, mapping onto our input-mode split.

---

## 5. HUD/overlay design principles

- **Legibility over arbitrary video:** "a subtle drop shadow, a semi-transparent
  scrim behind text, or an outline/stroke on key glyphs" — test over "the
  brightest and busiest scenes, not... a flat gray comp." Our video spans
  daylight/thermal/night sources — scrim or shadow is mandatory, not polish.
  [Medium — 7 HUD mistakes](https://medium.com/design-bootcamp/7-obvious-beginner-mistakes-with-your-games-hud-from-a-ui-ux-art-director-d852e255184a)
- **Numerals must be unambiguous at a glance** (6-vs-8 confusion called out) —
  matters for our throttle/steering % and battery/voltage numbers on video.
- **Shape + color before text:** "Readable HUDs... communicate through shape
  and color rather than text where possible, stay anchored in stable
  positions." Directly targets our three status *sentences* — each should
  collapse to icon + one word in a stable slot, matching DJI's top-left status
  dot and Betaflight's fixed arm-status slot.
- **Progressive disclosure** is the antidote to "wall of controls": reveal full
  detail on hover/focus/engagement, not by default — a compact badge at rest,
  the full input widget only once control is actually taken.
- **Transient vs persistent:** ephemeral events ("control released") are toasts
  that decay; ongoing state (armed, link, commandable) is a persistent small
  badge, never a re-read-each-time sentence.

---

## 6. Neutral-stick arm gating precedent

Direct precedent for "center sticks to arm":

- **ArduPilot Rover** (our rover analogue — steering + throttle, no 4-axis):
  arm by sticks = "**center the throttle stick and hold the steering stick
  fully to the right for 2 seconds**"; disarm = hold steering fully left for
  2s. Throttle-centered is a precondition of the gesture itself.
  [Rover — Arming](https://ardupilot.org/rover/docs/arming-your-rover.html)
- **ArduPilot Copter/Plane pre-arm checks** include a named **"Throttle too
  high"** check blocking arm outright — surfaced on the GCS HUD in red, not a
  generic denial. Mis-calibrated RC endpoints (`RC3_MIN` set to the failsafe
  value, not the true minimum) are the top false-positive cause — argues for
  building this nuance into our neutral-tolerance config, not a hardcoded
  epsilon. [Pre-Arm Checks](https://ardupilot.org/copter/docs/common-prearm-safety-checks.html) ·
  [Forum: Throttle too high](https://discuss.ardupilot.org/t/prearm-throttle-too-high/70686)
- **Betaflight default stick arming:** arm = "**Throttle LOW, Yaw HIGH**, Pitch
  CENTER, Roll CENTER"; disarm = "Throttle LOW, Yaw LOW, Pitch CENTER, Roll
  CENTER." Throttle-low is the universal precondition; yaw direction toggles
  state. Yaw is also **deadened below the arm threshold** — "yaw input will
  not cause the craft to yaw when the throttle is LOW (below `min_check`)" —
  the mechanism that makes ground arm-gestures safe. If our rover ever allows
  steering movement during the arm hold, the analogous move is to deaden
  steering while throttle is below the arm gate.
  [Betaflight Controls](https://betaflight.com/docs/wiki/guides/current/Controls)
- **General hold-to-confirm precedent:** a sustained-press requirement "adds
  intentional friction... shifting the action from reflex to conscious
  decision" — recommended windows run **~1–1.5s for reversible actions up to
  ~5s for higher-consequence ones**, always paired with a filling bar/countdown
  so the control communicates its own progress.
  [Holding beats dialogs](https://tomj.pro/why-holding-buttons-is-superior-to-confirmation-dialogs-in-ux-design/) ·
  [Hold-to-confirm pattern](https://scrollxui.dev/docs/components/hold-toconfirm)

**Synthesis:** ArduPilot Rover is a near-exact precedent for our rover case
(throttle-center + deliberate steering hold); Betaflight is the precedent for
drone (throttle-low precondition common to arm *and* disarm). Both encode "arm
control is inert until sticks read neutral" and name the specific blocking
axis rather than a generic denial — our arm affordance should visually lock
(dim + centered-stick glyph) until axes read neutral, and any denial should
name the offending axis, matching that convention.

---

## 7. Synthesis — layout options for `/fly`

Rail today = throttle/steering % + axis rows ("Axis 2 — not mapped · Set up
›") + Disarm/Arm + input-mode tabs (On-screen/Transmitter/Keyboard) + Take
Control + three status sentences. Target: command surface moves onto the
video; rail becomes calmer/informational. Must cover rover (2-axis) and drone
(4-axis), all three input modes, and control staying opt-in.

### Status-sentence → icon/badge/toast mapping (applies to all options)

| Today | Becomes | Precedent |
|---|---|---|
| "Control released." | **Toast**, ~2s, decays | DJI transient banners |
| "These do nothing until this drone accepts commands." | Overlay controls **dimmed/locked**, lock glyph; sentence on hover/tap only | Progressive disclosure; QGC arm button disabled pre-checks |
| "This drone isn't commandable right now." | **One badge**, fixed top-edge slot: icon + one word, color-coded | DJI top-left safety dot; Betaflight arm-status slot |
| "Axis 2 — not mapped · Set up ›" | **Stays in rail** — no precedent puts mapping on video; optional warning glyph on the matching overlay control, deep-links to setup | Betaflight: mapping is Configurator-only |
| Arm/Disarm buttons | **Slide-/hold-to-arm** overlay, bottom-center or -right, locked until neutral | QGC confirmation slider; ArduPilot/Betaflight neutral-gate |
| Take Control | Persistent **pill/toggle**, top-right or bottom corner, flips label | DJI top-right icon cluster |

### Option A — Edge-anchored HUD (QGC/DJI-style)

```
┌───────────────────────────────────────────────────────────────┐
│ [●Not commandable/Ready]     LIVE VIDEO       [Link ▂▃▅][🔋72%]│  top: status badges
│                                                                 │
│                        ↑ Throttle 62%                          │
│  [On-screen|Xmit|Keys]  ┌────┐              [Take control ⏻]   │  bottom: inputs + mode
│                         │ ⊙  │  ← Steering -18%                │
│                         └────┘   [ hold-to-ARM ▓▓▓▓░░░ ]       │
└───────────────────────────────────────────────────────────────┘
```
Top edge = status badges (commandable, link, battery). Bottom-center =
throttle/steering bars or dual joystick (drone), live only in On-screen mode,
replaced by a compact "Transmitter connected · ch bars" / "Keys active"
ticker otherwise. Bottom-right = slide/hold-to-arm, locked until neutral.
Mode switcher + Take-control pills at bottom/top-right corners. Rail keeps
axis-mapping table, telemetry history, device info.
**Pros:** matches conventions operators may already know from QGC/DJI; clean
top/bottom split leaves frame-center empty; arm reads as deliberate in
isolation. **Cons:** four zones to keep disciplined; narrow viewports can
crowd 4-axis joysticks + arm slider together — may need side-docking instead.

### Option B — Minimal strip + expand-on-engage drawer

```
Idle:                                    After "Take control":
┌───────────────────────────┐  ┌───────────────────────────────┐
│ [●Ready]      VIDEO         │  │ [●Armed 00:42]  VIDEO [Link][🔋]│
│               [Take control]│  │ ┌─────────────────────────────┐│
└───────────────────────────┘  │ │[On-screen|Xmit|Keys] ⊙T ⊙S [ARM]│
                                 │ └─────────────────────────────┘│
                                 └───────────────────────────────┘
```
Only a Ready/Not-ready badge + Take-control pill at rest; the full input/arm
drawer renders only once control is taken — progressive disclosure applied
at the opt-in boundary itself. Rail can mirror live throttle/steering once
engaged, for operators who prefer glancing at the rail. **Pros:** cleanest
idle state; most literal embodiment of "opt-in"; nothing command-shaped
exists until asked for; least collision risk with CV detection boxes already
drawn on the video; best fit for keyboard mode (no stick glyph needed);
smallest diff from today's hide/disable behavior. **Cons:** the drawer risks
recreating a mini rail one layer up if not disciplined; needs a placement
that doesn't cover the CV detection area operators watch most.

### Option C — Corner-docked joystick + always-on OSD line

```
┌───────────────────────────────────────────────────────────────┐
│ Armed 00:42 · Link 92% · 🔋72% · Not mapped: Axis 2  ⚠         │  always-on top line
│                          LIVE VIDEO                             │
│ ┌────┐                                              ┌────┐     │
│ │ ⊙  │ Steering                        Throttle     │ ⊙  │     │  corner-docked (drone)
│ └────┘         [On-screen|Xmit|Keys]  [hold-to-ARM ▓▓░░]  └────┘│
└───────────────────────────────────────────────────────────────┘
```
An FPV-OSD-style single always-on line at top (armed, link, battery, one
warning slot — Betaflight's "Warnings" element) plus corner-docked joysticks
(drone) or one bottom-center throttle/steering pair (rover). **Pros:** most
information-dense per pixel, closest to real FPV goggles pilots may know;
corner docking avoids Option A's bottom-center crowding. **Cons:** an
always-on text line risks reintroducing "sentence on screen" if not
disciplined to icon+number; dual corner joysticks are the biggest visual
footprint of the three — most in tension with "calmer" as the goal.

### Recommendation

Lead with **Option B** for the opt-in idle state (most literal "opt-in",
smallest diff, lowest collision risk with existing CV overlays) using
**Option A's zone discipline** (top=status, bottom=inputs, corners=secondary)
inside the drawer once control is taken. Skip Option C's always-on OSD line
unless a later cycle specifically wants an FPV-veteran mode. In every option,
the arm control is a slide/hold widget visually locked (dimmed + centered-stick
glyph) until live axis values sit inside the neutral tolerance from config —
mirroring ArduPilot Rover and Betaflight — and any denial names the offending
axis rather than a generic "can't arm," per ArduPilot's "Throttle too high."

---

## Open questions for the plan (Fable)

1. Where do CV detection boxes already draw on the video (CV-fly-interaction)
   — do they claim any of these top/bottom/corner zones? Check against R1's
   code truth before zones are frozen.
2. Rover is 2-axis, drone is 4-axis — same bottom-center widget with a
   different shape, or a shared component with an axis-count prop?
3. Keyboard-mode ticker content (Option A/B's "Keys active" replacement for
   the joystick) — static legend, or live key-down highlighting?
4. Neutral tolerance is config per CLAUDE.md rule 1 — confirm R1 identifies
   where live axis values are readable on the frontend for the lock-glyph
   check, and whether tolerance should differ rover vs drone.
