# PLATFORM-AUDIT-UI — asset / person / role journeys in vision-web

Status: **audit report, read-only** (2026-08-21). Lane A of `docs/plans/active/PLATFORM-AUDIT-CONTEXT.md`.
No product code was changed to produce this document. Method: read `app.routes.ts`, every
`features/*/*.routes.ts`, `nav-entries.ts`, guards, facades and templates directly; cross-checked
claims in `docs/plans/active/{IA-TRUTH,OPS-UX,CREW-CONTROL}-PLAN.md` and
`docs/plans/done/{UI-REDESIGN,NAV-IA-REDESIGN}-PLAN.md` against the current tree (several of those
docs are stale in the *optimistic* direction — more has shipped than they credit — and in one
important case stale in the *dangerous* direction, see Defect 1). Where a claim mattered for safety
(the authority-vs-nav question), the Java source (`contexts/vision-learning`) was read directly
rather than trusting either doc or the frontend's own comments.

---

## 1. Summary

| Journey | Verdict | One line |
|---|---|---|
| **Asset lifecycle** (discover → create → categorize → attach → assign → preflight → fly → usage → retire) | 🟡 **AMBER** | Essentially complete and mostly self-contained (no id hand-copying anywhere); one confirmed authority-mismatch defect (category field), one stale nav label, one unresolved owner-id fact, and one drifted confirm-vs-immediate archive posture |
| **Person lifecycle** (create → group/role → assign assets → activity → suspend) | 🟡 **AMBER** | Fuller than the planning docs admit — create, enable/disable, roster-by-pilot, and attention counters are all built, and MANAGER is correctly scoped server-side to their own subtree. But group edits are create-only (no rename/delete/re-parent), there's no hard delete for a user, and one deliberate design tension (role picker shows ADMIN to a MANAGER) plus one missing deep link |
| **Role journeys** (ADMIN / MANAGER / PILOT nav + page access) | 🔴 **RED** | A MANAGER following the *ordinary, nav-shown, intended* path to two features (CV model promote, start training) hits a deterministic 403 — the backend's own authority tightening (OPS-UX wave C) was never mirrored in the frontend gate. Separately, only 3 of ~10 `managerOnly` routes have a server-equivalent route guard; the rest rely on nav-hiding alone |

---

## 2. Defect list

Ordered by operator pain, not by discovery order.

### Defect 1 — MANAGER-visible "Promote"/"Start training" buttons now always 403 (BLOCKER)

**What the user experiences:** A MANAGER (not just ADMIN) sees "CV model registry" and "CV training"
in their Manage nav (`nav-entries.ts:284-291`, `:278-283`, both `managerOnly: true`, no `badge`).
They open the model registry, see an enabled **Promote** button on a candidate model, click it, and
get a 403 toast — every time, for every MANAGER, with no error text that explains *why* beyond a
generic HTTP failure. Same for "Start training" on a labeled dataset.

**Root cause, precisely:** OPS-UX wave C (merged, verified green) deliberately tightened
`ModelRegistryService#promote` and `TrainingJobService#start` from `canManageOrg()` (ADMIN or
MANAGER) to `canAdminister()` (ADMIN only) — confirmed directly in the enforcing implementation:

```java
// contexts/vision-learning/.../DefaultModelRegistryService.java:76
if (!scope.canAdminister()) { … }   // ADMIN only
```

```java
// contexts/vision-learning/.../DefaultTrainingJobService.java:189
if (!scope.canAdminister()) { … }   // ADMIN only
```

The frontend was never updated to match. Both facades still gate their button on the *old* rule:

- `station/vision-web/src/app/features/models/models-facade.ts:71` — `canManage = computed(() =>
  canManageOrg(this.auth.user()?.topRole))`, consumed by `canPromote()` at `:100-101` and rendered
  at `models.html:69`.
- `station/vision-web/src/app/features/labeling/dataset-detail-facade.ts:77` — the identical
  `canManageOrg`-based gate for "Start training".

