# CV-PANEL-SPLIT-PLAN — the Vision panel becomes two surfaces

Status: **authoritative implementation plan** (2026-08-20). Owner decision: the `/fly` CV settings
are too much in one place — split into separate surfaces opened one by one, side panel for some,
modal for the rest. `/fly` is the operator's cockpit for flying *any* drone on *any* setup:
simple, useful, easy — with the ability to set up.

Design rationale inherited from `docs/plans/active/CV-UX-RESEARCH.md` (§1 diagnosis, §2 task
ranking, §5 intent cards, §6 disposition) — that doc's *tiering* is kept; its *one-panel with
collapsed tiers* layout is **superseded** by this split. Builds on CV-CLEAN-FEED waves W3–W5
(branch `feat/cv-clean-feed`; this work is sub-branch `feat/cv-panel-split`, merged back on green).
Wire contract: **frozen** — `PATCH /api/streams/{id}/config` (incl. `labelDenyFilter`),
`GET /api/cv/models`, `GET /api/streams/{id}/tracks`. Pure frontend, `station/vision-web/**` only.

## 1. The two postures, and what lives where

An operator has two hand states. Each gets one surface; nothing renders on both.

### Surface 1 — the Vision side panel (flying: seconds matter, one glance)

Top to bottom, nothing else:
1. **Hero**: `Detect on this stream` switch + one **measured** status line — served from the
   tracks poll `DetectionsStore` already owns (W5): `"9.9/s measured · 3 classes on screen"`;
   off: `"Off — zero CPU. Video unaffected."`. The SPA finally reads the `rate` object the
   backend has served all along (`DetectionRateResponse`; CV-UX §1.2's honest number).
2. **Looking for — summary row**: current intent name + cost word (`People & vehicles · fast`)
   with a `Change…` affordance that opens the setup modal. Not the cards themselves — a glance
   answers "what is it scanning for", the decision lives in the modal.
3. **Seen now** — the W5 strip (counts, hover-promote, click-hide) stays exactly as shipped.
4. **Boxes** — the W4 declutter segmented control (All/Priority/Locked-only/Off) + `(B)` hint.
5. **Following #N · Release** chip — wire-confirmed only, unchanged.
6. **Conditional notices** — capability-downgrade warn and lag-over-budget danger render *only
   when they fire* (UX-DESIGN §7.2: bad news surfaces itself; quiet numbers do not).
7. **`Detection setup…`** button — the door to surface 2.

### Surface 2 — the Detection setup modal (calm hands: decisions)

A centered dialog over the cockpit (video stays visible behind the scrim), sections:
1. **Looking for — intent cards** (CV-UX §5/U2): three radio cards from the roster —
   *People & vehicles* (fast, default) / *Military vehicles* / *Everything — wide search*
   (slower). One card = one decision = today's `onModelChange` + seed path. The `kind` taxonomy
   chip is removed; the open-vocab CPU paragraph shrinks to the card's cost word + one line when
   selected. The people/vehicles/buildings chip-fill folds into the "Everything" card.
2. **Confidence as symptom** (CV-UX U1 wording): slider labeled
   `Fewer false boxes ←→ Find more`, numeric value in mono beside it.
3. **Classes** — full management: the checklist/search/add apparatus, plus the **hidden classes**
   (deny-list) list with un-hide; the one honest sentence about drop-everywhere, once.
4. **Tracking** — Off / Associate / Follow segmented control with its plain-language hints and
   the "click a tracked box in the video to lock on" sentence.
5. **▸ Expert** — collapsed disclosure, tier 3: detector fps floor (re-labeled
   `Detector floor — adaptive rate raises above this, never below`), capability ceiling
   (fix: today it renders even with tracking Off — move it inside the mode guard), engine picker,
   re-verify cadence, follow sampling, the flow strip, Serving/lag quiet readouts.

### Mechanics

- Modal uses the cockpit's existing **transient dialog `UiStore` group** (the arm-confirm
  precedent) — a confirm can open over a drawer, two dialogs can never coexist. Esc and scrim
  click close. Opening the modal does not close the drawer.
- The modal is its own 3-file component `features/fly/cv-setup-modal.{ts,html,css}`; the slimmed
  panel remains `cv-control-panel.*`. Shared pure logic stays in `cv-control-panel-logic.ts`
  (rename only if the split makes the name a lie — prefer keeping citations stable).
- Expert-disclosure open state lives in the facade/store layer, not a component boolean
  (UI-ARCHITECTURE rule).
- Everything keeps applying **live** via the existing PATCH path; the modal is not a form with
  Save/Cancel — it is the same hot knobs, staged calmly. The re-arm honesty toast stays.

## 2. Honesty fixes that ride along (from CV-UX §1, now buildable)

- The measured-rate hero line (surface 1.1) replaces any implied "inference N fps" promise; the
  fps slider moves to Expert with the floor label.
- The capability-ceiling-outside-the-tracking-guard render bug is fixed by relocation.
- Any surviving copy claiming the class filter steers the model is corrected to the
  drop-everywhere sentence (W5 removed the trap; the wording must not resurrect it).

## 3. Waves (sequential — same files)

### P1 — the split (structure, no content redesign)
Create `cv-setup-modal.*`; move sections 2/3/4/5-of-§1.2 out of the panel verbatim; slim the
panel to §1.1's seven items (status line may ship as today's serving text placeholder if `rate`
typing is deferred to P2); wire the dialog UiStore id + `Detection setup…` + `Change…`; Esc/scrim;
keyboard/help text updated. All existing behavior preserved; specs updated; MODULE.md W-entry.

### P2 — the content (intent + honesty)
Intent cards replacing the model buttons (roster-driven, seed path unchanged); confidence symptom
axis; the measured `rate` status line (extend the tracks-poll typing in `core/api/models.ts` with
the `rate`/`latency` objects `DetectionRateResponse.java` serves — read the Java DTO, match
exactly); fps→floor relabel in Expert; ceiling guard fix; copy pass per §2. Specs; MODULE.md.

Each wave: `npm run test:ci` + `npx tsc --noEmit` + prod build green, foreground; 3-file
components; both themes; `architecture.spec.ts` green.

## 4. Out of scope

Backend anything (wire frozen); the strip's internals (W5 shipped them); the declutter control's
semantics (W4); auto-ASSOCIATE-on-detect (still the owner's open product call, CV-UX §8); per-user
persistence of modal state beyond the existing SettingsStore profiles.
