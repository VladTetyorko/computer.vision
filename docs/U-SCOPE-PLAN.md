# U-SCOPE-PLAN — U-e slice 2: visibility scoping + the user-scoped feature catalog

Status: draft for selection (2026-07-30). Slice 1 (U-AUTH-PLAN) gave real logins + roles but
scoped nothing — every logged-in user still sees everything. This slice makes identity *matter*:
what you see and may do is bounded by who you are. It also catalogs the broader user-scoped
feature set and maps each to the backend work it needs, so we can pick a build order.

## The alignment win (why this is cheaper than it looks)

`Asset` already carries `Ownership(UserId ownerId, GroupId groupId)`, and assets are created with
`currentUser.ownership()` — so **every asset already has a groupId today**. Slice 1's
`SecurityContextPrincipalResolver` already resolves the authenticated user's `Ownership`. So the
data foundation is done; slice 2 is *filtering reads by group subtree* + *pilot→asset assignment*
+ *role gates* — application-layer logic, not a schema migration.

## Core: visibility scoping (the must-have of this slice)

### Design — enforce in the application layer, one resolver, every read path
- **`VisibilityScope`** (application) resolved once per request from the current user:
  - `ADMIN` → sees everything (unbounded) — and, crucially, **the dev principal when
    `vision.auth.enabled=false` resolves to ADMIN/unbounded, so the default-off build has zero
    behavior change** (same gate that kept slice 1 safe).
  - `MANAGER` → the set of visible `GroupId`s = the subtree (self + descendants) of each group
    they're a MANAGER of, computed from the `Group.parentGroupId` tree (`GroupRepositoryPort`).
    Sees assets whose `ownership().groupId()` is in that set.
  - `PILOT` → only assets explicitly assigned to them (see Assignment below), not a group subtree
    ("a pilot flies their aircraft, not their org's inventory").
- **A `ScopeResolver`** (application) builds the `VisibilityScope` from the user's memberships +
  the group tree + their assignments. `CurrentUser` (vision-api) gains `scope()` returning it, the
  same way it already exposes `userId()`/`ownership()`; the SecurityContext resolver computes it,
  the dev resolver returns unbounded — no controller sees a role or a group.
- **Filtering lives in the read services, not controllers**: `AssetService.assets(...)`,
  `FleetSummaryService.summary(...)`, the map/fleet reads, and the event/detection reads gain a
  `VisibilityScope` argument and filter by it (`asset.ownership().groupId() ∈ scope.groups()` or
  `asset.id() ∈ scope.assignedAssets()`; unbounded short-circuits to today's behavior). One choke
  point per read; ArchUnit already forbids controllers reaching past services, so scoping can't be
  bypassed by a stray query.
- **Write-path guard**: mutating an asset you can't see is a 403/404 (prefer 404 — don't reveal
  existence outside scope). Enforced in the same services, on the same scope.

### Assignment (pilot → asset)
- `AssignmentRepositoryPort` (new): `assign(UserId pilot, AssetId)`, `unassign(...)`,
  `assetsFor(UserId pilot)`, `pilotsFor(AssetId)`. A join, independent of asset identity (a pilot's
  roster changes far more often than an asset does). JPA + in-memory like every other port.
- `AssignmentService` + endpoints: `PUT/DELETE /api/assets/{id}/pilots/{userId}` (manager grants
  within their scope only — the ≤-own-scope rule). `GET /api/me/assignments` for the pilot.
- Pilot's Fly picker + fleet list then show only assigned assets — free, once the scope filter is in.

