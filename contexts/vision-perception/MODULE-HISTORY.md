# vision-perception — wave history

`MODULE.md` is the contract (current API surface, conventions, gotchas, status). This file is the
narrative record of what each wave did, in what order, with what test counts — read it to learn
*why* something is the way it is, not to learn what is true today. Newest first.

## 2026-09-13 — CV-ORCHESTRATION-PLAN wave W9.0a+W9.0 (`tracks:` SSE payload widening)

**2026-09-13, CV-ORCHESTRATION wave W9.0a+W9.0 (`tracks:` SSE payload widening, plan §4.9/§8 decisions E25/E27).** Retiring the last per-stream REST poll (`GET /api/streams/{id}/tracks`) started here. W9.0a (decision E27) relocated `TrackingStats`/`PipelineLatency`/`DetectionRate` from `application.pipeline` to `domain.model` (`git mv` + import fixes across perception/vision-api/vision-app; `TrackingStats#zeroedStates()` widened `package-private` → `public` since its one caller, `TrackingStatsWindow`, is now a different package) — values over `java.time` and domain enums belong beside `DetectionState`/`FollowStatus`/`TrackedObject`, and doing this first is what let W9.0's `TracksSnapshot` live entirely in `domain.model` (all seven of its components are domain types). W9.0 then added `domain.model.TracksSnapshot` and `StreamPipeline#tracksSnapshot()` as the single assembly point; the `onDetectionResult` capture moved from immediately after `world.accept` to the end of the `if (live)` block, after `trackingStats.accept`/`rateController.observeDetections` too — a deliberate correctness fix (CLAUDE.md rule 7, newest-data-wins), disclosed as a deviation from the plan's literal "right where the old capture was" wording, so the pushed snapshot's own tracking-stats/latency/rate never lag the frame that produced them. `DetectionLiveUpdatePort#publishDetections`'s third parameter widened `List<WorldObject>` → `TracksSnapshot` (CLAUDE.md rule 10, no overload) — every call site/implementer updated (`StreamPipeline`, `vision-app`'s `NoopLiveUpdatePublisher`, `vision-api`'s `LiveUpdateRegistry`, deliberately kept minimal there — publishing the snapshot's other six fields on the wire was left to W9.1 per the brief's own step boundary); `StreamService#tracksSnapshot(StreamId)` added with the same forgiving empty-for-unknown-stream idiom as its four siblings. **ArchUnit proof `TracksSnapshot` is legitimately domain-layer**: no per-class exception exists or is needed — `station/vision-app`'s generic `ArchitectureTest#domainDependsOnlyOnDomainAndJava` (`..domain..` may depend only on `..domain../..kernel../..platform../java..`) and `#domainAndApplicationAreSpringAnnotationFree` both cover it by package, green at 14/14 throughout. A stale-bytecode trap hit and worked around, not a real defect: the first `-pl ... test` pass (no `clean`) false-failed three perception test files with unchanged sources, because the default incremental compiler didn't notice their *compiled* classes still referenced the pre-widen port signature — `clean test` (not `-Dmaven.test.skip=true install` then `test`) is the correct scoped command for this reason. Scoped build (`./mvnw -B -pl contexts/vision-perception,station/vision-api,station/vision-app -am clean test -DskipWeb`): perception 874/874 throughout both commits, vision-api 1121/1121 (→ 1122/1122 after W9.1's own new contract test), vision-app 355/355 (`ArchitectureTest` 14/14, `ContextArchitectureTest` 5/5) — zero failures/errors. W9.1 (`station/vision-api`) and W9.2 (`station/vision-web`) build the wire assembly and the store transport flip on top of this; see those modules' own `MODULE.md`. Payload size and the full wave-W9 evidence trail are recorded in `docs/plans/active/CV-ORCHESTRATION-CONTEXT.md`'s W9 status line.

## 2026-09-13 — CV-ORCHESTRATION-PLAN wave W5b.2 (trace replay)

**2026-09-13, CV-ORCHESTRATION wave W5b.2 (trace replay, plan §8 E23).** New `domain.model.DetectorBox(String label, double confidence, BoundingBox box)`, validated like `Detection` but never that type (no `TrackRef`). `FrameLedger` grew from a 12- to a 15-component record: `List<DetectorBox> detections, int frameWidth, int frameHeight`, defensively copied, both ints validated ≥0, `0`/`List.of()` meaning "not carried" (untraced, or a pre-W5b cv-service) — never a null-means-off parameter (CLAUDE.md rule 10). All six pre-existing `new FrameLedger(` call sites updated explicitly, no convenience overload added: the production one in `cv/grpc`'s `DetectionFrameCodec#toFrameLedger` (now also decoding `TracedDetection` via a new `toDetectorBox`), plus `FrameLedgerFixtures#everyFieldDistinct()` (two new distinct `DetectorBox`es + a distinct frame size), `FrameLedgerTest`'s local `ledger(...)` helper (widened the same way, all 19 downstream calls threaded `List.of(), 0, 0`, plus four new tests for the three fields' own validation and defensive copy), and three `station/vision-api` test fixtures (`StreamControllerTest`, `CvTraceResponseWireContractTest`, `LiveUpdateRegistryTest` — each passed `List.of(), 0, 0`, unchanged behaviour; W5b.3 is what actually threads detections through the wire DTO). Scoped build: `./mvnw -B -pl cv/vision-proto,contexts/vision-perception,cv/grpc,station/vision-api -am test -DskipWeb` — vision-perception 839/0/0, vision-proto 5/0/0, adapter-cv-grpc 195/0/0, vision-api 1121/0/0, all green.

## 2026-09-13 — CV-ORCHESTRATION-PLAN wave W8 (StreamPipeline collaborator cuts: FrameSampler/OutageSupervisor/DetectionGate)

`PipelineTrace` (wave W2.9)'s original extraction note, before W8 corrected it: **"Line-count target not fully reached"**: final `StreamPipeline.java` was 1784 lines, not the 1643 target — the git-archaeology breakdown of the remaining gap (most of it wave W2.2's unrelated `WorldModel` integration, plus `maybeDetect`/`submitDetection`'s own gate-classification control flow, which reads multiple `StreamPipeline`-private fields together and was left in place deliberately rather than risk exposing those fields or duplicating decision logic across two classes) lived in this doc's own Status section until wave W8 addressed it directly.

**2026-09-13, wave W8 (`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.9/K3, MASTER-MATRIX row K3, decision E24) — three more collaborators cut from `StreamPipeline`, same pattern as `PipelineTrace`/`WorldModel` (built directly inside the canonical constructor from `StreamPipelineSettings`/`PipelineConfig`, no constructor-signature change, CLAUDE.md rule 10):**

- **`FrameSampler(StreamPipelineSettings, LongSupplier nanoTimeSource)`** (W8.0, package-private, final) — arrival bookkeeping and the sample-deadline schedule: `recordArrival()`, `framesObserved()`, `nanosSinceLastFrame()`, `sourceFps()` (the measured-EWMA cadence, warmup-gated, sanity-clamped), `targetFps(DetectionRateController, effectiveInferenceFps, maxInFlightInferences)`, `sampleDue(now, DetectionRateController, effectiveInferenceFps, maxInFlightInferences, DetectionRateWindow)`. The rate controller's own target stays where it lived — this class *asks* it, never owns it. `StreamPipeline#framesObserved()`/`#nanosSinceLastFrame()` delegate unchanged. Tested alone (`FrameSamplerTest`, 8 cases) against a fake clock: warmup window, EWMA clamp, schedule arming, exact-boundary due, a stalled source's missed-deadline counting (restart from `now`, no catch-up burst), a frozen/backwards clock failing open, and a rate change taking effect only at the next armed deadline.
- **`OutageSupervisor(StreamPipelineSettings, LongSupplier nanoTimeSource)`** (W8.1, package-private, final) — the outage/probe backoff state machine: nested `OutageDecision{NORMAL,SKIP,PROBE}` and `record Recovery(boolean recovered, long failuresDuringOutage)`; `outageDecision()`, `clearProbeInFlight()`, `boolean recordFailure(boolean isProbe)` (returns `true` only on the entering edge), `Recovery recordSuccess()`. `StreamPipeline` keeps the logging and the one `PIPELINE_ERROR` event publish per outage — this class owns only the state transitions (entering, backoff doubling on a failed probe only, single-probe-in-flight admission, cap, and reset on recovery). Tested alone (`OutageSupervisorTest`, 10 cases) against a fake clock; `GateReason#CV_UNAVAILABLE` is explicitly out of scope there but exercised transitively, since `submitDetection`'s synchronous-completion peek reaches this class through the same `recordFailure(isProbe)` path as any other failure.
- **`DetectionGate`** (W8.2, package-private, final; constructor `DetectionGate(PipelineConfig initialConfig)`) — the demand/policy fields (`detectionDemand`, `detectionPolicyAlwaysOn`, fail-open/fail-closed respectively) and the gate classification: `detectionGateOpen(PipelineConfig)`/`liveGateOpen(PipelineConfig)` (the same two boolean expressions, now taking `config` as a parameter rather than reading a field, same reason `PipelineTrace` does), nested `Edge{NONE,INFERENCE_CLOSED,LIVE_CLOSED}` and `handleTransition(PipelineConfig)` (the edge-detection half of the former `handleDetectionGateTransition()`), and `Optional<GateVerdict> classify(PipelineConfig, now, FrameSampler, OutageSupervisor, effectiveInferenceFps, DetectionRateController, DetectionRateWindow, AtomicInteger inFlightInferences, PullDetectionBinding)` — the former inline `maybeDetect` three-way switch, unchanged in substance. `GateVerdict` (new file, sealed interface) — `Send(boolean probe, DemandSnapshot)` | `Skip(GateReason reason, DemandSnapshot)`; `classify` returns `Optional.empty()` for exactly one case (an outage-backoff deadline not yet due), matching the original's "no gate ledger entry, no rate-window entry" comment for every other frame arriving inside a backoff window. **Kept on `StreamPipeline`, deliberately:** the `PipelineConfig` read itself (collaborators take it as a parameter, never store a possibly-stale copy); the transition side effects `clearDetectionDerivedState()`/`clearLiveDerivedState()`, driven by the `Edge` `handleTransition` reports; `detectionGateOpen()`/`liveGateOpen()`/`handleDetectionGateTransition()` as thin no-arg delegating methods (not deleted) so every pre-existing call site and javadoc `@link` across the class keeps resolving; `GateReason#CV_UNAVAILABLE`, which `classify()` cannot produce — that peek at an already-failed `CompletionStage` lives entirely in `submitDetection`, on a port `DetectionGate` never calls. Tested alone (`DetectionGateTest`, 17 cases): the six producible `GateReason`s in isolation (`CV_UNAVAILABLE` disclosed out of scope, same pattern as `OutageSupervisorTest`), the demand/policy-ALWAYS truth table for both gates, and live-vs-detection independence via `Edge` (`INFERENCE_CLOSED` vs `LIVE_CLOSED` vs `NONE`, including the "repeated close is a no-op" case).
- Net: `StreamPipeline.java` 1784 → 1652 (W8.0) → 1585 (W8.1) → 1485 (W8.2) lines, −299 total. Zero behaviour change — every pre-existing test in `vision-perception`/`adapter-cv-grpc`/`vision-api`/`vision-app` passed unmodified at each cut; only new collaborator test files were added. `./mvnw -B -pl contexts/vision-perception,cv/grpc,station/vision-api,station/vision-app -am test -DskipWeb` green at final HEAD: perception 874, adapter-cv-grpc 195, vision-api 1121, vision-app 355 (Docker available).

## 2026-09-13 — CV-ORCHESTRATION-PLAN wave W7.1 (CV profile per-knob fold finalized)

**`CvProfileResolver`'s fold is now genuinely per-knob** (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7, decision E22), superseding both wave W7.0's "wholesale, `foldOnto(platformDefault)` every time" interim state and the W2.6-era `ProfileSource`/`EffectiveProfile` bullets' claim that `INTENT` "never" comes from the resolver. Per bound tier, in organization→category→asset order: if that tier's own `CvProfile#intent()` is non-null, `IntentPolicyResolver#resolve(intent, profile.labelFilter())` first patches `model`/`labelFilter`/`confidenceThreshold`/`inferenceFps` onto the running accumulation (everything else, including `maxInFlightInferences`/`trace`, passes through untouched) — *then* that tier's own explicit knobs fold on top of the result via `CvProfile#foldOnto`, exactly matching that method's own documented (but, by design, not self-performed) seed contract. A knob a tier sets explicitly is attributed to that tier in the accumulated `KnobSources`; a knob a tier leaves null but that tier's own intent seeds (only those same four knobs) is attributed to `PLATFORM`→`INTENT`; every other knob simply carries forward the accumulation from the tier before it. No bound tier at all still returns `platformDefault` **unchanged, same instance**, with `KnobSources.platform()` and `intent=null`. A fully-specified profile (every knob set, matching every migration-seeded built-in's own shape) still folds byte-identical to before this rewrite — pinned by a dedicated resolver test, since a fully-specified profile's own knobs always win regardless of tier-wholesale vs. per-knob semantics.

`EffectiveProfile` gained two components: `KnobSources sources` (per-knob provenance across the whole fold) and `Intent intent` (the most specific bound tier whose own `intent` seeded at least one knob, `null` if none did) — both new, non-optional (`sources` `Objects.requireNonNull`'d in the compact ctor). `profileId()`/`profileName()`/`source()` keep naming only the most specific *bound tier as a whole*, unchanged; per-knob provenance is `sources()`'s job now, not a second meaning overloaded onto `source()`.

`CoverageRow`'s javadoc now says explicitly what was already true in code and untested by doc: every field is an **effective (folded)** value read from `EffectiveProfile#config()`, never a raw, possibly-null `CvProfile` knob — `DefaultCvProfileService#toCoverageRow` was already built this way (no code change needed here, only the doc catching up).

`CvProfileResolverTest`'s `assetBindingReplacesEveryKnobWholesaleRatherThanOnlyTheOnesThatDifferFromCategory` (pinned the now-false "wholesale, never per-knob merge across tiers" contract) is **removed**, replaced by `organizationInferenceFpsAndAssetModelBothPersistWithCorrectPerKnobSources`, `organizationIntentSeedsFourKnobsAndAssetExplicitConfidenceThresholdWinsOverThem`, and `fourBuiltInShapedProfilesFoldThroughTheResolverByteIdenticalToFoldOntoDirectly`; `noBindingAtAnyLevelReturnsThePlatformDefaultUnchanged` gained `sources`/`intent` assertions.

## 2026-09-13 — CV-ORCHESTRATION-PLAN wave W7.0 ("a profile is a patch")

**(CV-ORCHESTRATION-PLAN.md §4.7, decision E22 — "a profile is a patch").** `CvProfile`'s previous fully-specified shape (`CvProfile(CvProfileId id, String name, String description, boolean builtIn, GroupId groupId, ModelRef model, double confidenceThreshold, int inferenceFps, List<String> labelFilter, List<String> labelDenyFilter, boolean detectionEnabled, TrackingConfig tracking, EventRuleConfig eventRule, Instant createdAt, Instant updatedAt)`) is superseded — 8 of its knobs (`model`, `confidenceThreshold`, `inferenceFps`, `labelFilter`, `labelDenyFilter`, `detectionEnabled`, `tracking`, `eventRule`) are now nullable, `null` meaning "inherit from the tier below" (the documented patch idiom already established by `application.stream.PipelineConfigPatch`/`TrackingConfigPatch` — CLAUDE.md rule 10, never a fresh "off" flag), plus a new nullable `Intent intent` component **persisted with the profile** (previously resolved only at request time in `station/vision-api` and lost on reload). Compact ctor: `confidenceThreshold`/`inferenceFps` range checks fire only when non-null; `intent==Intent.CUSTOM` requires non-null/non-empty `labelFilter`; built-in profiles are still expected to leave every knob set (the four migration-seeded rows do), but nothing in this record enforces that. `toPipelineConfig(PipelineConfig defaults)` is **removed**, replaced by `PipelineConfig foldOnto(PipelineConfig below)` — a pure per-knob patch: each non-null knob of this profile wins, each null knob comes from `below` (`tracking` folds via the new `TrackingKnobPatch#foldOnto`, not a wholesale swap); `maxInFlightInferences`/`trace` still always come from `below`, unchanged from `toPipelineConfig`. A profile whose every knob mirrors `PipelineConfig.defaults()`'s own components still folds byte-identical to what the old `toPipelineConfig` produced (pinned by `CvProfileTest`). **Deviation (disclosed):** the brief's literal ask was for `foldOnto` to also resolve `intent` internally via `IntentPolicyResolver`; this method is deliberately **intent-agnostic** instead — `IntentPolicyResolver` lives in `application.profile`, and calling it from this domain record would violate this module's own `ArchitectureTest` rule that domain/kernel/platform must never depend outward on application-layer packages. Intent-seeding is `CvProfileResolver#resolve`'s job instead (`application.profile`, wave W7.1: fold an intent-derived seed `PipelineConfig` first, then this method's per-knob patch on top of it).

New `TrackingKnobPatch(TrackingMode mode, String engineId, Integer capabilityLevel, Integer verifyEveryMillis, Integer followFps)` — the profile-owned subset of `TrackingConfig`'s ten knobs (the same five fields `station/vision-api`'s `CvProfileTrackingResponse` already narrows a profile's tracking down to on the read side), each individually nullable = inherit; all-null leaves every owned knob alone. `TrackingConfig foldOnto(TrackingConfig below)` patches only these five fields — the other five (`redetectIouPercent`, `maxAgeFrames`, `minHits`, `reupdateMaxGapMillis`, `lock`) always come from `below`, a profile never owns them. **Deviation (disclosed):** the brief's literal ask was to reuse `application.stream.TrackingConfigPatch` (the already-established per-knob-patch precedent, which also carries `lock` and requires it always-null) as `CvProfile`'s own `tracking` field type; that was not possible without moving that type into a domain package, which would force edits to `application.stream.StartStreamRequest` (the session-tier hot path, explicitly out of this wave's write scope). `TrackingKnobPatch` is a new, narrower domain type instead — it has no `lock` field at all, rather than a field that must always validate to `null`.

`CvProfileSpec`'s previous shape (`create`/`update`'s input, every `CvProfile` field except `id`/`builtIn`/`groupId`/timestamps) is superseded to mirror `CvProfile`'s new patch shape — `model`/`confidenceThreshold`/`inferenceFps`/`labelFilter`/`labelDenyFilter`/`detectionEnabled`/`eventRule` all now nullable = inherit, `tracking` is now `domain.model.TrackingKnobPatch` (not `TrackingConfig`), plus a new nullable `Intent intent` component; same validation posture as `CvProfile` (range checks only when non-null, `CUSTOM`+`labelFilter` guard). `DefaultCvProfileService#create`/`#update`/`#fork` now thread `intent` through into the persisted `CvProfile` (previously computed at request time in `station/vision-api` and never stored, so it vanished on reload — the motivating defect for this whole wave).

