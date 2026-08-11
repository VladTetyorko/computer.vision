# 02 — Command

**Files:** `features/command/**`
**Wave:** 1 (full-bleed), 3 (side panel unification)

## Current state

The strongest page in the app, and the template the rest should follow. A toolbar row
(`COMMAND` · `1 assets` · `GO · 0.8 m/s, gusts 3.0` · `Zones (0)` · `Marks (0)` · `Include archived`),
a ~250px left asset list, and a Leaflet map filling everything else with basemap switcher and
`Auto-fit on`.

## Problems

- Renders under the global 40px header, so the map loses a band it could use (F11).
- The left list is a fixed ~250px with no collapse — at 1280px it costs 20% of the map.
- Selecting an asset has no detail affordance on this page; the operator must leave for `/assets/:id`.
- `COMMAND` as a toolbar label duplicates what the sidebar's active state already says.
- The map's own controls (`Auto-fit`, basemap) and the page's controls (`Zones`, `Marks`) live in two
  different bands with no visual relationship.

## Suggested design

Keep the structure — it is right. Tighten the edges and complete the two-pane idea.

```
┌────┬───────────────────────────────────────────────┬──────────────┐
│ ▎◎ │ 1 asset · GO 0.8 m/s   [Zones 0][Marks 0][⌗] │              │
│    ├─────────────┬─────────────────────────────────┤   selected   │
│  🛩│ ASSETS    1 │                                 │   asset      │
│  ▦ │ ▎● 11  CRIT │            map                  │              │
│  ☑ │             │                                 │  battery 0%  │
│    │             │                                 │  link 93%    │
│  ⚠ │             │                                 │  [Cockpit]   │
│  ⟲ │             │                                 │  [Bring home]│
│    │           ⟨ │                                 │            ⟩ │
└────┴─────────────┴─────────────────────────────────┴──────────────┘
```

- **Full-bleed**: `data.fullBleed` collapses the sidebar to the rail; no global header.
- **Both flanks collapse.** The asset list and the new detail panel each get a chevron; state
  persists. At <1024px both become overlays over the map.
- **Right panel = `shared/ui/side-panel.ts`**, driven by `?sel=<assetId>` — the same pattern
  Assets/Devices/Alerts adopt in wave 3. Clicking a map marker or a list row fills it. This is where
  `Bring home` / `Open cockpit` / `Watch live` belong, so the operator never leaves the map to act.
- **One toolbar.** Fold `Auto-fit` and the basemap switcher into the page toolbar's right side with
  the Zones/Marks toggles; the map keeps only zoom. Drop the redundant `COMMAND` label.
- **Attention ordering**: rows sort by severity (`CRIT` first), and the count chip reads
  `1 asset · 1 needs attention`.

## Refactor list

- **Add** `data: { fullBleed: true }` to the `/command` route.
- **Extract** the map-control cluster into the toolbar; delete the floating `Auto-fit on` button.
- **Add** `command-detail-panel.ts` using `shared/ui/side-panel.ts`, bound to a `sel` query param.
- **Add** collapse toggles + `localStorage` keys for both flanks.
- **Reuse**: this page's list+map split becomes the reference implementation for wave 3 — extract the
  shared shell as `shared/ui/two-pane.{ts,css}` here and have `/assets`, `/devices`, `/alerts` import
  it rather than each rolling its own.

## Acceptance

- Map gains ≥40px of height and ≥250px of width when both flanks are collapsed.
- `?sel=<id>` survives refresh and Back.
- `shared/ui/two-pane` is consumed by at least Command + Assets by end of wave 3.
