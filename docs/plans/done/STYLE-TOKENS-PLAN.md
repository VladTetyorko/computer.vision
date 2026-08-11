# STYLE-TOKENS-PLAN — design-token consolidation for `vision-web`

Status: **done** (2026-07-30). Owner: styling refactor. Result: two-tier tokens + rem 8px grid
across all of `vision-web`; 4 repeatable elements promoted to `shared/ui` components
(`vision-notice`/`vision-empty`/`vision-kebab-menu`/`vision-stat`); `.chip` kept a class.
Verified: `npm run test:ci` 76 files / 1274 tests green, production build clean, tsc clean, and a
full-tree audit showing zero raw hex / off-grid px / dead tokens in components. See
`vision-web/MODULE.md`'s dated Status entry for the full writeup.

## Goal (user rules, verbatim intent)

1. **One source of truth.** Every colour, spacing, radius, and font lives as a CSS custom
   property in `src/styles.css`. Components reference `var(--…)` only — no raw hex, no raw `rgb()`,
   no magic `px` paddings in component CSS or in `.ts` inline `styles`.
2. **≤ 3 font families.** Currently **2** and staying there: `--font` (sans, UI/display text) and
   `--mono` (B612 Mono, telemetry/numerals). No third family is introduced. Every `font-family`
   in the app already resolves to one of these two — do not add more.
3. **Semantic colour names, two-tier.** A primitive hue ramp holds the only raw hex in the app;
   semantic aliases (`--color-info`, `--color-danger`, …) reference the ramp; components use the
   semantic aliases. See §Colour.
4. **8px spacing rule, in `rem`.** All gaps/paddings/margins are `--space-*` tokens on a strict
   8-grid (multiples of 8; **2px and 4px are the only sub-8 exceptions**). 12px/20px are dropped.
   Values are expressed in `rem` (root = 16px, so 0.5rem = 8px). Hairline borders (1px/2px) and
   `--radius-*` are geometry, not spacing, and stay in px.
5. **Extract shareables.** When a component pattern repeats (notice banners, stat tiles, dialog
   scaffolding), lift it into a shared primitive (`src/styles.css` for global primitives, or
   `src/app/shared/ui/` for a component) rather than restyling per feature.

## Frozen token contract

### Fonts (unchanged, 2 families)
```
--font  : -apple-system, …, sans-serif;   /* UI + display text */
--mono  : "B612 Mono", …, monospace;       /* telemetry, ids, numerals */
```

### Colour — tier 1: primitive ramp (the ONLY place raw hex is allowed)
```
/* Neutrals — the surface/border/text staircase */
--gray-950:#0b0e13  --gray-900:#141922  --gray-850:#1a212c  --gray-800:#1e2632
--gray-700:#232b36  --gray-600:#323d4c  --gray-400:#788496  --gray-300:#8d99ab
--gray-100:#e7ecf3  --white:#ffffff     --black:#000000

/* Blue (info / accent / focus) */
--blue-500:#4f8cff  --blue-900:#16264a  --blue-100:#a9c8ff  --blue-ink:#04101f

/* Green (success) */
--green-500:#37c977 --green-900:#10301f --green-200:#8ce7b4

/* Amber (warn / attention) */
--amber-500:#ffb340 --amber-900:#33260c --amber-800:#5a4413 --amber-200:#ffd479

/* Red (danger) */
--red-500:#ff5d5d   --red-900:#331419   --red-800:#6b2530
--red-200:#ff9a9a   --red-100:#ffb3bd   --red-ink:#1a0009

/* Rose (live / happening-now) */
--rose-500:#ff3d78
```

