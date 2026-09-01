---
name: java-clean-code
description: Java design and naming rules for the vision project — when an interface earns its place, how many dependencies a class may have, where the acting user comes from, DTO boundaries. Use before adding any interface, service, or constructor parameter, and when reviewing Java for ceremony.
---

# Java clean code (vision)

Rules that override generic "hexagonal tutorial" habits. Read before creating a type.

## 1. An interface must earn its place

Create an interface only when **more than one implementation really exists, or a boundary must be swappable**. One implementation plus one caller is not a boundary — it is indirection that costs a file, an import, and a jump every time someone reads the code.

**Earns it — driven/outbound ports.** `VideoSourcePort` (rtsp, sim, mjpeg, usb…), `DetectionPort` (gRPC today, ONNX-in-JVM later), `StreamPublisherPort` (mediamtx, no-op), repository ports (in-memory today, JPA later). These are genuinely substituted, including in tests. Keep them, keep the `*Port` suffix.

**Does not earn it — one interface per operation.** `UpdateDeviceUseCase`, `SetDeviceStateUseCase`, `DeleteDeviceUseCase` are three files, three imports and three constructor parameters describing one object doing three things to devices. That is *speculative generality* (Fowler) and it inflates every constructor downstream.

**Rule:** at most **one interface + one implementation** per service area. `DeviceService` (interface) → `DefaultDeviceService` (impl). Not five interfaces implemented by one class.

## 2. Name things for what they are

- Services: `DeviceService`, `AssetService`, `StreamService` — a noun for the thing it manages. No `*UseCase` suffix; "use case" is analysis vocabulary, not a type name.
- Methods: plain verbs — `update`, `deactivate`, `delete`, `restore`. Not `execute`, `handle`, `perform`.
- Driven ports keep `*Port`; that suffix carries real information (this one is substitutable).
- Implementations: `Default*` or the technology (`MediamtxStreamPublisher`, `InMemoryDeviceRepository`) — never `*Impl` on its own unless there is genuinely nothing better to say.

## 3. Constructor dependencies are capped

**Five is the ceiling; three is the target.** A constructor growing past that is the class telling you it does too much (SRP) — split it, or the dependency does not belong there.

Specifically:

- **Never inject request-scoped state.** The acting user is not a dependency, it is part of the request. Inject `AuthenticationFacade`/`SecurityContext` access once at the edge, or pass the resolved user as a method parameter. Do not thread `Ownership actingOwnership` through constructors — that is what made `AssetService` take eight arguments.
- **Do not inject a port you use once** to cascade a delete you should not be cascading. Question the operation before adding the parameter.
- Constructor injection only (no field `@Autowired`), assigned to `private final` fields.

### No overload chains, no null-means-off

A new collaborator means **updating the call sites**, or bundling into a settings record. It does
not mean one more constructor overload.

The repo ran the other rule for a while — *"every field a wave adds gets one more convenience ctor
layer so every pre-existing call site keeps compiling"* — and it produced `UsageTracker` with ten
constructors, `StreamPipeline` with nine (927 → 1530 lines), `DefaultStreamService` with eight, and
58 collaborators whose documented contract was *"pass `null` to skip that feature"*. The rule is
withdrawn (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md, R1). Two rules replace it:

- **One public constructor per class.** Optional collaborators and tunables go into a settings or
  collaborators record with a `defaults()` factory — the pattern `UsagePhaseSettings`,
  `StreamPipelineSettings` and `UsageSummaryBatchSettings` already established. One parameter, not
  one per wave. A package-private test seam is fine when a test genuinely needs to inject a clock or
  a backoff bound; a *chain* of them is not.
- **A parameter may not mean "off" by being `null`.** Use a no-op implementation constant
  (`UsagePhaseObserver.NOOP`), an `Optional`, or a flag on the settings record. A reader must not
  have to consult the module doc to learn that `null` in position seven disables detection.

Updating call sites is the cost the withdrawn rule was deferring, and deferring it is what turned
three classes on the live video path into the largest in the codebase. Pay it in the same change.

## 4. The user comes from the token

Authentication is resolved at the **API edge** and nowhere else. Controllers obtain the principal from the security context (JWT claims → user id, roles), and pass it down as an argument. Services never know about tokens, headers, or Spring Security; they receive a plain `UserId`.

Until authentication exists, one small resolver at the edge returns the dev principal — one place to change later, not a constructor parameter on every service.

## 5. DTOs live at the boundary, not inside ports

Request/response records belong in the API module (`…api.dto`), mapping to and from domain types. Do **not** nest `Registration`/`AssetEdit`/`DeviceEdit` records inside service interfaces — that pushes wire concerns into the core and forces every caller to import the interface just to name its input.

For partial updates, one DTO with nullable fields (`null` = unchanged) beats one method per field.

## 6. Keep the domain plain

- Value types are `record`s with validation in the compact constructor.
- No framework annotations in a context module's `domain` / `application` packages (`contexts/vision-<ctx>/`).
- Prefer `final` classes and package-private visibility until something outside actually needs the type.
- Enums over booleans when a third state is plausible (`LifecycleState`, not `boolean active`) — but do not invent states nobody asked for.

## 7. Delete rather than deprecate, but never destroy user data

Unused code goes. User data does not: prefer **soft delete** (a lifecycle state) over hard delete, so history stays intact, invariants that require a child record stay satisfiable, and the action is reversible. Pair it with an audit record of who changed what.

## 8. Comments

Javadoc on public types and non-obvious methods, explaining *why* and the contract (nullability, threading, exceptions). No comment that restates the code. No history ("changed in phase 3"), no TODO without an owner.

## Checklist before adding a type

1. Does a second implementation exist today? If no → no interface.
2. Does this constructor now exceed five parameters? If yes → the class does too much.
3. Am I adding an overload instead of updating call sites? If yes → update the call sites, or bundle into a settings record.
4. Am I injecting something that varies per request? If yes → it is a method parameter.
5. Is this record describing the wire? If yes → it belongs in `…api.dto`.
6. Can an existing service own this method instead of a new type? Usually yes.
