# adapter-cv-grpc — module history

Current contract: [`MODULE.md`](MODULE.md). Newest wave first.

## docs/plans/active/CV-ORCHESTRATION-PLAN.md — wave W5b.2

2026-09-13, trace replay (plan §8, decision E23). `toFrameLedger` gained a decode of the wire's
`TracedDetection` list (proto field 13) and `frame_width`/`frame_height` (fields 14-15, added this
wave in `cv/vision-proto`) onto the domain `FrameLedger`'s three new components via a new
`toDetectorBox` — see MODULE.md's "Frame ledger decode" for the mapping and its "0/empty means not
carried" contract. No new failure mode: a malformed traced detection is caught by the same
whole-ledger `try/catch` the rest of `toFrameLedger` already used, not a per-item salvage.
`DetectionFrameCodecTest` gained assertions that an untraced-shaped wire ledger decodes to
`List.of()`/`0`/`0`, plus a new `tracedDetectionsRoundTripAsTheDomainsDetectorBoxes` test with two
`TracedDetection`s and a non-zero frame size. Scoped build
(`./mvnw -B -pl cv/vision-proto,contexts/vision-perception,cv/grpc,station/vision-api -am test
-DskipWeb`): **adapter-cv-grpc 195/0/0**, all green (vision-proto 5/0/0, vision-perception 839/0/0,
vision-api 1121/0/0 — see `contexts/vision-perception/MODULE.md`'s own W5b.2 entry for the
domain-record half: new `DetectorBox`, `FrameLedger` widened 12→15 components, all six
`new FrameLedger(` call sites updated).

## docs/plans/active/CV-ORCHESTRATION-PLAN.md — wave W2.5

2026-09-12 (plan §4.4/§4.9). `DetectionFrameCodec#decode` gained the first decode of
`DetectionResponse.ledger` (field 28) into `DetectionResult#ledger()`, via the new
`toFrameLedger`/`toLedgerEntry`/`toObjectEvidence`/`toLedgerOutcome` — this module's own half of
closing the "not wired to any production producer yet" gap `contexts/vision-perception/MODULE.md`
used to carry for `DetectionResult#ledger()`. New `GrpcCvInspectClient` (`Inference/Inspect`
blocking-stub wrapper, §4.9) and `CvStatusProvider`'s constructor widened from 1-arg to 2-arg
(`Supplier<GrpcCvInspectClient>` added) to fold capacity facts into `GET /api/system/status`'s cv row
— see both classes' own bullets in MODULE.md. `./mvnw -B -pl cv/grpc test` — green (this wave's docs
pass did not re-run the full count; the last-measured figure came from the
`station/vision-app -am test` run that transitively covers this module, see
`contexts/vision-perception/MODULE.md`'s W2.7 status entry). No proto/`cv-service` change — both new
wire fields (`Inspect` RPC, `FrameLedger`/`ledger` field) already existed on the wire from earlier
work; this wave only added the Java-side reader for each.

## docs/plans/active/CV-ORCHESTRATION-PLAN.md — wave W1 step 4 ("wire mirror")

2026-09-12. `DetectionFrameCodec#decode` gained a mapping of `DetectionResponse.objects` (field 27)
onto the new `DetectionResult.objects()` via `toObjectStates` (see MODULE.md's "The per-identity
mirror") — every group (`identity`/`kinematics`/`belief`/`provenance`/`memory`/`lock`/`timing`)
decodes through `hasX()` presence; a malformed or unspecified-enum object is dropped and logged
without touching the rest of `objects[]` or any of `detections[]`. `DetectionResponse.ledger` (field
28) was deliberately left undecoded this wave (no domain `FrameLedger` type existed yet — W2.5's
job, above). `PipelineConfig.trace()` was encoded onto the wire in both
`DetectionFrameCodec#encode` (`FrameRequest.trace`) and `PulledDetectionSession#controlBuilder`
(`PullControl.trace`) for the first time — `false` on every call site at the time, restated every
request/control like the rest of each message's desired state; `TraceDemandPort`/`PipelineTrace`
(station/vision-api) is what later made it actually flip per-stream (see MODULE.md's "Frame ledger
decode" for the current state). One addition beyond the plan's four numbered work items:
`GrpcCvSettings` gained a `withResponseTimeout` wither (same copy-with-one-field-changed convention
as its five siblings) so a new mismatched-stream-id test could shorten the 2s production default
rather than waiting it out — no production call site uses it. `DetectionStreamSession#onResponse` and
`PulledDetectionSession#onResponse` started checking `DetectionResponse.stream_id` against their own
session's id before doing anything else with a response (R4 surprise 2 — see MODULE.md's
"`DetectionResponse.stream_id` checking"); a mismatch is dropped and logged, an empty wire value (an
older peer that never set the field) is accepted, never treated as a mismatch.

Scoped build: `./mvnw -B -pl cv/vision-proto,contexts/vision-perception,cv/grpc -am -DskipWeb test`
— **187 tests, all green** (+15 over this wave's starting 172): 9 in `DetectionFrameCodecTest`
(every group round-tripping with distinct values including a dormant object, absent-group-decodes-null,
unspecified-lifecycle-drops-the-object, unspecified-evidence-source-drops-the-object, an id-zero
object dropped by its own compact ctor, `objects[]` presence never perturbing `detections[]` decode
byte-for-byte, a missing `objects` field decoding to an empty not-null list, `trace` false-by-default
and true-when-carried on `FrameRequest`), 2 in `GrpcDetectionPortTest` (empty `stream_id` accepted,
mismatched `stream_id` dropped and the pending future left to time out rather than complete), 4 in
`GrpcPulledDetectionPortTest` (`trace` false-by-default and true-when-carried on `PullControl`, empty
`stream_id` accepted, mismatched `stream_id` dropped without disturbing the session). Files touched:
`DetectionFrameCodec.java`, `PulledDetectionSession.java`, `DetectionStreamSession.java`,
`GrpcCvSettings.java` (`src/main/java/.../cvgrpc/`) and `DetectionFrameCodecTest.java`,
`GrpcDetectionPortTest.java`, `GrpcPulledDetectionPortTest.java` (`src/test/java/.../cvgrpc/`) —
blast radius confined to this module; no call site outside `cv/grpc` needed a change
(`DetectionResult.objects()` and `PipelineConfig.trace()` were already added, non-breaking, by the
context work this wave's step 4 built on).

## docs/plans/active/TRACK-FOLLOW-PLAN.md — wave W1

2026-09-05, §3.1, decision D11. `toTrackRef` gained a decode of wire `Detection.identity_confidence`/
`dormant_millis` (fields 10-11) onto `TrackRef`'s two new trailing components (see MODULE.md's
"Tracking wire mapping") — the proto already carried these fields from an earlier L4 wave, and the
generated `Detection.getIdentityConfidence()`/`getDormantMillis()` accessors already existed; this
wave only wired the codec to read them. No proto edit, no new enum value, no new port.
`./mvnw -B -pl cv/grpc -am test` — **172 tests, all green** (+2:
`aRecoveredDetectionRoundTripsIdentityConfidenceAndDormantMillis`,
`aNonRecoveredDetectionDecodesIdentityConfidenceAndDormantMillisAsZero`). Blast radius: `TrackRef`'s
canonical constructor grew from 7 to 9 args (`contexts/vision-perception`, see that module's
MODULE.md); this wave's own `DetectionFrameCodec.java` call site and one `StreamControllerTest` call
site outside this module were updated to match.
