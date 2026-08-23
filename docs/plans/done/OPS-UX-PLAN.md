# OPS-UX-PLAN — resolving the pilot / manager / crew findings

Status: **active spec** (2026-08-16). Branch: `feat/ops-ux` (subbranch of `feat/cv-demand`).
Source of findings: [docs/conclusions/OPS-UX-REVIEW.md](../../conclusions/OPS-UX-REVIEW.md).
This document is the **authoritative contract**; the review is the reasoning behind it.

**Wave status (2026-08-16):** A ✅ · B ✅ · C ✅ · D ✅ (spec written — `CREW-CONTROL-PLAN.md` — not implemented).
Verified independently of the implementing agents: `vision-platform`/`vision-identity`/`vision-learning`
and `vision-api` (587/587) build green; `vision-web` 2024/2024 with a clean typecheck. Nothing committed.

Delegation: three implementation waves with **disjoint file scopes** (A and B share two files, so
they run in sequence, not in parallel), plus one design wave that writes a spec and no code.

---

## 1. The frozen decision: authority is derived, not stored

The review's §A1 asks to separate *what you may see* from *what you may do*. The cheapest correct
answer is that **the distinction already exists inside `VisibilityScope.Kind`** and was simply never
exposed:

| Kind | Who has it | Visibility | Authority |
|---|---|---|---|
| `UNBOUNDED` | ADMIN (and the dev principal when auth is off) | everything | administer the deployment |
| `GROUPS(subtree)` | MANAGER | that subtree | manage assets in those groups |
| `ASSIGNED_ASSETS` | PILOT | those assets | operate them, nothing more |

So **no new field, no new parameter, no new type, and no ripple through service signatures.**
`core/vision-platform/.../VisibilityScope.java` gains two derived predicates beside the existing
`canManageOrg()` / `includes()` / `includesGroup()`:

| Predicate | Definition | Answers |
|---|---|---|
| `canAdminister()` | `kind == UNBOUNDED` | "may I take a deployment-global action?" |
| `canManage(Ownership)` | `UNBOUNDED`, or `GROUPS` **and** `groups.contains(ownership.groupId())` | "may I administer *this* asset?" |

`canManageOrg()` keeps its exact current meaning and callers — it is the correct gate for
*team-scoped* management (creating an asset, creating a dataset, creating users). It is **not** the
correct gate for the two rows below it in the table.

**Non-negotiable invariant:** with `vision.auth.enabled=false` (the default) every caller is the
unbounded dev principal, so every predicate above returns `true` and **nothing about the running
system changes**. Any wave-C test that fails under the default profile is a bug in the wave, not a
behaviour change to accept.

### Where the two new predicates apply

| Operation | Gate today | Gate after | Failure |
|---|---|---|---|
| `AssetController` `setState` / `delete` / `assignDevice` / `unassignDevice` | `requireInScope` (visibility) | `canManage(asset.ownership())` | `403` — the caller can already see it, so hiding it is pointless |
| `AssetController` `update` — **revised after review**: split by what the body touches (`AssetEdit#changesManagedFields`). `displayName`/`attributes` are the operator's own record of the aircraft they fly and stay editable by an assigned pilot (visibility alone); `category` is fleet classification and requires manage. | `requireInScope` (visibility) | visibility for name/custom fields, `canManage(asset.ownership())` for `category` | `403` on a category change |
| `AssignmentService` `assign` / `unassign` | `scope.includes(...)` | `canManage(asset.ownership())` | `AccessDeniedException` → `403` |
| `POST /api/assets` (create) | none | `canManageOrg()` | `403` |
| `ModelRegistryService#promote` | `canManageOrg()` | `canAdminister()` | `AccessDeniedException` → `403` |
| `TrainingJobService#start` | `canManageOrg()` | `canAdminister()` | `AccessDeniedException` → `403` |
| `DatasetService` create/delete, `UserService`/`GroupService` create | `canManageOrg()` | **unchanged** | — |
| Every read, every flight command (arm/disarm/mode/RTH) | scope | **unchanged** | — |

