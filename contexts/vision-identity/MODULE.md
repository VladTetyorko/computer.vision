# vision-identity

Users, groups, roles, authentication, and the pilot→asset assignment roster.

**What it deliberately does not do**: resolve *what* a user may see — `VisibilityScope`/`AccessDeniedException` live in `vision-platform` (every context filters by them, not just this one). This context turns a `User` into a `VisibilityScope` (`ScopeResolver`) and manages the roster a scope is computed from — memberships, group tree, pilot→asset assignments. It does not decide whether a command may proceed against a specific asset/device — that is each consuming context's own gate (e.g. flight's `DefaultFlightCommandService`).

**Depends on:** `vision-kernel` (`UserId`, `GroupId`, `AssetId`…) · `vision-platform` (`VisibilityScope`, `AccessDeniedException`, `AuditTrailPort`/`AuditEntry`) · `vision-warehouse` — `DefaultAssignmentService` reaches `AssetService#details(AssetId)` for the one fact it needs (asset exists, its group) to run the grant check
**Used by:** `vision-map` (viewer resolution), `vision-app`, `vision-api`, `adapter-persistence`
**Build/test:** `./mvnw -B -pl contexts/vision-identity test`

## API surface

### `com.drones.vision.identity.domain.model`
- `record Group(GroupId id, String name, GroupId parentGroupId)` — org-chart node a `User` holds a `Membership` in; `parentGroupId` nullable (`null` = root); `name` non-blank; subtree scoping is `ScopeResolver`'s job
- `record Membership(GroupId groupId, Role role)` — a `User`'s role within one group; rides on the `User` aggregate, no separate membership repository
- `enum Role` — `PILOT`, `MANAGER`, `ADMIN`, declared least→most privileged; ordinal ordering is load-bearing (`User#topRole()` picks the highest role by ordinal)
- `record User(UserId id, String username, String displayName, String email, String passwordHash, boolean enabled, List<Membership> memberships)` — `username` non-blank, normalized lower-case in the compact ctor; `email` shape-checked only (non-blank, contains `@`), not RFC-validated; `passwordHash` opaque to the domain (BCrypt lives behind `PasswordHasherPort`); `memberships` defensively copied; 6-arg convenience ctor defaults `memberships=List.of()`; `topRole()` → `Optional<Role>`; `toString()` renders `passwordHash=***`

### `com.drones.vision.identity.domain.port` (driven — implemented by adapters)
- `AssignmentRepositoryPort` — the pilot→asset join, independent of both `User` and `Asset`: `void assign(UserId, AssetId)` idempotent upsert; `void unassign(UserId, AssetId)` idempotent; `Set<AssetId> assetsForPilot(UserId)`; `Set<UserId> pilotsForAsset(AssetId)`; `boolean isAssigned(UserId, AssetId)`. Empty set (never `null`) means "no links"
- `GroupRepositoryPort` — `Optional<Group> findById(GroupId)`; `List<Group> findAll()` snapshot; `Group save(Group)` upsert
- `PasswordHasherPort` — `String hash(String rawPassword)`; `boolean verify(String rawPassword, String hash)`; implemented outside domain/application (BCrypt, `vision-app`)
- `UserRepositoryPort` — `Optional<User> findByUsername(String)` case-insensitive; `Optional<User> findById(UserId)`; `User save(User)` upsert; `List<User> findAll()` snapshot

### `application` (root package)
- `AuthService` (interface) → `DefaultAuthService(UserRepositoryPort, PasswordHasherPort)` — login checks + principal reload
  - `Optional<User> authenticate(String username, String rawPassword)` — `null`/blank input short-circuits to empty; returns the user only if **both** `enabled()` and `passwordHasher.verify(...)` hold. Unknown username, disabled user, wrong password are **all** `Optional.empty()` alike — never throws, deliberately, to avoid an information leak
  - `Optional<User> find(UserId)` — reload the current session's principal fresh on every request
  - `Optional<User> loadByUsername(String)` — for Spring Security's `UserDetailsService` bridge. Both are kept — two different callers, neither subsumes the other
- `UserService` (interface) → `DefaultUserService(UserRepositoryPort, PasswordHasherPort)` — every method takes the acting `VisibilityScope` and enforces management authority from it; `unbounded()` (ADMIN/auth-off) passes every gate
  - `User create(UserSpec, VisibilityScope acting)` — `!acting.canManageOrg()` → 403; no-memberships spec allowed only for `unbounded()`; per membership, `!acting.includesGroup(m.groupId())` → 403, a role above `maxGrantableRole(acting)` → 403; duplicate username (case-insensitive) → 409
  - `private static Optional<Role> maxGrantableRole(VisibilityScope)` — `UNBOUNDED`→`ADMIN`, `GROUPS`→`MANAGER`, `ASSIGNED_ASSETS`→empty (unreachable, `canManageOrg()` already excludes it)
  - `List<User> list(VisibilityScope acting)` — `unbounded()`→every user; `GROUPS`→users with ≥1 membership `acting.includesGroup(...)`; any other scope→empty
  - `User setEnabled(UserId, boolean, VisibilityScope acting)` — `!acting.canManageOrg()` → 403; non-unbounded acting scope must include one of the target's membership groups → 403; idempotent
