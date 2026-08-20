# CV-FLY-INTERACTION-RESEARCH — the on-video detection layer, decluttered

Status: **research / design proposal** (2026-08-20). Design only — no product code in this task.
Subject: how detections are **rendered and interacted with on the `/fly` video stage** — boxes,
labels, trails, hover/click, the detections strip — reported by the owner as "not as efficient and
clear as it could be, because of many detections overlaying each other".

Companion doc: `docs/plans/active/CV-UX-RESEARCH.md` (2026-08-16) covers the Detection **settings
panel** (waves U1–U5, specced not built). This doc covers the **video surface itself**. The two
share one philosophy — honest, tiered, primary-act-first — and their waves are disjoint by file.

Grounding: every claim about current behaviour cites the file actually read. The frozen wire
contract (`PATCH /api/streams/{id}/config`, `GET /api/cv/models`, tracking lock — CV-CONTROL-PLAN
§2–4, TRACKING-PLAN §4.D) is untouched; backend candidates are quarantined in §8.

---

## 1. Diagnosis — why the video surface reads as clutter

The renderer treats **every detection as equally important and equally labeled, forever**. There is
no priority, no collision handling, and — the sharpest defect — one mode that literally draws two
box sets for the same object.

### D1 — "Overlay" mode double-draws on a default stream *(the literal "overlaying each other")*

Server-side burn-in ships **on by default** (`PipelineConfig.DEFAULT_OVERLAY_BURN_IN = true`,
`contexts/vision-perception/.../PipelineConfig.java:72`), so the video frames already carry baked
Java2D boxes. The client `'overlay'` mode draws its canvas boxes **on top of that same video**
(`shouldDrawOverlay`, `shared/player/detection-overlay-logic.ts:161` — only `'burned'`/`'off'`
suppress the canvas; nothing suppresses the burn-in). Result: every object gets **two boxes** —
one baked (frame-perfect) and one canvas (latency-estimated via `selectDetectionResult`) — that
jitter against each other by exactly the sync error. The mode cycle (`B` key) offers this state as
a normal choice. This was a known scope cut ("no server change", `detection-overlay-logic.ts:158`)
that has now become the top user-facing complaint.

### D2 — every box carries an always-on filled label; labels have no collision handling

`drawBox` (`shared/player/player.ts:1878-1900`) paints `"#id label conf%"` with an opaque
background above **every** box, every frame. In a dense scene (a street of cars; the 4585-class
open-vocab model), labels stack on labels and cover the very objects being pointed at. There is no
label-yield, no clustering, no max-count, no min-box-size gate. AR view-management literature
solved exactly this (collision-aware placement in <5 ms/frame, temporal coherence) — see §4.5.

### D3 — no priority model: a locked target looks like a tree

All boxes draw with identical weight (2 px stroke, full alpha) whether the box is the target the
operator is actively FOLLOWing, a high-confidence vehicle, or a 32 % "roof". When a follow lock is
active (`buildFollowLockPatch`, click-to-follow) the locked box gets **no visual distinction at
all** beyond its stable track hue — the operator's one chosen object does not stand out from the
noise. The only emphasis that exists is hover amber, which is transient.

### D4 — confidence-as-text on every box is expert data at tier 0

`formatDetectionLabel` (`detection-overlay-logic.ts:253-258`) always appends `82%`. Per the panel
research (CV-UX-RESEARCH §2), a pilot reasons in symptoms, not numbers; per-frame percentages
flicker (78→81→79) and add width to every label without informing any mid-flight decision.

### D5 — per-track hash hues make dense scenes look like confetti

`trackHue` (`detection-overlay-logic.ts:270`) gives each track a random-but-stable hue at fixed
90 %/65 % saturation/lightness. With 15 tracked objects the stage shows 15 saturated colors of
equal vividness — color stops encoding anything (identity is better carried by the `#id` and
constancy) and starts being noise. Analogs (§4) color by **affiliation/class/state**, and reserve
one accent for the selected track.

### D6 — the detections strip and the boxes are strangers