**A 403 from an asset write is a pilot-only outcome, by construction.** For a `GROUPS` scope `canManage(ownership)` and `includes(id, ownership)` are the same predicate, so a manager who can see an asset can always manage it, and one who cannot is stopped by the existence-hiding 404 first. Only a scope that sees without managing — a pilot's — can reach the 403.

Flight commands stay on visibility deliberately: operating an assigned aircraft is exactly what a
pilot's authority *is*. This plan restricts administering the fleet, never flying it.

---

## 2. Wave A — pilot honesty and dead ends  *(vision-web only, no new backend)*

Every endpoint this wave needs already exists: `GET /api/auth/me` (`topRole`, `memberships`,
`authEnabled`), `GET /api/users`, `PUT /api/assets/{assetId}/pilots/{userId}`.

| # | Change | Where |
|---|---|---|
| A1 | **Role-based landing.** `''` resolves to `/command` for ADMIN/MANAGER, `/fly` for PILOT, instead of the unconditional `redirectTo: 'fly'`. Must await `AuthStore#ready` — a guard, not a static redirect. Deep links and the browser back button are unaffected. **Corrected after implementation:** this applies only when `authEnabled` is true. With auth off the backend reports a fixed dev principal whose `topRole` is the literal `ADMIN`, so honouring it would send every unsecured install, demo and dev run to `/command` — reversing MVP3-PLAN §C-b's deliberate cockpit landing and breaking §1's own "with auth off nothing changes" invariant. Auth off ⇒ `/fly`, always. | `app.routes.ts` (+ a small guard beside it) |
| A2 | **A truthful empty state in the Fly picker.** Distinguish "the fleet is empty" from "nothing is assigned to *you*". A PILOT with zero assets sees *"No aircraft assigned to you yet"* and who to ask — their group's name from `memberships`, not a fabricated person's name if none is resolvable. Only a caller with `canManageOrg`-equivalent `topRole` is offered "Add source", pointing at **`/add-source`**, never `/devices?addSource=1`. | `features/fly/drone-picker.{html,ts}`, its facade |
| A3 | **"Who flies this?" as the wizard's last step.** After the asset is created, offer the group's pilots and assign the chosen ones via the existing endpoint. Default selection: the creator when they are a pilot in that group, otherwise none. Assignment failure must not read as creation failure — the asset exists; say so and link to the roster. | `features/onboarding/**` |
| A4 | **`Add source` becomes `managerOnly`** in the nav, matching wave C's new gate on `POST /api/assets`. | `features/hubs/nav-entries.ts` |
| A5 | **Hide `badge: 'soon'` entries from PILOT.** `navTiers()` already separates `upcoming`; ADMIN/MANAGER keep seeing them as the roadmap. | `shared/ui/app-sidebar/**` |
| A6 | **Unsecured-station banner.** While `/api/auth/me` reports `authEnabled === false`, show a persistent, non-dismissable strip: *"This station is unsecured — anyone on this network is an administrator."* Honest status over optimistic status (UX-DESIGN §7.2). It must not shift the cockpit's video layout. **rev.2 — see §5c**: shipped as an independent `position: fixed` strip, which satisfied "must not shift the layout" by painting over the sidebar, over an open drawer, and over the offline banner instead. Superseded by one measured banner stack. | app shell |

**Wave A file scope:** `station/vision-web/src/app/` — `app.routes.ts`, `features/fly/drone-picker.*`,
`features/onboarding/**`, `features/hubs/nav-entries.ts`, `shared/ui/app-sidebar/**`, the shell, and
new files these need. **Do not touch** `features/command/**`, `features/roster/**`,
`features/org-settings/**`, `core/api/**` beyond additive client methods.

---

## 3. Wave B — the manager's missing half  *(vision-web only, starts after A lands)*