### Colour — tier 2: semantic aliases (what components use)
```
/* Surfaces */                    /* Text */                  /* Intent */
--bg           :var(--gray-950)   --text      :var(--gray-100) --color-info        :var(--blue-500)
--panel        :var(--gray-900)   --text-muted:var(--gray-300) --color-info-soft   :var(--blue-900)
--panel-raised :var(--gray-850)   --text-faint:var(--gray-400) --color-info-text   :var(--blue-100)
--panel-hover  :var(--gray-800)                                --color-on-info     :var(--blue-ink)
--border       :var(--gray-700)                                --color-success     :var(--green-500)
--border-strong:var(--gray-600)                                --color-success-soft:var(--green-900)
                                                               --color-success-text:var(--green-200)
                                                               --color-warn        :var(--amber-500)
                                                               --color-warn-soft   :var(--amber-900)
                                                               --color-warn-line   :var(--amber-800)
                                                               --color-warn-text   :var(--amber-200)
                                                               --color-danger      :var(--red-500)
                                                               --color-danger-soft :var(--red-900)
                                                               --color-danger-line :var(--red-800)
                                                               --color-danger-text :var(--red-200)
                                                               --color-on-danger   :var(--red-ink)
                                                               --color-live        :var(--rose-500)
```

**Back-compat aliases** (kept until the final wave so each wave stays green independently, then
removed): `--accent→--color-info`, `--accent-soft→--color-info-soft`,
`--accent-text→--color-on-info`, `--ok→--color-success`, `--ok-soft→--color-success-soft`,
`--warn→--color-warn`, `--warn-soft→--color-warn-soft`, `--danger→--color-danger`,
`--danger-soft→--color-danger-soft`, `--live→--color-live`.

### Spacing — 8px grid, in `rem` (root 16px). Token name = px value.
```
--space-2 :0.125rem /*2px  — tight exception*/   --space-24:1.5rem /*24px*/
--space-4 :0.25rem  /*4px  — tight exception*/   --space-32:2rem   /*32px*/
--space-8 :0.5rem   /*8px*/                       --space-40:2.5rem /*40px*/
--space-16:1rem     /*16px*/                      --space-48:3rem   /*48px*/
                                                  --space-64:4rem   /*64px*/
```
**Old→new spacing aliases** (old scale was px, used in ~75 spots; kept until migrated, then
removed): `--space-1→--space-4`, old `--space-2→--space-8`, `--space-3(12px)→--space-16`,
`--space-4(16px)→--space-16`, `--space-5→--space-24`, `--space-6→--space-32`, `--space-7→--space-48`,
`--space-8(64px)→--space-64`.

**Snapping table for raw values** (apply when migrating component CSS):
| raw | →token | raw | →token |
|---|---|---|---|
| 2px / .125rem | `--space-2` | 16px / 1rem | `--space-16` |
| 4px / .25rem | `--space-4` | 20px / 1.25rem | `--space-24` |
| 6px / .4rem / .45rem | `--space-8` | 24px / 1.5rem | `--space-24` |
| 8px / .5rem / .55rem | `--space-8` | 32px / 2rem | `--space-32` |
| 10px / .6rem / .65rem | `--space-8`* | 40px | `--space-40` |
| 12px / .75rem / .85rem | `--space-16` | 48px / 3rem | `--space-48` |
| 14px / .9rem | `--space-16` | 64px | `--space-64` |
\* prefer `--space-8`; use `--space-16` where the tighter value visibly crowds.

