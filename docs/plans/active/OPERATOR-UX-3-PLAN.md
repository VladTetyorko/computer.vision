# OPERATOR-UX-3 — what a walkthrough of the running app found (2026-08-28)

Branch `feat/operator-ux-3`. Pure `station/vision-web`; no wire change. Found by opening every
Operate/Monitor page in Chrome against the dev backend with 17 offline assets — the state a real
station is in most of the time. Style law: `.claude/skills/frontend-style/SKILL.md`.

## 1. Findings

| # | Where | What was seen | Why it matters |
|---|---|---|---|
| **H1** | `/fly/<id>` OSD + Controller drawer | A rover last heard from **4 days ago** shows `POWER 90% · ARMED · Loiter · 3D 13 sat` in full colour; only the LINK chip says `353099s`. The drawer's state strip says **ARMED** in green from the same sample | CLAUDE.md rule 9 — newest data wins, and stale data must not impersonate live data. "ARMED" from a 4-day-old sample is the wrong thing to be confident about |
| **T1** | `/fly` index | 17 identical "Offline" cards, three real ESP32 rovers mixed with simulated aircraft, no order, no filter | The page's single job is "which one do I enter"; today it cannot answer it |
| **P1** | `/operate/preflight` | Every row reads `Unknown · Map position, Preflight checklist +9 more`; the GO/NO-GO/UNKNOWN cards are not filters | The one blocker that matters is hidden behind "+9 more"; the cards invite a click and do nothing |

## 2. Design

### H1 — stale is not live (`STALE_AFTER_SECONDS` already exists in `core/telemetry/telemetry-logic.ts`)
- One pure helper, `freshness(ageSeconds)` → `'live' | 'aging' | 'stale' | 'none'`, reusing the OSD's existing age tiers (`fly-osd.ts#ageSeverityTier`) rather than a second threshold.
- **OSD**: past stale, every metric group (POWER, NAV) dims to `--text-muted`, the numbers stay (they are the last known) but the group label becomes `LAST KNOWN · 4d 2h` in the structural register; the age chip stays red. Armed reads `ARMED?` never `ARMED` while stale.
- **Drawer state strip**: the armed chip while stale is neutral with text `Armed 4d ago` (no `ok` tone); mode chip likewise faint. The `armedChipView` helper in `rc-monitor-logic.ts` gains the age input.
- **Cockpit body when nothing streams**: replace the centred "Not streaming" caption with an honest last-known card: last seen (relative), last position with "Open on map" (`/command`), and the primary action `Watch live`/`Start stream` the asset panel already offers. Video stays the page; the card is one `--panel-raised` block centred in the dark surface.
- Age formatting: `ageLabel` becomes human (`12s`, `3m 10s`, `4h`, `4d 2h`) — `353099s` is a number nobody parses.

### T1 — Fly index that triages
- Cards sorted: streaming first, then by `lastSeen` descending, never-seen last.
- Two groups with quiet structural-label headers: **Your vehicles** (any asset with a non-simulated device / category ≠ Simulated) and **Simulated**. Group headers carry counts. A `Hide simulated` toggle (persisted in `localStorage`) collapses the second group.
- The status chip reads the age, not the word: `Offline · 2h 29m` / `Offline · 6d 4h` / `Never seen` (H1's `humanAge`, one vocabulary); streaming stays the live chip.
- No new filters beyond the toggle — the page has one job.

### P1 — Pre-flight table that names the blocker
- Row "needs attention" becomes: the **first failing check's own label** + `+N` count chip (one chip per row, style §5); rows sorted worst-first (NO-GO, then UNKNOWN with the most failures, then GO).
- The three verdict cards become the filter (selection language §4); clicking one filters the table, clicking again clears.
- Empty state for a filtered-out table: "No NO-GO drones — every failing check is listed under Unknown."

## 3. Waves (disjoint files)

| Wave | Files | Green when |
|---|---|---|
| **H1** | `core/telemetry/telemetry-logic.ts` (+spec: `freshness`, `humanAge`), `features/fly/fly-osd.{ts,html,css}` (+spec), `features/fly/rc-monitor-logic.{ts,spec.ts}` + `rc-monitor.{ts,html,css}` state strip only, `features/fly/cockpit.{html,css}` not-streaming block (+ `cockpit-facade.ts` if last-seen/position isn't already exposed) | fly specs; both themes inside `.surface-dark` |
| **T1** | `features/fly/drone-picker*.{ts,html,css,spec.ts}`, a pure `features/fly/drone-picker-logic.ts` (+spec) for grouping/sorting/age labels | picker specs |
| **P1** | `features/preflight/*`, a pure `features/preflight/preflight-logic.ts` (+spec) | preflight specs |

Status: H1/T1/P1 open.
