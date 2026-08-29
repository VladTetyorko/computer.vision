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
| W1 rail | web-ui | done | `3b8a9649` |
| W2 domain | domain-modeler | started | |
| W3 persistence + API | spring-integrator | blocked on W2 | |
| W4 inventory page | web-ui | blocked on W1, W3 | |
| W5 readiness ← maintenance | application-service | blocked on W2 | |
| W6 wizard | web-ui | blocked on W3 | |
| W7 maintenance + crew | web-ui | blocked on W3 | |

Shared tree: agents commit **by path**, never stash. Unrelated dirty files (`infra/rover-sim/**`, `core/rc/manual-control-client*`, `DefaultPeerDirectory.java`) belong to another session — do not touch.

## W1 notes for W4 (inventory page)

- Asset categories, Inventory reports, and Devices — no §3.1 group names a home for these three — landed in **FLEET** alongside the renamed Inventory/Add vehicle/Crew, since W1 is pure IA (OQ5) and none of the three is a stub. They're still separate nav entries/routes today (`/manage/categories`, `/manage/reports`, `/devices`); when W4's merged Inventory page ships its own tabs (OQ4), drop the three now-redundant `nav-entries.ts` FLEET entries in the same commit rather than leaving dead rail links alongside the new tabs.
- Manager entry count is **20**, not §3.1's own illustrative "15" — that figure already assumes W4's tab merge + W7's Maintenance entry, neither of which exists yet. Pilot count matches the plan's "10" exactly. Full reconciliation in `station/vision-web/MODULE.md`'s W1 changelog entry and `nav-entries.spec.ts`'s own count test.
- `/devices`, `/manage/categories`, `/manage/reports` all gained `canActivate: [orgGuard]` this wave (they didn't have it before) — W4's OQ4 redirect-into-tabs plan should keep that gate on whatever route ends up serving that content.