`detections-strip.ts` lists the last ~8 label chips; the boxes draw on the stage. No linkage:
hovering a chip highlights nothing, clicking a chip does nothing (the everyday "stop boxing trees"
act lives only in the settings panel, and only after wave U4 ships). Two renderings of the same
data that don't acknowledge each other.

### D7 — coasting honesty exists, staleness honesty doesn't

A `COASTING` track draws dashed (`player.ts:1888`) — good, honest. But when detections stop
arriving entirely (detector stalled, viewer-gating, reconnect), the last selected batch keeps
drawing at full confidence until the store empties; there is no "boxes are N s old" fade or cutoff
at the renderer level. `selectDetectionResult` even deliberately falls back to the **oldest**
batch rather than showing nothing (`detection-overlay-logic.ts:103-105`) — right for attach
jitter, wrong as an unbounded policy.

### D8 — the trail layer scales with track count, not with relevance

2 s fading trails draw for **every** tracked object (`drawTrails`, `player.ts:1911`,
`TRAIL_WINDOW_MS` = 2 s). Trails are the single best "is it moving / which way" cue for one chosen
target — and pure stroke-noise when 12 parked cars each drag one.

### D9 — the default experience is the non-interactive one

`defaultBoxesMode` returns `'burned'` whenever the stream carries burn-in
(`detection-overlay-logic.ts:45-47`) — which, per D1, is every default deployment. So the crisp
HiDPI canvas, hover tooltips, click-to-follow, track colors, coasting dashes, and trails — the
entire interactive layer this codebase built — is **not what an operator sees** unless they cycle
`B`. Click-to-follow, the flagship interaction, is undiscoverable in the default mode.

## 2. The operator's real on-video tasks, ranked

The video stage is not a debug view of the model; it is where the operator's eyes live. Boxes are
an **index**, not the content:

1. **Notice** — "something new/relevant entered the frame" (a mark appears; motion; an alert).
2. **Identify** — "what is that one?" (point at it → its label/confidence, on demand).
3. **Commit** — "follow *that*" (click → lock; everything else becomes background).
4. **Monitor the committed target** — where it is, where it's going (trail), is the system still
   seeing it (coasting dash) — while the rest of the scene stays legible but quiet.
5. **Silence noise** — "stop boxing trees" — one gesture on the offending thing itself.

Principle borrowed from every analog in §4: **all detections are peripheral awareness; one
selected track is the story.** The current renderer inverts this — it tells 15 stories at once and
none louder than another.

## 3. Proposed interaction model

### 3.1 One overlay, never two (fixes D1, D9)

- `'overlay'` and burned-in frames must be mutually exclusive on screen. Pure-frontend rule: when
  `resolveBurnedIn(stream)` is true, the cycle is `burned ↔ off` (drop `'overlay'` — today it is
  the *double* mode); when the stream is burn-in-free, the cycle is `overlay ↔ off` (already the
  case, `BOXES_CYCLE_WITHOUT_BURN_IN`). This makes the double-draw state unreachable — one line of
  truth in `boxesModeCycle`.
- The real fix is product-level: **turn server burn-in off by default for streams the SPA views**
  so the interactive overlay becomes the default experience (backend candidate §8.1 — the flag
  already exists per stream; MEDIA-SOT deliberately kept "today's default" and this doc is the
  case for flipping it). Burn-in remains right for external players (VLC, HLS embeds) — it is a
  compatibility feature, not the primary UX.

### 3.2 Priority tiers at draw time (fixes D2, D3, D4, D5, D8)

Every detection gets a computed **draw tier**, pure function of what the client already knows
(lock state, track presence/state, box area, confidence, alert match, hover):

| Tier | Who | Renders as |
|---|---|---|
| **T0 — committed** | the FOLLOW-locked track; a hovered box | full box, accent color, label always on, trail on, 3 px |
| **T1 — notable** | tracked & moving, or alert-rule match, or top-K by (area × confidence) | full box in a **class-stable** hue, label subject to collision-yield (§3.3), no trail |
| **T2 — ambient** | everything else above the confidence floor | thin 1 px box or corner-brackets, **no label**, 55 % alpha |
| **T3 — sub-scale** | boxes smaller than ~12 px on screen | a dot marker (a label would be bigger than the object) |