Both facades' own doc comments assert "Role-gating mirrors the backend exactly" — that was true when
written and has silently gone false. (The Java interface-level Javadoc,
`ModelRegistryService.java:21` and `TrainingJobService.java:19`, is *also* stale — still describing
`canManageOrg()` — which is almost certainly how the frontend's comment came to cite the wrong
predicate: it was written against the interface doc, not the enforcing `Default*` class.)

**Smallest fix:** add a `canAdminister(topRole)` predicate to `core/org/org-logic.ts` (mirrors
`VisibilityScope#canAdminister()` — `topRole === 'ADMIN'`, dev-off parity unchanged since the dev
principal's `topRole` is the literal `'ADMIN'`), and swap it in at `models-facade.ts:71` and
`dataset-detail-facade.ts:77`. Two one-line changes. Leave the nav entries `managerOnly` (a MANAGER
should still *see* the registry/training pages — reading them is fine) — only the action-level gate
needs to change; refresh the stale Java interface Javadoc while touching the area.

---

### Defect 2 — the add-source wizard has no route guard; a PILOT can complete five of six steps before failing (MAJOR)

**What the user experiences:** `/add-source` carries `managerOnly: true` in the nav
(`nav-entries.ts:251-259`), so a PILOT doesn't see the tile — but `features/onboarding/onboarding.routes.ts`
has no `canActivate` at all, and nothing in `onboarding-store.ts`/`onboarding-facade.ts` reads the
caller's role. A PILOT who has the URL (an old link, a bookmark, a shared screenshot) reaches the
full wizard: profile → connect → **test** (a real device probe/decoded frame) → verify → and only at
**Create** does `POST /api/assets` 403 (`canManageOrg()`, OPS-UX wave C2). They can spend real time
scanning the network or testing an RTSP connection before the failure.

**Compare:** `/manage/roster`, `/monitor/audit`, and `/manage/geo/regions` all do this correctly —
each carries `canActivate: [orgGuard]` (`roster.routes.ts:15`, `audit.routes.ts:16`,
`geo.routes.ts:16`), so a PILOT is redirected to `/fly` before the page ever renders, exactly
matching `nav-entries.ts`'s `managerOnly` on the same entries.

**Smallest fix:** add `canActivate: [orgGuard]` to `onboarding.routes.ts`, one line, same pattern as
the three routes above.

### Defect 3 — `/devices` has no route guard *and* no button-level gating either (MAJOR)

**What the user experiences:** `/devices` is `managerOnly` + `group: 'advanced'` in nav
(`nav-entries.ts:349-356`) but `devices.routes.ts` has no guard, and unlike `models`/`labeling`
(which at least gate their one destructive action), nothing in `devices-facade.ts` or `devices.html`
checks role at all. A PILOT who reaches it by URL sees a fully live table: Start/Stop stream
(`devices.html:161,353`), Archive (kebab menu), and **Create asset from this device**
(`devices.html:442`, `canSubmitCreateAsset()`) — the last of which 403s at the same
`POST /api/assets` gate as Defect 2, with no warning before the click.

**Smallest fix:** add `canActivate: [orgGuard]` to `devices.routes.ts`. This is the single cheapest,
highest-leverage fix in this report — the page is already fully built and useful to a manager; it
just needs the one-line guard `roster`/`audit`/`geo` already prove out.

### Defect 4 — one edit form lets a PILOT touch a field they can never save (MAJOR)

**What the user experiences:** Every asset's "Rename asset…" button (`asset-detail.html:34-36`, no
role check) opens one combined form with **both** the display-name input and the category-slug input
(`asset-detail.html:63-80`) — no visual or functional distinction between them. Per OPS-UX wave C
(§1 table), `displayName`/attributes need only visibility (any assigned pilot may change them);
`category` needs `canManage(ownership)`, which a PILOT's `ASSIGNED_ASSETS` scope never satisfies. But
`confirmEditAsset()` → `saveAssetEdit()` (`asset-detail-facade.ts:382-401`) sends both fields in **one**
`updateAsset` request. A pilot who is simply renaming their drone and also (out of curiosity, or
muscle memory) edits the category slug gets the *whole* request rejected — losing the rename they
were entitled to make, with only a generic HTTP-error toast (`fleet-store.ts:499-507`,
`describeHttpError`) to explain why. `asset-detail-facade.ts:95` already has the exact predicate
needed (`canManagePilots = computed(() => canManageOrg(...))`) — it just isn't applied to this form.

