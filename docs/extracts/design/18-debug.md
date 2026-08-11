# 18 — Debug

**Files:** `features/debug/**`
**Wave:** 2

## Current state

`page-head` ("Debug" + a 3-line description). Three panels:

- **Raw API console** — `ENDPOINT` select ("Custom…"), `METHOD` select (GET), `PATH` input
  (`/api/devices`), `Send`.
- **Health** — `Overall: 404` (red chip), "No component-level detail exposed by this backend — see raw
  JSON.", a `▸ Raw JSON` disclosure, `Refresh`.
- **Last scan** — explanatory prose about `/api/discovery/scan`, `Run scan`, "No scan run yet."

## Problems

- **`Overall: 404`** — the health panel is pointing at an endpoint the backend does not serve
  (confirmed independently: `GET /actuator/health` returns 404 on this deployment). It renders a red
  failure chip for a *missing probe*, not an unhealthy system, which is actively misleading on a
  diagnostics page.
- **No response area.** `Send` has nowhere visible to put the result — the console's output is below
  the fold or absent, which makes the primary control look inert.
- The three panels are unrelated tools stacked vertically with equal weight; the console (the reason
  to open the page) is not obviously primary.
- `PATH` is 530px wide, `METHOD` is 165px for a 6-value enum, `ENDPOINT` is 370px for a preset list.
- The prose in `Last scan` (three code-formatted identifiers in a sentence) is reference material, not
  UI copy.
- Sits under Manage → Diagnostics, correctly, but has no indication it is a power-user tool.

## Suggested design

A console is a two-pane tool: request left, response right.

```
┌────┬─────────────────────────────┬────────────────────────────────┐
│ ▎🔧│ 🔧 Debug          ⬤ backend reachable · 1 live               │
│    ├─────────────────────────────┼────────────────────────────────┤
│    │ [preset ⌄]                  │ 200 OK · 42ms · 1.2 KB         │
│    │ [GET ⌄] [/api/devices    ]  │ ┌────────────────────────────┐ │
│    │              [Send ⏎]       │ │ [                          │ │
│    │ ── History ──               │ │   {                        │ │
│    │ GET /api/devices     200    │ │     "deviceId": "9d21…",   │ │
│    │ GET /api/assets      200    │ │     "name": "11 · video"   │ │
│    │ GET /actuator/health 404    │ │   }                        │ │
│    │                             │ │ ]                          │ │
│    │ ── Discovery ──             │ └────────────────────────────┘ │
│    │ [Run scan]  no scan yet     │            [Copy] [Download]   │
└────┴─────────────────────────────┴────────────────────────────────┘
```

- **Response pane is the point** — status, latency, size, pretty-printed body, copy/download.
- **Request history** with status codes, click to re-run. This is what makes a console usable and it
  costs a signal array.
- **Health becomes a header indicator, not a panel.** Probe reachability honestly: if
  `/actuator/health` 404s, show `health probe not exposed` in neutral grey — not a red `404`. Reuse
  the shell's existing reachability signal rather than a second mechanism.
- **Discovery scan** demoted to a small control in the left column; its explanatory prose moves to a
  `?` popover.
- Fields sized to content (`METHOD` 12ch, `PATH` fills).
- `Ctrl/Cmd+Enter` sends.

## Refactor list

- **Add** the response pane (status/latency/size/body/copy/download) — the console's missing half.
- **Add** in-memory request history for the session.
- **Fix** the health probe's failure semantics: distinguish "probe absent" (neutral) from "probe says
  unhealthy" (red). Verify which actuator endpoints this deployment actually exposes before choosing
  the path.
- **Demote** Health to a header chip; delete the panel.
- **Size** the inputs; add the keyboard shortcut.
- **Replace** `page-head` with `vision-page-bar`.

## Acceptance

- `Send` renders a response without scrolling.
- A missing health probe is never shown as a red failure.
- Re-running a history entry reproduces the request exactly.
