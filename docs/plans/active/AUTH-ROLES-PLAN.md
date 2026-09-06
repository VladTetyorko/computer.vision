# AUTH-ROLES — who this station belongs to, and what each person may do

Status: **BUILT and MERGED to master 2026-09-05** (branch `feat/auth-roles`, live-verified; merged
inside the four-feature stack `auth-roles` → `source-onboarding-2` → `track-follow` → `crew-control`).
Header corrected 2026-09-06 by E2E-FLOW-AUDIT proposal N2 — it still read *"spec only — nothing here
is built"* a day after the code shipped, which is exactly the kind of stale status line that audit
warns not to trust. The body below is the **as-built** spec; `ARCHITECTURE.md` §6 was reconciled
against it in the same pass and now describes capabilities + Postgres-backed sessions rather than the
three-role/JWT design that was never built.

Owner request 2026-09-04: *"authentication + roles — think the WHOLE FLOW from the user's perspective
before any implementation."*

This is the MVP4 candidate [`MVP3-PLAN.md`](../done/MVP3-PLAN.md) §Deferred named and never
scheduled (*"Auth/login + operator-vs-manager as real roles"*). It finishes what
[`U-AUTH-PLAN.md`](../done/U-AUTH-PLAN.md) (identity became real) and
[`U-SCOPE-PLAN.md`](../done/U-SCOPE-PLAN.md) (visibility became real) started, and it makes true the
sentence [`OPS-UX-PLAN.md`](../done/OPS-UX-PLAN.md) §1 shipped as doctrine and `VisibilityScope`'s
own javadoc still asserts:

> **Visibility is not authority.**
> — `core/vision-platform/src/main/java/com/drones/vision/platform/VisibilityScope.java:33-37`

Today the most safety-critical gate in the platform — *may this person fly this aircraft* — is
answered by the **visibility** predicate. This plan gives authority its own axis, its own type, and
its own place in the wire contract, without re-opening what U-SCOPE froze.

**Two constraints bind this document and are honoured throughout, not argued with:**

- [`LIVE-SCOPE-PLAN.md`](../done/LIVE-SCOPE-PLAN.md) §2 evaluated and **rejected** `@PreAuthorize` /
  declarative role rules, choosing "access collaborator called from the controller + an ArchUnit
  guard". §3.8 explains why that rejection holds for the coarse half too, and adds no second
  mechanism.
- [`CREW-CONTROL-PLAN.md`](CREW-CONTROL-PLAN.md) §3.7 **IC-2** requires that the "may fly" vs
  "camera only" distinction live **on the assignment, never on `Role`**, and states plainly: *"The
  one thing that must not happen is AUTH-ROLES answering IC-2 with a new `Role` constant."* §3.3
  and §3.4 obey this exactly, and §3.9 is the join.

---

## 0. The flow

Four people, one station. Every journey ends in a **verdict** against today's code.

### 0.1 Who is at the station

| Persona | The one sentence | How it is expressed (frozen, §3.2/§3.4) |
|---|---|---|
| **Admin** | *"This station is mine. I decide who is on it."* Sets it up, creates people, hands out aircraft, and is the only one who can undo any of it. Often the same physical person as a pilot, on a laptop in a van. | `Role.ADMIN` |
| **Commander** | *"Where is everything and what needs me?"* Runs `/command`, owns a unit's aircraft and people, may fly and may manage the fleet — but does not own the station. | `Role.MANAGER` |
| **Pilot** | *"Give me my drone and get out of the way."* Flies what is assigned to them, sees nothing else, and wants to see nothing else. | `Role.PILOT` + `AssignmentRole.PILOT` on that asset |
| **Crew** | *"I run the camera, not the aircraft."* The second seat: payload, CV, tracking, marks. Must never be able to take the stick. | `Role.PILOT` + **`AssignmentRole.CREW`** on that asset — **not a role** |
| **Viewer / the Wall** | *"I am a screen on a wall."* A TV in the ops room, logged in once, never touched again. Sees the whole picture, may change nothing. | `Role.VIEWER` — **does not exist today** |

The Crew row is the shape [`CREW-CONTROL-PLAN.md`](CREW-CONTROL-PLAN.md) IC-2 demands, and it buys
something a role could never express: **the same person may be pilot on drone A and crew on drone
B.** A role is global; a seat is per-aircraft, and crew is a seat.

### 0.2 J1 — first boot of a fresh station

**What the person does.** Unpacks a box, `docker compose up` (or `./mvnw spring-boot:run`), opens
`http://station.local:8080`, and expects to be asked who they are.

**What happens today.**

- The shipped `station/vision-app/src/main/resources/application.yaml:166` sets
  `vision.auth.enabled: false`. The app opens straight into `/fly`, every request resolves to a
  fixed dev admin with an unbounded scope, and a red banner reads, at `station/vision-web/src/app/app.html:12`:
  *"This station is unsecured — anyone on this network is an administrator."*
- The banner is correct and there is **nothing the person can do about it from the UI**. It offers
  no action, and no page in the SPA names the setting that would fix it.
- If they find it and set `VISION_AUTH_ENABLED=true`, the station becomes **unusable**:
  `AuthController` exposes exactly three endpoints — `POST /api/auth/login`, `POST /api/auth/logout`,
  `GET /api/auth/me` — and `DefaultUserService#create` requires `acting.canManageOrg()`. With auth
  on and no users, nobody has a scope, so nobody can create the first account. There is no bootstrap
  path.
- The one escape hatch is `VISION_PERSISTENCE_SEED_DEV_USERS=true`, which `docker-compose.yml:514`
  sets, applying `storage/persistence/src/main/resources/db/seed/dev/V90001__dev_accounts.sql` —
  **`admin/admin`, `manager/manager`, `pilot/pilot`**, passwords equal to usernames, on the
  deployment path CLAUDE.md calls *the* deployment path. That file's own header says it:
  *"ANY DATABASE CARRYING THESE ROWS IS UNSECURED."*
- And if they instead **delete** the key, trusting the documented default, the station **locks
  itself out permanently** — three components disagree about what "absent" means (D2).

**Verdict: broken at every setting.** Auth off is honest and insecure. Auth on is secure and
inoperable. Auth absent is a brick. The only working combination ships three published passwords.
See **D1**, **D2**, **D3**.

### 0.3 J2 — creating people, handing out aircraft, and handing over a password

**What the person does.** Adds three pilots and one camera operator, puts them in a group, gives
each pilot their drone, tells each of them their password.

**What happens today.** Real, and better than expected — but write-once and credential-blind.

- `/manage/roster?tab=org` (`features/org-settings/org-settings.html:56-172`) creates a user with
  username, display name, email, an **admin-chosen temporary password**, one group and one role.
- Assignment is real: `features/asset-detail/pilots-card.*` and `features/roster/pilot-assignments-panel.*`
  add and remove pilots over `AssignmentController`, gated by `currentUser.scope()`.
- **The temporary password is permanent.** There is no forced change at first login, no self-service
  change, and no admin reset. Whatever the admin typed into that form is that person's password
  until the row is edited in `psql`.
- **There is no way to change anyone's role after creation.** Memberships are set in the create form
  and nowhere else. No edit, no removal, no second membership, no delete-user.
- **Crew cannot be expressed.** A camera operator is entered as a PILOT, which grants them the stick.

**Verdict: works once, then freezes, and never closes the credential loop.** See **D13**, **D14**,
and §3.5's password contract.

### 0.4 J3 — login

`/login` posts `{username, password}` to `/api/auth/login` and gets a `MeResponse` plus a
same-origin `JSESSIONID`. A 401 renders *"Incorrect username or password."* — one message for
unknown user, disabled user and wrong password alike, deliberately
(`DefaultAuthService.java:28-35`). No lockout, no backoff, no rate limit (the repo's `RateLimitFilter`
exists but ships off, and its own javadoc explains it is meaningless until auth is on).

**Verdict: works.** The login screen is the healthiest part of this surface. Its one real defect is
invisible: **no session-fixation protection runs** (D12).

### 0.5 J4 — landing where the job is

`core/shell/landing-logic.ts:26-31`:

```ts
if (!authEnabled) { return '/fly'; }
return topRole === 'ADMIN' || topRole === 'MANAGER' ? '/command' : '/fly';
```

The rule is written, tested, and **never fires**, because `authEnabled` is `false` on the shipped
default (J1) — every persona, including the dev admin, lands on `/fly`.

**Verdict: correct code, dead by configuration.** Fixing D1 turns it on.

### 0.6 J5 — day two, a shift

- The sidebar filters on one boolean: `NavEntry.managerOnly` → `canManageOrg(topRole)` =
  `ADMIN || MANAGER` (`shared/ui/app-sidebar/app-sidebar.ts:157,168`). One granularity for the whole
  application.