### Invite / grant ≤-own-scope (completes slice 1's deferred `UserService.create`)
- `UserService.create`/invite takes the acting user's scope: an inviter may grant a role/group
  **at or below their own** — a MANAGER can't mint an ADMIN or attach a user to a group outside
  their subtree. Enforced in the application layer (the plan's own rule). Endpoint:
  `POST /api/users` (invite), `POST /api/groups` (already exists, now scope-checked).

### UI (built responsive, extends the slice-1 pattern)
- Manager **group tree in a left rail** (Command) — sub-groups appear; drill-down re-scopes the
  map/list/warehouse (Geotab "Belonging to" pattern). Pilot → Fly with only their assigned drones.
- An org-settings surface (ADMIN/MANAGER): users, groups, memberships, assignments — CRUD within
  scope. No visibility change for the dev/`auth.enabled=false` build (still sees all).

## The broader user-scoped feature catalog (pick what's worth building)

Each row = a feature + the backend it needs. Ranked by value×fit; the core above is the
prerequisite for most.

| # | Feature | What it gives the user | Backend alignment |
|---|---|---|---|
| **1** | **Group-subtree visibility** (core) | Managers see their org's assets/fleet/map/events only; no cross-team leakage | `VisibilityScope` + `ScopeResolver` + scope arg on read services + write-path 404. Uses existing `Asset.ownership.groupId` + `GroupRepositoryPort`. |
| **2** | **Pilot assignment** (core) | Pilot logs in → Fly with *their* drones only; manager rosters "who flies what" | `AssignmentRepositoryPort` (JPA+in-mem) + `AssignmentService` + `/api/assets/{id}/pilots/*`, `/api/me/assignments` |
| **3** | **Role-gated command TX** | Only PILOT/MANAGER (not a viewer) may "Bring home"; unblocks Stage-2 arm/mode with real authority | `FlightCommandService` checks `scope`/role before commanding; audit already records the actor. Small, high-value, rides existing command path. |
| **4** | **Manager tasking / "go look at X"** | Manager pins a task to a pilot/asset ("inspect north fence"); pilot sees it in Fly | `Task(id, assetId, assigneeUserId, note, status, createdBy)` domain + `TaskRepositoryPort` + `TaskService` + `/api/tasks` + SSE task topic. New aggregate, medium size. |
| **5** | **Multi-operator presence** | "who's flying what right now" — avoid two pilots on one drone | A presence registry keyed by active stream + `CurrentUser`; rides the live SSE channel (`LiveUpdateRegistry`) — a `presence` topic. Mostly wiring on existing infra. |
| **6** | **Per-user server-side preferences** | `flyAssetId`, settings, saved filters follow the user across devices (today they're `localStorage`, per-browser) | `UserPreferenceRepositoryPort` (k/v per user) + `/api/me/preferences`; migrate `settings-store`/`flyAssetId` to read-through. Independent of scoping. |
| **7** | **"My activity" / per-user audit** | A user sees their own actions; a manager sees their team's | Audit trail already stores `actor` (UserId). Add `AuditQueryPort.findByActor/byGroup` + `/api/me/activity`. Read-only over existing data. |
| **8** | **Per-user notifications** | Event/geofence/failsafe alerts scoped to what a user can see + their opt-ins | Slice-1 `eventNotifications` is per-browser; make it per-user (feature 6) and scope-filter events by `VisibilityScope` (feature 1). Composes 1+6. |
| **9** | **Per-user API tokens** | A pilot/integration gets a scoped token (UX-DESIGN §7 "the UI has no private API") | `ApiToken(hash, userId, scope, expiry)` + a token auth filter alongside the session filter (vision-app) + `/api/me/tokens`. Security-sensitive; later. |

## Recommended build order

1. **Slice 2 core = features 1 + 2 + 3** (visibility + assignment + role-gated command). This is
   "user scope" in the real sense and unblocks command-TX Stage 2. One coherent build:
   backend scope/assignment/gates → org-settings + pilot-roster UI → responsive.
2. **Feature 6** (server-side preferences) next — small, independent, immediately nice (settings
   follow you across devices), and feature 8 needs it.
3. **Features 4/5** (tasking, presence) — the multi-operator collaboration layer, once 1–3 prove
   the scope model.
4. **Features 7/9** (activity view, API tokens) — read-only audit is cheap; tokens are
   security-sensitive and belong after the scope model is battle-tested.

## Guardrails (unchanged from slice 1)

- `vision.auth.enabled=false` → dev principal resolves to unbounded ADMIN scope → **zero behavior
  change**; every existing test stays green. Scoping only bites when auth is on.
- No fake scoping: an out-of-scope asset is *absent* (404), never a greyed-out teaser.
- Enforcement in the application layer only — controllers stay scope-agnostic, ArchUnit keeps it so.
