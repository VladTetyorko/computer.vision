# CV-UX-RESEARCH — the Fly Detection panel, simplified without lying

Status: **research / design proposal** (2026-08-16). Design only — no product code in this task.
Subject: the Fly cockpit's "Detection" side panel
(`station/vision-web/src/app/features/fly/cv-control-panel.{ts,html,css}` + `cv-control-panel-logic.ts`),
which the product owner reports as "too complicated", with two named questions: **is model switching
useful, and how should the classes flow work?**

Grounding: every claim about current behaviour below cites the file and line actually read.
Constraints honored: the frozen wire contract (`PATCH /api/streams/{id}/config`,
`GET /api/cv/models` — docs/plans/done/CV-CONTROL-PLAN.md §2–4; `tracking(...)` —
docs/plans/done/TRACKING-PLAN.md §4.D) is untouched; the one candidate backend addition is listed
separately in §9 and nothing in the waves depends on it.

---

## 1. Diagnosis — what is actually wrong

The panel is not ugly; it is **mis-prioritized and dishonestly worded in three places**. It renders
as one flat scroll of **15 interactive controls** (model ×3, preset, clear-all, class search,
per-class chips, confidence, inference-fps, tracking mode ×3, capability ceiling ×6, engine ×2,
verify cadence, follow sampling, release, boxes ×3, detection on/off — `cv-control-panel.html:12-370`)
plus ~9 blocks of explanatory prose. Of those 15, an operator mid-flight can meaningfully answer
about **six**. The rest demand answers no human has:

| Control | Can a human answer it? | Why not |
|---|---|---|
| Detection on/off | **yes** — the primary act | |
| What am I looking for (model + classes) | **yes** — as an *intent*, not a model filename | |
| Boxes on screen or not | **yes** | |
| Follow *that* target | **yes** — but the real interaction is clicking the box in the video (`buildFollowLockPatch`, `cv-control-panel-logic.ts:320`), not anything in this panel |
| Confidence | **partially** — a human knows the *symptom* ("too many false boxes" / "it's missing things"), never the number 0.40 |
| Inference fps | **no — and the slider's label is now false.** Adaptive rate shipped enabled by default (`AdaptiveRateSettings.DEFAULT_ENABLED = true`, `contexts/vision-perception/.../pipeline/AdaptiveRateSettings.java:26`) and may only *raise* the rate above this number (`station/vision-app/src/main/resources/application.yaml:492`, docs/plans/done/CV-RATE-CONTROL-PLAN.md §2 "Floor"). "Inference 4 fps" is really "at least 4 fps, machine decides the rest" — a floor an operator has no basis to pick |
| Capability ceiling (Auto/L1–L5) | **no** — "what can this host afford" is exactly what auto-probe measures (`CAPABILITY_LEVEL_OPTIONS[0]`, `cv-control-panel-logic.ts:355-358`); L1–L5 exist for a fleet admin who *knows* a companion is a Pi, not for a pilot |
| Tracking engine (optical flow vs template match) | **no** — the server already falls back per R11 (`cv-control-panel.ts:289-299` re-syncs from the wire) |
| Re-verify cadence / follow sampling | **no** — tuning numbers with no readback (`cv-control-panel.ts:196-208`) |

Three **honesty defects** compound the overload:

1. **The classes copy conflates three different promises.** "Showing every class this model detects"
   / the checked-chip metaphor read as *display* filtering; the "Primary control for this model" chip
   (`cv-control-panel.html:50`) reads as *steering the model*. The truth is neither: `labelFilter` is
   applied Java-side in `StreamPipeline#onDetectionResult` **before all fan-out**
   (`contexts/vision-perception/.../pipeline/StreamPipeline.java:1240-1292`) — a hidden class is
   dropped from the screen **and** from live SSE, the event engine (alerts), the extrapolator, and
   persistence (recording), while the model still computes it every frame (no CPU saved). Even the
   panel's own perf hint understates this: "the class filter only trims what's shown"
   (`cv-control-panel-logic.ts:265`) is wrong about alerts and recording.
2. **The fps slider's label predates the rate controller** (§ above). Meanwhile the *measured* rate —
   the honest number, per docs/main/UX-DESIGN.md §7.2 — is already served on
   `GET /api/streams/{id}/tracks` as the `rate` object
   (`station/vision-api/.../dto/DetectionRateResponse.java:6,47`) and the SPA simply doesn't read it
   (`StreamTracksResponse` in `core/api/models.ts:473-478` has no `rate` field).
