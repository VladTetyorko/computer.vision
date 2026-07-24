# UX-REWORK-PLAN — pilots and managers first

Status: **proposed** (2026-07-24). Companion to [UX-DESIGN.md](UX-DESIGN.md) (principles still valid),
[UI-STRUCTURE-PLAN.md](UI-STRUCTURE-PLAN.md) (folder migration; runs first), [REALTIME-PLAN.md](REALTIME-PLAN.md).
Ordered **least effort → architecture change**. Sources: full UX inventory of every screen (2026-07-24)
and design research across Flightradar24, FlightAware, DJI FlightHub 2, QGroundControl/Auterion,
FlytBase Fleet View 2.0, Samsara, Geotab, Verizon Connect, Traccar, MarineTraffic.

## 0. Users and their screens (target model)

| User | Landing screen | What they need |
|---|---|---|
| **Pilot** | Fly cockpit | their current drone(s), all main attributes, map inset — instrument-dense, QGC-style; the only screen allowed to be dense |
| **Manager** | Command (map-first) | the map FIRST, then their pilots' drones as list + video; select → detail panel |
| **Manager of managers** | Command (map-first) | same map + a group tree (their sub-managers/teams) to drill down |
| all | Warehouse **scoped to their level** | assets/devices of their group subtree only |

Industry validation: the pilot/supervisor split is exactly DJI FlightHub 2 (monitoring vs Virtual
Cockpit as a deliberate transition), and hierarchy drill-down is the Geotab/Samsara **group-tree**
pattern — visibility = group membership, never a bespoke "sub-manager" entity.

