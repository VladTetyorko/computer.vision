# OPERATOR-UX-7 — lists that mean something

Seventh cycle of the operator-UX series (after OPERATOR-UX-6). Live walkthrough 2026-08-29 of Wall, Pre-flight, Devices, Reports, Roster, Org. Theme: a list of N identical rows, a badge that never resets, a conflict shown as three unrelated rows — the UI has the data to say more and says less.

## 1. Findings

| # | Where | What the operator sees | Why it is wrong |
|---|---|---|---|
| P1 | `/operate/preflight` | `Fleet readiness · 18 drones`, `GO 0 · NO-GO 0 · UNKNOWN 18`, eighteen alphabetical rows each `Unknown · Map position +10 · Report ›`, an IP camera among them, real rovers mixed with 14 simulated | A camera is not a drone and a rover does not fly; 18 identical rows say nothing — every asset is simply *not probed yet* (cycle 6 `core/readiness/readiness-logic.ts#hasBeenProbed`); no triage (cycle 3/4/5 fixed `/fly`, `/command`, `/assets`; this is the fourth list) |
| B1 | sidebar bell, every page load | `9+` unread badge on a station where nothing has happened for days | `readIds` is an in-memory signal — every reload marks all 33 historic events unread again. A badge that is always `9+` is a badge nobody reads |
| W1 | `/wall` events rail | 33 rows, every one `Removed device · 7fd88790`, the newest 9 days old; the selected row shows a red `OPEN` chip *and* truncates its label to `P…` | A live page's rail is 100 % history from devices that no longer exist; two chips fight for one row's width (§5: one chip per row) |
| D1 | `/devices` | `ESP32 Rover`, `ESP32 telemetry`, `ESP32 Rover · telemetry` — three telemetry devices, all `mavlink udp://0.0.0.0:14550`, shown as unrelated rows | Only one of them can own that UDP port; the operator learns which by starting one and watching the others fail. The table has the data (identical protocol + uri) and says nothing |

## 2. Design

**P1 — pre-flight triages and speaks the product's words.** Page bar `Fleet readiness · 18 vehicles`. Rows in `core/fleet/triage-logic.ts` order with the same **Your vehicles (n)** / **Simulated (n)** group headers as `/fly` and `/command` and the shared `vision.fly.hideSimulated` flag. A row whose report was never probed reads verdict `Not probed yet` (structural-label register, no chip) and its attention cell is `—`, not `Map position +10`; the `+n` rollup stays only for probed rows. An asset with no telemetry-capable device (capabilities never include `TELEMETRY`) reads `No telemetry device` in the same register — it cannot have flight checks and the page should not pretend to wait for them. Summary strip gains `NOT PROBED n` so `UNKNOWN` counts only probed-but-undecided rows.

**B1 — unread means "since you last looked".** `readIds` persists (`vision.bell.readIds`, via `core/panel-state.ts` read/write helpers, capped to the newest 500 ids); on a cold start with no persisted set the bell seeds the set with everything already there — history is not news (same rule cycle 5 gave toasts). Pure `notification-logic.ts#seedReadIds(events, persisted)` + `pruneReadIds(ids, cap)` with specs.

**W1 — the rail is the fleet's rail.** `events-rail` default filter excludes events whose source resolves to a removed device (`describeEventSource` already knows); a `Include removed devices (33)` checkbox in the rail header brings them back (not persisted). Empty state when everything is hidden: `No events from the current fleet · 33 from removed devices` with the checkbox as the action. Row: one chip — `OPEN` is the chip, the severity dot goes when the chip is present; label gets `flex: 1; min-width: 0` and the chip sits after the time, so the label is never `P…`.

**D1 — a shared endpoint is a fact worth one line.** Pure `features/devices/devices-page-logic.ts#endpointConflicts(devices)` → `Map<deviceId, string[]>` of *other* active device names with identical `protocol + uri` (archived devices excluded). The source cell shows, under the uri, `Same endpoint as ESP32 telemetry, ESP32 Rover · telemetry` in the warning text token (plain text, not a chip — the row already has its state chip); title carries the full list. No backend change, no blocking — the operator may well intend a shared listener.

## 3. Waves (disjoint files)

| Wave | Agent | Files |
|---|---|---|
| **W1 pre-flight** | web-ui | `features/preflight/**` |
| **W2 bell + rail** | web-ui | `shared/ui/notification-bell.*`, `shared/ui/notification-logic.*`, `shared/ui/events-rail.*`, `shared/ui/event-row.*` |
| **W3 devices** | web-ui | `features/devices/**` |

Every wave: `npx tsc --noEmit -p tsconfig.app.json` and `-p tsconfig.spec.json`, `npm run test:ci`, prod build, `station/vision-web/MODULE.md`, own files only, commit by path. **Do not stash** — the coordinator stashed the unrelated rc files once; three agents share this tree.

Status: in progress on `feat/operator-ux-7` (cut from master 2026-08-29).