New `KnobSources(ProfileSource model, confidenceThreshold, inferenceFps, labelFilter, labelDenyFilter, detectionEnabled, tracking, eventRule)` — per-knob provenance, one `ProfileSource` per profile knob; `platform()` factory returns all-`PLATFORM`. **Not yet wired into `CvProfileResolver`/`EffectiveProfile`** at this point — that landed in wave W7.1 (above), which folds this per-knob so `sources` survives a reload instead of only ever appearing in the save-time response. **Deviation (disclosed):** the brief's literal ask placed this record in `domain.model`; it lives in `application.profile` instead because its fields are typed `ProfileSource`, which already lives in this package — moving `ProfileSource` itself into `domain.model` was not requested and out of this wave's scope.

`CvProfileResolver`'s fold was **not yet rewritten** as of this step — its internal loop still applied each bound tier's `CvProfile` wholesale (only a compile-only rename, `toPipelineConfig`→`foldOnto`, no behavior change). The genuine per-knob accumulation, plus per-knob `KnobSources` and `EffectiveProfile#intent()`, landed in wave W7.1 (above).

## CV-ORCHESTRATION-PLAN wave W2.8 (coordinator follow-up items 1–3)

`WorldObject` reached the `tracks`/`cv-trace` wire (see `MODULE.md`'s current `WorldObject`/`DetectionLiveUpdatePort`/`StreamService` bullets for the accessor and DTO detail — coordinator item 1); `ProfileSource#INTENT` and its `CvProfileRequest#fieldSources()`/`CvProfileResponse#sources` plumbing (coordinator item 2) shipped as a separate `test(...)`-prefixed commit alongside `IntentPolicyResolverTest`/two new `CvProfileResolverTest` cases; `PipelineTrace` extraction (coordinator item 3) is wave W2.9, below.

`IntentPolicyResolverTest` (new this wave) now covers every `Intent` value plus null-intent and `CUSTOM`'s validation/pass-through — wave W2.6 had shipped `IntentPolicyResolver` without tests; closed as a follow-up before the W2 branch merged. Two new `CvProfileResolverTest` cases (also this wave) pinned the resolver's then-current **wholesale-replace-per-tier** contract (an asset binding built from an intent-seeded model/labelFilter plus explicit `confidenceThreshold`/`inferenceFps` superseded *every* field of a bound category, never a per-knob merge) — that contract was itself superseded by wave W7.1's per-knob rewrite (above); the pinning test was removed and replaced there.

## CV-ORCHESTRATION-PLAN wave W2.7 (documentation catch-up, no source changed)

This module's own MODULE.md was corrected against the actual W2.2–W2.6 state in this commit (it had only ever been updated through W2.1, and had accumulated several claims — "nothing wired yet," "`CvProfileRepositoryPort` has zero implementations," `FollowTracker`/`TrackBook` described as live production types — that were stale or, in the persistence case, already false when originally written); `cv/grpc`, `station/vision-api`, `station/vision-app` and `storage/persistence`'s own MODULE.md files were updated for their own share of the same wave (see their own Status/history). No source changed in this commit.

Two self-corrections made in that pass, recorded here since the false claims they replaced are otherwise lost:

- **`CvProfileRepositoryPort` is implemented** — `storage/persistence`'s `JpaCvProfileRepository`, backed by `V29__cv_profiles.sql`, landed by the separate CV-SETTINGS wave W3 (commit `49e84edc`) and is wired into `vision-app`'s Spring context; profiles/bindings persist across a restart. A prior revision of this doc (written during this wave's own W2.1 documentation pass) had claimed "zero implementations… nothing persists until wave W3 lands, `vision-app` does not yet construct a bean" — that was already false when written (W3 had already merged before this wave's baseline), not a regression introduced by this wave; corrected here.
- **CV-ORCHESTRATION wave W2 is now fully wired through W2.6 — `FrameLedger`/`WorldObject`/`GateDecision` (and their families) ARE constructed by production code paths.** `StreamPipeline#maybeDetect` records a `GateDecision` into `FrameGateLedger` for every branch that ends without sending a frame (wave W2.3); `DetectionResult#ledger()` is populated by `cv/grpc`'s `DetectionFrameCodec#decode` whenever cv-service attaches a `FrameLedger` (wave W2.5), fed into `FrameLedgerRing`; `WorldModel` (wave W2.2) replaces `TrackBook`/`FollowTracker`/`DetectionExtrapolator` and constructs a `WorldObject` per tracked identity every accepted frame. A prior revision of this doc (written during this wave's own W2.1 step, before any of the above landed) said none of this was reachable yet — that was accurate for W2.1 alone but is stale now; corrected here. As of wave W2.8, `WorldObject` also gained a dedicated read-model accessor (`StreamService#worldObjects(StreamId)`) and wire DTO (`station/vision-api`'s `WorldObjectResponse`) — what remained open at this point was only `IntentPolicy#detectFloor`/`#rateCeiling`, computed but unconsumed since wave W2.6.

## CV-ORCHESTRATION-PLAN waves W2.2 through W2.9

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` **wave W2 steps W2.2 through W2.7 are done** (commits
`617e6a69` W2.2, `cbd44202` W2.3, `01678a42` W2.4, `cdf17816` W2.5, `fc0bfa00` W2.6, plus a W2.7
docs-only commit, see above) — the "nothing wired yet" note from step A1/W2.1 alone no longer
described this module by the end of this range; every bullet and Gotcha it applied to was corrected
in place. Summary of what changed in this module across those steps (`cv/grpc`, `station/vision-api`,
`station/vision-app`, `storage/persistence` each have their own MODULE.md entry for their own share
of the same wave):

- **W2.2** — `WorldModel` (`application.pipeline`, package-private) replaces `TrackBook`,
  `FollowTracker` and `DetectionExtrapolator` in one class: `tracks()`/`followStatus()` are ported
  **byte-identical**, `DetectionExtrapolator` is **deleted outright** (dead `.at()`, plan §7 D4,
  not ported), and `objects()` (new) folds each frame's `ObjectState` mirror into a `WorldObject`
  with `operator`/`event`/`render` facets `StreamPipeline` previously had no equivalent for.
  `StreamPipeline#latestDetections()`/`#latestObjects()`/`#tracks()`/`#followStatus()` all now
  delegate to this one collaborator instead of three.
- **W2.3** — `FrameGateLedger` (coalescing ring of `GateDecision`) wired into every `maybeDetect`
  branch that ends without sending a frame; `TraceDemandPort` (new driven port, fail-**closed**,
  the deliberate opposite of `DetectionDemandPort`'s fail-open) added to `domain.port`, consulted
  on the same demand-poll tick.
- **W2.4** — `onDetectionResult`'s live-before-durable ordering (already load-bearing since D2, see
  its own long-standing Gotcha/javadoc) is now additionally pinned by a dedicated ordering test;
  `StreamPipelineTest` gained a case asserting the gate is read exactly once per call.
- **W2.5** — `FrameLedgerRing` (non-coalescing ring of decoded `FrameLedger`s) added; `cv/grpc`'s
  `DetectionFrameCodec#decode` now actually decodes the wire's `FrameLedger` into
  `DetectionResult#ledger()` instead of always passing `Optional.empty()`; `StreamService`/
  `DefaultStreamService` gained `gateLedger(StreamId,int)`/`frameLedger(StreamId,int)` (the
  established "never errors, empty list for unknown/stopped" idiom). `station/vision-api` is the
  read side: `GET /api/streams/{id}/cv/trace`, `LiveTopicKind.TRACKS`/`.CV_TRACE` SSE topics,
  `LiveAndPollTraceDemand` implementing `TraceDemandPort`.
- **W2.6** — `CvProfileResolver#resolve` rewritten from first-match-wins to a genuine
  organization→category→asset fold (identical `PipelineConfig` output for today's fully-specified
  `CvProfile` records; the payoff is structural, for a future partial-profile type). New `Intent`
  enum, `IntentPolicy` record and `IntentPolicyResolver` (`application.profile`) give
  `station/vision-api`'s `CvProfileRequest` a 10th, optional `intent` field that seeds a blank
  `model`/empty `labelFilter`/the synthesized `eventRule`'s confidence — `IntentPolicy#detectFloor`/
  `#rateCeiling` are computed but deliberately left unconsumed this wave (no unset-sentinel exists
  for `CvProfileRequest`'s bare-primitive `confidenceThreshold`/`inferenceFps` under the frozen wire
  contract; see `IntentPolicy`'s own javadoc).
- **W2.7** — documentation only, see the dedicated entry above.
- **W2.8** — coordinator follow-up items 1–3, see the dedicated entry above.
- **W2.9 (coordinator follow-up item 3)** — `PipelineTrace` extracted as one collaborator holding
  `FrameGateLedger`/`FrameLedgerRing`/the trace-demand fold, in response to coordinator review that
  `StreamPipeline.java` had grown to 1801/1808 lines carrying this wave's additions inline. Final
  count: **1784 lines**, not the 1643 target (cut to **1485** by wave W8, 2026-09-13 — see the W8
  entry above). Git archaeology across every wave-W2 commit touching this file (`617e6a69` W2.2 +106
  net, `cbd44202` W2.3 +113 net, `cdf17816` W2.5 +34 net, `423bb89b` W2.8 +8 net) shows the gap is not
  hiding unmoved ledger code: **100% of W2.5's +34** and the ledger-ring/read-model/trace-demand
  portion of W2.3 are now fully inside `PipelineTrace`. What is left inline and was deliberately
  **not** moved: (1) ~106 lines from W2.2's `WorldModel` integration — outside item 3's named scope
  ("the gate-ledger and frame-ledger read models... the trace-demand glue and the ledger rings"),
  and a rewrite of that integration was never authorized; (2) `maybeDetect`'s three-way gate
  classification and `submitDetection`'s `CV_UNAVAILABLE` peek (the rest of W2.3's growth) — control
  flow that reads several `StreamPipeline`-private fields together (`pullDetection`, `config`,
  `detectionDemand`, `detectionPolicyAlwaysOn`, the in-flight `pending` future) in one place; moving
  it would mean exposing those fields outside `StreamPipeline` or duplicating the classification
  logic across two classes, either of which is a bigger and riskier change than the "not a rewrite"
  item 3 authorized. Behavior confirmed unchanged: full `contexts/vision-perception` suite is
  unchanged in test count post-extraction (449 tests), `FrameGateLedgerTest`/
  `StreamPipelineGateLedgerTest` both still fully green.

Deviations from the plan, disclosed rather than silently taken: `TraceDemandPort` is fail-**closed**
(plan implies a demand port, doesn't specify direction — chosen opposite `DetectionDemandPort`
deliberately, an absent trace-demand signal must never turn a costly tier on by itself);
`FrameGateLedger`/`FrameLedgerRing` **coalesce differently** from each other (gate decisions
coalesce consecutive identical `SKIPPED` reasons, frame ledgers never coalesce — no steady-state
repetition to guard against there); `GET /api/streams/{id}/cv/trace?last=N` defaults `N` to a
self-chosen `50` (not specified by the plan); the CV profile fold's "session" tier is realized one
layer up in `station/vision-api` (`StreamDetectionSupport#resolveStartConfig`), not as a `start()`
signature change in this module, for the blast-radius reasons stated in `CvProfileResolver`'s own
javadoc.

## CV-ORCHESTRATION-PLAN wave W2 step A1 (domain modeling)

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` **wave W2 step A1 "domain modeling" is done here** —
domain-only, nothing wired to a production producer yet at this step. New `domain.model` types: `LedgerOutcome`
(3-value enum), `LedgerEntry`, `ObjectEvidence`, `FrameLedger` (§4.4's warm debug tier — present only
on a traced frame, never persisted), `RenderTier` (`HIDDEN` plus the frozen `T0`-`T3` client
vocabulary), `WorldObject` with nested `Operator`/`EventLink`/`Render` (§4.6 — `state` is the wire
mirror verbatim, the three relations are platform-owned and must never reach cv-service). New
`application.pipeline` types: `GateReason` (7-value enum, one per `StreamPipeline#maybeDetect` skip
branch), `GateOutcome`, `DemandSnapshot` (documents the `sse|pose|poll` breakdown as unobtainable
here — arrives as one boolean via `DetectionDemandPort`), `GateDecision` (one entry per sampler
*deadline*, not per frame; if-and-only-if `reason`/`SKIPPED` pairing, the same idiom
`TrackingTelemetry` already uses for `detectorRan`/`reason`). `DetectionResult` gained a 9th
component, `Optional<FrameLedger> ledger` — deliberately `Optional`, never a nullable component
(CLAUDE.md rule 10) — with **no** convenience constructor, so every one of the ~62 existing
`new DetectionResult(...)` call sites across 20 files (13 outside this module: `vision-learning`,
`vision-events`, `storage/persistence` ×2, `station/vision-app`'s `NoopDetectionPort`,
`station/vision-api` ×4, `cv/grpc`'s `DetectionFrameCodec#decode`) now pass `Optional.empty()`
explicitly. New fixture `FrameLedgerFixtures#everyFieldDistinct()` (test-only, stable cross-wave API,
mirrors `ObjectStateFixtures`'s own convention).

`./mvnw -B -pl contexts/vision-perception,cv/grpc,station/vision-api,station/vision-app -am -q test -DskipWeb` —
`vision-perception` **760 → 808 tests, all green** (48 new: 16 `FrameLedgerTest`, 7 `LedgerEntryTest`,
3 `ObjectEvidenceTest`, 8 `WorldObjectTest`, 10 `GateDecisionTest`, 3 new `DetectionResultTest` ledger
cases, 1 new `StreamPipelineTest` case —
`theLabelFilterCarriesTheLedgerThroughReconstructionInsteadOfDroppingIt` — pinning that
`applyLabelFilters`'s reconstruction branch carries `ledger()` forward exactly as it already carries
`pullTelemetry()`, not the `Optional.empty()` the mechanical call-site pass first wrote there; see the
method's own comment). Per-module `Results:` lines from the exact 4-module `-am` run: `vision-perception`
— Tests run: 808, Failures: 0, Errors: 0, Skipped: 0; `cv/grpc` (`adapter-cv-grpc`) — Tests run: 187,
Failures: 0, Errors: 0, Skipped: 0; `station/vision-api` (`vision-api`) — Tests run: 1074, Failures: 0,
Errors: 0, Skipped: 0; `station/vision-app` (`vision-app`) — Tests run: 355, Failures: 0, Errors: 0,
Skipped: 0. `BUILD SUCCESS`, zero `[ERROR]` lines in the full log, Docker present and healthy
(`vision-postgres-1`, `vision-mediamtx-1`). Nothing in this module's own file scope was wired to a
production producer yet at this step — that landed across waves W2.2–W2.9, above.

## CV-ORCHESTRATION-PLAN wave W1 step 5 ("wire mirror")

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` **wave W1 step 5 "wire mirror" is done here** (this
module's file scope: `StreamPipeline`/`StreamService`/`DefaultStreamService`, `station/vision-api`'s
DTOs/`StreamController`, and `station/vision-app`'s `LiveFrameFallbackStreamService` — the domain
`ObjectState` itself, its `DetectionResult#objects()` component and the cv-service/codec population of
it landed in earlier, already-committed W1 steps). `StreamPipeline` gained a `latestObjects()` read
model — a `volatile List<ObjectState>` sibling of `latestDetections`, written in `onDetectionResult`
on exactly the same `if (live)` edge and cleared by both `clearDetectionDerivedState()` and
`clearLiveDerivedState()` — surfaced through a new `StreamService#objects(StreamId)` method
(implemented in `DefaultStreamService` and `vision-app`'s `LiveFrameFallbackStreamService`, the same
"never null, empty for an unknown/stopped stream" idiom as `tracks`/`latestDetections`).
`applyLabelFilters` now filters `DetectionResult#objects()` by the same allow/deny rule as
`detections`, matched on `identity().label()`, with one added rule: an object whose `identity` is
`null` is always kept (nothing to match against, so dropping it would invent an answer the platform
doesn't have) — see that method's own javadoc for the full "denied label must not reappear under a
new JSON key" reasoning and why `tracks[]` needs no separate filtering.

`./mvnw -B -pl contexts/vision-perception,cv/grpc,station/vision-api,storage/persistence,contexts/vision-events,contexts/vision-learning,station/vision-app -am -DskipWeb test` —
`vision-perception` **755 → 760 tests, all green** (5 new `StreamPipelineTest` cases:
`onDetectionResultWritesLatestObjectsAlongsideLatestDetections`,
`aModelReArmClearsLatestObjectsJustAsItClearsLatestDetections`,
`theLiveOnlyEdgeClearsLatestObjectsButDurableInferenceKeepsRunningForAnAlwaysPolicyStream`,
`labelFilterKeepsAnObjectWithNoIdentityButDropsADeniedIdentifiedObject`,
`emptyLabelFiltersKeepEveryObjectRegardlessOfIdentity`). See `station/vision-api/MODULE.md` and
`station/vision-app/MODULE.md` for those modules' own count breakdowns. `BUILD SUCCESS`, all green,
Docker ran for real for every Testcontainers-based suite this module's own tests touch. Nothing
deferred from this module's own file scope.

## 2026-09-06 — ALWAYS-ON-FLOW-PLAN waves D1/D2 (per-asset `DetectionPolicy`)

`docs/plans/active/ALWAYS-ON-FLOW-PLAN.md` **waves D1/D2 (2026-09-06) are done here**: a per-asset
`DetectionPolicy` (`ON_VIEW`/`ALWAYS`, stored in `Asset#attributes`) that can widen a stream's
inference/durable gate independently of the live gate a viewer's screen depends on.

- **D1** — `DetectionPolicy` enum (domain.model) + `DetectionPolicyPort` (domain.port, fail-closed,
  a wholly separate port from `DetectionDemandPort` — see Gotchas for why this deviates from the
  wave's literal "fourth OR-term" wording) + `DefaultStreamServiceSettings`'s new 7th field +
  `DefaultStreamService#evaluateDetectionDemand`'s new policy block, independently null-guarded and
  independently `catch(Throwable)`-resilient alongside the pre-existing demand block. The cheap,
  in-memory-read cache the port needs (`DetectionPolicyCache`, mirroring `TrackProjectionRunner`'s
  own caching precedent) lives in `station/vision-app` — see that module's MODULE.md.
- **D2** — `StreamPipeline#detectionGateOpen()` (inference: `detectionEnabled && (viewerDemand ||
  policyAlwaysOn)`) split from `#liveGateOpen()` (live: exactly the pre-D2 single gate, unchanged);
  durable persistence/the `DETECTION` event/`DetectionEventEngine` follow the wider inference gate.
  `handleDetectionGateTransition()` now clears state differently per edge — the inference edge does
  the pre-existing full clear, the live-only edge (reachable only for an `ALWAYS` asset losing its
  last viewer) clears the same read models minus `pipelineLatency`/`detectionRate`. `onDetectionResult`
  and pull mode's `PullResultSubscriber#onNext` both re-check both gates independently, closing the
  late-completion race for each on its own terms (see `MODULE.md`'s "three questions, not one" Gotcha
  for the full edge/race accounting, including the pull-mode "Finding 6" fix that stops discarding a
  worker's already-paid-for results for an `ALWAYS` stream with no current viewer).

`./mvnw -B -pl contexts/vision-perception test` — 717 → 730 tests, all green (13 new: 5
`DetectionPolicyTest`, 6 `StreamPipelineTest` covering the inertness-then-widening default, the
last-viewer-leaves live-only clear, the policy-revoked full clear, edge-vs-level idempotency under
repeated confirmation, and the live-gate-specific late-completion race; 2 `DefaultStreamServiceTest`
proving a throwing `DetectionPolicyPort` is exactly as contained as a throwing `DetectionDemandPort`
and that the port alone — demand port absent — still arms the poll scheduler, plus one end-to-end
`evaluateDetectionDemand`→`detectionState()` scenario). `CvWiringTest`/`CvEnabledWiringTest` add 3
wiring-level cases: `DetectionPolicyCache` absent by default, present when CV is switched on
(`@ConditionalOnExpression`, mirroring `cvChannelSupervisor`'s own precedent), and `DetectionPolicyPort`
always present and reading `false` for every asset when the cache is absent — the D1 acceptance bar
that `ALWAYS` is behaviourally inert with nothing opted in, proven even in a deployment that never
wires the cache at all.

**Deliberately deferred, out of scope for D1/D2:** a fleet-wide inference budget (`maxInFlightInferences`
stays a fixed `2`/stream constant, no property key added) — plan wave D3, see `MODULE.md`'s Gotchas for the
capacity risk this leaves open. **No new endpoint** — the existing generic `PATCH /api/assets/{id}`
(`attributes` map) already round-trips `DetectionPolicy.ATTRIBUTE_KEY`, so `vision-api` needed no
change for D1's storage requirement.

## 2026-09-06 — ALWAYS-ON-FLOW-PLAN wave A (telemetry pinning)

`docs/plans/active/ALWAYS-ON-FLOW-PLAN.md` **wave A (A1/A2/A3, 2026-09-06) is done here** — the
context module's half of "telemetry becomes a fact about the world, not a side effect of video."
Three changes, each recorded as its own Gotcha in `MODULE.md`:

- **A1** — new public `#pinTelemetry(AssetId)`/`#unpinTelemetry(AssetId)`, plus `Tracking#telemetryPinned`.
- **A2** — `#applySample` split into a live-link sink that always runs (`#publishLiveLinkFacts`) and a
  durable per-usage sink that still requires an open usage. **A documented doctrine changed here** —
  see `MODULE.md`'s "never opens a usage from a sample arriving" Gotcha for what was kept and what was not.
- **A3** — `#deviceStreamStopped`'s teardown guard gained `!telemetryPinned` as a third keep-alive reason.

`./mvnw -B -pl contexts/vision-perception -am test` — **717 tests, 0 failures** (`UsageTrackerTest`
+7: pinning subscribes a telemetry-capable device and is idempotent, pinning opens no usage, a
pinned asset survives its last stream stopping, an unpinned idle asset is torn down, unpinning a
flying asset leaves telemetry up, a sample with no usage still publishes live and reaches the
geofence observer while writing no durable record).

The scheduling half is `station/vision-app`'s `TelemetryPinRunner` — see that module's MODULE.md.
**Ships off** (`vision.telemetry.always-on.enabled`, compiled default `false`), on in
`docker-compose.yml`. Waves B/C/D of the plan (state plane, UI view plane, CV alignment) were not
started as of this wave; D is the one that must not be skipped once D1/D2 land — see the plan's §3 measured ceiling.

## 2026-09-05 — TRACK-FOLLOW-PLAN wave W2 (follow state machine)

`docs/plans/active/TRACK-FOLLOW-PLAN.md` wave W2 (2026-09-05) is **done here**: `FollowState`/
`FollowStatus` domain records, the `FollowTracker` state machine (`application.pipeline`),
`StreamPipeline#followStatus()`, and `StreamService#followStatus(StreamId)`/`DefaultStreamService`.
Consumes W1's `TrackRef` recovery fields for the first time: a bind with `identityConfidence>0` now
stamps `FollowStatus.recoveredAfterMillis()`/`recoveryConfidence()` and returns straight to `HOLDING`/
`COASTING` rather than being read as a fresh acquisition. `./mvnw -B -pl contexts/vision-perception
test` — 665 → 710 tests, all green (45 new: 10 `FollowStatusTest`, 24 `FollowTrackerTest`, 7 added to
`StreamPipelineTest`, 4 added to `DefaultStreamServiceTest`). **Zero out-of-module call sites needed
updating** — no `vision-app` wiring change, unlike W1's `TrackRef` field additions. **One flagged
deviation from the plan text:** `reacquirable` reads `FollowTracker.DEFAULT_MEMORY_TTL` (a documented
class constant), not a resolved `TrackingConfig` field, since `TrackingConfig` has no
`memoryTtlMillis` yet even though the wire proto defines one — adding that field is
domain-modeler territory, out of this wave's file scope. Not this wave's scope: no wire endpoint/DTO
exposes `followStatus` yet (`vision-api`'s job, a later wave); `lastSeenAgeMillis` (DTO-only, derived
from a request instant) is deliberately not a domain field.

(`WorldModel` absorbed `FollowTracker` byte-identically in wave W2.2, above — the class named here no
longer exists as a standalone type, but its behavior is unchanged.)

## 2026-09-05 — TRACK-FOLLOW-PLAN wave W1 (`TrackRef` recovery fields)

`docs/plans/active/TRACK-FOLLOW-PLAN.md` wave W1 (2026-09-05) is **done here**: `TrackRef`'s
canonical constructor grew from 7 to 9 args, appending `double identityConfidence`/
`long dormantMillis` (D11 — the two wire facts that prove a bound track was recovered from
cv-service's L4 follow memory rather than freshly acquired). Both existing convenience constructors
(3-/6-arg) keep their arity and now default the two new fields to `0.0`/`0` — every pre-existing call
site in this module compiles and behaves unchanged. `cv/grpc`'s `DetectionFrameCodec#toTrackRef`
(this plan's own W1 scope, not this module's) is the only production reader; nothing in
`application.pipeline` consulted the two new fields yet at this point — that was wave W2's
`FollowTracker`, above. `./mvnw -B -pl contexts/vision-perception
test` — 665 tests, all green (`TrackRefTest` +6: `sixArgConvenienceConstructorDefaultsRecoveryFactsToZero`,
`threeArgConvenienceConstructorDefaultsRecoveryFactsToZero`,
`acceptsARecoveredTrackWithIdentityConfidenceAndDormantMillis`,
`rejectsIdentityConfidenceOutsideZeroToOne`, `rejectsNegativeDormantMillis`, plus the existing
`acceptsAnExplicitlyReupdatedTrack` widened to the new 9-arg canonical form). **Blast radius, as
surveyed by the plan and confirmed here**: of the ten `new TrackRef(...)` call sites across
`vision-perception`/`cv/grpc`/`station/vision-api`/`storage/persistence`, exactly **two** used the
old 7-arg canonical form — `DetectionFrameCodec.java:419` (updated as part of this wave, see
`cv/grpc/MODULE.md`) and, inside `StreamControllerTest.java`, **two** call sites (lines ~1176 and
~1381 as the file stood then), both widened to 9-arg here. The plan's own grounding sweep named
only one `StreamControllerTest` line (`:1164`, since drifted to `:1176` by unrelated edits) and
missed the second (`:1381`) — flagged here since a build relying on the plan's count alone would
have gone red on that second site. Every other call site (3-/6-arg convenience) was left untouched,
including `StreamControllerTest.java:1343` and `storage/persistence`'s
`PostgresDockerIntegrationTest.java:1454`.

## 2026-09-05 — SOURCE-ONBOARDING-2-PLAN wave B2 (§3.2 C6, `StreamStateObserver`)

`docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md` wave B2 (§3.2 C6) is **done here**: the new
`StreamStateObserver` seam plus its required (never `Optional`) 7th `DefaultStreamServiceSettings`
component. `DefaultStreamService`'s `RunningStream` record gained a trailing
`AtomicReference<StreamState> lastKnownState` component (seeded `null` at `start`);
`stateOf(StreamId, RunningStream)` (renamed from the previous 1-arg `stateOf(RunningStream)` — **an
out-of-module caller matching the old 1-arg signature did not exist at the time, but flagged in case
one appeared**) now does the edge-detection and calls the new private `notifyStreamStateChanged`.
`./mvnw -B -pl contexts/vision-perception test` — 656 → 660 tests, all green (`DefaultStreamServiceTest`
+4: `firstStreamStateReadNeverFiresANotification`, `aRepeatedReadWithNoStateChangeFiresNoAdditionalNotification`,
`aFrameArrivalTransitionsStartingToLiveAndFiresExactlyOneNotification`,
`aThrowingStreamStateObserverDoesNotBreakStreamStateReads`). Every pre-existing
`new DefaultStreamServiceSettings(...)` call site in this module's own tests (14 across
`DefaultStreamServiceTest`/`DefaultStreamServiceProxyAndPullModeTest`) was mechanically widened with a
trailing `StreamStateObserver.NOOP` argument — no behavior change for any of them. **`station/vision-app`'s
`ApplicationServiceWiring`, which constructs `DefaultStreamServiceSettings` directly, did not compile
until a later wave supplied a `StreamStateObserver` argument (`NOOP` is the correct value until a real
consumer exists) — out of this wave's scope.**

## 2026-09-04 — AUTH-ROLES-PLAN wave B6 (`Authority` migration)

`docs/plans/active/AUTH-ROLES-PLAN.md` wave B6 (2026-09-04) is **done here**: `CvProfileService`'s
six mutation methods (`create`/`update`/`delete`/`fork`/`bind`/`unbind`) migrated their `scope`
parameter from `VisibilityScope` to `core/vision-platform`'s `Authority`, gating on
`scope.mayManageOrg()` instead of the now-`@Deprecated` `VisibilityScope#canManageOrg()` — the
mechanical widen-the-parameter-type migration rule established by earlier B-waves. `list`/`get`/
`effective`/`coverage` are unchanged (still `VisibilityScope scope`; none of the four calls a
deprecated predicate). No behavior change for any caller passing `Authority.full()`, the identity
element. **This wave did not delete anything from `VisibilityScope`** — the three deprecated
predicates stay until every context/module has migrated (tracked centrally by the plan's
coordinator). Out of this wave's scope, flagged for the caller: `vision-app`'s Spring wiring
already did not compile `DefaultStreamService`'s 8-arg constructor against this module until wave W5
(of CV-SETTINGS-PLAN) landed (pre-existing, unrelated); once a `vision-app`/`vision-api` wave wires
`CvProfileService`'s six mutation methods to a controller, that caller must supply an `Authority`,
not a bare `VisibilityScope`. `./mvnw -B -pl contexts/vision-perception test` — **656/656 green, no new/removed
tests** (`DefaultCvProfileServiceTest`'s 22 cases changed only the type of the `scope` value they
construct/pass, same assertions).

## 2026-09-01 — ZERO-CONFIG-ONBOARDING-CONTEXT wave Z1 (`engage` opens telemetry independent of video)

`docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md` wave Z1 (`engage` opens telemetry
subscriptions for the asset's `TELEMETRY`-capable devices, independent of any video stream — closes
`docs/plans/active/TELEMETRY-ONLY-ONBOARDING-CONTEXT.md` §B4, the "MAVLink gateway only ever opens
via a video stream" defect) is **done here** (48 `UsageTrackerTest` cases, 647 total,
`./mvnw -B -pl contexts/vision-perception test`). Not this wave's scope: nobody outside this module
called `engage`/`disengage` yet — `docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md` §8 assigned the
Fly cockpit's `POST /api/assets/{id}/session` caller to a later wave (`vision-api`/`vision-web`); the
"pair it with a fake sim camera" workaround for a telemetry-only SITL asset could be retired once that
landed.

## 2026-09-01 — ASSET-FLOWS-PLAN §4/BK2b (`LinkLossNotifier`)

`docs/plans/active/ASSET-FLOWS-PLAN.md` S4/BK2b (2026-09-01) is **done here — closes the gap S4/BK2
left in `contexts/vision-flight`**: `subscribeTelemetry`'s `SupervisedPublisher` outage callback,
previously a hardcoded `cause -> { }` no-op, now calls `linkLossNotifier.reportLinkLost(asset.id(),
"MAVLink link to " + asset.displayName() + " lost")` — see `MODULE.md`'s `SupervisedPublisher`/Gotchas
entries for the edge-only semantics (no new debouncing added; `SupervisedPublisher`'s own
"outageAnnounced" latch already guarantees one call per outage, not one per retry). `LinkLossNotifier`
(flight's `application.alerting`) is a new required 9th component on `UsageTrackerSettings`, appended
after S1's `maintenanceQuery`, with the same "required, no `Optional`" rationale (a link-loss signal
is safety-adjacent, not an optional feature — `LinkLossNotifier` itself already tolerates a
fully-disabled deployment via its own nullable ports, so requiring the collaborator here costs
nothing when publishing is off). `./mvnw -B -pl contexts/vision-perception -am test` — **656 tests,
all green** (`UsageTrackerTest` 56→57:
`telemetrySourceFailureRaisesExactlyOneLinkLostEventNotOnePerRetry`, which fails the very first
retry attempt too before a later one succeeds, proving no second `LINK_LOST` fires while the outage
is still ongoing). `station/vision-app`'s `usageTracker` bean now also takes `LinkLossNotifier` and
threads it through `UsageTrackerSettings.defaults(maintenanceQuery, linkLossNotifier)` — see
`station/vision-app/MODULE.md`. `./mvnw -B -pl station/vision-app -am test` green, full reactor,
`vision-app` module 317/317.

## 2026-09-01 — ASSET-FLOWS-PLAN §2 D1p / wave BK4 (pilot attribution)

`docs/plans/active/ASSET-FLOWS-PLAN.md` §2 D1p (wave BK4, 2026-09-01) is **done here**:
`UsageTracker#engage` widened to `engage(AssetId, UserId pilotIdOrNull)` — passes the pilot through
on a fresh open, backfills it onto an already-open `STREAM`-origin usage at promote time without
ever overwriting a pilot already recorded (see `vision-warehouse/MODULE.md`'s matching Gotcha).
`deviceStreamStarted`'s `open()` call now passes `null` explicitly, with an in-line comment — a
device-pushed stream carries no acting-user context. `./mvnw -B -pl contexts/vision-perception test`
— 650 → 655 tests, all green (`UsageTrackerTest`
51→56: `engageWithAKnownPilotStampsTheOpenedUsage`, `engageWithNoKnownPilotLeavesPilotIdHonestlyNull`,
`engagingAnAlreadyOperatorEngagedUsageNeverOverwritesTheRecordedPilot`,
`promotingAStreamOpenedUsageBackfillsTheEngagingPilot`,
`promotingAStreamOpenedUsageWithNoPilotKnownLeavesPilotIdNull`). `station/vision-api`'s
`AssetSessionController#engage` is the only production caller of the widened method, passing
`CurrentUser#userId()`.

## 2026-09-01 — ASSET-FLOWS-PLAN S1 / wave BK1 (maintenance-grounding refusal)

`docs/plans/active/ASSET-FLOWS-PLAN.md` S1 (wave BK1, 2026-09-01) is **done here**: `UsageTracker#engage`
now refuses (`IllegalStateException`, unaudited) a maintenance-grounded asset via a new required
`MaintenanceQuery` collaborator bundled into `UsageTrackerSettings`; `onStreamStarted`/`disengage`
are deliberately not gated (see `MODULE.md`'s Gotchas). Sibling half of `vision-flight`'s
`DefaultFlightCommandService#arm` gate from the same wave. `./mvnw -B -pl contexts/vision-perception -am test` — **650 tests, all
green** (`UsageTrackerTest` 48→51: `engageRefusesWhenAssetIsMaintenanceGroundedWithoutOpeningAnyUsage`,
`engageProceedsWhenAnOpenMaintenanceRecordDoesNotBlockFlight`,
`onStreamStartedStillOpensAUsageForAMaintenanceGroundedAsset`). `station/vision-app`'s `usageTracker`
bean now also takes `MaintenanceQuery` and passes it to `UsageTrackerSettings.defaults(maintenanceQuery)`.

## Earlier closed plans folded into the API surface

All plan waves this doc previously tracked individually and are not covered by a dedicated entry
above are done and folded into `MODULE.md`'s API surface; see `docs/plans/done/` for
`TRACKING-PLAN.md`, `CV-CONTROL-PLAN.md`, `CV-DEMAND-PLAN.md`, `CV-CLEAN-FEED-PLAN.md`,
`STREAM-STATE-PLAN.md`, `MEDIA-SOT-PLAN.md`, `CV-RATE-CONTROL-PLAN.md`, `VISUAL-GEO-V2-PLAN.md`,
`SCALE-100-PLAN.md`, `DEAD-CODE-AUDIT.md` §2 (`RecordingPort` removed — recording now ships via
mediamtx's own recording + `StreamPublisherPort#playbackUrl`, not a frame-pushing Java port), and
`docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md` R1/R2/R3/R5d (constructor collapse;
`engage`/`disengage` replacing `onTelemetryDeviceDiscovered`; the
`AssetDirectoryService`/`UsageSessionService`/`TelemetryService` migration off raw repository
ports). `docs/plans/active/DRONE-ONBOARDING-PLAN.md` Wave O7/O11 (phase machinery,
`UsagePhaseObserver`) are done here; still open elsewhere: `evaluateLinkHealth` has no production
scheduler wiring (see `MODULE.md`'s Gotchas). `docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md` wave J1
(capability ladder + ORU domain shapes) is done here; J2 (`adapter-cv-grpc` wire codec)/J3
(`vision-app`/`vision-api` wiring+DTOs) build on it and are not this module's work.

## CV-SETTINGS-PLAN waves W1/W2 (domain + application layer introduced)

`docs/plans/active/CV-SETTINGS-PLAN.md` wave W1 (domain: `CvProfile`, `CvProfileId`, `BindingScope`,
`CvProfileBinding`, `CvProfileRepositoryPort`) and wave W2 (`application.profile`:
`CvProfileService`/`DefaultCvProfileService`, `CvProfileResolver`, `CvProfileCache`,
`CvProfileCacheSettings`, `EffectiveProfile`, `CoverageRow`, `CvProfileSpec`, `ProfileSource`, and
`DefaultStreamService.start` consulting the resolver) were **built, uncommitted at the time this
entry was written** on `feat/cv-settings` (641 tests green, `./mvnw -B -pl contexts/vision-perception test`).
Open next at that point: W3 (`storage/persistence` — JPA adapter + `V29__cv_profiles.sql` implementing
`CvProfileRepositoryPort`), W4 (`vision-learning`), W5 (`vision-api`/`vision-app` — REST controller +
DTOs, exception→HTTP mapping, and the Spring beans `DefaultStreamService`'s 8-arg constructor needed),
W6 (`vision-web` profiles page), W7 (cockpit honesty). All of W1–W8 later merged to master
2026-08-30 (see `docs/plans/active/CV-SETTINGS-CONTEXT.md` "Handoffs § W1 → W2/W3" and "§ W2 → W5"
for the frozen signatures and every flagged deviation from the plan text).