3. **The primary act is buried last.** `Detection: on/off` is the panel's final section
   (`cv-control-panel.html:353-370`). With the parallel change making detection **off by default and
   viewer-gated**, the first thing every operator will do in this panel is the thing currently at the
   bottom of the scroll.

One small behavioural oddity found while reading: the **capability ceiling renders even when tracking
is Off** — the `<select>` at `cv-control-panel.html:212-222` sits outside the
`trackingMode() !== 'OFF'` guard (which starts at line 224). Harmless but reinforces "expert knob
shown to everyone".

Accuracy check on the owner's quoted rendering: it is faithful to the code as read, with one nuance —
the "Re-verify / Follow sampling" sliders only appear in FOLLOW mode (`cv-control-panel.html:246`),
and "Burned in" is omitted when the stream is confirmed burn-in-free (`cv-control-panel.html:333`).
Nothing quoted has since been removed.

## 2. The operator's real tasks, ranked

Per-flight acts (seconds matter, done while flying):

1. **Turn detection on for the stream I'm watching** — becomes the #1 act once off-by-default lands.
   Frequency: once per flight, first thing.
2. **Follow that target** — click its box; release later. The panel's job is only to *report* the
   lock honestly ("Following #12 · Release"), which it already does right
   (`cv-control-panel.ts:272-277`, the tracks-poll-confirmed chip).
3. **Silence a noisy class** ("stop boxing trees") — occasional, must be one click on something
   visible, not a search flow.
4. **Boxes on/off** (shortcut B) — occasional, already fine.

Set-once configuration (before/between flights, calm hands):

5. **What am I looking for** — people & vehicles / military vehicles / everything. Per-mission, not
   per-minute. This is the model + class-seed choice, and it is one decision, not two.
6. **Fewer false boxes ↔ find more** — the confidence trade, tuned rarely, by symptom.

Expert/diagnostic (a developer or fleet admin, not a pilot):

7. fps floor, capability ceiling, engine, cadences, flow strip, serving/lag readouts.

The current panel presents 7-before-1: expert material occupies the middle of the scroll and the
primary act is last.

## 3. Proposed flow — three tiers, primary act first

```
┌─ Detection ──────────────────────────────────── ✕ ─┐
│  [ ●——— ]  Detect on this stream                   │ ← hero switch (the old bottom toggle)
│  status: 9.9/s measured · 3 classes on screen      │ ← one honest line, from tracks.rate
│                                                    │   (off: "Off — zero CPU. Video unaffected.")
│  LOOKING FOR                                       │
│  (•) People & vehicles              fast           │ ← intent cards = model + class seed,
│  ( ) Military vehicles                             │   one decision (see §5)
│  ( ) Everything — wide search       slower         │
│                                                    │
│  SEEN NOW                                          │
│  [person ✓] [car ✓] [tree ✓] [roof ✓]              │ ← observed chips, click to hide
│  Hiding a class drops it everywhere — screen,      │ ← the honest sentence, once
│  alerts, recording. The model still scans for      │
│  everything.                                       │
│                                                    │
│  Boxes   [Overlay][Off]                 (B)        │
│  [Following #12 · Release]                         │ ← only when a lock is wire-confirmed
│                                                    │
│  ▸ Tune        confidence · all classes · tracking │ ← tier 1, collapsed
│  ▸ Expert      rates · ceiling · engine · flow     │ ← tier 2, collapsed
└────────────────────────────────────────────────────┘
```

**Tier 0 — at rest** (everything a pilot needs mid-flight): the Detect switch with an honest
measured-status line; the three intent cards; the observed-class chips (recency-ordered, capped ~8 —
the fuller list lives in Tune); the Boxes row; the lock chip when present. Two *conditional* notices
also surface here because burying honest bad news would violate UX-DESIGN §7.2: the capability
**downgrade** warn (`cv-control-panel.html:300-302`) and the **lag over budget** danger notice
(`cv-control-panel.html:310-315`) — both already render only when they fire, so at rest they cost
nothing.

