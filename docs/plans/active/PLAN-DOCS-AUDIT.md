# Plan-docs audit — can we safely delete them?

**Date** 2026-08-21 · method: exhaustive inbound-citation count per doc across the repo
(excluding `target/`, `node_modules/`, `.git/`), split by **CODE** (java/py/ts/html/css/yaml/xml/sql/sh)
and **DOCS** (other `.md`).

## The answer in one line

**No — not the plans.** 23 of 50 active plan docs are cited *from source code*, several by 50–134 files.
They stopped being plans and became **reference documentation**; deleting them turns javadoc into a lie.
What is safe is a much smaller sweep: the 21 docs with **zero** code citations, of which only the
merged `*-CONTEXT.md` session scratchpads are genuinely disposable.

## Why moving is almost as dangerous as deleting

Citations are **by path** (`docs/plans/active/X.md`), so an `active/ → done/` archive move breaks exactly
the same references a deletion would. The cost of archiving a doc is therefore its CODE column, not zero.

## Inbound citations per active plan doc

| Doc | CODE | DOCS | Disposition |
|---|---:|---:|---|
| VISUAL-GEO-V2-PLAN.md | 134 | 16 | **KEEP — reference doc**, never move without a scripted fix-up |
| LAYERING-REFACTOR-PLAN.md | 109 | 19 | **KEEP — reference doc**, never move without a scripted fix-up |
| DRONE-ONBOARDING-PLAN.md | 107 | 13 | **KEEP — reference doc**, never move without a scripted fix-up |
| DRONE-INFRA-PLAN.md | 78 | 16 | **KEEP — reference doc**, never move without a scripted fix-up |
| FIXED-CAMERA-GEO-PLAN.md | 70 | 9 | **KEEP — reference doc**, never move without a scripted fix-up |
| SYSTEM-STATUS-PLAN.md | 60 | 8 | **KEEP — reference doc**, never move without a scripted fix-up |
| MEDIA-SOT-PLAN.md | 57 | 16 | **KEEP — reference doc**, never move without a scripted fix-up |
| OPS-UX-PLAN.md | 52 | 7 | **KEEP — reference doc**, never move without a scripted fix-up |
| DOMAIN-SEPARATION-W1.md | 51 | 18 | **KEEP — reference doc**, never move without a scripted fix-up |
| CV-DEMAND-PLAN.md | 49 | 6 | **KEEP — reference doc**, never move without a scripted fix-up |
| STREAM-STATE-PLAN.md | 41 | 8 | **KEEP — reference doc**, never move without a scripted fix-up |
| POSTGRES-ONLY-CONTEXT.md | 32 | 10 | **KEEP — reference doc**, never move without a scripted fix-up |
| SCALE-100-PLAN.md | 30 | 9 | **KEEP — reference doc**, never move without a scripted fix-up |
| TRACKING-V3-BAND1-CONTEXT.md | 25 | 5 | **KEEP — reference doc**, never move without a scripted fix-up |
| AFTER-ACTION-PLAN.md | 23 | 6 | **KEEP — reference doc**, never move without a scripted fix-up |
| MAVLINK-CORE-PLAN.md | 22 | 9 | **KEEP — reference doc**, never move without a scripted fix-up |
| CV-RECONNECT-PLAN.md | 20 | 5 | **KEEP — reference doc**, never move without a scripted fix-up |
| CV-RATE-CONTROL-PLAN.md | 20 | 8 | **KEEP — reference doc**, never move without a scripted fix-up |
| GEO-POSE-PLAN.md | 18 | 5 | KEEP — code-cited |
| TRACKING-V2-PLAN.md | 14 | 5 | KEEP — code-cited |
| TRACKING-V3-PLAN.md | 6 | 8 | KEEP — code-cited |
| RC-CONTROL-PLAN.md | 6 | 5 | KEEP — code-cited |
| CV-UX-RESEARCH.md | 6 | 6 | KEEP — code-cited |
| IA-TRUTH-PLAN.md | 3 | 2 | KEEP — code-cited |
| MODULE-LAYOUT-PROPOSAL.md | 2 | 2 | KEEP — code-cited |
| MISSIONS-PLAN.md | 2 | 6 | KEEP — code-cited |
| DOMAIN-SEPARATION-PLAN.md | 2 | 10 | KEEP — code-cited |
| STREAM-STATE-CONTEXT.md | 1 | 1 | KEEP — code-cited |
| SCALE-100-CONTEXT.md | 1 | 2 | KEEP — code-cited |
| VISUAL-GEO-V2-DEMO.md | 0 | 1 | ARCHIVE candidate (cheap: only `.md` links to fix) |
| VISUAL-GEO-RESEARCH-CONTEXT.md | 0 | 1 | ARCHIVE candidate (cheap: only `.md` links to fix) |
| PLATFORM-AUDIT-UI.md | 0 | 2 | ARCHIVE candidate (cheap: only `.md` links to fix) |
| PLATFORM-AUDIT-SCOPE.md | 0 | 2 | ARCHIVE candidate (cheap: only `.md` links to fix) |
| PLATFORM-AUDIT-FINDINGS.md | 0 | 1 | ARCHIVE candidate (cheap: only `.md` links to fix) |
| PLATFORM-AUDIT-DB.md | 0 | 2 | ARCHIVE candidate (cheap: only `.md` links to fix) |
| PLATFORM-AUDIT-CONTEXT.md | 0 | 2 | ARCHIVE candidate (cheap: only `.md` links to fix) |
| PLATFORM-AUDIT-ANALOGS.md | 0 | 2 | ARCHIVE candidate (cheap: only `.md` links to fix) |
| PLAN-DOCS-AUDIT.md | 0 | 0 | **DELETE-SAFE** (zero inbound references) |
| MISSIONS-CONTEXT.md | 0 | 1 | ARCHIVE candidate (cheap: only `.md` links to fix) |
| MEDIA-SOT-CONTEXT.md | 0 | 0 | **DELETE-SAFE** (zero inbound references) |
| MAVLINK-CORE-CONTEXT.md | 0 | 1 | ARCHIVE candidate (cheap: only `.md` links to fix) |
| FLEET-MIGRATION-PLAN.md | 0 | 3 | ARCHIVE candidate (cheap: only `.md` links to fix) |
| FLEET-MIGRATION-CONTEXT.md | 0 | 1 | ARCHIVE candidate (cheap: only `.md` links to fix) |
| FIXED-CAMERA-GEO-DEMO.md | 0 | 3 | ARCHIVE candidate (cheap: only `.md` links to fix) |
| FIXED-CAMERA-GEO-CONTEXT.md | 0 | 1 | ARCHIVE candidate (cheap: only `.md` links to fix) |
| EVENT-TOPOLOGY-PROPOSAL.md | 0 | 1 | ARCHIVE candidate (cheap: only `.md` links to fix) |
| DRONE-ONBOARDING-CONTEXT.md | 0 | 1 | ARCHIVE candidate (cheap: only `.md` links to fix) |
| DEAD-CODE-AUDIT.md | 0 | 2 | ARCHIVE candidate (cheap: only `.md` links to fix) |
| CV-SCALE-PLAN.md | 0 | 5 | ARCHIVE candidate (cheap: only `.md` links to fix) |
| CREW-CONTROL-PLAN.md | 0 | 5 | ARCHIVE candidate (cheap: only `.md` links to fix) |
| AFTER-ACTION-CONTEXT.md | 0 | 1 | ARCHIVE candidate (cheap: only `.md` links to fix) |