- Confidence % leaves the ambient label entirely; it stays in the hover tooltip and on the T0
  label — the identify-on-demand act (§2.2).
- Color simplifies: **hue by class** (person/vehicle/other buckets), not by track id; identity is
  the `#id` text and box constancy. The one accent color (`--accent` amber/blue) is reserved for
  T0. Composite-model hues (`modelHue`) stay for the multi-model legend case, unchanged.
- Trails draw **only for T0** — the committed target's history is signal; twelve parked cars'
  trails are noise. (`trackTrails` already computes per-track; the change is a filter at the call
  site.)
- When a lock is active, T1/T2 additionally dim (≈40 % alpha) — the Spotlight/focus pattern every
  analog uses (§4.2, §4.3). Releasing the lock restores them.

### 3.3 Label collision-yield (fixes D2)

Greedy, priority-ordered label placement per frame: draw labels in tier order; before painting,
test the label rect against already-painted label rects; on collision try below-box, then inside-
top, then **skip the label** (the box still draws — identity is recoverable by hover). Cap total
painted labels (~10). This is the standard AR view-management approach and runs in well under a
millisecond for tens of boxes; hysteresis (keep last frame's placement if still valid) prevents
flicker. Pure function → unit-testable in `detection-overlay-logic.ts` next to the existing math.

### 3.4 Staleness is visible (fixes D7)

The renderer already knows each batch's age (`capturedAt` vs now). Policy: boxes older than
~2 batch intervals draw at reduced alpha; older than ~5 s draw not at all, and the existing
stage-notice framework (`cockpit.html:45` "stalled feed" pattern) says "detections paused — last
seen 12 s ago" instead. Never delete silently, never draw stale as fresh — UX-DESIGN §7.2 applied
to pixels.

### 3.5 The strip becomes the class-level remote control (fixes D6)

The strip chips and the boxes are the same data at two zoom levels — link them:
- **Hover chip → highlight** that class's boxes on the stage (temporary T1 promotion).
- **Click chip → hide class** — the same `labelFilter` PATCH wave U4 specs for the panel, surfaced
  where the operator's eyes already are; honest copy identical to U4 ("dropped everywhere — screen,
  alerts, recording").
- Chip gains a count: `person ×3` — the aggregate answer VMS interfaces converge on (§4.4) when
  individual boxes stop being informative.

### 3.6 Declutter levels, one key (extends today's `B`)

Avionics practice (§4.6): discrete, named declutter states, not sliders. `B` cycles
**All → Priority (T0+T1 only) → Locked-only (T0) → Off**. "Priority" is the sane default. The
current mode chip UI (`cv-control-panel.html` "Boxes rendering") renders the same four states.
This subsumes today's overlay/burned/off cycle — burned-vs-overlay stops being the operator's
choice (§3.1 makes it automatic) and *density* becomes the choice, which is the decision an
operator actually has an opinion about.

## 4. Analogs — what the field converged on

1. **Anduril Lattice** frames the operator's job as four decisions — *where to look, what to
   follow, how to engage, when to strike* — and models detections as **entities/tracks** in a
   common operating picture, not as per-frame paint ([Lattice AI](https://www.anduril.com/lattice-ai),
   [Command & Control](https://www.anduril.com/lattice/command-and-control)). The screen serves the
   *follow* decision; everything else is subordinate. Our §2 task model is the same shape.
2. **DJI (Pilot 2 / FocusTrack family)** does not label-and-box everything: subject scanning marks
   trackable objects with a minimal **"+" glyph**, and selecting one enters Spotlight — one subject
   emphasized, gimbal-locked, everything else visually quiet
   ([DJI tracking support](https://support.dji.com/help/content?customId=en-us03400006832&spaceId=34&re=US&lang=en&documentType=artical&paperDocType=paper),
   [public-safety features](https://enterprise-insights.dji.com/blog/top-public-safety-drone-features),
   [Spotlight walkthrough](https://vicvideopic.com/how-to-use-spotlight-with-dji-flip-mini-4-pro-air-3s-and-mavic-4-pro/)).
   Exactly our T3-dot → click → T0-focus flow.
3. **VMS / surveillance operations** literature is unanimous that undifferentiated detection output
   produces alert fatigue — operators miss up to 45 % of screen activity after ~12 minutes and tune
   the system out; the cure is **stacked filters and prioritization**, not more boxes
   ([Fora Soft on tuning analytics](https://www.forasoft.com/learn/video-surveillance/articles-vms/tuning-analytics-false-alarms-accuracy),
   [ArcadianAI 2026 alarm-monitoring guide](https://www.arcadian.ai/blogs/blogs/alarm-monitoring-for-video-surveillance-the-2026-guide-to-accuracy-compliance-and-ai-driven-operations),
   [Radius Security](https://blog.radiussecurity.com/security-systems-with-video-analytics-reduce-operator-fatigue)).
4. **Frigate NVR** moved from per-object events to aggregated **review items** ("a time period
   where tracked objects were active") precisely because per-detection granularity overwhelmed
   users ([Frigate review docs](https://docs.frigate.video/configuration/review/),
   [project](https://github.com/blakeblackshear/frigate)) — the same aggregate-over-individuals
   move as our strip counts (§3.5).
5. **AR view management** research provides the label mechanics: collision-aware placement with
   temporal coherence runs in <5 ms/frame and cluster-based placement scores best
   ([Evaluating label placement for AR view management, IEEE](https://ieeexplore.ieee.org/document/1240689/),
   [Real-Time Video Annotations for AR](https://link.springer.com/chapter/10.1007/11595755_36),
   [semantic-aware label placement](https://dl.acm.org/doi/abs/10.1007/s00371-020-01939-w)).
6. **Avionics HUD practice**: discrete declutter modes, task-related symbology only, and
   *adaptive* declutter — the display sheds secondary symbology automatically when the situation
   escalates ([GlobeAir HUD overview](https://www.globeair.com/g/head-up-display-hud),
   [intelligent-assistant flight-deck principles, Aeronautical Journal](https://www.cambridge.org/core/journals/aeronautical-journal/article/principles-for-intelligent-assistant-systems-in-future-flight-deck-design-autonomous-action-integration-to-reduce-pilot-workload/F6C5B6B0AB254973B34C62B73B5406C2)).
   Our lock-dims-the-rest rule (§3.2) is adaptive declutter: committing to a target *is* the
   escalation.

## 5. Disposition — every current element

(a) remove · (b) demote/conditional · (c) automate · (d) keep, re-scope.

| Element today | Verdict | Why |
|---|---|---|
| `'overlay'` offered on burned streams | (a) | the double-draw state (D1); cycle becomes burn-aware (§3.1) |
| `'burned'` as default mode | (c) | correct only because burn-in is on; flips with §8.1 |
| Always-on label per box | (b) | T0 always; T1 collision-yield; T2/T3 hover-only (§3.2–3.3) |
| Confidence % in every label | (b) | tooltip + T0 only (D4) |
| Per-track hash hue | (d) | re-key to class buckets; accent reserved for T0 (D5) |
| Hover amber + tooltip | (d) | keep — it *is* identify-on-demand; tooltip gains "hide class" |
| Click-to-follow on tracked box | (d) | keep; make discoverable by default (§3.1) + T0 emphasis |
| COASTING dashed stroke | (d) | keep — honest-UI done right; extend with staleness fade (§3.4) |
| Trails for every track | (b) | T0-only (D8) |
| Oldest-batch fallback forever | (b) | bounded by staleness policy (§3.4) |
| Multi-model legend chip | (d) | unchanged; already gated on actual mixing |
| Detections strip chips | (d) | gain counts + hover-link + click-to-hide (§3.5) |
| `B` cycle overlay/burned/off | (d) | becomes All/Priority/Locked-only/Off (§3.6) |

## 6. Implementation waves — ranked, pure frontend, disjoint

Same conventions as CV-UX-RESEARCH §7: each wave ends `npm run test:ci` + `npx tsc --noEmit` +
prod build green, `station/vision-web/MODULE.md` updated; 3-file components; logic as pure
functions in `detection-overlay-logic.ts` beside their tests.

| Wave | What | Size | Files (disjoint) |
|---|---|---|---|
| **V1 — never two box sets** | burn-aware mode cycle drops `'overlay'` on burned streams (§3.1); staleness fade + cutoff + "detections paused" notice (§3.4) | **S** | `shared/player/detection-overlay-logic.{ts,spec.ts}`, `player.ts` draw path |
| **V2 — priority tiers** | tier computation (pure fn) + tiered rendering: T0 emphasis, T2 thin/alpha, T3 dots, lock-dims-rest, trails T0-only, class-bucket hues | **M** | same files as V1 — sequence after it |
| **V3 — label collision-yield** | greedy priority-ordered placement with hysteresis + label cap; confidence % out of ambient labels | **S** | `detection-overlay-logic.{ts,spec.ts}` (new pure fns), `player.ts#drawBox` |
| **V4 — strip ↔ stage linkage** | chip counts, hover-highlight, click-to-hide via the U4 PATCH path + honest copy | **S** | `shared/player/detections-strip.ts`, `core/detections/detections-{store,logic}.ts` |
| **V5 — declutter levels** | `B` becomes All/Priority/Locked-only/Off; panel "Boxes rendering" chips renamed to match | **S** | mode plumbing in facades (`CockpitFacade`, `LiveFacade`, wall tile) + panel html |
| **V6 — measure it** | overlay draw stats (boxes drawn/skipped per tier, labels yielded) into the Expert flow strip — so "is it less cluttered" is a number, not vibes | **S** | `detection-overlay-logic.ts`, cv panel Expert tier |

Sequencing: V1 alone kills the reported symptom. V2→V3 same files, serialize. V4 parallel with
V2/V3. V5 after V2 (it exposes the tiers). V6 last. Interaction with the U-waves: V4 reuses U4's
PATCH helper if U4 lands first — otherwise it lands the helper and U4 reuses it; no other overlap.

## 7. What I deliberately did not propose

- **Clustering boxes into group glyphs** ("12 people" merged marker). Right for crowds; premature
  before tiers + collision-yield are measured. Revisit if V6 shows label-yield saturating.
- **Auto-promotion of "interesting" detections** (behavioral triggers, zone rules). That is the
  event-engine's job upstream; the renderer should consume an alert flag, not invent one (§8.3).
- **Removing the burned-in path.** External players need it; it just stops masquerading as the
  operator UX.
- **Any change to click = follow.** It matches the wire contract and DJI/Lattice conventions;
  hide-class goes on the tooltip/strip, never on box-click.
- **Redesigning the settings panel.** That is CV-UX-RESEARCH's scope; nothing here touches its
  waves except sharing U4's PATCH helper.

## 8. Backend candidates (nothing above depends on them)

1. **Burn-in off by default for SPA-viewed streams** (or a start-option the SPA always sends).
   The per-stream flag exists (`PipelineConfig.overlayBurnIn`); this is a default flip +
   MEDIA-SOT-PLAN note. Unlocks §3.1's real fix and makes the interactive overlay the product.
2. **`labelDenyFilter`** — already CV-UX-RESEARCH §9.1; V4's click-to-hide inherits the same
   allowlist trap and the same honest hint until it lands.
3. **Alert/priority flag on the detection wire** — the event engine already evaluates rules
   server-side; carrying `alerting: true` per detection would let T1 promotion reflect the rules
   the operator configured instead of client-side heuristics (area × confidence). Small proto/DTO
   addition; scalars-only, so it respects the exhaustive-switch codec discipline.
4. **Track age/staleness on `GET .../tracks`** — optional; §3.4 is computable client-side from
   `capturedAt`, wire support would only harden it.