Findings that anchor the phases: Devices has **34 static buttons** (3 jobs in one 774-LOC screen);
Asset-detail has 20 (its Hardware block duplicates Devices' table actions verbatim); Register/Discover
create **orphaned devices** — the UI never calls `POST /api/assets`, so real hardware can't become an
asset; **no image / registration-number field exists in the wire contract**; `owner` is one hardcoded
dev UUID (`DevPrincipal.OWNERSHIP`) — no user/role concept exists anywhere in the backend.

---

## U-a — Declutter: delete and demote (≈1 day, frontend only)

No design system needed — pure removal of confirmed dead weight:

1. Delete the **PTZ placeholder card** on Live (`live.html:187-194` — "arrives with adapter-onvif in Phase 4").
2. Delete the duplicate **"Add the simulated source"** (appears verbatim twice: `devices.html:343` and `:435`).
3. Remove **"Draw a flight plan…"** from the Map empty state (`map.html:31`) — demo action in a
   referee-facing surface; the Devices entry point remains.
4. Merge **"All drones" + drone `<select>`** on Fly (`fly.html:71,75`) into one switcher control.
5. Move **Debug** out of the "More ▾" menu — reachable by URL and (later) the advanced-mode setting only.
6. Fix or flatten the Command **readiness tile** false affordance (`command.html:109` — looks like a
   category drill-down, actually plain navigation): make it filter Devices by category, or make it
   visibly a plain link.
7. Collapse Asset-detail's **Hardware action block** (`asset-detail.html:353-388`) into a kebab (⋯)
   menu per device row — the actions stay, the button wall goes. Same for Devices' Advanced table rows.

Exit: Devices static buttons 34 → ≤20; Asset-detail 20 → ≤12; no screen shows a control that does
nothing or duplicates a sibling.

## U-a2 — Action clarity & poka-yoke (≈2–3 days, frontend; runs with/right after U-a)

User feedback (2026-07-24): "Watch / Open / Archive on Assets, Start / Create asset / rename /
deactivate / archive / assign on Devices, Watch / Preview / Open on Map, and the info-less asset
card on Fly are not understandable." Root causes: bare-verb labels with no object or consequence,
three near-synonyms for viewing, destructive and routine actions styled identically, and cards
that are buttons without saying what pressing them does. Fix with **poka-yoke** (mistake-proofing)
principles — prevent the wrong action, don't just warn after it.

### 1. One verb dictionary, app-wide (the core fix)

| Verb | Meaning (only this, everywhere) | Replaces |
|---|---|---|
| **Fly** | enter the pilot cockpit for this asset | picker card click, some "Watch" |
| **Watch live** | see the live video (viewer, no controls) | "Watch", "Preview" |
| **Details** | open the asset's detail page | "Open" |
| **Start / Stop stream** | begin/end streaming (with object: "Stop stream") | "Start" |
| **Archive** | hide from lists, stops streams; reversible | "Archive", "deactivate" (pick ONE lifecycle verb pair; today deactivate vs archive is an internal distinction no operator understands — surface it as Archive + Restore only, keep the finer states in Advanced) |

Map's Watch/Preview/Open triple collapses to **Watch live** + **Details**. Every button label is
verb + object ("Archive asset", "Assign to asset…"), never a bare verb.

### 2. Poka-yoke rules (enforced by a checklist at review, applied everywhere)

1. **Prevention over confirmation**: an action that can't apply now is disabled *with the reason
   inline* ("Stop the stream first" under a disabled Archive), never enabled-then-error.
2. **Consequence-stating confirmations** only for destructive/irreversible acts, and they say what
   happens: "Archive *Falcon-2*? Its stream stops and it disappears from lists. Restore any time
   from Warehouse → Advanced." Routine actions never confirm.
3. **Undo over confirm** where reversible: Archive fires immediately with a 10s "Archived — Undo"
   toast (poka-yoke's mistake-*recovery* arm; less friction than dialogs, safer than nothing).
4. **Destructive actions look different and live apart**: red-tinted, physically separated (bottom
   of kebab menu behind a divider), never adjacent to a primary button.
5. **One primary action per card/row**: visible button = the single most likely action for that
   user on that screen (Fly page → "Fly"; manager list → "Watch live"); everything else in a
   labeled kebab menu whose entries carry verb+object and, for risky ones, a one-line consequence.
6. **Cards state their action**: any clickable card gets an explicit affordance ("Enter cockpit →"),
   not just hover styling.

### 3. Asset-based surfaces (user thinks in drones, not devices)

- Device-level actions (rename device, assign/unassign, raw register) stay ONLY in Warehouse →
  Advanced; asset rows/cards never show device verbs. "Create asset from this device" is renamed to
  its outcome: "Promote to asset…" with a one-line explainer in the dialog.
- The Fly picker card (screenshot evidence: filename title + Streaming chip, nothing else) becomes
  an asset card: given name (fallback: filename, but U-d's wizard makes names first-class), category
  icon, **live attributes** (battery, position/last-seen, stream state with word), owner (post U-e),
  and the explicit "Enter cockpit →" affordance. If it's streaming, a thumbnail.

Exit: a first-time operator can say out loud what every visible button will do before pressing it
(hallway test, 5 users × 3 screens); zero enabled-then-error paths; archive recoverable via Undo;
Map/Assets/Devices share the §1 dictionary verbatim.

## U-b — Visual restyle: engineered, not generated (≈1 week, frontend only)

Kill the named "AI-slop tells" and adopt the ops-room language (all researched, sourced in the
design report):

1. **Panels**: flat, bordered, elevation by background-lightness steps (L0/L1/L2) — delete
   rounded-card-with-shadow styling; shadows only on true overlays (menus, modals, toasts).
2. **Palette**: near-black charcoal base (never `#000`), one desaturated surface family, **one**
   saturated accent for primary action/selection only. Status hues maximally dissimilar (mint/cyan/
   amber/red family; no violet) and distinct from the accent; system errors visually separate from
   operational alerts.
3. **Typography**: telemetry numerals switch to **B612 Mono** (open-source, designed by Airbus for
   cockpit displays — the single strongest "engineered" signal available) with
   `font-variant-numeric: tabular-nums` as minimum fallback; one intentional UI sans for chrome.
4. **Header**: identity/nav only (logo, nav tabs, later org switcher + user menu). Zero action
   buttons in persistent chrome — actions belong to the selected entity's panel.
5. **Iconography**: one line-icon family, single stroke weight, no decorative icons.
6. **Mode controls**: mutually-exclusive map tools (layers/follow/draw) become segmented controls
   with strong active state, not parallel buttons.
7. Panel state (collapsed/pinned/width) persists per user (localStorage), Traccar-style: explicit
   collapse, reopen via toggle chip.

Exit: side-by-side screenshot review; no gradient/shadow-card/violet leftovers; telemetry numbers
don't jitter; header has no buttons.

## U-c — Screen consolidation: map-first manager, cockpit pilot (≈1–2 weeks, frontend)

Today three screens overlap on "see the whole fleet" (Wall=video grid, Map=geo, Command=attention)
and Live is subsumed by Fly. Target: **two primary surfaces + warehouse + settings.**

1. **Command becomes THE manager screen** (FlytBase Fleet View 2.0 three-panel model):
   - Full-bleed **map as canvas**; slim entity list docked left (collapsible); events strip bottom.
   - Selecting an asset (marker or row) opens a right detail panel (~380 px) with tabs
     **Status / Telemetry / Video / History** — all per-asset actions live here, not in toolbars.
   - **Pin** action: layout reflows around one asset — map recenters+follows, its video tiles surface.
   - **Video-grid panel** (the old Wall) becomes a dockable panel/tab of Command, not a route.
     Verizon-Connect-style scale rule: above ~200 assets the list virtualizes and markers cluster.
   - `/map` and `/wall` routes redirect into Command states; their components fold in.
2. **Fly stays the pilot cockpit**, deliberately dense (QGC grammar: left action rail, center
   video+HUD, right instrument stack, map inset). Add the **keyboard-shortcut layer** as the second
   UI so on-screen controls shrink further. "Watch" links from Command open Fly in watch mode —
   **Live retires** once Fly covers fixed-camera watching (capability-driven: no OSD/map for
   camera-only assets, per UX-DESIGN §5.2).
3. **Replay** stays as-is (clean); density-strip capped to sane bucket count per viewport.
4. Confirmation discipline: two-step only for high-consequence acts (stop stream mid-flight,
   archive, manual takeover); everything else single-click.

Exit: nav = Fly · Command · Warehouse · Settings (+Debug by URL). A manager triages from one screen;
map visible at rest; zero action buttons outside detail panels/selection context.

## U-d — Asset & warehouse rework (≈1 week frontend + small backend)

Keep what works: the **3-choice connect component (port / resource / simulate) stays** — it becomes
step 2 of a wizard instead of a card on a crowded page.

1. **Onboarding wizard** (own route, Fleetio/Oxmaint sequence), replacing the inline card:
   1. *Profile* — display name, **registration/tail number**, **photo/image**, category.
   2. *Connect* — the existing 3-choice component, unchanged.
   3. *Test* — probe + first decoded frame before save (UX-DESIGN §5.1 "test-before-save";
      no asset persists that can't produce a frame; specific errors, not "start failed").
   4. *Assign* — group/site (until U-e ships: category + attributes only).
2. **Close the orphaned-device dead end**: Register/Discover paths end by creating an Asset
   (`POST /api/assets` exists and is unused by the UI) wrapping the new device; "Advanced (raw
   devices)" keeps working for surgery but is no longer the only outcome.
3. **Backend additions** (small, honest scope):
   - `registrationNumber` as first-class attribute convention + editable `attributes` in
     `PATCH /api/assets/{id}` UI (contract already carries `attributes?` — UI never sends it).
   - **Asset image**: new endpoint pair (`PUT/GET /api/assets/{id}/image`, stored via persistence
     adapter; small binary, thumbnail derivative). Wire into AssetSummary as `hasImage`/image URL.
4. **Warehouse page** = the current Devices page minus onboarding (moved to wizard), renamed,
   asset-first list + collapsed advanced table, kebab menus from U-a.

Exit: a real camera goes from "discovered" to "first-class asset with photo and tail number" in one
wizard pass; Devices' three jobs live on three surfaces (wizard / warehouse / advanced).

## U-e — Users, roles, hierarchy (architecture change; ≈3–4 weeks, all layers)

The MVP4 item, now with a concrete researched shape. Nothing here can be faked in UI first —
`owner` is a hardcoded UUID and no Role type exists.

1. **Domain**: `User` (id, name, email), `Role` (`PILOT`, `MANAGER`, `ADMIN`), `Group` tree
   (id, name, parentGroupId) — the org chart. Asset gains `groupId`; User gains group memberships
   with a role per membership. Visibility rule (Geotab/Samsara convergence): **you see your group's
   subtree**; warehouse, fleet list, map, events all filter by it. An inviter can grant role/scope
   **at or below their own** — enforced in application layer.
2. **Application/API**: user CRUD + invite flow (org-settings surface, not the map), group CRUD,
   assignment endpoints (asset→group, pilot→asset). `CurrentUser` replaces `DevPrincipal` — its
   javadoc already marks it "the single thing to replace"; dev mode keeps an auto-admin.
3. **Auth**: session login (Spring Security, server-rendered login), password or OIDC later;
   API tokens stay documented (UX-DESIGN §7 "the UI has no private API").
4. **Role-based UX**:
   - Pilot logs in → **Fly** with *their assigned drones* only (assignment from U-e.2).
   - Manager → **Command** scoped to their group; sub-groups appear in the left-rail tree.
   - Manager-of-managers → same Command; tree shows sub-managers' groups, drill-down re-scopes
     map/list/warehouse (Geotab "Belonging to" pattern).
   - Warehouse per level = group-subtree filter, free with the visibility rule.
5. **Persistence**: users/groups/memberships tables behind `vision.persistence.enabled`; dev
   in-memory fallback like other repos.

Exit: three seeded users (pilot / manager / manager-of-managers) log in and each sees exactly their
§0 row; a pilot never sees another team's drones; invite respects the ≤-own-scope rule.

---

## Sequencing & dependencies

```
UI-STRUCTURE-PLAN migration  →  U-a → U-a2  →  U-b  →  U-c  →  U-d  →  U-e
(folders first: everything           (each phase independently shippable and
 after lands in the new layout)       valuable; stop-points between all of them)
```

- REALTIME-PLAN R-c/LiveStore must land before U-c (Command's live panels ride the SSE channel).
- U-d backend (image endpoint, attributes edit) can run parallel to U-b/U-c frontend.
- U-e is a full MVP4 cycle of its own; U-a…U-d never block on it.
- Per CLAUDE.md delegation: each phase = disjoint-scope subagent tasks, MODULE.md updated, scoped builds green.
```