- 14 routes carry `orgGuard` (also `ADMIN || MANAGER`). **~17 real pages carry no role guard at
  all** — `/command`, `/fly/:assetId`, `/live/:deviceId`, `/assets`, `/wall`, `/replay`,
  `/manage/controller`, `/manage/system`, `/settings`… all reachable by typing the URL, and
  `canActivate` (never `canMatch` — zero occurrences in the codebase) still fetches the lazy chunk.
- After lunch the browser's session may be gone. Nothing in the SPA notices — J6a.

**Verdict: one gate, two roles, and no revalidation.** `auth.ready` is a boot-time promise resolved
once; neither `authGuard` nor `orgGuard` ever asks the server again, so a role change or a
revocation mid-session is invisible until a full reload.

### 0.7 J6 — the hard edges

#### J6a — the session ends mid-flight

**What must never happen.** A pilot with a drone in the air loses the cockpit.

- There is **no HTTP interceptor of any kind** (`app.config.ts:22` is a bare
  `provideHttpClient(withFetch())`; zero matches for `Interceptor` in `src/app/`). A mid-session 401
  on any endpoint but `/api/auth/me` and `/api/auth/login` is handled nowhere. It surfaces through
  `core/api-error.ts:26` as **"The device rejected these credentials."** — copy written for an RTSP
  camera — in a toast. Nothing redirects, nothing re-authenticates, nothing tells the truth.
- The **control socket survives, by accident.** `ManualControlHandshakeInterceptor:62-69` reads
  `CurrentUser` once, at handshake, and stashes `UserId` + `VisibilityScope` in
  `WebSocketSession#getAttributes()` for the connection's life. Nothing refreshes it, so an expired
  session, a logout in another tab, or a revoked assignment does not close a live RC link. This is
  the right behaviour and it is **undocumented, untested and unowned** — one refactor toward the SSE
  path's *deliberate* per-delivery re-resolution (`api/live/LiveAssetAccess.java:59-67`, 5 s TTL)
  would silently start yanking control from pilots.
- **SSE dies and stays dead.** `LiveStore` connects from its own root-injectable constructor at app
  boot — before any session. A 401 closes the `EventSource` (a browser never retries a non-2xx SSE
  handshake) and the store waits out its own 60 s timer; a successful login does not force a
  reconnect; `logout()` never tears it down. It also paints *"Live updates disconnected"* behind the
  login screen on an anonymous first load.
- **The session does not survive the station.** `SecurityConfig:109` returns a plain
  `HttpSessionSecurityContextRepository`: in-memory Tomcat sessions, no Spring Session, no shared
  store, and **no `server:` block in any yaml in the repo** — so Boot's 30-minute idle default
  applies and the cookie is an unconfigured `JSESSIONID`. A restart, a redeploy, or a second app
  instance logs out every pilot at once, mid-flight, in a platform CLAUDE.md requires to be scalable
  across servers.

**Verdict: the aircraft is safe today by luck, and the operator is told a lie about a camera.**
See **D8**, **D9**, and the frozen rule in §3.7.

#### J6b — the Wall as a kiosk

`features/wall/` is a real page behind the ordinary `authGuard`, with no kiosk path, no display
identity and no long-lived session (`kiosk` has zero occurrences in `src/`). A wall display needs a
human to type a password into it, and when its 30-minute session lapses it does not prompt — it
silently degrades to stale tiles plus the "Live updates disconnected" banner.

**Verdict: the Wall cannot be a wall.** See **D11**, and `Role.VIEWER` + kiosk sessions in §3.6.

#### J6c — a forgotten password on an offline field station

No self-service change (`features/settings/account-settings.html` has Appearance, Interface,
Notifications and System — not one credential control), no admin reset, no invite flow
(`org-settings.html:58-61` says so on the page itself), and no email to send anything to. A station
in a field has no route back in except a `psql` `UPDATE` with a hand-generated BCrypt hash.

**Verdict: absent.** See **D13**, and §3.5's three password endpoints.

#### J6d — what an unauthenticated person sees

`/` → `landingGuard` → `/fly` → `authGuard` → `/login?returnUrl=/fly`. Two guard hops, and the
recorded `returnUrl` is `/fly` even for a commander who would have landed on `/command`. Behind the
login card, `LiveStore` has already fired `/api/live`, taken its 401 and raised the degraded banner.
The `**` not-found route sits outside `authGuard` entirely.

**And one real leak:** `/hls/**` is **not** inside the secured chain's matchers at all — see **D10**.

**Verdict: scruffy at the door, and one window is open.**

---

## 1. The users and their jobs

One question per persona. The plan is judged on whether each can be answered honestly.

1. **Admin — "who is on my station, and can I change it?"** Needs a first-boot path that is not a
   published password, a people list where a role can be edited, and a way to reset a password with
   no internet.
2. **Commander — "who is flying what, and may I stop them?"** Needs authority distinct from
   visibility, so "I can see it" stops meaning "I can fly it".
3. **Pilot — "is this aircraft mine, and will anything take it from me?"** Needs §3.7: nothing about
   sessions, logins or admin actions reaches into a flight in progress.
4. **Crew — "may I run the camera without being able to arm?"** Needs a per-asset seat, not a role.
5. **Viewer / the Wall — "may I be a screen?"** Needs an identity that sees everything, changes
   nothing, and never expires.

---

## 2. Diagnosis

Evidence is file-cited. Consequence is written from the persona's seat.

### 2.1 Defects

