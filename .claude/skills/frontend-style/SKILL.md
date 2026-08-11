---
name: frontend-style
description: Visual style and decoration rules for vision-web — the daylight-chart theme, dark video enclaves, chip/table/panel idioms, alignment and anti-slop rules. Use before styling or restyling any component, page, table, panel, or map overlay in vision-web, and when reviewing UI work for visual drift.
---

# Frontend style (vision-web)

The app's look is **"daylight chart"**: cool paper-gray canvas, ink text, one aviation blue,
colour reserved for status, B612 Mono for telemetry numerals. Calm and instrumental — an aviation
chart in daylight, not a SaaS dashboard and not an "AI product". Migration history:
`docs/plans/done/VISUAL-REFRESH-PLAN.md`; token contract: `docs/plans/done/STYLE-TOKENS-PLAN.md`.

## 1. Tokens are the only vocabulary

- Every colour/spacing/radius/font in component CSS or inline `.ts` styles is a `var(--…)` from
  `src/styles.css`. Raw hex lives in the tier-1 ramp there and nowhere else. Think a new shade is
  needed → stop and flag; don't add it.
- Spacing is the 8px `--space-*` scale (2/4 the only sub-8 steps). Radii/hairlines are geometry
  and stay px. Two font families only: `--font` and `--mono`.
- Reuse the shared primitives (`.btn`, `.chip`, `.notice`, `.segmented`, kebab, `vision-empty`,
  `vision-stat`, two-pane) before styling anything bespoke. A pattern repeated twice gets lifted
  to `shared/ui`, not copy-pasted a third time.

## 2. The surface model: two themes, and dark where video lives

- The app has **two user-selectable themes** — light (default) and dark — implemented entirely as
  tier-2 token values (`:root` = light; `:root[data-theme='dark']` = dark, chosen via `ThemeStore`).
  Components are theme-blind: style against semantic tokens and both themes work. Never write
  theme-conditional CSS in a component.
- Elevation is lightness-only in both themes: canvas `--bg` → `--panel` → `--panel-raised`; no
  box-shadow on resting panels — `--shadow` is for true overlays (menus, dialogs, toasts) only.
- Full-bleed video surfaces (Fly cockpit, Wall, the replay player region) carry `.surface-dark`
  and are dark in **both** themes. Never apply it to a lone widget inside a themed page, and never
  hand-build a dark look with tier-1 grays.
- `--hud-*` frosted pills and `--scrim*` are compositing-over-video values — theme-invariant and
  legal **only inside `.surface-dark`**. A control floating over a *map* on a themed page is a
  `--panel-raised` + `--border` + `--shadow` card instead.
- Anything you style must be checked in **both themes** before you're done.
- **When you add a new `.surface-dark` root** (a new full-bleed video surface), it needs two things
  the shared `styles.css` rule does *not* give it for free: (1) if it can render with less content
  than the viewport, give it its own `min-height: 100dvh` (or equivalent) scoped to that page's own
  file — `.page` and other shared containers are content-height-only by design, so a short enclave
  leaves the themed page's background showing through the gap (this happened to Wall — see
  `docs/plans/done/VISUAL-REFRESH-PLAN.md`'s post-launch fix note); (2) verify with a live check, not just
  `tsc`/tests, that plain elements with no explicit `color` of their own (a bare `<h1>`, a stray
  `<p>`) actually render light-on-dark — a `color`/`background` inherited from an ancestor *outside*
  the enclave is the ancestor's already-*computed* value, not a live `var()` lookup, so it does not
  automatically follow `--text`/`--bg` changing lower in the tree. `styles.css`'s
  `:root[data-theme='dark'], .surface-dark` block already declares an explicit `color`/`background`
  of its own precisely to restart inheritance at that boundary — this only bites an element that
  skips that boundary somehow (rare) or a *new* pattern this fix doesn't cover yet.

## 3. Colour discipline (the non-eye-damaging rules)

- Never pure `#fff` canvas or `#000` text in either theme: canvases are tinted gray, text is ink
  (`--text`).
- Saturation budget: chrome is achromatic; saturated colour appears only as the one blue accent
  (action/selection/focus) and the status hues (success/warn/danger/live) — as chips, dots,
  lines, and soft tints. Large saturated fills are never OK outside `.btn` primary.
- Status colours mean state, nothing else. Blue means "interactive/selected", nothing else.
  Decoration never borrows an intent colour.

## 4. One selection language

Selected/active anything — sidebar route, table row, list row, map marker's list twin — is the
2px left inset bar in `--color-info` + `--color-info-soft` tint. Do not invent another.

## 5. Tables (scan → compare → act)

- Column order: name → classification → state → numbers → timestamps → actions (kebab last,
  narrow, fixed width).
- Text left; numbers right in `.mono` (tabular-nums); nothing centered. `th`/`td` share identical
  horizontal padding so headers sit exactly over their column's content.
- **One chip per row max.** Secondary state is a dot + plain text; classification (category,
  protocol) is muted text, not a chip. Uniform ~36px rows; `.truncate` + `title` on names; empty
  cells render a faint `—`, never blank.

## 6. Side panels

- **Sidebar (nav):** one row height, strict icon column, quiet faint small-caps group labels
  (no icons on labels), status is one quiet line in the footer. The sidebar informs; it never
  competes with page content for attention.
- **Detail panel (two-pane):** fixed anatomy, top to bottom — title row → status chips →
  key-value fact grid (fixed-width muted label column, values aligned) → `h3` sections → actions
  row (max one primary `.btn`, "Open full ›" last). It is a triage surface: identity, is-it-OK,
  2–3 actions; deep work belongs on the full page.

## 7. Map overlays

The default basemap follows the theme (light tiles ↔ light theme; dark tiles ↔ dark theme and
`.surface-dark`); an explicit user layer pick always wins. One marker glyph, state by colour only (live/offline/attention), `--color-info`
ring for selection. Overlay controls are light cards pinned to corners on the 8px grid; the
legend is one quiet row of count chips.

## 8. Typography registers

Two registers, never blended: **display/prose** (mixed case, 600, `-0.01em`) for titles and
sentences; **structural labels** (uppercase, `+0.04–0.05em`, muted, small) for `th`, `.label`,
group labels. Telemetry, ids, timestamps, and any value that ticks → `.mono` (tabular-nums keeps
neighbors still).

## 9. Anti-slop (hard bans)

No gradients. No glow/neon. No glassmorphism outside the `.surface-dark` HUD. No purple/violet
accents. No border-radius above `--radius` except `--radius-pill` on pills. No drop shadows for
elevation. No emoji as UI icons (use `vision-icon`). No animation beyond the existing pulse and
0.15s state transitions; respect `prefers-reduced-motion`. No centered table columns. No
letter-spaced lowercase. If a screen looks "designed", remove one thing.

## 10. Copy in the UI

Name things by what the user controls, not how it's built. Buttons say what happens ("Save
changes", not "Submit") and keep the same verb through the flow. Errors say what went wrong and
what to do next; empty states invite the next action (see `vision-empty` usage). Sentence case
everywhere except structural labels (§8).

## Checklist before finishing any UI task

1. Grep your diff: no raw hex/`rgb()`, no off-grid px, no `--hud-*`/`--scrim*` outside
   `.surface-dark`.
2. Every table/panel you touched conforms to §5/§6; selection per §4.
3. Screenshot the touched surface in **both themes** (and inside `.surface-dark` if applicable).
4. `npm run test:ci` + `npx tsc --noEmit` green; `vision-web/MODULE.md` updated if the surface
   or conventions changed.
