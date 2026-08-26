# vision-identity

Users, groups, roles, authentication, and the pilot→asset assignment roster. Domain + application in
one module (docs/plans/active/DOMAIN-SEPARATION-W1.md §16 — the eight-context split dissolved
`vision-domain` and `vision-application`; this module holds identity's `.domain`/`.application`
packages together, the same layer boundary ArchUnit still enforces).

**What it deliberately does not do**: resolve *what* a user may see — that value type
(`VisibilityScope`) and the exception it throws (`AccessDeniedException`) moved out to
`vision-platform` in **W1.6a**, because every context filters by them, not just this one (see that
module's MODULE.md). This context's own job is narrower and upstream of that: turning a `User` into
a `VisibilityScope` (`ScopeResolver`) and managing the roster a `VisibilityScope` is computed
from — memberships, group tree, pilot→asset assignments. It does not itself decide whether a command
may proceed against a specific asset/device (that's each consuming context's own gate, e.g. flight's
`DefaultFlightCommandService`) — it only hands out the scope value those gates check against.

**Depends on:** `vision-kernel` (`UserId`, `GroupId`, `AssetId`…) · `vision-platform`
(`VisibilityScope`, `AccessDeniedException`, `AuditTrailPort`/`AuditEntry`) ·
**`vision-warehouse`** — assignment reads assets: `DefaultAssignmentService` reaches
`AssetService#details(AssetId)` (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R5, replacing a
direct `AssetRepositoryPort` read — see Gotchas) for the one fact it needs per call — does the
asset exist, what group does it belong to — so the ≤-own-scope grant check can run. No other context.
**Used by:** `vision-map` (viewer resolution), `vision-app`, `vision-api`, adapter-persistence.
**Build/test:** `./mvnw -B -pl contexts/vision-identity test` — 91 tests green.

## API surface

### `com.drones.vision.identity.domain.model`
- `record Group(GroupId id, String name, GroupId parentGroupId)` (docs/plans/done/U-AUTH-PLAN.md wave 1) — org-chart node a `User` can hold a `Membership` in; `parentGroupId` nullable (`null` = root group); `name` non-blank; subtree visibility scoping is `ScopeResolver`'s job, not this record's
- `record Membership(GroupId groupId, Role role)` — a `User`'s role within one group; rides on the `User` aggregate (saved whole), no separate membership repository
- `enum Role` — `PILOT`, `MANAGER`, `ADMIN`, declared **least→most privileged**; ordinal ordering is meaningful (`User#topRole()` picks the highest role by ordinal). Pure marker, no dedicated test
- `record User(UserId id, String username, String displayName, String email, String passwordHash, boolean enabled, List<Membership> memberships)` — the identity aggregate; `username` non-blank, normalized to lower-case in the compact ctor (trim + `toLowerCase(Locale.ROOT)`) so lookups are case-insensitive by construction — actual uniqueness enforced by `UserRepositoryPort` implementations; `email` shape-checked only (non-blank, contains `@`), deliberately not RFC-validated; `passwordHash` non-blank and **opaque to the domain** — never hashed/verified here (BCrypt lives behind `PasswordVerifier`, application layer); `memberships` defensively copied, may be empty; 6-arg convenience ctor defaults `memberships=List.of()`; `topRole()` → `Optional<Role>`, `Optional.empty()` (not a throw) when unassigned; `toString()` overridden to render `passwordHash=***`

### `com.drones.vision.identity.domain.port` (driven — implemented by adapters)
- `AssignmentRepositoryPort` (docs/plans/done/U-SCOPE-PLAN.md U-e slice 2, feature 2) — the pilot→asset join, deliberately independent of both `User` and `Asset`: `void assign(UserId, AssetId)` idempotent upsert; `void unassign(UserId, AssetId)` idempotent; `Set<AssetId> assetsForPilot(UserId)`; `Set<UserId> pilotsForAsset(AssetId)`; `boolean isAssigned(UserId, AssetId)`. Empty set (never `null`) means "no links"
- `GroupRepositoryPort`: `Optional<Group> findById(GroupId)`; `List<Group> findAll()` snapshot; `Group save(Group)` upsert. `Group#parentGroupId` links the tree; no separate membership storage
- `PasswordHasherPort` (docs/plans/done/U-AUTH-PLAN.md wave 2): `String hash(String rawPassword)`; `boolean verify(String rawPassword, String hash)`; implemented outside domain/application (BCrypt, `vision-app`)
- `UserRepositoryPort`: `Optional<User> findByUsername(String)` case-insensitive against the normalized username; `Optional<User> findById(UserId)`; `User save(User)` upsert, whole aggregate; `List<User> findAll()` snapshot

### `application` (root package)
- `AuthService` (interface) → `DefaultAuthService(UserRepositoryPort, PasswordHasherPort)` — login checks + principal reload, behind the future Spring Security bridge
  - `Optional<User> authenticate(String username, String rawPassword)` — `null`/blank input short-circuits to empty before any repository call; returns the user only if **both** `enabled()` and `passwordHasher.verify(...)` hold. An unknown username, a disabled user, and a wrong password are **all** `Optional.empty()` alike and this method never throws for any of them — deliberately: a login endpoint must not be able to distinguish which happened (information leak)
  - `Optional<User> find(UserId)` — for a security adapter reloading the current session's principal fresh on every request (role/enabled changes since login are picked up immediately)
  - `Optional<User> loadByUsername(String)` — for Spring Security's own `UserDetailsService` bridge, always keyed by username. **Both are kept** — they serve two different callers, neither subsumes the other
- `UserService` (interface) → `DefaultUserService(UserRepositoryPort, PasswordHasherPort)` — creates/manages `User` accounts; **every method takes the acting `VisibilityScope` and enforces management authority from it** (kind maps 1:1 to role); an `unbounded()` scope (ADMIN/auth-off) passes every gate byte-identically to pre-gate behavior
  - `User create(UserSpec, VisibilityScope acting)` — `!acting.canManageOrg()` → 403; a spec with **no memberships** is allowed only for `unbounded()` (a manager must place a new user in a group they manage); per membership, `!acting.includesGroup(m.groupId())` → 403, and a role above `maxGrantableRole(acting)` → 403 (this switch used to live on `VisibilityScope` itself — it moved here, its only caller, when `VisibilityScope` left for `vision-platform`, since granting roles is user administration, not visibility); duplicate username (case-insensitive) → 409
  - `private static Optional<Role> maxGrantableRole(VisibilityScope)` — `UNBOUNDED`→`ADMIN`, `GROUPS`→`MANAGER`, `ASSIGNED_ASSETS`→empty (unreachable in practice, `canManageOrg()` already excludes it)
  - `List<User> list(VisibilityScope acting)` — `unbounded()`→every user; `GROUPS`→users with ≥1 membership `acting.includesGroup(...)`; any other scope→empty
  - `User setEnabled(UserId, boolean, VisibilityScope acting)` — `!acting.canManageOrg()` → 403; non-unbounded acting scope must include one of the target's membership groups → 403; idempotent
- `GroupService` (interface) → `DefaultGroupService(GroupRepositoryPort)` — creates/lists `Group`s; both methods take the acting `VisibilityScope`
  - `Group create(GroupSpec, VisibilityScope acting)` — `!acting.canManageOrg()` → 403; a **root** group (`null` `parentGroupId`) only by `unbounded()` (a manager creating one → 403); a parent must be `acting.includesGroup(parent)` (403) before the existence check (404)
  - `List<Group> list(VisibilityScope acting)` — name-sorted; `unbounded()`→every group; `GROUPS`→groups `acting.includesGroup(id)`; any other scope→empty. **No `tree()` read model** — full hierarchy management deferred
- Records: `UserSpec(username, displayName, email, rawPassword, memberships, enabled)` — validation deliberately minimal, only `rawPassword` non-blank is checked here (`User`'s own compact ctor validates the rest once the aggregate is built; the raw password is the one field `User` never sees); `GroupSpec(name, parentGroupId)` — `name` non-blank, `parentGroupId` nullable (root)

### `application.scope`
- `ScopeResolver` (interface) → `DefaultScopeResolver(GroupRepositoryPort, AssignmentRepositoryPort)` — turns a `User` into a `VisibilityScope`; the single place role + group tree + assignments become "what may you see"
  - `VisibilityScope scopeFor(User)` — precedence: any **ADMIN** membership → `unbounded()`; else any **MANAGER** membership → `groups(union of each manager group's subtree)`; else (PILOT-only or no membership at all) → `assignedAssets(assignmentRepo.assetsForPilot(user.id()))` (empty set for an unassigned user). Subtree = self + descendants via `Group.parentGroupId`, built once as a parent→children map, walked BFS per manager root; a `visited` `LinkedHashSet` both dedupes overlapping subtrees and **breaks any malformed cycle** in the stored tree
- `AssignmentService` (interface) → `DefaultAssignmentService(AssignmentRepositoryPort, AssetService)` — the pilot→asset roster
  (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R5 — the second constructor argument was
  warehouse's `AssetRepositoryPort` until this wave; it is now warehouse's published
  `AssetService`, see Gotchas)
  - `void assign(UserId pilot, AssetId, VisibilityScope granterScope)` / `unassign(...)` — both validate the asset exists (404) then enforce the grant rule: `granterScope.canManage(asset.ownership())` or 403 (docs/plans/done/OPS-UX-PLAN.md §1/C3 — was `includes(asset)`, a visibility check; a PILOT could see-and-therefore-grant their own assigned asset, which conflated "may see" with "may reassign pilots." `canManage` is `false` for `ASSIGNED_ASSETS` regardless of the asset, so a pilot can no longer grant/revoke even their own assignment). Idempotent; an `unbounded()` granter may assign anything; a `groups()` (MANAGER) granter may assign within their own subtree, same as before
  - `Set<AssetId> assignmentsFor(UserId pilot)` — thin pass-through, not scope-checked (a pilot reading their own roster); backs `GET /api/me/assignments`
- `ActivityService` (interface) → `DefaultActivityService(AuditTrailPort)` — a user's own activity feed
  - `List<AuditEntry> myActivity(UserId actor, int limit)` — thin pass-through over `AuditTrailPort.findByActor`; scoping is just "your own actor id" (a manager-sees-team view is deferred)

## Conventions
- **Validation**: domain records validate in their compact constructor (`if (…) throw new IllegalArgumentException(…)`); the application layer uses `Objects.requireNonNull`.
- **The acting user is a method parameter (`UserId actor`/`VisibilityScope acting`)**, never a constructor dependency.
- **Constructor injection only**, every collaborator `Objects.requireNonNull`-wrapped.
- **Management gates are all "unbounded passes, else check `includesGroup`/`canManageOrg`"** — the same shape repeats across `UserService`/`GroupService`/`AssignmentService`, deliberately, so a reviewer checking one understands the other two.
- **`Role`'s ordinal ordering is load-bearing** — declared least→most privileged; reordering the enum constants would silently change what "top role" and "max grantable role" mean.

## Gotchas
- **`VisibilityScope`, `AccessDeniedException`, and the whole `AuditEntry` family live in `vision-platform`, not here** (moved **W1.6a**) — every context filters by the scope and throws the exception; it is the authorization *value*, not this context's aggregate. Do not add a new field to `VisibilityScope` here — that module owns it now. See `core/vision-platform/MODULE.md` for its full shape, including `includes(AssetId, Ownership)` (narrowed from `includes(Asset)` so the type could leave the application layer without dragging warehouse's `Asset` behind it).
- **`maxGrantableRole` intentionally does not live on `VisibilityScope` any more** — it moved to `DefaultUserService` (its only caller) precisely because granting a role is user-administration policy, not a fact about what a scope can see. A future second caller of "what's the ceiling role this scope may grant" should call this class, not resurrect the method on the value type.
- **`ScopeResolver`'s cycle guard is a real safety net, not defensive paranoia** — `DefaultScopeResolverTest` proves a malformed cyclic group tree still terminates (`assertTimeoutPreemptively`), because nothing in `GroupRepositoryPort`/`Group` itself prevents one from being stored.
- **`DefaultAssignmentService` reaches `AssetService#details(AssetId)`, not `AssetRepositoryPort`** (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R5) — until this wave it reached warehouse's `AssetRepositoryPort` directly ("one fact, avoid a full assembly"); the audit's finding T1 is that a cross-context read through another context's repository port is a shared-database coupling wearing an interface, so it now goes through the published service instead, even though `details(AssetId)` assembles more than this call needs (it also resolves the asset's devices and recent usages). A net-zero-parameter swap — `AssignmentRepositoryPort, AssetRepositoryPort` became `AssignmentRepositoryPort, AssetService` — and grant/revoke is roster management, not a hot path, so the extra assembly work is accepted rather than requesting a narrower `AssetService` method for this one caller. If a future narrower read (e.g. `AssetService#ownershipOf(AssetId)`) is added for another caller, reconsider swapping this one onto it too.
- **`AuthService#authenticate`'s "never distinguish the failure reason" behavior is a security requirement, not an incomplete implementation** — do not add a more specific exception or return type here to help a caller show a friendlier error message; that is exactly the information leak this method exists to prevent.
- **`GroupService` has no `tree()` read model** — the flat, name-sorted `list()` is deliberately the only read slice 1 shipped; a manager UI wanting a nested tree view has to build it client-side from the flat list's `parentGroupId` links, or a later wave adds one.