| # | Change | Where |
|---|---|---|
| B1 | **An audit page over the built-and-unused `GET /api/audit`.** Actor, action, target, time, result; newest first; filterable by actor and action. Manager/admin only, both in nav (`managerOnly`) and by handling the endpoint's `403` honestly. This is the manager's accountability surface and today nothing in the SPA calls the endpoint. | new `features/audit/**`, `core/api/vision-api.ts` + `models.ts`, `nav-entries.ts`, `app.routes.ts` |
| B2 | **"Set up this station" checklist on `/command`**, shown to ADMIN only while the station is fresh (no users beyond the seeded three, or zero assets). Four rows — create a group · add pilots · add your first aircraft · assign a pilot — each linking to the page that already does it, each ticking itself off from live data. Disappears on its own; never dismissable-and-forgotten. | `features/command/**` |
| B3 | **Roster attention rows.** Surface `countAssetsWithoutPilot` (already written, already tested, currently unused as a headline) plus its mirror, pilots with no assignment. These two numbers are the roster's reason to exist. | `features/roster/**` |
| B4 | **Rename "Invite a user" to what it does** — the form creates a user with an admin-chosen password and sends nothing. Copy only; the real invite flow is wave D. | `features/org-settings/org-settings.html` |

**Wave B file scope:** the files named above. `nav-entries.ts` and `app.routes.ts` are shared with
wave A — **wave B must start from wave A's committed state**, never in parallel with it.

---

## 4. Wave C — authority ≠ visibility  *(Java only)*

Implements §1 exactly. Nothing in this wave is discretionary; the table in §1 is the contract.

| # | Change |
|---|---|
| C1 | `VisibilityScope` gains `canAdminister()` and `canManage(Ownership)`, with unit tests covering all three kinds × in/out of group. Javadoc must state *why* the two predicates exist (visibility answered an authority question) — this file is where the next reader will look. |
| C2 | `AssetController`'s five write endpoints move from `requireInScope` to a manage guard returning **403**; reads keep `requireInScope`'s 404-hides-existence behaviour untouched. `POST /api/assets` gains `canManageOrg()`. |
| C3 | `DefaultAssignmentService#requireGrantable` moves from `includes` to `canManage(asset.ownership())`. |
| C4 | `DefaultModelRegistryService#promote` and `DefaultTrainingJobService#start` move from `canManageOrg()` to `canAdminister()`, audit lines included — a denied promote is already audited and must stay so. |
| C5 | Tests: a PILOT scope may fly an assigned asset and may **not** rename, deactivate, delete, re-pilot it, promote a model or start training. A MANAGER may do all asset-level actions in their subtree and **not** promote a model. Existing tests that assumed pilot-writes succeed are the finding, not the regression — update them and say so. |
| C6 | `MODULE.md` for every module touched: `core/vision-platform`, `contexts/vision-warehouse`, `contexts/vision-identity`, `contexts/vision-learning`, `station/vision-api`. |

**Wave C file scope:** `core/vision-platform/**`, `contexts/vision-warehouse/**`,
`contexts/vision-identity/**`, `contexts/vision-learning/**`, `station/vision-api/**`,
`station/vision-app/**` (only if wiring demands it). **Never** `station/vision-web/**`.

Build scoped, never reactor-wide: `./mvnw -B -pl <path> test` per module touched.

---

## 5. Wave D — crew and control ownership  *(spec only, no code)*

The review's §A2 and §A3 are a real feature, not a fix, and this repo's process is that features get
an authoritative plan before they get agents. Wave D writes `docs/plans/active/CREW-CONTROL-PLAN.md`
covering:

- **Who holds control.** `AssetUsage` is already the flight session with a start, an end and
  telemetry — it is the natural home for a control holder. The plan must answer: how control is
  taken and handed over, what happens when the holder's session drops, whether a manager may
  force-take, and what the FC does (or does not) need to know.