- `GroupService` (interface) → `DefaultGroupService(GroupRepositoryPort)` — both methods take the acting `VisibilityScope`
  - `Group create(GroupSpec, VisibilityScope acting)` — `!acting.canManageOrg()` → 403; a **root** group (`null` `parentGroupId`) only by `unbounded()`; a parent must be `acting.includesGroup(parent)` (403) before the existence check (404)
  - `List<Group> list(VisibilityScope acting)` — name-sorted; `unbounded()`→every group; `GROUPS`→groups `acting.includesGroup(id)`; any other scope→empty. **No `tree()` read model** — a manager UI wanting a nested view builds it client-side from the flat list's `parentGroupId` links
- Records: `UserSpec(username, displayName, email, rawPassword, memberships, enabled)` — only `rawPassword` non-blank is validated here, `User`'s own compact ctor validates the rest; `GroupSpec(name, parentGroupId)` — `name` non-blank, `parentGroupId` nullable (root)

### `application.scope`
- `ScopeResolver` (interface) → `DefaultScopeResolver(GroupRepositoryPort, AssignmentRepositoryPort)` — turns a `User` into a `VisibilityScope`
  - `VisibilityScope scopeFor(User)` — precedence: any **ADMIN** membership → `unbounded()`; else any **MANAGER** membership → `groups(union of each manager group's subtree)`; else (PILOT-only or none) → `assignedAssets(assignmentRepo.assetsForPilot(user.id()))` (empty for an unassigned user). Subtree = self + descendants via `Group.parentGroupId`, walked BFS per manager root; a `visited` set both dedupes overlapping subtrees and breaks any malformed cycle in the stored tree
- `AssignmentService` (interface) → `DefaultAssignmentService(AssignmentRepositoryPort, AssetService)` — the pilot→asset roster
  - `void assign(UserId pilot, AssetId, VisibilityScope granterScope)` / `unassign(...)` — validates the asset exists (404) then `granterScope.canManage(asset.ownership())` or 403 (an authority check, not a visibility one — a PILOT may not grant/revoke even their own assignment). Idempotent; `unbounded()` may assign anything, `groups()` (MANAGER) within their own subtree
  - `Set<AssetId> assignmentsFor(UserId pilot)` — thin pass-through, not scope-checked (a pilot reading their own roster)
- `ActivityService` (interface) → `DefaultActivityService(AuditTrailPort)`
  - `List<AuditEntry> myActivity(UserId actor, int limit)` — thin pass-through over `AuditTrailPort.findByActor`; scoping is just "your own actor id" (a manager-sees-team view is deferred)

## Conventions
- Domain records validate in their compact constructor (`if (…) throw new IllegalArgumentException(…)`); the application layer uses `Objects.requireNonNull`.
- The acting user is a method parameter (`UserId actor`/`VisibilityScope acting`), never a constructor dependency.
- Constructor injection only, every collaborator `Objects.requireNonNull`-wrapped.
- Management gates repeat the same shape across `UserService`/`GroupService`/`AssignmentService`: "unbounded passes, else check `includesGroup`/`canManageOrg`" — a reviewer checking one understands the other two.
- `Role`'s ordinal ordering is load-bearing — reordering the enum constants silently changes what "top role" and "max grantable role" mean.

## Gotchas
- `VisibilityScope`/`AccessDeniedException`/the `AuditEntry` family live in `vision-platform`, not here — it is the authorization *value*, not this context's aggregate. Do not add a field to `VisibilityScope` here.
- `maxGrantableRole` lives on `DefaultUserService` (its only caller), not on `VisibilityScope` — granting a role is user-administration policy, not a fact about what a scope can see.
- `ScopeResolver`'s cycle guard is a real safety net: nothing in `GroupRepositoryPort`/`Group` prevents a cyclic group tree from being stored; `DefaultScopeResolverTest` proves it still terminates.
- `DefaultAssignmentService` reaches warehouse's `AssetService#details(AssetId)`, not its `AssetRepositoryPort` — a cross-context read goes through the published service, even though `details` assembles more than this call needs (devices, recent usages too). If a narrower read (e.g. `AssetService#ownershipOf(AssetId)`) is added for another caller, reconsider swapping this one onto it.
- `AuthService#authenticate`'s "never distinguish the failure reason" behavior is a security requirement — do not add a more specific exception/return type to help a caller show a friendlier error message.

## Status
Auth, user/group management, scope resolution, pilot↔asset assignment, and the activity feed are fully implemented. Deferred: `GroupService#tree()` (nested hierarchy view — flat `list()` only), the invite flow.