## Status

**ARCHITECTURE-AUDIT-2026-08-26 wave R5b done**: `DefaultAssignmentService`'s second constructor
parameter swapped from warehouse's `AssetRepositoryPort` to warehouse's published `AssetService`
(`requireGrantable` now calls `assetService.details(assetId).summary().asset()` instead of
`assetRepository.findById(assetId)` — same `NoSuchElementException` message/behavior for an unknown
asset). Net-zero parameter count. This module no longer imports any foreign context's
`*RepositoryPort` — see Gotchas for the "why `details(AssetId)` and not a narrower method" call.
93/93 tests green (`./mvnw -B -pl contexts/vision-identity test`), unchanged count — a pure
collaborator swap, no behavior change for any test. **Wiring not applied here** (`vision-app`'s
`AuthWiringConfiguration#assignmentService` bean method still passes an `AssetRepositoryPort` and
must be updated to take/pass an `AssetService` instead — see the R5b task's own final report for the
exact edit; `contexts/**` cannot touch `station/vision-app`).

**W1.7b/c** (docs/plans/active/DOMAIN-SEPARATION-W1.md §16): `vision-domain`/`vision-application` dissolved; identity's `.domain`/`.application` packages became this one module, a directory move with the ArchUnit layer boundary preserved. `VisibilityScope`/`AccessDeniedException`/the audit family had already left for `vision-platform` in W1.6a, so this module's own dependency graph shrank to kernel+platform+warehouse only. 91/91 tests green.

