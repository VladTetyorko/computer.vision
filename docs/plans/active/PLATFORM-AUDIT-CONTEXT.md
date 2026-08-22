# PLATFORM AUDIT — context

**Started** 2026-08-21 · owner-requested, read-only audit (no product code changes in this pass).

## The ask

1. Double-check the **UI flows**: assets handling, persons handling, and the ADMIN / crew / PILOT scopes.
2. Check the **analogs on the internet** and say how to make the application more useful.
3. Check the **database**: is the state scalable and useful.

## What is already known before the audit starts

- Roles are `PILOT < MANAGER < ADMIN` (`identity/domain/model/Role.java`). **There is no CREW role** —
  `docs/plans/active/CREW-CONTROL-PLAN.md` is specced-only. The owner's "crew" is therefore either
  MANAGER, or a gap.
- `VisibilityScope` (vision-platform) splits *visibility* (UNBOUNDED / GROUPS / ASSIGNED_ASSETS) from
  *authority* (`canAdminister` / `canManage`) — the OPS-UX §1 outcome, merged b435838.
- SPA has 34 routed features under one `authGuard`, landing resolved by role (`landing-guard.ts`).
- 23 Flyway migrations, Postgres is the only store (`[[postgres-only]]`, 72fae57).
- Scale work so far: SCALE-100 bands A+B merged; the 50k tier is deferred to DOMAIN-SEPARATION.

## Audit lanes (four parallel agents, read-only)

| Lane | Output | Question |
|---|---|---|
| A — UI flows | `PLATFORM-AUDIT-UI.md` | Are asset/person/role journeys complete and reachable? |
| B — scope enforcement | `PLATFORM-AUDIT-SCOPE.md` | Is authority enforced server-side on every write path? |
| C — database | `PLATFORM-AUDIT-DB.md` | Does the schema survive growth; is it indexed, retained, useful? |
| D — analogs | `PLATFORM-AUDIT-ANALOGS.md` | What do DELTA/TAK/Lattice/Skydio/Auterion do that we don't? |

Synthesis lands in `PLATFORM-AUDIT-FINDINGS.md` with a ranked, costed action list.
