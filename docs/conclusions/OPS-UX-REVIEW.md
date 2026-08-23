# OPS-UX-REVIEW — architecture, UI flow and onboarding, judged by pilot / manager / crew

Status: **review for decision** (2026-08-16). Scope: the three things a field deployment lives or
dies by — who may do what (architecture), what a person sees when they open the app (UI flow), and
how a person or an aircraft gets into the system in the first place (onboarding). Every claim below
cites the file actually read. No product code changed by this task.

Prior art this builds on rather than repeats: docs/main/UX-DESIGN.md (the product promise),
docs/conclusions/UX-SIMPLIFY-REVIEW.md (nav overwhelm — F1/F2/F3 largely shipped via
NAV-IA-REDESIGN), docs/conclusions/FEATURE-MATRIX.md (feature value/effort), and
docs/plans/done/CV-UX-RESEARCH.md (the Detection panel).

---

## 0. The short version

The platform's *structure* is in good shape: hexagonal, ArchUnit-enforced, eight bounded contexts
over a measured DAG, ports with several real implementations each. Nothing below asks to redraw it.

What is missing is not structure but **operational identity**: the system knows *what you may see*
and has no opinion on *what you may do*, *who is flying right now*, or *who you belong to*. Three
consequences, one per persona:

| Persona | What breaks today |
|---|---|
| **Pilot** | Lands in a cockpit that may be empty with a message that is factually wrong; can delete the airframe they were assigned to; can add a source and then lose sight of it |
| **Manager** | Lands in a single-drone cockpit instead of the fleet; has no accountability view even though the API and audit trail are built; must relay passwords by hand |
| **Crew / referee** | Does not exist — no role, no scope, no surface. A spotter must be given a PILOT account, which hands them arm/disarm/RTH |

The fixes are mostly small and mostly already unblocked by U-e (auth) having shipped.

---

## 1. Architecture

### A1 — `VisibilityScope` is a visibility type doing an authorization type's job  *(structural, highest value)*

`core/vision-platform/.../VisibilityScope.java` is a clean, well-tested read filter: `UNBOUNDED` /
`GROUPS(subtree)` / `ASSIGNED_ASSETS`, resolved once per request by
`DefaultScopeResolver#scopeFor`. As a *read* model it is exactly right.

It is then used as the *authority* model, and two things fall out of that:

1. **Destructive management is gated on visibility, not on role.** `AssetController#update`,
   `#setState`, `#delete`, and device assign/unassign guard with `requireInScope(assetId)`
   (`AssetController.java:273`) — which is `assetService.details(scope, id)`, i.e. "can you see it".
   A PILOT's scope *is* their assigned assets. So the pilot assigned to an airframe may rename it,
   deactivate it, soft-delete it, and unassign its camera. That is not a bug in the guard; it is the
   guard answering a question it was never asked.
2. **`canManageOrg()` flattens MANAGER and ADMIN**, because it is `kind == UNBOUNDED || kind ==
   GROUPS` (`VisibilityScope.java`). Every consumer of that gate therefore treats "manager of one
   small team" as "administrator of the deployment". Two of those consumers have a
   **deployment-global blast radius**: `DefaultModelRegistryService#promote` (line 74) swaps the
   live CV model *for every stream and every group*, and `DefaultTrainingJobService#start`
   (line 187) claims the single inference/training host. `DefaultDatasetService#create/#delete` is
   the same shape.

**Recommendation.** Keep `VisibilityScope` as the read filter, unchanged — it is good. Add the
missing half beside it in `vision-platform`: the acting user's **authority**, carried from the API
edge exactly the way scope already is (`CurrentUser#scope()` gains a peer, not a redesign). Then:

- asset lifecycle writes (rename / deactivate / delete / device (un)assign) require MANAGER over the
  asset's group — a pilot flies an airframe, they do not administer it;
- deployment-global actions (model promote, training start) require ADMIN;
- reads stay exactly as they are.

This is the prerequisite for §A2 and §A3, and it is the only item here that touches the domain.

### A2 — Nobody owns control of an aircraft

`?watch=1` on `/fly/:assetId` hides Start/Stop in the SPA (`cockpit.ts:114-115`) and is otherwise
cosmetic: two people can open the same cockpit URL without it and both get arm, disarm, mode change
and return-home. `FlightCommandController` checks scope, never occupancy.

The domain already has the right home for this: `AssetUsage` is opened when the asset starts
streaming and closed on stop — a flight session with a start, an end and telemetry. It needs one
field (who holds control) and one transition (hand over). FEATURE-MATRIX filed this as Tier 3
"Control/watch handoff, L, post U-e"; **U-e has shipped, so it is unblocked now**, and the estimate
was made before the session object existed.

Until then, a two-person crew has no way to answer "am I flying, or are you?" except by talking.

### A3 — There is no crew, only pilots