## Recommended action, safest first

1. **Do nothing to the 23 code-cited docs.** They are load-bearing. If they must ever move, it is a
   scripted `sed` sweep over the whole repo in the same commit — the `docs-organization` convention.
2. **Add `docs/plans/README.md`** — a one-line-per-plan index with status. This solves the real problem
   (51 files is a retrieval cost for every future agent) without moving a single path.
3. **Archive the zero-code-citation `*-CONTEXT.md` scratchpads whose work is merged** — each costs only
   1–3 markdown link fixes. These are per-session working notes by convention, not specs.
4. **`MEDIA-SOT-CONTEXT.md` is the only file with zero inbound references of any kind** — the single
   unambiguous DELETE-SAFE, and its work is merged (1bece62).
5. **Do not touch** `CREW-CONTROL-PLAN.md` (unbuilt spec we are about to need), the `PLATFORM-AUDIT-*`
   set (written today), `FLEET-MIGRATION-PLAN.md`, `CV-SCALE-PLAN.md`, `EVENT-TOPOLOGY-PROPOSAL.md` —
   zero code citations only because the work is **unbuilt**, which is the opposite of disposable.
6. **`MISSIONS-PLAN.md` needs a decision, not a filing action** — an active XL spec that `MOAT.md` §6 and
   `MASTER-MATRIX.md` B9/M3 both mark NO. Reconcile in writing first.

## Honest cost/benefit

The whole `docs/plans/active/` tree is ~1.2 MB of text. Deleting every genuinely-safe file reclaims a
rounding error of disk and removes context a future agent would want. **The retrieval cost is real; the
storage cost is not.** Fix retrieval with an index (action 2), not with deletions.
