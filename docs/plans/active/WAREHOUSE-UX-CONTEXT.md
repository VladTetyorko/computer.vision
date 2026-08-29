# WAREHOUSE-UX — working context

Opened 2026-08-29 · Branch `feat/warehouse-ux` (cut from master `59b879a5`) · Spec: [WAREHOUSE-UX-PLAN.md](WAREHOUSE-UX-PLAN.md)

## Decisions taken at start (defaults for §6 open questions, owner may override)

| OQ | Taken |
|---|---|
| OQ1 | `MAINTENANCE` **blocks**: readiness NO-GO with the record's summary; `engage` refuses. Manager releases in one click. |
| OQ2 | Custody per person (`UserId`). |
| OQ3 | Batteries/equipment first-class now; cycles as `attributes.cycles`. |
| OQ4 | `/assets` stays the canonical URL for W4 (rename deferred — 640 citations & bookmarks); `/devices`, `/manage/categories`, `/manage/reports` redirect into its tabs. |
| OQ5 | W1 ships first as pure IA. |

## Wave ledger

| Wave | Agent | State | Commit |
|---|---|---|---|
| W1 rail | web-ui | started | |
| W2 domain | domain-modeler | started | |
| W3 persistence + API | spring-integrator | blocked on W2 | |
| W4 inventory page | web-ui | blocked on W1, W3 | |
| W5 readiness ← maintenance | application-service | blocked on W2 | |
| W6 wizard | web-ui | blocked on W3 | |
| W7 maintenance + crew | web-ui | blocked on W3 | |

Shared tree: agents commit **by path**, never stash. Unrelated dirty files (`infra/rover-sim/**`, `core/rc/manual-control-client*`, `DefaultPeerDirectory.java`) belong to another session — do not touch.