`Role` is PILOT / MANAGER / ADMIN (`identity/domain/model/Role.java`). Assignment is a flat set of
pilots per asset (`AssignmentController`, `roster-logic.ts`). Yet "crew/referee" is a first-class
persona in CYCLES-PLAN.md, FEATURE-MATRIX.md, VISUAL-REFRESH-PLAN.md and TACTICAL-MARKS-PLAN.md.

Today the only way to give a spotter, a referee or a ground crew member a screen is to make them a
PILOT and assign them — which grants arm/disarm/RTH on a live aircraft.

**Recommendation — the cheap version, not a new role tree.** Put a role on the *assignment*, not on
the user: **PIC** (pilot in command) and **OBSERVER**. An observer gets the cockpit read-only — which
is the screen `?watch=1` already renders — plus map, marks and Wall. This single field turns
`?watch=1` from a URL hint into an enforced posture, and combined with §A2 it gives the crew story
its whole shape for a fraction of a new role hierarchy's cost.

### A4 — Registering an asset never connects it to a person

`POST /api/assets` sets `Ownership` from the creator (`AssetController.java:107`) and creates **no
assignment**. Because a PILOT's scope is `ASSIGNED_ASSETS`, and because `Add source` is deliberately
ungated for every role (`nav-entries.ts` doc comment: "a plain PILOT sees just two Manage entries
(Assets, Add source)"), the flow closes on itself:

> A pilot completes the four-step wizard, sees the test frame, presses Save — and the asset vanishes
> from their list, because they are not assigned to the thing they just registered.

For a manager it is merely a missing step (register here, then go to Roster). For a pilot it is a
dead end that reads as data loss.

### A5 — What is genuinely right, for the record

Contexts as Maven modules over a measured DAG with `vision-warehouse` as the pure leaf; ports with
multiple real adapters behind them; scope-aware reads that 404 rather than 403 so existence never
leaks; audit-trail decoration already present across warehouse, flight, learning and identity
services; `AuditEntry` recorded on *denied* actions too, not only successful ones. None of this
needs work — it is why the fixes above are small.

---

## 2. UI flow

### U1 — Every role lands in the cockpit

`app.routes.ts:51` — `{ path: '', redirectTo: 'fly' }`, unconditionally. The cockpit is the right
landing page for a pilot and the wrong one for a manager, whose job is the fleet and whose page
(`/command`) is already built and good. Role-based landing (ADMIN/MANAGER → `/command`,
PILOT → `/fly`) is a few lines against `AuthStore#user()?.topRole`, which the sidebar already reads.

### U2 — The pilot's empty state states a falsehood

`drone-picker.html:29` — **"No drones registered yet"**, with the sub-line "Add a source from the
Devices tab" and a CTA to `/devices?addSource=1`. Three problems in one card:

- For an unassigned pilot the fleet may be full; what is empty is *their assignment list*. The honest
  message is "No aircraft assigned to you yet" plus who to ask — resolvable from their group
  memberships, which `/api/auth/me` already returns.
- "the Devices tab" no longer exists for a pilot: Devices is `managerOnly` and in the `advanced`
  group (`nav-entries.ts`).
- The CTA leads to `/add-source`, which for a pilot produces §A4's dead end.

This is the first screen a new pilot ever sees, and all three of its sentences are wrong for them.

### U3 — The manager's accountability surface is built and unreachable

`GET /api/audit` exists, is gated on `canManageOrg` (`AuditController.java:66-70`), and is fed by
audit decoration across every context. **No page in the SPA calls it** — `grep` over
`core/api/vision-api.ts` finds no `/api/audit` client at all. What the UI does offer is `/activity`,
described in its own nav entry as "Your own recent actions across the fleet" — the least useful
slice for the persona who needs "who armed that aircraft, who promoted that model, who deleted that
asset".

This is the cheapest high-value item in the whole review: the backend is done.

### U4 — Nav honesty for the pilot

Of the five Operate entries, one is a `soon` scaffold; five `ComingSoon` routes exist overall. This
was a deliberate, honest choice (NAV-IA-REDESIGN F8 — better than a dead end that looks like a bug),
and it should stay for ADMIN as a visible roadmap. But `navTiers()` already separates `upcoming` from
`primary`, so hiding `badge: 'soon'` entries from PILOT is a filter change, not a redesign: an
operator's menu should contain only things that work.

### U5 — The cockpit's Detection panel  *(already diagnosed, not yet built)*

docs/plans/done/CV-UX-RESEARCH.md is a finished design: 15 controls of which an operator can
answer 6, three honesty defects (the class filter silently drops detections from alerts and
recording, not just from the screen; the fps slider's label predates the rate controller; the
primary on/off act is last in the scroll). Waves U1–U5 are pure frontend. It remains the
highest-value *pilot-facing* UI work already specified — worth scheduling rather than re-researching.

---

## 3. Onboarding

The word covers two flows here. One is genuinely good; the other barely exists.

### O1 — Onboarding an aircraft: good, one step short

The four-step wizard (`features/onboarding/`: profile → connect → test → create) does the thing
UX-DESIGN §5.1 calls non-negotiable — probes the device and refuses to save what cannot produce a
frame (`canAdvanceFromTest`) — and offers register / discover / simulate / listen / drone paths, so
the product is demonstrable with no hardware. UX-SIMPLIFY-REVIEW already marked this flow ✅.

The one gap is §A4: it ends at "the asset exists" instead of "the asset is flyable by someone".
**Add a fifth question — "Who flies this?" — defaulted to the creator.** That single step closes the
pilot dead-end, removes the manager's remember-to-go-to-Roster step, and makes the roster's existing
`countAssetsWithoutPilot` metric trend to zero by construction.

### O2 — Onboarding a person: an admin typing a password into a form

`org-settings.html` heads the panel **"Invite a user"**; the mechanism (`org-settings-facade.ts`)
is `POST` username + displayName + email + **a password the admin chooses**. Nothing is sent to the
invitee. There is no password change, no reset, no first-login "set your own password". For a crew
that rotates, this means credentials travel by voice or chat and are never rotated.

The honest minimum, in order of cost: rename the panel to what it does; add self-service password
change; then a real invite (one-time token link, invitee sets their own password) — which also gives
"invite ≤ own scope", already named as a goal in U-SCOPE-PLAN §"Invite / grant ≤-own-scope".

### O3 — First run is unsecured by default, and says so only in a log line

`vision.auth.enabled: false` is the default (`application.yaml:55`), and `AuthSeedRunner` seeds
`admin/admin`, `manager/manager`, `pilot/pilot` regardless of the flag. With auth off, every caller
is the auto-admin dev principal — so the full ~20-entry console is what every demo, every dev install
and every forgotten deployment shows, and `managerOnly` never hides anything. The only warning is a
`log.warn` at boot.

This is a defensible development default and an indefensible silent one. UX-DESIGN §7.2 already
states the rule this violates: *honest status over optimistic status*. **Show a persistent banner in
the UI while `authEnabled` is false or a seeded dev credential still exists** — "This station is
unsecured: anyone on this network is an administrator." `/api/auth/me` already returns
`authEnabled`, so the SPA has the fact in hand.

### O4 — Nobody is guided from empty station to first flight

Every piece exists — create group, create users, add source, assign pilot — as four separate pages
under Manage, in no stated order. There is no first-run path connecting them, which is the one thing
UX-DESIGN §6 explicitly asked for ("the ten minutes that decide adoption").

A **"Set up this station" checklist card on `/command`**, shown to ADMIN while the station is fresh
(only seeded users exist, or zero assets), with four ticking rows linking to the pages that already
work, is a self-dismissing feature: it disappears the moment the station is real.

---

## 4. Recommended sequence

Ordered by value-per-effort, and each wave independently shippable.

| # | Wave | Items | Personas | Cost |
|---|---|---|---|---|
| **1** | **Honesty & dead ends** | U1 role-based landing · U2 truthful pilot empty state + `/add-source` CTA · O1 "Who flies this?" wizard step + auto-assign · O3 unsecured-station banner · U4 hide `soon` from pilots | P, M | S — mostly frontend, one small backend call |
| **2** | **The manager's missing half** | U3 audit page over the built `GET /api/audit` · O4 "Set up this station" checklist · Roster attention rows (assets with no pilot / pilots with no asset — the logic exists) | M | S–M, frontend only |
| **3** | **Authority ≠ visibility** | A1 — authority beside scope at the API edge; asset lifecycle writes require MANAGER; model promote and training start require ADMIN | all | M — the one domain change; prerequisite for wave 4 |
| **4** | **Control ownership & crew** | A2 control holder on the flight session + explicit handover · A3 `AssignmentRole` PIC/OBSERVER, making `?watch=1` enforced | P, C | M–L |
| **5** | **Cockpit simplification** | Build CV-UX-RESEARCH waves U1–U5 as specified | P | M, frontend only |
| **6** | **Real invitations** | O2 — password change, then token-based invite ≤ own scope | M, C | M |

Wave 1 alone removes every factually-wrong screen a new pilot meets. Wave 3 is the only item that
touches the domain, and waves 4 and 6 both depend on it — so it is the one to schedule deliberately
rather than opportunistically.

## 5. Deliberately not proposed

- **A separate CREW role in `Role`.** The assignment-role approach (§A3) covers the persona at a
  fraction of the cost and does not disturb `topRole` ordering, which `User#topRole()` depends on.
- **Reworking `VisibilityScope`.** It is a good read filter; the finding is that it was asked a
  second question, not that it answers its own one badly.
- **Another IA pass.** NAV-IA-REDESIGN closed UX-SIMPLIFY-REVIEW's F1–F3; the remaining nav issues
  here are role-scoping and copy, not structure.
