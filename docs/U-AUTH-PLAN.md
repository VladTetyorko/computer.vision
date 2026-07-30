# U-AUTH-PLAN — U-e slice 1: real identity + auth foundation

Status: approved spec (2026-07-30, user chose "auth foundation first"). The first shippable
slice of UX-REWORK-PLAN §U-e. Turns the hardcoded `DevPrincipal` UUID into real logins with
`User`/`Role`/`Group`, wires `CurrentUser` to the authenticated session, and ships a login
screen — **identity becomes real; nothing is visibility-scoped yet** (that's slice 2). Also
the honest prerequisite for command-TX Stage 2+ ("who may command what").

## Guiding constraints

- **`CurrentUser` is the single replacement point** — its own javadoc says so. Controllers keep
  calling `currentUser.userId()`/`.ownership()`; nothing downstream changes.
- **Auth is opt-in via `vision.auth.enabled` (default false)** so every existing test/flow stays
  green with no login: disabled → `CurrentUser` returns today's `DevPrincipal` (auto-admin),
  login endpoints are no-ops that report the dev admin. Enabled → real session login required,
  `CurrentUser` reads the authenticated principal.
- Persistence follows the established pattern: domain ports, JPA impls behind
  `vision.persistence.enabled`, in-memory devsupport fallback. Ids wrap UUID (`random()`/`of()`).
- Deviation from the plan's "server-rendered login": this is an Angular SPA, so auth is a
  **JSON login endpoint + session cookie**, not a server-rendered form — the SPA renders its own
  login screen. "The UI has no private API" (UX-DESIGN §7) still holds: `/api/auth/*` is public
  contract like everything else.

## Domain (wave 1)

New records/enums in `com.drones.vision.domain.model` (framework-free, compact-ctor validation):
- `Role` enum `{PILOT, MANAGER, ADMIN}` — pure marker, ordered least→most privileged.
- `Group(GroupId id, String name, GroupId parentGroupId)` — org-chart node; `parentGroupId`
  nullable (null = root). Name non-blank.
- `Membership(GroupId groupId, Role role)` — a user's role within one group.
- `User(UserId id, String username, String displayName, String email, String passwordHash,
  boolean enabled, List<Membership> memberships)` — the identity aggregate. `username` non-blank
  and lowercased-unique (repo-enforced); `email` shape-validated (non-blank, contains `@`);
  `passwordHash` non-blank (BCrypt string; the domain never hashes — it stores what it's given);
  `memberships` defensively copied, may be empty. Convenience ctor without memberships → empty.
  Helper `topRole()` → the highest `Role` across memberships (empty → throws or `Optional`,
  decide in impl; a user with no membership can't act meaningfully yet).
- Ports: `UserRepositoryPort` (`findByUsername(String)→Optional`, `findById(UserId)→Optional`,
  `save(User)`, `findAll()`), `GroupRepositoryPort` (`findById`, `findAll`, `save`). Memberships
  ride on the `User` aggregate (saved whole) — no separate membership port in slice 1.
- Tests: id round-trips, validation (blank username/email/hash, bad email), `topRole`,
  membership defensive copy, repo-port contract via a fake.

## Application (wave 2)

- `AuthService` (interface) + `DefaultAuthService`:
  - `Optional<User> authenticate(String username, String rawPassword)` — loads by username,
    verifies via an injected `PasswordVerifier` seam (BCrypt in prod; the domain/application stay
    framework-free, so the BCrypt dependency lives in the adapter/app layer behind this seam),
    returns the user iff enabled and the password matches.
  - `Optional<User> find(UserId)` / `loadByUsername` for the security adapter.
- `UserService` (interface) + `DefaultUserService` — minimal CRUD needed to seed/manage:
  `create(spec)` (hashes via the same seam), `list()`, `setEnabled`. Invite flow + the
  ≤-own-scope grant rule are **slice 2** (they need the visibility model) — documented as deferred.
- `GroupService` — `create`, `list`, `tree()`. Minimal; full hierarchy management is slice 2.
- Seeding is data, not logic: the app layer seeds three users (see Persistence).

## Auth + API + wiring (wave 3)

- **Spring Security** added to `vision-app` (session-based). `SecurityConfig`:
  - `vision.auth.enabled=false` (default): permit-all, no filter chain friction — preserves every
    current test. `CurrentUser` returns `DevPrincipal` exactly as today.
  - `vision.auth.enabled=true`: authenticated session required for `/api/**` except `/api/auth/**`
    and static assets; session cookie; CSRF handled for the SPA (cookie-to-header or disabled for
    the token-less same-origin session — decide in impl, document).
  - `PasswordEncoder` = BCrypt bean; a `UserDetailsService` bridging `AuthService` → Spring's user.
- **`CurrentUser` rewrite**: when auth enabled, resolve `UserId`/`Ownership` from the
  `SecurityContext` principal; when disabled, the injected `DevPrincipal` fallback (unchanged).
  Its constructor gains the enabled flag + a resolver; **no controller changes**.
- **`AuthController`** (`vision-api`) — frozen wire contract:
  - `POST /api/auth/login {username, password}` → 200 `MeResponse` + session cookie; 401 bad creds
    / disabled user. When `vision.auth.enabled=false`, always 200 with the dev admin (no-op login).
  - `POST /api/auth/logout` → 204, invalidates session.
  - `GET /api/auth/me` → 200 `MeResponse` when authenticated (or always, in dev-disabled mode);
    401 when auth enabled and no session.
  - `MeResponse {userId, username, displayName, email, memberships:[{groupId, groupName, role}],
    topRole, authEnabled}` — `authEnabled` lets the SPA know whether to show a login screen at all.
- **Persistence** (`adapter-persistence`): `UserEntity`/`GroupEntity` + `V8__users_groups.sql`
  (users, groups, memberships tables; memberships as a child table or jsonb — match the `extra`/
  `flight_state` jsonb precedent or a proper join table, pick the cleaner). JPA repos implement the
  ports. Devsupport in-memory `InMemoryUserRepository`/`InMemoryGroupRepository`.
- **Seeding**: an `ApplicationRunner` (like `SimulationResumeRunner`) seeds — only when the repos
  are empty — three users with known dev passwords and one root group:
  `admin`/`admin` (ADMIN), `manager`/`manager` (MANAGER), `pilot`/`pilot` (PILOT). Dev-only
  passwords, documented as such; real deployments create users via `UserService`.

## UI: login + identity + responsive (wave 4)

- `core/auth/auth-store.ts` — calls `GET /api/auth/me` on boot; holds the current user signal;
  `login(username,password)`, `logout()`. `authEnabled=false` → treats the dev admin as logged in,
  never shows the login screen (dev parity).
- **Login screen** (`features/auth/login`) — username/password, error on 401, **responsive**
  (single-column, large touch targets, works phone→desktop). Shown by a route guard when
  `authEnabled && !user`.
- **Identity chip** in the app header — display name + role + logout; responsive (collapses to an
  avatar/menu on narrow viewports). This is the first piece of the broader responsive pass
  (slice 3) — build it responsive from the start so it sets the pattern.
- No visibility filtering yet — every logged-in user still sees everything (slice 2 adds the
  group-subtree filter). Documented so it's not mistaken for a bug.

## Waves & sequencing

1. **Wave 1 — domain** (`vision-domain/**`): records, ports, tests. Blocks the rest. Solo (safe).
2. **Wave 2 — application** (`vision-application/**`): AuthService/UserService/GroupService +
   PasswordVerifier seam + tests. After wave 1.
3. **Wave 3 — security/api/persistence/wiring** (`vision-app/**`, `vision-api/**`,
   `adapters/adapter-persistence/**`): Spring Security, CurrentUser rewrite, AuthController, JPA +
   in-memory repos, seeding. After wave 2. The high-risk wave — reviewed carefully, `vision.auth`
   defaults keep everything green.
4. **Wave 4 — UI** (`vision-web/**`): auth store, login screen, identity chip, guard, responsive.
   Builds against the frozen `MeResponse`/`/api/auth/*` contract — parallelizable with wave 3.

## Exit criteria (slice 1)

- `vision.auth.enabled=false` (default): zero behavior change — every existing test green, no login.
- `vision.auth.enabled=true`: the three seeded users log in via the SPA login screen; `/api/auth/me`
  reflects the session; logout ends it; an unauthenticated `/api/**` call is 401. Roles exist and
  are visible in the identity chip. **No view is group-scoped yet** — that's slice 2.

## Explicitly deferred to slice 2 (visibility scoping)

Group-subtree visibility filter (warehouse/fleet/map/events), pilot-sees-only-assigned-drones,
the invite ≤-own-scope grant rule, Command's group tree / drill-down re-scoping, `Asset.groupId`.
