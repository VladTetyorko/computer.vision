# vision-platform

Cross-cutting seams every bounded context writes to: events, the audit trail, and visibility
scoping. Split out of `vision-domain` in **W1.7a** (docs/plans/active/DOMAIN-SEPARATION-W1.md §16) as
the second of the wave's two universal modules — a pure `git mv` of `com.drones.vision.platform`, no
package rename, no import changes anywhere in the repo.

**Depends on:** `vision-kernel` only
**Used by:** `vision-domain` (every one of the 8 bounded contexts), `vision-application`, `vision-api`, `vision-app`,
and — since wave S2 (docs/plans/active/SYSTEM-STATUS-PLAN.md) added `SubsystemStatusPort` — the three
adapters that implement it directly: `cv/grpc`, `drone-link/mavlink`, `video-output/publish-hls`
**Build/test:** `./mvnw -B -pl core/vision-platform test`

## The rule

Platform holds the seams that are genuinely nobody's business logic — every context writes to them,
so no one context may own them. It exists because of **W1.6a's finding** (docs/plans/active/DOMAIN-SEPARATION-W1.md
§14 **C7**): four of the module graph's seven cycles were caused by these exact seams being filed
inside `events`/`identity` by first-mover rather than by owner (`warehouse` took an `AuditTrailPort`
and a `VisibilityScope` constructor argument, both living inside `identity`, while `identity` read
`warehouse`'s `Asset` for the reverse reason). Moving the seam to a package neither context owns
breaks the cycle without picking a side.

**May depend on the kernel and the JDK, nothing else** — enforced by `vision-app`'s
`ContextArchitectureTest` (`platformDependsOnNothingButTheKernel`), the same shape as the kernel's own
rule. A platform type that reached into a context would smuggle that context's coupling into all
eight others at once, since everyone already depends on platform — this is the precise failure W1.6a
fixed, so the rule guards against reintroducing it. `VisibilityScope` needed a signature change to
qualify: `includes(Asset)` (warehouse's aggregate) became `includes(AssetId, Ownership)` (both kernel
types); `maxGrantableRole()` moved out entirely, to `identity`'s `DefaultUserService` — granting roles
is user administration, not visibility.

## API surface

- `class AccessDeniedException extends RuntimeException` — thrown when a user's `VisibilityScope` forbids an operation on a resource that **does exist** — the command gate, the ≤-own-scope grant rule, and the user/group management gates (all `vision-application`). `vision-api` maps it to **403**. Deliberately distinct from the scoped *read*'s `NoSuchElementException` (404): reads hide existence, commands/grants/management honestly deny. Reused verbatim by the `map` context's services even though those gate on `MapAccessPolicy`/`Viewer`, not `VisibilityScope` — this type's contract is orthogonal to which scoping model produced the refusal
- `enum AuditAction` — CREATED, UPDATED, DEACTIVATED, ACTIVATED, DELETED, RESTORED
- `record AuditEntry(AuditId id, Instant occurredAt, UserId actor, AuditAction action, AuditTargetType targetType, String targetId, String summary, Map<String,String> details)` — immutable trail line; `static of(...)` stamps id + now; `details` carries `before → after` for edits
- `record AuditId(UUID value)` — `static random()`, `static of(String)`
- `enum AuditTargetType` — ASSET, DEVICE, DATASET, MODEL (a training dataset / a trained CV model version in the registry) — opaque `targetId` string, so new auditable kinds need no storage change
- `AuditTrailPort`: `AuditEntry record(AuditEntry)` — append-only, never updated or deleted, not even when its target is soft-deleted; `List<AuditEntry> findRecent(int limit)`, `List<AuditEntry> findByTarget(AuditTargetType, String targetId, int limit)`, `List<AuditEntry> findByActor(UserId, int limit)` (the "my activity" feed for one actor), all newest-first
- `record Event(String id, StreamId streamId, Instant at, EventType type, String message, Map<String,String> attributes)` — **streamId nullable** (device-level events); `static of(StreamId,EventType,String)` generates id+now(); **not the same thing as `DetectionEvent`** (perception context, `vision-domain`) — see Gotchas
- `EventLiveUpdatePort`: `void publishEvent(Event)` — announces a domain `Event` live to a driving adapter; must not throw on ordinary delivery failure, must return quickly (mirrors `EventPublisherPort`'s own contract). One of five ports the former god-port `LiveUpdatePublisherPort` split into (W1.6b); the one filed in `platform` rather than a bounded context, since every context raises an `Event`
- `EventPublisherPort`: `void publish(Event)` — must not throw on ordinary delivery failure; called on hot pipeline path, must return quickly
- `enum EventType` — DETECTION, DEVICE_ONLINE, DEVICE_OFFLINE, STREAM_STARTED, STREAM_STOPPED, PIPELINE_ERROR, TRAINING, GEOFENCE_BREACH (raised by `GeofenceMonitor`, `vision-application`, on a breach edge transition; attributes carry `{assetId, zoneId, zoneName, kind, direction}`, `streamId` always `null` since a breach is asset-scoped, not stream-scoped), POSITION_DIVERGENCE (docs/plans/active/VISUAL-GEO-V2-PLAN.md §4.5, H2a — raised by `contexts/vision-flight`'s `DefaultTrackCorrectionService`, via its internal `DivergenceRule`, on the alarm's rising edge only — never on every qualifying fix while already latched; attributes carry `{assetId, usageId, separationMeters, sigmaMeters}`, `streamId` always `null` since divergence is asset-scoped, not stream-scoped)
- `record VisibilityScope(Kind kind, Set<GroupId> groups, Set<AssetId> assignedAssets)` — what a request may see, resolved once per request by `vision-application`'s `ScopeResolver` and threaded into user-facing reads/commands. Nested `enum Kind {UNBOUNDED, GROUPS, ASSIGNED_ASSETS}`; three static factories — `unbounded()` (ADMIN/auth-off), `groups(Set<GroupId>)` (MANAGER), `assignedAssets(Set<AssetId>)` (PILOT); both sets defensively copied, null→empty. `boolean includes(AssetId, Ownership)` — `UNBOUNDED`→true, `GROUPS`→`groups.contains(ownership.groupId())`, `ASSIGNED_ASSETS`→`assignedAssets.contains(assetId)`. `boolean canManageOrg()` — true iff `UNBOUNDED`/`GROUPS`. `boolean includesGroup(GroupId)` — the group half of `includes`
- **Authority, not visibility (docs/plans/active/OPS-UX-PLAN.md §1, Wave C):** `includes`/`includesGroup`/`canManageOrg` all answer "what may this request see/reach" — two real call sites had been asking them an authority question instead (a PILOT could rename/delete their own assigned aircraft; `canManageOrg()` let a MANAGER promote the deployment's live CV model or start a training job, exactly as an ADMIN could). Two predicates answer "what may this request do": `boolean canAdminister()` — `true` iff `UNBOUNDED`; gates deployment-global actions with no group boundary (model promote, training start). `boolean canManage(Ownership)` — `true` for `UNBOUNDED`; for `GROUPS` iff `ownership.groupId()` is in `groups()` (a MANAGER's subtree is already a management boundary, so this agrees with `includes` for that one kind); always `false` for `ASSIGNED_ASSETS` — a PILOT's scope is built so they can see *and fly* exactly their assigned aircraft, and that is the whole of their authority, so `canManage` is `false` regardless of whether the asset is the very one assigned to them. With `vision.auth.enabled=false` every caller is `unbounded()`, so both predicates are always `true` and behavior is unchanged.
- `enum Health` (docs/plans/active/SYSTEM-STATUS-PLAN.md §4.1, wave S2) — `OK`, `DEGRADED`, `DOWN`, `DISABLED`, `UNKNOWN`. Declaration order is **not** severity order (see `SystemStatusController`'s javadoc, `vision-api`, for the ranking used to compute an overall rollup); `DISABLED` is deliberately excluded from that rollup so an intentionally-off subsystem never reads as a fault
- `record SubsystemStatus(String id, String label, Health health, String detail, Instant since, String hint)` — one subsystem's self-reported health. `id` is a stable slug (`cv-service`, `mavlink-link`, `video-publish`, `live-updates`) — the wire contract a UI keys off, never the (potentially reworded) `label`. `detail` is the honest human sentence explaining the health; `hint` is the next actionable step, nullable when there's nothing useful to say; `since` (nullable) is when the current state began. Compact-constructor validation: `id`/`label`/`detail` must be non-blank, `health` non-null; `since`/`hint` are unvalidated (legitimately absent)
- `interface SubsystemStatusPort { SubsystemStatus status(); }` — one per subsystem, implemented adapter-side (`cv/grpc`'s `CvStatusProvider`, `drone-link/mavlink`'s `MavlinkLinkStatusProvider`, `video-output/publish-hls`'s `PublishStatusProvider`, `vision-api`'s `LiveUpdateStatusProvider`), collected as a `List<SubsystemStatusPort>` by `vision-api`'s `SystemStatusController`. Filed here (not in a context) for the same reason `EventPublisherPort` is: every adapter that has a subsystem to report on needs it, and no one adapter may depend on another. `status()` must not throw for an ordinary "this subsystem happens to be down" condition — that **is** a normal answer (`Health.DOWN`), not an exception; a genuinely broken provider is still caught per-provider by the controller and downgraded to `Health.UNKNOWN` rather than failing the whole endpoint
- `final class PlatformActor` (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.4, Wave O11) — not a domain type, a **constant holder**: `public static final UserId USER_ID = new UserId(new UUID(-1L, -1L))` (`ffffffff-…-ffffffffffff`), a fixed, documented `UserId` for actions the platform itself takes with no user request behind them (today: `vision-app`'s `PassportCaptureObserver` auditing an automatic flight-passport capture). Exists so `AuditTrailPort` rows for unattended platform action say "the platform did this" with a stable, recognizable id rather than either a fabricated human actor or a `null` that would break every `AuditEntry`/`UserId`-typed call site's non-null contract. Deliberately **not** the same thing as `vision-app`'s `DevPrincipal` (`UUID(0,0)`/`UUID(0,1)`, "whoever is using the system when auth is off") — `DevPrincipal` stands in for an absent *human*; `PlatformActor` stands in for the *absence of a human entirely*. **Deliberately outside the `UUID(0, n)` namespace**, and `PlatformActorTest` pins that: the first version of this constant took `UUID(0, 2)` as "the next free slot after `DevPrincipal`", which is precisely the id `db/seed/dev/V90001__dev_accounts.sql` gives the dev **manager account** — so every unattended capture would have been audited as that person. A constant holder normally has nothing to unit-test, but this one has a real invariant that no compiler and no wiring test can catch, because the colliding rows are seeded by SQL in another module

## Conventions

- **UUID id pattern:** `AuditId` wraps `UUID` with the kernel's usual `random()`/`of(String)` pair.
- **Validation:** compact-constructor manual `if (…) throw new IllegalArgumentException(…)`, same idiom as the kernel.
- Flat package, no `.model`/`.port` split, deliberately — kept tiny like the kernel rather than growing a taxonomy for ten types.

## Gotchas

- `Event.streamId` is nullable — device-level events (`DEVICE_ONLINE`/`DEVICE_OFFLINE`) have no stream.
- **`Event` and `DetectionEvent` are unrelated types despite the name overlap** — `Event` (this module) is `EventPublisherPort`'s fire-and-forget, write-only notification (device online/offline, stream started/stopped, pipeline errors, raw per-frame detections), never persisted or queried back by anything today; `DetectionEvent` (`vision-domain`'s `perception` context) is a debounced, stored/upserted/queried "label X was seen for a while" record with a completely different shape. There is no cheap way to serve `Event`s (e.g. `PIPELINE_ERROR`) through `DetectionEventRepositoryPort`/`GET /api/events` — see `vision-application`/`vision-api`'s MODULE.mds for the full reasoning.

## Status

VISUAL-GEO-V2-PLAN wave **H2a** (docs/plans/active/VISUAL-GEO-V2-PLAN.md, 2026-08-19) added the
`POSITION_DIVERGENCE` `EventType` constant — the sole reason this module was touched by that wave
(D7 assigns the value/service/divergence-rule types to `vision-kernel`/`contexts/vision-flight`, not
here; this is purely the shared enum every context's `EventPublisherPort` already draws from). Pure
addition, no existing constant renumbered/removed, no existing test enumerates all values, so nothing
else needed updating. `./mvnw -B -pl core/vision-platform test` green (19/19 unchanged — no new test
file needed for one enum constant, consistent with how `GEOFENCE_BREACH` itself was added).

Stable since W1.6b (docs/plans/active/DOMAIN-SEPARATION-W1.md §15), which last changed the type set
(the god-port `LiveUpdatePublisherPort` deleted; `EventLiveUpdatePort` added here). W1.7a (this
module's creation) moved the package's *jar*, not its contents — same 11 types (`Event`, `EventType`,
`EventPublisherPort`, `EventLiveUpdatePort`, the `AuditEntry`/`AuditId`/`AuditAction`/
`AuditTargetType` family, `AuditTrailPort`, `VisibilityScope`, `AccessDeniedException`), same
behavior, zero import changes anywhere in the repo (verified: `./mvnw -B -DskipWeb test` green across
the reactor after the split).

**OPS-UX-PLAN Wave C (C1, docs/plans/active/OPS-UX-PLAN.md §4):** `VisibilityScope` gained
`canAdminister()`/`canManage(Ownership)` — pure additions, no existing method's signature or
behavior changed. `VisibilityScopeTest`: 9 → 12. `./mvnw -B -pl core/vision-platform test` green.

Wave S2 (docs/plans/active/SYSTEM-STATUS-PLAN.md, 2026-08-15) added the `Health`/`SubsystemStatus`/
`SubsystemStatusPort` trio backing `GET /api/system/status` — the same "seam nobody's business logic
owns" rationale as `EventPublisherPort`: every adapter with a subsystem to self-report on needs the
port, and adapters may not depend on each other, so it lives here rather than in any one of them.
The three new types have no independent test file — their behavior is exercised through
`vision-api`'s `SystemStatusControllerTest` via fake `SubsystemStatusPort` implementations, and
through each real provider's own module.

Module total after both waves: 17/17 (`VisibilityScopeTest` 12 + `EventTest` 5).

**docs/plans/active/DRONE-ONBOARDING-PLAN.md Wave O11 done** (making `VehicleProfileService#captureSnapshot` actually get called — see `contexts/vision-perception/MODULE.md` and `station/vision-app/MODULE.md` for the rest of this wave): added `PlatformActor` (own entry above), a purely additive change — no existing type's signature or behavior touched. The wave shipped it holding `UUID(0, 2)`, which collides with the dev seed's **manager user account**; corrected to `UUID(-1L, -1L)` on review and pinned by the new `PlatformActorTest`. `./mvnw -B -pl core/vision-platform -am test` — **19/19 green** (17 before this wave, +2).
