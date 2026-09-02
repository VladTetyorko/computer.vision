# FLY-FLOW — the pilot's open-and-connect flow, staged and layered

Status: ACTIVE · branch `feat/fly-flow-ux` · owner request 2026-09-02 (screenshot review of the
post-FLY-CONTROL-UX cockpit): *"I don't really like the structure and alignment of buttons and
modules… think from the user's perspective… make the flow of opening Pilot and connecting to a
drone easy and stylish. Low effort, low overwhelm, levels/layers of modules, and how/when the user
interacts with them."*

This plan **supersedes** two deliberate decisions of `FLY-CONTROL-UX-PLAN.md` §3 (source pills
always visible; armed/mode chips in the header) — the owner reviewed the shipped result and asked
for less at-rest surface. Everything else there (one-socket identity, honest denial, neutral-stick
arm gate, the engaged input widget) is untouched.

## 1. Diagnosis (screenshot + code, `station/vision-web/src/app/features/fly/`)

| # | Defect | Where |
|---|---|---|
| D1 | Three competing bottom-center zones: `.stage-notice` (bottom 6.5rem), `fly-hud`'s `.hud-bottom-center` (bottom pad), and the `.grid-controls` row below the video. In the screenshot "Waiting for the first frame" sits *on top of* the source pills, which sit on "Not commandable" + "Take control". | `cockpit.css`, `fly-hud.css` |
| D2 | Start stream exists twice at once: `.not-streaming-card` (center) and `.grid-controls` (bottom row). | `cockpit.html` |
| D3 | Armed/mode shown twice: header chips *and* the OSD Power/Nav groups. | `cockpit.html`, `fly-osd.html` |
| D4 | Left-bottom pileup: map inset, preflight chip and secondary tiles are three independent absolutes with approximate `calc(+ --space-64)` offsets — in the screenshot the preflight chip paints over the minimap. | `cockpit.css` |
| D5 | Map inset renders world-zoomed when there is no position — pure noise, answers nothing. | `cockpit.html` gate |
| D6 | At rest a non-engaged pilot faces 4 control-plane controls (3 source pills + Take control) plus a badge — before they've expressed any intent to control. | `fly-hud.html` |
| D7 | Three grid rows (telemetry / ticker / controls) below the video shrink the "full-screen hero" the layout comment promises. | `cockpit.css` |

(`frame #936` and the raw ISO timestamp are burned into the *simulated video frames* by the sim
source, not drawn by the web UI — out of scope here, noted for the sim adapter backlog.)

## 2. The model — stages × layers

**Stages** (computed once, in `fly-logic.ts`, from signals the facade already has):

```
S0 picker      /fly            pick a drone (unchanged this plan)
S1 idle        not live        ground time: preflight, replay, Start stream
S2 starting    busy/first-frame one action pending, say what's happening
S3 live        watching        the picture is the point; control is one quiet offer
S4 engaged     controlling     input widget + Release; Arm zone active
(watch mode    any             actions hidden, instruments remain)
```

**Layers** — every module belongs to exactly one; a layer never borrows another's slot:

```
L0  the glass      the video, full-bleed, nothing burned on it
L1  the frame      header (top) · OSD shelf (one bottom row) · tool-rail (right)
L2  the dock       ONE bottom-center zone on the glass owning stage text + the
                   stage's primary action · plus the Arm zone (bottom-right, S3+)
L3  on demand      rail drawers, CV setup modal, stop-confirm scrim
```

Rule of thumb the owner asked for, made law: **at any stage the glass carries at most one
call-to-action, and it is the next step of the flow.**

## 3. The dock (new anchor in `fly-hud`/`cockpit`) — what it shows per stage

- **S1 idle** — the current `.not-streaming-card` content *becomes* the dock card: "Not
  streaming", last-seen, last position (+ map link), primary **Start stream**, and — folded in
  from the deleted controls row — the quiet **Replay last flight** link + earlier-flights kebab.
- **S2 starting** — same dock, button shows *Starting…*; the stream-state text ("Waiting for the
  first frame") renders **inside the dock**, never as a separate floating toast.
- **S3 live** — dock collapses to one row: `[state badge] [Take control] [source ▾]`. The three
  source pills become **one compact select-style HUD pill** (On-screen / Transmitter / Keys),
  locked once a handshake starts, exactly the old lock rule. Stage notices (stalled /
  reconnecting / detections-paused / detection-off) render as one line directly **above** the
  dock, same anchor stack — collision-free by construction.
- **S4 engaged** — unchanged from FLY-CONTROL-UX: live input widget + **Release**. Arm/Disarm
  stays bottom-right.
- **Watch mode** — dock renders instruments/notices only, no actions (existing gates).

## 4. Waves

**W1 — dock + stage** (`fly-logic.ts`, `cockpit.{html,css}`, `fly-hud.{html,css,ts}`)
Stage computed; one dock zone; delete `.stage-notice`'s separate anchor, `.not-streaming-card`,
`.grid-controls` row (D1/D2/D7). Source pills → one select pill (D6). Stop stream moves to the
right end of the OSD shelf as a small quiet `danger` ghost (confirm scrim kept verbatim).

**W2 — frame dedupe + left stack** (`cockpit.{html,css}`, `fly-osd.*`)
Header chips removed — armed/mode live in the OSD groups only (D3). One `.main-left` flex column
stacks preflight → map inset → secondary tiles with real `gap` (no calc offsets, one z-index)
(D4). Map inset additionally gated on a known position (D5). Ticker leaves its grid row and
overlays bottom-left above the shelf, max 3 transient rows (D7). Preflight expanded in S1,
auto-collapses entering S3 (signal exists: `preflightCollapsed`).

**W3 — verify**
`npx tsc --noEmit`, `npm run test:ci` (never raw vitest), update specs touching deleted zones,
live screenshot of S1→S3, `station/vision-web/MODULE.md` + this plan's close-out.

Frozen ids/contracts: `ToolRailPanelId` unchanged; no backend/API change anywhere; keyboard map
unchanged. Style law: `.claude/skills/frontend-style` — HUD pills only over glass, one selection
language, no new tokens.
