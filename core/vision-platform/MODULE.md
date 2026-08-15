# vision-platform

Cross-cutting seams every bounded context writes to: events, the audit trail, and visibility
scoping. Split out of `vision-domain` in **W1.7a** (docs/plans/active/DOMAIN-SEPARATION-W1.md §16) as
the second of the wave's two universal modules — a pure `git mv` of `com.drones.vision.platform`, no
package rename, no import changes anywhere in the repo.

**Depends on:** `vision-kernel` only
**Used by:** `vision-domain` (every one of the 8 bounded contexts), `vision-application`, `vision-api`, `vision-app`
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
- `enum EventType` — DETECTION, DEVICE_ONLINE, DEVICE_OFFLINE, STREAM_STARTED, STREAM_STOPPED, PIPELINE_ERROR, TRAINING, GEOFENCE_BREACH (raised by `GeofenceMonitor`, `vision-application`, on a breach edge transition; attributes carry `{assetId, zoneId, zoneName, kind, direction}`, `streamId` always `null` since a breach is asset-scoped, not stream-scoped)
- `record VisibilityScope(Kind kind, Set<GroupId> groups, Set<AssetId> assignedAssets)` — what a request may see, resolved once per request by `vision-application`'s `ScopeResolver` and threaded into user-facing reads/commands. Nested `enum Kind {UNBOUNDED, GROUPS, ASSIGNED_ASSETS}`; three static factories — `unbounded()` (ADMIN/auth-off), `groups(Set<GroupId>)` (MANAGER), `assignedAssets(Set<AssetId>)` (PILOT); both sets defensively copied, null→empty. `boolean includes(AssetId, Ownership)` — `UNBOUNDED`→true, `GROUPS`→`groups.contains(ownership.groupId())`, `ASSIGNED_ASSETS`→`assignedAssets.contains(assetId)`. `boolean canManageOrg()` — true iff `UNBOUNDED`/`GROUPS`. `boolean includesGroup(GroupId)` — the group half of `includes`

## Conventions

- **UUID id pattern:** `AuditId` wraps `UUID` with the kernel's usual `random()`/`of(String)` pair.
- **Validation:** compact-constructor manual `if (…) throw new IllegalArgumentException(…)`, same idiom as the kernel.
- Flat package, no `.model`/`.port` split, deliberately — kept tiny like the kernel rather than growing a taxonomy for ten types.

## Gotchas

- `Event.streamId` is nullable — device-level events (`DEVICE_ONLINE`/`DEVICE_OFFLINE`) have no stream.
- **`Event` and `DetectionEvent` are unrelated types despite the name overlap** — `Event` (this module) is `EventPublisherPort`'s fire-and-forget, write-only notification (device online/offline, stream started/stopped, pipeline errors, raw per-frame detections), never persisted or queried back by anything today; `DetectionEvent` (`vision-domain`'s `perception` context) is a debounced, stored/upserted/queried "label X was seen for a while" record with a completely different shape. There is no cheap way to serve `Event`s (e.g. `PIPELINE_ERROR`) through `DetectionEventRepositoryPort`/`GET /api/events` — see `vision-application`/`vision-api`'s MODULE.mds for the full reasoning.

## Status

Stable since W1.6b (docs/plans/active/DOMAIN-SEPARATION-W1.md §15), which last changed the type set
(the god-port `LiveUpdatePublisherPort` deleted; `EventLiveUpdatePort` added here). W1.7a (this
module's creation) moved the package's *jar*, not its contents — same 11 types (`Event`, `EventType`,
`EventPublisherPort`, `EventLiveUpdatePort`, the `AuditEntry`/`AuditId`/`AuditAction`/
`AuditTargetType` family, `AuditTrailPort`, `VisibilityScope`, `AccessDeniedException`), same
behavior, zero import changes anywhere in the repo (verified: `./mvnw -B -DskipWeb test` green across
the reactor after the split).