**Smallest fix:** disable (or hide) the category input in the same form unless `canManageOrg(topRole)`
— reusing `asset-detail-facade.ts:95`'s existing computed, one `[disabled]`/`@if` binding in
`asset-detail.html:70-75`.

### Defect 5 — the role picker in "Create a user" always offers ADMIN, even to a MANAGER who can never grant it (MINOR–MAJOR, deliberate tradeoff)

**What the user experiences:** `org-settings-facade.ts:40` (`roleOptions()`) returns
`['PILOT','MANAGER','ADMIN']` unconditionally — `core/org/org-logic.ts:40-41`'s own comment states
this is deliberate ("the UI never silently pretends a role doesn't exist… lets the backend be the
single authority"). A MANAGER filling the whole create-user form, picking ADMIN, gets a 403 only on
submit. This is graceful (the form's other fields are **not** cleared on failure — `submitUser()`
only resets on success, `org-settings-facade.ts:88-105` — so a retry is one dropdown change away) but
it is still the "commit, then fail" pattern named in the audit brief, just with a soft landing.
**Recommendation, not a clear bug:** cap the offered list to the caller's own `topRole` and below,
mirroring the backend's `maxGrantableRole` ceiling — flagged for the owner's judgment, since the
current choice is intentional and documented, not an oversight.

### Defect 6 — no per-person deep link from Roster into the Audit trail (MINOR)

**What the user experiences:** `features/audit/**` supports filtering by actor
(`audit-facade.ts:37,48-55`, `audit.html:14-24`) and `features/roster/**` has a "By pilot" pivot
(`roster.html:29-37`) — but nothing links the two. To answer "what has this specific pilot done", a
manager must open Audit trail separately and pick the name from a dropdown; roster doesn't pass an
`?actor=` (or equivalent) through. **Smallest fix:** a query param on `/monitor/audit` plus a link
from each roster pilot row; small, backend-free.

### Defect 7 — "Pre-flight checklist" nav entry doesn't lead to a checklist (MINOR, honestly disclosed)

**What the user experiences:** The Operate nav entry "Pre-flight checklist" → `/operate/preflight`
renders a page titled **"Fleet readiness"** (`preflight.html:2-8`) — a Go/No-go table across the
whole fleet, not a checklist. The actual interactive checklist
(`shared/ui/preflight-checklist.*`, moved there by IA-TRUTH wave U2) only renders inside the cockpit.
The page is honest about this ("the live cockpit checklist stay on Fly" — `preflight.html:6`), so
this is not a lie, just a stale/misleading **nav label** — the page itself has evolved past what
`nav-entries.ts:170-171`'s description still says ("The live status card for any drone" — it's not
per-drone, it's fleet-wide). This is the exact regression `IA-TRUTH-PLAN.md` was written to
eliminate, recurring because a later wave (`DRONE-ONBOARDING-PLAN.md` §8.1, O6) rebuilt the page
without revisiting the nav copy pointing at it — verified directly against `preflight.ts`'s own doc
comment, which names the O6 rebuild and confirms the old single-drone card is gone from this route
entirely. **Smallest fix:** rename the nav entry to match the page's own title ("Fleet readiness"),
or split: keep "Pre-flight checklist" pointing at the cockpit's own checklist concept and rename this
page's tile.

### Defect 8 — the asset "home" page's own Owner fact is an unresolved UUID (MINOR)

**What the user experiences:** `asset-detail.html:517` renders `<dt>Owner</dt><dd class="mono">{{
a.owner }}</dd>` — literally the raw group/user id, e.g. `a1b2c3d4-...`, never a display name. This
is the direct answer to "who owns this asset" the audit brief specifically asks after, and on the one
page that is supposed to be the asset's single "home" (§5), it's unreadable without a second lookup.
**Root cause, precisely:** `station/vision-api/.../dto/AssetSummaryResponse.java:17-18,25` documents
this as a known, named gap in its own Javadoc — *"until the identity phase, this will just be a raw
id"* — and stamps the raw UUID at `:58` (`summary.asset().ownership().ownerId().value().toString()`).
The identity phase (`contexts/vision-identity`) has existed and been wired for a long time; the
resolution step was simply never done, and the comment was never revisited. **Smallest fix:** either
join the owner id to a display name server-side in `AssetSummaryResponse` (identity already exposes
this via `UserService`/`GroupService`), or resolve it client-side the same way
`org-settings-facade.ts`'s `groupName()` helper already resolves a group id to a name elsewhere in
the same app — the pattern already exists, it just wasn't reused here.

### Defect 9 — the same destructive action (Archive) has two different safety postures depending which page you're on (MINOR)

**What the user experiences:** Archiving an asset from the `/assets` list page's kebab menu fires
**immediately**, no confirmation — by explicit design: `assets-facade.ts:206-207`'s own doc comment
states *"Archive executes immediately, no confirm dialog (`UX-REWORK-PLAN.md` §U-a2 item 3b — 'Undo
over confirm')."* Archiving the same asset from `asset-detail`'s header kebab menu instead **requires
a confirm dialog first** — `asset-detail-facade.ts:404-409`'s own doc comment openly names this as *"a
departure from this method's original 'Undo over confirm, fires immediately' design… now that Archive
lives behind a kebab rather than a plain header button."* Both keep the Undo toast, so neither is
unsafe, but the same action, behind the same kind of menu (a kebab, not a plain header button — the
stated reason for the departure applies equally to both), asks for confirmation on one page and not
the other, with no documented reason the two pages need different postures. **Smallest fix:** pick
one policy and apply it in both places, or record why list-row archiving genuinely warrants a
different bar than detail-page archiving (e.g. one-at-a-time focus vs. scanning a table) — today the
difference reads as drift, not a decision.

---

## 3. Role × page matrix

Legend: 👁 = visible in that role's sidebar. 🚫 = hidden from nav. **Guard** = a real `canActivate`
route guard exists (redirects before render) vs. **nav-only** (page renders fully for any
authenticated role; safety depends entirely on individual actions' own 403s, if any).

| Route | Mode | ADMIN | MANAGER | PILOT | Guard | Verdict |
|---|---|---|---|---|---|---|
| `/fly`, `/fly/:assetId` | Operate | 👁 | 👁 | 👁 | n/a (all roles) | safe |
| `/wall` | Operate | 👁 | 👁 | 👁 | n/a | safe |
| `/operate/preflight` | Operate | 👁 | 👁 | 👁 | n/a | safe (see Defect 7) |
| `/settings/detection` | Operate | 👁 | 👁 | 👁 | n/a (deliberate, IA-TRUTH U1.5) | safe |
| `/operate/missions` | Operate (scaffold) | 👁 | 👁 | 👁 | n/a | safe — `ComingSoon` |
| `/command` | Monitor | 👁 | 👁 | 👁 | n/a | safe |
| `/activity` | Monitor | 👁 | 👁 | 👁 | n/a | safe (self-scoped) |
| `/monitor/audit` | Monitor | 👁 | 👁 | 🚫 | **`orgGuard`** (`audit.routes.ts:16`) | **safe** |
| `/monitor/alerts` | Monitor | 👁 | 👁 | 👁 | n/a | safe (read-only feed) |
| `/replay`, `/assets/:id/replay/:usageId` | Monitor | 👁 | 👁 | 👁 | n/a | safe |
| `/monitor/layouts` | Monitor (scaffold) | 👁 | 👁 | 👁 | n/a | safe — `ComingSoon` |
| `/assets`, `/assets/:assetId`, `/assets/:assetId/readiness` | Manage | 👁 | 👁 | 👁 | n/a | safe reads; see Defect 4 for one write |
| `/add-source` | Manage | 👁 | 👁 | 🚫 | **none** | **Defect 2** |
| `/manage/roster` | Manage | 👁 | 👁 | 🚫 | **`orgGuard`** | **safe** |
| `/manage/categories` | Manage | 👁 | 👁 | 🚫 | none | low risk today — create/edit is scaffold-only |
| `/manage/training` (+ `:datasetId`, `:datasetId/samples/:sampleId`) | Manage | 👁 | 👁 | 🚫 | none (**by design** — dataset reads are visibility-scoped, per `geo.routes.ts:12`'s own comment) | **Defect 1** on Start-training |
| `/manage/training/models` | Manage | 👁 | 👁 | 🚫 | none | **Defect 1** on Promote |
| `/manage/training/jobs/:jobId` | (unlisted, drill-in) | 👁 | 👁 | 👁 | none | safe (read-only progress) |
| `/manage/geo/regions` | Manage | 👁 | 👁 | 🚫 | **`orgGuard`** | **safe** |
| `/manage/firmware`, `/manage/health` | Manage (scaffold) | 👁 | 👁 | 🚫 | none | safe — `ComingSoon` |
| `/manage/reports` | Manage | 👁 | 👁 | 🚫 | none | low risk — read-only, export is scaffold |
| `/manage/system` | Manage | 👁 | 👁 | 👁 (deliberate) | n/a | safe |
| `/devices` | Manage | 👁 | 👁 | 🚫 | **none** | **Defect 3** |
| `/debug` | Manage | 👁 | 👁 | 🚫 | none | minor — exploratory console, not a commit-then-fail flow, but needless exposure |
| `/org` | (profile menu) | 👁 | 👁 | 🚫 | **`orgGuard`** | **safe** |
| `/live/:deviceId` | (drill-in only, correctly no top nav entry) | 👁 | 👁 | 👁 | n/a | safe |
| `/login`, `**` (not-found) | outside guard / catch-all | — | — | — | n/a | safe |

**Pattern:** every route with a real route guard (`roster`, `audit`, `geo/regions`, `org`) is safe by
construction. Every `managerOnly` route *without* one is safe only if its write actions happen to
also check role client-side (models/labeling do, but against the wrong predicate — Defect 1) or have
no live write yet (categories, reports, firmware/health scaffolds). `add-source` and `devices` are
the two with neither a route guard nor action-level gating — Defects 2 and 3.

---

## 4. What is missing entirely

- **A crew/observer seat.** Confirmed precisely: `?watch=1` (`features/fly/cockpit.ts:122-123`,
  `fly-logic.ts:76`, `isWatchMode`) hides Start/Stop and the flight-command/CV-setup drawers
  (`cockpit.html:80,258,410,419,457,508`) — a **client-side layout hint only**. No server concept of
  an observer exists (`CREW-CONTROL-PLAN.md` is spec-only; no `AssignmentRole`, no
  `ControlClaimService`, no `/control` endpoint anywhere in `core/api/`). Anyone assigned to an
  asset can open the same cockpit without the query param and command it — nothing stops two
  assigned pilots commanding concurrently today. There is also **no UI affordance to construct or
  share a watch link** — no "Copy spotter link" button anywhere in the cockpit; a crew member can
  only get watch mode if someone hand-types `?watch=1` onto the URL for them.
  **Smallest honest version, frontend-only:** a "Copy watch-only link" action on the cockpit's own
  tool-rail (plain `navigator.clipboard` + URL construction, the same pattern `onboarding-facade.ts#copyBlock`
  already uses) so a PIC can hand a spotter a link that opens *read-only by default*. Stated plainly:
  this **only helps an honest crew member avoid touching controls by accident** — it protects
  against nothing adversarial, since any recipient can strip the query param and get full command
  authority with the same account. Real enforcement is `CREW-CONTROL-PLAN.md`'s job, not this.
- **Editable pre-flight checklist templates**, **flight-plan/mission upload**, **saved Wall
  layouts**, **firmware inventory**, **maintenance/health records**, **category create/edit**, and
  **exportable reports** — all honestly labelled `ComingSoon`/"…are coming" scaffolds, not silent
  gaps. Not re-litigated here; `docs/plans/done/UI-REDESIGN-PLAN.md`'s Additions table already
  names each one's backend follow-up.
- **Real invitations, self-service password change, per-asset control-claim/handover** — all
  `CREW-CONTROL-PLAN.md` spec, none built. "Create a user" (renamed from "Invite a user" per OPS-UX
  B4, confirmed at `org-settings.html:36`) is the only onboarding path; there is no email/token
  invite flow anywhere in the tree today.
- **Group hierarchy is append-only from the UI.** `org-settings-facade.ts` has exactly one group
  mutation method — `submitGroup()` (`:119-133`) → `this.org.createGroup(...)`. There is no
  rename, delete, or re-parent anywhere in the facade or template. A group created with the wrong
  name or under the wrong parent stays that way forever from the UI (a fresh row must be created
  instead). Likely fine for a slow-changing org tree, but worth naming since "manage your org's
  structure" is part of the person-lifecycle ask and this is two-thirds of it (create, not
  fix-a-mistake).
- **Orphan routed pages:** none found. Every suspect checked (`/live/:deviceId`,
  `features/camera-geo/**`, `features/geo/**`'s divergence chip, `features/demo/**`,
  `/manage/training/jobs/:jobId`) is either a legitimate drill-in reachable from Wall/Assets/
  Asset-detail/Replay/Devices/Alerts/CV-training, or (camera-geo, demo) not routed at all — embedded
  directly in asset-detail/the sidebar shell, correctly. `route-audit-logic.ts` +
  `app.routes.spec.ts:41-53` already regression-test that every `NAV_MODES` entry resolves, reading
  `NAV_MODES` directly (drift-proof, not a hand-maintained parallel list).
- **Empty/loading/error triad:** consistently well-built, not a gap. Seven sampled features
  (preflight, roster, audit, categories, reports, alerts, org-settings) all follow the same
  `@if (loading) … @else if (error) { <vision-empty title="Couldn't load …" [message]="error"> } @else if (empty) { <vision-empty title="No … yet" … /> }`
  idiom via the shared `vision-empty` component — this is a real strength worth preserving as new
  pages are added, not something the audit needed to flag as broken.

---

## 5. Answers to the questions asked

- **"Is there a single asset home that answers what/who/ready/last-flew?"** Yes —
  `asset-detail.html`'s overview (`grid12 overview-grid`, `:100+`) composes identity, category/
  lifecycle chips, KPI/utilization, recent flights, position, and a live-status card in one
  multi-column view (per `UI-REDESIGN-PLAN.md §D-F`'s design), with drill-ins (not a single stacked
  column) for usage history and hardware. It genuinely shipped past what `UI-REDESIGN-PLAN.md`
  described as a draft. One caveat: the "who" half of that answer is a raw UUID today, not a name
  (Defect 8) — the page answers the question, but not legibly.
- **"Is the live view actually capability-driven, or one generic view for camera and drone?"**
  Actually capability-driven — `live-facade.ts:118`'s `hasTelemetry` and the cockpit's real
  `GET .../flight-capabilities` call (`cockpit-facade.ts:313-320,881-906`) gate telemetry/OSD/
  command panels for real, not just in the aspirational `docs/main/UX-DESIGN.md`.
- **"Can you create a user from the UI at all, or only via seed/SQL?"** Yes, from the UI —
  `org-settings.html`'s "Create a user" form, reachable by ADMIN or MANAGER.
- **"Can a MANAGER manage their own group's people, or is it ADMIN-only?"** MANAGER, confirmed —
  `orgGuard` (`core/org/org-guard.ts:23-24`, `canManageOrg`) admits both ADMIN and MANAGER to `/org`;
  it is not an ADMIN-only surface in the UI.
