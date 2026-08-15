# TRACKING-V3 — band 1 (make the ladder reachable) + O3

Follow-up to `TRACKING-V3-PLAN.md` §3.1 and §6b-O3. Branch `feat/tracking-v3`, one commit per wave.

## 0. The problem this closes

cv-service ships a five-level capability ladder, ORU, and late-detection back-correction. The wire
carries eight new numbers for them. **The Java side sets none of the request fields and reads none of
the response fields** (verified by grep across all `*.java` / `*.ts`). Consequences today:

- `capability_level` is never sent, so cv-service always auto-probes *its own host*. There is no way
  to say "this stream runs L1 because its companion is a Pi."
- `capability_level_served` / `capability_level_reason` are never read, so a silent downgrade
  (**E12**: serve `min(requested, affordable)`) is invisible to the operator.
- `detection_lag_millis` — the number `CV-RATE-BUDGET` §1 budgets at < 50 ms — is measured and thrown away.

## 1. What is actually on the wire (and what is not)

**Settable request fields, currently unset:** `TrackingConfig.capability_level` (11),
`TrackingConfig.reupdate_max_gap_millis` (14).

**Readable response fields, currently unread:** `Detection.reupdated` (14),
`DetectionResponse.reupdate_millis` (22), `reupdated_tracks` (23), `detection_lag_millis` (24),
`capability_level_served` (25), `capability_level_reason` (26).

**NOT available — do not attempt:** `coast_millis` / `motion_confidence` are *reserved* field numbers
(Detection 12–13) with no Python emitter. Plan §3.1's "dashed box fades by `motion_confidence`" is
therefore **blocked on a later wave**, not part of this work.

**Proto rule (D5) still holds: no proto edit at all in this work.** Every field already exists.

## 2. Frozen domain contract

Waves J2/J3/J4 are written against this; J1 delivers exactly it, no more.

### `TrackingConfig` — two components, inserted before the nullable `lock`

```
TrackingConfig(mode, engineId, verifyEveryMillis, followFps, redetectIouPercent,
               maxAgeFrames, minHits, capabilityLevel, reupdateMaxGapMillis, lock)
```

| component | type | range | meaning |
|---|---|---|---|
| `capabilityLevel` | `int` | `[0,5]` | **a ceiling, not a demand** (E12). `0` = auto-probe |
| `reupdateMaxGapMillis` | `int` | `>= 0` | longest gap ORU may reconstruct. `0` = server default |

Both default to `0` in `off()` and `defaults()` — i.e. today's behaviour exactly.

### `TrackingCapability` — NEW record, what the server actually served

```
TrackingCapability(int levelServed, String reason)
```
`levelServed` in `[1,5]`; `reason` never null, `""` = served as requested. This record exists at all
only so a downgrade is a *thing* the UI can render, not two loose scalars.

### `TrackingTelemetry` — four components appended, `capability` last and nullable

```
TrackingTelemetry(detectorRan, reason, trackerLatency, engineId, lockedTrackId,
                  detectionLag, reupdateLatency, reupdatedTracks, capability)
```

| component | type | meaning |
|---|---|---|
| `detectionLag` | `Duration` | measured capture→association lag; `ZERO` = unknown. Never negative |
| `reupdateLatency` | `Duration` | ORU cost this frame; `ZERO` = none ran. Never negative |
| `reupdatedTracks` | `int` | tracks backfilled by ORU this frame; `>= 0` |
| `capability` | `TrackingCapability` | `null` = server reported no level (a pre-V3 cv-service) |

### `TrackRef` — one component appended

`boolean reupdated` — this track's gap was reconstructed by ORU on this frame.

## 3. Waves

| Wave | Agent | Scope | Depends on |
|---|---|---|---|
| **J1** | domain-modeler | `contexts/vision-perception/**` — the four records above, `TrackingConfigPatch`, tests, MODULE.md | — |
| **J2** | adapter-builder | `adapters/adapter-cv-grpc/**` — encode the two request fields, decode the six response fields, tests, MODULE.md | J1 |
| **J3** | spring-integrator | `vision-app/**` (`VisionTrackingProperties`, `TrackingWiring`, `application.properties`) + `vision-api/**` (`FrameTrackingResponse`, `TrackingConfigRequest`, track DTO), tests, MODULE.md | J1 |
| **J4** | web-ui | `vision-web/src/**` — models, level ceiling control, served-level + lag readout | J3 |
| **O3** | sonnet | `cv-service/tools/trackeval/**` + `cv-service/tests/trackeval/**` + `BASELINE.md` + `cv-service/MODULE.md` | — (parallel with all) |

J2 and J3 have disjoint file scopes and run in parallel after J1.

## 4. Invariants (in addition to the plan's P1–P9)

- **B1 — no proto edit.** Every field used here already exists; `DetectionFrameCodec`'s exhaustive
  Java switches must not need a new enum case.
- **B2 — zero behavioural change at defaults.** `capabilityLevel=0` / `reupdateMaxGapMillis=0` send
  the proto zero-value, which is byte-identical to not setting the field. A deployment that sets
  nothing behaves exactly as it does today, and the existing E2E tests prove it unchanged.
- **B3 — absent means absent.** A response with all six fields at their zero-value must decode to
  `capability == null` and zero lag — the shape an old cv-service returns. Same reasoning
  `toTrackingTelemetry` already applies to the five pre-V3 fields.
- **B4 — no magic numbers.** The deployment defaults live in `application.properties` under
  `vision.tracking.*`, never inline in a wiring class.
- **B5 — a level is a ceiling.** Nothing in the Java side may *raise* a served level, and the UI must
  render `levelServed` (what happened), never echo the request back as if it were the outcome.
