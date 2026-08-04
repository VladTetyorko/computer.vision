# Page design files

Per-page designs for the navigation + layout rework. The master spec — findings, the target
navigation model, and the wave plan — is [`../NAV-IA-REDESIGN-PLAN.md`](../NAV-IA-REDESIGN-PLAN.md).

Each file follows the same shape: **Current state** (what the running app does today, observed) →
**Problems** (cross-referenced to the master file's `F1…F12` findings) → **Suggested design** (with an
ASCII layout) → **Refactor list** → **Acceptance**.

| # | Page | Route(s) | Wave | Verdict |
|---|---|---|---|---|
| [00](00-shell.md) | App shell / navigation | — | 1 | Top bar → persistent left sidebar |
| [01](01-fly.md) | Fly — picker + cockpit | `/fly` | 1, 4 | Full-bleed; cockpit gets a URL |
| [02](02-command.md) | Command | `/command` | 1, 3 | Keep — it is the reference layout |
| [03](03-wall.md) | Wall | `/wall` | 1, 2 | Full-bleed; density replaces tile count |
| [04](04-assets.md) | Assets | `/assets` | 2, 3 | Card grid → list + side panel |
| [05](05-asset-detail.md) | Asset detail | `/assets/:id` | 2, 4 | Drill-in grid → tabs |
| [06](06-devices.md) | Devices | `/devices` | 2, 3 | Keep table; one action + panel |
| [07](07-add-source.md) | Add source | `/add-source` | 2 | Centered form; size the fields |
| [08](08-alerts.md) | Alerts center | `/monitor/alerts` | 2, 3 | Dense rows + frame preview |
| [09](09-activity.md) | My activity | `/activity` | 2, 3 | Day grouping; Mine/Everyone scope |
| [10](10-replay.md) | Replay | `/replay` | 4 | **Broken today** — mark `soon`, then build |
| [11](11-settings.md) | Settings | `/settings` | 2, 4 | Split account vs detection defaults |
| [12](12-org.md) | Organization | `/org` | 2, 3 | Invite → modal; mask the credential |
| [13](13-roster.md) | Pilots / roster | `/manage/roster` | 2, 3 | Accordion → pivotable matrix |
| [14](14-categories.md) | Asset categories | `/manage/categories` | 2 | Group the empties; make it editable |
| [15](15-reports.md) | Inventory reports | `/manage/reports` | 2 | Add time; make every number a link |
| [16](16-training.md) | CV training | `/manage/training` | 2 | Fix the disabled state; add tabs |
| [17](17-preflight.md) | Pre-flight | `/operate/preflight` | 2, 4 | Fold into cockpit as a GO/NO-GO gate |
| [18](18-debug.md) | Debug | `/debug` | 2 | Add the missing response pane |
| [19](19-hubs.md) | Hubs | `/operate` `/monitor` `/manage` | 1 | **Delete** — redirect to their primary child |

## Evidence

Written from a full click-through of the running app on 2026-08-04 (localhost:4200, admin session,
1854×961 viewport, seeded with one simulated asset "11" carrying a video + a telemetry device).

Two pages could not be exercised and their designs are marked provisional where that matters:

- **CV training** — gated off by `vision.training.enabled`; only the disabled state was observed.
- **Replay player** — `/assets/:assetId/replay/:usageId` needs a finished usage; only the broken
  library entry point was observed.