| # | Defect | Evidence | Consequence |
|---|---|---|---|
| **D1** | **The station ships unsecured, and its own config file says the opposite three lines above.** `application.yaml:152-166` documents `enabled` as *"true (default, set explicitly)… the safe default for a platform deployed onto operator-controlled servers"* and then sets `enabled: false`. The compiled filter-chain default **is** secure (`SecurityConfig:77,107`, `matchIfMissing = true`). The override was added by commit `23d13895` *"feat(operator-ux-5): W1 idle usages close at their last activity"* — an unrelated feature — silently undoing [`ARCHITECTURE-AUDIT-2026-08-26.md`](ARCHITECTURE-AUDIT-2026-08-26.md) **R7**. Its stated justification (*"~26 `@SpringBootTest` classes rely on that default"*, `docker-compose.yml:515-521`) **was already false when it landed**: R7 created `station/vision-app/src/test/resources/application.properties:24` precisely so tests could hold the flag off while production held it on. | `station/vision-app/src/main/resources/application.yaml:152-166`; `station/vision-app/.../config/SecurityConfig.java:65-78,107`; `git show 23d13895 -- station/vision-app/src/main/resources/application.yaml`; `station/vision-app/src/test/resources/application.properties:16-24` | Every deployment that does not know one environment variable is wide open, and the file an operator would read to check tells them they are safe. **One line, zero test impact.** |
| **D2** | **`vision.auth.enabled` has three different defaults, and "absent" means permanent lockout.** There is **no `@ConfigurationProperties` record** for `vision.auth` — 23 sibling `Vision*Properties` records exist and this namespace is not one of them. The single key is read four ways with three answers: `SecurityConfig:77,107` → secured (`matchIfMissing = true`); `AuthWiringConfiguration:110,124` → **dev principal + no-op authenticator** (`matchIfMissing = false`); `AuthController:59` → `@Value("${vision.auth.enabled:false}")`, so `login` returns the dev admin **without ever calling `sessionAuthenticator.login`**. With the key absent: `/api/**` demands a session, and login can never mint one. | `station/vision-app/.../config/SecurityConfig.java:67,77,107`; `station/vision-app/.../config/wiring/AuthWiringConfiguration.java:110,117,124,131`; `station/vision-api/.../controller/AuthController.java:59,81-83` | Masked today only because `application.yaml:166` sets the key explicitly. It also means **D1 cannot be fixed by editing one line** — flipping the value without unifying the defaults leaves the trap armed for anyone who deletes the key. CLAUDE.md rule 1's answer is the missing properties record. |
| **D3** | **Turning security on makes the station inoperable.** `AuthController` has three endpoints. `DefaultUserService#create` demands `acting.canManageOrg()`. With auth on and an empty `users` table there is no principal, therefore no scope, therefore no first user. The only path is `VISION_PERSISTENCE_SEED_DEV_USERS=true` → `admin/admin`. | `station/vision-api/.../controller/AuthController.java:77-114`; `contexts/vision-identity/.../DefaultUserService.java:37-53`; `storage/persistence/.../db/seed/dev/V90001__dev_accounts.sql:3-4` | D1 cannot be fixed without D3. Together they are why the flag was flipped rather than the flow finished. |
| **D4** | **The command gate is the visibility predicate.** Both command services call `scope.includes(assetId, ownership)` — the same predicate scoped *reads* use — and neither reads a role. `VisibilityScope#canManage`/`#canAdminister` are `false` for `ASSIGNED_ASSETS` *by construction and by design* (`:186-190`: *"that is the whole of a pilot's authority"*), so **there is no authority predicate a pilot could ever pass**, and `includes` is the only thing left. | `contexts/vision-flight/.../DefaultFlightCommandService.java:219-232`; `.../DefaultManualControlService.java:235-243`; `core/vision-platform/.../VisibilityScope.java:33-37,114-122,186-190` | *Seeing an aircraft is flying it.* A crew seat and a viewer account are not merely missing — they are **unrepresentable**. This is the structural root of the audit's "crew is not a role", and of `CREW-CONTROL` IC-2. |
| **D5** | **Role never reaches a seam that could ask for it, and the role data that *is* computed is dead.** `CurrentUser` exposes `userId()`, `ownership()`, `scope()`, `viewer()`. The acting `Role` **is already resolved on every request** — `PrincipalResolver#viewer()` returns `MapAccessPolicy.Viewer(userId, groups, topRole)` — but is reachable only through the map's model. Separately, `VisionUserDetails#getAuthorities():60-65` emits `ROLE_ADMIN`/`ROLE_MANAGER`/`ROLE_PILOT` into every `Authentication` token and **no rule anywhere reads them**: method security is not enabled and the repo's only `@PreAuthorize` mention is a javadoc sentence explaining its absence. | `station/vision-api/.../security/{CurrentUser,PrincipalResolver}.java:52-69`; `contexts/vision-map/.../MapAccessPolicy.java:73`; `station/vision-app/.../security/VisionUserDetails.java:60-65`; `station/vision-api/.../controller/AfterActionController.java:29` | The one fact every gate in §3.8 needs is computed on every request, written into the security token, and thrown away twice. The fix is a **lift**, not a new resolution path. |
| **D6** | **The client re-derives authority from a role string, because the wire carries nothing else.** `MeResponse` is `{userId, username, displayName, email, memberships, topRole, authEnabled}` and states in its own doc *"No visibility scoping rides on this type"*. `auth.user()?.topRole` is read 15+ times; `canManageOrg`'s expression is hand-inlined at `fly-logic.ts:504`; there is no `AuthStore.topRole()`/`can()` accessor. **One confirmed live drift:** the geo-regions UI gates ingest and delete on `canManageOrg` (ADMIN‖MANAGER) while the server requires `canAdminister()` (ADMIN only) — the facade's own comment admits it. | `station/vision-web/src/app/core/api/models.ts:3008-3021`; `features/geo/region-manager-facade.ts:29-35,60`; `station/vision-api/.../controller/GeoRegionController.java:114-118`; `features/fly/fly-logic.ts:504`; `features/models/models-logic.ts:48`; `shared/map/map-controls/layer-manager.ts:163-165` | Every gate finer than "ADMIN or MANAGER" must be approximated or inlined. Drift is not a discipline failure here; it is the only thing the contract permits. |
| **D7** | **Assignment carries no seat.** `pilot_assignments` is `(pilot_user_id, asset_id, assigned_at)` with no role column, so being assigned means full command authority. `pilotId` on `AssetUsage` — the closest thing to "who is flying" — is **attribution only, first-write-wins, read by no conditional anywhere**. | `storage/persistence/.../db/migration/V9__pilot_assignments.sql:19-24`; `contexts/vision-identity/.../domain/port/AssignmentRepositoryPort.java`; `contexts/vision-warehouse/.../AssetUsage.java:49-60`; `contexts/vision-perception/.../UsageTracker.java:314-336` | The one column that would answer `CREW-CONTROL` IC-2 is the one column the table does not have. **This is the cheapest fix in the plan** (§3.4). |
| **D8** | **A session that ends mid-use is invisible to the app.** No HTTP interceptor exists. A mid-session 401 renders `core/api-error.ts:26` — *"The device rejected these credentials."* — as a toast. `auth.ready` resolves once; guards never revalidate. `LiveStore` opens `/api/live` from its constructor at boot, takes a 401, goes `'closed'`, and waits ≤60 s; login does not force a reconnect; `logout()` never tears it down. | `station/vision-web/src/app/app.config.ts:22`; `core/api-error.ts:25-26`; `core/auth/auth-guard.ts:20-30`; `core/live/live-store.ts:107-116,214-221,423-424`; `core/auth/auth-store.ts:114-124` | A pilot whose session lapses is told their *camera* rejected a password. Nobody reaches a login screen; the app just starts failing. |
| **D9** | **Sessions do not survive the station.** `HttpSessionSecurityContextRepository` over in-memory Tomcat sessions; no Spring Session, no shared store, **no `server:` block in any yaml in the repo** (so Boot's 30-minute idle default and an unconfigured `JSESSIONID`). | `station/vision-app/.../config/SecurityConfig.java:102-110`; no `spring-session` dependency in any `pom.xml` | A restart, a redeploy or a second instance logs out every pilot at once — while they are flying. Directly contradicts CLAUDE.md *"Deployment maintenance: … the module should be scalable"*. |
| **D10** | **`/hls/**` is outside the security matchers, and its own guard no-ops for the ids that matter.** The secured chain matches only `/api/**` and `/ws/**`; `/hls/**` falls to `anyRequest().permitAll()`. Its sole guard is `HlsProxyController#requireVisibleStream` → `StreamAccess.requireVisible(StreamId)`, which that class's own javadoc documents as **a no-op for a stream id that is unknown or not currently running** — so for such an id `currentUser.scope()` is never called and the proxy fetches from mediamtx **with the app's own `vision-viewer` Basic credential attached**. Worse, with auth on and no session the scope lookup throws `IllegalStateException` → **409**, not 401. | `station/vision-app/.../config/SecurityConfig.java:91-92`; `station/vision-api/.../proxy/HlsProxyController.java:258,285,359-366`; `station/vision-api/.../security/StreamAccess.java:44-52,109-114`; `station/vision-api/.../exception/ApiExceptionHandler.java:84-86` | LIVE-SCOPE W4 scoped this endpoint; the matcher gap and the unknown-id path survived it. An unauthenticated caller can drive an authenticated proxy. |
| **D11** | **The Wall needs a human to log it in, and expires.** No kiosk route, no display identity, no long session; ordinary `authGuard`, ordinary 30-minute session, silent degradation. | `station/vision-web/src/app/features/wall/wall.routes.ts:9-17`; `app.routes.ts:92`; zero matches for `kiosk` | An always-on display is not a supported deployment. |
| **D12** | **Session-cookie auth with no fixation protection, CSRF disabled, and no cookie policy.** `sessionManagement()` is never called, and `SecuritySessionAuthenticator:47-54` writes the `SecurityContext` **directly** rather than through an authentication filter — so Spring Security's default `changeSessionId` strategy never runs and the pre-login session id survives login. Both chains call `csrf.disable()`; nothing sets `same-site`, `http-only` or `secure`. | `station/vision-app/.../config/SecurityConfig.java:70,81` (and the absence of `sessionManagement`); `station/vision-app/.../security/SecuritySessionAuthenticator.java:47-54` | Session fixation is real, not theoretical. CSRF is mitigated only by modern browsers' `SameSite=Lax` default for unmarked cookies — i.e. by the browser, not by the station. §3.6 fixes both cheaply. |
| **D13** | **A password can never be changed or reset, and a temporary password is permanent.** No self-service endpoint or UI, no admin reset, no forced change at first login, no invite flow. | `station/vision-api/.../AuthController.java` (3 endpoints); `features/settings/account-settings.html`; `features/org-settings/org-settings.html:58-61` | On an offline field station a forgotten password is a `psql` job, and the password an admin typed into a form once is the pilot's password forever. |
| **D14** | **Roles are write-once.** Memberships are set in the create form and edited nowhere; no delete-user, no second membership, no role change, and no group rename/delete/re-parent. | `features/org-settings/org-settings.html:56-172,209-219`; `features/org-settings/org-settings-facade.ts:54,119-133` | A promotion is impossible; so is correcting a mistake. |
| **D15** | **Nothing about identity is ever audited.** `AuditAction` = `{CREATED, UPDATED, DEACTIVATED, ACTIVATED, DELETED, RESTORED}`; `AuditTargetType` = `{ASSET, DEVICE, DATASET, MODEL}`. `vision-identity` writes **zero** audit entries (its only `AuditTrailPort` contact is `DefaultActivityService`'s read side). `AuthController` contains no audit call. `DefaultUserService`/`DefaultGroupService`/`DefaultAssignmentService` enforce their gates and record nothing — neither grants nor denials. | `core/vision-platform/.../AuditAction.java:11-29`; `.../AuditTargetType.java:13-23`; `contexts/vision-identity/.../DefaultActivityService.java:19-28` | The trail records *command* refusals but has no login, no failed login, no role grant, no assignment change. After an incident, "who was allowed to fly that" is unanswerable. |
| **D16** | **The auth-off → auth-on transition has a visibility cliff, and no test covers it.** Every asset created while auth is off is owned by `DevPrincipal.GROUP_ID` (`UUID(0,1)`). `V13__identity_baseline.sql:17-19` now seeds that exact group unconditionally, and `V16__adopt_fixed_root.sql:51-76` repairs a single pre-existing random root — but [`OPS-UX-PLAN.md`](../done/OPS-UX-PLAN.md) §5b wave E items (2)–(4) are **open and unowned**: no reconciliation for a station carrying a *stale random-id* root group, and **no end-to-end regression test that an asset created with auth off is visible to a MANAGER of root after auth is enabled**. | `station/vision-app/.../devsupport/DevPrincipal.java:24-30`; `storage/persistence/.../V13__identity_baseline.sql:17-19`, `V16__adopt_fixed_root.sql:51-76`; `docs/plans/done/OPS-UX-PLAN.md:177-207` | This is **exactly the transition this plan performs** (B0b). Flipping the flag without that test risks every demo-era drone going silently invisible to every manager. |
| **D17** | **RC exclusivity is one session per *process*, not per aircraft, and identity-blind.** `DefaultManualControlService:229-233` guards a single `activeSession` field on a singleton, so a second operator is refused **even on a different aircraft**, while REST `arm`/`disarm`/`mode`/`rtl`/`estop`/`aux` have no arbitration at all. `AssetSessionController#disengage` takes **no actor** — any in-scope caller can end anyone's session. | `contexts/vision-flight/.../DefaultManualControlService.java:71-79,229-233`; `station/vision-api/.../controller/FlightCommandController.java:73-130`; `.../AssetSessionController.java:112-125` | Confirms `ARCHITECTURE-AUDIT-2026-08-26.md` **A4** and sharpens it. **CREW-CONTROL owns the fix** (seats); this plan owns only the *actor* on `disengage`, which is attribution. |
| **D18** | **Two smaller, named authority leaks.** (a) `AuditController:70-72` gates on `canManageOrg()`, so an admitted MANAGER reads **the whole fleet's** audit trail, not their subtree — self-documented as deferred U-SCOPE cleanup. (b) **12 handlers have no authority check of any kind**, carried as a build-enforced ledger nothing shrinks. | `station/vision-api/.../controller/AuditController.java:70-72`; `station/vision-app/src/test/java/.../EndpointAuthorizationTest.java:53-67` | (a) is one call site and rides B6. (b) is *twelve product decisions*, pre-existing and outside this plan — §5. |

### 2.2 The 2026-08-21 platform audit, re-checked against today's code

The mandate asks which findings are still true. They are not all still true — LIVE-SCOPE shipped.

| Audit finding | Today | Evidence |
|---|---|---|
| **T1 "the live-operations surface has no authority"** (29 handlers; streams, SSE, HLS, mediamtx) | **Largely remediated.** [`LIVE-SCOPE-PLAN.md`](../done/LIVE-SCOPE-PLAN.md) W1–W5 landed `@OpenByDesign`, the `EndpointAuthorizationTest` ArchUnit rule, `StreamAccess`/`LiveAssetAccess`, mediamtx credentials, and device+geofence authority; R7 closed the last four live entries. **Two residues:** the 12-entry ledger (D18b) and the `/hls/**` matcher gap (D10). | `EndpointAuthorizationTest.java:25-67`; `api/live/LiveAssetAccess.java:59-67`; `application.yaml:285-310` |
| **T2 "the client promises authority the server refuses"** (6 drifted SPA gates) | **Partly remediated; the mechanism is untouched.** LIVE-SCOPE W6 realigned them — `models-facade.ts:87` now uses `canAdministerRegistry`, and `onboarding.routes.ts:16` gained `orgGuard`. One confirmed drift remains (geo regions, D6), and the cause is unchanged: `MeResponse` carries no permission finer than `topRole`. *(Do not re-file UI Defects 1–3 as open; `devices.routes.ts:13` deliberately traded its route guard for a tab-level gate.)* | `features/geo/region-manager-facade.ts:29-35`; `core/api/models.ts:3008-3011` |
| **"Crew is not a role"** | **Still true, and now explained.** D4 is why: even adding the constant would change nothing, because the gate reads visibility. `CREW-CONTROL` IC-2 forbids the constant outright; §3.4 answers it on the assignment instead. | `contexts/vision-identity/.../domain/model/Role.java`; `AssignmentRepositoryPort.java` |
| **A4 "two pilots can command one aircraft; no arbitration exists"** | **Still true, and worse than recorded** — D17. | `DefaultManualControlService.java:229-233` |
| **"asset-unit leases" (DOMAIN-SEPARATION)** | **Zero code.** *"Specced only… zero lease code exists."* No `Lease`/`Claim`/`Seat`/`Reservation` type exists in the tree. | `docs/plans/active/asset-flows/O1-SYNTHESIS.md:133` |
| **A1 "four parallel access mechanisms; no single place answers *who can see X*"** | **Still true, and this plan reduces it to three** by giving every per-asset *command* question one seam (§3.9). Group-scope, `Ownership` and `MapAccessPolicy` remain distinct, deliberately. | `ARCHITECTURE-AUDIT-2026-08-26.md:163-166` |

---

## 3. The target model

### 3.1 Two axes, two seams, and nothing else

```
         WHAT MAY I SEE?              WHAT VERBS DO I HAVE?         ON THIS AIRCRAFT?
         ────────────────             ─────────────────────         ─────────────────
         VisibilityScope              Authority                     AssetAuthority
         (platform, UNCHANGED)        (platform, NEW)               (vision-api bean)
         UNBOUNDED/GROUPS/            Set<Capability>               mayFly · mayOperateCamera
         ASSIGNED_ASSETS              from Role                     · mayForceSeat
              │                            │                              │
              └────── scope() ─────────────┘                     capabilities ∧ scope
                                                                  ∧ AssignmentRole
```

**The division, stated once so it is never ambiguous:**

- **`Authority` (a threaded value, `vision-platform`)** answers questions about the **deployment and
  the org** — may this caller manage users, manage the fleet, administer the station. It replaces
  `VisibilityScope`'s three authority predicates one-for-one.
- **`AssetAuthority` (a request-scoped bean, `vision-api`)** answers every question about **one
  specific aircraft**. Its interface is **imported from
  [`CREW-CONTROL-PLAN.md`](CREW-CONTROL-PLAN.md) §3.7 verbatim**; this plan supplies the real
  implementation behind it (§3.9).
- **`VisibilityScope`** keeps answering, unchanged, *what may I see*. It gains no field — its
  MODULE.md forbids exactly that.

**The law.** A gate never asks for a role. Role appears in exactly two places: the `User` aggregate
that stores it, and the one pure table that turns it into capabilities.

### 3.2 The role set — FROZEN

```java
public enum Role { VIEWER, PILOT, MANAGER, ADMIN }   // least → most AUTHORITY
```

**One constant, prepended.** No `CREW` — [`CREW-CONTROL-PLAN.md`](CREW-CONTROL-PLAN.md) IC-2 forbids
it, [`OPS-UX-PLAN.md`](../done/OPS-UX-PLAN.md) wave D declined it, and `asset-flows/P-PROPOSAL.md`
lists it under "Not proposed". §3.4 answers crew on the assignment, where it belongs.

`VIEWER` is a different question — *"this account may never command anything, anywhere"* — which is
per-account, not per-asset, and therefore genuinely role-shaped. It is safe to add because the
refusal's stated reason does not apply to a **prepend**:

- `Role`'s own javadoc states the constraint precisely — *"reordering these constants would
  silently change what 'top' means"* — and a **prepend is not a reorder**: the relative order of
  `PILOT < MANAGER < ADMIN` is untouched, so `User#topRole()` picks the same winner for every
  existing user. Nothing outranks `ADMIN`.
- **Verified, not assumed:** `User#topRole()` is `max(Comparator.naturalOrder())` — *relative*, not
  an absolute ordinal. A repo-wide grep for `.ordinal()` finds exactly two call sites, both in
  `MapAccessPolicy:166,176` and both on `AccessLevel`, not `Role`; there is no `Role.values()[…]`
  anywhere. No ordinal is persisted or compared to a literal.
- Roles persist **by name**: `V90001__dev_accounts.sql` stores `"role":"ADMIN"` in the memberships
  `jsonb`, and `DefaultUserService#maxGrantableRole` switches on `Kind` and returns a Role by name.

Ordinal order is **authority**, not visibility. `VIEWER` sits at the bottom and (once B6 lands) sees
the widest picture of the lower roles. That inversion is the whole point of §3.1 and must be written
into `Role`'s javadoc, because it reads wrong otherwise.

### 3.3 Capabilities — FROZEN

New in `core/vision-platform`, a sibling of `VisibilityScope`:

```java
public enum Capability { OPERATE_PAYLOAD, COMMAND_FLIGHT, MANAGE_FLEET, MANAGE_ORG }

public record Authority(VisibilityScope scope, Set<Capability> capabilities) {
    boolean mayManageOrg();              // MANAGE_ORG   && scope.canManageOrg()
    boolean mayManageFleet(Ownership o); // MANAGE_FLEET && scope.canManage(o)
    boolean mayAdminister();             // MANAGE_ORG   && scope.canAdminister()
    VisibilityScope scope();             // the read axis, unchanged
    static Authority full();             // unbounded + every capability
}
```

`Authority` **wraps** `VisibilityScope`; it never copies or replaces it. It deliberately carries
**no per-asset command verb** — those live on `AssetAuthority` (§3.9), so there is exactly one place
to look for "may this person fly this aircraft".

**`Authority.full()` is the identity element and the whole guardrail.** The dev principal, every
`PrincipalResolver.fixed(...)`, and every existing test double get it, so **every existing test
stays green with no change** — the same trick U-SCOPE used with `VisibilityScope.unbounded()`. There
is no new feature flag: the switch that already exists (`vision.auth.enabled`) is the switch, and
`CREW-CONTROL`'s own `vision.crew.enabled` guardrail is untouched.

**Role → capabilities — FROZEN**, one pure function in `vision-identity`
(`application.scope.RoleAuthority#capabilitiesOf(Role)`) — granting authority from a role is org
policy, exactly the argument that already keeps `maxGrantableRole` on `DefaultUserService`:

| Role | OPERATE_PAYLOAD | COMMAND_FLIGHT | MANAGE_FLEET | MANAGE_ORG |
|---|:--:|:--:|:--:|:--:|
| `VIEWER` | – | – | – | – |
| `PILOT` | ✓ | ✓ | – | – |
| `MANAGER` | ✓ | ✓ | ✓ | ✓ |
| `ADMIN` | ✓ | ✓ | ✓ | ✓ |

`MANAGER` and `ADMIN` are separated on the **scope** axis (`GROUPS` vs `UNBOUNDED`, i.e.
`canAdminister()`), not by a capability — no fifth constant.

`ScopeResolver` gains one method beside the one it has:

```java
VisibilityScope scopeFor(User user);      // unchanged
Authority       authorityFor(User user);  // NEW: scopeFor(user) + capabilitiesOf(user.topRole())
```

### 3.4 The seat — FROZEN, and it answers CREW-CONTROL IC-2

```java
public enum AssignmentRole { PILOT, CREW }   // identity.domain.model — no ordinal semantics
```

- `PILOT` — may hold the FLIGHT seat and the CAMERA seat on that asset.
- `CREW` — may hold the CAMERA seat only.

**Migration:** `ALTER TABLE pilot_assignments ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'PILOT'`
(next free Flyway version — master is at **V32**, so `V33`; the numbers in CREW-CONTROL's and
OPS-UX's prose are historical). Every existing row becomes `PILOT`, so **behaviour is unchanged and
every existing test stays green** — the same guardrail as `Authority.full()`.

**Port changes** (`AssignmentRepositoryPort`), additive except one replacement:

```java
void assign(UserId, AssetId, AssignmentRole);          // REPLACES the 2-arg form
Optional<AssignmentRole> roleFor(UserId, AssetId);     // NEW — the IC-2 answer
List<Assignment> assignmentsForAsset(AssetId);         // NEW — the roster, with roles
// unassign / assetsForPilot / pilotsForAsset / isAssigned unchanged
```

`ScopeResolver#scopeFor` keeps using `assetsForPilot(user)` — **both** seats are visible; only the
verb differs. That is the whole design: **visibility comes from the assignment, authority comes from
the capability, and the seat narrows the verb.**

**Why not on `Role`** — restating IC-2 so no implementer has to go looking: a role is global, a seat
is per-aircraft, and the same person is routinely pilot on one drone and crew on another. A role
constant could not express that even if the ordinal contract permitted it.

### 3.5 The wire contract — FROZEN

**`MeResponse` — additive; existing fields byte-identical:**

```jsonc
{
  "userId": "…", "username": "…", "displayName": "…", "email": "…",
  "memberships": [ { "groupId": "…", "groupName": "…", "role": "PILOT" } ],
  "topRole": "PILOT",
  "authEnabled": true,
  "capabilities": ["OPERATE_PAYLOAD", "COMMAND_FLIGHT"],  // NEW — string[], server-computed
  "scopeKind": "ASSIGNED_ASSETS",                          // NEW — UNBOUNDED|GROUPS|ASSIGNED_ASSETS
  "mustChangePassword": false                              // NEW — boolean
}
```

`capabilities` is the **only** thing the client may gate on. `scopeKind` lets the client express
`mayAdminister` (`scopeKind === 'UNBOUNDED' && capabilities.includes('MANAGE_ORG')`) without a role
literal — it retires the last two (`models-logic.ts:48`, `layer-manager.ts:165`). `topRole` and
`memberships` remain, for display only.

**New endpoints — paths, bodies and status codes frozen:**

| Method + path | Auth | Request | Success | Failures |
|---|---|---|---|---|
| `GET /api/auth/bootstrap` | anonymous (`@OpenByDesign`, `permitAll` in the secured chain) | — | `200 {"required": true|false}` | — |
| `POST /api/auth/bootstrap` | anonymous, **only while `required`** | `{"username","displayName","email","password"}` | `201 MeResponse` (session established) | `409 {"code":"ALREADY_INITIALIZED"}` · `400 {"code":"WEAK_PASSWORD"}` |
| `POST /api/auth/password` | session | `{"currentPassword","newPassword"}` | `204` (clears `mustChangePassword`) | `401` wrong current · `400 {"code":"WEAK_PASSWORD"}` · **`409 {"code":"AUTH_DISABLED"}`** when `vision.auth.enabled=false` |
| `POST /api/users/{userId}/password` | `MANAGE_ORG` | `{"newPassword"}` | `204` (sets `mustChangePassword=true`) | `403` · `404` unknown/invisible · `400 {"code":"WEAK_PASSWORD"}` |
| `PUT /api/users/{userId}/memberships` | `MANAGE_ORG` | `{"memberships":[{"groupId","role"}]}` | `200 UserResponse` | `403` out of scope or above `maxGrantableRole` · `404` |
| `PUT /api/assets/{assetId}/pilots/{userId}` | existing gate | **`{"role"?: "PILOT"\|"CREW"}`** — absent body or field ⇒ `PILOT` | `200`, byte-identical to today for an absent body | unchanged |
| `POST /api/auth/login` | anonymous | **`{"username","password","kiosk"?}`** — `kiosk` optional, default `false` | `200 MeResponse` | `401` · `400 {"code":"KIOSK_NOT_PERMITTED"}` when a non-`VIEWER` requests `kiosk` |

`PilotResponse`/`AssignmentResponse` gain `role`. `required` is defined once, exactly:
**`vision.auth.enabled && no enabled user holds an ADMIN membership`**. It is a one-way latch — once
an admin exists it is `false` forever and the `POST` 409s. First-come-wins on a fresh station, the
same contract a home router's setup page has; the UI says so rather than pretending otherwise.

The `409 AUTH_DISABLED` on self-service password change is deliberate and borrowed verbatim from
[`CREW-CONTROL-PLAN.md`](CREW-CONTROL-PLAN.md)'s own retired §4: *changing a password on an
unsecured station is a lie — refuse honestly.*

Password policy: `vision.auth.password.min-length`, default **12**, no composition rules
(CLAUDE.md rule 1 — the number is config, not a constant buried in a validator).

### 3.6 Session mechanics — FROZEN

| Decision | Value | Why |
|---|---|---|
| **One default, one place** | **New `VisionAuthProperties` record** (`prefix = "vision.auth"`) joining its 23 siblings, holding `enabled`, `session.*` and `password.*`. `SecurityConfig`, `AuthWiringConfiguration` and `AuthController` all read **it**, never `@ConditionalOnProperty`'s or `@Value`'s own default. | D2. Three components cannot disagree about a value that exists once. |
| **Store** | **Spring Session JDBC on the existing Postgres.** The `SPRING_SESSION`/`SPRING_SESSION_ATTRIBUTES` DDL is copied into a new Flyway migration — **Flyway owns it**; `spring.session.jdbc.initialize-schema` stays `never`. | Fixes D9's three halves at once: a restart no longer logs out a flying pilot, a second app instance works, and it lands on the one store the platform already mandates. |
| **Idle timeout** | `vision.auth.session.idle-timeout`, default **12h**. Idle only — **no absolute expiry**. | A shift, not a browser session. An absolute expiry is precisely the thing that would fire mid-flight, and §3.7 would then have to invent a silent-renewal backdoor. Not having one is better than mitigating one. |
| **Kiosk** | `POST /api/auth/login` with `"kiosk": true`, permitted **only** when the user's top role is exactly `VIEWER`; that session gets `vision.auth.session.kiosk-idle-timeout`, default **365d**. | D11, with the smallest surface: no display tokens, no anonymous path, a real auditable account that can be disabled. |
| **Fixation + cookie** | Login rotates the session id explicitly before writing the `SecurityContext`; `server.servlet.session.cookie.http-only: true`, `same-site: strict`, and `secure` bound to `vision.auth.session.cookie-secure` (default `false` — field stations run plain HTTP on a LAN; a TLS deployment sets it). | D12. Full CSRF tokens stay deferred (§5) with the trigger that would change that named. |
| **Logout** | invalidates server-side (already does) **and** the SPA tears down SSE and releases an engaged control session before navigating. | D8. |

### 3.7 The mid-flight rule — FROZEN

> **A session ending never takes the aircraft.**

Three clauses, each independently testable:

1. **The control socket is never closed by session state.** `/ws/manual-control` authenticates once,
   at handshake, and what `ManualControlHandshakeInterceptor` stashes is authoritative for that
   connection's lifetime. Session expiry, a logout in another tab, an admin disabling the account,
   or a revoked assignment must not close it, refresh it, or degrade it. Only the deadman watchdog,
   an explicit `release`, or the socket itself ends manual control.
   *This already holds — by accident. This plan makes it deliberate, writes it into that
   interceptor's javadoc, and pins it with a test, so the next refactor toward `LiveAssetAccess`'s
   per-delivery re-resolution cannot silently start yanking control.*
2. **The cockpit is never unmounted by a 401.** The new 401 seam (D8) shows a **re-authenticate
   overlay in place** whenever the active route is `/fly/:assetId` **and** `ManualControlClient` is
   not `'idle'` — never `router.navigateByUrl('/login')`, never a component teardown, never a lost
   video element. Everywhere else a 401 routes to `/login?returnUrl=<url>` as normal.
3. **A revocation takes effect at the next engage, not during this one**, and the UI says so.
   Disabling a user, changing their role, or downgrading their assignment to `CREW` is immediate for
   every *new* authority decision and inert for a control session in progress. The admin who clicks
   Disable on a flying pilot sees: *"<name> is flying <asset>. They keep control until they land or
   release; they cannot take control again."* — the honest sentence, not a fake instant revocation.

**Deliberately not built here:** a forced takeover. `mayForceSeat` is `CREW-CONTROL`'s verb on
`CREW-CONTROL`'s surface; this plan only supplies its answer (§3.9).

### 3.8 The endpoint × authority matrix — FROZEN

**Why no filter and no annotation.** [`LIVE-SCOPE-PLAN.md`](../done/LIVE-SCOPE-PLAN.md) §2 rejected
declarative role rules because authority here is *data-dependent* (who owns the asset behind this
stream). A URL→capability `SecurityFilterChain` rule would be a **second** enforcement mechanism and
a second place to look — the A1 defect, freshly made. This plan therefore folds the verb into the
predicate the call site already makes. `EndpointAuthorizationTest.AUTHORITY_METHODS` gains
`"authority"` and the `AssetAuthority` type.

| URL family | Predicate the handler must reach | Denial |
|---|---|---|
| `/api/auth/login`, `/logout`, `/bootstrap` | none — `@OpenByDesign`, `permitAll` in the secured chain | — |
| reads: `GET /api/assets/**`, `/api/live/**`, `/api/streams/**`, `/api/map/**`, `/api/usages/**` | `scope().includes(...)`, or the existing `StreamAccess`/`LiveAssetAccess`/`MapAccessPolicy` collaborators | **404** (hide existence — unchanged convention) |
| payload: stream start/stop, `/api/cv/**`, tracker control, per-stream profile apply | `assetAuthority.mayOperateCamera(assetId)` | **403** |
| flight: `POST /api/assets/{id}/{arm,disarm,mode,rtl,estop,aux}`, `/ws/manual-control` engage | `assetAuthority.mayFly(assetId)` | **403** (audited, as today) |
| fleet: asset/device/category/geofence/model/dataset/CV-profile writes | `authority.mayManageFleet(ownership)` | **403** |
| org: `/api/users/**`, `/api/groups/**`, `/api/assignments/**` | `authority.mayManageOrg()` (`mayAdminister()` where the code already requires `canAdminister`) | **403** |
| `/hls/**` | **added to the secured chain's `authenticated()` matcher**, and `StreamAccess.requireVisible(StreamId)` fails closed on an unknown or stopped id (D10) | **401** unauthenticated, **404** unknown/invisible |

`AccessDeniedException → 403` and `NoSuchElementException → 404` are unchanged
(`ApiExceptionHandler:67,79-82`). The WebSocket's own second mapping to an `OUT_OF_SCOPE` frame is
unchanged.

**The honest limit of the ArchUnit rule**, to be written into its javadoc: it proves a handler
*reaches* an authority seam, never that it reached the *right* one. A payload endpoint calling
`mayFly` passes the build. Only the per-wave tests in §4 close that.

### 3.9 The join with CREW-CONTROL — the bean swap

[`CREW-CONTROL-PLAN.md`](CREW-CONTROL-PLAN.md) §3.7 declares one imported interface and ships a stub
so the join is a bean swap, not a refactor. **This plan supplies the real bean and changes not one
call site**, exactly as IC-2 requires:

```java
// station/vision-api/src/main/java/com/drones/vision/api/security/AssetAuthority.java  — THEIRS, verbatim
public interface AssetAuthority {
    boolean mayFly(AssetId asset);
    boolean mayOperateCamera(AssetId asset);
    boolean mayForceSeat(AssetId asset);
}
```

| | `ScopeAssetAuthority` (their W2 stub) | **`CapabilityAssetAuthority` (this plan, wave B4)** |
|---|---|---|
| `mayFly` | `scope().includes(asset, ownership)` | `COMMAND_FLIGHT ∈ capabilities` **∧** `scope().includes(...)` **∧** (`scope().kind() != ASSIGNED_ASSETS` **∨** `roleFor(user, asset) == PILOT`) |
| `mayOperateCamera` | same as `mayFly` — *"honest and weaker"* | `OPERATE_PAYLOAD ∈ capabilities` **∧** `scope().includes(...)` |
| `mayForceSeat` | `scope().canManage(ownership)` | `authority.mayManageFleet(ownership)` |

The third conjunct on `mayFly` is the whole of IC-2: the assignment narrows the verb **only for a
caller whose scope is `ASSIGNED_ASSETS`**, because a MANAGER or ADMIN has no assignment row at all
and must keep the authority `canManage` already gives them.

**IC-1 (a second operator is a distinct principal)** is satisfied today when auth is on, and this
plan makes auth-on the default (B0b) — which is what turns CREW-CONTROL from inert to real.
**IC-3 (`UserId` → display name)** is already satisfied by `AuthService#find`.

**Ordering, either way round.** As of this writing **neither file exists** — `find` for
`AssetAuthority.java` / `ScopeAssetAuthority.java` returns nothing, so CREW-CONTROL W2 has not
landed. Whichever plan reaches the path first creates the interface **at that exact path with that
exact signature**; the other finds it already there. If B4 goes first it ships
`CapabilityAssetAuthority` directly and CREW-CONTROL's stub is never written. Neither plan blocks
the other, and neither may change the signature unilaterally.

---

## 4. Waves

Every wave is file-scoped, independently green, and ends with its `MODULE.md` updated.

- **Backend green** = `./mvnw -B -pl <modules> test` — scoped, never reactor-wide while another wave
  holds modules red, and **never `-pl` without `-am` for a module whose dependency this wave just
  changed** (a stale `~/.m2` jar resolves silently).
- **Web green** = `npx tsc --noEmit -p tsconfig.app.json` **and** `-p tsconfig.spec.json`, then
  `npm run test:ci` — **never bare `npx vitest run`**, which fakes ~536 failures.
- Web constraints: 3-file components, Component → Facade → Store → Service layering
  (`core/ui/architecture.spec.ts` guards it), tokens only, `.claude/skills/frontend-style`.

### Round 0

**B0a — config honesty** *(`spring-integrator`)* · scope: `station/vision-app/src/main/resources/application.yaml`
(the `vision.auth` comment block only), `station/vision-app/.../config/SecurityConfig.java` (javadoc),
`station/vision-app/MODULE.md`. Corrects D1's self-contradicting comment to describe what the file
actually does, and adds a boot `WARN` when auth is off, naming the setting and the banner. **Does not
flip the value** — that is B0b, after a station can bootstrap. Green: `./mvnw -B -pl station/vision-app test`.

### Round 1 — the model (B2 follows B1 inside the same module)

**B1 — the authority axis** *(`domain-modeler`)* · scope: **new** `core/vision-platform/.../Capability.java`,
`.../Authority.java` + specs; `core/vision-platform/.../AuditAction.java`, `.../AuditTargetType.java`
(new constants only — `LOGIN`, `LOGIN_FAILED`, `GRANTED`, `REVOKED`; `USER`, `GROUP`, `ASSIGNMENT`);
`core/vision-platform/.../VisibilityScope.java` (`@Deprecated` + javadoc on the three authority
predicates — **no behaviour change**); `contexts/vision-identity/.../domain/model/Role.java`
(prepend `VIEWER`, plus the ordinal-means-authority javadoc); **new**
`contexts/vision-identity/.../domain/model/AssignmentRole.java`;
`contexts/vision-identity/.../domain/port/AssignmentRepositoryPort.java` (§3.4's three changes);
**new** `.../application/scope/RoleAuthority.java` + spec; `.../application/scope/ScopeResolver.java`
+ `DefaultScopeResolver.java` (`authorityFor` only — **precedence unchanged**, see B6);
the `MapAccessPolicy` `VIEWER` test cases in `contexts/vision-map`; the ArchUnit rule forbidding new
`canManageOrg`/`canManage`/`canAdminister` call sites; three MODULE.md.
**The `VIEWER` prepend is pre-verified safe** (§3.2) — the wave still adds a `Role` spec pinning
`topRole()` across a `VIEWER`+`PILOT` membership pair, so a future reorder fails a test rather than
a review.
Green: `./mvnw -B -pl core/vision-platform,contexts/vision-identity,contexts/vision-map -am test`.

**B2 — identity lifecycle** *(`application-service`, after B1)* · scope:
`contexts/vision-identity/.../application/` — `User` gains `mustChangePassword`; `UserService`/
`DefaultUserService` (`setMemberships`, `setPassword`, `createFirstAdmin`); `AuthService`/
`DefaultAuthService` (`changePassword`, `adminExists`); `AssignmentService`/`DefaultAssignmentService`
(the `AssignmentRole` parameter, `roleFor`); audit writes on **every** grant, revoke, seat change,
enable/disable, password change and login outcome (D15); their specs; MODULE.md.
Green: `./mvnw -B -pl contexts/vision-identity -am test`.

### Round 2 — the edge (sequential; both touch `vision-api` + `vision-app`)

**B3 — principal, properties, persistence and wire** *(`spring-integrator`, after B2)* · scope:
**new** `station/vision-app/.../config/properties/VisionAuthProperties.java` and the three read sites
unified onto it (**D2**); `station/vision-api/.../security/{CurrentUser,PrincipalResolver}.java`
(`role()`, `authority()`); `station/vision-api/.../dto/{MeResponse,LoginRequest,PilotResponse,AssignmentResponse}.java`;
`station/vision-api/.../controller/{AuthController,UserAdminController,AssignmentController}.java`
(§3.5's seven rows); `station/vision-app/.../security/{DevPrincipalResolver,SecurityContextPrincipalResolver}.java`;
`station/vision-app/.../config/wiring/AuthWiringConfiguration.java`;
`station/vision-app/.../config/SecurityConfig.java` (`/api/auth/bootstrap` → `permitAll`);
**new** `storage/persistence/.../db/migration/V33__assignment_roles.sql` (+ the `users.must_change_password`
column) with `AssignmentEntity`/`JpaAssignmentRepository`/`UserEntity` and the `vision-app` devsupport
in-memory sibling; `station/vision-app/src/test/java/.../EndpointAuthorizationTest.java`
(`AUTHORITY_METHODS` += `authority`); new `*AuthEnabledTest` coverage for bootstrap/409, password
change, membership edit, seat assignment; three MODULE.md.
Green: `./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app -am test`.

**B4 — the gates** *(`spring-integrator`, after B3)* · scope: **new**
`station/vision-api/.../security/CapabilityAssetAuthority.java` (+ `AssetAuthority.java` itself if
CREW-CONTROL W2 has not landed — §3.9), replacing `ScopeAssetAuthority` in the wiring;
`contexts/vision-flight/.../DefaultFlightCommandService.java:219-232` and
`.../DefaultManualControlService.java:235-243` (the gate moves to the injected authority answer —
per CLAUDE.md rule 10, **bundle, never a second parameter**); their interfaces + specs;
`station/vision-api/.../controller/FlightCommandController.java`;
`.../ws/ManualControlHandshakeInterceptor.java` (**plus §3.7 clause 1's javadoc and test**);
`.../ws/ManualControlWebSocketHandler.java`; `.../controller/AssetSessionController.java`
(`disengage` gains the acting user — D17's attribution half only);
`.../proxy/HlsProxyController.java` + `.../security/StreamAccess.java` + `SecurityConfig`'s matcher
(**D10**); MODULE.md.
**Tests that must exist:** a `CREW`-assigned principal is **403** on arm and on `/ws/manual-control`
engage while **200** on payload; a `VIEWER` is 403 on both; a `PILOT`-assigned principal and a
MANAGER are unchanged; `/hls/**` is 401 unauthenticated and 404 for an unknown id.
Green: `./mvnw -B -pl contexts/vision-flight,station/vision-api,station/vision-app -am test`.

### Round 3

**B5 — sessions that survive the station** *(`spring-integrator`)* · scope: `station/vision-app/pom.xml`
(+`spring-session-jdbc`), `SecurityConfig.java` (session-id rotation on login, cookie policy),
`VisionAuthProperties` (the `session.*` block), **new**
`storage/persistence/.../db/migration/V34__spring_session.sql`, `application.yaml`,
`docker-compose.yml`, two MODULE.md. Implements §3.6.
Green: `./mvnw -B -pl storage/persistence,station/vision-app -am test`.

**B6 — one authority idiom** *(`application-service`)* · scope: every `scope.canManageOrg()` (~20),
`scope.canManage(ownership)` (8) and `scope.canAdminister()` (6) call site across
`contexts/{warehouse,flight,perception,learning,map,identity}` and the seven controllers holding one,
moved onto `Authority`; the `AuditController` subtree leak (**D18a**) fixed in passing; **and, in the
same commit**, `DefaultScopeResolver`'s `VIEWER → groups(subtree)` precedence; the `@Deprecated`
predicates deleted; every touched MODULE.md.

> **The staging rule, and it is a safety property, not a preference.** `VisibilityScope#canManageOrg()`
> returns `true` for `GROUPS`, so the instant a `VIEWER` receives a `GROUPS` scope, any call site still
> asking `scope.canManageOrg()` directly would hand a wall display the power to create users.
> **`RoleAuthority` therefore maps `VIEWER` to the `ASSIGNED_ASSETS` branch until this wave, and the
> precedence flip rides with the *last* piece of the migration.** No earlier wave may flip it; a B6
> split by context is safe only under that condition.

Green: `./mvnw -B -pl core/vision-platform,contexts/vision-warehouse,contexts/vision-flight,contexts/vision-perception,contexts/vision-learning,contexts/vision-map,contexts/vision-identity,station/vision-api,station/vision-app -am test`.
*The largest wave, and the only one that may be split — by context, never by call site.*

**B0b — secure by default** *(`spring-integrator`, after B3 and B5)* · scope: one value in
`application.yaml`, the now-redundant `VISION_AUTH_ENABLED` note in `docker-compose.yml`,
`station/vision-app/MODULE.md`, **and the missing regression test for D16**: an asset created under
the auth-off dev principal is visible to a MANAGER of the root group once auth is enabled — the
[`OPS-UX-PLAN.md`](../done/OPS-UX-PLAN.md) wave E item (3) nobody has ever written. Zero other test
impact — `src/test/resources/application.properties:24` already holds tests at `false`.
**Needs the owner's yes** (§5).

### Round 4 — the web (W1 first; W2 and W3 parallel after it)

**W1 — session honesty + capabilities** *(`web-ui`, after B3)* · scope: `core/api/models.ts`
(`MeResponse` + `Capability`/`ScopeKind`/`AssignmentRole` types), `core/auth/auth-store.ts` +
`auth-logic.ts` + specs (`capabilities()`, `can(cap)`, `scopeKind()`, `topRole()`,
`mustChangePassword()`), **new** `core/auth/session-interceptor.ts` + spec, `app.config.ts`
(`provideHttpClient(withFetch(), withInterceptors([...]))`), `core/api-error.ts` (retire the RTSP 401
copy), `core/live/live-store.ts` (reconnect on login, tear down on logout).
Delivers D8 and **§3.7 clause 2 verbatim** — the in-place re-authenticate overlay on `/fly/:assetId`
while engaged, with a spec asserting **no navigation occurs**.

**W2 — the gates realigned** *(`web-ui`, after W1)* · scope: `core/org/org-logic.ts`,
`core/org/org-guard.ts`, `core/shell/landing-logic.ts` + `landing-guard.ts`,
`features/hubs/nav-entries.ts` (`managerOnly` → `requires: Capability`),
`shared/ui/app-sidebar/app-sidebar.ts`, `shared/ui/identity-chip.ts`,
`shared/map/map-controls/layer-manager.ts`, `features/models/models-logic.ts` + `models-facade.ts`,
`features/geo/region-manager-facade.ts` (**fixes D6's confirmed drift**), `features/fly/fly-logic.ts:504`,
`features/command/command-facade.ts:394,454`, and the 13 `canManageOrg(...)` facades; all specs.
**Exit criterion:** zero `topRole ===` comparisons outside `core/auth/` — asserted by a spec, not a
review. **Does not touch `features/fly/cockpit.*`** — `?watch=1`'s mounted-but-hidden flight
controls are `CREW-CONTROL` W4's, not this plan's; double-scoping them would collide.

**W3 — first boot, people and credentials** *(`web-ui`, after W1; disjoint from W2)* · scope: **new**
`features/setup/` (the `/setup` bootstrap page, reached when `GET /api/auth/bootstrap` says
`required`), `app.routes.ts` (`/setup`, outside `authGuard`), the forced-password-change gate when
`mustChangePassword`, `core/command/setup-checklist-logic.ts` + spec (a fifth **"Secure this
station"** row; also fixes its citation of the deleted `AuthSeedRunner`),
`features/org-settings/*` (edit a membership, reset a password), `features/settings/account-settings*`
(change my own password), `features/asset-detail/pilots-card.*` + `features/roster/pilot-assignments-panel.*`
(the `PILOT`/`CREW` seat picker), `app.html`/`app.ts` (the unsecured banner gains an action).
`station/vision-web/MODULE.md`.

### Round 5

**V — verify + close out** *(`web-ui`)* · the full chain per §5, a live pass on a station booted from
an **empty** database, and this document's close-out table.

**Sequencing at a glance**

```
B0a ─▶ B1 ─▶ B2 ─▶ B3 ─▶ B4 ─▶ B5 ─▶ B6 ─▶ B0b
                     └──▶ W1 ─┬─▶ W2 ─┐
                              └─▶ W3 ─┴─▶ V
```
Only W2 ∥ W3 and (B5 ∥ W1) run in parallel; everything else shares `vision-api`/`vision-app` files.

---

## 5. Verification, residuals, and non-goals

**Verification (wave V):**
- `./mvnw -B verify` once, at the end, on a tree no other agent holds red.
- `npx tsc --noEmit` on both configs; `npm run test:ci`; `ng build --configuration production` with a
  bundle delta noted.
- **A live pass from an empty database** — the one thing no unit test proves: `docker compose up`
  with `VISION_PERSISTENCE_SEED_DEV_USERS=false`, land on `/setup`, create the admin, log in, land on
  `/command`; create a pilot and hand over a temporary password; confirm the forced change at first
  login; assign one aircraft as `PILOT` and another as `CREW` to the same person, and confirm crew
  sees payload controls and gets an honest 403 on arm for the crew aircraft while arming the other;
  drive the Wall from a `VIEWER` kiosk session for a day.
- **The mid-flight test, by hand**: engage manual control, `docker restart` the app, confirm the
  socket's fate matches §3.7 clause 1 and that the cockpit shows the overlay rather than navigating.
- **The D16 transition test**, automated in B0b: an asset created auth-off is visible to a MANAGER of
  root after auth is enabled.

**Needs the owner's yes before delegating:**
1. **B0b — flipping `vision.auth.enabled` to `true`.** It changes what a bare `./mvnw spring-boot:run`
   does for every developer. The plan's position: R7 already decided this, an unrelated commit undid
   it, and B3 removes the reason it was undone.
2. **`Role.VIEWER` — a new constant in an enum three documents call load-bearing.** §3.2 argues the
   refusal's stated reason does not apply to a prepend, but it is a deliberate narrow exception to a
   written refusal and should be said out loud, not assumed.
3. **`VIEWER` gets a group-wide scope (B6).** The only place this plan widens what somebody can see,
   and what makes the Wall possible.
4. **Kiosk sessions live 365 days.** A long-lived credential on a screen in a room, by design.

**Genuinely open, with a stated default** (implementer's call):
- A user holding both a `VIEWER` membership in one group and a `PILOT` membership in another —
  today's precedence picks one `Kind` and cannot union them. **Default: keep the existing single-Kind
  precedence** and document the limitation; a union `Kind` is a `VisibilityScope` change this plan
  deliberately does not make.
- Whether `POST /api/auth/bootstrap` also creates a root group when none exists. **Default: yes**,
  reusing `V13__identity_baseline.sql`'s fixed root-group id if that row is absent.
- The re-authenticate overlay's copy, and whether it offers "log out instead". **Default: it does**,
  behind a confirm — a pilot may genuinely be handing the laptop over.
- `vision.auth.password.min-length` **12**; `session.idle-timeout` **12h**; BCrypt strength stays
  Spring's default 10 (`BcryptPasswordHasher:19`) unless the owner asks otherwise.
- Whether `CurrentUser#scope()`'s per-call `ScopeResolver` round trip (no per-request cache, its own
  javadoc admits it) needs a request-scoped cache once B6 multiplies the call count. **Default: no**,
  measure first.

**Non-goals — named, not silently dropped:**
- **No seats, no arbitration, no forced takeover.** D17 stays open here. This plan supplies
  `AssetAuthority`'s answers (§3.9) and nothing else; `SeatController`, `SeatAccess`, the `SEAT_HELD`
  code and the `?watch=1` cleanup are all `CREW-CONTROL`'s. `AssetSessionController#disengage` gains
  an *actor* in B4 for the audit trail — attribution, not authority.
- **No invite links.** Ownership of invites moved here from `CREW-CONTROL`, and this plan covers the
  credential loop end to end **without** them: admin creates the account with a temporary password →
  `mustChangePassword` forces a change at first login → self-service change and admin reset exist
  from then on. On an offline field station an emailed token is theatre; a copy-a-link invite is
  genuine convenience, its design is already frozen at `CREW-CONTROL-PLAN.md`'s retired §2.7, and it
  is deferred **as a named, owned item**, not dropped.
- **No SSO, OIDC, LDAP or MFA.** A field station is offline by design; a federated identity provider
  it cannot reach is worse than a password.
- **No CSRF tokens.** §3.6 sets `SameSite=Strict` + `HttpOnly` + session-id rotation, which is the
  correct fix for the LAN threat model. The trigger that would change this, stated so it is not
  forgotten: exposing the station to a hostile network, or supporting a browser without
  `SameSite`-by-default.
- **No login rate limiting or lockout.** `RateLimitFilter` exists and ships off; its own javadoc
  explains it is meaningless until auth is on. Wiring `/api/auth/login` into it is a one-wave
  follow-up that B0b finally makes possible.
- **No per-user API tokens.** U-SCOPE catalog feature 9 / README §3 row 7 (`ApiToken(hash, userId,
  scope, expiry)` + a token filter beside the session filter) stays unowned and unwritten.
- **No lost-admin-password recovery flow.** Documented workaround only, and B0b must write it into
  `station/vision-app/MODULE.md`: a `psql` `UPDATE users SET password_hash = '<bcrypt>'` — note that
  re-enabling `seed-dev-users` does **not** help, because `V90001` skips any row whose username
  already exists. A boot-printed one-shot reset token is the right eventual answer and is not here.
- **The 12 `TEMPORARY_UNSCOPED` handlers stay** (D18b). Twelve product decisions, pre-existing, out
  of the live surface; this plan neither shrinks nor grows the ledger.
- **mediamtx's own credentials are untouched.** `mediamtx.yml`'s `any` account still grants
  unauthenticated Control API access from any IP on the compose network (safe only because the host
  port is loopback-bound), and its `change-me` passwords are duplicated by hand in `mediamtx.yml` and
  `docker-compose.yml`. Named because a plan about authentication that silently ignored it would be
  dishonest; it is a separate deployment-hardening wave.
- **No per-station role→capability remapping table.** The four roles map 1:1 onto the personas in
  §0.1 once the seat carries crew; a remap is a policy feature nobody asked for, and a DB table with
  no UI is fake capability. If it is ever wanted, CLAUDE.md rule 1 says where it goes.
- **`asset-flows` D4 — "may a PILOT onboard their own vehicle?"** — is surfaced, not decided. It
  edits OPS-UX's frozen authority table and is a product question, not a wave.
- **No audit retention or pruning.** `JpaAuditTrail` never prunes; B1/B2 make the table grow faster.
- **No change to `/api/map/**`.** `MapAccessPolicy`, `Affiliation`, `LayerKind`, `AccessLevel` and
  the map's deliberate separation from `VisibilityScope` are untouched; B1 adds only the test cases
  pinning `VIEWER` to its lowest tier.
- **No code written by this document.** It is a spec; nothing here is built.

**Registration:** add a row to [`docs/plans/README.md`](../README.md) §3 OPEN
(`| # | Work | Plan doc | Effort | Why now |`), beside row 10 (CREW-CONTROL) — the two are joined at
§3.9 and should be read together.
