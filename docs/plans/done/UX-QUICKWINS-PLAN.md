# UX-QUICKWINS-PLAN — parallel quick-fix batch

Status: **in execution** (2026-07-24). Extracted from [UX-REWORK-PLAN.md](UX-REWORK-PLAN.md) U-a plus
the usability-review findings (2026-07-24) and user directions. Design ethos for every fix:
**fleet/military systems** — instant understandability, unambiguous status, quick access to anything
an operator may need mid-task; controls labeled with words, never color alone; no hidden affordances.

Four agents, disjoint file scopes, run simultaneously. Each ends with its targeted tests green;
one integration pass (full test:ci + prod build) runs after all four land.

## QF-1 — Fly cockpit (scope: `features/fly/**`)

| Fix | Expected result |
|---|---|
| Switcher unreachable under map inset (BROKEN #1) | Drone switcher clickable in every cockpit state incl. map inset visible; verified by z-index/layout, not hacks |
| Switcher shows wrong selected drone (BROKEN #2) | Selected value always matches the active asset regardless of load order (bind after options render / track by id) |
| Header seam ghost sliver | No stray sliver at cockpit top-left at 1440/1920 |
| Quick-access pass | Switcher restyled as a labeled cockpit control (not bare native select); every icon-only control gets a tooltip + entry in the `?` help |

## QF-2 — Devices page (scope: `features/devices/**`, `core/api/vision-api.ts` createAsset only)

| Fix | Expected result |
|---|---|
| **Protocol select** (user-requested) | Register-manually protocol field becomes a `<select>` of protocols the app actually supports (derive from adapters: `rtsp`, `file`, `mjpeg`, `sim`, `v4l2`, `mavlink` — verify list against adapter MODULE.mds) with a short "what is this" hint per option and a `Custom…` option revealing the free-text input. URI placeholder updates per protocol (e.g. `rtsp://host:554/path`) |
| Duplicate "Add the simulated source" | Rendered once in any state |
| Orphaned-device dead end (quick version) | After register/discover-use, offer "Create asset from this device" (calls existing `POST /api/assets`, wraps the device); advanced-table rows with `Owner: unassigned` get the same action |
| `?category=` filter support | `/devices?category=<slug>` pre-filters the asset list (contract for QF-3's readiness tiles) |

## QF-3 — Command + global chrome (scope: `features/command/**`, `shared/ui/**`, global styles)

| Fix | Expected result |
|---|---|
| Readiness-tile false affordance | Tile navigates to `/devices?category=<its category>` (real drill-down per its visual promise) |
| Dark-theme form controls (global) | All native `select`/`input` styled for the dark theme — visible borders, readable options, consistent with buttons |
| Status legibility pass (military ethos) | Everywhere a status is shown by color/dot alone, add the status word (STREAMING / IDLE / OFFLINE / ERROR); consistent terminology across command, wall, asset lists |

## QF-4 — Player + asset detail (scope: `shared/player/**`, `features/asset-detail/**`)

| Fix | Expected result |
|---|---|
| Latency badge (UX-DESIGN §T1) | Player shows measured transport + latency (`WebRTC 0.3s` / `HLS ~6s`, from existing getStats/live-edge data); honest, updates live |
| Player empty/error states | "Not streaming" panel shrinks to compact state with cause + next action ("Start the stream", "feed unreachable — retrying in Ns"); no 630px black voids |
| Asset-detail idle layout | Video panel collapses when idle; page usable without scrolling past emptiness |

## Out of scope (stays in UX-REWORK-PLAN)

U-b restyle (B612, palette, flat panels), U-c consolidation, U-d wizard, U-e roles. QF fixes must not
restyle beyond their table rows — no palette/typography changes here.

## Integration gate (coordinator)

After all four: full `npm run test:ci`, `ng build --configuration production`, `tsc --noEmit`,
screenshot re-check of fly/command/devices, then commit as one batch.
