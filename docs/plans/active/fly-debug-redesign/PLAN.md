# FLY-DEBUG-REDESIGN — new designs for `/debug` and `/fly/:assetId`

Status: **IN PROGRESS** (2026-09-12). Branch `feat/fly-debug-redesign`, based on `feat/crew-control`
(clean prefix of the tree; keeps this stack independent of `feat/cv-orchestration`).

Research grounding (same folder, read both before implementing):
- [R1-api-console-analogs.md](R1-api-console-analogs.md) — Postman/Insomnia/Bruno/Hoppscotch/
  DevTools/Grafana Explore/Stripe Workbench survey for the raw API console.
- [R2-cockpit-analogs.md](R2-cockpit-analogs.md) — DJI/QGC/ATAK/FPV-OSD/aviation-PFD/Skydio survey
  for the cockpit.

Style law: `.claude/skills/frontend-style/SKILL.md` — including the working-tree §11
(component sourcing: headless behavior only; `@angular/aria` first, CDK second, never hand-rolled
ARIA, never a pre-skinned kit). §11 is uncommitted foreign work — **read it, follow it, do not edit
that file.**

## 0. The two verdicts (why the waves are shaped so differently)

- **`/debug` gets a real redesign.** The current page is three stacked cards with a 3-field form;
  R1's survey says the strongest consoles are a *rail* (catalog + history as one recall surface)
  beside a *request→response stack*, with copy-as-cURL as the highest-value cheap addition.
- **`/fly` gets a surgical pass, not a rework.** R2's honest verdict: after ~15 plans of iteration
  the cockpit already matches the state of the art (corner/edge discipline, dark-cockpit silence,
  one CTA, on-glass lock + off-glass pill). The camera-first rework was already declined by the
  owner. What's left is the steal-list's one cheap precedented win, a stated policy, and a
  style-drift sweep.

### Decided against, with reasons (do not re-open inside the waves)

