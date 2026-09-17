# vision-api — wave history

`MODULE.md` is the contract (current wire surface, conventions, gotchas, status). This file is the
narrative: what each wave did, when, on what branch, with what test counts, and what the agent
learned on the way. Newest first.

## 2026-09-13, CV-ORCHESTRATION wave W9.1 (`tracks:` SSE carries the whole `StreamTracksResponse` snapshot, plan §4.9/§8 decision E25)

"One assembly, two transports": `StreamTracksResponse.from(TracksSnapshot, Instant)` now owns every
gating rule `GET /api/streams/{id}/tracks` used to compute inline — `stats`/`latency`/`rate`/
`detectionState` presence, the `lockedTrackId` hoist from `follow`, `FollowResponse.from`, the
`tracks[]` filter — carried verbatim from the old controller body. `StreamController#tracks`
collapses to a 3-line delegation (`requireVisible` → `streamService.tracksSnapshot(id).orElse(
TracksSnapshot.empty(id))` → `StreamTracksResponse.from(snapshot, now)`, see its own API-surface
bullet in MODULE.md). `LiveUpdateRegistry#flushPending` now publishes that same
`StreamTracksResponse` (built from the `PendingDetection`'s own `TracksSnapshot`, wave W9.0) onto
`tracks:<assetId>` instead of the bare `List<WorldObjectResponse>` fold it carried before this wave —
retiring the gap that made the REST poll the only way to learn `stats`/`latency`/`rate`/`follow` live;
the ring buffer of 1 now hands a (re)subscribing connection the last full snapshot, not just the last
object list. New `StreamTracksResponseWireContractTest` pins the wire shape against a committed
fixture, `station/vision-web/src/app/core/api/__fixtures__/stream-tracks.wire.json` (`full`+`minimal`
examples) — the same fixture wave W9.2's `stream-tracks.wire.contract.spec.ts`
(`station/vision-web`) loads to pin the TS side. **Payload size, measured against that fixture**: one
`tracks:` envelope was **243 bytes** before this wave (`WorldObject[]` only, wave W3.1) and is
**1,596 bytes** after (the whole snapshot) — the payload now rides at frame cadence, not poll
cadence, a fact the next capacity/scale decision needs. `StreamControllerTest`'s `/tracks` HTTP
assertions (`.andExpect(...)` chains) are byte-identical to before; only the Mockito arrange lines
changed, from stubbing five separate `StreamService` accessors to stubbing the one
`tracksSnapshot(streamId)` the collapsed controller now actually calls — two tests (unknown/stopped
stream, no lock issued) needed no stub at all, since an unstubbed mock already returns
`Optional.empty()`, exactly `TracksSnapshot.empty(id)`'s own trigger. `LiveUpdateRegistryTest`'s
tracks-topic assertions updated for the wider payload type. Scoped build (`./mvnw -B -pl
contexts/vision-perception,station/vision-api,station/vision-app -am clean test -DskipWeb`):
perception 874/874, **vision-api 1122/1122** (1121 baseline + 1 new contract test), vision-app
355/355, `ArchitectureTest` 14/14, `ContextArchitectureTest` 5/5 — zero failures/errors. W9.0's
prerequisite domain work is `contexts/vision-perception/MODULE.md`'s own W9.0a+W9.0 dated entry; W9.2
(`station/vision-web`) is the store-side transport flip that finally lets `DetectionsStore.tracks`
stop polling this endpoint by default.

## CV-ORCHESTRATION wave W7.3 (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7/§8, decision E22 — "a profile is a patch")

Rewrote every `dto.CvProfile*` type to mirror `contexts/vision-perception`'s W7.0/W7.1 nullable-patch
domain shape and to report per-knob provenance on **every** read, not only a save-time response:

- `CvProfileTrackingResponse` became a fully-nullable 5-field patch (`mode`/`engineId`/
  `capabilityLevel`/`verifyEveryMillis`/`followFps`, `@JsonInclude(NON_NULL)`), with `from(TrackingKnobPatch)`
  (null-tolerant) and `fromResolved(TrackingConfig)` (an EFFECTIVE fold's own tracking, always present)
  as two separately named factories rather than one overloaded method — replaced the old single
  `from(TrackingConfig)`/`toTrackingConfig()` pair. `CvProfileEventRuleResponse#from(EventRuleConfig)`
  became null-tolerant the same way, serving both the raw-profile (nullable `eventRule`) and
  EFFECTIVE (always-resolved) call sites with one method.
- New `CvKnobSourcesResponse` (8 fields, one `String` per `KnobSources` component, no `@JsonInclude` —
  every field is always present, `KnobSources`'s own compact constructor guarantees non-`null`) is the
  wire view of a fold's real per-knob provenance across every bound tier, nested in
  `EffectiveCvProfileResponse` alongside a new `intent` field (the matched tier's own persisted pick).
- `CvProfileRequest#toSpec()` became a straight, unresolved pass-through onto `CvProfileSpec` — every
  knob nullable and carried through byte-identical to what the request said, `intent` never consulted
  to seed `model`/`labelFilter` here (that is `CvProfileResolver`'s job now, at fold time, wave W7.1).
  `fieldSources()` and the nested `CvProfileRequest.Sources` record were **deleted** — superseded by
  `CvProfileResponse`'s own per-read `FieldSources`, since `intent` surviving on the saved profile means
  "was this knob seeded from intent" is answerable from the profile alone, on every read, not only by
  the one save request that happened to compute it.
- `CvProfileResponse` widened every knob to nullable (mirroring `CvProfile` field-for-field) and gained
  `intent`. Three static factories: `from(CvProfile)` (every plain `GET`/`list`/`create`/`update`),
  `platformDefault(PipelineConfig)` (unchanged shape, now null-tolerant factories underneath), and new
  `fromEffective(CvProfile matchedProfile, PipelineConfig config)` for `GET .../effective`'s nested
  `profile` — identity/bookkeeping fields come from `matchedProfile`, but every knob comes from
  `config` (the fold's own result), not `matchedProfile`'s own possibly-partial fields: under
  per-knob inheritance the matched tier may leave several knobs unset (inherited from a lower tier), so
  echoing its raw fields would misreport what actually runs. This was a deliberate behavior correction
  relative to pre-W7 (which had no partial tiers to misreport).
- **Disclosed deviation — `Sources` vs `FieldSources`:** the brief's design point widened the old
  2-field `CvProfileResponse.Sources` (`model`/`labelFilter`) to 4 fields (`model`/
  `confidenceThreshold`/`inferenceFps`/`labelFilter`), computed fresh from `CvProfile` on every read
  instead of once at save time. Widening `Sources` *in place* was tried first and reverted:
  `dto.UpdateStreamConfigRequest`/`dto.UpdateStreamConfigResponse` (the stream-config hot path,
  explicitly out of this wave's write scope) directly reuse `Sources`'s original two-argument
  constructor for their own live PATCH-path provenance reporting, and a 2→4 field widen breaks that
  unrelated, untouched call site's compile. Fix: `Sources` stays byte-identical to its pre-W7.3 shape
  (kept solely so `UpdateStreamConfigRequest`/`UpdateStreamConfigResponse` keep compiling unchanged); a
  new sibling record, `CvProfileResponse.FieldSources` (4 fields, `of(CvProfile)`/`none()`), is what
  `CvProfileResponse#sources()` is actually typed as. Both records live in the same file; each
  carries a javadoc note cross-referencing the other and explaining why two near-identical types exist.
- `CvProfileController#effective` now builds `CvKnobSourcesResponse.from(resolved.sources())`/
  `resolved.intent()` into `EffectiveCvProfileResponse`; `list`/`get`/`create`/`update` all simplified
  to the single-argument `CvProfileResponse.from(profile)` (provenance no longer threads through the
  controller — it is computed inside the DTO factory itself).
- **Disclosed ordering deviation:** this wave's DTO/controller rework was written, and the MODULE.md
  entry drafted, before wave W7.2's own persistence-side build had been re-verified green — Maven's
  reactor topologically builds every upstream module through the full requested phase even under
  `-pl <target> -am`, so `station/vision-app`'s own scoped build command failed at `station/vision-api`'s
  MAIN SOURCE compile (a real compile error, `TrackingKnobPatch` vs `TrackingConfig`/`CvProfileSpec`'s
  new arity) the moment W7.0's domain rewrite landed, regardless of which wave's turn it nominally was.
  Completing W7.3's substance immediately, rather than a throwaway compile-only patch to be redone
  properly later, was the only way to get either wave's own build green — W7.2 and W7.3 are still
  committed as two separate, correctly file-scoped commits.

Test files updated to the new shapes: `CvProfileControllerTest#profile()` (the fixture helper) now
builds a `TrackingKnobPatch`/passes `intent=null` instead of `TrackingConfig.defaults()`, and both
`effective(...)` tests' `new EffectiveProfile(...)` calls gained the two new trailing arguments
(`KnobSources`/`Intent`) `EffectiveProfile` picked up in wave W7.1; `StreamControllerTest`/
`StreamDetectionSupportTest` needed the identical `EffectiveProfile` arity fix at their own one
construction site each (mechanical, unrelated to those files' actual W5b/hot-path subject matter).
`CvProfileRequestTest`'s old 12 cases (the W2.6-era intent-fold/`fieldSources()` behavior this wave
deletes) were replaced by a new 12 pinning `toSpec()`'s unresolved pass-through:
`toSpecPassesEveryFieldThroughUnresolvedByteIdenticalToTheRequest`,
`blankModelBecomesNullOnTheSpecRatherThanAnInvalidModelRef`, `nullModelStaysNullOnTheSpec`,
`explicitModelBecomesAModelRefWithTheLatestSentinelVersion`, `intentTravelsToTheSpecUnresolvedRatherThanSeedingAnyField`,
`emptyLabelFilterIsPassedThroughAsAnExplicitAllLabelsValueNotSeeded`, `nullLabelFilterStaysNullMeaningInherit`,
`customIntentWithEmptyLabelFilterThrowsFromCvProfileSpecsOwnValidation`,
`customIntentWithNonEmptyLabelFilterSucceedsAndPassesThroughVerbatim`,
`nullTrackingStaysNullOnTheSpecMeaningInheritTheWholeGroup`,
`explicitTrackingBecomesATrackingKnobPatchWithAllFiveKnobsSet`,
`eventRuleIsAlwaysNullOnTheSpecSinceThisWireShapeNeverAcceptsIt`.

`./mvnw -B -pl station/vision-api -am test -DskipWeb` — **1121** tests, `BUILD SUCCESS`. Full three-module
command (`storage/persistence,station/vision-app -am test -DskipWeb`) also green: persistence **286**,
vision-app **357** (see `storage/persistence/MODULE.md`'s own W7.2 entry). One unrelated pre-existing
flake surfaced and fixed along the way, in `station/vision-app`'s own `PersistenceWiringTest` — see
that module's MODULE.md.

## 2026-09-13, CV-ORCHESTRATION wave W5b.3 (trace replay, plan §8 E23)

`FrameLedgerResponse` gained `detections: [{label, confidence, box{x,y,width,height}}]`, `frameWidth`,
`frameHeight`, mirroring the domain `FrameLedger`'s three new components (`contexts/vision-perception`'s
own W5b.2 entry). A new nested `FrameLedgerResponse.DetectorBoxResponse(label, confidence, box)`
reuses the existing `BoundingBoxResponse` — the same box shape `DetectionResponse` already uses —
rather than a second box DTO, so the wire's `TracedDetection`/`Detection` boxes are byte-identical
in shape even though their domain sources (`DetectorBox`/`Detection`) are deliberately unrelated
types. `CvTraceResponseWireContractTest`'s `full` example now carries **two** `FrameLedger`s
instead of one — `fullFrameLedger()` (unchanged shape, now also two distinct `DetectorBox`es at a
1920×1080 frame size) and a new `fullFrameLedgerWithoutDetections()` (`detections: []`,
`frameWidth`/`frameHeight: 0`, the untraced/pre-W5b shape) — covering both halves of the new
fields' contract in the one committed fixture, per this wave's own instruction ("populated on one
frame and empty on another"). `station/vision-web/src/app/core/api/__fixtures__/cv-trace.wire.json`
was regenerated from the test's own mismatch artifact (`cp target/cv-trace.wire.actual.json
../vision-web/src/app/core/api/__fixtures__/cv-trace.wire.json`), not hand-edited — the frontend
mirror types this fixture pins are W5b.4's job, not this step's. `./mvnw -B -pl
cv/vision-proto,contexts/vision-perception,cv/grpc,station/vision-api -am test -DskipWeb`:
vision-proto 5/0/0, vision-perception 839/0/0, adapter-cv-grpc 195/0/0, **vision-api 1121/0/0**
(same count as W5b.2 — no new `@Test` method, only richer fixture data in the two existing
examples), all green.

## 2026-09-13, CV-ORCHESTRATION wave W3.0 (`intent` reaches the live `PATCH /api/streams/{streamId}/config` hot path, plan §4.7)

Wired the same `IntentPolicyResolver` profile create/update already uses into
`StreamController#updateConfig` too. `dto.UpdateStreamConfigRequest` grew from a 7-component to an
**8-component** record (`Intent intent` appended, the last component); its existing 6-arg convenience
constructor (defaulting `labelDenyFilter`) now also passes `null` for `intent`, and `EMPTY` passes one
more `null` — no third constructor added (CLAUDE.md rule 10). `toPatch()` resolves `intent` (when
non-`null`) via `IntentPolicyResolver.resolve(intent, labelFilter)` and seeds `model`/`labelFilter`
only when this request's own fields were absent, then builds `PipelineConfigPatch` from the resolved
values. New `fieldSources()` mirrors `CvProfileRequest`'s own method field-for-field but returns
`CvProfileResponse.Sources` directly rather than a second, near-identical nested type — both DTOs
report provenance for the same two knobs, so one type serves both. `dto.UpdateStreamConfigResponse`
grew from a 3-component to a **4-component** record (`CvProfileResponse.Sources sources` appended);
its 2-arg convenience constructor now defaults `sources` to the explicit `CvProfileResponse.Sources.none()`
— never a bare `null` (rule 10 again: "a parameter may not mean 'off' by being `null`").
`StreamController#updateConfig` passes `body.fieldSources()` through as the response's 4th argument;
`UpdateStreamConfigRequest.EMPTY`'s `intent` is `null`, so the no-body case still resolves to
`Sources.none()` exactly as before this wave, byte-identical on the wire for every pre-existing caller
that never sends `intent`.

**Deliberately a *different* "was this explicit" test than `CvProfileRequest#toSpec()`/
`#fieldSources()` use, and not an inconsistency to fix later:** `CvProfileRequest`'s fields are
always-present with blank-string/empty-list as the "caller left this to the platform" sentinel,
because that wire shape has no `null`. `UpdateStreamConfigRequest` is already a true partial-patch
DTO where `null` itself means "not sent" and a non-`null` (even empty) `labelFilter` is a real,
explicit value ("keep all labels" — this record's own pre-existing javadoc). So the correct
"was this explicit" test for both `toPatch()` and `fieldSources()` is plain `model == null`/
`labelFilter == null`, not a blank/empty check — using the blank/empty test here would treat an
operator's explicit "keep all labels" `labelFilter: []` as if it were absent and let intent overwrite
it, which is exactly backwards for this DTO's contract.

**Observed JSON shape for `sources` (verified with a throwaway direct-`JsonMapper` test, not just
read from a doc comment):** when `intent` was sent, e.g. `{"intent":"VEHICLES"}`, the response's
`sources` group is `{"model":"INTENT","labelFilter":"INTENT"}`. When no `intent` was sent at all, the
observed shape is `"sources":{}` — present as an empty object, not an absent key. This is because
`CvProfileResponse.Sources`'s own class-level `@JsonInclude(NON_NULL)` only omits a `null` field
*inside* the `Sources` object; the `Sources` value itself (`Sources.none()`) is never `null` here
(rule 10 — an explicit sentinel value, not a `null`), and `UpdateStreamConfigResponse` carries no
`@JsonInclude` of its own to omit a non-null `sources` field. This measured shape is at odds with
`CvProfileResponse.Sources.none()`'s own javadoc claim ("`@JsonInclude(NON_NULL)` still omits the
whole `sources` group from the wire, exactly as a bare `null` used to") — for `UpdateStreamConfigResponse`
that claim does not hold; the group is present as `{}`. That javadoc describes `CvProfileResponse`
specifically, which was not re-verified end-to-end as part of this wave (out of this wave's scope —
`contexts/vision-perception` and other DTOs were untouched), so this paragraph records the
discrepancy rather than "fixing" that unrelated file's claim on unverified authority.

`./mvnw -B -pl station/vision-api -am test -DskipWeb`: **1112 → 1120 tests, all green** (8 new
`StreamControllerTest` cases: one per `Intent` value proving the resolved `model`/`labelFilter` reach
`PipelineConfigPatch` unchanged from `IntentPolicyResolver`'s own values, an "explicit wins" case, two
response-shape cases for `sources` with and without `intent`, and — added during the W3.8 merge review,
closing a gap the javadoc already promised — `updateConfigReturns400ForIntentCustomWithNoLabelFilter`,
asserting `{"intent":"CUSTOM"}` with no `labelFilter` is a 400 `BAD_REQUEST` because
`IntentPolicyResolver#resolve` throws for `CUSTOM` with empty/null `customClasses`). No
`contexts/vision-perception`, `station/vision-web`, or other module change — Java-only, `vision-api`
only, per this wave's scope.

## CV-ORCHESTRATION wave W5.0 (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4/§4.8, engineer inspector wire contract)

New `dto.CvTraceResponseWireContractTest`, the same fixture-comparison idiom as
`WorldObjectResponseWireContractTest` (W2.8) — builds a `full` `CvTraceResponse` covering every
`GateReason` value (all seven, declaration order) plus a `SENT` and a `PROBE` `GateDecisionResponse`,
one `FrameLedgerResponse` with a `RAN`/`SKIPPED`/`FAILED` `LedgerEntryResponse` triad and one
`ObjectEvidenceResponse` claim shaped like cv-service's real `predict.cv` evidence (`predicted`/
`held`/`velocity` keys, `cv/cv-service/cv_service/orchestration/contributors/predict.py`), and one
`WorldObjectResponse`; and a `minimal` never-traced-this-stream shape (`gate`/`frame`/`world` all
empty lists, per `CvTraceResponse`'s own "never errors" contract). Fixture committed at
`station/vision-web/src/app/core/api/__fixtures__/cv-trace.wire.json`, consumed by W5.1's
`cv-trace.wire.contract.spec.ts`. `./mvnw -B -pl station/vision-api -am test -DskipWeb` —
**1113 tests, all green** (up from 1097 counted at W2.7; the gap includes tests added by other
waves running on this same branch concurrently, not solely this step).

**Plan-vs-code discrepancy disclosed here** (not acted on, since it is prose-only): §4.4's
narrative describes coalesced `SKIPPED` gate entries as carrying "a count" that "rides along."
Reading `contexts/vision-perception`'s `FrameGateLedger` shows coalescing instead *replaces* the
previous entry's timestamp/frameSequence/demand snapshot outright — there is no `count` field
anywhere on `GateDecision`/`GateDecisionResponse`, and this test does not fabricate one. A
coalesced run is indistinguishable on the wire from a single decision at the same reason; only the
refreshed `atMillis`/`frameSequence` say time passed. (This fact is now also carried as a Gotcha in
MODULE.md.)

## CV-ORCHESTRATION wave W2.8 test/gap follow-up (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7)

W2.6 shipped `CvProfileRequest`'s `intent` fold without tests; closed here with
`dto.CvProfileRequestTest` (12 cases: null-intent passthrough, blank-model/empty-labelFilter seeding,
an explicit value winning over the intent's own choice, `CUSTOM` returning the caller's own list
unchanged, the synthesized `eventRule` confidence, and `fieldSources()`'s per-knob provenance) and
`support.StreamDetectionSupportTest` (2 cases, new file — proves an asset-tier profile fold and the
session-tier `StartStreamRequest#mergeOnto` override coexist in one resolved `PipelineConfig`, and
that an unowned device skips the fold entirely). Also new at the time: `CvProfileRequest#fieldSources()`
→ nested `CvProfileRequest.Sources(ProfileSource model, ProfileSource labelFilter)`, reporting
`contexts/vision-perception`'s new `ProfileSource.INTENT` for exactly the two fields `toSpec()`
actually seeded from `intent`; `CvProfileResponse` grew from a 15-component to a 16-component record
(`Sources sources` appended, `@JsonInclude(NON_NULL)` — omitted, not `null`, for every read with no
originating request) with its own nested `CvProfileResponse.Sources`, populated by
`CvProfileController#create`/`#update` from `request.fieldSources()` so a caller (W3's Tuning modal)
could render "resolved from intent People." **W2.10 fix:** the one-arg `from(CvProfile)` overload
(silently delegating to `from(profile, null)`) was the withdrawn "null means the feature is off"
convenience-overload pattern (CLAUDE.md rule 10) in static-factory form — removed.
`CvProfileResponse.from(CvProfile, Sources)` became the sole factory; `Sources.none()` (both fields
`null`, so `@JsonInclude(NON_NULL)` still omits the group) is what every read-path caller (`list`,
`get`, `effective`, `platformDefault`) passed explicitly.

**Disclosed gap at the time (superseded by wave W7.3 — see that entry above):**
`confidenceThreshold`/`inferenceFps` had no `sources` representation at all under this shape, even
though `IntentPolicyResolver` computes an `IntentPolicy#detectFloor()`/`#rateCeiling()` for them,
because those two `CvProfileRequest` fields were bare primitives with no "caller left this to the
platform" sentinel under this frozen wire contract. Wave W7.3 replaced this whole `fieldSources()`/
request-time-`Sources` mechanism with `CvProfileResponse.FieldSources` (4 fields, computed on every
read) and `CvKnobSourcesResponse` (8 fields, the fold's real per-knob provenance), which cover
`confidenceThreshold`/`inferenceFps` — this gap does not carry forward as a current fact.

No `station/vision-web` change accompanied this follow-up: `models.ts` had no exhaustive
wire-contract-spec test for `CvProfile`/`CvProfileRequest` and no mirror of `intent` yet (still
Java-only), so `sources` needed none either at this point.

## CV-ORCHESTRATION waves W2.5 (`GET /api/streams/{id}/cv/trace`, TRACKS/CV_TRACE SSE, cv status capacity, commit `cdf17816`) and W2.6 (profile fold + `intent`, Java only, commit `fc0bfa00`)

W2.5: new `dto.CvTraceResponse`/`GateDecisionResponse`/`FrameLedgerResponse`, new
`LiveTopicKind.TRACKS`/`.CV_TRACE` plus `LiveUpdateRegistry#watchingTrace(AssetId)`, new
`live.LiveAndPollTraceDemand implements TraceDemandPort` (`contexts/vision-perception`'s new port),
and `StreamDetectionSupport` grew from a 5-component to a 6-component record (`traceDemand`
appended, nullable on the same "demand gate not wired at all" condition as `demand`, backing
`#touchedTrace(StreamId)`). `SystemStatusResponse`'s cv-service row gained no new structured field —
capacity facts fold into the existing free-text `detail` string (`cv/grpc`'s `CvStatusProvider`).
W2.6: `dto.CvProfileRequest` grew from a 9-component to a 10-component record (`Intent intent`
appended, nullable — `null` left every other field exactly as sent); `toSpec()` at the time resolved
it via `contexts/vision-perception`'s new `IntentPolicyResolver` to seed a blank `model`/empty
`labelFilter`/the synthesized `eventRule`'s confidence only (this request-time seeding behavior was
later replaced by wave W7.3's unresolved pass-through — see that entry above). No web surface consumed
`intent` at this point (Java-only wave).

`./mvnw -B -pl contexts/vision-perception,station/vision-api -am test` — `station/vision-api`
**1074 → 1097 tests, all green** (new coverage: `LiveUpdateRegistryTest`'s
`aResultWithAPopulatedObjectMirrorBroadcastsOntoTheTracksTopic`/
`aResultWithATracedLedgerBroadcastsOntoTheCvTraceTopic`/
`aResultWithNoLedgerNeverBroadcastsOntoTheCvTraceTopic`/`watchingTraceIsFalseWhenNoConnectionIsSubscribed`/
`watchingTraceIsTrueOnceAConnectionSubscribesToThatAssetsCvTraceTopic`, plus 7 new
`StreamControllerTest` cases for the `trace` endpoint); full `station/vision-app -am test` reactor
(vision-perception/adapter-cv-grpc/vision-api/vision-web/vision-app) **`BUILD SUCCESS`**,
`vision-app` itself **355 tests, all green**. A W2.7 documentation-only pass (this file, `cv/grpc`'s,
`contexts/vision-perception`'s, `station/vision-app`'s and `storage/persistence`'s own MODULE.md)
followed with no source change — counts above carried forward from W2.5/W2.6's own measurement, not
re-run for that step per its "skip the tests" instruction.

## CV-ORCHESTRATION wave W1 step 5 "wire mirror" (this module's file scope: DTOs + `StreamController`)

New `dto.ObjectStateResponse` — `@JsonInclude(NON_NULL)` wire mirror of the domain `ObjectState`
(`contexts/vision-perception`, landed in an earlier W1 step), with one nested static record per facet
(`Identity`, `Kinematics`, `Belief`, `Provenance`, `MemoryFacts`, `LockFacts`, `Timing`,
`LabelCandidate`), a `static from(ObjectState)` factory on every record, and `Kinematics` reusing the
shared `BoundingBoxResponse` for its `box`/`detectorBox`/`trackerBox`/`predictedBox`. JSON keys matched
the already-committed TypeScript mirror (`station/vision-web/src/app/core/api/models.ts`'s
`ObjectState`) field-for-field — verified directly against that file, not just against the domain
record. (This DTO's shape is unaffected by later waves and is carried forward as current fact in
MODULE.md.)

`DetectionResultResponse` gained a 7th component, `objects` (`List<ObjectStateResponse>`, never
`null`, sourced from `DetectionResult#objects()`) — this shape is still current. `StreamTracksResponse`
also gained `objects` as its 9th and last component at this point, typed `List<ObjectStateResponse>` —
**superseded by wave W2.8** (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.6): both
`StreamTracksResponse#objects` and `CvTraceResponse#world` were retyped to `List<WorldObjectResponse>`
(new file, `dto/WorldObjectResponse.java` — `{state, operator, event, render}`, `state` the same
`ObjectStateResponse` verbatim) and sourced from the new `StreamService#worldObjects(StreamId)` rather
than `#objects(StreamId)`. `DetectionResultResponse#objects` and the `detections:` SSE topic stayed
unchanged — they deliberately keep the flat mirror, since the operator/event/render relations are a
platform concern cv-service's own wire shape (and the durable/detections path mirroring it) must never
carry. `LiveUpdateRegistry`'s `tracks:` envelope payload followed the same retype (see MODULE.md's
`live` package section) — it and the `/tracks`/`/cv/trace` REST reads now share one
`WorldObjectResponse::from` mapping fed by one `WorldModel` fold per result, never re-folded per
surface. Per CLAUDE.md rule 10, both DTOs' dead N-1-arg convenience constructors were deleted rather
than growing a new overload — confirmed zero call sites for all four (`DetectionResultResponse`'s
5-arg ctor, `StreamTracksResponse`'s three) both before and after this change via
`grep -rn "new DetectionResultResponse(\|new StreamTracksResponse("`.

`./mvnw -B -pl contexts/vision-perception,cv/grpc,station/vision-api,storage/persistence,contexts/vision-events,contexts/vision-learning,station/vision-app -am -DskipWeb test` —
`station/vision-api` **1069 → 1074 tests, all green** (5 new: 4 `ObjectStateResponseTest` cases —
`everyGroupAbsentSerializesToMissingKeysNotNulls`,
`everyGroupPresentRoundTripsEveryLeafByPairwiseDistinctValue`,
`kinematicsOmitsIndividualSourceBoxesTheyDoNotClaim`, `idAndStreamIdMapToTheWireShapeExactly` — against
the real Jackson setup, not a mocked one; plus 1 new `StreamControllerTest#tracksReportsTheObjectMirrorAlongsideTracks`).
`BUILD SUCCESS`, all green. `contexts/vision-perception` **755 → 760**, `station/vision-app` **354 → 354**
(only a one-line `LiveFrameFallbackStreamService#objects` delegation, no test file touched). Docker ran
for real for this module's and `vision-app`'s own Testcontainers-based suites (confirmed via live
Flyway migration through `V35`). `storage/persistence` **283 tests, 0 failures**, including
`PostgresDockerIntegrationTest$DetectionRepositoryTests`. **Gotcha hit and resolved during this wave:
do not read that outer test class's own `surefire-reports/*.txt` and conclude it skipped** — the class
holds no `@Test` method of its own, so its report honestly says `Tests run: 0` while its 35 `@Nested`
classes each get their own line in the build log and none of their own `.txt`. A per-file tally over
`surefire-reports/*.txt` therefore under-counts this module by an order of magnitude; trust Maven's
per-module `Results:` line instead. An earlier pass through this wave mistook exactly that for a
Docker-gate flake.

## LIVE-POLL-RETIREMENT-PLAN.md waves L3+L4 (2026-09-06, branch `feat/live-topics-zones-system`, uncommitted at time of writing)

L3: new `zones` SSE topic — `GeofenceLiveUpdatePort`/`GeofenceZoneEvent` (`contexts/vision-flight`),
`GeofenceZoneEventPayload` (`dto/`), `LiveTopicKind.ZONES`/`LiveTopic.ZONES`, a `zonesBuffer` (FIFO,
shares `eventBufferCapacity`) and `publishZoneEvent` on `LiveUpdateRegistry` (now implementing seven
ports). L4: new `system` SSE topic backed by `live/SystemStatusSampler` (a fellow vision-api class,
not a per-context port) — `LiveUpdateRegistry` gained one public method (`publishSystemStatus`) and
one inline-initialized buffer (`systemBuffer`, capacity-1 latest-only), no new constructor
collaborator. L4a: `support/SystemStatusReader` extracted `safeStatus`/`overall`(now `worstHealth`,
made public for reuse) out of `SystemStatusController` — that controller's wire output stayed
unchanged, its own pre-existing test suite (`SystemStatusControllerTest`, untouched) is the guardrail.
(Both topics' full current mechanics live in MODULE.md's "Live updates" section, including the
self-feedback-mitigation writeup and the widened `@Qualifier("liveUpdateRegistry")` gotcha.)

No new `ApiExceptionHandler` mapping — neither wave introduced a new HTTP-facing failure mode. No new
REST endpoint — both waves were pure SSE-topic additions.

Tests added: `LiveUpdateRegistryTest` gained 3 (`publishZoneEventAppendsAZonesEnvelopeForEachAction`,
`deletedZoneEventCarriesTheLastKnownZoneInFullOnTheWire`,
`publishZoneEventReachesASubscribedConnection`); `LiveTopicTest` gained 1
(`parsesZonesAndSystemAsTheSharedConstants`); new `SystemStatusSamplerTest` (5 cases) proved the three
L4d requirements plus one extra: (1) `unchangedStatusSampledRepeatedlyBroadcastsExactlyOnce`, (2)
`aLiveUpdatesOnlyChangeNeverTriggersAnAdditionalBroadcast` (the self-feedback proof), (3)
`aRealSubsystemHealthChangeTriggersOneAdditionalBroadcast` (contrast case), plus
`checkedAtAloneNeverTriggersABroadcast` and `constructorRejectsNullCollaborators`.
`contexts/vision-flight`'s `DefaultGeofenceServiceTest` was widened in place (not new test methods) to
assert publish-on-create/update/delete and the `DELETED`-carries-last-known-zone contract — see that
module's own MODULE.md entry.

`./mvnw -B -pl station/vision-api -am test -DskipWeb` — **1069/1069** green (1060 → 1069, +9: 3+1+5
above). `./mvnw -B -pl core/vision-kernel,core/vision-platform,contexts/vision-warehouse,contexts/vision-identity,
contexts/vision-flight,contexts/vision-perception,contexts/vision-map,contexts/vision-events,
contexts/vision-learning,contexts/vision-simulation install -DskipTests` then `./mvnw -B -pl
storage/persistence,station/vision-api,station/vision-app test -DskipWeb` (the `-am`-on-`vision-app`
form was tried first and pulled in `adapter-rtsp` as a reactor dependency, whose
`MediamtxDockerIntegrationTest` hit a genuine, pre-existing, unrelated Docker/network flake — RX side
never connected to a real mediamtx container within its 1-minute bound; switched to the
install-then-`-pl`-without-`-am` recipe instead, which reuses `adapter-rtsp`'s already-installed
`~/.m2` jar untouched, since this task's file scope never touched that module) — `storage/persistence`
**283/283** (unchanged, read-only this wave; Postgres Testcontainers ran for real), `station/vision-api`
**1069/1069**, `station/vision-app` **354/354** (`LiveWiringTest` 5→6, `+systemStatusSamplerBeanExists`;
`LiveDisabledWiringTest` renamed one test in place to also assert `SystemStatusSampler`'s bean absence)
— all green, `BUILD SUCCESS`, default-config bar held throughout both `vision.live.enabled=true`
(default) and `=false` wiring tests. Docker ran for real. Nothing deferred except the pre-existing
`everDropped`/`live-updates` `DEGRADED` defect, explicitly out of scope per this wave's own task spec.

## ALWAYS-ON-FLOW-PLAN.md wave D1 (2026-09-06)

Confirmed `PATCH /api/assets/{id}` needed no change for a new per-asset `DetectionPolicy` opt-in
(`contexts/vision-perception`'s new `DetectionPolicy.ATTRIBUTE_KEY = "cv.detection-policy"`, values
`"on-view"`/`"always"`): it is stored under the existing free-form `attributes` map, so the generic
PATCH already round-trips it — setting an asset's policy is just
`PATCH {"attributes":{"cv.detection-policy":"always"}}` under the pre-existing `scope`-only authority
level, no new endpoint/DTO/wire contract added. (The durable fact — that this policy rides the generic
attributes PATCH — is carried forward in MODULE.md's Conventions.)

## fix/fleet-topic-scope: closed the `fleet`-topic visibility-scope leak

`freshFleetEnvelope`'s unscoped `AssetService#assets()` snapshot used to reach every connection
unfiltered — `mayReceive` only ever checked `map` events and per-asset envelopes, so a `fleet`
envelope (neither) always passed. Replaced `LiveConnection#mayReceive(envelope): boolean` with
`project(envelope): LiveEnvelopeResponse` (nullable) reusing the connection's existing
`assetVisibility` predicate (the same one `LiveAssetAccess#deliveryPredicate` builds from
`StreamAccess#visibleAsset`, which `AssetService#assets(scope, includeDeleted)` — i.e.
`GET /api/assets` — already applies): `map` unchanged (envelope-or-null by `layerId`), per-asset
unchanged (envelope-or-null by `assetId`), `fleet` now narrows its `List<AssetSummaryResponse>` to
visible entries and never returns `null` (an empty list is correct for a viewer with nothing visible),
everything else passes through as the identical instance (load-bearing for `broadcast`'s
serialize-once reuse). Fixed all three call sites that used to hand a connection an unfiltered
envelope: `broadcast` (rewritten to project first, reuse the one shared serialized `String` only when
`project` returned the same instance back), `connect()`'s snapshot/resume burst, and
`updateTopics()`'s newly-added-topic burst — the last two are what actually leaked in production,
since a fresh connection's seeded `fleet` snapshot and any `Last-Event-ID` resume both replayed
straight from the buffer with no per-viewer narrowing at all. `fleetBuffer` itself stays unfiltered by
design, matching `map`'s buffer, so resume re-filters against the resuming viewer's *current* scope
rather than the scope of whoever happened to seed the buffer. Fixed every now-false "only `map` is
filtered" javadoc claim found by grep (`LiveUpdateRegistry` class doc, `broadcast`, the old
`mayReceive`, `LiveTopicKind.FLEET`/`MAP`, `LiveTopic.MAP`, `LiveAssetAccess`,
`LiveEnvelopeResponse`'s `@param payload`) — four more locations than the four named going in, since
the claim had spread past them. (The resulting current behavior — `fleet` filtered per connection,
same mechanism as `map` — is documented in MODULE.md's "Live updates" section.)

No new collaborator, query, DTO field, or exception mapping — this was a pure delivery-filtering fix
using a predicate the connection already carried; `ApiExceptionHandler`'s table was unchanged. No new
endpoint or wire-shape change either: `fleet`'s envelope shape (`List<AssetSummaryResponse>`) stayed
exactly what it always was, only which elements a given connection receives changed.

Six required proofs, three end-to-end (`LiveFleetScopingTest`, MockMvc over the real
`LiveController`/`LiveUpdateRegistry`/`LiveAssetAccess`/`StreamAccess` chain, mirroring
`LiveAssetScopingTest`'s established pattern) plus three pure-unit (`LiveUpdateRegistryTest`,
package-private `register()`/`publishFleetChanged()` seam): (1) a GROUPS-scoped viewer's fleet
broadcast only carries its own visible assets; (2) the connect-time seeded snapshot — the actual leak
path — is already narrowed; (3) a `Last-Event-ID` resume re-filters the buffer per resuming viewer,
proving the buffer itself was never filtered; (4) UNBOUNDED (admin) sees every asset, no regression;
(5) a viewer whose scope includes nothing gets an envelope with an empty list, never a dropped one;
(6) non-fleet topics are byte-identical to before, guarding serialize-once —
`nonFleetTopicsReuseTheIdenticalSerializedStringAcrossConnectionsGuardingSerializeOnce` asserts
`assertSame` on the delivered `String` across two connections with divergent predicates.

`./mvnw -B -pl station/vision-api -am test -DskipWeb` — `station/vision-api` **1060** (before this
task's 8 new tests: **1052**), 0 failures/errors/skipped; full reactor summary (`kernel` through
`vision-simulation` plus `vision-api` itself) all `SUCCESS`, `BUILD SUCCESS`, exit 0. No feature flag
gates this change, so there was no default-config-off suite to separately hold green. Docker not
needed/not run — this module's tests are pure-unit/MockMvc, no Testcontainers dependency in the
touched files. `vision-web`'s `drone-picker-facade.ts` was read for context, per instruction, but not
modified, and self-corrects once the server stops over-sending.

## CREW-CONTROL-PLAN.md wave W2 (2026-09-05, uncommitted at time of writing, branch `feat/crew-control`)

New `security.SeatAccess` (the one collaborator every guard calls — 5 params, at the constructor
ceiling) + `security.SeatAccessSettings` (plain, framework-free settings record bridged from
`vision-app`'s `VisionCrewProperties`) + `support.SeatSupport` (device/stream→asset resolution,
display-name lookup, `FORCE`/`DENIED:SEAT_HELD` audit writes) + new `SeatController`
(`GET`/`POST`/`DELETE /api/assets/{id}/seats[/{kind}]`, `docs/plans/active/CREW-CONTROL-PLAN.md`
§3.6 frozen wire contract) + 3 new DTOs
(`SeatsResponse`/`SeatHolderResponse`/`TakeSeatRequest`) + the seat guard threaded into
`FlightCommandController` (6 command handlers), `AssetStreamController` (start/stop),
`AssetSessionController` (engage/disengage), `StreamController` (start/stop/updateConfig-family), and
`ManualControlWebSocketHandler#handleEngage` — see MODULE.md's `/ws/manual-control` and endpoint-table
sections for the exact insertion points and rule composition, which this wave established.
`AssetAuthority`/`CapabilityAssetAuthority` already existed (AUTH-ROLES-PLAN wave B4) and needed no
change; this wave only added `SeatAccess` as a second, later-consulted gate.

Deviations, each one-line: (1) fixed a genuine pre-existing compile defect unrelated to
CREW-CONTROL, in `vision-app`'s `LiveFrameFallbackStreamService` (missing `StreamService#followStatus`
override — `git blame`-confirmed leftover from an already-merged, unrelated commit,
`29536635 feat(track-follow W2)`, that widened the interface without updating this one decorator);
fixed minimally, matching the class's own "every other method delegates unchanged" pattern. (2) Four
constructors were pushed past the 5-arg ceiling — `SeatAccess` and `SeatSupport` land exactly at 5,
`AssetStreamController` to 6, `StreamController` to 7 — each documented in its own javadoc;
`StreamController` was already at 6 for `StreamAccess` before this wave, so 7 continued an existing
precedent rather than opening a new one. (3) §3.6 worked one example (`force` on the flight seat only);
this implementation generalized `force`/`mayForceSeat` to both seat kinds symmetrically, since the
plan's own rule table (§3.2) states the force rule kind-agnostically and a flight-only implementation
would have been an arbitrary, undocumented asymmetry. (4) The explicit-actor overload
`requireFlightSeat(UserId, AssetId)` trusts `mayFly=true` unconditionally (mirrors
`CapabilityAssetAuthority`'s own explicit-actor precedent, AUTH-ROLES wave B4) since the WS handler
already ran its own `mayFly` check immediately before calling it.

`./mvnw -B -pl contexts/vision-flight,station/vision-api,station/vision-app test -DskipWeb` —
`contexts/vision-flight` **447** (unchanged — W2's file scope excludes this module, which W1 already
shipped), `station/vision-api` **1046** (+37 over 1009: 22 `SeatAccessTest` + 12 `SeatControllerTest`
+ 1 `StreamControllerTest` case proving rule 3 — flight-seat holder never conflicts on the camera
seat and preempts any prior camera holder — + 1 `ManualControlWebSocketHandlerTest` case proving the
WS `SEAT_HELD` denial fires before `ManualControlService#engage` is ever called + 1
`FlightCommandControllerTest` case), `station/vision-app` **334** (unchanged — no test file in this
module's scope touched). All green, 0 failures/errors. Docker ran for real (Testcontainers
`postgres:16`, Flyway migrated through `V34`). Default-config guardrail held: `vision.crew.enabled`
defaults `false`, and every pre-existing test in all three modules is unmodified and still green
under that default — the four pre-existing controller/WS-handler test files needed only a
disabled/pass-through `SeatAccess` threaded into their existing construction call sites to keep
compiling, never a behavioral change. Nothing deferred to a later wave from this module's own scope;
W3 (crew UI, vision-web) is a separate, concurrently-running agent's file scope, not this one's.

## SOURCE-ONBOARDING-2-PLAN.md §3.2 wave C (2026-09-05, uncommitted at time of writing)

C1 (`POST /api/discovery/inbox/{id}/attach`), C2 (new `DiscoveryStatusController`, `GET
/api/discovery/status`), C3 (`SystemNetworkController`/`NetworkAddressResponse`/
`SystemNetworkResponse` widened for `kind` + mediamtx push facts), C4 (new `discovery` SSE topic),
C5 (`POST /api/discovery/inbox/{id}/restore`) — see MODULE.md's endpoint table, exception-mapping
table, DTO conventions, and "Live updates" section for the full shapes this wave established; C6
(a real `StreamStateObserver` wired to `devices`) was wholly a `vision-app` change, detailed in that
module's own MODULE.md. Also fixed two pre-existing test compile breaks found while wiring this
wave, unrelated to discovery/network but blocking this module's test compile either way: `git
blame`-confirmed leftovers from an earlier, already-merged wave that widened `SourceHealth` to a
3-arg canonical constructor (`id, status, lastScanAt`) without updating
`DiscoveryInboxControllerTest`'s two `new SourceHealth("mediamtx", SourceStatus.UNREACHABLE)`
2-arg call sites (fixed by adding `Instant.now()` as the third argument).

**Deliberate deviation, documented in place**: `LiveUpdateDiscoveryInboxService` (the decorator that
turns a `ReportOutcome#changed()` into a `discovery` SSE publish) lives in `vision-app` and depends
directly on the concrete `LiveUpdateRegistry` class, not a new per-context `*LiveUpdatePort`
interface — every other such decorator in this codebase depends on a narrow port instead. Adding a
`DiscoveryLiveUpdatePort` to `contexts/vision-warehouse` would have meant writing outside this
wave's file scope (that context belonged to earlier waves A/B); see that class's own javadoc in
`vision-app` for the full reasoning. (Carried forward as a short Gotcha in MODULE.md.)

`./mvnw -B -pl core/vision-kernel,core/vision-platform,contexts/vision-warehouse,contexts/vision-identity,contexts/vision-flight,contexts/vision-perception,contexts/vision-map,contexts/vision-events,contexts/vision-learning,contexts/vision-simulation
install -DskipTests` (this worktree's own source — the shared `~/.m2` local repo had been
concurrently overwritten by another agent's build of the main checkout's identity module mid-task,
surfacing as `VisibilityScope`-vs-`Authority` and `AssignmentService`/`UserService` signature
mismatches with no relation to this wave's own edits; reinstalling from this worktree's source
resolved it — see `station/vision-app/MODULE.md`'s own entry for the parallel adapter-module
reinstall this same contamination required) then `./mvnw -B -pl
storage/persistence,station/vision-api,station/vision-app test -DskipWeb` — `storage/persistence`
**274** (unchanged, read-only this wave; docker ran, not skipped), `station/vision-api` **965**
(+11 over 954: 7 new `DiscoveryInboxControllerTest` cases for `attach`/`restore`, 2 new
`SystemNetworkControllerTest` cases for the video-push-facts present/absent cases, 2 new
`LocalNetworkAddressesTest` cases for `VIRTUAL` classification + LAN-before-VIRTUAL sort order),
`station/vision-app` **326** (unchanged in count from the prior B1 entry — see that module's own
MODULE.md for wave C's actual test-count delta there) — all green, default-config bar held
throughout (`vision.discovery.live.enabled`/`vision.live.stream-state-push.enabled` both default
**true**, proven by this same green run rather than a separate flag-off suite). Nothing deferred.

## AUTH-ROLES-PLAN wave B6

The ~34 remaining `canManageOrg`/`canManage`/`canAdminister` call sites this wave's own plan text
named were migrated onto `Authority` everywhere (see MODULE.md's "Authorization tags" table for the
current `mayManageOrg()`/`mayManageFleet(ownership)`/`mayAdminister()` shape) — by the time this wave
reached vision-api, most controller production call sites had already migrated in an earlier pass of
the same wave; what remained here was fixing the test fixtures and fixing one missed production call
site:

- **9 controller test fixtures** (`GeoRegionControllerTest`, `CameraPoseControllerTest`,
  `GeofenceControllerTest`, `DiscoveryInboxControllerTest`, `CategoryControllerTest`,
  `EventControllerTest`, `SimulationControllerTest`, `AssetControllerTest`, `DeviceControllerTest`) had
  a `currentUserWithScope(VisibilityScope scope)`-style `CurrentUser` fake whose `authority()` override
  threw `UnsupportedOperationException("<Controller> never calls authority()")` — written before this
  wave's controllers actually started calling `authority()`. Once they did, every one of these 9 broke
  (40 errors on the first honest, non-`-q` run). Fixed uniformly: `return new Authority(scope,
  EnumSet.allOf(Capability.class))` — full capabilities, deliberately, so a PILOT/MANAGER-scope
  test case still proves the *scope* gate, not a missing capability. `AssetControllerTest`/
  `DeviceControllerTest` reference `com.drones.vision.platform.Capability` fully-qualified (no import)
  since both already import `com.drones.vision.kernel.Capability` — a different type, unrelated device
  capabilities (`VIDEO`, …) — under the same simple name.
- **`AfterActionAssemblerTest`/`AssetParameterControllerTest`** — same "full capabilities, restricted
  scope" pattern used deliberately where a test needs to prove a *scope* boundary causes a denial.
- **`security.StreamAccess`** — a genuine production call site this wave's earlier grep-based "zero
  remaining callers" pass missed, surfaced only by running the plan's full multi-module `-am test`
  build after `core/vision-platform` deleted the three methods outright (`NoSuchMethodError` at
  runtime, 42+ cascading errors in `StreamControllerTest`/`HlsProxyControllerTest`/
  `LiveAssetAccessDevPrincipalTest`). Two call sites, `visible(DeviceId)` and `visibleAsset(AssetId,
  VisibilityScope)` — both an "unowned device/asset falls back to a caller whose scope is deployment-
  wide" check. Fixed by replacing `scope.canAdminister()` with `scope.isUnbounded()` directly, **not**
  by widening `StreamAccess` to carry an `Authority` — the deleted `canAdminister()`'s own body was
  always exactly `kind == UNBOUNDED` (confirmed from `git log`), so `isUnbounded()` is a byte-identical
  replacement; widening instead would have rippled into `StreamAccess`'s eight `StreamController`
  callers for no behavior change, and would have made this fallback capability-gated
  (`Authority#mayAdminister()` also requires `Capability.MANAGE_ORG`) when it never was before. Class
  javadoc updated in three places to stop citing the now-deleted methods and to state plainly why this
  fallback is deliberately scope-only. (Carried forward as a Gotcha in MODULE.md.)

`./mvnw -B -pl station/vision-api test -DskipWeb` — **985/985** green, 0 failures/errors — the same
count before and after this wave's fixes. The doc's last-recorded vision-api count (**979**, wave B4
entry below) predates an undocumented wave B5 (Spring Session JDBC — the `V34` migration this module's
tests already exercise) that isn't this wave's to reconstruct; 985 is this wave's own before-and-after
baseline, not a delta from 979. Plan's full green line (`core/vision-platform,contexts/vision-warehouse,contexts/vision-flight,
contexts/vision-perception,contexts/vision-learning,contexts/vision-map,contexts/vision-identity,
station/vision-api,station/vision-app -am test`) — **BUILD SUCCESS** end-to-end in the foreground (a
first attempt was backgrounded and lost when the agent turn ended — backgrounded builds do not
survive the turn that started them); see `core/vision-platform/MODULE.md`'s own B6 entry for the full
per-module tally. Docker ran for real (Testcontainers `postgres:16`). `vision.auth.enabled` stays
`false` by default, unchanged — the default-config bar held throughout. The plan's B1 text also asked
for an ArchUnit rule banning new `canManageOrg`/`canManage`/`canAdminister` call sites (deferred there
to this wave); it was never added and is now moot — the three methods are deleted outright, a stronger
guarantee than any reflection-based check over a method that no longer exists to call. Wave B0b (flip
`vision.auth.enabled`'s default) is the only item this plan still has open.

## AUTH-ROLES-PLAN wave B4

New `security.AssetAuthority` (interface, frozen 3-method shape per §3.9 — a join point shared with
CREW-CONTROL-PLAN §3.7) + `CapabilityAssetAuthority` (the one implementation) — see MODULE.md's
API-surface/Live-updates sections for every gated call site (`ManualControlWebSocketHandler#handleEngage`,
`FlightCommandController`'s six commands, `HlsProxyController#proxy` via the new
`StreamAccess#requireVisibleForHlsProxy`) this wave established, and its Gotchas for why the HLS gate
is a second `StreamAccess` method rather than a change to the shared `requireVisible(StreamId)`.
`AssetSessionController#disengage` now records an `AuditTrailPort` entry naming the calling user (D17)
— attribution only; no seat/arbitration logic, which stayed CREW-CONTROL's to add. `SecurityConfig`'s
secured-chain matcher gained `/hls/**` (D10, `vision-app`). Deviations, each one-line: (1)
`CapabilityAssetAuthority` gained a second public method, `mayFly(Authority, UserId, AssetId)`, not on
the frozen interface — the WebSocket message thread has no `SecurityContext`, so the interface's
ambient-`CurrentUser` `mayFly(AssetId)` cannot be called from `ManualControlWebSocketHandler` at all
once auth is enabled; (2) D17 (actor attribution on disengage) was solved via the already-vision-api-
reachable `AuditTrailPort` rather than widening `UsageTracker#disengage`'s signature or adding a field
to `AssetUsage` — both of those modules were reserved for a concurrent agent's own wave; (3)
`contexts/vision-flight`/`contexts/vision-identity` were not touched despite being named in the plan's
literal B4 file list — every call site this wave actually needed lives in `vision-api`.
`./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app
test -DskipWeb` — vision-api **979** (961 → 979, +18: 12 `CapabilityAssetAuthorityTest` + 2
`ManualControlWebSocketHandlerTest` + 2 `AssetSessionControllerTest` + 2 `FlightCommandControllerTest`),
`storage/persistence` unaffected at **276**, `station/vision-app` **328** (326 → 328, +2). Docker ran
for real (Testcontainers `postgres:16`, Flyway unchanged at `V33` — this wave added no migration).
`vision.auth.enabled` stays `false` by default, unchanged; the default-config auth-off suites stayed
green throughout. Waves B5/B6/B0b open at the time.

## AUTH-ROLES-PLAN wave B3

New `BootstrapController` (`GET`/`POST /api/auth/bootstrap`, `@OpenByDesign`, anonymous, `permitAll`),
`AuthPasswordController` (`POST /api/auth/password`, self-service), and two new `UserAdminController`
handlers (`POST /api/users/{userId}/password`, `PUT /api/users/{userId}/memberships`) — see
MODULE.md's endpoint table for the shapes this wave introduced. `AuthController#login` gained an
optional `kiosk` body field; a non-`VIEWER` requesting a kiosk session is refused `400
KIOSK_NOT_PERMITTED` *after* a real credential check (so the already-established session is explicitly
torn down via `SessionAuthenticator#logout`, not merely left to expire). `AssignmentController#assign`'s
body gained an optional `role` field (`AssignAssetRequest#toRole()`, absent body/field → `PILOT`,
byte-identical to every pre-B3 caller); `pilots()`/`myAssignments()` now enrich each entry with its
`AssignmentRole` seat (`PilotResponse`/`AssignmentResponse`), the latter defaulting to `PILOT` when
`AssignmentRepositoryPort#roleFor` finds no link. `MeResponse` gained `capabilities[]`/`scopeKind`/
`mustChangePassword` — every existing field byte-identical. `PrincipalResolver` (`security/`) gained
`role()`/`authority()` — both implementations live in `vision-app`; this module never imports
`org.springframework.security` to use them (carried forward as a Convention in MODULE.md).
`DemoPeople#seed`/`DemoScenario`'s private `assign`/`grant` helpers were threaded with an explicit
`UserId actor` parameter (the demo has no CREW story — every demo grant is the wide
`AssignmentRole#PILOT` seat, documented in place). Error field naming: every new
`ApiExceptionHandler` mapping this wave added (`KIOSK_NOT_PERMITTED`/`WEAK_PASSWORD`/
`ALREADY_INITIALIZED`/`AUTH_DISABLED`) uses the pre-existing `ErrorResponse(String error, String
message)` shape — the wire field is `error`, matching every mapping that predates this wave; nothing
introduced a `code` field.

`./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app test -DskipWeb` — vision-api
**961** tests (954 → 961, +7: 2 kiosk-login cases, 3 `UserAdminController` cases, 2
`AssignmentController` seat-enrichment cases), 0 failures. Also green in the same run:
`storage/persistence` **276** (274 → 276, +2, `AssignmentRepositoryTests`) and `station/vision-app`
**326** (unchanged test count — this wave's `vision-app` edits were mechanical call-site fixes for
widened application-service signatures, not new tests). Docker ran (not skipped — Testcontainers
started a real `postgres:16`, Flyway migrated through `V33`). Waves B4 (per-asset command
authority)/B5 (Spring Session JDBC)/B6 (migrate the ~34 `canManageOrg`/`canManage`/`canAdminister`
call sites + the VIEWER-precedence flip)/B0b (flip `vision.auth.enabled`'s default) were open at the
time — see `docs/plans/active/AUTH-ROLES-PLAN.md`.

## COMMAND-MAP-FLOW-PLAN wave B1 (D1 fix)

`AssetController#telemetry` (`GET /api/usages/{usageId}/telemetry`) was switched from reading
`TelemetryRepositoryPort#findByUsage` to `#findLatestByUsage` — the `/command` fleet map polls this
endpoint treating the last array element as "current position", and `findByUsage`'s earliest-first
window meant that element never changed again past `limit` samples (docs/plans/active/COMMAND-MAP-FLOW-PLAN.md
D1). Wire contract unchanged: same path, same `limit` param, same `TelemetrySampleResponse[]` shape,
same ascending order, same `200 []` on an unknown usage — only which window of the flight comes back
(this current behavior is captured in MODULE.md's endpoint table). `AssetController`'s constructor was
unchanged (still the same `TelemetryRepositoryPort` field, just a different method called on it).
`AssetControllerTest`'s existing telemetry-block tests were repointed to stub/verify
`findLatestByUsage` in place — no test methods added or removed here, since the port-level "latest
window" / "ascending order" / "empty case" proofs live in `storage/persistence`'s
`PostgresDockerIntegrationTest$TelemetryRepositoryTests` instead (271 → 274 tests there).
`./mvnw -B -pl station/vision-api test` — **954** tests, 0 failures, 0 errors (unchanged from before
this wave — every edit here was a rename of an existing stub/verify call plus one javadoc rewrite, not
a new test). Docker not needed for this module. Also green in the same session: `storage/persistence`
**274** and `station/vision-app` **326** (both docker-ran, not skipped). Nothing deferred;
`vision-events`/`DefaultReplayService` and `UsageTimelineController` untouched by design (they own the
earliest-first replay window, a different question — see plan §3.7/§5).

## FLY-CONTROL-UX-PLAN wave H1

Traced from a real report ("Control denied — the station never confirmed control"):
`docs/plans/active/fly-control-ux/R3-handshake-denial.md` proved this is a client-local 4s abandon
(`ManualControlClient`'s `ENGAGE_TIMEOUT_MS`) that fires only when *neither* an `engaged` nor a
`denied` frame arrives, and that `engage()`'s whole path (`DefaultManualControlService` →
`MavlinkManualControlSender` → `mavlink-core`'s `ManualControlService`) is local and ack-less — no
vehicle round-trip exists to be slow, so a firing timeout is always a station fault. `handleEngage`
gained a fourth, final `catch (RuntimeException e)` after the three documented types
(`AccessDeniedException`/`VehicleUnidentifiedException`/`IllegalStateException`) — it logs WARNING
with the full stack trace and still answers `denied` with the additive code `INTERNAL_ERROR` (this
wire behavior is captured in MODULE.md's `/ws/manual-control` section). This closed the one gap R3's
trace could not rule out (an uncaught type — e.g. a plain `NoSuchElementException` from an unknown
`assetId`, which `AssetService#details` throws and none of the three documented types cover) and
guaranteed `handleEngage` never returns without a reply on an open socket.

**Blocking audit (read-only, no bound added)**: every step under `engage()` — scope/readiness/
maintenance checks (DB-backed, no live link, per `DefaultManualControlService`'s own javadoc),
`MavlinkManualControlSender.engage` (a map read plus a non-blocking `ManualControlService(mavlink-core).engage`,
which only checks `PeerDirectory` and calls `TxScheduler.repeat` — itself a non-blocking
`ScheduledExecutorService.scheduleAtFixedRate`) — was traced and found to contain no wait, sleep, or
socket read that could approach 4s; no bound was added because none was needed. Also referenced its
own appended "Firmware note" (a read-only rover-sim/firmware finding, outside this repo's `vision-api`
scope: F4's learned-peer authority gate also silently drops `RC_CHANNELS_OVERRIDE` from a second
source, confirmed by reading the real sketch at `~/Arduino/ardupoilot-start/MavlinkUdpLink.cpp` outside
this repo — no firmware file touched). Neither `drone-link/mavlink` nor `contexts/vision-flight` were
touched this wave.

`ManualControlWebSocketHandlerTest` gained 3 new cases (14 → 17): unexpected-exception →
`INTERNAL_ERROR` denied frame + WARNING log with stack trace (and that the exception's own message
never reaches the client), a `NoSuchElementException` (unknown asset) instance of the same gap, and a
0ms-threshold smoke test proving the duration log actually escalates to WARNING.
`./mvnw -B -pl station/vision-api -am test` — **954** tests, 0 failures, 0 errors (951 → 954, +3, all
new; nothing else changed). Docker not needed. `drone-link/mavlink`/`contexts/vision-flight` gates not
run — this wave changed no file in either module.

## FLY-CONTROL-UX-PLAN wave BK1

New `RcThresholdsResponse(int neutralTolerancePercent)` `dto/` record — same one-field, no-validation
wire-record shape `BatteryThresholdsResponse` already establishes. `OpsThresholdsResponse` widened to
`OpsThresholdsResponse(BatteryThresholdsResponse battery, RcThresholdsResponse rc)` — a second
constructor parameter at the one record, not a new overload (CLAUDE.md rule 10); the only call site is
`vision-app`'s `OpsWiringConfiguration#opsThresholds`. `OpsThresholdsController` gained no new endpoint
and no new exception mapping — `GET /api/ops/thresholds` now serves `{"battery":{...},
"rc":{"neutralTolerancePercent":5}}` (this current shape is in MODULE.md's endpoint table). No new
`@OpenByDesign` decision needed — the existing "display config, any signed-in caller may read it"
reasoning already covered the RC tolerance. `OpsThresholdsControllerTest` gained no new test methods;
its 2 existing cases were widened with `jsonPath("$.rc.neutralTolerancePercent")` assertions alongside
the pre-existing `battery` ones.

`./mvnw -B -pl station/vision-api -am test` — **951** tests, 0 failures, 0 errors (0 net new test
*methods* from this wave — the 2 `OpsThresholdsControllerTest` cases were widened in place, not
duplicated; the module's total moved from the 949 documented at BK4 to 951 from other concurrently-landed
waves on this shared branch, not from this one). Docker not needed for this module. Also green in the
same session: `station/vision-app` **326**. Nothing deferred.

## ASSET-FLOWS wave BK3 (D6/S3 backend)

New `OpsThresholdsController` (1 handler, `GET /api/ops/thresholds`, `@OpenByDesign` — display
config, not fleet or per-user data, so any signed-in caller may read it) + 2 new `dto/` records,
`BatteryThresholdsResponse(int warningPercent, int criticalPercent)` nested inside
`OpsThresholdsResponse(BatteryThresholdsResponse battery)`, wire shape frozen exactly per
docs/plans/active/ASSET-FLOWS-PLAN.md §2: `{"battery":{"warningPercent":25,"criticalPercent":10}}`.
Followed the `CvTrackersController`/`CvModelsController` precedent (a plain config-backed DTO bean
built once in `vision-app`'s wiring and injected into a controller that does nothing but return it)
rather than the `OnboardingProperties` bridge-properties pattern (`support/`) — there is exactly one
caller and no per-request branching, so a second bridge type would only add indirection
(`java-clean-code` §1: an interface/bridge needs to earn its place — carried forward as a Convention
in MODULE.md). The controller threw nothing, so `ApiExceptionHandler` gained no new mapping. `station/
vision-app`'s wiring was `OpsWiringConfiguration#opsThresholds(VisionOpsProperties)` — see that
module's own MODULE.md entry for the properties record and its note on
`ApplicationServiceWiring#batteryMonitor` (BK2, this same cycle). New `OpsThresholdsControllerTest`
(2 cases, MockMvc `standaloneSetup`, mirrors `CvTrackersControllerTest`): asserts the exact frozen
JSON shape, and that the controller reflects whatever `OpsThresholdsResponse` it was built from.

`./mvnw -B -pl station/vision-api -am test` — **944** tests, 0 failures (net +2 over BK6's own 944
baseline is misleading by coincidence — this wave's 2 new `OpsThresholdsControllerTest` cases were
already counted inside BK6's reported 944 since both waves' changes were concurrently present in this
shared working tree at either wave's gate time). Also independently verified green inside the full
`-am` reactor build gating BK3's own `station/vision-app` run. Docker not needed for this module.
Nothing deferred on the vision-api side.

## ASSET-FLOWS wave BK4 (D1p)

`AssetSessionController#engage` now passes `CurrentUser#userId()` through to
`UsageTracker#engage(AssetId, UserId)` (widened, CLAUDE.md rule 10 — every call site updated, no new
overload); `AssetUsageResponse`/`UsageSummaryResponse` each gained a `pilotId` field (raw UUID
string, `null`/omitted when unknown — this is the current shape captured in MODULE.md's endpoint
table/DTO conventions). No new Flyway migration (`storage/persistence`'s `V28` already carried the
column schema-only). `./mvnw -B -pl station/vision-api -am test -DskipWeb` — **949** tests, 0 failures
(+5 over BK3's 944: `AssetSessionControllerTest#engagePassesTheCurrentUsersIdThroughToUsageTrackerEngage`
plus new/strengthened pilot assertions in `AssetUsageResponseTest`/`UsageTimelineControllerTest`).
Docker not needed for this module.

## ASSET-FLOWS wave BK6 (A3)

`DiscoveryInboxController#list` now returns the `DiscoveryInboxResponse` envelope (`candidates` +
`sources`) instead of a bare `DiscoveryCandidateResponse[]` — see MODULE.md's DTO conventions for the
full wire-contract writeup and the reason it is an intentional breaking change. Two new `dto/` records
(`DiscoveryInboxResponse`, `DiscoverySourceResponse`); the controller's constructor gained a third
param, `DiscoveryService` (`vision-warehouse`'s `application.discovery` package). **At the time of this
wave, `vision-web`'s frontend still expected the old bare-array shape; fixing that was wave WB2's job,
out of this module's scope** (status note, presumably resolved by now — not reverified). 1 new test,
`DiscoveryInboxControllerTest#listCarriesSourceHealthAlongsideTheCandidateList`; every pre-existing
`list` test's JSON-path assertions moved from `$[...]` to `$.candidates[...]`.
`./mvnw -B -pl contexts/vision-warehouse -am install -DskipTests` then `./mvnw -B -pl
station/vision-api -o test` (offline, no `-am` — a concurrent agent's in-progress, uncommitted
`contexts/vision-flight` edit was mid-break on this shared branch at the time; resolving `vision-flight`
from its last-known-good installed jar instead of rebuilding its currently-broken source avoided
blocking on unrelated work, per CLAUDE.md's "never run reactor-wide builds while another agent's
task holds modules red") — `station/vision-api` **944** tests, 0 failures (the exact delta from 941
is not attributable to this wave alone: `git status` showed a sibling agent's concurrent, unrelated
`OpsThresholdsController`/`BatteryThresholdsResponse` additions already present in this shared
working tree).

## ZERO-CONFIG-ONBOARDING wave Z2c

New `DiscoveryInboxController` (3 handlers: `list`, `register`, `dismiss` — see MODULE.md's endpoint
table, DTO conventions, and Conventions for the auth split and the deliberate poll-only-not-SSE
decision this wave made) + 3 new `dto/` records (`DiscoveryCandidateResponse`/
`RegisterDiscoveryCandidateRequest`/`RegisterDiscoveryCandidateResponse`). Also fixed a pre-existing
compile break in `AfterActionAssemblerTest`'s `FakeAssetService` (missing `findDuplicateDevice`
override, added trivially returning `Optional.empty()`) found while wiring this wave's own test —
unrelated to discovery, but blocking the module's test compile either way.
`./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app test -DskipWeb` (after `-am
install -Dmaven.test.skip=true` on the upstream context modules, then a separate `-am install
-DskipTests` on `drone-link/mavlink-core,drone-link/mavlink` specifically) — `station/vision-api`
**941** (+9 over 932: `DiscoveryInboxControllerTest`'s 9 cases), `storage/persistence` **267** (+7),
`station/vision-app` **293** (+15) — all green, Docker ran for real, default-config bar held
throughout.

## CV-SETTINGS wave W5 (2026-08-30, uncommitted)

New `CvProfileController` (8 handlers: profile CRUD, `PUT`/`DELETE /api/cv/bindings`,
`GET /api/cv/profiles/effective`, `GET /api/cv/coverage`) and 11 new `dto/` records backing it
(`CvProfileResponse`/`CvProfileTrackingResponse`/`CvProfileEventRuleResponse`/`CvProfilesResponse`/
`CvProfileRequest`/`CvProfileBindingResponse`/`CvProfileBindingRequest`/`EffectiveCvProfileResponse`/
`CvCoverageRowResponse`/`CvCoverageResponse`) plus `TrainingRunResponse`/`TrainingRunsResponse` — the
`CvProfile*` shapes from this wave were later substantially rewritten by wave W7.3 (see that entry
above); `TrainingRunResponse`/`TrainingRunsResponse` are unaffected and still current. Also: `GET
/api/cv/models` widened to serve the registry's live roster when on; `GET /api/cv/registry/models`
deleted (folded into the widened `/api/cv/models`); `POST /api/cv/registry/rollback` added;
`TrainingJobController` gained `GET /api/cv/training/runs` (+`/{runId}`) reading
`TrainingJobService`'s newly-persisted run history, resolving `datasetName` via a new
`DatasetRepositoryPort` collaborator injected directly into the controller (the same "controllers
call a driving-port service, driven ports only read-only" precedent `DatasetController`'s own javadoc
already documents — carried forward into MODULE.md's Conventions). Full write scope was
`station/vision-api`/`station/vision-app` only — `contexts/vision-perception`/`contexts/vision-learning`/
`contexts/vision-warehouse`/`storage/persistence` were read-only for this wave.

Two deliberate DTO/wire deviations, both informational, still current: `TrainingRunResponse` drops
`TrainingRunRecord#message` entirely (`models.ts`'s `TrainingRun` has no such field);
`TrainingRunResponse#loss`/`#map50` are boxed `Double`s that are always populated from the domain's
primitive `double` fields, never actually `null`, even though `models.ts` declares `number | null` —
the domain has no way to represent "no progress reported yet" distinctly from a genuine `0.0` (carried
forward as a Gotcha in MODULE.md).

`./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app test -DskipWeb` (run as three
separate synchronous foreground commands after an `-am` install) — `storage/persistence` **260**
(unchanged, read-only this wave; docker ran, not skipped), `station/vision-api` **932** (+31 from 901:
`CvProfileControllerTest` ~20 new cases + `TrainingJobControllerTest`'s 9 new `runs`/`run` cases, net
of the deleted `ModelRegistryControllerTest#models` case and its removed `GET /api/cv/registry/models`
coverage), `station/vision-app` **278** — all green, default-config bar held throughout.

## WAREHOUSE-UX wave W8

New `GET /api/maintenance` (`AssetInventoryController#fleetMaintenance`) + `firmware`/
`totalFlightSeconds` joined onto `AssetController#list`/`#details`'s response rows via the new
`AssetRowFacts` collaborator (see MODULE.md's Conventions/API-surface for the current shape).
`AssetControllerTest` **62** (+4), `station/vision-api` **901** (+8) total, all green; ArchUnit
(`ArchitectureTest`/`ContextArchitectureTest`/`EndpointAuthorizationTest`) unaffected — `AssetRowFacts`
carries no stereotype annotation and depends on ports from two different contexts, which
`ContextArchitectureTest`'s `contextOf(...)` does not flag since it only recognizes
`com.drones.vision.<context>` packages, not `vision-api`/`vision-app`/adapter code; `fleetMaintenance`
reaches `currentUser.scope()` directly so needed no `TEMPORARY_UNSCOPED` ledger entry.

## docs/plans/active/FLEET-RADIO-PLAN.md R5 (2026-08-27)

New `AssetParameterController` (`POST /api/assets/{id}/parameters`, `dto.ParameterWriteRequest`/
`ParameterWriteResponse`) — the one caller of `vision-flight`'s already-built
`RemediationService#writeParameter` anywhere in `vision-api`; `RemediationOrchestrator`'s own
`PARAM_WRITE` refusal was untouched (`git diff` empty). Added no service/adapter/wiring —
`RemediationService`/`VehicleProfileService` are already wired unconditionally
(`OnboardingWiringConfiguration`), so this endpoint inherits the existing `VehicleConfigPort` flag-swap
for free: with `vision.onboarding.probe.enabled` at its default (`false`) every write 409s from the
same `NoopVehicleConfigPort`-caused refusal every other onboarding endpoint already gives, proven by
`AssetParameterFlagGatingTest` (`station/vision-app`) against a real, known-disarmed sim asset (not
merely an asset with no telemetry at all). `consent` is enforced by `dto.ParameterWriteRequest#requireConsent`
as a hard requirement for every write this controller dispatches — stricter than
`RemediationService#writeParameter`'s own `explicitConsent` parameter, which only actually gates Tier
B internally; Tier A (including `SYSID_THISMAV`/`MAV_SYSID`) would otherwise need no consent at all,
and this endpoint's whole reason to exist is carrying an explicit operator act (carried forward as a
Gotcha in MODULE.md). Spelling resolution (F0 — ArduPilot 4.7 renamed `SYSID_THISMAV` to `MAV_SYSID`,
and MAVLink has no "no such parameter" reply) is a private controller method, `resolveSpelling`,
consulting `VehicleProfileService#latestProfile` only for names with more than one known alias
(`ParameterAliases#spellingsOf`), falling back to the requested spelling on any lookup failure
(also carried forward as a Gotcha). `./mvnw -B -o -pl contexts/vision-flight,station/vision-api,station/vision-app test`
— `vision-flight` **351** (unchanged — no source touched), `vision-api` **874** (+13,
`AssetParameterControllerTest`), `vision-app` **271** (+1, `AssetParameterFlagGatingTest`), all green.

## docs/plans/active/FLEET-RADIO-PLAN.md R2 (2026-08-27)

`ws/ManualControlWebSocketHandler` gained one `catch (VehicleUnidentifiedException e)` clause ahead
of its existing `IllegalStateException` clause, mapping to a new, additive `denied` code
`VEHICLE_UNIDENTIFIED` (this current behavior is captured in MODULE.md's `/ws/manual-control`
section). `./mvnw -B -pl station/vision-api test` — **861 tests**, all green (2026-08-27; +2 from
before this wave: `ManualControlWebSocketHandlerTest`'s new cases asserting the code is distinct and
not message-sniffed into one of the other `denied` causes).

## docs/plans/active/CONTROLLER-SETUP-CONTEXT.md wave C15

The operator's transmitter, not just their bindings — reconciled as part of merging
`feat/controller-setup-c15` onto master. `GET`/`PUT /api/control-profiles[/{id}]` gained
`stickMode`/`forwardIsUp` on every row (`ControlProfileResponse`), and `PUT` accepts them as optional
fields on `UpdateControlProfileRequest` — absent means the platform default (`TransmitterView.DEFAULT`,
mode 2/forward-up), a `stickMode` outside 1-4 is a 400 (this current shape is captured in MODULE.md's
endpoint table). None of the pre-existing `ControlProfileController`-specific gotchas (catalogue
served not hardcoded, `AuxFunctionCatalog` seed list, ownership not `VisibilityScope`, domain-owned
validation, `maxRcChannel`) were changed by this wave.