**docs/plans/done/U-AUTH-PLAN.md wave 1 done** (domain half, now this module's own domain package): `Role`, `Group`, `Membership`, `User`, plus `UserRepositoryPort`/`GroupRepositoryPort`.

**docs/plans/done/U-AUTH-PLAN.md wave 2 done** (application half — `AuthService`/`UserService`/`GroupService`): the three interface+`Default*` pairs plus `UserSpec`/`GroupSpec` command records (see API surface above). `PasswordHasherPort` was the one domain addition this wave needed (the password-hashing seam is a domain out-port per the plan's own explicit carve-out). **Design decisions recorded at the time**: `AuthService` keeps both `find(UserId)` and `loadByUsername(String)` rather than collapsing to one, since they serve two different wave-3 callers and neither subsumes the other; `GroupService` has no `tree()` (deferred); `UserSpec` validates only `rawPassword` (every other field deferred to `User`'s own compact ctor once the aggregate is built, unlike `AssetSpec`'s fuller duplication, since the spec can't defer the one field `User` never sees); `DefaultUserService#create`'s duplicate-username check runs against the already-built candidate `User`'s normalized username, reusing `User`'s own shape validation and lower-casing rather than re-implementing it. Hand-written fake ports (in-memory `ConcurrentHashMap`s) rather than Mockito for this test suite — a login/duplicate-check/toggle service reads more clearly against a real, if trivial, in-memory store. Not built in this wave (explicitly deferred): the invite flow, the ≤-own-scope grant rule, `GroupService#tree()`, Spring Security/persistence/seeding, and the login UI.

**docs/plans/done/OPS-UX-PLAN.md Wave C (C3) done**: `DefaultAssignmentService#requireGrantable` moved off `granterScope.includes(asset.id(), asset.ownership())` onto `granterScope.canManage(asset.ownership())` — a PILOT's own-scope visibility of their assigned asset no longer doubles as authority to grant/revoke pilots on it (docs/conclusions/OPS-UX-REVIEW.md §A1). `AccessDeniedException`'s message and the 403 mapping are unchanged; only which predicate decides the outcome changed. New tests: a PILOT scope may not `assign`/`unassign` even the exact asset assigned to them. 91 → 93 tests, `./mvnw -B -pl contexts/vision-identity test` green. With `vision.auth.enabled=false` every granter is `unbounded()`, so `canManage` is always `true` and behavior is unchanged from before this wave.

**docs/plans/done/U-SCOPE-PLAN.md U-e slice 2, wave 1 done** (visibility scoping + pilot assignment + role-gated command + "my activity" — features 1+2+3+7): this wave is identity's core deliverable — `VisibilityScope` (the three-case value, later relocated to `vision-platform` in W1.6a), `ScopeResolver`/`DefaultScopeResolver`, `AssignmentService`/`DefaultAssignmentService`, `ActivityService`/`DefaultActivityService`, `AccessDeniedException` (403 marker, also later relocated to `vision-platform`). Ripple into consuming contexts — warehouse's `AssetService.assets(VisibilityScope,...)`/`details(VisibilityScope,...)`, warehouse's `FleetSummaryService.summary(VisibilityScope,...)`, flight's `FlightCommandService.returnToHome(..., VisibilityScope)` — is documented on those contexts' own API surfaces, not repeated here. **The guardrail held**: every new scoped method short-circuits/behaves identically for `VisibilityScope.unbounded()`, so internal/system callers keep the unscoped signatures unchanged. Command-gate exception decision: `AccessDeniedException` → HTTP 403 (not 409, not the scoped-read 404) — an honest "you may not command/grant this."

**docs/plans/done/U-SCOPE-PLAN.md U-e slice 2 — deferred cleanup done**: the ADMIN/MANAGER management gate on user/group management and the invite ≤-own-scope grant rule — `VisibilityScope` gained `canManageOrg()`/`includesGroup(GroupId)`/`maxGrantableRole()`, and `UserService.create/list/setEnabled` + `GroupService.create/list` all gained the `VisibilityScope acting` argument and enforce it (see API surface above). **Empty-memberships rule**: a user with no memberships may be created only by `unbounded()` (ADMIN) — a manager must place a new user in a group they manage. Guardrail: `unbounded()` hits none of the gates, so ADMIN/auth-off behavior is byte-identical.