- **`?watch=1` becomes enforced,** not a URL hint that merely hides Start/Stop.
- **`AssignmentRole` PIC / OBSERVER** on the existing assignment record — a spotter or referee gets a
  read-only cockpit, map, marks and Wall without being handed arm/disarm. Explicitly *not* a fourth
  entry in `Role`, whose ordinal ordering `User#topRole()` depends on.
- **Real invitations** (review §O2): self-service password change first, then a one-time-token invite
  bounded by "≤ own scope", which U-SCOPE-PLAN already named as a goal.

Wave D writes **no product code and no tests.**

---

## 5b. Wave E — the synthetic dev group  *(proposal, not yet approved)*

> **Item (1) below is done, via a different mechanism than proposed — flag before picking this wave
> back up.** docs/plans/done/POSTGRES-ONLY-CONTEXT.md W1 (station/vision-app + storage/persistence,
> merged to `fix/postgres-only-auth`) independently found this exact bug and fixed it: `AuthSeedRunner`
> is **deleted** (not given a `GroupService` explicit-id parameter — Option A's outcome, a different
> route to it), and `storage/persistence`'s `V13__identity_baseline.sql` now seeds the root group at
> the well-known `DevPrincipal.GROUP_ID` directly via a Flyway migration (`ON CONFLICT (id) DO
> NOTHING`), which sidesteps Option B's rejected boot-ordering dependency for free — the seed is data,
> not something wiring waits on. **Items (2)–(4) are still open**: nothing in W1 reconciles a station
> whose Postgres already carries a stale random-id root group from a pre-W1 run (that row and its
> orphaned assets are simply left alongside the new fixed-id one — `ON CONFLICT` only prevents a
> *second* problem, it does not repair the first one), no regression test asserts "an asset created
> with auth off is visible to a MANAGER of root after auth is enabled" end-to-end, and
> `contexts/vision-identity/MODULE.md` was not touched (the fix lived entirely in the schema/seed
> layer, not identity's domain code). See storage/persistence/MODULE.md's and station/vision-app/MODULE.md's
> own "POSTGRES-ONLY-CONTEXT.md W1 done" sections for the full account.

Wave A surfaced this as a cosmetic annoyance: with auth off, the new "Who flies this?" step finds
zero pilot candidates. The cause is worse than the symptom.

### What is actually wrong

- `DevPrincipal.GROUP_ID` is the compile-time constant `UUID(0,1)`, and with `vision.auth.enabled=false`
  every request resolves to `DevPrincipal.OWNERSHIP`.
- `AuthSeedRunner` creates the real root group through `GroupService#create`, which assigns
  `GroupId.random()`.
- **Nothing reconciles the two.** So the dev principal claims membership of a group that does not
  exist in the group repository, while the three seeded users belong to a different, real one.

Two consequences, in increasing order of seriousness:

1. *(cosmetic, known)* `/api/auth/me` and `GET /api/users` disagree about the caller's group, so any
   UI that filters people by the caller's group finds nobody.
2. *(serious, previously unnoticed)* **every asset created while auth is off is owned by a group that
   does not exist.** `AssetController#create` stamps `currentUser.ownership()` onto the asset. The day
   an operator flips `vision.auth.enabled=true`, a MANAGER of the real root has scope
   `GROUPS({realRootId})`, and `includes(assetId, ownership)` tests `groups.contains(UUID(0,1))` →
   `false`. Every drone registered during the demo/dev life of that station becomes **invisible to
   every manager**, visible only to ADMIN. Silently, with no error and nothing in the log.

That is a data-visibility cliff at exactly the transition this whole plan exists to make safe.

### Options considered

| | Approach | Verdict |
|---|---|---|
| **A** | Seed the root group **with** the well-known `DevPrincipal.GROUP_ID` instead of a random id, so the dev principal's group is the real root | **Recommended.** Smallest change, fixes both consequences at the source, and makes the dev→secured transition a no-op. Group ids are not secrets and every scope check is server-side, so a predictable root id costs nothing |
| **B** | Resolve the dev principal's group from the seeded root at boot instead of from a constant | Rejected: `DevPrincipal.OWNERSHIP` is a `static final` consumed by wiring, so this trades a constant for a boot-ordering dependency between the seed runner and the wiring that needs its result |
| **C** | Leave the mismatch, and rewrite asset ownership when auth is switched on | Rejected: patches the data, leaves the two ids disagreeing, and needs a trigger point that does not exist |

### Proposed wave E

1. `AuthSeedRunner` creates the root group with the well-known id. This needs `GroupService` to
   accept an explicit id on the seeding path — a narrow addition, not a change to `create`'s normal
   contract.
2. **Reconcile stations that already seeded a random root**, since (1) alone does nothing for them
   and their dev-created assets stay orphaned: on boot, when a group with the well-known id does not
   exist but assets carry it as their ownership group, re-point those assets at the real root. One
   idempotent pass, logged at INFO with a count — never silent.
3. Tests: an asset created with auth off is visible to a MANAGER of root after auth is enabled —
   this is the regression that matters, and nothing asserts it today.
4. `MODULE.md` for `station/vision-app` and `contexts/vision-identity`.

Effort: S. **Not started — this section is a proposal awaiting a decision**, unlike waves A–D.

## 6. Out of scope here, and why

- **The Fly Detection panel** (review §U5) already has its own finished spec in
  docs/plans/done/CV-UX-RESEARCH.md, and its subject matter sits on the unmerged `feat/cv-demand`
  work this branch descends from. It is scheduled separately, not folded in here.
- **Reworking `VisibilityScope`'s shape.** §1 is deliberately a pure addition.
- **A fourth `Role`.** See wave D.

---

## §5c — A6 rev.2: one banner stack, measured (done)

**Reported:** *"This station is unsecured and other banners — overlaps the side panel and each other."*

The first revision read A6's *"must not shift the cockpit's video layout"* as *"must be out of flow"* and made the banner `position: fixed; inset: 0 0 auto 0; z-index: 130`. Out of flow is the right call and stays — an in-flow banner above `<router-outlet>` genuinely does push `.cockpit`/`.command-shell` below the fold, because both size themselves against the **viewport**, not against their parent, deliberately (see either file's own comment on why a percentage height cannot resolve down that chain).

What it got wrong is that out of flow means **nothing reserves space for it**, and every box that had been claiming the full viewport went on claiming it:

| Box | Anchored at | What the strip covered |
|---|---|---|
| `.sidebar` (docked) | grid row from y=0, `height: 100dvh` | the brand row |
| `.sidebar` (≤640px sheet) | `position: fixed; inset: 0 auto 0 0` | the sheet's own head |
| `.side-panel` (drawer) | `position: fixed; top: 0` | the drawer title and its close button |
| `.offline-banner` | in flow, first child of `<main>` | itself — two strips, same pixels |
| `.cockpit` / `.command-shell` | `height: 100dvh` | nothing, but each ran one strip past the fold |

### The fix, in two halves — both required

1. **One stack, not one strip per banner.** `app.html` renders a single `.shell-banners` flex column; every station-wide notice is a `.shell-banner` inside it. A second banner now pushes the first up. Two independently-`fixed` strips at the same inset can only ever overlap, so this is structural, not cosmetic.
2. **A measured height.** `App` observes the stack and publishes `--shell-banner-h` on `<html>`; `--shell-h: calc(100dvh - var(--shell-banner-h))` (`styles.css`) replaces every bare `100dvh` in the app, and the fixed boxes above offset their `top`/`inset` by the same token. Measured rather than a constant because the copy wraps to two lines below ~520px, and a wrong constant fails in both directions — a gap when absent, an overlap when wrapped.

`--shell-banner-h` is `0px` whenever no banner shows, which is the normal case for a secured, reachable station: both terms collapse and the layout is byte-for-byte what it was before A6 existed.

**Verified:** `station/vision-web` 2027/2027 (2024 before), including three new regressions — both banners land in one flex-column stack in severity order, no banner renders inside `<main>`, and the token is actually written.