| Idea | Source | Why not |
|---|---|---|
| Hold-to-confirm "Bring home" | R2 §2.1 | Stale premise — `shared/ui/return-home-button` already opens `<vision-confirm-dialog>`; a hold gesture is a *weaker* guarantee than the existing second explicit tap (R2's own Stop-stream footnote). |
| Hold-to-confirm Arm | R2 §2.6 | `arm-confirm-dialog` is already a deliberate two-stage flow reserving full `--live`; replacing it with a hold would lower friction on the app's highest-danger action. |
| Header vehicle-state bar, permanent mini-map, tape gauges, attitude ladder, center crosshair, on-glass AR compass | R2 §6 | Each is a documented prior defect (D3/D5) or a fake-instrument trap at our telemetry rate. |
| Collections/saved requests, environments, auth field, codegen beyond cURL, JSON tree, resizable panes | R1 no-list | Scope traps; the console rides the app session and stays a developer surface, not a Postman clone. |
| Green "all systems OK" indicator anywhere | R2 §5 | Dark-cockpit policy: **silence, not a green light** — an affirmative OK glyph is a claim that can go stale exactly like a red one. |

## 1. Wave D1 — `/debug` console redesign (web-ui agent)

**Scope: `station/vision-web/src/app/features/debug/**` only.** No `styles.css` edits (existing
primitives suffice: `.card`, `.btn`, `.chip`, `.segmented`, `.mono`, `vision-page-bar`). No backend
change; `DebugApiService` keeps its contract. Page stays out of `ROUTED_PAGES` (existing exemption).

### 1.1 IA — rail + stack

Replace the console card's internal layout with a two-region console (still one `.card` or a
grid of two; agent's call, but visually one console):

```
┌ Debug ─────────────────────────────────────────────────────────┐
│ ┌ rail 280px ───────────┐ ┌ request → response stack ────────┐ │
│ │ ENDPOINTS (catalog)   │ │ [GET ▾] /api/devices     [Send]  │ │
│ │  ▍List devices        │ │ (body editor when method allows) │ │
│ │   Create asset …      │ │ ───────────────────────────────  │ │
│ │  … 13 fixed entries   │ │ 200 OK · 41 ms   [Copy as cURL]  │ │
│ │ HISTORY (last 20)     │ │ [Pretty|Raw]  header chips       │ │
│ │  ● GET /api/… 200     │ │ <pre> response body </pre>       │ │
│ │  ● POST /api/… 409 ⟳  │ │                                  │ │
│ └───────────────────────┘ └──────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────┘
   Health card (unchanged) · Last-scan card (unchanged) below
```

- **Rail** = one recall surface, two labeled groups (structural-label register, §8): the 13
  catalog endpoints, then history (most recent first). Selection language per §4: 2px left inset
  bar in `--color-info` + soft tint for the *selected catalog entry only*.
- Catalog click = prefill method+path+body template, **never send** (today's `selectEndpoint`
  semantics, kept — R1's opt-in try-it).
- History row: method + truncated path + status. Status is a **colored dot + `.mono` code**
  (status-family: 2xx ok, 4xx warn, 5xx/unreachable danger), not a chip — the rail is a list, and
  the response pane's chip stays the one chip. Row click = **silent refill** (today's `replay`);
  a small "send again" icon button appears on hover/focus and is the only thing that re-sends
  (R1's view-vs-replay distinction). The old in-card history table is deleted.
- Under 900px the rail collapses above the stack (catalog as a `<select>` again is acceptable
  there; history stays a list).

### 1.2 Request bar

One combined bar: compact method `<select>` (`.mono`) visually joined to the path input (shared
border, one focus ring), Send button at the right. Body editor appears below for body-methods
(today's `showBody()` rule). Keep `ngModel` or move to signals — agent's call, no new form library.

### 1.3 Response pane

- Status chip (ok/danger as today) + `.mono` ms + **Copy as cURL** (`.btn secondary`, copies to
  clipboard, brief "Copied" state on the button itself — same-verb rule §10).
- **Pretty | Raw** `.segmented` toggle above the body; Pretty = today's `formatted()`
  pretty-print, Raw = the exact response text. Persist choice in component state only.
- Header chips unchanged. Empty body renders a faint `—` line, not a blank `<pre>`.

### 1.4 cURL builder + keyboard — behavior rules

- `buildCurl(entry)` is a **pure function in a new `debug-curl.ts` + spec**: `curl -X METHOD
  'origin+path'`, `-H 'Content-Type: application/json'` + `--data '<body>'` only when a body was
  sent; single-quote escaping covered by tests. History entries must therefore carry the request
  body (extend the history record; `debug-history.ts` + spec updated).
- Keyboard: Ctrl/Cmd+Enter sends from path/body fields; `/` focuses the path input when focus is
  not already in an input/textarea.
- Rail keyboard nav per §11: `@angular/aria` is **not installed** (CDK 21.2 only). Do not add a
  dependency speculatively — use `cdk/a11y`'s `ActiveDescendantKeyManager`/`ListKeyManager` for
  the listbox pattern (roles `listbox`/`option`, arrow-key movement, Enter = activate). No
  hand-rolled ARIA state machine beyond wiring the key manager.

### 1.5 D1 definition of done

`npx tsc --noEmit` + `npm run test:ci` green (foreground — never background the build); new logic
(`debug-curl`, history model, status-family mapping) spec-covered; diff greps clean per the skill
checklist (no raw hex, no off-grid px, no `--hud-*` outside video surfaces — this page has none);
commit on `feat/fly-debug-redesign`.

## 2. Wave F1 — cockpit surgical pass (web-ui agent)

**Scope: `station/vision-web/src/app/features/fly/**` except `cv-control-panel*` (in flight on
another branch). No layout rework — the L0–L3 structure and the one-CTA law are frozen.**

1. **OSD heading glyph** (R2 steal #5): in `fly-osd.html`'s Nav cluster, a rotating direction
   glyph beside the numeric `headingLabel()`, reusing the file's own `.wind-arrow` idiom
   (`transform: rotate(Ndeg)`); rendered only when a real heading exists (no glyph for `—`).
   Derivation logic in `fly-osd-logic.ts` + spec (degrees passthrough, null-guard).
2. **Dark-cockpit policy stated where future work will see it**: a short comment block at the top
   of `cockpit.html`'s notice/banner region: appears-on-state-change surfaces stay zero-height
   when nominal; **silence means healthy — never add a positive "all OK" indicator** (R2 §5).
3. **Style-drift sweep** of `features/fly/*.css` + templates against the frontend-style law:
   raw hex/`rgb()` outside tokens, off-grid px spacing, `--hud-*`/`--scrim*` used outside the
   `.surface-dark` enclave, decoration borrowing intent colors, animation beyond pulse/0.15s,
   more than one chip per row. **Fix violations in place; change no layout, no copy, no behavior.**
   List every fix in the commit message body.

Definition of done: same gates as D1; commit on `feat/fly-debug-redesign` (runs **after** D1 —
same tree, one test runner at a time).

## 3. Verification & close-out (orchestrator)

- Live check in Chrome, both themes (§ checklist): /debug rail+bar+cURL+keyboard+history replay;
  /fly OSD glyph with live heading, cockpit unchanged otherwise.
- `station/vision-web/MODULE.md` updated (debug surface, OSD glyph, dark-cockpit policy line).
- `docs/plans/README.md` row; this plan gets an as-built block; memory updated.