### Geometry (unchanged, stays px)
`--radius:5px  --radius-sm:3px  --radius-pill:999px`. Hairlines: `1px`/`2px` borders stay literal.
Breakpoints stay literal px in `@media` (CSS can't read `var()`); cite `--bp-sm/md/lg` in a comment.

## Hex → token crosswalk (authoritative — agents map, never add to `styles.css`)

Every raw hex found in components maps to an existing semantic token below. The ramp already
covers every hue in the app, so **wave agents must not edit `src/styles.css`** — if you think a
new shade is needed, stop and flag it instead.

| raw literal | → token | note |
|---|---|---|
| `#0b0f14` | `var(--bg)` | off-spec map bg, unify to page canvas |
| `#3b82f6` | `var(--color-info)` | rogue 2nd blue (fleet-map fallback) |
| `#1e293b` | `var(--panel-raised)` | slate drift |
| `#334155` | `var(--border)` | slate drift |
| `#94a3b8` | `var(--text-faint)` | slate drift |
| `#cbd5e1` | `var(--text-muted)` | slate drift |
| `#e2e8f0`, `#f8fafc`, `#e7ecf3` | `var(--text)` | near-white drift |
| `#a9c8ff` | `var(--color-info-text)` | |
| `#ffd479` | `var(--color-warn-text)` | notice-banner text |
| `#5a4413` | `var(--color-warn-line)` | notice-banner line |
| `#f59e0b`, `#eab308` | `var(--color-warn)` | Tailwind amber/yellow drift |
| `#ff9a9a`, `#ffb3bd` | `var(--color-danger-text)` | |
| `#6b2530` | `var(--color-danger-line)` | notice-banner line |
| `#ef4444` | `var(--color-danger)` | Tailwind red drift |
| `#1a0009`, `#1a0410` | `var(--color-on-danger)` | ink on saturated red |
| `#000`, `#000000` | `var(--black)` | (or `--scrim*` if it's an overlay) |
| `#fff`, `#ffffff` | `var(--white)` | |
| `rgb(0 0 0 / 55%)` etc. | `var(--scrim)` / `var(--scrim-strong)` | nearest step; bespoke opacity allowed if unique |
| `rgb(4 6 10 / 72–78%)` | `var(--hud-bg-strong)` | HUD panel scrim |
| `rgb(6 9 14 / 62%)` | `var(--hud-bg)` | HUD pill scrim |
| `rgb(255 255 255 / 9–15%)` | `var(--hairline)` (or `--hud-border` for a full border) | |
| `rgba(245,158,11,0.25)` | keep (amber glow shadow) | a glow, not a fill |

**Prefer the `.notice`/`.notice-warn`/`.notice-danger` primitive** over re-coloring a bespoke banner
whenever the markup is the amber/red attention-banner pattern.

## Shared primitives to extract (kill copy-paste)

- **`.notice` / `.notice-warn` / `.notice-danger`** — the amber-text-on-amber-line and
  red-text-on-red-line attention banners duplicated across `app.css`, `wall.css`, `command.css`,
  `fly.css`, `live.css`, `onboarding.css`, `toast-host.ts`, `player.ts`. New primitive in
  `styles.css`.
- Frosted HUD (`.surface-hud` / `.surface-hud-strong`) and 12-col grid already exist — reuse, don't
  re-derive.
- Any per-feature "stat tile" / dialog-backdrop repetition a wave finds → lift to `shared/ui/`.

## Waves (disjoint file scopes)

- **W0 — foundation (this session, direct):** rewrite `styles.css` `:root` to the contract above +
  back-compat aliases; migrate `styles.css`'s own primitives to new tokens + rem spacing; add
  `.notice*`; **fix the leaflet popup bug** (literal `gray`/`white`/`blue` → tokens).
- **W1 — app shell:** `app/app.css`, `app/features/hubs/*`, `shared/ui/*`, `shared/player/*`.
- **W2 — fly:** `features/fly/*.css` + fly `.ts` inline styles.
- **W3 — command + maps:** `features/command/*`, `shared/map/**` (kill the `#3b82f6` / slate
  `#94a3b8/#1e293b/#334155/…` drift fallbacks in `fleet-map.css`; `#0b0f14`→`--bg`).
- **W4 — remaining features:** assets, asset-detail, devices, warehouse, activity, onboarding,
  org-settings, settings, debug, live, replay, wall.
- **W5 — cleanup:** remove back-compat aliases once no consumers remain; verify no raw hex/`rgb()`/
  off-grid `px` survive in components; update `vision-web/MODULE.md`.

## Definition of done (per wave)
- No raw hex or `rgb()` literals in the wave's component CSS/`.ts` (grep-clean).
- No spacing `px`/off-grid `rem`; all gaps/paddings are `--space-*`.
- `npm run test:ci` + `npx tsc --noEmit` clean; `ng build` succeeds. `vision-web/MODULE.md` updated.