**Tier 1 — "Tune"** (one click, `<details>`-style section): the confidence slider re-worded as a
symptom axis ("Fewer false boxes ↔ Find more", mono readout of the number beside it); the full class
checklist with search, preset, and clear (today's mechanism unchanged — it is good, §4); the tracking
mode segmented control (Off/Associate/Follow) with its plain-language hints.

**Tier 2 — "Expert"** (collapsed, plainly labeled): the fps **floor** slider (re-labeled "Detector
floor — adaptive rate raises above this, never below"); capability ceiling; engine picker; verify
cadence; follow sampling; the flow strip; the full Serving/lag readout. Nothing is deleted —
demoted, with its existing honest copy intact. Persisting each disclosure's open state follows the
`UiStore`/store pattern (docs/plans/done/UI-ARCHITECTURE-PLAN.md — no new booleans in the component;
the open/closed state of the two disclosure groups belongs in the Fly facade/store layer).

**Off-by-default posture** (the parallel change): the hero switch's copy carries the new contract —
"Runs only while this stream is watched" — and the status line must never claim inference that is
not happening: when on but idle, say "on — waiting for a viewer" *only if the wire can attest it*
(see §9; until then, the measured line "no detector passes in the last 10 s" is the honest fallback,
computable from `rate.submittedFps`). No blank states: detection off is a named state with the next
action visible, per UX-DESIGN §7.1.

## 4. The classes question — concrete answer

**Keep the observed-labels mechanism; change the framing, the staging, and the wording.** The
existing candidate model (union of filter + labels actually seen, `chipCandidates`,
`cv-control-panel-logic.ts:81-87`) is the right foundation — it was a deliberate correction after the
real YOLOE vocabulary measured 4585 classes (docs/plans/done/CV-CONTROL-PLAN.md, resolved Q1). What
overwhelms is presenting the *whole apparatus* (state line + preset + clear + search + checklist +
add-button) at rest.

1. **At rest: "Seen now" chips, click to hide.** The everyday act is negative — "stop boxing trees" —
   and it should be one click on a chip the operator can already see. Cap at ~8 recency-ordered
   chips; "All classes…" opens Tune. The empty state stays ("No classes observed yet — waiting for
   the first detections", `cv-control-panel.html:114`) — it is already §7.1-correct.
2. **Presets become the intent layer, not a button among buttons.** The "People, vehicles &
   buildings" chip-fill (`PEOPLE_VEHICLES_BUILDINGS_PRESET`, `cv-control-panel-logic.ts:189-204`)
   merges into the "Everything" intent card's seed offer (§5) instead of floating in the classes
   section. Search + free-text add + clear-all move to Tune, unchanged in mechanism.
3. **Filtering boxes vs telling the model — say the true third thing.** Verified: the filter is a
   Java-side drop before all fan-out (`StreamPipeline.java:1288-1292`) — so it is *stronger* than
   "hides boxes" (it also silences alerts and recording) and *weaker* than "tells the model what to
   look for" (zero CPU change; that would be text-prompted YOLOE, an explicitly deferred non-goal in
   CV-CONTROL-PLAN). The honest wording, used once at rest and once in Tune:
   > *"Hidden classes are dropped everywhere — screen, alerts, recording. The model still scans for
   > everything; hiding classes doesn't make it faster."*
   The "Primary control for this model" chip is **removed** — it asserts the steering promise the
   backend doesn't make. The perf hint's "only trims what's shown"
   (`cv-control-panel-logic.ts:265`) is corrected to the same sentence.
4. **Name the wire's one sharp edge instead of hiding it.** `labelFilter` is an allowlist; empty
   means "all" (`isLabelChecked`, `cv-control-panel-logic.ts:92-94`). Hiding one class while the
   filter is empty necessarily enumerates the *observed-so-far* complement
   (`toggleLabelChip`, `cv-control-panel-logic.ts:107-118`) — which will also hide classes never yet
   seen. Tier-0 hide should therefore show a one-time hint: "Hiding your first class keeps only the
   classes seen so far — new classes won't appear until you clear it." The clean fix is a deny-list
   field on the wire — listed as the one candidate backend change in §9, not assumed.

## 5. The models question — concrete recommendation

**Yes, switching is useful — but the user should pick an intent, not a model.** The three roster
entries (`CvWiring.java:214-220`) are genuinely three different capabilities, not variants:
closed-set fast (yolo26n), specialized military (orion12l), open-vocabulary wide (yoloe-seg-pf), and
the speed/coverage trade is real and measured (~2× slower for open-vocab,
docs/plans/done/CV-CONTROL-PLAN.md, resolved Q1). Deleting the choice would be fake simplification.

- **Frame as "Looking for", not "Model".** Three radio cards: *People & vehicles* (fast — the
  default), *Military vehicles*, *Everything — wide search* (slower). Each card is one sentence of
  what it finds plus the honest cost word. The `kind` chip (`general`/`specialized`/`open-vocab`,
  `cv-control-panel.html:31`) is removed — taxonomy jargon that answers no operator question. The
  long open-vocab CPU paragraph (`perfHint`, `cv-control-panel-logic.ts:262-266`) shrinks to the
  card's "slower" plus one line under it when selected.
- **Picking a card = one PATCH-model + seed decision**, exactly today's `onModelChange` +
  `seedLabelFilterForModel` path (`cv-control-panel.ts:427-449`, `cv-control-panel-logic.ts:50-55`)
  — no wire change. "Everything" keeps seeding empty (show all; the measured reason at
  `cv-control-panel-logic.ts:38-49` stands) and offers the people/vehicles/buildings chip-fill as
  the card's optional second line, where it belongs.
- **Three is the right roster size for this UI.** The roster endpoint is deploy-config
  (`CvWiring.java:213-220`); the cards render whatever it serves. If a deployment ever ships >4
  models, that is a roster problem, not a panel problem.
- **Placement: set-once tier, below the hero switch** — visible without a click (an operator should
  see *what* is being detected at a glance) but after the primary act.
- The "re-arming detection" honesty toast (`reArmHint`, `cv-control-panel-logic.ts:250-252`) stays
  exactly as is.

## 6. Classification table — every element, disposed of

(a) remove · (b) demote to Tune/Expert · (c) automate, report what was chosen · (d) keep, re-word.

| Element (as rendered today) | Verdict | Why |
|---|---|---|
| Panel subtitle "Model · classes · confidence · boxes" | (d) | becomes "What to detect on this stream" — name the job, not the knobs |
| "Changes below apply live…" hint | (d) | one small line, keep; it earns its place (`cv-control-panel.html:3-9`) |
| Model picker (3 buttons + kind chip) | (d) | reframe as "Looking for" intent cards; kind chip **(a)** removed (§5) |
| Classes "Primary control for this model" chip | (a) | asserts model-steering the backend doesn't do (§4.3) |
| "Showing every class this model detects." state line | (d) | re-word to the drop-everywhere truth (§4.3) |
| "+ People, vehicles & buildings" preset button | (d) | folds into the "Everything" card's seed offer (§4.2) |
| Class search box + "Add \<query\>" | (b) | Tune tier; mechanism unchanged |
| Observed-label chip checklist | (d) | becomes tier-0 "Seen now" (capped, recency) + full list in Tune |
| "No classes observed yet…" empty state | (d) | keep — §7.1-correct already |
| Confidence slider | (b)+(d) | Tune tier; re-word as "Fewer false boxes ↔ Find more", number in mono |
| Inference fps slider | (c)+(b) | label is false since adaptive rate (§1.2); Expert tier as "Detector floor"; tier 0 shows the **measured** rate instead |
| Open-vocab CPU notice paragraph | (d) | shrinks to the card's "slower" + one line; drop-everywhere correction folded in |
| Tracking mode Off/Associate/Follow | (b) | Tune tier; the pilot's real path is click-to-follow in the video, which already switches mode itself (`buildFollowLockPatch`) |
| Follow hint copy | (d) | keep the one sentence "Click a tracked box in the video to lock onto it" — it is the actual interaction |
| Capability ceiling select (Auto/L1–L5) | (c)+(b) | Auto is default and correct (`cv-control-panel-logic.ts:355-358`); Expert tier; also fix: today it renders even with tracking Off (`cv-control-panel.html:212` outside the mode guard) |
| Ceiling hint paragraph | (b) | rides with its select |
| Engine picker (optical flow / template match) | (c)+(b) | server default + wire re-sync already exist (`cv-control-panel.ts:289-299`); Expert tier |
| Re-verify cadence slider | (b) | Expert; no readback exists, a pilot cannot reason about it |
| Follow sampling slider | (b) | Expert; same |
| "Following #N · Release" chip | (d) | keep at tier 0 — wire-confirmed honesty done right (`cv-control-panel.ts:272-277`) |
| Flow strip `DETECT 0/s ▸ TRACK 0/s …` | (b) | Expert diagnostic; its honesty rules unchanged |
| "Serving: L3" readout | (c) | quiet reading moves to Expert; the **downgrade warn** stays surfaced at tier 0 when it fires (§7.2) |
| "Detection lag —" readout | (c) | same split: quiet number in Expert, over-budget danger notice surfaces at tier 0 |
| Boxes Overlay/Burned-in/Off + copy | (d) | keep at tier 0; copy shortens to one line; burned-in omission logic untouched (`cv-control-panel.html:333`) |
| Detection on/off toggle | (d) | **promoted to the hero position** with off-by-default/viewer-gated copy and a measured status line (§3) |

## 7. Implementation waves — ranked, disjoint, estimated

All waves are **pure frontend** (vision-web) unless marked; each ends with `npm run test:ci` +
`npx tsc --noEmit` + prod build green and `station/vision-web/MODULE.md` updated. Components stay
3-file (.ts/.html/.css); disclosure state lives in the facade/store layer per
docs/plans/done/UI-ARCHITECTURE-PLAN.md; styling per `.claude/skills/frontend-style/SKILL.md`
(both themes, `.surface-dark` context, one chip per row discipline).

| Wave | What | Size | Files (disjoint) | Pure FE? |
|---|---|---|---|---|
| **U1 — tiers + hero switch + honest copy** | Restructure into tier 0/Tune/Expert; move the on/off toggle to the top with off-by-default copy; correct the three dishonest strings (§1); remove the "Primary control" chip and kind chips; fix ceiling-visible-with-tracking-Off | **M** | `features/fly/cv-control-panel.{ts,html,css}`, `cv-control-panel-logic.{ts,spec.ts}` | yes |
| **U2 — "Looking for" intent cards** | Model picker → 3 intent cards; preset folds into the "Everything" card; roster-driven, existing `onModelChange`/seed path | **S** | same panel files (sequenced after U1, same scope — not parallel with it) | yes |
| **U3 — measured rate status line** | Extend TS `StreamTracksResponse` with the `rate` (+`latency`) objects the backend already serves (`DetectionRateResponse.java`); tier-0 status line "N/s measured"; Expert fps slider re-labeled "floor" | **S** | `core/api/models.ts`, panel files' status region, `core/fleet/**` if the poll surface needs a field | yes — backend already serves it |
| **U4 — Seen-now chips + hide flow** | Tier-0 capped recency chips with click-to-hide + the first-hide allowlist hint (§4.4); full checklist stays in Tune | **S** | `cv-control-panel-logic.{ts,spec.ts}` (new pure fns), panel html/css | yes |
| **U5 — viewer-gated state copy** | "On — waiting for a viewer" / honest idle wording once the parallel off-by-default change exposes state; until then the `submittedFps`-derived fallback | **S** | panel files; gated on the parallel change's wire surface | yes, but **blocked on** the parallel backend change landing |

Sequencing: U1 first (it is the skeleton); U2 and U4 after it (same file scope — serialize);
U3 parallel with U2/U4 (different files except a one-line status hookup, integrate last);
U5 last. No wave touches Java.

## 8. What I deliberately did not propose

- **Deleting any model from the roster or the roster endpoint.** Three intents are real (§5);
  removal would be fake simplification.
- **Auto-enabling ASSOCIATE tracking whenever detection turns on.** Tempting (stable IDs make
  click-to-follow discoverable) and reachable via the existing PATCH — but it changes default
  runtime cost and behaviour, which is a product decision, not a UX rewording. Flagged for the
  owner; not in any wave.
- **A text-prompted YOLOE "tell the model what to look for" flow.** Already a named non-goal
  (docs/plans/done/CV-CONTROL-PLAN.md, non-goals) — it would touch the model-load path; the honest
  copy in §4.3 exists precisely because this doesn't exist.
- **Merging the `detections` strip drawer into this panel.** Separate concern, separate drawer
  (`cockpit.html:208-210`); this task is about the settings panel only.
- **Server-side persistence of per-stream panel choices.** Named non-goal of CV-CONTROL-PLAN;
  `SettingsStore` profiles already cover the client side.
- **Any change to `PATCH /api/streams/{id}/config` or `GET /api/cv/models`.** Every wave above is
  reachable with the frozen contract.

## 9. Requires backend change (candidates only — nothing above depends on them)

1. **`labelDenyFilter` (deny-list) on config/PATCH.** Justification: the allowlist wire semantics
   make "hide one class" silently freeze the visible vocabulary to classes observed so far (§4.4) —
   a real trap on the 4585-class open-vocab model where new labels appear continuously. A deny-list
   expresses the operator's actual intent ("everything except trees"). Until it exists, U4 ships the
   honest hint instead.
2. **Explicit detection state on a status read** (e.g. `detectionState: RUNNING | OFF |
   IDLE_NO_VIEWERS` wherever the viewer-gating change surfaces its truth). Justification:
   UX-DESIGN §7.2 — "on but idle because nobody is watching" should be attested by the wire, not
   inferred client-side from `submittedFps == 0`, which cannot distinguish viewer-gating from a
   stalled detector. Belongs to the parallel off-by-default change's contract, not this one.
